---
status: done
title: "真實交易分頁"
requirement: "在「模擬交易」旁新增第五個分頁「真實交易」，記錄使用者本人實際持有的部位。與模擬交易的差別有三：資料不進資料庫，改由一份隨程式碼一起版控的 CSV 提供；每一筆的股數由該 CSV 指定（模擬交易固定 1 張）；買進價是使用者實際的成交價，不是當日收盤價。畫面唯讀——沒有加入、修改、刪除任何操作，新增一筆就是編輯那份 CSV 並重新部署。只記目前持有（未平倉）的部位，賣掉就把那一行刪掉。現價與股票名稱向既有的個股端點取得，成本／未實現損益／報酬率在前端以與模擬交易完全相同的算法算出。"
depends_on: [stock-list, simulated-trade]
---

# 真實交易分頁 — Frontend Spec

## Overview

`/stocks` 的**第五個頁籤**，排在「模擬交易」右邊（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`，第二個見 `specs/frontend/strategy.md`，第三個見 `specs/frontend/momentum.md`，第四個見 `specs/frontend/simulated-trade.md`）。

它回答的問題與模擬交易不同：模擬交易問「如果我當時買了會怎樣」，本頁問**「我現在實際持有的部位，到今天為止賺賠多少」**。兩者的畫面幾乎一樣，是因為「一筆買進 ＋ 一個現價 ＝ 未實現損益」這件事本來就一樣；不同的是資料從哪裡來，以及誰決定買進價與股數。

**本頁完全唯讀，沒有任何寫入操作。** 沒有加入列、沒有刪除鈕、沒有任何可編輯的欄位。這是「不進資料庫、資料放在一份隨程式碼版控的 CSV」這個決定的直接結果：瀏覽器寫不了專案裡的檔案，所以新增一筆部位的唯一方式是編輯那份 CSV 再重新部署。把一個按了不會有效果的「加入」按鈕擺在畫面上，比沒有按鈕更糟。

**資料怎麼進到 CSV 裡，不屬於本系統的功能範圍。** 使用者可能手動編輯，也可能把券商的對帳截圖交給 AI 助理、由助理讀圖後把資料寫進該檔。兩者都是在程式之外發生的事——**不得為此建立任何上傳、OCR、圖片解析或匯入的畫面或端點**。本頁對那份 CSV 的唯一假設是：它存在、而且符合下面的格式。

## Requirements

### 資料來源：版控中的 CSV

路徑固定為 `develop/frontend/src/data/real-trades.csv`，隨前端程式碼一起進版控、一起部署。

- **編碼為 UTF-8（不含 BOM）**，換行為 `LF`。
- **第一行是欄位名列**，內容必須逐字等於：`stockId,buyDate,buyPrice,shares`
- 其後每一行是一筆**目前持有中**的部位。欄位以半形逗號分隔，**欄位值本身不含逗號、不含引號**（四個欄位都是代號、日期或數字，不會有需要跳脫的情形，因此不實作 CSV 引號跳脫規則）。
- 前後空白自動去除。**完全空白的行略過**；第一個非空白字元為 `#` 的行視為註解、略過。
- 檔案只有欄位名列（沒有任何資料行）是合法的，畫面顯示空狀態。

| 欄位 | 型別 | 規則 |
|---|---|---|
| `stockId` | 字串 | 股票代號，例如 `2330`。必填 |
| `buyDate` | 日期 | 實際成交日，`YYYY-MM-DD`。必填，不得晚於今日 |
| `buyPrice` | 數值 | **實際成交價**（每股），最多兩位小數，必須大於 `0`。這是使用者真正成交的價格，**不是該日收盤價**——這是本頁與模擬交易最根本的差別，模擬交易的買進價由系統以收盤價填入，本頁的由使用者填入 |
| `shares` | 整數 | **股數，不是張數**。1 張 = 1000 股，所以買 2 張要寫 `2000`。必填，必須大於 `0`。用股數而不是張數，是因為真實交易會有零股，張數表達不了 |

**同一檔可以有多行**，代表不同時間、不同價格買進的多筆部位；**同一檔同一天也可以有多行**（分批成交）。每一行各自是一筆，各自算損益，不做任何合併或唯一性檢查——真實交易的紀錄就是逐筆的，把它們併成一筆會讓平均成本掩蓋掉個別進場點的好壞。

### 每一筆要顯示什麼

每一筆需要股票名稱與現價，兩者都向既有端點取得，本頁不自己存任何股票資料。

| 項目 | 來源 |
|---|---|
| 股票名稱 | `GET /api/stocks/{stockId}` 的 `stockName` |
| 現價日 | 同一回應的 `latestTradeDate` |
| 現價 | 同一回應的 `latestClose` |

**對 CSV 中出現的每一個相異 `stockId` 各呼叫一次**，同一檔出現多行只呼叫一次、結果共用。本檔案是個人持股紀錄，筆數在數十筆的量級，逐檔呼叫的成本可以接受；**不得為此新增任何批次端點或後端改動**——本次需求明確排除後端。

### 成本與未實現損益

**算法與 `specs/backend/simulated-trade.md` 的「現價與未實現損益」完全同一套，逐字沿用，不另立一份定義。**

| 項目 | 規則 |
|---|---|
| 成本 | `買進價 × 股數 ＋ 買進手續費` |
| 未實現損益 | `現價 × 股數 − 賣出手續費 − 證交稅 − 成本` |
| 報酬率 | `未實現損益 ÷ 成本 × 100`，四捨五入至小數第二位 |

交易成本三項：**手續費率 `0.1425%`**（買進與賣出各算一次、不打券商折扣、不設最低）、**證交稅 `0.3%`**（只在賣出時）。三項**各自獨立無條件捨去至整數元**，不是先加總再捨去。

- 買進手續費 ＝ `floor(買進價 × 股數 × 0.001425)`
- 賣出手續費 ＝ `floor(現價 × 股數 × 0.001425)`
- 證交稅 ＝ `floor(現價 × 股數 × 0.003)`

**賣出成本要先扣**：不扣的話，畫面上的未實現損益會比真的賣掉拿得到的錢多一截，而使用者會拿它跟模擬交易分頁（已扣）的數字並排看。同一個系統裡兩種口徑，比錯的數字更難發現。

**這段算法在前端實作，是本次「不動後端」這個決定付出的代價。** 它與後端 `GET /api/simulated-trades` 回傳的那一份是同一個定義，因此本 spec 要求以同一組輸入交叉驗證兩者逐位相同（見 Acceptance Criteria），確保兩份實作不會隨時間分歧。

### 沒有現價的情形

`GET /api/stocks/{stockId}` 回 `404`、或該檔的 `latestClose` 為 `null` 或 `0` 時，該筆**無法計算**：

- 名稱欄顯示 `—`（`404` 時）或照常顯示（有主檔但無行情時）
- 現價日、現價、未實現損益、報酬率四欄皆顯示 `—`
- **成本照常顯示**——成本只需要買進價與股數，與現價無關
- 該筆**不計入**總未實現損益與總報酬率，但**計入**總成本與「共 N 筆」

### 格式錯誤的行

CSV 是手工維護的，所以解析錯誤要**逐行**報告，而不是整頁失敗。

- 某一行欄位數不是 4、`buyDate` 不是合法日期或晚於今日、`buyPrice` 不是大於 `0` 的數值、`shares` 不是大於 `0` 的整數 → **略過該行**，其餘各行照常顯示。
- 表格下方以錯誤色列出被略過的行：「第 {行號} 行格式有誤，已略過：{原始內容}」，行號為**檔案中的實際行號**（含欄位名列，從 1 起算），這樣才能直接跳到那一行去改。
- 欄位名列不符合規定內容 → 整頁視為讀取失敗，顯示「`real-trades.csv` 的欄位名列必須是 `stockId,buyDate,buyPrice,shares`」，不顯示表格。

## Implementation Details

### 頁面結構

由上而下：摘要列 → 表格 → （若有）略過行的錯誤清單。**沒有加入列。**

#### 摘要列

| 元素 | 內容 |
|---|---|
| 「共 N 筆」 | 資料行筆數（不含被略過的行） |
| 總成本 | 各筆成本合計，千分位、不帶小數 |
| 總未實現損益 | **有現價的各筆**合計，千分位、不帶小數，套漲跌色 |
| 總報酬率 | `總未實現損益 ÷ 有現價各筆的成本合計 × 100`，兩位小數加 `%`，套漲跌色 |
| 費率說明 | 三個總計下方一行次要文字：「未實現損益已扣手續費 0.1425%（買賣各一次）與證交稅 0.3%，賣出成本以現價估算」 |

**總報酬率的分母只含有現價的那幾筆**，與分子的涵蓋範圍一致。若分母含了算不出損益的筆，報酬率會被無故稀釋。

筆數為 `0` 時三個總計皆顯示 `—`（不是 `0`／`0%`）。

#### 表格

| 欄位 | 內容 | 對齊 |
|---|---|---|
| 代號 / 名稱 | `stockId` `stockName` | 靠左 |
| 買進日 | `buyDate` | 靠左 |
| 買進價 | `buyPrice`，兩位小數 | 靠右 |
| 股數 | `shares`，千分位、不帶小數 | 靠右 |
| 現價日 | `latestTradeDate` | 靠左 |
| 現價 | `latestClose`，兩位小數 | 靠右 |
| 成本 | 千分位、不帶小數 | 靠右 |
| 未實現損益 | 千分位、不帶小數，套漲跌色 | 靠右 |
| 報酬率 | 兩位小數加 `%`，套漲跌色 | 靠右 |

**「股數」欄是本頁相對於模擬交易多出來的那一欄**，位置在買進價右邊——買進價與股數相乘就是部位金額，兩者相鄰才讀得出來。

- 預設排序：`buyDate` 由新到舊，同日依 `stockId` 升冪。
- 列**不可點選、不導向任何頁面**，表頭**不可排序**——本頁是一份紀錄的呈現，不是查詢工具。
- 現價日早於今日時，該格文字用次要文字色（與模擬交易同一處理），提示這個價格不是今天的。

### API 整合

| 時機 | 呼叫 | 用途 |
|---|---|---|
| 進入分頁 | `GET /api/stocks/{stockId}` | 取該檔的 `stockName`、`latestTradeDate`、`latestClose`；對 CSV 中每一個相異代號各一次 |

CSV 本身隨程式碼打包，**不是**一次網路請求，因此不列在本表中。本頁不呼叫任何其他端點——特別是**不呼叫** `GET /api/simulated-trades`，那是另一份資料。

### 載入與錯誤狀態

| 狀態 | 畫面 |
|---|---|
| 載入中 | 表格區顯示載入指示，摘要列的三個總計顯示 `—` |
| CSV 無資料行 | 空狀態文字「`real-trades.csv` 裡還沒有任何部位」，三個總計顯示 `—` |
| 欄位名列不符 | 整頁錯誤訊息（見上），不顯示表格 |
| 個別代號查詢失敗 | 只影響該筆（見「沒有現價的情形」），不影響整頁 |
| 全部代號查詢皆失敗 | 表格照常顯示（成本仍算得出來），三個總計中總未實現損益與總報酬率顯示 `—` |

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
| 錯誤訊息文字／背景／邊框（含略過行清單） | `#F09A94` / `#3A1C1A` / `#8A3A34` |

以上皆為固定色碼，**不得取自任何會隨 `prefers-color-scheme` 改變的變數或 token**：本頁在深色與淺色系統偏好下呈現完全相同。

本頁**未新增任何顏色**——所有色碼都已存在於模擬交易與策略分頁的色盤中。

## Acceptance Criteria

### 分頁與資料來源
- [x] `/stocks` 的頁籤列多出第五個頁籤「真實交易」，排在「模擬交易」右邊；既有四個頁籤的文字、順序與行為完全未變
- [x] 資料讀自 `develop/frontend/src/data/real-trades.csv`，該檔進版控、隨前端一起部署；畫面上沒有任何上傳、匯入、圖片解析的入口
- [x] 頁面**完全唯讀**：沒有加入列、沒有刪除鈕、沒有任何可編輯欄位，且不送出任何 `POST`／`PUT`／`DELETE`
- [x] 欄位名列逐字為 `stockId,buyDate,buyPrice,shares` 時正常解析；改成其他內容時整頁顯示欄位名列錯誤訊息且不顯示表格
- [x] 空白行與以 `#` 開頭的註解行被略過，且**不**列入「共 N 筆」、**不**進略過行錯誤清單
- [x] 只有欄位名列的檔案顯示空狀態「`real-trades.csv` 裡還沒有任何部位」，三個總計顯示 `—`

### 逐筆計算
- [x] `shares` 解讀為**股數**不是張數：`shares` 為 `2000` 的一筆，其成本以 2000 股計算（不是 2000 張、也不是 2 股）
- [x] `buyPrice` 直接取自 CSV，**不**向任何端點索取該日收盤價、也不以收盤價覆蓋——以「CSV 寫 `21.55`、該日實際收盤為其他值」的情境驗證畫面顯示 `21.55`
- [x] 成本、未實現損益、報酬率依本 spec 的公式算出，三項交易成本各自獨立無條件捨去至整數元：以買進價 `21.55`、股數 `1000`、現價 `23.10` 驗證 → 買進手續費 `30`、賣出手續費 `32`、證交稅 `69`、成本 `21580`、未實現損益 `1419`、報酬率 `6.58`
- [x] **與後端同一套算法交叉驗證**：同一組（買進價、股數、現價）分別由本頁算出與 `GET /api/simulated-trades` 對同值部位回傳的 `cost`／`unrealizedProfit`／`returnPercent` **逐位相同**，證明前端這份實作沒有與後端那份分歧
- [x] 現價日等於買進日時未實現損益為負值，**不得夾為 `0`**
- [x] 同一檔多行（不同買進日、或同日分批）各自成為一列、各自計算，不合併也不去重
- [x] 相同 `stockId` 出現多行時，`GET /api/stocks/{stockId}` 只呼叫一次，結果由那幾列共用

### 沒有現價與格式錯誤
- [x] 某檔回 `404` 時該列名稱顯示 `—`，現價日／現價／未實現損益／報酬率皆顯示 `—`，**成本照常顯示**，該筆不計入總未實現損益與總報酬率但計入總成本與「共 N 筆」
- [x] `latestClose` 為 `null` 或 `0` 時與 `404` 同樣處置
- [x] 欄位數不是 4、`buyDate` 非法或晚於今日、`buyPrice` 不大於 `0`、`shares` 不是大於 `0` 的整數 → 該行被略過，其餘各行照常顯示
- [x] 被略過的行以錯誤色列在表格下方，文案為「第 {行號} 行格式有誤，已略過：{原始內容}」，行號為**含欄位名列、從 1 起算**的實際檔案行號——以第 3 行有誤的檔案驗證顯示的是 `3`
- [x] 全部代號查詢皆失敗時，表格與總成本照常顯示，總未實現損益與總報酬率顯示 `—`

### 版面與呈現
- [x] 表格欄位順序為 代號/名稱、買進日、買進價、**股數**、現價日、現價、成本、未實現損益、報酬率；「股數」緊鄰買進價右邊
- [x] 漲跌色只套在未實現損益與報酬率兩欄，成本／買進價／現價／股數一律為主要文字色
- [x] 總報酬率的分母只含**有現價**的那幾筆的成本，與分子涵蓋範圍一致——以「兩筆有現價、一筆無現價」的資料驗證分母不含第三筆
- [x] 預設排序為買進日由新到舊、同日依代號升冪；列不可點選、不導向任何頁面，表頭不可排序
- [x] 現價日早於今日時該格套次要文字色
- [x] 筆數為 `0` 時三個總計顯示 `—`，不是 `0` 或 `0%`
- [x] 所有顏色為 `## Visual Style` 的固定色碼，且在 `prefers-color-scheme: dark` 與 `light` 下實際渲染色完全相同；本次未新增任何既有色盤以外的顏色

---
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
