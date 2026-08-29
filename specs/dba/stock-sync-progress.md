---
status: done
title: "批次同步進度表 stock_sync_progress"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 全市場回補需逐檔進行且受資料源速率限制，需記錄每檔進度以支援斷點續傳與失敗重試；並釐清 last_synced_date 記的是區間迄日而非最後交易日，供啟動補齊的跳過條件依據"
---

# 批次同步進度表 stock_sync_progress — DBA Spec

## Overview

記錄每檔股票在長時間批次作業中的進度，使作業可**斷點續傳**。

此表的必要性來自一個實測到的外部限制：行情資料源的免費層**不允許一次取得全市場**（不指定股票代號的請求回 HTTP 400），必須逐檔抓取。全市場回補因此是約 2200 次外部請求的長時間作業，會因速率限制、網路中斷或程序重啟而中途停止。沒有逐檔進度記錄，每次中斷都只能從頭重跑。

同一機制同時服務兩種批次作業，以 `job_type` 區分：行情回補、指標重算。

**本 migration 為 V004，在 V003（`stock_daily_indicator`）之後。**

## Requirements

- 一列代表「一檔股票 × 一種批次作業」的當前進度，主鍵 `(stock_id, job_type)`。
- 必須記錄 `last_synced_date`——已成功處理到哪一個交易日。續傳時從此日期之後接續，而非重跑整個區間。
- 必須區分 `FAILED`（可重試）與 `SKIPPED`（不需重試，如該檔在目標區間內無任何交易資料）。將兩者混為一談會讓重試排程反覆嘗試永遠不會成功的標的。
- 必須保留 `last_error`，否則 2200 檔中失敗的少數幾檔無法診斷。
- `attempt_count` 用於設定重試上限，避免單一標的無限重試卡住整批作業。

## Implementation Details

### Migration SQL — V004__create_stock_sync_progress.sql

```sql
CREATE TABLE stock_sync_progress (
    stock_id          VARCHAR(10)  NOT NULL COMMENT '股票代號',
    job_type          VARCHAR(20)  NOT NULL COMMENT '作業類型：PRICE_BACKFILL / INDICATOR_REBUILD',
    status            VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                          COMMENT 'PENDING / RUNNING / DONE / FAILED / SKIPPED',
    target_start_date DATE         NOT NULL COMMENT '本批次目標區間起日（含暖身區間）',
    target_end_date   DATE         NOT NULL COMMENT '本批次目標區間迄日',
    last_synced_date  DATE         NULL     COMMENT '已成功處理到的區間迄日（未必是交易日）；NULL 表示尚未開始',
    attempt_count     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '嘗試次數，用於重試上限判斷',
    last_error        VARCHAR(500) NULL     COMMENT '最近一次失敗原因',
    started_at        DATETIME     NULL     COMMENT '本檔最近一次開始處理時間',
    finished_at       DATETIME     NULL     COMMENT '本檔完成時間',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_id, job_type),
    KEY idx_job_status (job_type, status),
    CONSTRAINT chk_ssp_job_type CHECK (job_type IN ('PRICE_BACKFILL','INDICATOR_REBUILD')),
    CONSTRAINT chk_ssp_status   CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','SKIPPED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='批次同步逐檔進度（支援斷點續傳與失敗重試）';
```

### 索引說明
- `idx_job_status`：續傳時的核心查詢即「取出某作業類型下所有 `PENDING` / `FAILED` 的標的」。

### Migration SQL — V008__fix_last_synced_date_comment.sql

僅修正 `last_synced_date` 的欄位註解，不改型別、可空性或任何資料。原註解寫「已成功處理到的**交易日**」，與上方「進度語意」所定的實際語意（記的是區間迄日，未必是交易日）相反，會把實作者導向每逢週末重抓一次的錯誤寫法。接在 `V007`（`stock` 的種子資料）之後。

V004 的 DDL 已同步改為新的註解文字，因此從空重建的資料庫在 V004 就是正確值，V008 在其上重跑只是把同樣的註解再寫一次——兩條路徑收斂到相同結果，重複執行安全。

```sql
ALTER TABLE stock_sync_progress
    MODIFY COLUMN last_synced_date DATE NULL
    COMMENT '已成功處理到的區間迄日（未必是交易日）；NULL 表示尚未開始';
```

### 進度語意
- 批次啟動時，為目標標的（多選清單或全市場）以 UPSERT 建立／重置為 `PENDING`。
- 每檔開始處理設為 `RUNNING`、成功設為 `DONE` 並寫入 `last_synced_date`、失敗設為 `FAILED` 並累加 `attempt_count`、該檔區間內無交易資料設為 `SKIPPED`。
- 重試時只挑 `status = 'FAILED'` 且 `attempt_count` 未達上限者。
- **`last_synced_date` 記的是該次請求的迄日，不是實際寫入的最後一列的日期。** 兩者在迄日為交易日時相同，但迄日落在週末、假日或收盤前時，資料源不回傳該日、依「不得補零」也不寫入任何列——此時若改記最後一筆實際資料的日期，之後每次補齊都會把那段沒有資料的尾巴重抓一次，週末重啟等於固定重複請求外部 API。記為迄日表示「這個區間已經處理過了」。此語意是 `specs/backend/stock-price-ingestion.md` 啟動補齊之跳過條件能否生效的前提。
- 補齊作業（該 backend spec 的 `catchUp`）會把 `status = 'DONE'` 但 `last_synced_date` 落後於目標迄日的列重新開啟為 `PENDING` 並將 `attempt_count` 歸零。落後是因為時間前進而非先前失敗，沿用舊的重試次數會讓這類列提早撞上重試上限。

## Acceptance Criteria
- [x] `stock_sync_progress` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [x] 主鍵為 `(stock_id, job_type)`
- [x] `status` 寫入列舉外的值時被 CHECK 約束拒絕；`job_type` 同理
- [x] 對同一 `(stock_id, job_type)` 重複 UPSERT，表中僅一列
- [x] 以 `WHERE job_type = ? AND status IN ('PENDING','FAILED')` 查詢時，`EXPLAIN` 顯示使用 `idx_job_status`
- [x] `last_synced_date` 的欄位註解為「已成功處理到的區間迄日（未必是交易日）；NULL 表示尚未開始」，在既有資料庫上由 V008 更新，在從空重建時由 V004 直接建成該值
- [x] V008 於已套用 V004 的資料庫上執行成功，且不影響表中既有列的資料

---
## Execution Result
- Status: DONE
- Files changed: `specs/dba/stock-sync-progress.md` (migration applied directly to live `stock` database; no standalone `.sql` file created)
- Notes:
  - Applied V004 `CREATE TABLE stock_sync_progress` against the live `stock` database via `mysql` CLI. Table did not previously exist (clean slate); creation succeeded on first attempt.
  - Verified via `SHOW CREATE TABLE`: all 11 columns, types, defaults, and comments match the DDL exactly (`stock_id` VARCHAR(10), `job_type` VARCHAR(20), `status` VARCHAR(12) DEFAULT 'PENDING', `target_start_date`/`target_end_date` DATE NOT NULL, `last_synced_date` DATE NULL, `attempt_count` INT UNSIGNED DEFAULT 0, `last_error` VARCHAR(500) NULL, `started_at`/`finished_at` DATETIME NULL, `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP), plus `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` and table comment.
  - Verified via `SHOW INDEX`: `PRIMARY` is `(stock_id, job_type)`; `idx_job_status` is `(job_type, status)`.
  - Verified CHECK constraints: inserting `status='BOGUS'` raised `ERROR 3819 (HY000): Check constraint 'chk_ssp_status' is violated`; inserting `job_type='BOGUS_JOB'` raised `ERROR 3819 (HY000): Check constraint 'chk_ssp_job_type' is violated`.
  - Verified UPSERT idempotency: three sequential `INSERT ... ON DUPLICATE KEY UPDATE` statements against the same `(stock_id, job_type) = ('2330','PRICE_BACKFILL')` key resulted in exactly one row, with `status` reflecting the last write (`DONE`).
  - Verified index usage: `EXPLAIN SELECT * FROM stock_sync_progress WHERE job_type = 'PRICE_BACKFILL' AND status IN ('PENDING','FAILED')` showed `key: idx_job_status`, `type: range`, `Extra: Using index condition`.
  - All test rows inserted during verification were deleted afterward (`DELETE FROM stock_sync_progress;`, confirmed `COUNT(*) = 0`); table left schema-only.

### Increment 2 — 2026-08-29
- Status: DONE
- Files changed: `specs/dba/stock-sync-progress.md` (V008 applied directly to live `stock` database via `mysql` CLI; no standalone `.sql` file created)
- Notes:
  - Applied `V008__fix_last_synced_date_comment.sql` (`ALTER TABLE stock_sync_progress MODIFY COLUMN last_synced_date DATE NULL COMMENT '已成功處理到的區間迄日（未必是交易日）；NULL 表示尚未開始';`) against the live `stock` database, connecting with `--default-character-set=utf8mb4` throughout to avoid client-side codepage corruption of the Chinese comment.
  - Row count before migration: `SELECT COUNT(*) FROM stock_sync_progress;` → `0`. Row count after migration: `0`. No data affected (table was already empty from Increment 1's cleanup, and this ALTER only touches column metadata).
  - Verified the new comment via `information_schema.columns`: `COLUMN_COMMENT` = `已成功處理到的區間迄日（未必是交易日）；NULL 表示尚未開始`; `HEX(COLUMN_COMMENT)` began `E5B7B2E68890E58A9F...` — genuine multi-byte UTF-8 sequences (`E5`/`E6`/`E7`/`E8`/`E9` lead bytes), with no `3F` (`?`) bytes, confirming no mojibake/corruption.
  - Verified via `SHOW CREATE TABLE stock_sync_progress`: `last_synced_date` is still `date DEFAULT NULL`, now carrying the corrected comment; no other column, index, or constraint changed.
