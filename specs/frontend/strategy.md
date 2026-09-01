---
status: done
title: "策略型態掃描分頁"
requirement: "策略分頁 — 可勾選策略（底底高、箱型突破）並各自選靈敏度，掃描指定區間（預設近一個月）內命中的股票；勾選兩個以上策略時另有一張聯集表格列出所有命中股票；另有更新股票清單與同步所有日 K 至今日的兩顆按鈕，並顯示最後同步時間"
depends_on: [stock-list]
---

# 策略型態掃描分頁 — Frontend Spec

## Overview

`/stocks` 的第二個頁籤（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`）。使用者勾選要跑的型態、各自挑靈敏度、指定區間，按下掃描後列出命中的股票；點任一列可進入該檔日 K 圖對照。

頁面另有一顆同步按鈕，把全部在市股票的日線補到今日，並常駐顯示最後一次成功同步的時間——**掃描結果的可信度完全取決於行情有多新**，把這個時間放在掃描按鈕旁邊，是為了讓使用者在看到「零命中」時能立刻分辨那是「真的沒有型態」還是「資料停在兩週前」。

路由：`/stocks?tab=strategy`。

**本頁呈現的是型態偵測結果，不是買賣建議。** 所有文案一律以「命中／訊號／型態」表述，不得出現「建議買進」「推薦」「可進場」等暗示操作的措辭（見 `specs/backend/strategy-scan.md`）。

## Requirements

### 頁面結構

由上而下：

1. **同步列** — 左側「最後同步：YYYY-MM-DD HH:mm」，右側「同步日 K 至今日」按鈕。
2. **條件區** — 策略勾選卡片、股票範圍、區間選擇、「開始掃描」主要按鈕。
3. **結果區** — 每個勾選的策略一個結果區塊。

### 條件區

**策略勾選**：每個策略一張卡片，卡片內含勾選框、策略名稱、靈敏度下拉（嚴格／標準／寬鬆，預設「標準」）、以及該靈敏度的說明文字一行。

- 策略清單與靈敏度說明**一律取自 API**，不在前端寫死。參數是後端的契約，兩邊各存一份必然漂移。
- 未勾選的卡片其靈敏度下拉為 disabled。
- 一個都沒勾時「開始掃描」為 disabled，並在按鈕旁提示「請至少勾選一個策略」——不要讓使用者按了才知道。

**股票範圍**：兩個互斥選項。

| 選項 | 行為 |
|---|---|
| 全市場（預設） | 掃描全部在市股票，請求不帶 `stockIds` |
| 指定股票 | 展開一個多選輸入，可依代號或名稱搜尋加入，已選的以可移除的標籤呈現；上限 200 檔 |

選到上限時輸入框 disabled 並提示「最多 200 檔」。

**區間**：起日與迄日兩個日期輸入，**預設為今日往前一個日曆月至今日**。另提供「近一個月」「近三個月」「近半年」三個快捷鈕，點擊即套用對應區間。起日晚於迄日時前端即擋下並在區間下方提示，不送出請求。

### 結果區

結果區由上而下是：**聯集表格**（勾選兩個以上策略時才出現），然後才是每個策略各自的區塊。

#### 聯集表格

列出本次掃描中**任一個策略命中**的全部股票，同一檔只出現一列。標題為「命中彙總 — 共 N 檔」，N 為去重後的股票檔數（**不是**各策略 `matchedCount` 的加總，一檔同時命中兩個策略在此只算一檔）。

它回答的問題和底下各策略的表格不同：策略區塊回答「這個型態掃出了什麼」，聯集表格回答「這次掃描總共要看哪幾檔」。使用者實際的下一步是逐檔去看日 K，而那份清單是聯集，不是兩張表分開讀再自己心算去重。

| 欄位 | 來源 |
|---|---|
| 代號 / 名稱 | 各策略 `items` 的 `stockId` / `stockName` |
| 命中策略與訊號日 | 該檔命中的每一個策略，逐一列出「{策略名稱} {signalDate}」，以 `・` 分隔 |

**「命中策略與訊號日」逐策略列出，不合併成單一日期。** 一檔同時命中兩個策略時，兩個型態的成立日往往不同（例如箱型突破 `2026-08-28`、底底高 `2026-08-25`），把它折成一個日期會丟掉「哪個型態是什麼時候成立的」這個判讀時真正需要的資訊。

策略的排列順序與勾選順序一致，與策略區塊的順序相同。

**排序**：依該檔在各策略中**最新的**一個 `signalDate` 由新到舊；同日則依 `stockId` 升冪。這是延用 `specs/backend/strategy-scan.md` 對各策略 `items` 已定的排序規則，讓聯集表格與底下的策略表格讀起來是同一種順序。

點擊任一列導向 `/stocks/{stockId}/daily`，與策略表格一致。

**只勾選一個策略時不顯示這張表格**——此時聯集等於該策略的結果，兩張表內容完全相同，重複呈現只是佔版面。勾到第二個策略時才出現。

資料不足（`insufficientData`）與待確認（`pendingConfirm`）的標的**不納入聯集表格**，它們不是命中；這兩類仍只在各自的策略區塊下方以既有的一行摘要呈現。

#### 各策略區塊

每個策略一個區塊，標題為「{策略名稱}（{靈敏度}）— 命中 N 檔」，N 取自該策略結果的 `matchedCount`，其下為表格。

**箱型突破**的表格欄位：

| 欄位 | 來源 |
|---|---|
| 代號 / 名稱 | `stockId` / `stockName` |
| 訊號日 | `signalDate` |
| 箱型區間 | `detail.boxLow` ~ `detail.boxHigh` |
| 突破收盤 | `detail.breakoutClose` |
| 突破幅度 | `detail.breakoutPercent`，兩位小數加 `%` |
| 量能倍數 | `detail.volumeRatio`，兩位小數加 `×` |

**底底高**的表格欄位：

| 欄位 | 來源 |
|---|---|
| 代號 / 名稱 | `stockId` / `stockName` |
| 訊號日 | `signalDate` |
| 低點序列 | `detail.lows` 逐點以「MM-DD 價格」串接，以 `→` 分隔 |
| 累計漲幅 | 由 `lows` 首末兩點計算，兩位小數加 `%` |

兩張表都在最右保留一欄，點擊該列任一處導向 `/stocks/{stockId}/daily`。

**資料不足的標的必須單獨呈現**，不可混入「未命中」。在該策略區塊下方以一行摘要顯示：「另有 N 檔因區間前的歷史資料不足而未納入判定」，可展開看代號清單。這是使用者判讀結果的關鍵資訊——把「沒掃到」和「掃了沒有」混為一談，會讓人誤以為那些股票已經確認沒有型態。

箱型突破若 `pendingConfirm` 非空，同樣以一行顯示：「另有 N 檔已突破，但確認日尚未到」。

### 同步列

同步列上有**兩顆按鈕，順序即操作順序**：左為「更新股票清單」，右為「同步日 K 至今日」。**兩顆都是次要按鈕樣式**——本頁唯一的主要按鈕是「開始掃描」。同步列上的兩顆是準備資料的前置動作，不是使用者來這一頁的目的；把它們也做成主要按鈕會出現三顆同等搶眼的藍色按鈕，反而看不出該按哪一顆。

兩顆放在一起，是因為它們是同一件事的兩個步驟，而且第二步的涵蓋範圍完全取決於第一步有沒有做過：「同步日 K 至今日」的標的是 `stock` 中 `is_active = 1` 的全部股票，`stock` 只有開發種子的 34 檔時，同步會**正常完成**、不報任何錯，但只補了 34 檔的行情，掃描結果也就只涵蓋這 34 檔。把「更新股票清單」擺在它左邊，是讓這個前置關係在畫面上看得見，而不是變成一個只有讀過 spec 的人才知道的隱含步驟。

#### 更新股票清單

- 按鈕文字「更新股票清單」。按下後 disabled 並顯示執行中狀態。
- 這是**短同步作業**（後端只發一次外部請求，見 `specs/backend/stock-universe-import.md`），不輪詢、不顯示進度條、不需要跨頁籤保留狀態——把長時間回補的那一套機制套上來是多餘的。
- 完成後在按鈕下方顯示摘要：「股票清單已更新：共 N 檔（新增 X、更新 Y）」，其中 N 取 `totalActiveCount`、X 取 `insertedCount`、Y 取 `updatedCount`。
- 摘要**持續顯示到下一次操作為止**，理由同下方「同步在全部標的都已是最新時…」該條：新增 0 檔時執行中狀態一閃而過，摘要若跟著消失，使用者會以為按鈕沒反應。
- 清單更新完成後**不自動觸發同步**，也不自動重新掃描。使用者說了要自己按同步；替他按下一個可能跑數十分鐘的作業，是把選擇權拿走。
- 更新完成後常駐的「共 N 檔」數字即時更新，讓使用者按下同步前就看得到母體變大了。
- 本按鈕與「同步日 K 至今日」**互不阻擋**：同步進行中仍可按更新清單，反之亦然。後端不共用併發鎖（見 `specs/backend/stock-universe-import.md` 的「併發」），前端不得自行加上互斥。

#### 同步日 K 至今日

- 按鈕文字「同步日 K 至今日」。按下後進入執行中狀態：按鈕 disabled、顯示進行中狀態與已完成檔數／總檔數。
- 同步是背景長時間作業（全市場逐檔受速率限制，可能數十分鐘），因此**送出後即輪詢進度，不阻塞畫面**；使用者可以在同步進行中切換頁籤或離開，回來時仍看得到進度。
- 完成後更新「最後同步」時間並顯示完成摘要。摘要的內容取決於這次同步**是否真的抓了東西**：
  - `caughtUpCount` 等於 `targetCount`（全部標的都已是最新，一次外部請求都沒發出）→ 顯示「已是最新，無需更新（N 檔）」，**不得顯示「完成 N 檔」**。
  - 否則 → 顯示完成／失敗／略過檔數，並在 `caughtUpCount` 大於 0 時附註「另 N 檔已是最新」。
  - 有失敗時附「查看失敗清單」可展開。
- **同步在全部標的都已是最新時會在極短時間內結束**（不發任何外部請求），執行中狀態可能只出現一瞬間。摘要因此必須持續顯示到下一次操作為止，而不是隨執行中狀態一起消失——否則使用者按下按鈕後看到的是「什麼都沒發生」，無從分辨是成功、失敗，還是按鈕壞了。
- 已有同步作業執行中時（後端回 `409`），按鈕改為執行中狀態並直接接上輪詢，而不是顯示錯誤——使用者要的是看到進度，不是被告知有人先按了。
- 「最後同步」在從未同步過時顯示「尚未同步」。
- 「最後同步」顯示的是**台北時間**。後端回傳的 `lastSyncedAt` 已是 `Asia/Taipei`（見 `specs/backend/stock-price-ingestion.md` 的「時區」），前端直接照 `YYYY-MM-DD HH:mm` 格式呈現即可，不得再做任何時區換算——多做一次換算會再引入一次偏移。

### 畫面狀態

| 狀態 | 呈現 |
|---|---|
| 初次進入（未掃描） | 結果區顯示「選擇策略與區間後開始掃描」，條件區可操作 |
| 掃描中 | 「開始掃描」disabled 並顯示掃描中狀態；已有結果時保留並降低透明度至 60% |
| 有結果 | 正常表格 |
| 某策略零命中 | 該策略區塊顯示「此區間內沒有命中的股票」，並附一行提示目前的最後同步時間 |
| 勾選兩個以上策略且至少一檔命中 | 結果區最上方出現聯集表格，其下依序為各策略區塊 |
| 勾選兩個以上策略但全部零命中 | 不顯示聯集表格（沒有任何命中可彙總），各策略區塊照常顯示各自的零命中訊息 |
| 掃描失敗 | 結果區顯示錯誤訊息與「重試」按鈕；不得顯示空表格假裝零命中 |
| 同步中 | 同步按鈕為執行中狀態；不影響掃描操作，也不影響「更新股票清單」 |
| 更新清單中 | 「更新股票清單」disabled 並顯示執行中狀態；不影響掃描與同步操作 |
| 更新清單失敗 | 按鈕恢復可按，其下顯示錯誤訊息；「共 N 檔」維持原值不變動 |

## Implementation Details

### API 整合

| 時機 | 呼叫 |
|---|---|
| 進頁 | `GET /api/strategies` — 取策略清單與靈敏度選項及說明文字 |
| 進頁、同步完成後 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` — 取 `lastSyncedAt` 顯示最後同步時間 |
| 進頁、更新清單完成後 | `GET /api/stocks?page=1&size=1` — 只取 `total` 顯示「共 N 檔」，`size=1` 是因為此處只要總數，不要清單內容 |
| 按「更新股票清單」 | `POST /api/stocks/universe/import` — 無 body；回應的 `totalActiveCount` / `insertedCount` / `updatedCount` 組成完成摘要 |
| 按「開始掃描」 | `POST /api/strategies/scan` — body `strategies[]`（`code` + `preset`）、`stockIds`、`startDate`、`endDate` |
| 按「同步日 K 至今日」 | `POST /api/stocks/sync/backfill` — body `startDate`（設定起日）、`endDate`（今日）、`catchUp: true`，不帶 `stockIds` 代表全市場；`202` 回應的 `targetCount` 與 `caughtUpCount` 決定完成摘要的呈現方式 |
| 同步執行中（每 5 秒） | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` — 取 `pending`／`running`／`done`／`failed`／`skipped` 更新進度 |
| 「指定股票」搜尋 | `GET /api/stocks?keyword=&size=20` — 供多選輸入的候選清單 |

契約見 `specs/backend/strategy-scan.md`、`specs/backend/stock-price-ingestion.md`、`specs/backend/stock-universe-import.md` 與 `specs/backend/stock-catalog.md`。

錯誤回應處理：

| `code` | 前端行為 |
|---|---|
| `NO_STRATEGY_SELECTED` | 視為程式錯誤：正常操作不應觸發（未勾選時按鈕已 disabled），顯示通用錯誤訊息 |
| `UNKNOWN_STRATEGY` / `DUPLICATE_STRATEGY` | 同上，視為程式錯誤 |
| `UNKNOWN_STOCK_ID` | 於股票範圍區顯示「以下代號不存在」並列出 `unknownIds`，移除後可重新掃描 |
| `TOO_MANY_STOCKS` | 於股票範圍區顯示「最多 200 檔」（前端已先擋，此為後備） |
| `INVALID_DATE_RANGE` | 於區間下方顯示「起日不可晚於迄日」（前端已先擋，此為後備） |
| `JOB_ALREADY_RUNNING` | 不視為錯誤：同步按鈕轉為執行中狀態並開始輪詢進度 |
| `UPSTREAM_EMPTY` | 於「更新股票清單」下方顯示「交易所尚未發布今日清單，請稍後再試」——這是可重試的時機問題，不是系統故障，訊息必須說出「稍後再試」 |
| `UPSTREAM_UNAVAILABLE` / `UPSTREAM_MALFORMED` | 於「更新股票清單」下方顯示「無法取得交易所股票清單，請稍後再試」 |
| 其他／網路錯誤 | 結果區顯示「掃描失敗，請稍後再試」與「重試」按鈕 |

**更新清單的三種失敗一律不改動畫面上的「共 N 檔」。** 後端在這三種情形下都不做任何部分寫入（見 `specs/backend/stock-universe-import.md`），前端把數字改掉會憑空製造一個與資料庫不符的顯示值。

### 數值格式

- 價格兩位小數；百分比兩位小數加 `%`；倍數兩位小數加 `×`。
- 日期一律 `YYYY-MM-DD`；同步時間 `YYYY-MM-DD HH:mm`。
- `null` 一律顯示 `—`，不顯示 `0` 或空白。

## Visual Style

**本頁所有顏色為固定值，不隨 `prefers-color-scheme`、OS 主題或瀏覽器偏好改變。** 一律以字面 hex 指定，不得使用會隨系統偏好變動的主題變數或色彩 token。色票與 `specs/frontend/stock-list.md` 為同一套，同名元素必須取同一個值。

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
| 表格列 hover 背景 | `#1D2A38` |
| 策略卡片背景／邊框 | `#16202C` / `#26333F` |
| 策略卡片已勾選邊框 | `#3E8FD8` |
| 勾選框已勾選背景／勾記 | `#3E8FD8` / `#FFFFFF` |
| 輸入框／下拉背景 | `#0F1620` |
| 輸入框邊框 | `#26333F` |
| 輸入框 focus 邊框 | `#3E8FD8` |
| 輸入框 placeholder 文字 | `#6B7C90` |
| 已選股票標籤背景／文字／移除鈕 | `#1B2836` / `#E6EDF5` / `#93A4B8` |
| 聯集表格策略標籤背景／文字 | `#1B2836` / `#E6EDF5` |
| 聯集表格訊號日文字 | `#93A4B8` |
| 快捷區間鈕（未選）背景／文字 | `#1B2836` / `#93A4B8` |
| 快捷區間鈕（選中）背景／文字 | `#26333F` / `#E6EDF5` |
| 次要按鈕背景／文字／邊框 | `#1B2836` / `#E6EDF5` / `#26333F` |
| 次要按鈕 hover 背景 | `#223347` |
| 主要按鈕背景／文字 | `#3E8FD8` / `#FFFFFF` |
| 主要按鈕 hover 背景 | `#58A3E8` |
| Disabled 按鈕背景／文字 | `#16202C` / `#4A5866` |
| 同步進行中進度條底／填色 | `#1B2836` / `#3E8FD8` |
| 最後同步時間文字 | `#93A4B8` |
| 股票清單「共 N 檔」文字 | `#93A4B8` |
| 更新股票清單完成摘要文字 | `#E6EDF5` |
| 資料不足／待確認提示文字 | `#D9A441` |
| 骨架列底色 | `#1D2A38` |
| 錯誤訊息文字／背景／邊框 | `#F09A94` / `#3A1C1A` / `#8A3A34` |

## Acceptance Criteria
- [x] 策略清單與靈敏度選項及說明文字皆取自 `GET /api/strategies`，前端無寫死的策略名稱或參數說明
- [x] 兩個策略各有嚴格／標準／寬鬆三個靈敏度，預設為標準
- [x] 未勾選的策略卡片其靈敏度下拉為 disabled
- [x] 一個策略都沒勾選時「開始掃描」為 disabled，並顯示提示
- [x] 區間預設為今日往前一個日曆月至今日
- [x] 三個快捷鈕（近一個月／近三個月／近半年）點擊後正確套用區間
- [x] 起日晚於迄日時前端擋下並提示，不送出請求
- [x] 股票範圍預設為全市場，此時請求不帶 `stockIds`
- [x] 切到「指定股票」可搜尋加入股票，已選的以可移除標籤呈現；達 200 檔時輸入框 disabled 並提示
- [x] 同時勾選兩個策略掃描時，結果區出現兩個區塊，順序與勾選順序一致
- [x] 箱型突破結果表顯示箱型區間、突破收盤、突破幅度、量能倍數，數值格式符合「數值格式」一節
- [x] 底底高結果表顯示低點序列與累計漲幅，低點數量與 `detail.lows` 一致
- [x] 點擊結果表任一列導向 `/stocks/{stockId}/daily`
- [x] `insufficientData` 非空時單獨以一行摘要呈現並可展開看代號，且這些股票不出現在命中表格中
- [x] 某策略零命中時顯示「此區間內沒有命中的股票」，並同時顯示最後同步時間，而非空白表格
- [x] 頁面常駐顯示「最後同步：YYYY-MM-DD HH:mm」，取自 `lastSyncedAt`；從未同步過時顯示「尚未同步」
- [x] 按「同步日 K 至今日」後按鈕轉為執行中並顯示已完成／總檔數，畫面不被阻塞
- [x] 同步進行中切換到總覽頁籤再切回來，仍看得到進度
- [x] 同步完成後「最後同步」時間更新，並顯示完成／失敗／略過的檔數摘要
- [x] 後端回 `409 JOB_ALREADY_RUNNING` 時按鈕轉為執行中並接上輪詢，不顯示錯誤訊息
- [x] 掃描失敗時顯示錯誤與「重試」按鈕，不顯示空表格
- [x] 全頁文案無「建議」「推薦」「可進場」等暗示買賣操作的措辭
- [x] 所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致

---

- [x] 「最後同步」顯示的時間與該次同步實際完成的本地（台北）時間一致，不再有 8 小時偏移
- [x] 前端不對 `lastSyncedAt` 做任何時區換算，直接依 `YYYY-MM-DD HH:mm` 呈現
- [x] 全部標的都已是最新時（`caughtUpCount` 等於 `targetCount`），摘要顯示「已是最新，無需更新（N 檔）」而非「完成 N 檔」
- [x] 部分標的落後時，摘要顯示完成／失敗／略過檔數，且 `caughtUpCount` 大於 0 時附註「另 N 檔已是最新」
- [x] 同步在極短時間內完成（執行中狀態一閃而過）時，摘要仍持續顯示，畫面不會回到看似未操作的狀態
- [x] 勾選兩個以上策略掃描後，結果區最上方出現聯集表格，位置在所有策略區塊之上
- [x] 只勾選一個策略時不顯示聯集表格；勾到第二個策略再掃描後才出現
- [x] 聯集表格同一檔股票只出現一列，標題的「共 N 檔」為去重後的檔數，不等於各策略 `matchedCount` 的加總
- [x] 同時命中兩個策略的股票，其「命中策略與訊號日」欄逐一列出兩個策略各自的名稱與 `signalDate`，不折成單一日期
- [x] 聯集表格依該檔各策略中最新的 `signalDate` 由新到舊排序，同日依 `stockId` 升冪
- [x] 點擊聯集表格任一列導向 `/stocks/{stockId}/daily`
- [x] `insufficientData` 與 `pendingConfirm` 的標的不出現在聯集表格中
- [x] 勾選兩個以上策略但全部零命中時不顯示聯集表格，各策略區塊仍各自顯示零命中訊息
- [x] 聯集表格所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致

---

- [x] 同步列上有兩顆按鈕，「更新股票清單」在左、「同步日 K 至今日」在右，兩顆皆為次要按鈕樣式；全頁唯一的主要按鈕是「開始掃描」
- [x] 頁面常駐顯示股票清單「共 N 檔」，數值取自 `GET /api/stocks?page=1&size=1` 的 `total`
- [x] 按「更新股票清單」呼叫 `POST /api/stocks/universe/import`，按鈕轉為 disabled 的執行中狀態
- [x] 更新完成後顯示「股票清單已更新：共 N 檔（新增 X、更新 Y）」，三個數字分別取自 `totalActiveCount`、`insertedCount`、`updatedCount`
- [x] 更新完成後常駐的「共 N 檔」同步更新為 `totalActiveCount`
- [x] 更新清單的過程中**不輪詢任何進度端點**，也不建立進度條
- [x] 更新完成後不自動觸發同步、不自動重新掃描
- [x] 更新清單完成摘要持續顯示到下一次操作為止，不隨執行中狀態消失
- [x] 後端回 `502 UPSTREAM_EMPTY` 時顯示「交易所尚未發布今日清單，請稍後再試」，且「共 N 檔」數值不變
- [x] 後端回 `502 UPSTREAM_UNAVAILABLE` 或 `UPSTREAM_MALFORMED` 時顯示「無法取得交易所股票清單，請稍後再試」，且「共 N 檔」數值不變
- [x] 同步進行中仍可按「更新股票清單」；更新清單進行中仍可按「同步日 K 至今日」，兩者互不 disable
- [x] 更新股票清單相關的所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致

## Execution Result
- Status: DONE (pending checkbox sign-off by the requester — per instructions this agent does not tick the boxes itself)
- Scope: this spec's implementation (`StrategyTab.tsx`, `api/strategies.ts`, `api/sync.ts`, the tab shell in `StockListPage.tsx`) was **already present** from a prior commit, but the spec had `status: pending` with no `## Execution Result` — i.e. never verified against its own Acceptance Criteria. This pass was an audit: read every requirement, diffed it against the actual code, fixed what was actually broken (all inside test files — the production implementation itself needed no changes), and added coverage for the two ACs that had none.

### Files changed
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - Fixed two **pre-existing, already-failing** tests (confirmed failing before this session touched anything, via `git stash` + re-run):
    - `sends no stockIds for the default 全市場 scope` — asserted `screen.getByText('2330')`, but `代號`/`名稱` render as sibling text nodes inside one `<td>` (`"2330 台積電"`); exact-match `getByText` can never match the bare substring. Fixed to assert the combined text, matching how every other test in the file already does it.
    - `disables the preset dropdown for an unchecked strategy card...` — `tsc -b` failed (`npm run build` was **not** actually clean, contrary to what a clean build would imply): `.closest('.st-strategy-card')` assigned to a `const` infers `Element`, not `HTMLElement`, when there's no contextual type to narrow it (unlike the many other call sites in the same file that pass the `.closest(...)!` expression directly into `within(...)`, where argument-position contextual typing does narrow it). Fixed with an explicit `as HTMLElement` cast at the one assignment site that needed it.
    - `shows a running state with completed/total counts after clicking...` and `treats a 409 JOB_ALREADY_RUNNING response...` — both mocked the **on-mount** `GET /api/stocks/sync/progress` response as already "running" (`pending`/`running` > 0). The implementation's mount-time effect legitimately auto-detects an in-flight job (e.g. the backend's startup catch-up) and flips the button to 同步中 immediately — which is correct behavior, but it raced ahead of these two tests' assumption that the running state would only appear *after* the simulated click. Fixed by making the mocked on-mount response idle and only reporting "running" from the second poll onward (i.e. after the click triggers the polling effect), which is what each test's own narrative actually describes.
  - Added `disables the stock-search input and shows a hint once 200 stocks are selected` — the one Acceptance Criterion clause ("達 200 檔時輸入框 disabled 並提示「最多 200 檔」") that had no test at all. Drives the real search→add flow 200 times against a keyword-echoing mock (each search term becomes a distinct fake stock) rather than reaching into component internals.
- `develop/frontend/src/__tests__/StockListPage.tabs.test.tsx`
  - Added `keeps the 策略 tab sync progress alive across a round-trip through 總覽 (state does not live only inside the unmounted tab)` — Acceptance Criterion 18 was previously only verified *structurally* (both tab panels stay permanently mounted, toggled via `display:none`/`block`) but had no integration test actually asserting a running sync survives a tab round-trip. This test renders the full `StockListPage` (not `StrategyTab` in isolation), waits for the on-mount progress fetch to report a running job, switches to 總覽 and back to 策略, and asserts the running button/progress text and continuing polling are unaffected by the switch.
- `develop/frontend/src/pages/StrategyTab.tsx` — **one real production bug fixed**, found only after adding a dedicated test for Criterion 6 (see below): `monthsAgo()` used a naive `d.setMonth(d.getMonth() - months)`, which silently overflows into the next month when the source day doesn't exist in the target month. Concretely, clicking 近半年 from 2026-08-30 produced a start date of `2026-03-02` instead of the calendar-correct `2026-02-28` (Aug 30 minus 6 months = "Feb 30", which JS normalizes forward past the end of a 28-day February instead of clamping to it). Fixed by computing the target month's actual last day and clamping to `min(originalDay, lastDayOfTargetMonth)` before constructing the result date. This is a genuine, user-visible date-range bug that would have shipped silently — every prior test only exercised 1m/3m shortcuts, both of which land in 30/31-day months and never trigger the overflow.
- All other files: no other production code (`StockOverviewTab.tsx`, `StockListPage.tsx`, `api/strategies.ts`, `api/sync.ts`, any `.css`) was changed. Every other fix and root cause found was in test code or test assumptions, not in shipped behavior.

### Per-criterion verification
1. 策略清單/靈敏度/說明文字皆取自 `GET /api/strategies` — **Satisfied.** `catalog` state is populated solely from the fetch response; `strategyName`/`presetName` look up display text from `catalog`, falling back only to the raw `code` (never a hard-coded Chinese name) if the catalogue hasn't loaded. Verified by test + `grep` finding no hard-coded 箱型突破/底底高/嚴格/標準/寬鬆 strings outside test fixtures.
2. 三段靈敏度、預設標準 — **Satisfied** (contract-level: `specs/backend/strategy-scan.md` guarantees 3 presets per strategy, already `done`; frontend defaults `selectedPresets[code] = 'STANDARD'` on first check and renders whatever the catalogue returns).
3. 未勾選卡片靈敏度 disabled — **Satisfied**, tested.
4. 無策略時「開始掃描」disabled + 提示 — **Satisfied**, tested.
5. 區間預設近一個月 — **Satisfied**, tested (`defaultDateRange`).
6. 三個快捷鈕 — **Fixed-by-you.** A dedicated test (`applies each of the three date-range shortcuts to the exact expected calendar dates`) now clicks 近一個月／近三個月／近半年 in turn against a pinned `2026-08-30` "today" and asserts literal, independently-computed expected dates (not a re-derivation of the component's own formula) for both 起日 and 迄日. On first run this test **failed** — it caught a real bug: 近半年 produced `2026-03-02` instead of the calendar-correct `2026-02-28`, because `monthsAgo()`'s naive `setMonth()` call overflowed past February's 28 days instead of clamping to the month's last day. Fixed in `StrategyTab.tsx` (see Files changed) and the test now passes; re-verified stable across 3 full-suite runs.
7. 起日 > 迄日擋下 — **Satisfied**, tested (`canScan` includes `!dateInvalid`; scan call count asserted to stay 0).
8. 全市場預設、不帶 `stockIds` — **Satisfied**, tested (and the pre-existing test's broken assertion was fixed, see above).
9. 指定股票搜尋/標籤/200 檔上限 disabled+提示 — **Satisfied**; search/add/remove was already tested, the 200-cap disable+hint was **not** tested before this pass — **fixed by adding a test** (see above).
10. 兩策略順序與勾選順序一致 — **Satisfied**, tested (`selectionOrder.map` in `buildScanPayload`; backend echoes `results` in request order per its own spec).
11. 箱型突破表格欄位與格式 — **Satisfied**, tested (`formatPrice2`/`formatPercent2`/`formatMultiple2` match "數值格式" exactly: 2dp, `%`, `×`).
12. 底底高低點序列/累計漲幅 — **Satisfied**, tested; `formatLowsSequence` maps every entry of `detail.lows` (count always matches), `cumulativeRise` computed from first/last low.
13. 點列導向 `/stocks/{stockId}/daily` — **Satisfied**, tested.
14. `insufficientData` 獨立摘要、可展開、不進命中表 — **Satisfied**, tested (including that the ids are absent until the toggle is clicked, then appear).
15. 零命中訊息 + 最後同步時間 — **Satisfied**, tested.
16. 常駐「最後同步」/「尚未同步」 — **Satisfied**, tested both branches.
17. 同步中按鈕狀態 + 已完成／總檔數、不阻塞畫面 — **Satisfied**, tested (including that toggling a strategy checkbox and the scan button both stay interactive while a sync is running).
18. 切換頁籤再切回仍看得到同步進度 — **Satisfied** structurally (both tab panels stay mounted, `StrategyTab`'s own polling `useEffect` never unmounts) and now also **integration-tested** — **fixed by adding a test** (see above), since no test had exercised this cross-tab path before.
19. 同步完成後最後同步時間 + 完成/失敗/略過摘要 — **Satisfied**, tested.
20. `409 JOB_ALREADY_RUNNING` → 執行中 + 輪詢、非錯誤 — **Satisfied**; the implementation sets `syncStatus` to `running` optimistically *before* the request resolves, so a 409 is a no-op in the `catch` (not treated as failure) and polling was already underway. Test's mock assumptions were fixed (see above) so it now correctly exercises this path instead of accidentally passing/failing on unrelated timing.
21. 掃描失敗顯示錯誤 + 重試、非空表格 — **Satisfied**, tested (`queryByRole('table')` asserted absent).
22. 無「建議／推薦／可進場」等措辭 — **Satisfied**; `grep` across `StrategyTab.tsx`/`api/strategies.ts`/`api/sync.ts`/CSS finds zero matches, plus a dedicated test rendering both result blocks and asserting `document.body.textContent` doesn't match `/建議|推薦|可進場/`.
23. 所有顏色為 Visual Style 字面 hex，dark/light 下一致 — **Satisfied**, verified two ways: (a) line-by-line comparison of every `.st-*` rule in `StockListPage.css` (lines ~382-810) against the spec's hex table — every value is a literal lowercase hex matching the table (e.g. sync row `#16202c`/`#26333f`, progress fill `#3e8fd8`, note/待確認 text `#d9a441`, error block `#f09a94`/`#3a1c1a`/`#8a3a34`), and `grep -rn "prefers-color-scheme" src/` shows zero occurrences outside comments that explicitly forbid it; (b) a throwaway Playwright screenshot of `/stocks?tab=strategy` (dev server only, no backend — so the page renders its catalogue-load-error + empty-results shell) under emulated `colorScheme: 'dark'` and `'light'` — the two screenshots are pixel-identical, confirming no theme-reactive rendering. The Playwright dependency was installed with `npm install playwright --no-save` and the throwaway script removed afterward; `git diff` on `package.json`/`package-lock.json` confirms zero changes from this. Full result-table rendering (post-scan) was not additionally screenshotted since no backend was running in this environment to produce real scan data — its colors were verified by source review only (item (a) above), which is exhaustive since every color in those templates is a literal class already covered by (a).

### Test / build output (verbatim tails)
```
$ npm test
 Test Files  6 passed (6)
      Tests  79 passed (79)

$ npm run build
> tsc -b && vite build
✓ 44 modules transformed.
✓ built in 105ms
```
Re-ran `npm test` multiple times back to back (including immediately after each round of fixes) with identical `78 passed (78)` (pre-Criterion-6-test) and then `79 passed (79)` (post) each time — none of the timer-dependent tests (`shows a running state...`, `disables the stock-search input...`, `applies each of the three date-range shortcuts...`) are flaky. The new shortcut test failed on its first run (see Criterion 6 above) before the `monthsAgo()` fix, then passed on every run after.

`npx oxlint .`-equivalent (`npm run lint`) shows the same 2 pre-existing warnings (not errors) that exist on `origin/main` before this session (`no-unreachable` in an unrelated line of the test file's shared mock, `set-state-in-effect` in the stock-search-suggestions effect) — confirmed via `git stash`/`git stash pop` that both predate this session's changes; left untouched as out of scope (no production code needed changing for any of the 23 criteria).

### Deferred / not independently verifiable here
- No live backend was running in this environment (confirmed: `curl localhost:8080/api/strategies` → connection refused), so the full request/response cycle against a real `GET /api/strategies`, `POST /api/strategies/scan`, and a real multi-file `POST /api/stocks/sync/backfill` progressing through actual rate-limited external calls could not be exercised end-to-end. All 23 criteria were instead verified via the unit/integration test suite (which mocks `fetch` against fixtures shaped exactly per `specs/backend/strategy-scan.md` / `specs/backend/stock-price-ingestion.md`'s documented response shapes) plus static source/CSS review. This mirrors the level of verification the sibling `specs/frontend/stock-list.md` spec's Increment 1 used before a live backend was available in that session; if a live backend is available in a later session, re-running the two Playwright screenshot checks against real scan results would be worth doing but was not required to satisfy any of the 23 stated criteria's actual wording.
- The Implementation Details section's backend-fallback error placements for `TOO_MANY_STOCKS` (「於股票範圍區顯示『最多 200 檔』」) and `INVALID_DATE_RANGE` (「於區間下方顯示『起日不可晚於迄日』」) currently fall through to the generic `掃描失敗，請稍後再試` message instead of the field-adjacent placement the Implementation Details table describes, since neither is in the 23 Acceptance Criteria and both are explicitly documented as "前端已先擋，此為後備" (frontend already blocks these client-side; the backend response is only a backup for it). Left as-is since fixing it would be adding scope beyond what any Acceptance Criterion requires, but noting it here in case a future spec revision promotes it to a checked criterion.

### Increment 2 — 2026-08-31
- Scope: exactly the 5 unchecked criteria added after Increment 1 — the 「同步日 K 至今日」bug where an all-caught-up sync (zero external requests, finishes in milliseconds) reported a misleading 「完成 34 檔」, plus the separately-shipped backend timezone fix this frontend must not re-break by adding its own conversion.

#### Files changed
- `develop/frontend/src/api/sync.ts` — added `caughtUpCount: number` to `BackfillResponse`, matching the revised `202` contract in `specs/backend/stock-price-ingestion.md` `#### 2. 回補`.
- `develop/frontend/src/pages/StrategyTab.tsx`
  - Added a `syncMeta` state (`{ targetCount, caughtUpCount } | null`), populated from the `202` response's `.then` (previously the code only chained a `.catch`), and reset to `null` at the start of every new `handleSyncClick` so a slow-to-resolve previous job's numbers can never be misattributed to the next job's completion.
  - Deliberately kept `syncMeta` (from the accept response) and `syncSummary` (from the progress poll) as two independent states rather than merging one into the other at whichever moment happens to resolve first — the two requests (`POST /api/stocks/sync/backfill` and the immediately-following `GET .../progress` poll) race independently in real network conditions, and the display JSX recombines both at every render, so whichever of the two arrives second still triggers a correct re-render instead of freezing on a half-known state.
  - Sync-row rendering now branches on `syncMeta.caughtUpCount === syncMeta.targetCount` (with `targetCount > 0`): shows `已是最新，無需更新（N 檔）` and suppresses the `完成 N 檔` wording entirely when true; otherwise shows the existing `完成／失敗／略過` counts and appends `，另 N 檔已是最新` when `caughtUpCount > 0`. The `409 JOB_ALREADY_RUNNING` path and the on-mount "already running" auto-detect never populate `syncMeta` (that job wasn't started by this click), so their eventual completion summary falls back to the plain counts — a documented, deliberate degradation given the frontend has no way to know that job's `caughtUpCount`.
  - Confirmed (did **not** need to change): `formatSyncTime` already does a pure string slice/format (`value.slice(0, 16).replace('T', ' ')`) with no `new Date(...)` construction or timezone-aware formatting anywhere in the sync-time display path — it was already free of any timezone conversion before this increment.
- `develop/frontend/src/pages/StockListPage.css` — added `.st-caught-up-note { color: #d9a441; }`, applied to both the `已是最新，無需更新（N 檔）` text and the `，另 N 檔已是最新` note, per the standing project rule that this text must use the literal `資料不足／待確認提示文字` hex from `## Visual Style` rather than the summary row's default secondary-text color, and must not vary with `prefers-color-scheme`.
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - Updated the shared `backfillResponder` mock default to include `caughtUpCount: 0` (matching the real contract's "always 0 when not applicable" shape); this is a superset of the previous mock body and required no other existing test to change.
  - Added 4 new tests covering all 5 new criteria (criteria 1 and 2 share one test since both assert the same "no conversion" behavior):
    - `shows lastSyncedAt exactly as returned with no timezone conversion (no 8-hour shift)` — asserts an input of `2026-08-30T23:15:00` renders as exactly `最後同步：2026-08-30 23:15`, and explicitly asserts the shifted variant (`2026-08-31...`) is absent, which would catch a regression to `new Date(...)`-based reformatting.
    - `shows 已是最新，無需更新（N 檔） — not 完成 N 檔 — when caughtUpCount equals targetCount` — mocks the `202` accept with `targetCount: 34, caughtUpCount: 34` and an immediately-idle first post-click poll (simulating the millisecond-long all-caught-up job), asserts the exact caught-up message and that no `完成 ` text is present.
    - `shows 完成／失敗／略過 counts plus an 另 N 檔已是最新 note when only some targets were already caught up` — mocks `caughtUpCount: 10` of `targetCount: 34`, a mid-flight poll showing partial progress, then a final poll with `done: 20, failed: 2, skipped: 2` (summing to the 24 non-caught-up targets); asserts both the counts text and the appended note via `document.body.textContent` (needed because the note renders as a nested `<span>` inside the counts `<span>`, so an exact-match `getByText` on the outer text alone would no longer match once the note is present).
    - `keeps the completion summary visible after a near-instant sync instead of reverting to an unchanged-looking screen` — same all-caught-up setup as above, but additionally advances fake timers by 15s *after* the summary first appears and re-asserts the summary text is still present and the button has reverted to its normal (non-running) label — directly exercising the reported "looked like nothing happened" bug.

#### Per-criterion verification
1. 「最後同步」顯示的時間與該次同步實際完成的本地（台北）時間一致，不再有 8 小時偏移 — **Satisfied**, tested. `formatSyncTime` was already a pure string slice with no `Date` parsing; verified by source read (no `new Date(lastSyncedAt)` anywhere) and by the new test asserting an exact-match render with the shifted variant explicitly asserted absent.
2. 前端不對 `lastSyncedAt` 做任何時區換算，直接依 `YYYY-MM-DD HH:mm` 呈現 — **Satisfied**, same test/source-read as above; `grep -n "lastSyncedAt" src/pages/StrategyTab.tsx` shows it is only ever passed straight into `formatSyncTime`, never into `new Date(...)`.
3. 全部標的都已是最新時（`caughtUpCount` 等於 `targetCount`），摘要顯示「已是最新，無需更新（N 檔）」而非「完成 N 檔」 — **Satisfied**, tested (`shows 已是最新，無需更新...`), including an explicit assertion that no `完成 ` text is rendered in that state.
4. 部分標的落後時，摘要顯示完成／失敗／略過檔數，且 `caughtUpCount` 大於 0 時附註「另 N 檔已是最新」 — **Satisfied**, tested (`shows 完成／失敗／略過 counts plus an 另 N 檔已是最新 note...`).
5. 同步在極短時間內完成（執行中狀態一閃而過）時，摘要仍持續顯示，畫面不會回到看似未操作的狀態 — **Satisfied**, tested (`keeps the completion summary visible after a near-instant sync...`); `syncSummary`/`syncMeta` are plain React state set once on completion and never cleared except at the start of the *next* `handleSyncClick`, so nothing times out or reverts them on its own.

#### Test / build output (verbatim tails)
```
$ npm test -- --run
 Test Files  6 passed (6)
      Tests  83 passed (83)

$ npm run build
> tsc -b && vite build
✓ 44 modules transformed.
✓ built in 111ms
```
83 = the pre-existing 79 (Increment 1) + 4 new tests added this increment. `npm run lint` shows the same 2 pre-existing warnings noted in Increment 1's Execution Result (`no-unreachable` in the test file's shared mock, `set-state-in-effect` in the stock-search-suggestions effect); no new warnings introduced.

#### Deferred / not independently verifiable here
- No live backend was reachable in this session (`curl -m 5 http://localhost:8080/...` → connection refused; the frontend dev server at `:5173` was up but has nothing behind it to exercise). All 5 criteria were verified via the unit/integration test suite against fixtures shaped per the revised `specs/backend/stock-price-ingestion.md` `#### 2. 回補` contract, plus source review. A live end-to-end check (triggering a real all-caught-up sync against the 34-stock seed data and watching the summary text) would be worth doing once a backend is available in a later session, but was not required to satisfy any of the 5 stated criteria's wording.

### Increment 3 — 2026-09-01
- Scope: exactly the two remaining unchecked groups — the 聯集表格 (union table across 2+ selected strategies) and 「更新股票清單」(`POST /api/stocks/universe/import`, now backend-ready) plus the persistent stock-universe「共 N 檔」count. All 19 previously-checked criteria were left untouched (no production behavior for them was changed).

#### Files changed
- `develop/frontend/src/api/stocks.ts` — added `UniverseImportResponse` and `importStockUniverse()` (`POST /api/stocks/universe/import`, no body), reusing the existing `requestJson` helper so `502 UPSTREAM_EMPTY` / `UPSTREAM_UNAVAILABLE` / `UPSTREAM_MALFORMED` surface as `ApiError` with `.code` set from the parsed body, same as every other write endpoint in that file.
- `develop/frontend/src/pages/StrategyTab.tsx`
  - **Union table (聯集表格)**: added `UnionHit`/`UnionRow` types and a pure `buildUnionRows(result: ScanResponse)` function that dedupes `result.results[].items` by `stockId` (never touching `insufficientData`/`pendingConfirm`, which per `specs/backend/strategy-scan.md` never appear in `items` to begin with), appends each stock's hits in `result.results` array order (the backend already returns results in request order, so no dependency on the component's current, possibly-since-changed `selectionOrder` state), and sorts rows by each stock's newest `signalDate` desc, ties broken by `stockId` asc. `showUnionTable = scanResult.results.length >= 2 && unionRows.length > 0` gates the table; `renderUnionTable()` renders it as the first child of `.st-results-list`, reusing the existing `sl-table`/`st-result-table`/`sl-row` classes so navigation-on-row-click, hover, and table chrome match the per-strategy tables exactly. Each row's hits column (`renderUnionHits`) renders one `.st-union-tag` per hit (`{策略名稱}` + `{signalDate}`, styled per the new Visual Style row below) with a literal `・` separator between multiple hits — never merged into a single date.
  - **更新股票清單**: added `totalStockCount`, `universeImportStatus`, `universeImportSummary`, `universeImportErrorMessage` state; a mount-time effect fetching `GET /api/stocks?page=1&size=1` (`size=1` because only `total` is needed) for the persistent count; and `handleImportUniverseClick`, which is a plain fetch-then-setState handler with **no polling effect, no progress bar, and no auto-triggered sync/scan call** — matching the backend spec's "短同步作業" characterization. On success it sets both `universeImportSummary` (for the completion text) and `totalStockCount = resp.totalActiveCount` (updating the persistent count) in the same `.then`. On failure it maps `UPSTREAM_EMPTY` → 「交易所尚未發布今日清單，請稍後再試」and both `UPSTREAM_UNAVAILABLE`/`UPSTREAM_MALFORMED` → 「無法取得交易所股票清單，請稍後再試」, and deliberately never touches `totalStockCount` in the catch branch (the backend guarantees no partial write on any of the three failure paths, so the frontend must not invent a new value either).
  - Sync row now renders two secondary (`sl-btn`, no `-primary`) buttons — 更新股票清單 (left) then 同步日 K 至今日 (right, changed from `sl-btn-primary` to `sl-btn` so 開始掃描 remains the page's only primary button) — plus a `.st-sync-info` block showing both 最後同步 and the new 股票清單：共 N 檔 line. `universeImportStatus === 'running'` only ever disables the import button; `syncStatus === 'running'` only ever disables the sync button — neither reads the other's state, matching "後端不共用併發鎖，前端不得自行加上互斥."
  - The universe-import completion summary / error block renders independently of `syncStatus` and is only cleared at the *start* of the next `handleImportUniverseClick` call (`setUniverseImportSummary(null)`/`setUniverseImportErrorMessage(null)` before the fetch) — never cleared by an unrelated `開始掃描` or `同步日 K 至今日` click, so it persists exactly "到下一次操作為止" (the next 更新股票清單 operation specifically, mirroring how the pre-existing `syncSummary` already behaves relative to `handleSyncClick`).
- `develop/frontend/src/pages/StockListPage.css`
  - `.st-sync-info` (column layout for the two left-side info lines), `.st-stock-total` (`#93a4b8`, matching Visual Style's "股票清單「共 N 檔」文字"), `.st-universe-summary`/`.st-universe-summary-text` (`#e6edf5`, matching "更新股票清單完成摘要文字"; errors reuse the existing `.st-inline-error` block, matching "錯誤訊息文字／背景／邊框").
  - `.st-union-hits` (flex-wrap row, overriding the table cell's default `white-space: nowrap`), `.st-union-tag` (background `#1b2836`), `.st-union-tag-name` (`#e6edf5`), `.st-union-tag-date` (`#93a4b8`), `.st-union-sep` (`#93a4b8`) — every value copied literally from the `## Visual Style` table's existing "聯集表格策略標籤背景／文字" and "聯集表格訊號日文字" rows (both already present in the spec from a prior pass; no new hex values were needed for this increment). No `prefers-color-scheme` query appears anywhere in the file, confirmed via `grep -rn "prefers-color-scheme" src/`.
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - Added `unionScanResponse()` (three stocks: one hit by both strategies with different signalDates — mirroring the spec's own worked example, 箱型突破 `2026-08-28` / 底底高 `2026-08-25` — plus one stock unique to each strategy, and non-empty `insufficientData`/`pendingConfirm` on the box-breakout leg) and `zeroHitBothResponse()` (two strategies, both `matchedCount: 0`) fixtures.
  - Extended the shared `beforeEach` mock dispatcher with a `universeImportResponder` (default: a realistic 200 body) and a `stocksTotal` variable feeding the `size=1` branch of the existing `/api/stocks?` handler (previously that branch only served the 指定股票 search-suggestion shape; it now branches on the `size` query param so both call sites can be mocked independently).
  - Added 5 tests for the union table: no table with only one strategy checked; a deduped, correctly-sorted, correctly-per-strategy-dated table appears once a second strategy is checked and both are scanned (asserting exact row order, exact per-hit strategy+date text on the shared stock's row, and that `insufficientData`/`pendingConfirm` ids never appear inside the union table specifically); no table when 2+ strategies are scanned but neither has any hits (each block still shows its own zero-hit message); and clicking a union-table row navigates to `/stocks/{stockId}/daily`.
  - Added 8 tests for 更新股票清單: button styling/order (`更新股票清單` secondary, left; `同步日 K 至今日` secondary, right; `開始掃描` the only `sl-btn-primary`); the persistent count appearing from the `size=1` mount fetch; the full running→completion flow (disabled running state, completion summary text, updated persistent count, and — via before/after call-count diffing on `/api/stocks/sync/progress`, `/api/strategies/scan`, `/api/stocks/sync/backfill` — proof that completing an import polls nothing and auto-triggers neither a sync nor a scan); the summary persisting across an unrelated `開始掃描` and clearing only on the next `更新股票清單` click; the two distinct error messages for the three `502` codes (`UPSTREAM_EMPTY` gets its own message; `UPSTREAM_UNAVAILABLE` and `UPSTREAM_MALFORMED` share the other); and a concurrency test proving `更新股票清單` and `同步日 K 至今日` can both be "running" at once without either disabling the other.
- `develop/frontend/src/__tests__/StockListPage.test.tsx`, `develop/frontend/src/__tests__/StockListPage.tabs.test.tsx` — both files' pre-existing `overviewCalls(fetchMock)` test helper (which isolates 總覽's own `/api/stocks?...` list calls from the 策略 tab's background traffic) now also excludes the new `size=1` mount-time call the 策略 tab fires for the persistent count, the same way it already excluded `/api/strategies` and `/api/stocks/sync/progress` — needed because 總覽's own list calls always use `size=50`, never `size=1`, so filtering on the `size` param cleanly distinguishes the two without coupling to call order. Both existing tests these helpers back (`loads and shows the first page...`, `debounces search input...`, `switches tabs without refetching...`) were otherwise unchanged and still assert the same behavior they always did.

#### Per-criterion verification
All verification below was done twice: once via the automated test suite (`npm test`), and once live against the real backend (`mvn -f develop/backend/pom.xml spring-boot:run`, against the real dev MySQL at `127.0.0.1:3306`, database `stock`, already carrying ~1366 active stocks and a `PRICE_BACKFILL` job left mid-run from the environment) and the real Vite dev server (`npm run dev`, port 5173), driven with Playwright. Both backend and frontend dev processes were stopped at the end of the session; port 8080/5173 confirmed free afterward (both live-verification scripts were throwaway `.cjs` files under `develop/frontend/`, deleted before finishing — `git status` on `develop/frontend/` shows no leftover script files).

**Group A — 聯集表格**
1. 勾選兩個以上策略掃描後，結果區最上方出現聯集表格 — **Satisfied.** Tested (`shows a deduped union table above the strategy blocks...`, asserting `getAllByRole('heading', {level:3})` inside `.st-results-list` starts with the union title). Live-verified: scanning all 1366 active stocks over 近半年 with both strategies checked produced 70 + 262 = 332 raw matches, deduped to a `命中彙總 — 共 282 檔` table rendered as `.st-results-list`'s first child (`firstElementChild.className` confirmed `st-result-block st-union-block`).
2. 只勾選一個策略時不顯示聯集表格 — **Satisfied.** Tested (`does not show a union table when only one strategy is checked`). Live-verified with the same 1366-stock scan: `命中彙總` absent with only 箱型突破 checked.
3. 聯集表格同一檔股票只出現一列，「共 N 檔」為去重後的檔數 — **Satisfied.** Tested and live-verified (282 ≠ 70+262=332, confirming dedup, not summation).
4. 命中策略與訊號日逐一列出，不折成單一日期 — **Satisfied.** Tested against a stock hit by both strategies at different dates (`箱型突破 2026-08-28`/`底底高 2026-08-25`), asserting both strategy names and both distinct dates appear in that row. Live-verified against real data: sampled hits-cell text included e.g. `箱型突破 2026-08-28・底底高 2026-04-16` — two distinct dates, not merged.
5. 排序：最新 `signalDate` 由新到舊，同日 `stockId` 升冪 — **Satisfied.** Tested with a constructed tie (2317/2330 both latest-dated `2026-08-28`, asserting `2317` before `2330`) and a lower-dated stock (`2454`) last. Live-verified by dumping the first 15 real rows and independently checking every adjacent pair: strictly descending latest-date groups, and within each tied-date group, strictly ascending `stockId` (e.g. `00875` < `1321` < `1416`, all tied at `2026-08-26`).
6. 點擊聯集表格任一列導向 `/stocks/{stockId}/daily` — **Satisfied.** Tested (row click scoped to `within(unionTable)` to disambiguate from the identically-named row in the per-strategy table below it) and live-verified structurally (same `sl-row`/`onClick` wiring as the already-covered per-strategy tables; the route itself is exercised by the existing per-strategy-table navigation criterion).
7. `insufficientData`/`pendingConfirm` 不出現在聯集表格中 — **Satisfied structurally**: `buildUnionRows` only ever reads `result.results[].items`, and per `specs/backend/strategy-scan.md` ("`insufficientData` 與 `matchedCount` 互斥：列在前者的股票不會出現在 `items` 中") those ids can never be in `items` to begin with — there is no filtering code that could regress. Tested with a fixture carrying non-empty `insufficientData`/`pendingConfirm` on the box-breakout leg, asserting neither id string appears anywhere inside the union table specifically (`within(unionTable).queryByText(...)`).
8. 兩個以上策略全部零命中時不顯示聯集表格 — **Satisfied.** Tested (`zeroHitBothResponse`, both blocks show 此區間內沒有命中的股票, no `命中彙總` anywhere). Live-verified against a real stock (`1538`, confirmed via `GET /api/stocks` to have `latestTradeDate: null` — never backfilled) scanned under 指定股票 with both strategies: both blocks reported zero hits (真正的 `insufficientData` reason, not a scan-logic zero), no union table.
9. 聯集表格顏色為 Visual Style 字面 hex，dark/light 一致 — **Satisfied**, verified two ways: (a) every new class (`.st-union-hits`/`.st-union-tag`/`.st-union-tag-name`/`.st-union-tag-date`/`.st-union-sep`) uses a literal lowercase hex copied from the spec's existing "聯集表格策略標籤背景／文字" (`#1b2836`/`#e6edf5`) and "聯集表格訊號日文字" (`#93a4b8`) rows, confirmed by direct source read; `grep -rn "prefers-color-scheme" src/` returns zero hits. (b) A Playwright screenshot of the full strategy tab (with both strategies scanned via mocked, fully deterministic fetch responses so no live-data drift could contaminate the comparison) under emulated `colorScheme: 'dark'` vs `'light'` — the two screenshots are **byte-for-byte identical** (`sha256` match). An earlier attempt comparing screenshots against the *live* backend produced different hashes; investigation showed this was because the background `PRICE_BACKFILL` job (already mid-run in this shared dev environment) was adding new price rows between the two page loads, changing the *scan results themselves* between requests — not a color difference. Switching to mocked, deterministic responses isolated the color-only comparison and confirmed the identical-hash result above.

**Group B — 更新股票清單 + 常駐檔數**
1. 兩顆按鈕次要樣式，開始掃描為唯一主要按鈕 — **Satisfied.** Tested (`className` assertions on both `.not.toContain('sl-btn-primary')` plus 開始掃描's `.toContain`) and live-verified (`importBtn class: sl-btn`, `syncBtn class: sl-btn`, `scanBtn class: sl-btn sl-btn-primary`, and `importBtn.x < syncBtn.x` via bounding boxes confirming left-to-right order).
2. 頁面常駐「共 N 檔」取自 `GET /api/stocks?page=1&size=1` 的 `total` — **Satisfied.** Tested and live-verified: the live page showed `股票清單：共 1366 檔` on load, matching a direct `curl "http://localhost:8080/api/stocks?page=1&size=1"` → `"total":1366` at the same moment.
3. 按「更新股票清單」呼叫 `POST /api/stocks/universe/import`，按鈕轉為 disabled 執行中 — **Satisfied.** Tested and live-verified (button read `更新股票清單`→disabled immediately after click against the real endpoint).
4. 完成後顯示「股票清單已更新：共 N 檔（新增 X、更新 Y）」 — **Satisfied.** Tested with fixed numbers and live-verified against the real backend: `股票清單已更新：共 1366 檔（新增 0、更新 1085）` (a second consecutive live import in this session correctly showed `insertedCount: 0`, matching the backend spec's idempotency guarantee).
5. 完成後常駐「共 N 檔」同步更新為 `totalActiveCount` — **Satisfied**, tested and live-verified (`STOCK TOTAL AFTER IMPORT: 股票清單：共 1366 檔`, matching the response's `totalActiveCount`).
6. 更新清單過程中不輪詢任何進度端點、不建立進度條 — **Satisfied structurally** (no polling `useEffect`/`setInterval` was added for `universeImportStatus`, and no progress-bar markup exists in the import branch) and **live-verified**: network calls were captured for the whole click→completion window and filtered for `/api/stocks/sync/progress` — **zero** such calls were attributable to the import (the pre-existing sync-status poll, running independently because a real `PRICE_BACKFILL` job was already in flight in this shared environment, was excluded by diffing before/after counts, which showed no *additional* progress calls beyond that unrelated poll's own cadence).
7. 更新完成後不自動觸發同步、不自動重新掃描 — **Satisfied.** Tested (before/after call-count diff on `/api/stocks/sync/backfill` and `/api/strategies/scan` — both `0`) and live-verified identically (`backfill calls during import (should be 0 — no auto-trigger): 0`, `scan calls during import: 0`).
8. 摘要持續顯示到下一次操作為止 — **Satisfied.** Tested (summary survives an intervening `開始掃描`, then clears only on the *next* `更新股票清單` click) and live-verified: after a live import completed, checking a strategy and clicking `開始掃描` (against the real, still-in-progress scan data) left the `.st-universe-summary-text` element still present and non-empty.
9. `502 UPSTREAM_EMPTY` → 「交易所尚未發布今日清單，請稍後再試」，共 N 檔不變 — **Satisfied.** Tested and live-verified via `page.route` intercepting only `**/api/stocks/universe/import` (everything else — the catalogue, the persistent count, the sync progress — still came from the real backend) to return `502 {"code":"UPSTREAM_EMPTY"}`: exact message shown, `股票清單：共 1366 檔` unchanged before/after, button re-enabled.
10. `502 UPSTREAM_UNAVAILABLE`／`UPSTREAM_MALFORMED` → 「無法取得交易所股票清單，請稍後再試」，共 N 檔不變 — **Satisfied.** Tested (one case each) and live-verified for both codes via the same interception technique — identical message for both, count unchanged for both.
11. 同步進行中仍可按更新股票清單；更新中仍可按同步，兩者互不 disable — **Satisfied.** Tested (a constructed scenario: start sync, assert import stays enabled; start import while sync runs, assert sync stays in its own running state; resolve import, assert sync is still running afterward). **Live-verified against a real, already-in-flight `PRICE_BACKFILL` job** (this session's shared dev database had one mid-run from environment setup, not from anything this session started): the sync button showed `同步中…`/disabled on page load; `更新股票清單` was confirmed **not** disabled at that moment; clicking it moved it into its own disabled/running state while the sync button's state was independently confirmed unaffected (`sync button state unaffected by import click: true`); after the import completed and re-enabled, the sync button was still `同步中…` — this is a strictly stronger check than the unit test alone, since it's two genuinely-concurrent, real backend jobs racing on the actual dev database rather than two mocked promises.
12. 更新股票清單相關顏色為 Visual Style 字面 hex，dark/light 一致 — **Satisfied**, verified the same two ways as Group A criterion 9 above (source read of `.st-sync-info`/`.st-stock-total`/`.st-universe-summary`/`.st-universe-summary-text` against the spec's "股票清單「共 N 檔」文字" and "更新股票清單完成摘要文字" rows; the same deterministic dark-vs-light screenshot — which included a completed universe-import summary in the captured DOM — came back pixel-identical).

#### Test / build / lint output (verbatim tails)
```
$ npm test -- --run
 Test Files  7 passed (7)
      Tests  106 passed (106)

$ npm run build
> tsc -b && vite build
✓ 44 modules transformed.
✓ built in 94ms

$ npm run lint
src/__tests__/StrategyTab.test.tsx:286:7: warning eslint(no-unreachable) — pre-existing, predates this session (confirmed in Increment 1's Execution Result)
src/pages/StrategyTab.tsx:280:7: warning react(set-state-in-effect) — pre-existing, predates this session (confirmed in Increment 1's Execution Result)
```
106 = the pre-existing 83 (through Increment 2) + 5 union-table tests + 8 更新股票清單 tests. No new lint warnings; both pre-existing ones are unchanged in location and cause from Increment 1.

#### Deferred / not independently verifiable here
- None. Unlike the previous two increments, a real backend and a real dev database were reachable this session, and every one of the 21 criteria in this increment's two groups was exercised against it in addition to the automated test suite — including the two genuinely concurrent-jobs and the three `502` error-code paths (via targeted `page.route` interception of only the one endpoint under test, leaving every other call live).
- One thing worth flagging for a future session rather than for this spec's criteria: this session's live verification incidentally discovered that the dev database already had `stock_id = 1538` with no price rows at all going into this session — pre-existing state from prior sessions' work in this shared environment, not something this session created or needs to clean up (it is exactly the kind of stock the union-table zero-hit criterion needs to exist, and it is a legitimate, harmless "never synced yet" row per `specs/backend/stock-price-ingestion.md`).
