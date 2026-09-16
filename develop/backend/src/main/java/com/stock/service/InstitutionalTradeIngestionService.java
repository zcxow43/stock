package com.stock.service;

import com.stock.domain.StockInstitutionalTrade;
import com.stock.mapper.StockInstitutionalTradeMapper;
import com.stock.mapper.StockMapper;
import com.stock.service.external.dto.NormalizedInstitutionalTradeRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns every write into `stock_institutional_trade` (spec:
 * specs/backend/institutional-trade-ingestion.md, 寫入). A whole day's rows are written or not
 * written as a single unit (spec: 單日的全部列在同一個交易邊界內寫入) — a mid-write failure must leave the
 * day at zero rows, not a partial market, since "has any row at all" is exactly how the caller
 * later decides whether this day still needs to be re-fetched (spec: 缺少日期的認定).
 */
@Service
public class InstitutionalTradeIngestionService {

    private static final String SOURCE_TWSE = "TWSE";

    private final StockInstitutionalTradeMapper tradeMapper;
    private final StockMapper stockMapper;

    public InstitutionalTradeIngestionService(StockInstitutionalTradeMapper tradeMapper, StockMapper stockMapper) {
        this.tradeMapper = tradeMapper;
        this.stockMapper = stockMapper;
    }

    /**
     * Writes one trading day's institutional-trade rows, filtered down to exactly the stock ids
     * that already exist in the `stock` master (spec: 只寫入 stock 主檔中已存在的代號...不新增主檔列) —
     * regardless of {@code is_active} or common-stock status, since a strategy scan may explicitly
     * target ETFs or inactive stocks (spec: 不以普通股過濾). Returns the number of rows actually
     * written, so the caller can log a day with zero writable rows (an id-filtered-to-nothing day,
     * not observed in practice) distinctly from one with real writes.
     */
    @Transactional
    public int applyDay(List<NormalizedInstitutionalTradeRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> stockIds = new ArrayList<>(rows.size());
        for (NormalizedInstitutionalTradeRow row : rows) {
            stockIds.add(row.getStockId());
        }
        Set<String> existingStockIds = new HashSet<>(stockMapper.findExistingStockIds(stockIds));

        int written = 0;
        for (NormalizedInstitutionalTradeRow row : rows) {
            if (!existingStockIds.contains(row.getStockId())) {
                continue;
            }
            tradeMapper.upsert(toDomain(row));
            written++;
        }
        return written;
    }

    private StockInstitutionalTrade toDomain(NormalizedInstitutionalTradeRow row) {
        StockInstitutionalTrade trade = new StockInstitutionalTrade();
        trade.setStockId(row.getStockId());
        trade.setTradeDate(row.getTradeDate());
        trade.setForeignBuyShares(row.getForeignBuyShares());
        trade.setForeignSellShares(row.getForeignSellShares());
        trade.setForeignNetShares(row.getForeignNetShares());
        trade.setForeignDealerBuyShares(row.getForeignDealerBuyShares());
        trade.setForeignDealerSellShares(row.getForeignDealerSellShares());
        trade.setForeignDealerNetShares(row.getForeignDealerNetShares());
        trade.setTrustBuyShares(row.getTrustBuyShares());
        trade.setTrustSellShares(row.getTrustSellShares());
        trade.setTrustNetShares(row.getTrustNetShares());
        trade.setDealerNetShares(row.getDealerNetShares());
        trade.setDealerSelfBuyShares(row.getDealerSelfBuyShares());
        trade.setDealerSelfSellShares(row.getDealerSelfSellShares());
        trade.setDealerSelfNetShares(row.getDealerSelfNetShares());
        trade.setDealerHedgeBuyShares(row.getDealerHedgeBuyShares());
        trade.setDealerHedgeSellShares(row.getDealerHedgeSellShares());
        trade.setDealerHedgeNetShares(row.getDealerHedgeNetShares());
        trade.setTotalNetShares(row.getTotalNetShares());
        trade.setSource(SOURCE_TWSE);
        return trade;
    }
}
