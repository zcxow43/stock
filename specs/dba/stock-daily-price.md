---
status: done
title: "股票日線行情表 stock_daily_price"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 儲存日線 OHLC 原始行情，作為指標運算的唯一資料來源"
---

# 股票日線行情表 stock_daily_price — DBA Spec

## Overview

建立日線行情表，儲存個股每個交易日的 OHLC 與成交量資訊。此表是**所有技術指標的唯一原始資料來源**：指標一律由本表的 OHLC 重新推導，不從外部抓取指標數值。

指標運算結果存放於獨立的 `stock_daily_indicator` 表（見 `specs/dba/stock-daily-indicator.md`，V003）。兩表分離的理由：行情是不可變的事實，指標會隨參數組合而有多份，生命週期不同。

**本 migration 為 V001，是專案的第一個 migration，無前置。**

## Requirements

### 資料範圍
- 一列代表「一檔股票的一個交易日」。
- 僅存**已收盤**的日線；盤中未定案的資料不得寫入。
- 價格一律使用**原始成交價（未經除權息還原）**。這是可重現性的前提：還原價會隨每次除權息事件而整段變動，導致歷史指標值無法重現。此決定必須全系統一致。

### 欄位設計原則（不可妥協）

1. **主鍵為 `(stock_id, trade_date)` 複合鍵，禁止使用 `AUTO_INCREMENT` 代理鍵。**
   資料庫的主鍵為叢集索引，實體儲存順序即主鍵順序。以 `(stock_id, trade_date)` 為主鍵時，同一檔股票的歷史資料在磁碟上連續存放，「查詢單一股票兩個月區間」退化為一次順序掃描；若改用自增 id，同一檔的資料將散落全表，同樣查詢變成大量隨機 I/O。指標運算需要逐檔讀取長區間歷史，此設計直接決定其效能。

2. **所有價格欄位使用 `DECIMAL`，嚴禁 `FLOAT` / `DOUBLE`。**
   MACD 與 KD 皆為**遞迴**指標，當日結果會作為隔日的輸入。浮點數的表示誤差會沿遞迴鏈逐日累積放大，最終表現為「指標值與市面看盤軟體有微小但持續的偏差」——這是最難追查的一類 bug。

3. **日線與分線必須是不同的表。** 本表僅存日線。分線存放於 `stock_minute_price`（見 `specs/dba/stock-minute-price.md`，V005），其資料量級（全市場約每年 1.46 億列）需要 RANGE 分區等完全不同的設計，不可用單一表加 `period` 欄位混存。

## Implementation Details

### Migration SQL — V001__create_stock_daily_price.sql

```sql
CREATE TABLE stock_daily_price (
    stock_id           VARCHAR(10)     NOT NULL          COMMENT '股票代號，如 2330',
    trade_date         DATE            NOT NULL          COMMENT '交易日期',
    open_price         DECIMAL(10,2)   NOT NULL          COMMENT '開盤價（原始成交價，未還原）',
    high_price         DECIMAL(10,2)   NOT NULL          COMMENT '最高價',
    low_price          DECIMAL(10,2)   NOT NULL          COMMENT '最低價',
    close_price        DECIMAL(10,2)   NOT NULL          COMMENT '收盤價',
    volume             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成交股數',
    turnover           DECIMAL(20,2)   NOT NULL DEFAULT 0 COMMENT '成交金額（元）',
    transaction_count  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '成交筆數',
    source             VARCHAR(20)     NOT NULL          COMMENT '資料來源識別，如 FINMIND / TWSE',
    created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_id, trade_date),
    KEY idx_trade_date (trade_date),
    CONSTRAINT chk_sdp_high_low CHECK (high_price >= low_price)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='股票日線行情（原始成交價，未經除權息還原）';
```

### 索引說明
- `PRIMARY KEY (stock_id, trade_date)`：主要存取路徑。支援「單檔 + 日期區間」的範圍掃描，即統計 API 的核心查詢。
- `idx_trade_date`：支援「某一交易日全市場」的批次寫入後驗證與跨股票查詢。

### 寫入語意
抓取排程必須以 **UPSERT** 寫入（`INSERT ... ON DUPLICATE KEY UPDATE`），不可先刪後插。重跑同一天的抓取必須是冪等的——資料源事後修正（如成交筆數更正）時，重跑即修正，不產生重複列，也不留下空窗。

### Migration SQL — V012__shift_daily_price_timestamps_to_taipei.sql

一次性資料位移，不改結構。接在 `V010` 之後，理由與 V009／V010 相同。

```sql
UPDATE stock_daily_price t
JOIN (SELECT COUNT(*) AS c FROM schema_migration WHERE version = 'V012') g
SET t.created_at = t.created_at + INTERVAL 8 HOUR,
    t.updated_at = t.updated_at + INTERVAL 8 HOUR
WHERE g.c = 0;

INSERT IGNORE INTO schema_migration (version) VALUES ('V012');
```

守門走 `schema_migration`（見 `specs/dba/schema-migration.md`），理由與 V010 相同。

只動 `created_at` / `updated_at` 兩個稽核欄位。**`trade_date` 不在位移範圍內**——它是資料源給定的台北交易日曆日，本來就與資料庫時區無關，位移它會直接破壞行情資料的正確性。

`stock_daily_indicator`、`stock_minute_price`、`stock_minute_fetch_status` 三張表目前沒有任何資料列，沒有需要位移的內容，因此不需要對應的 migration；它們在時區修正後才會第一次寫入，寫入的即是台北時間。

## Acceptance Criteria
- [x] `stock_daily_price` 表建立成功，欄位、型別、註解與上述 DDL 完全一致
- [x] 主鍵為 `(stock_id, trade_date)` 複合鍵，且表中**不存在** `AUTO_INCREMENT` 欄位
- [x] 四個價格欄位型別皆為 `DECIMAL(10,2)`，表中不存在任何 `FLOAT` 或 `DOUBLE` 欄位
- [x] 對同一 `(stock_id, trade_date)` 重複執行 UPSERT 兩次，表中僅有一列，且第二次的值覆蓋第一次
- [x] 插入 `high_price < low_price` 的資料時被 CHECK 約束拒絕
- [x] `EXPLAIN` 驗證「單檔股票 + 日期區間」查詢使用主鍵範圍掃描（`type=range`, `key=PRIMARY`），而非全表掃描

---

- [x] V012 執行後，既有列的 `created_at` / `updated_at` 與執行前相比正好增加 8 小時
- [x] V012 連續執行兩次，第二次影響 0 列，且 `schema_migration` 中 `V012` 仍只有一列（位移為冪等）
- [x] V012 執行前後 `trade_date` 完全未變動，任一列的 `trade_date` 都沒有位移

## Execution Result
- Status: DONE
- Files changed: specs/dba/stock-daily-price.md (Migration SQL V001 applied to live database; no standalone .sql file created)
- Notes: Applied `V001__create_stock_daily_price.sql` directly against the live `stock` database via `mysql` CLI. Verified all 6 acceptance criteria with real queries:
  - `SHOW CREATE TABLE` confirms columns, types, comments, engine, charset/collation, and CHECK constraint match the DDL exactly.
  - `information_schema.columns` confirms composite PRIMARY KEY `(stock_id, trade_date)` and no column has `auto_increment` in EXTRA.
  - `information_schema.columns` confirms all four price columns (`open_price`, `high_price`, `low_price`, `close_price`) are `decimal(10,2)`; no `float`/`double` columns exist anywhere in the table.
  - Ran the same `INSERT ... ON DUPLICATE KEY UPDATE` twice for `('2330','2026-08-27')` with different values each time; `COUNT(*)` for that key stayed at 1 and the row reflected the second call's values.
  - Attempted to insert a row with `high_price(90.00) < low_price(95.00)`; MySQL rejected it with `ERROR 3819 (HY000): Check constraint 'chk_sdp_high_low' is violated.`
  - `EXPLAIN SELECT * FROM stock_daily_price WHERE stock_id='2330' AND trade_date BETWEEN ...` returned `type=range, key=PRIMARY` (not a full table scan).
  - All test rows were deleted afterward (`DELETE FROM stock_daily_price`); table confirmed empty (`COUNT(*) = 0`), left schema-only.

### Increment 2 — 2026-08-31

套用 V012（時間戳由 UTC 位移為 Asia/Taipei），以 `schema_migration` 守門。

| | `created_at` / `updated_at`（1101 / 2026-01-02） | 列數 | `trade_date` 範圍 |
|---|---|---|---|
| 位移前 | `2026-08-31 03:07:19` | 5372 | `2026-01-02` ~ `2026-08-28` |
| 位移後 | `2026-08-31 11:07:19` | 5372 | `2026-01-02` ~ `2026-08-28` |

第一次執行影響 5372 列，正好 +8 小時。**`trade_date` 完全未動**——位移前後的列數與最早／最晚交易日三個值完全相同，確認位移只落在兩個稽核欄位上，行情資料的日期未受影響。第二次執行影響 **0** 列，`trade_date` 範圍仍相同。
