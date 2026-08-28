---
status: done
title: "股票主檔 stock"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 全市場範圍需要一份股票universe，供回補排程列舉標的與統計結果顯示名稱"
---

# 股票主檔 stock — DBA Spec

## Overview

儲存全市場股票的代號與名稱，作為兩件事的依據：

1. **回補排程的標的清單（universe）** — 逐檔回補需要知道「有哪些檔要補」，這份清單就是本表。
2. **統計結果的名稱來源** — 行情表只存代號，API 回傳需要中文名稱時由本表取得。

本表由每日行情抓取時順帶維護（資料源同時回傳代號與名稱），不需要獨立的維護介面。

**本 migration 為 V002，在 V001（`stock_daily_price`）之後。**

## Requirements

- 一列代表一檔股票，以股票代號為主鍵。
- 需區分市場別（上市 / 上櫃），因為兩者的資料來源端點不同，回補排程要據此分流。
- 需要 `is_active` 旗標標記已下市／終止交易的標的：下市股票不應再進入每日抓取與回補排程，但其歷史行情必須保留（不可刪除 `stock_daily_price` 既有資料）。
- **不與 `stock_daily_price` 建立外鍵約束。** 理由：新上市股票可能在主檔同步之前就出現在當日行情資料中，外鍵會讓該筆行情寫入失敗而遺失資料。行情抓取的健壯性優先於參照完整性；孤兒代號由對帳查詢檢出，而非由約束阻擋。

## Implementation Details

### Migration SQL — V002__create_stock.sql

```sql
CREATE TABLE stock (
    stock_id     VARCHAR(10)  NOT NULL                COMMENT '股票代號，如 2330',
    stock_name   VARCHAR(60)  NOT NULL                COMMENT '股票名稱，如 台積電',
    market       VARCHAR(10)  NOT NULL                COMMENT '市場別：TSE=上市, OTC=上櫃',
    is_active    TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否仍在交易：1=是, 0=已下市／終止交易',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_id),
    KEY idx_market_active (market, is_active),
    CONSTRAINT chk_stock_market CHECK (market IN ('TSE','OTC'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='股票主檔（全市場 universe）';
```

### 索引說明
- `idx_market_active`：回補與每日排程列舉標的時的查詢條件即 `WHERE market = ? AND is_active = 1`。

### 維護語意
以 UPSERT 寫入。股票更名（台股實務上會發生）時，`stock_name` 直接覆蓋為最新值——本表存的是「現況」，不保留名稱異動歷史。

## Acceptance Criteria
- [x] `stock` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [x] `market` 欄位寫入 `'TSE'`/`'OTC'` 以外的值時被 CHECK 約束拒絕
- [x] 對同一 `stock_id` 重複 UPSERT，表中僅一列且名稱被更新為最新值
- [x] `stock` 與 `stock_daily_price` 之間**不存在**外鍵約束（`SHOW CREATE TABLE stock_daily_price` 無 FOREIGN KEY）
- [x] 將某檔設為 `is_active = 0` 後，其在 `stock_daily_price` 的歷史資料仍完整存在

---
## Execution Result
- Status: DONE
- Files changed: `specs/dba/stock.md` (this spec — SQL applied live, no standalone .sql file created)
- Notes:
  - Applied `CREATE TABLE stock` (V002) against the live `stock` database via `mysql` CLI. `SHOW CREATE TABLE stock` / `DESCRIBE stock` confirmed columns, types, comments, PK, `idx_market_active` index, and `chk_stock_market` CHECK constraint all match the spec DDL exactly.
  - Verified CHECK constraint: `INSERT ... market='XXX'` raised `ERROR 3819 (HY000) Check constraint 'chk_stock_market' is violated`; no row was inserted.
  - Verified UPSERT semantics: inserted `stock_id='2330'` with name `台積電`, then `INSERT ... ON DUPLICATE KEY UPDATE` with name `台積電新名`; table retained exactly one row for `2330` with the updated name.
  - Verified no FK: `stock_daily_price` (created concurrently by the `stock_daily_price` DBA agent, confirmed present) has no `FOREIGN KEY` clause in its `SHOW CREATE TABLE` output — only its own `chk_sdp_high_low` CHECK and `idx_trade_date` index.
  - Verified `is_active=0` does not lose history: inserted a `stock_daily_price` row for `2330` (plus 3 pre-existing rows from the concurrent agent were visible at the time), set `stock.is_active=0` for `2330`, and confirmed all `stock_daily_price` rows for `2330` remained intact afterward.
  - Cleanup: deleted my own test rows (`stock.stock_id IN ('2330','9999')` — note `'9999'` was never actually inserted since the CHECK constraint rejected it; and the one `stock_daily_price` row I inserted for `2330`/`2026-08-01`/`source='TEST'`). Final state: `stock` table is empty (schema-only, 0 rows). `stock_daily_price` is also empty at time of writing — the concurrent agent appears to have cleaned up its own test rows independently during this verification.
