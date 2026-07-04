package com.reconai.matching;

import java.util.List;
import java.util.Map;

public record ReconcileResult(
    long batchId,
    int totalMatched,
    int totalBreaks,
    List<PassStatDto> passSummary,
    Map<String, Integer> breaksByType,
    long totalElapsedMs
) {}
