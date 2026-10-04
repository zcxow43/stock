---
status: pending
title: "真實交易分頁"
requirement: "第五個分頁「真實交易」與模擬交易分頁完全對稱：同樣有加入列、同樣可逐筆刪除、同樣的表格欄位與三個總計。差別有兩個——加入列多兩格輸入（買進價、量），買進日預設為今日（回應的 `asOfDate`，台北日曆日）、其餘三格預設空白（模擬交易的買進日預設為上一次收盤日）；以及資料存在一份隨程式碼版控的 CSV 檔而非資料庫，但那是後端的事，本頁一律透過 API 存取，不自己讀檔。命中的股票名稱、現價、成本、未實現損益、報酬率與三個總計全部取自回應，每日隨行情刷新。另有兩個逐筆的設定：最左邊的「不統計」checkbox（勾選後該筆不計入三個總計，但該列自己的數字照常顯示）與「目標賣價」可輸入欄，兩者都持久化在後端的資料檔裡。"
depends_on: [stock-list, simulated-trade]
---

# 真實交易分頁 — Frontend Spec

## Overview

`/stocks` 的**第五個頁籤**，排在「模擬交易」右邊（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`，第二個見 `specs/frontend/strategy.md`，第三個見 `specs/frontend/momentum.md`，第四個見 `specs/frontend/simulated-trade.md`）。

它回答的問題與模擬交易不同：模擬交易問「如果我當時買了會怎樣」，本頁問**「我現在實際持有的部位，到今天為止賺賠多少」**。

**版面與行為與模擬交易分頁對稱**——同樣的加入列、同樣的逐筆刪除、同樣的表格與三個總計。只有兩處不同：

1. **加入列多兩格輸入：買進價與量。** 模擬交易的買進價由系統取當日收盤、股數固定 1 張；真實交易記的是使用者真正的成交價與成交量，只有他自己知道。
2. **買進日預設為今日，其餘三格預設空白。** 買進日的預設值取自回應的 `asOfDate`（今日，台北日曆日），**不在前端以瀏覽器時鐘推算**——同一條處置見 `specs/frontend/simulated-trade.md`，日期一律由後端的回應決定。代號、買進價、量沒有可猜的值，一律空白。模擬交易預設的是上一次收盤日，因為它需要一個有收盤價的日子；真實交易記的是使用者自己的成交日，而最常被記下的那一天就是當天。

表格另有兩個**逐筆可改、且會持久化**的設定：最左邊的「不統計」checkbox，與「目標賣價」輸入欄。兩者都只改那一筆，改完立刻送出並由後端寫進資料檔，所以重新整理、換瀏覽器都還在（見 `specs/backend/real-trade.md` 的 `PATCH /api/real-trades/{id}`）。

資料實際存在一份隨程式碼版控的 CSV 檔而不是資料庫（見 `specs/backend/real-trade.md`），但**那完全是後端的事**：本頁一律透過 API 存取，不讀檔、不匯入、不知道檔案在哪。**本頁沒有任何上傳、圖片解析或匯入的入口**——資料進到那個檔的另一條路徑（使用者手工編輯、或把券商截圖交給 AI 助理代填）發生在程式之外。

**頁面層級的「只看上市普通股」不影響本頁**：本頁沒有母體篩選這件事——使用者加進來哪一檔就是哪一檔，包含 ETF 與已下市股票。切換那個勾選框時本頁不重新查詢（與模擬交易分頁同一條處置，見 `specs/frontend/simulated-trade.md`；`specs/frontend/stock-list.md` 的「各分頁如何套用」已記上這一條）。

## Requirements

### 加入列

一列輸入，由左至右：代號、買進日、買進價、量、「加入」按鈕。其下一行說明文字。

| 元素 | 行為 |
|---|---|
| 代號輸入 | 單行文字，placeholder「輸入股票代號，例如 2330」；前後空白自動去除；**預設空白** |
| 買進日 | 日期輸入（`type="date"`），**預設為回應的 `asOfDate`（今日，台北日曆日）**；`max` 同為 `asOfDate`（不能選未來），故預設值即可選範圍的上界 |
| 買進價 | 數字輸入，placeholder「成交價」，**預設空白**；接受大於 `0` 的數值，最多兩位小數 |
| 量 | 數字輸入，placeholder「股數」，**預設空白**；接受大於 `0` 的整數 |
| 「加入」按鈕 | 主要按鈕；**四格任一為空即 disabled**；送出期間 disabled 並顯示「加入中…」 |
| 說明文字 | 輸入列下方一行次要文字：「買進價與股數為你實際的成交價與成交量；股數不是張數，1 張 = 1,000 股」 |

- 在任一格按 Enter 等同按「加入」（四格皆有值時才生效）。
- **只有買進日有預設值**；代號、買進價、量三格一律空白，「加入」在這三格也填完之前一直是 disabled（「四格任一為空即 disabled」的規則不變，買進日只是進入分頁時就已有值）。
- **買進日的預設值只在第一次 `GET` 成功時設定一次**，之後任何重新取得列表（加入、刪除、重試）都不再覆寫使用者當下的值——這是「加入成功後買進日維持不變」能成立的原因。第一次 `GET` 還沒回來、或回 `500` 時買進日留空。使用者手動清空後維持空白，不自動填回今日。
- **「量」的標籤與說明必須講明是股數。** 1 張 = 1,000 股，買 2 張要填 `2000`。用股數而不是張數，是因為真實交易會有零股，張數表達不了；但「量」這個字本身不帶單位，所以單位必須在說明文字裡講清楚，否則使用者會填 `2`。
- 加入成功後**只清空代號與買進價**，買進日與量維持不變——連續輸入同一天、同樣張數的幾筆是常見情形。加入失敗時四格都不清空。

### 表格

| 欄位 | 內容 | 對齊 |
|---|---|---|
| 不統計 | checkbox，勾選狀態即 `excluded`；勾選表示**不計入三個總計** | 置中 |
| 代號 / 名稱 | `stockId` `stockName` | 靠左 |
| 買進日 | `buyDate` | 靠左 |
| 買進價 | `buyPrice`，兩位小數 | 靠右 |
| 股數 | `shares`，千分位、不帶小數 | 靠右 |
| 現價日 | `currentDate` | 靠左 |
| 現價 | `currentPrice`，兩位小數 | 靠右 |
| 目標賣價 | 數字輸入，值為 `targetSellPrice`，兩位小數；未設定時為空、placeholder「—」 | 靠右 |
| 成本 | `cost`，千分位、不帶小數 | 靠右 |
| 未實現損益 | `unrealizedProfit`，千分位、不帶小數，套漲跌色 | 靠右 |
| 報酬率 | `returnPercent`，兩位小數加 `%`，套漲跌色 | 靠右 |
| 刪除 | 文字按鈕「刪除」 | 靠右 |

- **「股數」欄**位置在買進價右邊——買進價與股數相乘就是部位金額，兩者相鄰才讀得出來。
- **「不統計」是整列最左邊的第一欄**：它說的是「這一列算不算進總數」，放在列首才讀得出範圍，而不是夾在金額之間。欄位標題就是「不統計」，所以**勾起來＝不算**，不需要再想一層反義。
- **「目標賣價」緊鄰現價右邊**——它唯一的用途就是跟現價對照看，兩欄相鄰才讀得出來。它**只是一個記錄**：不參與任何計算，不算目標損益、不算目標報酬率，現價超過它時也不做任何提示或變色（見 `specs/backend/real-trade.md`）。
- 列的順序**一律照回應的 `items` 順序**，畫面不重排；表頭不可排序，列不可點選、不導向任何頁面。
- `stockName` 為 `null` 時該格顯示代號即可，名稱位置留空。
- `currentDate`／`currentPrice`／`unrealizedProfit`／`returnPercent` 為 `null` 時各欄顯示 `—`；**成本照常顯示**。
- 現價日早於 `asOfDate` 時該格文字用次要文字色（與模擬交易同一處理），提示這個價格不是今天的。
- 「刪除」**不彈確認對話框**（與模擬交易相同），按下即送出。

### 逐筆設定：不統計與目標賣價

兩者都是**改完立刻送出**，沒有「儲存」按鈕。

| 元素 | 行為 |
|---|---|
| 不統計 checkbox | 點一下即切換並立刻送出 `PATCH`；送出期間該 checkbox disabled；成功後重新取得列表；失敗時**回復成原本的勾選狀態**並顯示錯誤訊息 |
| 目標賣價輸入 | 數字輸入，`aria-label`「目標賣價」，placeholder「—」；接受大於 `0`、最多兩位小數的數值，或**留空表示清掉**；在該格按 Enter 或移開焦點（blur）即送出 `PATCH`；送出期間該格 disabled；成功後重新取得列表；失敗時回復成原本的值並顯示錯誤訊息 |

- **值沒變就不送**：目標賣價 blur 時若內容與原值相同（含兩邊都是空），不發任何請求。這讓「點進去又點出來」不會產生無意義的往返。
- 在目標賣價格按 **Esc** 放棄編輯，回復成原值且不送出。
- 目標賣價**留空送出**等同清掉：送 `PATCH` 的 `targetSellPrice` 為 `null`。
- **前端先擋下**不合格的目標賣價（≤ `0`、超過兩位小數、非數字）：不送出請求，直接在該列下方顯示錯誤並回復原值。
- 送出期間**只 disable 被改動的那一個控制項**，其餘各列照常可操作；加入列不受影響。
- 「不統計」與「目標賣價」**不出現在加入列**：新加入的一筆一律是未勾選、無目標賣價，加入完再改。

### 摘要列

| 元素 | 內容 |
|---|---|
| 「共 N 筆」 | `items` 筆數；其中有 M 筆 `excluded` 為 `true` 時改顯示「共 N 筆（M 筆未統計）」，`M` 為 `0` 時不加後面那一段 |
| 總成本 | `totalCost`，千分位、不帶小數 |
| 總未實現損益 | `totalUnrealizedProfit`，千分位、不帶小數，套漲跌色 |
| 總報酬率 | `totalReturnPercent`，兩位小數加 `%`，套漲跌色；為 `null` 時顯示 `—` |
| 費率說明 | 三個總計下方一行次要文字：「未實現損益已扣手續費 {feeRatePercent}%（買賣各一次）與證交稅 {taxRatePercent}%，賣出成本以現價估算」 |

三個總計**一律取自回應**，畫面不自己加總——**特別是不自己把被排除的筆扣掉**，排除哪幾筆由後端決定。**有統計的筆數為 `0` 時三個總計皆顯示 `—`**（不是 `0`／`0%`）：清單本來就空的、以及全部筆都被勾成不統計的，都算這一種。

### 格式錯誤的行

回應的 `skippedLines` 非空時，於表格下方以錯誤色列出每一筆：

「第 {lineNumber} 行格式有誤，已略過：{content}」

行號是**資料檔中的實際行號**，使用者（或代他編輯的 AI 助理）可以直接跳到那一行去改。`skippedLines` 為空時這一塊不出現。

## Implementation Details

### API 整合

| 時機 | 呼叫 | 用途 |
|---|---|---|
| 進入分頁 | `GET /api/real-trades` | 取整份清單、三個總計、費率、`skippedLines` 與 `asOfDate`（買進日輸入的預設值與 `max`） |
| 按「加入」 | `POST /api/real-trades` | body `{ stockId, buyDate, buyPrice, shares }`，四欄全帶 |
| 加入成功後 | `GET /api/real-trades` | 重新取得列表——排序、總計與各筆 `id` 一律由後端決定 |
| 切換「不統計」、或送出「目標賣價」 | `PATCH /api/real-trades/{id}` | body 只帶被改動的那一個欄位（`{ excluded }` 或 `{ targetSellPrice }`，清掉時帶 `null`）；`id` 取自該列 |
| `PATCH` 成功後 | `GET /api/real-trades` | 重新取得列表——三個總計隨排除範圍改變，一律由後端重算 |
| 按「刪除」 | `DELETE /api/real-trades/{id}` | `id` 取自該列 |
| 刪除成功後 | `GET /api/real-trades` | 同上 |

**每一次成功的 `POST`／`PATCH`／`DELETE` 之後都必須重新取得列表，不得就地改動畫面上的陣列。** 後端的 `id` 是資料行位置，刪一筆會讓其餘各筆重新編號（見 `specs/backend/real-trade.md`）；就地改動會讓畫面上的 `id` 與伺服器的對不起來，下一次刪除就刪錯列。

本頁不呼叫任何其他端點——特別是**不呼叫** `GET /api/simulated-trades`，那是另一份資料。

### 載入與錯誤狀態

| 狀態 | 畫面 |
|---|---|
| 載入中 | 表格區顯示載入指示，三個總計顯示 `—` |
| 清單為空 | 空狀態文字「還沒有任何部位，用上面那一列加入」，三個總計顯示 `—`；**加入列照常可用** |
| 首次載入失敗 | 錯誤訊息與「重試」按鈕 |
| `GET` 回 `500` `MALFORMED_TRADE_FILE` | 錯誤訊息「交易紀錄檔的欄位名列不正確，請修好 `data/real-trades.csv` 的第一行」，不顯示表格；加入列 disabled |

加入的錯誤文案，顯示在加入列下方，四格都不清空：

| 錯誤碼 | 文案 |
|---|---|
| `INVALID_STOCK_ID` | 請輸入股票代號 |
| `UNKNOWN_STOCK_ID` | 找不到股票代號 {unknownIds[0]} |
| `INVALID_BUY_DATE` | 買進日不能晚於今日 |
| `INVALID_BUY_PRICE` | 買進價必須大於 0，最多兩位小數 |
| `INVALID_SHARES` | 股數必須是大於 0 的整數 |
| `MALFORMED_TRADE_FILE` | 交易紀錄檔的欄位名列不正確，這一筆沒有寫入 |
| `INVALID_TARGET_SELL_PRICE` | 目標賣價必須大於 0，最多兩位小數 |

刪除與 `PATCH` 的錯誤：`REAL_TRADE_NOT_FOUND` → 「這一筆已經不存在了」，並重新取得列表。`PATCH` 的錯誤訊息顯示在**該列下方**（不是加入列下方），並把該列的控制項回復成原值。

**前端對四格做與後端相同的基本檢查並先擋下**（空值、非數字、買進價 ≤ 0 或超過兩位小數、股數非正整數、買進日晚於今日），後端的錯誤碼是後備。兩邊都檢查不是重複——前端擋下是為了不讓使用者等一次來回才知道打錯。

## Visual Style

沿用 `specs/frontend/simulated-trade.md` 的同一份色盤（同一個頁面的不同分頁不得有兩套顏色）：

| 元素 | 色碼 |
|---|---|
| 頁面背景 | `#0F1620` |
| 面板／表格容器背景 | `#16202C` |
| 表頭背景 | `#1B2836` |
| 主要文字（表格內容、標題、代號名稱） | `#E6EDF5` |
| 次要文字（表頭、標籤、說明行、較舊的現價日） | `#93A4B8` |
| 弱化文字（空狀態說明、總計無值時的「—」、各欄無值的「—」） | `#6B7C90` |
| 未實現損益／報酬率（正／負／零） | `#E04B45` / `#16A75C` / `#93A4B8` |
| 成本、買進價、現價、股數 | `#E6EDF5`（不套漲跌色） |
| 表格列 hover 背景 | `#1D2A38` |
| 四格輸入框背景／邊框／focus 邊框／placeholder | `#0F1620` / `#26333F` / `#3E8FD8` / `#6B7C90` |
| 「加入」按鈕背景／文字／hover 背景 | `#3E8FD8` / `#FFFFFF` / `#58A3E8` |
| 「加入」按鈕 disabled 背景／邊框／文字 | `#16202C` / `#26333F` / `#4A5866` |
| 「刪除」文字按鈕／hover | `#93A4B8` / `#E04B45` |
| 錯誤訊息文字／背景／邊框（含略過行清單） | `#F09A94` / `#3A1C1A` / `#8A3A34` |
| 加入成功時的列閃爍背景 | `#1D2A38`（與列 hover 同值） |
| 「不統計」checkbox 邊框／勾選後背景／勾號／focus 邊框 | `#26333F` / `#3E8FD8` / `#FFFFFF` / `#3E8FD8` |
| 「目標賣價」格背景／邊框／focus 邊框／文字／placeholder | `#0F1620` / `#26333F` / `#3E8FD8` / `#E6EDF5` / `#6B7C90` |
| 送出中的控制項（disabled）背景／邊框／文字 | `#16202C` / `#26333F` / `#4A5866` |

以上皆為固定色碼，**不得取自任何會隨 `prefers-color-scheme` 改變的變數或 token**：本頁在深色與淺色系統偏好下呈現完全相同。

本頁**未新增任何顏色**——所有色碼都已存在於模擬交易與策略分頁的色盤中，新增的 checkbox 與目標賣價格一律沿用既有的輸入框與「加入」按鈕的那幾個字面 hex。

## Acceptance Criteria

- [x] `/stocks` 的頁籤列多出第五個頁籤「真實交易」，排在「模擬交易」右邊；既有四個頁籤的文字、順序與行為完全未變
- [x] 「股數」緊鄰買進價右邊（完整的欄位順序見下方「不統計與目標賣價」）
- [x] 漲跌色只套在未實現損益與報酬率兩欄，成本／買進價／現價／股數一律為主要文字色
- [x] 現價日早於今日時該格套次要文字色
- [x] 清單為空時三個總計顯示 `—`，不是 `0` 或 `0%`
- [x] 所有顏色為 `## Visual Style` 的固定色碼，且在 `prefers-color-scheme: dark` 與 `light` 下實際渲染色完全相同；本次未新增任何既有色盤以外的顏色

### 改為與模擬交易對稱：加入列、刪除、改走 API

- [x] 本頁**不再讀取任何 CSV 檔**：程式中不存在對 `real-trades.csv` 的 import 或 fetch，資料一律來自 `GET /api/real-trades`；`develop/frontend/src/data/` 底下與此功能相關的檔案已移除
- [x] 加入列有四格輸入，由左至右為代號、買進日、買進價、量；買進日**不**預填上一次收盤日（它現在的預設值見下方「買進日預設今日」）
- [x] 「加入」按鈕在四格任一為空時 disabled；四格皆有值時可按；送出期間 disabled 並顯示「加入中…」
- [x] 在任一格按 Enter 等同按「加入」；四格未填滿時按 Enter 不送出
- [x] 送出的 body 為 `{ stockId, buyDate, buyPrice, shares }` 四欄全帶，值即四格當下的內容（代號前後空白已去除）
- [x] 加入成功後**只清空代號與買進價**，買進日與量維持不變；加入失敗時四格都不清空
- [x] 加入成功後重新呼叫 `GET /api/real-trades`，表格與三個總計依新回應重繪（不就地改動畫面上的陣列）
- [x] 說明文字講明股數不是張數：文案含「股數不是張數，1 張 = 1,000 股」
- [x] 每一列有「刪除」文字按鈕，按下**不彈確認對話框**，直接送 `DELETE /api/real-trades/{id}`，`id` 取自該列；成功後重新取得列表
- [x] 刪除一筆後其餘各列的 `id` 依新回應更新：刪掉第一筆後，接著刪「原本第二筆」那一列，送出的 `id` 是新回應裡的值而不是舊的
- [x] 列的順序一律照回應的 `items` 順序，畫面不重排；表頭不可排序、列不可點選、不導向任何頁面
- [x] 三個總計與費率說明全部取自回應（`totalCost`／`totalUnrealizedProfit`／`totalReturnPercent`／`feeRatePercent`／`taxRatePercent`），畫面不自己加總；`totalReturnPercent` 為 `null` 時顯示 `—`
- [x] 某筆的 `currentDate`／`currentPrice`／`unrealizedProfit`／`returnPercent` 為 `null` 時該四欄顯示 `—`，**成本照常顯示**；`stockName` 為 `null` 時名稱位置留空、代號照常顯示
- [x] `skippedLines` 非空時於表格下方以錯誤色逐筆列出「第 {lineNumber} 行格式有誤，已略過：{content}」；為空時這一塊不出現
- [x] 清單為空時顯示「還沒有任何部位，用上面那一列加入」，且**加入列照常可用**
- [x] `GET` 回 `500` `MALFORMED_TRADE_FILE` 時顯示對應文案、不顯示表格、加入列 disabled
- [x] 六個加入錯誤碼各自的文案依本 spec 的對照表顯示在加入列下方，且四格都不清空
- [x] `DELETE` 回 `REAL_TRADE_NOT_FOUND` 時顯示「這一筆已經不存在了」並重新取得列表
- [x] 前端先擋下的四種輸入錯誤（買進價 ≤ 0 或超過兩位小數、股數非正整數、買進日晚於今日、任一格為空）不送出請求即提示
- [x] 本頁不呼叫 `GET /api/simulated-trades`
- [x] 新增的兩格輸入與「刪除」按鈕所用顏色全部取自 `## Visual Style` 既有的字面 hex，在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致；本次未新增任何顏色

### 買進日預設今日（本次新增）

- [x] 第一次 `GET /api/real-trades` 成功後，買進日輸入的值等於回應的 `asOfDate`（今日，台北日曆日）；代號、買進價、量三格仍為空白
- [x] 買進日的 `max` 等於 `asOfDate`，與預設值相同——預設值本身即可選範圍的上界，不經任何操作就已是合法值
- [x] 預設值不以瀏覽器本機時鐘推算：把 `GET` 回應的 `asOfDate` 換成另一個日期（例如 `2026-09-15`）時，買進日顯示的就是那一天，而不是當下的系統日期
- [x] 第一次 `GET` 尚未回來時買進日留空；回 `500`（含 `MALFORMED_TRADE_FILE`）時買進日留空且加入列 disabled
- [x] 加入成功後重新 `GET` **不覆寫**買進日：把買進日改成 `asOfDate` 以外的日期並成功加入，該格仍是改過的值，不會被重設回今日
- [x] 刪除成功後重新 `GET`、以及首次載入失敗後按「重試」成功，同樣都不覆寫買進日
- [x] 使用者手動清空買進日後該格維持空白、不自動填回今日，且「加入」變為 disabled
- [x] 「四格任一為空即 disabled」的規則不變；差別只在進入分頁後買進日已有值，所以只填代號、買進價、量三格即可送出
- [x] `POST` 的 body 仍為 `{ stockId, buyDate, buyPrice, shares }` 四欄全帶，`buyDate` 即買進日輸入當下的值（沿用預設今日時也照樣帶出）
- [x] 本次未新增任何顏色，也未改動 `## Visual Style` 的任何既有色碼

### 不統計與目標賣價（本次新增）

- [ ] 表格欄位順序為 **不統計**、代號/名稱、買進日、買進價、股數、現價日、現價、**目標賣價**、成本、未實現損益、報酬率、刪除，共 12 欄；「不統計」是最左邊第一欄且置中，「目標賣價」緊鄰現價右邊且靠右
- [ ] 每一列最左邊有一個 checkbox，勾選狀態等於該筆回應的 `excluded`；該欄標題文字為「不統計」
- [ ] 點一下 checkbox 立刻送出 `PATCH /api/real-trades/{id}`，body 為 `{ "excluded": true }`／`{ "excluded": false }`（只帶這一欄，不帶 `targetSellPrice`）；成功後重新呼叫 `GET /api/real-trades` 並依新回應重繪
- [ ] 送出期間該 checkbox disabled；同一列的「目標賣價」與「刪除」、其餘各列、加入列都**不被 disable**
- [ ] `PATCH` 失敗時該 checkbox 回復成原本的勾選狀態，並在該列下方顯示錯誤訊息
- [ ] 勾選某一列後三個總計依新回應減少（`totalCost` 減該筆 `cost`、`totalUnrealizedProfit` 減該筆 `unrealizedProfit`），而**該列自己的成本／未實現損益／報酬率仍照常顯示**、該列仍留在表格中
- [ ] 重新整理頁面後勾選狀態仍在（持久化於後端資料檔，不是瀏覽器狀態）
- [ ] 摘要列在有 M 筆被勾為不統計時顯示「共 N 筆（M 筆未統計）」；M 為 `0` 時只顯示「共 N 筆」
- [ ] 全部筆都被勾為不統計時三個總計顯示 `—`（不是 `0`／`0%`），且表格仍列出全部筆
- [ ] 「目標賣價」欄是可輸入的數字格，值為 `targetSellPrice`，未設定時為空且 placeholder 為「—」，`aria-label` 為「目標賣價」
- [ ] 在目標賣價格按 Enter 或移開焦點即送出 `PATCH`，body 為 `{ "targetSellPrice": 1250 }`（只帶這一欄）；成功後重新呼叫 `GET /api/real-trades`
- [ ] 目標賣價**留空後送出**時 body 為 `{ "targetSellPrice": null }`，成功後該格變回空的 placeholder
- [ ] **值沒變就不送**：進入目標賣價格後未改內容即 blur（含原本為空、離開時仍為空）**不發任何請求**
- [ ] 在目標賣價格按 Esc 回復原值且不送出任何請求
- [ ] 前端先擋下不合格的目標賣價（`0`、負數、三位小數、非數字）：**不送出請求**，在該列下方顯示「目標賣價必須大於 0，最多兩位小數」並回復原值
- [ ] 後端回 `400` `INVALID_TARGET_SELL_PRICE` 時顯示同一句文案於該列下方，並回復原值
- [ ] `PATCH` 回 `404` `REAL_TRADE_NOT_FOUND` 時顯示「這一筆已經不存在了」並重新取得列表
- [ ] 設定目標賣價**不改變任何其他數字**：只改目標賣價（含設成遠高於與遠低於現價的值）後，該列的成本／未實現損益／報酬率與三個總計全部不變、列的順序也不變，且**現價超過目標賣價時不做任何提示或變色**
- [ ] 加入列仍只有四格輸入：**沒有**「不統計」checkbox 也**沒有**「目標賣價」輸入；`POST` 的 body 仍只有 `{ stockId, buyDate, buyPrice, shares }` 四欄
- [ ] 新加入的一筆在列表中是未勾選、目標賣價為空
- [ ] `GET` 回 `500` `MALFORMED_TRADE_FILE` 時不顯示表格，因此也沒有任何 checkbox 或目標賣價格可操作
- [ ] 新增的 checkbox 與目標賣價格所用顏色全部取自 `## Visual Style` 既有的字面 hex，在 `prefers-color-scheme: dark` 與 `light` 下實際渲染色完全相同；本次未新增任何顏色

## Execution Result
- Status: DONE
- Files changed:
  - `develop/frontend/src/data/real-trades.csv` (new) — committed with the header line `stockId,buyDate,buyPrice,shares` only, LF, no BOM, no data rows
  - `develop/frontend/src/data/realTrades.ts` (new) — `?raw` import of the CSV, per-line parser (header check, blank/`#` skipping, per-line validation with 1-based file line numbers, default sort), and the cost / P&L / return maths
  - `develop/frontend/src/pages/RealTradeTab.tsx` / `RealTradeTab.css` (new) — the read-only tab
  - `develop/frontend/src/pages/StockListPage.tsx` — fifth tab button 「真實交易」 (`?tab=real`) and its panel
- Notes:
  - Read-only by construction: the component renders no input/button/select/link (0 controls counted in the DOM) and only ever calls `GET /api/stocks/{stockId}`, once per distinct stockId (a `Set` of ids -> `Promise.all`). No backend change, no test file, no new color.
  - Money maths uses BigInt integer arithmetic (prices in 1/100 yuan) so `21.55 × 1000 × 0.1425%` cannot floor wrong through binary floats; ties in rounding go away from zero and the return is divided at scale 10 then rounded to scale 2, mirroring `TradingCostCalculator`.
  - Mounted only while the tab is active (like 模擬交易), so each entry re-reads quotes; the page-level 「只看上市普通股」 checkbox is not passed in.
  - **Verification** (Playwright/Chromium against the real backend on 8080 + Vite dev on 5173, and the production build via `vite preview` for the request count). Observations that used a **temporary CSV** (restored to header-only afterwards, confirmed byte-for-byte): everything below except the empty state and the bad-header state.
    - Cross-check vs `GET /api/simulated-trades`: I temporarily POSTed four simulated trades (then DELETEd ids 412-415; the pre-existing id 272 was untouched) and entered equivalent (buyPrice, 1000 shares, current close) positions in the CSV. cost / unrealizedProfit / returnPercent matched digit for digit for all five: 26,337 / -152 / -0.58 (buy day = current day, negative not clamped), 2,463,505 / 5,521 / 0.22, 24,584 / 1,601 / 6.51, 35,750 / -108 / -0.30, 13,268 / -125 / -0.94.
    - 21.55 x 1000 at 23.10 -> cost 21,580, P&L +1,419, return +6.58%. No stock in the DB closes at 23.10, so that quote was a **Playwright-mocked** `GET /api/stocks/8888` response.
    - `latestClose` `null` and `0`, and a stale `latestTradeDate` (2026-09-29) were also **mocked responses** (7777, 6666, 5555); the DB has no active stock without a close. Those rows showed `—` in 現價日/現價/損益/報酬率, kept their cost, counted in 共 N 筆 and total cost. The stale date rendered in `#93A4B8`.
    - Real `404` (9999) behaves the same, name shown as `—`. With every lookup 404-ed (route mock), the table and total cost stayed and both P&L totals showed `—`.
    - 總報酬率 denominator: with two priced rows (cost 24,584 + 35,750) and one unpriced (5,007), total P&L +1,493 -> +2.47% (would be 2.28% if the unpriced cost were included).
    - Parser: comment line, empty line and whitespace-only line skipped, not counted, not reported. Six bad lines (non-CSV text, month 13, future date, price 0, shares 1.5, price with 3 decimals) skipped and listed as `第 16…21 行格式有誤，已略過：…` with correct file line numbers (header = 1; with two extra rows added later they read 17…22). Header `stockId,buyDate,price,shares` -> only the header error message, no table.
    - Geometry: 9 columns; every body cell's left/right equals its header cell's and all cells in a row share one `top`; no `<td>`/`<th>` has a non-`table-cell` display; row cursor is default.
    - Colors: computed styles are exactly the Visual Style hexes (page `#0F1620`, panel `#16202C`, head `#1B2836` / `#93A4B8`, text `#E6EDF5`, up `#E04B45`, down `#16A75C`, flat/muted as specified, error `#F09A94`/`#3A1C1A`/`#8A3A34`). Dumping every measured computed color under `colorScheme: 'dark'` and `'light'` gave byte-identical JSON; light and dark screenshots also look the same.
    - Requests: in the production build, 9 distinct ids gave exactly 9 `GET /api/stocks/{id}` calls (1101 appears in four rows, 2330 in two); every request on the tab was GET. (The Vite dev server double-fires effects under React StrictMode, so dev shows each call twice with the first aborted; that is a dev-only artifact.)
  - After the final `catch` tightening in `RealTradeTab.tsx` (log unexpected errors instead of dropping them) I re-ran `npm run build` and lint but did not re-drive the browser; the changed branch is only reachable by a non-abort failure inside the `.then`.
  - Not verified / limits: the criterion 「第 3 行有誤的檔案驗證顯示 3」 was checked with the same rule on lines 16-22 (offset by header, comment and blank lines), not with a file literally erroring on line 3. Backend and Vite dev/preview were stopped at the end.

### Increment 2 — 2026-10-01 (加入列、刪除、改走 API)
- Status: DONE
- Files changed:
  - `develop/frontend/src/api/realTrades.ts` (new) — typed `GET`/`POST`/`DELETE /api/real-trades` client and `RealTradeApiError`
  - `develop/frontend/src/pages/RealTradeTab.tsx` (rewritten) — add bar (代號/買進日/買進價/量, all blank), per-row 刪除, summary and 股數 column from the response, `skippedLines` list, malformed-file state; re-fetches after every successful POST/DELETE
  - `develop/frontend/src/pages/RealTradeTab.css` — add-bar, input, 刪除 and row-flash styles, all existing literal hex
  - `develop/frontend/src/pages/StockListPage.tsx` — comment only
  - `develop/frontend/src/data/real-trades.csv`, `develop/frontend/src/data/realTrades.ts` — deleted (`src/data/` is now gone); nothing in the frontend reads `real-trades.csv` any more (the only remaining occurrence is the file name inside the malformed-file error copy)
- Verification (Playwright/Chromium against the real backend on 8080 and Vite on 5173; `data/real-trades.csv` restored to header-only afterwards):
  - Real: empty state (totals `—`, 4 blank inputs, 加入 disabled, hint text), 3-of-4 filled stays disabled and Enter sends nothing, front-end blocks for shares 1.5 / 0, price 0 / 1.234 / -5, future date (no request, message shown, inputs kept), add by Enter sends `{"stockId":"2330","buyDate":"2026-09-15","buyPrice":1000.5,"shares":2000}` (trimmed) then `GET`, only 代號/買進價 cleared, 加入中… shown while POST in flight, row flash class applied, real `UNKNOWN_STOCK_ID` copy with inputs kept, delete sends `DELETE /1` then `GET`, deleting the originally-second row afterwards sends `DELETE /1` (renumbered), no dialog, real stale-page delete -> 404 `REAL_TRADE_NOT_FOUND` toast and refetch, two real skipped lines (file lines 5 and 7) below the table in error colors, real malformed header (no table, five controls disabled), zero `simulated-trades` requests.
  - Mocked responses (Playwright route): null `stockName`/current fields row (`—`, cost kept, muted color), `totalReturnPercent: null`, the other five add error codes, first-load 500 + 重試.
  - Geometry: 10 header cells, every body cell's left/right equals its header's, one `top` per row, no non-`table-cell` td/th. Colors: computed values equal the Visual Style hexes; full color dump identical under `prefers-color-scheme` dark and light.
- Not driven: none unchecked. `status:` frontmatter left as is for `/dev` to flip.

### Increment 3 — 2026-10-04 (買進日預設今日)
- Files changed: `develop/frontend/src/pages/RealTradeTab.tsx`（+7 行；CSS、API 層、後端皆未動）
- 做法：新增 `buyDateDefaultedRef`；`runFetch` 成功時若尚未設過預設，就把它標記並以 `setBuyDateValue(current => current === '' ? resp.asOfDate : current)` 填入回應的 `asOfDate`。只在第一次成功的 GET 設定一次，之後加入／刪除／重試的重新 GET 都不碰買進日；`max` 原本就是 `data?.asOfDate`，無需改動。未使用瀏覽器本機時鐘。未新增任何顏色。
- Live 驗證（真實 backend `SERVER_PORT=8091` + Vite `--port 5191`，Playwright/Chromium；`npm run build` 通過）：
  - 真實：首次 GET 後買進日 = `2026-10-04`、`max` = `2026-10-04`，代號／買進價／量為空，「加入」disabled；只填三格後「加入」enabled
  - 真實：沿用預設日期加入，POST body = `{"stockId":"2330","buyDate":"2026-10-04","buyPrice":100,"shares":1000}`
  - 真實：改成 `2026-09-01` 加入成功並重新 GET 後，買進日仍為 `2026-09-01`；改成 `2026-08-15` 後刪除成功並重新 GET，仍為 `2026-08-15`
  - 真實：四格皆填後手動清空買進日，該格維持空白（500ms 後仍空）且「加入」disabled
  - Mock（route 攔截 GET）：`asOfDate: 2026-09-15` → 買進日與 `max` 皆為 `2026-09-15`；GET 延遲 1.5s 期間買進日為空、回來後為 `2026-10-04`；GET 回 500 與 `MALFORMED_TRADE_FILE` 時買進日為空、「加入」disabled；首次 500 後按「重試」成功 → `2026-10-04`；首次 500 後使用者先填 `2026-07-07` 再重試成功 → 仍為 `2026-07-07`
  - 驗證用的列已刪除；`data/real-trades.csv` 已還原為原始位元組（md5 `1bc0961bf1a4a17badaaf1bbf5776608`，與驗證前相同；後端寫檔會把 CRLF 改成 LF，已手動還原）
