package com.reconai.ledger.api;

import com.reconai.ledger.domain.Account;
import com.reconai.ledger.repository.AccountRepository;
import com.reconai.ledger.service.TransferRequest;
import com.reconai.ledger.service.TransferResponse;
import com.reconai.ledger.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/ledger")
@Tag(name = "Ledger", description = "Double-entry ledger operations")
public class LedgerController {

    private final TransferService transferService;
    private final AccountRepository accountRepository;

    public LedgerController(TransferService transferService, AccountRepository accountRepository) {
        this.transferService = transferService;
        this.accountRepository = accountRepository;
    }

    @PostMapping("/accounts")
    @Operation(summary = "Create an account")
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest req) {
        Account account = new Account(req.name(), req.currency(), req.type());
        return ResponseEntity.status(HttpStatus.CREATED).body(accountRepository.save(account));
    }

    @GetMapping("/accounts/{id}")
    @Operation(summary = "Get account by ID")
    public ResponseEntity<Account> getAccount(@PathVariable long id) {
        return accountRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/accounts/{id}/balance")
    @Operation(summary = "Get current balance for an account")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable long id) {
        BigDecimal balance = transferService.getBalance(id);
        return ResponseEntity.ok(Map.of("accountId", id, "balance", balance));
    }

    @PostMapping("/transfers")
    @Operation(summary = "Create an idempotent transfer (double-entry)")
    public ResponseEntity<TransferResponse> createTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest req) {
        TransferRequest serviceReq = new TransferRequest(
                idempotencyKey, req.debitAccountId(), req.creditAccountId(),
                req.amount(), req.currency(), req.valueDate(),
                req.description(), req.counterparty(), req.externalRef()
        );
        return ResponseEntity.ok(transferService.createTransfer(serviceReq));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleConflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Data integrity violation"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleBadState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }
}
