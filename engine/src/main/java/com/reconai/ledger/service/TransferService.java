package com.reconai.ledger.service;

import com.reconai.ledger.domain.*;
import com.reconai.ledger.repository.AccountRepository;
import com.reconai.ledger.repository.JournalEntryRepository;
import com.reconai.ledger.repository.PostingRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final JournalEntryRepository journalEntries;
    private final PostingRepository postings;

    public TransferService(AccountRepository accounts,
                           JournalEntryRepository journalEntries,
                           PostingRepository postings) {
        this.accounts = accounts;
        this.journalEntries = journalEntries;
        this.postings = postings;
    }

    // REPEATABLE_READ prevents phantom reads on concurrent balance queries against the same account
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TransferResponse createTransfer(TransferRequest req) {
        // Idempotency: return existing result for duplicate key
        return journalEntries.findByIdempotencyKey(req.idempotencyKey())
                .map(this::toResponse)
                .orElseGet(() -> doCreateTransfer(req));
    }

    private TransferResponse doCreateTransfer(TransferRequest req) {
        Account debit = accounts.findById(req.debitAccountId())
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + req.debitAccountId()));
        Account credit = accounts.findById(req.creditAccountId())
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + req.creditAccountId()));

        JournalEntry entry = new JournalEntry(req.idempotencyKey(), req.description());

        Posting debitPosting = new Posting(debit, req.amount(), Direction.DEBIT, req.currency(), req.valueDate());
        debitPosting.setCounterparty(req.counterparty());
        debitPosting.setExternalRef(req.externalRef());

        Posting creditPosting = new Posting(credit, req.amount(), Direction.CREDIT, req.currency(), req.valueDate());
        creditPosting.setCounterparty(req.counterparty());
        creditPosting.setExternalRef(req.externalRef());

        entry.addPosting(debitPosting);
        entry.addPosting(creditPosting);

        validateZeroSum(entry);

        journalEntries.save(entry);
        return toResponse(entry);
    }

    private void validateZeroSum(JournalEntry entry) {
        BigDecimal net = entry.getPostings().stream()
                .map(p -> p.getDirection() == Direction.DEBIT ? p.getAmount() : p.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (net.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("Journal entry postings do not sum to zero: net=" + net);
        }
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(long accountId) {
        if (!accounts.existsById(accountId)) {
            throw new EntityNotFoundException("Account not found: " + accountId);
        }
        return postings.getBalance(accountId);
    }

    private TransferResponse toResponse(JournalEntry entry) {
        Posting debitPosting = entry.getPostings().stream()
                .filter(p -> p.getDirection() == Direction.DEBIT)
                .findFirst().orElseThrow();
        Posting creditPosting = entry.getPostings().stream()
                .filter(p -> p.getDirection() == Direction.CREDIT)
                .findFirst().orElseThrow();
        return new TransferResponse(
                entry.getId(),
                entry.getIdempotencyKey(),
                debitPosting.getId(),
                creditPosting.getId(),
                debitPosting.getAmount(),
                debitPosting.getCurrency(),
                debitPosting.getValueDate(),
                entry.getCreatedAt()
        );
    }
}
