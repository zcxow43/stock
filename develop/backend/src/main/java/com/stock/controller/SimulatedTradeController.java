package com.stock.controller;

import com.stock.dto.CreateSimulatedTradeRequest;
import com.stock.dto.SimulatedTradeItemDto;
import com.stock.dto.SimulatedTradeResponseDto;
import com.stock.service.SimulatedTradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET/POST/DELETE /api/simulated-trades — specs/backend/simulated-trade.md. Read-only aside from
 * the create/delete of the holding row itself: no external fetch, no `stock_sync_progress` row,
 * no concurrency lock is touched by any of the three endpoints.
 */
@RestController
@RequestMapping("/api/simulated-trades")
public class SimulatedTradeController {

    private final SimulatedTradeService simulatedTradeService;

    public SimulatedTradeController(SimulatedTradeService simulatedTradeService) {
        this.simulatedTradeService = simulatedTradeService;
    }

    @GetMapping
    public ResponseEntity<SimulatedTradeResponseDto> list() {
        return ResponseEntity.ok(simulatedTradeService.list());
    }

    @PostMapping
    public ResponseEntity<SimulatedTradeItemDto> create(@RequestBody CreateSimulatedTradeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(simulatedTradeService.create(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        simulatedTradeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
