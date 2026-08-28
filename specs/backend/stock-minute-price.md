---
status: done
title: "分 K 隨選抓取與查詢 API"
requirement: "前端 K 線瀏覽 — 在日 K 圖上點選某一個交易日後，要能看到該日的分 K 走勢"
depends_on: [stock-price-ingestion, stock-catalog]
---

# 分 K 隨選抓取與查詢 API — Backend Spec

## Overview

提供「某檔股票某一個交易日的分鐘 K 棒」，供前端分 K 圖表（`specs/frontend/stock-minute-chart.md`）繪製。

與日線的抓取策略**刻意不同**：日線是排程主動全市場抓取，分 K 是**隨點選隨抓取（on-demand）並永久快取**。

理由是量級。全市場分 K 為每年約 1.46 億列（2200 檔 × 約 240 個交易日 × 271 根）；而使用者實際會看的分 K，是他點開的那少數幾個「股票 × 日期」組合。主動全抓等於為了 0.01% 的實際使用量，付出 1.46 億列的儲存與數十萬次的外部請求。隨選抓取讓成本正比於真實使用，且抓過就存進 `stock_minute_price`，第二次以後為純資料庫讀取。

## Requirements

### 資料源與其限制（設計前提，已實測確認）

- **不能沿用日線的資料源。** FinMind 的分 K 資料集 `TaiwanStockKBar` 在免費層被拒絕：請求回 HTTP 400 `{"msg":"Your level is free. Please update your user level."}`，任何日期皆然。這與日線 `TaiwanStockPrice` 免費層可用的情形不同，不是參數帶錯。

- **採用的來源**：`https://query1.finance.yahoo.com/v8/finance/chart/<代號>.TW?interval=1m&period1=<起始epoch秒>&period2=<結束epoch秒>`
  上櫃股票的代號後綴為 `.TWO`，上市為 `.TW`，依 `stock.market` 決定。

  回應為**平行陣列**結構：`chart.result[0].timestamp[]` 為每根 K 棒起始時間的 epoch 秒，`chart.result[0].indicators.quote[0]` 之下的 `open[]` / `high[]` / `low[]` / `close[]` / `volume[]` 與其**逐一索引對應**。正規化時必須依索引配對，不可各自獨立處理。

- **關鍵限制：`interval=1m` 只提供最近 30 天。** 超出範圍的請求不回空資料，而是回 HTTP 422：
  `{"chart":{"result":null,"error":{"code":"Unprocessable Entity","description":"1m data not available for startTime=... and endTime=.... The requested range must be within the last 30 days."}}}`

  這是**產品層級的限制，必須誠實地傳達到畫面上**，不可假裝資料只是暫時抓不到。已在 30 天內抓取並存入的日期會永久保留，因此系統的分 K 涵蓋範圍會隨使用逐漸累積——但從未被點開過的舊日期，事後無法補抓。

- **超窗判斷在本地完成，不發請求。** 目標日期早於「今日減 30 天」時直接標記 `OUT_OF_WINDOW`，不對來源發出注定失敗的請求。

- **速率控制沿用 `stock-price-ingestion` 既有的請求間隔與指數退避機制**，不另做一套。分 K 由使用者點擊觸發，尖峰更不可預測，節流比排程抓取更必要。

### 抓取決策（每次查詢的第一步）

依 `stock_minute_fetch_status`（見 `specs/dba/stock-minute-fetch-status.md`）決定是否請求外部來源：

| 狀態表的情形 | 動作 |
|---|---|
| 無對應列 | 請求外部來源 |
| `AVAILABLE`，且目標日**非今日** | 直接讀資料庫，零外部請求 |
| `AVAILABLE`，目標日**為今日**且 `fetched_at` 早於當日 14:00 | 重新請求（盤中資料會持續增長） |
| `AVAILABLE`，目標日為今日且 `fetched_at` 在當日 14:00 之後 | 直接讀資料庫（該日已收盤定案） |
| `NO_DATA` / `OUT_OF_WINDOW` / `NOT_A_TRADING_DAY` | 直接回覆該狀態，零外部請求 |
| `FAILED` 且 `attempt_count` 未達上限（預設 3） | 請求外部來源 |
| `FAILED` 且已達上限 | 直接回覆失敗，零外部請求；僅 `refresh=true` 可強制重試 |

台股收盤為 13:30，加上資料源的延遲緩衝取 14:00 為「當日定案」的分界。

### 正規化規則

- epoch 秒一律以 **Asia/Taipei** 轉換為當地時間；`bar_time` 取 `HH:MM:00`。以 UTC 或伺服器預設時區換算會讓整條序列偏移 8 小時，畫出一張時間軸完全錯位但看起來仍「像」K 線圖的圖——這類錯誤不會拋出例外，只能靠檢查首根 K 棒是否為 09:00 發現。
- **落在 09:00–13:30 之外的 K 棒一律丟棄**（來源可能夾帶盤前試撮或延伸時段資料）。
- `open` / `high` / `low` / `close` 任一為 `null` 的索引代表該分鐘無成交，**整根丟棄，不得補零、不得沿用前一根收盤價填補**。理由見 `specs/dba/stock-minute-price.md`。
- 來源的 `volume` 若為當日累計值，必須先轉為單根增量再寫入。
- 寫入一律 UPSERT；K 棒寫入與 `stock_minute_fetch_status` 更新必須在**同一個交易邊界**內完成。

### 週期聚合

`interval` 大於 1 時，由已存的 1 分 K **於查詢時聚合**，不另外落地（理由見 `specs/dba/stock-minute-price.md`）。

聚合規則：自 **09:00 為錨點**、每 `interval` 分鐘切一組，組內 `open` 取第一根的開盤、`high` 取最大、`low` 取最小、`close` 取最後一根的收盤、`volume` 取總和。**組內完全無 K 棒時不產生該組**，不補零值 K 棒。

以 09:00 為固定錨點（而非以當日第一根 K 棒為錨點）確保同一檔不同日、以及不同檔同一日的 5 分 K 落在相同的時間格線上，否則跨日比對會出現半根 K 棒的位移。

## Implementation Details

### API 契約

#### 分 K 查詢

```
GET /api/stocks/{stockId}/minute-bars
```

Query 參數：

| 參數 | 型別 | 必填 | 預設 | 說明 |
|---|---|---|---|---|
| `tradeDate` | date | 是 | — | 目標交易日 |
| `interval` | int | 否 | `1` | K 棒週期分鐘數，允許 `1` / `5` / `15` / `30` / `60` |
| `refresh` | boolean | 否 | `false` | `true` 時忽略快取與重試上限，強制向來源重抓 |

Response `200`：
```json
{
  "stockId": "2330",
  "stockName": "台積電",
  "tradeDate": "2026-08-25",
  "interval": 1,
  "dataStatus": "AVAILABLE",
  "source": "YAHOO",
  "fetchedAt": "2026-08-27T09:12:33",
  "barCount": 271,
  "dailySummary": {
    "open": 2355.00,
    "high": 2380.00,
    "low": 2350.00,
    "close": 2375.00,
    "volume": 18234000
  },
  "bars": [
    { "barTime": "09:00", "open": 2355.00, "high": 2360.00, "low": 2355.00, "close": 2360.00, "volume": 1523 },
    { "barTime": "09:01", "open": 2360.00, "high": 2360.00, "low": 2355.00, "close": 2355.00, "volume": 842 }
  ]
}
```

`dataStatus` 的取值對應 `stock_minute_fetch_status.status`，其中 `AVAILABLE` / `NO_DATA` / `OUT_OF_WINDOW` / `NOT_A_TRADING_DAY` 四者同名同義；資料庫的 `FAILED` 對外序列化為 `FETCH_FAILED`（對呼叫端而言「抓取失敗」比裸的 `FAILED` 明確）：

| `dataStatus` | 意義 | `bars` | `dailySummary` |
|---|---|---|---|
| `AVAILABLE` | 有分 K 資料 | 有內容 | 有值 |
| `NO_DATA` | 該日為交易日，但來源無分鐘成交資料 | `[]` | 有值 |
| `OUT_OF_WINDOW` | 該日超出來源提供的 30 日時間窗，無法取得且無法補抓 | `[]` | 有值 |
| `NOT_A_TRADING_DAY` | 該日在 `stock_daily_price` 中無對應日線 | `[]` | `null` |
| `FETCH_FAILED` | 向來源請求失敗 | `[]` | 有值 |

**即使沒有分 K，只要該日是交易日就必須回傳 `dailySummary`**——前端要據此顯示「這天的日線是這樣，但分 K 取不到」，而不是一片空白。

`FETCH_FAILED` 時額外回傳 `"message"` 欄位，內容為 `stock_minute_fetch_status.last_error`，供前端顯示失敗原因。

驗證與錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `stockId` 不存在於 `stock` 主檔 | `404` | `{"code":"STOCK_NOT_FOUND","stockId":"9999"}` |
| 缺少 `tradeDate` | `400` | `{"code":"MISSING_TRADE_DATE"}` |
| `tradeDate` 格式非法 | `400` | `{"code":"INVALID_DATE_FORMAT"}` |
| `tradeDate` 晚於今日 | `400` | `{"code":"FUTURE_TRADE_DATE"}` |
| `interval` 非允許值 | `400` | `{"code":"INVALID_INTERVAL","allowed":[1,5,15,30,60]}` |

「該日無分 K」一律以 `200` + `dataStatus` 表達，不使用 `404`。取不到資料是本功能的**預期常態**（30 日以外的日期一律如此），不是錯誤；用錯誤碼表達會讓前端無法區分「這天沒有分 K」與「這支 API 壞了」。

### 處理流程

1. 驗證參數 → 查 `stock` 主檔確認代號存在（不存在回 `404`）。
2. 查 `stock_daily_price` 確認 `tradeDate` 為該檔的交易日；否則寫入狀態 `NOT_A_TRADING_DAY` 並回覆。
3. 依「抓取決策」表判斷是否需要請求外部來源。
4. 需要抓取時：`tradeDate` 早於今日減 30 天 → 直接寫入 `OUT_OF_WINDOW` 並回覆，不發請求；否則以 `Asia/Taipei` 當日 00:00～次日 00:00 換算 `period1`／`period2` 發出請求。
5. 依「正規化規則」處理回應 → UPSERT 進 `stock_minute_price` → 於同一交易內更新 `stock_minute_fetch_status`（狀態、`bar_count`、`source`、`fetched_at`）。
6. 自資料庫讀取該日 1 分 K → 依 `interval` 聚合 → 併入 `dailySummary` 後回覆。

### 資料來源對應

| 回應欄位 | 來源 |
|---|---|
| `stockId` / `stockName` | `stock.stock_id` / `stock.stock_name` |
| `bars[].barTime` | `stock_minute_price.bar_time`，序列化為 `HH:mm` |
| `bars[].open` / `high` / `low` / `close` / `volume` | `stock_minute_price.open_price` / `high_price` / `low_price` / `close_price` / `volume`（`interval > 1` 時為聚合值） |
| `dataStatus` / `source` / `fetchedAt` | `stock_minute_fetch_status.status`（`FAILED` → `FETCH_FAILED`）/ `source` / `fetched_at` |
| `dailySummary` | `stock_daily_price.open_price` / `high_price` / `low_price` / `close_price` / `volume` |

`barCount` 一律為**回應中 `bars` 的實際長度**（聚合後），而非狀態表中的 1 分 K 根數。

### 數值格式

價格 2 位小數，成交量為整數。

## Acceptance Criteria
- [x] `GET /api/stocks/2330/minute-bars?tradeDate=<近 30 日內的交易日>` 首次呼叫觸發外部抓取，回傳 `dataStatus: AVAILABLE` 且 `bars` 首根 `barTime` 為 `09:00`、末根不晚於 `13:30`
- [x] 正常交易日的 1 分 K 根數為 271（09:00–13:30 含首尾）
- [x] 同一 `(stockId, tradeDate)` 第二次呼叫**不再發出任何外部請求**（以請求記錄或來源呼叫計數驗證），回應內容與第一次相同
- [x] `tradeDate` 早於今日減 30 天時回 `dataStatus: OUT_OF_WINDOW`，且**未對外部來源發出請求**
- [x] `OUT_OF_WINDOW` / `NO_DATA` 的日期重複呼叫多次，外部請求數維持為 0
- [x] 已標記 `AVAILABLE` 的過往日期，其分 K 在超過來源 30 日視窗之後仍可正常讀取（資料已永久落地）
- [x] `interval=5` 的聚合結果：`open` 等於組內第一根的 `open`、`close` 等於最後一根的 `close`、`high`／`low` 為組內極值、`volume` 為組內總和（以 1 分 K 原始資料逐組驗證）
- [x] `interval=5` 的首根 K 棒時間為 `09:00`（以 09:00 為錨點，非以首根有成交的 K 棒為錨點）
- [x] 來源回應中 OHLC 為 `null` 的分鐘不產生 K 棒；`stock_minute_price` 中**不存在任何價格為 0 的列**
- [x] 時間軸以 Asia/Taipei 換算：以已知交易日驗證首根 K 棒為 `09:00` 而非 `01:00` 或 `17:00`
- [x] 非交易日（如週六）回 `200` 與 `dataStatus: NOT_A_TRADING_DAY`，`dailySummary` 為 `null`
- [x] 分 K 取不到時（`NO_DATA` / `OUT_OF_WINDOW` / `FETCH_FAILED`），只要該日為交易日就仍回傳 `dailySummary`
- [x] `interval=3` 回 `400` 與 `INVALID_INTERVAL`
- [x] `tradeDate` 為未來日期回 `400` 與 `FUTURE_TRADE_DATE`
- [x] 資料庫狀態為 `FAILED` 的日期，API 回應的 `dataStatus` 為 `FETCH_FAILED`；其餘四種狀態的名稱在 API 與資料庫中相同
- [x] 抓取失敗後 `stock_minute_fetch_status` 的 `attempt_count` 累加且 `last_error` 有內容；達上限後不再自動重試，但 `refresh=true` 仍可強制重抓
- [x] K 棒寫入與狀態更新在同一交易內完成：模擬狀態更新失敗時，該次的 K 棒亦不留存（不出現「狀態為 AVAILABLE 但無 K 棒」）

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/domain/StockMinutePrice.java` (new)
  - `develop/backend/src/main/java/com/stock/domain/StockMinuteFetchStatus.java` (new)
  - `develop/backend/src/main/java/com/stock/mapper/StockMinutePriceMapper.java` (new) + `develop/backend/src/main/resources/mapper/StockMinutePriceMapper.xml` (new)
  - `develop/backend/src/main/java/com/stock/mapper/StockMinuteFetchStatusMapper.java` (new) + `develop/backend/src/main/resources/mapper/StockMinuteFetchStatusMapper.xml` (new)
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` / `StockDailyPriceMapper.xml` (added `findOne` — single-row trading-day lookup, reused existing mapper)
  - `develop/backend/src/main/java/com/stock/service/external/YahooFinanceClient.java` (new) — parallel-array normalization, Asia/Taipei conversion, 09:00–13:30 session filter, null-OHLC drop, browser-like `User-Agent` header (Yahoo's edge rejects bare-JVM UA strings)
  - `develop/backend/src/main/java/com/stock/service/external/dto/YahooChartResponse.java`, `YahooChart.java`, `YahooChartResult.java`, `YahooIndicators.java`, `YahooQuote.java`, `NormalizedMinuteBar.java` (new)
  - `develop/backend/src/main/java/com/stock/service/MinutePriceIngestionService.java` (new) — `@Transactional` bars+status write
  - `develop/backend/src/main/java/com/stock/service/MinuteBarQueryService.java` (new) — decision table, window logic, 09:00-anchored aggregation, response assembly
  - `develop/backend/src/main/java/com/stock/controller/MinuteBarController.java` (new) — `GET /api/stocks/{stockId}/minute-bars`
  - `develop/backend/src/main/java/com/stock/exception/MissingTradeDateException.java`, `InvalidDateFormatException.java`, `FutureTradeDateException.java`, `InvalidIntervalException.java` (new) + `GlobalExceptionHandler.java` (handlers added)
  - `develop/backend/src/main/java/com/stock/dto/MinuteBarDto.java`, `DailySummaryDto.java`, `MinuteBarResponse.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` (widened `allowed` to `List<?>` so the same JSON key serves both `INVALID_SORT_FIELD` (strings) and `INVALID_INTERVAL` (integers); added `invalidInterval()` factory)
  - `develop/backend/src/main/java/com/stock/config/MinutePriceProperties.java` (new, `app.minute-price.max-attempt-count`, default 3)
  - `develop/backend/src/main/resources/application.yml` / `develop/backend/src/test/resources/application.yml` (added `app.external.yahoo-finance-base-url`, `app.minute-price.max-attempt-count`)
  - `develop/backend/src/test/java/com/stock/StockMinutePriceIntegrationTest.java` (new, 15 tests)
- Notes:
  - Reused `stock-price-ingestion`'s rate-limit/backoff mechanism directly (`BackfillProperties.RateLimit`, injected as-is) rather than adding a parallel config block, per spec's "不另做一套".
  - Fixed a self-caught design bug during implementation: the local out-of-window check must only run when the fetch decision table has already decided to fetch (i.e. never on an already-`AVAILABLE`/`NO_DATA` row) — otherwise an old `AVAILABLE` date would get silently downgraded to `OUT_OF_WINDOW` the moment it aged past 30 days, even though its bars are still physically in the table. Caught by acceptance criterion "已標記 AVAILABLE 的過往日期...仍可正常讀取" during test-writing, before it ever shipped.
  - `stock_minute_fetch_status.status` is written via five explicit mapper methods (`upsertAvailable`/`upsertNoData`/`upsertOutOfWindow`/`upsertNotATradingDay`/`markFailed`), mirroring `StockSyncProgressMapper`'s shape, rather than one generic upsert — keeps each write's intent explicit and makes it structurally impossible for application code to write an invalid status.
  - The last acceptance criterion ("模擬狀態更新失敗時，該次的 K 棒亦不留存") is verified by forcing the *bars* statement to fail mid-transaction (a `chk_smp_high_low` CHECK violation on the second of two bars in one `applyFetchResult` call) rather than the *status* statement, because the production status-write methods hardcode a valid literal (`'AVAILABLE'`/`'NO_DATA'`/etc.) in the mapper XML and can't be made to violate `stock_minute_fetch_status`'s CHECK constraint through the public API. This proxy exercises the exact same `@Transactional` boundary and proves the same invariant ("either both persist or neither does") that the criterion is protecting against.
  - Verified live against the real Yahoo Finance endpoint (not just mocks): confirmed the exact response shape (`chart.result[0].timestamp[]` + `indicators.quote[0]`), confirmed a full trading day is exactly 271 bars 09:00–13:30 Asia/Taipei, confirmed `volume` is already a per-minute value (not cumulative — real TSMC data showed non-monotonic per-minute volumes), and confirmed null-OHLC minutes genuinely occur (5 in one sample day), validating the drop-on-null design. In this sandbox, Java's TLS handshake gets blocked by Yahoo's edge with HTTP 429 even with a correct browser `User-Agent` header (curl with an identical UA succeeds; this is TLS/JA3-level bot detection, not a header or rate-limit issue) — added the `User-Agent` header regardless since Yahoo's edge is documented to reject header-less requests outright, but did not chase full TLS fingerprint spoofing as it's outside this spec's scope. The system's own retry/backoff/failure-tracking behavior was exercised end-to-end against this real failure and worked exactly as designed: `RateLimitedException` → backoff retries → `FAILED` status with populated `attempt_count`/`last_error` → API surfaced `FETCH_FAILED` with `message` and a non-null `dailySummary`.
  - All 15 new tests plus the pre-existing 55 pass (`mvn -f develop/backend/pom.xml test` → Tests run: 70, Failures: 0, Errors: 0). Verified all validation error codes (`MISSING_TRADE_DATE`, `INVALID_DATE_FORMAT`, `FUTURE_TRADE_DATE`, `INVALID_INTERVAL`, `STOCK_NOT_FOUND`) live via `curl` against a running instance. All tables (`stock`, `stock_daily_price`, `stock_daily_indicator`, `stock_minute_price`, `stock_minute_fetch_status`, `stock_sync_progress`) confirmed at 0 rows after every run; app stopped and port 8080 confirmed free before finishing.
