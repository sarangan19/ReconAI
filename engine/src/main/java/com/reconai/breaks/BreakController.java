package com.reconai.breaks;

import com.reconai.matching.MatchingService;
import com.reconai.matching.ReconcileResult;
import com.reconai.recon.repository.BatchRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class BreakController {

    private final MatchingService matching;
    private final BreakRepository breaks;
    private final BatchRepository batches;
    private final JdbcTemplate jdbc;

    public BreakController(MatchingService matching, BreakRepository breaks,
                           BatchRepository batches, JdbcTemplate jdbc) {
        this.matching = matching;
        this.breaks   = breaks;
        this.batches  = batches;
        this.jdbc     = jdbc;
    }

    // ── POST /api/batches/{id}/reconcile ──────────────────────────────────
    @PostMapping("/api/batches/{id}/reconcile")
    public ResponseEntity<ReconcileResult> reconcile(@PathVariable long id) {
        ReconcileResult result = matching.reconcile(id);
        return ResponseEntity.ok(result);
    }

    // ── GET /api/breaks?batchId=&status=&type=&page=&size= ────────────────
    @GetMapping("/api/breaks")
    public ResponseEntity<Page<ReconBreak>> listBreaks(
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<ReconBreak> result;

        if (batchId != null && status != null) {
            result = breaks.findByBatchIdAndStatus(batchId, status, pageable);
        } else if (batchId != null && type != null) {
            result = breaks.findByBatchIdAndDetectedType(batchId, type, pageable);
        } else if (batchId != null) {
            result = breaks.findByBatchId(batchId, pageable);
        } else {
            result = breaks.findAll(pageable);
        }
        return ResponseEntity.ok(result);
    }

    // ── GET /api/breaks/{id} ──────────────────────────────────────────────
    @GetMapping("/api/breaks/{id}")
    public ResponseEntity<Map<String, Object>> getBreak(@PathVariable long id) {
        var brk = breaks.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Break not found: " + id));

        // Fetch associated txn IDs
        List<Long> txnIds = jdbc.queryForList(
            "SELECT txn_id FROM break_txn WHERE break_id = ?", Long.class, id);

        return ResponseEntity.ok(Map.of(
            "id",                  brk.getId(),
            "batchId",             brk.getBatchId(),
            "detectedType",        String.valueOf(brk.getDetectedType()),
            "detectedConfidence",  brk.getDetectedConfidence(),
            "status",              brk.getStatus(),
            "createdAt",           brk.getCreatedAt(),
            "txnIds",              txnIds
        ));
    }

    // ── Exception handlers ────────────────────────────────────────────────
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(EntityNotFoundException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(Map.of("error", ex.getMessage()));
    }
}
