---
status: pending
title: "真實交易 API"
requirement: "真實交易與模擬交易完全對稱——同樣可加入、列出、刪除，同樣即時算出現價與未實現損益。唯一的差別是儲存位置：模擬交易寫資料庫的 simulated_trade 表，真實交易寫一份隨程式碼版控的 CSV 檔，由本模組讀寫。另一個差別是買進價與股數由呼叫端指定（模擬交易的買進價取當日收盤、股數固定 1,000），因為記錄的是使用者真正的成交價與成交量。不新增任何資料表。另有兩個逐筆的設定也存在同一份 CSV、因此可持久化：`excluded`（勾選後該筆不計入三個總計，但該筆自己的數字照常回報）與 `targetSellPrice`（使用者自填的目標賣價，只記錄與回報，不衍生任何計算）。"
depends_on: [simulated-trade, stock-catalog]
---

# 真實交易 API — Backend Spec

## Overview

記錄使用者**本人實際持有**的部位，並即時算出到今天為止的未實現損益。

**它與 `specs/backend/simulated-trade.md` 是同一個功能的兩份資料**：同樣的端點形狀、同樣的回應欄位、同樣的交易成本算法、同樣的排序規則。差別只有兩件事：

| | 模擬交易 | 真實交易 |
|---|---|---|
| 儲存位置 | 資料庫 `simulated_trade` 表 | 一份隨程式碼版控的 CSV 檔 |
| 買進價與股數 | 系統填入（該日收盤 × 固定 1,000 股） | **呼叫端指定** |

**為什麼存 CSV 而不是資料庫**：這是使用者的個人交易紀錄，筆數在數十筆的量級，而且他要能直接手工編輯、或把券商對帳截圖交給 AI 助理代填，再隨程式碼一起進版控帶著走。一張資料表換不到這些，只會讓紀錄綁在某一台機器的資料庫裡。

**不得新增任何資料表或資料庫欄位**（本次新增的 `excluded` 與 `targetSellPrice` 是那份 CSV 的欄位，不是資料表的欄位）。本模組唯一讀資料庫的地方是既有的 `stock`（取股票名稱）與 `stock_daily_price`（取現價），兩者都只讀。

**資料進到 CSV 的方式不屬於本模組**：使用者手工編輯、或由 AI 助理讀圖後寫入，都發生在程式之外。**不得為此建立任何上傳、OCR、圖片解析或匯入的端點。** 本模組只負責「讀這個檔、寫這個檔」，以及檔案內容不合格式時如何回報。

## Requirements

### 儲存：版控中的 CSV

路徑固定為 **`data/real-trades.csv`**（專案根目錄下的 `data/`），隨程式碼一起進版控。

放在專案根目錄而不是 `develop/backend/` 或 `develop/frontend/` 底下，是因為它既不是原始碼也不是打包進 jar 的資源——它是這台機器上的一份資料，後端要能在執行期讀它也寫它。放進 `src/main/resources` 的話，打包成 jar 之後就寫不進去了。

- **編碼 UTF-8（不含 BOM）**，換行 `LF`。
- **第一行是欄位名列**，內容必須逐字等於下列**兩者之一**：
  - `stockId,buyDate,buyPrice,shares,excluded,targetSellPrice` —— 現行格式
  - `stockId,buyDate,buyPrice,shares` —— 舊格式，**仍接受**；該檔每一行視同 `excluded` 為 `false`、`targetSellPrice` 為空
- 其後每一行是一筆**目前持有中**的部位，欄位以半形逗號分隔，**欄位數為 4、5 或 6 皆合法**——尾端兩欄是後來加上的，缺的視為預設（`excluded` 為 `false`、`targetSellPrice` 為空）。欄位值本身不含逗號與引號（六欄都是代號、日期、數字或 `true`／`false`），因此**不實作 CSV 引號跳脫規則**。
- 前後空白去除。完全空白的行略過；第一個非空白字元為 `#` 的行視為註解、略過。
- 只有欄位名列（無資料行）是合法狀態，`GET` 回空清單。
- 檔案不存在時，**`GET` 視同只有欄位名列**（回空清單，不是錯誤），`POST` 則建立該檔並寫入欄位名列與第一筆。

| 欄位 | 型別 | 規則 |
|---|---|---|
| `stockId` | 字串 | 股票代號。必填 |
| `buyDate` | 日期 | 實際成交日，`YYYY-MM-DD`。必填，不得晚於今日 |
| `buyPrice` | 數值 | **實際成交價**（每股），最多兩位小數，須大於 `0` |
| `shares` | 整數 | **股數，不是張數**（1 張 = 1,000 股）。須大於 `0` |
| `excluded` | 布林 | **`true` 表示該筆不計入三個總計**。`true`／`false`（不分大小寫），空值視為 `false`。選填 |
| `targetSellPrice` | 數值 \| 空 | 使用者自填的**目標賣價**（每股），須大於 `0`、最多兩位小數；空值表示未設定。選填 |

### `id` 是資料行的位置，不是存下來的值

CSV 沒有自增主鍵，所以**每一筆的 `id` 是它在檔案中的資料行序號（1 起算，欄位名列不計、被略過的空白行與註解行不計）**。刪除後其餘各筆的 `id` 會往前移，這是刻意的：另外維護一個 `id` 欄位會讓手工編輯多一份必須自己對齊的帳，而這個檔的價值就在於可以隨手改。

代價是 `id` **只在一次 `GET` → `DELETE` 的來回之內穩定**。因此：

- 畫面在每一次 `POST`／`DELETE` 成功後**一律重新取得列表**（與 `specs/frontend/simulated-trade.md` 既有的做法相同）。
- `DELETE` 的 `id` 超出當下的資料行數時回 `404`，不做任何猜測。
- 這是單人自用工具，沒有多人同時編輯的情境；真正的風險只有「開著舊頁面很久之後才按刪除」，而重新取得列表就把它收斂掉了。

### 同一檔同一天可以有多筆

**不做 `(stockId, buyDate)` 唯一性檢查，也沒有對應的 `409`。** 真實交易會分批成交——同一檔同一天成交兩次、價格不同，是兩筆不同的部位。模擬交易需要這個唯一鍵，是因為它的買進價由系統從收盤價推出來，同檔同日必然重複；真實交易的價格由使用者給，同檔同日並不代表重複輸入。

每一行各自是一筆、各自算損益，**不做任何合併或加權平均**——把它們併成一筆會讓平均成本掩蓋掉個別進場點的好壞。

### 不統計與目標賣價

兩個逐筆的設定，都存在同一份 CSV，所以重新整理、換瀏覽器、換機器都還在。

**`excluded`（不統計）**：為 `true` 時該筆**只從三個總計中被排除**——`totalCost`、`totalUnrealizedProfit`、`totalReturnPercent` 都不含它。該筆**仍出現在 `items` 中，且自己的 `buyFee`／`cost`／`currentDate`／`currentPrice`／`sellFee`／`sellTax`／`unrealizedProfit`／`returnPercent` 照常計算與回報**。筆數也不受影響：`items` 仍含該筆。

為什麼不是直接把它從 `items` 移掉：那樣使用者就看不到自己排除了什麼，也沒辦法再把它加回來。排除是一個**可逆的統計設定**，不是刪除。

**`targetSellPrice`（目標賣價）**：使用者自填的每股目標賣價，**只記錄與回報，不衍生任何計算**——不算目標損益、不算目標報酬率、不與現價比較、不影響任何總計、不影響排序。它是使用者寫給自己看的一個數字。

兩者都**不是新增持股時的輸入**：`POST` 的請求本體仍只有四個欄位，新寫入的一筆一律是 `excluded` 為 `false`、`targetSellPrice` 為空，之後再以 `PATCH` 改。

### 現價與未實現損益

**算法與 `specs/backend/simulated-trade.md` 的「現價與未實現損益」完全同一套，共用同一份實作，不另立一份定義。**

| 項目 | 規則 |
|---|---|
| 現價日 | 該檔 `trade_date <= 今日` 且 `close_price > 0` 的**最大** `trade_date` |
| 現價 | 該日的 `close_price` |
| 成本 | `買進價 × 股數 ＋ 買進手續費` |
| 未實現損益 | `現價 × 股數 − 賣出手續費 − 證交稅 − 成本` |
| 報酬率 | `未實現損益 ÷ 成本 × 100`，四捨五入至小數第二位 |

手續費率 `0.1425%`（買進與賣出各一次、不打折、不設最低）、證交稅 `0.3%`（只在賣出時），三項**各自獨立無條件捨去至整數元**。

- **現價日等於買進日時未實現損益必為負**（剛買進、只付出成本），這是正確結果，不得夾為 `0`。
- 現價日**可能早於今日**（假日、或該檔已停止交易），因此逐筆回報現價日。

### 該檔查不到現價時

某檔在 `stock_daily_price` 完全沒有 `close_price > 0` 的列時，該筆**無法計算**，但仍照常回報：

- `currentDate`／`currentPrice`／`sellFee`／`sellTax`／`unrealizedProfit`／`returnPercent` 皆為 `null`
- `buyFee` 與 `cost` **照常有值**——成本只需要買進價與股數，與現價無關
- 該筆（在 `excluded` 為 `false` 時）**計入** `totalCost`，**不計入** `totalUnrealizedProfit` 與 `totalReturnPercent`

`stockId` 不在 `stock` 主檔時，`stockName` 為 `null`，其餘處置與上一段相同。**不回 `404`**：檔案裡已經有這一筆了，因為主檔查不到就整個清單失敗，會讓使用者連自己記了什麼都看不到。

### 格式錯誤的行

CSV 是手工維護的，所以解析錯誤**逐行**回報，不讓整個清單失敗。

- 某一行欄位數不是 4、5 或 6、`buyDate` 非法或晚於今日、`buyPrice` 不是大於 `0` 且最多兩位小數的數值、`shares` 不是大於 `0` 的整數、`excluded` 既非空也不是 `true`／`false`、`targetSellPrice` 既非空也不是大於 `0` 且最多兩位小數的數值 → **略過該行**，其餘各行照常回報。
- 被略過的行列在回應的 `skippedLines` 中，每筆含**檔案中的實際行號**（含欄位名列，1 起算）與**原始內容**，好讓使用者直接跳到那一行去改。
- 被略過的行不佔 `id` 序號，也不計入任何總計。
- 欄位名列不是上述兩種合法名列之一 → 整個 `GET` 回 `500`，`{"code":"MALFORMED_TRADE_FILE"}`；`POST`／`DELETE` 同樣回 `500` 且**不寫入檔案**。這不是呼叫端的錯，而是伺服器上的資料檔壞了，靜默改寫它會弄丟使用者手寫的內容。

### 寫入必須是整檔改寫，且不得弄丟無法解析的行

`POST`、`PATCH` 與 `DELETE` 都以「讀進全部行 → 在記憶體改動 → 整檔寫回」完成。寫回時：

- **欄位名列一律寫成現行的六欄形式** `stockId,buyDate,buyPrice,shares,excluded,targetSellPrice`。讀進來的是舊的四欄名列時，就在這一次寫回時升級——使用者不必自己去改第一行。
- **各資料行保留原本已有欄位的字面內容**，只在欄位不足六欄時於尾端補上缺少的欄位（`excluded` 補 `false`，`targetSellPrice` 補空）。**不重新格式化使用者寫的數字**：`1140.00` 不會被寫成 `1140.0`。
- 被 `PATCH` 改動的那一筆，只有 `excluded`／`targetSellPrice` 兩欄寫入新值，前四欄字面不變。
- **註解行、空白行與被略過的格式錯誤行，全部原樣保留在原來的相對位置。** 使用者手寫的註解和一筆還沒改好的錯誤行，不能因為他新增了一筆別的部位就被清掉。
- 寫入採**先寫暫存檔再原子換名**，避免寫一半就中斷而留下截斷的檔案。

## Implementation Details

### API 契約

```
GET /api/real-trades
```

Response `200`：

```json
{
  "asOfDate": "2026-10-01",
  "feeRatePercent": 0.1425,
  "taxRatePercent": 0.3,
  "totalCost": 2283249,
  "totalUnrealizedProfit": 116087,
  "totalReturnPercent": 5.08,
  "items": [
    {
      "id": 1,
      "stockId": "2330",
      "stockName": "台積電",
      "buyDate": "2026-09-12",
      "buyPrice": 1140.00,
      "shares": 2000,
      "excluded": false,
      "targetSellPrice": 1250.00,
      "buyFee": 3249,
      "cost": 2283249,
      "currentDate": "2026-09-30",
      "currentPrice": 1205.00,
      "sellFee": 3434,
      "sellTax": 7230,
      "unrealizedProfit": 116087,
      "returnPercent": 5.08
    }
  ],
  "skippedLines": [
    { "lineNumber": 5, "content": "2454,2026-09-25,,1000" }
  ]
}
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `asOfDate` | date | 本次查詢的今日（台北日曆日） |
| `feeRatePercent` / `taxRatePercent` | number | 恆為 `0.1425` / `0.3`，供畫面說明扣了什麼 |
| `totalCost` | number | **`excluded` 為 `false` 的各筆** `cost` 的總和；無持股、或全部筆都被排除時為 `0` |
| `totalUnrealizedProfit` | number | **`excluded` 為 `false` 且有現價的各筆** `unrealizedProfit` 的總和；沒有這樣的筆時為 `0` |
| `totalReturnPercent` | number \| null | `totalUnrealizedProfit ÷（`excluded` 為 `false` 且有現價各筆的 cost 合計）× 100`，兩位小數；**無持股、或沒有任何一筆同時「有統計」且「有現價」時為 `null`** |
| `items[].id` | int | 資料行序號（1 起算），刪除時用 |
| `items[].stockName` | string \| null | 取自 `stock`；主檔查不到時為 `null` |
| `items[].buyDate` / `buyPrice` / `shares` | date / number / int | CSV 中的值，原樣回報 |
| `items[].excluded` | boolean | 該筆是否不計入三個總計；CSV 中的值，空值為 `false` |
| `items[].targetSellPrice` | number \| null | 使用者自填的目標賣價；未設定時為 `null`。**不參與任何計算** |
| `items[].buyFee` / `cost` | int | 元，整數；**一律有值** |
| `items[].currentDate` / `currentPrice` | date \| null / number \| null | 現價日與該日收盤價；查不到現價時為 `null` |
| `items[].sellFee` / `sellTax` / `unrealizedProfit` | int \| null | 元，整數，`unrealizedProfit` 可為負；查不到現價時為 `null` |
| `items[].returnPercent` | number \| null | 兩位小數，可為負；查不到現價時為 `null` |
| `skippedLines` | array | 格式錯誤而被略過的行；沒有時為空陣列 |
| `skippedLines[].lineNumber` | int | 檔案中的實際行號，含欄位名列，1 起算 |
| `skippedLines[].content` | string | 該行原始內容，原樣回報 |

**`totalReturnPercent` 的分母只含「有統計且有現價」的那幾筆**，與分子的涵蓋範圍一致。分母若含了算不出損益的筆、或含了被排除的筆，報酬率都會被無故稀釋。

`items` 依 `buyDate` **由新到舊**排序，同日依 `stockId` 升冪，再同則依 `id` 升冪（同檔同日多筆時保持檔案中的先後）。排序由本端點決定，畫面不重排。

**沒有任何持股時回 `200`**，`items` 與 `skippedLines` 為空陣列、兩個總額為 `0`、`totalReturnPercent` 為 `null`。空清單不是錯誤。

```
POST /api/real-trades
```

Request：

```json
{ "stockId": "2330", "buyDate": "2026-09-12", "buyPrice": 1140.00, "shares": 2000 }
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockId` | string | 是 | 股票代號；前後空白去除後比對，須存在於 `stock`（不篩 `is_active`） |
| `buyDate` | date | 是 | 買進日（台北日曆日）；不得晚於今日。**不要求該日在庫中有收盤價**——買進價由呼叫端給，系統不需要那天的行情 |
| `buyPrice` | number | 是 | 實際成交價，大於 `0`，最多兩位小數 |
| `shares` | int | 是 | 股數，大於 `0` 的整數 |

`excluded` 與 `targetSellPrice` **不是本端點的輸入**：請求本體給了也不採用，新寫入的一筆一律是 `false` 與空，要改就用 `PATCH`。

**四個欄位全部必填**，沒有任何一個有預設值。模擬交易可以只給 `stockId`，是因為買進日與買進價都能從行情推出來；真實交易的買進價與股數只有使用者知道，替他猜一個值等於寫進一筆假紀錄。

Response `201`：與 `GET` 的 `items[]` 單筆同一個形狀（含即時算出的現價與未實現損益），`id` 為它寫入後的資料行序號。這個回應體是「剛才建立了什麼」的回執；**畫面仍會在成功後重新取得列表**（排序、總計與其餘各筆的 `id` 一律由 `GET` 決定）。

```
PATCH /api/real-trades/{id}
```

改一筆的「不統計」與「目標賣價」。**只有這兩個欄位可改**——代號、買進日、買進價、股數不得由本端點修改（要改就刪掉重加，或直接編輯資料檔）。

Request（兩個欄位都是選填，**未出現的欄位不動**）：

```json
{ "excluded": true, "targetSellPrice": 1250.00 }
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `excluded` | boolean | 省略時不動；`true` 表示該筆不計入三個總計 |
| `targetSellPrice` | number \| null | 省略時不動；**明確給 `null` 表示清掉已設定的目標賣價**；給數值時須大於 `0` 且最多兩位小數 |

兩個欄位都省略時**不是錯誤**：回 `200` 與該筆現狀，且不改寫檔案。

Response `200`：與 `GET` 的 `items[]` 單筆同一個形狀（含即時算出的現價與未實現損益），`id` 不變。畫面仍會在成功後重新取得列表。

`id` 與 `DELETE` 的 `id` 同樣是資料行序號，因此同樣**只在一次 `GET` → `PATCH` 的來回之內穩定**；超出當下資料行數時回 `404`，處置與 `DELETE` 相同。

```
DELETE /api/real-trades/{id}
```

Response `204`，無內容。`id` 為 `GET` 回報的資料行序號。

驗證與錯誤：

| 情形 | 狀態碼 | 回應 |
|---|---|---|
| `stockId` 缺漏或去除空白後為空 | `400` | `{"code":"INVALID_STOCK_ID"}` |
| `stockId` 不存在於 `stock` | `400` | `{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}` |
| `buyDate` 缺漏、格式不合法或晚於今日 | `400` | `{"code":"INVALID_BUY_DATE","buyDate":"2099-01-01"}` |
| `buyPrice` 缺漏、不大於 `0`、或小數超過兩位 | `400` | `{"code":"INVALID_BUY_PRICE","buyPrice":0}` |
| `shares` 缺漏、非整數、或不大於 `0` | `400` | `{"code":"INVALID_SHARES","shares":0}` |
| `targetSellPrice` 給了數值但不大於 `0`、或小數超過兩位 | `400` | `{"code":"INVALID_TARGET_SELL_PRICE","targetSellPrice":0}` |
| `PATCH` 或 `DELETE` 的 `id` 不是正整數、或超出當下的資料行數 | `404` | `{"code":"REAL_TRADE_NOT_FOUND","id":99}` |
| CSV 的欄位名列不符規定 | `500` | `{"code":"MALFORMED_TRADE_FILE"}` |
| 請求本體無法解析（型別不符、JSON 格式錯誤） | `400` | `{"code":"INVALID_REQUEST_BODY"}` |

**新增時不檢查 `(stockId, buyDate)` 是否重複**，因此沒有 `409`。

### 處理流程

`GET`：讀檔 → 逐行解析（略過空白／註解，收集格式錯誤行）→ 以一次查詢批次取回目標代號的 `stockName` → 以一次查詢批次取回各檔的現價日與現價 → 逐筆算成本與損益 → 彙總三個總計 → 排序後組裝回應。

**兩次查詢都必須是批次的**，不得逐筆各查一次：筆數雖少，但逐筆往返在同一個系統裡已經有現成的批次做法（見 `specs/backend/simulated-trade.md`），沒有理由退回去。

`POST`：驗證 → 讀進全部行 → 附加一行到資料行末尾 → 整檔寫回（保留欄位名列、註解、空白行與略過行）→ 以新寫入的那一筆算出回執。

`PATCH`：驗證 → 讀進全部行 → 依 `id` 定位資料行 → 只改該行的 `excluded`／`targetSellPrice` 兩欄（欄位不足時補齊到六欄）→ 整檔寫回 → 以改動後的那一筆算出回執。

`DELETE`：讀進全部行 → 依 `id` 定位資料行 → 移除該行 → 整檔寫回（同樣保留其餘非資料行）。

## Acceptance Criteria

### 儲存與檔案格式
- [x] 資料讀寫的路徑為 `data/real-trades.csv`（專案根目錄），該檔進版控；`develop/frontend/src/data/real-trades.csv` 不再被任何程式讀取（於 `specs/frontend/real-trade.md` 同一次 `/dev` 執行中補齊：`develop/frontend/src/data/` 整個目錄已刪除，`develop/frontend/src` 內唯一出現 `real-trades.csv` 字樣的地方是 `RealTradeTab.tsx:14` 的錯誤訊息文案，非讀取）
- [x] 欄位名列逐字為 `stockId,buyDate,buyPrice,shares` 時正常解析（舊格式，仍接受）；改成兩種合法名列以外的內容時 `GET`／`POST`／`DELETE` 皆回 `500` `MALFORMED_TRADE_FILE`，且 `POST`／`DELETE` 未寫入檔案（以寫入前後的檔案位元組比對驗證）
- [x] 檔案不存在時 `GET` 回 `200` 空清單（不是 `404`／`500`）；接著 `POST` 一筆會建立該檔並寫入欄位名列與那一筆
- [x] 只有欄位名列時 `GET` 回 `200`，`items` 與 `skippedLines` 為空陣列、兩個總額為 `0`、`totalReturnPercent` 為 `null`
- [x] 空白行與以 `#` 開頭的註解行被略過，不進 `items`、不進 `skippedLines`、不佔 `id` 序號
- [x] `POST` 與 `DELETE` 之後，欄位名列、註解行、空白行與被略過的格式錯誤行**全部原樣保留在原來的相對位置**：以一個含註解行、空白行與一個壞行的檔案驗證，新增一筆後那三類行逐字不變
- [x] 寫入為先寫暫存檔再原子換名：寫入完成後目錄中不留任何暫存檔
- [x] 不新增任何資料表或欄位，本次不產生任何 DBA migration；只讀 `stock` 與 `stock_daily_price`

### `GET` 的計算與回應
- [x] 以買進價 `21.55`、股數 `1000`、現價 `23.10` 驗證：`buyFee` `30`、`sellFee` `32`、`sellTax` `69`、`cost` `21580`、`unrealizedProfit` `1419`、`returnPercent` `6.58`（三項成本各自獨立無條件捨去至整數元）
- [x] **與模擬交易共用同一份算法**：把同一組（買進價、股數、現價）分別建成一筆真實交易與一筆等值的模擬交易，兩個端點回的 `cost`／`unrealizedProfit`／`returnPercent` **逐位相同**
- [x] `shares` 解讀為**股數**不是張數：`shares` 為 `2000` 的一筆，成本以 2,000 股計算
- [x] `buyPrice` 原樣取自 CSV，**不**向行情索取該日收盤價、也不以收盤價覆蓋：以「CSV 寫 `21.55`、該日實際收盤不同」的情境驗證回的是 `21.55`
- [x] 現價日等於買進日時 `unrealizedProfit` 為負值，不得夾為 `0`
- [x] `items` 依 `buyDate` 由新到舊、同日依 `stockId` 升冪、再同依 `id` 升冪；同檔同日兩筆保持檔案中的先後
- [x] `feeRatePercent` 為 `0.1425`、`taxRatePercent` 為 `0.3`、`asOfDate` 為今日；回應**不含** `lotSize`（股數逐筆不同，沒有統一的每筆股數可報）與 `defaultBuyDate`（買進日的預設值就是今日，前端直接取同一個回應裡的 `asOfDate`，不需要第二個欄位）
- [x] `totalReturnPercent` 的分母只含**有統計且有現價**的那幾筆的 `cost`：以「兩筆有現價、一筆查不到現價」的資料驗證分母不含第三筆
- [x] 全部筆都查不到現價時 `totalUnrealizedProfit` 為 `0`、`totalReturnPercent` 為 `null`，`totalCost` 照常有值

### 查不到現價與查不到主檔
- [x] 某檔在 `stock_daily_price` 沒有任何 `close_price > 0` 的列時：`currentDate`／`currentPrice`／`sellFee`／`sellTax`／`unrealizedProfit`／`returnPercent` 皆為 `null`，`buyFee` 與 `cost` 照常有值，該筆（`excluded` 為 `false` 時）計入 `totalCost` 但不計入另兩個總計
- [x] `stockId` 不在 `stock` 主檔時 `stockName` 為 `null`，整個 `GET` 仍回 `200`（**不得**因此回 `404` 或讓清單失敗）

### 格式錯誤逐行回報
- [x] 欄位數不是 4、5 或 6、`buyDate` 非法或晚於今日、`buyPrice` 不大於 `0` 或小數超過兩位、`shares` 不是大於 `0` 的整數 → 該行被略過，其餘各行照常回報
- [x] `skippedLines[].lineNumber` 為**含欄位名列、1 起算**的實際檔案行號：以錯誤恰在第 3 行的檔案驗證回的是 `3`
- [x] `skippedLines[].content` 為該行原始內容，原樣回報
- [x] 被略過的行不佔 `id` 序號：第 2 行壞、第 3 行好時，第 3 行那一筆的 `id` 為 `1`

### `POST`
- [x] 四個欄位齊全且合法時回 `201`，形狀與 `GET` 的 `items[]` 單筆相同，`id` 為寫入後的資料行序號；再 `GET` 一次看得到該筆
- [x] **四個欄位全部必填**：分別缺 `stockId`／`buyDate`／`buyPrice`／`shares` 各回對應的 `400`，且**任一個缺漏都不會被補上預設值**
- [x] `buyDate` 不要求該日在庫中有收盤價：以一個該檔確定沒有行情的過去日期新增，回 `201` 而**不是** `NO_PRICE_ON_BUY_DATE`
- [x] `buyDate` 晚於今日 → `400` `INVALID_BUY_DATE`；`buyPrice` 為 `0`、負數或三位小數 → `400` `INVALID_BUY_PRICE`；`shares` 為 `0`、負數或小數 → `400` `INVALID_SHARES`
- [x] `stockId` 去除空白後為空 → `400` `INVALID_STOCK_ID`；不存在於 `stock` → `400` `UNKNOWN_STOCK_ID`；代號前後帶空白時去除後比對成功
- [x] **同檔同日可以有多筆**：對同一個 `(stockId, buyDate)` 以不同 `buyPrice` 連續 `POST` 兩次，兩次皆回 `201`，`GET` 回兩筆獨立的列，**沒有任何 `409`**
- [x] 請求本體型別不符（例如 `shares` 給字串）→ `400` `INVALID_REQUEST_BODY`，不回 `500`

### `DELETE`
- [x] 刪除存在的 `id` 回 `204`，再 `GET` 一次該筆消失、其餘各筆的 `id` 依新的行位置重新編號
- [x] `id` 不是正整數、為 `0`、或超出當下的資料行數 → `404` `REAL_TRADE_NOT_FOUND`
- [x] 刪除後檔案中的註解行與空白行仍在原來的相對位置

### 不統計（`excluded`）與目標賣價（`targetSellPrice`）
- [ ] 六欄名列 `stockId,buyDate,buyPrice,shares,excluded,targetSellPrice` 正常解析；舊的四欄名列也正常解析，且該檔各筆回 `excluded` 為 `false`、`targetSellPrice` 為 `null`
- [ ] 同一個檔案中欄位數 4、5、6 的三種資料行同時存在時皆正常解析：缺的尾欄各自補為 `excluded` `false`／`targetSellPrice` `null`，三筆都進 `items`、都不進 `skippedLines`
- [ ] `excluded` 接受 `true`／`false` 且不分大小寫（`TRUE`／`False` 皆可），空值視為 `false`；給 `yes`／`1`／`是` 等其他內容 → 該行進 `skippedLines` 並被略過
- [ ] `targetSellPrice` 空值回 `null`；給 `0`、負數或三位小數 → 該行進 `skippedLines` 並被略過
- [ ] `excluded` 為 `true` 的一筆**仍出現在 `items` 中**，且它自己的 `buyFee`／`cost`／`currentDate`／`currentPrice`／`sellFee`／`sellTax`／`unrealizedProfit`／`returnPercent` 全部照常有值（與它被設為 `false` 時逐位相同）
- [ ] `excluded` 為 `true` 的一筆**不計入** `totalCost`、`totalUnrealizedProfit`、`totalReturnPercent`：以三筆資料驗證——把其中一筆由 `false` 改為 `true` 後，`totalCost` 恰好減少該筆的 `cost`、`totalUnrealizedProfit` 恰好減少該筆的 `unrealizedProfit`、`totalReturnPercent` 等於剩下兩筆重算的值
- [ ] 全部筆都 `excluded` 為 `true` 時：`totalCost` 與 `totalUnrealizedProfit` 為 `0`、`totalReturnPercent` 為 `null`，`items` 仍含全部筆
- [ ] `targetSellPrice` **不影響任何計算**：對同一筆只改 `targetSellPrice`（含設為遠高於與遠低於現價的兩個值），`cost`／`unrealizedProfit`／`returnPercent`／三個總計／`items` 的排序全部逐位不變
- [ ] `PATCH /api/real-trades/{id}` 只給 `excluded` 時只改該欄、`targetSellPrice` 不動；只給 `targetSellPrice` 時只改該欄、`excluded` 不動；兩個都給時兩欄都改
- [ ] `PATCH` 明確給 `targetSellPrice: null` 會清掉已設定的目標賣價（再 `GET` 回 `null`，CSV 該欄為空）；**省略**該欄則維持原值
- [ ] `PATCH` 兩個欄位都省略（`{}`）回 `200` 與該筆現狀，且檔案位元組與呼叫前完全相同（未改寫）
- [ ] `PATCH` 回 `200`，形狀與 `GET` 的 `items[]` 單筆相同、`id` 不變；回應中的 `excluded`／`targetSellPrice` 即改動後的值
- [ ] `PATCH` 的 `targetSellPrice` 給 `0`、負數或三位小數 → `400` `INVALID_TARGET_SELL_PRICE`，且**檔案未被改寫**
- [ ] `PATCH` 的 `id` 不是正整數、為 `0`、或超出當下的資料行數 → `404` `REAL_TRADE_NOT_FOUND`，且檔案未被改寫
- [ ] `PATCH` 的本體型別不符（例如 `excluded` 給字串）→ `400` `INVALID_REQUEST_BODY`，不回 `500`
- [ ] `PATCH` **不能改**代號、買進日、買進價、股數：本體帶這四個欄位時它們一律不生效（`GET` 回的那四個值與呼叫前相同）
- [ ] `POST` **不接受** `excluded`／`targetSellPrice`：本體帶了這兩欄也一樣，新寫入的那一筆回 `excluded` `false`、`targetSellPrice` `null`
- [ ] **名列升級**：以舊的四欄名列的檔案做一次 `POST`（或 `PATCH`／`DELETE`）後，檔案第一行變成六欄名列；再 `GET` 一次結果不變
- [ ] **不重新格式化既有數字**：原本寫 `1140.00` 的一行，在任何一次整檔寫回之後該欄仍逐字為 `1140.00`（不得變成 `1140.0` 或 `1140`）
- [ ] 欄位不足六欄的既有資料行，在整檔寫回後於尾端補成六欄（`excluded` 為 `false`、`targetSellPrice` 為空），前四欄字面不變
- [ ] `PATCH` 之後，欄位名列、註解行、空白行與被略過的格式錯誤行**全部保留在原來的相對位置**（以含這三類行的檔案驗證）
- [ ] 本次仍**不新增任何資料表或資料庫欄位**、不產生任何 DBA migration；只讀 `stock` 與 `stock_daily_price`

---
## Execution Result
- Status: DONE（33 項中 32 項已對 live app + 真實資料庫驗證；1 項因屬前端範圍未勾）
- Files changed:
  - New: `data/real-trades.csv`（僅欄位名列）
  - New: `develop/backend/src/main/java/com/stock/controller/RealTradeController.java`
  - New: `.../service/RealTradeService.java`, `.../service/RealTradeCsvStore.java`（讀、解析、整檔改寫、暫存檔＋原子換名、保留原檔權限；路徑預設為往上找到含 `develop/backend/pom.xml` 的目錄下的 `data/real-trades.csv`，可用 `app.real-trade.csv-path` 覆寫）
  - New: `.../domain/RealTrade.java`
  - New DTO: `CreateRealTradeRequest`, `RealTradeItemDto`, `RealTradeResponseDto`, `SkippedLineDto`, `StrictNumberDeserializer`（buyPrice/shares 只接受 JSON 數字，字串→`INVALID_REQUEST_BODY`）
  - New exception: `MalformedTradeFileException`, `RealTradeNotFoundException`, `InvalidBuyPriceException`, `InvalidSharesException`
  - Changed: `dto/ErrorResponse.java`（新增 `buyPrice`/`shares` 欄位與三個 factory；舊 13 參數建構子委派給新的 15 參數建構子，既有 factory 不動）、`exception/GlobalExceptionHandler.java`（四個 handler）
- Notes:
  - 成本演算法直接呼叫與模擬交易共用的 `TradingCostCalculator`；`INVALID_BUY_DATE`／`INVALID_STOCK_ID`／`UNKNOWN_STOCK_ID` 重用模擬交易既有的 exception 與回應形狀。
  - 不新增資料表、不產生 DBA migration；只讀 `stock`、`stock_daily_price`。
  - Live 驗證用的夾具（`stock`/`stock_daily_price` 中的 `RT01`–`RT04`）與為了比對模擬交易而臨時建立的 `simulated_trade` 表（live DB 原本就沒有該表）都已在驗證後清除／刪除。
  - 已知未處理：`buyPrice` 沒有上限（如 `1e30` 會被寫入 CSV）；spec 未規定上限。
