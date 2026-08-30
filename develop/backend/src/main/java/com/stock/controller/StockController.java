package com.stock.controller;

import com.stock.dto.CreateStockRequest;
import com.stock.dto.StockDeleteResponse;
import com.stock.dto.StockDetailDto;
import com.stock.dto.StockListResponse;
import com.stock.dto.UpdateStockRequest;
import com.stock.service.StockCatalogService;
import com.stock.service.StockQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock catalog: list/search and single-stock detail (read-only, backed by {@link StockQueryService}),
 * plus create/update/soft-delete of the stock master (backed by {@link StockCatalogService}).
 */
@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockQueryService stockQueryService;
    private final StockCatalogService stockCatalogService;

    public StockController(StockQueryService stockQueryService, StockCatalogService stockCatalogService) {
        this.stockQueryService = stockQueryService;
        this.stockCatalogService = stockCatalogService;
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

    @PostMapping
    public ResponseEntity<StockDetailDto> create(@RequestBody CreateStockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockCatalogService.createStock(request));
    }

    @PutMapping("/{stockId}")
    public ResponseEntity<StockDetailDto> update(@PathVariable String stockId,
                                                  @RequestBody UpdateStockRequest request) {
        return ResponseEntity.ok(stockCatalogService.updateStock(stockId, request));
    }

    @DeleteMapping("/{stockId}")
    public ResponseEntity<StockDeleteResponse> delete(@PathVariable String stockId) {
        return ResponseEntity.ok(stockCatalogService.deactivateStock(stockId));
    }
}
