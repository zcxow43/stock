---
status: done
title: "個股日 K 線圖頁"
requirement: "前端 K 線瀏覽 — 從股票清單點選一檔後，以 K 線圖展示該檔的日 K；單擊 K 棒釘選該日資訊，連點兩下才進入該日分 K"
depends_on: [stock-list]
---

# 個股日 K 線圖頁 — Frontend Spec

## Overview

以標準 K 線圖（蠟燭圖）展示單一股票的日線走勢，下方疊加成交量、MACD、KD 三個副圖。使用者可在此切換觀察區間，並**連點兩下任一根 K 棒進入該日的分 K 頁**（`specs/frontend/stock-minute-chart.md`）。

路由：`/stocks/{stockId}/daily`。

本頁同時定義**共用的圖表元件**：分 K 頁沿用同一個元件的座標軸、十字準星、tooltip 與點選行為，只是餵入不同週期的資料。兩頁各自實作一份座標軸與互動，最終必然在配色、十字準星行為、tooltip 版面上出現不一致。

**主圖的繪製型態則由各頁指定**：本頁（日 K）是蠟燭圖，分 K 頁是現價流線圖（見 `specs/frontend/stock-minute-chart.md` 的說明——日內兩百多根蠟燭會擠成紅綠雜訊，看不出當天走向）。共用的是軸與互動，不是圖形本身。

## Requirements

### 頁面結構

由上而下：

1. **頁首列** — 「← 返回清單」連結、股票代號與名稱、市場標籤、最新收盤價與漲跌（大字），右側為區間選擇器。
2. **區間摘要條** — 一列統計數字。
3. **主圖（蠟燭圖）+ 三個副圖**，共用同一條 X 軸與十字準星。
4. **操作提示** — 「連點兩下任一根 K 棒可查看當日分 K」。

### 區間選擇器

按鈕群組：`1 個月` / `3 個月`（預設）/ `6 個月` / `1 年` / `自訂`。

- 選擇 `自訂` 時展開兩個日期輸入（起日、迄日）。
- 起日晚於迄日時，於輸入下方顯示「起日不可晚於迄日」，**不送出請求**。
- 其餘選項換算為 `startDate` = 今日往前推對應月數、`endDate` 省略（由後端取庫內最新交易日）。

### 主圖：蠟燭圖

- 每根 K 棒對應一個交易日，繪出開、高、低、收四個價格。
- **X 軸為類別軸而非時間軸**：只排列有交易的日期，週末與停牌日不留空隙。以時間軸繪製會在每個週末留下兩格空白，一年下來圖上超過三分之一是空的。
- **紅漲綠跌（台股慣例）**：`close >= open` 為紅色實心 K 棒，`close < open` 為綠色實心 K 棒。上下影線與 K 棒同色。
- Y 軸為價格，範圍依區間內的最高與最低價自動決定並各留 5% 邊距；刻度標籤 2 位小數。
- 滑鼠移動時顯示十字準星，X／Y 軸端點顯示對應的日期與價格標籤。

### 副圖

三個副圖與主圖共用 X 軸，垂直堆疊，各自獨立的 Y 軸：

| 副圖 | 內容 |
|---|---|
| 成交量 | 柱狀圖，柱色依當日漲跌（紅／綠）；Y 軸以「張」為單位標示（成交股數 ÷ 1000），刻度以 K／M 縮寫 |
| MACD | `dif` 線、`dea` 線、`osc` 柱狀圖（正值紅、負值綠），並繪出 0 軸水平線 |
| KD | `k`、`d`、`j` 三條線，並繪出 20 與 80 兩條水平參考線 |

指標值為 `null` 的日期，其線段**斷開**（不與前後點連接），柱狀圖不繪製該根。以 0 代替 `null` 會在圖上畫出一段假的暴跌。

### Tooltip

十字準星停在某一根 K 棒時，顯示浮動資訊框，內容為：

日期、開、高、低、收、漲跌額與漲跌幅（依漲跌著色）、成交量（張）、`DIF`／`DEA`／`OSC`、`K`／`D`／`J`。

指標為 `null` 時該列顯示「—」。價格 2 位小數、指標 4 位小數。

### 區間摘要條

取自 API 的 `items[0].summary`（「交易日數」除外，該欄位位於 `items[0].tradingDays`），依序顯示：

| 標籤 | 欄位 | 格式 |
|---|---|---|
| 區間開盤 | `firstOpen` | 2 位小數 |
| 最新收盤 | `lastClose` | 2 位小數 |
| 區間最高 | `highest`（下方小字為 `highestDate`） | 2 位小數 |
| 區間最低 | `lowest`（下方小字為 `lowestDate`） | 2 位小數 |
| 區間漲跌 | `changeAmount` / `changePercent` | 依漲跌著色，正值前綴 `+` |
| 交易日數 | `tradingDays`（item 層級，非 `summary` 之下） | 整數 |
| 總成交量 | `totalVolume` | 千分位，單位「張」 |
| MACD 交叉 | `macdGoldenCross` / `macdDeathCross` | 「黃金 N ／ 死亡 M」 |
| KD 交叉 | `kdGoldenCross` / `kdDeathCross` | 「黃金 N ／ 死亡 M」 |

交叉次數為 `null`（尚未運算指標）時顯示「—」，**不可顯示為 0**——`0` 意為「算過但沒有交叉」，兩者意義不同。

### 指標尚未運算的呈現

後端在指標尚未重算時仍回傳完整的 OHLC，指標欄位為 `null`（契約見 `specs/backend/stock-indicator-statistics.md`）。此時：

- 主圖與成交量副圖**照常繪製**。
- MACD 與 KD 副圖顯示置中提示「指標尚未運算」，不繪製任何線段。
- 摘要條的指標相關欄位顯示「—」。

### 暖身不足提示

回應中 `warmupSufficient` 為 `false` 時，於區間摘要條上方顯示警示橫幅：「本檔歷史資料不足 250 個交易日，MACD／KD 數值尚未收斂，僅供參考。」橫幅可關閉，切換股票後重新出現。

### 點選 K 棒：單擊釘選、雙擊進入分 K

主圖的 K 棒有**兩種**互動，對應兩種不同的意圖：

| 操作 | 行為 |
|---|---|
| **單擊**一根 K 棒（或其對應的 X 軸位置） | 釘選該根 K 棒——十字準星與 tooltip 固定在該日，不再隨滑鼠移動；同時該根 K 棒加上選取外框。**不導覽、不改變網址。** |
| **連點兩下**同一根 K 棒 | 導向 `/stocks/{stockId}/minute/{tradeDate}`，`tradeDate` 為該 K 棒的 `YYYY-MM-DD` |

為什麼進入分 K 是雙擊而不是單擊：日 K 圖上滑鼠會頻繁掃過 K 棒，單擊是使用者在圖上「指著某一天看數字」最自然的動作。若單擊即導覽，任何一次想細看某日數值的點擊都會把人踢出當前頁面，且回來後區間與捲動位置全部重置。雙擊是明確的「鑽進去」意圖，兩者分開才不會互相干擾。

實作要求：

- 使用瀏覽器原生的雙擊事件判定，**不可自行以計時器實作**「300ms 內第二次點擊」的判斷——手寫的門檻值會與使用者的系統雙擊速度設定不一致。
- 雙擊必然會先觸發一次（或兩次）單擊事件。單擊處理只做釘選，因此這個先後順序**不會**造成誤導覽；但釘選狀態必須在導覽前就已套用，不可因導覽而閃爍。
- 雙擊區域必須抑制瀏覽器預設的文字選取（雙擊會選取圖表上的文字標籤，視覺上像是壞掉）。
- 再次單擊已釘選的 K 棒，或按 `Esc`，取消釘選，十字準星恢復跟隨滑鼠。
- 滑鼠停留於 K 棒上時，游標為 `pointer`。
- 主圖下方常駐一行提示文字「連點兩下任一根 K 棒可查看當日分 K」。沒有這行提示，這個互動不會被發現——雙擊比單擊更不容易被猜到，這行字比單擊時更必要。

### 畫面狀態

| 狀態 | 呈現 |
|---|---|
| 載入中 | 圖表區顯示骨架方塊，頁首的代號名稱在取得後即先顯示 |
| 正常 | 完整圖表 |
| 該檔在區間內無行情（`tradingDays` 為 `0`、`summary` 為 `null`） | 圖表區顯示「此區間內沒有行情資料」與「改看近 1 年」按鈕；摘要條隱藏 |
| 股票代號不存在（`404` / `STOCK_NOT_FOUND`） | 全頁顯示「找不到此股票代號」與「返回清單」按鈕 |
| 請求失敗 | 圖表區顯示錯誤訊息與「重新載入」按鈕 |

## Implementation Details

### API 整合

| 時機 | 呼叫 |
|---|---|
| 進頁 | `GET /api/stocks/{stockId}` — 取頁首的名稱、市場、最新收盤與漲跌 |
| 進頁、切換區間 | `GET /api/stocks/statistics?stockIds={stockId}&includeSeries=true&startDate={起日}&endDate={迄日}` |

統計 API 回傳 `items[]`，本頁固定只送單一 `stockId`，故取 `items[0]`。使用的欄位：

- `items[0].tradingDays`、`warmupSufficient`
- `items[0].summary.*` → 摘要條
- `items[0].series[]` → 圖表，每列含 `tradeDate`、`open`、`high`、`low`、`close`、`volume`、`dif`、`dea`、`osc`、`k`、`d`、`j`

契約見 `specs/backend/stock-indicator-statistics.md` 與 `specs/backend/stock-catalog.md`。

錯誤回應處理：

| `code` | 前端行為 |
|---|---|
| `STOCK_NOT_FOUND` | 顯示「找不到此股票代號」全頁狀態 |
| `INVALID_DATE_RANGE` | 於自訂區間輸入下方顯示「起日不可晚於迄日」（前端已先行檢核，此為後備） |
| 其他／網路錯誤 | 顯示「載入失敗，請稍後再試」與「重新載入」按鈕 |

`items` 為空陣列時，視同「此區間內沒有行情資料」。

### 共用 K 線圖元件

主圖與副圖以一個可重複使用的圖表元件實作，對外參數至少包含：資料陣列、X 軸標籤陣列、**主圖型態（蠟燭／折線）**、副圖設定（線／柱、資料、顏色）、水平參考線設定、單擊回呼與雙擊回呼（兩者分開，不可合併為單一回呼）。

**主圖型態必須是元件的參數，不能寫死成蠟燭。** 本頁傳入蠟燭；分 K 頁（`specs/frontend/stock-minute-chart.md`）傳入折線，並額外傳入一條開盤價水平參考線、X 軸標籤由日期改為時間、副圖只有成交量、不傳雙擊回呼。折線型態下元件只讀每筆資料的 `close`，以單一線色繪製，不套用蠟燭的紅漲綠跌配色。

繪圖方式（自繪或引入圖表函式庫）由 dev agent 依 `env.md` 的前端技術棧決定；本 spec 只約束外觀與互動行為。

## Visual Style

**本頁所有顏色為固定值，不隨 `prefers-color-scheme`、OS 主題或瀏覽器偏好改變。** 一律以字面 hex 指定，不得使用會隨系統偏好變動的主題變數或色彩 token。

| 元素 | 色碼 |
|---|---|
| 頁面背景 | `#0F1620` |
| 面板／圖表容器背景 | `#16202C` |
| 頁首列背景 | `#1B2836` |
| 邊框、面板分隔線 | `#26333F` |
| 主要文字（價格、標題） | `#E6EDF5` |
| 次要文字（標籤、軸文字） | `#93A4B8` |
| 弱化文字（「—」、提示文字） | `#6B7C90` |
| 「← 返回清單」連結文字 | `#3E8FD8` |
| 上漲 K 棒與數值（紅） | `#E04B45` |
| 下跌 K 棒與數值（綠） | `#16A75C` |
| 平盤數值 | `#93A4B8` |
| 成交量柱（漲） | `#9A3B37` |
| 成交量柱（跌） | `#12784A` |
| MACD `DIF` 線 | `#E8B84B` |
| MACD `DEA` 線 | `#4B9FE8` |
| MACD `OSC` 柱（正／負） | `#E04B45` / `#16A75C` |
| KD `K` 線 | `#E8B84B` |
| KD `D` 線 | `#4B9FE8` |
| KD `J` 線 | `#C77DD8` |
| 圖表格線 | `#1F2C3A` |
| 零軸／參考線（MACD 0 軸、KD 20/80） | `#3A4757` |
| 十字準星線 | `#6B7C90` |
| 已釘選 K 棒的選取外框 | `#3E8FD8` |
| 十字準星軸標籤背景／文字 | `#3A4757` / `#E6EDF5` |
| Tooltip 背景／邊框／文字 | `#1B2836` / `#3A4757` / `#E6EDF5` |
| 區間按鈕（未選）背景／文字／邊框 | `#1B2836` / `#93A4B8` / `#26333F` |
| 區間按鈕（已選）背景／文字 | `#3E8FD8` / `#FFFFFF` |
| 區間按鈕 hover 背景 | `#223347` |
| 日期輸入背景／邊框／focus 邊框 | `#0F1620` / `#26333F` / `#3E8FD8` |
| 「上市」標籤文字／邊框 | `#4B9FE8` / `#2C4A66` |
| 「上櫃」標籤文字／邊框 | `#C77DD8` / `#4A2C5A` |
| 暖身警示橫幅文字／背景／邊框 | `#E8C766` / `#3A2E12` / `#8A6D1F` |
| 錯誤訊息文字／背景／邊框 | `#F09A94` / `#3A1C1A` / `#8A3A34` |
| 骨架方塊底色 | `#1D2A38` |
| 副圖「指標尚未運算」提示文字 | `#6B7C90` |

## Acceptance Criteria
- [x] `/stocks/2330/daily` 顯示頁首（代號、名稱、市場標籤、最新收盤與漲跌）與預設 3 個月區間的 K 線圖
- [x] 主圖為蠟燭圖，`close >= open` 的 K 棒為紅色 `#E04B45`、`close < open` 為綠色 `#16A75C`
- [x] X 軸只排列有交易的日期：連續兩個交易日之間不因週末產生空格
- [x] 成交量、MACD、KD 三個副圖與主圖共用同一條 X 軸，十字準星在四張圖上同步移動
- [x] MACD 副圖含 `DIF` 線、`DEA` 線、`OSC` 柱與 0 軸；KD 副圖含 `K`／`D`／`J` 三線與 20／80 參考線
- [x] Tooltip 顯示該日的日期、開高低收、漲跌額與漲跌幅、成交量、`DIF`/`DEA`/`OSC`、`K`/`D`/`J`，價格 2 位小數、指標 4 位小數
- [x] 切換區間為「1 年」後重新請求並重繪，K 棒數量與 `series` 長度一致
- [x] 自訂區間中起日晚於迄日時顯示錯誤訊息且**不發出請求**
- [x] **連點兩下任一根 K 棒導向 `/stocks/{stockId}/minute/{該 K 棒的日期}`**，且滑鼠停留於 K 棒上時游標為 `pointer`
- [x] **單擊一根 K 棒只釘選該日的十字準星與 tooltip，網址不變、不發生導覽**
- [x] 釘選後十字準星不再隨滑鼠移動；再次單擊同一根 K 棒或按 `Esc` 取消釘選
- [x] 雙擊 K 棒不會在圖表上留下被選取（反白）的文字
- [x] 主圖下方常駐顯示「連點兩下任一根 K 棒可查看當日分 K」
- [x] 指標欄位為 `null` 的資料：主圖與成交量圖正常繪製，MACD／KD 副圖顯示「指標尚未運算」且不畫出任何線段（不以 0 代替）
- [x] 區間中間某幾日指標為 `null` 時，指標線段在該處斷開而非連成直線
- [x] `warmupSufficient` 為 `false` 時顯示暖身警示橫幅，且可關閉
- [x] 交叉次數為 `null` 時摘要條顯示「—」，為 `0` 時顯示「0」
- [x] 區間內無行情時顯示「此區間內沒有行情資料」與「改看近 1 年」按鈕，而非空白圖表
- [x] 不存在的股票代號顯示「找不到此股票代號」與「返回清單」按鈕
- [x] 在瀏覽器強制 `prefers-color-scheme: dark` 與 `light` 兩種偏好下截圖比對，頁面與圖表配色完全相同


---

- [x] 共用圖表元件的主圖型態為對外參數，可傳入蠟燭或折線；折線型態下只讀 `close`、以單一線色繪製，不套用紅漲綠跌
- [x] 共用圖表元件可接受水平參考線設定，並將其值納入 Y 軸範圍計算
- [x] 本頁（日 K）主圖仍為蠟燭圖，紅漲綠跌與既有行為完全不變

## Execution Result
- Status: DONE
- Files changed:
  - `develop/frontend/src/pages/StockDailyChartPage.tsx` (new) — page component: header (code/name/market tag/last close+change), range selector (1M/3M/6M/1Y/custom with inline validation), warmup banner (dismissible, resets per stock), summary strip, loading/no-data/not-found/error states, pin/unpin + Esc handling, double-click → `/stocks/{stockId}/minute/{tradeDate}` navigation (pin applied before navigating), tooltip content builder
  - `develop/frontend/src/pages/StockDailyChartPage.css` (new) — literal-hex dark theme per this spec's Visual Style table; no `prefers-color-scheme` anywhere
  - `develop/frontend/src/components/KLineChart.tsx` (new) — the shared candlestick chart element required by this spec ("共用 K 線圖元件"): category X-axis, candle body/wick rendering, N stacked subplots (line/bar series, reference lines, zero-baseline domain forcing for bar series, per-subplot "unavailable" centered message), synced crosshair across all panels, native single-click vs `onDoubleClick` separation (not a hand-rolled timer), HTML tooltip overlay positioned to avoid right-edge clipping. Designed to be reused as-is by `specs/frontend/stock-minute-chart.md` (time labels instead of dates, volume-only subplot)
  - `develop/frontend/src/components/KLineChart.css` (new) — `user-select: none` on the chart root (prevents the double-click text-selection artifact), tooltip box styling, literal hex colors passed in as props by the page (component itself holds no hardcoded palette)
  - `develop/frontend/src/api/statistics.ts` (new) — `fetchStockStatistics`, `StatisticsResponse`/`StatisticsItem`/`StatisticsSummary`/`StatisticsSeriesRow` types matching `StockStatisticsController`/`StatisticsResponseDto` family (`specs/backend/stock-indicator-statistics.md`)
  - `develop/frontend/src/api/stocks.ts` — added `StockDetail` type and `fetchStockDetail` for `GET /api/stocks/{stockId}` (header data), reusing the existing `ApiError`/`ApiErrorBody` machinery from the stock-list spec
  - `develop/frontend/src/App.tsx` — replaced the `StockDailyChartPagePlaceholder` stub route with the real `StockDailyChartPage`
  - `develop/frontend/src/pages/StockDailyChartPagePlaceholder.tsx` (deleted) — placeholder from `stock-list.md`, now superseded
  - `develop/frontend/src/__tests__/StockDailyChartPage.test.tsx` (new) — 16 unit tests (vitest + @testing-library/react) covering header rendering, candle up/down coloring, summary strip formatting, null-vs-zero cross counts, warmup banner show/dismiss, "指標尚未運算" when a whole range has no indicator rows (candles still drawn), single-click pin/unpin (no navigation, no URL change), Esc-unpins, native double-click → minute route navigation, `kc-svg` cursor class, persistent hint text, 1-year range refetch, custom-range validation blocking the request, no-data state, 404 not-found state, generic error+reload state
  - `develop/frontend/package.json` / `package-lock.json` — no net new runtime dependency (reused `react-router-dom` from `stock-list.md`); `playwright` was added as a throwaway devDependency for manual/E2E screenshot verification and removed again before finishing
- Notes:
  - `npm run build` (tsc -b && vite build) and `npm test` (`vitest run`, 28/28 passing — 12 pre-existing `StockListPage` + 16 new) both green; `npx oxlint .` clean.
  - Chart implementation is hand-rolled SVG (no charting library added) — `env.md` names no charting library for the frontend stack, and a bespoke component gives full, exact control over the literal-hex palette, the native-dblclick contract, and the shared-component reuse this spec mandates for the minute-chart page.
  - End-to-end verified against the real backend: started it with `mvn -f develop/backend/pom.xml spring-boot:run`, inserted throwaway fixture data directly via `mysql` — `2330`/台積電 with 400 synthetic-but-realistic daily OHLCV rows and a full `POST /api/stocks/indicators/rebuild` (`mode=FULL`), `1101`/台泥 with no price rows at all (no-data-in-range case), `2454`/聯發科 with 60 price rows and **no** indicator rebuild (indicator-not-yet-computed case), plus a deliberate mid-range deletion of `stock_daily_indicator` rows for 2330 (2026-07-10..14) to prove line breaks instead of connecting across `null`s. Drove the real dev server (`npm run dev`, proxied to the real backend) with a throwaway Playwright script (removed afterward, along with the `playwright` devDependency) and captured screenshots for: default 3-month view, 1-year view (which naturally exercises the `warmupSufficient=false` banner and its dismiss button since the synthetic 400-day history isn't 250 trading days deep before the 1-year window's start), hover crosshair/tooltip, single-click pin, custom-range validation error, indicator-not-yet-computed (both MACD and KD panels showing "指標尚未運算" while candles/volume render normally), the mid-range null gap (visibly broken DIF/DEA/K/D/J lines), the 404 not-found full-page state, and the no-data-in-range state. Also drove a real native double-click via `page.mouse.dblclick`, confirming the URL changed to `/stocks/2330/minute/2026-07-15` (a real trading date resolved from the clicked bar) and that `window.getSelection().toString()` was empty immediately after (no text-selection artifact), and confirmed the SVG's computed `cursor` style is `pointer`.
  - `prefers-color-scheme` independence verified by rendering the same page twice — once with Playwright's `colorScheme: 'dark'` and once with `'light'` — and diffing the two full-page screenshots: **byte-for-byte identical** (`md5` match). Confirmed by `grep` that no CSS file in this feature (`StockDailyChartPage.css`, `KLineChart.css`) references `prefers-color-scheme`; every color reaching the SVG is a literal hex prop supplied by the page from its `COLOR` constant.
  - A real layout bug was caught and fixed during this verification pass (not visible from code review alone): the KD subplot was being clipped because the chart's total SVG height was computed from the last subplot's *top* offset instead of its *bottom* — the panel's content silently overflowed past the `viewBox`/`height`. Fixed in `KLineChart.tsx` by tracking the running `bottom` cursor through the panel-layout loop instead of reusing the last `panelTops` entry. Also tightened the X-axis tick-selection logic (`xTickIndices`) so the always-included final tick no longer overlaps the previous evenly-spaced tick when the bar count isn't a clean multiple of the tick step (e.g. 66 daily bars against `maxXTicks=10` previously rendered two overlapping date labels at the right edge).
  - One fixture-authoring mistake surfaced and was corrected along the way, worth noting since it looked like an app bug at first: an initial `mysql -e "INSERT INTO stock ... VALUES ('2330','台積電',...)"` run through a non-UTF-8 shell produced double-encoded (mojibake) `stock_name` bytes in the database; the page correctly rendered whatever UTF-8 bytes the backend returned, so the garbled text was purely a fixture-insertion artifact (fixed with `--default-character-set=utf8mb4` and a corrective `UPDATE`), not a frontend rendering bug — all other CJK text on the page (labels, hints, tags) rendered correctly throughout, confirming the app's own text pipeline was never at fault.
  - Backend (`mvn spring-boot:run`, port 8080) and frontend (`npm run dev`, port 5173) dev processes were both stopped before finishing; all fixture rows (`2330`, `1101`, `2454` and their `stock_daily_price`/`stock_daily_indicator`/`stock_sync_progress` rows) were deleted afterward — `stock`, `stock_daily_price`, `stock_daily_indicator`, and `stock_sync_progress` all confirmed back at 0 rows.
  - `docker/launch.json` already contained a correct `frontend` entry (`npm --prefix develop/frontend run dev`, port 5173) alongside the existing `backend` entry from a concurrent scaffold — left untouched. `.claude/launch.json` symlink to `../docker/launch.json` was already present and valid.

### Increment 2 — 2026-09-01

Implemented the 3 previously-unchecked Acceptance Criteria (共用圖表元件的主圖型態外部化為蠟燭／折線參數，並支援水平參考線納入 Y 軸範圍；本頁行為不變). This is a pure refactor/generalization of the shared `KLineChart` component so `specs/frontend/stock-minute-chart.md` can reuse it for its close-price line + open-price baseline; no behavior of this page (`StockDailyChartPage`) changed.

- Files changed:
  - `develop/frontend/src/components/KLineChart.tsx` — added three new optional props, all additive with backward-compatible defaults:
    - `mainType?: 'candle' | 'line'` (default `'candle'`) — selects the main-panel drawing style. `'candle'` renders exactly the pre-existing OHLC candlestick body/wick/pin-outline logic, untouched. `'line'` renders a single `<path>` built solely from each bar's `close` (via the existing `buildLinePath` helper, reused verbatim), stroked with `lineColor`, with no red/green up-down coloring and no reading of `open`/`high`/`low` for drawing.
    - `lineColor?: string` — stroke color used only when `mainType === 'line'`; ignored in candle mode. Falls back to `upColor` if omitted (defensive default, not expected to be relied on by callers).
    - `priceReferenceLines?: PriceReferenceLine[]` (new exported type `{ value: number; color: string; label?: string }`) — horizontal dashed reference lines drawn across the main price panel. Each line's `value` is folded into the main panel's Y-axis domain via the existing `computeDomain(valueArrays, refs, paddingRatio)` helper (previously called with an empty `refs` array for the price panel; now passed `priceRefValues = priceReferenceLines?.map(r => r.value) ?? []`), so a reference value outside the bars' high/low range still expands the visible Y range to include it (with the same padding ratio applied to the union), rather than being clipped off-canvas. Each line renders in its own `color` (not the shared `referenceLineColor` used by subplots, since the minute-chart's open baseline needs a distinct color `#4A5866` from the daily chart's shared `referenceLineColor` `#3A4757`), with an optional `label` text at its Y position on the right axis.
  - `develop/frontend/src/__tests__/KLineChart.test.tsx` (new) — 6 unit tests exercising the two new capabilities directly against the component (not through a page): (1) default/candle-mode regression — red/green rects render, no line path; (2) line mode — mixed up/down closes produce zero candle rects and exactly one single-color path built from `close` only; (3) a `priceReferenceLines` entry renders a `<line>` in its given color; (4)/(5) a reference value far below/above every bar's close still lands within `[0, priceHeight]` in the rendered SVG's `y1` coordinate — i.e. it is not clipped out of the visible range — and its optional `label` renders; (6) explicit regression check that omitting all three new props reproduces the exact pre-refactor candle output with zero reference-line elements.
  - No other file changed. `develop/frontend/src/pages/StockDailyChartPage.tsx` was not touched — it does not pass `mainType`, `lineColor`, or `priceReferenceLines`, so it continues to render through the `mainType = 'candle'` default path exactly as before.
- Notes:
  - **New component parameters for the next spec's consumer** (`specs/frontend/stock-minute-chart.md`): pass `mainType="line"` with `lineColor="#3E8FD8"` for the close-price line, and `priceReferenceLines={[{ value: dailySummary.open, color: '#4A5866' }]}` (optionally with a formatted `label`) for the open-price baseline — its value will automatically be included in the Y-axis domain calculation so it stays visible even when every close is above or below it.
  - `npm run build` (`tsc -b && vite build`) and `npx vitest run` (89/89 passing — 83 pre-existing + 6 new) both green; `npx oxlint .` shows only two pre-existing, unrelated warnings in `StrategyTab.tsx`/`StrategyTab.test.tsx` (not touched by this increment).
  - End-to-end verified against the real backend and real MySQL data already present in the database (no fixture insert/cleanup needed this run): started the backend (`mvn -f develop/backend/pom.xml spring-boot:run`, port 8080) and the frontend dev server (`npm run dev`, port 5173), then loaded `/stocks/2330/daily` in a headless Chromium (Playwright, installed with `npm install --no-save` so `package.json`/`package-lock.json` show no diff — verified via `git diff --stat`) against the real 2330 data (159 daily price rows, 158 indicator rows) already in the database. Screenshot confirms the page renders identically to its pre-refactor appearance: red-up/green-down candles, warmup banner, summary strip, volume/MACD/KD subplots, all colors matching. Both dev processes were stopped afterward (confirmed via `curl` timing out on both ports); no test data was inserted or needed cleanup since real data was reused as-is.
  - Line-mode and reference-line correctness (the part not exercisable through the daily-K page, since it never passes those props) was verified via the new unit tests calling `KLineChart` directly with `mainType="line"` and `priceReferenceLines` — this was the "temporary harness" called for by the task; no scaffolding beyond the (kept) unit test file was needed, so nothing further required removal.
