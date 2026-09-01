---
status: pending
title: "上市股票 universe 匯入 API"
requirement: "將所有上市的股票先放到 stock 裡面，之後再由使用者自行按同步按鈕回補日 K；同一個動作一併帶入交易所官方產業別，供動態分頁按產業別分組"
depends_on: [stock-price-ingestion]
---

# 上市股票 universe 匯入 API — Backend Spec

## Overview

取得全體上市（`TSE`）股票的**代號、名稱與產業別**，UPSERT 進 `stock` 主檔與 `industry`／`stock_industry` 兩張產業別表，**不寫入任何行情**。

代號名稱與產業別是**同一個使用者動作的兩半**，因此由同一支 API 完成，而不是兩顆按鈕：使用者按「更新股票清單」時要的是「把主檔更新成最新狀態」，而「這檔股票叫什麼」與「這檔股票屬於哪個產業」都是主檔的一部分。拆成兩支 API 只會製造一種永遠存在的中間狀態——清單已更新、產業別還沒更新——而動態分頁（`specs/frontend/momentum.md`）在那個狀態下會把大量股票歸入「未分類」，看起來像是壞掉了。

這支 API 存在的理由是把「有哪些股票」與「這些股票的行情」拆成兩個各自可獨立觸發的動作。目前 `stock` 只有 `specs/dba/stock.md` V007 那 34 檔開發種子，而 `specs/backend/stock-price-ingestion.md` 的全市場回補、`specs/backend/strategy-scan.md` 的全市場掃描都以 `stock` 中 `is_active = 1` 的列為母體——母體只有 34 檔時，兩者都只會在這 34 檔上運作，且**不會報錯**（回補對空／小清單是合法情形，見該 spec 的「目標清單為空是合法情形」）。使用者因此會拿到一份看似完整、實則只涵蓋 34 檔的掃描結果。本 API 補的就是這個缺口。

與既有抓取路徑的分工：

| 動作 | 寫 `stock` | 寫 `industry`／`stock_industry` | 寫 `stock_daily_price` | 請求數 | 由誰觸發 |
|---|---|---|---|---|---|
| 本 API（universe 匯入） | ✅ 僅代號與名稱 | ✅ | ❌ **完全不寫** | **2** | 使用者按「更新股票清單」 |
| 每日增量（`POST /api/stocks/sync/daily`） | ✅ 順帶維護 | ❌ | ✅ 當日一天 | 1 | 每交易日收盤後 |
| 全市場回補（`POST /api/stocks/sync/backfill`） | ❌ | ❌ | ✅ 歷史區間 | 每檔 1 次 | 使用者按「同步日 K 至今日」 |

**產業別只由本 API 維護。** 每日增量與回補的資料源是行情快照，其中沒有產業別欄位；讓它們去猜或去補會多出兩條寫入路徑，而三條路徑對同一份分類資料各有各的更新時機，正是分類資料最容易悄悄變得不一致的成因。

**本 API 刻意不寫行情，即使資料源在同一個回應裡就附了當日 OHLCV。** 使用者要的順序是先把清單建好、再自己決定何時開始長時間的行情回補；順手寫入一天的行情會讓 `stock_daily_price` 出現「只有某一天、前後都沒有」的孤立列，而 `specs/backend/stock-indicator-statistics.md` 的 MACD／KD 是遞迴推進的，孤立列會產生一個沒有前值可承接的起點。要行情就走回補路徑，一次補整段。

## Requirements

### 資料源

本 API 對外發出**兩次**請求，兩者互不依賴。

| # | 用途 | 端點 | 取用欄位 |
|---|---|---|---|
| 1 | 股票清單 | `https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL` | `Code`（代號）、`Name`（名稱） |
| 2 | 產業別 | `https://openapi.twse.com.tw/v1/opendata/t187ap03_L`（上市公司基本資料） | `公司代號`、`產業別` |

- 來源 1 與 `specs/backend/stock-price-ingestion.md` 的「每日全市場快照」為**同一個資料源**，本 API 只取代號與名稱，其餘欄位一律丟棄。
- 來源 2 的 `產業別` 是**中文名稱字串**（例如「半導體業」「光電業」「電子零組件業」），不是代碼。因此 `industry` 以名稱為唯一鍵、比對不到就新增一列（見 `specs/dba/industry.md`）——來源沒有提供穩定代碼，名稱是唯一可用的識別依據。
- **交易所的官方分類不包含「AI」這類主題型概念股分類。** 來源 2 給的是公司登記的主要營業類別，因此畫面上會出現「半導體業」「光電業」「電子零組件業」，而不會出現「AI」。要有主題分類需要另一份人工維護的資料，那是另一個需求，不在本 API 範圍內。
- **兩次都是單一請求即涵蓋全市場**，因此本 API 仍然**不需要速率控制、不需要 `stock_sync_progress` 進度追蹤、不需要斷點續傳**——這些機制在 `stock-price-ingestion` 中存在，是因為逐檔歷史查詢有每檔一次請求的限制；本 API 沒有這個限制，不得為求一致而複製一套上來。
- 上櫃（`OTC`）不在本 API 範圍內。櫃買中心是另一個端點、另一套欄位格式，需要時另立需求。

### 標的篩選：只收普通股

只有**恰為 4 個數字字元、且首字元不為 `0`** 的代號會被寫入。其餘一律計入 `skippedCount` 並忽略。

| 類型 | 代號樣態 | 處理 |
|---|---|---|
| 普通股 | `2330`、`1101`、`9958` | ✅ 寫入 |
| ETF／受益憑證 | `0050`、`006208`、`00878` | ❌ 略過（首字為 `0`） |
| 特別股 | `2881A`、`2891B` | ❌ 略過（含非數字字元） |
| TDR／存託憑證 | `910322`、`911616` | ❌ 略過（超過 4 字元） |
| REIT | `01004T` | ❌ 略過（首字為 `0` 且含字母） |

理由：`specs/backend/strategy-scan.md` 的兩個型態（箱型突破、底底高）都是為個股價格行為設計的判定規則。ETF 的價格由一籃子成分股加權而成，其「箱型」與「突破」在意義上與個股不同；把約 200 檔 ETF 混進母體，只會稀釋掃描結果並拉長回補時間，不會讓使用者多得到可用的訊號。

### 寫入語意

- 以 UPSERT 寫入 `stock`：`stock_id`、`stock_name`、`market = 'TSE'`。
- **新列**：`is_active` 寫入 `1`。
- **既有列**：只更新 `stock_name`（台股實務上會更名，見 `specs/dba/stock.md` 的「維護語意」）。**`is_active` 與 `market` 一律不動。**
- 本 API **在任何情況下都不會把 `is_active` 由 `1` 改為 `0`**，也不會由 `0` 改回 `1`。

`is_active` 兩個方向都不動，是同一個理由的兩面：

- **不自動下市**：`STOCK_DAY_ALL` 是「當日有成交」的快照，停牌、當日全無交易的股票不會出現在回應中。用「沒出現在這份清單裡」反推下市，會把停牌股票誤標成下市，而下市會讓該檔退出所有回補與掃描排程——一次停牌就足以讓一檔股票從系統中無聲消失。
- **不自動復活**：既有列可能是使用者透過 `DELETE /api/stocks/{stockId}`（見 `specs/backend/stock-catalog.md`）刻意下市的。匯入若把它改回 `is_active = 1`，等於每按一次按鈕就默默撤銷一次人為決定。

下市與復市一律由 `specs/backend/stock-catalog.md` 的 `DELETE` / `PUT /api/stocks/{stockId}` 人工處理。

#### 產業別的寫入語意

產業別的寫入在 `stock` UPSERT **之後**、同一個交易邊界內進行：

1. 對來源 2 的每一列，取 `產業別` 字串並去除前後空白；為空字串者忽略該列。
2. 以名稱 UPSERT `industry`，取得 `industry_id`（已存在的名稱不產生新列，`industry_id` 不變）。
3. 對每一檔**在來源 2 中出現、且通過普通股篩選、且存在於 `stock` 中**的股票，**先刪除其在 `stock_industry` 的既有關聯，再寫入本次取得的關聯**（整組取代，理由見 `specs/dba/stock-industry.md`）。
4. **沒有出現在來源 2 中的股票，其既有關聯一律不動**——不是清成空的。來源 2 涵蓋的是上市公司基本資料，人工新增的標的、或當次來源漏掉的股票，沒有理由因為別人被更新而失去自己的分類。

「先刪再寫」的範圍嚴格限制在**本次來源有涵蓋到的股票**。對整張 `stock_industry` 做 `TRUNCATE` 再重建，會讓來源 2 的一次異常回應（少了一半公司）直接抹掉一半股票的分類，而畫面上只會表現為「很多股票突然變成未分類」，沒有任何錯誤訊息。

`stock_industry` 的寫入必須在 `stock` 與 `industry` 兩端都 UPSERT 完成之後才進行——關聯表有指向兩者的外鍵，順序錯了會直接違反約束。

### 空回應的處理

資料源在非交易日、或當日資料尚未發布時，可能回傳空陣列。

**空回應必須以錯誤結束，不得視為「成功匯入 0 檔」。** 回 `502 UPSTREAM_EMPTY`，且不對 `stock` 做任何寫入。理由：這兩者對使用者的意義完全相反——「成功匯入 0 檔」會讓人以為清單已是最新而直接去按同步，結果同步仍只跑那 34 檔；明確的錯誤才會讓人知道要換個時間再試。

### 產業別來源失敗的處理

**來源 2 的任何失敗（連線失敗、空陣列、無法解析）都不會讓整支 API 失敗。** 匯入照常完成、照常回 `200`，只是完全跳過產業別的寫入階段（既有的 `industry` 與 `stock_industry` 資料原封不動），並在回應中以 `industrySourceStatus` 誠實回報發生了什麼。

兩個來源的失敗處理不對稱，是因為它們對使用者的意義不同：

- **來源 1 失敗 = 這次操作什麼也沒做成**。使用者按「更新股票清單」要的就是這份清單，拿不到就沒有可回報的成功。
- **來源 2 失敗 = 這次操作成了一半**。清單已經更新，那是使用者要的主要結果；因為產業別拿不到就把清單更新一併退掉，等於用一個次要資料的問題否決一個已經成功的主要動作。既有的產業別關聯仍然可用（只是沒更新到最新），動態分頁不會因此空掉。

回報必須明確，不得靜默：`industrySourceStatus` 不是 `OK` 時，前端要在完成摘要中顯示產業別未更新（見 `specs/frontend/momentum.md` 與 `specs/frontend/strategy.md`），而不是讓使用者以為兩半都成功了。

### 併發

本 API 為同步短作業（單一外部請求），**不使用 `stock_sync_progress` 的 `jobType` 併發鎖**，因此與執行中的 `PRICE_BACKFILL` 不互斥。

這是安全的：回補作業在啟動時就已解析並固定了自己的標的清單（見 `specs/backend/stock-price-ingestion.md` 的「批次目標選擇」），匯入期間新增的股票不會被塞進一個正在跑的批次。新股票會在**下一次**回補時才納入——這正是使用者「先更新清單、再按同步」的操作順序所預期的行為。

## Implementation Details

### API 契約

```
POST /api/stocks/universe/import
```

無 request body、無 query 參數。範圍固定為上市普通股，沒有可調參數。

Response `200`：

```json
{
  "fetchedCount": 1247,
  "eligibleCount": 1032,
  "skippedCount": 215,
  "insertedCount": 998,
  "updatedCount": 34,
  "totalActiveCount": 1032,
  "industrySourceStatus": "OK",
  "industryCount": 29,
  "industryLinkedStockCount": 1015,
  "uncategorizedStockCount": 17
}
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `fetchedCount` | int | 來源 1 回傳的總列數（篩選前） |
| `eligibleCount` | int | 通過普通股篩選的檔數 |
| `skippedCount` | int | 被篩掉的檔數（ETF／特別股／TDR 等），`fetchedCount − eligibleCount` |
| `insertedCount` | int | 本次新增的 `stock` 列數 |
| `updatedCount` | int | 本次命中既有 `stock` 列的檔數（名稱可能相同，仍計入） |
| `totalActiveCount` | int | 匯入後 `stock` 中 `is_active = 1` 的總列數 |
| `industrySourceStatus` | string | `OK` / `UNAVAILABLE` / `EMPTY` / `MALFORMED`；非 `OK` 時代表本次完全跳過產業別寫入 |
| `industryCount` | int | 匯入後 `industry` 的總列數；`industrySourceStatus` 非 `OK` 時為寫入前的既有值 |
| `industryLinkedStockCount` | int | 匯入後在 `stock_industry` 中至少有一筆關聯的**在市**股票檔數 |
| `uncategorizedStockCount` | int | 匯入後完全沒有產業別關聯的在市股票檔數，等於 `totalActiveCount − industryLinkedStockCount` |

`uncategorizedStockCount` 存在的理由是讓「動態分頁的未分類區塊為什麼這麼大」在按下按鈕的當下就有答案，而不必等使用者切過去看到一堆未分類才開始查。

錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| 來源 1 回傳空陣列 | `502` | `{"code":"UPSTREAM_EMPTY"}` |
| 來源 1 連線失敗／逾時／非 2xx | `502` | `{"code":"UPSTREAM_UNAVAILABLE"}` |
| 來源 1 回應無法解析 | `502` | `{"code":"UPSTREAM_MALFORMED"}` |

三種失敗一律**不對 `stock`、`industry`、`stock_industry` 做任何部分寫入**：先完整取得並解析來源 1 的回應、篩選出合格清單，確認非空後才進入寫入階段。

**來源 2 的對應三種失敗不產生錯誤回應**，改為以 `industrySourceStatus` 回報並回 `200`（見「產業別來源失敗的處理」）。

### 服務流程

1. 對來源 1 發出一次請求（逾時與重試沿用 `stock-price-ingestion` 既有的 HTTP 設定，不另訂一套）。
2. 解析回應為列陣列；解析失敗 → `502 UPSTREAM_MALFORMED`。
3. 陣列為空 → `502 UPSTREAM_EMPTY`，結束，不寫入。
4. 逐列取 `Code` 與 `Name`，套用「只收普通股」的篩選規則，統計 `skippedCount`。
5. 名稱正規化：去除前後空白。名稱為空字串的列計入 `skippedCount` 並忽略——沒有名稱的主檔列在清單頁上是一列空白，比不存在更難察覺。
6. 合格清單為空（來源 1 有回應但一檔普通股都沒有）→ 同樣回 `502 UPSTREAM_EMPTY`。
7. 對來源 2 發出一次請求並解析。任何失敗都不中止流程，僅記下 `industrySourceStatus`（`UNAVAILABLE` / `EMPTY` / `MALFORMED`）並讓產業別清單為空。
8. 逐列取 `公司代號` 與 `產業別`：套用同一套「只收普通股」的代號篩選；產業別名稱去除前後空白，為空字串者忽略該列。
9. 在**單一交易邊界**內依序：批次 UPSERT `stock` → 批次 UPSERT `industry`（依名稱）→ 對本次來源涵蓋到的股票整組取代 `stock_industry` 關聯。產業別清單為空時，第 2、3 步整段跳過。
10. 查詢 `is_active = 1` 的總數、`industry` 的總列數、有／無關聯的在市股票檔數，組出回應。

**來源 2 的請求發生在寫入階段之前**，不是穿插在寫入之中。兩個來源都取回並解析完畢，才開一次交易寫完三張表——否則一次網路逾時會讓交易長時間持有寫入鎖，或留下只寫了一半的狀態。

### 資料來源對應

來源 1（`STOCK_DAY_ALL`）：

| 資料源欄位 | `stock` 欄位 |
|---|---|
| `Code` | `stock_id` |
| `Name` | `stock_name` |
| （固定值） | `market` = `'TSE'` |
| （僅新列） | `is_active` = `1` |

來源 2（`t187ap03_L`）：

| 資料源欄位 | 目標 |
|---|---|
| `產業別`（去空白後） | `industry.industry_name`（依名稱 UPSERT，取回 `industry_id`） |
| `公司代號` | `stock_industry.stock_id` |
| （上一步取得） | `stock_industry.industry_id` |

## Acceptance Criteria

- [x] `POST /api/stocks/universe/import` **不逐檔查詢**外部來源、不建立 `stock_sync_progress` 列
- [x] 匯入後 `stock` 的列數大於 1000，且全部 `market = 'TSE'`
- [x] 匯入後 `stock_daily_price` 與 `stock_daily_indicator` 的列數**完全不變**（本 API 不寫行情）
- [x] 代號 `0050`、`00878`、`2881A`、`910322` 一律不出現在 `stock` 中，且計入 `skippedCount`
- [x] 代號 `2330`、`1101` 出現在 `stock` 中，`stock_name` 為資料源回傳的名稱
- [x] 連續執行兩次，第二次的 `insertedCount` 為 `0`、`updatedCount` 等於第一次的 `eligibleCount`，且 `stock` 列數不變
- [x] 既有 34 檔種子股票在匯入後 `stock_id` 不重複、`stock_name` 為資料源的最新值
- [x] 事前以 `DELETE /api/stocks/{stockId}` 將某檔設為 `is_active = 0`，匯入後該檔仍為 `is_active = 0`
- [x] 資料源回傳空陣列時回 `502 UPSTREAM_EMPTY`，且 `stock` 列數與內容完全不變
- [x] 資料源連線失敗時回 `502 UPSTREAM_UNAVAILABLE`，且 `stock` 列數與內容完全不變
- [x] 回應的 `skippedCount` 等於 `fetchedCount` 減 `eligibleCount`
- [x] 回應的 `totalActiveCount` 等於匯入後 `SELECT COUNT(*) FROM stock WHERE is_active = 1` 的結果
- [x] 匯入完成後呼叫 `GET /api/stocks?page=1&size=50`，`total` 反映匯入後的檔數

---

- [ ] 一次呼叫**恰好**對外發出 2 次請求（`STOCK_DAY_ALL` 一次、`t187ap03_L` 一次），不逐檔查詢
- [ ] 匯入後 `industry` 有多列，`industry_name` 為中文產業別名稱（例如「半導體業」「光電業」），非亂碼、非數字代碼
- [ ] 匯入後 `stock_industry` 有多列，且 `2330` 的關聯指向「半導體業」（以 `HEX(industry_name)` 確認為正確 UTF-8）
- [ ] 回應含 `industrySourceStatus`（`OK`）、`industryCount`、`industryLinkedStockCount`、`uncategorizedStockCount` 四個欄位
- [ ] `uncategorizedStockCount` 等於 `totalActiveCount − industryLinkedStockCount`
- [ ] 連續執行兩次，第二次的 `industryCount` 與 `stock_industry` 列數與第一次相同（產業別匯入為冪等）
- [ ] **整組取代生效**：先人工為 `2330` 多插一筆指向其他產業的關聯，再匯入一次，該筆多餘關聯消失，只留下來源給的那一筆
- [ ] **未涵蓋的股票關聯不動**：先人工建立一檔不存在於來源 2 的股票（例如以 `POST /api/stocks` 新增）及其產業別關聯，匯入後該關聯仍完整存在
- [ ] `industry` 中既有名稱的 `industry_id` 在重複匯入後**不變**（以匯入前後的 `industry_id` 比對驗證）
- [ ] 產業別來源連線失敗時，API 仍回 `200`，`industrySourceStatus` 為 `UNAVAILABLE`，`stock` 的清單更新照常生效，且 `industry`／`stock_industry` 的列數與內容完全不變
- [ ] 產業別來源回傳空陣列時，API 回 `200`、`industrySourceStatus` 為 `EMPTY`，且 `stock_industry` 內容完全不變
- [ ] 產業別來源回應無法解析時，API 回 `200`、`industrySourceStatus` 為 `MALFORMED`，且 `stock_industry` 內容完全不變
- [ ] 來源 1 失敗（空陣列／連線失敗）時，`stock`、`industry`、`stock_industry` **三張表**的列數與內容皆完全不變
- [ ] 產業別來源中的非普通股代號（`0050`、`2881A`、`910322` 等）不會在 `stock_industry` 產生任何列
- [ ] 匯入後 `stock_daily_price` 與 `stock_daily_indicator` 的列數仍完全不變（本 API 仍不寫行情）

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/service/external/TwseClient.java` — added `fetchDailyAllRaw()`: same URL/RestTemplate as `fetchDailyAll()`, but returns raw unfiltered rows (no no-trade filtering) and distinguishes connectivity failure (`ExternalApiException`) from an unparsable response (new `ExternalApiMalformedException`)
  - `develop/backend/src/main/java/com/stock/service/external/ExternalApiMalformedException.java` — new
  - `develop/backend/src/main/java/com/stock/exception/{UpstreamEmptyException,UpstreamUnavailableException,UpstreamMalformedException}.java` — new, mapped to `502` by `GlobalExceptionHandler`
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — added handlers for the three exceptions above (`UPSTREAM_EMPTY` / `UPSTREAM_UNAVAILABLE` / `UPSTREAM_MALFORMED`, all `502`), with `log.warn` for operational visibility
  - `develop/backend/src/main/java/com/stock/service/StockUniverseImportService.java` — new: fetches once, applies the "恰為 4 位數字、首字元非 0" ordinary-share filter, computes `fetchedCount`/`eligibleCount`/`skippedCount`, delegates the UPSERT batch to `PriceIngestionService.applyUniverseImport`, and reads `totalActiveCount` after
  - `develop/backend/src/main/java/com/stock/service/PriceIngestionService.java` — added `applyUniverseImport(List<Stock>)`: `@Transactional`, UPSERTs only `stock` (via the new `upsertUniverse` mapper method), writes no price row; existing-id set resolved up front (same pattern as `applyDailySnapshot`) so insert/update counts don't depend on MySQL's ambiguous `ON DUPLICATE KEY UPDATE` affected-row count
  - `develop/backend/src/main/java/com/stock/mapper/StockMapper.java` + `develop/backend/src/main/resources/mapper/StockMapper.xml` — added `upsertUniverse` (on conflict, refreshes only `stock_name`; `market`/`is_active` untouched — deliberately different from the existing `upsert`, which also overwrites `is_active`/`market`) and `countActive()`
  - `develop/backend/src/main/java/com/stock/dto/{UniverseImportResponse,UniverseUpsertCounts}.java` — new
  - `develop/backend/src/main/java/com/stock/controller/StockSyncController.java` — added `POST /api/stocks/universe/import`
  - `develop/backend/src/test/java/com/stock/StockUniverseImportIntegrationTest.java` — new, 7 tests (happy path incl. filtering/no-price-write, idempotent re-run, deactivated-stock-stays-deactivated, empty array → 502, rows-but-none-eligible → 502, connection failure → 502, malformed JSON → 502)

- Notes:
  - Design: kept `PriceIngestionService` as the single owner of every write into `stock` (per its existing class doc), rather than putting `@Transactional` on a method `StockUniverseImportService` would call on itself — a same-class `@Transactional` call would silently not run in a transaction (Spring proxy self-invocation), which was caught and fixed during implementation before it shipped.
  - `design-patterns` skill reviewed: this is a single-algorithm CRUD-style import with no second implementation/variant in sight, so no pattern (Strategy/Factory/etc.) was introduced — matches the skill's "call it directly" guidance.
  - `code-quality` skill reviewed: added `log.warn` (with cause for the two failure exceptions) to the three new `GlobalExceptionHandler` entries for operational visibility, since a `502` here is a real external-dependency failure an operator would want to see, unlike routine `4xx` validation errors. One known, deliberately-accepted limitation: since the spec explicitly forbids a `stock_sync_progress`-style concurrency lock for this endpoint ("不使用 `stock_sync_progress` 的 `jobType` 併發鎖"), two overlapping calls could both resolve the same "existing ids" snapshot before either commits and both report a row as `insertedCount` instead of one insert + one update; the underlying `stock` table write itself stays correct (MySQL's `ON DUPLICATE KEY UPDATE` serializes concretely), only the reported counts could be off by one in that race — accepted as out of scope per the spec's own no-lock instruction, and not exercised by any acceptance criterion.
  - Verified against the **real** TWSE endpoint and the **real** dev database (not mocked), in addition to the 7 automated integration tests (which use `MockRestServiceServer`, all passing, `mvn -f develop/backend/pom.xml test` → 141/141 green):
    - Single external request, no `stock_sync_progress` rows created, response `{"fetchedCount":1377,"eligibleCount":1085,"skippedCount":292,"insertedCount":1,"updatedCount":1084,"totalActiveCount":1365}` (`skippedCount == fetchedCount - eligibleCount` ✓).
    - `stock` row count > 1000 and 100% `market = 'TSE'` confirmed via `SELECT COUNT(*) FROM stock WHERE market != 'TSE'` → `0`.
    - Re-ran immediately after: `insertedCount=0`, `updatedCount=1085` (equals the first run's `eligibleCount`), `stock` row count unchanged (1366 → 1366).
    - With `app.backfill.startup-catch-up.enabled=false`/`app.master-sync.startup.enabled=false` (to eliminate unrelated background writes from `StartupCatchUpRunner`), confirmed `stock_daily_price` and `stock_daily_indicator` row counts byte-identical before/after a live import call (22808/5372 → 22808/5372).
    - `2330`/`1101` present with their real names (台積電/台泥, confirmed correct UTF-8 via `mysql --default-character-set=utf8mb4`, not mojibake).
    - `0050`/`2881A`/`910322` already existed in the live DB (written by the unrelated daily-sync path, which applies no such filter) — confirmed their `stock_name` was byte-identical before/after the import call, proving this endpoint's filter skipped them rather than overwriting them; the integration test additionally proves fresh unseen codes in these shapes are never inserted at all.
    - Deactivated `1102` via the real `DELETE /api/stocks/1102`, ran import (which included `1102` in the upstream response), confirmed `is_active` stayed `0` after import, then restored it to `1` afterward (its pre-test state) so the live dev database was left as found.
    - `GET /api/stocks?page=1&size=50` → `total: 1365`, matching `SELECT COUNT(*) FROM stock WHERE is_active = 1` and the import response's `totalActiveCount`.
    - `502 UPSTREAM_EMPTY`: pointed `app.external.twse-daily-all-url` at a local stub returning `[]`; got `{"code":"UPSTREAM_EMPTY"}` / `502`, `stock` row count unchanged.
    - `502 UPSTREAM_UNAVAILABLE`: pointed the same property at a closed port (connection refused); got `{"code":"UPSTREAM_UNAVAILABLE"}` / `502`, `stock` row count unchanged.
    - `502 UPSTREAM_MALFORMED`: exercised only in the automated test (stub returns a JSON object instead of an array) — not re-verified against the live app in this session, since it requires the same kind of stub already used for the other two live checks; the integration test result is the evidence for this one.
  - All test artifacts (`9871`/`9872`/`9873` stock rows) were cleaned up by the test's `@AfterEach`; confirmed absent afterward. The backend process and local stub servers used for manual verification were stopped before finishing; port 8080 confirmed free.
