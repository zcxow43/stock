---
status: done
title: "模擬交易持股 API"
requirement: "模擬交易分頁 — 輸入股票代號與買進日（買進日可指定，預設為全市場最後一個交易日）即建立一筆模擬持股（買進價為該檔該日收盤、固定 1 張），列表回報每筆以最新收盤價計算的未實現損益與報酬率（扣買進手續費、以現價估的賣出手續費與證交稅），可刪除單筆"
depends_on: [stock-catalog, stock-price-ingestion]
---

# 模擬交易持股 API — Backend Spec

## Overview

讓使用者用最少的輸入（只有代號）記下「假設我在最近一個收盤價買了一張，到今天賺賠多少」。持股本身存在 `simulated_trade`（見 `specs/dba/simulated-trade.md`）；**未實現損益、報酬率與現價都在每次查詢當下即時算出、不落地**——它們隨最新收盤價每天改變。畫面是 `specs/frontend/simulated-trade.md`。

**它不抓任何外部資料、也不做型態判定**：只讀 `stock_daily_price` 已有的收盤價與 `stock` 的名稱，做四則運算。與 `specs/backend/strategy-backtest.md` 的關係是「同一套成本口徑、不同的出場假設」：回測的出場是區間內最高開盤價，本模組的出場是**假設今天以最新收盤價賣掉**。

## Requirements

### 一筆持股怎麼建立

使用者送出 `stockId` 與（選填的）`buyDate`：

| 項目 | 規則 |
|---|---|
| 買進日 | 請求的 `buyDate`；**省略時**為該檔在 `stock_daily_price` 中 `trade_date <= 今日`（台北日曆日）且 `close_price > 0` 的**最大** `trade_date` |
| 買進價 | 該檔在買進日那一天的 `close_price`（必須存在且 > 0，否則拒絕） |
| 股數 | 固定 `1000`（1 張），與 `specs/backend/strategy-backtest.md` 的部位約定相同 |

- **買進日可指定、買進價與股數不可指定**：價格不是使用者自己填的數字，而是那一天實際的收盤價——開放輸入等於允許一筆與市場無關的成本。要換價格就換日期。
- **買進日不得晚於今日**：未來還沒有收盤價，回 `400 INVALID_BUY_DATE`。今日本身可以選，但只有在今日收盤價已寫入時才成立，否則同樣是「那天沒有收盤價」。
- **那一天必須正好有該檔的收盤價**：停牌、尚未上市、或該日 `close_price = 0`（「該日沒有成交」的記錄方式，見 `specs/backend/strategy-backtest.md` 的「價格為 `0` 的日子不是行情」）都回 `400 NO_PRICE_ON_BUY_DATE`。**不得自動往前找最近的交易日**——使用者指定 2026-09-13（週日）時，靜靜地改用 09-11 的價格建立一筆，畫面上會出現一個他沒有選過的日期。回報錯誤，讓他自己改。
- **買進價寫入後不再變動**：即使該日收盤價事後被來源更正，這筆持股的成本不變（理由見 `specs/dba/simulated-trade.md`）。

### 預設買進日（`defaultBuyDate`）

`GET` 的回應帶一個 `defaultBuyDate`：**全市場 `trade_date <= 今日` 且 `close_price > 0` 的最大 `trade_date`**，也就是「上一次收盤日」。畫面用它當日期輸入的預設值（見 `specs/frontend/simulated-trade.md`）。

**它是全市場的、不是某一檔的**：使用者還沒輸入代號時就要有一個預設日期可填，那時沒有「哪一檔」可問。個別股票在那天可能剛好停牌——那就是上面的 `NO_PRICE_ON_BUY_DATE`，由使用者改日期，不是由系統猜。

資料庫完全沒有行情時 `defaultBuyDate` 為 `null`。

### 現價與未實現損益

| 項目 | 規則 |
|---|---|
| 現價日 | 該檔 `trade_date <= 今日` 且 `close_price > 0` 的**最大** `trade_date` |
| 現價 | 該日的 `close_price` |
| 成本 | `買進價 × 股數 ＋ 買進手續費` |
| 未實現損益 | `現價 × 股數 − 賣出手續費 − 證交稅 − 成本` |
| 報酬率 | `未實現損益 ÷ 成本 × 100`，四捨五入至小數第二位 |

**交易成本與 `specs/backend/strategy-backtest.md` 的「交易成本」完全同一套**，不另立一份定義：手續費率 `0.1425%`（買進與賣出各一次、不打券商折扣、不設最低）、證交稅 `0.3%`（只在賣出時）、三項**各自無條件捨去至元**、以精確十進位運算而非二進位浮點數。差別只有一個：**賣出那一側是「假設今天賣掉」的估計**，以現價計算。

- **賣出成本要先扣**：不扣的話，畫面上的未實現損益會比真的賣掉拿得到的錢多一截，而使用者拿它跟策略分頁（已扣）的數字並排看。同一個系統裡兩種口徑，比錯的數字更難發現。
- **現價日等於買進日時，未實現損益必為負**（剛買進、只付出成本），這是正確結果，不得夾為 `0`。
- 現價日**可能早於今日**（例如假日、或該檔已下市停止交易），回應因此逐筆帶出現價日，由畫面顯示（見 `specs/frontend/simulated-trade.md`）。

### 彙總

| 欄位 | 算法 |
|---|---|
| 總成本 | `Σ(成本)` |
| 總未實現損益 | `Σ(未實現損益)` |
| 總報酬率 | `總未實現損益 ÷ 總成本 × 100`，兩位小數；**沒有任何持股時為 `null`**（不是 `0`） |

以成本加權，理由與 `specs/backend/strategy-backtest.md` 的彙總相同：部位固定每筆 1 張，各檔股價量級差距大，算術平均會讓一張低價股與一張高價股有同樣的權重。

### 已下市或已無行情的持股

持股建立之後該檔可能下市。**已下市（`is_active = 0`）的股票照常回報**，不從列表中消失，也不被排除在彙總之外——它的現價就是它最後一個有成交的交易日的收盤價，現價日欄位會把那一天說出來。與 `specs/backend/strategy-backtest.md`「不篩 `is_active`」一致。

## Implementation Details

### API 契約

```
GET /api/simulated-trades
```

Response `200`：

```json
{
  "asOfDate": "2026-09-20",
  "defaultBuyDate": "2026-09-19",
  "lotSize": 1000,
  "feeRatePercent": 0.1425,
  "taxRatePercent": 0.3,
  "totalCost": 1151638,
  "totalUnrealizedProfit": 48030,
  "totalReturnPercent": 4.17,
  "items": [
    {
      "id": 12,
      "stockId": "2330",
      "stockName": "台積電",
      "buyDate": "2026-09-18",
      "buyPrice": 1150.00,
      "shares": 1000,
      "buyFee": 1638,
      "cost": 1151638,
      "currentDate": "2026-09-19",
      "currentPrice": 1205.00,
      "sellFee": 1717,
      "sellTax": 3615,
      "unrealizedProfit": 48030,
      "returnPercent": 4.17
    }
  ]
}
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `asOfDate` | date | 本次查詢的今日（台北日曆日） |
| `defaultBuyDate` | date \| null | 全市場上一次收盤日，供畫面當日期輸入的預設值；資料庫無任何行情時為 `null` |
| `lotSize` | int | 每筆股數，恆為 `1000`；回報它，呼叫端不必自己知道這個約定 |
| `feeRatePercent` / `taxRatePercent` | number | 恆為 `0.1425` / `0.3`，供畫面說明扣了什麼 |
| `totalCost` | number | 各筆 `cost` 的總和；無持股時為 `0` |
| `totalUnrealizedProfit` | number | 各筆 `unrealizedProfit` 的總和；無持股時為 `0` |
| `totalReturnPercent` | number \| null | 成本加權；**無持股時為 `null`** |
| `items[].id` | int | 這筆持股的識別碼，刪除時用 |
| `items[].stockName` | string | 取自 `stock`（見 `specs/dba/stock.md`） |
| `items[].buyDate` / `buyPrice` / `shares` | date / number / int | 建立時寫入的值，原樣回報 |
| `items[].buyFee` / `sellFee` / `sellTax` / `cost` | int | 元，整數 |
| `items[].currentDate` / `currentPrice` | date / number | 現價日與該日收盤價 |
| `items[].unrealizedProfit` | int | 元，**可為負值** |
| `items[].returnPercent` | number | 兩位小數，**可為負值** |

`items` 依 `buyDate` **由新到舊**排序，同日依 `stockId` 升冪——最近加入的在最上面，與使用者的操作順序一致。排序由本端點決定，畫面不再重排。

**沒有任何持股時回 `200`**，`items` 為空陣列、`totalCost` 與 `totalUnrealizedProfit` 為 `0`、`totalReturnPercent` 為 `null`。空清單不是錯誤。

```
POST /api/simulated-trades
```

Request：

```json
{ "stockId": "2330", "buyDate": "2026-09-18" }
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockId` | string | 是 | 股票代號；前後空白去除後比對，須存在於 `stock`（不篩 `is_active`） |
| `buyDate` | date | 否 | 買進日（台北日曆日）；不得晚於今日；省略時為該檔最後一個有收盤價的交易日 |

Response `201`：與 `GET` 的 `items[]` 單筆同一個形狀（含即時算出的現價與未實現損益），讓呼叫端能直接確認系統替它選了哪一個買進日與買進價。**畫面仍會在成功後重新取得列表**（排序與三個總計一律由後端決定，見 `specs/frontend/simulated-trade.md`）——這個回應體是「剛才建立了什麼」的回執，不是列表的替代品。

```
DELETE /api/simulated-trades/{id}
```

Response `204`，無內容。

驗證與錯誤：

| 情形 | 狀態碼 | 回應 |
|---|---|---|
| `stockId` 缺漏或去除空白後為空 | `400` | `{"code":"INVALID_STOCK_ID"}` |
| `stockId` 不存在於 `stock` | `400` | `{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}` |
| 該檔完全沒有任何 `close_price > 0` 的交易日（`buyDate` 省略時） | `400` | `{"code":"NO_PRICE_BEFORE_TODAY","stockId":"9999"}` |
| `buyDate` 格式不合法或晚於今日 | `400` | `{"code":"INVALID_BUY_DATE","buyDate":"2099-01-01"}` |
| 該檔在指定的 `buyDate` 沒有收盤價（無該列或 `close_price = 0`） | `400` | `{"code":"NO_PRICE_ON_BUY_DATE","stockId":"2330","buyDate":"2026-09-13"}` |
| `(stockId, buyDate)` 已存在 | `409` | `{"code":"DUPLICATE_SIMULATED_TRADE","stockId":"2330","buyDate":"2026-09-18"}` |
| `DELETE` 的 `id` 不存在 | `404` | `{"code":"SIMULATED_TRADE_NOT_FOUND","id":99}` |

`UNKNOWN_STOCK_ID` 沿用 `specs/backend/strategy-scan.md` 已定的同名錯誤碼與回應形狀（同一個畫面上的兩支端點對同一種錯誤用兩套代碼，前端就得寫兩份處理）。**重複回 `409` 而不是 `400`**：請求本身完全合法，只是這筆持股已經在那裡了——前端要據此顯示「這一檔今天已經加過了」，與「你打錯代號」是兩種不同的回饋。

### 處理流程

`GET`：

1. 取今日（台北日曆日）為 `asOfDate`，並查出全市場上一次收盤日為 `defaultBuyDate`（一次查詢；無行情時為 `null`）。
2. 讀出 `simulated_trade` 全部列（列數是使用者手動加入的量級）。
3. **以整份代號清單為條件批次查詢**各檔的現價（`trade_date <= asOfDate` 且 `close_price > 0` 的最大 `trade_date` 與其收盤價）與名稱——不得逐筆各發一次查詢，與 `specs/backend/strategy-backtest.md`「有界次數的查詢」同一條規定。清單為空時直接回空結果，不把空集合交給資料庫。
4. 逐筆算出四項費用、成本、未實現損益與報酬率，彙總後依 `buyDate` 由新到舊組出 `items`。

`POST`：

1. 驗證 `stockId`（非空、存在於 `stock`）與 `buyDate`（有值時須為合法日期且不晚於今日，否則 `400 INVALID_BUY_DATE`）。
2. 取買進日與買進價：
   - `buyDate` 有值 → 查該檔該日的 `close_price`；無該列或為 `0` → `400 NO_PRICE_ON_BUY_DATE`。
   - `buyDate` 省略 → 查該檔 `trade_date <= 今日` 且 `close_price > 0` 的最大 `trade_date` 與其收盤價；查無 → `400 NO_PRICE_BEFORE_TODAY`。
3. `INSERT` 一列（`shares = 1000`）；唯一鍵衝突 → `409 DUPLICATE_SIMULATED_TRADE`（不得改成 UPSERT）。
4. 以剛寫入的列即時算出與 `GET` 相同形狀的單筆回應。

`DELETE`：以 `id` 刪一列，影響列數為 `0` → `404`。

**三支端點都不抓取任何外部資料、不建立 `stock_sync_progress` 列、不與任何併發鎖互斥。**

## Acceptance Criteria

### 建立持股
- [x] `POST /api/simulated-trades` 帶 `{"stockId":"2330"}` 回 `201`，`buyDate` 等於該檔最後一個 `close_price > 0` 的交易日，`buyPrice` 等於該日收盤價，`shares` 為 `1000`
- [x] 省略 `buyDate` 時 `close_price` 為 `0` 的交易日被跳過，取再前一個 `close_price > 0` 的交易日
- [x] 建立後修改 `stock_daily_price` 中該買進日的收盤價，再查 `GET`：該筆的 `buyPrice` 與 `cost` 不變

### 指定買進日（本次新增）
- [x] 帶 `{"stockId":"2330","buyDate":"<某個更早的交易日>"}` 回 `201`，`buyDate` 等於送出的日期、`buyPrice` 等於該檔該日的 `close_price`；未實現損益仍以最新收盤價計算
- [x] 同一檔以兩個不同的 `buyDate` 各加一筆皆成功，`GET` 依買進日由新到舊列出兩筆，兩筆各自計入彙總
- [x] 今日已有該檔收盤價時，帶今日為 `buyDate` 可成立（`buyPrice` 為今日收盤、未實現損益為負的費用）
- [x] `buyDate` 晚於今日（例如 `2099-01-01`）→ `400 INVALID_BUY_DATE`，且未寫入任何列；格式不合法的字串同樣回 `400 INVALID_BUY_DATE`
- [x] 指定的 `buyDate` 該檔無該列（例如週日）或 `close_price = 0` → `400 NO_PRICE_ON_BUY_DATE`（帶 `stockId` 與 `buyDate`），**不得**自動改用鄰近交易日建立，且未寫入任何列
- [x] `GET` 回的 `defaultBuyDate` 等於全市場 `trade_date <= 今日` 且 `close_price > 0` 的最大交易日；它與個別股票的最後交易日無關（構造一檔更早停止交易的股票驗證）
- [x] 回應為與 `GET` 的 `items[]` 相同的形狀，含 `currentDate`／`currentPrice`／`unrealizedProfit`／`returnPercent`

### 未實現損益與成本
- [x] 買進價 `1150.00`、現價 `1205.00`：`buyFee` `1638`、`cost` `1151638`、`sellFee` `1717`、`sellTax` `3615`、`unrealizedProfit` `48030`、`returnPercent` `4.17`（與 `specs/backend/strategy-backtest.md` 同一組數字）
- [x] 尾數無條件捨去：買 `21.55`、現價 `23.10` → `buyFee` `30`、`sellFee` `32`、`sellTax` `69`、`cost` `21580`、`unrealizedProfit` `1419`、`returnPercent` `6.58`
- [x] 精確十進位：買賣皆 `200.00` 時 `buyFee` 與 `sellFee` 皆為 `285`（不是 `284`）、`sellTax` `600`、`unrealizedProfit` `−1170`
- [x] 現價日等於買進日（今日尚無新行情）時 `unrealizedProfit` 為負、不為 `0`
- [x] 現價取 `trade_date <= 今日` 且 `close_price > 0` 的最大交易日：該檔最新一日收盤為 `0` 時，`currentDate` 為再前一個交易日
- [x] 已下市（`is_active = 0`）的股票照常回報，不被排除，`currentDate` 為它最後一個有成交的交易日

### 列表與彙總
- [x] `GET /api/simulated-trades` 回 `lotSize` `1000`、`feeRatePercent` `0.1425`、`taxRatePercent` `0.3`，`asOfDate` 為今日
- [x] `totalCost`／`totalUnrealizedProfit` 等於各筆的總和；`totalReturnPercent` 等於 `總未實現損益 ÷ 總成本 × 100`（兩位小數），且以兩檔股價差距懸殊的資料驗證它**不等於**兩檔報酬率的算術平均
- [x] 無任何持股時回 `200`、`items` 為 `[]`、兩個總額為 `0`、`totalReturnPercent` 為 **`null`**
- [x] `items` 依 `buyDate` 由新到舊、同日依 `stockId` 升冪
- [x] 行情以批次查詢讀取：持股筆數由 2 筆增為 20 筆時，查詢次數不變

### 驗證與刪除
- [x] `stockId` 缺漏或為空白字串 → `400 INVALID_STOCK_ID`；代號前後有空白時去除後仍可建立
- [x] 不存在的代號 → `400 UNKNOWN_STOCK_ID`，`unknownIds` 含該代號
- [x] 今日以前沒有任何 `close_price > 0` 的交易日 → `400 NO_PRICE_BEFORE_TODAY`，且**未寫入任何列**
- [x] 同一檔在同一天加入第二次 → `409 DUPLICATE_SIMULATED_TRADE`（帶 `stockId` 與 `buyDate`），資料庫仍只有一列
- [x] `DELETE /api/simulated-trades/{id}` 回 `204` 且該列消失；再刪一次回 `404 SIMULATED_TRADE_NOT_FOUND`
- [x] 三支端點都不寫入 `stock`、`stock_daily_price`、`stock_sync_progress`：呼叫前後三張表的列數與內容不變

---
## Execution Result
- Status: DONE

- Files changed:
  - `develop/backend/src/main/java/com/stock/util/TradingCostCalculator.java` (new) — the shared trading-cost model (fee 0.1425% buy+sell, tax 0.3% sell-only, each floored to whole yuan independently, `BigDecimal` throughout) extracted out of `StrategyBacktestService` so both endpoints share one definition, per this spec's own instruction ("交易成本與 `specs/backend/strategy-backtest.md` 的『交易成本』完全同一套，不另立一份定義").
  - `develop/backend/src/main/java/com/stock/service/StrategyBacktestService.java` — refactored to delegate to `TradingCostCalculator` instead of its own private `computeFeeOrTax`/`computeCost`/`computeProfit`/`computeReturnPercent`/`computeTotalReturnPercent` methods and duplicated rate/scale constants; `LOT_SIZE` now aliases `TradingCostCalculator.LOT_SIZE`. Behavior unchanged — all 54 existing tests in `StrategyBacktestIntegrationTest` still pass unmodified.
  - `develop/backend/src/main/java/com/stock/domain/SimulatedTrade.java` (new) — maps `simulated_trade` (id, stockId, buyDate, buyPrice, shares, createdAt, updatedAt).
  - `develop/backend/src/main/java/com/stock/mapper/SimulatedTradeMapper.java` (new) + `develop/backend/src/main/resources/mapper/SimulatedTradeMapper.xml` (new) — `findAllOrderedByBuyDateDescStockIdAsc` (sort done at the SQL level, matching the wire contract exactly), plain `insert` (never UPSERT; a `(stock_id, buy_date)` conflict surfaces as `DuplicateKeyException` via the table's existing unique key), `deleteById`.
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` / `.xml` — two additions: `findLatestPositiveCloseBefore` (single-stock buy-day lookup for POST) and `findLatestPositiveCloseOnOrBeforeByStockIds` (batched, window-function current-price lookup for GET/POST, one query regardless of holding count).
  - `develop/backend/src/main/java/com/stock/service/SimulatedTradeService.java` (new) — `list()`, `create()`, `delete()`; two batched queries total for `GET` (current prices + stock names) regardless of row count, four bounded queries for `POST` (existence check, buy-price lookup, insert, current-price lookup for the response body).
  - `develop/backend/src/main/java/com/stock/controller/SimulatedTradeController.java` (new) — `GET`/`POST` (201)/`DELETE` (204) `/api/simulated-trades`.
  - `develop/backend/src/main/java/com/stock/dto/CreateSimulatedTradeRequest.java`, `SimulatedTradeItemDto.java`, `SimulatedTradeResponseDto.java` (new).
  - `develop/backend/src/main/java/com/stock/exception/InvalidStockIdException.java`, `NoPriceBeforeTodayException.java`, `DuplicateSimulatedTradeException.java`, `SimulatedTradeNotFoundException.java` (new).
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` — added `buyDate`/`id` fields plus `noPriceBeforeToday`/`duplicateSimulatedTrade`/`simulatedTradeNotFound` factories, following this file's existing telescoping-constructor convention (every existing factory call site updated to the new 13-arg form; behavior for all pre-existing error codes unchanged, verified by the full suite staying green).
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — four new `@ExceptionHandler`s (`INVALID_STOCK_ID`→400, `NO_PRICE_BEFORE_TODAY`→400, `DUPLICATE_SIMULATED_TRADE`→409, `SIMULATED_TRADE_NOT_FOUND`→404).
  - `develop/backend/src/test/java/com/stock/SimulatedTradeIntegrationTest.java` (new) — 23 tests, `ST`-prefixed synthetic stock ids, modeled on `StrategyBacktestIntegrationTest`.

- Design decisions:
  - **Shared cost model as a plain stateless utility, not an interface/Strategy.** Invoked the `design-patterns` skill before extracting: there is exactly one trading-cost algorithm with two callers using it identically (only the sell-side price source differs, and that stays in each caller) — no variation to abstract behind an interface, so a `final` class with static methods (same shape as the project's existing `NormalizeUtil`) is the right amount of machinery, not a Factory/Strategy.
  - **`ErrorResponse` extended in place, not rebuilt as a Builder.** The class is already a textbook telescoping-constructor (11→13 positional args), which the `design-patterns` skill would normally flag — but this is an entrenched, dozens-of-call-sites-deep project convention every other endpoint's errors already follow, and this spec's three new error codes only need two more nullable fields. A full Builder rewrite here would be a large, high-risk diff unrelated to this spec's acceptance criteria; extended the existing pattern instead and verified every pre-existing factory still round-trips correctly (full suite green).
  - **`buildResponse`'s per-stock dedup uses `LinkedHashSet`, not `List#contains`** — flagged by the `code-quality` self-review pass as an avoidable O(n²) scan; fixed before considering the task done, then re-verified with the full `SimulatedTradeIntegrationTest` run.
  - **Existence check reuses `StockMapper.findById`** (single stock, not the batched `findExistingStockIds` `StrategyBacktestService` uses for lists) — the right-sized query for a single-`stockId` POST body, and its result (`Stock`, not just a boolean) is reused directly for the response's `stockName`, avoiding a second query.
  - **`toItemDto`'s missing-current-row case throws `IllegalStateException`** rather than silently defaulting: per the spec, the buy day itself always carries a positive close (that is how it was chosen) and always satisfies `trade_date <= asOfDate`, so the current-price lookup is guaranteed to find at least that row for every row this service itself wrote. Surfacing an invariant violation as a loud 500 (via the existing catch-all handler) beats a silent, incorrect fallback.
  - **Sort order (`buyDate` DESC, `stockId` ASC) is done in `ORDER BY` at the SQL level**, not re-sorted in Java after the fact — the wire contract's ordering rule then has exactly one place it is expressed.

- Verification (real command output tails):
  - `mvn -f develop/backend/pom.xml compile` — `BUILD SUCCESS`.
  - New test class alone: `mvn -f develop/backend/pom.xml test -Dtest=SimulatedTradeIntegrationTest` → `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`.
  - Refactor regression check: `mvn -f develop/backend/pom.xml test -Dtest=SimulatedTradeIntegrationTest,StrategyBacktestIntegrationTest` → `Tests run: 77, Failures: 0, Errors: 0, Skipped: 0` (23 + 54, both green — the `TradingCostCalculator` extraction did not change `StrategyBacktestService`'s behavior).
  - Full suite: `mvn -f develop/backend/pom.xml test` → `Tests run: 573, Failures: 6, Errors: 0, Skipped: 0`. The 6 failures are all in `StockInstitutionalTradeIngestionIntegrationTest` (hardcoded 2026-03 dates vs. today's `LocalDate.now()`), confirmed pre-existing and out of scope per the task instructions — not touched.
  - Ran against the real MySQL database on `127.0.0.1:3306` (the same one the other integration tests and the live `/start` server use), via `mvn test`'s own `RANDOM_PORT` `SpringBootTest` context — never started a second `spring-boot:run` on `:8080`, and confirmed the pre-existing `/start` launcher (PID from `.run/backend.json`) was left running throughout and untouched.
  - Verified `simulated_trade` is empty (`SELECT COUNT(*) = 0`) and no `ST%`-prefixed rows remain in `stock`/`stock_daily_price`/`stock_sync_progress` after every test run — `@BeforeEach`/`@AfterEach` cleanup confirmed effective.
  - `code-quality` skill self-review pass: found one real Important-level issue (the `List#contains` O(n²) dedup noted above) and fixed it; no other Critical/Important findings applied — null-safety, error handling (`DuplicateKeyException` → 409 following `StockCatalogService`'s existing check-then-act pattern), transaction boundaries, and the bounded-query-count requirement all checked out against the diff.
- Notes: none left unfixed. All 22 acceptance criteria verified and checked off above.

---
### Increment 1 — 2026-09-21

This increment implements the 6 previously-unchecked criteria under "指定買進日（本次新增）": an optional `buyDate` on `POST /api/simulated-trades`, its `INVALID_BUY_DATE`/`NO_PRICE_ON_BUY_DATE` validation, and a market-wide `defaultBuyDate` on `GET /api/simulated-trades`. Ran against the live remote database (`env.md`'s `Use Env: true`), not the local `application.yml` connection — credentials sourced from the external env file one directory above the project root (`../env`, per `.claude/rules/external-env.md`) and passed as `SPRING_DATASOURCE_*` environment variables on the same command line as `mvn test`; never printed, echoed, or written to any file.

- Files changed:
  - `develop/backend/src/main/java/com/stock/dto/CreateSimulatedTradeRequest.java` — added `buyDate`, deliberately typed as a raw `String` (not `LocalDate`) so a malformed value can reach the service and come back as `400 INVALID_BUY_DATE` with the offending string, instead of failing Jackson deserialization generically before the service ever runs.
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` / `.xml` — added `findMarketWideLatestPositiveCloseDate(asOfDate)`: `SELECT MAX(trade_date) ... WHERE trade_date <= asOfDate AND close_price > 0`, not scoped to any stock id — the one query `defaultBuyDate` needs. `findOne` (pre-existing) is reused, unmodified, for the exact-date buy-price lookup on the explicit-`buyDate` path.
  - `develop/backend/src/main/java/com/stock/exception/InvalidSimulatedTradeBuyDateException.java` (new) — carries the raw, as-submitted `buyDate` string. Kept separate from the pre-existing `InvalidBuyDateException` (POST /api/strategies/backtest's own same-JSON-code error, whose body shape carries `stockId` instead of `buyDate`) — same wire `code`, two different endpoints, two different body shapes, so two different exception/handler/factory triples rather than forcing one shape to serve both.
  - `develop/backend/src/main/java/com/stock/exception/NoPriceOnBuyDateException.java` (new) — carries `stockId` + the parsed `buyDate`.
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` — `buyDate` field's Java type widened from `LocalDate` to `String` (wire format unchanged: Spring Boot's default Jackson config already serializes `LocalDate` as the same `yyyy-MM-dd` string a `String` field holds, so this is byte-for-byte identical on the wire for the pre-existing `DUPLICATE_SIMULATED_TRADE` caller). Needed because the new `INVALID_BUY_DATE` (this endpoint's own) error must be able to echo back a value that cannot always parse as a `LocalDate`. Added two factories: `invalidSimulatedTradeBuyDate(String)` and `noPriceOnBuyDate(String, LocalDate)`.
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — two new `@ExceptionHandler`s, both `400`: `InvalidSimulatedTradeBuyDateException` and `NoPriceOnBuyDateException`.
  - `develop/backend/src/main/java/com/stock/dto/SimulatedTradeResponseDto.java` — added `defaultBuyDate` (`LocalDate`, nullable).
  - `develop/backend/src/main/java/com/stock/service/SimulatedTradeService.java` — `list()` now also runs the one market-wide max-date query and sets `defaultBuyDate` on the response. `create()`'s buy-date resolution is now a plain if/else on whether the request's raw `buyDate` string is blank: blank → unchanged pre-existing `findLatestPositiveCloseBefore` flow; non-blank → parse (catch `DateTimeParseException` → `INVALID_BUY_DATE`), reject `isAfter(today)` → `INVALID_BUY_DATE`, look up that exact date via `findOne` and require a positive `close_price` → otherwise `NO_PRICE_ON_BUY_DATE`, **never** falling back to a nearby trading day.
  - `develop/backend/src/test/java/com/stock/SimulatedTradeIntegrationTest.java` — 6 new tests under a new `// ==================== 指定買進日（本次新增） ====================` block (`ST100`–`ST106`), plus two small helper overloads (`createBody`/`createAndReadJson`/`createRequest` with a `buyDate` parameter) and a `textOrNull(JsonNode)` helper. One line in the pre-existing (checked, untouched-behavior) `duplicateSameDay_returns409_dbStillHasOnlyOneRow` test changed from `assertEquals(buyDate, second.getBody().getBuyDate())` to `assertEquals(buyDate.toString(), ...)` — required purely by `ErrorResponse.getBuyDate()`'s Java return type changing from `LocalDate` to `String` above; the JSON on the wire, and therefore this test's actual behavior, is unchanged.

- Design decisions:
  - **Invoked the `design-patterns` skill before writing the buy-date resolution branch.** Conclusion: this is "exactly one call site, two mutually exclusive paths, no second variant in sight" — a plain if/else in `create()`, not a Strategy interface. Over-engineering a two-branch decision into an abstraction would violate this project's own KISS/YAGNI standard.
  - **`ErrorResponse.buyDate` widened to `String` rather than adding a second field.** The wire contract fixes the JSON key as `"buyDate"` for three different error codes (`DUPLICATE_SIMULATED_TRADE`, and this increment's `INVALID_BUY_DATE` and `NO_PRICE_ON_BUY_DATE`); Jackson cannot serialize two distinct Java fields to the same external property name, so a second field was not an option. Widening the existing field's type to the common denominator (`String`) that both a real date and a malformed string can be represented as was the smallest change that keeps a single source of truth for that JSON key, at the cost of one pre-existing test line's assertion updated to match (see above) — the wire format itself did not change.
  - **`create()`'s explicit-`buyDate` price lookup reuses the pre-existing `findOne(stockId, tradeDate)` mapper method** rather than adding a new one — it already returns exactly "the row for this stock on this date, or null", which is exactly what the `NO_PRICE_ON_BUY_DATE` check needs (row absent, or present with `close_price <= 0`).
  - **`defaultBuyDate` is a single new aggregate query, never joined with the per-request holdings query** — it answers a market-wide question ("what is the newest closed trading day"), independent of which stocks the caller happens to hold, so coupling it to the batched current-price query (which is scoped to the caller's own holdings' stock ids) would be wrong, not just inefficient.

- Verification (real command output tails):
  - `mvn -f develop/backend/pom.xml compile` — `BUILD SUCCESS`.
  - `mvn -f develop/backend/pom.xml test-compile` — `BUILD SUCCESS` (all 34 test source files, including `StrategyBacktestIntegrationTest`, still compile against the widened `ErrorResponse.buyDate` type — confirmed no other test file reads `ErrorResponse.getBuyDate()`; `StrategyBacktestIntegrationTest`'s own `getBuyDate()` calls are on the unrelated `BacktestDuplicateItemDto`).
  - `SPRING_DATASOURCE_URL/USERNAME/PASSWORD mvn -f develop/backend/pom.xml test -Dtest=SimulatedTradeIntegrationTest` against the live remote database, run twice (once before, once after fixing one of this increment's own new tests — see below):
    - First run: `Tests run: 29, Failures: 4` — the failure at `create_sameStockTwoDifferentBuyDates_bothSucceed_bothListedNewestFirst_bothCountTowardTotals:198` was this increment's own test bug (it compared the endpoint's *global* `totalCost`/`totalUnrealizedProfit` to the sum of just its own two new rows, which breaks if the shared live table already holds any other row); the other 3 failures (`totals_equalSumOfItems_costWeighted_notArithmeticMean`, `items_orderedByBuyDateDesc_thenStockIdAsc`, `get_noHoldings_returns200EmptyItems_totalsZero_totalReturnPercentNull`) are pre-existing, already-`[x]`-checked tests that likewise assert an exact global `items.size()`/total.
    - Root-caused via a direct, read-only `SELECT stock_id, buy_date, created_at FROM simulated_trade ORDER BY created_at` against the live database: one real, non-`ST`-prefixed row already exists — `stock_id='2330', buy_date=2026-09-18, created_at=2026-09-21 05:45:23` — created hours before this session by real traffic against the already-running `/start` server, not by any test. This is genuine pre-existing data, not something this increment's code or tests wrote.
    - Fixed only this increment's own test (rewrote its total assertions to compare *deltas* — `GET` totals captured immediately before creating the two new rows, plus each new row's own `cost`/`unrealizedProfit` — the same technique already used by the `defaultBuyDate`-independence test). Per the task's explicit instruction, did **not** touch the 3 pre-existing checked tests (their assertion style predates this increment and is unrelated to it) and did **not** delete the real `2330` row (a live, non-test row this increment must not disturb).
    - Second run: `Tests run: 29, Failures: 3, Errors: 0, Skipped: 0`, `Time elapsed: 153.907 s`. All 6 of this increment's new tests passed; the same 3 pre-existing tests failed again, with the exact same off-by-one (`expected: <2> but was: <3>`, `expected: <3> but was: <4>`, `expected: <0> but was: <1>`) — consistent with the one stray real row, and not a regression this increment introduced.
  - Did not run `mvn spring-boot:run` (a `/start`-launched backend was already bound to port 8080 for the whole session); did not run the full suite or any other broader run beyond this single test class, per the task's instructions on remote-DB round-trip cost.

- Criteria verified and how (all 6 previously-unchecked, now `[x]`):
  1. **Explicit earlier `buyDate` → 201 with that `buyDate`/`buyPrice`, unrealized profit off the latest close** — `create_withExplicitEarlierBuyDate_usesThatDateAndItsClose_unrealizedProfitUsesLatestClose` (`ST100`).
  2. **Same stock, two different `buyDate`s, both succeed, both listed newest-first, both counted** — `create_sameStockTwoDifferentBuyDates_bothSucceed_bothListedNewestFirst_bothCountTowardTotals` (`ST101`), asserting the two rows' relative order among all `ST101` items and the totals' delta.
  3. **`buyDate` = today succeeds when today's close already exists; unrealized profit negative (fees only)** — `create_buyDateIsToday_whenTodaysCloseAlreadyExists_succeeds_unrealizedProfitIsNegativeFeesOnly` (`ST102`).
  4. **`buyDate` after today, and a malformed string, both → 400 `INVALID_BUY_DATE` with the offending `buyDate` echoed; no row written** — `create_buyDateAfterToday_returns400InvalidBuyDate_writesNoRow` (`ST103`, tests both `"2099-01-01"` and `"not-a-date"`).
  5. **Explicit `buyDate` with no price row (a constructed Sunday) or `close_price = 0` → 400 `NO_PRICE_ON_BUY_DATE` with `stockId`+`buyDate`, never a nearby-day fallback, no row written** — `create_explicitBuyDateWithNoPriceRow_returns400NoPriceOnBuyDate_neverFallsBackToNearbyDate_writesNoRow` (`ST104`, tests both the missing-row and the zero-close sub-cases).
  6. **`defaultBuyDate` is market-wide, independent of any one stock's own last trade date** — `get_defaultBuyDate_isMarketWideLatestClose_independentOfAnyOneStocksOwnLastTradeDate` (`ST105` delisted 400 days before the market baseline, `ST106` only inserted — and only asserted to move `defaultBuyDate` forward — when the live baseline isn't already `today`, since this runs against a live shared database whose real max trade date is unknown in advance).

- Notes: the 3 pre-existing test failures are a live-database environmental fact (one real leftover `simulated_trade` row from prior real usage of the running server), not a regression from this increment — left un-fixed and unremediated deliberately, per the instruction not to modify checked-criteria behavior/tests and not to disturb existing rows. Nothing else left unfixed.
