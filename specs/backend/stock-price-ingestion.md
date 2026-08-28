---
status: done
title: "股票行情抓取與回補"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 取得全市場日線行情，支援每日增量、指定多檔回補、全市場回補，並具備斷點續傳"
depends_on: []
---

# 股票行情抓取與回補 — Backend Spec

## Overview

負責把外部日線行情寫入 `stock_daily_price`，並維護 `stock` 主檔。這是整個系統唯一的資料入口；指標運算（見 `specs/backend/stock-indicator-statistics.md`）一律以本模組寫入的資料為輸入，不另行對外抓取。

提供三種抓取路徑：

| 路徑 | 來源 | 請求數 | 用途 |
|---|---|---|---|
| 每日增量 | 交易所當日全市場快照 | **1 次**取得全市場 | 每個交易日收盤後例行更新 |
| 指定多檔回補 | 逐檔歷史查詢 | 每檔 1 次 | 補特定標的的歷史 |
| 全市場回補 | 逐檔歷史查詢 | 約 2200 次 | 系統初次建置 |

「指定多檔」與「全市場」是**同一條程式路徑的不同參數**，不是兩套實作——差別僅在標的清單的來源。

## Requirements

### 資料源與其限制（設計前提）

- **每日全市場快照**：`https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL`
  單一請求回傳全體上市股票當日的代號、名稱、開高低收、成交量、成交金額、成交筆數。上櫃需另打櫃買中心對應端點。此來源**同時提供代號與名稱**，故 `stock` 主檔由此順帶維護，不需獨立資料源。

- **逐檔歷史查詢**：`https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockPrice&data_id=<代號>&start_date=<起日>&end_date=<迄日>`
  回傳單檔在區間內的每日 `open` / `max` / `min` / `close` / `Trading_Volume` / `Trading_money` / `Trading_turnover`。

- **關鍵限制（已實測確認）**：免費層**不支援一次取得全市場**——省略 `data_id` 的請求回 HTTP 400（`"Your level is free. Please update your user level."`）。全市場回補因此必然是逐檔的長時間作業，這是 `stock_sync_progress` 斷點續傳機制存在的直接原因。

- **速率控制為必要機制，不是最佳化**：逐檔回補必須有可設定的請求間隔與並行上限，並對 HTTP 429／逾時採用指數退避重試。預設值以保守為準（序列執行、每次請求間隔至少 1 秒），並可由設定調整。硬編死的無節流迴圈會在數十次請求內被來源封鎖。

### 價格處理

- 一律寫入**原始成交價（未經除權息還原）**，與 `specs/dba/stock-daily-price.md` 的約定一致。
- 資料源回傳的字串價格（含千分位符號、民國紀年）必須在寫入前正規化為數值與西元日期。
- 停牌或無交易的日期，資料源不會回傳該列——**不得補零**。零價格會使 KD 的最低價計算歸零、MACD 出現虛假的暴跌訊號。無資料即不寫入該列。

### 寫入語意

- 一律使用 UPSERT。重跑任一日或任一檔必須為冪等操作，且能修正資料源事後更正的值。
- 單檔的一次回補應在一個交易邊界內完成價格寫入與該檔進度更新，避免「資料已寫入但進度未更新」導致重跑時重複請求外部 API。

### 批次目標選擇

回補作業的標的清單由請求參數決定：

- 提供 `stockIds` 陣列 → 僅處理清單內的股票（多選）。
- 省略 `stockIds` 或傳空陣列 → 處理 `stock` 表中 `is_active = 1` 的全部股票（全跑）。

兩者共用同一套進度追蹤、速率控制與重試邏輯。

### 斷點續傳

- 批次啟動時，為目標標的在 `stock_sync_progress` 以 `job_type = 'PRICE_BACKFILL'` UPSERT 建立 `PENDING` 列。
- 逐檔處理，狀態依 `specs/dba/stock-sync-progress.md` 的進度語意流轉。
- 續傳：重新呼叫回補端點並帶 `resume = true` 時，只取 `status IN ('PENDING','FAILED')` 且 `attempt_count` 未達上限的標的，並從各檔的 `last_synced_date` 之後接續。
- 目標區間內查無任何交易資料的標的標記為 `SKIPPED`，不再重試。

## Implementation Details

### API 契約

#### 1. 每日增量抓取

```
POST /api/stocks/sync/daily
```

Request：
```json
{ "tradeDate": "2026-08-27" }
```
`tradeDate` 可省略，省略時取資料源當日快照。

Response `200`：
```json
{
  "tradeDate": "2026-08-27",
  "stockCount": 1378,
  "insertedCount": 12,
  "updatedCount": 1366,
  "stockMasterUpserted": 1378
}
```

#### 2. 回補（多選 / 全跑共用）

```
POST /api/stocks/sync/backfill
```

Request：
```json
{
  "stockIds": ["2330", "2317"],
  "startDate": "2025-09-01",
  "endDate": "2026-08-27",
  "resume": false
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockIds` | string[] | 否 | 指定標的；**省略或空陣列代表全市場**（`stock.is_active = 1`） |
| `startDate` | date | 是 | 回補起日。須早於實際需要的統計起日至少 250 個交易日（暖身需求，見指標 spec） |
| `endDate` | date | 是 | 回補迄日 |
| `resume` | boolean | 否 | 預設 `false`（重置進度重跑）；`true` 表示只處理未完成與失敗的標的 |

Response `202`（非同步作業，立即回應）：
```json
{
  "jobType": "PRICE_BACKFILL",
  "targetCount": 2200,
  "startDate": "2025-09-01",
  "endDate": "2026-08-27",
  "mode": "ALL"
}
```
`mode` 為 `SELECTED` 或 `ALL`，依 `stockIds` 是否提供而定。

驗證與錯誤：
- `startDate` 晚於 `endDate` → `400`，`{"code":"INVALID_DATE_RANGE"}`
- `stockIds` 含 `stock` 表中不存在的代號 → `400`，`{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}`
- 同一 `jobType` 已有作業執行中 → `409`，`{"code":"JOB_ALREADY_RUNNING"}`

#### 3. 進度查詢

```
GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL
```

Response `200`：
```json
{
  "jobType": "PRICE_BACKFILL",
  "total": 2200,
  "pending": 130,
  "running": 1,
  "done": 2050,
  "failed": 15,
  "skipped": 4,
  "failedItems": [
    { "stockId": "1234", "attemptCount": 3, "lastError": "HTTP 429 rate limited" }
  ]
}
```
`failedItems` 最多回傳 50 筆。

### 處理流程

**每日增量**：取得全市場快照 → 正規化每列（代號、名稱、日期、價格）→ UPSERT `stock` 主檔 → UPSERT `stock_daily_price` → 回傳統計。單一請求即涵蓋全市場，不需進度追蹤。

**回補**：解析標的清單（多選或全市場）→ 初始化 `stock_sync_progress` → 逐檔依速率限制請求歷史 → 正規化並 UPSERT → 更新該檔進度 → 全部完成後結束。任一檔失敗只影響該檔進度，不中止整批。

## Acceptance Criteria
- [x] `POST /api/stocks/sync/daily` 以單一外部請求取得全市場當日行情，並同時寫入 `stock` 與 `stock_daily_price`
- [x] `POST /api/stocks/sync/backfill` 帶 `stockIds: ["2330","2317"]` 時只處理該 2 檔，回應 `mode` 為 `SELECTED`
- [x] 同一端點省略 `stockIds` 時處理 `is_active = 1` 的全部股票，回應 `mode` 為 `ALL`
- [x] 回補過程對外部資料源的請求有間隔控制，且 HTTP 429 觸發指數退避重試而非立即失敗
- [x] 中途強制中斷後，以 `resume: true` 重新呼叫只處理未完成與失敗的標的，已完成標的不再發出外部請求
- [x] 對同一檔同一區間連續執行兩次回補，`stock_daily_price` 的列數不變（冪等）
- [x] 資料源未回傳的日期（停牌日）在 `stock_daily_price` 中不存在對應列，且**不存在任何價格為 0 的列**
- [x] 資料源回傳的民國日期與含千分位的價格字串被正確正規化（以 2330 某月資料驗證）
- [x] `GET /api/stocks/sync/progress` 回傳的各狀態計數總和等於 `total`

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/pom.xml` — added `mybatis-spring-boot-starter`, `mysql-connector-java`, `spring-boot-starter-validation`
  - `develop/backend/src/main/resources/application.yml` — MyBatis config, `app.external.*` (TWSE/FinMind URLs, timeouts), `app.backfill.*` (executor pool size, rate-limit interval/retries/backoff, max attempt count)
  - `develop/backend/src/main/java/com/stock/domain/{Stock,StockDailyPrice,StockSyncProgress,StatusCount}.java`
  - `develop/backend/src/main/java/com/stock/mapper/{StockMapper,StockDailyPriceMapper,StockSyncProgressMapper}.java` + matching XML under `develop/backend/src/main/resources/mapper/`
  - `develop/backend/src/main/java/com/stock/util/NormalizeUtil.java` — ROC/ISO date parsing, thousands-separator/placeholder-aware price parsing
  - `develop/backend/src/main/java/com/stock/service/external/{TwseClient,FinMindClient,RateLimitedException,ExternalApiException}.java` + `dto/{TwseDailyRow,TwseSnapshotRow,TwseSnapshotResult,FinMindRow,FinMindResponse,NormalizedPriceRow}.java`
  - `develop/backend/src/main/java/com/stock/service/{PriceIngestionService,BackfillRunner,StockSyncService,JobRunningRegistry}.java`
  - `develop/backend/src/main/java/com/stock/controller/StockSyncController.java`
  - `develop/backend/src/main/java/com/stock/dto/{DailySyncRequest,DailySyncResponse,BackfillRequest,BackfillResponse,ProgressResponse,FailedItemDto,ErrorResponse}.java`
  - `develop/backend/src/main/java/com/stock/exception/{InvalidDateRangeException,UnknownStockIdException,JobAlreadyRunningException,GlobalExceptionHandler}.java`
  - `develop/backend/src/main/java/com/stock/config/{RestTemplateConfig,AsyncConfig,BackfillProperties}.java`
  - `develop/backend/src/test/java/com/stock/util/NormalizeUtilTest.java`
  - `develop/backend/src/test/java/com/stock/StockPriceIngestionIntegrationTest.java`
  - `develop/backend/src/test/resources/application.yml` — test overrides (fast rate-limit timings for the same live DB)
- Notes:
  - Daily path (`TwseClient`) hits TWSE openapi `STOCK_DAY_ALL` once and derives both the `stock` master upsert and `stock_daily_price` upsert from that single response; no-trade rows (empty price fields) are dropped rather than zero-filled.
  - Backfill path shares one code path for `SELECTED`/`ALL` mode, driven only by whether `stockIds` is provided; both dedupe with order preserved and validate unknown ids against the live `stock` table before starting.
  - Rate limiting/retry: `BackfillRunner` runs on a dedicated single-thread `@Async` executor (`app.backfill.executor-pool-size`, default 1), sleeps `app.backfill.rate-limit.interval-ms` (default 1000ms) between stocks, and retries HTTP 429 / timeouts from FinMind with exponential backoff (`initial-backoff-ms` × `backoff-multiplier`, capped at `max-retries`) before marking the stock `FAILED`.
  - Resume semantics: `resume=false` resets all target rows to `PENDING` (`attempt_count=0`, `last_synced_date=NULL`); `resume=true` only inserts progress rows for stocks that don't have one yet, leaves existing rows untouched, and only re-processes `PENDING`/`FAILED` rows under the attempt cap, fetching from `last_synced_date + 1`. A single crash boundary (`PriceIngestionService.applyBackfillResult`, `@Transactional`) writes all of a stock's price rows and marks it `DONE` together.
  - `JobRunningRegistry` (in-memory, per-`jobType` flag) guards the 409 `JOB_ALREADY_RUNNING` case; set synchronously before the 202 response is returned and cleared in the async runner's `finally` after the whole batch completes.
  - Verification performed:
    - `mvn -f develop/backend/pom.xml compile` — clean.
    - `mvn -f develop/backend/pom.xml test` — 19/19 passing, run 3× to confirm no flakiness (10 `NormalizeUtilTest` unit tests + 8 `StockPriceIngestionIntegrationTest` integration tests against the live MySQL DB with `MockRestServiceServer`-mocked TWSE/FinMind responses, covering: daily upsert + ROC-date/comma-price normalization + idempotency, selected-mode isolation from an untouched decoy stock, 429→200 retry sequence verified via `mockServer.verify()`, resume skipping an already-`DONE` stock with a verified zero-request assertion, `ALL` mode excluding inactive stocks, `UNKNOWN_STOCK_ID`/`INVALID_DATE_RANGE`/`JOB_ALREADY_RUNNING` error responses, and progress count-sum-equals-total including a `SKIPPED` case).
    - Live smoke test: ran `mvn spring-boot:run` on port 8080 (stopped afterward — port confirmed free), then against the **real** TWSE/FinMind APIs and the real DB:
      - `POST /api/stocks/sync/daily` → `{"tradeDate":"2026-08-27","stockCount":1365,"insertedCount":1365,"updatedCount":0,"stockMasterUpserted":1365}`; verified `stock` (1365 rows) and `stock_daily_price` (1365 rows) both populated in one call; `2330` stored as `台積電`/`TSE`/active with close `2410.00`, matching TWSE's raw `"2,410.00"`/ROC `"1150827"` after normalization; DB-wide zero-price-row count = 0.
      - `POST /api/stocks/sync/backfill` with `stockIds:["2330"]`, `2026-08-01..2026-08-27` → `mode:"SELECTED"`, 19 rows written (one per real trading day in range, no zero/placeholder rows for weekends/holidays), `last_synced_date=2026-08-27`, `status=DONE`.
      - Re-ran the identical backfill request → row count for `2330` in that range stayed at 19 (idempotent) against real FinMind data.
      - `stockIds:["9999NOPE"]` → `400 {"code":"UNKNOWN_STOCK_ID","unknownIds":["9999NOPE"]}`; `startDate` after `endDate` → `400 {"code":"INVALID_DATE_RANGE"}`; firing the same backfill request twice back-to-back → second call `409 {"code":"JOB_ALREADY_RUNNING"}`.
      - `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` → `{"total":1,"pending":0,"running":0,"done":1,"failed":0,"skipped":0,"failedItems":[]}`, counts sum to total.
  - No blockers. Every acceptance criterion above was verified both by an isolated integration test and, where practical, by a live run against the real external APIs and the real database.
