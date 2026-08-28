package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.config.MinutePriceProperties;
import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockMinuteFetchStatus;
import com.stock.domain.StockMinutePrice;
import com.stock.dto.DailySummaryDto;
import com.stock.dto.MinuteBarDto;
import com.stock.dto.MinuteBarResponse;
import com.stock.exception.FutureTradeDateException;
import com.stock.exception.InvalidDateFormatException;
import com.stock.exception.InvalidIntervalException;
import com.stock.exception.MissingTradeDateException;
import com.stock.exception.StockNotFoundException;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockMinuteFetchStatusMapper;
import com.stock.mapper.StockMinutePriceMapper;
import com.stock.service.external.YahooFinanceClient;
import com.stock.service.external.dto.NormalizedMinuteBar;
import com.stock.service.external.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GET /api/stocks/{stockId}/minute-bars — on-demand fetch-and-cache of one stock's 1-minute bars
 * for a single trade date, aggregated to the requested interval at read time. See
 * specs/backend/stock-minute-price.md for the full decision table and normalization rules.
 */
@Service
public class MinuteBarQueryService {

    private static final Logger log = LoggerFactory.getLogger(MinuteBarQueryService.class);

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 0);
    private static final LocalTime INTRADAY_CUTOFF = LocalTime.of(14, 0);
    private static final int WINDOW_DAYS = 30;
    private static final String SOURCE_YAHOO = "YAHOO";
    private static final String API_STATUS_FETCH_FAILED = "FETCH_FAILED";
    private static final int PRICE_SCALE = 2;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final DateTimeFormatter BAR_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final StockMapper stockMapper;
    private final StockDailyPriceMapper dailyPriceMapper;
    private final StockMinutePriceMapper minutePriceMapper;
    private final StockMinuteFetchStatusMapper fetchStatusMapper;
    private final MinutePriceIngestionService ingestionService;
    private final YahooFinanceClient yahooFinanceClient;
    private final BackfillProperties backfillProperties;
    private final MinutePriceProperties minutePriceProperties;

    public MinuteBarQueryService(StockMapper stockMapper, StockDailyPriceMapper dailyPriceMapper,
                                  StockMinutePriceMapper minutePriceMapper,
                                  StockMinuteFetchStatusMapper fetchStatusMapper,
                                  MinutePriceIngestionService ingestionService,
                                  YahooFinanceClient yahooFinanceClient,
                                  BackfillProperties backfillProperties,
                                  MinutePriceProperties minutePriceProperties) {
        this.stockMapper = stockMapper;
        this.dailyPriceMapper = dailyPriceMapper;
        this.minutePriceMapper = minutePriceMapper;
        this.fetchStatusMapper = fetchStatusMapper;
        this.ingestionService = ingestionService;
        this.yahooFinanceClient = yahooFinanceClient;
        this.backfillProperties = backfillProperties;
        this.minutePriceProperties = minutePriceProperties;
    }

    public MinuteBarResponse getMinuteBars(String stockId, String tradeDateRaw, Integer intervalParam, boolean refresh) {
        int interval = intervalParam == null ? 1 : intervalParam;
        if (!InvalidIntervalException.ALLOWED.contains(interval)) {
            throw new InvalidIntervalException(interval);
        }
        if (tradeDateRaw == null || tradeDateRaw.trim().isEmpty()) {
            throw new MissingTradeDateException();
        }
        LocalDate tradeDate;
        try {
            tradeDate = LocalDate.parse(tradeDateRaw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidDateFormatException(tradeDateRaw);
        }

        LocalDate today = LocalDate.now(TAIPEI);
        if (tradeDate.isAfter(today)) {
            throw new FutureTradeDateException();
        }

        Stock stock = stockMapper.findById(stockId);
        if (stock == null) {
            throw new StockNotFoundException(stockId);
        }

        StockDailyPrice dailyPrice = dailyPriceMapper.findOne(stockId, tradeDate);
        if (dailyPrice == null) {
            fetchStatusMapper.upsertNotATradingDay(stockId, tradeDate);
            return buildResponse(stock, tradeDate, interval, StockMinuteFetchStatus.STATUS_NOT_A_TRADING_DAY,
                    null, null, null, Collections.emptyList(), null);
        }

        StockMinuteFetchStatus current = fetchStatusMapper.findOne(stockId, tradeDate);

        // The 抓取決策 table alone decides whether we would fetch at all; a date that's already
        // AVAILABLE/NO_DATA/OUT_OF_WINDOW never reaches this branch when refresh=false (see
        // shouldFetch below), so an already-fetched date remains readable forever even once its
        // trade date ages past the source's rolling window (spec: 已標記 AVAILABLE 的過往日期...
        // 仍可正常讀取，資料已永久落地) — the window check below only ever applies to a *new*
        // fetch attempt, never retroactively downgrades a resolved outcome.
        if (shouldFetch(current, tradeDate, today, refresh)) {
            LocalDate windowFloor = today.minusDays(WINDOW_DAYS);
            boolean alreadyResolved = current != null && (
                    StockMinuteFetchStatus.STATUS_AVAILABLE.equals(current.getStatus())
                            || StockMinuteFetchStatus.STATUS_NO_DATA.equals(current.getStatus())
                            || StockMinuteFetchStatus.STATUS_OUT_OF_WINDOW.equals(current.getStatus()));
            if (tradeDate.isBefore(windowFloor)) {
                // Out-of-window is a purely local, permanent determination: it can only ever get
                // more out-of-window as time passes, so no request is ever worth making for it,
                // refresh=true included (spec: 超窗判斷在本地完成，不發請求). If we already have a
                // resolved outcome for this date (typically refresh=true on old AVAILABLE data),
                // leave it untouched instead of clobbering good data with OUT_OF_WINDOW.
                if (!alreadyResolved) {
                    fetchStatusMapper.upsertOutOfWindow(stockId, tradeDate);
                    current = freshStatus(stockId, tradeDate, StockMinuteFetchStatus.STATUS_OUT_OF_WINDOW);
                }
            } else {
                current = doFetch(stock, tradeDate, current);
            }
        }

        List<MinuteBarDto> bars = StockMinuteFetchStatus.STATUS_AVAILABLE.equals(current.getStatus())
                ? aggregate(minutePriceMapper.findByStockAndDate(stockId, tradeDate), interval)
                : Collections.emptyList();

        String message = StockMinuteFetchStatus.STATUS_FAILED.equals(current.getStatus()) ? current.getLastError() : null;

        return buildResponse(stock, tradeDate, interval, apiStatus(current.getStatus()),
                current.getSource(), current.getFetchedAt(), toDailySummary(dailyPrice), bars, message);
    }

    /** See specs/backend/stock-minute-price.md, 抓取決策 — everything except the local out-of-window check above. */
    private boolean shouldFetch(StockMinuteFetchStatus current, LocalDate tradeDate, LocalDate today, boolean refresh) {
        if (refresh) {
            return true;
        }
        if (current == null) {
            return true;
        }
        switch (current.getStatus()) {
            case StockMinuteFetchStatus.STATUS_AVAILABLE:
                if (!tradeDate.isEqual(today)) {
                    return false;
                }
                LocalDateTime cutoff = today.atTime(INTRADAY_CUTOFF);
                return current.getFetchedAt() == null || current.getFetchedAt().isBefore(cutoff);
            case StockMinuteFetchStatus.STATUS_FAILED:
                return current.getAttemptCount() < minutePriceProperties.getMaxAttemptCount();
            case StockMinuteFetchStatus.STATUS_NO_DATA:
            case StockMinuteFetchStatus.STATUS_OUT_OF_WINDOW:
            case StockMinuteFetchStatus.STATUS_NOT_A_TRADING_DAY:
            default:
                return false;
        }
    }

    private StockMinuteFetchStatus doFetch(Stock stock, LocalDate tradeDate, StockMinuteFetchStatus previous) {
        String stockId = stock.getStockId();
        try {
            List<NormalizedMinuteBar> bars = fetchWithRetry(stockId, stock.getMarket(), tradeDate);
            LocalDateTime fetchedAt = LocalDateTime.now(TAIPEI);
            ingestionService.applyFetchResult(stockId, tradeDate, bars, SOURCE_YAHOO, fetchedAt);

            StockMinuteFetchStatus result = new StockMinuteFetchStatus();
            result.setStockId(stockId);
            result.setTradeDate(tradeDate);
            result.setStatus(bars.isEmpty() ? StockMinuteFetchStatus.STATUS_NO_DATA : StockMinuteFetchStatus.STATUS_AVAILABLE);
            result.setBarCount(bars.size());
            result.setSource(SOURCE_YAHOO);
            result.setAttemptCount(0);
            result.setFetchedAt(fetchedAt);
            return result;
        } catch (Exception e) {
            log.warn("Minute bar fetch failed for stock {} on {}", stockId, tradeDate, e);
            String message = truncate(e.getMessage());
            fetchStatusMapper.markFailed(stockId, tradeDate, message);

            StockMinuteFetchStatus result = new StockMinuteFetchStatus();
            result.setStockId(stockId);
            result.setTradeDate(tradeDate);
            result.setStatus(StockMinuteFetchStatus.STATUS_FAILED);
            result.setBarCount(0);
            result.setSource(previous != null ? previous.getSource() : null);
            result.setAttemptCount((previous != null ? previous.getAttemptCount() : 0) + 1);
            result.setLastError(message);
            result.setFetchedAt(previous != null ? previous.getFetchedAt() : null);
            return result;
        }
    }

    /** Same request-pacing/backoff as stock-price-ingestion's backfill path (spec: 速率控制沿用既有機制). */
    private List<NormalizedMinuteBar> fetchWithRetry(String stockId, String market, LocalDate tradeDate) {
        BackfillProperties.RateLimit rateLimit = backfillProperties.getRateLimit();
        long backoffMs = rateLimit.getInitialBackoffMs();
        int attempt = 0;
        while (true) {
            try {
                return yahooFinanceClient.fetchMinuteBars(stockId, market, tradeDate);
            } catch (RateLimitedException e) {
                attempt++;
                if (attempt > rateLimit.getMaxRetries()) {
                    throw e;
                }
                sleep(backoffMs);
                backoffMs *= rateLimit.getBackoffMultiplier();
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Minute bar fetch interrupted", e);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    private StockMinuteFetchStatus freshStatus(String stockId, LocalDate tradeDate, String status) {
        StockMinuteFetchStatus result = new StockMinuteFetchStatus();
        result.setStockId(stockId);
        result.setTradeDate(tradeDate);
        result.setStatus(status);
        return result;
    }

    private String apiStatus(String dbStatus) {
        return StockMinuteFetchStatus.STATUS_FAILED.equals(dbStatus) ? API_STATUS_FETCH_FAILED : dbStatus;
    }

    /**
     * Aggregates stored 1-minute bars into `interval`-minute groups, anchored at 09:00 (not at the
     * first traded bar), so the same interval lines up on the same grid across stocks/dates — see
     * specs/backend/stock-minute-price.md, 週期聚合. Groups with zero source bars are simply absent.
     */
    private List<MinuteBarDto> aggregate(List<StockMinutePrice> oneMinuteBars, int interval) {
        if (oneMinuteBars.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Integer, List<StockMinutePrice>> groups = new TreeMap<>();
        for (StockMinutePrice bar : oneMinuteBars) {
            int minutesFromOpen = (int) Duration.between(SESSION_OPEN, bar.getBarTime()).toMinutes();
            int groupIndex = Math.floorDiv(minutesFromOpen, interval);
            groups.computeIfAbsent(groupIndex, k -> new ArrayList<>()).add(bar);
        }

        List<MinuteBarDto> result = new ArrayList<>(groups.size());
        for (Map.Entry<Integer, List<StockMinutePrice>> entry : groups.entrySet()) {
            List<StockMinutePrice> group = entry.getValue();
            LocalTime groupStart = SESSION_OPEN.plusMinutes((long) entry.getKey() * interval);

            BigDecimal open = group.get(0).getOpenPrice();
            BigDecimal close = group.get(group.size() - 1).getClosePrice();
            BigDecimal high = null;
            BigDecimal low = null;
            long volume = 0;
            for (StockMinutePrice bar : group) {
                if (high == null || bar.getHighPrice().compareTo(high) > 0) {
                    high = bar.getHighPrice();
                }
                if (low == null || bar.getLowPrice().compareTo(low) < 0) {
                    low = bar.getLowPrice();
                }
                volume += bar.getVolume();
            }

            result.add(new MinuteBarDto(groupStart.format(BAR_TIME_FORMAT), open, high, low, close, volume));
        }
        return result;
    }

    private DailySummaryDto toDailySummary(StockDailyPrice price) {
        return new DailySummaryDto(scale(price.getOpenPrice()), scale(price.getHighPrice()),
                scale(price.getLowPrice()), scale(price.getClosePrice()), price.getVolume());
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private MinuteBarResponse buildResponse(Stock stock, LocalDate tradeDate, int interval, String dataStatus,
                                             String source, LocalDateTime fetchedAt, DailySummaryDto dailySummary,
                                             List<MinuteBarDto> bars, String message) {
        MinuteBarResponse response = new MinuteBarResponse();
        response.setStockId(stock.getStockId());
        response.setStockName(stock.getStockName());
        response.setTradeDate(tradeDate);
        response.setInterval(interval);
        response.setDataStatus(dataStatus);
        response.setSource(source);
        response.setFetchedAt(fetchedAt);
        response.setBarCount(bars.size());
        response.setDailySummary(dailySummary);
        response.setBars(bars);
        response.setMessage(message);
        return response;
    }
}
