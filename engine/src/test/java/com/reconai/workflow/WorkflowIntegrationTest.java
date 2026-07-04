package com.reconai.workflow;

import com.reconai.breaks.BreakRepository;
import com.reconai.matching.MatchingService;
import com.reconai.recon.domain.Batch;
import com.reconai.recon.repository.BatchRepository;
import com.reconai.simulator.InjectionRates;
import com.reconai.simulator.SimulatorService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("integration")
class WorkflowIntegrationTest {

    @Autowired SimulatorService simulator;
    @Autowired MatchingService  matching;
    @Autowired WorkflowService  workflow;
    @Autowired BatchRepository  batches;
    @Autowired BreakRepository  breaks;
    @Autowired JdbcTemplate     jdbc;

    // ── State machine (real DB) ───────────────────────────────────────────

    @Test
    void transition_OpenToInvestigating_WritesAuditEvent() {
        long breakId = firstBreakId(200, 7L);

        workflow.transition(breakId, "INVESTIGATING", "USER", "starting review");

        var brk = breaks.findById(breakId).orElseThrow();
        assertThat(brk.getStatus()).isEqualTo("INVESTIGATING");

        List<Map<String, Object>> audit = jdbc.queryForList(
            "SELECT actor, action FROM audit_event WHERE break_id = ? ORDER BY created_at", breakId);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("actor")).isEqualTo("USER");
        assertThat(audit.get(0).get("action")).isEqualTo("STATUS_TRANSITION");
    }

    @Test
    void transition_FullHappyPath_ReachesResolved() {
        long breakId = firstBreakId(200, 8L);

        workflow.transition(breakId, "INVESTIGATING",       "USER",   "assigning");
        workflow.transition(breakId, "RESOLUTION_PROPOSED", "AGENT",  "analysis done");
        workflow.transition(breakId, "RESOLVED",            "USER",   "confirmed fix");

        var brk = breaks.findById(breakId).orElseThrow();
        assertThat(brk.getStatus()).isEqualTo("RESOLVED");
        assertThat(brk.getResolvedAt()).isNotNull();

        long auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE break_id = ?", Long.class, breakId);
        assertThat(auditCount).isEqualTo(3);
    }

    @Test
    void transition_EscalationPath_WorksCorrectly() {
        long breakId = firstBreakId(200, 9L);

        workflow.transition(breakId, "INVESTIGATING", "USER", "escalating");
        workflow.transition(breakId, "ESCALATED",     "USER", "needs L2");
        workflow.transition(breakId, "RESOLVED",      "USER", "resolved at L2");

        var brk = breaks.findById(breakId).orElseThrow();
        assertThat(brk.getStatus()).isEqualTo("RESOLVED");
    }

    @Test
    void transition_IllegalMove_ThrowsIllegalState() {
        long breakId = firstBreakId(200, 11L);

        // OPEN → RESOLVED is not allowed
        assertThatThrownBy(() -> workflow.transition(breakId, "RESOLVED", "USER", "skip"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OPEN")
            .hasMessageContaining("RESOLVED");
    }

    @Test
    void transition_AfterResolved_AllMovesBlocked() {
        long breakId = firstBreakId(200, 12L);
        workflow.transition(breakId, "INVESTIGATING",       "USER", "step1");
        workflow.transition(breakId, "RESOLUTION_PROPOSED", "USER", "step2");
        workflow.transition(breakId, "RESOLVED",            "USER", "step3");

        assertThatThrownBy(() -> workflow.transition(breakId, "OPEN", "USER", "reopen"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transition_UnknownBreak_ThrowsEntityNotFound() {
        assertThatThrownBy(() -> workflow.transition(Long.MAX_VALUE, "INVESTIGATING", "USER", null))
            .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Audit immutability (DB trigger) ──────────────────────────────────

    @Test
    void auditEvent_Update_RejectedByTrigger() {
        long breakId = firstBreakId(200, 13L);
        workflow.transition(breakId, "INVESTIGATING", "USER", "test");

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE audit_event SET actor = 'HACKED' WHERE break_id = ?", breakId))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    void auditEvent_Delete_RejectedByTrigger() {
        long breakId = firstBreakId(200, 14L);
        workflow.transition(breakId, "INVESTIGATING", "USER", "test");

        assertThatThrownBy(() -> jdbc.update(
            "DELETE FROM audit_event WHERE break_id = ?", breakId))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("immutable");
    }

    // ── Verdict ───────────────────────────────────────────────────────────

    @Test
    void postVerdict_StoresVerdictAndAuditEvent() {
        long breakId = firstBreakId(300, 15L);
        var req = new VerdictRequest(
            "AMT_FX_ROUNDING",
            new BigDecimal("0.9200"),
            "FX conversion difference of 0.3%",
            "APPROVE_TOLERANCE"
        );

        workflow.postVerdict(breakId, req);

        List<Map<String, Object>> verdicts = jdbc.queryForList(
            "SELECT root_cause_code, confidence FROM agent_verdict WHERE break_id = ?", breakId);
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).get("root_cause_code")).isEqualTo("AMT_FX_ROUNDING");

        long agentAudits = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE break_id = ? AND actor = 'AGENT'",
            Long.class, breakId);
        assertThat(agentAudits).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void postVerdict_AutoAdvancesToResolutionProposed() {
        long breakId = firstBreakId(200, 16L);
        var req = new VerdictRequest("MISSING_EXTERNAL", new BigDecimal("0.8500"),
            "Internal txn with no external match", "CREATE_EXTERNAL_ENTRY");

        workflow.postVerdict(breakId, req);

        var brk = breaks.findById(breakId).orElseThrow();
        assertThat(brk.getStatus()).isEqualTo("RESOLUTION_PROPOSED");
    }

    // ── Context endpoint ──────────────────────────────────────────────────

    @Test
    void getContext_ReturnsAllExpectedSections() {
        long breakId = firstBreakId(500, 17L);
        Map<String, Object> ctx = workflow.getContext(breakId);

        assertThat(ctx).containsKeys(
            "break", "transactions", "nearMissCandidates",
            "counterpartyHistory", "duplicateScan", "auditTrail", "agentVerdicts");

        @SuppressWarnings("unchecked")
        Map<String, Object> brkMap = (Map<String, Object>) ctx.get("break");
        assertThat(brkMap).containsKeys("id", "batchId", "detectedType", "status");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txns = (List<Map<String, Object>>) ctx.get("transactions");
        assertThat(txns).isNotEmpty();
    }

    @Test
    void getContext_AuditTrailGrowsWithTransitions() {
        long breakId = firstBreakId(300, 18L);
        workflow.transition(breakId, "INVESTIGATING",       "USER", "note1");
        workflow.transition(breakId, "RESOLUTION_PROPOSED", "USER", "note2");

        Map<String, Object> ctx = workflow.getContext(breakId);
        @SuppressWarnings("unchecked")
        List<?> trail = (List<?>) ctx.get("auditTrail");
        assertThat(trail).hasSize(2);
    }

    @Test
    void getContext_UnknownBreak_ThrowsEntityNotFound() {
        assertThatThrownBy(() -> workflow.getContext(Long.MAX_VALUE))
            .isInstanceOf(EntityNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private long firstBreakId(int n, long seed) {
        Batch batch = batches.save(new Batch("wf-test-" + System.nanoTime()));
        simulator.simulate(batch.getId(), n, seed, InjectionRates.defaults());
        matching.reconcile(batch.getId());
        return breaks.findByBatchId(batch.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1))
            .getContent()
            .get(0)
            .getId();
    }
}
