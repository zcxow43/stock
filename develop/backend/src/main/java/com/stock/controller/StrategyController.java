package com.stock.controller;

import com.stock.dto.BacktestRequestDto;
import com.stock.dto.BacktestResponseDto;
import com.stock.dto.ScanRequestDto;
import com.stock.dto.ScanResponseDto;
import com.stock.dto.StrategyCatalogResponseDto;
import com.stock.service.StrategyBacktestService;
import com.stock.service.StrategyCatalogService;
import com.stock.service.StrategyScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/strategies (catalogue), POST /api/strategies/scan (read-only pattern detection), and
 * POST /api/strategies/backtest (read-only, stateless "signal-day close in, highest open out"
 * recomputation over a scan's hits — specs/backend/strategy-backtest.md).
 */
@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyCatalogService catalogService;
    private final StrategyScanService scanService;
    private final StrategyBacktestService backtestService;

    public StrategyController(StrategyCatalogService catalogService, StrategyScanService scanService,
                               StrategyBacktestService backtestService) {
        this.catalogService = catalogService;
        this.scanService = scanService;
        this.backtestService = backtestService;
    }

    @GetMapping
    public ResponseEntity<StrategyCatalogResponseDto> getCatalog() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }

    @PostMapping("/scan")
    public ResponseEntity<ScanResponseDto> scan(@RequestBody ScanRequestDto request) {
        return ResponseEntity.ok(scanService.scan(request));
    }

    @PostMapping("/backtest")
    public ResponseEntity<BacktestResponseDto> backtest(@RequestBody BacktestRequestDto request) {
        return ResponseEntity.ok(backtestService.backtest(request));
    }
}
