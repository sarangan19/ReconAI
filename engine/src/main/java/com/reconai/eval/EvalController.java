package com.reconai.eval;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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

    private static final String SUMMARY_SQL = """
        SELECT DISTINCT rb.id AS break_id,
               av.root_cause_code  AS predicted,
               gt.injected_code    AS actual
        FROM recon_break rb
        JOIN break_txn bt ON bt.break_id = rb.id
        JOIN canonical_txn ct ON ct.id = bt.txn_id
        JOIN ground_truth gt ON gt.batch_id = rb.batch_id
          AND gt.external_ref = ct.external_ref
        LEFT JOIN agent_verdict av ON av.break_id = rb.id
        WHERE rb.batch_id = ?
        ORDER BY rb.id
        """;

    private final JdbcTemplate jdbc;

    public EvalController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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

    /** Returns accuracy + per-type metrics + confusion matrix from DB verdicts. */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam long batchId) {
        List<Map<String, Object>> rows = jdbc.queryForList(SUMMARY_SQL, batchId);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of("batchId", batchId, "totalEvaluated", 0,
                    "message", "No ground-truth labels or agent verdicts found for this batch."));
        }

        int total = 0, correct = 0;
        Map<String, Integer> tp = new LinkedHashMap<>(), predCount = new LinkedHashMap<>(),
                actualCount = new LinkedHashMap<>();
        List<Map<String, Object>> actualRows = rows.stream()
                .filter(r -> r.get("predicted") != null && r.get("actual") != null)
                .toList();

        Set<String> codes = new TreeSet<>();
        for (var r : actualRows) {
            String pred = String.valueOf(r.get("predicted"));
            String act  = String.valueOf(r.get("actual"));
            codes.add(pred); codes.add(act);
            actualCount.merge(act, 1, Integer::sum);
            predCount.merge(pred, 1, Integer::sum);
            total++;
            if (pred.equals(act)) { correct++; tp.merge(act, 1, Integer::sum); }
        }

        // Per-type P/R/F1
        Map<String, Map<String, Object>> perType = new LinkedHashMap<>();
        for (String code : codes) {
            int tpVal = tp.getOrDefault(code, 0);
            int fp = predCount.getOrDefault(code, 0) - tpVal;
            int fn = actualCount.getOrDefault(code, 0) - tpVal;
            double prec = (tpVal + fp) > 0 ? (double) tpVal / (tpVal + fp) : 0.0;
            double rec  = (tpVal + fn) > 0 ? (double) tpVal / (tpVal + fn) : 0.0;
            double f1   = (prec + rec) > 0  ? 2 * prec * rec / (prec + rec) : 0.0;
            perType.put(code, Map.of("support", actualCount.getOrDefault(code, 0),
                    "precision", Math.round(prec * 1000.0) / 1000.0,
                    "recall",    Math.round(rec  * 1000.0) / 1000.0,
                    "f1",        Math.round(f1   * 1000.0) / 1000.0));
        }

        // Confusion matrix
        List<String> codeList = new ArrayList<>(codes);
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (String act : codeList) {
            Map<String, Integer> row = new LinkedHashMap<>();
            for (String pred : codeList) row.put(pred, 0);
            matrix.put(act, row);
        }
        for (var r : actualRows) {
            matrix.get(String.valueOf(r.get("actual")))
                  .merge(String.valueOf(r.get("predicted")), 1, Integer::sum);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("totalEvaluated", total);
        out.put("correct", correct);
        out.put("accuracy", total > 0 ? Math.round((double) correct / total * 10000.0) / 10000.0 : 0.0);
        out.put("perType", perType);
        out.put("confusionMatrix", matrix);
        out.put("labels", codeList);
        return ResponseEntity.ok(out);
    }
}
