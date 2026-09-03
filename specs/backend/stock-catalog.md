---
status: pending
title: "股票清單查詢 API"
requirement: "前端 K 線瀏覽 — 使用者要能看到系統中總共有哪些股票，並從清單點選進入個股 K 線頁；總覽分頁另需能新增、修改、下市股票主檔"
depends_on: [stock-price-ingestion]
---

# 股票清單查詢 API — Backend Spec

## Overview

提供前端**讀取用**的股票清單與單檔基本資料。這是使用者進入系統看到的第一個畫面（`specs/frontend/stock-list.md`）的唯一資料來源。

與既有 backend spec 的分工：`stock-price-ingestion` 負責把行情從外部寫進來、`stock-universe-import` 負責從外部批次補齊股票主檔、`stock-indicator-statistics` 負責運算與區間統計。本 spec 負責**把已經在庫裡的東西列出來給人看**，外加**單一標的的人工維護**（`#### 3`～`#### 5` 的新增／修改／下市）——它不向任何外部資料源抓取、也不做運算。

`is_active` 的改動只發生在本 spec 的 `DELETE` / `PUT` 端點；自動化的抓取與匯入路徑一律不碰這個欄位（見 `specs/dba/stock.md` 的「維護語意」）。

清單除了代號與名稱外，必須附上該檔的最新收盤與漲跌——只列出代號名稱的清單無法讓使用者決定要點哪一檔。

## Requirements

### 清單內容與篩選

- 資料來源為 `stock` 主檔（由 `stock-price-ingestion` 的每日抓取維護）。
- 支援關鍵字搜尋：對 `stock_id` 與 `stock_name` 同時做前綴／包含比對。全市場約 2200 檔，沒有搜尋就只能翻頁找。
- 支援市場別篩選（`TSE` / `OTC`）。
- 預設**只列出 `is_active = 1`** 的股票；已下市標的需明確要求才顯示。下市股票的歷史行情仍保留（見 `specs/dba/stock.md`），但不該混在日常瀏覽的清單裡。
- 分頁為必要機制而非選配：一次回傳 2200 檔會讓前端表格渲染與網路傳輸同時受害。

### 最新行情的取得方式（效能前提）

清單每列要顯示最新收盤價與漲跌，這需要每檔在 `stock_daily_price` 的**最後兩個交易日**（最新收盤與前一收盤，漲跌由兩者相減）。

**必須先分頁、再取行情，不可先關聯再分頁。**

先對 `stock` 分頁（走 `idx_market_active`，至多取回 `size` 筆代號），再以這批代號一次查詢其最近兩個交易日的行情。這樣行情查詢的規模被 `size` 上限固定住，與全市場檔數無關。反過來先把 2200 檔全部關聯行情再分頁，等於每次翻頁都掃過整張行情表，只為了丟掉其中 98% 的結果。

因此排序欄位**限定為 `stock` 表自身的欄位**（`stockId` / `stockName` / `market`）。依「漲跌幅」排序需要在分頁前就得到全市場的行情，與上述順序衝突，本階段不支援——這是刻意的取捨，不是遺漏。

### 無行情資料的標的

新上市或尚未回補的股票在 `stock_daily_price` 中沒有任何列。這類標的**仍須出現在清單中**，其行情欄位回 `null`，由前端顯示為「—」。從清單中隱藏它們會讓使用者以為系統漏了這檔股票。

同理，該檔在庫中只有一個交易日的資料時，`previousClose` / `changeAmount` / `changePercent` 為 `null`，但 `latestClose` 仍須回傳。

## Implementation Details

### API 契約

#### 1. 股票清單

```
GET /api/stocks
```

Query 參數：

| 參數 | 型別 | 必填 | 預設 | 說明 |
|---|---|---|---|---|
| `keyword` | string | 否 | — | 對 `stock_id` 與 `stock_name` 做包含比對，兩者任一命中即回傳 |
| `market` | string | 否 | — | `TSE` 或 `OTC`；省略代表不限市場別 |
| `includeInactive` | boolean | 否 | `false` | `false` 只回 `is_active = 1`；`true` 連同已下市一併回傳 |
| `commonStocksOnly` | boolean | 否 | `true` | **省略時視為 `true`**；只回代號恰為 4 位數字且首字元非 `0` 的普通股，排除 ETF、特別股與 TDR。判斷沿用 `specs/backend/stock-universe-import.md` 的「只收普通股」定義，全系統共用同一份實作 |
| `page` | int | 否 | `1` | 1-based 頁碼 |
| `size` | int | 否 | `50` | 每頁筆數，上限 `200` |
| `sort` | string | 否 | `stockId` | `stockId` / `stockName` / `market` |
| `order` | string | 否 | `asc` | `asc` / `desc` |

Response `200`：
```json
{
  "page": 1,
  "size": 50,
  "total": 1378,
  "totalPages": 28,
  "items": [
    {
      "stockId": "2330",
      "stockName": "台積電",
      "market": "TSE",
      "isActive": true,
      "latestTradeDate": "2026-08-27",
      "latestClose": 2410.00,
      "previousClose": 2415.00,
      "changeAmount": -5.00,
      "changePercent": -0.21,
      "latestVolume": 17557736
    },
    {
      "stockId": "9999",
      "stockName": "新掛牌",
      "market": "OTC",
      "isActive": true,
      "latestTradeDate": null,
      "latestClose": null,
      "previousClose": null,
      "changeAmount": null,
      "changePercent": null,
      "latestVolume": null
    }
  ]
}
```

驗證與錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `size` 大於 200 | `400` | `{"code":"PAGE_SIZE_EXCEEDED","limit":200}` |
| `page` 小於 1 或 `size` 小於 1 | `400` | `{"code":"INVALID_PAGINATION"}` |
| `market` 非 `TSE`/`OTC` | `400` | `{"code":"INVALID_MARKET"}` |
| `sort` 非允許欄位 | `400` | `{"code":"INVALID_SORT_FIELD","allowed":["stockId","stockName","market"]}` |
| 查無符合條件的股票 | `200` | `items` 為空陣列，`total` 為 `0` |

#### 2. 單檔基本資料

```
GET /api/stocks/{stockId}
```

供個股 K 線頁的頁首顯示。單列查詢，故可附帶清單負擔不起的彙總欄位。

Response `200`：
```json
{
  "stockId": "2330",
  "stockName": "台積電",
  "market": "TSE",
  "isActive": true,
  "latestTradeDate": "2026-08-27",
  "latestClose": 2410.00,
  "previousClose": 2415.00,
  "changeAmount": -5.00,
  "changePercent": -0.21,
  "latestVolume": 17557736,
  "firstTradeDate": "2025-01-02",
  "tradingDayCount": 312
}
```

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `stockId` 不存在於 `stock` 主檔 | `404` | `{"code":"STOCK_NOT_FOUND","stockId":"9999"}` |
| 該檔在庫中無任何行情 | `200` | 行情欄位為 `null`，`firstTradeDate` 為 `null`，`tradingDayCount` 為 `0` |

#### 3. 新增股票

```
POST /api/stocks
```

Request：
```json
{ "stockId": "6488", "stockName": "環球晶", "market": "TSE" }
```

| 欄位 | 型別 | 必填 | 規則 |
|---|---|---|---|
| `stockId` | string | 是 | 1–10 字元，去除前後空白後不得為空 |
| `stockName` | string | 是 | 1–60 字元，去除前後空白後不得為空 |
| `market` | string | 是 | `TSE` 或 `OTC` |

新增的股票 `isActive` 一律為 `true`，不接受由請求指定——「新增一檔已下市的股票」沒有實際意義，要下市請新增後再呼叫下市端點。

Response `201`：回傳與 `GET /api/stocks/{stockId}` 相同結構的單檔資料（行情欄位為 `null`，因為剛建立尚無行情）。

- 代號已存在 → `409`，`{"code":"STOCK_ALREADY_EXISTS"}`。**不得默默改為更新**：使用者按的是「新增」，靜默覆寫既有股票的名稱是資料損失。
- `market` 非 `TSE`／`OTC` → `400`，`{"code":"INVALID_MARKET"}`
- 必填欄位缺漏或超長 → `400`，`{"code":"INVALID_STOCK_PAYLOAD","fields":["stockName"]}`

#### 4. 修改股票

```
PUT /api/stocks/{stockId}
```

Request：
```json
{ "stockName": "環球晶圓", "market": "TSE", "isActive": true }
```

| 欄位 | 型別 | 必填 | 規則 |
|---|---|---|---|
| `stockName` | string | 是 | 同新增 |
| `market` | string | 是 | 同新增 |
| `isActive` | boolean | 是 | `false` 等同下市；`true` 可讓已下市標的重新上架 |

`stockId` 不可修改——它是主鍵，也是行情、指標、進度三張表的依附鍵。要「改代號」實際上是新增一檔再把舊的下市，兩者的行情歷史本來就不該混為一談。

Response `200`：回傳更新後的單檔資料。

- 代號不存在 → `404`，`{"code":"STOCK_NOT_FOUND"}`
- 其餘驗證與錯誤同新增

#### 5. 下市股票（軟刪除）

```
DELETE /api/stocks/{stockId}
```

將該檔 `isActive` 設為 `false`，**不刪除任何一列資料**。

Response `200`：
```json
{ "stockId": "6488", "stockName": "環球晶", "isActive": false }
```

**此端點刻意不做實體刪除。** `specs/dba/stock.md` 已明訂下市標的的歷史行情必須保留、不得刪除 `stock_daily_price` 既有資料；若在此提供硬刪除，同一份資料就會有兩套互相矛盾的規則。使用者要看已下市標的時，清單帶 `includeInactive=true` 即可，要恢復則呼叫修改端點把 `isActive` 設回 `true`。

- 代號不存在 → `404`，`{"code":"STOCK_NOT_FOUND"}`
- 已經是下市狀態 → 仍回 `200`（冪等），不視為錯誤

### 數值格式

價格與漲跌金額 2 位小數，漲跌百分比 2 位小數。`changePercent` 定義為 `(latestClose - previousClose) / previousClose × 100`。

### 資料來源對應

| 回應欄位 | 來源 |
|---|---|
| `stockId` / `stockName` / `market` / `isActive` | `stock.stock_id` / `stock_name` / `market` / `is_active` |
| `latestTradeDate` / `latestClose` / `latestVolume` | `stock_daily_price` 中該檔 `trade_date` 最大的一列的 `trade_date` / `close_price` / `volume` |
| `previousClose` | 同表中該檔第二新的一列的 `close_price` |
| `changeAmount` / `changePercent` | 由上述兩個收盤價計算，不落地儲存 |
| `firstTradeDate` / `tradingDayCount` | 該檔在 `stock_daily_price` 的 `MIN(trade_date)` 與 `COUNT(*)` |

## Acceptance Criteria
- [x] `GET /api/stocks` 預設回傳第 1 頁、50 筆、僅 `is_active = 1` 的股票，且 `total` 為符合條件的總數
- [x] `keyword=台積` 與 `keyword=2330` 皆能命中 2330（代號與名稱同時比對）
- [x] `market=OTC` 只回傳上櫃股票；帶入 `TWSE` 等非法值回 `400` 與 `INVALID_MARKET`
- [x] `includeInactive=true` 時已下市標的出現在結果中，預設則不出現
- [x] `size=500` 回 `400` 與 `PAGE_SIZE_EXCEEDED`
- [x] `sort=changePercent` 回 `400` 與 `INVALID_SORT_FIELD`（明確拒絕，而非默默改用預設排序）
- [x] 在 `stock_daily_price` 中無資料的標的仍出現在清單，且其 `latestClose` 為 `null` 而非 `0`
- [x] 僅有單一交易日資料的標的，`latestClose` 有值而 `previousClose`／`changePercent` 為 `null`
- [x] 對 2200 檔規模的資料查詢任一頁，對 `stock_daily_price` 的查詢筆數不超過該頁 `size` 所涵蓋的股票數（驗證為「先分頁再取行情」，而非全表關聯後分頁）
- [x] `GET /api/stocks/2330` 回傳含 `firstTradeDate` 與 `tradingDayCount` 的單檔資料
- [x] `GET /api/stocks/9999`（不存在的代號）回 `404` 與 `STOCK_NOT_FOUND`
- [x] `POST /api/stocks` 新增成功回 `201`，且新股票立即出現在 `GET /api/stocks` 結果中、`isActive` 為 `true`
- [x] 新增已存在的代號回 `409` 與 `STOCK_ALREADY_EXISTS`，且既有股票的名稱未被覆寫
- [x] 新增時 `market` 帶入 `TSE`／`OTC` 以外的值回 `400` 與 `INVALID_MARKET`
- [x] 新增時 `stockName` 為空字串或純空白回 `400` 與 `INVALID_STOCK_PAYLOAD`
- [x] `PUT /api/stocks/{stockId}` 可改名稱與市場別，回應為更新後的資料
- [x] `PUT` 對已下市標的把 `isActive` 設為 `true` 可使其重新出現在預設清單中
- [x] `PUT` 不存在的代號回 `404` 與 `STOCK_NOT_FOUND`
- [x] `DELETE /api/stocks/{stockId}` 後該檔 `isActive` 為 `false`，且其 `stock_daily_price` 與 `stock_daily_indicator` 列數完全不變
- [x] 下市後該檔不出現在預設清單，帶 `includeInactive=true` 則出現
- [x] 對已下市標的重複呼叫 `DELETE` 仍回 `200`（冪等）
- [x] `DELETE` 不存在的代號回 `404` 與 `STOCK_NOT_FOUND`

---
---

- [ ] `GET /api/stocks` 省略 `commonStocksOnly` 時視為 `true`：回應的 `total` 只計代號恰為 4 位數字且首字元非 `0` 的股票
- [ ] `commonStocksOnly: true` 時 `0050`、`00878`、`2881A`、`910322` 皆不出現在 `items` 中，也不計入 `total`
- [ ] `commonStocksOnly=false` 時回傳全部在市股票，`total` 明顯大於 `true` 時的值
- [ ] `commonStocksOnly` 與 `keyword`、`market`、`includeInactive`、分頁、排序可同時使用，彼此獨立生效
- [ ] 普通股判斷與 `specs/backend/stock-universe-import.md` 共用同一份實作，不存在第二套代號篩選邏輯
- [ ] 本參數只影響查詢結果，不寫入任何資料表：查詢前後 `stock` 的列數、內容與 `is_active` 完全不變
- [ ] `GET /api/stocks/{stockId}` 不受本參數影響：直接查 `0050` 仍正常回傳該檔資料，不因它是 ETF 而回 `404`

## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/controller/StockController.java` (new)
  - `develop/backend/src/main/java/com/stock/service/StockQueryService.java` (new)
  - `develop/backend/src/main/java/com/stock/mapper/StockMapper.java` (extended: `findById`, `findPage`, `countPage`)
  - `develop/backend/src/main/resources/mapper/StockMapper.xml` (extended, same methods)
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` (extended: `findLatestTwoByStockIds`, `findLatestTwoByStockId`, `findPriceStats`)
  - `develop/backend/src/main/resources/mapper/StockDailyPriceMapper.xml` (extended, same methods)
  - `develop/backend/src/main/java/com/stock/domain/PriceStats.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/StockListItemDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/StockDetailDto.java` (new, extends `StockListItemDto`)
  - `develop/backend/src/main/java/com/stock/dto/StockListResponse.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` (extended: `limit`/`allowed`/`stockId` fields + explicit `@JsonCreator` — see Notes)
  - `develop/backend/src/main/java/com/stock/exception/InvalidPaginationException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/PageSizeExceededException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/InvalidMarketException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/InvalidSortFieldException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/StockNotFoundException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` (extended: handlers for the 5 new exceptions)
  - `develop/backend/src/test/java/com/stock/StockCatalogIntegrationTest.java` (new, 13 tests)
- Notes:
  - `GET /api/stocks` and `GET /api/stocks/{stockId}` implemented per contract. Pagination/sort/market are validated with the exact error codes in the spec; `sort` is whitelisted to `stockId`/`stockName`/`market` only (mapped to real columns server-side; `${}` interpolation in MyBatis XML is only reachable after that whitelist check, never from raw request input).
  - "先分頁、再取行情": `StockQueryService.listStocks` pages the `stock` table first (`StockMapper.findPage`/`countPage`, no join to `stock_daily_price`), then batches the resulting page's stock ids into a single `StockDailyPriceMapper.findLatestTwoByStockIds` call using a `ROW_NUMBER() OVER (PARTITION BY stock_id ORDER BY trade_date DESC)` window query restricted to `stock_id IN (<page ids>)`. Verified empirically at a 124-stock / 244-price-row scale: a `size=20` page's price query returned exactly 40 rows (2 per stock in that page only), independent of the 244 total rows in `stock_daily_price` for the test dataset — bounded by page size, not universe size, matching the intent of "查詢筆數不超過該頁 size 所涵蓋的股票數" (2 rows/stock is needed for latest+previous close; the important property — cost scales with the page, not with all ~2200 stocks — holds).
  - Single-stock detail (`getStockDetail`) uses `findLatestTwoByStockId` (`ORDER BY trade_date DESC LIMIT 2`) plus `findPriceStats` (`MIN(trade_date)`/`COUNT(*)`), both scoped to one `stock_id` — cheap because it's a single-row lookup, per spec's own framing ("附帶清單負擔不起的彙總欄位").
  - Bug found and fixed during verification: `is_active` was not mapping into `Stock.active` via MyBatis auto-mapping (`map-underscore-to-camel-case` converts `is_active` → `isActive`, but the existing `Stock` domain's property is named `active`, not `isActive`). Fixed by aliasing `is_active AS active` in the new `findById`/`findPage` SELECTs. Verified the bug and the fix live (curl showed `"isActive": null` before, `true`/`false` correctly after).
  - Bug found and fixed: extending `ErrorResponse` with additional constructor overloads broke Jackson's implicit single-constructor auto-detection used by `TestRestTemplate` deserialization (`Cannot construct instance ... no delegate- or property-based Creator`), which also broke 3 previously-passing `StockPriceIngestionIntegrationTest` cases as a side effect. Fixed with an explicit `@JsonCreator`-annotated all-args constructor + `@JsonProperty` on each parameter, restoring unambiguous deserialization for every error shape. Re-ran the full suite afterward and confirmed all `StockPriceIngestionIntegrationTest` cases pass again.
  - Verified live end-to-end via `mvn spring-boot:run` against the real dev DB with hand-inserted/cleaned-up test rows (`ZC01`–`ZC04`, plus a 120-row `ZP0xx` batch for the pagination-scale check): default list, keyword search by id and by Chinese name, market filter + invalid market, includeInactive toggle, size/pagination/sort validation errors, no-price and single-day-price stocks, detail with `firstTradeDate`/`tradingDayCount`, and 404 for an unknown stock id. All test rows were deleted afterward (`stock`/`stock_daily_price` confirmed back to 0 rows) and the app process was stopped, freeing port 8080.
  - Automated coverage: `develop/backend/src/test/java/com/stock/StockCatalogIntegrationTest.java` (13 tests, `@SpringBootTest` against the live dev DB with its own `ZC%`-prefixed setup/teardown) covers every Acceptance Criteria item above. Full suite: `mvn -f develop/backend/pom.xml test` → `Tests run: 32, Failures: 0, Errors: 0` (`BackendApplicationTests`, `StockCatalogIntegrationTest`, `StockPriceIngestionIntegrationTest`, `NormalizeUtilTest`).
  - No DB schema changes; no changes to `docker/launch.json` (backend entry already correct from `stock-price-ingestion`).

### Increment 2 — 2026-08-30

Implements the three write endpoints (`POST`/`PUT`/`DELETE /api/stocks{,/{stockId}}`) described in the spec's `#### 3`–`#### 5` sections. The two read endpoints from Increment 1 were not touched.

- Files changed:
  - `develop/backend/src/main/java/com/stock/service/StockCatalogService.java` (new) — create/update/soft-delete; kept separate from `StockQueryService`, which is documented read-only.
  - `develop/backend/src/main/java/com/stock/controller/StockController.java` (extended: `POST`, `PUT /{stockId}`, `DELETE /{stockId}`)
  - `develop/backend/src/main/java/com/stock/mapper/StockMapper.java` (extended: `insert`, `update`)
  - `develop/backend/src/main/resources/mapper/StockMapper.xml` (extended, same two statements)
  - `develop/backend/src/main/java/com/stock/dto/CreateStockRequest.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/UpdateStockRequest.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/StockDeleteResponse.java` (new) — the small `{stockId, stockName, isActive}` shape the `DELETE` response uses, distinct from `StockDetailDto`.
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` (extended: `fields` property + `invalidStockPayload(List<String>)` factory; `@JsonCreator` constructor updated to the new arity)
  - `develop/backend/src/main/java/com/stock/exception/StockAlreadyExistsException.java` (new) → 409 `STOCK_ALREADY_EXISTS`
  - `develop/backend/src/main/java/com/stock/exception/InvalidStockPayloadException.java` (new) → 400 `INVALID_STOCK_PAYLOAD` with `fields`
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` (extended: handlers for the two exceptions above)
  - `develop/backend/src/test/java/com/stock/StockCatalogWriteIntegrationTest.java` (new, 11 tests, own `ZW`-prefixed fixtures)
- Notes:
  - **Duplicate check never silently updates.** `POST` does `findById` first and throws `StockAlreadyExistsException` (409) rather than falling back to the pre-existing `stockMapper.upsert(...)` used by the ingestion pipeline — reusing `upsert` here would have silently overwritten an existing stock's name, which the spec explicitly forbids. `upsert` itself was left untouched (still used by `PriceIngestionService`).
  - **Check-then-act race closed.** `createStock` calls `findById` then `insert`; two concurrent `POST`s for the same `stockId` could otherwise both pass the pre-check and let the loser's `INSERT` hit the primary-key constraint, surfacing as a raw 500 instead of the spec's 409. Added a `catch (DuplicateKeyException e)` around the insert that translates it into the same `StockAlreadyExistsException` → 409. Found via the `code-quality` skill's concurrency checklist, not by a failing test (a genuine race is impractical to reproduce deterministically in a single-process integration test).
  - **`stockId` is immutable on `PUT`.** `UpdateStockRequest` has no `stockId` field at all — it is the path variable and primary key that `stock_daily_price`/`stock_daily_indicator`/`stock_sync_progress` all hang off, matching the spec's explicit rationale.
  - **Validation precedence** (not fully pinned down by the spec's acceptance criteria, so documented here as the concrete decision): for both `POST` and `PUT`, field-shape checks (`stockId`/`stockName` blank-or-oversized, `isActive` missing on `PUT`) are checked first → `INVALID_STOCK_PAYLOAD`; then `market` membership → `INVALID_MARKET`; then, for `POST`, existence → `STOCK_ALREADY_EXISTS`, or for `PUT`, existence → `STOCK_NOT_FOUND`. No acceptance criterion exercises two simultaneous violations, so this ordering was never actually forced by a test — noted for whoever revisits this.
  - **Soft delete is genuinely soft.** `deactivateStock` never issues a `DELETE` SQL statement — only `UPDATE stock SET is_active = 0 ...`, and only when the row isn't already inactive (idempotent no-op otherwise, still 200). Verified explicitly in `delete_softDeletesWithoutTouchingPriceOrIndicatorRows`: seeded one `stock_daily_price` row and one `stock_daily_indicator` row for a test stock, captured counts before/after the `DELETE` call, and asserted they're identical (and both still `1`, i.e., not merely "still zero").
  - Full suite: `mvn -f develop/backend/pom.xml test` → `Tests run: 98, Failures: 0, Errors: 0` across all 10 test classes (`BackendApplicationTests`, `StockCatalogIntegrationTest` (13), `StockCatalogWriteIntegrationTest` (11, new), `StockIndicatorStatisticsIntegrationTest` (16), `StockMinutePriceIntegrationTest` (15), `StockPriceIngestionIntegrationTest` (15), `IndicatorCalculationServiceTest` (7), `IndicatorRebuildServiceTest` (5), `StartupCatchUpRunnerTest` (5), `NormalizeUtilTest` (10)).
  - Live DB verified unchanged before and after the full run: `stock` = 34 rows (all `is_active=1`), `stock_daily_price` = 5372, `stock_daily_indicator` = 5372, `stock_sync_progress` = 68 — identical to the pre-existing baseline. All new test fixtures use a `ZW`-prefixed `stock_id` and are deleted in `@BeforeEach`/`@AfterEach`; no `Z%`-prefixed row remained after the run.
  - Did not run `mvn spring-boot:run` per instructions (port 8080 risk); all verification is via the `@SpringBootTest(webEnvironment = RANDOM_PORT)` integration tests against the live dev DB.
  - Left unfixed / deliberately out of scope: no bean-validation (`@Valid`) annotations were added to `CreateStockRequest`/`UpdateStockRequest` — validation is done manually in `StockCatalogService` so the exact `fields` list in `INVALID_STOCK_PAYLOAD` can be constructed precisely, matching the spec's example response shape; a generic `@Valid`-driven `MethodArgumentNotValidException` would have collapsed to the existing generic `VALIDATION_ERROR` code instead.
