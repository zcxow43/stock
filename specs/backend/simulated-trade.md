---
status: pending
title: "模擬交易持股 API"
requirement: "模擬交易分頁 — 輸入股票代號與買進日（買進日可指定，預設為全市場最後一個交易日）即建立一筆模擬持股（買進價為該檔該日收盤、固定 1 張），列表回報每筆以最新收盤價計算的未實現損益與報酬率（扣買進手續費、以現價估的賣出手續費與證交稅），可刪除單筆；建立時可指定 `priceBasis: OPEN`，改以買進日開盤價為買進價（策略分頁命中彙總表的「加入模擬」用，與回測買進價同一口徑）；列表可帶目標結算日 `settleDate`，每筆另回報「在目標結算日當天以該檔當天的開盤價賣出」的結算損益與報酬率，及其彙總（該檔當天無行情時改用目標結算日當天或之前最後一個有行情的交易日）（2026-11-01，使用者原文「代號名稱後面多一個按鈕, 加入模擬, 會用買進日作為價格」「模擬交易多一個日期可以選, 目標結算日, 所有股票會以那天以前的最高價做賣出」）；`settleDate` 不是交易日時改用距離它最近的全市場交易日（等距取較早、不晚於全市場最後一個交易日），回應的 `settleDate` 為改過後的交易日（2026-11-09，使用者原文「目標結算日 應為距離這天最近的一天交易日」）；賣出日一律為目標結算日、不再取區間最高價（2026-11-09，使用者原文「6834 should use 11/2 as target day」）；結算賣出價改為該檔在賣出日的開盤價 `open_price`，無行情判定改以 `open_price > 0` 為準（2026-11-09，使用者原文「open price」，回答「結算賣出價要用當天最高價、收盤價還是開盤價」）；建立時可改帶 `buyOnOrAfter`（與 `buyDate` 二擇一），買進日由本端點取該檔在該日（含）以後、不晚於今日、所取價格 > 0 的第一個交易日，查無時回 `400 NO_PRICE_ON_OR_AFTER`（策略分頁「加入模擬以區間後首個交易日開盤買進」用；2026-11-10，使用者原文「像是這個, 加入模擬後, 買進價為 10/19 日以後的第一個交易日開盤價」）"
depends_on: [stock-catalog, stock-price-ingestion]
---

# 模擬交易持股 API — Backend Spec

## Overview

讓使用者用最少的輸入（代號，加上選填的買進日）記下「假設我在那天的收盤價買了一張，到今天賺賠多少」。持股本身存在 `simulated_trade`（見 `specs/dba/simulated-trade.md`）；**未實現損益、報酬率與現價都在每次查詢當下即時算出、不落地**——它們隨最新收盤價每天改變。畫面是 `specs/frontend/simulated-trade.md`。

**它不抓任何外部資料、也不做型態判定**：只讀 `stock_daily_price` 已有的收盤價與 `stock` 的名稱，做四則運算。與 `specs/backend/strategy-backtest.md` 的關係是「同一套成本口徑、不同的出場假設」：回測的出場是區間內最高開盤價，本模組預設的出場是**假設今天以最新收盤價賣掉**；呼叫端帶了目標結算日時，另外回報**在目標結算日當天、以該檔當天的開盤價賣掉**的結算結果（見「目標結算日」）。

## Requirements

### 一筆持股怎麼建立

使用者送出 `stockId` 與（選填的）`buyDate` 或 `buyOnOrAfter`（兩者最多帶一個）：

| 項目 | 規則 |
|---|---|
| 買進日 | 帶 `buyDate` 時為請求的 `buyDate`；帶 `buyOnOrAfter` 時為該檔在 `stock_daily_price` 中 `buyOnOrAfter <= trade_date <= 今日` 且所取價格（依 `priceBasis`）> 0 的**最小** `trade_date`；**兩者皆省略時**為該檔 `trade_date <= 今日`（台北日曆日）且 `close_price > 0` 的**最大** `trade_date` |
| 買進價 | 依請求的 `priceBasis`：省略或 `CLOSE` 為該檔在買進日那一天的 `close_price`；`OPEN` 為那一天的 `open_price`。所取的價格必須存在且 > 0，否則拒絕 |
| 股數 | 固定 `1000`（1 張），與 `specs/backend/strategy-backtest.md` 的部位約定相同 |

- **買進日可指定、買進價與股數不可指定**：價格不是使用者自己填的數字，而是那一天實際的收盤價（或開盤價，見下）——開放輸入等於允許一筆與市場無關的成本。要換價格就換日期。
- **`priceBasis: OPEN` 給策略分頁的「加入模擬」用**（見 `specs/frontend/strategy.md`）：命中彙總表的買進價是回測的買進價，即**買進日的開盤價**（見 `specs/backend/strategy-backtest.md` 的「買進價為什麼是開盤價」）。使用者在那張表上看到 33.95 就按「加入模擬」，持股的買進價就必須是 33.95，而不是同一天另一個收盤價。呼叫端送的是「取哪一個價格欄位」而不是價格本身，買進價照樣由本端點從 `stock_daily_price` 讀出——價格不可由呼叫端輸入這條規則不變。
- **`priceBasis: OPEN` 必須帶 `buyDate` 或 `buyOnOrAfter`**：開盤價只對一個指定的日子有意義；兩者皆省略時的「最後一個有收盤價的交易日」只用於 `CLOSE`。`OPEN` 而兩者皆無回 `400 INVALID_PRICE_BASIS`。
- **`buyOnOrAfter` 給策略分頁「加入模擬以區間後首個交易日開盤買進」用**（見 `specs/frontend/strategy.md`）：呼叫端只知道「掃描區間結束的隔天」這個日曆日，不知道哪一天是交易日；「該日（含）以後第一個交易日」由本端點從 `stock_daily_price` 查出，前端不另外查行情。例：`buyOnOrAfter` 為 2026-10-19、`priceBasis` 為 `OPEN`，買進日為該檔 2026-10-19 起第一個 `open_price > 0` 的 `trade_date`，買進價為那一天的 `open_price`。
  - **「交易日」以該檔自己的行情為準**：取的是**該檔**第一個所取價格 > 0 的日子，不是全市場的交易日；該檔在全市場第一個交易日停牌時，買進日是它復牌那天。
  - **該日（含）以後到今日為止，該檔沒有任何所取價格 > 0 的日子時**（例如 `buyOnOrAfter` 為明日或更晚、或今日尚無該檔行情）回 `400 NO_PRICE_ON_OR_AFTER`，不寫入任何列。`buyOnOrAfter` 晚於今日不是格式錯誤，而是「還沒有行情」，同樣回這個錯誤。
  - **與 `buyDate` 同時帶**回 `400 INVALID_BUY_ON_OR_AFTER`：兩個欄位對買進日各說一套，本端點不替呼叫端挑一個。格式不合法同樣回這個錯誤。
- **買進日不得晚於今日**：未來還沒有收盤價，回 `400 INVALID_BUY_DATE`。今日本身可以選，但只有在今日收盤價已寫入時才成立，否則同樣是「那天沒有收盤價」。
- **帶 `buyDate` 時，那一天必須正好有該檔的收盤價**（`OPEN` 時為開盤價）：停牌、尚未上市、或該日所取的價格為 `0`（「該日沒有成交」的記錄方式，見 `specs/backend/strategy-backtest.md` 的「價格為 `0` 的日子不是行情」）都回 `400 NO_PRICE_ON_BUY_DATE`。**不得自動往前找最近的交易日**——使用者指定 2026-09-13（週日）時，靜靜地改用 09-11 的價格建立一筆，畫面上會出現一個他沒有選過的日期。回報錯誤，讓他自己改。往後找交易日只發生在呼叫端明確帶 `buyOnOrAfter` 時。
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

### 目標結算日（`settleDate`）

`GET` 可帶選填的 `settleDate`（台北日曆日）。帶了，每一筆持股另外回報一份**結算**——「假設每一筆都在目標結算日當天、以該檔當天的開盤價賣掉」（使用者原文「6834 should use 11/2 as target day」：買進日 2026-10-12、目標結算日 2026-11-02 的 6834，賣出日是 2026-11-02，不是區間內最高價出現的 2026-10-14；賣出價用開盤價，使用者原文「open price」）。

**目標結算日一律是交易日**：請求的 `settleDate` 先換成**距離它最近的全市場交易日**，以下所有規則中的「`settleDate`」都指換過之後的這一天，回應的 `settleDate` 也回報這一天（使用者原文「目標結算日 應為距離這天最近的一天交易日」）：

| 項目 | 規則 |
|---|---|
| 交易日 | `stock_daily_price` 中至少有一列 `close_price > 0` 的 `trade_date`——與 `defaultBuyDate`「上一次收盤日」同一個定義，不篩股票、不篩 `is_active` |
| 最近 | 與請求日期相差的日曆天數最小者；請求日期本身是交易日時就是它自己 |
| 等距 | 前後兩個交易日與請求日期相差的天數相同時（例如週六：前一個是週五、後一個是週一時取週五）取**較早**的那天 |
| 上限 | 不得晚於全市場最後一個交易日（`trade_date <= 今日` 的最大交易日，即 `defaultBuyDate`）；請求日期晚於它時就是它 |
| 無任何行情 | 資料庫沒有任何交易日時不換，照請求的日期原樣使用 |


| 項目 | 規則 |
|---|---|
| 賣出日 | **`settleDate` 當天**；該檔當天沒有行情（無該列，或 `open_price = 0`，例如停牌、已下市）時，改用該檔 `trade_date <= settleDate` 且 `open_price > 0` 的**最後一個**交易日 |
| 賣出價 | 該檔在賣出日的 `open_price`（「開盤價」即 `stock_daily_price.open_price`，與 `priceBasis: OPEN` 買進價同一個欄位）；**不取**最高價 `high_price` 或收盤價，也**不取**買進日至 `settleDate` 之間的任何最大值 |
| 賣出手續費／證交稅 | 以賣出價計，與「現價與未實現損益」同一套交易成本（各自無條件捨去至元） |
| 結算損益 | `賣出價 × 股數 − 賣出手續費 − 證交稅 − 成本` |
| 結算報酬率 | `結算損益 ÷ 成本 × 100`，四捨五入至小數第二位 |

- **一定有賣出價**：買進日本身必有一列價格大於 `0` 的行情（買進價就是從那一列取的；那天有成交，`open_price` 也大於 `0`——`stock_daily_price` 中沒有 `close_price > 0` 而 `open_price = 0` 的列），所以只要買進日不晚於 `settleDate`，「`settleDate` 當天或之前最後一個有行情的交易日」一定存在，且不早於買進日；`settleDate` 等於買進日時，賣出日就是買進日。
- **買進日晚於 `settleDate` 的筆沒有結算**：那一天這筆持股還沒買進，談不上賣出。它的 `settlement` 為 `null`，**不計入三個結算總計**，但照常出現在 `items`，照常計入以現價計算的三個總計。
- **該檔在 `settleDate` 當天沒有行情**（停牌、已下市、或今日尚無該檔行情）時，依「賣出日」的規則改用之前最後一個有行情的交易日，照常結算，不報錯；賣出日回報的就是那一天，所以畫面上看得出它不是目標結算日。
- **結算損益可為負**：賣出日的開盤價可能不足以付清成本與費稅，如實回報，不得夾為 `0`。
- **結算不取代現價**：帶了 `settleDate`，`currentDate`／`currentPrice`／`unrealizedProfit` 與三個現價總計照常回報；結算是另一組欄位（`items[].settlement` 與三個 `settled*` 總計）。畫面顯示哪一組由畫面決定（見 `specs/frontend/simulated-trade.md`）。
- **`settleDate` 不得晚於今日**：未來的日子不是已發生的結算點，回 `400 INVALID_SETTLE_DATE`；格式不合法同樣回這個錯誤。這個檢查作用在**請求的原始日期**上，先驗證、後換成交易日。
- **不帶 `settleDate` 時**，回應的 `settleDate` 為 `null`、每筆 `settlement` 為 `null`、三個結算總計為 `0`／`0`／`null`，其餘欄位與過去完全相同。
- **它不落地**：目標結算日是一次查詢的參數，不寫入 `simulated_trade` 或任何其他表。

結算彙總：

| 欄位 | 算法 |
|---|---|
| 結算總成本 | 有結算的各筆 `cost` 的總和 |
| 結算總損益 | 有結算的各筆 `settlement.profit` 的總和 |
| 結算總報酬率 | `結算總損益 ÷ 結算總成本 × 100`，兩位小數；**沒有任何一筆有結算時為 `null`** |

與現價彙總一樣以成本加權。

### 已下市或已無行情的持股

持股建立之後該檔可能下市。**已下市（`is_active = 0`）的股票照常回報**，不從列表中消失，也不被排除在彙總之外——它的現價就是它最後一個有成交的交易日的收盤價，現價日欄位會把那一天說出來。與 `specs/backend/strategy-backtest.md`「不篩 `is_active`」一致。

## Implementation Details

### API 契約

```
GET /api/simulated-trades
GET /api/simulated-trades?settleDate=2026-09-19
```

| Query | 型別 | 必填 | 說明 |
|---|---|---|---|
| `settleDate` | date | 否 | 目標結算日（台北日曆日），不得晚於今日；不是交易日時由本端點換成最近的交易日；見「目標結算日」 |

Response `200`（下例帶了 `settleDate=2026-09-19`）：

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
  "settleDate": "2026-09-19",
  "settledTotalCost": 1151638,
  "settledTotalProfit": 72920,
  "settledTotalReturnPercent": 6.33,
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
      "returnPercent": 4.17,
      "settlement": {
        "sellDate": "2026-09-19",
        "sellPrice": 1230.00,
        "sellFee": 1752,
        "sellTax": 3690,
        "profit": 72920,
        "returnPercent": 6.33
      }
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
| `settleDate` | date \| null | 實際採用的目標結算日：請求的日期換成最近的交易日之後的那一天（請求的日期本身是交易日時與它相同）；未帶時為 `null` |
| `settledTotalCost` | number | 有結算（`settlement` 不為 `null`）的各筆 `cost` 總和；沒有任何一筆有結算時為 `0` |
| `settledTotalProfit` | number | 有結算的各筆 `settlement.profit` 總和；沒有任何一筆有結算時為 `0` |
| `settledTotalReturnPercent` | number \| null | 成本加權；**沒有任何一筆有結算時為 `null`** |
| `items[].settlement` | object \| null | 該筆的結算；未帶 `settleDate`、或該筆買進日晚於 `settleDate` 時為 `null` |
| `items[].settlement.sellDate` / `sellPrice` | date / number | 賣出日（`settleDate`；該檔當天無行情時為之前最後一個有行情的交易日）與該日的 `open_price` |
| `items[].settlement.sellFee` / `sellTax` | int | 以 `sellPrice` 計的賣出手續費與證交稅（元） |
| `items[].settlement.profit` | int | 結算損益（元），**可為負值** |
| `items[].settlement.returnPercent` | number | 兩位小數，**可為負值** |

`items` 依 `buyDate` **由新到舊**排序，同日依 `stockId` 升冪——最近加入的在最上面，與使用者的操作順序一致。排序由本端點決定，畫面不再重排。

**沒有任何持股時回 `200`**，`items` 為空陣列、`totalCost` 與 `totalUnrealizedProfit` 為 `0`、`totalReturnPercent` 為 `null`。空清單不是錯誤。

```
POST /api/simulated-trades
```

Request：

```json
{ "stockId": "2330", "buyDate": "2026-09-18", "priceBasis": "OPEN" }
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockId` | string | 是 | 股票代號；前後空白去除後比對，須存在於 `stock`（不篩 `is_active`） |
| `buyDate` | date | 否 | 買進日（台北日曆日）；不得晚於今日；與 `buyOnOrAfter` 皆省略時為該檔最後一個有收盤價的交易日（僅限 `CLOSE`） |
| `buyOnOrAfter` | date | 否 | 與 `buyDate` 二擇一；買進日為該檔在此日（含）以後、不晚於今日、所取價格 > 0 的第一個交易日 |
| `priceBasis` | string | 否 | `CLOSE`（省略時的預設）或 `OPEN`：買進價取買進日的收盤價或開盤價；`OPEN` 必須同時帶 `buyDate` 或 `buyOnOrAfter` |

例（策略分頁勾選「加入模擬以區間後首個交易日開盤買進」時）：

```json
{ "stockId": "2330", "buyOnOrAfter": "2026-10-19", "priceBasis": "OPEN" }
```

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
| 該檔在指定的 `buyDate` 沒有所取的價格（無該列，或 `CLOSE` 時 `close_price = 0`、`OPEN` 時 `open_price = 0`） | `400` | `{"code":"NO_PRICE_ON_BUY_DATE","stockId":"2330","buyDate":"2026-09-13"}` |
| `buyOnOrAfter` 格式不合法，或與 `buyDate` 同時帶 | `400` | `{"code":"INVALID_BUY_ON_OR_AFTER","buyOnOrAfter":"abc"}` |
| 該檔在 `buyOnOrAfter`（含）以後到今日為止沒有所取價格 > 0 的交易日（含 `buyOnOrAfter` 晚於今日） | `400` | `{"code":"NO_PRICE_ON_OR_AFTER","stockId":"2330","buyOnOrAfter":"2026-11-11"}` |
| `priceBasis` 不是 `CLOSE`／`OPEN`，或為 `OPEN` 而 `buyDate` 與 `buyOnOrAfter` 皆未帶 | `400` | `{"code":"INVALID_PRICE_BASIS"}` |
| `GET` 的 `settleDate` 格式不合法或晚於今日 | `400` | `{"code":"INVALID_SETTLE_DATE","settleDate":"2099-01-01"}` |
| `(stockId, buyDate)` 已存在 | `409` | `{"code":"DUPLICATE_SIMULATED_TRADE","stockId":"2330","buyDate":"2026-09-18"}` |
| `DELETE` 的 `id` 不存在 | `404` | `{"code":"SIMULATED_TRADE_NOT_FOUND","id":99}` |

`UNKNOWN_STOCK_ID` 沿用 `specs/backend/strategy-scan.md` 已定的同名錯誤碼與回應形狀（同一個畫面上的兩支端點對同一種錯誤用兩套代碼，前端就得寫兩份處理）。**重複回 `409` 而不是 `400`**：請求本身完全合法，只是這筆持股已經在那裡了——前端要據此顯示「這一檔今天已經加過了」，與「你打錯代號」是兩種不同的回饋。

### 處理流程

`GET`：

1. 取今日（台北日曆日）為 `asOfDate`，並查出全市場上一次收盤日為 `defaultBuyDate`（一次查詢；無行情時為 `null`）。
2. 讀出 `simulated_trade` 全部列（列數是使用者手動加入的量級）。
3. **以整份代號清單為條件批次查詢**各檔的現價（`trade_date <= asOfDate` 且 `close_price > 0` 的最大 `trade_date` 與其收盤價）與名稱——不得逐筆各發一次查詢，與 `specs/backend/strategy-backtest.md`「有界次數的查詢」同一條規定。清單為空時直接回空結果，不把空集合交給資料庫。
4. 帶了 `settleDate` 時（先驗證，不合法回 `400 INVALID_SETTLE_DATE`），先以**一次查詢**取得全市場 `close_price > 0` 中 `trade_date <= 請求日期` 的最大交易日與 `trade_date >= 請求日期` 且 `trade_date <= asOfDate` 的最小交易日，依「目標結算日」的規則選出實際的 `settleDate`；再**以一次批次查詢**取得每一筆持股的結算賣出日與賣出價：以 `simulated_trade` 與 `stock_daily_price` 依 `stock_id` 相接，條件 `trade_date BETWEEN 該筆 buy_date AND settleDate` 且 `open_price > 0`，每筆取**最大的 `trade_date`**（`settleDate` 當天有行情時就是 `settleDate`）與該列的 `open_price`。查詢次數與持股筆數無關；買進日晚於 `settleDate` 的筆在這次查詢中沒有列，即 `settlement` 為 `null`。
5. 逐筆算出四項費用、成本、未實現損益與報酬率（以及有結算時的結算各欄），彙總後依 `buyDate` 由新到舊組出 `items`。

`POST`：

1. 驗證 `stockId`（非空、存在於 `stock`）、`buyOnOrAfter`（有值時須為合法日期且未同時帶 `buyDate`，否則 `400 INVALID_BUY_ON_OR_AFTER`）、`priceBasis`（省略視為 `CLOSE`；不是 `CLOSE`／`OPEN`，或 `OPEN` 而 `buyDate` 與 `buyOnOrAfter` 皆省略 → `400 INVALID_PRICE_BASIS`）與 `buyDate`（有值時須為合法日期且不晚於今日，否則 `400 INVALID_BUY_DATE`）。
2. 取買進日與買進價：
   - `buyDate` 有值 → 查該檔該日的 `close_price`（`OPEN` 時為 `open_price`）；無該列或為 `0` → `400 NO_PRICE_ON_BUY_DATE`。
   - `buyOnOrAfter` 有值 → 以一次查詢取該檔 `trade_date >= buyOnOrAfter` 且 `trade_date <= 今日` 且 `close_price > 0`（`OPEN` 時為 `open_price > 0`）的最小 `trade_date` 與該列的所取價格；查無 → `400 NO_PRICE_ON_OR_AFTER`。
   - 兩者皆省略 → 查該檔 `trade_date <= 今日` 且 `close_price > 0` 的最大 `trade_date` 與其收盤價；查無 → `400 NO_PRICE_BEFORE_TODAY`。
3. `INSERT` 一列（`shares = 1000`，`buy_date` 為第 2 步取得的買進日）；唯一鍵衝突 → `409 DUPLICATE_SIMULATED_TRADE`（不得改成 UPSERT；`buyDate` 欄位帶第 2 步取得的買進日）。
4. 以剛寫入的列即時算出與 `GET` 相同形狀的單筆回應。

`DELETE`：以 `id` 刪一列，影響列數為 `0` → `404`。

**三支端點都不抓取任何外部資料、不建立 `stock_sync_progress` 列、不與任何併發鎖互斥。**

## Acceptance Criteria

### 建立持股
- [x] `POST /api/simulated-trades` 帶 `{"stockId":"2330"}` 回 `201`，`buyDate` 等於該檔最後一個 `close_price > 0` 的交易日，`buyPrice` 等於該日收盤價，`shares` 為 `1000`
- [x] 省略 `buyDate` 時 `close_price` 為 `0` 的交易日被跳過，取再前一個 `close_price > 0` 的交易日
- [x] 建立後修改 `stock_daily_price` 中該買進日的收盤價，再查 `GET`：該筆的 `buyPrice` 與 `cost` 不變
- [x] 回應為與 `GET` 的 `items[]` 相同的形狀，含 `currentDate`／`currentPrice`／`unrealizedProfit`／`returnPercent`

### 指定買進日
- [x] 帶 `{"stockId":"2330","buyDate":"<某個更早的交易日>"}` 回 `201`，`buyDate` 等於送出的日期、`buyPrice` 等於該檔該日的 `close_price`；未實現損益仍以最新收盤價計算
- [x] 同一檔以兩個不同的 `buyDate` 各加一筆皆成功，`GET` 依買進日由新到舊列出兩筆，兩筆各自計入彙總
- [x] 今日已有該檔收盤價時，帶今日為 `buyDate` 可成立（`buyPrice` 為今日收盤、未實現損益為負的費用）
- [x] `buyDate` 晚於今日（例如 `2099-01-01`）→ `400 INVALID_BUY_DATE`，且未寫入任何列；格式不合法的字串同樣回 `400 INVALID_BUY_DATE`
- [x] 指定的 `buyDate` 該檔無該列（例如週日）或 `close_price = 0` → `400 NO_PRICE_ON_BUY_DATE`（帶 `stockId` 與 `buyDate`），**不得**自動改用鄰近交易日建立，且未寫入任何列
- [x] `GET` 回的 `defaultBuyDate` 等於全市場 `trade_date <= 今日` 且 `close_price > 0` 的最大交易日；它與個別股票的最後交易日無關（構造一檔更早停止交易的股票驗證）

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

### 以開盤價建立（`priceBasis`）
- [x] `POST` 帶 `{"stockId":"2305","buyDate":"2026-09-08","priceBasis":"OPEN"}` 回 `201`，`buyPrice` 等於 `stock_daily_price` 中 2305 在 2026-09-08 的 `open_price`，且等於 `POST /api/strategies/backtest` 對同一 `(stockId, buyDate)` 回報的 `buyPrice`
- [x] 省略 `priceBasis` 與帶 `"CLOSE"` 時行為與過去相同（`buyPrice` 為該日 `close_price`）
- [x] `priceBasis` 為 `"OPEN"` 而未帶 `buyDate`、或為其他字串（例如 `"HIGH"`）→ `400 INVALID_PRICE_BASIS`，且未寫入任何列
- [x] `OPEN` 時該日無該列（例如週日）→ `400 NO_PRICE_ON_BUY_DATE`，不改用收盤價、不改用鄰近交易日，且未寫入任何列
- [x] 同一 `(stockId, buyDate)` 已加入後再以另一個 `priceBasis` 加入 → `409 DUPLICATE_SIMULATED_TRADE`

### 目標結算日（`settleDate`）
- [x] 不帶 `settleDate` 時回應的 `settleDate` 為 `null`、每筆 `settlement` 為 `null`、`settledTotalCost`／`settledTotalProfit` 為 `0`、`settledTotalReturnPercent` 為 `null`，其餘欄位與過去相同
- [x] 帶 `settleDate`：每筆 `settlement.sellPrice` 等於以 SQL 直接查出的 `MAX(high_price)`（`trade_date` 介於該筆 `buy_date` 與 `settleDate`、含兩端，`high_price > 0`），`sellDate` 為該最大值最早出現的交易日
- [x] 費稅與損益沿用同一套交易成本：買進價 `1150.00`、結算賣出價 `1230.00` 時 `sellFee` `1752`、`sellTax` `3690`、`profit` `72920`、`returnPercent` `6.33`（成本 `1151638`）；以實際一筆持股的回應數字依此公式手算驗證
- [x] 窗口含買進日：`settleDate` 等於 `buyDate` 時 `settlement.sellDate` 等於 `buyDate`、`sellPrice` 等於該日 `high_price`
- [x] 買進日晚於 `settleDate` 的筆 `settlement` 為 `null`，照常出現在 `items` 且照常計入 `totalCost`／`totalUnrealizedProfit`，但不計入三個 `settled*` 總計；全部筆都晚於 `settleDate` 時 `settledTotalReturnPercent` 為 `null`
- [x] `settledTotalCost`／`settledTotalProfit` 等於有結算各筆的 `cost`／`settlement.profit` 總和，`settledTotalReturnPercent` 等於兩者之比 × 100（兩位小數）
- [x] `settleDate` 晚於今日（`2099-01-01`）或格式不合法 → `400 INVALID_SETTLE_DATE`（帶 `settleDate`）
- [x] 結算以一次批次查詢讀取，查詢次數與持股筆數無關
- [x] 帶 `settleDate` 的 `GET` 不寫入任何表（`simulated_trade` 的列數與內容不變）

### 目標結算日換成最近的交易日
- [x] 請求的 `settleDate` 本身是交易日（以 SQL `SELECT COUNT(*) FROM stock_daily_price WHERE trade_date = ? AND close_price > 0` 大於 `0` 確認）時，回應的 `settleDate` 等於它
- [x] 請求的 `settleDate` 為非交易日、且離後一個交易日較近（例如週日、其後的週一是交易日）時，回應的 `settleDate` 為那個較近的交易日，以 SQL 查出的前後交易日核對
- [x] 請求的 `settleDate` 與前後交易日等距（例如週六、前一天週五與後兩天週一皆為交易日）時，回應的 `settleDate` 為較早的那天（週五）
- [ ] 請求的 `settleDate` 晚於全市場最後一個交易日（例如今日尚無行情）時，回應的 `settleDate` 等於回應的 `defaultBuyDate`（未驗證：今日 2026-11-09 已有行情，請求不得晚於今日，且不得修改 `stock_daily_price` 構造無今日行情的情境；程式路徑為「後一個交易日不存在 → 取前一個」）
- [x] 結算以換過之後的日期計算：回應中每筆 `settlement.sellPrice` 等於以 SQL 查出的 `MAX(high_price)`（`trade_date` 介於該筆 `buy_date` 與**回應的** `settleDate`、含兩端）；買進日晚於回應 `settleDate` 的筆 `settlement` 為 `null`
- [x] 換成交易日不改變驗證：`settleDate=2099-01-01` 與 `abc` 仍回 `400 INVALID_SETTLE_DATE`，回應的 `settleDate` 為請求的原始值

### 在目標結算日當天賣出
- [x] `settleDate=2026-11-02`：6834（買進日 2026-10-12）的 `settlement.sellDate` 為 `2026-11-02`，`settlement.sellPrice` 等於 `SELECT high_price FROM stock_daily_price WHERE stock_id='6834' AND trade_date='2026-11-02'`（不是區間最高價出現的 2026-10-14 的 `168.00`）
- [x] 帶 `settleDate` 時，每一筆買進日不晚於回應 `settleDate`、且該檔在回應 `settleDate` 當天有 `high_price > 0` 行情的持股，`settlement.sellDate` 等於回應的 `settleDate`，`settlement.sellPrice` 等於該檔當天的 `high_price`（以 SQL 逐筆核對）
- [x] 該檔在回應 `settleDate` 當天沒有行情（無該列或 `high_price = 0`）時，`settlement.sellDate` 等於以 SQL 查出的 `MAX(trade_date)`（`stock_id` 為該檔、`trade_date <= settleDate`、`high_price > 0`），`sellPrice` 等於那一天的 `high_price`；以資料庫中既有的停牌日驗證，不新增或修改 `stock_daily_price` 的列
- [x] `settleDate` 等於某筆的買進日時，該筆 `settlement.sellDate` 等於 `buyDate`、`sellPrice` 等於該日 `high_price`
- [x] 買進日晚於回應 `settleDate` 的筆 `settlement` 仍為 `null`，不計入三個 `settled*` 總計；`settledTotalCost`／`settledTotalProfit`／`settledTotalReturnPercent` 等於有結算各筆的 `cost`／`settlement.profit` 總和與兩者之比

---
### 以開盤價結算
- [x] `settleDate=2026-11-02`：6834（買進日 2026-10-12）的 `settlement.sellDate` 為 `2026-11-02`，`settlement.sellPrice` 等於 `SELECT open_price FROM stock_daily_price WHERE stock_id='6834' AND trade_date='2026-11-02'`（不是同日的 `high_price` `116.00`）
- [x] 帶 `settleDate` 時，每一筆有結算的持股 `settlement.sellDate` 等於以 SQL 查出的 `MAX(trade_date)`（`stock_id` 為該檔、`trade_date` 介於該筆 `buy_date` 與回應 `settleDate`、含兩端、`open_price > 0`），`settlement.sellPrice` 等於那一天的 `open_price`（以 SQL 逐筆核對，至少三個不同的 `settleDate`）
- [x] `settlement.sellFee`／`sellTax`／`profit`／`returnPercent` 以 `open_price` 計：`profit` 等於 `sellPrice × 1000 − sellFee − sellTax − cost`，`sellFee`／`sellTax` 為 `sellPrice × 1000` 的 `0.1425%`／`0.3%` 無條件捨去至元
- [x] 該檔在回應 `settleDate` 當天沒有行情（無該列或 `open_price = 0`）時，`settlement.sellDate` 為該檔 `trade_date <= settleDate` 且 `open_price > 0` 的最後一個交易日，`sellPrice` 為那一天的 `open_price`；以資料庫中既有資料驗證，不新增或修改 `stock_daily_price` 的列
- [x] 買進日晚於回應 `settleDate` 的筆 `settlement` 仍為 `null`，不計入三個 `settled*` 總計；`settledTotalCost`／`settledTotalProfit`／`settledTotalReturnPercent` 等於有結算各筆的 `cost`／`settlement.profit` 總和與兩者之比 × 100（兩位小數）
- [x] 結算的查詢仍為一次批次查詢（與持股筆數無關），帶 `settleDate` 的 `GET` 不寫入任何表

### 以某日（含）以後的第一個交易日建立（`buyOnOrAfter`）
- [ ] `POST` 帶 `{"stockId":"2330","buyOnOrAfter":"<某個週六>","priceBasis":"OPEN"}` 回 `201`，`buyDate` 等於以 SQL `SELECT MIN(trade_date) FROM stock_daily_price WHERE stock_id='2330' AND trade_date >= '<該週六>' AND trade_date <= CURDATE() AND open_price > 0` 查出的日子（其後的週一或更晚），`buyPrice` 等於那一天的 `open_price`；`simulated_trade` 新增的列 `buy_date`／`buy_price` 與回應相同
- [ ] `buyOnOrAfter` 本身是該檔有 `open_price > 0` 的交易日時，回應的 `buyDate` 等於 `buyOnOrAfter`
- [ ] 帶 `priceBasis: "CLOSE"`（或省略）與 `buyOnOrAfter` 時，`buyDate` 為該日（含）以後第一個 `close_price > 0` 的交易日，`buyPrice` 為那一天的 `close_price`
- [ ] 該檔在 `buyOnOrAfter` 起第一個全市場交易日停牌（無該列或 `open_price = 0`）時，`buyDate` 為該檔其後第一個 `open_price > 0` 的日子；以資料庫中既有的停牌日驗證，不新增或修改 `stock_daily_price` 的列（資料中找不到停牌日時，在 Execution Result 記為未驗證）
- [ ] `buyOnOrAfter` 為明日（例如 `2099-01-01` 或今日 + 1 天）→ `400 NO_PRICE_ON_OR_AFTER`（帶 `stockId` 與 `buyOnOrAfter`），且未寫入任何列
- [ ] `buyOnOrAfter` 為 `"abc"`，或與 `buyDate` 同時帶 → `400 INVALID_BUY_ON_OR_AFTER`，且未寫入任何列
- [ ] `priceBasis: "OPEN"` 帶 `buyOnOrAfter`、不帶 `buyDate` 時不回 `INVALID_PRICE_BASIS`；`OPEN` 而兩者皆未帶仍回 `400 INVALID_PRICE_BASIS`
- [ ] 同一檔以兩個不同、但解析到同一個交易日的 `buyOnOrAfter`（例如同一個週末的週六與週日）各送一次：第一次 `201`，第二次 `409 DUPLICATE_SIMULATED_TRADE`，回應的 `buyDate` 為解析後的交易日；資料庫只有一列
- [ ] 不帶 `buyOnOrAfter` 時 `POST` 的行為與過去相同：「建立持股」「指定買進日」「以開盤價建立」三組既有條件以同樣的請求重跑，結果不變

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

### Increment 2 — 2026-11-01

Implements `priceBasis` on POST and `settleDate` on GET. Verified live with curl against :8081 (local DB 127.0.0.1:3306/stock); no tests written or run.

- Files changed (all under `develop/backend/src/main/`):
  - `java/com/stock/service/SimulatedTradeService.java` — `list(String settleDate)`, `parsePriceBasisIsOpen`, `parseSettleDate`, `toSettlement`; `create()` picks `open_price` or `close_price` from the existing `findOne` row.
  - `java/com/stock/controller/SimulatedTradeController.java` — optional `settleDate` request param.
  - `java/com/stock/mapper/SimulatedTradeMapper.java` + `resources/mapper/SimulatedTradeMapper.xml` — `findSettlementPrices(settleDate)`: one query joining `simulated_trade` to `stock_daily_price` (`trade_date BETWEEN buy_date AND settleDate`, `high_price > 0`), `ROW_NUMBER() OVER (PARTITION BY st.id ORDER BY high_price DESC, trade_date ASC)` keeps the max and its earliest date.
  - `java/com/stock/domain/SimulatedTradeSettlementPrice.java` (new) — row type of that query.
  - `java/com/stock/dto/CreateSimulatedTradeRequest.java` (`priceBasis` as raw String), `SimulatedTradeItemDto.java` (nested `Settlement`), `SimulatedTradeResponseDto.java` (`settleDate`, `settled*`).
  - `java/com/stock/dto/ErrorResponse.java` — `settleDate` field, `invalidPriceBasis()`, `invalidSettleDate(String)`.
  - `java/com/stock/exception/InvalidPriceBasisException.java`, `InvalidSettleDateException.java` (new) + two handlers in `GlobalExceptionHandler.java` (both 400).
- Design decisions: `TradingCostCalculator` reused unchanged for settlement fee/tax/profit/return. `settleDate` is parsed as a raw String so a malformed value is echoed in `INVALID_SETTLE_DATE`. The settlement query runs only when `settleDate` is present, once, independent of holding count (one mapper call, no per-row query).
- Evidence (rows created: ids 416 (2305 2026-09-08 OPEN), 417 (2330 2026-09-08 CLOSE), 418 (2305 2026-10-20 default basis); all deleted via DELETE -> 204, `SELECT COUNT(*) FROM simulated_trade` = 0 afterwards):
  - POST 2305 / 2026-09-08 / OPEN -> 201 `buyPrice 33.95`; SQL `open_price` = 33.95; `POST /api/strategies/backtest` same item -> `buyPrice 33.95`.
  - Omitted priceBasis and `CLOSE` -> 201, `buyPrice` = `close_price` (2330 2026-09-08: 2470.00 = SQL).
  - OPEN without buyDate, and `HIGH` -> `400 {"code":"INVALID_PRICE_BASIS"}`; OPEN on 2026-09-13 -> `400 NO_PRICE_ON_BUY_DATE` (stockId, buyDate); same (2305, 2026-09-08) with `CLOSE` after OPEN -> `409 DUPLICATE_SIMULATED_TRADE`. No rows written by the failures (only 416-418 existed).
  - GET without settleDate -> `settleDate null`, `settledTotalCost 0`, `settledTotalProfit 0`, `settledTotalReturnPercent null`, `items[].settlement null`.
  - GET settleDate=2026-10-30: 2305 (from 09-08) sellPrice 74.70 on 2026-10-13, SQL MAX(high_price)=74.70, earliest date 2026-10-13; 2330 sellPrice 2590.00 on 2026-10-06, SQL max 2590, `MIN(trade_date)` at that value = 2026-10-06; 2305 (from 10-20) sellPrice 57.50, SQL max 57.50.
  - Hand arithmetic, 2330: 2590*1000*0.001425 = 3690.75 -> sellFee 3690; *0.003 = 7770; profit 2590000-3690-7770-2473519 = 105021; 105021/2473519 = 4.25%. Matches response.
  - settleDate = buyDate (2026-09-08): `sellDate` 2026-09-08, `sellPrice` 36.2 (2305) and 2505.0 (2330) = SQL `high_price` of that day.
  - settleDate=2026-10-10: the 10-20 holding has `settlement null` yet stays in `items` and in `totalCost 2562294`; `settledTotalCost 2507517` = 33998+2473519, `settledTotalProfit 143203` = 38182+105021, `settledTotalReturnPercent 5.71` = 143203/2507517*100.
  - settleDate=2026-09-07 (all later): all `settlement null`, `settledTotalReturnPercent null`.
  - settleDate=2099-01-01 and `abc` -> `400 {"code":"INVALID_SETTLE_DATE","settleDate":...}`.
  - GET writes nothing: `simulated_trade` rows unchanged between GETs.
- code-quality review: no Critical/Important findings. Noted: the pre-existing `ErrorResponse` telescoping constructors grew one more delegate (kept to match the file's convention). A blank `settleDate=` returns `INVALID_SETTLE_DATE`.

### Increment 3 — 2026-11-09

Settle date snapped to the nearest trading day. Verified live with curl on :8081 (local DB); no tests written or run.

- Files changed (under `develop/backend/src/main/`):
  - `java/com/stock/domain/TradingDayNeighbors.java` (new) — `previous` / `next` trading days.
  - `java/com/stock/mapper/StockDailyPriceMapper.java` + `resources/mapper/StockDailyPriceMapper.xml` — `findTradingDayNeighbors(date, asOfDate)`: one SELECT with two scalar subqueries (`MAX(trade_date) <= date`, `MIN(trade_date) >= date AND <= asOfDate`, both `close_price > 0`).
  - `java/com/stock/service/SimulatedTradeService.java` — `snapToTradingDay` runs after `parseSettleDate` (so validation still sees the raw value): only prev -> prev; only next -> next; neither -> requested; both -> fewer calendar days, tie -> prev (earlier).
- Evidence (rows created: ids 443 (2330, 2026-10-20), 444 (2330, 2026-11-06); both DELETE -> 204, count 0 afterwards; backend on :8081 stopped). Trading days from SQL: 10-23, 10-27 (10-26 has none), 11-06, 11-09; COUNT(close_price>0) on 10-22 = 1082, 10-24/10-25/10-26 = none.
  - settleDate=2026-10-22 (trading day) -> `settleDate` 2026-10-22; 2026-11-09 -> 2026-11-09.
  - 2026-10-24 (Sat; prev 10-23 is 1 day, next 10-27 is 3) -> 2026-10-23. 2026-11-07 (prev 11-06 1 day, next 11-09 2) -> 2026-11-06.
  - 2026-10-26 (prev 3, next 1) -> 2026-10-27. 2026-11-08 (Sun; prev 2, next 1) -> 2026-11-09.
  - 2026-10-25 (prev 10-23 2 days, next 10-27 2 days, tie) -> 2026-10-23 (earlier).
  - Settlement uses the snapped date: for 10 holdings, each `settlement.sellPrice`/`sellDate` equals SQL `MAX(high_price)` / earliest date between `buy_date` and the response `settleDate` for all three snapped dates 10-27, 11-06, 11-09. The difference shows on 2466: window end 11-06 gives 402.0 (11-03); window end 10-27 gives 287.0 (10-27). Holding 444 (buy 11-06): `settlement` null at settleDate 10-26 -> 10-27, 2195.0 at 11-07 -> 11-06 (= SQL), 2290.0 at 11-08 -> 11-09 (= SQL). `settledTotalCost` 3892836 -> 6030878 when 444 gets a settlement.
  - 2099-01-01 -> `400 {"code":"INVALID_SETTLE_DATE","settleDate":"2099-01-01"}`; `abc` -> same code with `"abc"`.
- Not verified: "settleDate later than the last trading day -> equals defaultBuyDate". Today (2026-11-09) already has quotes and requests after today are rejected; `stock_daily_price` must not be modified.

### Increment 4 — 2026-11-09
- Files changed:
  - `develop/backend/src/main/resources/mapper/SimulatedTradeMapper.xml` — `findSettlementPrices` ROW_NUMBER ordering changed from `high_price DESC, trade_date ASC` to `trade_date DESC` (window still `buy_date..settleDate`, `high_price > 0`, still one batch query).
  - `develop/backend/src/main/java/com/stock/mapper/SimulatedTradeMapper.java`, `develop/backend/src/main/java/com/stock/domain/SimulatedTradeSettlementPrice.java` — comments updated. Service unchanged (it already uses the row's `trade_date` and `high_price`).
- Live evidence (backend on :8081, local MySQL; every holding cross-checked by SQL `latest trade_date in [buy_date, response settleDate] with high_price>0`, 0 mismatches in every run; no table rows written):
  - `settleDate=2026-11-02`: 6834 (buy 2026-10-12) `sellDate=2026-11-02`, `sellPrice=116.0` = SQL 116.00 (not 168.00). All 8 holdings sold on 2026-11-02 and match SQL (e.g. 2466 389.0, 2492 375.5, 6672 531.0). Totals: cost 1484409, profit 328340, return 22.12; the cost and profit sums over the items equal the totals.
  - Fallback: `settleDate=2026-10-23` (response `settleDate` stays 2026-10-23, since that date has 1 stray row with close>0 so the snap rule treats it as a trading day; 6834 has no row that day). All 8 holdings fall back to 2026-10-22: 6834 149.5, 2466 270.0, 6672 677.0, matching SQL. Total profit 497736.
  - `settleDate=2026-10-26` snaps to response `settleDate=2026-10-27`; all match SQL. `2026-10-12` (6834 buy date): sellDate = buyDate, matches SQL.
  - Later buys: `settleDate=2026-10-06` → 6834, 4934, 6668 `settlement=null`, totals cover only 5 holdings (cost 1286629, profit 40578); `2026-10-07` → 6834, 4934 null (cost 1338602, profit 144811). `2026-11-09` all settled, profit 57843.
- Not verified: no halt day exists in the data for a holding on a date with full-market quotes (the query for holdings x market days with no quote returned 0 rows); the fallback was verified through 2026-10-23, where only 1 stock quoted. Criteria covered: all five under 在目標結算日當天賣出 (fallback via 10-23 as above). Backend stopped after verification.

### Increment 5 — 2026-11-09
- Files changed: `develop/backend/src/main/resources/mapper/SimulatedTradeMapper.xml` (`findSettlementPrices` selects/filters `open_price`, `open_price > 0`), `develop/backend/src/main/java/com/stock/domain/SimulatedTradeSettlementPrice.java` (`highPrice` -> `openPrice`), `develop/backend/src/main/java/com/stock/mapper/SimulatedTradeMapper.java` (javadoc), `develop/backend/src/main/java/com/stock/service/SimulatedTradeService.java` (`getOpenPrice()`), `develop/backend/src/main/java/com/stock/dto/SimulatedTradeItemDto.java` (comment).
- Evidence (backend on :8081, curl vs mysql CLI, 8 existing holdings, no writes):
  - settleDate=2026-11-02: 6834 `sellDate` 2026-11-02, `sellPrice` 111.50 (SQL open_price 111.50; previously 116.00), fee 158, tax 334, profit -19177. All 8 holdings' sellDate/sellPrice equal SQL (2466 354.00, 6278 246.00, 6672 500.00, 6225 83.50, 6668 49.65, 2492 365.00, 4934 13.80); settledTotalProfit 231422, settledTotalReturnPercent 15.59.
  - settleDate=2026-10-23 (fallback): all 8 holdings sellDate 2026-10-22, sellPrice equals SQL open_price (2466 261.00, 6278 316.50, 6672 665.00, 6225 81.50, 6668 58.60, 6834 141.00, 2492 415.00, 4934 15.25); settledTotalProfit 460802, 31.04%.
  - settleDate=2026-10-06 (null case): 6834, 6668, 4934 `settlement: null` (bought after); other 5 sell 2026-10-06 at open (160.00, 230.00, 456.00, 68.80, 370.00); per-holding profits sum -7512 = settledTotalProfit -7512, settledTotalCost 1286629, -0.58%.
  - `simulated_trade` before/after GETs: 8 rows, sum(id) 3459, sum(shares) 8000, sum(buy_price) 1482.30 (unchanged).
  - Still one batched query (ROW_NUMBER over join), independent of holding count.
- Fee/tax arithmetic checked: 6834 at 111.50 -> proceeds 111,500; fee floor(158.8875) = 158, tax floor(334.5) = 334, cost 130,185 -> profit -19,177, equal to the response (spec rounds fee/tax down to the yuan).
- Not verified: the deferred "settleDate later than last trading day" item (unchanged by this increment). Backend stopped.
