---
status: done
title: "上市股票 universe 匯入 API"
requirement: "將所有上市的股票先放到 stock 裡面，之後再由使用者自行按同步按鈕回補日 K"
depends_on: [stock-price-ingestion]
---

# 上市股票 universe 匯入 API — Backend Spec

## Overview

以**一次**外部請求取得全體上市（`TSE`）股票的代號與名稱，UPSERT 進 `stock` 主檔，**不寫入任何行情**。

這支 API 存在的理由是把「有哪些股票」與「這些股票的行情」拆成兩個各自可獨立觸發的動作。目前 `stock` 只有 `specs/dba/stock.md` V007 那 34 檔開發種子，而 `specs/backend/stock-price-ingestion.md` 的全市場回補、`specs/backend/strategy-scan.md` 的全市場掃描都以 `stock` 中 `is_active = 1` 的列為母體——母體只有 34 檔時，兩者都只會在這 34 檔上運作，且**不會報錯**（回補對空／小清單是合法情形，見該 spec 的「目標清單為空是合法情形」）。使用者因此會拿到一份看似完整、實則只涵蓋 34 檔的掃描結果。本 API 補的就是這個缺口。

與既有抓取路徑的分工：

| 動作 | 寫 `stock` | 寫 `stock_daily_price` | 請求數 | 由誰觸發 |
|---|---|---|---|---|
| 本 API（universe 匯入） | ✅ 僅代號與名稱 | ❌ **完全不寫** | **1** | 使用者按「更新股票清單」 |
| 每日增量（`POST /api/stocks/sync/daily`） | ✅ 順帶維護 | ✅ 當日一天 | 1 | 每交易日收盤後 |
| 全市場回補（`POST /api/stocks/sync/backfill`） | ❌ | ✅ 歷史區間 | 每檔 1 次 | 使用者按「同步日 K 至今日」 |

**本 API 刻意不寫行情，即使資料源在同一個回應裡就附了當日 OHLCV。** 使用者要的順序是先把清單建好、再自己決定何時開始長時間的行情回補；順手寫入一天的行情會讓 `stock_daily_price` 出現「只有某一天、前後都沒有」的孤立列，而 `specs/backend/stock-indicator-statistics.md` 的 MACD／KD 是遞迴推進的，孤立列會產生一個沒有前值可承接的起點。要行情就走回補路徑，一次補整段。

## Requirements

### 資料源

- 端點：`https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL`
- 與 `specs/backend/stock-price-ingestion.md` 的「每日全市場快照」為**同一個資料源**，本 API 只取用其中的代號（`Code`）與名稱（`Name`）兩個欄位，其餘欄位一律丟棄。
- 單一請求即涵蓋全體上市股票，**不需要速率控制、不需要 `stock_sync_progress` 進度追蹤、不需要斷點續傳**——這些機制在 `stock-price-ingestion` 中存在，是因為逐檔歷史查詢有每檔一次請求的限制；本 API 沒有這個限制，不得為求一致而複製一套上來。
- 上櫃（`OTC`）不在本 API 範圍內。櫃買中心是另一個端點、另一套欄位格式，需要時另立需求。

### 標的篩選：只收普通股

只有**恰為 4 個數字字元、且首字元不為 `0`** 的代號會被寫入。其餘一律計入 `skippedCount` 並忽略。

| 類型 | 代號樣態 | 處理 |
|---|---|---|
| 普通股 | `2330`、`1101`、`9958` | ✅ 寫入 |
| ETF／受益憑證 | `0050`、`006208`、`00878` | ❌ 略過（首字為 `0`） |
| 特別股 | `2881A`、`2891B` | ❌ 略過（含非數字字元） |
| TDR／存託憑證 | `910322`、`911616` | ❌ 略過（超過 4 字元） |
| REIT | `01004T` | ❌ 略過（首字為 `0` 且含字母） |

理由：`specs/backend/strategy-scan.md` 的兩個型態（箱型突破、底底高）都是為個股價格行為設計的判定規則。ETF 的價格由一籃子成分股加權而成，其「箱型」與「突破」在意義上與個股不同；把約 200 檔 ETF 混進母體，只會稀釋掃描結果並拉長回補時間，不會讓使用者多得到可用的訊號。

### 寫入語意

- 以 UPSERT 寫入 `stock`：`stock_id`、`stock_name`、`market = 'TSE'`。
- **新列**：`is_active` 寫入 `1`。
- **既有列**：只更新 `stock_name`（台股實務上會更名，見 `specs/dba/stock.md` 的「維護語意」）。**`is_active` 與 `market` 一律不動。**
- 本 API **在任何情況下都不會把 `is_active` 由 `1` 改為 `0`**，也不會由 `0` 改回 `1`。

`is_active` 兩個方向都不動，是同一個理由的兩面：

- **不自動下市**：`STOCK_DAY_ALL` 是「當日有成交」的快照，停牌、當日全無交易的股票不會出現在回應中。用「沒出現在這份清單裡」反推下市，會把停牌股票誤標成下市，而下市會讓該檔退出所有回補與掃描排程——一次停牌就足以讓一檔股票從系統中無聲消失。
- **不自動復活**：既有列可能是使用者透過 `DELETE /api/stocks/{stockId}`（見 `specs/backend/stock-catalog.md`）刻意下市的。匯入若把它改回 `is_active = 1`，等於每按一次按鈕就默默撤銷一次人為決定。

下市與復市一律由 `specs/backend/stock-catalog.md` 的 `DELETE` / `PUT /api/stocks/{stockId}` 人工處理。

### 空回應的處理

資料源在非交易日、或當日資料尚未發布時，可能回傳空陣列。

**空回應必須以錯誤結束，不得視為「成功匯入 0 檔」。** 回 `502 UPSTREAM_EMPTY`，且不對 `stock` 做任何寫入。理由：這兩者對使用者的意義完全相反——「成功匯入 0 檔」會讓人以為清單已是最新而直接去按同步，結果同步仍只跑那 34 檔；明確的錯誤才會讓人知道要換個時間再試。

### 併發

本 API 為同步短作業（單一外部請求），**不使用 `stock_sync_progress` 的 `jobType` 併發鎖**，因此與執行中的 `PRICE_BACKFILL` 不互斥。

這是安全的：回補作業在啟動時就已解析並固定了自己的標的清單（見 `specs/backend/stock-price-ingestion.md` 的「批次目標選擇」），匯入期間新增的股票不會被塞進一個正在跑的批次。新股票會在**下一次**回補時才納入——這正是使用者「先更新清單、再按同步」的操作順序所預期的行為。

## Implementation Details

### API 契約

```
POST /api/stocks/universe/import
```

無 request body、無 query 參數。範圍固定為上市普通股，沒有可調參數。

Response `200`：

```json
{
  "fetchedCount": 1247,
  "eligibleCount": 1032,
  "skippedCount": 215,
  "insertedCount": 998,
  "updatedCount": 34,
  "totalActiveCount": 1032
}
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `fetchedCount` | int | 資料源回傳的總列數（篩選前） |
| `eligibleCount` | int | 通過普通股篩選的檔數 |
| `skippedCount` | int | 被篩掉的檔數（ETF／特別股／TDR 等），`fetchedCount − eligibleCount` |
| `insertedCount` | int | 本次新增的列數 |
| `updatedCount` | int | 本次命中既有列的檔數（名稱可能相同，仍計入） |
| `totalActiveCount` | int | 匯入後 `stock` 中 `is_active = 1` 的總列數 |

錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| 資料源回傳空陣列 | `502` | `{"code":"UPSTREAM_EMPTY"}` |
| 資料源連線失敗／逾時／非 2xx | `502` | `{"code":"UPSTREAM_UNAVAILABLE"}` |
| 資料源回應無法解析 | `502` | `{"code":"UPSTREAM_MALFORMED"}` |

三種失敗一律**不對 `stock` 做任何部分寫入**：先完整取得並解析回應、篩選出合格清單，確認非空後才進入寫入階段。

### 服務流程

1. 對資料源發出一次請求（逾時與重試沿用 `stock-price-ingestion` 既有的 HTTP 設定，不另訂一套）。
2. 解析回應為列陣列；解析失敗 → `502 UPSTREAM_MALFORMED`。
3. 陣列為空 → `502 UPSTREAM_EMPTY`，結束，不寫入。
4. 逐列取 `Code` 與 `Name`，套用「只收普通股」的篩選規則，統計 `skippedCount`。
5. 名稱正規化：去除前後空白。名稱為空字串的列計入 `skippedCount` 並忽略——沒有名稱的主檔列在清單頁上是一列空白，比不存在更難察覺。
6. 合格清單為空（資料源有回應但一檔普通股都沒有）→ 同樣回 `502 UPSTREAM_EMPTY`。
7. 在單一交易邊界內批次 UPSERT。
8. 查詢 `is_active = 1` 的總數，組出回應。

### 資料來源對應

| 資料源欄位 | `stock` 欄位 |
|---|---|
| `Code` | `stock_id` |
| `Name` | `stock_name` |
| （固定值） | `market` = `'TSE'` |
| （僅新列） | `is_active` = `1` |

## Acceptance Criteria

- [x] `POST /api/stocks/universe/import` 以**單一次**外部請求完成，不逐檔查詢、不建立 `stock_sync_progress` 列
- [x] 匯入後 `stock` 的列數大於 1000，且全部 `market = 'TSE'`
- [x] 匯入後 `stock_daily_price` 與 `stock_daily_indicator` 的列數**完全不變**（本 API 不寫行情）
- [x] 代號 `0050`、`00878`、`2881A`、`910322` 一律不出現在 `stock` 中，且計入 `skippedCount`
- [x] 代號 `2330`、`1101` 出現在 `stock` 中，`stock_name` 為資料源回傳的名稱
- [x] 連續執行兩次，第二次的 `insertedCount` 為 `0`、`updatedCount` 等於第一次的 `eligibleCount`，且 `stock` 列數不變
- [x] 既有 34 檔種子股票在匯入後 `stock_id` 不重複、`stock_name` 為資料源的最新值
- [x] 事前以 `DELETE /api/stocks/{stockId}` 將某檔設為 `is_active = 0`，匯入後該檔仍為 `is_active = 0`
- [x] 資料源回傳空陣列時回 `502 UPSTREAM_EMPTY`，且 `stock` 列數與內容完全不變
- [x] 資料源連線失敗時回 `502 UPSTREAM_UNAVAILABLE`，且 `stock` 列數與內容完全不變
- [x] 回應的 `skippedCount` 等於 `fetchedCount` 減 `eligibleCount`
- [x] 回應的 `totalActiveCount` 等於匯入後 `SELECT COUNT(*) FROM stock WHERE is_active = 1` 的結果
- [x] 匯入完成後呼叫 `GET /api/stocks?page=1&size=50`，`total` 反映匯入後的檔數

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/service/external/TwseClient.java` — added `fetchDailyAllRaw()`: same URL/RestTemplate as `fetchDailyAll()`, but returns raw unfiltered rows (no no-trade filtering) and distinguishes connectivity failure (`ExternalApiException`) from an unparsable response (new `ExternalApiMalformedException`)
  - `develop/backend/src/main/java/com/stock/service/external/ExternalApiMalformedException.java` — new
  - `develop/backend/src/main/java/com/stock/exception/{UpstreamEmptyException,UpstreamUnavailableException,UpstreamMalformedException}.java` — new, mapped to `502` by `GlobalExceptionHandler`
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — added handlers for the three exceptions above (`UPSTREAM_EMPTY` / `UPSTREAM_UNAVAILABLE` / `UPSTREAM_MALFORMED`, all `502`), with `log.warn` for operational visibility
  - `develop/backend/src/main/java/com/stock/service/StockUniverseImportService.java` — new: fetches once, applies the "恰為 4 位數字、首字元非 0" ordinary-share filter, computes `fetchedCount`/`eligibleCount`/`skippedCount`, delegates the UPSERT batch to `PriceIngestionService.applyUniverseImport`, and reads `totalActiveCount` after
  - `develop/backend/src/main/java/com/stock/service/PriceIngestionService.java` — added `applyUniverseImport(List<Stock>)`: `@Transactional`, UPSERTs only `stock` (via the new `upsertUniverse` mapper method), writes no price row; existing-id set resolved up front (same pattern as `applyDailySnapshot`) so insert/update counts don't depend on MySQL's ambiguous `ON DUPLICATE KEY UPDATE` affected-row count
  - `develop/backend/src/main/java/com/stock/mapper/StockMapper.java` + `develop/backend/src/main/resources/mapper/StockMapper.xml` — added `upsertUniverse` (on conflict, refreshes only `stock_name`; `market`/`is_active` untouched — deliberately different from the existing `upsert`, which also overwrites `is_active`/`market`) and `countActive()`
  - `develop/backend/src/main/java/com/stock/dto/{UniverseImportResponse,UniverseUpsertCounts}.java` — new
  - `develop/backend/src/main/java/com/stock/controller/StockSyncController.java` — added `POST /api/stocks/universe/import`
  - `develop/backend/src/test/java/com/stock/StockUniverseImportIntegrationTest.java` — new, 7 tests (happy path incl. filtering/no-price-write, idempotent re-run, deactivated-stock-stays-deactivated, empty array → 502, rows-but-none-eligible → 502, connection failure → 502, malformed JSON → 502)

- Notes:
  - Design: kept `PriceIngestionService` as the single owner of every write into `stock` (per its existing class doc), rather than putting `@Transactional` on a method `StockUniverseImportService` would call on itself — a same-class `@Transactional` call would silently not run in a transaction (Spring proxy self-invocation), which was caught and fixed during implementation before it shipped.
  - `design-patterns` skill reviewed: this is a single-algorithm CRUD-style import with no second implementation/variant in sight, so no pattern (Strategy/Factory/etc.) was introduced — matches the skill's "call it directly" guidance.
  - `code-quality` skill reviewed: added `log.warn` (with cause for the two failure exceptions) to the three new `GlobalExceptionHandler` entries for operational visibility, since a `502` here is a real external-dependency failure an operator would want to see, unlike routine `4xx` validation errors. One known, deliberately-accepted limitation: since the spec explicitly forbids a `stock_sync_progress`-style concurrency lock for this endpoint ("不使用 `stock_sync_progress` 的 `jobType` 併發鎖"), two overlapping calls could both resolve the same "existing ids" snapshot before either commits and both report a row as `insertedCount` instead of one insert + one update; the underlying `stock` table write itself stays correct (MySQL's `ON DUPLICATE KEY UPDATE` serializes concretely), only the reported counts could be off by one in that race — accepted as out of scope per the spec's own no-lock instruction, and not exercised by any acceptance criterion.
  - Verified against the **real** TWSE endpoint and the **real** dev database (not mocked), in addition to the 7 automated integration tests (which use `MockRestServiceServer`, all passing, `mvn -f develop/backend/pom.xml test` → 141/141 green):
    - Single external request, no `stock_sync_progress` rows created, response `{"fetchedCount":1377,"eligibleCount":1085,"skippedCount":292,"insertedCount":1,"updatedCount":1084,"totalActiveCount":1365}` (`skippedCount == fetchedCount - eligibleCount` ✓).
    - `stock` row count > 1000 and 100% `market = 'TSE'` confirmed via `SELECT COUNT(*) FROM stock WHERE market != 'TSE'` → `0`.
    - Re-ran immediately after: `insertedCount=0`, `updatedCount=1085` (equals the first run's `eligibleCount`), `stock` row count unchanged (1366 → 1366).
    - With `app.backfill.startup-catch-up.enabled=false`/`app.master-sync.startup.enabled=false` (to eliminate unrelated background writes from `StartupCatchUpRunner`), confirmed `stock_daily_price` and `stock_daily_indicator` row counts byte-identical before/after a live import call (22808/5372 → 22808/5372).
    - `2330`/`1101` present with their real names (台積電/台泥, confirmed correct UTF-8 via `mysql --default-character-set=utf8mb4`, not mojibake).
    - `0050`/`2881A`/`910322` already existed in the live DB (written by the unrelated daily-sync path, which applies no such filter) — confirmed their `stock_name` was byte-identical before/after the import call, proving this endpoint's filter skipped them rather than overwriting them; the integration test additionally proves fresh unseen codes in these shapes are never inserted at all.
    - Deactivated `1102` via the real `DELETE /api/stocks/1102`, ran import (which included `1102` in the upstream response), confirmed `is_active` stayed `0` after import, then restored it to `1` afterward (its pre-test state) so the live dev database was left as found.
    - `GET /api/stocks?page=1&size=50` → `total: 1365`, matching `SELECT COUNT(*) FROM stock WHERE is_active = 1` and the import response's `totalActiveCount`.
    - `502 UPSTREAM_EMPTY`: pointed `app.external.twse-daily-all-url` at a local stub returning `[]`; got `{"code":"UPSTREAM_EMPTY"}` / `502`, `stock` row count unchanged.
    - `502 UPSTREAM_UNAVAILABLE`: pointed the same property at a closed port (connection refused); got `{"code":"UPSTREAM_UNAVAILABLE"}` / `502`, `stock` row count unchanged.
    - `502 UPSTREAM_MALFORMED`: exercised only in the automated test (stub returns a JSON object instead of an array) — not re-verified against the live app in this session, since it requires the same kind of stub already used for the other two live checks; the integration test result is the evidence for this one.
  - All test artifacts (`9871`/`9872`/`9873` stock rows) were cleaned up by the test's `@AfterEach`; confirmed absent afterward. The backend process and local stub servers used for manual verification were stopped before finishing; port 8080 confirmed free.
