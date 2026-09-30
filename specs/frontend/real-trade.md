---
status: pending
title: "真實交易分頁"
requirement: "第五個分頁「真實交易」與模擬交易分頁完全對稱：同樣有加入列、同樣可逐筆刪除、同樣的表格欄位與三個總計。差別有兩個——加入列多兩格輸入（買進價、量），且四格輸入全部預設空白（模擬交易的買進日預設為上一次收盤日）；以及資料存在一份隨程式碼版控的 CSV 檔而非資料庫，但那是後端的事，本頁一律透過 API 存取，不自己讀檔。命中的股票名稱、現價、成本、未實現損益、報酬率與三個總計全部取自回應，每日隨行情刷新。"
depends_on: [stock-list, simulated-trade]
---

# 真實交易分頁 — Frontend Spec

## Overview

`/stocks` 的**第五個頁籤**，排在「模擬交易」右邊（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`，第二個見 `specs/frontend/strategy.md`，第三個見 `specs/frontend/momentum.md`，第四個見 `specs/frontend/simulated-trade.md`）。

它回答的問題與模擬交易不同：模擬交易問「如果我當時買了會怎樣」，本頁問**「我現在實際持有的部位，到今天為止賺賠多少」**。

**版面與行為與模擬交易分頁對稱**——同樣的加入列、同樣的逐筆刪除、同樣的表格與三個總計。只有兩處不同：

1. **加入列多兩格輸入：買進價與量。** 模擬交易的買進價由系統取當日收盤、股數固定 1 張；真實交易記的是使用者真正的成交價與成交量，只有他自己知道。
2. **四格輸入全部預設空白。** 模擬交易的買進日預設為上一次收盤日，因為那是它最可能的值；真實交易的買進日是一個過去的實際成交日，猜哪一天都不會比空白更有用。

資料實際存在一份隨程式碼版控的 CSV 檔而不是資料庫（見 `specs/backend/real-trade.md`），但**那完全是後端的事**：本頁一律透過 API 存取，不讀檔、不匯入、不知道檔案在哪。**本頁沒有任何上傳、圖片解析或匯入的入口**——資料進到那個檔的另一條路徑（使用者手工編輯、或把券商截圖交給 AI 助理代填）發生在程式之外。

## Requirements

### 加入列

一列輸入，由左至右：代號、買進日、買進價、量、「加入」按鈕。其下一行說明文字。

| 元素 | 行為 |
|---|---|
| 代號輸入 | 單行文字，placeholder「輸入股票代號，例如 2330」；前後空白自動去除；**預設空白** |
| 買進日 | 日期輸入（`type="date"`），**預設空白**；`max` 為回應的 `asOfDate`（不能選未來） |
| 買進價 | 數字輸入，placeholder「成交價」，**預設空白**；接受大於 `0` 的數值，最多兩位小數 |
| 量 | 數字輸入，placeholder「股數」，**預設空白**；接受大於 `0` 的整數 |
| 「加入」按鈕 | 主要按鈕；**四格任一為空即 disabled**；送出期間 disabled 並顯示「加入中…」 |
| 說明文字 | 輸入列下方一行次要文字：「買進價與股數為你實際的成交價與成交量；股數不是張數，1 張 = 1,000 股」 |

- 在任一格按 Enter 等同按「加入」（四格皆有值時才生效）。
- **四格都沒有預設值**，「加入」在使用者把四格都填完之前一直是 disabled。
- **「量」的標籤與說明必須講明是股數。** 1 張 = 1,000 股，買 2 張要填 `2000`。用股數而不是張數，是因為真實交易會有零股，張數表達不了；但「量」這個字本身不帶單位，所以單位必須在說明文字裡講清楚，否則使用者會填 `2`。
- 加入成功後**只清空代號與買進價**，買進日與量維持不變——連續輸入同一天、同樣張數的幾筆是常見情形。加入失敗時四格都不清空。

### 表格

| 欄位 | 內容 | 對齊 |
|---|---|---|
| 代號 / 名稱 | `stockId` `stockName` | 靠左 |
| 買進日 | `buyDate` | 靠左 |
| 買進價 | `buyPrice`，兩位小數 | 靠右 |
| 股數 | `shares`，千分位、不帶小數 | 靠右 |
| 現價日 | `currentDate` | 靠左 |
| 現價 | `currentPrice`，兩位小數 | 靠右 |
| 成本 | `cost`，千分位、不帶小數 | 靠右 |
| 未實現損益 | `unrealizedProfit`，千分位、不帶小數，套漲跌色 | 靠右 |
| 報酬率 | `returnPercent`，兩位小數加 `%`，套漲跌色 | 靠右 |
| 刪除 | 文字按鈕「刪除」 | 靠右 |

- **「股數」欄是本頁相對於模擬交易多出來的那一欄**，位置在買進價右邊——買進價與股數相乘就是部位金額，兩者相鄰才讀得出來。
- 列的順序**一律照回應的 `items` 順序**，畫面不重排；表頭不可排序，列不可點選、不導向任何頁面。
- `stockName` 為 `null` 時該格顯示代號即可，名稱位置留空。
- `currentDate`／`currentPrice`／`unrealizedProfit`／`returnPercent` 為 `null` 時各欄顯示 `—`；**成本照常顯示**。
- 現價日早於 `asOfDate` 時該格文字用次要文字色（與模擬交易同一處理），提示這個價格不是今天的。
- 「刪除」**不彈確認對話框**（與模擬交易相同），按下即送出。

### 摘要列

| 元素 | 內容 |
|---|---|
| 「共 N 筆」 | `items` 筆數 |
| 總成本 | `totalCost`，千分位、不帶小數 |
| 總未實現損益 | `totalUnrealizedProfit`，千分位、不帶小數，套漲跌色 |
| 總報酬率 | `totalReturnPercent`，兩位小數加 `%`，套漲跌色；為 `null` 時顯示 `—` |
| 費率說明 | 三個總計下方一行次要文字：「未實現損益已扣手續費 {feeRatePercent}%（買賣各一次）與證交稅 {taxRatePercent}%，賣出成本以現價估算」 |

三個總計**一律取自回應**，畫面不自己加總。筆數為 `0` 時三個總計皆顯示 `—`（不是 `0`／`0%`）。

### 格式錯誤的行

回應的 `skippedLines` 非空時，於表格下方以錯誤色列出每一筆：

「第 {lineNumber} 行格式有誤，已略過：{content}」

行號是**資料檔中的實際行號**，使用者（或代他編輯的 AI 助理）可以直接跳到那一行去改。`skippedLines` 為空時這一塊不出現。

## Implementation Details

### API 整合

| 時機 | 呼叫 | 用途 |
|---|---|---|
| 進入分頁 | `GET /api/real-trades` | 取整份清單、三個總計、費率與 `skippedLines` |
| 按「加入」 | `POST /api/real-trades` | body `{ stockId, buyDate, buyPrice, shares }`，四欄全帶 |
| 加入成功後 | `GET /api/real-trades` | 重新取得列表——排序、總計與各筆 `id` 一律由後端決定 |
| 按「刪除」 | `DELETE /api/real-trades/{id}` | `id` 取自該列 |
| 刪除成功後 | `GET /api/real-trades` | 同上 |

**每一次成功的 `POST`／`DELETE` 之後都必須重新取得列表，不得就地改動畫面上的陣列。** 後端的 `id` 是資料行位置，刪一筆會讓其餘各筆重新編號（見 `specs/backend/real-trade.md`）；就地改動會讓畫面上的 `id` 與伺服器的對不起來，下一次刪除就刪錯列。

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

刪除的錯誤：`REAL_TRADE_NOT_FOUND` → 「這一筆已經不存在了」，並重新取得列表。

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

以上皆為固定色碼，**不得取自任何會隨 `prefers-color-scheme` 改變的變數或 token**：本頁在深色與淺色系統偏好下呈現完全相同。

本頁**未新增任何顏色**——所有色碼都已存在於模擬交易與策略分頁的色盤中。

## Acceptance Criteria

- [x] `/stocks` 的頁籤列多出第五個頁籤「真實交易」，排在「模擬交易」右邊；既有四個頁籤的文字、順序與行為完全未變
- [x] 表格欄位順序為 代號/名稱、買進日、買進價、**股數**、現價日、現價、成本、未實現損益、報酬率；「股數」緊鄰買進價右邊
- [x] 漲跌色只套在未實現損益與報酬率兩欄，成本／買進價／現價／股數一律為主要文字色
- [x] 現價日早於今日時該格套次要文字色
- [x] 筆數為 `0` 時三個總計顯示 `—`，不是 `0` 或 `0%`
- [x] 所有顏色為 `## Visual Style` 的固定色碼，且在 `prefers-color-scheme: dark` 與 `light` 下實際渲染色完全相同；本次未新增任何既有色盤以外的顏色

### 改為與模擬交易對稱：加入列、刪除、改走 API（本次新增）

- [ ] 本頁**不再讀取任何 CSV 檔**：程式中不存在對 `real-trades.csv` 的 import 或 fetch，資料一律來自 `GET /api/real-trades`；`develop/frontend/src/data/` 底下與此功能相關的檔案已移除
- [ ] 加入列有四格輸入，由左至右為代號、買進日、買進價、量，**四格初始皆為空白**——特別是買進日**不**預填上一次收盤日
- [ ] 「加入」按鈕在四格任一為空時 disabled；四格皆有值時可按；送出期間 disabled 並顯示「加入中…」
- [ ] 在任一格按 Enter 等同按「加入」；四格未填滿時按 Enter 不送出
- [ ] 送出的 body 為 `{ stockId, buyDate, buyPrice, shares }` 四欄全帶，值即四格當下的內容（代號前後空白已去除）
- [ ] 加入成功後**只清空代號與買進價**，買進日與量維持不變；加入失敗時四格都不清空
- [ ] 加入成功後重新呼叫 `GET /api/real-trades`，表格與三個總計依新回應重繪（不就地改動畫面上的陣列）
- [ ] 說明文字講明股數不是張數：文案含「股數不是張數，1 張 = 1,000 股」
- [ ] 每一列有「刪除」文字按鈕，按下**不彈確認對話框**，直接送 `DELETE /api/real-trades/{id}`，`id` 取自該列；成功後重新取得列表
- [ ] 刪除一筆後其餘各列的 `id` 依新回應更新：刪掉第一筆後，接著刪「原本第二筆」那一列，送出的 `id` 是新回應裡的值而不是舊的
- [ ] 列的順序一律照回應的 `items` 順序，畫面不重排；表頭不可排序、列不可點選、不導向任何頁面
- [ ] 三個總計與費率說明全部取自回應（`totalCost`／`totalUnrealizedProfit`／`totalReturnPercent`／`feeRatePercent`／`taxRatePercent`），畫面不自己加總；`totalReturnPercent` 為 `null` 時顯示 `—`
- [ ] 某筆的 `currentDate`／`currentPrice`／`unrealizedProfit`／`returnPercent` 為 `null` 時該四欄顯示 `—`，**成本照常顯示**；`stockName` 為 `null` 時名稱位置留空、代號照常顯示
- [ ] `skippedLines` 非空時於表格下方以錯誤色逐筆列出「第 {lineNumber} 行格式有誤，已略過：{content}」；為空時這一塊不出現
- [ ] 清單為空時顯示「還沒有任何部位，用上面那一列加入」，且**加入列照常可用**
- [ ] `GET` 回 `500` `MALFORMED_TRADE_FILE` 時顯示對應文案、不顯示表格、加入列 disabled
- [ ] 六個加入錯誤碼各自的文案依本 spec 的對照表顯示在加入列下方，且四格都不清空
- [ ] `DELETE` 回 `REAL_TRADE_NOT_FOUND` 時顯示「這一筆已經不存在了」並重新取得列表
- [ ] 前端先擋下的四種輸入錯誤（買進價 ≤ 0 或超過兩位小數、股數非正整數、買進日晚於今日、任一格為空）不送出請求即提示
- [ ] 本頁不呼叫 `GET /api/simulated-trades`
- [ ] 新增的兩格輸入與「刪除」按鈕所用顏色全部取自 `## Visual Style` 既有的字面 hex，在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致；本次未新增任何顏色

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
