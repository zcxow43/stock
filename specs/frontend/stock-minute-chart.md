---
status: done
title: "個股分 K 線圖頁"
requirement: "前端 K 線瀏覽 — 在日 K 圖上連點兩下某一天後，以現價流線圖展示該交易日的分 K 走勢"
depends_on: [stock-daily-chart]
---

# 個股分 K 線圖頁 — Frontend Spec

## Overview

展示**單一交易日內**的分鐘價格走勢，是日 K 頁連點兩下某根 K 棒後的去處。主圖是**現價流線圖**（一條收盤價折線），不是蠟燭圖；X 軸為當日時間，副圖只保留成交量（分鐘級的 MACD／KD 不在本階段範圍）。

**主圖刻意不用蠟燭。** 一個交易日的 1 分 K 有兩百多根，每根各自紅綠、各自帶上下影線，在一個螢幕寬度內擠成一片紅綠雜訊，看不出當天的價格走向——而那正是使用者點進這一頁唯一想知道的事。日內走勢要看的是「往哪走」，不是每一分鐘的開高低收；後者仍可由 tooltip 逐點查閱。日 K 頁維持蠟燭圖不變（見 `specs/frontend/stock-daily-chart.md`），兩頁的圖形不同是刻意的，因為要回答的問題不同。

路由：`/stocks/{stockId}/minute/{tradeDate}`，`tradeDate` 格式 `YYYY-MM-DD`。

本頁的關鍵設計課題不是繪圖，而是**誠實呈現「這天的分 K 拿不到」**：資料來源只提供最近 30 天的分鐘資料（見 `specs/backend/stock-minute-price.md`），使用者在日 K 上點一個三個月前的 K 棒是完全正常的操作，該情境必須有明確的說明，而不是轉圈或空圖。

## Requirements

### 頁面結構

由上而下：

1. **頁首列** — 「← 返回日 K」連結、股票代號與名稱、該交易日日期（大字）、週期選擇器、「重新整理」按鈕。
2. **當日概況條** — 該交易日的日線資訊（開、高、低、收、成交量）。
3. **主圖（現價流線圖）+ 成交量副圖**。
4. **前後交易日切換** — 圖表下方的「← 前一交易日」／「後一交易日 →」。

### 週期選擇器

按鈕群組：`1 分`（預設）/ `5 分` / `15 分` / `30 分` / `60 分`，對應 API 的 `interval` 參數（`1`/`5`/`15`/`30`/`60`）。切換時重新請求並重繪。

### 主圖：現價流線圖

- 一條折線，每個資料點取 `bars[]` 該根的 `close`（該 `interval` 區間的最後成交價，即「現價」）。`open`／`high`／`low` 不再繪製，但仍保留在 tooltip 中。
- **線為單一顏色 `#3E8FD8`，不隨漲跌變色。** 折線本身沒有「每一根」可著色，整條線依終值紅綠又會讓同一條線在盤中不同時間點呈現不同顏色、造成誤讀；方向資訊改由下述基準線承擔。
- **純線，線下不做漸層或面積填色。**
- **開盤價基準線**：一條貫穿全圖的水平虛線，值為 `dailySummary.open`，色 `#4A5866`，在 Y 軸該位置標注價格。使用者用「線在虛線之上或之下」判斷當日漲跌，不需要靠線的顏色。
- **X 軸為類別軸**，只排列實際有資料的時間；無成交的分鐘不留空格（API 本就不回傳這些分鐘）。X 軸標籤為 `HH:mm`，於整點與半點加粗顯示。
- Y 軸為價格，範圍需**同時涵蓋當日 `bars` 的最高與最低 `close` 以及開盤價基準線**，並各留 5% 邊距——基準線落在可視範圍外會讓整張圖失去判讀依據。當日僅有一個價位（漲停或跌停鎖死）導致高低相同時，Y 軸須以該價位為中心給出固定高度的範圍，不可產生零高度的座標軸。
- 十字準星與 tooltip 行為同日 K 頁，並在折線上以一個圓點標示目前對應的資料點。tooltip 內容維持完整：時間、開、高、低、收、與**當日開盤價**相比的漲跌幅、成交量（張）——逐分鐘的開高低收沒有從畫面上消失，只是從主圖移到 tooltip。

### 成交量副圖

柱狀圖，與主圖共用 X 軸與十字準星。**柱色統一為中性色 `#3A4757`，不依漲跌分紅綠。** 主圖已經不再以顏色表達漲跌，量柱若還維持紅綠，畫面就會剩下一排紅綠柱在傳達方向，等於把剛從主圖移除的雜訊原封不動搬到副圖；量副圖要回答的是「這個時間點量放大了嗎」，那是高低的問題，不是方向的問題。

### 當日概況條

取自 API 的 `dailySummary`，顯示：開盤、最高、最低、收盤、成交量（張）。收盤價依「收盤 vs 開盤」著色。

**分 K 取不到時，只要 `dailySummary` 有值就仍須顯示這一條。** 這是使用者在該情境下唯一能拿到的當日資訊。

### 資料狀態的呈現（本頁核心）

依 API 回應的 `dataStatus` 決定圖表區內容（契約見 `specs/backend/stock-minute-price.md`）：

| `dataStatus` | 圖表區呈現 |
|---|---|
| `AVAILABLE` | 正常繪製分 K 圖與成交量副圖 |
| `OUT_OF_WINDOW` | 訊息：「資料來源僅提供最近 30 天的分鐘資料，此交易日已超出可取得範圍。」附「返回日 K」按鈕。**不提供重試按鈕**——重試永遠不會成功 |
| `NO_DATA` | 訊息：「此交易日沒有分鐘成交資料。」附「返回日 K」按鈕 |
| `NOT_A_TRADING_DAY` | 訊息：「此日期非該股票的交易日。」附「返回日 K」按鈕 |
| `FETCH_FAILED` | 訊息：「取得分鐘資料失敗」，下方以小字顯示回應的 `message`，附「重試」按鈕（以 `refresh=true` 重新請求） |

四種非 `AVAILABLE` 狀態的文案**必須各自不同**。統一顯示「無資料」會讓使用者無法分辨是這天沒交易、資料太舊了、還是系統壞了——這三件事對他的下一步動作影響完全不同。

### 載入中

首次抓取需向外部來源請求，可能耗時數秒。載入中顯示骨架方塊與說明文字「正在取得當日分鐘資料…」，而非僅一個轉圈圖示——使用者需要知道這裡的等待是預期內的。

### 重新整理

頁首「重新整理」按鈕以 `refresh=true` 重新請求，強制向來源重抓。

- 僅在 `tradeDate` 為**今日**時顯示（盤中分 K 會持續增長，其餘日期的資料已定案，重抓沒有意義）。
- `dataStatus` 為 `FETCH_FAILED` 時，圖表區的「重試」按鈕不受此條件限制，任何日期皆可用。
- 請求進行中按鈕為 disabled。

### 前後交易日切換

- 「← 前一交易日」／「後一交易日 →」導向同一檔股票相鄰交易日的分 K 頁。
- 相鄰交易日以 `GET /api/stocks/statistics` 取得（見下方 API 整合）；**必須是真正的交易日，不可用日期加減一天**——那會導向週末，得到 `NOT_A_TRADING_DAY`。
- 已是可得區間的第一／最後一個交易日時，對應按鈕為 disabled。

## Implementation Details

### API 整合

| 時機 | 呼叫 |
|---|---|
| 進頁、切換週期、重新整理 | `GET /api/stocks/{stockId}/minute-bars?tradeDate={日期}&interval={週期}&refresh={true 僅於重新整理}` |
| 進頁 | `GET /api/stocks/statistics?stockIds={stockId}&includeSeries=true&startDate={tradeDate 往前 14 天}&endDate={tradeDate 往後 14 天}` — 僅為取得相鄰交易日清單 |

分 K 回應使用的欄位：`stockId`、`stockName`、`tradeDate`、`interval`、`dataStatus`、`source`、`fetchedAt`、`barCount`、`dailySummary{open,high,low,close,volume}`、`bars[]{barTime,open,high,low,close,volume}`、以及 `FETCH_FAILED` 時的 `message`。

相鄰交易日取自統計回應的 `items[0].series[].tradeDate`：排序後找出目前 `tradeDate` 的前一個與後一個。此請求失敗或回傳空序列時，前後切換按鈕一律 disabled，**不影響分 K 圖本身的顯示**。

錯誤回應處理：

| `code` / 狀況 | 前端行為 |
|---|---|
| `STOCK_NOT_FOUND`（`404`） | 全頁顯示「找不到此股票代號」與「返回清單」按鈕 |
| `INVALID_DATE_FORMAT` / `MISSING_TRADE_DATE` / `FUTURE_TRADE_DATE`（`400`） | 全頁顯示「網址中的日期無效」與「返回日 K」按鈕（正常導覽不會觸發，僅手動改網址時發生） |
| `INVALID_INTERVAL`（`400`） | 視為程式錯誤：重設週期為 `1 分` 並重新請求 |
| 網路錯誤／逾時 | 圖表區顯示「載入失敗，請稍後再試」與「重新載入」按鈕 |

「該日無分 K」由 `200` 回應的 `dataStatus` 表達，不是錯誤路徑，不得走到上表任何一列。

### 共用 K 線圖元件

沿用 `specs/frontend/stock-daily-chart.md` 定義的圖表元件的**座標軸、十字準星、tooltip 版面與單擊釘選**行為：本頁傳入的 X 軸標籤為 `HH:mm`、副圖僅一個成交量圖、不傳入雙擊回呼（分 K 沒有下一層可鑽）。

**主圖的繪製型態由本頁指定為折線，不是該元件預設的蠟燭。** 該元件的 K 棒紅綠配色不適用於本頁主圖；本頁主圖與量柱的顏色以下方 `## Visual Style` 為準。日 K 頁維持蠟燭，兩頁共用同一個元件的軸與互動，主圖型態各自指定。

## Visual Style

**本頁所有顏色為固定值，不隨 `prefers-color-scheme`、OS 主題或瀏覽器偏好改變。** 一律以字面 hex 指定，不得使用會隨系統偏好變動的主題變數或色彩 token。色票與 `specs/frontend/stock-daily-chart.md` 完全相同。

| 元素 | 色碼 |
|---|---|
| 頁面背景 | `#0F1620` |
| 面板／圖表容器背景 | `#16202C` |
| 頁首列背景 | `#1B2836` |
| 邊框、面板分隔線 | `#26333F` |
| 主要文字（價格、日期、標題） | `#E6EDF5` |
| 次要文字（標籤、軸文字） | `#93A4B8` |
| 弱化文字（「—」、`fetchedAt` 時間、提示文字） | `#6B7C90` |
| 「← 返回日 K」連結文字 | `#3E8FD8` |
| 現價流線 | `#3E8FD8` |
| 開盤價基準虛線 | `#4A5866` |
| 十字準星對應的折線資料點 | `#E6EDF5` |
| 上漲數值（紅，用於當日概況條，主圖不使用） | `#E04B45` |
| 下跌數值（綠，用於當日概況條，主圖不使用） | `#16A75C` |
| 平盤數值 | `#93A4B8` |
| 成交量柱（中性，不分漲跌） | `#3A4757` |
| 圖表格線 | `#1F2C3A` |
| 十字準星線 | `#6B7C90` |
| 十字準星軸標籤背景／文字 | `#3A4757` / `#E6EDF5` |
| Tooltip 背景／邊框／文字 | `#1B2836` / `#3A4757` / `#E6EDF5` |
| 週期按鈕（未選）背景／文字／邊框 | `#1B2836` / `#93A4B8` / `#26333F` |
| 週期按鈕（已選）背景／文字 | `#3E8FD8` / `#FFFFFF` |
| 週期按鈕 hover 背景 | `#223347` |
| 次要按鈕（重新整理、前後交易日）背景／文字／邊框 | `#1B2836` / `#E6EDF5` / `#26333F` |
| Disabled 按鈕背景／文字 | `#16202C` / `#4A5866` |
| 資料不可得訊息文字（`OUT_OF_WINDOW`／`NO_DATA`／`NOT_A_TRADING_DAY`） | `#93A4B8` |
| 資料不可得訊息圖示 | `#4A5866` |
| 錯誤訊息文字／背景／邊框（`FETCH_FAILED`） | `#F09A94` / `#3A1C1A` / `#8A3A34` |
| 骨架方塊底色 | `#1D2A38` |

## Acceptance Criteria
- [x] 由日 K 頁連點兩下某根 K 棒後，導向 `/stocks/{stockId}/minute/{該日期}` 並顯示該日分 K 圖
- [x] 主圖為單一顏色 `#3E8FD8` 的收盤價折線，畫面上不存在任何蠟燭實體或上下影線
- [x] X 軸標籤為 `HH:mm`，首根為 `09:00`、末根不晚於 `13:30`
- [x] 成交量副圖與主圖共用 X 軸，十字準星在兩張圖上同步移動
- [x] Tooltip 顯示時間、開高低收、相對當日開盤的漲跌幅、成交量
- [x] 週期切換為 `5 分` 後重新請求並重繪，資料點數量約為 1 分的五分之一
- [x] 當日概況條顯示 `dailySummary` 的開高低收與成交量
- [x] `dataStatus` 為 `OUT_OF_WINDOW` 時顯示「資料來源僅提供最近 30 天的分鐘資料…」，**不出現重試按鈕**，且當日概況條仍顯示
- [x] `dataStatus` 為 `NO_DATA` / `NOT_A_TRADING_DAY` / `FETCH_FAILED` 時各顯示不同文案，四種狀態的訊息互不相同
- [x] `dataStatus` 為 `FETCH_FAILED` 時顯示回應的 `message` 與「重試」按鈕，點擊後以 `refresh=true` 重新請求
- [x] 載入中顯示「正在取得當日分鐘資料…」說明文字，而非僅一個轉圈圖示
- [x] 「重新整理」按鈕僅在 `tradeDate` 為今日時出現；其他日期不顯示
- [x] 「前一交易日」導向真正的前一個交易日（跨過週末），而非日期減一天
- [x] 相鄰交易日查詢失敗時，前後切換按鈕 disabled，但分 K 圖仍正常顯示
- [x] 當日高低價相同（漲停鎖死）時圖表仍正常繪出，Y 軸不塌陷為零高度
- [x] 手動輸入非法日期的網址時顯示「網址中的日期無效」，而非空圖或無限載入
- [x] 在瀏覽器強制 `prefers-color-scheme: dark` 與 `light` 兩種偏好下截圖比對，頁面與圖表配色完全相同

---

- [x] 折線不隨漲跌變色：同一張圖在上漲日與下跌日的線色相同，皆為 `#3E8FD8`
- [x] 線下沒有漸層或面積填色
- [x] 圖上有一條值為 `dailySummary.open` 的水平虛線，色 `#4A5866`，且 Y 軸標注其價格
- [x] 當日所有 `close` 都高於（或都低於）開盤價時，開盤價基準線仍在可視範圍內，未被裁切到圖外
- [x] 成交量柱統一為 `#3A4757`，畫面上不存在紅色或綠色的量柱
- [x] Tooltip 仍顯示該點的時間、開、高、低、收、相對當日開盤的漲跌幅與成交量，逐分鐘的開高低收未因主圖改為折線而遺失
- [x] 十字準星移動時，折線上對應的資料點以 `#E6EDF5` 圓點標示
- [x] 日 K 頁（`/stocks/{stockId}/daily`）的主圖仍為蠟燭圖，未受本次變更影響

## Execution Result
- Status: DONE
- Files changed:
  - `develop/frontend/src/pages/StockMinuteChartPage.tsx` (new)
  - `develop/frontend/src/pages/StockMinuteChartPage.css` (new)
  - `develop/frontend/src/api/minuteBars.ts` (new)
  - `develop/frontend/src/components/KLineChart.tsx` (extended with optional `boldXTick` prop — additive, daily-chart page unaffected)
  - `develop/frontend/src/App.tsx` (added `/stocks/:stockId/minute/:tradeDate` route)
  - `develop/frontend/src/__tests__/StockMinuteChartPage.test.tsx` (new, 16 tests)
- Notes:
  - All 17 Acceptance Criteria verified. 44/44 frontend tests pass (28 pre-existing + 16 new), `tsc -b && vite build` and `oxlint` are clean.
  - Verified end-to-end against the real backend (`mvn spring-boot:run` on 8080) and a live MySQL fixture (stock 2330, seeded then fully deleted afterward — DB confirmed back to 0 rows in all four tables touched). Exercised every `dataStatus` via real API responses: `AVAILABLE` (271 one-minute bars, `interval=5` aggregates to 55 bars matching the backend's own aggregation), `NO_DATA`, `NOT_A_TRADING_DAY`, `OUT_OF_WINDOW`, `FETCH_FAILED` (with `message`), plus `STOCK_NOT_FOUND` (404) and `INVALID_DATE_FORMAT` (400, via a malformed `tradeDate` in the URL). Also exercised the real refresh path for `tradeDate` = today, which triggered a genuine on-demand Yahoo Finance fetch through `MinuteBarQueryService`/`MinutePriceIngestionService` and returned real `AVAILABLE` data — confirming the "重新整理" button and its `refresh=true` request work against live ingestion, not just a mock.
  - Took real browser screenshots (Playwright + Chromium) of every state: available chart with tooltip/crosshair sync, 5-minute redraw, all four non-AVAILABLE messages, the two full-page error states, and the today/refresh-visible variant.
  - Verified AC "prefers-color-scheme" requirement empirically, not just by code review: rendered the same URL under `colorScheme: 'light'` and `colorScheme: 'dark'` and diffed the two PNGs pixel-by-pixel (Pillow `ImageChops.difference`) — bounding box `None`, zero-extrema, i.e. byte-for-byte identical.
  - Two real bugs found and fixed during this verification (not just from unit tests): (1) the adjacent-trading-day effect called `new Date(...)` on an unvalidated `tradeDate` and threw `RangeError: Invalid time value` for a malformed URL date, breaking the full-page "網址中的日期無效" state — fixed by guarding with `isValidISODate` before running that best-effort sibling request. (2) the footer's `fetchedAt` rendered the raw backend `LocalDateTime` JSON string (`2026-08-28T16:18:56.711257`) instead of the storyboard's `YYYY-MM-DD HH:mm:ss` — added `fmtFetchedAt` to trim/format it.
  - `boldXTick` was added to the shared `KLineChart` as an optional prop (defaults to no bold, so `StockDailyChartPage` is untouched); the minute page passes a `HH:mm` on-the-hour/half-hour predicate. Empirically, with the real 271-bar/1-min and 55-bar/5-min fixture data, the generic evenly-spaced tick picker happened to land exactly on `:00`/`:30` — matching `docs/frontend/stock-chart/step-4.png` and `step-5.png` pixel-for-pixel in layout.
  - Backend on port 8080 and frontend dev server were both stopped at the end of this session (`kill` confirmed, `curl` to both ports now returns no response).

### Increment 2 — 2026-09-01

Implemented the 8 previously-unchecked Acceptance Criteria: switched the main panel from candles to the single-color `#3E8FD8` close-price line with a dashed `#4A5866` open-price baseline, made volume bars a uniform neutral color, and added a crosshair dot on the line — reusing the shared `KLineChart` component's `mainType`/`lineColor`/`priceReferenceLines` props added under `specs/frontend/stock-daily-chart.md` Increment 2 (no forking, no second chart implementation). The daily-K page (`specs/frontend/stock-daily-chart.md`) was not touched.

- Files changed:
  - `develop/frontend/src/components/KLineChart.tsx` — added one further optional, backward-compatible prop: `lineDotColor?: string`. When `mainType === 'line'`, the existing crosshair rendering now also draws an `<circle>` at the active index's `(x, close→y)` position in `lineDotColor` (falls back to `axisLabelText` if omitted). Candle mode (`StockDailyChartPage`, which never passes `lineDotColor`) is unaffected — the circle is gated strictly on `mainType === 'line'`.
  - `develop/frontend/src/pages/StockMinuteChartPage.tsx` — the `KLineChart` call now passes `mainType="line"`, `lineColor={COLOR.line}` (`#3E8FD8`), `lineDotColor={COLOR.lineDot}` (`#E6EDF5`), and `priceReferenceLines={dailySummary ? [{ value: dailySummary.open, color: COLOR.openBaseline, label: dailySummary.open.toFixed(2) }] : undefined}` (`#4A5866`, guarded against a null `dailySummary` — the `OUT_OF_WINDOW`/etc. states where the chart isn't rendered anyway, but keeps the expression total). The volume subplot's `colorFor` was changed from `close >= open ? volUp : volDown` to a constant `COLOR.volNeutral` (`#3A4757`); the now-unused `volUp`/`volDown` color constants were removed from the page's `COLOR` object (they remain in `StockDailyChartPage.tsx`'s own copy, untouched, since that page's volume bars still color by daily up/down per its own spec).
  - `develop/frontend/src/__tests__/StockMinuteChartPage.test.tsx` — replaced the one test that asserted candle rects (`renders candles colored red/green...`) with a test asserting the line renders, no candle rects/wicks exist, and volume bars are all neutral; updated the locked-limit-day test to check for the line path instead of a candle rect; added 6 new tests: same-line-color on an up-trending vs down-trending day, no `<linearGradient>`/filled-area under the line, the dashed `#4A5866` open baseline with its Y-axis price label, the baseline staying within the SVG's `viewBox` height when every close sits above the open (non-clipping), and the `#E6EDF5` crosshair dot appearing on click.
- Notes:
  - `npm run build` (`tsc -b && vite build`) clean; `npx vitest run` 106/106 passing (94 pre-existing across this page + `KLineChart` + concurrently-touched `StockListPage`/`StrategyTab` files, + these changes' net new/updated tests); `npx oxlint .` shows only two pre-existing warnings in `StrategyTab.tsx`/`StrategyTab.test.tsx` from the concurrently-running strategy-page work, not touched by this increment.
  - End-to-end verified against the real backend (`mvn -f develop/backend/pom.xml spring-boot:run`, port 8080) and the real MySQL data already in the database (no fixture insert/cleanup needed) — real stock `2330`/台積電, 159 daily rows through `2026-08-31`. Started the frontend dev server on port 5199 (not 5173, to avoid colliding with a concurrently-running agent's own dev server per the task's concurrency instructions) and drove it with a throwaway Playwright script (chromium already present in `node_modules` from a prior increment's verification, not re-added to `package.json`/`package-lock.json` — confirmed via `git diff --stat` on both files showing no change).
  - Queried the real minute-bar API across several real trading days to find concrete cases for the two non-clipping directions required by the AC: `2026-08-31` (`open=2395`, every `close` in `[2375, 2395]`, i.e. all closes ≤ open) and `2026-08-26` (`open=2375`, every `close` in `[2375, 2425]`, i.e. all closes ≥ open — `min(close) == open` exactly, the tightest case). Screenshotted both: in the first, the dashed baseline sits at the very top of the visible price range; in the second, at the very bottom; in both, `getBoundingClientRect`-equivalent SVG `y1` stayed within `[0, viewBox height]` — not clipped off-canvas.
  - Verified via direct SVG inspection (not just visual screenshot) in the same session: `svg path[stroke="#3E8FD8"]` count 1 with `fill="none"`; zero `rect[fill="#E04B45"|"#16A75C"]` (no candle bodies) and zero `line[stroke="#E04B45"|"#16A75C"]` (no wicks) anywhere on the minute page; zero `rect[fill="#9A3B37"|"#12784A"]` (no colored volume bars) and 262/265 `rect[fill="#3A4757"]` (all volume bars neutral, one per bar); zero `<linearGradient>`/`<radialGradient>` in the SVG (no area fill under the line); the open-price label text (`2395.00`) present among the SVG `<text>` nodes; clicking the chart produced exactly one `circle[fill="#E6EDF5"]` at the crosshair's active point, with the tooltip showing full `開/高/低/收/對開盤/成交量` for that minute.
  - Loaded `/stocks/2330/daily` in the same session and confirmed by direct SVG inspection that it still renders 127 candle rects (`fill="#E04B45"`/`"#16A75C"`) and zero `path[stroke="#3E8FD8"]` — the daily page's main chart is unaffected by this increment, as required by the last AC.
  - Backend and frontend dev processes were both stopped at the end of this session (`pkill` on `spring-boot:run` and `vite --port 5199`; confirmed with `ps aux`/`lsof` showing neither process nor either port listening). No fixture data was inserted or needed cleanup — all verification reused real data already present in the database.
