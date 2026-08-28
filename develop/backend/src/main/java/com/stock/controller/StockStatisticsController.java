package com.stock.controller;

import com.stock.dto.StatisticsResponseDto;
import com.stock.service.StockStatisticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** GET /api/stocks/statistics — two-month (default) price + MACD/KD interval statistics. */
@RestController
public class StockStatisticsController {

    private final StockStatisticsService statisticsService;

    public StockStatisticsController(StockStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/api/stocks/statistics")
    public ResponseEntity<StatisticsResponseDto> getStatistics(
            @RequestParam(required = false) String stockIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Boolean includeSeries) {
        return ResponseEntity.ok(statisticsService.getStatistics(stockIds, startDate, endDate, includeSeries));
    }
}
