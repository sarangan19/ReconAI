package com.reconai.simulator;

import com.reconai.recon.domain.Batch;
import com.reconai.recon.domain.BatchStatus;
import com.reconai.recon.domain.TxnSide;
import com.reconai.recon.repository.BatchRepository;
import com.reconai.recon.repository.CanonicalTxnRepository;
import com.reconai.groundtruth.GroundTruthRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration")
class SimulatorIntegrationTest {

    @Autowired SimulatorService simulator;
    @Autowired BatchRepository batches;
    @Autowired CanonicalTxnRepository ctxnRepo;
    @Autowired GroundTruthRepository gtRepo;

    @Test
    void simulate_1000Txns_PopulatesCanonicalAndGroundTruth() {
        Batch batch = batches.save(new Batch("integration-test-" + System.currentTimeMillis()));
        InjectionRates rates = InjectionRates.defaults();

        SimulatorResult result = simulator.simulate(batch.getId(), 1000, 42L, rates);

        assertThat(result.internalCount()).isPositive();
        assertThat(result.externalCount()).isPositive();
        assertThat(result.groundTruthCount()).isPositive();
        assertThat(result.batchId()).isEqualTo(batch.getId());

        long dbInternal = ctxnRepo.countByBatchIdAndSide(batch.getId(), TxnSide.INTERNAL);
        long dbExternal = ctxnRepo.countByBatchIdAndSide(batch.getId(), TxnSide.EXTERNAL);
        long dbGt       = gtRepo.countByBatchId(batch.getId());

        assertThat(dbInternal).isEqualTo(result.internalCount());
        assertThat(dbExternal).isEqualTo(result.externalCount());
        assertThat(dbGt).isEqualTo(result.groundTruthCount());

        // Batch status updated to SIMULATED
        var saved = batches.findById(batch.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BatchStatus.SIMULATED);
    }

    @Test
    void simulate_SameSeed_ProducesIdenticalCounts() {
        Batch b1 = batches.save(new Batch("seed42-run1"));
        Batch b2 = batches.save(new Batch("seed42-run2"));

        SimulatorResult r1 = simulator.simulate(b1.getId(), 500, 42L, InjectionRates.defaults());
        SimulatorResult r2 = simulator.simulate(b2.getId(), 500, 42L, InjectionRates.defaults());

        assertThat(r1.internalCount()).isEqualTo(r2.internalCount());
        assertThat(r1.externalCount()).isEqualTo(r2.externalCount());
        assertThat(r1.injectionCounts()).isEqualTo(r2.injectionCounts());
    }

    @Test
    void simulate_AlreadySimulated_ThrowsConflict() {
        Batch batch = batches.save(new Batch("conflict-test"));
        simulator.simulate(batch.getId(), 100, 1L, InjectionRates.defaults());

        assertThatThrownBy(() -> simulator.simulate(batch.getId(), 100, 1L, InjectionRates.defaults()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already simulated");
    }
}
