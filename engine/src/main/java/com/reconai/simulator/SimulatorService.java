package com.reconai.simulator;

import com.reconai.recon.domain.BatchStatus;
import com.reconai.recon.domain.DiscrepancyCode;
import com.reconai.recon.repository.BatchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

@Service
public class SimulatorService {

    private static final String[] CURRENCIES = {"USD", "EUR", "GBP", "JPY", "CHF"};
    private static final String[] COUNTERPARTIES = {
        "JPMorgan", "Goldman Sachs", "Citibank", "Bank of America", "Wells Fargo",
        "HSBC", "Barclays", "Deutsche Bank", "BNP Paribas", "Societe Generale",
        "Credit Suisse", "UBS", "Nomura", "Mizuho", "MUFG",
        "Standard Chartered", "Lloyds", "Santander", "ING", "ABN AMRO"
    };
    private static final LocalDate BASE_DATE = LocalDate.of(2024, 1, 1);
    private static final int CHUNK = 10_000;

    private static final String INSERT_CTXN = """
        INSERT INTO canonical_txn
          (batch_id, side, external_ref, amount, currency, counterparty,
           trade_date, value_date, settlement_date, direction, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNMATCHED')
        """;

    private static final String INSERT_GT = """
        INSERT INTO ground_truth (batch_id, external_ref, side, injected_code)
        VALUES (?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;
    private final BatchRepository batches;

    public SimulatorService(JdbcTemplate jdbc, BatchRepository batches) {
        this.jdbc = jdbc;
        this.batches = batches;
    }

    @Transactional
    public SimulatorResult simulate(long batchId, int n, long seed, InjectionRates rates) {
        var batch = batches.findById(batchId)
            .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
        if (batch.getStatus() != BatchStatus.CREATED) {
            throw new IllegalStateException("Batch " + batchId + " already simulated (status=" + batch.getStatus() + ")");
        }

        long start = System.currentTimeMillis();
        var rng = new Random(seed);
        var injectionCounts = new EnumMap<DiscrepancyCode, Integer>(DiscrepancyCode.class);
        for (var code : DiscrepancyCode.values()) injectionCounts.put(code, 0);

        // Precompute cumulative thresholds for O(1) discrepancy roll
        double[] thresholds = buildThresholds(rates);

        int totalInternal = 0, totalExternal = 0, totalGt = 0;

        for (int chunkStart = 0; chunkStart < n; chunkStart += CHUNK) {
            int chunkEnd = Math.min(chunkStart + CHUNK, n);
            int chunkSize = chunkEnd - chunkStart;

            List<Object[]> internalRows = new ArrayList<>(chunkSize);
            List<Object[]> externalRows = new ArrayList<>(chunkSize + (int) (chunkSize * 0.05));
            List<Object[]> gtRows     = new ArrayList<>((int) (chunkSize * 0.05));

            for (int i = chunkStart; i < chunkEnd; i++) {
                String ref        = String.format("TXN%010d", i);
                BigDecimal amount = generateAmount(rng);
                String currency   = CURRENCIES[rng.nextInt(CURRENCIES.length)];
                String party      = COUNTERPARTIES[rng.nextInt(COUNTERPARTIES.length)];
                LocalDate trade   = BASE_DATE.plusDays(rng.nextInt(365));
                LocalDate value   = trade.plusDays(1);
                LocalDate settle  = value.plusDays(1);

                DiscrepancyCode disc = rollDiscrepancy(rng, thresholds);

                // --- internal side ---
                if (disc != DiscrepancyCode.MISSING_INTERNAL) {
                    internalRows.add(ctxnRow(batchId, "INTERNAL", ref, amount, currency, party, trade, value, settle, "DEBIT"));
                }

                // --- external side ---
                switch (disc) {
                    case null -> externalRows.add(ctxnRow(batchId, "EXTERNAL", ref, amount, currency, party, trade, value, settle, "CREDIT"));

                    case MISSING_EXTERNAL -> gtRows.add(gtRow(batchId, ref, "INTERNAL", disc));

                    case MISSING_INTERNAL -> {
                        externalRows.add(ctxnRow(batchId, "EXTERNAL", ref, amount, currency, party, trade, value, settle, "CREDIT"));
                        gtRows.add(gtRow(batchId, ref, "EXTERNAL", disc));
                    }

                    case DUP_EXTERNAL -> {
                        externalRows.add(ctxnRow(batchId, "EXTERNAL", ref, amount, currency, party, trade, value, settle, "CREDIT"));
                        String dupRef = ref + "_D";
                        externalRows.add(ctxnRow(batchId, "EXTERNAL", dupRef, amount, currency, party, trade, value, settle, "CREDIT"));
                        gtRows.add(gtRow(batchId, dupRef, "EXTERNAL", disc));
                    }

                    case AMT_FX_ROUNDING -> {
                        double drift = 1.0 + (rng.nextDouble() * 0.005 * (rng.nextBoolean() ? 1 : -1));
                        BigDecimal driftedAmount = amount.multiply(BigDecimal.valueOf(drift)).setScale(4, RoundingMode.HALF_UP);
                        externalRows.add(ctxnRow(batchId, "EXTERNAL", ref, driftedAmount, currency, party, trade, value, settle, "CREDIT"));
                        gtRows.add(gtRow(batchId, ref, "EXTERNAL", disc));
                    }

                    case AMT_FAT_FINGER -> {
                        BigDecimal mangled = fatFinger(amount, rng);
                        externalRows.add(ctxnRow(batchId, "EXTERNAL", ref, mangled, currency, party, trade, value, settle, "CREDIT"));
                        gtRows.add(gtRow(batchId, ref, "EXTERNAL", disc));
                    }

                    case DATE_TIMING -> {
                        LocalDate shiftedSettle = settle.plusDays(1 + rng.nextInt(2));
                        externalRows.add(ctxnRow(batchId, "EXTERNAL", ref, amount, currency, party, trade, value, shiftedSettle, "CREDIT"));
                        gtRows.add(gtRow(batchId, ref, "EXTERNAL", disc));
                    }

                    case REF_CORRUPTION -> {
                        String corrupt = corruptRef(ref, rng);
                        externalRows.add(ctxnRow(batchId, "EXTERNAL", corrupt, amount, currency, party, trade, value, settle, "CREDIT"));
                        gtRows.add(gtRow(batchId, corrupt, "EXTERNAL", disc));
                    }

                    case SPLIT_SETTLEMENT -> {
                        int parts = 2 + rng.nextInt(2);
                        BigDecimal[] splits = splitAmount(amount, parts, rng);
                        for (int p = 0; p < parts; p++) {
                            String partRef = ref + "_S" + (p + 1);
                            externalRows.add(ctxnRow(batchId, "EXTERNAL", partRef, splits[p], currency, party, trade, value, settle, "CREDIT"));
                            if (p == 0) gtRows.add(gtRow(batchId, partRef, "EXTERNAL", disc));
                        }
                    }
                }

                if (disc != null) injectionCounts.merge(disc, 1, Integer::sum);
            }

            // JDBC batch inserts
            jdbc.batchUpdate(INSERT_CTXN, internalRows, internalRows.size(), (ps, row) -> bindCtxn(ps, row));
            jdbc.batchUpdate(INSERT_CTXN, externalRows, externalRows.size(), (ps, row) -> bindCtxn(ps, row));
            if (!gtRows.isEmpty()) {
                jdbc.batchUpdate(INSERT_GT, gtRows, gtRows.size(), (ps, row) -> {
                    ps.setLong(1,   (Long)   row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setString(4, (String) row[3]);
                });
            }

            totalInternal += internalRows.size();
            totalExternal += externalRows.size();
            totalGt       += gtRows.size();
        }

        batch.setStatus(BatchStatus.SIMULATED);
        batches.save(batch);

        Map<String, Integer> countsByName = new LinkedHashMap<>();
        for (var entry : injectionCounts.entrySet()) {
            countsByName.put(entry.getKey().name(), entry.getValue());
        }

        return new SimulatorResult(batchId, totalInternal, totalExternal, totalGt, countsByName,
                                   System.currentTimeMillis() - start);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static BigDecimal generateAmount(Random rng) {
        // 1.0000 – 9999.0000 with 4 decimal places
        int whole = 1 + rng.nextInt(9999);
        int frac  = rng.nextInt(10000);
        return new BigDecimal(whole + "." + String.format("%04d", frac));
    }

    static double[] buildThresholds(InjectionRates r) {
        // Order must match the switch cases below: DUP, MISS_EXT, MISS_INT, FX, FAT, DATE, REF, SPLIT
        double[] t = new double[8];
        t[0] = r.dupExternal();
        t[1] = t[0] + r.missingExternal();
        t[2] = t[1] + r.missingInternal();
        t[3] = t[2] + r.amtFxRounding();
        t[4] = t[3] + r.amtFatFinger();
        t[5] = t[4] + r.dateTiming();
        t[6] = t[5] + r.refCorruption();
        t[7] = t[6] + r.splitSettlement();
        return t;
    }

    static DiscrepancyCode rollDiscrepancy(Random rng, double[] t) {
        double roll = rng.nextDouble();
        if (roll < t[0]) return DiscrepancyCode.DUP_EXTERNAL;
        if (roll < t[1]) return DiscrepancyCode.MISSING_EXTERNAL;
        if (roll < t[2]) return DiscrepancyCode.MISSING_INTERNAL;
        if (roll < t[3]) return DiscrepancyCode.AMT_FX_ROUNDING;
        if (roll < t[4]) return DiscrepancyCode.AMT_FAT_FINGER;
        if (roll < t[5]) return DiscrepancyCode.DATE_TIMING;
        if (roll < t[6]) return DiscrepancyCode.REF_CORRUPTION;
        if (roll < t[7]) return DiscrepancyCode.SPLIT_SETTLEMENT;
        return null; // clean
    }

    /** Pure helper: simulate injection decisions without JDBC (test use only). */
    static Map<String, Integer> computeInjectionCounts(int n, long seed, InjectionRates rates) {
        var rng = new Random(seed);
        double[] thresholds = buildThresholds(rates);
        var counts = new EnumMap<DiscrepancyCode, Integer>(DiscrepancyCode.class);
        for (var code : DiscrepancyCode.values()) counts.put(code, 0);
        for (int i = 0; i < n; i++) {
            // mirror the per-txn RNG consumption in simulate(): amount, currency, counterparty, trade-date roll
            generateAmount(rng);               // amount (2 nextInt calls internally: whole + frac)
            rng.nextInt(CURRENCIES.length);    // currency
            rng.nextInt(COUNTERPARTIES.length);// counterparty
            rng.nextInt(365);                  // trade date
            DiscrepancyCode disc = rollDiscrepancy(rng, thresholds);
            // consume additional rng for each discrepancy type that needs it
            if (disc == DiscrepancyCode.AMT_FX_ROUNDING) { rng.nextDouble(); rng.nextBoolean(); }
            else if (disc == DiscrepancyCode.AMT_FAT_FINGER) { rng.nextInt(); } // fatFinger rng.nextInt
            else if (disc == DiscrepancyCode.DATE_TIMING)    { rng.nextInt(2); }
            else if (disc == DiscrepancyCode.REF_CORRUPTION) { rng.nextBoolean(); rng.nextInt(); }
            else if (disc == DiscrepancyCode.SPLIT_SETTLEMENT){ rng.nextInt(2); /* parts */ }
            if (disc != null) counts.merge(disc, 1, Integer::sum);
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var e : counts.entrySet()) result.put(e.getKey().name(), e.getValue());
        return result;
    }

    private static BigDecimal fatFinger(BigDecimal amount, Random rng) {
        // Transpose two adjacent digits in the whole-number part
        String s = amount.toPlainString();
        int dotIdx = s.indexOf('.');
        String whole = dotIdx >= 0 ? s.substring(0, dotIdx) : s;
        String frac  = dotIdx >= 0 ? s.substring(dotIdx) : "";
        if (whole.length() >= 2) {
            int pos = rng.nextInt(whole.length() - 1);
            char[] chars = whole.toCharArray();
            char tmp = chars[pos]; chars[pos] = chars[pos + 1]; chars[pos + 1] = tmp;
            whole = new String(chars);
        }
        return new BigDecimal(whole + frac);
    }

    private static String corruptRef(String ref, Random rng) {
        // Truncate last 2 chars or swap a digit
        if (rng.nextBoolean() && ref.length() > 4) {
            return ref.substring(0, ref.length() - 2);
        }
        char[] chars = ref.toCharArray();
        int pos = rng.nextInt(chars.length);
        chars[pos] = (char) ('0' + rng.nextInt(10));
        return new String(chars);
    }

    private static BigDecimal[] splitAmount(BigDecimal total, int parts, Random rng) {
        BigDecimal[] splits = new BigDecimal[parts];
        BigDecimal remaining = total;
        for (int i = 0; i < parts - 1; i++) {
            // random fraction of remaining, min 10% of total
            double frac = 0.1 + rng.nextDouble() * 0.6;
            splits[i] = remaining.multiply(BigDecimal.valueOf(frac)).setScale(4, RoundingMode.HALF_UP);
            remaining = remaining.subtract(splits[i]);
        }
        splits[parts - 1] = remaining.max(BigDecimal.valueOf(0.0001));
        return splits;
    }

    private static Object[] ctxnRow(long batchId, String side, String ref, BigDecimal amount,
                                    String currency, String party,
                                    LocalDate trade, LocalDate value, LocalDate settle, String dir) {
        return new Object[]{batchId, side, ref, amount, currency, party,
                            Date.valueOf(trade), Date.valueOf(value), Date.valueOf(settle), dir};
    }

    private static Object[] gtRow(long batchId, String ref, String side, DiscrepancyCode code) {
        return new Object[]{batchId, ref, side, code.name()};
    }

    private static void bindCtxn(java.sql.PreparedStatement ps, Object[] row) throws java.sql.SQLException {
        ps.setLong(1,   (Long)       row[0]);
        ps.setString(2, (String)     row[1]);
        ps.setString(3, (String)     row[2]);
        ps.setBigDecimal(4, (BigDecimal) row[3]);
        ps.setString(5, (String)     row[4]);
        ps.setString(6, (String)     row[5]);
        ps.setDate(7,   (Date)       row[6]);
        ps.setDate(8,   (Date)       row[7]);
        ps.setDate(9,   (Date)       row[8]);
        ps.setString(10,(String)     row[9]);
    }
}
