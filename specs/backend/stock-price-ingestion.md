---
status: done
title: "股票行情抓取與回補"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 取得全市場日線行情，支援每日增量、指定多檔回補、全市場回補，並具備斷點續傳；系統啟動時自動把全部在市股票的日線補齊至今日"
depends_on: []
---

# 股票行情抓取與回補 — Backend Spec

## Overview

負責把外部日線行情寫入 `stock_daily_price`，並維護 `stock` 主檔。這是整個系統唯一的資料入口；指標運算（見 `specs/backend/stock-indicator-statistics.md`）一律以本模組寫入的資料為輸入，不另行對外抓取。

提供三種抓取路徑，以及一個在系統啟動時自動觸發其中一條路徑的機制：

| 路徑 | 來源 | 請求數 | 用途 |
|---|---|---|---|
| 每日增量 | 交易所當日全市場快照 | **1 次**取得全市場 | 每個交易日收盤後例行更新 |
| 指定多檔回補 | 逐檔歷史查詢 | 每檔 1 次 | 補特定標的的歷史 |
| 全市場回補 | 逐檔歷史查詢 | 約 2200 次 | 系統初次建置 |

「指定多檔」與「全市場」是**同一條程式路徑的不同參數**，不是兩套實作——差別僅在標的清單的來源。

**啟動補齊（startup catch-up）不是第四條路徑**，而是在應用程式啟動完成時自動以 `catchUp` 參數呼叫上表的回補路徑，把每一檔在市股票從它自己的進度接續補到今日。它沒有自己的抓取邏輯、自己的速率控制或自己的進度表——完整規範見下方「啟動時自動補齊」。

## Requirements

### 資料源與其限制（設計前提）

- **每日全市場快照**：`https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL`
  單一請求回傳全體上市股票當日的代號、名稱、開高低收、成交量、成交金額、成交筆數。上櫃需另打櫃買中心對應端點。此來源**同時提供代號與名稱**，故 `stock` 主檔由此順帶維護，不需獨立資料源。

- **逐檔歷史查詢**：`https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockPrice&data_id=<代號>&start_date=<起日>&end_date=<迄日>`
  回傳單檔在區間內的每日 `open` / `max` / `min` / `close` / `Trading_Volume` / `Trading_money` / `Trading_turnover`。

- **關鍵限制（已實測確認）**：免費層**不支援一次取得全市場**——省略 `data_id` 的請求回 HTTP 400（`"Your level is free. Please update your user level."`）。全市場回補因此必然是逐檔的長時間作業，這是 `stock_sync_progress` 斷點續傳機制存在的直接原因。

- **速率控制為必要機制，不是最佳化**：逐檔回補必須有可設定的請求間隔與並行上限，並對 HTTP 429／逾時採用指數退避重試。預設值以保守為準（序列執行、每次請求間隔至少 1 秒），並可由設定調整。硬編死的無節流迴圈會在數十次請求內被來源封鎖。

### 時區

**全系統統一使用 `Asia/Taipei`**，包含資料庫容器、應用程式執行環境，以及資料庫連線本身。三者必須一致。

本模組所有的日期語意都是**台北日曆日**：資料源給的 `trade_date` 是台北交易日、回補區間的 `startDate` / `endDate` 是台北日曆日、`last_synced_date` 記的也是台北日曆日。而進度表的 `started_at` / `finished_at` / `updated_at` 是由**資料庫時鐘**寫入的（見 `specs/dba/stock-sync-progress.md`），因此資料庫時區若與應用程式時區不同，同一筆作業的「日期」和「時間戳」會來自兩個不同的時鐘。

這不只是顯示問題，有兩個實際後果：

- `GET /api/stocks/sync/progress` 的 `lastSyncedAt` 取自 `finished_at`，時區不一致時會固定偏移，讓一次剛完成的同步在畫面上看起來是幾小時前的舊資料。
- 「今日」在本地時間 00:00～08:00 之間，由應用程式算出來與由資料庫算出來會差一天。任何同時依賴兩者的判斷（例如 `endDate` 取今日、再與資料庫寫入的紀錄比對）在這段時間內會得到自相矛盾的結果。

容器端的設定見 `specs/infra/mysql.md`；既有資料列的一次性位移見 `specs/dba/stock-sync-progress.md`（V010）、`specs/dba/stock.md`（V011）、`specs/dba/stock-daily-price.md`（V012）。本 spec 負責的是應用程式端：執行環境的預設時區與資料庫連線的時區都必須明確設定為 `Asia/Taipei`，不得依賴主機的預設值——依賴預設值等於讓行為取決於部署機器的設定，在 CI 或他人機器上會得到不同結果。

### 價格處理

- 一律寫入**原始成交價（未經除權息還原）**，與 `specs/dba/stock-daily-price.md` 的約定一致。
- 資料源回傳的字串價格（含千分位符號、民國紀年）必須在寫入前正規化為數值與西元日期。
- 停牌或無交易的日期，資料源不會回傳該列——**不得補零**。零價格會使 KD 的最低價計算歸零、MACD 出現虛假的暴跌訊號。無資料即不寫入該列。

### 寫入語意

- 一律使用 UPSERT。重跑任一日或任一檔必須為冪等操作，且能修正資料源事後更正的值。
- 單檔的一次回補應在一個交易邊界內完成價格寫入與該檔進度更新，避免「資料已寫入但進度未更新」導致重跑時重複請求外部 API。

### 批次目標選擇

回補作業的標的清單由請求參數決定：

- 提供 `stockIds` 陣列 → 僅處理清單內的股票（多選）。
- 省略 `stockIds` 或傳空陣列 → 處理 `stock` 表中 `is_active = 1` 的全部股票（全跑）。

兩者共用同一套進度追蹤、速率控制與重試邏輯。

**目標清單為空是合法情形，不是錯誤。** 全市場模式在 `stock` 表尚無任何 `is_active = 1` 的股票時（例如剛建好結構、種子資料尚未匯入），解析出來的目標清單為空。此時必須以 `targetCount: 0` 正常受理並立即結束，不得拋出例外、不得讓啟動補齊失敗，也不得對外部資料源發出任何請求。

這一點要特別寫明，是因為空清單很容易在「以清單為條件的查詢」上炸掉——例如把空集合展開成 `IN` 而產生語法不完整的 SQL。凡是以目標清單為輸入的查詢，都必須在清單為空時直接略過查詢並回傳空結果，而不是把空集合交給資料庫。

### 斷點續傳

- 批次啟動時，為目標標的在 `stock_sync_progress` 以 `job_type = 'PRICE_BACKFILL'` UPSERT 建立 `PENDING` 列。
- 逐檔處理，狀態依 `specs/dba/stock-sync-progress.md` 的進度語意流轉。
- 續傳：重新呼叫回補端點並帶 `resume = true` 時，只取 `status IN ('PENDING','FAILED')` 且 `attempt_count` 未達上限的標的，並從各檔的 `last_synced_date` 之後接續。
- 目標區間內查無任何交易資料的標的標記為 `SKIPPED`，不再重試。

### 啟動時自動補齊

系統啟動完成後，後端自動把 `stock` 表中 `is_active = 1` 的全部股票的日線補齊至**今日**，不需要任何人手動打端點。這讓一個剛 `/reset-env` 過、只有股票主檔而沒有任何行情的資料庫，在開機後自行變成可用狀態。

行為規範：

- **重用既有回補路徑，不另寫一套。** 啟動補齊等同於以 `stockIds` 省略（全市場）、`startDate` 取設定值、`endDate` 取今日、`catchUp: true` 呼叫回補服務。速率控制、重試退避、進度追蹤、UPSERT 冪等性全部沿用，不得複製一份平行實作。
- **絕不阻塞啟動。** 補齊在背景非同步執行，應用程式必須在補齊開始後立刻進入可服務狀態。全市場約 2200 檔在預設每檔間隔 1 秒下需時超過半小時；若同步等待，服務等同於半小時不可用。
- **絕不因抓取失敗而讓啟動失敗。** 外部資料源逾時、429、DNS 失敗、回傳格式改變，一律記錄錯誤並讓該檔進入既有的 `FAILED` 重試流程；應用程式本身必須正常啟動。開發機在離線狀態下仍須能開起來。
- **可停用。** 由設定開關控制，預設啟用。測試與 CI 必須能關掉它——測試啟動時打真實外部 API 會使測試結果取決於外部服務的可用性，且在數十次請求內被來源封鎖。
- **重啟安全。** 開發過程中重啟極為頻繁，因此重啟不得重抓已有的資料。`catchUp` 的跳過條件即是這件事的保證：已補到今日的檔在下次啟動時完全不發出外部請求。

設定項（實際鍵名與檔案格式由 `env.md` 的技術棧決定，此處只規範語意與預設值）：

| 設定 | 預設 | 說明 |
|---|---|---|
| 啟動補齊開關 | 啟用 | 關閉時完全不觸發，啟動流程不受影響 |
| 補齊起日 | `2026-01-01` | 首次補齊的最早日期；已有進度的檔以其 `last_synced_date` 之後接續，不受此值影響 |

**關於預設起日 `2026-01-01` 的一個已知取捨**：MACD 與 KD 需要約 250 個交易日暖身（見「回補」的 `startDate` 說明與指標 spec），而 `2026-01-01` 至今日不足該長度。因此在只補這段區間的情況下，指標序列開頭會有相當比例的列被標記 `is_warmup = 1` 而不對外呈現，日 K 圖上的指標可用區間會晚於行情可用區間。這是刻意接受的：先讓系統有真實行情可看，需要完整指標時把補齊起日往前調即可，不需要改任何程式。

## Implementation Details

### API 契約

#### 1. 每日增量抓取

```
POST /api/stocks/sync/daily
```

Request：
```json
{ "tradeDate": "2026-08-27" }
```
`tradeDate` 可省略，省略時取資料源當日快照。

Response `200`：
```json
{
  "tradeDate": "2026-08-27",
  "stockCount": 1378,
  "insertedCount": 12,
  "updatedCount": 1366,
  "stockMasterUpserted": 1378
}
```

#### 2. 回補（多選 / 全跑共用）

```
POST /api/stocks/sync/backfill
```

Request：
```json
{
  "stockIds": ["2330", "2317"],
  "startDate": "2025-09-01",
  "endDate": "2026-08-27",
  "resume": false,
  "catchUp": false
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockIds` | string[] | 否 | 指定標的；**省略或空陣列代表全市場**（`stock.is_active = 1`） |
| `startDate` | date | 是 | 回補起日。須早於實際需要的統計起日至少 250 個交易日（暖身需求，見指標 spec） |
| `endDate` | date | 是 | 回補迄日 |
| `resume` | boolean | 否 | 預設 `false`（重置進度重跑）；`true` 表示只處理未完成與失敗的標的 |
| `catchUp` | boolean | 否 | 預設 `false`。`true` 表示「補到 `endDate` 為止」語意：逐檔以 `last_synced_date` 之後接續，已補到 `endDate` 的檔完全跳過、不發外部請求。詳見下方「`catchUp` 的語意」 |

`resume` 與 `catchUp` 不可同時為 `true` → `400`，`{"code":"INVALID_SYNC_MODE"}`。兩者都在描述「不要重跑已完成的部分」，但判斷依據不同（`resume` 看 `status`，`catchUp` 看 `last_synced_date`），同時給定會產生無法一眼判讀的組合語意。

Response `202`（非同步作業，立即回應）：
```json
{
  "jobType": "PRICE_BACKFILL",
  "targetCount": 2200,
  "caughtUpCount": 2200,
  "startDate": "2025-09-01",
  "endDate": "2026-08-27",
  "mode": "ALL"
}
```
`mode` 為 `SELECTED` 或 `ALL`，依 `stockIds` 是否提供而定。

`caughtUpCount` 是本次目標中**已補齊至 `endDate`、因此不會發出任何外部請求**的檔數，於受理當下即可算出（判斷依據見下方「`catchUp` 的語意」的跳過條件），故隨 `202` 一併回傳。`catchUp` 為 `false` 時一律為 `0`。

它存在的理由是「什麼都沒做」與「做完了」在進度表上長得一模一樣：被 `catchUp` 跳過的標的其進度列維持原狀（`status` 仍是 `DONE`、`finished_at` 仍是上次的時間），所以事後查進度只看得到「34 檔 DONE」，無從分辨這是本次補出來的、還是本來就已經補好的。`caughtUpCount == targetCount` 即表示這次同步完全沒有向外部要任何資料，呼叫端（見 `specs/frontend/strategy.md` 的「同步列」）必須據此如實呈現，不得顯示成剛完成了 `targetCount` 檔的同步。

驗證與錯誤：
- `startDate` 晚於 `endDate` → `400`，`{"code":"INVALID_DATE_RANGE"}`
- `stockIds` 含 `stock` 表中不存在的代號 → `400`，`{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}`
- 同一 `jobType` 已有作業執行中 → `409`，`{"code":"JOB_ALREADY_RUNNING"}`

#### 3. 進度查詢

```
GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL
```

Response `200`：
```json
{
  "jobType": "PRICE_BACKFILL",
  "total": 2200,
  "pending": 130,
  "running": 1,
  "done": 2050,
  "failed": 15,
  "skipped": 4,
  "lastSyncedAt": "2026-08-30T00:07:12",
  "failedItems": [
    { "stockId": "1234", "attemptCount": 3, "lastError": "HTTP 429 rate limited" }
  ]
}
```
`failedItems` 最多回傳 50 筆。

`lastSyncedAt` 是該 `jobType` **最近一次成功完成的時間**，取自進度紀錄中狀態為 `DONE` 的列的完成時間最大值；從未成功過時為 `null`。它回答的是使用者在畫面上問的「資料到底多新」，因此語意是「最後一次真的補完是什麼時候」，不是「最後一次按下按鈕是什麼時候」——一次全部失敗的同步不應該讓畫面上的時間往前跳。

### 處理流程

**每日增量**：取得全市場快照 → 正規化每列（代號、名稱、日期、價格）→ UPSERT `stock` 主檔 → UPSERT `stock_daily_price` → 回傳統計。單一請求即涵蓋全市場，不需進度追蹤。

**回補**：解析標的清單（多選或全市場）→ 初始化 `stock_sync_progress` → 逐檔依速率限制請求歷史 → 正規化並 UPSERT → 更新該檔進度 → 全部完成後結束。任一檔失敗只影響該檔進度，不中止整批。

#### `catchUp` 的語意

`catchUp: true` 時，每一檔目標股票依其 `stock_sync_progress` 中 `job_type = 'PRICE_BACKFILL'` 的列，各自決定要不要抓、從哪天抓：

| 該檔的 `last_synced_date` | 行為 |
|---|---|
| 無進度列，或為 `NULL` | 建立／重設為 `PENDING`，自請求的 `startDate` 抓到 `endDate` |
| 早於 `endDate` | 重設為 `PENDING`，自 `last_synced_date` 的**次日**抓到 `endDate`（不重抓已有區間） |
| 等於或晚於 `endDate` | **完全跳過，不發出任何外部請求**，進度列維持原狀 |

與 `resume` 的差別在判斷依據：`resume` 只挑 `status` 為 `PENDING`／`FAILED` 的檔，因此一檔已經 `DONE` 但只補到上週的股票會被它略過，永遠追不上今日；`catchUp` 看的是 `last_synced_date` 與 `endDate` 的差距，所以 `DONE` 但落後的檔會被重新開啟並只補上落後的那一段。啟動補齊要的正是後者。

`attempt_count` 在 `catchUp` 重新開啟一檔時歸零——落後是因為時間前進，不是因為先前失敗，不應沿用舊的重試次數而提早撞上重試上限。

#### `last_synced_date` 的認定

一檔成功處理完一個區間後，`last_synced_date` 記為該次請求的 **`endDate`**，而不是實際寫入的最後一列的日期。兩者在 `endDate` 為交易日時相同，但在 `endDate` 落在週末、假日或收盤前時不同：此時資料源不回傳該日（依「不得補零」規定也不寫入任何列），若把 `last_synced_date` 記為最後一筆實際資料的日期，則每次啟動都會把「最後一個交易日的次日 ~ 今日」這段空區間重抓一次，週末重啟等於固定重複請求外部 API。記為 `endDate` 表示「這個區間已經處理過了」，才能讓 `catchUp` 的跳過條件真正生效。

**啟動補齊**：應用程式啟動完成 → 讀取設定，未啟用則結束 → 以全市場、設定起日、今日、`catchUp: true` 呼叫回補服務 → 立即返回，其餘在背景依既有回補流程進行。啟動補齊不經過 HTTP 端點，直接呼叫回補服務，因此不產生 `202` 回應；但它與手動回補共用同一個 `jobType` 併發鎖，啟動補齊執行期間手動觸發回補會得到 `409 JOB_ALREADY_RUNNING`。

## Acceptance Criteria
- [x] `POST /api/stocks/sync/daily` 以單一外部請求取得全市場當日行情，並同時寫入 `stock` 與 `stock_daily_price`
- [x] `POST /api/stocks/sync/backfill` 帶 `stockIds: ["2330","2317"]` 時只處理該 2 檔，回應 `mode` 為 `SELECTED`
- [x] 同一端點省略 `stockIds` 時處理 `is_active = 1` 的全部股票，回應 `mode` 為 `ALL`
- [x] 回補過程對外部資料源的請求有間隔控制，且 HTTP 429 觸發指數退避重試而非立即失敗
- [x] 中途強制中斷後，以 `resume: true` 重新呼叫只處理未完成與失敗的標的，已完成標的不再發出外部請求
- [x] 對同一檔同一區間連續執行兩次回補，`stock_daily_price` 的列數不變（冪等）
- [x] 資料源未回傳的日期（停牌日）在 `stock_daily_price` 中不存在對應列，且**不存在任何價格為 0 的列**
- [x] 資料源回傳的民國日期與含千分位的價格字串被正確正規化（以 2330 某月資料驗證）
- [x] `GET /api/stocks/sync/progress` 回傳的各狀態計數總和等於 `total`
- [x] 系統啟動完成後，未經任何手動呼叫，`stock` 中 `is_active = 1` 的股票在 `stock_daily_price` 出現自設定起日（預設 `2026-01-01`）至今日的真實日線資料
- [x] 啟動補齊在背景執行：應用程式在補齊尚未跑完時即可正常回應 `GET /api/stocks`，不因補齊而延後可服務時間
- [x] 外部資料源不可用（離線或逾時）時應用程式仍正常啟動，失敗的標的落在 `FAILED` 而非讓啟動流程中斷
- [x] 關閉啟動補齊設定後重啟，完全不發出任何外部請求，`stock_daily_price` 列數不變
- [x] 補齊完成後重啟一次，已補到今日的標的不再發出外部請求，且 `stock_daily_price` 列數不變（重啟冪等）
- [x] `catchUp: true` 對一檔 `status = 'DONE'` 但 `last_synced_date` 早於 `endDate` 的股票，會重新開啟該檔並只請求 `last_synced_date` 次日之後的區間，不重抓既有區間
- [x] `catchUp: true` 對一檔 `last_synced_date` 已等於 `endDate` 的股票完全不發出外部請求
- [x] `endDate` 為週末或假日時，該次處理後 `last_synced_date` 記為該 `endDate` 本身；緊接著再以相同 `endDate` 執行一次 `catchUp`，不發出任何外部請求
- [x] `resume` 與 `catchUp` 同時為 `true` 時回應 `400`，`{"code":"INVALID_SYNC_MODE"}`
- [x] 啟動補齊執行期間呼叫 `POST /api/stocks/sync/backfill` 回應 `409`，`{"code":"JOB_ALREADY_RUNNING"}`
      （未驗證：併發鎖本身已由既有的 `JobRunningRegistry` 與其既有測試涵蓋，但「啟動補齊執行中」這個特定時間窗未實測——補齊完成後的重啟會在數秒內跳過全部標的，沒有足以發出第二個請求的窗口。待下次從空資料庫啟動時補驗。）
- [x] `GET /api/stocks/sync/progress` 回應含 `lastSyncedAt`，其值等於該 `jobType` 中 `DONE` 列的完成時間最大值
- [x] 從未成功同步過時 `lastSyncedAt` 為 `null`；一次全部失敗的同步不會更新該值
- [x] 啟動補齊寫入的資料中不存在任何價格為 0 的列，停牌／非交易日不產生列

---

- [x] 應用程式執行環境與資料庫連線的時區皆明確設定為 `Asia/Taipei`，不依賴主機預設值
- [x] 資料庫容器改為 `Asia/Taipei` 後，新寫入的進度列其 `finished_at` 與當下本地時間一致（誤差在一分鐘內）
- [x] `GET /api/stocks/sync/progress` 的 `lastSyncedAt` 與該次同步實際完成的本地時間一致，不再有 8 小時偏移
- [x] `POST /api/stocks/sync/backfill` 的 `202` 回應含 `caughtUpCount`
- [x] 全部標的都已補齊至 `endDate` 時，`caughtUpCount` 等於 `targetCount`，且該次作業對外部資料源的請求數為 0
- [x] 部分標的落後時，`caughtUpCount` 等於已補齊的檔數，落後的檔仍照常補齊
- [x] `catchUp` 為 `false` 時 `caughtUpCount` 一律為 `0`
- [x] `stock` 表中沒有任何 `is_active = 1` 的股票時，全市場回補以 `targetCount: 0` 正常受理並結束，不拋出例外、不發出任何外部請求
- [x] 同上情境下應用程式啟動補齊不產生任何錯誤紀錄，啟動流程正常完成

## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/pom.xml` — added `mybatis-spring-boot-starter`, `mysql-connector-java`, `spring-boot-starter-validation`
  - `develop/backend/src/main/resources/application.yml` — MyBatis config, `app.external.*` (TWSE/FinMind URLs, timeouts), `app.backfill.*` (executor pool size, rate-limit interval/retries/backoff, max attempt count)
  - `develop/backend/src/main/java/com/stock/domain/{Stock,StockDailyPrice,StockSyncProgress,StatusCount}.java`
  - `develop/backend/src/main/java/com/stock/mapper/{StockMapper,StockDailyPriceMapper,StockSyncProgressMapper}.java` + matching XML under `develop/backend/src/main/resources/mapper/`
  - `develop/backend/src/main/java/com/stock/util/NormalizeUtil.java` — ROC/ISO date parsing, thousands-separator/placeholder-aware price parsing
  - `develop/backend/src/main/java/com/stock/service/external/{TwseClient,FinMindClient,RateLimitedException,ExternalApiException}.java` + `dto/{TwseDailyRow,TwseSnapshotRow,TwseSnapshotResult,FinMindRow,FinMindResponse,NormalizedPriceRow}.java`
  - `develop/backend/src/main/java/com/stock/service/{PriceIngestionService,BackfillRunner,StockSyncService,JobRunningRegistry}.java`
  - `develop/backend/src/main/java/com/stock/controller/StockSyncController.java`
  - `develop/backend/src/main/java/com/stock/dto/{DailySyncRequest,DailySyncResponse,BackfillRequest,BackfillResponse,ProgressResponse,FailedItemDto,ErrorResponse}.java`
  - `develop/backend/src/main/java/com/stock/exception/{InvalidDateRangeException,UnknownStockIdException,JobAlreadyRunningException,GlobalExceptionHandler}.java`
  - `develop/backend/src/main/java/com/stock/config/{RestTemplateConfig,AsyncConfig,BackfillProperties}.java`
  - `develop/backend/src/test/java/com/stock/util/NormalizeUtilTest.java`
  - `develop/backend/src/test/java/com/stock/StockPriceIngestionIntegrationTest.java`
  - `develop/backend/src/test/resources/application.yml` — test overrides (fast rate-limit timings for the same live DB)
- Notes:
  - Daily path (`TwseClient`) hits TWSE openapi `STOCK_DAY_ALL` once and derives both the `stock` master upsert and `stock_daily_price` upsert from that single response; no-trade rows (empty price fields) are dropped rather than zero-filled.
  - Backfill path shares one code path for `SELECTED`/`ALL` mode, driven only by whether `stockIds` is provided; both dedupe with order preserved and validate unknown ids against the live `stock` table before starting.
  - Rate limiting/retry: `BackfillRunner` runs on a dedicated single-thread `@Async` executor (`app.backfill.executor-pool-size`, default 1), sleeps `app.backfill.rate-limit.interval-ms` (default 1000ms) between stocks, and retries HTTP 429 / timeouts from FinMind with exponential backoff (`initial-backoff-ms` × `backoff-multiplier`, capped at `max-retries`) before marking the stock `FAILED`.
  - Resume semantics: `resume=false` resets all target rows to `PENDING` (`attempt_count=0`, `last_synced_date=NULL`); `resume=true` only inserts progress rows for stocks that don't have one yet, leaves existing rows untouched, and only re-processes `PENDING`/`FAILED` rows under the attempt cap, fetching from `last_synced_date + 1`. A single crash boundary (`PriceIngestionService.applyBackfillResult`, `@Transactional`) writes all of a stock's price rows and marks it `DONE` together.
  - `JobRunningRegistry` (in-memory, per-`jobType` flag) guards the 409 `JOB_ALREADY_RUNNING` case; set synchronously before the 202 response is returned and cleared in the async runner's `finally` after the whole batch completes.
  - Verification performed:
    - `mvn -f develop/backend/pom.xml compile` — clean.
    - `mvn -f develop/backend/pom.xml test` — 19/19 passing, run 3× to confirm no flakiness (10 `NormalizeUtilTest` unit tests + 8 `StockPriceIngestionIntegrationTest` integration tests against the live MySQL DB with `MockRestServiceServer`-mocked TWSE/FinMind responses, covering: daily upsert + ROC-date/comma-price normalization + idempotency, selected-mode isolation from an untouched decoy stock, 429→200 retry sequence verified via `mockServer.verify()`, resume skipping an already-`DONE` stock with a verified zero-request assertion, `ALL` mode excluding inactive stocks, `UNKNOWN_STOCK_ID`/`INVALID_DATE_RANGE`/`JOB_ALREADY_RUNNING` error responses, and progress count-sum-equals-total including a `SKIPPED` case).
    - Live smoke test: ran `mvn spring-boot:run` on port 8080 (stopped afterward — port confirmed free), then against the **real** TWSE/FinMind APIs and the real DB:
      - `POST /api/stocks/sync/daily` → `{"tradeDate":"2026-08-27","stockCount":1365,"insertedCount":1365,"updatedCount":0,"stockMasterUpserted":1365}`; verified `stock` (1365 rows) and `stock_daily_price` (1365 rows) both populated in one call; `2330` stored as `台積電`/`TSE`/active with close `2410.00`, matching TWSE's raw `"2,410.00"`/ROC `"1150827"` after normalization; DB-wide zero-price-row count = 0.
      - `POST /api/stocks/sync/backfill` with `stockIds:["2330"]`, `2026-08-01..2026-08-27` → `mode:"SELECTED"`, 19 rows written (one per real trading day in range, no zero/placeholder rows for weekends/holidays), `last_synced_date=2026-08-27`, `status=DONE`.
      - Re-ran the identical backfill request → row count for `2330` in that range stayed at 19 (idempotent) against real FinMind data.
      - `stockIds:["9999NOPE"]` → `400 {"code":"UNKNOWN_STOCK_ID","unknownIds":["9999NOPE"]}`; `startDate` after `endDate` → `400 {"code":"INVALID_DATE_RANGE"}`; firing the same backfill request twice back-to-back → second call `409 {"code":"JOB_ALREADY_RUNNING"}`.
      - `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` → `{"total":1,"pending":0,"running":0,"done":1,"failed":0,"skipped":0,"failedItems":[]}`, counts sum to total.
  - No blockers. Every acceptance criterion above was verified both by an isolated integration test and, where practical, by a live run against the real external APIs and the real database.

### Increment 2 — 2026-08-29
- Status: DONE (pending checkbox sign-off by the requester)
- Files changed:
  - `develop/backend/src/main/java/com/stock/dto/BackfillRequest.java` — added `catchUp` (default `false`) with getter/setter
  - `develop/backend/src/main/java/com/stock/exception/InvalidSyncModeException.java` — new; `resume && catchUp` both true
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — maps it to `400 {"code":"INVALID_SYNC_MODE"}`
  - `develop/backend/src/main/java/com/stock/mapper/StockSyncProgressMapper.java` + `develop/backend/src/main/resources/mapper/StockSyncProgressMapper.xml` — added `findCaughtUpStockIds` (ids already synced through/past a given `endDate`), `upsertPendingForCatchUp` (reopens PENDING with `target_start_date = last_synced_date + 1 day`, or the requested `startDate` for a brand-new/never-synced row, via `COALESCE(DATE_ADD(last_synced_date, INTERVAL 1 DAY), VALUES(target_start_date))`; resets `attempt_count = 0`), and `markSkippedThrough` (marks SKIPPED while also recording `last_synced_date`, kept as a separate statement from the pre-existing `markSkipped` — which `IndicatorRebuildRunner` still uses unchanged — since MyBatis mapper interfaces don't support overloaded method names)
  - `develop/backend/src/main/java/com/stock/service/StockSyncService.java` — `startBackfill` now validates `resume && catchUp` first; when `catchUp` is true it resolves the per-stock caught-up/lagging/new split via `findCaughtUpStockIds` and calls `upsertPendingForCatchUp` only for the non-caught-up remainder (skipped stocks' progress rows are never touched and no request is issued for them)
  - `develop/backend/src/main/java/com/stock/service/BackfillRunner.java` and `develop/backend/src/main/java/com/stock/service/PriceIngestionService.java` — **`last_synced_date` semantics fix (applies to all backfill modes, not just catchUp)**: `applyBackfillResult` and the empty-rows branch now record `last_synced_date` as the requested range's `endDate` (`fetchEnd`, i.e. `progress.getTargetEndDate()`), never the trade date of the last row actually written or left as unset — this is what makes a range landing entirely on a weekend/holiday register as "already processed through `endDate`" instead of being re-requested by every subsequent catch-up
  - `develop/backend/src/main/java/com/stock/config/BackfillProperties.java` — new nested `StartupCatchUp` (`enabled`, default `true`; `startDate`, default `2026-01-01`) under `app.backfill.startup-catch-up.*`
  - `develop/backend/src/main/java/com/stock/service/StartupCatchUpRunner.java` — new; `@EventListener(ApplicationReadyEvent.class)` + `@Async("backfillExecutor")` (the same executor bean `BackfillRunner` already uses — no parallel executor/rate-limit/progress implementation), builds a `BackfillRequest` (`stockIds` omitted, `startDate` = configured, `endDate` = today, `catchUp = true`) and calls `StockSyncService.startBackfill` directly (no HTTP hop); wraps the call in try/catch so any failure (offline source, `JOB_ALREADY_RUNNING`, DB issue, etc.) is only logged, never propagated — application startup itself never fails or blocks on this
  - `develop/backend/src/main/resources/application.yml` — added `app.backfill.startup-catch-up.enabled: true` / `start-date: 2026-01-01`
  - `develop/backend/src/test/resources/application.yml` — added `app.backfill.startup-catch-up.enabled: false` (test profile must never call the real external APIs on boot)
  - `develop/backend/src/test/java/com/stock/StockPriceIngestionIntegrationTest.java` — added `seedProgress` helper and four new tests (see Notes); also fixed a pre-existing, unrelated failure in `backfill_allMode_targetsActiveStocksOnly` (see Notes)
- Notes:
  - **catchUp per-stock decision**: implemented exactly per the spec's table — no progress row/NULL `last_synced_date` → PENDING from `startDate`; `last_synced_date < endDate` → PENDING from `last_synced_date + 1 day`, `attempt_count` reset to 0; `last_synced_date >= endDate` → left completely untouched, no row read/write beyond the initial `findCaughtUpStockIds` SELECT, and `backfillRunner.run` is invoked with that stock excluded from the list, so `BackfillRunner`'s loop never reaches it and issues zero HTTP calls.
  - **`resume` vs `catchUp` are otherwise unchanged**: `resume`'s existing `upsertPendingIfAbsent` / `findProcessableStockIds` (status-based) path was not touched; `catchUp` is a fully separate branch in `StockSyncService.startBackfill` sharing only `backfillRunner.run` and the mutual-exclusion validation.
  - **Startup catch-up design choice**: used `@Async` directly on the `@EventListener(ApplicationReadyEvent.class)` method (rather than relying on `ApplicationReadyEvent` firing after Tomcat is already listening) so that literally zero work — not even the "resolve active stock ids" query — runs on the thread that completes application startup; this was judged simpler and more bulletproof than adding a second hand-off layer.
  - **Pre-existing test fixed as a prerequisite**: `backfill_allMode_targetsActiveStocksOnly` (from Increment 1) started failing before any of this increment's code changed, once `specs/dba/stock-seed-data.md`'s 34 real seed stocks landed in the live DB — "ALL mode" now also targets those real, unmocked stocks, and the test's `mockServer` only ever expected the one `T221` fixture. Root-caused and fixed by having the test deactivate every non-`T%` stock for its duration (restored in a `finally` block) rather than pinning it to today's specific 34 ids, so it stays correct regardless of future seed-data growth. This was a pre-existing breakage unrelated to the catchUp/startup-catch-up work, not something this increment introduced.
  - **New tests** (all in `StockPriceIngestionIntegrationTest`, live MySQL DB + `MockRestServiceServer`-mocked FinMind, cleaned up via the existing `T2%` teardown pattern):
    - `catchUp_reopensLaggingDoneStock_fetchesOnlyGapAfterLastSyncedDate_andRecordsEndDateAsLastSynced` — seeds a `DONE` progress row with `last_synced_date=2025-09-05`, `attempt_count=2`, a stale `last_error`; fires `catchUp=true` with `endDate=2025-09-10`; asserts the *only* registered mock expectation is for `start_date=2025-09-06` (not the full requested `2025-09-01`), and that afterwards `last_synced_date=2025-09-10` (the requested `endDate`, not `2025-09-08` — the one row actually returned), `attempt_count=0`, `last_error=null`.
    - `catchUp_stockAlreadySyncedThroughEndDate_makesNoExternalRequest_andLeavesProgressUntouched` — seeds `last_synced_date == endDate`; registers **zero** `mockServer.expect(...)` calls at all, so any external request would fail the test outright; asserts the progress row (`status`, `last_synced_date`, `attempt_count`) is byte-for-byte unchanged afterward.
    - `catchUp_endDateOnWeekend_recordsLastSyncedDateAsEndDate_andRepeatCatchUpMakesNoRequest` — first catchUp call's range returns an empty FinMind `data` array (weekend); asserts status becomes `SKIPPED` with `last_synced_date` set to the weekend `endDate` itself (not left `NULL`); a second, identical catchUp call registers no mock expectations and is asserted to make zero requests while the row stays exactly as it was.
    - `backfill_resumeAndCatchUpBothTrue_returns400InvalidSyncMode` — asserts `400 {"code":"INVALID_SYNC_MODE"}`.
  - Verification performed:
    - `mvn -f develop/backend/pom.xml compile` — clean.
    - `mvn -f develop/backend/pom.xml test` — **74/74 passing** (70 pre-existing + 4 new), run 3× consecutively with no flakiness.
    - Confirmed via direct `mysql` CLI query after the suite: `stock`/`stock_sync_progress`/`stock_daily_price` all have 0 rows matching `T1%`/`T2%` (full test-data cleanup), 0 rows anywhere in `stock_daily_price` with a zero-valued price column, and `stock` is back to `34 total / 34 active` (the `backfill_allMode` fix's activate/deactivate dance left no residue).
    - Confirmed via test log output that `StartupCatchUpRunner` fires on every test-suite boot but immediately no-ops: `Startup price catch-up disabled (app.backfill.startup-catch-up.enabled=false); skipping.` — proving the test profile's kill switch actually reaches the bean and that no real TWSE/FinMind call is attempted during tests.
    - Did **not** perform a live run against the real external APIs (explicitly out of scope per instructions); all new behavior is covered by `MockRestServiceServer`-backed integration tests against the live MySQL DB.
  - Not implemented (explicitly out of scope for this increment, belongs to `specs/backend/stock-indicator-statistics.md`): any chaining from price catch-up completion into an indicator rebuild.

### Increment 3 — 2026-08-30
- Status: DONE (pending checkbox sign-off by the requester)
- Files changed:
  - `develop/backend/src/main/java/com/stock/mapper/StockSyncProgressMapper.java` — added `findLastSyncedAt(jobType)` returning `LocalDateTime`
  - `develop/backend/src/main/resources/mapper/StockSyncProgressMapper.xml` — `findLastSyncedAt`: `SELECT MAX(finished_at) FROM stock_sync_progress WHERE job_type = ? AND status = 'DONE'` (uses the existing `idx_job_status (job_type, status)` index; returns SQL `NULL` → mapped to a `null` `LocalDateTime` when no row has ever completed)
  - `develop/backend/src/main/java/com/stock/dto/ProgressResponse.java` — added nullable `lastSyncedAt` (`LocalDateTime`) field, constructor parameter, and getter
  - `develop/backend/src/main/java/com/stock/service/StockSyncService.java` — `getProgress` now calls `progressMapper.findLastSyncedAt(jobType)` and passes it into the `ProgressResponse`
  - `develop/backend/src/test/java/com/stock/StockPriceIngestionIntegrationTest.java` — added three tests (see Notes)
- Notes:
  - **Semantics implemented exactly per spec**: `lastSyncedAt` = `MAX(finished_at)` among rows with `status = 'DONE'` for the given `jobType`, `null` when no row has ever reached `DONE`. `markFailed`/`markSkipped` also set `finished_at`, but since the query filters on `status = 'DONE'`, a FAILED or SKIPPED completion never contributes — a wholly-failed sync run cannot advance the value, and a stock later reopened by `catchUp` (status flips back to `PENDING`, `finished_at` from its prior `DONE` run left in place) is likewise excluded until it reaches `DONE` again, matching "最後一次真的補完是什麼時候" rather than "最後一次按下按鈕".
  - **New tests** (all in `StockPriceIngestionIntegrationTest`, live MySQL DB):
    - `progress_lastSyncedAt_equalsMaxFinishedAtAmongDoneRows` — seeds one `DONE` row with `finished_at` 50 years in the future (guaranteed max regardless of the real 34 pre-existing `DONE` rows), independently computes `MAX(finished_at)` via a raw `jdbc` query, and asserts the HTTP `GET /api/stocks/sync/progress` response's `lastSyncedAt` equals that independently-computed value — proving the wiring end-to-end through JSON (de)serialization, not just the mapper in isolation.
    - `progress_lastSyncedAt_isNull_whenJobTypeHasNeverCompletedARun` — since the live DB always carries real `DONE` rows for both existing `job_type`s (34 each, per the seed data this task must preserve), this test is annotated `@Transactional` so Spring's test-transaction rollback undoes its changes automatically at test end: within the transaction it flips every real `PRICE_BACKFILL` `DONE` row to `FAILED` with `finished_at = NULL`, calls `stockSyncService.getProgress("PRICE_BACKFILL")` **in-process on the same thread/connection** (an HTTP call via `TestRestTemplate` would run on a different connection under MySQL's default isolation and would not see the uncommitted change), and asserts `getLastSyncedAt()` is `null`. Nothing persists past the test — verified by direct `mysql` CLI counts after the full suite run (see below).
    - `progress_lastSyncedAt_unchanged_whenBackfillRunFailsCompletely` — captures `lastSyncedAt` before, runs a real backfill for a new `T244` stock that receives HTTP 429 on all 4 attempts (`max-retries: 3` in the test profile ⇒ 1 initial + 3 retries) so it ends `FAILED` without ever reaching `DONE`, then asserts `lastSyncedAt` afterward is byte-for-byte the same value as before — proving a fully-failed run never moves the displayed time forward, using the ordinary live-HTTP/live-DB test style (no transaction trick needed here since no real `DONE` row is touched).
  - Verification performed:
    - `mvn -f develop/backend/pom.xml compile` and `test-compile` — clean.
    - `mvn -f develop/backend/pom.xml test` — **87/87 passing** (84 pre-existing + 3 new), run twice consecutively with no flakiness.
    - Direct `mysql` CLI check before and after the full suite run: `stock` = 34, `stock_daily_price` = 5372, `stock_daily_indicator` = 5372, `stock_sync_progress` DONE counts = 34 `PRICE_BACKFILL` / 34 `INDICATOR_REBUILD` — all **identical** before and after; zero leftover rows anywhere matching `stock_id LIKE 'T%'` after the run, confirming the `@Transactional` rollback left no residue and the ordinary `T2%` teardown cleaned up the other two new tests' rows.
  - No blockers. The two in-scope Acceptance Criteria items are covered by both a direct mapper/SQL-level assertion and a full HTTP round-trip test; no schema change was made (reused the existing `finished_at`/`status` columns and `idx_job_status` index exactly as instructed).

### Increment 4 — 2026-08-31
- Status: DONE (pending checkbox sign-off by the requester)
- Scope: the single remaining unchecked Acceptance Criterion — 啟動補齊執行期間呼叫 `POST /api/stocks/sync/backfill` 回應 `409 JOB_ALREADY_RUNNING`.
- **Finding: this was a real, provable gap, not just an untested-but-correct path.** `StartupCatchUpRunner.onApplicationReady()` was annotated `@Async("backfillExecutor")`, so the *entire method body* — including the call chain that eventually reached `JobRunningRegistry.tryStart("PRICE_BACKFILL")` — only ran once Spring's async executor actually picked up the scheduled task, not synchronously as part of handling `ApplicationReadyEvent`. Since the embedded server is already accepting HTTP connections by the time `ApplicationReadyEvent` fires, there was a genuine (if narrow) window — from "app ready to serve traffic" to "the async catch-up task actually starts running on the backfill executor" — during which `jobRunningRegistry.isRunning("PRICE_BACKFILL")` was still `false`. A manual `POST /api/stocks/sync/backfill` arriving in that window would have wrongly received `202`, not `409`, violating the spec's stated guarantee (line 209: 啟動補齊執行期間手動觸發回補會得到 409).
- Files changed:
  - `develop/backend/src/main/java/com/stock/service/StockSyncService.java` — refactored `startBackfillInternal` into three pieces without changing its externally-observable behavior: `prepare(request)` (validation + target-id resolution, no lock touched — unchanged logic, just extracted), `runBackfill(request, jobType, prepared)` (progress-row resolution + `backfillRunner.run(...)` dispatch + response building — unchanged logic, just extracted), and a small private `Prepared` holder (mode + targetIds). Added a new public method `startBackfillWithLockAlreadyHeld(BackfillRequest)`: identical to the manual path except it never calls `jobRunningRegistry.tryStart` itself — it assumes the caller already acquired the lock synchronously — and still calls `jobRunningRegistry.finish(jobType)` in its own `catch (RuntimeException e)` if validation or scheduling fails before the batch is actually handed to `BackfillRunner`, so the lock is never leaked regardless of which of the two entry points is used. `startBackfill`/`startBackfillTrackingCompletion` (the manual/controller path) are otherwise unchanged, including the pre-existing ordering where validation happens *before* the lock check.
  - `develop/backend/src/main/java/com/stock/service/StartupCatchUpRunner.java` — removed `@Async` from `onApplicationReady()`. The method now: checks the enabled flag (unchanged) → **synchronously** calls `jobRunningRegistry.tryStart("PRICE_BACKFILL")` as the very first side-effecting step, logging and returning if it's already held (defensive; not expected to actually happen this early) → builds the `BackfillRequest` and calls the new `stockSyncService.startBackfillWithLockAlreadyHeld(request)` (replacing the old `startBackfillTrackingCompletion` call, which would have re-acquired the lock redundantly) → chains the indicator-rebuild trigger onto the batch's completion future exactly as before. The lock is now held before this method returns, full stop — no async indirection sits between "application ready" and "lock acquired". The rate-limited per-stock loop itself is untouched and still runs off-thread: `runBackfill`'s call to `backfillRunner.run(...)` is a genuine cross-bean call into a different `@Async` bean (`BackfillRunner`), so it dispatches to the backfill executor and returns immediately regardless of whether the caller's own method is `@Async`. Only a few quick DB queries (target-id resolution, progress upserts) now execute synchronously on the thread handling `ApplicationReadyEvent`, in exchange for closing the race completely; this is judged an acceptable, minor trade-off against Increment 2's original "zero work on the startup-completing thread" design goal, since the previous design left the functional 409 guarantee unverifiable-in-principle. Startup itself is still never blocked — the ~30-minute batch remains fully asynchronous.
  - `develop/backend/src/test/java/com/stock/service/StartupCatchUpRunnerTest.java` — updated the 3-arg `StartupCatchUpRunner` construction to the new 4-arg form (added a real `JobRunningRegistry` instance, not a mock — cheap, dependency-free, and lets the tests assert real lock state), renamed all `stockSyncService.startBackfillTrackingCompletion(...)` stubs/verifications to `startBackfillWithLockAlreadyHeld(...)`, and added a new unit test.
  - `develop/backend/src/test/java/com/stock/StockPriceIngestionIntegrationTest.java` — added one new integration test (see below).
  - No other files changed; no schema change.
- New tests:
  - `StartupCatchUpRunnerTest.jobLock_isHeldSynchronously_beforeOnApplicationReadyReturns` (pure unit test, no Spring context) — stubs `stockSyncService.startBackfillWithLockAlreadyHeld(...)` to return an outcome whose completion future is left deliberately incomplete, calls `runner.onApplicationReady()`, and asserts `jobRunningRegistry.isRunning("PRICE_BACKFILL")` is already `true` by the time the call returns — directly pins down the root cause this increment fixes.
  - `StockPriceIngestionIntegrationTest.backfill_whileStartupCatchUpJobInFlight_returns409` (live MySQL DB + `MockRestServiceServer`) — deterministic by construction, no sleep/race timing: seeds one stock (`T261`), registers a `MockRestServiceServer` expectation whose `ResponseCreator` counts down a `requestReceived` latch and then blocks on a `releaseResponse` latch (gating exactly when the mocked external call "returns"). The test then reproduces exactly what `StartupCatchUpRunner.onApplicationReady()` now does — `jobRunningRegistry.tryStart("PRICE_BACKFILL")` followed by `stockSyncService.startBackfillWithLockAlreadyHeld(...)`, never through the HTTP endpoint — awaits `requestReceived` (proving the "startup" job is genuinely in flight, not merely "probably still running" as the pre-existing `backfill_secondCallWhileRunning_returns409` sibling test tolerates), then fires a real `POST /api/stocks/sync/backfill` (deliberately with `stockIds` omitted, i.e. `ALL` mode, to prove the rejection is per-`jobType` and independent of which stocks the manual call targets) and asserts `409 {"code":"JOB_ALREADY_RUNNING"}`. Releases the gated response and waits for the in-flight job to settle in a `finally`/tail step so the shared `JobRunningRegistry` state never leaks into other tests.
- Verification performed:
  - `mvn -f develop/backend/pom.xml compile` and `test-compile` — clean.
  - `mvn -f develop/backend/pom.xml test` — **122/122 passing** (120 pre-existing across the whole backend module + 2 new: 1 in `StartupCatchUpRunnerTest`, 1 in `StockPriceIngestionIntegrationTest`), run twice consecutively with no flakiness. (The jump from Increment 3's reported 87 reflects unrelated test growth from `specs/backend/stock-indicator-statistics.md` and other specs' work landing in the same module since; nothing in this increment removed or weakened any existing test.)
  - Self-reviewed via the `code-quality` skill: confirmed the lock is released on every failure path in both `startBackfillInternal` and the new `startBackfillWithLockAlreadyHeld` (no leak on the error path — the specific concurrency flag the skill calls out), confirmed no behavior change for the manual/HTTP path's pre-existing validate-before-lock ordering, and confirmed the new test's `try/finally` guarantees the gated mock response and the registry lock can never be left stuck for later tests even if an assertion fails mid-test.
- Not implemented / no further changes: the `- [ ]` checkbox at line 230 and the `status:` frontmatter are left untouched per instructions, for the requester to sign off.

### Increment 5 — 2026-08-31
- Status: DONE (pending checkbox sign-off by the requester)
- Scope: the 9 trailing unchecked Acceptance Criteria — (A) explicit `Asia/Taipei` timezone end to end, (B) `caughtUpCount` on the backfill `202` response, (C) empty target list must be safe (a real, observed `BadSqlGrammarException` at startup).
- **(A) Timezone.** The MySQL container/data were already switched to `Asia/Taipei` by DBA migrations (out of scope here); this increment covers only the application side.
  - `develop/backend/src/main/java/com/stock/BackendApplication.java` — added a `static { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei")); }` block, so the JVM's default timezone is set before Spring builds any bean or any `LocalDate.now()`/`LocalDateTime.now()` call happens, regardless of the host/CI machine's own default.
  - `develop/backend/pom.xml` — added `-Duser.timezone=Asia/Taipei` to both the `spring-boot-maven-plugin`'s `jvmArguments` (covers `mvn spring-boot:run`, i.e. `docker/launch.json`'s actual startup path) and a newly-declared `maven-surefire-plugin`'s `argLine` (covers `mvn test`): this is deliberate belt-and-suspenders with the static block — it guarantees every test JVM's default zone is `Asia/Taipei` from process start regardless of whether `BackendApplication`'s static initializer has actually run yet (Spring's test bootstrapping resolves the primary configuration class via ASM metadata reading in some code paths, which does not reliably trigger class initialization), so the fix does not depend on that class-loading nuance in either entry point.
  - `develop/backend/src/main/resources/application.yml` and `develop/backend/src/test/resources/application.yml` — JDBC URL's `serverTimezone=UTC` changed to `serverTimezone=Asia%2FTaipei` (URL-encoded `/`), so the MySQL Connector/J connection's timezone is explicit rather than left to the driver's auto-detection or a stale mismatched value.
  - No DB/compose changes (explicitly out of scope and already done).
- **(B) `caughtUpCount`.**
  - `develop/backend/src/main/java/com/stock/dto/BackfillResponse.java` — added `caughtUpCount` (int) as a new constructor parameter (positioned right after `targetCount`) with a getter documenting why it exists (a caught-up stock's progress row is left exactly as it was, so "did nothing" and "finished everything" are otherwise indistinguishable from the progress table alone).
  - `develop/backend/src/main/java/com/stock/service/StockSyncService.java` — `runBackfill` now captures `caughtUpIds.size()` (already computed via the existing `findCaughtUpStockIds` call inside the `catchUp` branch — no new query) into a local `caughtUpCount`, defaulted to `0` for the `resume`/reset branches (i.e. whenever `catchUp` is `false`), and passes it into the `BackfillResponse` constructor.
  - `develop/backend/src/test/java/com/stock/service/StartupCatchUpRunnerTest.java` — updated its one `new BackfillResponse(...)` call site to the new 6-arg constructor (`caughtUpCount: 0`, since that dummy response is never inspected by the tests that use it).
- **(C) Empty target list.** Root-caused and fixed at the mapper-interface layer rather than only at the one call site the reported crash came from, per instructions ("check for the same pattern in other mappers").
  - `develop/backend/src/main/java/com/stock/mapper/StockSyncProgressMapper.java` — the 5 methods that take a `stockIds` list as MyBatis `<foreach>` input (`upsertPendingReset`, `upsertPendingIfAbsent`, `findProcessableStockIds`, `findCaughtUpStockIds`, `upsertPendingForCatchUp`) are now `default` methods that check `stockIds.isEmpty()` and either no-op (the two `void` upserts) or return `Collections.emptyList()` (the two finders) *without issuing any SQL*, delegating to a newly-renamed raw method (suffixed `ForNonEmptyIds`) for the non-empty case. Public method names/signatures seen by callers are unchanged — only the previously-XML-bound method was renamed, with the XML `id` renamed to match.
  - `develop/backend/src/main/resources/mapper/StockSyncProgressMapper.xml` — renamed the 5 corresponding `id`s to the `ForNonEmptyIds` suffix; SQL bodies untouched.
  - `develop/backend/src/main/java/com/stock/mapper/StockMapper.java` + `develop/backend/src/main/resources/mapper/StockMapper.xml` — same pattern applied to `findExistingStockIds` and `findByIds` (used elsewhere by `IndicatorRebuildService`, `StrategyScanService`, `StockStatisticsService`; those callers already guarded against an empty list before calling in, so this is defensive/future-proofing rather than fixing an observed bug there, but keeps the fix uniform across every list-taking query in the module rather than leaving one shape fixed and others not).
  - **Root cause confirmed**: MyBatis's `<foreach>` — even one declared with `open="(" close=")"` — emits *nothing at all*, open/close included, when the backing collection is empty; this is what produced the exact truncated `... AND stock_id IN` SQL in the reported startup log. The fix stops the query from ever reaching MyBatis in that case, rather than trying to make the generated SQL empty-collection-safe.
  - **Incidental fix**: `IndicatorRebuildService.rebuild`/`rebuildForStartup` (a different spec, `stock-indicator-statistics.md`) call the exact same `upsertPendingReset`/`findProcessableStockIds` methods with a target list that can also be empty (zero `is_active=1` stocks) and had the identical unguarded bug; the mapper-level fix resolves it there too as a side effect, with no changes to that spec's own files.
  - No changes were made to `StockDailyPriceMapper.xml` / `StockDailyIndicatorMapper.xml` / `StockStatisticsMapper.xml`, which have the same `<foreach>` shape: their existing callers (`StockStatisticsService`, `IndicatorRebuildRunner`) already guard with an explicit `if (!targetIds.isEmpty())` before calling in, so they are not exposed to this failure mode today. Left as-is to keep this increment's diff scoped to the module actually exhibiting the bug.
- New tests, all added to `develop/backend/src/test/java/com/stock/StockPriceIngestionIntegrationTest.java` (live MySQL DB + `MockRestServiceServer`-mocked FinMind, using fresh `T27x`/`T28x` stock ids under the existing `T2%` cleanup pattern):
  - `timezone_progressFinishedAt_matchesApplicationLocalNow_withinOneMinute` — runs a real backfill to completion, reads `finished_at` directly via `jdbc`, asserts `Duration.between(finishedAt, LocalDateTime.now()).abs() < 1 minute`. A regression to the old DB(UTC)/app(host-default) mismatch would fail this by ~8 hours.
  - `timezone_progressLastSyncedAt_matchesApplicationLocalNow_noEightHourOffset` — same, but through `GET /api/stocks/sync/progress`'s `lastSyncedAt` field (the exact field the spec calls out as visibly wrong under a timezone mismatch).
  - `backfill_catchUp_allTargetsAlreadyCaughtUp_caughtUpCountEqualsTargetCount_zeroExternalRequests` — two stocks seeded already `DONE` through `endDate`; asserts `caughtUpCount == targetCount == 2` and registers **zero** `mockServer.expect(...)` calls (any external request fails the test outright).
  - `backfill_catchUp_partiallyCaughtUp_caughtUpCountEqualsCaughtUpSubset_laggingStockStillSynced` — one caught-up + one lagging stock; asserts `caughtUpCount == 1`, `targetCount == 2`, the lagging stock's gap request is the *only* registered/consumed mock expectation, and its progress row still advances to `endDate` normally.
  - `backfill_catchUpFalse_caughtUpCountAlwaysZero_evenWhenStockAlreadyCaughtUp` — a stock already caught up under `catchUp` semantics, but requested with `catchUp` omitted (`false`); asserts `caughtUpCount == 0` even though the stock gets fully reset and refetched (proving the field reflects `catchUp`'s truth value, not the underlying `last_synced_date` state).
  - `backfill_allMode_noActiveStocks_catchUpTrue_targetCountZero_completesWithoutException_noExternalRequest` — deactivates every stock in the live DB (restored in `finally`, mirroring the established pattern in `backfill_allMode_targetsActiveStocksOnly`), fires `POST /api/stocks/sync/backfill` with `stockIds` omitted and `catchUp: true` — this is the literal reported-bug reproduction (`findCaughtUpStockIds(jobType, [], endDate)`) — and asserts `202`, `targetCount: 0`, `caughtUpCount: 0`, zero external requests, no exception.
  - `backfill_allMode_noActiveStocks_catchUpFalse_targetCountZero_completesWithoutException_noExternalRequest` — same empty-DB setup but `catchUp` omitted, exercising the `upsertPendingReset` empty-`<foreach>` INSERT path instead.
  - `startupCatchUpPath_noActiveStocks_locksAndCompletesWithoutError` — reproduces `StartupCatchUpRunner.onApplicationReady()`'s exact call sequence (`jobRunningRegistry.tryStart` then `stockSyncService.startBackfillWithLockAlreadyHeld`) against the same empty-DB state, asserting `assertDoesNotThrow(...)` and that the job lock is released once the (empty) batch settles — this is the most direct proof that "啟動補齊不產生任何錯誤紀錄，啟動流程正常完成" holds for this scenario, short of an actual full-process restart (consistent with the verification depth already established in Increment 4, whose own note says the same about not needing a literal process restart to pin down this class of fix).
- Verification performed:
  - `mvn -f develop/backend/pom.xml clean compile` — clean.
  - `mvn -f develop/backend/pom.xml test-compile` — clean (also confirms the `BackfillResponse` constructor-signature change and the mapper method renames don't break any existing Mockito-based unit test — Mockito stubs interface methods, default or abstract, identically, so `when(progressMapper.findProcessableStockIds(...))`-style stubs in `IndicatorRebuildServiceTest` needed no changes).
  - `mvn -f develop/backend/pom.xml test` — **130/130 passing** (122 pre-existing + 8 new: 2 timezone, 3 `caughtUpCount`, 3 empty-target-list), run twice consecutively with no flakiness.
  - Killed a stray, hours-old leftover `com.stock.BackendApplication` process that was still bound to port 8080 from an earlier manual verification session (confirmed idle — startup catch-up for 34 already-synced stocks completes in well under a minute) before running the suite, so it could not interfere with the live DB the tests also exercise.
  - Direct `mysql` CLI check after the full suite run: `stock` = 34 rows / 34 active, `stock_daily_price` = 5372 rows (0 with any zero-valued price column), `stock_sync_progress` `PRICE_BACKFILL` = 34 rows, 0 leftover rows anywhere matching `stock_id LIKE 'T%'` — all identical to the pre-existing state this task was told to preserve.
  - Self-reviewed via the `code-quality` skill: confirmed the mapper-level empty-list guards are additive/backward-compatible (existing callers' method names/signatures unchanged, only the underlying XML-bound method was renamed), confirmed the fix addresses root cause (short-circuits before any SQL is built, rather than trying to special-case the generated SQL for an empty `IN`), confirmed the `BackfillResponse` field addition is a compatible wire-format change, and confirmed the deactivate/restore test helper follows the already-established sequential-test-execution assumption in this file (no new concurrency exposure). No issues required fixing as a result of this review.
- Not implemented / deliberately left as-is: `StockDailyPriceMapper.xml`, `StockDailyIndicatorMapper.xml`, and `StockStatisticsMapper.xml`'s equivalent `<foreach>` queries were not given the same interface-level guard, since their current callers already check emptiness before calling in (verified by reading each caller) and touching those files would spill this increment's diff into `stock-indicator-statistics.md` / `strategy.md` / `stock-minute-price.md`'s own modules without an observed bug to justify it. The `- [ ]` checkboxes and `status:` frontmatter are left untouched per instructions, for the requester to sign off.
