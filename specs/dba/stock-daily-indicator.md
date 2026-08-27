---
status: pending
title: "股票日線技術指標表 stock_daily_indicator"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 儲存每日 MACD、KD 指標值與其遞迴中間狀態，供統計 API 直接讀取並支援逐日增量推進"
---

# 股票日線技術指標表 stock_daily_indicator — DBA Spec

## Overview

儲存由 `stock_daily_price` 的 OHLC 推導出的 MACD 與 KD 指標值。

本表存在的關鍵理由是 **MACD 與 KD 都是遞迴指標** —— 當日的值由前一日的值推導而來。若不落地保存，每次查詢都必須從數百根 K 棒重新推算整條序列。落地之後，每日新增只需讀取前一交易日的一列狀態、推進一步即可。

因此本表**不只存指標輸出值，也存遞迴所需的中間狀態**（`ema_fast`、`ema_slow`）。少存這兩欄，隔日就無法接續推進，整個增量設計會退化成每日全量重算。

**本 migration 為 V003，在 V002（`stock`）之後。**

## Requirements

### 指標定義（台股慣例，此為系統的唯一契約）

**KD — 參數 (9, 3, 3)，平滑方式為 RMA：**
```
RSV = (C - 最近9日最低) / (最近9日最高 - 最近9日最低) × 100
K   = 2/3 × K前一日 + 1/3 × RSV
D   = 2/3 × D前一日 + 1/3 × K
J   = 3K - 2D
```
序列起點的 K、D 初始值為 50。

**MACD — 參數 (12, 26, 9)：**
```
DIF = EMA12(收盤價) - EMA26(收盤價)
DEA = EMA9(DIF)              （即市面上所稱的 MACD 線 / signal 線）
OSC = DIF - DEA              （柱狀圖）
```

> **命名警告**：台股慣例中「MACD」一詞指 DEA 這條訊號線，而國外（含 TradingView）的 "MACD line" 指的是 DIF。本系統一律使用 `dif` / `dea` / `osc` 三個明確欄位名，不使用 `macd` 這個有歧義的名稱，以免前後端對接時對錯欄位。

> **與 TradingView 的關係**：TradingView 內建的 Stochastic 預設為 (14, 3, 3) 且平滑方式為 **SMA**，與本表的台股 KD (9,3,3, RMA) 是**不同的指標**，數值本來就不會相等（同一天 2330 實測：台股 K=64.00 vs TradingView %K=67）。這不是誤差，不得為了「對上 TradingView」而修改上述公式。MACD 則兩者定義相同，會吻合。

### 多參數組合支援

主鍵含 `param_key`，使同一檔同一天可並存多組參數的計算結果。`param_key` 必須**自我描述**完整參數，不使用需要另查對照表的代號。本階段唯一使用的值為：

```
MACD_12_26_9__KD_9_3_3
```

### 精度要求

遞迴狀態欄位（`ema_fast`、`ema_slow`、`k_value`、`d_value`）會被讀出後餵回下一日的計算。若以低精度儲存，捨入誤差將沿遞迴鏈逐日累積。故：

- MACD 相關欄位使用 `DECIMAL(18,8)`
- KD 相關欄位使用 `DECIMAL(12,8)`（J 值可為負值且可超過 100，四位整數位足敷使用）
- **嚴禁 `FLOAT` / `DOUBLE`**

## Implementation Details

### Migration SQL — V003__create_stock_daily_indicator.sql

```sql
CREATE TABLE stock_daily_indicator (
    stock_id    VARCHAR(10)    NOT NULL COMMENT '股票代號',
    trade_date  DATE           NOT NULL COMMENT '交易日期',
    param_key   VARCHAR(40)    NOT NULL COMMENT '參數組合識別，本階段固定為 MACD_12_26_9__KD_9_3_3',

    ema_fast    DECIMAL(18,8)  NOT NULL COMMENT '遞迴狀態：EMA12(收盤價)',
    ema_slow    DECIMAL(18,8)  NOT NULL COMMENT '遞迴狀態：EMA26(收盤價)',
    dif         DECIMAL(18,8)  NOT NULL COMMENT 'DIF = EMA12 - EMA26',
    dea         DECIMAL(18,8)  NOT NULL COMMENT 'DEA = EMA9(DIF)，台股慣稱的 MACD 線',
    osc         DECIMAL(18,8)  NOT NULL COMMENT 'OSC = DIF - DEA，柱狀圖',

    rsv         DECIMAL(12,8)  NOT NULL COMMENT '未成熟隨機值',
    k_value     DECIMAL(12,8)  NOT NULL COMMENT '遞迴狀態：K 值',
    d_value     DECIMAL(12,8)  NOT NULL COMMENT '遞迴狀態：D 值',
    j_value     DECIMAL(12,8)  NOT NULL COMMENT 'J = 3K - 2D',

    is_warmup   TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '1=此列位於暖身區間，數值尚未收斂，不得對外呈現',
    created_at  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (stock_id, trade_date, param_key),
    KEY idx_date_param (trade_date, param_key)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='股票日線技術指標（MACD / KD，含遞迴中間狀態）';
```

### 主鍵順序說明
`(stock_id, trade_date, param_key)` 的欄位順序不可調換。核心查詢為「單檔股票 + 日期區間 + 單一參數組」，此順序讓同一檔的指標序列在磁碟上連續存放，區間查詢為順序掃描；若將 `param_key` 前置，同一檔的資料會被參數組切散。

### `is_warmup` 的用途
指標序列開頭數值尚未收斂（見 backend spec 的暖身規範）。這些列**必須寫入**——它們是遞迴鏈的必要環節——但不得對外呈現。以此旗標區分，而非刪除。

## Acceptance Criteria
- [ ] `stock_daily_indicator` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [ ] 主鍵為 `(stock_id, trade_date, param_key)` 且順序正確
- [ ] 表中不存在任何 `FLOAT` 或 `DOUBLE` 欄位
- [ ] `ema_fast`、`ema_slow` 兩個遞迴狀態欄位存在且為 `DECIMAL(18,8)`
- [ ] 寫入 `j_value = -150.5` 與 `j_value = 250.75` 皆成功（J 值可超出 0~100 範圍）
- [ ] `EXPLAIN` 驗證「單檔 + 日期區間 + param_key」查詢使用主鍵範圍掃描（`type=range`, `key=PRIMARY`）
