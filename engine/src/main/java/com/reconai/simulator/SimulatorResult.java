package com.reconai.simulator;

import java.util.Map;

public record SimulatorResult(
    long batchId,
    int internalCount,
    int externalCount,
    int groundTruthCount,
    Map<String, Integer> injectionCounts,
    long elapsedMs
) {}
