package com.stock.mapper;

import com.stock.domain.SimulatedTrade;
import com.stock.domain.SimulatedTradeSettlementPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SimulatedTradeMapper {

    /**
     * Every row, sorted by buy_date DESC then stock_id ASC — the exact ordering
     * GET /api/simulated-trades must return (specs/backend/simulated-trade.md, "items 依 buyDate 由
     * 新到舊排序，同日依 stockId 升冪"). Row count is user-added, small; a plain unfiltered scan is
     * enough (specs/dba/simulated-trade.md, "索引說明").
     */
    List<SimulatedTrade> findAllOrderedByBuyDateDescStockIdAsc();

    /**
     * Plain INSERT — never UPSERT (specs/dba/simulated-trade.md, "寫入語意"). A conflicting
     * (stock_id, buy_date) surfaces as {@link org.springframework.dao.DuplicateKeyException} via
     * the table's unique key; the caller translates that into 409 DUPLICATE_SIMULATED_TRADE. On
     * success, the generated id is written back into {@code trade.id}.
     */
    int insert(SimulatedTrade trade);

    /** Hard delete by id (specs/dba/simulated-trade.md, "硬刪除"). Returns the affected row count — 0 means the id did not exist. */
    int deleteById(@Param("id") Long id);

    /**
     * One batched query for every holding's settlement sell price: the {@code open_price} on the latest
     * {@code trade_date} in [buy_date, settleDate] with {@code open_price > 0} — settleDate itself when the
     * stock quoted that day, otherwise its last quoted day before it (specs/backend/simulated-trade.md,
     * "settleDate"). Holdings bought after settleDate yield no row.
     */
    List<SimulatedTradeSettlementPrice> findSettlementPrices(@Param("settleDate") LocalDate settleDate);
}
