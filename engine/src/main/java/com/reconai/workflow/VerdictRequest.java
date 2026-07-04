package com.reconai.workflow;

import java.math.BigDecimal;

public record VerdictRequest(
    String rootCauseCode,
    BigDecimal confidence,
    String explanation,
    String suggestedAction
) {}
