---
status: pending
title: "股票清單查詢 API"
requirement: "前端 K 線瀏覽 — 使用者要能看到系統中總共有哪些股票，並從清單點選進入個股 K 線頁"
depends_on: [stock-price-ingestion]
---

# 股票清單查詢 API — Backend Spec

## Overview

提供前端**讀取用**的股票清單與單檔基本資料。這是使用者進入系統看到的第一個畫面（`specs/frontend/stock-list.md`）的唯一資料來源。

與既有兩支 backend spec 的分工：`stock-price-ingestion` 負責把外部資料寫進來、`stock-indicator-statistics` 負責運算與區間統計，本 spec 只負責**把已經在庫裡的東西列出來給人看**，不抓取、不運算、不寫入。

清單除了代號與名稱外，必須附上該檔的最新收盤與漲跌——只列出代號名稱的清單無法讓使用者決定要點哪一檔。

## Requirements

### 清單內容與篩選

- 資料來源為 `stock` 主檔（由 `stock-price-ingestion` 的每日抓取維護）。
- 支援關鍵字搜尋：對 `stock_id` 與 `stock_name` 同時做前綴／包含比對。全市場約 2200 檔，沒有搜尋就只能翻頁找。
- 支援市場別篩選（`TSE` / `OTC`）。
- 預設**只列出 `is_active = 1`** 的股票；已下市標的需明確要求才顯示。下市股票的歷史行情仍保留（見 `specs/dba/stock.md`），但不該混在日常瀏覽的清單裡。
- 分頁為必要機制而非選配：一次回傳 2200 檔會讓前端表格渲染與網路傳輸同時受害。

### 最新行情的取得方式（效能前提）

清單每列要顯示最新收盤價與漲跌，這需要每檔在 `stock_daily_price` 的**最後兩個交易日**（最新收盤與前一收盤，漲跌由兩者相減）。

**必須先分頁、再取行情，不可先關聯再分頁。**

先對 `stock` 分頁（走 `idx_market_active`，至多取回 `size` 筆代號），再以這批代號一次查詢其最近兩個交易日的行情。這樣行情查詢的規模被 `size` 上限固定住，與全市場檔數無關。反過來先把 2200 檔全部關聯行情再分頁，等於每次翻頁都掃過整張行情表，只為了丟掉其中 98% 的結果。

因此排序欄位**限定為 `stock` 表自身的欄位**（`stockId` / `stockName` / `market`）。依「漲跌幅」排序需要在分頁前就得到全市場的行情，與上述順序衝突，本階段不支援——這是刻意的取捨，不是遺漏。

### 無行情資料的標的

新上市或尚未回補的股票在 `stock_daily_price` 中沒有任何列。這類標的**仍須出現在清單中**，其行情欄位回 `null`，由前端顯示為「—」。從清單中隱藏它們會讓使用者以為系統漏了這檔股票。

同理，該檔在庫中只有一個交易日的資料時，`previousClose` / `changeAmount` / `changePercent` 為 `null`，但 `latestClose` 仍須回傳。

## Implementation Details

### API 契約

#### 1. 股票清單

```
GET /api/stocks
```

Query 參數：

| 參數 | 型別 | 必填 | 預設 | 說明 |
|---|---|---|---|---|
| `keyword` | string | 否 | — | 對 `stock_id` 與 `stock_name` 做包含比對，兩者任一命中即回傳 |
| `market` | string | 否 | — | `TSE` 或 `OTC`；省略代表不限市場別 |
| `includeInactive` | boolean | 否 | `false` | `false` 只回 `is_active = 1`；`true` 連同已下市一併回傳 |
| `page` | int | 否 | `1` | 1-based 頁碼 |
| `size` | int | 否 | `50` | 每頁筆數，上限 `200` |
| `sort` | string | 否 | `stockId` | `stockId` / `stockName` / `market` |
| `order` | string | 否 | `asc` | `asc` / `desc` |

Response `200`：
```json
{
  "page": 1,
  "size": 50,
  "total": 1378,
  "totalPages": 28,
  "items": [
    {
      "stockId": "2330",
      "stockName": "台積電",
      "market": "TSE",
      "isActive": true,
      "latestTradeDate": "2026-08-27",
      "latestClose": 2410.00,
      "previousClose": 2415.00,
      "changeAmount": -5.00,
      "changePercent": -0.21,
      "latestVolume": 17557736
    },
    {
      "stockId": "9999",
      "stockName": "新掛牌",
      "market": "OTC",
      "isActive": true,
      "latestTradeDate": null,
      "latestClose": null,
      "previousClose": null,
      "changeAmount": null,
      "changePercent": null,
      "latestVolume": null
    }
  ]
}
```

驗證與錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `size` 大於 200 | `400` | `{"code":"PAGE_SIZE_EXCEEDED","limit":200}` |
| `page` 小於 1 或 `size` 小於 1 | `400` | `{"code":"INVALID_PAGINATION"}` |
| `market` 非 `TSE`/`OTC` | `400` | `{"code":"INVALID_MARKET"}` |
| `sort` 非允許欄位 | `400` | `{"code":"INVALID_SORT_FIELD","allowed":["stockId","stockName","market"]}` |
| 查無符合條件的股票 | `200` | `items` 為空陣列，`total` 為 `0` |

#### 2. 單檔基本資料

```
GET /api/stocks/{stockId}
```

供個股 K 線頁的頁首顯示。單列查詢，故可附帶清單負擔不起的彙總欄位。

Response `200`：
```json
{
  "stockId": "2330",
  "stockName": "台積電",
  "market": "TSE",
  "isActive": true,
  "latestTradeDate": "2026-08-27",
  "latestClose": 2410.00,
  "previousClose": 2415.00,
  "changeAmount": -5.00,
  "changePercent": -0.21,
  "latestVolume": 17557736,
  "firstTradeDate": "2025-01-02",
  "tradingDayCount": 312
}
```

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `stockId` 不存在於 `stock` 主檔 | `404` | `{"code":"STOCK_NOT_FOUND","stockId":"9999"}` |
| 該檔在庫中無任何行情 | `200` | 行情欄位為 `null`，`firstTradeDate` 為 `null`，`tradingDayCount` 為 `0` |

### 數值格式

價格與漲跌金額 2 位小數，漲跌百分比 2 位小數。`changePercent` 定義為 `(latestClose - previousClose) / previousClose × 100`。

### 資料來源對應

| 回應欄位 | 來源 |
|---|---|
| `stockId` / `stockName` / `market` / `isActive` | `stock.stock_id` / `stock_name` / `market` / `is_active` |
| `latestTradeDate` / `latestClose` / `latestVolume` | `stock_daily_price` 中該檔 `trade_date` 最大的一列的 `trade_date` / `close_price` / `volume` |
| `previousClose` | 同表中該檔第二新的一列的 `close_price` |
| `changeAmount` / `changePercent` | 由上述兩個收盤價計算，不落地儲存 |
| `firstTradeDate` / `tradingDayCount` | 該檔在 `stock_daily_price` 的 `MIN(trade_date)` 與 `COUNT(*)` |

## Acceptance Criteria
- [ ] `GET /api/stocks` 預設回傳第 1 頁、50 筆、僅 `is_active = 1` 的股票，且 `total` 為符合條件的總數
- [ ] `keyword=台積` 與 `keyword=2330` 皆能命中 2330（代號與名稱同時比對）
- [ ] `market=OTC` 只回傳上櫃股票；帶入 `TWSE` 等非法值回 `400` 與 `INVALID_MARKET`
- [ ] `includeInactive=true` 時已下市標的出現在結果中，預設則不出現
- [ ] `size=500` 回 `400` 與 `PAGE_SIZE_EXCEEDED`
- [ ] `sort=changePercent` 回 `400` 與 `INVALID_SORT_FIELD`（明確拒絕，而非默默改用預設排序）
- [ ] 在 `stock_daily_price` 中無資料的標的仍出現在清單，且其 `latestClose` 為 `null` 而非 `0`
- [ ] 僅有單一交易日資料的標的，`latestClose` 有值而 `previousClose`／`changePercent` 為 `null`
- [ ] 對 2200 檔規模的資料查詢任一頁，對 `stock_daily_price` 的查詢筆數不超過該頁 `size` 所涵蓋的股票數（驗證為「先分頁再取行情」，而非全表關聯後分頁）
- [ ] `GET /api/stocks/2330` 回傳含 `firstTradeDate` 與 `tradingDayCount` 的單檔資料
- [ ] `GET /api/stocks/9999`（不存在的代號）回 `404` 與 `STOCK_NOT_FOUND`
