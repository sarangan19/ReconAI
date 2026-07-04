package com.reconai.simulator;

public record InjectionRates(
    double dupExternal,
    double missingExternal,
    double missingInternal,
    double amtFxRounding,
    double amtFatFinger,
    double dateTiming,
    double refCorruption,
    double splitSettlement
) {
    public static InjectionRates defaults() {
        return new InjectionRates(0.004, 0.005, 0.003, 0.006, 0.002, 0.010, 0.004, 0.003);
    }
}
