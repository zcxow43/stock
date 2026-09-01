package com.stock.controller;

import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import com.stock.dto.DailySyncRequest;
import com.stock.dto.DailySyncResponse;
import com.stock.dto.ProgressResponse;
import com.stock.dto.UniverseImportResponse;
import com.stock.service.StockSyncService;
import com.stock.service.StockUniverseImportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
public class StockSyncController {

    private final StockSyncService stockSyncService;
    private final StockUniverseImportService stockUniverseImportService;

    public StockSyncController(StockSyncService stockSyncService,
                                StockUniverseImportService stockUniverseImportService) {
        this.stockSyncService = stockSyncService;
        this.stockUniverseImportService = stockUniverseImportService;
    }

    @PostMapping("/api/stocks/sync/daily")
    public ResponseEntity<DailySyncResponse> syncDaily(
            @RequestBody(required = false) DailySyncRequest request) {
        // tradeDate is currently informational only: TWSE's whole-market snapshot endpoint
        // always returns its own latest trading-day data (see TwseClient); the actual
        // trade date used is taken from that response.
        return ResponseEntity.ok(stockSyncService.syncDaily());
    }

    @PostMapping("/api/stocks/sync/backfill")
    public ResponseEntity<BackfillResponse> backfill(@Valid @RequestBody BackfillRequest request) {
        BackfillResponse response = stockSyncService.startBackfill(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/api/stocks/universe/import")
    public ResponseEntity<UniverseImportResponse> importUniverse() {
        return ResponseEntity.ok(stockUniverseImportService.importUniverse());
    }

    @GetMapping("/api/stocks/sync/progress")
    public ResponseEntity<ProgressResponse> progress(@RequestParam String jobType) {
        return ResponseEntity.ok(stockSyncService.getProgress(jobType));
    }
}
