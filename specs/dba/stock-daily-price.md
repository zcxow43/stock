---
status: pending
title: "股票日線行情表 stock_daily_price"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 儲存日線 OHLC 原始行情，作為指標運算的唯一資料來源"
---

# 股票日線行情表 stock_daily_price — DBA Spec

## Overview

建立日線行情表，儲存個股每個交易日的 OHLC 與成交量資訊。此表是**所有技術指標的唯一原始資料來源**：指標一律由本表的 OHLC 重新推導，不從外部抓取指標數值。

指標運算結果存放於獨立的 `stock_daily_indicator` 表（見 `specs/dba/stock-daily-indicator.md`，V002）。兩表分離的理由：行情是不可變的事實，指標會隨參數組合而有多份，生命週期不同。

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

3. **日線與分線必須是不同的表。** 本表僅存日線。分線（`stock_minute_price`）為後續階段，其資料量級（全市場約每年 1.46 億列）需要 RANGE 分區等完全不同的設計，不可用單一表加 `period` 欄位混存。

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

## Acceptance Criteria
- [ ] `stock_daily_price` 表建立成功，欄位、型別、註解與上述 DDL 完全一致
- [ ] 主鍵為 `(stock_id, trade_date)` 複合鍵，且表中**不存在** `AUTO_INCREMENT` 欄位
- [ ] 四個價格欄位型別皆為 `DECIMAL(10,2)`，表中不存在任何 `FLOAT` 或 `DOUBLE` 欄位
- [ ] 對同一 `(stock_id, trade_date)` 重複執行 UPSERT 兩次，表中僅有一列，且第二次的值覆蓋第一次
- [ ] 插入 `high_price < low_price` 的資料時被 CHECK 約束拒絕
- [ ] `EXPLAIN` 驗證「單檔股票 + 日期區間」查詢使用主鍵範圍掃描（`type=range`, `key=PRIMARY`），而非全表掃描
