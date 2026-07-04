package com.reconai.ledger;

import com.reconai.ledger.domain.*;
import com.reconai.ledger.repository.AccountRepository;
import com.reconai.ledger.repository.JournalEntryRepository;
import com.reconai.ledger.repository.PostingRepository;
import com.reconai.ledger.service.TransferRequest;
import com.reconai.ledger.service.TransferResponse;
import com.reconai.ledger.service.TransferService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock JournalEntryRepository journalEntryRepository;
    @Mock PostingRepository postingRepository;

    TransferService service;

    Account cashAccount;
    Account revenueAccount;

    @BeforeEach
    void setup() {
        service = new TransferService(accountRepository, journalEntryRepository, postingRepository);

        cashAccount = new Account("Cash", "USD", AccountType.ASSET);
        setId(cashAccount, 1L);

        revenueAccount = new Account("Revenue", "USD", AccountType.INCOME);
        setId(revenueAccount, 2L);
    }

    @Test
    void createTransfer_happyPath_returnsTwoPostings() {
        when(journalEntryRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(accountRepository.findById(1L)).thenReturn(Optional.of(cashAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(revenueAccount));
        when(journalEntryRepository.save(any())).thenAnswer(inv -> {
            JournalEntry e = inv.getArgument(0);
            setId(e, 100L);
            e.getPostings().get(0); // trigger lazy init
            setId(e.getPostings().get(0), 10L);
            setId(e.getPostings().get(1), 11L);
            return e;
        });

        TransferResponse response = service.createTransfer(request("key-1", BigDecimal.TEN));

        assertThat(response.journalEntryId()).isEqualTo(100L);
        assertThat(response.amount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(response.currency()).isEqualTo("USD");
    }

    @Test
    void createTransfer_sameKeyTwice_returnsFirstResult() {
        JournalEntry existing = existingEntry("key-dup");
        when(journalEntryRepository.findByIdempotencyKey("key-dup")).thenReturn(Optional.of(existing));

        TransferResponse first = service.createTransfer(request("key-dup", BigDecimal.ONE));
        TransferResponse second = service.createTransfer(request("key-dup", BigDecimal.ONE));

        // No new saves for the second call
        verify(journalEntryRepository, never()).save(any());
        assertThat(first.idempotencyKey()).isEqualTo(second.idempotencyKey());
    }

    @Test
    void createTransfer_missingAccount_throwsEntityNotFound() {
        when(journalEntryRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(accountRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTransfer(request("key-x", BigDecimal.ONE)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- helpers ----

    private TransferRequest request(String key, BigDecimal amount) {
        return new TransferRequest(key, 1L, 2L, amount, "USD", LocalDate.now(), null, null, null);
    }

    private JournalEntry existingEntry(String key) {
        JournalEntry e = new JournalEntry(key, "existing");
        setId(e, 99L);
        Posting d = new Posting(cashAccount, BigDecimal.ONE, Direction.DEBIT, "USD", LocalDate.now());
        setId(d, 20L);
        Posting c = new Posting(revenueAccount, BigDecimal.ONE, Direction.CREDIT, "USD", LocalDate.now());
        setId(c, 21L);
        e.addPosting(d);
        e.addPosting(c);
        return e;
    }

    private static void setId(Object entity, Long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
