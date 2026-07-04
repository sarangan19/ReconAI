package com.reconai.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconai.ledger.api.CreateAccountRequest;
import com.reconai.ledger.api.CreateTransferRequest;
import com.reconai.ledger.domain.Account;
import com.reconai.ledger.domain.AccountType;
import com.reconai.ledger.repository.AccountRepository;
import com.reconai.ledger.repository.PostingRepository;
import com.reconai.ledger.service.TransferRequest;
import com.reconai.ledger.service.TransferResponse;
import com.reconai.ledger.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class LedgerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired TransferService transferService;
    @Autowired AccountRepository accountRepository;
    @Autowired PostingRepository postingRepository;

    Account cashAccount;
    Account revenueAccount;

    @BeforeEach
    void setup() {
        cashAccount = accountRepository.save(new Account("Cash-" + UUID.randomUUID(), "USD", AccountType.ASSET));
        revenueAccount = accountRepository.save(new Account("Revenue-" + UUID.randomUUID(), "USD", AccountType.INCOME));
    }

    @Test
    void createAccount_returns201() throws Exception {
        String body = mapper.writeValueAsString(new CreateAccountRequest("Test Account", "EUR", AccountType.ASSET));
        mockMvc.perform(post("/api/ledger/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Account"))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void createTransfer_roundTrip_balanceCorrect() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("100.5000");

        String body = mapper.writeValueAsString(new CreateTransferRequest(
                cashAccount.getId(), revenueAccount.getId(),
                amount, "USD", LocalDate.now(), "test transfer", null, null));

        MvcResult result = mockMvc.perform(post("/api/ledger/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey))
                .andReturn();

        TransferResponse response = mapper.readValue(result.getResponse().getContentAsString(), TransferResponse.class);
        assertThat(response.amount()).isEqualByComparingTo(amount);

        mockMvc.perform(get("/api/ledger/accounts/{id}/balance", cashAccount.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.5));
    }

    @Test
    void createTransfer_idempotent_sameResponseTwice() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = mapper.writeValueAsString(new CreateTransferRequest(
                cashAccount.getId(), revenueAccount.getId(),
                new BigDecimal("50.00"), "USD", LocalDate.now(), null, null, null));

        String first = mockMvc.perform(post("/api/ledger/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/ledger/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        TransferResponse r1 = mapper.readValue(first, TransferResponse.class);
        TransferResponse r2 = mapper.readValue(second, TransferResponse.class);
        assertThat(r1.journalEntryId()).isEqualTo(r2.journalEntryId());
        assertThat(r1.debitPostingId()).isEqualTo(r2.debitPostingId());
    }

    @Test
    void concurrencyTest_50threads_exactBalance() throws Exception {
        int threads = 50;
        BigDecimal perTransfer = new BigDecimal("10.0000");
        BigDecimal expected = perTransfer.multiply(BigDecimal.valueOf(threads));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String key = UUID.randomUUID().toString();
            futures.add(pool.submit(() -> {
                ready.countDown();
                ready.await();
                try {
                    transferService.createTransfer(new TransferRequest(
                            key, cashAccount.getId(), revenueAccount.getId(),
                            perTransfer, "USD", LocalDate.now(), null, null, null));
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                return null;
            }));
        }

        for (Future<Void> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(errors.get()).isZero();
        BigDecimal balance = postingRepository.getBalance(cashAccount.getId());
        assertThat(balance).isEqualByComparingTo(expected);
    }
}
