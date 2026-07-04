package com.reconai.ledger.api;

import com.reconai.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull AccountType type
) {}
