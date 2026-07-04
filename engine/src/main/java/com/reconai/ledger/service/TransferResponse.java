package com.reconai.ledger.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TransferResponse(
        long journalEntryId,
        String idempotencyKey,
        long debitPostingId,
        long creditPostingId,
        BigDecimal amount,
        String currency,
        LocalDate valueDate,
        Instant createdAt
) {}
