package com.reconai.recon.api;

import com.reconai.ingest.IngestionService;
import com.reconai.recon.domain.Batch;
import com.reconai.recon.domain.TxnSide;
import com.reconai.recon.repository.BatchRepository;
import com.reconai.recon.repository.CanonicalTxnRepository;
import com.reconai.simulator.InjectionRates;
import com.reconai.simulator.SimulatorResult;
import com.reconai.simulator.SimulatorService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchRepository batches;
    private final CanonicalTxnRepository ctxnRepo;
    private final SimulatorService simulator;
    private final IngestionService ingestion;

    public BatchController(BatchRepository batches,
                           CanonicalTxnRepository ctxnRepo,
                           SimulatorService simulator,
                           IngestionService ingestion) {
        this.batches   = batches;
        this.ctxnRepo  = ctxnRepo;
        this.simulator = simulator;
        this.ingestion = ingestion;
    }

    // ── POST /api/batches ─────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<BatchResponse> createBatch(@RequestBody CreateBatchRequest req) {
        var batch = batches.save(new Batch(req.name()));
        return ResponseEntity
            .created(URI.create("/api/batches/" + batch.getId()))
            .body(toBatchResponse(batch));
    }

    // ── POST /api/batches/simulate?n=100000&seed=42 ───────────────────────
    @PostMapping("/simulate")
    public ResponseEntity<SimulatorResult> simulate(
            @RequestParam int n,
            @RequestParam(defaultValue = "42") long seed,
            @RequestParam(required = false) String name) {

        if (n < 1) return ResponseEntity.badRequest().build();
        String batchName = name != null ? name : "batch-seed" + seed + "-n" + n;
        var batch = batches.save(new Batch(batchName));
        SimulatorResult result = simulator.simulate(batch.getId(), n, seed, InjectionRates.defaults());
        return ResponseEntity.ok(result);
    }

    // ── POST /api/batches/{id}/ingest?side=INTERNAL ───────────────────────
    @PostMapping("/{id}/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @PathVariable long id,
            @RequestParam String side,
            jakarta.servlet.http.HttpServletRequest request) throws IOException {

        TxnSide txnSide;
        try {
            txnSide = TxnSide.valueOf(side.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "side must be INTERNAL or EXTERNAL"));
        }
        if (!batches.existsById(id)) {
            throw new EntityNotFoundException("Batch not found: " + id);
        }
        var result = ingestion.ingest(id, txnSide.name(), request.getInputStream());
        return ResponseEntity.ok(Map.of(
            "batchId",       result.batchId(),
            "side",          result.side(),
            "rowsInserted",  result.rowsInserted(),
            "wasIdempotent", result.wasIdempotent()
        ));
    }

    // ── GET /api/batches/{id}/summary ─────────────────────────────────────
    @GetMapping("/{id}/summary")
    public ResponseEntity<Map<String, Object>> summary(@PathVariable long id) {
        var batch = batches.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Batch not found: " + id));
        long internal = ctxnRepo.countByBatchIdAndSide(id, TxnSide.INTERNAL);
        long external = ctxnRepo.countByBatchIdAndSide(id, TxnSide.EXTERNAL);
        return ResponseEntity.ok(Map.of(
            "batchId",       id,
            "name",          batch.getName(),
            "status",        batch.getStatus(),
            "internalCount", internal,
            "externalCount", external,
            "totalCount",    internal + external
        ));
    }

    // ── GET /api/batches/{id} ─────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<BatchResponse> getBatch(@PathVariable long id) {
        return batches.findById(id)
            .map(b -> ResponseEntity.ok(toBatchResponse(b)))
            .orElseThrow(() -> new EntityNotFoundException("Batch not found: " + id));
    }

    // ── exception handlers ────────────────────────────────────────────────

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

    // ── helpers ───────────────────────────────────────────────────────────

    private BatchResponse toBatchResponse(Batch b) {
        return new BatchResponse(b.getId(), b.getName(), b.getStatus().name(), b.getCreatedAt().toString());
    }

    public record CreateBatchRequest(String name) {}

    public record BatchResponse(Long id, String name, String status, String createdAt) {}
}
