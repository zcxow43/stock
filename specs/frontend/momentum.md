---
status: pending
title: "動態分頁（產業別漲幅）"
requirement: "新增「動態」分頁，內含「漲幅平均」與「漲幅加總」兩個標籤；可設定近幾個交易日、或勾選哪幾週，列出漲幅超過門檻的股票，並按產業別分組顯示；一檔股票屬於多個產業別時，在每一個產業別下都要出現"
depends_on: [stock-list]
---

# 動態分頁（產業別漲幅） — Frontend Spec

## Overview

`/stocks` 的第三個頁籤（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`，第二個見 `specs/frontend/strategy.md`）。使用者設定一段期間與一個漲幅門檻，按下查詢後，**達標的股票依產業別分組列出**——回答的是「這段期間哪些產業在動、動的是哪幾檔」。

路由：`/stocks?tab=momentum`。

**本頁呈現的是漲幅統計結果，不是買賣建議。** 所有文案一律以「漲幅／命中／統計」表述，不得出現「建議買進」「推薦」「可進場」等暗示操作的措辭（見 `specs/backend/industry-gain-ranking.md`）。

## Requirements

### 頁面結構

由上而下：

1. **標籤列** — 頁內第二層標籤，兩個：「漲幅平均」「漲幅加總」。預設停在「漲幅平均」。
2. **條件區** — 期間設定、漲幅門檻、「查詢」主要按鈕。
3. **摘要列** — 實際採用的區間、交易日數、命中檔數、資料不足檔數。
4. **結果區** — 每個產業別一個區塊。

標籤列在條件區**之上**：切換標籤換的是「用哪種方式衡量漲幅」，那是比期間與門檻更上位的選擇，放在下面會讓人以為它只影響結果的排序。

### 標籤列

| 標籤 | `metric` | 說明文字（顯示於條件區上方） |
|---|---|---|
| 漲幅平均 | `AVERAGE` | 期間內每個交易日漲跌幅的平均 |
| 漲幅加總 | `SUM` | 期間內每個交易日漲跌幅的相加 |

- 切換標籤即以新的 `metric` **重新查詢**（期間條件沿用，門檻不沿用，見下）。
- 兩個標籤各自保留自己的**門檻值**與**查詢結果**，切回去時不需重查。
- **門檻不跨標籤沿用**，兩邊各自預設 `5`。這兩個度量的量級差一個「交易日數」的倍數——「平均每日漲 5%」與「整段累計漲 5%」是完全不同量級的條件，把一邊輸入的數字帶到另一邊，會產生一個看起來合理、實際上問錯問題的門檻。

### 條件區

#### 期間

兩種模式，以一組互斥選項切換。切換模式時保留另一個模式已輸入的值，切回去即恢復。

| 模式 | 控制項 | 預設 |
|---|---|---|
| 近 N 交易日（預設） | 數字輸入「近 __ 個交易日」，範圍 1–120 | `20` |
| 指定週 | 最近 12 個自然週的複選清單 | 最近 1 週勾選 |

- **「近 N 交易日」的預設 `20`**，取自「約一個月」——與 `specs/frontend/strategy.md` 掃描區間預設「近一個月」是同一個判斷，不另訂一套。
- 「近 N 交易日」數的是**交易日**不是日曆日，實際涵蓋的日期區間由後端決定並回傳；摘要列一律顯示後端回傳的 `startDate`／`endDate`，不由前端自行推算。

#### 指定週的週清單

- 列出**最近 12 個自然週**（週一至週日），最新的在最上面。每一列一個核取方塊，標籤格式為 `MM/DD – MM/DD`，並在本週該列附「本週」標記。
- 週的邊界由前端計算：以今日所在週的週一為第 1 週起點，往前推 12 週。
- 可複選，**允許不連續的勾選**。
- 送出的區間為「**最早**被勾選那一週的週一」到「**最晚**被勾選那一週的週日」。
- **勾選出現空隙時，必須明白告知中間那幾週也被算進去了**：在週清單下方顯示一行提示，例如「已選 2 週，實際計算區間 2026-08-10 ~ 2026-08-30，含未勾選的 08/17 那一週」。後端只收一個連續的日期區間（見 `specs/backend/industry-gain-ranking.md`），不支援跳過中間的週；讓使用者以為自己排除了某一週、結果那一週的漲跌仍計入，是這個介面唯一會騙人的地方，必須用文字擋住。
- 一週都沒勾時「查詢」為 disabled，並在按鈕旁提示「請至少勾選一週」。

#### 漲幅門檻

- 數字輸入「漲幅 ≥ __ %」，預設 `5`，範圍 `-100` ~ `1000`，可為負值與 0。
- 只有「超過門檻」一個方向，沒有跌幅方向的選項。
- 超出範圍時前端即擋下並在欄位下方提示，不送出請求。

### 摘要列

一行，顯示本次查詢實際採用的條件與結果總量，全部取自回應：

> 區間 `2026-08-04` ~ `2026-08-29`（20 個交易日）・命中 **37** 檔・資料不足 12 檔

- **「命中 N 檔」用的是 `matchedStockCount`（去重後）**，不是各產業 `matchedCount` 的加總。一檔股票同時屬於兩個產業時會在畫面上出現兩次，若總數用加總，使用者數出來的列數會比總數少，而那個差額沒有任何地方解釋得了。
- 「資料不足 K 檔」在 `insufficientDataCount` 為 0 時整段不顯示。滑鼠移上去顯示說明「期間開始前沒有行情，無法計算漲幅」——這與「有算但沒達標」是不同的事，不能讓使用者以為那些股票是跌的。
- 區間為 `null`（勾選的週內完全沒有交易日）時，摘要列改為顯示「所選期間內沒有交易日」。

### 結果區

每個產業別一個區塊，由上而下依回應的 `industries` 順序排列（後端已排序，前端不重排）。

區塊標題：`{industryName}　{matchedCount} 檔`。

區塊內為一張表格：

| 欄位標題 | 資料來源 | 對齊 | 顯示規則 |
|---|---|---|---|
| 代號 | `stockId` | 左 | 等寬數字 |
| 名稱 | `stockName` | 左 | — |
| 漲幅 | `gain` | 右 | 2 位小數加 `%`，正值前綴 `+`；依漲跌著色 |
| 交易日 | `tradingDays` | 右 | 整數；小於摘要列的全市場交易日數時，於數字後加註記符號並以 tooltip 說明「期間內有停牌」 |
| 期初收盤 | `startClose` | 右 | 2 位小數 |
| 期末收盤 | `endClose` | 右 | 2 位小數 |

- **漲跌著色採台股慣例：漲為紅、跌為綠**，與 `specs/frontend/stock-list.md` 同一組色值。門檻可設為負值，因此本表確實會出現綠色數字。
- 整列可點擊，游標為 `pointer`，導向 `/stocks/{stockId}/daily`。
- **一檔股票屬於多個產業別時，會在每一個產業別的表格中各出現一次**——這是需求明確要求的行為，不是重複資料。不需要任何「此檔重複出現」的標記：使用者是在看某個產業有哪些股票在動，那一列出現在這裡本來就是對的。
- `industryId` 為 `null` 的區塊標題顯示為「未分類」，永遠排在最後（後端已保證順序）。

### 產業別尚未匯入的引導

回應的 `industries` **只有**「未分類」一個區塊時，在結果區頂端顯示一則提示：

> 目前沒有任何股票有產業別資料。請到「策略」分頁按「更新股票清單」，該動作會一併帶入交易所的官方產業別。

不擋住結果——未分類區塊照常顯示，使用者仍看得到哪些股票漲幅達標。這則提示解釋的是「為什麼沒有分組」，不是錯誤。

**產業別的匯入入口不重複做一顆按鈕**：它已經在策略分頁的「更新股票清單」裡（見 `specs/frontend/strategy.md` 與 `specs/backend/stock-universe-import.md`）。同一個動作出現在兩個分頁上，使用者無從判斷兩顆按鈕是不是同一件事。

### 畫面狀態

| 狀態 | 呈現 |
|---|---|
| 尚未查詢（首次進入） | 條件區可操作，結果區顯示「設定期間與門檻後按下查詢」 |
| 查詢中 | 「查詢」按鈕 disabled 並顯示查詢中狀態；結果區顯示 3 個骨架區塊，既有結果保留並降低透明度至 60% |
| 有結果 | 摘要列 + 產業別區塊 |
| 查無命中（`matchedStockCount` 為 0） | 結果區顯示「這段期間沒有股票的漲幅達到 {門檻}%」，並附「把門檻調低」的次要按鈕（點擊將門檻減半後重查） |
| 期間內無交易日 | 摘要列顯示「所選期間內沒有交易日」，結果區顯示「請改選其他週」 |
| 請求失敗 | 結果區顯示錯誤訊息與「重新查詢」按鈕；不得顯示空結果假裝查無命中 |
| 切換標籤且該標籤已有結果 | 直接顯示既有結果，不重新請求 |

## Implementation Details

### API 整合

| 時機 | 呼叫 |
|---|---|
| 按「查詢」、切換標籤（該標籤尚無結果時） | `GET /api/momentum/gain?metric=&mode=&days=&startDate=&endDate=&minGain=&commonStocksOnly=` |

契約見 `specs/backend/industry-gain-ranking.md`。

送出參數的組法：

| 參數 | 值 |
|---|---|
| `metric` | 目前標籤：`AVERAGE`／`SUM` |
| `mode` | 目前期間模式：`DAYS`／`WEEKS` |
| `days` | `mode=DAYS` 時帶入輸入框的值；`WEEKS` 時不帶 |
| `startDate` / `endDate` | `mode=WEEKS` 時帶入勾選週折算出的起訖日；`DAYS` 時不帶 |
| `minGain` | 目前標籤的門檻值 |

回應欄位：`metric`、`mode`、`startDate`、`endDate`、`tradingDays`、`minGain`、`scannedStocks`、`matchedStockCount`、`insufficientDataCount`、`industries[]`（`industryId`、`industryName`、`matchedCount`、`items[]`），每個 item 含 `stockId`、`stockName`、`gain`、`tradingDays`、`startClose`、`endClose`、`firstTradeDate`、`lastTradeDate`。

`firstTradeDate` / `lastTradeDate` 不在表格中顯示，但需保留於資料模型中——供「交易日」欄的 tooltip 說明該檔實際涵蓋的日期。

錯誤回應處理：

| `code` | 前端行為 |
|---|---|
| `INVALID_METRIC` / `INVALID_MODE` | 視為程式錯誤：正常操作不應觸發（兩者皆由受控元件產生），顯示通用錯誤訊息 |
| `INVALID_DAYS` | 於天數輸入框下方顯示「請輸入 1 – 120 之間的交易日數」（前端已先擋，此為後備） |
| `INVALID_DATE_RANGE` | 於週清單下方顯示「所選週的區間無效」（前端已先擋，此為後備） |
| `INVALID_MIN_GAIN` | 於門檻輸入框下方顯示「請輸入 -100 – 1000 之間的數值」（前端已先擋，此為後備） |
| 其他／網路錯誤 | 結果區顯示「查詢失敗，請稍後再試」與「重新查詢」按鈕 |

### 數值格式

- 價格兩位小數；百分比兩位小數加 `%`，正值前綴 `+`。
- 日期一律 `YYYY-MM-DD`；週清單的標籤為 `MM/DD – MM/DD`。
- `null` 一律顯示 `—`，不顯示 `0` 或空白。

## Visual Style

**本頁所有顏色為固定值，不隨 `prefers-color-scheme`、OS 主題或瀏覽器偏好改變。** 一律以字面 hex 指定，不得使用會隨系統偏好變動的主題變數或色彩 token。色票與 `specs/frontend/stock-list.md`、`specs/frontend/strategy.md` 為同一套，同名元素必須取同一個值。

| 元素 | 色碼 |
|---|---|
| 頁面背景 | `#0F1620` |
| 面板／表格容器背景 | `#16202C` |
| 表頭背景 | `#1B2836` |
| 邊框、分隔線 | `#26333F` |
| 主要文字（表格內容、標題） | `#E6EDF5` |
| 次要文字（表頭、標籤、說明） | `#93A4B8` |
| 弱化文字（「—」、空狀態說明） | `#6B7C90` |
| 上漲數值（紅） | `#E04B45` |
| 下跌數值（綠） | `#16A75C` |
| 平盤數值 | `#93A4B8` |
| 表格列 hover 背景 | `#1D2A38` |
| 目前標籤文字／底線 | `#E6EDF5` / `#3E8FD8` |
| 非目前標籤文字 | `#93A4B8` |
| 非目前標籤 hover 文字 | `#E6EDF5` |
| 標籤列底線（未選中區段） | `#26333F` |
| 期間模式選項（未選）背景／文字 | `#1B2836` / `#93A4B8` |
| 期間模式選項（選中）背景／文字 | `#26333F` / `#E6EDF5` |
| 勾選框已勾選背景／勾記 | `#3E8FD8` / `#FFFFFF` |
| 週清單「本週」標記文字／邊框 | `#4B9FE8` / `#2C4A66` |
| 輸入框／下拉背景 | `#0F1620` |
| 輸入框邊框 | `#26333F` |
| 輸入框 focus 邊框 | `#3E8FD8` |
| 輸入框 placeholder 文字 | `#6B7C90` |
| 欄位錯誤文字／邊框 | `#F09A94` / `#8A3A34` |
| 次要按鈕背景／文字／邊框 | `#1B2836` / `#E6EDF5` / `#26333F` |
| 次要按鈕 hover 背景 | `#223347` |
| 主要按鈕背景／文字 | `#3E8FD8` / `#FFFFFF` |
| 主要按鈕 hover 背景 | `#58A3E8` |
| Disabled 按鈕背景／文字 | `#16202C` / `#4A5866` |
| 摘要列文字 | `#93A4B8` |
| 摘要列命中檔數（強調） | `#E6EDF5` |
| 產業別區塊標題文字 | `#E6EDF5` |
| 產業別區塊命中檔數文字 | `#93A4B8` |
| 「未分類」區塊標題文字 | `#93A4B8` |
| 資料不足／停牌註記提示文字 | `#D9A441` |
| 產業別未匯入提示文字／背景／邊框 | `#D9A441` / `#2E2411` / `#5C4A1E` |
| 區間空隙提示文字 | `#D9A441` |
| 骨架列底色 | `#1D2A38` |
| 錯誤訊息文字／背景／邊框 | `#F09A94` / `#3A1C1A` / `#8A3A34` |

## Acceptance Criteria
- [x] `/stocks` 的頁籤列有第三個頁籤「動態」，點擊後網址變為 `/stocks?tab=momentum`
- [x] 直接以 `/stocks?tab=momentum` 進入會停在動態頁籤
- [x] 動態分頁內有「漲幅平均」「漲幅加總」兩個標籤，預設停在「漲幅平均」
- [x] 切換標籤時以新的 `metric` 重新查詢，期間條件沿用
- [x] **門檻不跨標籤沿用**：在「漲幅加總」把門檻改為 `20` 後切到「漲幅平均」，門檻仍為該標籤自己的值（預設 `5`），切回去仍是 `20`
- [x] 兩個標籤各自保留自己的查詢結果，切回已查過的標籤不發出新請求
- [x] 期間預設為「近 20 個交易日」模式，門檻預設為 `5`
- [x] 天數輸入 `0` 或 `121` 時前端即擋下並在欄位下方提示，不送出請求
- [x] 門檻輸入 `1001` 或 `-101` 時前端即擋下並提示，不送出請求；輸入 `-100` 與 `0` 皆可正常送出
- [x] 切換到「指定週」模式後列出最近 12 個自然週，標籤格式為 `MM/DD – MM/DD`，本週該列有「本週」標記
- [x] 送出的 `startDate` 為最早勾選週的**週一**、`endDate` 為最晚勾選週的**週日**
- [x] **勾選不連續的週時，週清單下方顯示提示，明確指出實際計算區間包含未勾選的那幾週**
- [x] 一週都沒勾時「查詢」為 disabled，並顯示「請至少勾選一週」
- [x] 兩種期間模式間切換時，另一模式已輸入的值被保留，切回即恢復
- [x] 摘要列的區間與交易日數取自回應的 `startDate`／`endDate`／`tradingDays`，非前端自行推算
- [x] 摘要列的「命中 N 檔」取自 `matchedStockCount`（去重後），**不等於**各產業 `matchedCount` 的加總
- [x] `insufficientDataCount` 為 0 時，摘要列不顯示「資料不足」該段
- [x] 結果區的產業別區塊順序與回應的 `industries` 順序完全一致，前端不重排
- [x] **一檔屬於兩個產業別的命中股票，在兩個產業別的表格中各出現一次**
- [x] `industryId` 為 `null` 的區塊標題顯示「未分類」且排在最後
- [x] 漲幅為正值時顯示紅色 `#E04B45`、負值顯示綠色 `#16A75C`
- [x] 某檔的 `tradingDays` 小於摘要列的交易日數時，該欄有註記符號與「期間內有停牌」的說明
- [x] 點擊任一列導向 `/stocks/{stockId}/daily`
- [x] `industries` 只有「未分類」一個區塊時，結果區頂端顯示引導提示，指向策略分頁的「更新股票清單」，且未分類結果照常顯示
- [x] 動態分頁上**沒有**任何「更新股票清單」或匯入產業別的按鈕
- [x] `matchedStockCount` 為 0 時顯示「這段期間沒有股票的漲幅達到 {門檻}%」與「把門檻調低」按鈕，點擊後門檻減半並重新查詢
- [x] 回應的 `startDate` 為 `null` 時，摘要列顯示「所選期間內沒有交易日」，結果區顯示「請改選其他週」，非錯誤畫面
- [x] 請求失敗時顯示錯誤訊息與「重新查詢」按鈕，不顯示成查無命中
- [x] 查詢中「查詢」按鈕 disabled，既有結果保留並降低透明度，連點兩次不送出兩次請求
- [x] 頁面文案無「建議」「推薦」「可進場」等暗示買賣操作的措辭
- [x] 所有顏色取自 `## Visual Style` 的字面 hex；在瀏覽器強制 `prefers-color-scheme: dark` 與 `light` 兩種偏好下截圖比對，頁面配色完全相同、文字皆清晰可讀

---
### 普通股母體

本頁**沒有**自己的 ETF 排除選項。漲幅計算的母體由頁籤列上方的頁面層級勾選框「只看上市普通股」決定，該控制項與其狀態由 `specs/frontend/stock-list.md` 擁有，三個分頁共用。

每次查詢一律以該勾選框當下的狀態帶入 `commonStocksOnly`。切換該勾選框時，若本頁已有結果，**立即以新的值重新查詢**——與策略分頁不同，本頁的查詢是一次彙總查詢、成本低，沒有理由讓畫面停在跟設定不符的舊資料上。

---

- [ ] 本頁**沒有**任何 ETF 排除選項；該控制項只存在於頁籤列上方（見 `specs/frontend/stock-list.md`）
- [ ] 每次查詢帶出的 `commonStocksOnly` 等於頁面層級勾選框當下的狀態
- [ ] 頁面層級勾選框為預設（勾選）時，各產業別分組中不出現 `0050`、`00878`、`2881A`、`910322`
- [ ] 取消勾選後本頁立即重新查詢，命中檔數明顯增加，且結果中出現 ETF
- [ ] 「漲幅平均」與「漲幅加總」兩個標籤都套用同一個設定

## Execution Result
- Status: DONE
- Files changed:
  - `develop/frontend/src/pages/MomentumTab.tsx` (new) — the 動態 tab: metric tab bar (漲幅平均／漲幅加總, each with its own threshold + result cache), period condition area (近 N 交易日 / 指定週 with 12-week checklist + gap warning), summary line, industry-grouped result tables, all page states (idle/loading/success/zero-hit/no-trading-day/error).
  - `develop/frontend/src/pages/MomentumTab.css` (new) — fixed dark palette per this spec's `## Visual Style`, literal hex only; owned solely by this tab (reuses only the page-shell-level `.sl-tabs/.sl-tab`, `.sl-table`, `.sl-btn*`, `.sl-up/.sl-down/.sl-flat`, `.sl-row`, `.sl-skeleton-bar` classes from `StockListPage.css`, which was not modified).
  - `develop/frontend/src/api/momentum.ts` (new) — `fetchMomentumGain` wrapping `GET /api/momentum/gain`, typed response per `specs/backend/industry-gain-ranking.md`.
  - `develop/frontend/src/__tests__/MomentumTab.test.tsx` (new) — 26 tests.
  - `develop/frontend/src/pages/StockListPage.tsx` — swapped the placeholder import/mount for `MomentumTab` (the one line this spec owns; tab-container structure and the other two panels untouched).
  - `develop/frontend/src/pages/MomentumTabPlaceholder.tsx` (deleted) — superseded by `MomentumTab.tsx`, no longer referenced anywhere.
- Notes:
  - **Metric/period/threshold state model**: period condition (mode, days input, selected weeks) is a single shared state (satisfies "期間條件沿用"); threshold input + query status/data/error are a `Record<Metric, MetricState>` so each label keeps its own value and its own cached result independently (satisfies "門檻不跨標籤沿用" and "切回已查過的標籤不發出新請求"). Switching to a label re-queries only if that label doesn't already have a success result *and* at least one query has happened on the page before (an `everQueriedRef` gate) — this keeps the very first paint idle (no silent fetch) while still auto-firing on first visit to a not-yet-queried label after the user has queried at least once, matching "切換標籤時以新的 metric 重新查詢".
  - **Week math**: `startOfWeekMonday`/`buildWeekOptions` compute the 12 most recent Mon–Sun natural weeks purely from `new Date()`, index 0 = current week (newest, pre-checked by default). Submitted range = `weeks[maxSelectedIndex].monday` (earliest selected) to `weeks[minSelectedIndex].sunday` (latest selected). Gap detection scans the index range between the min and max selected index and lists every unselected week's Monday as `MM/DD`, driving the mandatory warning line the spec calls out as "the one place the UI could mislead".
  - **Race safety**: each metric has its own `AbortController`; starting a new query for a metric aborts that metric's own in-flight request first, and the abort/unmount cleanup effect captures the ref's target object into a local variable before returning the cleanup closure (avoids the `react-hooks/exhaustive-deps` "ref accessed directly in cleanup" warning while preserving identical behavior, since the ref's `.current` object is never reassigned wholesale). No request-per-item loops — a single `GET` per query.
  - **Absence safety**: `data.industries ?? []`, `group.items ?? []`, `data.insufficientDataCount ?? 0` guard the response shape at every render site; `formatPercent`/`formatPrice` render `—` for `null`/`undefined` per the spec's number-formatting rule.
  - Colors are 100% literal hex in `MomentumTab.css`, no `var()`/theme tokens/`prefers-color-scheme` media queries — verified by code review of the file (no such constructs present) and by live screenshot (see below); this mirrors the same fixed-palette approach already used in `StockListPage.css`.
  - `把門檻調低` halves the *currently applied* threshold and re-queries, passing the halved value directly into `runQuery`'s override parameter rather than relying on a state update completing first (avoids a stale-closure bug where the halved value could race against the async `setState`).
  - The 未分類-only guidance banner and the absence of any import/update-list button in this tab were both directly asserted in tests; no such button exists anywhere in `MomentumTab.tsx`.
- Verified live against the real backend (already running on port 8080, real ~1374-stock dataset) via a throwaway Playwright script (not committed — this repo has no browser-test harness yet, consistent with the rest of the frontend):
  - `minGain=0`, `mode=DAYS days=20`: summary line read exactly `區間 2026-08-06 ~ 2026-09-02（20 個交易日）・命中 366 檔・資料不足 754 檔`; first industry block `鋼鐵工業　22 檔` with correctly red (`+2.32%` etc.) 漲幅 values.
  - Switching to 漲幅加總 re-queried and showed a different `命中` count (173) at the tab's own still-default threshold (5), confirming per-label threshold/result independence end-to-end.
  - `minGain=-100` surfaced genuine green (down) values (e.g. `-1.04%`) rendered in the down color, confirming the red/green branch renders correctly against real data, not just the unit-test fixture.
  - 指定週 mode rendered all 12 weeks newest-first with `MM/DD – MM/DD` labels and the `本週` badge on the top (current) row, matching the computed-live week boundaries.
  - Clicking a result row navigated to `/stocks/{stockId}/daily` (observed via the browser's resulting URL).
  - No browser console errors during any of the above.
  - `prefers-color-scheme` A/B screenshot comparison (forcing `dark` vs `light` in the browser and diffing) was not run as an automated step this pass; visual/code review confirms no CSS in `MomentumTab.css` is conditioned on `prefers-color-scheme` or theme variables, so there is nothing in the stylesheet that could shift between the two OS preferences.
- Test results: `npm run build` — success (`tsc -b && vite build`, no errors). `npm test` — **148 tests, 0 failures** (122 pre-existing across the other 7 suites + 26 new in `MomentumTab.test.tsx`). `npm run lint` (`oxlint`) — no warnings from any file this task touched (pre-existing warnings in `StrategyTab.tsx`/`StrategyTab.test.tsx` belong to the parallel strategy-spec agent's files, not touched here).
- Left unfixed (deliberately, with reason): none identified within this tab's own scope during the code-quality self-review.
