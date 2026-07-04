package com.reconai.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for NDJSON parsing logic — no JdbcTemplate, no Spring context.
 * Full ingest flow (idempotency, DB insert) is covered in SimulatorIntegrationTest.
 */
class IngestionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toRow_ValidNdjson_MapsAllFields() throws Exception {
        String line = ndjsonLine("TXN_ABC", "123.4567");
        var node = MAPPER.readTree(line);

        Object[] row = IngestionService.toRow(1L, "INTERNAL", node);

        assertThat(row[0]).isEqualTo(1L);                                     // batch_id
        assertThat(row[1]).isEqualTo("INTERNAL");                             // side
        assertThat(row[2]).isEqualTo("TXN_ABC");                              // external_ref
        assertThat(row[3]).isEqualTo(new BigDecimal("123.4567"));             // amount
        assertThat(row[4]).isEqualTo("USD");                                  // currency
        assertThat(row[5]).isEqualTo("TestBank");                             // counterparty
        assertThat(row[6]).isEqualTo(Date.valueOf("2024-02-29"));             // trade_date
        assertThat(row[7]).isEqualTo(Date.valueOf("2024-03-01"));             // value_date
        assertThat(row[8]).isEqualTo(Date.valueOf("2024-03-02"));             // settlement_date
        assertThat(row[9]).isEqualTo("DEBIT");                               // direction
    }

    @Test
    void toRow_NullOptionalFields_MapsAsNull() throws Exception {
        String line = "{\"external_ref\":\"TXN001\",\"amount\":\"50.0000\",\"currency\":\"EUR\"," +
                      "\"value_date\":\"2024-03-01\",\"direction\":\"CREDIT\"}";
        var node = MAPPER.readTree(line);

        Object[] row = IngestionService.toRow(2L, "EXTERNAL", node);

        assertThat(row[5]).isNull();  // counterparty null
        assertThat(row[6]).isNull();  // trade_date null
        assertThat(row[8]).isNull();  // settlement_date null
    }

    @Test
    void toRow_MissingRequiredField_ThrowsIllegalArgument() throws Exception {
        String line = "{\"external_ref\":\"TXN001\",\"amount\":\"100.0000\"}"; // missing currency, value_date, direction
        var node = MAPPER.readTree(line);

        assertThatThrownBy(() -> IngestionService.toRow(1L, "INTERNAL", node))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required field");
    }

    @Test
    void toRow_DifferentSides_SetCorrectly() throws Exception {
        var node = MAPPER.readTree(ndjsonLine("TXN002", "10.0000"));

        Object[] internal = IngestionService.toRow(1L, "INTERNAL", node);
        Object[] external = IngestionService.toRow(1L, "EXTERNAL", node);

        assertThat(internal[1]).isEqualTo("INTERNAL");
        assertThat(external[1]).isEqualTo("EXTERNAL");
    }

    // ── helper ───────────────────────────────────────────────────────────

    private static String ndjsonLine(String ref, String amount) {
        return String.format(
            "{\"external_ref\":\"%s\",\"amount\":\"%s\",\"currency\":\"USD\"," +
            "\"value_date\":\"2024-03-01\",\"direction\":\"DEBIT\"," +
            "\"counterparty\":\"TestBank\",\"trade_date\":\"2024-02-29\"," +
            "\"settlement_date\":\"2024-03-02\"}",
            ref, amount);
    }
}
