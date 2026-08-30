package com.stock.service;

import com.stock.domain.Stock;
import com.stock.dto.CreateStockRequest;
import com.stock.dto.StockDeleteResponse;
import com.stock.dto.StockDetailDto;
import com.stock.dto.UpdateStockRequest;
import com.stock.exception.InvalidMarketException;
import com.stock.exception.InvalidStockPayloadException;
import com.stock.exception.StockAlreadyExistsException;
import com.stock.exception.StockNotFoundException;
import com.stock.mapper.StockMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Write side of the stock master: create / update / soft-delete. Kept separate from
 * {@link StockQueryService}, which is documented read-only and must stay that way.
 *
 * Never issues a hard DELETE against `stock` — specs/dba/stock.md requires a delisted stock's
 * stock_daily_price/stock_daily_indicator history to survive, so the only supported "removal" is
 * flipping is_active to false.
 */
@Service
public class StockCatalogService {

    private static final int MAX_STOCK_ID_LENGTH = 10;
    private static final int MAX_STOCK_NAME_LENGTH = 60;

    private static final Set<String> ALLOWED_MARKETS = new LinkedHashSet<>(Arrays.asList("TSE", "OTC"));

    private final StockMapper stockMapper;
    private final StockQueryService stockQueryService;

    public StockCatalogService(StockMapper stockMapper, StockQueryService stockQueryService) {
        this.stockMapper = stockMapper;
        this.stockQueryService = stockQueryService;
    }

    @Transactional
    public StockDetailDto createStock(CreateStockRequest request) {
        String stockId = trim(request == null ? null : request.getStockId());
        String stockName = trim(request == null ? null : request.getStockName());

        List<String> invalidFields = new ArrayList<>();
        if (isBlankOrTooLong(stockId, MAX_STOCK_ID_LENGTH)) {
            invalidFields.add("stockId");
        }
        if (isBlankOrTooLong(stockName, MAX_STOCK_NAME_LENGTH)) {
            invalidFields.add("stockName");
        }
        if (!invalidFields.isEmpty()) {
            throw new InvalidStockPayloadException(invalidFields);
        }

        String market = trim(request.getMarket());
        if (market == null || !ALLOWED_MARKETS.contains(market)) {
            throw new InvalidMarketException(market);
        }

        if (stockMapper.findById(stockId) != null) {
            throw new StockAlreadyExistsException(stockId);
        }

        try {
            stockMapper.insert(new Stock(stockId, stockName, market, Boolean.TRUE));
        } catch (DuplicateKeyException e) {
            // Closes the check-then-act race: two concurrent POSTs for the same stockId can both
            // pass the findById check above; the loser's INSERT then hits the primary key
            // constraint. Translate that into the same 409 the pre-check produces, rather than
            // letting a raw DB constraint violation fall through to the generic 500 handler.
            throw new StockAlreadyExistsException(stockId);
        }

        return stockQueryService.getStockDetail(stockId);
    }

    @Transactional
    public StockDetailDto updateStock(String stockId, UpdateStockRequest request) {
        String stockName = trim(request == null ? null : request.getStockName());
        Boolean isActive = request == null ? null : request.getIsActive();

        List<String> invalidFields = new ArrayList<>();
        if (isBlankOrTooLong(stockName, MAX_STOCK_NAME_LENGTH)) {
            invalidFields.add("stockName");
        }
        if (isActive == null) {
            invalidFields.add("isActive");
        }
        if (!invalidFields.isEmpty()) {
            throw new InvalidStockPayloadException(invalidFields);
        }

        String market = trim(request.getMarket());
        if (market == null || !ALLOWED_MARKETS.contains(market)) {
            throw new InvalidMarketException(market);
        }

        if (stockMapper.findById(stockId) == null) {
            throw new StockNotFoundException(stockId);
        }

        stockMapper.update(new Stock(stockId, stockName, market, isActive));

        return stockQueryService.getStockDetail(stockId);
    }

    @Transactional
    public StockDeleteResponse deactivateStock(String stockId) {
        Stock existing = stockMapper.findById(stockId);
        if (existing == null) {
            throw new StockNotFoundException(stockId);
        }

        if (Boolean.TRUE.equals(existing.getActive())) {
            stockMapper.update(new Stock(existing.getStockId(), existing.getStockName(), existing.getMarket(), Boolean.FALSE));
        }
        // Already inactive: idempotent no-op, per spec — still returns 200, not treated as an error.

        return new StockDeleteResponse(existing.getStockId(), existing.getStockName(), Boolean.FALSE);
    }

    private static boolean isBlankOrTooLong(String value, int maxLength) {
        return value == null || value.isEmpty() || value.length() > maxLength;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
