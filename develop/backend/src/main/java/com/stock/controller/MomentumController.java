package com.stock.controller;

import com.stock.dto.MomentumGainResponseDto;
import com.stock.service.MomentumGainService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * GET /api/momentum/gain — industry gain ranking (specs/backend/industry-gain-ranking.md).
 * Read-only: reports gain statistics, never a buy/sell recommendation.
 */
@RestController
public class MomentumController {

    private final MomentumGainService momentumGainService;

    public MomentumController(MomentumGainService momentumGainService) {
        this.momentumGainService = momentumGainService;
    }

    @GetMapping("/api/momentum/gain")
    public ResponseEntity<MomentumGainResponseDto> getGain(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "5") BigDecimal minGain,
            @RequestParam(required = false, defaultValue = "true") boolean commonStocksOnly,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(momentumGainService.getGain(metric, mode, days, startDate, endDate, minGain,
                commonStocksOnly, sort));
    }
}
