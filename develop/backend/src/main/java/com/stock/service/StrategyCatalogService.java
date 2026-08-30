package com.stock.service;

import com.stock.dto.StrategyCatalogResponseDto;
import com.stock.dto.StrategyDto;
import com.stock.service.pattern.PatternDetector;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * GET /api/strategies — the pattern + sensitivity-preset catalogue. Descriptions live here (each
 * {@link PatternDetector}'s own preset table), never duplicated on the frontend — see
 * specs/backend/strategy-scan.md.
 */
@Service
public class StrategyCatalogService {

    private final List<PatternDetector> detectors;

    public StrategyCatalogService(List<PatternDetector> detectors) {
        this.detectors = detectors;
    }

    public StrategyCatalogResponseDto getCatalog() {
        List<StrategyDto> strategies = new ArrayList<>();
        for (PatternDetector detector : detectors) {
            strategies.add(new StrategyDto(detector.getCode(), detector.getName(), detector.getPresets()));
        }
        return new StrategyCatalogResponseDto(strategies);
    }
}
