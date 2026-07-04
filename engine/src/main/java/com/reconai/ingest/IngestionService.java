package com.reconai.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Idempotent NDJSON bulk ingest into canonical_txn.
 * A second call with the same (batch_id, side) is a no-op: returns the previously stored count.
 */
@Service
public class IngestionService {

    private static final int CHUNK = 10_000;

    private static final String COUNT_SIDE = """
        SELECT COUNT(*) FROM canonical_txn WHERE batch_id = ? AND side = ?
        """;

    private static final String INSERT = """
        INSERT INTO canonical_txn
          (batch_id, side, external_ref, amount, currency, counterparty,
           trade_date, value_date, settlement_date, direction, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNMATCHED')
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IngestionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record IngestResult(long batchId, String side, long rowsInserted, boolean wasIdempotent) {}

    @Transactional
    public IngestResult ingest(long batchId, String side, InputStream ndjson) throws IOException {
        // Idempotency: if side already has rows, return the count without re-inserting
        Long existing = jdbc.queryForObject(COUNT_SIDE, Long.class, batchId, side);
        if (existing != null && existing > 0) {
            return new IngestResult(batchId, side, existing, true);
        }

        long total = 0;
        List<Object[]> chunk = new ArrayList<>(CHUNK);

        try (var reader = new BufferedReader(new InputStreamReader(ndjson))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;

                JsonNode node = mapper.readTree(line);
                chunk.add(toRow(batchId, side, node));

                if (chunk.size() == CHUNK) {
                    batchInsert(chunk);
                    total += chunk.size();
                    chunk.clear();
                }
            }
        }
        if (!chunk.isEmpty()) {
            batchInsert(chunk);
            total += chunk.size();
        }

        return new IngestResult(batchId, side, total, false);
    }

    private void batchInsert(List<Object[]> rows) {
        jdbc.batchUpdate(INSERT, rows, rows.size(), (ps, row) -> {
            ps.setLong(1,        (Long)       row[0]);
            ps.setString(2,      (String)     row[1]);
            ps.setString(3,      (String)     row[2]);
            ps.setBigDecimal(4,  (BigDecimal) row[3]);
            ps.setString(5,      (String)     row[4]);
            ps.setString(6,      (String)     row[5]);
            ps.setDate(7,        (Date)       row[6]);
            ps.setDate(8,        (Date)       row[7]);
            ps.setDate(9,        (Date)       row[8]);
            ps.setString(10,     (String)     row[9]);
        });
    }

    static Object[] toRow(long batchId, String side, JsonNode n) {
        return new Object[]{
            batchId,
            side,
            text(n, "external_ref"),
            new BigDecimal(text(n, "amount")),
            text(n, "currency"),
            textOrNull(n, "counterparty"),
            dateOrNull(n, "trade_date"),
            Date.valueOf(text(n, "value_date")),
            dateOrNull(n, "settlement_date"),
            text(n, "direction")
        };
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) throw new IllegalArgumentException("Missing required field: " + field);
        return v.asText();
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Date dateOrNull(JsonNode n, String field) {
        String s = textOrNull(n, field);
        return s == null ? null : Date.valueOf(LocalDate.parse(s));
    }
}
