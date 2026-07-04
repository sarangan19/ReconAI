package com.reconai.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateTransferRequest(
        @NotNull Long debitAccountId,
        @NotNull Long creditAccountId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull LocalDate valueDate,
        String description,
        String counterparty,
        String externalRef
) {}
