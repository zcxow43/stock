package com.stock.controller;

import com.stock.dto.MinuteBarResponse;
import com.stock.service.MinuteBarQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** On-demand, permanently-cached minute-bar chart data: GET /api/stocks/{stockId}/minute-bars. */
@RestController
public class MinuteBarController {

    private final MinuteBarQueryService minuteBarQueryService;

    public MinuteBarController(MinuteBarQueryService minuteBarQueryService) {
        this.minuteBarQueryService = minuteBarQueryService;
    }

    @GetMapping("/api/stocks/{stockId}/minute-bars")
    public ResponseEntity<MinuteBarResponse> getMinuteBars(
            @PathVariable String stockId,
            @RequestParam(required = false) String tradeDate,
            @RequestParam(required = false) Integer interval,
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(minuteBarQueryService.getMinuteBars(stockId, tradeDate, interval, refresh));
    }
}
