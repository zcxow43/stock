---
status: pending
title: "模擬交易分頁"
requirement: "在「動態」旁新增第四個分頁「模擬交易」：輸入股票代號與買進日（預設為上一次收盤日、可往前改）即加入一筆模擬持股（買進價為該檔該日收盤、固定 1 張，後兩者不可修改），表格逐筆列出以最新收盤價計算的未實現損益與報酬率（已扣買進手續費與以現價估的賣出手續費、證交稅），並顯示總成本、總未實現損益、總報酬率；每筆可刪除；資料存在後端，重新整理後仍在"
depends_on: [stock-list]
---

# 模擬交易分頁 — Frontend Spec

## Overview

`/stocks` 的**第四個頁籤**，排在「動態」右邊（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`，第二個見 `specs/frontend/strategy.md`，第三個見 `specs/frontend/momentum.md`）。回答一個問題：**「如果我最近買了這幾檔各一張，現在賺賠多少？」**

使用者輸入代號與買進日（預設為上一次收盤日）；買進價與張數由後端決定（見 `specs/backend/simulated-trade.md`），畫面上**不提供修改**。持股存在後端，重新整理、換瀏覽器都還在。

路由：`/stocks?tab=simulated`。

**本頁呈現的是使用者自己輸入的假設部位，不是真實帳戶、也不是買賣建議。** 文案一律以「模擬／未實現損益」表述，不得出現「建議買進」「推薦」「可進場」等措辭。

## Requirements

### 頁面結構

由上而下：

1. **加入列** — 一格股票代號輸入 + 一格買進日 + 「加入」主要按鈕 + 說明文字。
2. **摘要列** — 「共 N 筆」與三個總計（總成本、總未實現損益、總報酬率），另有一行費率說明。
3. **持股表格** — 一列一筆持股，最右一欄為刪除。

**頁面層級的「只看上市普通股」不影響本頁**：本頁沒有母體篩選這件事——使用者指名哪一檔就是哪一檔，包含 ETF 與已下市股票。切換那個勾選框時本頁不重新查詢（`specs/frontend/stock-list.md` 的「各分頁如何套用」需同步記上這一條）。

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
| 「共 N 筆」 | 持股筆數 |
| 總成本 | 回應的 `totalCost`，千分位、不帶小數 |
| 總未實現損益 | 回應的 `totalUnrealizedProfit`，千分位、不帶小數，套漲跌色 |
| 總報酬率 | 回應的 `totalReturnPercent`，兩位小數加 `%`，套漲跌色 |
| 費率說明 | 三個總計下方一行次要文字：「未實現損益已扣手續費 {feeRatePercent}%（買賣各一次）與證交稅 {taxRatePercent}%，賣出成本以現價估算」 |

- **三個總計直接取回應的值，不在前端重算**：本頁沒有勾選框、沒有篩選，畫面顯示的就是全部持股，沒有任何需要前端就地重算的情境（與 `specs/frontend/strategy.md` 的總計不同，那裡有勾選框）。多算一份只會多一種與後端不一致的可能。
- **費率取自回應**，不在前端寫死；一行說明的用意與策略分頁相同：使用者會拿「(現價 − 買進價) × 1000」驗算，沒有這行就對不起來。
- `totalReturnPercent` 為 `null`（沒有任何持股）時，三個總計皆顯示弱化色的「—」，不顯示 `0`／`0%`。

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

- **買進日與現價日都要顯示。** 少了現價日，使用者看到週末或已下市的持股時無從判斷「這個現價是哪一天的」，會把上週的收盤當成今天的。
- **現價日不等於 `asOfDate` 時，現價日以次要文字色顯示**（其餘列為主要文字色）——那一格說的是「這個數字比今天舊」。
- **列依後端回的順序呈現**（買進日由新到舊、同日代號升冪），前端不重排，也**不提供排序**：本頁的列數是使用者自己加進來的量級，排序不是這個頁面要解決的問題。
- **列不可點選、不導向任何頁面**，游標維持預設箭頭；只有「刪除」按鈕可點。
- **漲跌色沿用全站規則**：未實現損益與報酬率為正值時 `#E04B45`、負值 `#16A75C`、`0` 為次要文字色 `#93A4B8`。成本與買進價、現價**不套漲跌色**，恆為主要文字色。**以實際渲染出的顏色為準**（表格儲存格的預設文字色若以更高權重宣告會把漲跌色蓋掉，類別掛對了畫面照樣是白字）。
- **刪除立即生效、不跳確認框**：加回來只要再打一次代號，成本極低；確認框在這種可逆操作上只是多一次點擊。刪除後重新取得列表（總計隨之更新）。

### 畫面狀態

| 狀態 | 呈現 |
|---|---|
| 載入中（進入分頁、加入或刪除後重新取得） | 表格區顯示「載入中…」，加入列照常可用 |
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
| `404 SIMULATED_TRADE_NOT_FOUND`（刪除時） | 表格上方「這筆持股已經不存在」，並重新取得列表（別人或另一個分頁刪掉了） |
| 其他失敗 | 「加入失敗，請稍後再試」／「刪除失敗，請稍後再試」 |

錯誤訊息在下一次成功的加入或刪除時清除。

## Implementation Details

### API 整合

| 時機 | 呼叫 |
|---|---|
| 進入分頁（含由其他分頁切回、重新整理） | `GET /api/simulated-trades` — 取 `items[]` 與三個總計、`feeRatePercent`／`taxRatePercent`／`asOfDate`／`defaultBuyDate`（後者為買進日輸入的預設值與 `max`） |
| 按「加入」或在任一輸入框按 Enter | `POST /api/simulated-trades` — body `{ stockId, buyDate }`（買進日一律送出，即使它還是預設值）；成功後**重新取得列表**（`GET`），讓三個總計與排序一律由後端決定；`201` 的回應體只用來確認系統選了哪一個買進日與買進價，不拿它自行插入列表 |
| 按某列的「刪除」 | `DELETE /api/simulated-trades/{id}` — 成功後重新取得列表 |

契約見 `specs/backend/simulated-trade.md`。**前端不自行計算任何費用、成本、損益或報酬率**，全部取自回應——同一組數字兩邊各算一次，遲早會因為捨入方式不同而對不起來。

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
| 錯誤訊息文字／背景／邊框 | `#F09A94` / `#3A1C1A` / `#8A3A34` |
| 加入成功時的列閃爍背景 | `#1D2A38`（與列 hover 同值） |

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
- [x] 每列有「刪除」按鈕，點擊即送出 `DELETE /api/simulated-trades/{id}`（不跳確認框），成功後重新取得列表，總計隨之更新
- [x] `items` 為空時不顯示表格，改顯示「尚無模擬持股，輸入股票代號加入第一筆」，三個總計顯示「—」（不是 `0`／`0%`）
- [x] 載入中顯示「載入中…」且加入列仍可用；`GET` 失敗時顯示錯誤與「重試」按鈕，且不清空上一次成功的資料
- [x] 錯誤文案：`INVALID_STOCK_ID` → 「請輸入股票代號」；`UNKNOWN_STOCK_ID` → 「找不到代號 9999」；`NO_PRICE_BEFORE_TODAY` → 「9999 在今日以前沒有可用的收盤價，無法加入」；`409 DUPLICATE_SIMULATED_TRADE` → 「2330 在 2026-09-18 已經加過了」；四者皆顯示在輸入框下方且**不清空輸入內容**
- [x] 刪除回 `404` 時顯示「這筆持股已經不存在」並重新取得列表
- [x] 本頁不含「建議」「推薦」「可進場」等措辭
- [x] 所有顏色為 `## Visual Style` 的固定色碼，`prefers-color-scheme: dark` 與 `light` 下實際渲染色完全相同


### 指定買進日（本次新增）
- [ ] 加入列有一格買進日輸入（`type="date"`），預設值等於回應的 `defaultBuyDate`、`max` 等於回應的 `asOfDate`；`defaultBuyDate` 為 `null` 時留空且「加入」disabled
- [ ] 送出的 body 為 `{ stockId, buyDate }`，買進日即輸入框當下的值（沿用預設時也照樣帶出）；在買進日輸入框按 Enter 同樣送出
- [ ] 把買進日改成更早的交易日再加入：該列的買進日與買進價為那一天的值（以 mock 回應驗證畫面顯示的是回應值）
- [ ] 加入成功後買進日**維持不變**、只有代號被清空；加入失敗時兩格都不清空
- [ ] 錯誤文案：`INVALID_BUY_DATE` → 「買進日不能晚於今日」；`NO_PRICE_ON_BUY_DATE` → 「2330 在 2026-09-13 沒有收盤價（可能是假日或停牌），請換一天」；皆顯示在輸入框下方
- [ ] 買進日輸入框的背景／邊框／focus 邊框與代號輸入框同色碼，`prefers-color-scheme: dark` 與 `light` 下實際渲染色相同
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
