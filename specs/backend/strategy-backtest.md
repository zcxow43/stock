---
status: done
title: "策略命中回測 API"
requirement: "策略掃描結果回測：以每一筆命中的進場日（`buyDate`，由掃描回報；上漲支撐為確認完成日 D+2，其餘型態等於訊號日）收盤買進，其後至今日之間以最高開盤價賣出，逐筆回報買進日／買進價／賣出日／賣出價／報酬率／收益，加上彙總總報酬率與總收益。一檔股票每有一個相異買進日就是獨立的一筆（各 1 張、各自計入彙總），同一買進日不得重複送出"
depends_on: [strategy-scan, stock-price-ingestion]
---

# 策略命中回測 API — Backend Spec

## Overview

把一份掃描命中清單換算成「如果當時買了，到今天為止最好能賣在哪裡」的結果。輸入是 `specs/backend/strategy-scan.md` 掃出來的標的與各筆的**進場日**（`buyDate`），輸出是逐筆的賣出日、報酬率、收益，加上整份清單的總報酬率與總收益。

**它不做任何型態判定，也不對外抓任何資料。** 判定是掃描的事，行情是 `specs/backend/stock-price-ingestion.md` 的事；本模組只做一件事——讀 `stock_daily_price` 已有的價格，做四則運算。這是它能是一支獨立、無狀態、可重複呼叫的端點的原因，也是它不需要任何新資料表的原因：回測結果**不落地**，每次呼叫重算。

**與掃描分成兩支端點，不是合併成一個回應。** 掃描回應一到，命中清單就能先顯示；回測是前端在掃描成功後自動接著送出的第二個請求（見 `specs/frontend/strategy.md` 的「掃描後自動回測」）。全市場回測要算上千筆，合併成一個回應會讓命中清單陪著回測一起等；分開之後，回測失敗時也能只重送回測、不重跑掃描。

## Requirements

### 回測規則

一筆回測，由「買進日」（請求中該筆的 `buyDate`）與「今日」兩個日期決定，全程只讀該檔的 `stock_daily_price`：

| 項目 | 規則 |
|---|---|
| 買進日 | 請求中該筆的 `buyDate`——掃描回報的進場日（上漲支撐為確認完成日 D+2，其餘型態等於訊號日，見 `specs/backend/strategy-scan.md`） |
| 買進價 | 買進日的**收盤價** |
| 賣出窗口 | 買進日的**次一交易日**起，至今日為止（含今日） |
| 賣出價 | 賣出窗口內**最高的開盤價** |
| 賣出日 | 該最高開盤價**第一次出現**的交易日 |
| 部位 | **每一筆**固定 1 張＝ 1,000 股；一檔有幾個相異買進日就有幾筆，各佔 1 張 |
| 報酬率 | `(賣出價 − 買進價) ÷ 買進價 × 100`，四捨五入至小數第二位 |
| 收益 | `(賣出價 − 買進價) × 1000`，四捨五入至元 |

#### 買進日由請求指定，本端點不推算

請求與回應的每一筆都以 `buyDate` 表示買進日。**本端點不知道任何型態，也不推算進場日**——哪一天才是某個型態可以買進的日子，是型態定義的一部分，由 `specs/backend/strategy-scan.md` 在每筆命中上回報為 `buyDate`，呼叫端原樣送來。若由本端點依策略推算，就等於在掃描之外再寫一份型態規則，兩份遲早會對不起來。

請求與回應**不含 `signalDate`**：訊號日是畫面上「命中策略與訊號日」的顯示資訊，不參與任何回測計算。上漲支撐的訊號日（起漲日 D）與買進日（D+2）刻意不同；若回測仍以訊號日為錨，就會在 D 收盤買進一檔要到 D+2 收盤才確認成立的股票——等於用兩天的未來收盤價挑股票。

賣出日則是算出來的、無法由請求推得，所以 `sellDate` 必須是一個獨立欄位。

#### 買進價為什麼是收盤價

五個型態的訊號**全部是用收盤價判定的**（箱型突破看突破收盤、上漲支撐看上漲收盤與其後兩日收盤、底底高看 MA5、反彈與累積上漲看谷底與高點收盤——見 `specs/backend/strategy-scan.md`）。也就是說，型態要到**確認成立那一天的收盤**才成立——對大多數型態那就是訊號日，對上漲支撐則是 D+2（見上一節），這一天就是 `buyDate`。以那一天的**開盤價**買進，等於用了當下不可能知道的資訊（look-ahead bias），會系統性地把回測結果美化，而美化的幅度剛好就是當天從開盤漲到收盤的那一段——對「上漲支撐」這種以單日大漲為訊號的型態，那一段正是最大的一段。

#### 賣出窗口為什麼從次一交易日起

因為買在買進日的收盤，而買進日的開盤在那之前就已經過去了。「買進日直至今日找最高的開盤價」若照字面把買進日自己的開盤也納入，就會出現「在下午三點買進，卻賣在當天早上九點」——一筆時序上不可能成立的交易。窗口從次一交易日起，是這個買進價選擇的直接後果，不是額外的保守設定。

**這條規則有一個必然的副作用：買進日就是最後一個交易日時，該檔沒有任何可賣出的日子。** 這不是錯誤，處置見下方「無法回測的標的」。

#### 賣出價低於買進價是正常結果，不得夾住

「最高開盤價」是**這段期間開盤價的最大值**，不是「最好的出場點」，更不是「保證獲利的出場點」。一檔訊號後一路下跌的股票，它最高的開盤價仍然可能低於買進日的收盤價，此時報酬率為負。**負值必須如實回報**，不得夾為 `0`、不得改成「不賣出」、不得改挑別的日子。夾住負值會讓這份回測變成一個永遠不會虧錢的工具，那比沒有回測更有害。

### 彙總

| 欄位 | 算法 |
|---|---|
| 總成本 | `Σ(買進價 × 1000)`，只計**可回測**的筆（同一檔的多筆各計一次） |
| 總收益 | `Σ(收益)`，只計可回測的筆 |
| 總報酬率 | `總收益 ÷ 總成本 × 100`，四捨五入至小數第二位 |

**總報酬率是以成本加權的，不是各檔報酬率的算術平均。** 因為部位固定為每筆 1 張，而各檔股價相差可達兩個數量級（一張台積電的成本是一張低價股的數十倍），高價股在總報酬率中的權重因此遠高於低價股。這兩個數字**會不一致**，而且是設計如此：畫面上同時看得到逐檔報酬率與總報酬率的人，一定會拿它們互相驗算。

**沒有任何可回測標的時，總報酬率為 `null`，不是 `0`。** `0` 的意思是「算過了，剛好打平」，`null` 的意思是「沒有東西可以算」——兩者在畫面上要顯示不同的內容（見 `specs/frontend/strategy.md`）。此時總成本與總收益皆為 `0`。

### 無法回測的標的

三種情形下該筆無法產生回測結果：

1. **買進日之後沒有任何交易日的資料**（買進日就是該檔最新的一列，或其後區間內無列）——沒有可賣出的開盤價。
2. **買進日當天在 `stock_daily_price` 沒有該檔的列**——買不進去。這在正常流程下不該發生（進場日本來就是從這張表算出來的），但行情資料可能在掃描之後被改動，因此必須處理而不是假設它不會發生。

3. **買進日當天的收盤價為 `0`**——該日該檔實際沒有成交，行情以 `0` 記錄（見下方「價格為 `0` 的日子不是行情」）。買不進去，且報酬率的分母為 `0`、算不出來。

三種情形一律：`buyPrice`／`sellDate`／`sellPrice`／`returnPercent`／`profit` **皆為 `null`**（情形 1 的 `buyPrice` 有值，見下方契約），該檔**照常出現在回應的 `items` 中**，但**完全不計入總成本與總收益**。

#### 價格為 `0` 的日子不是行情

`stock_daily_price` 中存在成交價欄位為 `0` 的列（2026-09-12 實測：1,044 列、63 檔）——那是「該日這檔沒有成交」的記錄方式，不是「它那天值 0 元」。因此：

- **買進日收盤價為 `0` 時，該筆無法回測**（上表情形 3）。以 `0` 當買進價會讓報酬率的分母為零；真的去除，得到的是一次 `500`，而不是一個數字。
- **賣出窗口內開盤價為 `0` 的列不列入賣出候選。** 買不到也賣不掉的日子不該被選成賣出日；若窗口內沒有任何開盤價大於 `0` 的列，等同「沒有可賣出的交易日」（情形 1）。
- 這條規則對停牌日是自然一致的：停牌日在本表沒有列（見 `specs/backend/stock-price-ingestion.md` 的「不得補零」），而這些 `0` 列是另一種來源寫進來的同一件事——沒有成交。兩者都不該成為買點或賣點。

**不得改用「把 `0` 當成前一日收盤」之類的補值來繞過。** 那會憑空製造一筆沒有發生過的交易，且補出來的報酬率無從驗算。

**不得從彙總裡悄悄漏掉這些標的。** 呼叫端要能看出「這 12 檔裡有 2 檔沒算進去」，否則總報酬率會看起來像是全部 12 檔的成績。這與「不得因為資料不足就把標的從結果中拿掉」是同一條原則（見 `specs/backend/strategy-scan.md` 的 `insufficientData`）。

**不得對這兩種情形回傳錯誤。** 一份 100 檔的清單裡有 1 檔補不出價格，不構成整次回測失敗的理由。

### 已知限制，必須寫在文件上

- **價格是原始成交價，未經除權息還原**（見 `specs/dba/stock-daily-price.md`）。標的在買進日之後除權息時，除權息當日的開盤價會低於還原後的真實價值，本回測會把它當成真實跌價。因此**有除權息事件的標的，回測報酬率會系統性偏低**。這是全系統一致的價格約定造成的，不在本模組修正——修正它需要一份還原因子資料，那是另一個需求。
- **不計任何交易成本**：手續費、證交稅、滑價一律不計。實際報酬率會低於本回測的數字。
- **不做部位管理**：每檔各自獨立 1 張，不考慮總資金上限、不考慮同時持有幾檔、不考慮先賣先買。這是一份「逐檔最高開盤價出場」的統計，不是一個交易策略的績效模擬。

### 效能

**目標清單的價格必須以有界次數的查詢取得，不得逐檔各發一次查詢。** 一份 200 檔的清單若逐檔查詢即 200 次往返，而所需資料是「這 200 檔各自從自己的買進日到今日的 `open_price` 與買進日的 `close_price`」——以標的清單為條件的批次查詢即可取得。

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
    { "stockId": "2330", "buyDate": "2026-08-27" },
    { "stockId": "2317", "buyDate": "2026-08-25" }
  ]
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `items` | array | 是 | 至少一筆，上限 **20000 筆**（計的是**筆數**不是檔數，見下方「上限為什麼不是 200」） |
| `items[].stockId` | string | 是 | 股票代號；**同一代號可出現多次**，只要買進日不同 |
| `items[].buyDate` | date | 是 | 該筆的買進日＝掃描回報的進場日，台北日曆日，不得晚於今日 |
| — | — | — | `(stockId, buyDate)` 這個**組合**不得重複出現（見下方「一筆＝一個買進日」） |

#### 上限為什麼不是 200

**掃描的 `stockIds` 上限 200 綁的是使用者自己打字挑出來的清單；本端點的 `items` 綁的是掃描產生的命中清單。前者的長度由使用者決定，後者由市場決定。** 兩者數字看起來同類，實際上一個是輸入限制、一個是輸出規模，把 200 照抄過來是錯的。

照抄的後果是一個使用者無從迴避的失敗：`specs/frontend/strategy.md` 規定回測要**送出全部命中標的**，而一次全市場掃描命中 500 檔以上是常態（2026-09-11 實測：上漲支撐 `STANDARD`／漲幅 3%／近一個月，全市場命中 **532 檔**），於是回測必然回 `400 TOO_MANY_STOCKS`，畫面只顯示得出「回測失敗，請稍後再試」。使用者沒有任何辦法把命中檔數降到 200 以下——那不是他輸入的數字。`specs/frontend/strategy.md` 錯誤表把 `TOO_MANY_STOCKS` 註為「前端已先擋，此為後備」，對「指定股票」為真，對「回測」為假：**沒有任何前端擋得住一個它無法控制的數量。**

因此本端點的上限由**掃描能產出的規模**決定，而非由打字清單的上限決定。而自從一檔可送多筆（見「一筆＝一個買進日」）之後，這個規模不再等於檔數：**上限計的是筆數，而一檔股票每有一個相異買進日就貢獻一筆。** 全市場普通股不足 1,500 檔，一次「近半年」的掃描又可能讓同一檔在多個交易日各成立一次訊號，所以界要訂在「檔數 × 單檔合理進場日數」的量級上，取 **20000** 為界。

這個數字的作用只剩下擋掉荒謬的請求體（例如手工灌進十萬筆），**不再是一條產品規則**——正常操作永遠碰不到它。它也不需要更小：本端點的價格查詢是以整份清單為條件的單次批次查詢（見「效能」），成本隨筆數線性成長於一次查詢的結果集，而不是隨筆數增加往返次數。

**若某天真的有一次正當的掃描撞到這個上限，要做的是把上限調高，不是在前端截斷清單。** 截斷會讓總報酬率悄悄變成一份沒有說明的抽樣結果——那正是 200 那次事故的同一種錯誤，只是換了個數字。

**上限不得改回與掃描共用同一個常數。** 兩者會各自因為不同的理由變動：掃描的上限跟著「指定股票」輸入框的 UI 限制走，本端點的上限跟著市場檔數走。共用一個常數等於讓其中一邊的調整無聲地改掉另一邊的合約。

#### 一筆＝一個買進日

**唯一鍵是 `(stockId, buyDate)` 的組合，不是 `stockId`。** 一檔股票可能有多個進場日，每一個都是一次獨立的「那天收盤買進、其後最高開盤賣出」，各自有自己的買進價、賣出日、賣出價與報酬率，各自佔 1 張部位。三個相異買進日的標的送三筆，回應就回三筆。

**同一個買進日不得送兩筆，即使造成它的是兩個不同的策略、甚至兩個不同的訊號日。** 同一天的買進價相同、賣出窗口相同，兩筆的結果會完全一樣——計入彙總等於把同一個部位算兩次，讓該檔在成本加權的總報酬率中憑空取得雙倍權重。上漲支撐起漲日 08-26 的命中（買進日 08-28）與反彈訊號日 08-28 的命中（買進日 08-28）是同一筆；上漲支撐起漲日 08-26（買進日 08-28）與底底高訊號日 08-26（買進日 08-26）則是兩筆。那天只能買進一次，看的是買進日，不是訊號日。**本端點不接受 `strategyCode` 或 `signalDate` 之類的欄位**：哪些策略、哪個訊號日造成了這個買進日，是呼叫端的顯示問題（見 `specs/frontend/strategy.md` 的展開列），不是回測的計算輸入。

送出前依 `(stockId, buyDate)` 去重是呼叫端的責任；沒去重就是 `400`，不是由本端點默默合併——默默合併會讓回應的筆數與請求對不起來，呼叫端無從得知自己少算了什麼。

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
      "buyDate": "2026-08-27",
      "buyPrice": 1150.00,
      "sellDate": "2026-09-03",
      "sellPrice": 1205.00,
      "returnPercent": 4.78,
      "profit": 55000
    },
    {
      "stockId": "2330",
      "buyDate": "2026-09-02",
      "buyPrice": 1180.00,
      "sellDate": "2026-09-05",
      "sellPrice": 1210.00,
      "returnPercent": 2.54,
      "profit": 30000
    },
    {
      "stockId": "2317",
      "buyDate": "2026-09-10",
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
| `backtestedCount` | int | 實際計入彙總的**筆數**，即 `sellDate` 非 `null` 的筆數。它與 `items` 長度的差就是無法回測的筆數 |
| `items[].buyDate` | date | 該筆的買進日，原樣帶回請求中的 `buyDate`；回應不含 `signalDate` |
| `items[].buyPrice` | number \| null | 買進日收盤價，即**買進價**；買進日無該檔資料或收盤價為 `0` 時為 `null` |
| `items[].sellDate` | date \| null | 最高開盤價第一次出現的交易日；無法回測時為 `null` |
| `items[].sellPrice` | number \| null | 賣出窗口內的最高開盤價；無法回測時為 `null` |
| `items[].returnPercent` | number \| null | 兩位小數；**可為負值**；無法回測時為 `null` |
| `items[].profit` | number \| null | 以元為單位；**可為負值**；無法回測時為 `null` |

`items` 的順序與請求中 `items` 的順序一致，本端點不重新排序——排序是呼叫端已經決定好的事（見 `specs/frontend/strategy.md` 的排序規則），回一份順序不同的清單只會逼呼叫端再做一次對應。

驗證與錯誤：

| 情形 | 狀態碼 | 回應 |
|---|---|---|
| `items` 缺漏或為空陣列 | `400` | `{"code":"NO_BACKTEST_ITEMS"}` |
| `items` 超過 20000 筆 | `400` | `{"code":"TOO_MANY_STOCKS"}` |
| `items` 含 `stock` 表中不存在的代號 | `400` | `{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}` |
| 同一 `(stockId, buyDate)` 組合出現一次以上 | `400` | `{"code":"DUPLICATE_BACKTEST_ITEM","duplicatedItems":[{"stockId":"2330","buyDate":"2026-08-27"}]}` |
| 任一 `buyDate` 缺漏或晚於今日 | `400` | `{"code":"INVALID_BUY_DATE","stockId":"2330"}` |

`TOO_MANY_STOCKS` 與 `UNKNOWN_STOCK_ID` 沿用 `specs/backend/strategy-scan.md` 已定的同名錯誤碼與同樣的回應形狀——同一個畫面上的兩支端點對同一種錯誤用兩套代碼，只會讓前端寫兩份處理。**沿用的是錯誤碼，不是門檻值**：掃描的 `stockIds` 上限 200 與本端點的 `items` 上限 20000 綁的是兩種不同的東西，理由見下。

### 服務流程

1. 驗證請求（上表五項）。任一項不過即回 `400`，不做任何查詢。
2. 取今日（台北日曆日）為 `asOfDate`。
3. 以整份標的清單為條件，批次取得所需價格：各筆買進日的 `close_price`，以及各筆 `(買進日, asOfDate]` 區間內的 `open_price` 與其 `trade_date`。
4. 逐筆計算（一筆＝一個 `(stockId, buyDate)`，同一檔的多筆各自獨立計算，彼此不影響）：
   - 買進日無收盤價（或收盤價為 `0`） → `buyPrice` 為 `null`，該檔其餘欄位皆 `null`，不計入彙總。
   - 賣出窗口內無任何列 → `buyPrice` 有值，其餘欄位 `null`，不計入彙總。
   - 否則取窗口內 `open_price` 的最大值為 `sellPrice`，其**最早**出現的 `trade_date` 為 `sellDate`，依「回測規則」算出 `returnPercent` 與 `profit`，計入彙總。
5. 彙總 `totalCost` / `totalProfit` / `totalReturnPercent` / `backtestedCount`，依請求順序組出 `items`。

**本端點不寫入任何資料表**，也不建立任何 `stock_sync_progress` 列——它是同步的純讀取運算，沒有進度可追蹤，也沒有併發鎖需要持有。

### 資料來源對應

| 用途 | 來源 |
|---|---|
| 買進價 | `stock_daily_price.close_price`，`stock_id` = 該檔、`trade_date` = 買進日 |
| 賣出價候選 | `stock_daily_price.open_price`，`stock_id` = 該檔、`trade_date` 介於買進日之後與 `asOfDate` 之間 |
| 賣出日 | 上述最大 `open_price` 所在列的 `trade_date`，同值取最早 |
| 代號存在性 | `stock`（見 `specs/dba/stock.md`）；**不篩 `is_active`**，已下市股票照常可回測，與掃描允許指定已下市股票一致 |

停牌日在 `stock_daily_price` 中不存在列（見 `specs/backend/stock-price-ingestion.md` 的「不得補零」），因此自然不會成為賣出日，不需要額外排除。

## Acceptance Criteria

### 回測規則
- [x] `POST /api/strategies/backtest` 對單一標的回傳的 `buyPrice` 等於該檔買進日在 `stock_daily_price` 的 `close_price`
- [x] `sellPrice` 等於該檔「買進日之後至今日」區間內 `open_price` 的最大值，且 `sellDate` 為該值所在的交易日
- [x] **買進日當天的開盤價不納入賣出窗口**：構造一檔其買進日開盤價高於其後所有開盤價的資料，回應的 `sellDate` 不等於買進日，`sellPrice` 不等於買進日的開盤價
- [x] 最高開盤價在多個交易日出現相同數值時，`sellDate` 為其中**最早**的那一天
- [x] `returnPercent` 等於 `(sellPrice − buyPrice) ÷ buyPrice × 100` 四捨五入至小數第二位
- [x] `profit` 等於 `(sellPrice − buyPrice) × 1000` 四捨五入至元，且 `lotSize` 回傳 `1000`
- [x] 訊號後一路下跌的標的其 `returnPercent` 與 `profit` **為負值**，未被夾為 `0`，也未改挑其他日期
- [x] 停牌造成的缺列不會成為 `sellDate`（該日在 `stock_daily_price` 無列，自然不在候選中）
- [x] **買進日收盤價為 `0` 時該筆無法回測**：`buyPrice`／`sellDate`／`sellPrice`／`returnPercent`／`profit` 皆為 `null`，回應為 `200`，**不得回 `500`**（不得對 `0` 做除法）
- [x] 賣出窗口內開盤價為 `0` 的列**不列入賣出候選**：以一組「窗口內僅有的高開盤價那天為 `0`」的構造資料驗證，`sellDate` 不是那一天
- [x] 賣出窗口內所有開盤價皆為 `0` 時，視同無可賣出交易日：`sellDate`／`sellPrice`／`returnPercent`／`profit` 為 `null` 而 `buyPrice` 有值
- [x] 上述三種情形皆**不計入** `totalCost`／`totalProfit`／`backtestedCount`，且該筆仍留在 `items` 中

### 彙總
- [x] `totalCost` 等於各可回測標的 `buyPrice × 1000` 的總和
- [x] `totalProfit` 等於各可回測標的 `profit` 的總和
- [x] `totalReturnPercent` 等於 `totalProfit ÷ totalCost × 100`，兩位小數
- [x] 以兩檔股價差距懸殊（例如 `1150` 與 `21.5`）的資料驗證：`totalReturnPercent` **不等於**兩檔 `returnPercent` 的算術平均，且等於成本加權的結果
- [x] `backtestedCount` 等於 `sellDate` 非 `null` 的筆數；清單中含無法回測標的時，它小於 `items` 的長度

### 無法回測的標的
- [x] 買進日為該檔最新一個交易日時：`buyPrice` 有值，`sellDate`／`sellPrice`／`returnPercent`／`profit` 皆為 `null`，回應為 `200` 而非錯誤
- [x] 買進日在 `stock_daily_price` 無該檔列時：`buyPrice` 亦為 `null`，其餘同上，回應為 `200`
- [x] 上述兩種標的**仍出現在 `items` 中**，未被從回應裡移除
- [x] 上述兩種標的**不計入** `totalCost` 與 `totalProfit`：以「一檔可回測 + 一檔不可回測」的清單驗證，`totalCost` 等於可回測那一檔單獨回測時的值
- [x] 清單中全部標的皆無法回測時：`totalCost` 與 `totalProfit` 為 `0`，`totalReturnPercent` 為 **`null`**（不是 `0`），`backtestedCount` 為 `0`

### 契約與驗證
- [x] `items` 的順序與請求一致，本端點不重新排序
- [x] `items` 缺漏或為空陣列回 `400`，`{"code":"NO_BACKTEST_ITEMS"}`，且不對資料庫發出任何價格查詢
- [x] `items` 為 20001 筆時回 `400`，`{"code":"TOO_MANY_STOCKS"}`；2000 筆為合法（上限計的是**筆數**，`(stockId, buyDate)` 不同的多筆各計一筆）
- [x] **一份 532 筆的全市場命中清單回 `200`**，不因超過掃描的 `stockIds` 上限 200 而被拒絕

### 一檔多買進日
- [x] 同一 `stockId` 帶**兩個不同 `buyDate`** 送出時回 `200`，`items` 回**兩筆**，各自有自己的 `buyPrice`／`sellDate`／`sellPrice`／`returnPercent`／`profit`
- [x] 同一檔的兩筆各自獨立計算：以構造資料驗證，第二筆的 `sellDate` 只在其自己的 `(buyDate, asOfDate]` 窗口內選取，不受第一筆的窗口影響
- [x] 同一檔的兩筆**各自計入一次成本與收益**：`totalCost` 等於兩筆 `buyPrice × 1000` 的總和，`backtestedCount` 為 `2`
- [x] 同一 `(stockId, buyDate)` 組合出現兩次時回 `400`，`{"code":"DUPLICATE_BACKTEST_ITEM","duplicatedItems":[{"stockId":"...","buyDate":"..."}]}`，且**不**默默合併成一筆
- [x] 同一 `stockId` 搭配**不同** `buyDate` 不觸發上述錯誤
- [x] `items` 的順序仍與請求一致，同一檔的多筆維持送入順序
- [x] 回應的每一筆以 `buyDate` 表示買進日，請求與回應皆**不含** `signalDate` 欄位
- [x] 含不存在的代號時回 `400`，`{"code":"UNKNOWN_STOCK_ID","unknownIds":[...]}`
- [x] 同一 `stockId` 出現兩次時回 `400`，`{"code":"DUPLICATE_STOCK_ID","duplicatedIds":[...]}`
- [x] `buyDate` 晚於今日或缺漏時回 `400`，`{"code":"INVALID_BUY_DATE","stockId":"<該檔>"}`
- [x] 已下市（`is_active = 0`）的股票可正常回測，不因下市被拒絕或被排除
- [x] 本端點不寫入 `stock_daily_price`、`stock`、`stock_sync_progress` 任何一張表：一次回測前後三張表的列數與內容完全不變
- [x] 本端點不建立 `stock_sync_progress` 列，也不與 `PRICE_BACKFILL`／`INDICATOR_REBUILD` 的併發鎖互斥：回補進行中呼叫回測正常回 `200`，不回 `409`

### 效能
- [x] 一份 50 檔的清單，其價格查詢次數不隨檔數線性增加（以查詢計數斷言，非以耗時推測），不得逐檔各發一次查詢

### 以進場日買進（上漲支撐 D+2）
- [x] 請求 `items[]` 以 `buyDate` 表示買進日；只帶 `signalDate`、缺 `buyDate` 的請求回 `400` `INVALID_BUY_DATE`
- [x] 以構造資料驗證買進錨定在 `buyDate`：D 收盤 100、D+1 收盤 102、D+2 收盤 104、D+3 開盤 110，送 `buyDate`＝D+2 時 `buyPrice` 為 104（不是 D 的 100），賣出窗口從 D+3 起，`sellDate` 不早於 D+3
- [x] 同一檔兩筆 `buyDate` 相同即為重複（`DUPLICATE_BACKTEST_ITEM`，`duplicatedItems` 以 `{stockId, buyDate}` 表示），與造成它們的策略或訊號日無關
- [x] 本端點不接受也不依賴策略代碼或訊號日：相同 `(stockId, buyDate)` 的請求，不論它來自哪個型態，回應完全相同

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

### Increment
- Status: DONE

- Files changed:
  - `develop/backend/src/main/java/com/stock/service/StrategyBacktestService.java` — `MAX_ITEMS` raised `2000` → `20000` (still deliberately decoupled from `StrategyScanService.MAX_STOCK_IDS`); uniqueness validation switched from a `stockId`-only check to a `(stockId, signalDate)`-pair check (`findDuplicateItems`, replacing `findDuplicates`); the per-request `stockIds` list passed to the batched price query is now de-duplicated (a stock can now legitimately appear in multiple `items`, and the series it's read into is keyed by `stockId` regardless — no reason to ask the DB for the same id twice).
  - `develop/backend/src/main/java/com/stock/dto/BacktestDuplicateItemDto.java` (new) — one `{stockId, signalDate}` pair, the element type of a `DUPLICATE_BACKTEST_ITEM` error's `duplicatedItems` array.
  - `develop/backend/src/main/java/com/stock/exception/DuplicateBacktestItemException.java` (new) — replaces `DuplicateStockIdException`, carries `List<BacktestDuplicateItemDto>`.
  - `develop/backend/src/main/java/com/stock/exception/DuplicateStockIdException.java` — deleted; nothing else referenced it.
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` — `duplicatedIds` (`List<String>`) field/constructor-param/getter/`duplicateStockId(...)` factory replaced with `duplicatedItems` (`List<BacktestDuplicateItemDto>`) and a `duplicateBacktestItem(...)` factory. Wire key changed from `duplicatedIds` to `duplicatedItems` accordingly.
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — handler for `DuplicateStockIdException`/`DUPLICATE_STOCK_ID` replaced with one for `DuplicateBacktestItemException`/`DUPLICATE_BACKTEST_ITEM`.
  - `develop/backend/src/test/java/com/stock/StrategyBacktestIntegrationTest.java` — updated the old single-stockId duplicate test to the new pair contract, updated the cap test to build an oversized list via repeated `signalDate`s on one synthetic id (since the cap now counts items, not distinct stocks) rather than needing 20001 distinct ids, added a 2000-items-is-legal sanity test, and added the "一檔多訊號日" test group (two-signal-dates-per-stock: independent per-item computation, independent sell windows, each counted once in totals, request-order preservation, no `buyDate` field in the response, and a same-stock/different-signalDate case that must NOT trigger the duplicate error).

- Design decisions:
  - **Duplicate detection now keys on a composite string (`stockId + "|" + signalDate`)** rather than a `Set<Pair<...>>` or nested-object equality — simplest correct approach given `BacktestItemRequestDto` has no `equals()`/`hashCode()` override and adding one purely for this internal check would be more machinery than the problem needs (KISS).
  - **`stockIds` passed to the batched query is de-duplicated** with a `LinkedHashSet`-backed loop before hitting `loadSeries` — this was implicitly true before (uniqueness was per-stockId, so the list was already distinct) but is no longer guaranteed now that one stock can appear in several items; de-duplicating here keeps the "bounded query count, no per-item query" performance property intact and avoids redundant `IN (...)` entries.
  - **`DuplicateStockIdException` deleted outright rather than deprecated** — grepped first to confirm nothing else in `main` or `test` referenced it; the class, its exception handler, and `ErrorResponse.duplicatedIds`/`duplicateStockId(...)` were all backtest-endpoint-only, so removing them is not a breaking change to any other endpoint.
  - **`BacktestDuplicateItemDto` has no explicit `@JsonCreator`** (unlike `ErrorResponse`, which needs one because it has multiple constructors) — it has exactly one constructor, so Jackson's implicit single-constructor detection (enabled by `spring-boot-starter-parent`'s default `-parameters` compiler flag plus the auto-registered `jackson-module-parameter-names`) handles both serialization and the test-side deserialization via `TestRestTemplate` without extra annotations, matching the existing style of other constructor-only DTOs in this codebase (e.g. `FailedItemDto`).

- Verification (real command output tails):
  - `mvn -f develop/backend/pom.xml -o compile` → `BUILD SUCCESS`.
  - `mvn -f develop/backend/pom.xml -o -Dtest=StrategyBacktestIntegrationTest test` → `Tests run: 37, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` (28 previously-existing + 9 new).
  - Full suite: `mvn -f develop/backend/pom.xml -o test` →
    ```
    [INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.707 s - in com.stock.StrategyBacktestIntegrationTest
    [INFO] Tests run: 106, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.288 s - in com.stock.StrategyScanIntegrationTest
    [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0 s - in com.stock.util.NormalizeUtilTest
    [INFO] Tests run: 389, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS
    ```
    389 = 380 baseline (351 + 29 unrelated growth since the previous execution result was recorded) + 9 new. No pre-existing test regressed.
  - Ran against the real MySQL database on `127.0.0.1:3306` (`RANDOM_PORT` `@SpringBootTest`, same as all other integration tests in this suite) — not mocked.
  - Did not start `spring-boot:run` — verification is entirely via the test suite's own embedded random-port context, so the already-running dev instance on port 8080 (if any) was left untouched.
  - `code-quality` skill self-review: checked null-safety of the new pair-key construction (`signalDate` can be `null` — string concatenation handles it safely, no NPE), verified validation order is unchanged (existence check still precedes the duplicate check, matching the spec's error-table order), verified `BacktestDuplicateItemDto`/`DuplicateBacktestItemException` follow existing immutable-DTO/exception conventions, and confirmed no other code references the deleted `DuplicateStockIdException`/`DUPLICATE_STOCK_ID`/`duplicatedIds` (grepped `main` and `test`). No Critical/Important findings; nothing left unfixed.

- New acceptance criteria boxes covered by this increment's tests (not checked off myself, per instructions): all `- [ ]` items under "契約與驗證" (2001/20001-item cap, 2000-item legality, 532-item full-market list) and all of "一檔多訊號日".

### Increment
- Status: DONE

- Files changed:
  - `develop/backend/src/main/java/com/stock/dto/BacktestItemRequestDto.java` — field renamed `signalDate` → `buyDate` (getter/setter renamed to match); javadoc rewritten to describe the "buyDate is supplied by the caller, not recomputed" contract instead of the old signal-date framing.
  - `develop/backend/src/main/java/com/stock/dto/BacktestResultItemDto.java` — field renamed `signalDate` → `buyDate`; javadoc updated to state the response never carries a `signalDate` field.
  - `develop/backend/src/main/java/com/stock/dto/BacktestDuplicateItemDto.java` — field renamed `signalDate` → `buyDate` (constructor param + getter); javadoc updated to "一筆＝一個買進日".
  - `develop/backend/src/main/java/com/stock/exception/DuplicateBacktestItemException.java` — javadoc/exception message updated from "(stockId, signalDate)" to "(stockId, buyDate)"; no field/behavior change (it already only carried the DTO list, whose element type was renamed above).
  - `develop/backend/src/main/java/com/stock/exception/InvalidSignalDateException.java` — deleted; grepped `main`/`test` first to confirm nothing but the backtest service and its own handler referenced it.
  - `develop/backend/src/main/java/com/stock/exception/InvalidBuyDateException.java` (new) — same shape as the file it replaces (`stockId` field, same message pattern), renamed.
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` — `invalidSignalDate(String)` factory renamed to `invalidBuyDate(String)`, wire code changed `INVALID_SIGNAL_DATE` → `INVALID_BUY_DATE`; the `duplicatedItems` field's comment updated to say `{stockId, buyDate}` instead of `{stockId, signalDate}` (the field itself, its JSON key, and its element type were already correct — only the comment referenced the old name).
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — handler renamed `handleInvalidSignalDate`/`InvalidSignalDateException` → `handleInvalidBuyDate`/`InvalidBuyDateException`, now calls `ErrorResponse.invalidBuyDate(...)`.
  - `develop/backend/src/main/java/com/stock/service/StrategyBacktestService.java` — every internal reference to "signal date" renamed to "buy date": `minSignalDate` → `minBuyDate`, `findBuyPrice`/`findSellPick` parameters renamed, `item.getSignalDate()` → `item.getBuyDate()`, `resultItem.setSignalDate(...)` → `setBuyDate(...)`, `findDuplicateItems`'s composite key and `InvalidSignalDateException` → `InvalidBuyDateException`. Class-level and `MAX_ITEMS` javadoc rewritten to state the buy date is supplied by the caller (RISING_SUPPORT's D+2, everyone else's signal date, per strategy-scan) and is never recomputed here, and that this endpoint accepts neither a strategy code nor a signal date. No computational logic changed — the anchor date was always what the caller sent in that field; only its name, and the fact that it is now also returned in the response (see below), changed.
  - `develop/backend/src/test/java/com/stock/StrategyBacktestIntegrationTest.java` — renamed `signalDate`/`SignalDate` identifiers throughout (variables, JSON field accesses, helper method params, most test method names) to `buyDate`/`BuyDate`; inverted `responseItems_doNotContainBuyDateField` (which asserted the OLD contract's absence of `buyDate`) into `responseItems_containBuyDateField_neverSignalDate` (asserts the NEW contract: `buyDate` present, `signalDate` absent); renamed `signalDateAfterToday_returns400InvalidSignalDate_withStockId` → `buyDateAfterToday_returns400InvalidBuyDate_withStockId` and its expected code to `INVALID_BUY_DATE`; added 4 new tests under a new `### 以進場日買進（上漲支撐 D+2）` block: `signalDateOnlyRequest_missingBuyDate_returns400InvalidBuyDate`, `buyAnchoredOnBuyDate_notEarlierSignalDay_risingSupportD2Fixture` (the D/D+1/D+2/D+3 fixture from the spec), `duplicateBuyDate_ignoresOriginatingStrategyOrSignalDate`, and `sameStockAndBuyDate_identicalResponse_regardlessOfOriginatingStrategyOrSignalDate`.

- Design decisions:
  - **This increment is a rename, not a behavior change.** The service already anchored every computation on whatever date the request sent in that one field — nothing about *which* date gets used as the buy anchor moved. What changed is the field's name (`signalDate` → `buyDate`), the guarantee that it is the caller's responsibility to have already resolved D+2 for RISING_SUPPORT (documented, not enforced — this endpoint has no notion of strategies to enforce it against), the error code for a missing/future date, and the uniqueness/duplicate-reporting key's label.
  - **New tests that need to send a `signalDate` or `strategyCode` key use a raw `Map<String, Object>` body (`rawBody(...)` helper), not `BacktestItemRequestDto`.** The DTO deliberately has no property for either field (per "本端點不接受也不依賴策略代碼或訊號日"), so the only way to prove the endpoint actually ignores them (rather than merely never being asked to accept them) is to send them as raw, untyped JSON and confirm they have no effect. Confirmed via `mvn compile`/manual reasoning that Spring Boot's default (auto-configured) Jackson `ObjectMapper` has `FAIL_ON_UNKNOWN_PROPERTIES` disabled — no explicit test-only Jackson config was needed for the extra keys to be silently dropped rather than 400ing as malformed JSON.
  - **The D+2 fixture test asserts `sellDate` by parsing it into a `LocalDate` and calling `isBefore`**, not by string comparison — a date-typed assertion is what "not earlier than D+3" actually means; string comparison would only coincidentally agree with it for ISO-8601-formatted dates in the same era, and reads less directly as the date-ordering claim the spec makes.
  - **"Identical response regardless of origin" is asserted via `JsonNode` structural equality** (`assertEquals(itemA, itemB)` on parsed `items[0]` nodes) rather than field-by-field, since the spec's claim is "the item", not "these five fields of the item" — a structural diff would also catch a future field this test didn't anticipate needing to check.
  - Did not touch `StrategyScanService`, the pattern detectors, or `StrategyHitDto` — their own `buyDate`/`signalDate` fields are a separate, already-shipped increment and out of this spec's scope boundary.

- Verification (real command output tails):
  - `mvn -f develop/backend/pom.xml -o compile` → `BUILD SUCCESS`.
  - `mvn -f develop/backend/pom.xml -o -Dtest=StrategyBacktestIntegrationTest test` → `Tests run: 45, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` (41 pre-existing, renamed in place, + 4 new).
  - Full suite: `mvn -f develop/backend/pom.xml -o test` →
    ```
    [INFO] Tests run: 45, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.214 s - in com.stock.StrategyBacktestIntegrationTest
    [INFO] Tests run: 113, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.464 s - in com.stock.StrategyScanIntegrationTest
    [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0 s - in com.stock.util.NormalizeUtilTest
    [INFO] Tests run: 404, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS
    ```
    404 = 400 baseline (unrelated growth elsewhere since the prior execution result) + 4 new. No pre-existing test regressed; a `simulated connection failure` stack trace visible in the raw log belongs to `StockUniverseImportIntegrationTest`'s own deliberate error-path test (18/18 passing), not a real failure.
  - Ran against the real MySQL database on `127.0.0.1:3306` (`RANDOM_PORT` `@SpringBootTest`, same as all other integration tests in this suite) — not mocked.
  - Did not start `spring-boot:run` — verification is entirely via the test suite's own embedded random-port context.
  - `code-quality` skill self-review: confirmed this is a pure rename with no computation change (buy-anchor logic, rounding, batching, and null/zero-price handling are byte-for-byte the same as before, just re-labeled); verified null-safety of the renamed `buyDate == null` guard (unchanged behavior, renamed variable only); grepped `main` and `test` to confirm no stray `signalDate`/`SignalDate`/`INVALID_SIGNAL_DATE` reference survives in backtest-owned files (the one remaining hit, a javadoc line in `BacktestResultItemDto` stating the response *never* carries `signalDate`, is intentional); confirmed the scan endpoint's own `signalDate`/`buyDate` fields (`StrategyHitDto`, `StrategyScanService`, the pattern detectors) were left untouched, per the stated scope boundary. No Critical/Important findings; nothing left unfixed.

- Acceptance criteria this increment's tests newly cover (not checked off myself, per instructions): all 17 `- [ ]` boxes that were rewritten from `signalDate` to `buyDate` across "回測規則", "無法回測的標的", "一檔多買進日" (nee "一檔多訊號日"), and the new `### 以進場日買進（上漲支撐 D+2）` block.

- Anything NOT independently verified, and why:
  - No manual/live-server check via `spring-boot:run` — verification is entirely through the integration test suite against the real test database, consistent with every prior execution result for this spec.
