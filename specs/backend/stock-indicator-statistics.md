---
status: pending
title: "MACD／KD 指標運算與兩個月統計"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 由日線 OHLC 推導 MACD 與 KD，落地遞迴狀態供增量推進，並提供兩個月區間統計查詢，支援多選股票與全市場"
depends_on: [stock-price-ingestion]
---

# MACD／KD 指標運算與兩個月統計 — Backend Spec

## Overview

兩件事：

1. **指標運算** — 讀 `stock_daily_price` 的 OHLC，算出 MACD 與 KD，寫入 `stock_daily_indicator`（含遞迴中間狀態）。
2. **統計查詢** — 提供指定區間（預設兩個月）的行情與指標統計，可查**指定多檔**或**全市場**。

指標一律由本系統自行推導，**不從任何外部服務取得指標數值**。理由：外部服務（看盤網站、圖表平台）只提供整數或低精度的當前值、沒有歷史序列、且各家參數與平滑方式不同，無法作為可重現的資料來源。

## Requirements

### 指標定義

公式與參數以 `specs/dba/stock-daily-indicator.md` 的「指標定義」為唯一契約：KD 採**台股慣例 (9,3,3)、RMA 平滑**，MACD 採 (12,26,9)，輸出欄位為 `dif` / `dea` / `osc` 與 `k` / `d` / `j`。本階段 `param_key` 固定為 `MACD_12_26_9__KD_9_3_3`。

> 本系統的 KD 與 TradingView 內建 Stochastic (14,3,3, SMA) 是不同指標，數值不相等屬預期行為，不得為求一致而更動公式。MACD 定義相同，應吻合。

### 暖身區間（正確性的關鍵前提）

MACD 與 KD 皆為遞迴指標，序列開頭的值取決於人為設定的初始值，尚未收斂。**要輸出正確的兩個月指標，必須從統計起日往前多取 250 個交易日一併運算，並將該段標記為 `is_warmup = 1`、不對外呈現。**

250 這個數字有實測依據（以 2330、截至 2026-08-27 為基準，比較不同暖身長度算出的同一日數值）：

| 暖身根數 | DIF | DEA | K | D |
|---|---|---|---|---|
| 102 | 8.6338 | 5.5624 | 63.9981 | 56.3474 |
| 136 | 8.6396 | 5.5710 | 63.9981 | 56.3474 |
| 157 | 8.6410 | 5.5731 | 63.9981 | 56.3474 |
| 199 | 8.6416 | 5.5740 | 63.9981 | 56.3474 |
| 305 | 8.6417 | 5.5740 | 63.9981 | 56.3474 |

KD 在 102 根內即完全收斂（各長度數值相同）；MACD 的 EMA26 收斂最慢，需約 199 根才穩定至小數第四位。取 250 個交易日（約一年）保留安全邊際。日線資料量極小，加長暖身的成本可忽略。

若某檔在統計起日之前的歷史不足 250 個交易日（如新上市股票），仍照常運算並輸出，但該檔的回應必須標示 `warmupSufficient: false`，讓呼叫端知道其指標尚未收斂。**不得因暖身不足而隱瞞或拒絕回應。**

### 增量推進

指標運算有兩種模式，共用同一套公式實作：

- **全量重建**：捨棄該檔既有指標列，從暖身起點重新推導整條序列。用於初次建置或參數變更。
- **每日增量**：讀取該檔前一交易日的 `ema_fast` / `ema_slow` / `k_value` / `d_value` 遞迴狀態，配合當日 OHLC 推進一步後寫入。這是每個交易日的例行路徑，成本為每檔一次讀取加一次寫入，與歷史長度無關。

KD 的 RSV 需要最近 9 日的最高與最低價，故增量模式除前一日狀態外，另需讀取最近 9 個交易日的高低價。

若增量推進時找不到前一交易日的指標列（序列有缺口），**不得以初始值 50 起算補上**——那會產生一個未收斂的錯誤值並沿遞迴鏈污染其後所有日期。正確處理是將該檔標記為需要全量重建。

### 統計區間

- `endDate` 省略時，取該檔在 `stock_daily_price` 中的最新交易日。
- `startDate` 省略時，取 `endDate` 往前推兩個曆月後的第一個交易日。
- 統計僅涵蓋區間內實際有交易的日期；停牌日不計入 `tradingDays`，也不佔序列位置。

### 統計內容

除逐日序列外，每檔須計算區間摘要，包含價格區間統計與**指標交叉次數**——後者是「統計 MACD／KD」的實質內容，僅列出每日數值不構成統計。

交叉定義：

| 事件 | 條件 |
|---|---|
| MACD 黃金交叉 | `osc` 由 ≤ 0 轉為 > 0（DIF 上穿 DEA） |
| MACD 死亡交叉 | `osc` 由 ≥ 0 轉為 < 0 |
| KD 黃金交叉 | `k` 由 ≤ `d` 轉為 > `d` |
| KD 死亡交叉 | `k` 由 ≥ `d` 轉為 < `d` |

交叉判定必須以**相鄰兩個交易日**比較，不可跨越停牌造成的日曆間隔另做插補。

### 多選與全市場

統計查詢的標的選擇與回補一致：

- 提供 `stockIds` → 查詢指定標的（**上限 50 檔**），回應含逐日序列。
- 省略 `stockIds` → 查詢全市場 `is_active = 1` 的股票，**強制不含逐日序列**，僅回摘要。

全市場加逐日序列的組合會產生約 2200 檔 × 41 個交易日、近 9 萬筆的回應，不適合單次同步回傳；全市場查詢因此限定為摘要模式（每檔一列，約 2200 列）。此為契約層級的限制，`includeSeries=true` 搭配全市場時必須回 `400` 明確拒絕，而非默默截斷資料。

## Implementation Details

### API 契約

#### 1. 指標運算

```
POST /api/stocks/indicators/rebuild
```

Request：
```json
{
  "stockIds": ["2330", "2317"],
  "startDate": "2026-06-27",
  "endDate": "2026-08-27",
  "mode": "FULL",
  "resume": false
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockIds` | string[] | 否 | 省略或空陣列代表全市場 |
| `startDate` | date | 否 | 指標輸出起日；實際運算會自動往前取 250 個交易日暖身 |
| `endDate` | date | 否 | 預設為各檔最新交易日 |
| `mode` | string | 否 | `FULL`（全量重建，預設）或 `INCREMENTAL`（每日增量） |
| `resume` | boolean | 否 | `true` 時只處理 `stock_sync_progress` 中 `INDICATOR_REBUILD` 為 `PENDING`／`FAILED` 的標的 |

Response `202`：
```json
{
  "jobType": "INDICATOR_REBUILD",
  "targetCount": 2200,
  "mode": "ALL",
  "computeMode": "FULL",
  "warmupTradingDays": 250,
  "paramKey": "MACD_12_26_9__KD_9_3_3"
}
```

進度沿用 `GET /api/stocks/sync/progress?jobType=INDICATOR_REBUILD`（契約見 `specs/backend/stock-price-ingestion.md`）。

驗證與錯誤：
- `startDate` 晚於 `endDate` → `400`，`{"code":"INVALID_DATE_RANGE"}`
- `stockIds` 含未知代號 → `400`，`{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}`
- 已有 `INDICATOR_REBUILD` 作業執行中 → `409`，`{"code":"JOB_ALREADY_RUNNING"}`。與回補作業相同：同一 `jobType` 同時只允許一筆批次執行，避免兩個批次對同一檔的指標序列交錯寫入。

#### 2. 兩個月統計查詢

```
GET /api/stocks/statistics
```

Query 參數：

| 參數 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockIds` | string | 否 | 逗號分隔，上限 50 檔；省略代表全市場 |
| `startDate` | date | 否 | 預設 `endDate` 往前兩個曆月 |
| `endDate` | date | 否 | 預設庫內最新交易日 |
| `includeSeries` | boolean | 否 | 指定 `stockIds` 時預設 `true`；全市場時只能為 `false` |

Response `200`：
```json
{
  "startDate": "2026-06-27",
  "endDate": "2026-08-27",
  "paramKey": "MACD_12_26_9__KD_9_3_3",
  "scope": "SELECTED",
  "stockCount": 1,
  "items": [
    {
      "stockId": "2330",
      "stockName": "台積電",
      "tradingDays": 41,
      "warmupSufficient": true,
      "summary": {
        "firstOpen": 2200.00,
        "lastClose": 2410.00,
        "highest": 2535.00,
        "highestDate": "2026-08-12",
        "lowest": 2100.00,
        "lowestDate": "2026-07-02",
        "changeAmount": 210.00,
        "changePercent": 9.55,
        "totalVolume": 712345678,
        "avgVolume": 17374285,
        "latestDif": 8.6416,
        "latestDea": 5.5740,
        "latestOsc": 3.0677,
        "latestK": 63.9981,
        "latestD": 56.3474,
        "latestJ": 79.2995,
        "macdGoldenCross": 3,
        "macdDeathCross": 2,
        "kdGoldenCross": 4,
        "kdDeathCross": 3
      },
      "series": [
        {
          "tradeDate": "2026-08-27",
          "open": 2430.00, "high": 2435.00, "low": 2410.00, "close": 2410.00,
          "volume": 17557736,
          "dif": 8.6416, "dea": 5.5740, "osc": 3.0677,
          "k": 63.9981, "d": 56.3474, "j": 79.2995
        }
      ]
    }
  ]
}
```

`scope` 為 `SELECTED` 或 `ALL`。全市場查詢時各 item 不含 `series` 欄位。

數值格式：價格與漲跌金額 2 位小數、漲跌百分比 2 位小數、指標值 4 位小數。指標以資料庫中的完整精度參與所有計算，僅在序列化為回應時才四捨五入。

錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `stockIds` 超過 50 檔 | `400` | `{"code":"TOO_MANY_STOCK_IDS","limit":50}` |
| 全市場查詢帶 `includeSeries=true` | `400` | `{"code":"SERIES_NOT_ALLOWED_FOR_ALL_SCOPE"}` |
| `startDate` 晚於 `endDate` | `400` | `{"code":"INVALID_DATE_RANGE"}` |
| `stockIds` 含未知代號 | `400` | `{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}` |
| 指定標的在區間內無任何行情資料 | `200` | 該 item 的 `tradingDays` 為 `0`、`summary` 為 `null`、`series` 為空陣列 |

### 資料來源對應

| 回應欄位 | 來源 |
|---|---|
| `stockId` / `stockName` | `stock.stock_id` / `stock.stock_name` |
| `open` / `high` / `low` / `close` / `volume` | `stock_daily_price.open_price` / `high_price` / `low_price` / `close_price` / `volume` |
| `dif` / `dea` / `osc` | `stock_daily_indicator.dif` / `dea` / `osc` |
| `k` / `d` / `j` | `stock_daily_indicator.k_value` / `d_value` / `j_value` |
| `warmupSufficient` | 該檔區間起日之前 `is_warmup = 0` 的指標列數是否達 250 |
| 交叉次數 | 由區間內 `osc` 與 `k`/`d` 的逐日序列推導，不落地儲存 |

查詢一律排除 `is_warmup = 1` 的列。

## Acceptance Criteria

### 指標正確性（以真實資料驗證）
- [ ] 以 2330、截至 `2026-08-27`、暖身 ≥ 250 個交易日運算，`dif` = `8.6416`、`dea` = `5.5740`、`osc` = `3.0677`（容差 ±0.001）
- [ ] 同上條件，`k_value` = `63.9981`、`d_value` = `56.3474`（容差 ±0.0001）
- [ ] 上述 `dif` 四捨五入為整數後為 `9`，與 TradingView 公開技術指標頁的 `MACD Level (12, 26)` 一致
- [ ] 對同一檔分別以 250 根與 400 根暖身運算，輸出區間內所有日期的指標值差異均小於 0.001
- [ ] `j_value` 等於 `3 × k_value - 2 × d_value`（逐列驗證）

### 暖身與增量
- [ ] 暖身區間的指標列確實寫入資料庫且 `is_warmup = 1`，並且不出現在統計 API 回應中
- [ ] 歷史不足 250 個交易日的標的仍回傳指標，且 `warmupSufficient` 為 `false`
- [ ] `mode: INCREMENTAL` 推進一日的結果，與對同一檔執行 `mode: FULL` 的同日結果完全相同
- [ ] 已有 `INDICATOR_REBUILD` 作業執行中時，再次呼叫重算端點回 `409` 與 `JOB_ALREADY_RUNNING`
- [ ] 增量推進時若前一交易日指標列不存在，該檔被標記為需全量重建，而非以初始值 50 起算

### 統計查詢
- [ ] 省略 `startDate`／`endDate` 時，預設回傳最新交易日往前兩個曆月的區間，`scope` 為對應值
- [ ] `stockIds=2330,2317` 回傳 2 個 item 且含 `series`，`scope` 為 `SELECTED`
- [ ] 省略 `stockIds` 時回傳全市場摘要、`scope` 為 `ALL`，且各 item **不含** `series` 欄位
- [ ] 全市場查詢帶 `includeSeries=true` 回 `400` 與 `SERIES_NOT_ALLOWED_FOR_ALL_SCOPE`，而非截斷資料
- [ ] `stockIds` 帶 51 檔時回 `400` 與 `TOO_MANY_STOCK_IDS`
- [ ] 交叉次數以相鄰交易日比較得出；於已知含交叉的區間驗證次數與發生日期正確
- [ ] 區間內無行情的標的回 `200`，`tradingDays` 為 `0`、`summary` 為 `null`
- [ ] 回應中的指標值為 4 位小數，且中間計算未使用四捨五入後的值
