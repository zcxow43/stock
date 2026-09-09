package com.stock.service;

import com.stock.domain.Stock;
import com.stock.domain.StockSyncProgress;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.external.dto.NormalizedPriceRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * Proves {@link PriceIngestionService#applySnapshotDay} writes the day's price rows and advances
 * last_synced_date inside ONE transaction (spec: 單日的行情寫入與 last_synced_date 推進在同一個交易邊界內
 * 完成：模擬推進失敗時該日的行情列一並回滾): a simulated failure in the progress-advance step must
 * roll back the price rows written earlier in the same call, not leave them committed.
 *
 * {@link StockSyncProgressMapper} is replaced with a Mockito mock for this test's Spring context
 * only, so the progress-advance call can be made to throw deterministically without needing a real
 * failure condition in the database.
 */
@SpringBootTest
class PriceIngestionServiceSnapshotDayRollbackTest {

    private static final String STOCK_ID = "T601";
    private static final String JOB_TYPE = StockSyncProgress.JOB_PRICE_BACKFILL;

    @Autowired
    private PriceIngestionService priceIngestionService;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private DataSource dataSource;

    @MockBean
    private StockSyncProgressMapper progressMapper;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id = ?", STOCK_ID);
        jdbc.update("DELETE FROM stock WHERE stock_id = ?", STOCK_ID);
        stockMapper.upsert(new Stock(STOCK_ID, "回滾測試", "TSE", true));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id = ?", STOCK_ID);
        jdbc.update("DELETE FROM stock WHERE stock_id = ?", STOCK_ID);
    }

    @Test
    void progressAdvanceFailure_rollsBackThePriceRowWrittenEarlierInTheSameDay() {
        LocalDate tradeDate = LocalDate.of(2025, 9, 1);
        NormalizedPriceRow row = new NormalizedPriceRow(STOCK_ID, tradeDate,
                new BigDecimal("10.00"), new BigDecimal("10.50"), new BigDecimal("9.50"),
                new BigDecimal("10.20"), 1000L, new BigDecimal("10000"), 5);
        Set<String> targetIds = new HashSet<>(Collections.singletonList(STOCK_ID));

        doThrow(new RuntimeException("simulated progress-advance failure"))
                .when(progressMapper).advanceLastSyncedDateForExisting(anyList(), anyString(), any());

        assertThrows(RuntimeException.class, () -> priceIngestionService.applySnapshotDay(
                List.of(row), targetIds, JOB_TYPE, tradeDate));

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = ?", Integer.class, STOCK_ID);
        assertEquals(0, rowCount, "the price row written before the simulated failure must have "
                + "been rolled back along with it, not left committed");
    }
}
