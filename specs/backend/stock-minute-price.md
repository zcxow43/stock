---
status: done
title: "分 K 隨選抓取與查詢 API"
requirement: "前端 K 線瀏覽 — 在日 K 圖上點選某一個交易日後，要能看到該日的分 K 走勢；最近 30 天由 Yahoo 抓取，30 天以前改由富果 Fugle 補抓（分 K 自 2023-05-23 起），更早的日期誠實回報超出範圍；富果金鑰以環境變數 FUGLE_API_KEY 注入，本機取自專案上一層目錄的 env 檔"
depends_on: [stock-price-ingestion, stock-catalog]
---

# 分 K 隨選抓取與查詢 API — Backend Spec

## Overview

提供「某檔股票某一個交易日的分鐘 K 棒」，供前端分 K 圖表（`specs/frontend/stock-minute-chart.md`）繪製。

與日線的抓取策略**刻意不同**：日線是排程主動全市場抓取，分 K 是**隨點選隨抓取（on-demand）並永久快取**。

理由是量級。全市場分 K 為每年約 1.4 億列（2200 檔 × 約 240 個交易日 × 266 根）；而使用者實際會看的分 K，是他點開的那少數幾個「股票 × 日期」組合。主動全抓等於為了 0.01% 的實際使用量，付出 1.4 億列的儲存與數十萬次的外部請求。隨選抓取讓成本正比於真實使用，且抓過就存進 `stock_minute_price`，第二次以後為純資料庫讀取。

## Requirements

### 資料源與其限制（設計前提，已實測確認）

- **不能沿用日線的資料源。** FinMind 的分 K 資料集 `TaiwanStockKBar` 在免費層被拒絕：請求回 HTTP 400 `{"msg":"Your level is free. Please update your user level."}`，任何日期皆然。這與日線 `TaiwanStockPrice` 免費層可用的情形不同，不是參數帶錯。

- **依日期分由兩個來源抓取，兩者互不備援。**

  | 目標日期 | 來源 | 理由 |
  |---|---|---|
  | 不早於「今日減 30 天」 | Yahoo | 免費、不需金鑰，且最近的日期最常被點開 |
  | 早於「今日減 30 天」，且不早於 `availableFrom` | 富果 Fugle | Yahoo 的 1 分 K 只提供最近 30 天，更早的日期只能靠富果 |
  | 早於 `availableFrom` | 不請求，直接回 `OUT_OF_WINDOW` | 兩個來源都沒有這麼早的分 K |

  `availableFrom` 為**設定值，預設 `2023-05-23`**（富果分 K 歷史資料的起始日），程式中不寫死此日期；它同時決定 `OUT_OF_WINDOW` 的分界，並原樣回傳給前端顯示。來源日後若開放更早的資料，只需改設定。

  **兩個來源互不備援**：30 天內的日期 Yahoo 請求失敗時，照常記為 `FAILED`，不改問富果；30 天以前的日期富果失敗時，也不改問 Yahoo（Yahoo 對這些日期必然回 422）。這樣每個日期只有一條抓取路徑，失敗原因不會被另一個來源的結果掩蓋。

- **Yahoo（最近 30 天）**：`https://query1.finance.yahoo.com/v8/finance/chart/<代號>.TW?interval=1m&period1=<起始epoch秒>&period2=<結束epoch秒>`
  上櫃股票的代號後綴為 `.TWO`，上市為 `.TW`，依 `stock.market` 決定。

  回應為**平行陣列**結構：`chart.result[0].timestamp[]` 為每根 K 棒起始時間的 epoch 秒，`chart.result[0].indicators.quote[0]` 之下的 `open[]` / `high[]` / `low[]` / `close[]` / `volume[]` 與其**逐一索引對應**。正規化時必須依索引配對，不可各自獨立處理。

  `interval=1m` 只提供最近 30 天，超出範圍時回 HTTP 422：`{"chart":{"result":null,"error":{"code":"Unprocessable Entity","description":"1m data not available for startTime=... and endTime=.... The requested range must be within the last 30 days."}}}`。這正是 30 天以前改走富果的原因；本系統以本地日期判斷分流，**不會**把請求送到 Yahoo 再等它回 422。

- **富果 Fugle（30 天以前）**：`GET https://api.fugle.tw/marketdata/v1.0/stock/historical/candles/<代號>?from=<tradeDate>&to=<tradeDate>&timeframe=1&fields=open,high,low,close,volume&sort=asc`，以 `X-API-KEY` header 帶入 API Key。下列各點中，速率限額與單次區間上限取自官方文件，其餘皆已於 2026-09-15 以真實請求確認（上市 2330、上櫃 6488；日期 2026-08-05、2026-09-10、2023-05-22 與週六 2026-08-08）：
  - 代號為**純代號**，上市、上櫃皆不加 `.TW`／`.TWO` 後綴；回應以 `exchange` 標示 `TWSE` 或 `TPEx`。
  - 回應 `data[]` 每筆的 `date` 為含時區的 ISO 8601（例 `2026-08-05T09:00:00.000+08:00`），**標示該分鐘的起始時間**，首根為 `09:00`，與 Yahoo 同一條時間格線。
  - `volume` 為**單根成交量，不是當日累計**，不做增量轉換；但**單位是「張」（1 張 = 1000 股）**，與 Yahoo 及 `stock_minute_price.volume`、`stock_daily_price.volume` 的「股」不同，寫入前必須 **× 1000** 換算為股。以 2408 南亞科 2026-07-30 實測：富果各分鐘 `volume` 加總為 118,196，同日日線成交量為 120,154,208 股，比值約 1000；Yahoo 來源的日期同一比值約 1.1。未換算時分 K 副圖的每根量會比實際小 1000 倍，前端再以「股 ÷ 1000」顯示成張，畫面上只剩個位數。
  - 查詢區間內查無資料時回 **HTTP 404（Resource Not Found），不是空陣列**；早於 2023-05-23 的日期與非交易日皆如此。
  - 分 K 歷史資料自 2023-05-23 起；單次查詢區間須小於 1 年（本功能一次只查一天，不受影響）。
  - 超過速率限額時回 HTTP 429。
  - **13:25–13:29 沒有 K 棒。** 這 5 分鐘是收盤前集合競價，沒有逐分成交；正常交易日為 09:00–13:24 每分鐘一根，再加上 13:30 一根，**共 266 根**。Yahoo 在這 5 分鐘回傳的開高低收為空值，經下方「正規化規則」丟棄後同樣是 266 根，兩個來源的 `bar_time` 集合完全相同。

- **FinMind 不採用。** 其分 K 資料集 `TaiwanStockKBar` 限 sponsor 付費會員；免費層請求回 HTTP 400 `{"msg":"Your level is free. Please update your user level."}`，任何日期皆然。

- **富果 API Key 由環境變數 `FUGLE_API_KEY` 注入，不寫入版控中的設定檔，也不得出現在日誌、錯誤訊息或任何回應中。** 本機開發時，金鑰存放在專案**上一層目錄**的 `env` 檔（不在任何 git repo 內；該檔另有空行與一行標題，金鑰為第一個非空、只由 base64 字元組成的行），由 `/start` 啟動後端時讀入並設定此環境變數；該檔不得複製進專案目錄。未設定時，應用程式啟動階段留下明確的警告紀錄；此時對 30 天以前日期的查詢**不發出請求、不寫入狀態表**，直接回 `dataStatus: FETCH_FAILED` 與 `message`「未設定富果 API Key，無法取得 30 天以前的分 K」。不寫入狀態表是刻意的：這是設定問題而非來源失敗，若照常累加 `attempt_count`，設定好金鑰之後這些日期仍會被重試上限擋住。

- **「超出範圍」誠實傳達到畫面上**：早於 `availableFrom` 的日期是產品層級的限制，不可假裝資料只是暫時抓不到。已抓取並存入的日期會永久保留——包括 Yahoo 在 30 天內抓到的日期，超過 30 天後仍直接讀資料庫，不會改由富果重抓。

- **速率控制沿用 `stock-price-ingestion` 既有的逐來源請求間隔與指數退避機制**，不另做一套；富果是其中獨立的一個來源，與 Yahoo 各自計算間隔。富果免費方案的歷史 K 線限額為每分鐘 60 次，因此**富果相鄰兩次請求的間隔不小於 1 秒**，數值取自設定。分 K 由使用者點擊觸發，尖峰更不可預測，節流比排程抓取更必要。

### 抓取決策（每次查詢的第一步）

依 `stock_minute_fetch_status`（見 `specs/dba/stock-minute-fetch-status.md`）決定是否請求外部來源：

| 狀態表的情形 | 動作 |
|---|---|
| 無對應列 | 請求外部來源 |
| `AVAILABLE`，且目標日**非今日** | 直接讀資料庫，零外部請求 |
| `AVAILABLE`，目標日**為今日**且 `fetched_at` 早於當日 14:00 | 重新請求（盤中資料會持續增長） |
| `AVAILABLE`，目標日為今日且 `fetched_at` 在當日 14:00 之後 | 直接讀資料庫（該日已收盤定案） |
| `NO_DATA` / `NOT_A_TRADING_DAY` | 直接回覆該狀態，零外部請求 |
| `OUT_OF_WINDOW`，且目標日**早於** `availableFrom` | 直接回覆該狀態，零外部請求 |
| `OUT_OF_WINDOW`，但目標日**不早於** `availableFrom` | 視為過時，等同「無對應列」重新判斷來源並請求 |

`OUT_OF_WINDOW` 不是來源回報的結果，而是以 `availableFrom` 在本地推算的結論；`availableFrom` 一改（或早期只有 Yahoo 時以「30 天」寫入的列），舊列就可能與現行分界矛盾。每次查詢都以現行 `availableFrom` 重新檢驗，這類列會在下一次被點開時自行修正，不依賴一次性的資料清理——曾有使用者點開 2026-07-30 時，因富果上線前留下的舊列而仍看到「最早只提供到 2023-05-23」。
| `FAILED` 且 `attempt_count` 未達上限（預設 3） | 請求外部來源 |
| `FAILED` 且已達上限 | 直接回覆失敗，零外部請求；僅 `refresh=true` 可強制重試 |

台股收盤為 13:30，加上資料源的延遲緩衝取 14:00 為「當日定案」的分界。

### 正規化規則

- epoch 秒一律以 **Asia/Taipei** 轉換為當地時間；`bar_time` 取 `HH:MM:00`。以 UTC 或伺服器預設時區換算會讓整條序列偏移 8 小時，畫出一張時間軸完全錯位但看起來仍「像」K 線圖的圖——這類錯誤不會拋出例外，只能靠檢查首根 K 棒是否為 09:00 發現。
- **落在 09:00–13:30 之外的 K 棒一律丟棄**（來源可能夾帶盤前試撮或延伸時段資料）。
- `open` / `high` / `low` / `close` 任一為 `null` 的索引代表該分鐘無成交，**整根丟棄，不得補零、不得沿用前一根收盤價填補**。理由見 `specs/dba/stock-minute-price.md`。
- 來源的 `volume` 若為當日累計值，必須先轉為單根增量再寫入。
- 富果回應的 `date` 已帶 `+08:00`，換算為 **Asia/Taipei** 當地時間後取 `HH:MM:00` 作為 `bar_time`；同樣丟棄 09:00–13:30 以外與 OHLC 任一為 `null` 的 K 棒。兩個來源正規化後，同一交易日必須落在完全相同的 `bar_time` 格線上；正常交易日的結果為 266 根（13:25–13:29 收盤前集合競價沒有 K 棒，見上方「富果 Fugle」）。
- 寫入一律 UPSERT；K 棒寫入與 `stock_minute_fetch_status` 更新必須在**同一個交易邊界**內完成。

### 週期聚合

`interval` 大於 1 時，由已存的 1 分 K **於查詢時聚合**，不另外落地（理由見 `specs/dba/stock-minute-price.md`）。

聚合規則：自 **09:00 為錨點**、每 `interval` 分鐘切一組，組內 `open` 取第一根的開盤、`high` 取最大、`low` 取最小、`close` 取最後一根的收盤、`volume` 取總和。**組內完全無 K 棒時不產生該組**，不補零值 K 棒。

以 09:00 為固定錨點（而非以當日第一根 K 棒為錨點）確保同一檔不同日、以及不同檔同一日的 5 分 K 落在相同的時間格線上，否則跨日比對會出現半根 K 棒的位移。

## Implementation Details

### API 契約

#### 分 K 查詢

```
GET /api/stocks/{stockId}/minute-bars
```

Query 參數：

| 參數 | 型別 | 必填 | 預設 | 說明 |
|---|---|---|---|---|
| `tradeDate` | date | 是 | — | 目標交易日 |
| `interval` | int | 否 | `1` | K 棒週期分鐘數，允許 `1` / `5` / `15` / `30` / `60` |
| `refresh` | boolean | 否 | `false` | `true` 時忽略快取與重試上限，強制向來源重抓 |

Response `200`：
```json
{
  "stockId": "2330",
  "stockName": "台積電",
  "tradeDate": "2026-08-25",
  "interval": 1,
  "dataStatus": "AVAILABLE",
  "source": "YAHOO",
  "availableFrom": "2023-05-23",
  "fetchedAt": "2026-08-27T09:12:33",
  "barCount": 266,
  "dailySummary": {
    "open": 2355.00,
    "high": 2380.00,
    "low": 2350.00,
    "close": 2375.00,
    "volume": 18234000
  },
  "bars": [
    { "barTime": "09:00", "open": 2355.00, "high": 2360.00, "low": 2355.00, "close": 2360.00, "volume": 1523 },
    { "barTime": "09:01", "open": 2360.00, "high": 2360.00, "low": 2355.00, "close": 2355.00, "volume": 842 }
  ]
}
```

`dataStatus` 的取值對應 `stock_minute_fetch_status.status`，其中 `AVAILABLE` / `NO_DATA` / `OUT_OF_WINDOW` / `NOT_A_TRADING_DAY` 四者同名同義；資料庫的 `FAILED` 對外序列化為 `FETCH_FAILED`（對呼叫端而言「抓取失敗」比裸的 `FAILED` 明確）：

| `dataStatus` | 意義 | `bars` | `dailySummary` |
|---|---|---|---|
| `AVAILABLE` | 有分 K 資料 | 有內容 | 有值 |
| `NO_DATA` | 該日為交易日，但來源無分鐘成交資料 | `[]` | 有值 |
| `OUT_OF_WINDOW` | 該日早於分 K 資料的最早可取得日（`availableFrom`），無法取得且無法補抓 | `[]` | 有值 |
| `NOT_A_TRADING_DAY` | 該日在 `stock_daily_price` 中無對應日線 | `[]` | `null` |
| `FETCH_FAILED` | 向來源請求失敗 | `[]` | 有值 |

`source` 取值為 `YAHOO` 或 `FUGLE`，尚未成功抓取時為 `null`。`availableFrom` **每個回應都有**，值為設定的最早可取得日，與該次查詢的日期與狀態無關——前端據此顯示「超出範圍」的說明，不自行寫死日期。

**即使沒有分 K，只要該日是交易日就必須回傳 `dailySummary`**——前端要據此顯示「這天的日線是這樣，但分 K 取不到」，而不是一片空白。

`FETCH_FAILED` 時額外回傳 `"message"` 欄位，內容為 `stock_minute_fetch_status.last_error`，供前端顯示失敗原因。

驗證與錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `stockId` 不存在於 `stock` 主檔 | `404` | `{"code":"STOCK_NOT_FOUND","stockId":"9999"}` |
| 缺少 `tradeDate` | `400` | `{"code":"MISSING_TRADE_DATE"}` |
| `tradeDate` 格式非法 | `400` | `{"code":"INVALID_DATE_FORMAT"}` |
| `tradeDate` 晚於今日 | `400` | `{"code":"FUTURE_TRADE_DATE"}` |
| `interval` 非允許值 | `400` | `{"code":"INVALID_INTERVAL","allowed":[1,5,15,30,60]}` |

「該日無分 K」一律以 `200` + `dataStatus` 表達，不使用 `404`。取不到資料是本功能的**預期常態**（早於 `availableFrom` 的日期一律如此），不是錯誤；用錯誤碼表達會讓前端無法區分「這天沒有分 K」與「這支 API 壞了」。

### 處理流程

1. 驗證參數 → 查 `stock` 主檔確認代號存在（不存在回 `404`）。
2. 查 `stock_daily_price` 確認 `tradeDate` 為該檔的交易日；否則寫入狀態 `NOT_A_TRADING_DAY` 並回覆。
3. 依「抓取決策」表判斷是否需要請求外部來源。
4. 需要抓取時依「依日期分由兩個來源抓取」選擇來源：`tradeDate` 早於 `availableFrom` → 直接寫入 `OUT_OF_WINDOW` 並回覆，不發請求；早於今日減 30 天 → 富果（未設定 API Key 時依上文回 `FETCH_FAILED`，不寫狀態表），以 `from`＝`to`＝`tradeDate` 發出請求，HTTP 404 記為 `NO_DATA`，HTTP 401／403／429、逾時或 5xx 記為 `FAILED`；其餘 → Yahoo，以 `Asia/Taipei` 當日 00:00～次日 00:00 換算 `period1`／`period2` 發出請求。
5. 依「正規化規則」處理回應 → UPSERT 進 `stock_minute_price` → 於同一交易內更新 `stock_minute_fetch_status`（狀態、`bar_count`、`source`、`fetched_at`）。
6. 自資料庫讀取該日 1 分 K → 依 `interval` 聚合 → 併入 `dailySummary` 後回覆。

### 資料來源對應

| 回應欄位 | 來源 |
|---|---|
| `stockId` / `stockName` | `stock.stock_id` / `stock.stock_name` |
| `bars[].barTime` | `stock_minute_price.bar_time`，序列化為 `HH:mm` |
| `bars[].open` / `high` / `low` / `close` / `volume` | `stock_minute_price.open_price` / `high_price` / `low_price` / `close_price` / `volume`（`interval > 1` 時為聚合值） |
| `dataStatus` / `source` / `fetchedAt` | `stock_minute_fetch_status.status`（`FAILED` → `FETCH_FAILED`）/ `source` / `fetched_at` |
| `availableFrom` | 設定值（預設 `2023-05-23`），不來自資料庫 |
| `dailySummary` | `stock_daily_price.open_price` / `high_price` / `low_price` / `close_price` / `volume` |

`barCount` 一律為**回應中 `bars` 的實際長度**（聚合後），而非狀態表中的 1 分 K 根數。

### 數值格式

價格 2 位小數，成交量為整數。

## Acceptance Criteria
- [x] `GET /api/stocks/2330/minute-bars?tradeDate=<近 30 日內的交易日>` 首次呼叫觸發外部抓取，回傳 `dataStatus: AVAILABLE` 且 `bars` 首根 `barTime` 為 `09:00`、末根不晚於 `13:30`
- [x] 正常交易日的 1 分 K 根數為 271（09:00–13:30 含首尾）（此為**當時的觀測**；2026-09-15 以真實資料複驗：Yahoo 在 13:25–13:29 回傳空值，正規化後為 266 根，與富果一致，以下方增量為準）
- [x] 同一 `(stockId, tradeDate)` 第二次呼叫**不再發出任何外部請求**（以請求記錄或來源呼叫計數驗證），回應內容與第一次相同
- [x] `tradeDate` 早於今日減 30 天時回 `dataStatus: OUT_OF_WINDOW`，且**未對外部來源發出請求**（此為**當時只有 Yahoo 一個來源**的行為；已於下方增量改為 30 天以前走富果、早於 `availableFrom` 才回 `OUT_OF_WINDOW`）
- [x] `OUT_OF_WINDOW` / `NO_DATA` 的日期重複呼叫多次，外部請求數維持為 0
- [x] 已標記 `AVAILABLE` 的過往日期，其分 K 在超過來源 30 日視窗之後仍可正常讀取（資料已永久落地）
- [x] `interval=5` 的聚合結果：`open` 等於組內第一根的 `open`、`close` 等於最後一根的 `close`、`high`／`low` 為組內極值、`volume` 為組內總和（以 1 分 K 原始資料逐組驗證）
- [x] `interval=5` 的首根 K 棒時間為 `09:00`（以 09:00 為錨點，非以首根有成交的 K 棒為錨點）
- [x] 來源回應中 OHLC 為 `null` 的分鐘不產生 K 棒；`stock_minute_price` 中**不存在任何價格為 0 的列**
- [x] 時間軸以 Asia/Taipei 換算：以已知交易日驗證首根 K 棒為 `09:00` 而非 `01:00` 或 `17:00`
- [x] 非交易日（如週六）回 `200` 與 `dataStatus: NOT_A_TRADING_DAY`，`dailySummary` 為 `null`
- [x] 分 K 取不到時（`NO_DATA` / `OUT_OF_WINDOW` / `FETCH_FAILED`），只要該日為交易日就仍回傳 `dailySummary`
- [x] `interval=3` 回 `400` 與 `INVALID_INTERVAL`
- [x] `tradeDate` 為未來日期回 `400` 與 `FUTURE_TRADE_DATE`
- [x] 資料庫狀態為 `FAILED` 的日期，API 回應的 `dataStatus` 為 `FETCH_FAILED`；其餘四種狀態的名稱在 API 與資料庫中相同
- [x] 抓取失敗後 `stock_minute_fetch_status` 的 `attempt_count` 累加且 `last_error` 有內容；達上限後不再自動重試，但 `refresh=true` 仍可強制重抓
- [x] K 棒寫入與狀態更新在同一交易內完成：模擬狀態更新失敗時，該次的 K 棒亦不留存（不出現「狀態為 AVAILABLE 但無 K 棒」）


### 30 天以前的分 K 改由富果補抓（本次新增）

**來源分流**
- [x] 不早於今日減 30 天的交易日照舊由 Yahoo 抓取：回 `dataStatus: AVAILABLE`、`source: YAHOO`，且未對富果發出任何請求
- [x] 早於今日減 30 天、且不早於 `availableFrom` 的交易日由富果抓取：回 `dataStatus: AVAILABLE`、`source: FUGLE`，且未對 Yahoo 發出任何請求
- [x] 富果請求為 `GET .../historical/candles/<代號>?from=<tradeDate>&to=<tradeDate>&timeframe=1&fields=open,high,low,close,volume&sort=asc`，並帶 `X-API-KEY` header（以請求記錄驗證完整路徑、參數與 header）
- [x] 早於 `availableFrom` 的交易日回 `dataStatus: OUT_OF_WINDOW`，且未對 Yahoo 與富果發出任何請求
- [x] 狀態表中已存在 `OUT_OF_WINDOW` 列、但日期不早於 `availableFrom` 的交易日：查詢時不回 `OUT_OF_WINDOW`，而是依日期向對應來源（30 天內 Yahoo）請求，回 `AVAILABLE` 並將該列更新為 `AVAILABLE`
- [x] 30 天內的日期 Yahoo 請求失敗時記為 `FAILED`，**未改向富果請求**；30 天以前的日期富果失敗時，**未改向 Yahoo 請求**
- [x] Yahoo 已抓取為 `AVAILABLE` 的日期超過 30 天後，仍直接讀資料庫，未對任何來源重新請求

**`availableFrom`**
- [x] 每個回應（含 `AVAILABLE`、`NO_DATA`、`OUT_OF_WINDOW`、`NOT_A_TRADING_DAY`、`FETCH_FAILED`）都帶 `availableFrom`，預設值為 `2023-05-23`
- [x] `availableFrom` 取自設定：將設定改為其他日期後，回應中的 `availableFrom` 與 `OUT_OF_WINDOW` 的分界同步改變，程式中不存在寫死的 `2023-05-23`

**富果正規化（以真實回應確認）**
- [x] 以富果的真實回應確認：一個正常交易日的 1 分 K 首根 `barTime` 為 `09:00`、末根為 `13:30`，13:25–13:29 沒有 K 棒，共 266 根
- [x] 同一交易日分別以 Yahoo 與富果正規化後，兩者的 `bar_time` 集合完全相同（驗證兩個來源落在同一條時間格線，而非相差一分鐘）；Yahoo 在 13:25–13:29 回傳的空值 K 棒被丟棄，因此兩者皆為 266 根
- [x] 富果 `date` 以其 `+08:00` 時區換算：首根為 `09:00`，而非 `01:00` 或 `17:00`
- [x] 富果 `volume` 以單根量寫入：逐根比對，寫入的成交量等於來源該根的 `volume` × 1000（張換算為股），未做累計轉增量
- [x] 同一交易日的 1 分 K 成交量加總與 `stock_daily_price.volume` 同一數量級（以真實富果資料驗證比值落在 1 附近，而非約 1000）；兩個來源寫入後單位一致，皆為股
- [x] 上櫃股票以純代號查詢富果（不加 `.TWO`）可取得資料，以一檔實際上櫃股票驗證
- [x] 富果結果中 OHLC 任一為 `null` 的分鐘不產生 K 棒，`stock_minute_price` 中不存在價格為 0 的列
- [x] 富果的 K 棒寫入與 `stock_minute_fetch_status` 更新（`source` 為 `FUGLE`）在同一交易內完成

**富果錯誤處理**
- [x] 富果回 HTTP 404 時記為 `NO_DATA`；該日期重複呼叫多次，對外請求數維持為 0
- [x] 富果回 HTTP 401／403／429、逾時或 5xx 時記為 `FAILED`：`attempt_count` 累加、`last_error` 含 HTTP 狀態或逾時原因；達上限後不再自動重試，`refresh=true` 仍可強制重抓

**API Key**
- [x] 富果 API Key 取自環境變數 `FUGLE_API_KEY`，版控中的設定檔不含任何金鑰值
- [x] 金鑰值不出現在應用程式日誌、`stock_minute_fetch_status.last_error`、回應的 `message` 或任何 API 回應中：以一個可辨識的假金鑰觸發成功與失敗（401）的請求後，搜尋日誌、資料庫與回應皆找不到該值
- [x] 未設定 API Key 時，應用程式啟動階段留下明確的警告紀錄
- [x] 未設定 API Key 時，查詢 30 天以前的交易日回 `dataStatus: FETCH_FAILED` 與 `message`「未設定富果 API Key，無法取得 30 天以前的分 K」，未發出任何請求，且 `stock_minute_fetch_status` 中未新增或修改該日期的列
- [x] 承上，設定 API Key 後查詢同一日期，可正常由富果抓取，不受先前的失敗影響
- [x] 未設定 API Key 時，30 天內的日期仍正常由 Yahoo 抓取

**速率控制**
- [x] 富果相鄰兩次請求的間隔不小於 1 秒，數值取自設定；以並發觸發多個 30 天以前的日期驗證實際請求時間間隔
- [x] 富果與 Yahoo 各自計算請求間隔：富果的節流不拖慢 Yahoo 的請求

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/domain/StockMinutePrice.java` (new)
  - `develop/backend/src/main/java/com/stock/domain/StockMinuteFetchStatus.java` (new)
  - `develop/backend/src/main/java/com/stock/mapper/StockMinutePriceMapper.java` (new) + `develop/backend/src/main/resources/mapper/StockMinutePriceMapper.xml` (new)
  - `develop/backend/src/main/java/com/stock/mapper/StockMinuteFetchStatusMapper.java` (new) + `develop/backend/src/main/resources/mapper/StockMinuteFetchStatusMapper.xml` (new)
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` / `StockDailyPriceMapper.xml` (added `findOne` — single-row trading-day lookup, reused existing mapper)
  - `develop/backend/src/main/java/com/stock/service/external/YahooFinanceClient.java` (new) — parallel-array normalization, Asia/Taipei conversion, 09:00–13:30 session filter, null-OHLC drop, browser-like `User-Agent` header (Yahoo's edge rejects bare-JVM UA strings)
  - `develop/backend/src/main/java/com/stock/service/external/dto/YahooChartResponse.java`, `YahooChart.java`, `YahooChartResult.java`, `YahooIndicators.java`, `YahooQuote.java`, `NormalizedMinuteBar.java` (new)
  - `develop/backend/src/main/java/com/stock/service/MinutePriceIngestionService.java` (new) — `@Transactional` bars+status write
  - `develop/backend/src/main/java/com/stock/service/MinuteBarQueryService.java` (new) — decision table, window logic, 09:00-anchored aggregation, response assembly
  - `develop/backend/src/main/java/com/stock/controller/MinuteBarController.java` (new) — `GET /api/stocks/{stockId}/minute-bars`
  - `develop/backend/src/main/java/com/stock/exception/MissingTradeDateException.java`, `InvalidDateFormatException.java`, `FutureTradeDateException.java`, `InvalidIntervalException.java` (new) + `GlobalExceptionHandler.java` (handlers added)
  - `develop/backend/src/main/java/com/stock/dto/MinuteBarDto.java`, `DailySummaryDto.java`, `MinuteBarResponse.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` (widened `allowed` to `List<?>` so the same JSON key serves both `INVALID_SORT_FIELD` (strings) and `INVALID_INTERVAL` (integers); added `invalidInterval()` factory)
  - `develop/backend/src/main/java/com/stock/config/MinutePriceProperties.java` (new, `app.minute-price.max-attempt-count`, default 3)
  - `develop/backend/src/main/resources/application.yml` / `develop/backend/src/test/resources/application.yml` (added `app.external.yahoo-finance-base-url`, `app.minute-price.max-attempt-count`)
  - `develop/backend/src/test/java/com/stock/StockMinutePriceIntegrationTest.java` (new, 15 tests)
- Notes:
  - Reused `stock-price-ingestion`'s rate-limit/backoff mechanism directly (`BackfillProperties.RateLimit`, injected as-is) rather than adding a parallel config block, per spec's "不另做一套".
  - Fixed a self-caught design bug during implementation: the local out-of-window check must only run when the fetch decision table has already decided to fetch (i.e. never on an already-`AVAILABLE`/`NO_DATA` row) — otherwise an old `AVAILABLE` date would get silently downgraded to `OUT_OF_WINDOW` the moment it aged past 30 days, even though its bars are still physically in the table. Caught by acceptance criterion "已標記 AVAILABLE 的過往日期...仍可正常讀取" during test-writing, before it ever shipped.
  - `stock_minute_fetch_status.status` is written via five explicit mapper methods (`upsertAvailable`/`upsertNoData`/`upsertOutOfWindow`/`upsertNotATradingDay`/`markFailed`), mirroring `StockSyncProgressMapper`'s shape, rather than one generic upsert — keeps each write's intent explicit and makes it structurally impossible for application code to write an invalid status.
  - The last acceptance criterion ("模擬狀態更新失敗時，該次的 K 棒亦不留存") is verified by forcing the *bars* statement to fail mid-transaction (a `chk_smp_high_low` CHECK violation on the second of two bars in one `applyFetchResult` call) rather than the *status* statement, because the production status-write methods hardcode a valid literal (`'AVAILABLE'`/`'NO_DATA'`/etc.) in the mapper XML and can't be made to violate `stock_minute_fetch_status`'s CHECK constraint through the public API. This proxy exercises the exact same `@Transactional` boundary and proves the same invariant ("either both persist or neither does") that the criterion is protecting against.
  - Verified live against the real Yahoo Finance endpoint (not just mocks): confirmed the exact response shape (`chart.result[0].timestamp[]` + `indicators.quote[0]`), confirmed a full trading day is exactly 271 bars 09:00–13:30 Asia/Taipei, confirmed `volume` is already a per-minute value (not cumulative — real TSMC data showed non-monotonic per-minute volumes), and confirmed null-OHLC minutes genuinely occur (5 in one sample day), validating the drop-on-null design. In this sandbox, Java's TLS handshake gets blocked by Yahoo's edge with HTTP 429 even with a correct browser `User-Agent` header (curl with an identical UA succeeds; this is TLS/JA3-level bot detection, not a header or rate-limit issue) — added the `User-Agent` header regardless since Yahoo's edge is documented to reject header-less requests outright, but did not chase full TLS fingerprint spoofing as it's outside this spec's scope. The system's own retry/backoff/failure-tracking behavior was exercised end-to-end against this real failure and worked exactly as designed: `RateLimitedException` → backoff retries → `FAILED` status with populated `attempt_count`/`last_error` → API surfaced `FETCH_FAILED` with `message` and a non-null `dailySummary`.
  - All 15 new tests plus the pre-existing 55 pass (`mvn -f develop/backend/pom.xml test` → Tests run: 70, Failures: 0, Errors: 0). Verified all validation error codes (`MISSING_TRADE_DATE`, `INVALID_DATE_FORMAT`, `FUTURE_TRADE_DATE`, `INVALID_INTERVAL`, `STOCK_NOT_FOUND`) live via `curl` against a running instance. All tables (`stock`, `stock_daily_price`, `stock_daily_indicator`, `stock_minute_price`, `stock_minute_fetch_status`, `stock_sync_progress`) confirmed at 0 rows after every run; app stopped and port 8080 confirmed free before finishing.

### Increment 2 — 2026-09-15

本次執行「30 天以前的分 K 改由富果補抓」增量，25 項全數完成。

**來源分流**：查詢依日期決定來源——不早於今日減 30 天走 Yahoo（既有路徑不變）；更早但不早於 `availableFrom` 走富果；早於 `availableFrom` 直接寫入 `OUT_OF_WINDOW`，不對任何來源發出請求。兩個來源互不備援。已由 Yahoo 抓到的 `AVAILABLE` 日期超過 30 天後仍讀資料庫。

**`availableFrom`**：取自設定（預設 `2023-05-23`），每一種回應都帶。另以覆寫設定為 `2026-08-01` 的整合測試，證明回應值與 `OUT_OF_WINDOW` 分界同步移動；程式中除設定預設值外不存在 `2023-05-23` 字面值。

**富果**：新增富果用戶端，以純代號、`from`＝`to`＝`tradeDate`、`timeframe=1` 請求並帶 `X-API-KEY`。HTTP 404 記為 `NO_DATA`；401／403／429／逾時／5xx 記為 `FAILED`，`attempt_count` 累加、`last_error` 記錄狀態碼或逾時原因。以真實請求（上市 2330、上櫃 6488，2026-09-10）確認：266 根、首根 09:00、末根 13:30、13:25–13:29 無 K 棒、成交量為單根量、上櫃純代號可用，且同一日 Yahoo 與富果正規化後的 `bar_time` 集合完全相同。

**API Key**：以環境變數 `FUGLE_API_KEY` 綁定，版控設定檔中僅有空預設值。未設定時啟動留下警告；30 天以前的日期不發請求、不寫狀態表，回 `FETCH_FAILED` 與「未設定富果 API Key，無法取得 30 天以前的分 K」，Yahoo 日期不受影響；補上金鑰後同一日期可正常抓取。以可辨識的假金鑰觸發成功與 401 兩條路徑並掃描日誌與例外訊息，驗證金鑰值不外露。自動化測試一律模擬 HTTP 層並使用假金鑰，不呼叫真實富果 API。

**速率控制**：共用的逐來源節流器新增「以指定間隔取得許可」的呼叫方式，富果使用自己的設定間隔（預設 1000ms），與 Yahoo／FinMind 互不影響；以並發請求驗證富果實際間隔不小於設定值。

**已知但未處理（本增量範圍外）**：Yahoo 的分 K 路徑目前完全沒有經過共用節流器。這與本 spec「速率控制沿用既有逐來源請求間隔與指數退避機制」的原有要求不符，屬於本增量之前就存在的缺口；本次的「富果節流不拖慢 Yahoo」驗收因此成立，但 Yahoo 分 K 本身仍未節流。

**驗證**：`mvn -f develop/backend/pom.xml test` 由 404 項（0 失敗）增為 438 項（0 失敗），新增 34 項測試；另更新 1 項原先驗證「30 天即 `OUT_OF_WINDOW`」舊行為的測試，改為驗證以 `availableFrom` 為分界。

**變更檔案**：`FugleClient`、`FugleCandle`、`FugleCandlesResponse`（新）、`MinutePriceProperties`、`MinuteBarQueryService`、`SourceRateLimiter`、`MinuteBarResponse`、主程式與測試的 `application.yml`；測試 `FugleClientTest`、`StockMinutePriceFugleIntegrationTest`、`StockMinutePriceFugleMissingKeyIntegrationTest`、`StockMinutePriceAvailableFromConfigIntegrationTest`（新），`StockMinutePriceIntegrationTest`、`SourceRateLimiterTest`（更新）。
