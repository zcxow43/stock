---
status: done
title: "分 K 抓取狀態表 stock_minute_fetch_status"
requirement: "前端 K 線瀏覽 — 分 K 為隨點選隨抓取，需記錄每個「股票×交易日」的抓取結果，避免對永遠取不到資料的日期重複請求外部來源"
---

# 分 K 抓取狀態表 stock_minute_fetch_status — DBA Spec

## Overview

記錄每一個「股票 × 交易日」的分 K 抓取結果，讓後端能區分三種在 `stock_minute_price` 中**看起來完全一樣（都是查無資料）**的狀況：

| 實際狀況 | 沒有這張表時的行為 | 有這張表時的行為 |
|---|---|---|
| 尚未抓取過 | 向外部來源請求 | 向外部來源請求 |
| 已抓取，該日確實無成交 | **再次**向外部來源請求 | 直接回覆無資料，零外部請求 |
| 該日已超出來源提供的時間窗 | **再次**向外部來源請求 | 直接回覆超出範圍，零外部請求 |

分 K 採隨點選隨抓取（見 `specs/backend/stock-minute-price.md`），使用者每點一次日 K 上的 K 棒就可能觸發一次外部請求。少了這張表，任何一個「取不到資料」的日期都會在每次點擊時重打一次外部 API——冷門股與超窗日期正是最容易被反覆點選的情境，這會直接把速率額度耗盡。

`stock_sync_progress` 無法承擔此職責：其主鍵為 `(stock_id, job_type)`，一檔股票只有一列，記錄的是「批次作業進行到哪一天」；分 K 需要的是「每一天各自的抓取結果」，粒度不同。

**本 migration 為 V006，在 V005（`stock_minute_price`）之後。**

## Requirements

- 一列代表「一檔股票 × 一個交易日」的抓取結果，主鍵 `(stock_id, trade_date)`。
- `status` 必須明確區分下列五種結果，不可合併：

  | 值 | 意義 | 是否應再次請求外部來源 |
  |---|---|---|
  | `AVAILABLE` | 已成功抓取並寫入 K 棒 | 否（當日盤中除外，見下） |
  | `NO_DATA` | 已成功請求，但該日確實無任何分鐘成交資料 | 否 |
  | `OUT_OF_WINDOW` | 該日期超出資料來源提供的時間窗，來源永遠不會有此資料 | 否 |
  | `FAILED` | 請求失敗（逾時、HTTP 429、來源錯誤） | 是，但受 `attempt_count` 上限限制 |
  | `NOT_A_TRADING_DAY` | 該日在 `stock_daily_price` 中無對應日線，非該檔的交易日 | 否 |

  `NO_DATA` 與 `FAILED` 若混為一談，重試排程會對永遠不會有資料的日期無限重試；`OUT_OF_WINDOW` 與 `NO_DATA` 若混為一談，則無法向使用者說明「是這天沒交易，還是來源不提供這麼久以前的資料」——兩者在畫面上要顯示不同的訊息。

- 必須保留 `bar_count`：已寫入幾根 K 棒。`AVAILABLE` 但 `bar_count` 明顯偏低（如當日僅 12 根）代表抓取被中途截斷，可據此挑出需重抓的日期。
- 必須保留 `last_error` 與 `attempt_count`，理由同 `stock_sync_progress`：失敗若無原因記錄則無從診斷，無次數上限則單一標的可無限重試。
- 必須保留 `fetched_at`（最近一次成功抓取的時間）。**當日盤中的分 K 會持續增長**，後端據此判斷快取是否過期並重抓（判斷規則見 backend spec）；沒有這個時間戳就無法區分「今天早上 09:05 抓的 5 根」與「今天收盤後抓的 271 根」。

## Implementation Details

### Migration SQL — V006__create_stock_minute_fetch_status.sql

```sql
CREATE TABLE stock_minute_fetch_status (
    stock_id      VARCHAR(10)  NOT NULL COMMENT '股票代號',
    trade_date    DATE         NOT NULL COMMENT '交易日期',
    status        VARCHAR(20)  NOT NULL DEFAULT 'FAILED'
                      COMMENT 'AVAILABLE / NO_DATA / OUT_OF_WINDOW / FAILED / NOT_A_TRADING_DAY',
    bar_count     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已寫入的分鐘 K 棒數，正常交易日為 271',
    source        VARCHAR(20)  NULL     COMMENT '資料來源識別，如 YAHOO；尚未成功抓取時為 NULL',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '嘗試次數，用於重試上限判斷',
    last_error    VARCHAR(500) NULL     COMMENT '最近一次失敗原因',
    fetched_at    DATETIME     NULL     COMMENT '最近一次成功抓取完成時間；用於判斷當日盤中資料是否過期',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_id, trade_date),
    KEY idx_status_date (status, trade_date),
    CONSTRAINT chk_smfs_status CHECK (
        status IN ('AVAILABLE','NO_DATA','OUT_OF_WINDOW','FAILED','NOT_A_TRADING_DAY')
    )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='分 K 逐日抓取狀態（避免對無資料日期重複請求外部來源）';
```

### 索引說明
- `PRIMARY KEY (stock_id, trade_date)`：查詢分 K 前的第一個動作即「這檔這天抓過了嗎」，為單列主鍵查找。
- `idx_status_date`：支援維運查詢「挑出所有 `FAILED` 且尚未達重試上限的日期」與「清除超過保存期的狀態列」。

### 與 `stock_minute_price` 的一致性
兩表必須在同一個交易邊界內寫入：K 棒寫入與狀態更新若分屬兩個交易，可能出現「K 棒已寫入但狀態仍為 FAILED」（下次仍重抓，浪費請求）或「狀態為 AVAILABLE 但 K 棒未寫入」（前端拿到空圖且不會重試）。後者是使用者可見的錯誤。

清除舊分 K 分區時，對應日期的狀態列必須一併刪除；否則狀態表會宣稱 `AVAILABLE`，而 K 棒已隨分區消失。

## Acceptance Criteria
- [x] `stock_minute_fetch_status` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [x] 主鍵為 `(stock_id, trade_date)`
- [x] `status` 寫入五種列舉值以外的值時被 CHECK 約束拒絕
- [x] 對同一 `(stock_id, trade_date)` 重複 UPSERT，表中僅一列
- [x] 以 `WHERE status = 'FAILED' AND trade_date >= ?` 查詢時，`EXPLAIN` 顯示使用 `idx_status_date`
- [x] `fetched_at` 與 `source` 允許為 `NULL`（尚未成功抓取的列可寫入）

---
## Execution Result
- Status: DONE
- Files changed: `specs/dba/stock-minute-fetch-status.md` (Migration SQL executed against live DB; no standalone `.sql` files created)
- Notes:
  - Applied `V006__create_stock_minute_fetch_status.sql` directly against the live `stock` database via `mysql` CLI. Table did not previously exist (clean slate); creation succeeded with no errors.
  - Verified `SHOW CREATE TABLE` output matches the spec's DDL exactly: all 9 columns, types, comments, `PRIMARY KEY (stock_id, trade_date)`, `idx_status_date (status, trade_date)`, and `chk_smfs_status` CHECK constraint present.
  - Verified CHECK constraint: `INSERT ... status='BOGUS'` failed with `ERROR 3819 (HY000): Check constraint 'chk_smfs_status' is violated.`
  - Verified UPSERT: inserted `(2330, 2026-08-27, FAILED, ...)` then `INSERT ... ON DUPLICATE KEY UPDATE` with `status='AVAILABLE', bar_count=271, source='YAHOO', ...` on the same key; `COUNT(*)` for that key remained 1, and the row reflected the updated values.
  - Verified `EXPLAIN SELECT * FROM stock_minute_fetch_status WHERE status = 'FAILED' AND trade_date >= '2026-08-01'` returned `key: idx_status_date`, `type: range`, `Extra: Using index condition`.
  - Verified NULL acceptance: inserted a row with `status='NOT_A_TRADING_DAY'` omitting `source`/`fetched_at` (defaulting to NULL) — insert succeeded, and a row with explicit `NULL` for `source`/`fetched_at` was also accepted (tested during the UPSERT setup insert as well).
  - All test rows (`2330`/`2026-08-27`, `2330`/`2026-08-26`, `2317`/`2026-08-20`, `2412`/`2026-08-21`, `1101`/`2026-08-22`) were deleted after verification. Table confirmed empty (`COUNT(*) = 0`), left schema-only.
