package com.stock.service;

import com.stock.domain.PriceStats;
import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.dto.StockDetailDto;
import com.stock.dto.StockListItemDto;
import com.stock.dto.StockListResponse;
import com.stock.exception.InvalidMarketException;
import com.stock.exception.InvalidPaginationException;
import com.stock.exception.InvalidSortFieldException;
import com.stock.exception.PageSizeExceededException;
import com.stock.exception.StockNotFoundException;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import com.stock.util.CommonStockCodeUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only stock catalog: list/search of the stock master plus its latest quote, and single-stock
 * detail. Never writes stock or stock_daily_price — that belongs to stock-price-ingestion.
 */
@Service
public class StockQueryService {

    public static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 50;
    private static final String DEFAULT_SORT = "stockId";
    private static final String DEFAULT_ORDER = "asc";

    private static final Set<String> ALLOWED_MARKETS = new LinkedHashSet<>(Arrays.asList("TSE", "OTC"));

    private static final Map<String, String> SORT_COLUMNS = new LinkedHashMap<>();
    static {
        SORT_COLUMNS.put("stockId", "stock_id");
        SORT_COLUMNS.put("stockName", "stock_name");
        SORT_COLUMNS.put("market", "market");
    }

    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;

    public StockQueryService(StockMapper stockMapper, StockDailyPriceMapper priceMapper) {
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
    }

    public StockListResponse listStocks(String keyword, String market, boolean includeInactive,
                                         boolean commonStocksOnly,
                                         Integer page, Integer size, String sort, String order) {
        int pageValue = page == null ? DEFAULT_PAGE : page;
        int sizeValue = size == null ? DEFAULT_SIZE : size;
        String sortValue = (sort == null || sort.isEmpty()) ? DEFAULT_SORT : sort;
        String orderValue = (order == null || order.isEmpty()) ? DEFAULT_ORDER : order;

        if (sizeValue > MAX_PAGE_SIZE) {
            throw new PageSizeExceededException(MAX_PAGE_SIZE);
        }
        if (pageValue < 1 || sizeValue < 1) {
            throw new InvalidPaginationException();
        }

        String trimmedMarket = (market == null || market.trim().isEmpty()) ? null : market.trim();
        if (trimmedMarket != null && !ALLOWED_MARKETS.contains(trimmedMarket)) {
            throw new InvalidMarketException(trimmedMarket);
        }

        String sortColumn = SORT_COLUMNS.get(sortValue);
        if (sortColumn == null) {
            throw new InvalidSortFieldException(new ArrayList<>(SORT_COLUMNS.keySet()));
        }
        String orderDirection = "desc".equalsIgnoreCase(orderValue) ? "DESC" : "ASC";

        String trimmedKeyword = (keyword == null || keyword.trim().isEmpty()) ? null : keyword.trim();
        // commonStocksOnly defaults to true and is applied entirely inside the same paged `stock`
        // query as market/keyword/includeInactive — never as a post-fetch in-memory filter, which
        // would break the "先分頁、再取行情" cost bound the rest of this method relies on.
        String commonStockRegex = commonStocksOnly ? CommonStockCodeUtil.REGEX : null;

        long total = stockMapper.countPage(trimmedKeyword, trimmedMarket, includeInactive, commonStockRegex);

        List<Stock> stocks;
        if (total == 0) {
            stocks = Collections.emptyList();
        } else {
            int offset = (pageValue - 1) * sizeValue;
            stocks = stockMapper.findPage(trimmedKeyword, trimmedMarket, includeInactive, commonStockRegex,
                    sortColumn, orderDirection, sizeValue, offset);
        }

        Map<String, List<StockDailyPrice>> priceMap = loadLatestPrices(stocks);

        List<StockListItemDto> items = new ArrayList<>();
        for (Stock stock : stocks) {
            StockListItemDto dto = new StockListItemDto();
            dto.setStockId(stock.getStockId());
            dto.setStockName(stock.getStockName());
            dto.setMarket(stock.getMarket());
            dto.setIsActive(stock.getActive());
            applyPriceFields(dto, priceMap.get(stock.getStockId()));
            items.add(dto);
        }

        int totalPages = (int) Math.ceil(total / (double) sizeValue);
        return new StockListResponse(pageValue, sizeValue, total, totalPages, items);
    }

    public StockDetailDto getStockDetail(String stockId) {
        Stock stock = stockMapper.findById(stockId);
        if (stock == null) {
            throw new StockNotFoundException(stockId);
        }

        StockDetailDto dto = new StockDetailDto();
        dto.setStockId(stock.getStockId());
        dto.setStockName(stock.getStockName());
        dto.setMarket(stock.getMarket());
        dto.setIsActive(stock.getActive());

        applyPriceFields(dto, priceMapper.findLatestTwoByStockId(stockId));

        PriceStats stats = priceMapper.findPriceStats(stockId);
        dto.setFirstTradeDate(stats.getFirstTradeDate());
        dto.setTradingDayCount(stats.getTradingDayCount());

        return dto;
    }

    /** Batch-loads the latest two trade-date rows per stock id; bounded by the caller's page, not the universe. */
    private Map<String, List<StockDailyPrice>> loadLatestPrices(List<Stock> stocks) {
        if (stocks.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> stockIds = stocks.stream().map(Stock::getStockId).collect(Collectors.toList());
        List<StockDailyPrice> rows = priceMapper.findLatestTwoByStockIds(stockIds);

        Map<String, List<StockDailyPrice>> map = new LinkedHashMap<>();
        for (StockDailyPrice row : rows) {
            map.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    /** rows must be latest-first, at most 2 entries; leaves all quote fields null when rows is empty/null. */
    private void applyPriceFields(StockListItemDto dto, List<StockDailyPrice> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        StockDailyPrice latest = rows.get(0);
        dto.setLatestTradeDate(latest.getTradeDate());
        dto.setLatestClose(latest.getClosePrice());
        dto.setLatestVolume(latest.getVolume());

        if (rows.size() < 2) {
            return;
        }
        StockDailyPrice previous = rows.get(1);
        BigDecimal previousClose = previous.getClosePrice();
        dto.setPreviousClose(previousClose);

        BigDecimal changeAmount = latest.getClosePrice().subtract(previousClose).setScale(2, RoundingMode.HALF_UP);
        dto.setChangeAmount(changeAmount);

        if (previousClose.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal changePercent = changeAmount
                    .divide(previousClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            dto.setChangePercent(changePercent);
        }
    }
}
