---
status: done
title: "產業別漲幅排行 API"
requirement: "動態分頁 — 以「漲幅平均」與「漲幅加總」兩種度量，列出指定期間內（近 N 交易日，或勾選的數個自然週）漲幅超過門檻的股票，並按產業別分組顯示；一檔股票屬於多個產業別時，在每一個產業別下都要出現"
depends_on: [stock-price-ingestion, stock-catalog, stock-universe-import]
---

# 產業別漲幅排行 API — Backend Spec

## Overview

在既有日線行情上計算每檔股票在指定期間內的漲幅，篩出達到門檻的股票，並依 `stock_industry`（`specs/dba/stock-industry.md`）的關聯把它們歸入產業別區塊回傳。供前端動態分頁（`specs/frontend/momentum.md`）呈現。

此模組**只讀不寫**：輸入是 `stock_daily_price` 的收盤價與 `stock_industry` 的關聯，輸出是即時算出的分組清單，不落地任何結果表。理由與 `specs/backend/strategy-scan.md` 相同——結果完全由期間與門檻參數決定，換一組參數就是另一份答案，落地會立刻面臨「這列是用哪組參數算的」的問題，而重算成本本來就低（一次範圍查詢加一次順序掃描）。

**本模組回報的是漲幅統計結果，不是買賣建議。** 回應欄位、UI 文案與產出文件一律以「漲幅／命中／統計」表述，不得出現「建議買進」「推薦」「可進場」等暗示操作的措辭。這與 `specs/backend/strategy-scan.md` 是同一條契約，不是文字風格偏好。

## Requirements

### 兩種度量

兩者都建立在同一個基礎量上：**單日漲跌幅**。

> 單日漲跌幅(D) = (該檔 D 的 `close_price` ÷ 該檔**前一個有行情的交易日**的 `close_price` − 1) × 100

收盤價一律取 `stock_daily_price.close_price`（原始成交價，未經除權息還原，見 `specs/dba/stock-daily-price.md`）。除權息當日的價格跳空會如實反映在單日漲跌幅中——這與系統其他所有以收盤價為基礎的計算（`specs/backend/strategy-scan.md`、`specs/backend/stock-indicator-statistics.md`）採同一份未還原資料，不得只在本模組另做還原，否則同一檔股票在不同分頁會顯示互相矛盾的漲跌。

| 度量 | `metric` | 定義 |
|---|---|---|
| 漲幅加總 | `SUM` | 期間內每一個交易日的單日漲跌幅**相加** |
| 漲幅平均 | `AVERAGE` | 上述加總 **÷ 該檔在期間內的交易日數** |

- 兩者對同一組資料會給出**相同的排名**（只差一個固定除數），差別在門檻的量級：`SUM` 的 5% 是「整段期間累計漲 5%」，`AVERAGE` 的 5% 是「平均每個交易日漲 5%」。這是刻意的設計，讓使用者用同一個門檻數字問兩個不同的問題。
- **「前一個有行情的交易日」一律以相鄰交易日認定，不因停牌造成的日曆間隔做任何插補。** 此規則與 `specs/backend/strategy-scan.md`、`specs/backend/stock-indicator-statistics.md` 一致，不得各自為政。
- 期間**第一天**的單日漲跌幅需要期間開始之前的一根收盤價，因此判定所需的前置資料為每檔 `startDate` 之前最近的 1 個交易日。該日本身不計入交易日數，也不列入回傳。

### 期間

兩種模式，互斥。

| 模式 | `mode` | 參數 | 區間決定方式 |
|---|---|---|---|
| 近 N 交易日 | `DAYS` | `days`（1–120） | 取 `stock_daily_price` 全表相異 `trade_date` 由新到舊的前 `days` 個；最舊者為 `startDate`、最新者為 `endDate` |
| 指定週 | `WEEKS` | `startDate`、`endDate` | 直接採用；前端已把勾選的週折算成「最早一週的週一」到「最晚一週的週日」 |

- **`DAYS` 模式的區間以資料庫中最新的交易日為錨點，不是以今日。** 今日可能是假日、或當日行情尚未寫入，用今日往回數會讓「近 20 交易日」實際涵蓋 19 天或更少，而使用者從畫面上完全看不出來。回應一律回傳實際採用的 `startDate` / `endDate`，讓前端顯示的是真正算過的區間。
- `WEEKS` 模式的區間端點不需要是交易日；落在區間內的交易日即為計算範圍。區間內一個交易日都沒有時，回傳空結果而非錯誤（勾選了一整週國定假日是合法操作）。
- 週的定義為**週一至週日的自然週**，前端負責換算；後端只收日期區間，不認識「週」這個概念。這讓週的邊界規則只存在一處。

### 門檻

- 參數 `minGain`，單位為百分比，預設 `5`。
- 篩選條件為 **`metric` 算出的值 ≥ `minGain`**，只篩漲幅，不提供跌幅方向。
- **比較以四捨五入至小數點後 2 位之後的值進行**，與回傳／顯示的值是同一個數。否則會出現算出 `4.996`、畫面顯示 `5.00`、卻沒有被列出來的情形——使用者無從得知那是四捨五入造成的。
- 允許負值與 0（使用者要看全部股票時填 `-100`）。範圍 `-100` ~ `1000`。

### 掃描範圍

- 母體固定為 `stock` 中 `is_active = 1` 的全部股票，**沒有指定股票的參數**。本 API 回答的是「這段期間哪些股票漲得多」，先挑好股票再問這個問題沒有意義。
- 期間前置資料不足（`startDate` 之前沒有任何一根收盤價）或期間內完全無行情的股票，計入 `insufficientDataCount`，**不列入結果、也不視為未達門檻**。兩者對使用者是不同的訊息：「算過了沒到門檻」與「資料不夠所以沒算」。
- **收盤價為 `0` 的列同樣視為資料不足**，該檔計入 `insufficientDataCount`。單日漲跌幅的分母是前一個交易日的收盤價，為 `0` 時這個比值沒有定義——不是「漲了無限多」，而是「這一天沒有可用的價格」。
  - 這不是假設性的邊界情況：實際資料中有 876 列、涵蓋 49 檔標的（ETN、權證類）的 `close_price` 為 `0.00`。母體是全市場在市股票，因此**任何一次全市場查詢都必然掃到它們**。若不處理，整支 API 會在遇到第一檔時以除以零中止，使用者看到的是整個查詢失敗，而不是少了幾檔。
  - 一律以「該檔資料不足」處理，不要改用前一個非零收盤價去頂替——那等於憑空發明一個沒發生過的價格，而且會讓那一天的漲跌幅算成 0%，看起來像是平盤而不是無資料。

### 產業別分組

- 每一檔命中的股票，依 `stock_industry` 查出其所屬的**全部**產業別，並在**每一個**產業別的 `items` 中各出現一次。
- 因此各產業 `matchedCount` 的加總**會大於**實際命中的股票檔數。回應另給一個去重後的 `matchedStockCount`，前端顯示總數時一律用它。這兩個數字必須同時存在且不可互相取代——把它們折成一個，不論保留哪一個都會讓另一個問題變成無解。
- 命中但在 `stock_industry` 中沒有任何關聯的股票，歸入一個固定的**「未分類」**區塊（`industryId` 為 `null`），永遠排在所有產業別之後。不是丟掉——一檔漲幅達標的股票在畫面上憑空消失，比多一個「未分類」標題糟糕得多，而且產業別資料本來就可能還沒匯入。
- 沒有任何命中股票的產業別不出現在回應中（不回傳空區塊）。

### 排序

- 產業別區塊：依 `matchedCount` 由多到少；同數依 `industryName` 升冪；「未分類」永遠最後，不參與排序。
- 區塊內 `items`：依 `gain` 由大到小；同值依 `stockId` 升冪。

## Implementation Details

### API 契約

```
GET /api/momentum/gain
```

| 參數 | 型別 | 必填 | 預設 | 說明 |
|---|---|---|---|---|
| `metric` | string | 是 | — | `SUM` / `AVERAGE` |
| `mode` | string | 是 | — | `DAYS` / `WEEKS` |
| `days` | int | `mode=DAYS` 時是 | — | 1–120 |
| `startDate` | date | `mode=WEEKS` 時是 | — | `YYYY-MM-DD` |
| `endDate` | date | `mode=WEEKS` 時是 | — | `YYYY-MM-DD` |
| `minGain` | decimal | 否 | `5` | 百分比，範圍 `-100` ~ `1000` |

`mode=DAYS` 時帶入的 `startDate` / `endDate` 一律忽略；`mode=WEEKS` 時帶入的 `days` 一律忽略。忽略而非報錯，是因為前端在兩個模式間切換時保留另一個模式的輸入值是正常的 UI 行為。

Response `200`：

```json
{
  "metric": "SUM",
  "mode": "DAYS",
  "startDate": "2026-08-04",
  "endDate": "2026-08-29",
  "tradingDays": 20,
  "minGain": 5.00,
  "scannedStocks": 1365,
  "matchedStockCount": 37,
  "insufficientDataCount": 12,
  "industries": [
    {
      "industryId": 7,
      "industryName": "半導體業",
      "matchedCount": 12,
      "items": [
        {
          "stockId": "2330",
          "stockName": "台積電",
          "gain": 18.42,
          "tradingDays": 20,
          "startClose": 1180.00,
          "endClose": 1395.00,
          "firstTradeDate": "2026-08-04",
          "lastTradeDate": "2026-08-29"
        }
      ]
    },
    {
      "industryId": null,
      "industryName": "未分類",
      "matchedCount": 3,
      "items": []
    }
  ]
}
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `metric` / `mode` | string | 原樣回傳，供前端確認回應對應的是哪一次請求 |
| `startDate` / `endDate` | date | **實際採用**的區間端點（`DAYS` 模式由後端決定）；期間內無任何交易日時皆為 `null` |
| `tradingDays` | int | 區間內全市場相異交易日數 |
| `minGain` | decimal | 實際採用的門檻，2 位小數 |
| `scannedStocks` | int | 母體檔數（`is_active = 1` 的總數） |
| `matchedStockCount` | int | **去重後**的命中檔數 |
| `insufficientDataCount` | int | 因資料不足未參與計算的檔數 |
| `industries[].industryId` | int / null | `null` 代表「未分類」區塊 |
| `industries[].matchedCount` | int | 該產業別下的命中檔數，等於其 `items` 長度 |
| `items[].gain` | decimal | `metric` 對應的值，2 位小數；`SUM` 為累計、`AVERAGE` 為每日平均 |
| `items[].tradingDays` | int | **該檔**在區間內實際有行情的交易日數，可能小於 `tradingDays`（停牌） |
| `items[].startClose` / `endClose` | decimal | 該檔在區間內第一個／最後一個有行情交易日的收盤價，2 位小數 |
| `items[].firstTradeDate` / `lastTradeDate` | date | 對應上述兩個收盤價的日期 |

`startClose` / `endClose` 不參與門檻判定，僅供前端讓使用者對照「這段期間從多少漲到多少」。**`endClose ÷ startClose − 1` 不會等於 `gain`**，這是預期的：`gain` 是每日漲跌幅的算術加總（或平均），不是期間複利報酬，兩者在有波動時本來就不同。

錯誤：

| 情境 | 狀態碼 | 回應 |
|---|---|---|
| `metric` 缺漏或非 `SUM`/`AVERAGE` | `400` | `{"code":"INVALID_METRIC"}` |
| `mode` 缺漏或非 `DAYS`/`WEEKS` | `400` | `{"code":"INVALID_MODE"}` |
| `mode=DAYS` 但 `days` 缺漏或不在 1–120 | `400` | `{"code":"INVALID_DAYS"}` |
| `mode=WEEKS` 但 `startDate`／`endDate` 缺漏，或 `startDate` 晚於 `endDate` | `400` | `{"code":"INVALID_DATE_RANGE"}` |
| `minGain` 不在 `-100` ~ `1000` | `400` | `{"code":"INVALID_MIN_GAIN"}` |

### 處理流程

1. 解析並驗證參數。
2. 決定區間：`DAYS` 模式查詢相異 `trade_date` 由新到舊取 `days` 個；`WEEKS` 模式直接採用參數。
3. 取母體：`stock` 中 `is_active = 1` 的 `stock_id` 與 `stock_name`。
4. **一次批次查詢**取回 `[startDate, endDate]` 區間內全部股票的 `(stock_id, trade_date, close_price)`；**再一次批次查詢**取回每檔在 `startDate` 之前最近 1 個交易日的 `close_price`。
5. 在記憶體中依股票分組、依 `trade_date` 排序，逐日計算單日漲跌幅並累加；算出 `SUM` 或 `AVERAGE`，四捨五入至 2 位小數後與 `minGain` 比較。
6. **一次批次查詢**取回命中股票的產業別關聯（`stock_industry` join `industry`）。
7. 依產業別分組（一檔多產業則各歸一次），未關聯者歸「未分類」，排序後組裝回應。

**行情與關聯的讀取必須批次進行**，不得逐檔查詢：母體是全市場 1300 檔以上，逐檔即 1300 次往返。整支 API 對資料庫的查詢次數為固定值（區間交易日、母體、區間行情、前置行情、產業別關聯），**不隨股票檔數增加**。此為 `specs/backend/strategy-scan.md` 已建立的作法，沿用不另立一套。

## Acceptance Criteria
- [x] `GET /api/momentum/gain?metric=SUM&mode=DAYS&days=20` 回傳 `200`，`startDate`／`endDate` 為資料庫中最新 20 個相異交易日的兩端，且 `tradingDays` 為 20
- [x] `DAYS` 模式的區間錨點為資料庫中最新的 `trade_date`，不是今日：在最新交易日早於今日的資料上驗證 `endDate` 等於該最新交易日
- [x] `SUM` 的值等於期間內每日漲跌幅的相加：以構造資料（前置日收盤 100，區間內連續 4 個交易日收盤 100→105→105→110.25）驗證 `gain` 為 `0.00 + 5.00 + 0.00 + 5.00 = 10.00`
- [x] `AVERAGE` 對同一組構造資料為 `10.00 ÷ 4 = 2.50`，且 `SUM` 與 `AVERAGE` 兩種度量下的股票排名順序一致
- [x] 期間第一天的漲跌幅以 `startDate` 之前最近的交易日收盤價計算，該前置日不計入 `tradingDays`、不出現在 `firstTradeDate`
- [x] `startDate` 之前無任何收盤價的股票計入 `insufficientDataCount`，且不出現在任何產業別的 `items` 中
- [x] 相鄰交易日規則：區間內含停牌造成的日曆間隔時，結果與無間隔的同價格序列完全一致（不插補）
- [x] `minGain` 省略時預設為 `5`；`minGain=-100` 時全部有足夠資料的股票皆列出
- [x] 門檻比較以四捨五入至 2 位小數後的值進行：`gain` 原始值為 `4.996` 的股票在 `minGain=5` 時**會**被列出，且回傳的 `gain` 為 `5.00`
- [x] **一檔股票屬於兩個產業別且達標時，在兩個產業別的 `items` 中各出現一次**
- [x] 承上，`matchedStockCount` 為去重後的檔數，**小於**各產業 `matchedCount` 的加總
- [x] 命中但在 `stock_industry` 中無任何關聯的股票，出現在 `industryId` 為 `null`、`industryName` 為「未分類」的區塊中
- [x] 「未分類」區塊排在所有產業別之後，即使其 `matchedCount` 最大
- [x] 產業別區塊依 `matchedCount` 由多到少排序，同數依 `industryName` 升冪
- [x] `items` 依 `gain` 由大到小排序，同值依 `stockId` 升冪
- [x] 沒有命中股票的產業別不出現在 `industries` 中
- [x] `mode=WEEKS` 且 `startDate`／`endDate` 之間無任何交易日時，回傳 `200`、`industries` 為空陣列、`startDate`／`endDate` 為 `null`，而非錯誤
- [x] `mode=DAYS` 時帶入的 `startDate`／`endDate` 被忽略而不報錯；`mode=WEEKS` 時帶入的 `days` 同樣被忽略
- [x] `items[].tradingDays` 反映**該檔**實際有行情的日數：對區間內停牌數日的股票，其值小於回應層級的 `tradingDays`
- [x] 五種錯誤各自回傳指定的 `code`：`INVALID_METRIC`／`INVALID_MODE`／`INVALID_DAYS`／`INVALID_DATE_RANGE`／`INVALID_MIN_GAIN`
- [x] 全市場查詢時對資料庫的查詢次數為固定值，3 檔與 300 檔母體的查詢次數相同（不隨股票數線性增加）
- [x] 回應欄位名與說明文字皆無「建議」「推薦」等暗示買賣操作的措辭

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/controller/MomentumController.java` (new) — `GET /api/momentum/gain`
  - `develop/backend/src/main/java/com/stock/service/MomentumGainService.java` (new) — validation, range resolution, batched reads, per-stock gain math, industry grouping/sorting
  - `develop/backend/src/main/java/com/stock/dto/MomentumGainResponseDto.java`, `IndustryGainGroupDto.java`, `StockGainItemDto.java` (new)
  - `develop/backend/src/main/java/com/stock/domain/StockIndustryLink.java` (new) — `stock_industry` JOIN `industry` row
  - `develop/backend/src/main/java/com/stock/exception/InvalidMetricException.java`, `InvalidModeException.java`, `InvalidDaysException.java`, `InvalidMinGainException.java` (new); reused existing `InvalidDateRangeException` for `INVALID_DATE_RANGE`
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — added handlers for the 4 new exceptions
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` + `develop/backend/src/main/resources/mapper/StockDailyPriceMapper.xml` — added `findRecentDistinctTradeDates` (DAYS-mode anchor) and `countDistinctTradeDatesInRange` (WEEKS-mode market-wide `tradingDays`)
  - `develop/backend/src/main/java/com/stock/mapper/StockIndustryMapper.java` + `develop/backend/src/main/resources/mapper/StockIndustryMapper.xml` — added `findLinksByStockIds` (batched `stock_industry` JOIN `industry`)
  - `develop/backend/src/test/java/com/stock/MomentumGainIntegrationTest.java` (new) — 22 tests, one per AC
- Notes:
  - Followed the `StrategyScanService` batched-query convention exactly: population, window, and lookback reads are each a single query regardless of population size; the industry-link query only fires when at least one stock matched (its mapper method no-ops on an empty id list). Query count is fixed per mode (4 for WEEKS / DAYS with no hits, 5 with hits) — verified both by the `QueryCountInterceptor`-based test (3 vs 300 stocks) and by re-reading the SQL plan in the mapper XML.
  - **Found and fixed a real bug during live verification, not just automated tests**: production `stock_daily_price` contains rows with `close_price = 0.00` for certain ETN/warrant-type instruments (e.g. `020037`, `1312A`) on days they didn't trade. The original daily-percent-change formula divided by the previous close unconditionally, so any request touching one of these stocks threw `ArithmeticException: / by zero` and the *entire* endpoint 500'd (this is a whole-market scan, so one bad stock broke everyone). Fixed by detecting a zero close (either the lookback close or a mid-sequence bar) and folding that stock into `insufficientDataCount` instead of computing an undefined gain for it. This was invisible to the integration tests (which use synthetic non-zero prices) and only surfaced when hitting the live endpoint against real data — a good example of why the spec's live-verification step matters.
  - `tradingDays` is deliberately market-wide (not scoped to the active population) in both modes, per the spec's literal "全市場相異交易日數": `DAYS` mode gets it for free from the anchor query (`findRecentDistinctTradeDates`); `WEEKS` mode uses a dedicated `COUNT(DISTINCT trade_date)` query so a WEEKS-mode window's trading-day count doesn't depend on which stocks happen to be active — this costs one extra query per WEEKS-mode call but keeps the count fixed and mode-symmetric.
  - Rounding: per-day percentage changes are computed at `RoundingMode.HALF_UP`/scale 10 and summed (or summed-then-divided for `AVERAGE`) before the single final `setScale(2, HALF_UP)` — matching the spec's requirement that the threshold comparison and the displayed value are the same already-rounded number (verified live+test with the `4.996 → 5.00` boundary case).
  - `未分類` (unclassified) block: only emitted when at least one matched stock has no `stock_industry` link, consistent with "沒有命中股票的產業別不出現在回應中" — an all-classified result correctly omits the block rather than emitting an empty one.

### Verified live (real DB, `mvn spring-boot:run` on port 8080, then stopped)
- `GET /api/momentum/gain?metric=SUM&mode=DAYS&days=10&minGain=3` against the live 1374-active-stock / 35-industry / 1085-link dataset — 200, correct grouping/sorting/未分類-last, 140 matched stocks, 963 insufficient (this run is where the zero-close-price bug above was caught and fixed).
- `GET ...&mode=WEEKS&startDate=2026-09-01&endDate=2026-09-02&metric=AVERAGE` — 200, correct.
- All 5 error codes (`INVALID_METRIC`, `INVALID_MODE`, `INVALID_DAYS`, `INVALID_DATE_RANGE`, `INVALID_MIN_GAIN`) reproduced live with the exact JSON bodies specified.
- DB left exactly as found after both the live run and the automated test run: `stock` active count 1374 (unchanged), `industry` 35 rows (unchanged), `stock_industry` 1085 rows (unchanged), zero leftover `MG%` test rows.

### Verified by automated test only
- The full 22-case `MomentumGainIntegrationTest` suite (adjacency/no-interpolation, lookback-day exclusion, multi-industry membership, `matchedStockCount` dedup vs. per-industry sum, sort tie-breaks, `WEEKS`-mode zero-trading-day empty result, `DAYS`/`WEEKS` param-ignoring, per-stock `tradingDays` vs. response-level `tradingDays`, fixed query count) — these construct synthetic data (isolating the real ~1374-stock population per test, same technique as `StrategyScanIntegrationTest`'s AC3) and were not separately re-clicked through the live server one by one.

### Test results
- `mvn -f develop/backend/pom.xml test`: **188 tests, 0 failures, 0 errors, 0 skipped** (166 pre-existing + 22 new), BUILD SUCCESS.

### Left unfixed (deliberately, with reason)
- Malformed (non-numeric) `minGain` or malformed date strings fall through to Spring's default `MethodArgumentTypeMismatchException` handling rather than a custom `INVALID_MIN_GAIN`/`INVALID_DATE_RANGE` body — this is a pre-existing gap shared by every other numeric/date query param in the codebase (`StockStatisticsController`'s `startDate`/`endDate`, `MinuteBarController`'s `interval`), not something introduced here, and the spec's error table only covers missing/out-of-range values, not type-malformed ones. Left consistent with the rest of the codebase rather than fixing it ad hoc for one endpoint.
