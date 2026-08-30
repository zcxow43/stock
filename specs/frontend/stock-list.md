---
status: done
title: "股票總覽清單頁"
requirement: "前端 K 線瀏覽 — 使用者要能看到系統中總共有哪些股票，並從清單點選進入該檔的日 K 圖；此頁改為分頁式，分頁一為總覽（可新增／修改／下市股票），分頁二為策略"
depends_on: []
---

# 股票總覽清單頁 — Frontend Spec

## Overview

系統的進入點，一個**分頁式頁面**，含兩個分頁：

| 頁籤 | 內容 | 規格 |
|---|---|---|
| 一、總覽 | 全市場股票表格，附最新收盤與漲跌；可搜尋、篩選、點選進入個股日 K 圖，並可新增／修改／下市股票 | 本檔 |
| 二、策略 | 型態掃描 | `specs/frontend/strategy.md` |

本檔擁有分頁容器本身與分頁一的全部內容；分頁二的內容由策略頁的 spec 定義。

路由：`/stocks`（亦為應用程式根路徑 `/` 的導向目標）。分頁狀態反映在網址上（`/stocks?tab=overview`／`?tab=strategy`），使切換分頁後重新整理或分享網址能回到同一分頁；`tab` 缺漏或無法辨識時退回總覽。

## Requirements

### 頁面結構

由上而下三個區塊：

1. **頁首列** — 左側標題「股票總覽」，右側顯示「共 N 檔」（N 取自 API 的 `total`，隨篩選條件變動）。
2. **頁籤列** — 兩個頁籤「總覽」「策略」，位於頁首列下方、篩選列上方。目前分頁以底線與主要文字色標示，非目前分頁為次要文字色。切換分頁不重新載入整頁。
3. **篩選列** — 搜尋框、市場別下拉、「顯示已下市」核取方塊，右側「新增股票」主要按鈕。
4. **資料表格 + 分頁列**。表格最右新增「操作」欄，每列含「編輯」與「下市」兩個次要按鈕；已下市的列該按鈕改為「重新上架」。

頁籤列以下（第 3、4 項）的內容屬於分頁一；切到分頁二時整段換成策略頁的內容，頁首列與分頁列保持不動。

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

### 新增／編輯對話框

「新增股票」與列上的「編輯」共用同一個對話框，只有標題與送出目標端點不同。

| 欄位 | 型別 | 新增 | 編輯 |
|---|---|---|---|
| 代號 | 文字輸入 | 可填，必填 | **唯讀顯示**，不可修改 |
| 名稱 | 文字輸入 | 必填，上限 60 字 | 同左 |
| 市場別 | 下拉（上市／上櫃） | 必填，預設「上市」 | 同左 |

- 代號在編輯時唯讀，因為它是行情、指標、進度三張表的依附鍵（見 `specs/backend/stock-catalog.md`）。畫面上以唯讀樣式呈現而非隱藏，使用者需要看得到自己在編哪一檔。
- 前端先行檢核必填與長度，錯誤顯示在對應欄位下方；後端回傳的欄位級錯誤（`INVALID_STOCK_PAYLOAD` 的 `fields`）同樣標在對應欄位。
- 送出中按鈕 disabled 並顯示送出中狀態，避免重複送出建立兩筆。
- 成功後關閉對話框、重新載入目前這一頁的清單，並在頁首下方顯示一則可自動消失的成功訊息。

### 下市與重新上架

- 點「下市」跳出確認對話框：「確定將 {代號} {名稱} 下市？歷史行情會完整保留，之後可重新上架。」附「取消」與「確定下市」。
- **確認文案必須說明歷史行情會保留**——使用者看到「下市」很容易以為資料會被刪除，而實際上不會（見 `specs/backend/stock-catalog.md`）。
- 已下市的列，操作欄顯示「重新上架」，點擊後不需確認直接送出（此操作無破壞性）。
- 下市成功後，若目前篩選未勾「顯示已下市」，該列會從清單消失，此為預期行為。

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
| 對話框送出中 | 送出按鈕 disabled 並顯示送出中狀態，欄位維持可見但不可編輯 |
| 操作成功 | 頁首下方顯示成功訊息，數秒後自動消失；清單重新載入目前頁 |

## Implementation Details

### API 整合

| 動作 | 呼叫 |
|---|---|
| 載入／篩選／換頁／排序 | `GET /api/stocks?keyword=&market=&includeInactive=&page=&size=&sort=&order=` |
| 對話框送出（新增） | `POST /api/stocks` — body `stockId` / `stockName` / `market` |
| 對話框送出（編輯） | `PUT /api/stocks/{stockId}` — body `stockName` / `market` / `isActive` |
| 確認下市 | `DELETE /api/stocks/{stockId}` |
| 重新上架 | `PUT /api/stocks/{stockId}` — body 帶原名稱、原市場別、`isActive: true` |

回應欄位（契約見 `specs/backend/stock-catalog.md`）：`page` / `size` / `total` / `totalPages` / `items[]`，每個 item 含 `stockId`、`stockName`、`market`、`isActive`、`latestTradeDate`、`latestClose`、`previousClose`、`changeAmount`、`changePercent`、`latestVolume`。

`previousClose` 不在表格中顯示，但需保留於資料模型中——漲跌數值由後端提供，前端不重算。

錯誤回應處理：

| `code` | 前端行為 |
|---|---|
| `INVALID_MARKET` / `INVALID_SORT_FIELD` / `INVALID_PAGINATION` / `PAGE_SIZE_EXCEEDED` | 視為程式錯誤：顯示通用錯誤訊息並重設篩選條件為預設值（正常操作不應觸發，因所有輸入皆由受控元件產生） |
| 其他／網路錯誤 | 顯示「載入失敗，請稍後再試」與「重新載入」按鈕 |
| `STOCK_ALREADY_EXISTS` | 對話框不關閉，於代號欄位下方顯示「此代號已存在」 |
| `INVALID_STOCK_PAYLOAD` | 對話框不關閉，依回應的 `fields` 標示對應欄位 |
| `STOCK_NOT_FOUND` | 關閉對話框，顯示「此股票已不存在」並重新載入清單（多半是他處已刪除） |

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
| 頁籤列底線（未選中區段） | `#26333F` |
| 目前頁籤文字／底線 | `#E6EDF5` / `#3E8FD8` |
| 非目前頁籤文字 | `#93A4B8` |
| 非目前頁籤 hover 文字 | `#E6EDF5` |
| 對話框遮罩 | `#0A0F16` （不透明度 70%） |
| 對話框背景／邊框 | `#16202C` / `#26333F` |
| 對話框標題文字 | `#E6EDF5` |
| 唯讀欄位背景／文字 | `#1B2836` / `#93A4B8` |
| 欄位錯誤文字／邊框 | `#F09A94` / `#8A3A34` |
| 成功訊息文字／背景／邊框 | `#7ED9A6` / `#12301F` / `#2C5C40` |
| 破壞性按鈕背景／文字 | `#8A3A34` / `#FFFFFF` |
| 破壞性按鈕 hover 背景 | `#A6453E` |

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

- [x] 頁面有「總覽」「策略」兩個頁籤，預設停在總覽
- [x] 切換頁籤時網址的 `tab` 參數同步變動；直接以 `/stocks?tab=strategy` 進入會停在策略頁籤
- [x] `tab` 參數缺漏或為無法辨識的值時退回總覽，不顯示空白畫面
- [x] 切換頁籤不重新載入整頁，頁首列與頁籤列不閃動
- [x] 表格最右有「操作」欄，在市股票顯示「編輯」與「下市」，已下市股票顯示「編輯」與「重新上架」
- [x] 「新增股票」開啟對話框，代號欄可填；送出後新股票出現在清單中
- [x] 「編輯」開啟的對話框中代號為唯讀且仍可見，名稱與市場別可改
- [x] 新增重複代號時對話框不關閉，代號欄下方顯示「此代號已存在」
- [x] 名稱留空時前端即擋下並在欄位下方提示，不送出請求
- [x] 送出過程中送出按鈕為 disabled，連點兩次不會建立兩筆
- [x] 「下市」的確認對話框文案明確說明歷史行情會保留
- [x] 下市成功後，未勾「顯示已下市」時該列從清單消失；勾選後重新出現且標示為已下市
- [x] 「重新上架」不需二次確認，成功後該列回到在市狀態
- [x] 所有新增元素（頁籤、對話框、成功訊息、破壞性按鈕）的顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致

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

### Increment 2 — 2026-08-30

Turns the page into a tabbed page (總覽／策略) and adds create/edit/delist stock maintenance to the 總覽 tab, per the spec's 14 previously-unchecked Acceptance Criteria. The 策略 tab's own content is out of scope (belongs to `specs/frontend/strategy.md`); this increment only builds the tab shell it drops into.

- Files changed:
  - `develop/frontend/src/pages/StockListPage.tsx` (rewritten as the tab container) — reads/writes the `tab` URL param via `useSearchParams` (`resolveTab` defaults anything but `strategy` to `overview`, so a missing/garbage value never blanks the screen); renders the shared header (title + "共 N 檔") and the 總覽／策略 tab bar; mounts both tab panels permanently and toggles them with inline `display: none/block` so switching tabs never refetches, remounts, or flickers the header/tab bar — verified live (fetch call count unchanged across a tab switch) and by screenshot.
  - `develop/frontend/src/pages/StockOverviewTab.tsx` (new) — carries over all of Increment 1's filter/table/pagination logic verbatim, plus: an 操作 column (編輯 + 下市 for active rows, 編輯 + 重新上架 for delisted rows, `stopPropagation`'d so the row's own click-to-navigate doesn't also fire), the 新增股票 button, the create/edit dialog and delist-confirm dialog wiring, and a self-dismissing success/not-found toast. Lifts `total` up to the container via an `onTotalChange` callback so the header stays correct regardless of which tab is visible.
  - `develop/frontend/src/pages/StrategyTabPlaceholder.tsx` (new) — minimal placeholder mounted in the 策略 tab panel; exists solely so the tab shell has a concrete drop-in point for `specs/frontend/strategy.md`'s agent, without that agent having to touch `StockListPage.tsx`'s tab-container structure.
  - `develop/frontend/src/components/StockFormDialog.tsx` / `.css` (new) — shared create/edit dialog. `StockFormDialogProps` is a discriminated union on `mode` (`{mode:'create'}` vs `{mode:'edit'; stock: StockListItem}`) so the code field's required-in-edit-mode data is enforced at the type level, not via a runtime `!` assertion. In edit mode the 代號 field renders as a read-only `<div>` (still visible, per spec) instead of an `<input>`. Front-end validation (required, length caps) runs before any request; `STOCK_ALREADY_EXISTS` / `INVALID_STOCK_PAYLOAD.fields` / `STOCK_NOT_FOUND` are mapped to field-level or dialog-level messages per the spec's error table. The submit button disables for the duration of the request and the backdrop can't be click-dismissed while submitting, closing the double-submit and mid-flight-unmount gaps.
  - `develop/frontend/src/components/ConfirmDialog.tsx` / `.css` (new) — generic confirm dialog reused for the delist confirmation; renders the required "歷史行情會完整保留" wording verbatim and takes a `destructive` flag for the red confirm button styling. Not reused for 重新上架, which the spec explicitly says needs no confirmation.
  - `develop/frontend/src/pages/StockListPage.css` — added the tab bar, toast, `.sl-btn-danger`/`.sl-btn-sm`/`.sl-actions`, and strategy-placeholder styles, all literal hex straight from the spec's `## Visual Style` table (no theme tokens).
  - `develop/frontend/src/api/stocks.ts` — added `createStock` (`POST /api/stocks`), `updateStock` (`PUT /api/stocks/{stockId}`), `deactivateStock` (`DELETE /api/stocks/{stockId}`), their request/response types, and a `requestJson` helper factoring the fetch/error-parsing boilerplate for just these three new calls (the pre-existing `fetchStocks`/`fetchStockDetail` were left untouched, per "don't rewrite what already works"). `ApiError` gained an optional `fields` array to carry `INVALID_STOCK_PAYLOAD`'s field list.
  - `develop/frontend/src/__tests__/StockListPage.tabs.test.tsx` (new, 6 tests) — default-to-overview, direct `?tab=strategy` navigation, fallback on an unrecognised `tab` value, no-refetch-on-switch, header total staying visible across a switch, and the `?tab=` URL syncing both directions.
  - `develop/frontend/src/__tests__/StockOverviewTab.test.tsx` (new, 8 tests) — 操作 column contents per row status, create dialog (editable code, successful POST, blank-name validation, `STOCK_ALREADY_EXISTS` field error, submit-button-disabled-while-in-flight), edit dialog's read-only-but-visible code field, delist confirmation copy + DELETE call, and reactivate firing PUT immediately with no confirm dialog.
- Notes:
  - **Bug found and fixed via live browser testing, not caught by any unit test**: `StockFormDialog` used an `isMounted` ref (set `true` at declaration, flipped to `false` in a `useEffect` cleanup) to avoid calling `onSuccess`/`setState` after unmount. Under React 19 `StrictMode`'s dev-only mount→unmount→remount double-invoke, the cleanup fired on the synthetic first unmount and the ref was never reset back to `true` on the remount — so every real submit after that point looked "unmounted" from the guard's point of view and silently no-opped `onSuccess`, leaving the dialog open forever even though the `POST`/`PUT` had already succeeded server-side. Vitest's `render()` doesn't wrap in `StrictMode`, so all 58 unit tests passed while the live app hung on every single create/edit. Fixed by setting `isMounted.current = true` inside the effect body (not just at the `useRef` initializer), so the second StrictMode mount flips it back. Caught only because the workflow's live-verification step drove the real dialog through a real browser against the real backend — flagged here as the concrete reason that step isn't optional.
  - **Both tab panels stay mounted permanently**, toggled via inline `style={{display:...}}` rather than conditional JSX — deliberate, so `StockOverviewTab`'s fetched data/filters/pagination survive a round-trip through the 策略 tab and switching back doesn't look like a reload. Confirmed live: fetch call count was identical before and after two tab switches.
  - **Double-submit / mid-flight-close closed on both dialogs**: the submit button is `disabled` while `submitting`, and the overlay's click-to-dismiss handler now checks `!submitting` before calling `onClose`/`onCancel` — previously it would unconditionally close (and, per the bug above, effectively abandon the in-flight promise) even mid-request.
  - **`StockFormDialogProps` is a discriminated union**, not `{mode, stock?}` — checked against the `design-patterns` skill before writing it (variant-bearing component); a class hierarchy or `Strategy`-object would have been overkill for two rendering modes selected by a prop, and the union additionally makes `edit` mode requiring `stock` a compile-time fact instead of a runtime `stock!` assertion.
  - **`.sl-btn-danger` defined once**, in `StockListPage.css`; `ConfirmDialog.css` explicitly does not redefine it, to avoid the two copies drifting apart (both dialogs are only ever rendered from within `StockOverviewTab`, which already loads `StockListPage.css`).
  - Live end-to-end verification against the already-running backend on port 8080 (no `mvn spring-boot:run` invoked, per instructions): started the Vite dev server on 5173, drove it with a throwaway Playwright script (removed afterward, `playwright` installed with `--no-save` so `package.json`/`package-lock.json` are untouched) through: tab defaulting/switching/URL-sync/fallback for a bogus `tab` value/direct navigation to `?tab=strategy`; creating `ZP01`（測試新增股, OTC）with the code field editable and appearing in the list; a duplicate `POST` for the same code surfacing "此代號已存在" under the code field without closing the dialog; editing `ZP01`'s name with its code rendered as a read-only, still-visible field; delisting `ZP01` with the confirm dialog showing the exact "歷史行情會完整保留" wording, the row disappearing from the default (未勾已下市) view and reappearing with the 已下市 tag once the checkbox was ticked; and reactivating it with no confirmation dialog. Screenshotted the page under Playwright's emulated `dark` and `light` `colorScheme` — the two PNGs are visually identical (tab bar, action-column buttons, and all table colors included), consistent with `grep`-confirmed zero `prefers-color-scheme` usage anywhere in the changed CSS.
  - Database left exactly as required: confirmed `stock` = 34/34 active before starting, deleted the one throwaway `ZP01` row created during live verification, and re-confirmed `stock` = 34 rows, all `is_active = 1` afterward.
  - `npm run build` (`tsc -b && vite build`) clean; `npm test` → 58/58 passing (44 pre-existing + 6 new tab tests + 8 new maintenance tests); `npx oxlint .` clean.
  - Left unfixed / deliberately out of scope: the 策略 tab itself is `StrategyTabPlaceholder.tsx`'s one line of static text — `specs/frontend/strategy.md` owns replacing it. Did not touch `docker/launch.json`/`.claude/launch.json` (already correct from prior increments; `.claude/launch.json` materializes as a plain-file copy rather than a symlink on this checkout, but its content is identical to `docker/launch.json` and unrelated to this increment's scope).
