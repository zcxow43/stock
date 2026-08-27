---
status: pending
title: "股票行情抓取與回補"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 取得全市場日線行情，支援每日增量、指定多檔回補、全市場回補，並具備斷點續傳"
depends_on: []
---

# 股票行情抓取與回補 — Backend Spec

## Overview

負責把外部日線行情寫入 `stock_daily_price`，並維護 `stock` 主檔。這是整個系統唯一的資料入口；指標運算（見 `specs/backend/stock-indicator-statistics.md`）一律以本模組寫入的資料為輸入，不另行對外抓取。

提供三種抓取路徑：

| 路徑 | 來源 | 請求數 | 用途 |
|---|---|---|---|
| 每日增量 | 交易所當日全市場快照 | **1 次**取得全市場 | 每個交易日收盤後例行更新 |
| 指定多檔回補 | 逐檔歷史查詢 | 每檔 1 次 | 補特定標的的歷史 |
| 全市場回補 | 逐檔歷史查詢 | 約 2200 次 | 系統初次建置 |

「指定多檔」與「全市場」是**同一條程式路徑的不同參數**，不是兩套實作——差別僅在標的清單的來源。

## Requirements

### 資料源與其限制（設計前提）

- **每日全市場快照**：`https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL`
  單一請求回傳全體上市股票當日的代號、名稱、開高低收、成交量、成交金額、成交筆數。上櫃需另打櫃買中心對應端點。此來源**同時提供代號與名稱**，故 `stock` 主檔由此順帶維護，不需獨立資料源。

- **逐檔歷史查詢**：`https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockPrice&data_id=<代號>&start_date=<起日>&end_date=<迄日>`
  回傳單檔在區間內的每日 `open` / `max` / `min` / `close` / `Trading_Volume` / `Trading_money` / `Trading_turnover`。

- **關鍵限制（已實測確認）**：免費層**不支援一次取得全市場**——省略 `data_id` 的請求回 HTTP 400（`"Your level is free. Please update your user level."`）。全市場回補因此必然是逐檔的長時間作業，這是 `stock_sync_progress` 斷點續傳機制存在的直接原因。

- **速率控制為必要機制，不是最佳化**：逐檔回補必須有可設定的請求間隔與並行上限，並對 HTTP 429／逾時採用指數退避重試。預設值以保守為準（序列執行、每次請求間隔至少 1 秒），並可由設定調整。硬編死的無節流迴圈會在數十次請求內被來源封鎖。

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

### 斷點續傳

- 批次啟動時，為目標標的在 `stock_sync_progress` 以 `job_type = 'PRICE_BACKFILL'` UPSERT 建立 `PENDING` 列。
- 逐檔處理，狀態依 `specs/dba/stock-sync-progress.md` 的進度語意流轉。
- 續傳：重新呼叫回補端點並帶 `resume = true` 時，只取 `status IN ('PENDING','FAILED')` 且 `attempt_count` 未達上限的標的，並從各檔的 `last_synced_date` 之後接續。
- 目標區間內查無任何交易資料的標的標記為 `SKIPPED`，不再重試。

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
  "resume": false
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockIds` | string[] | 否 | 指定標的；**省略或空陣列代表全市場**（`stock.is_active = 1`） |
| `startDate` | date | 是 | 回補起日。須早於實際需要的統計起日至少 250 個交易日（暖身需求，見指標 spec） |
| `endDate` | date | 是 | 回補迄日 |
| `resume` | boolean | 否 | 預設 `false`（重置進度重跑）；`true` 表示只處理未完成與失敗的標的 |

Response `202`（非同步作業，立即回應）：
```json
{
  "jobType": "PRICE_BACKFILL",
  "targetCount": 2200,
  "startDate": "2025-09-01",
  "endDate": "2026-08-27",
  "mode": "ALL"
}
```
`mode` 為 `SELECTED` 或 `ALL`，依 `stockIds` 是否提供而定。

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
  "failedItems": [
    { "stockId": "1234", "attemptCount": 3, "lastError": "HTTP 429 rate limited" }
  ]
}
```
`failedItems` 最多回傳 50 筆。

### 處理流程

**每日增量**：取得全市場快照 → 正規化每列（代號、名稱、日期、價格）→ UPSERT `stock` 主檔 → UPSERT `stock_daily_price` → 回傳統計。單一請求即涵蓋全市場，不需進度追蹤。

**回補**：解析標的清單（多選或全市場）→ 初始化 `stock_sync_progress` → 逐檔依速率限制請求歷史 → 正規化並 UPSERT → 更新該檔進度 → 全部完成後結束。任一檔失敗只影響該檔進度，不中止整批。

## Acceptance Criteria
- [ ] `POST /api/stocks/sync/daily` 以單一外部請求取得全市場當日行情，並同時寫入 `stock` 與 `stock_daily_price`
- [ ] `POST /api/stocks/sync/backfill` 帶 `stockIds: ["2330","2317"]` 時只處理該 2 檔，回應 `mode` 為 `SELECTED`
- [ ] 同一端點省略 `stockIds` 時處理 `is_active = 1` 的全部股票，回應 `mode` 為 `ALL`
- [ ] 回補過程對外部資料源的請求有間隔控制，且 HTTP 429 觸發指數退避重試而非立即失敗
- [ ] 中途強制中斷後，以 `resume: true` 重新呼叫只處理未完成與失敗的標的，已完成標的不再發出外部請求
- [ ] 對同一檔同一區間連續執行兩次回補，`stock_daily_price` 的列數不變（冪等）
- [ ] 資料源未回傳的日期（停牌日）在 `stock_daily_price` 中不存在對應列，且**不存在任何價格為 0 的列**
- [ ] 資料源回傳的民國日期與含千分位的價格字串被正確正規化（以 2330 某月資料驗證）
- [ ] `GET /api/stocks/sync/progress` 回傳的各狀態計數總和等於 `total`
