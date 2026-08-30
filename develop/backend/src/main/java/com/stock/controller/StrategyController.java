package com.stock.controller;

import com.stock.dto.ScanRequestDto;
import com.stock.dto.ScanResponseDto;
import com.stock.dto.StrategyCatalogResponseDto;
import com.stock.service.StrategyCatalogService;
import com.stock.service.StrategyScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/strategies (catalogue) and POST /api/strategies/scan (read-only pattern detection). */
@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyCatalogService catalogService;
    private final StrategyScanService scanService;

    public StrategyController(StrategyCatalogService catalogService, StrategyScanService scanService) {
        this.catalogService = catalogService;
        this.scanService = scanService;
    }

    @GetMapping
    public ResponseEntity<StrategyCatalogResponseDto> getCatalog() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }

    @PostMapping("/scan")
    public ResponseEntity<ScanResponseDto> scan(@RequestBody ScanRequestDto request) {
        return ResponseEntity.ok(scanService.scan(request));
    }
}
