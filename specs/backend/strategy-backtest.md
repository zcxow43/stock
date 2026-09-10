---
status: done
title: "策略命中回測 API"
requirement: "策略掃描結果可一鍵回測：訊號日收盤買進，訊號日之後至今日之間以最高開盤價賣出，逐檔回報賣出日／報酬率／收益，並彙總總報酬率與總收益。部位固定每檔 1 張（1,000 股），總報酬率為總收益 ÷ 總成本"
depends_on: [strategy-scan, stock-price-ingestion]
---

# 策略命中回測 API — Backend Spec

## Overview

把一份掃描命中清單（每檔一個訊號日）換算成「如果當時買了，到今天為止最好能賣在哪裡」的結果。輸入是 `specs/backend/strategy-scan.md` 掃出來的標的與訊號日，輸出是逐檔的賣出日、報酬率、收益，加上整份清單的總報酬率與總收益。

**它不做任何型態判定，也不對外抓任何資料。** 判定是掃描的事，行情是 `specs/backend/stock-price-ingestion.md` 的事；本模組只做一件事——讀 `stock_daily_price` 已有的價格，做四則運算。這是它能是一支獨立、無狀態、可重複呼叫的端點的原因，也是它不需要任何新資料表的原因：回測結果**不落地**，每次呼叫重算。

**與掃描分成兩支端點，不是合併成一個回應。** 使用者按「開始掃描」要的是「有哪些標的」，那份結果本身就有用；回測是他看完命中清單之後才決定要不要做的第二個動作（見 `specs/frontend/strategy.md` 的「回測按鈕」）。把回測塞進掃描回應等於每次掃描都強制多做一輪價格查詢與運算，而多數掃描的下一步並不是回測。

## Requirements

### 回測規則

一檔標的的一次回測，由「訊號日」與「今日」兩個日期決定，全程只讀該檔的 `stock_daily_price`：

| 項目 | 規則 |
|---|---|
| 買進日 | 訊號日 |
| 買進價 | 訊號日的**收盤價** |
| 賣出窗口 | 訊號日的**次一交易日**起，至今日為止（含今日） |
| 賣出價 | 賣出窗口內**最高的開盤價** |
| 賣出日 | 該最高開盤價**第一次出現**的交易日 |
| 部位 | 每檔固定 **1 張＝ 1,000 股** |
| 報酬率 | `(賣出價 − 買進價) ÷ 買進價 × 100`，四捨五入至小數第二位 |
| 收益 | `(賣出價 − 買進價) × 1000`，四捨五入至元 |

#### 買進價為什麼是收盤價

五個型態的訊號**全部是用收盤價判定的**（箱型突破看突破收盤、上漲支撐看上漲收盤與其後兩日收盤、底底高看 MA5、反彈與累積上漲看谷底與高點收盤——見 `specs/backend/strategy-scan.md`）。也就是說，訊號要到當天收盤才成立。以訊號日的**開盤價**買進，等於用了當下不可能知道的資訊（look-ahead bias），會系統性地把回測結果美化，而美化的幅度剛好就是當天從開盤漲到收盤的那一段——對「上漲支撐」這種以單日大漲為訊號的型態，那一段正是最大的一段。

#### 賣出窗口為什麼從次一交易日起

因為買在訊號日的收盤，而訊號日的開盤在那之前就已經過去了。「訊號日直至今日找最高的開盤價」若照字面把訊號日自己的開盤也納入，就會出現「在下午三點買進，卻賣在當天早上九點」——一筆時序上不可能成立的交易。窗口從次一交易日起，是這個買進價選擇的直接後果，不是額外的保守設定。

**這條規則有一個必然的副作用：訊號日就是最後一個交易日時，該檔沒有任何可賣出的日子。** 這不是錯誤，處置見下方「無法回測的標的」。

#### 賣出價低於買進價是正常結果，不得夾住

「最高開盤價」是**這段期間開盤價的最大值**，不是「最好的出場點」，更不是「保證獲利的出場點」。一檔訊號後一路下跌的股票，它最高的開盤價仍然可能低於訊號日的收盤價，此時報酬率為負。**負值必須如實回報**，不得夾為 `0`、不得改成「不賣出」、不得改挑別的日子。夾住負值會讓這份回測變成一個永遠不會虧錢的工具，那比沒有回測更有害。

### 彙總

| 欄位 | 算法 |
|---|---|
| 總成本 | `Σ(買進價 × 1000)`，只計**可回測**的標的 |
| 總收益 | `Σ(收益)`，只計可回測的標的 |
| 總報酬率 | `總收益 ÷ 總成本 × 100`，四捨五入至小數第二位 |

**總報酬率是以成本加權的，不是各檔報酬率的算術平均。** 因為部位固定為每檔 1 張，而各檔股價相差可達兩個數量級（一張台積電的成本是一張低價股的數十倍），高價股在總報酬率中的權重因此遠高於低價股。這兩個數字**會不一致**，而且是設計如此：畫面上同時看得到逐檔報酬率與總報酬率的人，一定會拿它們互相驗算。

**沒有任何可回測標的時，總報酬率為 `null`，不是 `0`。** `0` 的意思是「算過了，剛好打平」，`null` 的意思是「沒有東西可以算」——兩者在畫面上要顯示不同的內容（見 `specs/frontend/strategy.md`）。此時總成本與總收益皆為 `0`。

### 無法回測的標的

兩種情形下該檔無法產生回測結果：

1. **訊號日之後沒有任何交易日的資料**（訊號日就是該檔最新的一列，或其後區間內無列）——沒有可賣出的開盤價。
2. **訊號日當天在 `stock_daily_price` 沒有該檔的列**——買不進去。這在正常流程下不該發生（訊號本來就是從這張表算出來的），但行情資料可能在掃描之後被改動，因此必須處理而不是假設它不會發生。

兩種情形一律：`buyPrice`／`sellDate`／`sellPrice`／`returnPercent`／`profit` **皆為 `null`**（情形 1 的 `buyPrice` 有值，見下方契約），該檔**照常出現在回應的 `items` 中**，但**完全不計入總成本與總收益**。

**不得從彙總裡悄悄漏掉這些標的。** 呼叫端要能看出「這 12 檔裡有 2 檔沒算進去」，否則總報酬率會看起來像是全部 12 檔的成績。這與「不得因為資料不足就把標的從結果中拿掉」是同一條原則（見 `specs/backend/strategy-scan.md` 的 `insufficientData`）。

**不得對這兩種情形回傳錯誤。** 一份 100 檔的清單裡有 1 檔補不出價格，不構成整次回測失敗的理由。

### 已知限制，必須寫在文件上

- **價格是原始成交價，未經除權息還原**（見 `specs/dba/stock-daily-price.md`）。標的在訊號日之後除權息時，除權息當日的開盤價會低於還原後的真實價值，本回測會把它當成真實跌價。因此**有除權息事件的標的，回測報酬率會系統性偏低**。這是全系統一致的價格約定造成的，不在本模組修正——修正它需要一份還原因子資料，那是另一個需求。
- **不計任何交易成本**：手續費、證交稅、滑價一律不計。實際報酬率會低於本回測的數字。
- **不做部位管理**：每檔各自獨立 1 張，不考慮總資金上限、不考慮同時持有幾檔、不考慮先賣先買。這是一份「逐檔最高開盤價出場」的統計，不是一個交易策略的績效模擬。

### 效能

**目標清單的價格必須以有界次數的查詢取得，不得逐檔各發一次查詢。** 一份 200 檔的清單若逐檔查詢即 200 次往返，而所需資料是「這 200 檔各自從自己的訊號日到今日的 `open_price` 與訊號日的 `close_price`」——以標的清單為條件的批次查詢即可取得。

清單為空的情形由驗證擋掉（見下方錯誤），因此不存在把空集合展開成 `IN` 的問題；但凡以清單為輸入的查詢仍須遵守 `specs/backend/stock-price-ingestion.md` 的同一條規定：清單為空時直接略過查詢，不把空集合交給資料庫。

## Implementation Details

### API 契約

```
POST /api/strategies/backtest
```

Request：
```json
{
  "items": [
    { "stockId": "2330", "signalDate": "2026-08-27" },
    { "stockId": "2317", "signalDate": "2026-08-25" }
  ]
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `items` | array | 是 | 至少一筆，上限 **2000**（見下方「上限為什麼不是 200」） |
| `items[].stockId` | string | 是 | 股票代號；同一代號不得重複出現 |
| `items[].signalDate` | date | 是 | 該檔的訊號日，台北日曆日，不得晚於今日 |

#### 上限為什麼不是 200

**掃描的 `stockIds` 上限 200 綁的是使用者自己打字挑出來的清單；本端點的 `items` 綁的是掃描產生的命中清單。前者的長度由使用者決定，後者由市場決定。** 兩者數字看起來同類，實際上一個是輸入限制、一個是輸出規模，把 200 照抄過來是錯的。

照抄的後果是一個使用者無從迴避的失敗：`specs/frontend/strategy.md` 規定按下「回測」要**送出全部命中標的**，而一次全市場掃描命中 500 檔以上是常態（2026-09-11 實測：上漲支撐 `STANDARD`／漲幅 3%／近一個月，全市場命中 **532 檔**），於是回測必然回 `400 TOO_MANY_STOCKS`，畫面只顯示得出「回測失敗，請稍後再試」。使用者沒有任何辦法把命中檔數降到 200 以下——那不是他輸入的數字。`specs/frontend/strategy.md` 錯誤表把 `TOO_MANY_STOCKS` 註為「前端已先擋，此為後備」，對「指定股票」為真，對「回測」為假：**沒有任何前端擋得住一個它無法控制的數量。**

因此本端點的上限由**掃描母體的規模**決定，而非由打字清單的上限決定：全市場普通股不足 1,500 檔，取 **2000** 為界。這個數字的作用只剩下擋掉荒謬的請求體（例如手工灌進十萬筆），**不再是一條產品規則**——正常操作永遠碰不到它。它也不需要更小：本端點的價格查詢是以整份清單為條件的單次批次查詢（見「效能」），成本隨檔數線性成長於一次查詢的結果集，而不是隨檔數增加往返次數。

**上限不得改回與掃描共用同一個常數。** 兩者會各自因為不同的理由變動：掃描的上限跟著「指定股票」輸入框的 UI 限制走，本端點的上限跟著市場檔數走。共用一個常數等於讓其中一邊的調整無聲地改掉另一邊的合約。

**一檔一筆，代號不得重複。** 一檔股票可能同時命中多個策略而有多個訊號日，選定哪一個是呼叫端的決定（見 `specs/frontend/strategy.md`：取該檔**最新的**一個訊號日），不是本端點的。本端點若容許同一代號多筆，回應就會出現同一檔多列，而畫面上一檔只有一列——那個對應關係要在哪一端建立會變得沒有定論。

Response `200`：
```json
{
  "asOfDate": "2026-09-10",
  "lotSize": 1000,
  "totalCost": 1150000,
  "totalProfit": 55000,
  "totalReturnPercent": 4.78,
  "backtestedCount": 1,
  "items": [
    {
      "stockId": "2330",
      "signalDate": "2026-08-27",
      "buyPrice": 1150.00,
      "sellDate": "2026-09-03",
      "sellPrice": 1205.00,
      "returnPercent": 4.78,
      "profit": 55000
    },
    {
      "stockId": "2317",
      "signalDate": "2026-09-10",
      "buyPrice": 215.50,
      "sellDate": null,
      "sellPrice": null,
      "returnPercent": null,
      "profit": null
    }
  ]
}
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `asOfDate` | date | 本次回測的窗口迄日，即今日（台北日曆日） |
| `lotSize` | int | 每檔的股數，恆為 `1000`。回報它是為了讓呼叫端不必自己知道這個約定就能解釋「收益」的單位 |
| `totalCost` | number | 可回測標的的買進成本總和；無可回測標的時為 `0` |
| `totalProfit` | number | 可回測標的的收益總和；無可回測標的時為 `0` |
| `totalReturnPercent` | number \| null | 總收益 ÷ 總成本 × 100；**無可回測標的時為 `null`** |
| `backtestedCount` | int | 實際計入彙總的標的數，即 `sellDate` 非 `null` 的筆數。它與 `items` 長度的差就是無法回測的檔數 |
| `items[].buyPrice` | number \| null | 訊號日收盤價；訊號日無該檔資料時為 `null` |
| `items[].sellDate` | date \| null | 最高開盤價第一次出現的交易日；無法回測時為 `null` |
| `items[].sellPrice` | number \| null | 賣出窗口內的最高開盤價；無法回測時為 `null` |
| `items[].returnPercent` | number \| null | 兩位小數；**可為負值**；無法回測時為 `null` |
| `items[].profit` | number \| null | 以元為單位；**可為負值**；無法回測時為 `null` |

`items` 的順序與請求中 `items` 的順序一致，本端點不重新排序——排序是呼叫端已經決定好的事（見 `specs/frontend/strategy.md` 的排序規則），回一份順序不同的清單只會逼呼叫端再做一次對應。

驗證與錯誤：

| 情形 | 狀態碼 | 回應 |
|---|---|---|
| `items` 缺漏或為空陣列 | `400` | `{"code":"NO_BACKTEST_ITEMS"}` |
| `items` 超過 2000 筆 | `400` | `{"code":"TOO_MANY_STOCKS"}` |
| `items` 含 `stock` 表中不存在的代號 | `400` | `{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}` |
| 同一 `stockId` 出現一次以上 | `400` | `{"code":"DUPLICATE_STOCK_ID","duplicatedIds":["2330"]}` |
| 任一 `signalDate` 晚於今日 | `400` | `{"code":"INVALID_SIGNAL_DATE","stockId":"2330"}` |

`TOO_MANY_STOCKS` 與 `UNKNOWN_STOCK_ID` 沿用 `specs/backend/strategy-scan.md` 已定的同名錯誤碼與同樣的回應形狀——同一個畫面上的兩支端點對同一種錯誤用兩套代碼，只會讓前端寫兩份處理。**沿用的是錯誤碼，不是門檻值**：掃描的 `stockIds` 上限 200 與本端點的 `items` 上限 2000 綁的是兩種不同的東西，理由見下。

### 服務流程

1. 驗證請求（上表五項）。任一項不過即回 `400`，不做任何查詢。
2. 取今日（台北日曆日）為 `asOfDate`。
3. 以整份標的清單為條件，批次取得所需價格：各檔訊號日的 `close_price`，以及各檔 `(訊號日, asOfDate]` 區間內的 `open_price` 與其 `trade_date`。
4. 逐檔計算：
   - 訊號日無收盤價 → `buyPrice` 為 `null`，該檔其餘欄位皆 `null`，不計入彙總。
   - 賣出窗口內無任何列 → `buyPrice` 有值，其餘欄位 `null`，不計入彙總。
   - 否則取窗口內 `open_price` 的最大值為 `sellPrice`，其**最早**出現的 `trade_date` 為 `sellDate`，依「回測規則」算出 `returnPercent` 與 `profit`，計入彙總。
5. 彙總 `totalCost` / `totalProfit` / `totalReturnPercent` / `backtestedCount`，依請求順序組出 `items`。

**本端點不寫入任何資料表**，也不建立任何 `stock_sync_progress` 列——它是同步的純讀取運算，沒有進度可追蹤，也沒有併發鎖需要持有。

### 資料來源對應

| 用途 | 來源 |
|---|---|
| 買進價 | `stock_daily_price.close_price`，`stock_id` = 該檔、`trade_date` = 訊號日 |
| 賣出價候選 | `stock_daily_price.open_price`，`stock_id` = 該檔、`trade_date` 介於訊號日之後與 `asOfDate` 之間 |
| 賣出日 | 上述最大 `open_price` 所在列的 `trade_date`，同值取最早 |
| 代號存在性 | `stock`（見 `specs/dba/stock.md`）；**不篩 `is_active`**，已下市股票照常可回測，與掃描允許指定已下市股票一致 |

停牌日在 `stock_daily_price` 中不存在列（見 `specs/backend/stock-price-ingestion.md` 的「不得補零」），因此自然不會成為賣出日，不需要額外排除。

## Acceptance Criteria

### 回測規則
- [x] `POST /api/strategies/backtest` 對單一標的回傳的 `buyPrice` 等於該檔訊號日在 `stock_daily_price` 的 `close_price`
- [x] `sellPrice` 等於該檔「訊號日之後至今日」區間內 `open_price` 的最大值，且 `sellDate` 為該值所在的交易日
- [x] **訊號日當天的開盤價不納入賣出窗口**：構造一檔其訊號日開盤價高於其後所有開盤價的資料，回應的 `sellDate` 不等於訊號日，`sellPrice` 不等於訊號日的開盤價
- [x] 最高開盤價在多個交易日出現相同數值時，`sellDate` 為其中**最早**的那一天
- [x] `returnPercent` 等於 `(sellPrice − buyPrice) ÷ buyPrice × 100` 四捨五入至小數第二位
- [x] `profit` 等於 `(sellPrice − buyPrice) × 1000` 四捨五入至元，且 `lotSize` 回傳 `1000`
- [x] 訊號後一路下跌的標的其 `returnPercent` 與 `profit` **為負值**，未被夾為 `0`，也未改挑其他日期
- [x] 停牌造成的缺列不會成為 `sellDate`（該日在 `stock_daily_price` 無列，自然不在候選中）

### 彙總
- [x] `totalCost` 等於各可回測標的 `buyPrice × 1000` 的總和
- [x] `totalProfit` 等於各可回測標的 `profit` 的總和
- [x] `totalReturnPercent` 等於 `totalProfit ÷ totalCost × 100`，兩位小數
- [x] 以兩檔股價差距懸殊（例如 `1150` 與 `21.5`）的資料驗證：`totalReturnPercent` **不等於**兩檔 `returnPercent` 的算術平均，且等於成本加權的結果
- [x] `backtestedCount` 等於 `sellDate` 非 `null` 的筆數；清單中含無法回測標的時，它小於 `items` 的長度

### 無法回測的標的
- [x] 訊號日為該檔最新一個交易日時：`buyPrice` 有值，`sellDate`／`sellPrice`／`returnPercent`／`profit` 皆為 `null`，回應為 `200` 而非錯誤
- [x] 訊號日在 `stock_daily_price` 無該檔列時：`buyPrice` 亦為 `null`，其餘同上，回應為 `200`
- [x] 上述兩種標的**仍出現在 `items` 中**，未被從回應裡移除
- [x] 上述兩種標的**不計入** `totalCost` 與 `totalProfit`：以「一檔可回測 + 一檔不可回測」的清單驗證，`totalCost` 等於可回測那一檔單獨回測時的值
- [x] 清單中全部標的皆無法回測時：`totalCost` 與 `totalProfit` 為 `0`，`totalReturnPercent` 為 **`null`**（不是 `0`），`backtestedCount` 為 `0`

### 契約與驗證
- [x] `items` 的順序與請求一致，本端點不重新排序
- [x] `items` 缺漏或為空陣列回 `400`，`{"code":"NO_BACKTEST_ITEMS"}`，且不對資料庫發出任何價格查詢
- [ ] `items` 為 2001 筆時回 `400`，`{"code":"TOO_MANY_STOCKS"}`；2000 筆為合法
- [ ] **一份 532 筆的全市場命中清單回 `200`**，不因超過掃描的 `stockIds` 上限 200 而被拒絕
- [x] 含不存在的代號時回 `400`，`{"code":"UNKNOWN_STOCK_ID","unknownIds":[...]}`
- [x] 同一 `stockId` 出現兩次時回 `400`，`{"code":"DUPLICATE_STOCK_ID","duplicatedIds":[...]}`
- [x] `signalDate` 晚於今日時回 `400`，`{"code":"INVALID_SIGNAL_DATE","stockId":"<該檔>"}`
- [x] 已下市（`is_active = 0`）的股票可正常回測，不因下市被拒絕或被排除
- [x] 本端點不寫入 `stock_daily_price`、`stock`、`stock_sync_progress` 任何一張表：一次回測前後三張表的列數與內容完全不變
- [x] 本端點不建立 `stock_sync_progress` 列，也不與 `PRICE_BACKFILL`／`INDICATOR_REBUILD` 的併發鎖互斥：回補進行中呼叫回測正常回 `200`，不回 `409`

### 效能
- [x] 一份 50 檔的清單，其價格查詢次數不隨檔數線性增加（以查詢計數斷言，非以耗時推測），不得逐檔各發一次查詢

---
## Execution Result
- Status: DONE

- Files changed:
  - `develop/backend/src/main/java/com/stock/controller/StrategyController.java` — added `POST /api/strategies/backtest`, constructor-injects the new `StrategyBacktestService`.
  - `develop/backend/src/main/java/com/stock/service/StrategyBacktestService.java` (new) — validation + the read-only backtest computation.
  - `develop/backend/src/main/java/com/stock/dto/BacktestRequestDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/BacktestItemRequestDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/BacktestResponseDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/BacktestResultItemDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` — added `duplicatedIds` field/constructor param, `invalidSignalDate(String)` and `duplicateStockId(List<String>)` factories.
  - `develop/backend/src/main/java/com/stock/exception/NoBacktestItemsException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/DuplicateStockIdException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/InvalidSignalDateException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — 3 new `@ExceptionHandler`s for the above; `TooManyStocksException` and `UnknownStockIdException` (and their existing handlers/`ErrorResponse` factories) are reused verbatim, unchanged, exactly as the spec requires.
  - `develop/backend/src/test/java/com/stock/StrategyBacktestIntegrationTest.java` (new) — 28 tests, `BT`-prefixed synthetic stock ids.

- Design decisions:
  - **Single batched query, not the spec's suggested two.** The service resolves the min `signalDate` across all items, then issues one call to the already-existing `StockDailyPriceMapper.findByStockIdsAndDateRange(stockIds, minSignalDate, asOfDate)` (the same batched range query `strategy-scan` uses) covering every item's own `(signalDate, asOfDate]` window at once. Each stock's own signal-date/window boundaries are then applied in memory per item. This satisfies "bounded query count" with one round trip instead of the two the spec's narrative describes (one for buy-day close, one for the sell window), and adds no new mapper method.
  - **Existence check reuses `StockMapper.findExistingStockIds`** (the same helper the write-side endpoints use) rather than `findByIds` + manual diff — narrower (ids only, no name/market columns) and, per its own contract, does not filter `is_active`, matching "已下市股票照常可回測".
  - **`TOO_MANY_STOCKS`/`UNKNOWN_STOCK_ID` reused verbatim**: `StrategyBacktestService` throws the existing `TooManyStocksException`/`UnknownStockIdException`, handled by the existing `GlobalExceptionHandler` entries and `ErrorResponse` factories already wired for `strategy-scan` — no parallel error type was introduced. `MAX_ITEMS` is declared as `StrategyScanService.MAX_STOCK_IDS` (single source of truth for the "200" cap) rather than a second literal constant.
  - **Missing `signalDate` is treated as `INVALID_SIGNAL_DATE`.** The spec doesn't define a distinct "missing signalDate" error code (unlike `NO_BACKTEST_ITEMS` for a missing `items` array), so a `null` signalDate is rejected via the same `INVALID_SIGNAL_DATE` path as "later than today" — both are dates the caller could not honestly have signalled on. This avoids a 500/NPE on malformed input without inventing an unspecced error code.
  - **Validation order follows the spec's error table top-to-bottom**: empty → too many → unknown ids → duplicate ids → invalid signal date. Since each acceptance criterion constructs exactly one violation at a time, this order was never actually exercised by a multi-violation case, but it matches the spec's literal table order for predictability.
  - **Rounding**: `RoundingMode.HALF_UP` throughout (matches `MomentumGainService`'s existing convention project-wide), 2-decimal scale for percentages, 0-decimal (whole yuan) scale for `profit`/`totalCost`/`totalProfit`. `totalReturnPercent` is computed from the *unrounded* accumulator `totalProfit`/`totalCost` (mathematically exact integers regardless, since 2-decimal prices × 1000 always land on a whole yuan) — no precision loss either way.
  - Per-stock `profit` values are summed into `totalProfit` *after* their own rounding (i.e. `totalProfit = Σ(rounded profit)`), matching the spec's literal aggregate definition ("`Σ(收益)`" where 收益 is itself already defined as the rounded-to-yuan quantity).

- Verification (real command output tails):
  - `mvn -f develop/backend/pom.xml compile` — `BUILD SUCCESS`, no warnings introduced.
  - New test class alone: `mvn -f develop/backend/pom.xml test -Dtest=StrategyBacktestIntegrationTest` → `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`.
  - Full suite: `mvn -f develop/backend/pom.xml test` →
    ```
    [INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.295 s - in com.stock.StrategyBacktestIntegrationTest
    [INFO] Tests run: 105, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.612 s - in com.stock.StrategyScanIntegrationTest
    [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0 s - in com.stock.util.NormalizeUtilTest
    [INFO] Tests run: 379, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS
    ```
    379 = 351 baseline + 28 new. No pre-existing test regressed.
  - Ran against the real MySQL test database on `127.0.0.1:3306` (same one the other integration tests use), not a mock — confirmed reachable before the run (`mysql -h127.0.0.1 -P3306 -uapp ... -e "SELECT 1"` → `1`).
  - Did not start `spring-boot:run` / a second server on port 8080 — the live instance already holding that port was left untouched; `mvn test` uses its own random-port context per the environment note.
  - `code-quality` skill self-review pass: found one real DRY issue (existence check duplicating `findByIds`-based logic already present in `StrategyScanService`) and fixed it by switching to the existing `StockMapper.findExistingStockIds` helper; re-ran `StrategyBacktestIntegrationTest` + `StrategyScanIntegrationTest` (133 tests) afterward to confirm no regression, then the full suite again (379 tests) — all green. No other Critical/Important findings from the checklist (null-safety, error handling, resource lifecycle, atomicity, performance) applied cleanly to this read-only, no-external-call endpoint.

- Acceptance criteria verified by the new tests (28 of 28 boxes in this spec have a corresponding passing test — mapped 1:1 to the `### 回測規則` / `### 彙總` / `### 無法回測的標的` / `### 契約與驗證` / `### 效能` sections above by test name). I did not check off any boxes myself, per instructions — please verify and check them off.

- Anything NOT independently verified, and why:
  - No manual/live-server check was performed (per the environment note, the already-running instance on port 8080 was left alone rather than restarted or duplicated). All verification is via the integration test suite against the real test database.
  - The 200-item boundary test (`items201_returns400TooManyStocks_200IsLegal`) only verifies the 200-item request returns `200 OK`; it seeds 200 bare stock master rows with no price history, so it does not additionally assert per-item backtest correctness at that scale — that combination (200 items × full correctness) was judged redundant given the dedicated correctness tests already cover the computation with small fixtures and the dedicated query-count test covers scale (50 stocks) separately.
