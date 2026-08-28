package com.stock.service;

import com.stock.domain.StockMinutePrice;
import com.stock.mapper.StockMinuteFetchStatusMapper;
import com.stock.mapper.StockMinutePriceMapper;
import com.stock.service.external.dto.NormalizedMinuteBar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Owns every write into `stock_minute_price` and `stock_minute_fetch_status` for a single
 * (stock, tradeDate) fetch outcome. Bars and status are written in one transaction so a crash
 * mid-write never leaves "status = AVAILABLE but bars missing" or vice versa — see
 * specs/backend/stock-minute-price.md, 正規化規則 / 與 stock_minute_price 的一致性.
 */
@Service
public class MinutePriceIngestionService {

    private final StockMinutePriceMapper minutePriceMapper;
    private final StockMinuteFetchStatusMapper fetchStatusMapper;

    public MinutePriceIngestionService(StockMinutePriceMapper minutePriceMapper,
                                        StockMinuteFetchStatusMapper fetchStatusMapper) {
        this.minutePriceMapper = minutePriceMapper;
        this.fetchStatusMapper = fetchStatusMapper;
    }

    /** Successful fetch: upserts every normalized bar, then marks AVAILABLE/NO_DATA accordingly. */
    @Transactional
    public void applyFetchResult(String stockId, LocalDate tradeDate, List<NormalizedMinuteBar> bars,
                                  String source, LocalDateTime fetchedAt) {
        for (NormalizedMinuteBar bar : bars) {
            minutePriceMapper.upsert(toDomain(bar, source));
        }
        if (bars.isEmpty()) {
            fetchStatusMapper.upsertNoData(stockId, tradeDate, source, fetchedAt);
        } else {
            fetchStatusMapper.upsertAvailable(stockId, tradeDate, bars.size(), source, fetchedAt);
        }
    }

    private StockMinutePrice toDomain(NormalizedMinuteBar bar, String source) {
        StockMinutePrice price = new StockMinutePrice();
        price.setStockId(bar.getStockId());
        price.setTradeDate(bar.getTradeDate());
        price.setBarTime(bar.getBarTime());
        price.setOpenPrice(bar.getOpen());
        price.setHighPrice(bar.getHigh());
        price.setLowPrice(bar.getLow());
        price.setClosePrice(bar.getClose());
        price.setVolume(bar.getVolume());
        price.setSource(source);
        return price;
    }
}
