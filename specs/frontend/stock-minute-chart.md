---
status: pending
title: "個股分 K 線圖頁"
requirement: "前端 K 線瀏覽 — 在日 K 圖上連點兩下某一天後，以 K 線圖展示該交易日的分 K"
depends_on: [stock-daily-chart]
---

# 個股分 K 線圖頁 — Frontend Spec

## Overview

展示**單一交易日內**的分鐘 K 線走勢，是日 K 頁連點兩下某根 K 棒後的去處。沿用 `specs/frontend/stock-daily-chart.md` 定義的共用 K 線圖元件，X 軸由日期改為當日時間，副圖只保留成交量（分鐘級的 MACD／KD 不在本階段範圍）。

路由：`/stocks/{stockId}/minute/{tradeDate}`，`tradeDate` 格式 `YYYY-MM-DD`。

本頁的關鍵設計課題不是繪圖，而是**誠實呈現「這天的分 K 拿不到」**：資料來源只提供最近 30 天的分鐘資料（見 `specs/backend/stock-minute-price.md`），使用者在日 K 上點一個三個月前的 K 棒是完全正常的操作，該情境必須有明確的說明，而不是轉圈或空圖。

## Requirements

### 頁面結構

由上而下：

1. **頁首列** — 「← 返回日 K」連結、股票代號與名稱、該交易日日期（大字）、週期選擇器、「重新整理」按鈕。
2. **當日概況條** — 該交易日的日線資訊（開、高、低、收、成交量）。
3. **主圖（分鐘蠟燭圖）+ 成交量副圖**。
4. **前後交易日切換** — 圖表下方的「← 前一交易日」／「後一交易日 →」。

### 週期選擇器

按鈕群組：`1 分`（預設）/ `5 分` / `15 分` / `30 分` / `60 分`，對應 API 的 `interval` 參數（`1`/`5`/`15`/`30`/`60`）。切換時重新請求並重繪。

### 主圖：分鐘蠟燭圖

- 每根 K 棒對應一個 `interval` 分鐘區間，資料為 `bars[]` 的 `open`／`high`／`low`／`close`。
- **X 軸為類別軸**，只排列實際有 K 棒的時間；無成交的分鐘不留空格（API 本就不回傳這些分鐘）。X 軸標籤為 `HH:mm`，於整點與半點加粗顯示。
- **紅漲綠跌**，判準與日 K 一致：`close >= open` 為紅、`close < open` 為綠。
- Y 軸為價格，範圍取當日 `bars` 的最高與最低價並各留 5% 邊距。當日僅有一個價位（漲停或跌停鎖死）導致高低相同時，Y 軸須以該價位為中心給出固定高度的範圍，不可產生零高度的座標軸。
- 十字準星與 tooltip 行為同日 K 頁；tooltip 內容為：時間、開、高、低、收、與**當日開盤價**相比的漲跌幅、成交量（張）。

### 成交量副圖

柱狀圖，柱色依該根 K 棒漲跌（紅／綠），與主圖共用 X 軸與十字準星。

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

沿用 `specs/frontend/stock-daily-chart.md` 定義的圖表元件：本頁傳入的 X 軸標籤為 `HH:mm`、副圖僅一個成交量圖、不傳入 K 棒雙擊回呼（分 K 沒有下一層可鑽）；單擊釘選的行為仍由該元件提供，本頁一併沿用。K 棒配色、十字準星、tooltip 版面一律由該元件提供，不在本頁另行定義。

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
| 上漲 K 棒與數值（紅） | `#E04B45` |
| 下跌 K 棒與數值（綠） | `#16A75C` |
| 平盤數值 | `#93A4B8` |
| 成交量柱（漲） | `#9A3B37` |
| 成交量柱（跌） | `#12784A` |
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
- [ ] 由日 K 頁連點兩下某根 K 棒後，導向 `/stocks/{stockId}/minute/{該日期}` 並顯示該日分 K 圖
- [ ] 主圖為蠟燭圖，`close >= open` 的 K 棒為紅色 `#E04B45`、`close < open` 為綠色 `#16A75C`
- [ ] X 軸標籤為 `HH:mm`，首根為 `09:00`、末根不晚於 `13:30`
- [ ] 成交量副圖與主圖共用 X 軸，十字準星在兩張圖上同步移動
- [ ] Tooltip 顯示時間、開高低收、相對當日開盤的漲跌幅、成交量
- [ ] 週期切換為 `5 分` 後重新請求並重繪，K 棒數量約為 1 分 K 的五分之一
- [ ] 當日概況條顯示 `dailySummary` 的開高低收與成交量
- [ ] `dataStatus` 為 `OUT_OF_WINDOW` 時顯示「資料來源僅提供最近 30 天的分鐘資料…」，**不出現重試按鈕**，且當日概況條仍顯示
- [ ] `dataStatus` 為 `NO_DATA` / `NOT_A_TRADING_DAY` / `FETCH_FAILED` 時各顯示不同文案，四種狀態的訊息互不相同
- [ ] `dataStatus` 為 `FETCH_FAILED` 時顯示回應的 `message` 與「重試」按鈕，點擊後以 `refresh=true` 重新請求
- [ ] 載入中顯示「正在取得當日分鐘資料…」說明文字，而非僅一個轉圈圖示
- [ ] 「重新整理」按鈕僅在 `tradeDate` 為今日時出現；其他日期不顯示
- [ ] 「前一交易日」導向真正的前一個交易日（跨過週末），而非日期減一天
- [ ] 相鄰交易日查詢失敗時，前後切換按鈕 disabled，但分 K 圖仍正常顯示
- [ ] 當日高低價相同（漲停鎖死）時圖表仍正常繪出，Y 軸不塌陷為零高度
- [ ] 手動輸入非法日期的網址時顯示「網址中的日期無效」，而非空圖或無限載入
- [ ] 在瀏覽器強制 `prefers-color-scheme: dark` 與 `light` 兩種偏好下截圖比對，頁面與圖表配色完全相同
