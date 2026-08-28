---
status: done
title: "股票總覽清單頁"
requirement: "前端 K 線瀏覽 — 使用者要能看到系統中總共有哪些股票，並從清單點選進入該檔的日 K 圖"
depends_on: []
---

# 股票總覽清單頁 — Frontend Spec

## Overview

系統的進入點。以表格列出全市場股票，附上各檔的最新收盤與漲跌，讓使用者搜尋、篩選並點選進入個股日 K 圖（`specs/frontend/stock-daily-chart.md`）。

路由：`/stocks`（亦為應用程式根路徑 `/` 的導向目標）。

## Requirements

### 頁面結構

由上而下三個區塊：

1. **頁首列** — 左側標題「股票總覽」，右側顯示「共 N 檔」（N 取自 API 的 `total`，隨篩選條件變動）。
2. **篩選列** — 搜尋框、市場別下拉、「顯示已下市」核取方塊。
3. **資料表格 + 分頁列**。

### 篩選列

| 控制項 | 型態 | 預設 | 對應 API 參數 |
|---|---|---|---|
| 搜尋框 | 文字輸入，placeholder「輸入股票代號或名稱」 | 空 | `keyword` |
| 市場別 | 下拉：`全部` / `上市` / `上櫃` | 全部 | `market`（`全部` 時不帶此參數） |
| 顯示已下市 | 核取方塊 | 未勾選 | `includeInactive` |

- 搜尋框需 **debounce 300ms** 後才發出請求。每一次按鍵都送一次請求，在 2200 檔規模下會產生大量被立即作廢的查詢。
- 任一篩選條件變動時，頁碼必須重設為第 1 頁。維持在第 5 頁而結果只剩 2 頁，會讓使用者看到一片空白並誤以為查無資料。

### 資料表格

| 欄位標題 | 資料來源 | 對齊 | 顯示規則 |
|---|---|---|---|
| 代號 | `stockId` | 左 | 等寬數字 |
| 名稱 | `stockName` | 左 | — |
| 市場 | `market` | 左 | `TSE` 顯示為「上市」、`OTC` 顯示為「上櫃」，以標籤樣式呈現 |
| 最新交易日 | `latestTradeDate` | 左 | `YYYY-MM-DD`；`null` 顯示「—」 |
| 收盤價 | `latestClose` | 右 | 2 位小數；`null` 顯示「—」 |
| 漲跌 | `changeAmount` | 右 | 2 位小數，正值前綴 `+`；依漲跌著色；`null` 顯示「—」 |
| 漲跌幅 | `changePercent` | 右 | 2 位小數加 `%`，正值前綴 `+`；依漲跌著色；`null` 顯示「—」 |
| 成交量 | `latestVolume` | 右 | 千分位整數；`null` 顯示「—」 |
| 狀態 | `isActive` | 左 | `false` 時顯示「已下市」標籤；`true` 時不顯示任何內容 |

**漲跌著色採台股慣例：漲為紅、跌為綠。** 這與歐美市場相反，若沿用圖表函式庫的預設配色會讓所有數字的紅綠意義顛倒——這是使用者一眼就會誤讀的錯誤。`changeAmount` 為 `0` 時使用平盤色。

- 整列可點擊，游標為 `pointer`，導向 `/stocks/{stockId}/daily`。
- 沒有行情資料（`latestTradeDate` 為 `null`）的列**仍然可點擊**，由日 K 頁自行呈現其空狀態。在清單就阻擋點擊會讓使用者無從得知那檔究竟怎麼了。
- 表頭在垂直捲動時固定於表格頂端。

### 排序

點擊「代號」、「名稱」、「市場」三個表頭可切換排序（升冪 ↔ 降冪），並在表頭顯示方向指示。**其餘欄位的表頭不可點擊、不顯示排序指示**——後端僅支援這三個欄位排序（見 `specs/backend/stock-catalog.md`），對收盤價或漲跌幅顯示可點擊的外觀卻無法排序，比不顯示更糟。

### 分頁列

- 位於表格下方：「第 X / Y 頁」、上一頁／下一頁按鈕、每頁筆數下拉（`20` / `50` / `100`）。
- 每頁筆數預設 `50`，變更時頁碼重設為第 1 頁。
- 第 1 頁時「上一頁」為 disabled，最後一頁時「下一頁」為 disabled。

### 畫面狀態

| 狀態 | 呈現 |
|---|---|
| 載入中（首次） | 表格區域顯示 8 列骨架列，篩選列維持可操作 |
| 載入中（換頁／篩選） | 既有表格保留並降低透明度至 60%，避免畫面整段閃動 |
| 有資料 | 正常表格 |
| 查無資料（`total` 為 0） | 表格區顯示「查無符合條件的股票」，並附「清除篩選條件」按鈕 |
| 請求失敗 | 表格區顯示錯誤訊息與「重新載入」按鈕；不得顯示空表格假裝查無資料 |

## Implementation Details

### API 整合

| 動作 | 呼叫 |
|---|---|
| 載入／篩選／換頁／排序 | `GET /api/stocks?keyword=&market=&includeInactive=&page=&size=&sort=&order=` |

回應欄位（契約見 `specs/backend/stock-catalog.md`）：`page` / `size` / `total` / `totalPages` / `items[]`，每個 item 含 `stockId`、`stockName`、`market`、`isActive`、`latestTradeDate`、`latestClose`、`previousClose`、`changeAmount`、`changePercent`、`latestVolume`。

`previousClose` 不在表格中顯示，但需保留於資料模型中——漲跌數值由後端提供，前端不重算。

錯誤回應處理：

| `code` | 前端行為 |
|---|---|
| `INVALID_MARKET` / `INVALID_SORT_FIELD` / `INVALID_PAGINATION` / `PAGE_SIZE_EXCEEDED` | 視為程式錯誤：顯示通用錯誤訊息並重設篩選條件為預設值（正常操作不應觸發，因所有輸入皆由受控元件產生） |
| 其他／網路錯誤 | 顯示「載入失敗，請稍後再試」與「重新載入」按鈕 |

### 導覽

點擊任一列 → `/stocks/{stockId}/daily`。

## Visual Style

**本頁所有顏色為固定值，不隨 `prefers-color-scheme`、OS 主題或瀏覽器偏好改變。** 一律以字面 hex 指定，不得使用會隨系統偏好變動的主題變數或色彩 token。

| 元素 | 色碼 |
|---|---|
| 頁面背景 | `#0F1620` |
| 面板／表格容器背景 | `#16202C` |
| 表頭背景 | `#1B2836` |
| 邊框、分隔線 | `#26333F` |
| 主要文字（表格內容、標題） | `#E6EDF5` |
| 次要文字（表頭、標籤、分頁文字） | `#93A4B8` |
| 弱化文字（「—」、空狀態說明） | `#6B7C90` |
| 上漲數值（紅） | `#E04B45` |
| 下跌數值（綠） | `#16A75C` |
| 平盤數值 | `#93A4B8` |
| 表格列 hover 背景 | `#1D2A38` |
| 「上市」標籤文字／邊框 | `#4B9FE8` / `#2C4A66` |
| 「上櫃」標籤文字／邊框 | `#C77DD8` / `#4A2C5A` |
| 「已下市」標籤文字／邊框 | `#93A4B8` / `#3A4757` |
| 輸入框／下拉背景 | `#0F1620` |
| 輸入框邊框 | `#26333F` |
| 輸入框 focus 邊框 | `#3E8FD8` |
| 輸入框 placeholder 文字 | `#6B7C90` |
| 次要按鈕背景／文字／邊框 | `#1B2836` / `#E6EDF5` / `#26333F` |
| 次要按鈕 hover 背景 | `#223347` |
| 主要按鈕背景／文字 | `#3E8FD8` / `#FFFFFF` |
| 主要按鈕 hover 背景 | `#58A3E8` |
| Disabled 按鈕背景／文字 | `#16202C` / `#4A5866` |
| 骨架列底色 | `#1D2A38` |
| 錯誤訊息文字／背景／邊框 | `#F09A94` / `#3A1C1A` / `#8A3A34` |

## Acceptance Criteria
- [x] `/stocks` 載入後顯示第 1 頁 50 筆股票，頁首「共 N 檔」與 API 的 `total` 一致
- [x] 搜尋框輸入「2330」與「台積」皆能篩出台積電，且輸入過程中請求經 debounce 合併（連續輸入 4 個字元不產生 4 次請求）
- [x] 市場別選「上櫃」後只顯示上櫃股票，市場欄顯示「上櫃」而非 `OTC`
- [x] 勾選「顯示已下市」後已下市標的出現於清單並帶「已下市」標籤，取消勾選後消失
- [x] 任一篩選條件變動後，頁碼重設為第 1 頁
- [x] 漲跌與漲跌幅為正值時顯示紅色 `#E04B45`、負值顯示綠色 `#16A75C`、為 0 時顯示 `#93A4B8`
- [x] 無行情資料的股票，其收盤價／漲跌／成交量欄顯示「—」而非 `0`，且該列仍可點擊
- [x] 點擊任一列導向 `/stocks/{stockId}/daily`
- [x] 「代號」「名稱」「市場」表頭可點擊排序並顯示方向指示；「收盤價」「漲跌幅」等表頭無排序互動與指示
- [x] 第 1 頁的「上一頁」與最後一頁的「下一頁」為 disabled
- [x] 每頁筆數切換為 100 後，實際回傳 100 筆且頁碼重設為 1
- [x] 篩選結果為空時顯示「查無符合條件的股票」與「清除篩選條件」按鈕，而非空白表格
- [x] API 失敗時顯示錯誤訊息與「重新載入」按鈕，不顯示成空狀態
- [x] 在瀏覽器強制 `prefers-color-scheme: dark` 與 `light` 兩種偏好下截圖比對，頁面配色完全相同、文字皆清晰可讀

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/frontend/src/pages/StockListPage.tsx` (new) — page component: filters, sortable/dumb table headers, pagination, loading/empty/error states
  - `develop/frontend/src/pages/StockListPage.css` (new) — literal-hex dark theme per Visual Style table; no `prefers-color-scheme` usage anywhere
  - `develop/frontend/src/pages/StockDailyChartPagePlaceholder.tsx` (new) — minimal stub for `/stocks/:stockId/daily` so row-click navigation is provable; the real page belongs to `specs/frontend/stock-daily-chart.md` (still `pending`), out of this spec's scope
  - `develop/frontend/src/api/stocks.ts` (new) — `fetchStocks`, `StockListParams`/`StockListResponse` types matching `StockController`/`StockQueryService`, `ApiError` + `PROGRAMMER_ERROR_CODES`
  - `develop/frontend/src/hooks/useDebouncedValue.ts` (new) — 300ms debounce hook used by the search box
  - `develop/frontend/src/App.tsx`, `develop/frontend/src/main.tsx` — added `react-router-dom` `BrowserRouter`/`Routes`: `/` → `/stocks`, `/stocks`, `/stocks/:stockId/daily`
  - `develop/frontend/src/index.css` — removed the old skeleton `.app` styles; fixed `color-scheme: dark`, literal `#0F1620` body background (no theme-reactive values)
  - `develop/frontend/vite.config.ts` — added dev-server proxy `/api` → `http://localhost:8080` (backend has no CORS config of its own) and vitest config (`jsdom`, `globals`, `setupFiles`)
  - `develop/frontend/src/setupTests.ts` (new) — `@testing-library/jest-dom` matchers
  - `develop/frontend/src/__tests__/StockListPage.test.tsx` (new) — 12 unit tests (vitest + @testing-library/react) covering load/total, dash rendering + still-clickable row, red/green/flat coloring, debounce (fake timers, 4 keystrokes → 1 request), filter-changes-resets-page, market label mapping, pager disabled state, page-size→100 resets page, empty state, error state, sort toggling, row-click navigation
  - `develop/frontend/package.json` / `package-lock.json` — added `react-router-dom`; added devDeps `vitest`, `jsdom`, `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event`; added `test` script
- Notes:
  - `npm run build` (tsc -b && vite build) and `npm test` (`vitest run`, 12/12 passing) both green; `npx oxlint .` clean.
  - End-to-end verified against the real backend: started it with `mvn -f develop/backend/pom.xml spring-boot:run`, inserted throwaway fixture rows (2330 台積電/TSE with two price rows incl. a positive change, 4966 譜瑞-KY/OTC with a negative change, 1101 台泥/TSE with no price rows, 9999 測試下市股/TSE inactive) directly via `mysql`, ran `npm run dev` and hit it through the Vite proxy, and exercised every filter/sort/pagination/empty/error path with headless Chrome (via a throwaway Playwright script, since removed along with the `playwright` devDependency after use) plus manual `curl` against `GET /api/stocks`. All fixture rows were deleted afterward (`stock`/`stock_daily_price` back to 0 rows) and both dev processes were stopped.
  - Confirmed by screenshot that the page renders byte-identical under Chrome's emulated `prefers-color-scheme: dark` and `light` (no CSS in the project reacts to that media feature — `grep` confirms zero occurrences outside of comments explicitly forbidding it).
  - The one AC that isn't fully drivable from the frontend alone — "每頁筆數切換為 100 後，實際回傳 100 筆" — is verified at the contract level: the UI correctly requests `size=100&page=1` (unit-tested) and renders whatever `items[]` the backend returns; the backend's obligation to actually return up to 100 rows is `specs/backend/stock-catalog.md`'s concern (already `done`) and the seeded fixture set only had 4 rows to exercise it against.
  - Programmer-error codes (`INVALID_MARKET` / `INVALID_SORT_FIELD` / `INVALID_PAGINATION` / `PAGE_SIZE_EXCEEDED`) are handled defensively in `fetchStocks`/`StockListPage` (generic message + reset to default filters) per spec, but — as the spec itself notes — normal UI operation can never produce these since every input is a controlled element, so this path was verified by code review rather than by driving it through the UI.
  - `/stocks/{stockId}/daily` currently renders a minimal placeholder (not part of this spec); the real page is `specs/frontend/stock-daily-chart.md`, still `pending`.
