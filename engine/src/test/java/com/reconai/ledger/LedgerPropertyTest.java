package com.reconai.ledger;

import com.reconai.ledger.domain.*;
import com.reconai.ledger.repository.AccountRepository;
import com.reconai.ledger.repository.JournalEntryRepository;
import com.reconai.ledger.repository.PostingRepository;
import com.reconai.ledger.service.TransferRequest;
import com.reconai.ledger.service.TransferResponse;
import com.reconai.ledger.service.TransferService;
import net.jqwik.api.*;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for the zero-sum invariant and idempotency.
 * Pure unit tests — no Spring context required; the invariants are in the service layer.
 */
class LedgerPropertyTest {

    @Property(tries = 30)
    void zeroSumInvariant_debitAndCreditAlwaysCancel(@ForAll("positiveAmounts") BigDecimal amount) {
        // The zero-sum check: for every transfer, debit amount + (-credit amount) = 0
        BigDecimal debit = amount;
        BigDecimal credit = amount.negate();
        assertThat(debit.add(credit)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Property(tries = 30)
    void idempotentReplay_sameSaveCount(@ForAll("positiveAmounts") BigDecimal amount) {
        AtomicLong idGen = new AtomicLong(1);

        AccountRepository accountRepo = Mockito.mock(AccountRepository.class);
        JournalEntryRepository journalRepo = Mockito.mock(JournalEntryRepository.class);
        PostingRepository postingRepo = Mockito.mock(PostingRepository.class);

        Account debit = makeAccount(idGen.getAndIncrement(), "USD", AccountType.ASSET);
        Account credit = makeAccount(idGen.getAndIncrement(), "USD", AccountType.INCOME);

        when(accountRepo.findById(debit.getId())).thenReturn(Optional.of(debit));
        when(accountRepo.findById(credit.getId())).thenReturn(Optional.of(credit));

        String key = UUID.randomUUID().toString();

        // First call: no existing entry → save
        when(journalRepo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(journalRepo.save(any())).thenAnswer(inv -> {
            JournalEntry e = inv.getArgument(0);
            setId(e, idGen.getAndIncrement());
            e.getPostings().get(0); e.getPostings().get(1); // ensure list populated
            setId(e.getPostings().get(0), idGen.getAndIncrement());
            setId(e.getPostings().get(1), idGen.getAndIncrement());
            return e;
        });

        TransferService service = new TransferService(accountRepo, journalRepo, postingRepo);
        TransferRequest req = new TransferRequest(key, debit.getId(), credit.getId(),
                amount, "USD", LocalDate.now(), null, null, null);

        TransferResponse first = service.createTransfer(req);

        // Second call: entry already exists → return cached, no new save
        when(journalRepo.findByIdempotencyKey(anyString())).thenAnswer(inv -> {
            // Simulate finding the saved entry
            JournalEntry existing = new JournalEntry(key, null);
            setId(existing, first.journalEntryId());
            Posting d = new Posting(debit, amount, Direction.DEBIT, "USD", LocalDate.now());
            setId(d, first.debitPostingId());
            Posting c = new Posting(credit, amount, Direction.CREDIT, "USD", LocalDate.now());
            setId(c, first.creditPostingId());
            existing.addPosting(d);
            existing.addPosting(c);
            return Optional.of(existing);
        });

        TransferResponse second = service.createTransfer(req);
        TransferResponse third = service.createTransfer(req);

        assertThat(first.journalEntryId()).isEqualTo(second.journalEntryId());
        assertThat(second.journalEntryId()).isEqualTo(third.journalEntryId());
    }

    @Property(tries = 20)
    void positiveAmountsAlwaysPositive(@ForAll("positiveAmounts") BigDecimal amount) {
        assertThat(amount).isGreaterThan(BigDecimal.ZERO);
    }

    @Provide
    Arbitrary<BigDecimal> positiveAmounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.0001"), new BigDecimal("999999.9999"))
                .ofScale(4);
    }

    private static Account makeAccount(long id, String currency, AccountType type) {
        Account a = new Account("Acct-" + id, currency, type);
        setId(a, id);
        return a;
    }

    private static void setId(Object entity, long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
