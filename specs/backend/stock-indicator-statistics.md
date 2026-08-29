---
status: done
title: "MACD／KD 指標運算與兩個月統計"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 由日線 OHLC 推導 MACD 與 KD，落地遞迴狀態供增量推進，並提供兩個月區間統計查詢，支援多選股票與全市場；系統啟動補齊行情後自動接著重算指標"
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

### 指標尚未運算時的行為

逐日序列以 `stock_daily_price` 為主表、`stock_daily_indicator` 為**外連接**（LEFT JOIN），不可用內連接。

原因：行情與指標是兩個獨立的作業寫入的（`stock-price-ingestion` 與本 spec 的重算端點），行情已回補但指標尚未重算是完全正常的中間狀態。內連接會讓這種狀態下的查詢回傳空序列——對呼叫端而言與「這檔沒有行情」無法區分，K 線圖會畫不出任何一根 K 棒，儘管價格資料就在庫裡。

具體行為：

- 某日有行情但無指標列時，該日仍出現在 `series` 中，OHLC 與 `volume` 有值，`dif` / `dea` / `osc` / `k` / `d` / `j` 為 `null`。
- 區間內完全沒有任何指標列時，`summary` 中的價格與成交量統計照常計算，`latestDif` / `latestDea` / `latestOsc` / `latestK` / `latestD` / `latestJ` 與四個交叉次數欄位為 `null`（**不是 `0`**——`0` 代表「算過，沒有發生交叉」，`null` 代表「還沒算」，兩者在畫面上要顯示不同的內容）。
- 交叉次數的計算僅比較**兩日皆有指標值**的相鄰交易日；任一日指標為 `null` 時該組不計入，亦不中斷其後的比較。

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

#### 啟動補齊後自動重算

`specs/backend/stock-price-ingestion.md` 的啟動補齊只負責把日線行情補到今日；指標不會因為行情寫入就自動存在——本 spec 的指標是預先算好落地在 `stock_daily_indicator`、供統計 API 直接讀取的，必須有人觸發重算。若只補行情而不重算，日 K 頁會有 K 棒但 MACD／KD 兩個副圖是空的，等於只完成一半。

因此：**啟動補齊的行情批次全部結束後，自動以 `INCREMENTAL` 模式對全市場觸發一次指標重算**，不需要人手動打端點。

- 觸發時機是行情批次**完成之後**，不是與其並行。指標由日線 OHLC 推導，對一檔尚未補完行情的股票算指標會得到一段依據不完整資料的指標值，之後還得重算。
- **模式逐檔決定，不是整批固定一種**：某檔在 `stock_daily_indicator` 尚無任何指標列時以 `FULL` 觸發，已有指標列時以 `INCREMENTAL` 觸發。
  這不是最佳化，是正確性要求。本 spec 既有的規則已明定「增量推進時若找不到前一交易日的指標列，不得以初始值起算，須將該檔標記為需要全量重建」——`INCREMENTAL` 依設計**無法**從零起算，對一檔全新的股票觸發增量只會得到 `NEEDS_FULL_REBUILD` 而寫不出任何指標。因此「首次啟動一律用增量」是行不通的：剛 `/reset-env` 過的資料庫每一檔都沒有指標，整批都會失敗。
  反過來每次啟動都一律用 `FULL` 也不對——那會在每次重啟時把每檔的整條歷史序列重算一次，違背「重啟不得做重複的工」這條啟動補齊的核心約束。逐檔判斷才同時滿足兩者。
- 沿用既有的重算服務與 `INDICATOR_REBUILD` 進度追蹤，不另寫一套。與行情批次使用不同的 `jobType`，因此兩者的併發鎖互不影響。
- 同樣**不得阻塞啟動**、**不得因失敗而讓應用程式啟動失敗**：在背景執行，單檔失敗只落在該檔的 `FAILED`。
- 由啟動補齊的同一個設定開關控制：該開關關閉時，行情補齊與後續的指標重算都不觸發。
- 行情批次因外部來源不可用而整批沒有寫入任何新資料時，仍照常觸發重算——重算對沒有新行情的標的是空操作，不會產生錯誤，額外成本遠低於「判斷要不要跳過」的複雜度。

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

`series` 各列的 `dif` / `dea` / `osc` / `k` / `d` / `j`，以及 `summary` 的 `latest*` 與四個交叉次數欄位，在該日／該區間尚未運算指標時為 `null`（見上方「指標尚未運算時的行為」）。價格與成交量相關欄位不受影響，永遠有值。

數值格式：價格與漲跌金額 2 位小數、漲跌百分比 2 位小數、指標值 4 位小數。指標以資料庫中的完整精度參與所有計算，僅在序列化為回應時才四捨五入。

錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `stockIds` 超過 50 檔 | `400` | `{"code":"TOO_MANY_STOCK_IDS","limit":50}` |
| 全市場查詢帶 `includeSeries=true` | `400` | `{"code":"SERIES_NOT_ALLOWED_FOR_ALL_SCOPE"}` |
| `startDate` 晚於 `endDate` | `400` | `{"code":"INVALID_DATE_RANGE"}` |
| `stockIds` 含未知代號 | `400` | `{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}` |
| 指定標的在區間內無任何行情資料 | `200` | 該 item 的 `tradingDays` 為 `0`、`summary` 為 `null`、`series` 為空陣列 |
| 指定標的在區間內有行情但尚未運算指標 | `200` | `series` 照常回傳且 OHLC 有值，指標欄位為 `null`；`summary` 的 `latest*` 與交叉次數為 `null` |

### 資料來源對應

| 回應欄位 | 來源 |
|---|---|
| `stockId` / `stockName` | `stock.stock_id` / `stock.stock_name` |
| `open` / `high` / `low` / `close` / `volume` | `stock_daily_price.open_price` / `high_price` / `low_price` / `close_price` / `volume` |
| `dif` / `dea` / `osc` | `stock_daily_indicator.dif` / `dea` / `osc` |
| `k` / `d` / `j` | `stock_daily_indicator.k_value` / `d_value` / `j_value` |
| `warmupSufficient` | 該檔區間起日之前 `is_warmup = 0` 的指標列數是否達 250 |
| 交叉次數 | 由區間內 `osc` 與 `k`/`d` 的逐日序列推導，不落地儲存 |

查詢一律排除 `is_warmup = 1` 的列。序列以 `stock_daily_price` 為主表外連接 `stock_daily_indicator`，故指標欄位可為 `null`；價格欄位不可為 `null`。

## Acceptance Criteria

### 指標正確性（以真實資料驗證）
- [x] 以 2330、截至 `2026-08-27`、暖身 ≥ 250 個交易日運算，`dif` = `8.6416`、`dea` = `5.5740`、`osc` = `3.0677`（容差 ±0.001）
- [x] 同上條件，`k_value` = `63.9981`、`d_value` = `56.3474`（容差 ±0.0001）
- [x] 上述 `dif` 四捨五入為整數後為 `9`，與 TradingView 公開技術指標頁的 `MACD Level (12, 26)` 一致
- [x] 對同一檔分別以 250 根與 400 根暖身運算，輸出區間內所有日期的指標值差異均小於 0.001
- [x] `j_value` 等於 `3 × k_value - 2 × d_value`（逐列驗證）

### 暖身與增量
- [x] 暖身區間的指標列確實寫入資料庫且 `is_warmup = 1`，並且不出現在統計 API 回應中
- [x] 歷史不足 250 個交易日的標的仍回傳指標，且 `warmupSufficient` 為 `false`
- [x] `mode: INCREMENTAL` 推進一日的結果，與對同一檔執行 `mode: FULL` 的同日結果完全相同
- [x] 已有 `INDICATOR_REBUILD` 作業執行中時，再次呼叫重算端點回 `409` 與 `JOB_ALREADY_RUNNING`
- [x] 啟動補齊的行情批次結束後，未經任何手動呼叫，`stock_daily_indicator` 出現對應的指標列
- [x] 該次自動重算對全市場觸發，且在行情批次完成之後才開始（不與其並行）
- [x] 尚無任何指標列的股票以 `FULL` 模式重算並成功寫出指標；已有指標列的股票以 `INCREMENTAL` 模式接續
- [x] 對一個剛重置、每檔都沒有指標的資料庫執行啟動重算，全部 34 檔皆成功，`stock_sync_progress` 中 `INDICATOR_REBUILD` 沒有任何 `FAILED`／`NEEDS_FULL_REBUILD`
- [x] 自動重算在背景執行，不延後應用程式的可服務時間；重算失敗不影響應用程式啟動
- [x] 關閉啟動補齊設定後重啟，行情補齊與指標重算皆不觸發，`stock_daily_indicator` 列數不變
- [x] 暖身不足的區間（起日往前不足 250 個交易日）其指標列 `is_warmup = 1`，且統計 API 不對外輸出該段
- [x] `endDate` 省略時，預設值為**該檔自己**的最新交易日，而非全市場的最新交易日；一檔資料只到 2026-03-15 的股票，在市場上其他股票已有 2026-08-28 資料時，其預設區間迄日仍為 2026-03-15
- [x] 增量推進時若前一交易日指標列不存在，該檔被標記為需全量重建，而非以初始值 50 起算

### 統計查詢
- [x] 省略 `startDate`／`endDate` 時，預設回傳最新交易日往前兩個曆月的區間，`scope` 為對應值
- [x] `stockIds=2330,2317` 回傳 2 個 item 且含 `series`，`scope` 為 `SELECTED`
- [x] 省略 `stockIds` 時回傳全市場摘要、`scope` 為 `ALL`，且各 item **不含** `series` 欄位
- [x] 全市場查詢帶 `includeSeries=true` 回 `400` 與 `SERIES_NOT_ALLOWED_FOR_ALL_SCOPE`，而非截斷資料
- [x] `stockIds` 帶 51 檔時回 `400` 與 `TOO_MANY_STOCK_IDS`
- [x] 交叉次數以相鄰交易日比較得出；於已知含交叉的區間驗證次數與發生日期正確
- [x] 區間內無行情的標的回 `200`，`tradingDays` 為 `0`、`summary` 為 `null`
- [x] 某檔已有行情但尚未執行指標重算時，`series` 仍回傳完整的 OHLC 與成交量，指標欄位為 `null`（而非回空序列）
- [x] 上述情況下 `summary` 的 `macdGoldenCross` 等交叉次數為 `null` 而非 `0`
- [x] 區間中間有數日缺指標列時，其前後兩端有指標的相鄰交易日仍正常參與交叉判定
- [x] 回應中的指標值為 4 位小數，且中間計算未使用四捨五入後的值

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/src/test/java/com/stock/service/IndicatorCalculationServiceTest.java` (new — pure-math unit tests, no Spring/DB)
  - `develop/backend/src/test/java/com/stock/StockIndicatorStatisticsIntegrationTest.java` (new — 16 integration tests covering both endpoints)
  - No production code required changes; the implementation left by the two prior interrupted agents (controllers, services, mappers, XML, DTOs, domain, config) was reviewed line-by-line against this spec and against `specs/dba/stock-daily-indicator.md`, and found correct as written. No bugs were found in the MACD/KD recursion, the warmup flagging, the LEFT JOIN series query, the null-vs-zero cross-count contract, or the error handling. Nothing else was touched.

### What was verified and how
1. **Pure math** (`IndicatorCalculationServiceTest`, 7 tests, no DB): `J = 3K-2D` on every row; first-row EMA/K/D seeding; `computeIncrementalStep` reproduces the exact same row `computeFull` would produce for that day (bit-identical after the shared 8-decimal rounding point); the null-previous-state guard throws instead of silently seeding; warmup-flag boundary logic; and a synthetic 250-vs-400-day-lookback convergence property test (diff < 0.001 on all 50 output days) reproducing the spec's own measured-convergence claim without depending on a live data fetch.
2. **Integration tests** (`StockIndicatorStatisticsIntegrationTest`, 16 tests, real MySQL via `TI`-prefixed synthetic stock ids, cleaned up in `@BeforeEach`/`@AfterEach`): rebuild validation (`UNKNOWN_STOCK_ID`, `INVALID_DATE_RANGE`, `JOB_ALREADY_RUNNING`), INCREMENTAL == FULL for the same day end-to-end through the real async runner, INCREMENTAL routing to `FAILED`/`NEEDS_FULL_REBUILD` instead of seeding 50 when no previous row exists, warmup rows written as `is_warmup=1` and excluded from the statistics series, `warmupSufficient=false` for a thin-history stock, statistics default date-range resolution, `SELECTED` vs `ALL` scope (including the raw-JSON check that `series` is *absent*, not merely `null`, for `ALL` scope), `SERIES_NOT_ALLOWED_FOR_ALL_SCOPE` / `TOO_MANY_STOCK_IDS` / no-price-data (`tradingDays=0`, `summary=null`) responses, price-without-indicator (`series` OHLC populated, indicator fields `null`, cross counts `null` not `0`), a hand-crafted osc/k/d sequence with a deliberate mid-series gap proving cross-counting skips the gap without breaking later comparisons, and 4-decimal-place response rounding of an 8-decimal stored value.
3. **Live app verification against real market data** (`mvn spring-boot:run` on port 8080, stopped before finishing): inserted a real `2330` row, ran `POST /api/stocks/sync/backfill` against the live FinMind API for 2024-06-03..2026-08-27 (544 real trading days — this sandbox's external APIs serve data through the environment's current date, 2026-08-27/28), then ran `POST /api/stocks/indicators/rebuild` (`mode=FULL`, `startDate=2026-06-27`, `endDate=2026-08-27`, 250-day auto warmup). Result for 2026-08-27: `dif=8.64165936`, `dea=5.57401144`, `osc=3.06764792`, `k=63.99805947`, `d=56.34735184`, `j=79.29947473` — all within the spec's stated tolerance of its reference table (`dif` diff 6e-5 vs ±0.001 tolerance; `k`/`d` diff ~4-5e-5 vs ±0.0001 tolerance), `dif` rounds to `9` matching the spec's stated TradingView cross-check, and `j = 3k-2d` holds exactly. Re-ran the same narrow-window rebuild with `app.indicator.warmup-trading-days` temporarily bumped to `400` (app restarted, config restored to `250` afterward) — the two runs' `dif`/`dea` differ by ~3e-7 and `k`/`d` are bit-identical across all 43 output days, confirming the spec's warmup-convergence claim on live 2330 data, not just synthetic data. Also reproduced `INCREMENTAL == FULL` for the real last trading day (deleted the row, re-ran `mode=INCREMENTAL`, got back the bit-identical row) and the `409 JOB_ALREADY_RUNNING` race by firing two real concurrent HTTP requests. Also confirmed `GET /api/stocks/statistics` over this real data serializes `latestDif=8.6417` etc. at 4 decimal places, matching the DB-precision computation.
4. Also discovered and confirmed (not a bug, by design): `warmupSufficient` per the spec's own "資料來源對應" contract counts `is_warmup=0` rows before the query window's start date — so a stock whose *only* rebuild ever was a narrow-window `FULL` rebuild (explicit `startDate`) will read `warmupSufficient=false` even with ample underlying price history, because everything before that `startDate` is by construction flagged `is_warmup=1`. Verified live: re-running the same 2330 rebuild without an explicit `startDate` (so the entire 544-day history becomes real, non-warmup output, per the documented "no explicit startDate → mark nothing as warmup" contract) flips `warmupSufficient` to `true` for the same 2-month statistics window. This is the intended production lifecycle (an initial full-history rebuild followed by daily `INCREMENTAL` steps, each of which is always non-warmup) — not something this task's scope covers changing, but noted here since it's easy to misread as a bug.
5. Full suite (`mvn -f develop/backend/pom.xml test`, all classes including `StockCatalogIntegrationTest` and `StockPriceIngestionIntegrationTest`): **55/55 passed**, 0 failures/errors. `stock`, `stock_daily_price`, `stock_daily_indicator`, `stock_sync_progress`, `stock_minute_price`, `stock_minute_fetch_status` all confirmed at 0 rows after the full suite and after the live manual verification (all seeded/computed rows for `2330` and `TI*` ids were deleted). Port 8080 confirmed free before finishing.

### Increment 2 — 2026-08-30
- Status: DONE (pending checkbox sign-off by the requester)
- Scope: only the unchecked "啟動補齊後自動重算" acceptance criteria — chaining a single whole-market `INCREMENTAL` indicator rebuild onto the actual completion of the price catch-up added in `specs/backend/stock-price-ingestion.md` Increment 2's `StartupCatchUpRunner`.
- Files changed:
  - `develop/backend/src/main/java/com/stock/service/BackfillRunner.java` — `run(...)` now returns `CompletableFuture<Void>` instead of `void`. Spring's `@Async` support treats a `CompletableFuture`-returning async method specially: the executor runs the whole method body (the per-stock loop and its `finally { jobRunningRegistry.finish(...) }`) synchronously on the async thread before the returned `CompletableFuture.completedFuture(null)` marker is constructed, so the *outer* future the caller receives from the proxy only completes once that body has actually finished — this is what makes the batch's real completion observable from outside, as opposed to the previous fire-and-forget `void` signature where the calling thread had no way to know when the batch (as opposed to its scheduling) was done.
  - `develop/backend/src/main/java/com/stock/service/BackfillOutcome.java` — new; a small internal (non-DTO, never serialized) pairing of the existing `BackfillResponse` with the `CompletableFuture<Void>` above. Kept out of `BackfillResponse` itself so the HTTP-facing 202 body never risks carrying a non-serializable `CompletableFuture` field.
  - `develop/backend/src/main/java/com/stock/service/StockSyncService.java` — the body of `startBackfill` was moved, unchanged, into a new private `startBackfillInternal(...)` returning `BackfillOutcome`; `startBackfill(request)` now just returns `.getResponse()` (same signature/behavior as before, no caller — controller or test — needed to change), and a new `startBackfillTrackingCompletion(request)` returns the full `BackfillOutcome` for internal callers that need the completion future.
  - `develop/backend/src/main/java/com/stock/service/StartupCatchUpRunner.java` — now also takes `IndicatorRebuildService` in its constructor. After scheduling the price catch-up via `startBackfillTrackingCompletion`, it registers `outcome.getCompletion().whenComplete((r, ex) -> triggerIndicatorRebuild())` — i.e. it chains off the batch's actual completion signal, not off the (already-returned) scheduling call. `triggerIndicatorRebuild()` builds an `IndicatorRebuildRequest` with `mode="INCREMENTAL"` and `stockIds` omitted (whole market) and calls `IndicatorRebuildService.rebuild(...)` directly (no HTTP hop, no parallel implementation — reuses the exact same service/runner/progress-tracking as the manual `POST /api/stocks/indicators/rebuild` endpoint, under the independent `INDICATOR_REBUILD` job-type lock). Both the price-catch-up-scheduling call and the indicator-rebuild-triggering call are wrapped in their own try/catch that only logs on failure, per the existing "must never fail/block startup" contract already established for the price side. The whole chain is still gated by the single existing `app.backfill.startup-catch-up.enabled` switch (the early return when disabled happens before either service is touched) — no second switch was added.
  - `develop/backend/src/test/java/com/stock/service/StartupCatchUpRunnerTest.java` — new; 5 pure Mockito unit tests (no Spring context, no DB) directly exercising `StartupCatchUpRunner` with `StockSyncService`/`IndicatorRebuildService` mocked and a manually-controlled `CompletableFuture` standing in for the price batch's completion signal.
- Notes:
  - **Why a unit test, not a full-context integration test**: the ordering property under test — "rebuild happens after completion, not in parallel with it" — is about the interaction between two async hand-offs, not about SQL or HTTP wiring (both of which are already covered by `StockPriceIngestionIntegrationTest` and `StockIndicatorStatisticsIntegrationTest`). Driving this through a real `ApplicationReadyEvent` boot would require either a real, slow, multi-stock backfill to observe genuine sequencing, or race-prone `Thread.sleep`-based assertions on a real async executor — both are slower and less deterministic than directly controlling the completion future's timing with Mockito, and neither would test anything about the ordering logic that this class doesn't already own.
  - **New tests** (`StartupCatchUpRunnerTest`):
    - `indicatorRebuild_firesOnlyAfterPriceBatchCompletes_notBeforeOrInParallel` — calls `onApplicationReady()` with a still-incomplete future backing the price batch; asserts `indicatorRebuildService` has zero interactions at that point (`verifyNoInteractions`); then completes the future and asserts `rebuild(...)` was called exactly once, with `mode="INCREMENTAL"` and `stockIds` null (whole market).
    - `indicatorRebuild_stillFires_whenPriceBatchCompletesExceptionally` — models the spec's "even a price batch that wrote no new rows must still trigger the rebuild" requirement at the future level (`completeExceptionally`, standing in for the batch ending abnormally): the rebuild still fires.
    - `suppressedWhenStartupCatchUpDisabled_neitherPriceBatchNorRebuildTriggers` — `enabled=false`; asserts `verifyNoInteractions` on **both** `stockSyncService` and `indicatorRebuildService` — the single shared switch, not a second independent one, gates both.
    - `rebuildFailure_doesNotPropagate` — `indicatorRebuildService.rebuild(...)` throws; asserts completing the price-batch future (which synchronously runs the `whenComplete` callback on the calling thread in this test) does not throw out to the caller.
    - `priceBatchSchedulingFailure_doesNotPropagate_andNeverTriggersRebuild` — `startBackfillTrackingCompletion(...)` itself throws (e.g. `JobAlreadyRunningException`, modeling another batch already running); asserts `onApplicationReady()` does not throw and the rebuild is never triggered (since the price batch never actually started in this case, there is nothing to chain a rebuild after).
  - Verification performed:
    - `mvn -f develop/backend/pom.xml compile` — clean.
    - `mvn -f develop/backend/pom.xml test -Dtest=StartupCatchUpRunnerTest` — `Tests run: 5, Failures: 0, Errors: 0`.
    - `mvn -f develop/backend/pom.xml test` (full suite) — `Tests run: 79, Failures: 0, Errors: 0` (74 pre-existing + 5 new).
    - Direct `mysql` query after the full suite: `stock` = 34 total / 34 active, `stock_daily_price` = 0, `stock_daily_indicator` = 0, `stock_sync_progress` = 0 — unchanged from the pre-task state, confirming the new unit test (which touches no DB at all) left no residue.
    - Did **not** perform a live `mvn spring-boot:run` run against real external APIs, per instructions (port availability was not verified/consumed for this increment).
  - Not implemented / explicitly out of scope for this increment: any change to the actual MACD/KD math, warmup logic, or the statistics query endpoint — all untouched from Increment 1. The remaining unchecked "啟動補齊後自動重算" criteria that are about the *price* side's own behavior (e.g. `stock_daily_price` populating on boot, `stock` count staying at 34) are covered by `specs/backend/stock-price-ingestion.md`'s own Increment 2, not by this file.

### Increment 3 — 2026-08-30 (defect fix: per-stock FULL/INCREMENTAL routing)
- Status: DONE
- Root cause of the live-run defect: Increment 2 chained the startup rebuild onto the price batch's completion, but fired it with a single, batch-wide `mode="INCREMENTAL"` for the whole market. On a freshly reset database every stock has zero rows in `stock_daily_indicator`, so `IndicatorRebuildRunner`'s own pre-existing, correct guard ("增量推進時若找不到前一交易日的指標列...須將該檔標記為需要全量重建") rejected every single stock with `NEEDS_FULL_REBUILD`/`FAILED`. Live run: 34/34 `PRICE_BACKFILL` DONE (5372 rows), but 34/34 `INDICATOR_REBUILD` FAILED and `stock_daily_indicator` left at 0 rows. This is exactly the "always-INCREMENTAL fails outright on a freshly reset database" failure mode this spec's "啟動補齊後自動重算" section now names explicitly as the reason the mode must be decided per stock, not fixed for the whole batch.
- Fix: the startup path now decides FULL vs INCREMENTAL independently for each stock, per the corrected spec bullet above — a stock with no rows at all in `stock_daily_indicator` is rebuilt with `FULL`; a stock that already has indicator rows is advanced with `INCREMENTAL`. The manual `POST /api/stocks/indicators/rebuild` endpoint's contract is untouched — it still takes one explicit `mode` applied to its whole batch, exactly as documented above in "API 契約".
- Files changed:
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyIndicatorMapper.java` / `develop/backend/src/main/resources/mapper/StockDailyIndicatorMapper.xml` — new `findStockIdsWithAnyIndicator(stockIds, paramKey)`: batch existence check (one query, not one per stock) for which of a candidate stock list already has at least one indicator row (any `is_warmup` value) for the given `param_key`.
  - `develop/backend/src/main/java/com/stock/service/IndicatorRebuildRunner.java` — `run(...)` now takes `Map<String, String> computeModeByStockId` instead of a single `String computeMode`, and looks the mode up per stock id inside the loop (defaulting to `FULL` if absent). This is the minimal change that lets one batch mix both modes while still sharing the exact same per-stock `processFull`/`processIncremental` code paths, failure isolation, and `INDICATOR_REBUILD` progress tracking as before.
  - `develop/backend/src/main/java/com/stock/service/IndicatorRebuildService.java` — the manual `rebuild(request)` method now builds a uniform `stockId -> computeMode` map (same mode for every id, unchanged external behavior) before calling the runner. New `rebuildForStartup()` method: whole market, no explicit date window (mirrors the manual endpoint's all-stocks/open-ended call), same `INDICATOR_REBUILD` job-type lock and progress-reset flow as `rebuild`, but calls the new `findStockIdsWithAnyIndicator` on the processable id list and routes each id to `INCREMENTAL` (has existing rows) or `FULL` (does not) before invoking the same runner. No parallel implementation — same runner, same mapper, same progress table as the manual path.
  - `develop/backend/src/main/java/com/stock/service/StartupCatchUpRunner.java` — `triggerIndicatorRebuild()` now calls `indicatorRebuildService.rebuildForStartup()` instead of building an `IndicatorRebuildRequest` with a hardcoded `mode="INCREMENTAL"`. Javadoc updated to describe the per-stock decision. The `IndicatorRebuildRequest` import was removed (no longer used here).
  - `develop/backend/src/test/java/com/stock/service/StartupCatchUpRunnerTest.java` — updated the two tests that previously asserted a captured `IndicatorRebuildRequest` with `mode="INCREMENTAL"` to instead verify `indicatorRebuildService.rebuildForStartup()` was called (no-arg); the disabled-switch, failure-isolation, and scheduling-failure tests were updated to mock/verify `rebuildForStartup()` in place of `rebuild(any())` but are otherwise unchanged in intent.
  - `develop/backend/src/test/java/com/stock/service/IndicatorRebuildServiceTest.java` — new; 5 pure Mockito unit tests (no Spring context, no DB) for `rebuildForStartup()`: a stock with no existing indicator rows is routed to `FULL`; a stock with existing indicator rows is routed to `INCREMENTAL`; a mixed 3-stock batch routes each stock independently to the correct mode; an empty processable list skips the indicator-existence query entirely and still calls the runner with an empty list; and `JOB_ALREADY_RUNNING` is thrown (and the runner never invoked) when the `INDICATOR_REBUILD` job type is already held by the registry.
- Database cleanup performed (residue from the defective live run, not real history): `DELETE FROM stock_sync_progress WHERE job_type = 'INDICATOR_REBUILD'` — removed all 34 `FAILED`/`attempt_count=1` rows so the next startup rebuild starts every stock from `PENDING` rather than immediately hitting the retry-attempt cap. `stock_sync_progress` `PRICE_BACKFILL` rows (34, all `DONE`) and `stock_daily_price` (5372 rows) were left completely untouched.
- Verification performed:
  - `mvn -f develop/backend/pom.xml compile` — clean.
  - `mvn -f develop/backend/pom.xml test -Dtest=StartupCatchUpRunnerTest,IndicatorRebuildServiceTest` — `Tests run: 10, Failures: 0, Errors: 0`.
  - `mvn -f develop/backend/pom.xml test` (full suite) — `Tests run: 84, Failures: 1, Errors: 0`. The one failure, `StockIndicatorStatisticsIntegrationTest.statistics_defaultDateRange_isLatestTradeDateMinusTwoCalendarMonths` (expected `2026-03-15`, got `2026-08-28`), is **not caused by this increment's change** — confirmed pre-existing and environmental: it reproduces in isolation on an unmodified checkout of this file (verified via `git stash` on the tracked files this increment touched), and its actual value (`2026-08-28`) is exactly `MAX(trade_date)` of the real `stock_daily_price` table left behind by the live verification run this task's DB cleanup was explicitly told to preserve (confirmed by direct query: `SELECT MAX(trade_date) FROM stock_daily_price` → `2026-08-28`). Root cause is a separate, pre-existing defect in `StockStatisticsService`: the default-`endDate` resolution calls `priceMapper.findGlobalLatestTradeDate()` (latest trade date across the *entire* table) even for `SELECTED`-scope queries against a single synthetic test stock, instead of scoping the lookup to the requested `stockIds` — this only surfaces once real, more-recent-dated rows exist in the same shared dev database the integration tests run against (test and dev point at the same `stock` schema; there is no isolated test database). Rerunning the full suite with only that one test excluded: `Tests run: 83, Failures: 0, Errors: 0` — every other test, including all newly added/modified tests for this increment, passes. This pre-existing defect is outside this increment's scope (per the task's "targeted fix, not a rewrite" instruction) and is not fixed here; it would need its own spec-driven fix to `StockStatisticsService`'s date-range resolution for `SELECTED` scope.
  - Post-cleanup `mysql` verification: `SELECT job_type, status, COUNT(*) FROM stock_sync_progress GROUP BY job_type, status` → `PRICE_BACKFILL/DONE = 34`, zero `INDICATOR_REBUILD` rows. `SELECT COUNT(*) FROM stock_daily_price` → `5372` (unchanged).
  - Did not run `mvn spring-boot:run` per instructions; the actual live-restart re-verification (34/34 `INDICATOR_REBUILD` succeeding, `stock_daily_indicator` populated) is left to the requester.
- Notes: the acceptance-criteria checkboxes under "暖身與增量" for "啟動補齊後自動重算" are intentionally left unchecked here for the requester's own sign-off after the live restart, per instructions.

### Increment 4 — 2026-08-30 (defect fix: `GET /api/stocks/statistics` default `endDate` was market-wide, not per-stock)
- Status: DONE
- Root cause: `StockStatisticsService.getStatistics(...)` resolved an omitted `endDate` via `priceMapper.findGlobalLatestTradeDate()` — `SELECT MAX(trade_date) FROM stock_daily_price` with no `stock_id` filter at all — and then derived the default `startDate` from that same unscoped value via `priceMapper.findGlobalFirstTradeDateOnOrAfter(...)`, again with no `stock_id` filter. Both defaults were therefore market-wide regardless of `scope` (`SELECTED` or `ALL`), contradicting this spec's "統計區間" requirement ("`endDate` 省略時，取該檔在 `stock_daily_price` 中的最新交易日") and the acceptance criterion this increment closes. The bug was latent while the dev database held no real price data (every stock's own latest date happened to coincide with the table-wide max) and was only exposed once a live ingestion run populated 34 real stocks through 2026-08-28, at which point a synthetic test stock whose seeded data stops at 2026-03-15 started reading back the *other* stocks' 2026-08-28 as its own default `endDate`.
- Fix: replaced the two unscoped mapper queries with `stock_id`-scoped equivalents, both filtered to exactly the `targetIds` the service already resolved (the `SELECTED` list or the `ALL`-scope active-stock list) — never a query per stock id:
  - `StockDailyPriceMapper.findGlobalLatestTradeDate()` → `findLatestTradeDatesByStockIds(stockIds)`: one `SELECT stock_id, MAX(trade_date) ... WHERE stock_id IN (...) GROUP BY stock_id` returning one row per stock that has any price data; the service takes the max of these per-stock latest dates as the response's effective `endDate` (for a single selected stock this is exactly that stock's own latest date; for `ALL` scope it is scoped to active stocks instead of the whole table, including any inactive/delisted rows).
  - `StockDailyPriceMapper.findGlobalFirstTradeDateOnOrAfter(candidateStart)` → `findFirstTradeDateOnOrAfterByStockIds(stockIds, candidateStart)`: same `stock_id IN (...)` scoping added to the existing `MIN(trade_date) WHERE trade_date >= candidateStart` query, so the default `startDate` (`endDate` minus two calendar months, snapped to the first actual trading day) is derived only from the requested stocks' own history, not from any other stock's earlier or later data. Both empty-`targetIds` edge cases (no active stocks) are guarded in the service to skip the now-mandatory `IN (...)` clause rather than emit `IN ()`.
  - New domain class `StockLatestTradeDate` (`stockId`, `tradeDate`) to carry the grouped query's rows — follows the same shape as the existing `HighLow`/`PriceStats` result-object convention in this package.
  - `StockStatisticsService.getStatistics(...)`: default-`endDate` branch now loops the grouped-query result to take the max per-stock date (still a single query, no N+1); default-`startDate` branch now calls the scoped mapper method with the already-resolved `targetIds`. Explicitly-supplied `startDate`/`endDate` query parameters are untouched — this only changes what happens when one or both are omitted. No change to the endpoint's request or response contract: `StatisticsResponseDto`/`StatisticsItemDto`/`StatisticsSummaryDto`/`StatisticsSeriesRowDto` fields are byte-for-byte the same as before this fix.
- Files changed:
  - `develop/backend/src/main/java/com/stock/domain/StockLatestTradeDate.java` (new)
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` — replaced `findGlobalLatestTradeDate`/`findGlobalFirstTradeDateOnOrAfter` with `findLatestTradeDatesByStockIds`/`findFirstTradeDateOnOrAfterByStockIds`
  - `develop/backend/src/main/resources/mapper/StockDailyPriceMapper.xml` — corresponding `<select>` rewrites, both scoped with a `stock_id IN (...)` `<foreach>`
  - `develop/backend/src/main/java/com/stock/service/StockStatisticsService.java` — default-`endDate`/`startDate` resolution now scoped to `targetIds`
- Verification performed:
  - `mvn -f develop/backend/pom.xml compile` — clean.
  - `mvn -f develop/backend/pom.xml test` (full suite, against the live dev database with real 2330-and-friends data already loaded) — `Tests run: 84, Failures: 0, Errors: 0`, including `StockIndicatorStatisticsIntegrationTest.statistics_defaultDateRange_isLatestTradeDateMinusTwoCalendarMonths` now passing (previously the sole failure, `expected: <2026-03-15> but was: <2026-08-28>`).
  - Response contract confirmed unchanged: no diff to any file under `develop/backend/src/main/java/com/stock/dto/` or `develop/backend/src/main/java/com/stock/controller/` for this endpoint.
  - Live data preserved (queried directly, not assumed): `stock` = 34, `stock_daily_price` = 5372, `stock_daily_indicator` = 5372, `stock_sync_progress` `PRICE_BACKFILL/DONE` = 34, `INDICATOR_REBUILD/DONE` = 34 — all identical to the counts before this fix. Zero leftover `TI%`-prefixed (or other test-prefixed) rows in `stock` after the test run's own `@AfterEach` cleanup.
- Notes: did not run `mvn spring-boot:run` (port 8080 was not occupied by a stray process, but the instructions for this task said not to start it). The acceptance-criteria checkbox this increment addresses is left unchecked for the requester's own sign-off, per instructions.
