package com.reconai.simulator;

import com.reconai.recon.domain.DiscrepancyCode;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Pure unit tests for the injection decision logic — no Spring context, no JDBC.
 * End-to-end counts are verified in SimulatorIntegrationTest.
 */
class SimulatorServiceTest {

    private static final InjectionRates RATES = InjectionRates.defaults();
    private static final double[] THRESHOLDS = SimulatorService.buildThresholds(RATES);

    @Test
    void sameSeedException_ProducesSameDiscrepancySequence() {
        int n = 5_000;
        var counts1 = rollCounts(n, 42L);
        var counts2 = rollCounts(n, 42L);

        assertThat(counts1).isEqualTo(counts2);
    }

    @Test
    void differentSeeds_ProduceDifferentSequences() {
        int n = 5_000;
        var counts42 = rollCounts(n, 42L);
        var counts99 = rollCounts(n, 99L);

        // Effectively impossible for all 8 counts to match across two independent seeds
        assertThat(counts42).isNotEqualTo(counts99);
    }

    @Test
    void injectionRates_WithinTolerance_Over50KRolls() {
        int n = 50_000;
        var counts = rollCounts(n, 7L);

        assertRate(counts, DiscrepancyCode.DUP_EXTERNAL,     RATES.dupExternal(),     n, 0.002);
        assertRate(counts, DiscrepancyCode.MISSING_EXTERNAL, RATES.missingExternal(), n, 0.002);
        assertRate(counts, DiscrepancyCode.MISSING_INTERNAL, RATES.missingInternal(), n, 0.002);
        assertRate(counts, DiscrepancyCode.AMT_FX_ROUNDING,  RATES.amtFxRounding(),   n, 0.002);
        assertRate(counts, DiscrepancyCode.AMT_FAT_FINGER,   RATES.amtFatFinger(),    n, 0.002);
        assertRate(counts, DiscrepancyCode.DATE_TIMING,      RATES.dateTiming(),      n, 0.002);
        assertRate(counts, DiscrepancyCode.REF_CORRUPTION,   RATES.refCorruption(),   n, 0.002);
        assertRate(counts, DiscrepancyCode.SPLIT_SETTLEMENT, RATES.splitSettlement(), n, 0.002);
    }

    @Test
    void noInjection_IsTheMajority() {
        int n = 10_000;
        var counts = rollCounts(n, 1L);

        int totalInjected = counts.values().stream().mapToInt(Integer::intValue).sum();
        double cleanFraction = 1.0 - (double) totalInjected / n;
        // ~96.3% of txns should be clean (total injection rate ~3.7%)
        assertThat(cleanFraction).isBetween(0.93, 0.99);
    }

    @Test
    void thresholdOrder_IsMonotonicallyIncreasing() {
        double[] t = THRESHOLDS;
        for (int i = 1; i < t.length; i++) {
            assertThat(t[i]).isGreaterThan(t[i - 1]);
        }
        // Total injection rate < 1.0 (otherwise clean branch is unreachable)
        assertThat(t[t.length - 1]).isLessThan(1.0);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static Map<DiscrepancyCode, Integer> rollCounts(int n, long seed) {
        var rng = new Random(seed);
        var counts = new EnumMap<DiscrepancyCode, Integer>(DiscrepancyCode.class);
        for (var code : DiscrepancyCode.values()) counts.put(code, 0);

        for (int i = 0; i < n; i++) {
            DiscrepancyCode disc = SimulatorService.rollDiscrepancy(rng, THRESHOLDS);
            if (disc != null) counts.merge(disc, 1, Integer::sum);
        }
        return counts;
    }

    private static void assertRate(Map<DiscrepancyCode, Integer> counts, DiscrepancyCode code,
                                   double expected, int n, double tol) {
        int count = counts.getOrDefault(code, 0);
        double actual = (double) count / n;
        assertThat(actual)
            .as("%s: expected %.3f ± %.3f, got %.3f (count=%d)", code, expected, tol, actual, count)
            .isBetween(expected - tol, expected + tol);
    }
}
