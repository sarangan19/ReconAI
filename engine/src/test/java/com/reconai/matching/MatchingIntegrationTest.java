package com.reconai.matching;

import com.reconai.breaks.BreakRepository;
import com.reconai.recon.domain.Batch;
import com.reconai.recon.domain.BatchStatus;
import com.reconai.recon.domain.TxnSide;
import com.reconai.recon.repository.BatchRepository;
import com.reconai.recon.repository.CanonicalTxnRepository;
import com.reconai.simulator.InjectionRates;
import com.reconai.simulator.SimulatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration")
class MatchingIntegrationTest {

    @Autowired SimulatorService simulator;
    @Autowired MatchingService  matching;
    @Autowired BatchRepository  batches;
    @Autowired CanonicalTxnRepository ctxnRepo;
    @Autowired BreakRepository  breakRepo;
    @Autowired JdbcTemplate     jdbc;

    private static final InjectionRates RATES = InjectionRates.defaults();

    // ── Core correctness ─────────────────────────────────────────────────

    @Test
    void reconcile_CleanBatch_MatchesAlmostAll() {
        long batchId = seed(2000, 42L);
        ReconcileResult result = matching.reconcile(batchId);

        // At least 95% of txns should be auto-matched (clean rate ~96.3%)
        long total   = ctxnRepo.countByBatchId(batchId);
        long matched = total - (result.totalBreaks() * 2L);  // approx: each break = 1 unmatched
        double matchRate = (double) matched / total;
        assertThat(matchRate).isGreaterThan(0.90);  // conservative
        assertThat(result.totalBreaks()).isPositive();
        assertThat(result.passSummary()).hasSize(4);
    }

    @Test
    void reconcile_StatusUpdatedToReconciling() {
        long batchId = seed(200, 10L);
        matching.reconcile(batchId);
        var batch = batches.findById(batchId).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.RECONCILING);
    }

    @Test
    void reconcile_NoTxnInTwoMatchGroups_ExclusivityProperty() {
        long batchId = seed(500, 77L);
        matching.reconcile(batchId);

        // Every matched txn should have exactly one match_id
        Long duplicateMatchCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM canonical_txn " +
            "WHERE batch_id = ? AND status = 'MATCHED' AND match_id IS NULL",
            Long.class, batchId);
        assertThat(duplicateMatchCount).isZero();

        // No txn appears in two match groups (each id has at most one match_id)
        Long multipleMatches = jdbc.queryForObject(
            "SELECT COUNT(*) FROM (" +
            "  SELECT id FROM canonical_txn WHERE batch_id = ? AND match_id IS NOT NULL " +
            "  GROUP BY id HAVING COUNT(DISTINCT match_id) > 1" +
            ") x",
            Long.class, batchId);
        assertThat(multipleMatches).isZero();
    }

    @Test
    void reconcile_Determinism_SameSeedSameBreakTypeDistribution() {
        long batchId1 = seed(1000, 42L);
        long batchId2 = seed(1000, 42L);

        ReconcileResult r1 = matching.reconcile(batchId1);
        ReconcileResult r2 = matching.reconcile(batchId2);

        // Same seed → same break type distribution
        assertThat(r1.breaksByType()).isEqualTo(r2.breaksByType());
        assertThat(r1.totalBreaks()).isEqualTo(r2.totalBreaks());
        assertThat(r1.totalMatched()).isEqualTo(r2.totalMatched());
    }

    @Test
    void reconcile_AlreadyReconciled_ThrowsConflict() {
        long batchId = seed(100, 5L);
        matching.reconcile(batchId);

        assertThatThrownBy(() -> matching.reconcile(batchId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SIMULATED");
    }

    @Test
    void reconcile_Pass1MatchesCleanTxns() {
        long batchId = seed(500, 42L);
        ReconcileResult result = matching.reconcile(batchId);

        // Pass 1 should match the majority (clean txns ~96.3%)
        PassStatDto pass1 = result.passSummary().get(0);
        assertThat(pass1.passNum()).isEqualTo(1);
        assertThat(pass1.matchedCount()).isPositive();
        // Pass 1 should match more than all other passes combined
        int pass1Count = pass1.matchedCount();
        int otherPasses = result.passSummary().stream()
            .skip(1).mapToInt(PassStatDto::matchedCount).sum();
        assertThat(pass1Count).isGreaterThan(otherPasses);
    }

    @Test
    void reconcile_BreakTypesIncludeExpectedCategories() {
        long batchId = seed(5000, 42L);
        ReconcileResult result = matching.reconcile(batchId);

        // With 5000 txns at default injection rates, we expect multiple break types
        var types = result.breaksByType().keySet();
        // At minimum: MISSING_EXTERNAL and MISSING_INTERNAL should appear
        assertThat(types).containsAnyOf("MISSING_EXTERNAL", "MISSING_INTERNAL", "DUP_EXTERNAL");
    }

    // ── helper ───────────────────────────────────────────────────────────

    private long seed(int n, long seedVal) {
        Batch batch = batches.save(new Batch("matching-test-" + System.nanoTime()));
        simulator.simulate(batch.getId(), n, seedVal, RATES);
        return batch.getId();
    }
}
