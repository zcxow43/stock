package com.stock.controller;

import com.stock.dto.StockDetailDto;
import com.stock.dto.StockListResponse;
import com.stock.service.StockQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only stock catalog: list/search (`GET /api/stocks`) and single-stock detail. */
@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockQueryService stockQueryService;

    public StockController(StockQueryService stockQueryService) {
        this.stockQueryService = stockQueryService;
    }

    @GetMapping
    public ResponseEntity<StockListResponse> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String market,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        return ResponseEntity.ok(
                stockQueryService.listStocks(keyword, market, includeInactive, page, size, sort, order));
    }

    @GetMapping("/{stockId}")
    public ResponseEntity<StockDetailDto> detail(@PathVariable String stockId) {
        return ResponseEntity.ok(stockQueryService.getStockDetail(stockId));
    }
}
