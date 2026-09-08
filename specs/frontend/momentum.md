---
status: done
title: "動態分頁（產業別漲幅）"
requirement: "新增「動態」分頁，內含「漲幅平均」與「漲幅加總」兩個標籤；可設定近幾個交易日、或勾選哪幾週，列出漲幅超過門檻的股票，並按產業別分組顯示；一檔股票屬於多個產業別時，在每一個產業別下都要出現；每個產業別區塊顯示該產業命中股票的平均漲幅，並可切換依命中檔數或依產業漲幅排序"
depends_on: [stock-list]
---

# 動態分頁（產業別漲幅） — Frontend Spec

## Overview

`/stocks` 的第三個頁籤（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`，第二個見 `specs/frontend/strategy.md`）。使用者設定一段期間與一個漲幅門檻，按下查詢後，**達標的股票依產業別分組列出**，每個產業別另顯示其命中股票的平均漲幅——回答的是「這段期間哪些產業在動、動得多大、動的是哪幾檔」。

路由：`/stocks?tab=momentum`。

**本頁呈現的是漲幅統計結果，不是買賣建議。** 所有文案一律以「漲幅／命中／統計」表述，不得出現「建議買進」「推薦」「可進場」等暗示操作的措辭（見 `specs/backend/industry-gain-ranking.md`）。

## Requirements

### 頁面結構

由上而下：

1. **標籤列** — 頁內第二層標籤，兩個：「漲幅平均」「漲幅加總」。預設停在「漲幅平均」。
2. **條件區** — 期間設定、漲幅門檻、「查詢」主要按鈕。
3. **摘要列** — 實際採用的區間、交易日數、命中檔數、資料不足檔數。
4. **結果區** — 排序切換列，其下每個產業別一個區塊。

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

### 普通股母體

本頁**沒有**自己的 ETF 排除選項。漲幅計算的母體由頁籤列上方的頁面層級勾選框「只看上市普通股」決定，該控制項與其狀態由 `specs/frontend/stock-list.md` 擁有，三個分頁共用。

每次查詢一律以該勾選框當下的狀態帶入 `commonStocksOnly`。切換該勾選框時，若本頁已有結果，**立即以新的值重新查詢**——與策略分頁不同，本頁的查詢是一次彙總查詢、成本低，沒有理由讓畫面停在跟設定不符的舊資料上。

### 摘要列

一行，顯示本次查詢實際採用的條件與結果總量，全部取自回應：

> 區間 `2026-08-04` ~ `2026-08-29`（20 個交易日）・命中 **37** 檔・資料不足 12 檔

- **「命中 N 檔」用的是 `matchedStockCount`（去重後）**，不是各產業 `matchedCount` 的加總。一檔股票同時屬於兩個產業時會在畫面上出現兩次，若總數用加總，使用者數出來的列數會比總數少，而那個差額沒有任何地方解釋得了。
- 「資料不足 K 檔」在 `insufficientDataCount` 為 0 時整段不顯示。滑鼠移上去顯示說明「期間開始前沒有行情，無法計算漲幅」——這與「有算但沒達標」是不同的事，不能讓使用者以為那些股票是跌的。
- 區間為 `null`（勾選的週內完全沒有交易日）時，摘要列改為顯示「所選期間內沒有交易日」。

### 結果區

#### 排序切換

結果區頂端一列，緊接在摘要列下方，兩個互斥選項：

| 選項 | `sort` | 語意 |
|---|---|---|
| 依命中檔數（預設） | `MATCH_COUNT` | 命中股票最多的產業別排最前 |
| 依產業漲幅 | `AVG_GAIN` | 平均漲幅最高的產業別排最前 |

- **切換排序即以新的 `sort` 重新查詢**，不在前端重排既有結果。排序歸後端決定是本頁一貫的作法（見下），前端另做一套排序會讓兩邊的同分規則有機會分歧；本頁的查詢是一次彙總查詢、成本低，多一次往返換到單一排序來源是划算的。
- **排序設定為兩個標籤共用**（與期間條件相同，與門檻不同）。排序是呈現偏好，和漲幅的量級無關，沒有理由讓兩個標籤各記一份。
- 因此**切換排序時，另一個標籤已快取的結果即失效**，切過去時重新查詢。否則會出現「切到另一個標籤，看到的順序是舊排序」——排序選項與畫面上的實際順序不符，而使用者完全看不出原因。
- 尚未查詢過時排序切換仍可操作，選到的值於首次查詢時帶出。

#### 產業別區塊

每個產業別一個區塊，由上而下依回應的 `industries` 順序排列（後端已排序，前端不重排，兩種排序皆然）。

區塊標題：`{industryName}　{matchedCount} 檔　平均 {avgGain}`。

- `avgGain` 以 2 位小數加 `%` 顯示，正值前綴 `+`，並依漲跌著色，與表格內「漲幅」欄用同一組色值。
- 標題上的「平均」帶說明（hover 顯示）：「此區塊列出的 {matchedCount} 檔的漲幅平均，必然 ≥ 門檻；不代表整個產業的表現」。**這句必須存在**：`avgGain` 的母體只含命中股票，數值必然 ≥ 門檻，讀成「這個產業平均漲了這麼多」是錯的，而畫面上沒有任何其他線索擋得住這個誤讀。
- 「未分類」區塊同樣顯示平均值。

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
| 切換排序 | 以新的 `sort` 重新查詢，呈現與「查詢中」一列相同；另一標籤的快取結果同時失效 |

## Implementation Details

### API 整合

| 時機 | 呼叫 |
|---|---|
| 按「查詢」、切換排序、切換標籤（該標籤尚無有效結果時） | `GET /api/momentum/gain?metric=&mode=&days=&startDate=&endDate=&minGain=&sort=&commonStocksOnly=` |

契約見 `specs/backend/industry-gain-ranking.md`。

送出參數的組法：

| 參數 | 值 |
|---|---|
| `metric` | 目前標籤：`AVERAGE`／`SUM` |
| `mode` | 目前期間模式：`DAYS`／`WEEKS` |
| `days` | `mode=DAYS` 時帶入輸入框的值；`WEEKS` 時不帶 |
| `startDate` / `endDate` | `mode=WEEKS` 時帶入勾選週折算出的起訖日；`DAYS` 時不帶 |
| `minGain` | 目前標籤的門檻值 |
| `sort` | 排序切換當下的值：`MATCH_COUNT`／`AVG_GAIN`（兩個標籤共用） |

回應欄位：`metric`、`mode`、`sort`、`startDate`、`endDate`、`tradingDays`、`minGain`、`scannedStocks`、`matchedStockCount`、`insufficientDataCount`、`industries[]`（`industryId`、`industryName`、`matchedCount`、`avgGain`、`items[]`），每個 item 含 `stockId`、`stockName`、`gain`、`tradingDays`、`startClose`、`endClose`、`firstTradeDate`、`lastTradeDate`。

`firstTradeDate` / `lastTradeDate` 不在表格中顯示，但需保留於資料模型中——供「交易日」欄的 tooltip 說明該檔實際涵蓋的日期。

錯誤回應處理：

| `code` | 前端行為 |
|---|---|
| `INVALID_METRIC` / `INVALID_MODE` / `INVALID_SORT` | 視為程式錯誤：正常操作不應觸發（三者皆由受控元件產生），顯示通用錯誤訊息 |
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
| 產業別區塊平均漲幅（漲／跌／平） | `#E04B45` / `#16A75C` / `#93A4B8` |
| 排序切換選項（未選）背景／文字 | `#1B2836` / `#93A4B8` |
| 排序切換選項（選中）背景／文字 | `#26333F` / `#E6EDF5` |
| 「未分類」區塊標題文字 | `#93A4B8` |
| 資料不足／停牌註記提示文字 | `#D9A441` |
| 產業別未匯入提示文字／背景／邊框 | `#D9A441` / `#2E2411` / `#5C4A1E` |
| 區間空隙提示文字 | `#D9A441` |
| 骨架列底色 | `#1D2A38` |
| 錯誤訊息文字／背景／邊框 | `#F09A94` / `#3A1C1A` / `#8A3A34` |

## Acceptance Criteria

### 頁籤與標籤
- [x] `/stocks` 的頁籤列有第三個頁籤「動態」，點擊後網址變為 `/stocks?tab=momentum`
- [x] 直接以 `/stocks?tab=momentum` 進入會停在動態頁籤
- [x] 動態分頁內有「漲幅平均」「漲幅加總」兩個標籤，預設停在「漲幅平均」
- [x] 切換標籤時以新的 `metric` 重新查詢，期間條件沿用
- [x] **門檻不跨標籤沿用**：在「漲幅加總」把門檻改為 `20` 後切到「漲幅平均」，門檻仍為該標籤自己的值（預設 `5`），切回去仍是 `20`
- [x] 兩個標籤各自保留自己的查詢結果，切回已查過的標籤不發出新請求

### 條件區：期間與門檻
- [x] 期間預設為「近 20 個交易日」模式，門檻預設為 `5`
- [x] 天數輸入 `0` 或 `121` 時前端即擋下並在欄位下方提示，不送出請求
- [x] 門檻輸入 `1001` 或 `-101` 時前端即擋下並提示，不送出請求；輸入 `-100` 與 `0` 皆可正常送出
- [x] 切換到「指定週」模式後列出最近 12 個自然週，標籤格式為 `MM/DD – MM/DD`，本週該列有「本週」標記
- [x] 送出的 `startDate` 為最早勾選週的**週一**、`endDate` 為最晚勾選週的**週日**
- [x] **勾選不連續的週時，週清單下方顯示提示，明確指出實際計算區間包含未勾選的那幾週**
- [x] 一週都沒勾時「查詢」為 disabled，並顯示「請至少勾選一週」
- [x] 兩種期間模式間切換時，另一模式已輸入的值被保留，切回即恢復

### 計算母體（只看上市普通股）
- [x] 本頁**沒有**任何 ETF 排除選項；該控制項只存在於頁籤列上方（見 `specs/frontend/stock-list.md`）
- [x] 每次查詢帶出的 `commonStocksOnly` 等於頁面層級勾選框當下的狀態
- [x] 頁面層級勾選框為預設（勾選）時，各產業別分組中不出現 `0050`、`00878`、`2881A`、`910322`
- [x] 取消勾選後本頁立即重新查詢，命中檔數明顯增加，且結果中出現 ETF
- [x] 「漲幅平均」與「漲幅加總」兩個標籤都套用同一個設定

### 摘要列
- [x] 摘要列的區間與交易日數取自回應的 `startDate`／`endDate`／`tradingDays`，非前端自行推算
- [x] 摘要列的「命中 N 檔」取自 `matchedStockCount`（去重後），**不等於**各產業 `matchedCount` 的加總
- [x] `insufficientDataCount` 為 0 時，摘要列不顯示「資料不足」該段

### 結果區：產業別分組
- [x] 結果區的產業別區塊順序與回應的 `industries` 順序完全一致，前端不重排
- [x] **一檔屬於兩個產業別的命中股票，在兩個產業別的表格中各出現一次**
- [x] `industryId` 為 `null` 的區塊標題顯示「未分類」且排在最後
- [x] 漲幅為正值時顯示紅色 `#E04B45`、負值顯示綠色 `#16A75C`
- [x] 某檔的 `tradingDays` 小於摘要列的交易日數時，該欄有註記符號與「期間內有停牌」的說明
- [x] 點擊任一列導向 `/stocks/{stockId}/daily`
- [x] `industries` 只有「未分類」一個區塊時，結果區頂端顯示引導提示，指向策略分頁的「更新股票清單」，且未分類結果照常顯示
- [x] 動態分頁上**沒有**任何「更新股票清單」或匯入產業別的按鈕

### 結果區：排序與產業平均漲幅
- [x] 結果區頂端有排序切換列，兩個選項「依命中檔數」「依產業漲幅」，預設停在「依命中檔數」
- [x] 切換排序時以新的 `sort` 重新查詢，不在前端重排既有結果
- [x] 排序設定兩個標籤共用：在「漲幅平均」切成「依產業漲幅」後切到「漲幅加總」，排序選項仍為「依產業漲幅」
- [x] 切換排序後另一標籤的快取結果失效：切過去時發出新請求，且畫面順序與排序選項一致
- [x] 尚未查詢過時排序切換可操作，其值於首次查詢時帶出；每次查詢帶出的 `sort` 等於該控制項當下的值
- [x] 產業別區塊標題為 `{industryName}　{matchedCount} 檔　平均 {avgGain}`，平均值為 2 位小數加 `%`、正值前綴 `+`
- [x] 區塊標題的平均值依漲跌著色：正值 `#E04B45`、負值 `#16A75C`、平盤 `#93A4B8`
- [x] 區塊標題的「平均」帶 hover 說明，明確指出母體只含命中股票、必然 ≥ 門檻、不代表整個產業的表現
- [x] 「未分類」區塊同樣顯示平均值
- [x] `sort=AVG_GAIN` 的結果中，畫面由上而下的區塊平均值為遞減，且「未分類」仍在最後
- [x] 兩種排序下前端皆完全依回應的 `industries` 順序呈現，不做任何前端重排

### 空狀態、錯誤與載入
- [x] `matchedStockCount` 為 0 時顯示「這段期間沒有股票的漲幅達到 {門檻}%」與「把門檻調低」按鈕，點擊後門檻減半並重新查詢
- [x] 回應的 `startDate` 為 `null` 時，摘要列顯示「所選期間內沒有交易日」，結果區顯示「請改選其他週」，非錯誤畫面
- [x] 請求失敗時顯示錯誤訊息與「重新查詢」按鈕，不顯示成查無命中
- [x] 查詢中「查詢」按鈕 disabled，既有結果保留並降低透明度，連點兩次不送出兩次請求

### 用語與外觀
- [x] 頁面文案無「建議」「推薦」「可進場」等暗示買賣操作的措辭
- [x] 所有顏色取自 `## Visual Style` 的字面 hex；在瀏覽器強制 `prefers-color-scheme: dark` 與 `light` 兩種偏好下截圖比對，頁面配色完全相同、文字皆清晰可讀
- [x] 新增元素的顏色皆取自 `## Visual Style`，平均值採與表格「漲幅」欄相同的紅綠色值

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

### Increment 2 — 2026-09-04

Scope: the 17 previously-unchecked Acceptance Criteria — the shared page-level `commonStocksOnly` filter (population source, immediate requery on toggle) and the `sort`/`avgGain` industry-block ranking (sort toggle, per-block average with hover explanation, shared across both metric tabs, front end never re-sorts).

- Starting state: `commonStocksOnly` was already fully wired end-to-end from increment 1 (`StockListPage.tsx` owns the checkbox, `MomentumTab` always sends the current value, `fetchMomentumGain` always sends it) — nothing to redo there. What was missing was (a) re-querying when the page-level checkbox changes after a result already exists, and (b) the entire `sort`/`avgGain` surface, which the backend (`specs/backend/industry-gain-ranking.md`) had just added in the same `/dev` run.
- Files changed:
  - `develop/frontend/src/api/momentum.ts` — added `MomentumSort` type, `avgGain` to `MomentumIndustryGroup`, `sort` to `MomentumGainResponse`, and a required `sort` field on `MomentumGainParams`; `fetchMomentumGain` now always sends `sort` in the query string.
  - `develop/frontend/src/pages/MomentumTab.tsx`:
    - Added a `sort` state (`MomentumSort`, default `MATCH_COUNT`) shared across both metric tabs, mirroring how the period condition is already shared (only the threshold and cached result are per-metric).
    - `resetQueryState(state)` — drops a metric's cached `status`/`data`/`errorMessage` while keeping its own `minGainInput` untouched; used whenever a setting shared across both tabs changes.
    - `handleSortChange(nextSort)` — no-ops if unchanged; otherwise sets `sort`, aborts any in-flight request for either metric, resets both metrics' cached results (so the inactive tab requeries next time it's switched to, matching "切換排序後另一標籤的快取結果失效"), and — only if the page has been queried at least once (`everQueriedRef`) — immediately requeries the active metric with the new sort passed as an explicit override (avoids reading the not-yet-committed `sort` state via a stale closure).
    - A `commonStocksOnly`-change effect (gated by a `prevCommonStocksOnlyRef` so it only fires on an actual change, not on mount): aborts any in-flight request for either metric, resets both metrics' cached results, and requeries the active metric immediately if it had already been queried (status was not `idle`) — per spec, "與策略分頁不同，本頁的查詢是一次彙總查詢、成本低，沒有理由讓畫面停在跟設定不符的舊資料上".
    - `buildParams`/`runQuery` extended to thread `sort` through (with the same override-parameter pattern already used for `minGain`'s halve-on-zero-hit flow), and always include it in the request.
    - `INVALID_SORT` added to the generic-error branch alongside `INVALID_METRIC`/`INVALID_MODE` (all three are produced only by controlled components, so a real occurrence is treated as a programming error, per spec's error table).
    - `renderSortToggle()` — two buttons (依命中檔數／依產業漲幅) reusing the exact `.mt-mode-btn`/`.mt-mode-btn-active` classes already used for the period-mode toggle, since the spec's `## Visual Style` table gives the sort toggle the identical unselected/selected colors. Rendered at the top of the results area in every status (idle/loading/error/data) so it stays operable before the first query, and directly below the summary line once one exists — matching the spec's "結果區頂端一列，緊接在摘要列下方".
    - `renderIndustryBlock` title now appends `　{matchedCount} 檔　`, a `title`-bearing "平均" label (the exact population/`≥ 門檻`/not-industry-wide caveat from the spec), and the `avgGain` value formatted via the existing `formatPercent`/`gainClass` helpers — the same functions and CSS classes (`sl-up`/`sl-down`/`sl-flat`) already used for the per-row 漲幅 cell, so the average automatically uses the identical red/green/flat hex values without any new color rule.
  - `develop/frontend/src/pages/MomentumTab.css` — added `.mt-sort-row`/`.mt-sort-label` (layout only; colors come from the reused `.mt-mode-btn` rules, which are already literal hex with no `prefers-color-scheme`/`var()` use).
  - `develop/frontend/src/__tests__/MomentumTab.test.tsx` — extended `baseResponse()`'s fixture with `sort`/`avgGain`; `renderTab()` now accepts an optional `commonStocksOnly` prop; added 18 new tests covering the two new AC blocks (no ETF-exclusion control on this tab, `commonStocksOnly` sent/defaulted/immediately-requeried-on-change/shared-across-both-tabs; sort toggle default/pre-query-operable/requeries-on-change/shared-across-tabs/invalidates-the-other-tab's-cache/never-re-sorts-on-the-front-end; `avgGain` rendering with sign/color/hover text, including for 未分類). Fixed two increment-1 tests (`positive gain renders red…`, `marks a stock whose tradingDays…`) that started matching two elements once `avgGain` duplicated an existing item's `gain` value in the fixture — scoped them to the `<td>` specifically.
- Notes:
  - **Race safety (code-quality self-review catch):** the first draft of both `handleSortChange` and the `commonStocksOnly`-change effect reset a metric's cached state to `idle` without aborting that metric's in-flight request first. If the active metric had a request in flight at the moment the setting changed, that request's `.then()` would still land afterward and silently overwrite the reset with a result computed under the *old* setting — an absence-of-cleanup race a reviewer would flag as "resource lifecycle" (an async operation that outlives the state it was allowed to update). Fixed by aborting both metrics' `AbortController`s before resetting state in both places, so a stale in-flight response's `catch (AbortError)` branch fires instead of its `.then()`.
  - **No front-end re-sort:** confirmed by code inspection (industries are rendered via `data.industries.map(...)` with no `.sort()`/`.slice().sort()` anywhere in the render path) and by a live check against the real backend — `sort=AVG_GAIN` at `minGain=-1` over the real ~1085-common-stock population returned blocks with `avgGain` `0.72 / 0.65 / 0.41 / 0.32 / 0.31 / 0.26 …`, already descending from the API, which the component renders as-is.
  - **Absence safety:** `formatPercent`/`gainClass` (both already null-safe from increment 1) are reused unchanged for `avgGain`, so `avgGain` being `null`/`undefined` in some future response shape renders `—`/`sl-flat` rather than throwing.
- Verified live against the real backend (MySQL 127.0.0.1:3306 `stock`/`app`, ~1377 stocks / 1085 common) — started `mvn -f develop/backend/pom.xml spring-boot:run` (port 8080) and `npm run dev` (port 5173, proxies `/api` to 8080):
  - `GET /api/momentum/gain?...&sort=AVG_GAIN&commonStocksOnly=true` — response includes `"sort":"AVG_GAIN"` and each industry has a rounded `avgGain`; at `minGain=-1`, `days=20` the blocks came back `avgGain`-descending (`航運業 0.72`, `光電業 0.65`, `金融保險業 0.41`, `化學工業 0.32`, `塑膠工業 0.31`, `油電燃氣業 0.26`, …).
  - `commonStocksOnly=false` vs `=true` at the same window (`minGain=-100`, `days=20`): `matchedStockCount` 625 vs 390, and `0050`/`00878` present in the `=false` response's items and absent from `=true` — reproduces the spec's literal example directly against live data (`2881A`/`910322` have no pre-window trading history in the live dataset, same caveat the backend spec's own verification section already notes).
  - Confirmed through the Vite dev-server proxy too (`curl http://localhost:5173/api/momentum/gain?...` returned the same JSON as hitting port 8080 directly), so the full dev-server-to-backend path is wired correctly.
  - **Browser-driven UI verification (sort-toggle clicks, hover-tooltip text, on-screen color check) could not be completed this pass**: this sandbox's Bash tool refused to spawn the cached Playwright/Chromium executable (`spawn UNKNOWN` via the Playwright driver, and `Permission denied` launching `chrome.exe` directly, even with `dangerouslyDisableSandbox`), unlike increment 1's environment where a throwaway Playwright script apparently worked. In its place, verification for this increment rests on (a) the 39-test `MomentumTab.test.tsx` suite, which exercises every new interaction (button clicks, `aria-pressed`, request query strings, cached-result invalidation, avgGain sign/color/tooltip text) against a mocked `fetch`, and (b) the live `curl` checks above proving the real backend contract and dev-server proxy both match what the component sends and expects. The `prefers-color-scheme` dark/light screenshot A/B (already unautomated in increment 1 for the same reason) was likewise not run; code review confirms no new CSS in `MomentumTab.css` uses `var()` or `prefers-color-scheme` — the new `.mt-sort-row`/`.mt-sort-label` rules are layout-only, and the sort toggle's colors come entirely from the pre-existing, already-reviewed `.mt-mode-btn`/`.mt-mode-btn-active` literal-hex rules.
- Test results: `npm run build` — success (`tsc -b && vite build`, no errors). `npm test` — **186 tests, 0 failures** (148 from increment 1 + 21 net-new/adjusted in `MomentumTab.test.tsx`, spread across the same 9 suites; some pre-existing suites also grew slightly from the sibling `stock-list`/`strategy` increments landing in the same `/dev` run). `npm run lint` (`oxlint`) — no warnings from any file this increment touched; the two pre-existing warnings (`StrategyTab.tsx`/`StrategyTab.test.tsx`) belong to the sibling strategy-spec agent's files.
- Left unfixed (deliberately, with reason): the browser-driven UI/screenshot verification described above — blocked by this session's sandbox refusing to launch a real browser process, not a defect in the implementation. Nothing else was left incomplete; all 17 targeted Acceptance Criteria are implemented and checked off.

#### Increment 2 補驗 — 2026-09-04（由 `/dev` 排程者以 in-app 瀏覽器完成）

Increment 2 當下無法執行的瀏覽器驗證，已在同一次 `/dev` 執行的最後補齊，改用 Claude Code 內建的 Browser pane（非 Playwright）對真實後端（port 8080）與真實 Vite dev server（port 5173）操作，資料為真實的 1,377 檔（普通股 1,085 檔）：

- **動態分頁載入**：頁籤列上方「只看上市普通股（排除 ETF、特別股、TDR）」預設勾選，計數顯示「共 1,085 檔」。
- **查詢**：近 20 交易日、漲幅門檻 0.5%，回傳「命中 51 檔・資料不足 695 檔」，依產業別分組，每個區塊標題含該產業平均漲幅（例：`電子零組件業　7 檔　平均 +0.79%`）。
- **`sort=MATCH_COUNT`（預設）**：區塊依命中檔數遞減排列（7、6、5、5、4、4、3、3、3、2、2…）。
- **`sort=AVG_GAIN`**：點「依產業漲幅」後，區塊改依平均漲幅遞減重排（光電業 +2.07% → 鋼鐵工業 +1.79% → 航運業 +1.03% → … → 玻璃陶瓷 +0.54%），與命中檔數無關。
- **`prefers-color-scheme` 固定配色**：於同一頁面分別模擬 `light` 與 `dark`，比對 `body`／`th`／`td`／`button`／`label` 的 computed `color` 與 `background-color`，兩者完全相同（例：`td` 皆為 `rgb(230,237,245)`、`th` 皆為 `rgb(147,164,184)` on `rgb(27,40,54)`），確認第 267 項驗收條件成立。

兩個 dev server 於驗證後皆已停止。
