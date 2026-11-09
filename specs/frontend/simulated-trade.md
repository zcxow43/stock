---
status: done
title: "模擬交易分頁"
requirement: "在「動態」旁新增第四個分頁「模擬交易」：輸入股票代號與買進日（預設為上一次收盤日、可往前改）即加入一筆模擬持股（買進價為該檔該日收盤、固定 1 張，後兩者不可修改），表格逐筆列出以最新收盤價計算的未實現損益與報酬率（已扣買進手續費與以現價估的賣出手續費、證交稅），並顯示總成本、總未實現損益、總報酬率；每筆可刪除；資料存在後端，重新整理後仍在；摘要列多一格可選的「目標結算日」，選了之後表格的現價四欄改為以買進日至該日之間最高價賣出的賣出日／賣出價／結算損益／報酬率，三個總計改為結算總計，可「清除」回到現價（2026-11-01，使用者原文「模擬交易多一個日期可以選, 目標結算日, 所有股票會以那天以前的最高價做賣出」）；持股也可由策略分頁命中彙總表的「加入模擬」加入（見 `specs/frontend/strategy.md`），本頁照常列出；按「刪除」成功後只在畫面上就地移除那一列、不重新取得列表，「共 N 筆」與三個總計依剩下的列以後端同一套公式在前端重算（2026-11-01，使用者原文「點擊刪除, 不要重新搜尋畫面, UI 刪除就好」）；選的目標結算日不是交易日時，日期輸入改顯示後端換成的最近交易日（回應的 `settleDate`），之後的重新取得都帶這個交易日（2026-11-09，使用者原文「目標結算日 應為距離這天最近的一天交易日」）"
depends_on: [stock-list]
---

# 模擬交易分頁 — Frontend Spec

## Overview

`/stocks` 的**第四個頁籤**，排在「動態」右邊（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`，第二個見 `specs/frontend/strategy.md`，第三個見 `specs/frontend/momentum.md`）。回答一個問題：**「如果我最近買了這幾檔各一張，現在賺賠多少？」**

使用者輸入代號與買進日（預設為上一次收盤日），或在策略分頁命中彙總表按「加入模擬」（以該筆的買進日與買進價加入，見 `specs/frontend/strategy.md`）；買進價與張數由後端決定（見 `specs/backend/simulated-trade.md`），畫面上**不提供修改**。持股存在後端，重新整理、換瀏覽器都還在。

路由：`/stocks?tab=simulated`。

**本頁呈現的是使用者自己輸入的假設部位，不是真實帳戶、也不是買賣建議。** 文案一律以「模擬／未實現損益」表述，不得出現「建議買進」「推薦」「可進場」等措辭。

## Requirements

### 頁面結構

由上而下：

1. **加入列** — 一格股票代號輸入 + 一格買進日 + 「加入」主要按鈕 + 說明文字。
2. **摘要列** — 「共 N 筆」、「目標結算日」日期輸入與「清除」按鈕、三個總計（總成本、總未實現損益、總報酬率；設了目標結算日時為三個結算總計），另有一行費率說明。
3. **持股表格** — 一列一筆持股，最右一欄為刪除。

**頁面層級的「只看上市普通股」不影響本頁**：本頁沒有母體篩選這件事——使用者指名哪一檔就是哪一檔，包含 ETF 與已下市股票。切換那個勾選框時本頁不重新查詢（`specs/frontend/stock-list.md` 的「各分頁如何套用」已記上這一條）。

### 加入列

| 元素 | 行為 |
|---|---|
| 代號輸入 | 單行文字，placeholder「輸入股票代號，例如 2330」；前後空白自動去除；按 Enter 等同按「加入」 |
| 買進日 | 日期輸入（`type="date"`），**預設為回應的 `defaultBuyDate`（上一次收盤日）**；`max` 為回應的 `asOfDate`（不能選未來）；在此按 Enter 同樣等同按「加入」 |
| 「加入」按鈕 | 主要按鈕；代號為空或買進日為空時 disabled；送出期間 disabled 並顯示「加入中…」 |
| 說明文字 | 輸入列下方一行次要文字：「以所選日期的收盤價買進 1 張（每筆 {lotSize} 股）」，股數取自回應的 `lotSize`，不在前端寫死 |

- **只有代號與買進日兩格輸入。** 買進價與張數不可輸入也不可修改：價格不是自己填的數字，而是所選日期實際的收盤價（要換價格就換日期）。畫面上因此不得出現任何看似可編輯的價格或張數欄位。
- **買進日預設為上一次收盤日**，值取自 `GET` 回應的 `defaultBuyDate`，不在前端以「今天減一天」推算——週末與連假都會讓那個推算落在沒有行情的日子。`defaultBuyDate` 為 `null`（資料庫尚無行情）時日期留空，「加入」維持 disabled。
- **加入成功後買進日維持不變**，只清空代號輸入：連續加入同一天的好幾檔是常見操作，每次都把日期重設回預設只會讓人重填。
- **加入成功**：清空輸入框、保持游標在輸入框（可以連續加入好幾檔），新的一筆出現在表格**最上方**（列表依買進日由新到舊，見下），並以列閃爍一次的方式提示（`#1D2A38` 淡入淡出一次，不改變列高）。
- **加入失敗**：輸入框下方顯示錯誤訊息，代號與買進日都**不清空**（使用者才能就地改掉打錯的代號或換一天）。錯誤碼對應文案見「錯誤處理」。

### 摘要列

| 元素 | 內容 |
|---|---|
| 「共 N 筆」 | 持股筆數（`items` 的筆數） |
| 總成本 | 回應的 `totalCost`，千分位、不帶小數 |
| 總未實現損益 | 回應的 `totalUnrealizedProfit`，千分位、不帶小數，套漲跌色 |
| 總報酬率 | 回應的 `totalReturnPercent`，兩位小數加 `%`，套漲跌色 |
| 費率說明 | 三個總計下方一行次要文字：「未實現損益已扣手續費 {feeRatePercent}%（買賣各一次）與證交稅 {taxRatePercent}%，賣出成本以現價估算」 |

- **每次 `GET` 之後，三個總計直接取回應的值，不在前端重算**。
- **唯一的例外是刪除成功之後**：刪除不重新取得列表（見「持股表格」），所以「共 N 筆」與三個總計改由前端依剩下的列重算，公式與 `specs/backend/simulated-trade.md` 的「彙總」與「目標結算日」結算彙總完全相同：
  - 總成本 ＝ 剩下各列 `cost` 的總和；總未實現損益 ＝ 剩下各列 `unrealizedProfit` 的總和；總報酬率 ＝ 總未實現損益 ÷ 總成本 × 100，四捨五入（half-up，遠離零）至小數第二位；剩下 0 筆時總報酬率為 `null`。
  - 結算模式下另算三個結算總計：只取 `settlement` 不為 `null` 的列；結算總成本 ＝ 那些列 `cost` 的總和；結算總損益 ＝ 那些列 `settlement.profit` 的總和；結算總報酬率 ＝ 結算總損益 ÷ 結算總成本 × 100，同樣 half-up 至小數第二位；沒有任何一列有結算時為 `null`。
  - 各列的 `cost`／`unrealizedProfit`／`settlement.profit` 都是後端算好的整數元，前端只做加總與一次除法，不重算任何一列的費用、成本或損益。
  - 重算後的值一直顯示到下一次 `GET`（加入、重試、改目標結算日、切回本頁）為止，下一次 `GET` 的回應值覆蓋它。
- **費率取自回應**，不在前端寫死；一行說明的用意與策略分頁相同：使用者會拿「(現價 − 買進價) × 1000」驗算，沒有這行就對不起來。
- `totalReturnPercent` 為 `null`（沒有任何持股）時，三個總計皆顯示弱化色的「—」，不顯示 `0`／`0%`。

### 目標結算日

回答「如果每一檔都在某一天之前、以那段期間的最高價賣掉，總共賺賠多少」。計算全在後端（見 `specs/backend/simulated-trade.md` 的「目標結算日」）：每筆以**買進日至目標結算日（含兩端）之間最大的最高價**賣出。

| 元素 | 行為 |
|---|---|
| 「目標結算日」標籤 + 日期輸入 | 位於摘要列「共 N 筆」之後、三個總計之前；`type="date"`，**預設空白**；`max` 為回應的 `asOfDate` |
| 「清除」次要按鈕 | 緊接日期輸入之後；日期為空時 disabled；按下清空日期 |

- **日期改變即重新取得列表**：`GET /api/simulated-trades?settleDate=<日期>`；清除（或把輸入清空）時改回不帶參數的 `GET`。不需要另按「套用」。
- **目標結算日一律是交易日**：使用者選的日期不是交易日時，後端換成距離它最近的交易日（規則見 `specs/backend/simulated-trade.md` 的「目標結算日」），回應的 `settleDate` 就是那一天。`GET` 成功後，**日期輸入的值改為回應的 `settleDate`**——例如選了週日 2026-10-18，輸入框隨即顯示 2026-10-19。前端不自己判斷哪天是交易日，只採用回應的值。
- **加入、「重試」之後的重新取得沿用目前的目標結算日**——也就是輸入框裡已換成的交易日；刪除不重新取得，目標結算日輸入維持原值：結算模式下加入一筆，新的一筆直接以同一個目標結算日結算。
- **目標結算日只存在本頁的畫面狀態**：不寫入網址、不寫入 `localStorage`；切到別的分頁再切回、或重新整理後回到空白（現價模式）。
- **空白時（現價模式）本頁與過去完全相同。**

設了目標結算日時（結算模式），畫面改顯示回應的結算欄位（三個結算總計在刪除後依「摘要列」一節的公式就地重算，其餘欄位一律取自回應）：

| 位置 | 現價模式 | 結算模式 |
|---|---|---|
| 表格第 4 欄 | 現價日 `currentDate` | 賣出日 `settlement.sellDate` |
| 表格第 5 欄 | 現價 `currentPrice` | 賣出價 `settlement.sellPrice`，兩位小數 |
| 表格第 7 欄 | 未實現損益 `unrealizedProfit` | 結算損益 `settlement.profit`，千分位、不帶小數，套漲跌色 |
| 表格第 8 欄 | 報酬率 `returnPercent` | 報酬率 `settlement.returnPercent`，兩位小數加 `%`，套漲跌色 |
| 總成本 | `totalCost` | `settledTotalCost` |
| 第二個總計 | 「總未實現損益」`totalUnrealizedProfit` | 「總結算損益」`settledTotalProfit` |
| 總報酬率 | `totalReturnPercent` | `settledTotalReturnPercent` |
| 費率說明 | 「未實現損益已扣手續費 {feeRatePercent}%（買賣各一次）與證交稅 {taxRatePercent}%，賣出成本以現價估算」 | 「結算損益已扣手續費 {feeRatePercent}%（買賣各一次）與證交稅 {taxRatePercent}%，每筆以買進日至 {settleDate} 之間的最高價賣出」 |

- **欄數與欄序不變**，只換表頭文字與內容：兩種模式的表格同寬，切換時欄位不跳動。
- **`settlement` 為 `null` 的筆**（買進日晚於目標結算日）：第 4、5、7、8 欄顯示弱化色「—」，其餘欄照常；它不計入三個結算總計（後端已排除）。這種筆至少一筆時，費率說明下方多一行次要文字「另 K 筆買進日晚於目標結算日，未計入」，K 為 `settlement` 為 `null` 的筆數。
- `settledTotalReturnPercent` 為 `null`（沒有任何一筆有結算）時，三個總計皆顯示弱化色「—」。
- 結算模式下「現價日不等於 `asOfDate` 時以次要文字色顯示」的規則不適用於賣出日：賣出日本來就早於今日，一律為主要文字色。
- `400 INVALID_SETTLE_DATE` → 日期輸入下方「目標結算日不能晚於今日」，表格保留上一次成功的資料。

### 持股表格

| 欄位 | 內容 | 對齊 |
|---|---|---|
| 代號 / 名稱 | `stockId` `stockName` | 靠左 |
| 買進日 | `buyDate` | 靠左 |
| 買進價 | `buyPrice`，兩位小數 | 靠右 |
| 現價日 | `currentDate` | 靠左 |
| 現價 | `currentPrice`，兩位小數 | 靠右 |
| 成本 | `cost`，千分位、不帶小數 | 靠右 |
| 未實現損益 | `unrealizedProfit`，千分位、不帶小數，套漲跌色 | 靠右 |
| 報酬率 | `returnPercent`，兩位小數加 `%`，套漲跌色 | 靠右 |
| 刪除 | 每列一顆「刪除」文字按鈕 | 靠右 |

上表為現價模式；結算模式下第 4、5、7、8 欄的表頭與內容依「目標結算日」一節替換。

- **買進日與現價日都要顯示。** 少了現價日，使用者看到週末或已下市的持股時無從判斷「這個現價是哪一天的」，會把上週的收盤當成今天的。
- **現價日不等於 `asOfDate` 時，現價日以次要文字色顯示**（其餘列為主要文字色）——那一格說的是「這個數字比今天舊」。
- **列依後端回的順序呈現**（買進日由新到舊、同日代號升冪），前端不重排，也**不提供排序**：本頁的列數是使用者自己加進來的量級，排序不是這個頁面要解決的問題。
- **列不可點選、不導向任何頁面**，游標維持預設箭頭；只有「刪除」按鈕可點。
- **漲跌色沿用全站規則**：未實現損益與報酬率為正值時 `#E04B45`、負值 `#16A75C`、`0` 為次要文字色 `#93A4B8`。成本與買進價、現價**不套漲跌色**，恆為主要文字色。**以實際渲染出的顏色為準**（表格儲存格的預設文字色若以更高權重宣告會把漲跌色蓋掉，類別掛對了畫面照樣是白字）。
- **刪除立即生效、不跳確認框**：加回來只要再打一次代號，成本極低；確認框在這種可逆操作上只是多一次點擊。
- **刪除成功後只在畫面上就地移除那一列，不重新取得列表**：不送 `GET`、不顯示「載入中…」、表格不清空再重畫，其餘列的位置、內容與目標結算日輸入都不變；「共 N 筆」與三個總計依「摘要列」一節的公式就地重算。剩下 0 筆時顯示「沒有任何持股」狀態。
- **送出中的那一列「刪除」按鈕 disabled**，避免同一列連按兩次送出兩個 `DELETE`。
- **刪除失敗時該列保留不動**，表格上方顯示錯誤訊息（見「錯誤處理」）。

### 畫面狀態

| 狀態 | 呈現 |
|---|---|
| 載入中（進入分頁、加入後重新取得、重試、改目標結算日；刪除不會進入此狀態） | 表格區顯示「載入中…」，加入列照常可用 |
| 沒有任何持股 | 表格不顯示，改以弱化色說明「尚無模擬持股，輸入股票代號加入第一筆」；摘要列的三個總計顯示「—」 |
| 有持股 | 摘要列 + 表格 |
| 載入失敗 | 錯誤訊息區塊 + 「重試」按鈕，保留上一次成功的資料不清空 |

### 錯誤處理

| 回應 | 畫面 |
|---|---|
| `400 INVALID_STOCK_ID` | 輸入框下方「請輸入股票代號」 |
| `400 UNKNOWN_STOCK_ID` | 輸入框下方「找不到代號 {代號}」 |
| `400 NO_PRICE_BEFORE_TODAY` | 輸入框下方「{代號} 沒有可用的收盤價，無法加入」 |
| `400 INVALID_BUY_DATE` | 輸入框下方「買進日不能晚於今日」 |
| `400 NO_PRICE_ON_BUY_DATE` | 輸入框下方「{代號} 在 {buyDate} 沒有收盤價（可能是假日或停牌），請換一天」 |
| `409 DUPLICATE_SIMULATED_TRADE` | 輸入框下方「{代號} 在 {buyDate} 已經加過了」 |
| `404 SIMULATED_TRADE_NOT_FOUND`（刪除時） | 表格上方「這筆持股已經不存在」；該列已不在後端（別人或另一個分頁刪掉了），因此與刪除成功相同，就地移除該列並重算「共 N 筆」與三個總計，不重新取得列表 |
| 其他失敗 | 「加入失敗，請稍後再試」／「刪除失敗，請稍後再試」（刪除失敗時該列保留不動） |

錯誤訊息在下一次成功的加入或刪除時清除。

## Implementation Details

### API 整合

| 時機 | 呼叫 |
|---|---|
| 進入分頁（含由其他分頁切回、重新整理） | `GET /api/simulated-trades` — 取 `items[]` 與三個總計、`feeRatePercent`／`taxRatePercent`／`asOfDate`／`defaultBuyDate`（後者為買進日輸入的預設值與 `max`；`asOfDate` 也是目標結算日輸入的 `max`） |
| 選了或改了「目標結算日」 | `GET /api/simulated-trades?settleDate=<日期>` — 取 `items[].settlement` 與 `settleDate`／`settledTotalCost`／`settledTotalProfit`／`settledTotalReturnPercent` 顯示結算模式；回應的 `settleDate`（換成的最近交易日）寫回日期輸入，其後加入、重試的重新取得都帶這個 `settleDate` |
| 按「清除」或把目標結算日清空 | `GET /api/simulated-trades` — 不帶 `settleDate`，回到現價模式 |
| 按「加入」或在任一輸入框按 Enter | `POST /api/simulated-trades` — body `{ stockId, buyDate }`（買進日一律送出，即使它還是預設值）；成功後**重新取得列表**（`GET`），讓三個總計與排序一律由後端決定；`201` 的回應體只用來確認系統選了哪一個買進日與買進價，不拿它自行插入列表 |
| 按某列的「刪除」 | `DELETE /api/simulated-trades/{id}` — 成功（`204`）或 `404` 後**不重新取得列表**：就地移除該列，「共 N 筆」與三個總計依「摘要列」一節的公式在前端重算 |

契約見 `specs/backend/simulated-trade.md`。**前端不自行計算任何一列的費用、成本、損益或報酬率**，全部取自回應。唯一在前端算的是刪除後的三個總計（與結算總計），只做加總與一次 half-up 至兩位小數的除法，公式與後端「彙總」完全相同，所以刪除後畫面上的總計等於下一次 `GET` 會回的值。

## Visual Style

沿用 `specs/frontend/strategy.md` 的同一份色盤（同一個頁面的不同分頁不得有兩套顏色）：

| 元素 | 色碼 |
|---|---|
| 頁面背景 | `#0F1620` |
| 面板／表格容器背景 | `#16202C` |
| 表頭背景 | `#1B2836` |
| 主要文字（表格內容、標題、代號名稱） | `#E6EDF5` |
| 次要文字（表頭、標籤、說明行、較舊的現價日） | `#93A4B8` |
| 弱化文字（空狀態說明、總計無值時的「—」） | `#6B7C90` |
| 未實現損益／報酬率（正／負／零） | `#E04B45` / `#16A75C` / `#93A4B8` |
| 成本、買進價、現價 | `#E6EDF5`（不套漲跌色） |
| 表格列 hover 背景 | `#1D2A38` |
| 代號輸入框與買進日輸入框背景／邊框／focus 邊框／placeholder | `#0F1620` / `#26333F` / `#3E8FD8` / `#6B7C90` |
| 「加入」按鈕背景／文字／hover 背景 | `#3E8FD8` / `#FFFFFF` / `#58A3E8` |
| 「加入」按鈕 disabled 背景／邊框／文字 | `#16202C` / `#26333F` / `#4A5866` |
| 「刪除」文字按鈕／hover | `#93A4B8` / `#E04B45` |
| 「刪除」文字按鈕 disabled（送出中） | `#4A5866`（與「加入」disabled 文字同值） |
| 錯誤訊息文字／背景／邊框 | `#F09A94` / `#3A1C1A` / `#8A3A34` |
| 加入成功時的列閃爍背景 | `#1D2A38`（與列 hover 同值） |
| 「目標結算日」標籤文字 | `#93A4B8`（與次要文字同值） |
| 目標結算日輸入框背景／文字／邊框／focus 邊框 | `#0F1620` / `#E6EDF5` / `#26333F` / `#3E8FD8`（與代號輸入框同值） |
| 「清除」次要按鈕背景／文字／邊框／hover 背景 | `#1B2836` / `#E6EDF5` / `#26333F` / `#223347`（與 `specs/frontend/strategy.md` 的次要按鈕同值） |
| 「清除」按鈕 disabled 背景／文字 | `#16202C` / `#4A5866`（與「加入」disabled 同值） |
| 結算損益／報酬率（正／負／零） | `#E04B45` / `#16A75C` / `#93A4B8`（與未實現損益同值） |
| 賣出日、賣出價 | `#E6EDF5` |
| 結算模式下 `settlement` 為 `null` 的「—」 | `#6B7C90` |
| 「另 K 筆買進日晚於目標結算日，未計入」 | `#93A4B8` |

以上皆為固定色碼，**不得取自任何會隨 `prefers-color-scheme` 改變的變數或 token**：本頁在深色與淺色系統偏好下呈現完全相同。

## Acceptance Criteria

### 分頁與加入
- [x] `/stocks` 頁籤列出現第四個頁籤「模擬交易」，排在「動態」右邊；點選後網址為 `/stocks?tab=simulated`，重新整理仍停在本頁
- [x] 進入分頁即呼叫一次 `GET /api/simulated-trades`；切到別的分頁再切回會重新取得
- [x] 加入列的輸入只有代號與買進日兩格，畫面上沒有任何買進價或張數的輸入；下方說明文字的股數取自回應的 `lotSize`（把 mock 的 `lotSize` 改為 `100` 時畫面隨之改變）
- [x] 代號為空時「加入」disabled；輸入 `2330` 後按鈕啟用，按 Enter 與按按鈕都送出 `POST /api/simulated-trades`（前後空白已去除）
- [x] 加入成功後代號輸入清空、游標留在代號輸入框，並重新呼叫 `GET`
- [x] 切換頁面層級的「只看上市普通股」時，本頁不重新查詢、內容不變

### 表格與數值
- [x] 表格欄位依序為代號 / 名稱、買進日、買進價、現價日、現價、成本、未實現損益、報酬率、刪除；每一格的值等於回應對應欄位（價格兩位小數、金額千分位不帶小數、報酬率兩位小數加 `%`）
- [x] 未實現損益與報酬率在瀏覽器中**實際渲染的文字色**：正值 `#E04B45`、負值 `#16A75C`、`0` 為 `#93A4B8`；成本與兩個價格為 `#E6EDF5`（以 computed color 斷言）
- [x] `currentDate` 不等於回應的 `asOfDate` 時，該列現價日以 `#93A4B8` 呈現；相等時為 `#E6EDF5`
- [x] 列依後端回的順序呈現，前端不重排；表頭不可點選排序；列本體不可點、游標為預設箭頭
- [x] 三個總計直接顯示回應的 `totalCost`／`totalUnrealizedProfit`／`totalReturnPercent`，前端未自行加總（以「回應的總計與各列相加不一致」的 mock 驗證畫面顯示的是回應值）
- [x] 總計下方顯示「未實現損益已扣手續費 0.1425%（買賣各一次）與證交稅 0.3%，賣出成本以現價估算」，數字取自回應（把 mock 的 `feeRatePercent` 改為 `0.1` 時畫面隨之改變）

### 刪除與狀態
- [x] 每列有「刪除」按鈕，點擊即送出 `DELETE /api/simulated-trades/{id}`（不跳確認框）
- [x] `items` 為空時不顯示表格，改顯示「尚無模擬持股，輸入股票代號加入第一筆」，三個總計顯示「—」（不是 `0`／`0%`）
- [x] 載入中顯示「載入中…」且加入列仍可用；`GET` 失敗時顯示錯誤與「重試」按鈕，且不清空上一次成功的資料
- [x] 錯誤文案：`INVALID_STOCK_ID` → 「請輸入股票代號」；`UNKNOWN_STOCK_ID` → 「找不到代號 9999」；`NO_PRICE_BEFORE_TODAY` → 「9999 在今日以前沒有可用的收盤價，無法加入」；`409 DUPLICATE_SIMULATED_TRADE` → 「2330 在 2026-09-18 已經加過了」；四者皆顯示在輸入框下方且**不清空輸入內容**
- [x] 刪除回 `404` 時顯示「這筆持股已經不存在」，該列就地移除、總計就地重算，`DELETE` 之後沒有任何 `GET`
- [x] 本頁不含「建議」「推薦」「可進場」等措辭
- [x] 所有顏色為 `## Visual Style` 的固定色碼，`prefers-color-scheme: dark` 與 `light` 下實際渲染色完全相同

### 指定買進日
- [x] 加入列有一格買進日輸入（`type="date"`），預設值等於回應的 `defaultBuyDate`、`max` 等於回應的 `asOfDate`；`defaultBuyDate` 為 `null` 時留空且「加入」disabled
- [x] 送出的 body 為 `{ stockId, buyDate }`，買進日即輸入框當下的值（沿用預設時也照樣帶出）；在買進日輸入框按 Enter 同樣送出
- [x] 把買進日改成更早的交易日再加入：該列的買進日與買進價為那一天的值（以 mock 回應驗證畫面顯示的是回應值）
- [x] 加入成功後買進日**維持不變**、只有代號被清空；加入失敗時兩格都不清空
- [x] 錯誤文案：`INVALID_BUY_DATE` → 「買進日不能晚於今日」；`NO_PRICE_ON_BUY_DATE` → 「2330 在 2026-09-13 沒有收盤價（可能是假日或停牌），請換一天」；皆顯示在輸入框下方
- [x] 買進日輸入框的背景／邊框／focus 邊框與代號輸入框同色碼，`prefers-color-scheme: dark` 與 `light` 下實際渲染色相同

### 目標結算日
- [x] 摘要列「共 N 筆」之後有「目標結算日」日期輸入（預設空白、`max` 等於回應的 `asOfDate`）與「清除」按鈕；日期為空時「清除」disabled
- [x] 選一個日期即送出 `GET /api/simulated-trades?settleDate=<該日期>`（不需另按按鈕）；按「清除」後日期清空並送出不帶 `settleDate` 的 `GET`，畫面回到現價模式
- [x] 結算模式下表頭依序為代號 / 名稱、買進日、買進價、賣出日、賣出價、成本、結算損益、報酬率、刪除；第 4、5、7、8 欄的值等於回應的 `settlement.sellDate`／`sellPrice`／`profit`／`returnPercent`
- [x] 結算模式下三個總計顯示 `settledTotalCost`／`settledTotalProfit`／`settledTotalReturnPercent`，第二個總計的標籤為「總結算損益」，費率說明為「結算損益已扣手續費 0.1425%（買賣各一次）與證交稅 0.3%，每筆以買進日至 {settleDate} 之間的最高價賣出」
- [x] `settlement` 為 `null` 的筆第 4、5、7、8 欄為弱化色「—」，並出現「另 K 筆買進日晚於目標結算日，未計入」；`settledTotalReturnPercent` 為 `null` 時三個總計皆為「—」
- [x] 結算模式下加入一筆後，重新取得的 `GET` 仍帶同一個 `settleDate`，目標結算日輸入維持原值
- [x] 結算損益與報酬率實際渲染的文字色：正 `#E04B45`、負 `#16A75C`、`0` 為 `#93A4B8`；目標結算日輸入框背景／邊框與代號輸入框相同；`prefers-color-scheme: dark` 與 `light` 下相同
- [x] 以真實資料驗證：在結算模式下，畫面上一筆的賣出價等於以 SQL 查出該檔在買進日至目標結算日之間的 `MAX(high_price)`
- [x] 由策略分頁「加入模擬」加入的持股出現在本頁表格，買進日與買進價等於策略分頁該筆顯示的值
- [x] 選一個非交易日（例如週日）後，日期輸入顯示回應的 `settleDate`（最近的交易日，非原選日期），費率說明中的 {settleDate} 也是這一天
- [x] 選一個交易日時，日期輸入維持所選日期不變
- [x] 選了非交易日、輸入已換成交易日之後再加入一筆，重新取得的 `GET` 帶的 `settleDate` 是換成的交易日（以真實瀏覽器的網路請求紀錄驗證）

### 刪除不重新取得
- [x] 按「刪除」成功（`204`）後，瀏覽器在 `DELETE /api/simulated-trades/{id}` 之後**沒有送出任何 `GET /api/simulated-trades`**（以真實瀏覽器的網路請求紀錄驗證）；畫面不出現「載入中…」，其餘列不變，只少了被刪的那一列
- [x] 現價模式下刪除一筆後，「共 N 筆」減 1；總成本／總未實現損益／總報酬率等於剩下各列 `cost`／`unrealizedProfit` 的總和與兩者之比 × 100（half-up 兩位小數），且等於刪除後重新整理頁面時 `GET` 回應的 `totalCost`／`totalUnrealizedProfit`／`totalReturnPercent`
- [x] 結算模式下刪除一筆後，目標結算日輸入維持原值、表格仍為結算模式；三個總計等於剩下有結算各列的 `cost`／`settlement.profit` 總和與兩者之比 × 100（half-up 兩位小數），且等於以同一個 `settleDate` 重新 `GET` 時回應的 `settledTotalCost`／`settledTotalProfit`／`settledTotalReturnPercent`；「另 K 筆…未計入」的 K 隨之更新
- [x] 刪到剩 0 筆時顯示「尚無模擬持股，輸入股票代號加入第一筆」，三個總計為「—」
- [x] `DELETE` 回 `500`（或網路錯誤）時該列仍在、總計不變，表格上方顯示「刪除失敗，請稍後再試」
- [x] 送出中的那一列「刪除」按鈕 disabled，渲染文字色 `#4A5866`

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/frontend/src/api/simulatedTrades.ts` (new) — `fetchSimulatedTrades` (`GET`), `createSimulatedTrade` (`POST`, body `{ stockId }` only), `deleteSimulatedTrade` (`DELETE`, 204), `SimulatedTradeItem`/`SimulatedTradeListResponse` types matching `specs/backend/simulated-trade.md`'s wire contract exactly, and a dedicated `SimulatedTradeApiError` carrying `code`/`stockId`/`unknownIds`/`buyDate`/`id` — kept separate from `api/stocks.ts`'s `ApiError` rather than extending it, since this endpoint's error bodies (`buyDate`, `id`) don't overlap with the other endpoints' `fields`/`strategy`/`param` shape and a shared class would just carry five always-partially-null fields for every caller.
  - `develop/frontend/src/pages/SimulatedTradeTab.tsx` (new) — the tab's full content: 加入列 (code input + button, Enter-to-submit, trims before sending), 摘要列 (3 totals + fee-rate line, all sourced directly from the response, never recomputed), 持股表格 (backend-ordered, no client-side sort, rows not clickable), per-row 刪除 with no confirm dialog, and the 4 畫面狀態 (loading/empty/success/error). Only mounted while `tab === 'simulated'` in `StockListPage.tsx` (see `specs/frontend/stock-list.md` Increment 6) — a plain mount effect then satisfies "進入分頁即呼叫一次 GET；切到別的分頁再切回會重新取得" without a bespoke visibility-triggered re-fetch. Takes no `commonStocksOnly` prop; this page has no population filter, so the page-level checkbox cannot affect it just by not being read.
  - `develop/frontend/src/pages/SimulatedTradeTab.css` (new) — literal hex straight from this spec's `## Visual Style` table (the same palette as `strategy.md`/`stock-list.md`). Reuses `StockListPage.css`'s shared `.sl-table`/`.sl-btn`/`.sl-up`/`.sl-down`/`.sl-flat`/`.sl-muted`/`.sl-empty`/`.sl-error`/`.sl-toast` classes rather than redefining them (colors match byte-for-byte with the shared table). New classes are scoped to this tab's own layout needs (加入列/摘要列/loading block/stale-date color/delete button/one-shot row-flash keyframe) and deliberately never declare `color` on `.sim-summary-value` — it inherits `#e6edf5` from `.stock-list-page`, so `.sl-up`/`.sl-down`/`.sl-flat` (each with an explicit `color`) always win regardless of stylesheet load order, the same cascade fix `stock-list.md` Increment 5 established for the other two tables.
  - `develop/frontend/src/pages/StockListPage.tsx` / `develop/frontend/src/__tests__/StockListPage.tabs.test.tsx` / `develop/frontend/src/__tests__/StockListPage.simulatedTab.test.tsx` — the tab-shell half of this work; see `specs/frontend/stock-list.md` Increment 6 for detail (these files are shared between the two specs' scopes by design, since the container and its fourth tab are dependent).
  - `develop/frontend/src/__tests__/SimulatedTradeTab.test.tsx` (new, 22 tests) — mount-fetch; add-row has only a code input (no date/price/quantity input anywhere); hint text reflects `lotSize` from the response; empty-input disables 加入; Enter and click both `POST` a trimmed body; success clears the input, keeps focus, refetches, and the new row lands on top; column order and per-field formatting; `sl-up`/`sl-down`/`sl-flat` applied by sign on 未實現損益/報酬率 only (never on cost/prices); stale-current-date coloring; rows not clickable/sortable; totals come from the response even when they don't equal the row sum; fee-rate line reflects `feeRatePercent`/`taxRatePercent`; 刪除 fires `DELETE` with no confirm dialog and refetches; empty state shows the placeholder and `—` for all three totals (not `0`/`0%`); loading state; first-load error + 重試; all four 加入 error codes' exact copy with the input preserved; 刪除 `404` copy; no forbidden wording.
- Design decisions:
  - **Panel un-mounts on tab switch instead of the other three tabs' `display:none`-but-mounted pattern.** This is the one deliberate departure from `stock-list.md`'s established container convention, made because this spec's own requirement ("切到別的分頁再切回會重新取得") is the opposite of the other three tabs' documented "不重新請求" — the container's job is to make the *right* thing happen per tab, not to make every tab behave identically. Checked against `design-patterns` before writing it: the idiomatic React answer to "this subtree's lifecycle should reset on a condition" is conditional mounting, not an imperative `key`-remount trick or a bespoke `IntersectionObserver`/visibility-change hook — a plain `{tab === 'simulated' ? <SimulatedTradeTab /> : null}` inside an always-present `display`-toggled wrapper div gives the fetch-on-every-visit behavior for free while keeping the wrapper's `data-testid`/`style` shape consistent with the other three panels for testing.
  - **Dedicated `SimulatedTradeApiError` class, not a reuse of `api/stocks.ts`'s `ApiError`.** The two error shapes only share `code`; this endpoint's `buyDate`/`id` fields have no counterpart in the other one's `fields`/`strategy`/`param`, and forcing them into one class would mean every caller carries fields it never uses. A small, focused error class per API module was judged simpler than one over-general shared class — checked against `design-patterns`' "prefer the smallest type that fits" guidance for this kind of value object.
  - **`mountedRef` + abort-controller-per-fetch, not a naive fire-and-forget.** Matches the exact StrictMode-safe pattern `stock-list.md` Increment 2 had to debug into existence (`mountedRef.current = true` set again inside the effect body, not just at declaration) — reused deliberately rather than rediscovering the same bug, since this component has the same "async work outlives a possible unmount" shape (now sharper here, since this tab actually unmounts on every tab switch, unlike the other three).
  - **On a failed reload, `data` is left untouched rather than cleared to `null`.** This is what makes "GET 失敗時...不清空上一次成功的資料" true as an invariant rather than just a UI label: if `data` were nulled, `items` would fall back to `[]` and the component would misrender the empty-state message instead of the error state.
- Verification:
  - `npx vitest run` (whole suite): 514/514 passing (492 pre-existing + 22 in `SimulatedTradeTab.test.tsx` — the other 4 new tests for this feature live in `StockListPage.simulatedTab.test.tsx`, counted under `stock-list.md`). The one flaky test named in this task's instructions (`StrategyTab.test.tsx`'s 200-stock-hint test) was not touched; it failed once under a whole-file run and passed on a clean rerun, consistent with the instructions' description of it as pre-existing flakiness, not something this change caused.
  - `npm run build` (`tsc -b && vite build`): clean.
  - Live-verified against the already-running backend (`:8080`) and Vite dev server (`:5173`, HMR only, neither process restarted) with a throwaway Playwright script (`playwright` was already a devDependency; script removed after use, `package.json`/`package-lock.json` untouched) covering, in one dark-scheme pass then one light-scheme pass: the fourth tab's position/URL/reload-persistence; empty state (3× `—`, not `0`/`0%`); adding a real `2330` (real `stock_daily_price` data, buy price = current price = 2026-09-18's close since today is a non-trading weekend) and seeing the row, fee-rate line, and input-clear-with-focus-retained; a duplicate add showing the 409 copy with the input preserved; an unknown code (`9999999`) showing the 400 copy; adding a second real stock (`2317`) and confirming it lands above `2330` (backend-ordered, newest first); delete removing exactly one row with no confirm dialog appearing; every body cell's left/right edges matching its header cell's and all cells in a row sharing one `top` (not just "no horizontal scroll"); zero `display:flex`/`grid` on any `td`/`th` in the table (computed-style scan); no forbidden wording; and computed colors for `sl-up`/`sl-down`/`sl-flat`/an uncolored cell/the page background, sampled via a throwaway probe row (since today's real data only produced a loss and a wash, not a gain, for the `sl-up` case) — all byte-identical between the emulated `dark` and `light` `colorScheme` contexts. Also confirmed toggling the page-level 「只看上市普通股」 checkbox fires zero `/api/simulated-trades` requests. 29/29 checks passed.
  - Every row the script created (`2330`, `2317`) was deleted again through the UI's own 刪除 button before the script exited; `SELECT COUNT(*) FROM simulated_trade` was confirmed `0` both before the script ran and after — the table was left exactly as found.
  - Reviewed against `code-quality` before reporting done: absence safety (`data?.items ?? []`, `err.unknownIds?.[0] ?? attemptedStockId` etc. throughout the error-mapping and rendering paths — nothing assumes a response field is present beyond what the backend contract guarantees), error handling (every `fetch`/`await` path has a `.catch`/`try`-`catch`, no floating promises — `void handleAdd()`/`void handleDelete(...)`/`void runFetch()` are the deliberate, explicit way this codebase marks an intentionally-unawaited promise, matching the convention already used in `MomentumTab.tsx`), resource lifecycle (the mount effect's cleanup aborts the in-flight `AbortController` and flips `mountedRef.current = false`; the one-shot row-flash `setTimeout` is cleared in its own effect's cleanup), and performance (no request-per-row loop — a single `GET` refresh after every add/delete, matching the spec's own "重新取得列表" instruction). No issues found beyond what's already described above.
- Notes: none left unfixed.

---
### Increment 1 — 2026-09-21

Implements the 6 previously-unchecked criteria under「指定買進日（本次新增）」: a `type="date"` 買進日 input next to the code input, defaulting from and bounded by the (already backend-implemented) `defaultBuyDate`/`asOfDate` fields on `GET`, always included in `POST`'s body, and its two new error codes' copy. The backend half (`specs/backend/simulated-trade.md` Increment 1) was already done and deployed on `:8080` before this increment started.

- Files changed:
  - `develop/frontend/src/api/simulatedTrades.ts` — added `defaultBuyDate: string | null` to `SimulatedTradeListResponse`; `createSimulatedTrade` now takes a required `buyDate` parameter and always sends `{ stockId, buyDate }` (previously `{ stockId }` only).
  - `develop/frontend/src/pages/SimulatedTradeTab.tsx` — new `buyDateValue` state plus a `buyDateInitializedRef` that sets it from `resp.defaultBuyDate ?? ''` exactly once, on the first successful `GET` — never re-synced on later refetches (after 加入/刪除/重試), which is what keeps "加入成功後買進日維持不變" true even though every add re-fetches the list. New `<input type="date">` (class `sim-add-input sim-date-input`, `aria-label="買進日"`, `max={data?.asOfDate}`) next to the code input, wired to the same `handleInputKeyDown` (Enter submits from either field). `canAdd` now also requires `buyDateValue !== ''`. `handleAdd` reads `buyDateValue` at submit time and passes it to `createSimulatedTrade`; on success only `inputValue` (the code field) is cleared, `buyDateValue` is left untouched; on failure neither is touched (unchanged from before). `addErrorMessage` gained `INVALID_BUY_DATE` → 「買進日不能晚於今日」 and `NO_PRICE_ON_BUY_DATE` → `` `${stockId} 在 ${buyDate} 沒有收盤價（可能是假日或停牌），請換一天` `` (both falling back to the attempted stockId/buyDate when the error body omits them, matching the existing pattern for `DUPLICATE_SIMULATED_TRADE`).
  - `develop/frontend/src/pages/SimulatedTradeTab.css` — added `.sim-date-input { width: 170px; }` only. Deliberately does **not** redeclare background/border/focus-border: the date input shares the literal `.sim-add-input` class (and therefore that exact rule) with the code input, so the two can never paint different colors regardless of `prefers-color-scheme` — the class *is* the guarantee, not a copy of the same four hex values into a second selector.
  - `develop/frontend/src/__tests__/SimulatedTradeTab.test.tsx` — `listResponse`/`emptyResponse` mock helpers now include `defaultBuyDate` (`emptyResponse` also accepts an `overrides` param, mirroring `listResponse`, so tests can set it to `null`); one pre-existing (already-`[x]`, otherwise-untouched) test's POST body assertion extended from `{ stockId: '2330' }` to `{ stockId: '2330', buyDate: '2026-09-17' }` — required because the wire contract itself now always includes `buyDate`, not a behavior change to what that test covers (button disable/enable, Enter-submits, trimming all unchanged); two new rows added to the existing table-driven 加入-error-copy test (`INVALID_BUY_DATE`, `NO_PRICE_ON_BUY_DATE`); 6 new tests under a new `// ---------- 指定買進日（本次新增） ----------` block covering each of the 6 unchecked criteria.
  - `develop/frontend/src/__tests__/StockListPage.simulatedTab.test.tsx` — its own local `simulatedResponse()` mock helper (this file's scope belongs to `stock-list.md`, but it constructs the same `SimulatedTradeListResponse` type) needed `defaultBuyDate` added purely to keep compiling against the now-required field; no test behavior in this file changed.
- Design decisions:
  - **`buyDateInitializedRef`, not deriving the default from `data` on every render.** A naive `value={buyDateValue || data?.defaultBuyDate || ''}` would look simpler but silently re-snaps the field back to the default the instant a refetch's `defaultBuyDate` differs from what the user typed (e.g. the market rolls to a new trading day between an add and its refetch) — the opposite of "加入成功後買進日維持不變". A one-shot ref initialized from the *first* successful `GET` only, decoupled from every later `data` change, was the smallest mechanism that actually satisfies that invariant; checked against `design-patterns` before writing it — this is a plain "initialize once" ref, not a state machine or a reducer, since there is exactly one transition (uninitialized → initialized) and it never reverses.
  - **Date input reuses the code input's CSS class rather than declaring its own copy of the four hex values.** Directly satisfies `.claude/rules/frontend.md`'s "same literal hex, never drifts" requirement by construction — there is only one place (`.sim-add-input` in `SimulatedTradeTab.css`) that could ever change those four colors, so the two inputs cannot desync the way two independently-declared rules could.
- Verification:
  - `npx vitest run src/__tests__/SimulatedTradeTab.test.tsx`: 30/30 passing (22 pre-existing + 2 new error-copy rows + 6 new 買進日 tests).
  - `npx vitest run` (whole suite): 522/522 passing (514 pre-existing + 8 new in `SimulatedTradeTab.test.tsx`). The `StrategyTab.test.tsx` 200-stock-hint flaky test named in the task instructions did not flake on this run.
  - `npm run build` (`tsc -b && vite build`): clean — confirms `StockListPage.simulatedTab.test.tsx`'s mock and every other caller of `SimulatedTradeListResponse`/`createSimulatedTrade` still typechecks against the widened contract.
  - `npm run lint` (`oxlint`): only two pre-existing warnings in unrelated files (`StrategyTab.test.tsx`/`StrategyTab.tsx`), neither touched by this increment.
  - Live-verified against the already-running backend (`:8080`, which already had this increment's backend half deployed) and Vite dev server (`:5173`, HMR only, neither restarted) with a throwaway Playwright script (removed after use) run once with `colorScheme: 'dark'` and once with `'light'`, 24 checks total, all passing: 買進日 input has a non-empty default value and a `max` attribute on load; its computed `background-color`/`border-top-color` are byte-identical to the code input's, and equal to `#0F1620`/`#26333F`; focusing it paints `border-top-color: #3E8FD8`; 加入 stays disabled with an empty code even though the date is prefilled; submitting via Enter in the date field fires `POST` with a `buyDate` string in the body; an unknown-stock-id attempt shows the `找不到代號` copy with both the code and date inputs retaining their values afterward.
  - Confirmed via `curl http://localhost:8080/api/simulated-trades` before and after the live-verification script that `simulated_trade` still contains exactly the one pre-existing real row (`2330`, `buyDate: 2026-09-18`) and nothing else — every POST the script sent used an invalid stock id (`9999999`) specifically so it would fail server-side and write nothing, since real inserts were unnecessary to prove the 6 criteria below.
  - Reviewed against `code-quality` before reporting done: absence safety (`resp.defaultBuyDate ?? ''`, `data?.asOfDate` for `max`, `err.buyDate ?? attemptedBuyDate` fallback — nothing assumes a field the wire contract doesn't guarantee), error handling (unchanged `try`/`catch`/`finally` shape around `handleAdd`, no new floating promises), resource lifecycle (no new subscriptions/timers introduced), performance (no new per-item requests — still one `GET` refresh per add/delete). No issues found.
- Criteria verified and how (all 6 previously-unchecked, now `[x]`):
  1. **買進日 input, `type="date"`, defaults to `defaultBuyDate`, `max` = `asOfDate`; `null` → empty + 加入 disabled** — unit tests `'買進日 defaults to defaultBuyDate with max = asOfDate'` and `'defaultBuyDate null leaves 買進日 empty and keeps 加入 disabled...'`; live-verified default value/`max` presence in both color schemes.
  2. **Body is `{ stockId, buyDate }` always, including when unchanged from the default; Enter in the date field submits** — unit test `'POST body always sends buyDate...'`; live-verified via `page.waitForResponse` capturing the actual `POST` body after pressing Enter in the date field.
  3. **Changing 買進日 to an earlier date adds a row whose buyDate/buyPrice reflect the mock response for that date** — unit test `'changing 買進日 to an earlier trading day adds a row...'`.
  4. **加入成功後買進日不變，只清空代號；失敗時兩格都不清空** — unit test `'加入成功後買進日維持不變、只清空代號；加入失敗時兩格都不清空'` (exercises the failure path first, then success, on the same rendered instance); live-verified the failure half (unknown stock id, both inputs retained).
  5. **`INVALID_BUY_DATE`/`NO_PRICE_ON_BUY_DATE` copy, both under the input** — added as two new rows to the existing table-driven 加入-error test.
  6. **買進日 input's background/border/focus-border match the code input's literal hex, unaffected by `prefers-color-scheme`** — unit test `'買進日輸入框與代號輸入框共用同一個背景／邊框／focus 邊框 CSS class...'` (class-identity assertion, per this suite's established jsdom-can't-paint-real-CSS limitation — see `StockListPage.cellColorCascade.test.ts`'s header comment); live-verified actual computed colors byte-identical between the two inputs and matching the spec's literal hex, in both `dark` and `light` emulated `colorScheme`.
- Notes: none left unfixed.

---
### Increment 2 — 2026-11-01

Implements the 「### 目標結算日」 criteria (all but the last, which needs the strategy-tab 「加入模擬」 button, not built yet; left `- [ ]`).

- Files changed:
  - `develop/frontend/src/api/simulatedTrades.ts` — `fetchSimulatedTrades(signal, settleDate?)` appends `?settleDate=` only when non-empty; `createSimulatedTrade(..., signal?, priceBasis?: 'CLOSE' | 'OPEN')` adds `priceBasis` to the body only when given (this tab still sends `{stockId, buyDate}`); added `settlement`, `settleDate`, `settled*` types.
  - `develop/frontend/src/pages/SimulatedTradeTab.tsx` — 目標結算日 label + date input (`max` = `asOfDate`) + 「清除」 in the summary row; change/clear refetches immediately; `settleDateRef` keeps add/delete/retry refetches on the current date; settle mode swaps headers 4/5/7/8, cells, totals, 「總結算損益」 label, fee note and the 「另 K 筆…未計入」 line; `settlement: null` rows show muted 「—」; `INVALID_SETTLE_DATE` shows 「目標結算日不能晚於今日」 and keeps the last table.
  - `develop/frontend/src/pages/SimulatedTradeTab.css` — `.sim-settle-item` only; the date input reuses `.sim-add-input` (identical colors by construction), 「清除」 reuses `.sl-btn`.
- Decisions: settle date is component state only (no URL/localStorage); settle mode is keyed on the response's `settleDate` plus a non-empty input so a stale response cannot flip the labels; totals taken from `settled*` response fields, no client math. No tests written or run, per project rule.
- Live evidence (backend 8081, Vite 5174, Playwright, dark and light `colorScheme`, same results):
  - Pick 2026-10-15 sent exactly `GET /api/simulated-trades?settleDate=2026-10-15`; 清除 sent `GET /api/simulated-trades` and emptied the input; 清除 disabled while empty; `max` = 2026-11-01.
  - Headers in settle mode: 代號 / 名稱|買進日|買進價|賣出日|賣出價|成本|結算損益|報酬率|刪除.
  - Real data: 2305 (buyDate 2026-09-08, OPEN) showed 賣出價 74.70, equal to `SELECT MAX(high_price) ... BETWEEN '2026-09-08' AND '2026-10-15'` = 74.70 (賣出日 2026-10-13).
  - 2330 (buyDate 2026-10-30) with settle 2026-10-15: four cells `—` in `#6B7C90`, note 「另 1 筆買進日晚於目標結算日，未計入」, totals from settled fields (33,998 / +40,372 / +118.75%).
  - Add 2317 and delete it while settle date 2026-10-30 was set: each refetch was `GET ...?settleDate=2026-10-30`; the input kept its value.
  - Computed colors: profit/return positive `rgb(224,75,69)`, negative `rgb(22,167,92)`; settle input bg/border `rgb(15,22,32)`/`rgb(38,51,63)` = code input; label `#93A4B8`; 清除 bg `#1B2836`. The zero case (`#93A4B8`) was not observed live (no zero-profit row); it uses the shared `sl-flat` class, same as 現價 mode.
  - Table geometry: every body cell's left/right equal its header's, one `top` per row, no `td` with non-`table-cell` display.
  - `npm run build` clean. Rows created (ids 420, 421, plus 2317 added by script) were all deleted; `GET /api/simulated-trades` returns `items: []`.
- Not verified: 「由策略分頁「加入模擬」…」 — depends on the strategy-tab button (next step).
- Increment 2 evidence (2026-11-01): 策略分頁 4526 東台 row (買進日 2026-10-20, 買進價 35.65) added via 「加入模擬」; 模擬交易分頁 showed 4526 with 買進日 2026-10-20 and 買進價 35.65.

### Increment 3 — 2026-11-01

- Requirement: 「點擊刪除, 不要重新搜尋畫面, UI 刪除就好」.
- Files:
  - `develop/frontend/src/pages/SimulatedTradeTab.tsx` — `handleDelete` no longer calls `runFetch()`. On `204` and on `404 SIMULATED_TRADE_NOT_FOUND` it calls `setData(prev => withoutItem(prev, id))`. New `withoutItem` removes the row and recomputes `totalCost`／`totalUnrealizedProfit`／`totalReturnPercent` and, when `settleDate` is set, `settledTotalCost`／`settledTotalProfit`／`settledTotalReturnPercent` from the remaining rows. New `returnPercentHalfUp(profit, cost)` does `profit ÷ cost × 100` half-up (away from zero) to 2 decimals in integer arithmetic. Other failures leave `data` untouched and show 「刪除失敗，請稍後再試」.
- Not changed: `SimulatedTradeTab.css` already had `.sim-del-btn:disabled { color: #4a5866 }`.
- Live verification (backend `:8081`, frontend `:5174`, real DB; test rows 438–442 created by `POST` and all deleted; the user's 8 rows 428, 430–436 untouched):
  - 現價模式, delete 2317 (id 439): browser network log shows `DELETE /api/simulated-trades/439 → 204` and no `GET` after it; 「載入中…」 never rendered. 共 11 → 10 筆; totals 4,431,101／+213,167／+4.81% → 4,191,260／+208,593／+4.98%; a fresh `GET` returned `10 / 4191260 / 208593 / 4.98`.
  - 結算模式 settleDate 2026-10-20, delete 2330 (id 438): only `DELETE /api/simulated-trades/438 → 204`, no `GET`; date input still `2026-10-20`; 共 10 → 9 筆; settled totals 3,927,886／+450,710／+11.47% → 1,484,409／+455,028／+30.65%; fresh `GET ?settleDate=2026-10-20` returned `1484409 / 455028 / 30.65`, 1 null settlement (「另 1 筆…未計入」 still shown).
  - `DELETE` mocked to `500`: row 2603 stays, totals unchanged, toast 「刪除失敗，請稍後再試」.
  - `404`: row 440 deleted by curl first, then UI 刪除 → `DELETE …/440 → 404`, toast 「這筆持股已經不存在」, row removed, no `GET`; the in-flight button was `disabled` with computed color `rgb(74, 88, 102)` (`#4A5866`).
  - 現價模式 delete of id 441: UI totals 1,484,409／+285,631／+19.24% equal fresh `GET` `1484409 / 285631 / 19.24`.
  - 0 筆: `GET` items filtered client-side to the one test row 442, then real `DELETE …/442 → 204`: table removed, 「尚無模擬持股，輸入股票代號加入第一筆」, three totals 「—」, 共 0 筆.


### Increment 4 — 2026-11-09
- Files changed: `develop/frontend/src/pages/SimulatedTradeTab.tsx` — in `runFetch`'s success handler, when a `settleDate` was requested and the response carries `settleDate`, write it into `settleDateRef` and `settleDateValue`. Setting state does not fire the input's `onChange`, so no extra GET.
- Verified with Playwright driving real Chrome on `http://localhost:5174/stocks?tab=simulated` against backend `:8081` (network log captured):
  - Pick 2026-10-25 (Sun): one `GET ?settleDate=2026-10-25`; input became 2026-10-23; fee note 「…至 2026-10-23 之間的最高價賣出」.
  - Pick 2026-11-08 (Sun): input became 2026-11-09; note shows 2026-11-09.
  - Pick 2026-10-22 (trading day): input stayed 2026-10-22; note shows 2026-10-22.
  - Each pick produced exactly one GET (no refetch after the rewrite).
  - After snap to 2026-10-23, add 2330: `POST /api/simulated-trades` then `GET /api/simulated-trades?settleDate=2026-10-23`; input stayed 2026-10-23.
- Cleanup: test row id 445 deleted (count 0); backend 8081 and Vite 5174 stopped. `npm run build` passes. No tests written or run.
