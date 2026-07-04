package com.reconai.matching;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Pure unit tests for matching utility logic — no DB, no Spring context.
 * End-to-end integration is in MatchingIntegrationTest.
 */
class MatchingServiceTest {

    // ── JaroWinkler ──────────────────────────────────────────────────────

    @Test
    void jaroWinkler_IdenticalStrings_Returns1() {
        assertThat(JaroWinkler.similarity("TXN0000001234", "TXN0000001234")).isEqualTo(1.0);
    }

    @Test
    void jaroWinkler_CompletelyDifferent_ReturnsLow() {
        double score = JaroWinkler.similarity("AAAA", "ZZZZ");
        assertThat(score).isLessThan(0.4);
    }

    @Test
    void jaroWinkler_RefCorruption_LastTwoCharsTruncated_ReturnsHigh() {
        // REF_CORRUPTION truncates last 2 chars: TXN0000001234 → TXN000000123
        double score = JaroWinkler.similarity("TXN0000001234", "TXN000000123");
        assertThat(score).isGreaterThan(0.85);
    }

    @Test
    void jaroWinkler_RefCorruption_SingleCharSwap_ReturnsHigh() {
        // Single digit change in middle
        double score = JaroWinkler.similarity("TXN0000001234", "TXN0000009234");
        assertThat(score).isGreaterThan(0.85);
    }

    @Test
    void jaroWinkler_NullInputs_Returns0() {
        assertThat(JaroWinkler.similarity(null, "TXN001")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity("TXN001", null)).isEqualTo(0.0);
    }

    @Test
    void jaroWinkler_EmptyStrings_ReturnsZero() {
        assertThat(JaroWinkler.similarity("", "TXN001")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity("TXN001", "")).isEqualTo(0.0);
    }

    // ── amountProximity ───────────────────────────────────────────────────

    @Test
    void amountProximity_EqualAmounts_Returns1() {
        assertThat(MatchingService.amountProximity(bd("100.0000"), bd("100.0000"))).isEqualTo(1.0);
    }

    @Test
    void amountProximity_SmallFxDrift_ReturnsHigh() {
        // 0.3% drift — typical AMT_FX_ROUNDING
        double prox = MatchingService.amountProximity(bd("1000.0000"), bd("1003.0000"));
        assertThat(prox).isGreaterThan(0.99);
    }

    @Test
    void amountProximity_FatFinger_ReturnsLow() {
        // Order-of-magnitude difference: 1234 vs 2134 (digit transposition)
        double prox = MatchingService.amountProximity(bd("1234.0000"), bd("2134.0000"));
        assertThat(prox).isLessThan(0.7);
    }

    @Test
    void amountProximity_ZeroVsZero_Returns1() {
        assertThat(MatchingService.amountProximity(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(1.0);
    }

    // ── Score threshold logic ─────────────────────────────────────────────

    @Test
    void fuzzyScore_RefCorruption_ExceedsThreshold() {
        // Simulates a REF_CORRUPTION case: same counterparty/currency/date, ref slightly different
        String intRef = "TXN0000001234";
        String extRef = "TXN000000123";  // truncated

        double refScore  = JaroWinkler.similarity(intRef, extRef);
        double amtProx   = MatchingService.amountProximity(bd("500.0000"), bd("500.0000"));
        double dateProx  = 1.0; // same date
        double score     = 0.60 * refScore + 0.25 * amtProx + 0.15 * dateProx;

        assertThat(score).isGreaterThan(0.85);
    }

    @Test
    void fuzzyScore_UnrelatedTxns_BelowThreshold() {
        // Completely different refs, different amounts → should not match
        double refScore  = JaroWinkler.similarity("TXN0000001111", "TXN9999990000");
        double amtProx   = MatchingService.amountProximity(bd("100.0000"), bd("9999.0000"));
        double dateProx  = 0.25; // 3 days apart
        double score     = 0.60 * refScore + 0.25 * amtProx + 0.15 * dateProx;

        assertThat(score).isLessThan(0.85);
    }

    // ── helper ───────────────────────────────────────────────────────────

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
