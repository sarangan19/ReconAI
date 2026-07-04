package com.reconai.matching;

import com.reconai.recon.domain.BatchStatus;
import com.reconai.recon.repository.BatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    // ── SQL: Pass 1 — exact ──────────────────────────────────────────────
    private static final String PASS1_CANDIDATES = """
        SELECT i.id AS int_id, MIN(e.id) AS ext_id
        FROM canonical_txn i
        JOIN canonical_txn e ON
            e.batch_id = ? AND e.side = 'EXTERNAL' AND e.status = 'UNMATCHED'
            AND i.external_ref  = e.external_ref
            AND i.amount        = e.amount
            AND i.currency      = e.currency
            AND i.value_date    = e.value_date
        WHERE i.batch_id = ? AND i.side = 'INTERNAL' AND i.status = 'UNMATCHED'
        GROUP BY i.id
        """;

    // ── SQL: Pass 2 — tolerance ──────────────────────────────────────────
    private static final String PASS2_CANDIDATES = """
        SELECT i.id AS int_id, e.id AS ext_id,
               ABS(i.amount - e.amount)          AS amt_diff,
               ABS(i.value_date - e.value_date)  AS date_diff,
               CASE WHEN i.amount = e.amount THEN 'TOLERANCE_DATE'
                    ELSE 'TOLERANCE_AMT' END      AS match_type
        FROM canonical_txn i
        JOIN canonical_txn e ON
            e.batch_id = ? AND e.side = 'EXTERNAL' AND e.status = 'UNMATCHED'
            AND i.external_ref = e.external_ref
            AND i.currency     = e.currency
            AND ABS(i.amount - e.amount) <= GREATEST(i.amount * 0.005, 0.01)
            AND ABS(i.value_date - e.value_date) <= 3
            AND NOT (i.amount = e.amount AND i.value_date = e.value_date)
        WHERE i.batch_id = ? AND i.side = 'INTERNAL' AND i.status = 'UNMATCHED'
        ORDER BY i.id, amt_diff ASC, date_diff ASC
        """;

    // ── SQL: Pass 3 — fuzzy candidates (blocked by counterparty+currency) ─
    private static final String PASS3_CANDIDATES = """
        SELECT i.id AS int_id, i.external_ref AS int_ref,
               i.amount AS int_amt, (i.value_date - DATE '1970-01-01') AS int_vd,
               e.id AS ext_id, e.external_ref AS ext_ref,
               e.amount AS ext_amt, (e.value_date - DATE '1970-01-01') AS ext_vd
        FROM canonical_txn i
        JOIN canonical_txn e ON
            e.batch_id = ? AND e.side = 'EXTERNAL' AND e.status = 'UNMATCHED'
            AND i.counterparty  = e.counterparty
            AND i.currency      = e.currency
            AND ABS(i.value_date - e.value_date) <= 3
        WHERE i.batch_id = ? AND i.side = 'INTERNAL' AND i.status = 'UNMATCHED'
        """;

    // ── SQL: Pass 4 — split candidates ───────────────────────────────────
    private static final String PASS4_INTERNAL = """
        SELECT i.id AS int_id, i.amount AS int_amt, i.counterparty,
               i.currency, (i.value_date - DATE '1970-01-01') AS int_vd
        FROM canonical_txn i
        WHERE i.batch_id = ? AND i.side = 'INTERNAL' AND i.status = 'UNMATCHED'
        ORDER BY i.counterparty, i.currency, i.value_date
        """;

    private static final String PASS4_EXTERNAL = """
        SELECT e.id AS ext_id, e.amount AS ext_amt, e.counterparty,
               e.currency, (e.value_date - DATE '1970-01-01') AS ext_vd
        FROM canonical_txn e
        WHERE e.batch_id = ? AND e.side = 'EXTERNAL' AND e.status = 'UNMATCHED'
        ORDER BY e.counterparty, e.currency, e.value_date
        """;

    // ── SQL: duplicate detection ──────────────────────────────────────────
    private static final String DUP_DETECTION = """
        SELECT e.id
        FROM canonical_txn e
        WHERE e.batch_id = ? AND e.side = 'EXTERNAL' AND e.status = 'UNMATCHED'
          AND EXISTS (
              SELECT 1 FROM canonical_txn e2
              WHERE e2.batch_id = e.batch_id AND e2.side = 'EXTERNAL'
                AND e2.status = 'UNMATCHED' AND e2.id < e.id
                AND e2.external_ref = e.external_ref
                AND e2.amount       = e.amount
                AND e2.currency     = e.currency
                AND e2.value_date   = e.value_date
          )
        """;

    private static final String INSERT_MG = """
        INSERT INTO match_group (batch_id, pass_num, match_type, score)
        SELECT ?, ?, ?, ? FROM generate_series(1, ?) RETURNING id
        """;

    private static final String UPDATE_MATCHED = """
        UPDATE canonical_txn SET match_id = ?, status = 'MATCHED' WHERE id = ?
        """;

    private static final String INSERT_BREAK = """
        INSERT INTO recon_break (batch_id, detected_type, detected_confidence)
        VALUES (?, ?, ?) RETURNING id
        """;

    private static final String INSERT_BREAK_TXN = """
        INSERT INTO break_txn (break_id, txn_id) VALUES (?, ?)
        """;

    private static final String UNMATCHED_TXNS = """
        SELECT id, side, external_ref, amount, currency,
               (value_date - DATE '1970-01-01') AS vd, counterparty
        FROM canonical_txn
        WHERE batch_id = ? AND status = 'UNMATCHED'
        ORDER BY id
        """;

    private static final String INSERT_PASS_STAT = """
        INSERT INTO pass_stat (batch_id, pass_num, matched_count, elapsed_ms)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (batch_id, pass_num) DO UPDATE
          SET matched_count = EXCLUDED.matched_count, elapsed_ms = EXCLUDED.elapsed_ms
        """;

    private final JdbcTemplate jdbc;
    private final BatchRepository batches;

    public MatchingService(JdbcTemplate jdbc, BatchRepository batches) {
        this.jdbc = jdbc;
        this.batches = batches;
    }

    @Transactional
    public ReconcileResult reconcile(long batchId) {
        var batch = batches.findById(batchId)
            .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
        if (batch.getStatus() != BatchStatus.SIMULATED) {
            throw new IllegalStateException(
                "Batch " + batchId + " must be in SIMULATED status to reconcile (current=" + batch.getStatus() + ")");
        }

        long wallStart = System.currentTimeMillis();
        List<PassStatDto> passStats = new ArrayList<>();

        passStats.add(runPass1(batchId));
        passStats.add(runPass2(batchId));
        passStats.add(runPass3(batchId));
        passStats.add(runPass4(batchId));

        Map<String, Integer> breaksByType = createBreaks(batchId);
        int totalBreaks  = breaksByType.values().stream().mapToInt(i -> i).sum();
        int totalMatched = passStats.stream().mapToInt(PassStatDto::matchedCount).sum();

        batch.setStatus(BatchStatus.RECONCILING);
        batches.save(batch);

        long elapsed = System.currentTimeMillis() - wallStart;
        log.info("Batch {} reconciled: {} matched, {} breaks, {}ms", batchId, totalMatched, totalBreaks, elapsed);
        return new ReconcileResult(batchId, totalMatched, totalBreaks, passStats, breaksByType, elapsed);
    }

    // ── Pass 1: exact ─────────────────────────────────────────────────────

    private PassStatDto runPass1(long batchId) {
        long t0 = System.currentTimeMillis();
        List<long[]> pairs = fetchPairs(PASS1_CANDIDATES, batchId);
        List<long[]> deduped = deduplicateOneToOne(pairs);
        int matched = applyMatches(deduped, batchId, 1, "EXACT", null);
        long elapsedMs = System.currentTimeMillis() - t0;
        jdbc.update(INSERT_PASS_STAT, batchId, 1, matched, elapsedMs);
        log.info("Pass 1 (exact): {} pairs in {}ms", matched, elapsedMs);
        return new PassStatDto(1, "EXACT", matched, elapsedMs);
    }

    // ── Pass 2: tolerance ─────────────────────────────────────────────────

    private PassStatDto runPass2(long batchId) {
        long t0 = System.currentTimeMillis();

        List<Pass2Row> candidates = new ArrayList<>();
        jdbc.query(PASS2_CANDIDATES, (RowCallbackHandler) rs -> candidates.add(new Pass2Row(
            rs.getLong("int_id"), rs.getLong("ext_id"),
            rs.getBigDecimal("amt_diff"), rs.getInt("date_diff"),
            rs.getString("match_type")
        )), batchId, batchId);

        // Best per internal → then best per external
        Map<Long, Pass2Row> byInt = new LinkedHashMap<>();
        for (Pass2Row r : candidates) byInt.putIfAbsent(r.intId, r);
        Map<Long, Pass2Row> byExt = new LinkedHashMap<>();
        for (Pass2Row r : byInt.values()) byExt.putIfAbsent(r.extId, r);

        Map<String, List<long[]>> byType = new LinkedHashMap<>();
        for (Pass2Row r : byExt.values()) {
            byType.computeIfAbsent(r.matchType, k -> new ArrayList<>()).add(new long[]{r.intId, r.extId});
        }

        int matched = 0;
        for (var entry : byType.entrySet()) {
            matched += applyMatches(entry.getValue(), batchId, 2, entry.getKey(), null);
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        jdbc.update(INSERT_PASS_STAT, batchId, 2, matched, elapsedMs);
        log.info("Pass 2 (tolerance): {} pairs in {}ms", matched, elapsedMs);
        return new PassStatDto(2, "TOLERANCE", matched, elapsedMs);
    }

    // ── Pass 3: fuzzy ─────────────────────────────────────────────────────

    private PassStatDto runPass3(long batchId) {
        long t0 = System.currentTimeMillis();

        List<Pass3Row> candidates = new ArrayList<>();
        jdbc.query(PASS3_CANDIDATES, (RowCallbackHandler) rs -> candidates.add(new Pass3Row(
            rs.getLong("int_id"), rs.getString("int_ref"),
            rs.getBigDecimal("int_amt"), rs.getInt("int_vd"),
            rs.getLong("ext_id"), rs.getString("ext_ref"),
            rs.getBigDecimal("ext_amt"), rs.getInt("ext_vd")
        )), batchId, batchId);

        List<ScoredPair> scored = new ArrayList<>(candidates.size());
        for (Pass3Row c : candidates) {
            double refScore  = JaroWinkler.similarity(c.intRef, c.extRef);
            double amtProx   = amountProximity(c.intAmt, c.extAmt);
            double dateProx  = Math.max(0.0, 1.0 - Math.abs(c.intVd - c.extVd) / 4.0);
            double score     = 0.60 * refScore + 0.25 * amtProx + 0.15 * dateProx;
            if (score >= 0.85) scored.add(new ScoredPair(score, c.intId, c.extId));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        Set<Long> usedInt = new HashSet<>();
        Set<Long> usedExt = new HashSet<>();
        List<long[]> pairs = new ArrayList<>();
        for (ScoredPair s : scored) {
            if (usedInt.contains(s.intId) || usedExt.contains(s.extId)) continue;
            pairs.add(new long[]{s.intId, s.extId});
            usedInt.add(s.intId);
            usedExt.add(s.extId);
        }

        int matched = applyMatches(pairs, batchId, 3, "FUZZY", null);
        long elapsedMs = System.currentTimeMillis() - t0;
        jdbc.update(INSERT_PASS_STAT, batchId, 3, matched, elapsedMs);
        log.info("Pass 3 (fuzzy): {} pairs in {}ms", matched, elapsedMs);
        return new PassStatDto(3, "FUZZY", matched, elapsedMs);
    }

    // ── Pass 4: split settlement ──────────────────────────────────────────

    private PassStatDto runPass4(long batchId) {
        long t0 = System.currentTimeMillis();

        List<TxnRow> internals = new ArrayList<>();
        jdbc.query(PASS4_INTERNAL, (RowCallbackHandler) rs -> internals.add(new TxnRow(
            rs.getLong("int_id"), rs.getBigDecimal("int_amt"),
            rs.getString("counterparty"), rs.getString("currency"), rs.getInt("int_vd")
        )), batchId);

        List<TxnRow> externals = new ArrayList<>();
        jdbc.query(PASS4_EXTERNAL, (RowCallbackHandler) rs -> externals.add(new TxnRow(
            rs.getLong("ext_id"), rs.getBigDecimal("ext_amt"),
            rs.getString("counterparty"), rs.getString("currency"), rs.getInt("ext_vd")
        )), batchId);

        Map<String, List<TxnRow>> extByBlock = new LinkedHashMap<>();
        for (TxnRow e : externals) {
            extByBlock.computeIfAbsent(e.counterparty + "|" + e.currency, k -> new ArrayList<>()).add(e);
        }

        Set<Long> usedExt = new HashSet<>();
        List<long[]> splitGroups = new ArrayList<>();

        for (TxnRow intern : internals) {
            String blockKey = intern.counterparty + "|" + intern.currency;
            List<TxnRow> candidates = extByBlock.getOrDefault(blockKey, List.of()).stream()
                .filter(e -> !usedExt.contains(e.id))
                .filter(e -> Math.abs(e.vd - intern.vd) <= 3)
                .toList();

            long[] subset = findSplitSubset(intern.amount, intern.id, candidates);
            if (subset != null) {
                splitGroups.add(subset);
                for (int i = 1; i < subset.length; i++) usedExt.add(subset[i]);
            }
        }

        int matched = 0;
        if (!splitGroups.isEmpty()) {
            List<Long> ids = new ArrayList<>(splitGroups.size());
            jdbc.query(INSERT_MG, (RowCallbackHandler) rs -> ids.add(rs.getLong(1)),
                batchId, 4, "SPLIT_SETTLEMENT", null, splitGroups.size());

            List<Object[]> updates = new ArrayList<>();
            for (int i = 0; i < splitGroups.size(); i++) {
                long mgId  = ids.get(i);
                long[] grp = splitGroups.get(i);
                updates.add(new Object[]{mgId, grp[0]});
                for (int j = 1; j < grp.length; j++) updates.add(new Object[]{mgId, grp[j]});
                matched++;
            }
            jdbc.batchUpdate(UPDATE_MATCHED, updates, updates.size(),
                (ps, row) -> { ps.setLong(1, (Long) row[0]); ps.setLong(2, (Long) row[1]); });
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        jdbc.update(INSERT_PASS_STAT, batchId, 4, matched, elapsedMs);
        log.info("Pass 4 (split): {} groups in {}ms", matched, elapsedMs);
        return new PassStatDto(4, "SPLIT_SETTLEMENT", matched, elapsedMs);
    }

    // ── Break creation ────────────────────────────────────────────────────

    private Map<String, Integer> createBreaks(long batchId) {
        Set<Long> dupIds = new HashSet<>();
        jdbc.query(DUP_DETECTION, (RowCallbackHandler) rs -> dupIds.add(rs.getLong(1)), batchId);

        List<UnmatchedTxn> unmatched = new ArrayList<>();
        jdbc.query(UNMATCHED_TXNS, (RowCallbackHandler) rs -> unmatched.add(new UnmatchedTxn(
            rs.getLong("id"), rs.getString("side")
        )), batchId);

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Object[]> breakTxns   = new ArrayList<>();

        for (UnmatchedTxn txn : unmatched) {
            String type;
            double confidence;
            if (dupIds.contains(txn.id)) {
                type = "DUP_EXTERNAL";   confidence = 0.90;
            } else if ("INTERNAL".equals(txn.side)) {
                type = "MISSING_EXTERNAL"; confidence = 0.85;
            } else {
                type = "MISSING_INTERNAL"; confidence = 0.85;
            }
            Long breakId = jdbc.queryForObject(INSERT_BREAK, Long.class,
                batchId, type, BigDecimal.valueOf(confidence));
            breakTxns.add(new Object[]{breakId, txn.id});
            counts.merge(type, 1, Integer::sum);
        }

        if (!breakTxns.isEmpty()) {
            jdbc.batchUpdate(INSERT_BREAK_TXN, breakTxns, breakTxns.size(),
                (ps, row) -> { ps.setLong(1, (Long) row[0]); ps.setLong(2, (Long) row[1]); });
        }
        log.info("Breaks created for batch {}: {}", batchId, counts);
        return counts;
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    private List<long[]> fetchPairs(String sql, long batchId) {
        List<long[]> pairs = new ArrayList<>();
        jdbc.query(sql, (RowCallbackHandler) rs ->
            pairs.add(new long[]{rs.getLong("int_id"), rs.getLong("ext_id")}),
            batchId, batchId);
        return pairs;
    }

    private static List<long[]> deduplicateOneToOne(List<long[]> pairs) {
        Map<Long, long[]> byInt = new LinkedHashMap<>();
        for (long[] p : pairs) byInt.putIfAbsent(p[0], p);
        Map<Long, long[]> byExt = new LinkedHashMap<>();
        for (long[] p : byInt.values()) byExt.putIfAbsent(p[1], p);
        return new ArrayList<>(byExt.values());
    }

    private int applyMatches(List<long[]> pairs, long batchId, int passNum,
                              String matchType, Double score) {
        if (pairs.isEmpty()) return 0;
        Object scoreParam = score != null ? BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP) : null;

        List<Long> ids = new ArrayList<>(pairs.size());
        jdbc.query(INSERT_MG, (RowCallbackHandler) rs -> ids.add(rs.getLong(1)),
            batchId, passNum, matchType, scoreParam, pairs.size());

        List<Object[]> updates = new ArrayList<>(pairs.size() * 2);
        for (int i = 0; i < pairs.size(); i++) {
            updates.add(new Object[]{ids.get(i), pairs.get(i)[0]});
            updates.add(new Object[]{ids.get(i), pairs.get(i)[1]});
        }
        jdbc.batchUpdate(UPDATE_MATCHED, updates, updates.size(),
            (ps, row) -> { ps.setLong(1, (Long) row[0]); ps.setLong(2, (Long) row[1]); });
        return pairs.size();
    }

    static double amountProximity(BigDecimal a, BigDecimal b) {
        if (a.signum() == 0 && b.signum() == 0) return 1.0;
        BigDecimal max = a.abs().max(b.abs());
        if (max.signum() == 0) return 1.0;
        BigDecimal diff = a.subtract(b).abs();
        return Math.max(0.0, 1.0 - diff.divide(max, 6, RoundingMode.HALF_UP).doubleValue() * 2);
    }

    private static long[] findSplitSubset(BigDecimal target, long intId, List<TxnRow> cands) {
        if (cands.size() < 2) return null;
        int n = Math.min(cands.size(), 50);
        BigDecimal tol = BigDecimal.valueOf(0.01);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (cands.get(i).amount.add(cands.get(j).amount).subtract(target).abs().compareTo(tol) <= 0)
                    return new long[]{intId, cands.get(i).id, cands.get(j).id};
                for (int k = j + 1; k < n; k++) {
                    if (cands.get(i).amount.add(cands.get(j).amount).add(cands.get(k).amount)
                           .subtract(target).abs().compareTo(tol) <= 0)
                        return new long[]{intId, cands.get(i).id, cands.get(j).id, cands.get(k).id};
                }
            }
        }
        return null;
    }

    // ── Inner record types ────────────────────────────────────────────────

    private record Pass2Row(long intId, long extId, BigDecimal amtDiff, int dateDiff, String matchType) {}
    private record Pass3Row(long intId, String intRef, BigDecimal intAmt, int intVd,
                            long extId, String extRef, BigDecimal extAmt, int extVd) {}
    private record ScoredPair(double score, long intId, long extId) {}
    private record TxnRow(long id, BigDecimal amount, String counterparty, String currency, int vd) {}
    private record UnmatchedTxn(long id, String side) {}
}
