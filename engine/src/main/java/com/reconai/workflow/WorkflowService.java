package com.reconai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconai.breaks.BreakRepository;
import com.reconai.breaks.ReconBreak;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class WorkflowService {

    // Valid transitions: key = current status, value = allowed target statuses
    static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
        "OPEN",                Set.of("INVESTIGATING", "RESOLUTION_PROPOSED"),
        "INVESTIGATING",       Set.of("RESOLUTION_PROPOSED", "ESCALATED"),
        "RESOLUTION_PROPOSED", Set.of("RESOLVED", "OPEN"),
        "ESCALATED",           Set.of("INVESTIGATING", "RESOLVED"),
        "RESOLVED",            Set.of()
    );

    static boolean isValidTransition(String from, String to) {
        return VALID_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    private static final String INSERT_AUDIT = """
        INSERT INTO audit_event (break_id, actor, action, payload) VALUES (?, ?, ?, ?::jsonb)
        """;

    private static final String INSERT_VERDICT = """
        INSERT INTO agent_verdict (break_id, root_cause_code, confidence, explanation, suggested_action)
        VALUES (?, ?, ?, ?, ?)
        """;

    private static final String CTX_TXNS = """
        SELECT c.id, c.side, c.external_ref, c.amount, c.currency, c.counterparty,
               c.value_date, c.settlement_date, c.direction, c.status
        FROM canonical_txn c
        JOIN break_txn bt ON bt.txn_id = c.id
        WHERE bt.break_id = ?
        ORDER BY c.id
        """;

    private static final String CTX_NEAR_MISS = """
        SELECT c.id, c.side, c.external_ref, c.amount, c.currency, c.counterparty,
               c.value_date, c.status
        FROM canonical_txn c
        WHERE c.batch_id = ?
          AND c.side = ?
          AND c.counterparty = ?
          AND c.currency = ?
          AND ABS(c.value_date - CAST(? AS DATE)) <= 5
        ORDER BY ABS(c.amount - CAST(? AS NUMERIC)) ASC
        LIMIT 5
        """;

    private static final String CTX_COUNTERPARTY_HIST = """
        SELECT c.id, c.side, c.external_ref, c.amount, c.currency,
               c.value_date, c.status, c.match_id
        FROM canonical_txn c
        WHERE c.batch_id = ?
          AND c.counterparty = ?
          AND c.id != ?
        ORDER BY c.value_date DESC
        LIMIT 10
        """;

    private static final String CTX_DUP_SCAN = """
        SELECT c.id, c.side, c.external_ref, c.amount, c.currency, c.value_date, c.status
        FROM canonical_txn c
        WHERE c.batch_id = ?
          AND c.external_ref = ?
          AND c.amount = CAST(? AS NUMERIC)
          AND c.currency = ?
          AND c.id != ?
        ORDER BY c.id
        """;

    private static final String CTX_AUDIT_TRAIL = """
        SELECT id, actor, action, payload::text AS payload, created_at
        FROM audit_event
        WHERE break_id = ?
        ORDER BY created_at ASC
        """;

    private static final String CTX_VERDICTS = """
        SELECT id, root_cause_code, confidence, explanation, suggested_action, created_at
        FROM agent_verdict
        WHERE break_id = ?
        ORDER BY created_at DESC
        """;

    private final JdbcTemplate jdbc;
    private final BreakRepository breaks;
    private final ObjectMapper json;

    public WorkflowService(JdbcTemplate jdbc, BreakRepository breaks, ObjectMapper json) {
        this.jdbc   = jdbc;
        this.breaks = breaks;
        this.json   = json;
    }

    // ── Transition ────────────────────────────────────────────────────────

    @Transactional
    public ReconBreak transition(long breakId, String targetStatus, String actor, String note) {
        ReconBreak brk = breaks.findById(breakId)
            .orElseThrow(() -> new EntityNotFoundException("Break not found: " + breakId));

        String current = brk.getStatus();
        if (!isValidTransition(current, targetStatus)) {
            throw new IllegalStateException(
                "Invalid transition from " + current + " to " + targetStatus + " for break " + breakId);
        }

        brk.setStatus(targetStatus);
        if ("RESOLVED".equals(targetStatus)) {
            brk.setResolvedAt(Instant.now());
        }
        breaks.save(brk);

        String payload = buildPayload("from", current, "to", targetStatus, "note", note);
        jdbc.update(INSERT_AUDIT, breakId, actor, "STATUS_TRANSITION", payload);

        return brk;
    }

    // ── Verdict ───────────────────────────────────────────────────────────

    @Transactional
    public void postVerdict(long breakId, VerdictRequest req) {
        if (!breaks.existsById(breakId)) {
            throw new EntityNotFoundException("Break not found: " + breakId);
        }
        jdbc.update(INSERT_VERDICT, breakId,
            req.rootCauseCode(), req.confidence(), req.explanation(), req.suggestedAction());

        String payload = buildPayload(
            "rootCauseCode", req.rootCauseCode(),
            "confidence",    String.valueOf(req.confidence()),
            "suggestedAction", req.suggestedAction()
        );
        jdbc.update(INSERT_AUDIT, breakId, "AGENT", "VERDICT_POSTED", payload);

        // Auto-advance to RESOLUTION_PROPOSED if still in a non-terminal state
        ReconBreak brk = breaks.findById(breakId).orElseThrow();
        String status = brk.getStatus();
        if (isValidTransition(status, "RESOLUTION_PROPOSED")) {
            brk.setStatus("RESOLUTION_PROPOSED");
            breaks.save(brk);
            String transPayload = buildPayload("from", status, "to", "RESOLUTION_PROPOSED", "trigger", "VERDICT");
            jdbc.update(INSERT_AUDIT, breakId, "SYSTEM", "STATUS_TRANSITION", transPayload);
        }
    }

    // ── Context ───────────────────────────────────────────────────────────

    public Map<String, Object> getContext(long breakId) {
        ReconBreak brk = breaks.findById(breakId)
            .orElseThrow(() -> new EntityNotFoundException("Break not found: " + breakId));

        List<Map<String, Object>> txns = jdbc.queryForList(CTX_TXNS, breakId);
        List<Map<String, Object>> auditTrail   = jdbc.queryForList(CTX_AUDIT_TRAIL, breakId);
        List<Map<String, Object>> agentVerdicts = jdbc.queryForList(CTX_VERDICTS, breakId);

        List<Map<String, Object>> nearMiss       = new ArrayList<>();
        List<Map<String, Object>> cpHistory      = new ArrayList<>();
        List<Map<String, Object>> dupScan        = new ArrayList<>();

        if (!txns.isEmpty()) {
            Map<String, Object> txn = txns.get(0);
            long batchId      = brk.getBatchId();
            String side       = String.valueOf(txn.get("side"));
            String otherSide  = "INTERNAL".equals(side) ? "EXTERNAL" : "INTERNAL";
            String cp         = String.valueOf(txn.get("counterparty"));
            String currency   = String.valueOf(txn.get("currency"));
            Object valueDate  = txn.get("value_date");
            Object amount     = txn.get("amount");
            long txnId        = toLong(txn.get("id"));

            nearMiss   = jdbc.queryForList(CTX_NEAR_MISS, batchId, otherSide, cp, currency, valueDate, amount);
            cpHistory  = jdbc.queryForList(CTX_COUNTERPARTY_HIST, batchId, cp, txnId);

            // Dup scan only meaningful for DUP_EXTERNAL or MISSING_INTERNAL
            String extRef = String.valueOf(txn.get("external_ref"));
            dupScan = jdbc.queryForList(CTX_DUP_SCAN, batchId, extRef, amount, currency, txnId);
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("break",               breakSummary(brk));
        ctx.put("transactions",        txns);
        ctx.put("nearMissCandidates",  nearMiss);
        ctx.put("counterpartyHistory", cpHistory);
        ctx.put("duplicateScan",       dupScan);
        ctx.put("auditTrail",          auditTrail);
        ctx.put("agentVerdicts",       agentVerdicts);
        return ctx;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> breakSummary(ReconBreak brk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  brk.getId());
        m.put("batchId",             brk.getBatchId());
        m.put("detectedType",        brk.getDetectedType());
        m.put("detectedConfidence",  brk.getDetectedConfidence());
        m.put("status",              brk.getStatus());
        m.put("createdAt",           brk.getCreatedAt());
        m.put("resolvedAt",          brk.getResolvedAt());
        m.put("resolutionCode",      brk.getResolutionCode());
        return m;
    }

    private String buildPayload(String... kvPairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            if (kvPairs[i + 1] != null) m.put(kvPairs[i], kvPairs[i + 1]);
        }
        try { return json.writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }

    private static long toLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }
}
