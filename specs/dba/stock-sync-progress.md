---
status: pending
title: "批次同步進度表 stock_sync_progress"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 全市場回補需逐檔進行且受資料源速率限制，需記錄每檔進度以支援斷點續傳與失敗重試"
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
    last_synced_date  DATE         NULL     COMMENT '已成功處理到的交易日；NULL 表示尚未開始',
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

### 進度語意
- 批次啟動時，為目標標的（多選清單或全市場）以 UPSERT 建立／重置為 `PENDING`。
- 每檔開始處理設為 `RUNNING`、成功設為 `DONE` 並寫入 `last_synced_date`、失敗設為 `FAILED` 並累加 `attempt_count`、該檔區間內無交易資料設為 `SKIPPED`。
- 重試時只挑 `status = 'FAILED'` 且 `attempt_count` 未達上限者。

## Acceptance Criteria
- [ ] `stock_sync_progress` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [ ] 主鍵為 `(stock_id, job_type)`
- [ ] `status` 寫入列舉外的值時被 CHECK 約束拒絕；`job_type` 同理
- [ ] 對同一 `(stock_id, job_type)` 重複 UPSERT，表中僅一列
- [ ] 以 `WHERE job_type = ? AND status IN ('PENDING','FAILED')` 查詢時，`EXPLAIN` 顯示使用 `idx_job_status`
