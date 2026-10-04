package com.stock.controller;

import com.stock.dto.CreateRealTradeRequest;
import com.stock.dto.RealTradeItemDto;
import com.stock.dto.RealTradeResponseDto;
import com.stock.dto.UpdateRealTradeRequest;
import com.stock.service.RealTradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET/POST/PATCH/DELETE /api/real-trades — specs/backend/real-trade.md. Positions live in
 * data/real-trades.csv; no endpoint here uploads, imports or parses images.
 */
@RestController
@RequestMapping("/api/real-trades")
public class RealTradeController {

    private final RealTradeService realTradeService;

    public RealTradeController(RealTradeService realTradeService) {
        this.realTradeService = realTradeService;
    }

    @GetMapping
    public ResponseEntity<RealTradeResponseDto> list() {
        return ResponseEntity.ok(realTradeService.list());
    }

    @PostMapping
    public ResponseEntity<RealTradeItemDto> create(@RequestBody CreateRealTradeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(realTradeService.create(request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<RealTradeItemDto> update(@PathVariable String id,
                                                   @RequestBody(required = false) UpdateRealTradeRequest request) {
        return ResponseEntity.ok(realTradeService.update(id, request));
    }

    // The id is taken as a String so a non-numeric segment is the spec's 404, not a type-mismatch 400.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        realTradeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
