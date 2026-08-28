package com.stock.controller;

import com.stock.dto.IndicatorRebuildRequest;
import com.stock.dto.IndicatorRebuildResponse;
import com.stock.service.IndicatorRebuildService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** MACD/KD indicator computation: POST /api/stocks/indicators/rebuild (full rebuild or daily increment). */
@RestController
public class IndicatorController {

    private final IndicatorRebuildService indicatorRebuildService;

    public IndicatorController(IndicatorRebuildService indicatorRebuildService) {
        this.indicatorRebuildService = indicatorRebuildService;
    }

    @PostMapping("/api/stocks/indicators/rebuild")
    public ResponseEntity<IndicatorRebuildResponse> rebuild(
            @RequestBody(required = false) IndicatorRebuildRequest request) {
        IndicatorRebuildRequest effective = request != null ? request : new IndicatorRebuildRequest();
        IndicatorRebuildResponse response = indicatorRebuildService.rebuild(effective);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
