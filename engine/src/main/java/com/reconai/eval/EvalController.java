package com.reconai.eval;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eval-harness-only endpoints. Engine runtime and agent runner MUST NOT call these.
 * Only agent/eval.py and benchmark scripts may use GET /api/eval/ground-truth.
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private static final String GROUND_TRUTH_SQL = """
        SELECT DISTINCT rb.id AS break_id, gt.injected_code
        FROM recon_break rb
        JOIN break_txn bt ON bt.break_id = rb.id
        JOIN canonical_txn ct ON ct.id = bt.txn_id
        JOIN ground_truth gt ON gt.batch_id = rb.batch_id
          AND gt.external_ref = ct.external_ref
        WHERE rb.batch_id = ?
        ORDER BY rb.id
        """;

    private final JdbcTemplate jdbc;

    public EvalController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns {break_id → injected_code} for every break that has a ground-truth label.
     * Breaks with no injected discrepancy (clean txns that still failed to match) are omitted.
     */
    @GetMapping("/ground-truth")
    public ResponseEntity<Map<Long, String>> groundTruth(@RequestParam long batchId) {
        List<Map<String, Object>> rows = jdbc.queryForList(GROUND_TRUTH_SQL, batchId);
        Map<Long, String> result = new LinkedHashMap<>();
        for (var row : rows) {
            Long breakId = ((Number) row.get("break_id")).longValue();
            result.put(breakId, String.valueOf(row.get("injected_code")));
        }
        return ResponseEntity.ok(result);
    }
}
