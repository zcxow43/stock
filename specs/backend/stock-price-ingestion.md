---
status: done
title: "股票行情抓取與回補"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 取得全市場日線行情，支援每日增量、指定多檔回補、全市場回補，並具備斷點續傳；系統啟動時自動把全部在市股票的日線補齊至今日。逐檔歷史採 Yahoo 為主、FinMind 為備援的雙來源，任一來源對本機 IP 施加封鎖時自動切換來源繼續作業，不中斷批次、不消耗重試次數。回補的全市場母體預設只含上市普通股（排除 ETF／特別股／TDR，可由 commonStocksOnly 覆寫），逐檔抓取由序列改為 8 檔並行、每來源間隔 0.5 秒"
depends_on: []
---

# 股票行情抓取與回補 — Backend Spec

## Overview

負責把外部日線行情寫入 `stock_daily_price`，並維護 `stock` 主檔。這是整個系統唯一的資料入口；指標運算（見 `specs/backend/stock-indicator-statistics.md`）一律以本模組寫入的資料為輸入，不另行對外抓取。

提供三種抓取路徑，以及一個在系統啟動時自動觸發其中一條路徑的機制：

| 路徑 | 來源 | 請求數 | 用途 |
|---|---|---|---|
| 每日增量 | 交易所當日全市場快照 | **1 次**取得全市場 | 每個交易日收盤後例行更新 |
| 指定多檔回補 | 逐檔歷史查詢（雙來源） | 每檔 1 次 | 補特定標的的歷史 |
| 全市場回補 | 逐檔歷史查詢（雙來源） | 約 1000 次（母體為普通股） | 系統初次建置 |

「指定多檔」與「全市場」是**同一條程式路徑的不同參數**，不是兩套實作——差別僅在標的清單的來源。

**逐檔歷史有兩個可互換的外部來源**（Yahoo 為主、FinMind 為備援），任一來源對本機 IP 施加封鎖時自動切換到另一個繼續作業。這不是備援設施的錦上添花，而是這條路徑能否跑完的前提：全市場回補是約 1000 次外部請求的長時間作業，單一來源等於單點故障。完整規範見下方「來源選擇與封鎖切換」。

**啟動補齊（startup catch-up）不是第四條路徑**，而是在應用程式啟動完成時自動以 `catchUp` 參數呼叫上表的回補路徑，把每一檔在市股票從它自己的進度接續補到今日。它沒有自己的抓取邏輯、自己的速率控制或自己的進度表——完整規範見下方「啟動時自動補齊」。

## Requirements

### 資料源與其限制（設計前提，均為實測確認）

本模組有**三個**外部來源，職責不同，不可互相替代：

| 用途 | 來源 | 端點 | 涵蓋 | 請求數 |
|---|---|---|---|---|
| 每日全市場快照 | 交易所 OpenAPI | `https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL` | 全體上市股票的**當日**一根日 K | 1 次 |
| 逐檔歷史（**主**） | Yahoo Finance | `https://query1.finance.yahoo.com/v8/finance/chart/<代號><.TW\|.TWO>?interval=1d&period1=<起始epoch秒>&period2=<結束epoch秒>` | 單檔**整段區間**的日 K | 每檔 1 次 |
| 逐檔歷史（**備援**） | FinMind | `https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockPrice&data_id=<代號>&start_date=<起日>&end_date=<迄日>` | 同上 | 每檔 1 次 |

#### 每日全市場快照（交易所）

單一請求回傳全體上市股票當日的代號、名稱、開高低收、成交量、成交金額、成交筆數。上櫃需另打櫃買中心對應端點。此來源**同時提供代號與名稱**，故 `stock` 主檔由此順帶維護，不需獨立資料源。

#### 逐檔歷史為什麼必須有兩個來源

全市場回補是約 1000 次外部請求的長時間作業，期間任一來源都可能對本機 IP 施加封鎖。已發生的實例（2026-09-06）：FinMind 回 `403 {"msg":"ip banned","status":403,"retry_after":505,"token_tail":""}`，該次啟動 131 檔全數失敗、**0 檔成功**；同一時間、同一 IP 的交易所快照是成功的（1368 檔主檔 upsert 完成），證實封鎖來自單一服務，而非連外中斷。**單一逐檔來源即單點故障**——這是雙來源存在的直接原因，與 `stock_sync_progress` 斷點續傳存在的原因（逐檔、長時間、會中斷）是同一個。

#### Yahoo（主來源）的已測事實

- 與分 K（`specs/backend/stock-minute-price.md`）是**同一支端點、同一個平行陣列格式**：`chart.result[0].timestamp[]` 為每根 K 棒起始時間的 epoch 秒，`indicators.quote[0]` 之下的 `open[]` / `high[]` / `low[]` / `close[]` / `volume[]` 與其**逐一索引對應**。正規化必須沿用該 spec 既有的索引配對機制，**不得另寫一套解析**。
- `interval=1d` **沒有** `interval=1m` 那個「只有最近 30 天」的限制：實測 2015-01 的區間照常回 `200` 且有資料。因此逐檔歷史**不需要**分 K 那套 `OUT_OF_WINDOW` 超窗判斷。
- 回應同時含 `quote` 與 `adjclose` 兩組。**一律取 `quote`**——它是未經除權息還原的原始成交價，與下方「價格處理」的約定一致；`adjclose` 不使用。
- 代號後綴依 `stock.market` 決定：上市 `.TW`、上櫃 `.TWO`（實測 `6488.TWO` 正常、ETF `0050.TW` 正常）。
- 不需 token。每檔 1 次請求即取得整段區間，請求數與 FinMind 相同——改以它為主來源**不會**讓回補變慢。

#### Yahoo 的 404 帶有歧義，不得逕自判定為下市

`2330.TWO`（正確股票、**錯誤後綴**）與 `9999.TW`（代號不存在）回完全相同的 `404`：

```json
{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}
```

回應本身**無法區分**「這檔真的不存在」與「我們把後綴組錯了」。因此 Yahoo 的 `404` **不得**直接標記為 `SKIPPED`——`SKIPPED` 的語意是「不需重試」，一旦標上去就永久不再嘗試，一個後綴 bug 會靜默地把整批標的判定成已下市，而且從進度表上看起來一切正常。處置規則見下方「來源選擇與封鎖切換」。

#### FinMind（備援來源）的已測限制

- 免費層**不支援一次取得全市場**：省略 `data_id` 的請求回 `400`（`"Your level is free. Please update your user level."`），故它同樣只能逐檔。
- **API token 必須設定。** 現行設定值為空字串，請求以匿名身分送出（403 回應中 `token_tail` 為空可證），套用最低的免費配額，這是 2026-09-06 封鎖的直接成因。**備援來源本身若處在容易被封鎖的狀態，等於沒有備援**——token 必須提供，且**不得寫死在設定檔中隨程式碼進版控**，改由部署環境注入；未設定時應在啟動時留下明確的警告紀錄，而不是安靜地以匿名身分運作。

#### 速率控制為必要機制，不是最佳化

逐檔回補必須有可設定的請求間隔與並行上限，並對逾時與速率類錯誤採用指數退避重試。硬編死的無節流迴圈會在數十次請求內被來源封鎖。

**節流的單位是「來源」，不是整批作業。** 預設為**同時處理 8 檔**，而**每個來源各自維持至少 0.5 秒的請求間隔**（每來源至多 2 req/s，兩個來源合計至多 4 req/s）。兩個數值皆由設定決定，不寫死。

並行度與間隔管的是不同的事，不可互相替代：**間隔**決定來源感受到的請求密度，是被封鎖與否的唯一相關量；**並行度**只決定「同時有幾檔在路上」，讓單檔的網路往返（數百毫秒到數秒）不再逐檔累加到總耗時上。序列執行時總耗時是「每檔延遲 + 間隔」的總和，並行後總耗時由間隔決定而不再受延遲支配——這是把全市場同步從數十分鐘壓到數分鐘的全部原因，而不是靠對來源施加更大壓力換來的。

**兩個來源各自獨立計算請求間隔、退避與封鎖狀態**，不共用計數——其中一個被封鎖不應拖慢另一個。

這組預設值是取捨後的一點，不是可以任意調大的效能旋鈕：請求密度直接決定被封鎖的機率，而封鎖的代價（見下方「來源選擇與封鎖切換」）是整批作業提早結束、剩餘標的留到下一次。要往上調之前先確認來源的實際反應，不是先調完再看有沒有被擋。

### 來源選擇與封鎖切換

逐檔歷史的來源選擇是本模組的核心機制。它要達成的事只有一件：**任一來源被封鎖時，整批作業繼續跑完，而不是把封鎖期內的每一檔都記成失敗。**

#### 來源順位

`YAHOO` → `FINMIND`。每一檔都從順位最前、且**當下可用**的來源開始。

#### 來源可用性

每個來源各自維護一個「封鎖到期時間」：

- 初始為無，即可用。
- 收到**封鎖類回應**時設為到期時間；在到期前該來源視為不可用，**完全不對它發出請求**——不是「發了再失敗」。被封鎖期間繼續請求會延長封鎖，2026-09-06 的紀錄即顯示程式在整個封鎖窗內以每秒一檔的節奏持續請求。
- 到期後恢復可用；下一檔重新從順位最前的來源開始嘗試，藉此**自動切回主來源**，不需人工介入或重啟。

**封鎖類回應**的判定：
- HTTP `403` 或 HTTP `429`；
- 或回應內容指出配額／封鎖（例如 FinMind 的 `{"msg":"ip banned"}`）。

到期時間的取得：
- 回應提供 `retry_after`（秒）時以它為準——FinMind 的 403 body 中即帶此欄位。
- 未提供時採可設定的預設冷卻時間，數值取保守值（寧可多等一段，也不要在冷卻未結束時再撞一次封鎖），由設定決定，不寫死。

#### 每一檔的處理順序

1. 取順位中第一個可用來源，請求該檔的目標區間。
2. **成功** → 正規化、UPSERT 寫入、更新該檔進度為 `DONE`，並在 `stock_daily_price.source` 記下**實際取得該列的來源**（`YAHOO` / `FINMIND` / `TWSE`）。
3. **封鎖類回應** → 將該來源標記為不可用（設定到期時間），**不動這一檔的進度、不累加 `attempt_count`**，改以順位中下一個可用來源重試**同一檔**。
4. **所有來源皆不可用** → 此時才是真正無來源可用。該檔維持或回到 `PENDING`，**不標記 `FAILED`、不累加 `attempt_count`**，並結束本批作業——繼續往下跑只會對剩下的每一檔重複同一件事。作業回報為正常結束（非錯誤），剩餘標的留給下一次 `catchUp`／`resume` 接手。
5. **非封鎖類的失敗**（逾時、格式錯誤、5xx）→ 依既有規則退避重試；重試耗盡才把該檔標記 `FAILED` 並累加 `attempt_count`。

#### 並行執行下的封鎖處置

上述順序是**單一標的**的處理規則；並行只是同時有多檔各自走這條規則，判定本身完全不變。三件事必須明確：

- **來源的封鎖狀態是全批共用的單一狀態**，不是每個工作者各持一份。任何一檔收到封鎖類回應就立刻把該來源標記為不可用，其餘工作者取下一檔時即看得到，不會出現「8 個工作者各撞一次 403 才停」的情形。被封鎖期間繼續請求會延長封鎖，這正是並行最容易放大的錯誤。
- **「所有來源皆不可用」時結束本批作業，指的是不再領取新的標的**，而不是中斷正在路上的請求。已在處理中的標的照常跑完（成功就寫入並記 `DONE`，失敗依既有規則處置），全部收斂後作業才回報正常結束。強行取消進行中的請求，換來的是一批狀態說不清楚的標的，而它們本來多半會成功。
- **每來源的請求間隔在並行下仍是全批共用的節流**：8 個工作者不代表同一來源可以同時發 8 個請求，而是它們競爭同一條「每 0.5 秒放行一次」的通道。間隔若做成每個工作者各自計時，實際請求密度會變成設定值的 8 倍，而且從設定上完全看不出來——這是並行化這條路徑唯一會把系統推向封鎖的實作錯誤。

#### 封鎖絕不消耗 `attempt_count`

這是本機制的關鍵約束。`attempt_count` 的用途是「避免單一標的無限重試卡住整批作業」（見 `specs/dba/stock-sync-progress.md`），它衡量的是**這一檔本身有問題**。來源被封鎖與這一檔是哪一檔完全無關，把它算進去會讓一次封鎖在 `max-attempt-count` 次啟動之後把**全市場**標的一起推過重試上限，屆時非人工重置無法恢復。2026-09-06 的事故正是這個形狀：630 檔 `FAILED`、`attempt_count` 全部為 `1`、`last_error` 全部是 `HTTP 403`——沒有任何一檔是真的有問題。

#### 404 的處置

因上述歧義，單一來源的 `404` 不構成結論：

- 收到 `404` → **改用順位中下一個來源重試同一檔**。
- 下一個來源取得資料 → 照常寫入。這同時暴露了後綴組錯之類的問題，因為資料明明存在。
- **所有來源都表示查無此標的** → 此時才標記 `SKIPPED`。單一來源說「找不到」不足以構成永久跳過的結論。

#### 事故善後不需要額外機制

封鎖留下的 `FAILED` 殘留列**不需要**一次性的資料修補，也不需要新的清理端點：`catchUp` 已經會依 `last_synced_date` 與 `endDate` 的差距重新開啟落後的列並把 `attempt_count` 歸零（見下方「`catchUp` 的語意」），`last_synced_date` 為 `NULL` 與落後於 `endDate` 兩種情形都涵蓋，`FAILED` 列也在其中。**下一次啟動補齊即自動恢復。** 不新增端點、不寫一次性 DML migration。

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
- **價格在兩個來源之間是一致的，來源切換不會造成價格斷層。** 以 2330 於 2026-01-01～2026-09-06 逐日比對，Yahoo 與 FinMind 的共同 163 個交易日中，開高低收**不一致 0 天**。覆蓋度 Yahoo 略優（Yahoo 有 `2026-07-10` 而 FinMind 缺該日，反向缺漏為 0）。
- **成交量的口徑隨來源而異，且無法互相換算。** 同一組比對中，成交量 163 天**全部不同**，Yahoo 系統性偏低，量比中位數 `0.864`（範圍 `0.54`～`0.95`），推測不含盤後定價、零股與鉅額交易。改以 Yahoo 為主來源後，畫面上的成交量會較交易所口徑低約 14%。此差異**可接受**，因為成交量不參與任何運算——MACD／KD 只讀價格，型態掃描（`specs/backend/strategy-scan.md`）與產業別漲幅排行（`specs/backend/industry-gain-ranking.md`）也不讀量——它只出現在顯示欄位（`latestVolume` / `totalVolume` / `avgVolume` 與日 K 圖的量副圖）。但它**必須被明講**：日後有人拿本系統的量去對交易所的量，會以為是 bug。
- **成交金額與成交筆數是選填的。** Yahoo 只提供 OHLCV，不提供這兩項；由它取得的列，`turnover` 與 `transaction_count` 寫入 `0`。這是**刻意接受的取捨，不是遺漏**——兩欄未被任何 API 契約輸出（對外只暴露成交量），且既有來源的列本來就有一部分為 `0`。交易所快照與 FinMind 仍照常寫入實際值。

### 寫入語意

- 一律使用 UPSERT。重跑任一日或任一檔必須為冪等操作，且能修正資料源事後更正的值。
- 單檔的一次回補應在一個交易邊界內完成價格寫入與該檔進度更新，避免「資料已寫入但進度未更新」導致重跑時重複請求外部 API。

### 批次目標選擇

回補作業的標的清單由請求參數決定：

- 提供 `stockIds` 陣列 → 僅處理清單內的股票（多選）。此模式**不套用普通股篩選**：使用者指名的代號一律照跑，即使是 ETF 或特別股——他指名了，就是他要的。
- 省略 `stockIds` 或傳空陣列 → 全跑，母體為 `stock` 表中 `is_active = 1` **且通過普通股篩選**的股票。

兩者共用同一套進度追蹤、速率控制與重試邏輯。

#### 全跑母體預設只含上市普通股

普通股的判定沿用 `specs/backend/stock-universe-import.md` 的同一條規則：**代號恰為 4 個數字字元、且首字元不為 `0`**。`0050`／`00878`（ETF）、`2881A`（特別股）、`910322`（TDR）、`01004T`（REIT）皆不在母體內。這與 `specs/backend/strategy-scan.md`、`specs/backend/industry-gain-ranking.md` 的 `commonStocksOnly` 是同一條判定，必須共用同一份實作，不得出現第二套代號篩選邏輯。

由請求的 `commonStocksOnly` 覆寫：省略時視為 `true`；傳 `false` 時母體為 `is_active = 1` 的全部股票，即本參數加入前的行為。

**為什麼預設是「只跑普通股」**：`stock` 主檔的內容不等於「值得同步的標的」。主檔由每日全市場快照順帶維護（見「啟動時同步股票主檔」），而快照涵蓋交易所當日所有有成交的商品，因此 ETF、特別股、TDR 都會被寫進主檔——目前約 1,378 檔中有約 350 檔屬於此類。但系統中真正讀日線的三個功能（型態掃描、產業別漲幅、MACD／KD 指標）母體都已經是普通股，那 350 檔補回來的行情沒有任何一個功能會讀。花整批作業四分之一的時間抓一份沒有讀者的資料，是這條路徑最直接的浪費，也是使用者反映「同步太久」時真正被浪費掉的那一段。

**本參數只縮小抓取母體，不刪除也不改動任何既有資料。** 非普通股在 `stock` 的列不動（`is_active` 不變，總覽頁的「共 N 檔」因此不受影響），其已寫入的 `stock_daily_price` 列也不刪——只是不再被同步推進。要補某一檔非普通股時，以 `stockIds` 指名即可。

**目標清單為空是合法情形，不是錯誤。** 全市場模式在 `stock` 表尚無任何 `is_active = 1` 的股票時（例如剛建好結構、種子資料尚未匯入），或篩選後一檔普通股都沒有時，解析出來的目標清單為空。此時必須以 `targetCount: 0` 正常受理並立即結束，不得拋出例外、不得讓啟動補齊失敗，也不得對外部資料源發出任何請求。

這一點要特別寫明，是因為空清單很容易在「以清單為條件的查詢」上炸掉——例如把空集合展開成 `IN` 而產生語法不完整的 SQL。凡是以目標清單為輸入的查詢，都必須在清單為空時直接略過查詢並回傳空結果，而不是把空集合交給資料庫。

### 斷點續傳

- 批次啟動時，為目標標的在 `stock_sync_progress` 以 `job_type = 'PRICE_BACKFILL'` UPSERT 建立 `PENDING` 列。
- 逐檔處理，狀態依 `specs/dba/stock-sync-progress.md` 的進度語意流轉。
- 續傳：重新呼叫回補端點並帶 `resume = true` 時，只取 `status IN ('PENDING','FAILED')` 且 `attempt_count` 未達上限的標的，並從各檔的 `last_synced_date` 之後接續。
- 目標區間內查無任何交易資料的標的標記為 `SKIPPED`，不再重試；**但單一來源回報查無資料不足以構成 `SKIPPED`**，須所有來源皆如此（見「來源選擇與封鎖切換」的 404 處置）。
- **來源封鎖造成的作業中止不改變任何標的的 `status`，也不累加 `attempt_count`**，因此不需要續傳以外的任何補救動作。

### 啟動時同步股票主檔（universe）

系統啟動完成後，後端先自動以**每日全市場快照**（`STOCK_DAY_ALL`，單一請求即回傳全體上市股票的代號與名稱）UPSERT `stock` 主檔，再進行下方的日線補齊。沒有這一步，一個剛建好的資料庫的 universe 就只剩 `specs/dba/stock.md` 的 V007 開發種子（34 檔）——而那份種子在它自己的 spec 裡就寫明「是開發樣本，不是完整上市清單」，全市場 universe 應由本模組的抓取流程補齊。

**這一步是必要的，因為啟動補齊只列舉標的、不新增標的。** 下方「啟動時自動補齊」以 `stockIds` 省略呼叫回補路徑，其目標清單解析為 `stock.is_active = 1` 的既有列；它會把已在主檔裡的每一檔補到今日，但永遠不會讓主檔多出一檔。兩者的分工必須寫明：**快照負責「有哪些股票」，回補負責「每檔有哪些日線」**。少了前者，後者補得再完整，使用者在總覽頁看到的仍然只有那 34 檔。

行為規範（與啟動補齊相同的四條約束，理由亦同，不重複論證）：

- **重用既有每日增量路徑，不另寫一套。** 等同於以 `tradeDate` 省略呼叫每日增量同步服務，其 `stockMasterUpserted` 即本步驟的成果。正規化、UPSERT 冪等性、字元集處理全部沿用。
- **絕不阻塞啟動、絕不因抓取失敗而讓啟動失敗。** 單一請求雖快，仍可能逾時或回傳格式改變；失敗一律記錄錯誤後繼續，應用程式必須正常啟動，且**後續的日線補齊照常進行**——主檔同步失敗只代表 universe 沒更新，不代表既有標的的行情不該補。
- **可停用**，與啟動補齊各自獨立開關。
- **順序固定：主檔同步在前，日線補齊在後。** 反過來會讓本次啟動新增進來的股票要等到下一次重啟才補得到行情。主檔同步是單一請求、耗時以秒計，讓補齊等它完成不違反「絕不阻塞啟動」——真正不能同步等待的是那條長達數十分鐘的逐檔補齊。

**只涵蓋上市（`TSE`）。** 本步驟的資料源是證交所端點，回傳的即是全體上市股票；上櫃（`OTC`）需另打櫃買中心對應端點，不在本次範圍內，其 universe 仍維持既有的補齊方式。

新增設定項：

| 設定 | 預設 | 說明 |
|---|---|---|
| 啟動主檔同步開關 | 啟用 | 關閉時完全不觸發，啟動流程與日線補齊皆不受影響 |

### 啟動時自動補齊

系統啟動完成後，後端自動把 `stock` 表中 `is_active = 1` 的全部**上市普通股**的日線補齊至**今日**，不需要任何人手動打端點。這讓一個剛 `/reset-env` 過、只有股票主檔而沒有任何行情的資料庫，在開機後自行變成可用狀態。

行為規範：

- **重用既有回補路徑，不另寫一套。** 啟動補齊等同於以 `stockIds` 省略（全市場，`commonStocksOnly` 取預設 `true`，故母體為普通股）、`startDate` 取設定值、`endDate` 取今日、`catchUp: true` 呼叫回補服務。速率控制、重試退避、進度追蹤、UPSERT 冪等性全部沿用，不得複製一份平行實作。
- **絕不阻塞啟動。** 補齊在背景非同步執行，應用程式必須在補齊開始後立刻進入可服務狀態。全市場約 1,030 檔普通股在預設 8 並行、每來源間隔 0.5 秒下仍需數分鐘；若同步等待，服務在這段時間內等同不可用。
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
  "commonStocksOnly": true,
  "startDate": "2025-09-01",
  "endDate": "2026-08-27",
  "resume": false,
  "catchUp": false
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `stockIds` | string[] | 否 | 指定標的；**省略或空陣列代表全市場**（`stock.is_active = 1` 且通過普通股篩選） |
| `commonStocksOnly` | boolean | 否 | 預設 `true`。全市場模式的母體是否只含普通股（代號恰 4 位數字、首字非 `0`）；`false` 則為 `is_active = 1` 的全部股票。**提供 `stockIds` 時本參數不生效**——指名的標的一律照跑 |
| `startDate` | date | 是 | 回補起日。須早於實際需要的統計起日至少 250 個交易日（暖身需求，見指標 spec） |
| `endDate` | date | 是 | 回補迄日 |
| `resume` | boolean | 否 | 預設 `false`（重置進度重跑）；`true` 表示只處理未完成與失敗的標的 |
| `catchUp` | boolean | 否 | 預設 `false`。`true` 表示「補到 `endDate` 為止」語意：逐檔以 `last_synced_date` 之後接續，已補到 `endDate` 的檔完全跳過、不發外部請求。詳見下方「`catchUp` 的語意」 |

`resume` 與 `catchUp` 不可同時為 `true` → `400`，`{"code":"INVALID_SYNC_MODE"}`。兩者都在描述「不要重跑已完成的部分」，但判斷依據不同（`resume` 看 `status`，`catchUp` 看 `last_synced_date`），同時給定會產生無法一眼判讀的組合語意。

Response `202`（非同步作業，立即回應）：
```json
{
  "jobType": "PRICE_BACKFILL",
  "targetCount": 1032,
  "caughtUpCount": 1032,
  "commonStocksOnly": true,
  "startDate": "2025-09-01",
  "endDate": "2026-08-27",
  "mode": "ALL"
}
```
`mode` 為 `SELECTED` 或 `ALL`，依 `stockIds` 是否提供而定。

`commonStocksOnly` 照實回傳本次實際採用的值（`SELECTED` 模式一律為 `false`，因為該模式不套用篩選）。它存在的理由是 `targetCount` 會小於總覽頁顯示的「共 N 檔」，而兩個數字擺在同一個畫面上時，使用者無從判斷少掉的那幾百檔是被刻意排除、還是漏掉了；呼叫端要能據此在摘要中說清楚母體是什麼（見 `specs/frontend/strategy.md` 的「同步日 K 至今日」）。

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
  "total": 1032,
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
      （429 的處置已由 Increment 7 的「來源選擇與封鎖切換」取代：429 現屬**封鎖類回應**，改為標記該來源不可用並切換至下一個來源，而非對同一來源退避重試。間隔控制不變；指數退避重試現適用於逾時與 5xx 等非封鎖類失敗。）
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

---

- [x] 對只有 V007 種子（34 檔）的資料庫啟動應用程式，不經任何手動呼叫，`stock` 的 `market = 'TSE'` 且 `is_active = 1` 的列數增加到當前上市家數（千餘檔，非 34），且新增股票的中文名稱正確非亂碼
- [x] 主檔同步在日線補齊之前完成：本次啟動新增進來的股票，其行情由**同一次**啟動的補齊流程處理，不需要再重啟一次
- [x] 主檔同步失敗（外部資料源離線或逾時）時，應用程式仍正常啟動，且日線補齊照常對既有標的執行
- [x] 關閉啟動主檔同步設定後重啟，`stock` 列數完全不變，且該步驟不發出任何外部請求；此時日線補齊仍照常執行
- [x] 連續重啟兩次，第二次的 `stock` 列數與名稱與第一次相同（UPSERT 冪等，不產生重複股票）
- [x] 主檔同步只寫入 `market = 'TSE'` 的列，不因本步驟產生任何 `OTC` 列
- [x] 既有 34 檔種子股票在同步後仍存在，其 `stock_id` 未變動、名稱為資料源的最新值

---

### 多來源與封鎖切換（本次新增）

- [x] 逐檔歷史預設走 Yahoo：一次全新回補後，`stock_daily_price` 中該批次寫入的列其 `source` 為 `YAHOO`
- [x] Yahoo 回應的 `quote` 與 `adjclose` 並存時取 `quote`：對一檔在區間內有除權息的股票，寫入值等於未還原的原始成交價，不等於 `adjclose`
- [x] 上櫃股票以 `.TWO`、上市股票以 `.TW` 組代號請求，依 `stock.market` 決定；以一檔 `OTC` 股票驗證能取得資料
- [x] 逐檔歷史請求區間早於今日 30 天時仍正常取得資料（`interval=1d` 無分 K 的 30 天限制），不產生任何超窗判定
- [x] 主來源回 `403`／`429` 或 body 指出封鎖時，該檔**改由 FinMind 取得並成功寫入**，該列 `source` 為 `FINMIND`，且該檔進度為 `DONE`
- [x] 承上，該檔的 `attempt_count` 維持不變（不因來源封鎖而累加），`status` 不曾進入 `FAILED`
- [x] 主來源被判定封鎖後，同一批次的**後續標的不再對該來源發出任何請求**（以請求數斷言，非以耗時推測）直到封鎖到期
- [x] 回應帶 `retry_after` 時以其值為封鎖到期依據；未帶時採設定的預設冷卻時間，兩者皆不寫死在程式中
- [x] 封鎖到期後的下一檔重新從順位最前的來源（Yahoo）開始嘗試，無需重啟或人工介入
- [x] **兩個來源同時不可用**時：本批作業正常結束（非拋出例外），剩餘標的維持 `PENDING`，無任何標的被標記 `FAILED`，且 `attempt_count` 全部未累加
- [x] 承上情境後緊接著執行一次啟動補齊，先前未處理的標的照常被接續處理完成
- [x] 主來源回 `404` 時改由備援來源重試同一檔；備援取得資料則照常寫入，該檔**不得**被標記 `SKIPPED`
- [x] 僅當**所有**來源都回報查無此標的時，該檔才標記 `SKIPPED`
- [x] Yahoo 來源寫入的列，`turnover` 與 `transaction_count` 為 `0`，且 `open`/`high`/`low`/`close`/`volume` 皆為有效值；此情形不觸發任何驗證錯誤
- [x] 同一檔同一區間分別由 Yahoo 與 FinMind 各回補一次，`stock_daily_price` 列數不變（跨來源仍冪等），且 OHLC 相同
- [x] 兩個來源各自獨立計算請求間隔與退避：其中一個進入封鎖冷卻，不影響另一個的請求節奏
- [x] FinMind token 未設定時，啟動階段留下明確警告紀錄；token 由部署環境注入，不寫死於版控中的設定檔
- [x] 既有的 `FAILED` 殘留列在下一次啟動補齊時由 `catchUp` 自動重新開啟並將 `attempt_count` 歸零，全程不需要任何一次性 DML migration 或新增清理端點
- [x] `stock_daily_price.source` 寫入 `YAHOO` 不需要任何 schema 變更（欄位為 `VARCHAR(20)` 且無 CHECK 約束，見 `specs/dba/stock-daily-price.md`）；本次不產生任何 DBA migration
- [x] 每日增量（交易所全市場快照）與分 K（Yahoo `interval=1m`）的行為完全未改變

---

### 母體預設普通股與並行抓取（本次新增）

- [x] `POST /api/stocks/sync/backfill` 省略 `stockIds` 且省略 `commonStocksOnly` 時，`targetCount` 等於 `stock` 中 `is_active = 1` 且代號恰 4 位數字、首字非 `0` 的檔數，明顯小於 `is_active = 1` 的總檔數
- [x] 同上情境下，`0050`、`00878`、`2881A`、`910322` 完全不出現在本批 `PRICE_BACKFILL` 的 `stock_sync_progress` 列中，且該批作業對這些代號不發出任何外部請求
- [x] 帶 `commonStocksOnly: false` 時母體回到 `is_active = 1` 的全部股票，`targetCount` 等於加入本參數前的值
- [x] 帶 `stockIds: ["0050"]` 時該檔照常回補完成，不因普通股篩選被略過（指名優先於預設母體），且回應的 `commonStocksOnly` 為 `false`
- [x] `202` 回應含 `commonStocksOnly`，其值為本次實際採用的母體規則
- [x] 普通股判定與 `specs/backend/stock-universe-import.md`、`specs/backend/strategy-scan.md` 共用同一份實作，程式中不存在第二套代號篩選邏輯
- [x] 本次變更不寫入、不刪除任何資料：一次預設母體的全跑前後，`stock` 的列數與各列 `is_active` 完全不變，既有非普通股的 `stock_daily_price` 列數亦不變
- [x] 啟動補齊的母體同樣預設只含普通股：從空進度啟動一次，`job_type = 'PRICE_BACKFILL'` 的進度列不含任何非普通股代號
- [x] 逐檔抓取為並行執行：一次全跑中同時在處理的標的數達到設定的並行上限（預設 8），以進度列的 `RUNNING` 檔數或請求時間重疊斷言，非以總耗時推測
- [x] 同一來源的相鄰兩次請求間隔不小於設定值（預設 0.5 秒）：節流為全批共用，不因並行度而縮成設定值的 1/8
- [x] 兩個來源各自計算間隔：其中一個進入冷卻時，另一個的請求節奏不受影響（並行下仍成立）
- [x] 並行度與間隔皆取自設定，預設 8 與 0.5 秒，調整設定即生效，程式中無寫死的數值
- [x] 任一來源被判定封鎖後，其餘工作者取下一檔時即不再對該來源發出請求（封鎖狀態為全批共用，非每工作者一份）
- [x] 兩個來源同時不可用時停止領取新標的，但已在處理中的標的照常收斂完成，作業回報為正常結束，無任何標的因此被標記 `FAILED`、無任何 `attempt_count` 被累加
- [x] 並行不改變冪等性：同一批全跑連續執行兩次，`stock_daily_price` 列數不變
- [x] 並行不改變單檔語意：`catchUp` 的跳過條件、`last_synced_date` 的認定、`attempt_count` 的累加規則與序列執行時完全相同
- [ ] 千檔規模的全跑實測總耗時較序列每檔 1 秒的版本明顯縮短，且全程未發生 `403`／`429` 封鎖

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

### Increment 6 — 2026-09-01
- Status: DONE
- Scope: the 7 trailing unchecked Acceptance Criteria — 啟動時同步股票主檔 (startup master sync).
- **Implemented exactly per the spec's own wording, not the sibling `stock-universe-import.md`'s filtering rules.** The Requirements prose for this section (`啟動時同步股票主檔（universe）`) explicitly says "重用既有每日增量路徑，不另寫一套。等同於以 `tradeDate` 省略呼叫每日增量同步服務" — i.e. reuse the *existing, already-implemented* `POST /api/stocks/sync/daily` code path (`StockSyncService.syncDaily()`), not the sibling spec's ordinary-shares-only/no-price-write import logic. That sibling spec (`stock-universe-import.md`) is a separate, still-pending endpoint with materially different semantics (filters to 4-digit ordinary shares only, never touches `is_active` on existing rows, writes no price data, 502s on an empty response) — deliberately **not** factored together with this step, since this spec does not ask for that behavior. No new fetch/parse/upsert logic was written; `syncDaily()` and its existing `PriceIngestionService.applyDailySnapshot` were reused byte-for-byte.
- Files changed:
  - `develop/backend/src/main/java/com/stock/config/MasterSyncProperties.java` — new; `@ConfigurationProperties(prefix = "app.master-sync")`, nested `Startup.enabled` (default `true`), independent from `BackfillProperties.StartupCatchUp` per the spec's "可停用，與啟動補齊各自獨立開關".
  - `develop/backend/src/main/java/com/stock/service/StartupCatchUpRunner.java` — `onApplicationReady()` now runs a new private `syncStockMaster()` step **synchronously, first**, before the existing price-catch-up step: checks `masterSyncProperties.getStartup().isEnabled()` (logs and returns if disabled, issuing zero external requests), otherwise calls `stockSyncService.syncDaily()` inside a try/catch that only logs on failure (never rethrows), so a master-sync failure can never abort startup or skip the price catch-up that follows. Constructor gained a `MasterSyncProperties` parameter. Class Javadoc rewritten to describe both steps, their fixed order, and why running master sync synchronously here does not violate "絕不阻塞啟動" (single request, seconds not minutes).
  - `develop/backend/src/main/resources/application.yml` — added `app.master-sync.startup.enabled: true`.
  - `develop/backend/src/test/resources/application.yml` — added `app.master-sync.startup.enabled: false` (test profile must never call the real TWSE endpoint on boot, mirroring the existing `startup-catch-up.enabled: false`).
  - `develop/backend/src/test/java/com/stock/service/StartupCatchUpRunnerTest.java` — updated the 4-arg → 5-arg `StartupCatchUpRunner` construction (added `MasterSyncProperties`, defaulted to disabled in `setUp()` so the pre-existing price-catch-up/rebuild tests are unaffected by the new step); added 4 new tests (see below).
- **Ordering guarantee**: master sync and the price catch-up's target-list resolution (`stock.is_active = 1`) both happen on the same thread, in the same method, with no `@Async` boundary between them — `syncStockMaster()` returns before `stockMapper.findActiveStockIds()` is ever called (inside `StockSyncService.prepare`), so a stock newly written by master sync is guaranteed to already be in `stock` when the catch-up batch's target list is built, without needing any explicit synchronization.
- **Failure isolation**: `syncStockMaster()`'s try/catch is independent of the price-catch-up step's own try/catch further down the same method — a master-sync failure only prevents that step's own log line from saying "finished"; it does not touch the job lock, does not throw past its own method boundary, and the very next lines of `onApplicationReady()` (price catch-up) run unconditionally afterward.
- New tests (all in `StartupCatchUpRunnerTest`, pure Mockito unit tests, no Spring context / no DB):
  - `masterSync_runsBeforePriceCatchUp_inThatOrder` — `InOrder` verification that `stockSyncService.syncDaily()` is called before `stockSyncService.startBackfillWithLockAlreadyHeld(...)`.
  - `masterSync_disabled_skipsSyncDaily_butPriceCatchUpStillRuns` — master sync off, catch-up on: asserts zero calls to `syncDaily()` and exactly one call to the catch-up path.
  - `masterSync_syncDailyThrows_doesNotPropagate_andPriceCatchUpStillRuns` — `syncDaily()` throws; asserts `onApplicationReady()` does not propagate and the catch-up step still runs.
  - `masterSync_independentOfPriceCatchUpSwitch_stillRunsWhenCatchUpDisabled` — master sync on, catch-up off: asserts `syncDaily()` is still called exactly once while `startBackfillWithLockAlreadyHeld`/`indicatorRebuildService` see zero interactions, proving the two switches are genuinely independent in both directions.
- Verification performed:
  - `mvn -f develop/backend/pom.xml compile` — clean.
  - `mvn -f develop/backend/pom.xml test` — **134/134 passing** (130 pre-existing + 4 new, all in `StartupCatchUpRunnerTest`), run twice consecutively with no flakiness.
  - **Live verification against the real TWSE endpoint and the real DB**, starting from the exact scenario the first three unchecked criteria describe — the live DB held precisely the 34 V007 seed rows and nothing else (`SELECT market, is_active, COUNT(*) FROM stock GROUP BY market, is_active` → `TSE / 1 / 34`) before any of the following runs:
    - **Run 1** (`mvn spring-boot:run`, both switches at their real defaults — enabled): log showed `Started BackendApplication in 0.828 seconds` (Tomcat already listening) immediately followed by `Starting startup stock master sync...` → `Startup stock master sync finished: 1365 stocks upserted into the stock master.` (~1.1s later) → `Starting startup price catch-up for all active stocks...` — confirming the fixed order and that master sync does not block the app from becoming ready to serve HTTP (Tomcat was already up before master sync even started). `SELECT market, is_active, COUNT(*) FROM stock` afterward → `TSE / 1 / 1365` (up from 34, all `TSE`, zero `OTC` rows). Chinese names verified non-mojibake with `mysql --default-character-set=utf8mb4`: `2330 → 台積電`, `1101 → 台泥`, and independently via `HEX(stock_name)` for `2330` → `E58FB0E7A98DE99BBB`, the correct UTF-8 bytes for `台積電`. All 34 original seed `stock_id`s (`1101,1102,1216,...,6669`) individually re-queried and confirmed still present, `stock_id` unchanged, `market='TSE'`, `is_active=1`, names updated to the source's current values (e.g. `2330 → 台積電`, matching the source, not the seed's possibly-stale name). ~60s after startup, `stock_sync_progress` for `PRICE_BACKFILL` already showed `DONE=41` (more than the original 34, proving newly-added stocks were already being processed by the *same* startup's catch-up, not deferred to a next restart) with `PENDING=1324` still running in the background; `stock_daily_price` had grown from 5372 to 12231 rows in that same window. Process killed once this was confirmed (full ~1300-stock backfill takes well over 30 minutes and was not necessary to prove the criterion).
    - **Run 2** (`APP_MASTER_SYNC_STARTUP_ENABLED=false`, catch-up left at its default enabled): log showed `Startup stock master sync disabled (app.master-sync.startup.enabled=false); skipping.` immediately followed by `Starting startup price catch-up for all active stocks...` — confirming the switch works via env-var override and that price catch-up runs normally when master sync is off. `stock` row count confirmed unchanged at `1365` afterward (no external request issued for master sync — no corresponding TWSE call appears in the log for this run).
    - **Run 3 and Run 4** (`APP_BACKFILL_STARTUP_CATCH_UP_ENABLED=false` to keep each restart fast; master sync left at its default enabled), run back-to-back: both logged `Startup stock master sync finished: 1365 stocks upserted into the stock master.` (same count both times), and `SELECT MD5(GROUP_CONCAT(stock_id, ':', stock_name ORDER BY stock_id)) FROM stock` computed **byte-identical** (`bfab149e98dba367e7d3e5888649628c`) after Run 1, Run 3, and Run 4 — proving the UPSERT is idempotent across repeated restarts and produces no duplicate rows and no name drift.
    - **Run 5** (`APP_EXTERNAL_TWSE_DAILY_ALL_URL` pointed at an unresolvable host, catch-up left enabled): log showed `Startup stock master sync failed; the stock master was not updated this run. The price catch-up step still runs normally for existing stocks.` with the full `ExternalApiException` / `UnknownHostException` stack trace attached, immediately followed by `Starting startup price catch-up for all active stocks...`. Confirmed via `curl` that `GET /api/stocks?page=1&size=5` returned `HTTP 200` (application fully serving traffic despite the master-sync failure), `stock` row count unchanged at `1365` (no partial write), and `stock_sync_progress` showed `DONE` counts climbing (`67` and rising) proving the price catch-up ran normally against the existing stocks despite master sync having failed.
    - Re-ran the full `mvn test` suite once more after all five live runs above (against the now-realistic, much larger live DB: 1365 stocks, 12k+ price rows) to confirm nothing in the existing suite implicitly depended on the old 34-row baseline — **134/134 still passing**.
    - Confirmed no stray `mvn`/`BackendApplication` process was left running and port 8080 was free before finishing (`ps aux` / `lsof -i :8080` both empty).
  - Self-reviewed via the `code-quality` skill: confirmed `syncStockMaster()`'s catch block logs with the exception attached (`log.warn(msg, e)`, not swallowed), confirmed no new resource-lifecycle or atomicity concerns were introduced (the single write path, `PriceIngestionService.applyDailySnapshot`, was already `@Transactional` and untouched), confirmed the ordering guarantee needs no explicit synchronization primitive because both steps run on the same thread with no `@Async` boundary between them, and confirmed the new config class carries only the one field the spec actually asks for (no speculative `startDate`/`rateLimit` fields copied over from `BackfillProperties.StartupCatchUp` for symmetry's sake). No issues required fixing as a result of this review.
- Not implemented / explicitly out of scope: no changes were made toward `specs/backend/stock-universe-import.md` itself (its own `POST /api/stocks/universe/import` endpoint, ordinary-share filtering, and `502` error contract) — that spec is still `pending` and is a separate execution. The `status:` frontmatter is left untouched per instructions.

### Increment 7 — 2026-09-06
- Status: DONE
- Scope: the 20 trailing unchecked Acceptance Criteria under 多來源與封鎖切換（本次新增） — the Yahoo-primary/FinMind-fallback dual-source pipeline with independent per-source block tracking. No other path was touched: the daily full-market snapshot, minute-bar ingestion, resume/catchUp semantics, caughtUpCount, timezone settings, and both startup-sync steps are unchanged (verified by the full pre-existing suite still passing, see below).
- Design: a `PriceHistorySource` interface (`getCode()`, `fetchDailyHistory(stockId, market, start, end)`) implemented by both `YahooFinanceClient` (priority) and `FinMindClient` (fallback). A new `PriceHistoryFetcher` walks them in a fixed `List.of(yahoo, finMind)` priority order per stock, consulting a new `SourceAvailabilityTracker` (in-memory, `ConcurrentHashMap<sourceCode, blockedUntilInstant>`) before ever calling a source. Three new exceptions in `service.external` carry the classification a source's HTTP call resolves to: `SourceBlockedException` (403/429/quota-body; carries a nullable `retryAfterSeconds`), `SymbolNotFoundException` (404, ambiguous), and the pre-existing `RateLimitedException` is narrowed to timeouts only (was previously also thrown for 429). A new `AllSourcesBlockedException` (raised by the fetcher, caught by `BackfillRunner`) is the "end this batch normally" signal. A shared `RetryAfterExtractor` (used by both clients) centralizes the block-response judgment (HTTP 403/429 or a quota-indicating body) and `retry_after` parsing (HTTP header first, then the JSON body field FinMind's actual incident response used) so the two clients cannot silently drift on what counts as "blocked".
- Files changed:
  - New: `service/external/PriceHistorySource.java`, `SourceBlockedException.java`, `SymbolNotFoundException.java`, `AllSourcesBlockedException.java`, `SourceAvailabilityTracker.java`, `PriceHistoryFetcher.java`, `RetryAfterExtractor.java`.
  - `service/external/FinMindClient.java` — implements `PriceHistorySource`; `fetchHistory`'s HTTP-error classification now checks `RetryAfterExtractor.isBlockedResponse` (403/429/quota-body) before falling through to 404/other; added `@PostConstruct warnIfTokenMissing()` logging a WARN when `app.external.finmind-token` is blank.
  - `service/external/YahooFinanceClient.java` — implements `PriceHistorySource` via a new `fetchDailyHistory(stockId, market, start, end)` method (interval=1d, no 30-day window check, reuses the existing `at()`/parallel-array pairing via a new shared `barAt`/`quoteOf` extraction that `fetchMinuteBars`'s `normalize()` was refactored to reuse too — same parsing, two call sites, no second parser). `buildSymbol(stockId, market)` extracted and reused by both `fetchMinuteBars` and `fetchDailyHistory`. `fetchMinuteBars` itself is otherwise byte-for-byte unchanged (same exception classes, same 429→RateLimitedException mapping, same 30-day-window contract).
  - `service/PriceIngestionService.java` — `applyBackfillResult` gained a `source` parameter (removed the hardcoded `SOURCE_FINMIND` constant); the daily-snapshot path (`applyDailySnapshot`, still `SOURCE_TWSE`) is untouched.
  - `service/BackfillRunner.java` — rewritten to call `PriceHistoryFetcher` instead of `FinMindClient` directly; added a `StockMapper` dependency to resolve each stock's `market` for Yahoo's suffix; `processOne` now catches `AllSourcesBlockedException` (reverts the stock to PENDING via a new `markPending`, then rethrows) and `SymbolNotFoundException` (marks SKIPPED via the existing `markSkippedThrough`); `run()`'s per-stock loop specially catches `AllSourcesBlockedException` to `break` the whole batch as a normal completion (no exception surfaces past `run()`), logged at INFO, distinct from the pre-existing generic `catch (Throwable)` → FAILED path used for every other failure mode.
  - `mapper/StockSyncProgressMapper.java` + `.xml` — new `markPending` (reverts RUNNING→PENDING without touching attempt_count/last_error/last_synced_date).
  - `config/BackfillProperties.java` — new `RateLimit.defaultBlockCooldownSeconds` (default 600), used only when a block response doesn't carry its own retry_after.
  - `application.yml` / test `application.yml` — added `app.backfill.rate-limit.default-block-cooldown-seconds` (600 prod, 1 test, so block-recovery tests don't wait 10 minutes).
- No DBA migration was created or needed — `stock_daily_price.source` is already VARCHAR(20) with no CHECK constraint (confirmed by writing YAHOO-sourced rows successfully in every new test). No cleanup endpoint or one-off DML was added for the 2026-09-06 incident's residual FAILED rows — catchUp's existing `findCaughtUpStockIds`/`upsertPendingForCatchUp` (Increment 2) already reopens any row whose `last_synced_date` is NULL or behind endDate, FAILED included, and resets attempt_count to 0; a new test (`preExistingFailedRow_fromBlockIncident_reopenedByCatchUp_...`) seeds a row in the exact incident shape (FAILED, attempt_count=1, last_error='HTTP 403', last_synced_date=NULL) and confirms catchUp: true reopens and completes it with attempt_count back to 0.
- New tests:
  - `service/external/PriceHistoryFetcherTest.java` (11 tests, pure Mockito, no Spring/DB/HTTP) — the priority/block/404/timeout orchestration in isolation, with every "no request issued" claim asserted by Mockito verify(..., times/never()) call counts, never elapsed time.
  - `service/external/YahooFinanceClientDailyHistoryTest.java` (7 tests) and `service/external/FinMindClientTest.java` (8 tests) — standalone RestTemplate + MockRestServiceServer (no Spring context), covering quote-vs-adjclose, OTC/TSE suffix, the 30-day-window absence, zero-turnover/transaction-count validity, 403/404 classification, retry_after extraction from both the header and FinMind's actual incident body shape, and the token-missing WARN log (via a Logback ListAppender).
  - `StockPriceIngestionMultiSourceIntegrationTest.java` (9 tests, real DB + MockRestServiceServer) — end-to-end wiring: default-Yahoo routing with a >30-day-old range and Yahoo-row turnover/transaction_count/OHLCV DB assertions; OTC routing; block→fallback with attempt_count/status untouched; a same-batch second stock proving the blocked source gets zero further requests; a full block-lifecycle test (both sources blocked → batch ends with both stocks left PENDING, attempt_count untouched, no FAILED → sleep past both cooldowns (the default *and* an explicit retry_after) → a second resume call recovers both stocks via Yahoo again, unprompted); 404-ambiguity fallthrough (found by FinMind, not SKIPPED) and all-sources-404 (SKIPPED); cross-source idempotency (Yahoo then FinMind, same range, row count unchanged, OHLC identical); and the pre-existing-FAILED-row recovery test above.
  - Updated `StockPriceIngestionIntegrationTest.java`: every existing per-stock backfill test now registers a Yahoo 404 ahead of its existing FinMind mock, since Yahoo is now tried first; test stock ids are synthetic and genuinely don't exist on Yahoo, so this is the realistic 404-ambiguity path, not a workaround. Two tests' *trigger* changed, not their intent: `backfill_selectedMode_onlyProcessesGivenIds_retriesOn429_andIsIdempotent` → renamed `...retriesOnTimeout_andIsIdempotent`, and `progress_lastSyncedAt_unchanged_whenBackfillRunFailsCompletely`'s repeated-429 fixture → a repeated simulated timeout (IOException). This is a deliberate, spec-mandated behavior change, not a silent regression: 429 is now classified as a block-class response (spec: 封鎖類回應的判定 — HTTP 403 or 429) that switches source instead of retrying the same one, which is the entire point of this increment (this is exactly what caused the 2026-09-06 incident: continuing to hammer a 429-returning source). Only a timeout remains same-source-backoff-retryable, so that's what now exercises the "backoff retry, not immediate failure" behavior those two tests were written to prove; the block-switch behavior itself (what 429 now does) is covered by the new tests above.
- Verification performed:
  - `mvn -f develop/backend/pom.xml compile` — clean.
  - `mvn -f develop/backend/pom.xml test` — 279/279 passing, run twice consecutively with no flakiness. This includes the full pre-existing suite (MomentumGainIntegrationTest 42/42 with its pre-existing uncommitted changes left untouched, StockMinutePriceIntegrationTest 15/15, StockIndicatorStatisticsIntegrationTest, StrategyScanIntegrationTest, StockCatalogIntegrationTest/WriteIntegrationTest, StockUniverseImportIntegrationTest, StartupCatchUpRunnerTest, IndicatorRebuildServiceTest/IndicatorCalculationServiceTest, NormalizeUtilTest, BackendApplicationTests) — none of it needed logic changes, only the Yahoo-mock additions described above.
  - One manual live check (no other live calls were made — the automated suite is 100% mock-driven): curl (not through the app) against the real Yahoo endpoint for a genuine OTC stock, 6488.TWO with interval=1d, confirming HTTP 200 with real quote data (GlobalWafers Co., Ltd., exchangeName: TWO) — corroborating the mocked OTC-suffix test against the real external contract without spending any requests through the batch pipeline.
  - Self-reviewed via the code-quality skill: found and fixed one DRY violation (the block-response predicate was duplicated identically in FinMindClient and YahooFinanceClient; consolidated into `RetryAfterExtractor.isBlockedResponse`), fixed a couple of test-assertion style nits (`assertEquals(false, ...)` → `assertFalse`, `assertTrue(x.equals(y))` → `assertEquals`). No null-safety, resource-lifecycle, atomicity, or performance issues found: `market` is looked up defensively (`stock != null ? stock.getMarket() : null`, safe because `YahooFinanceClient.buildSymbol` treats null as non-OTC); the price-write + progress-update transaction boundary in `PriceIngestionService.applyBackfillResult` is unchanged; `SourceAvailabilityTracker` uses a ConcurrentHashMap and never sleeps, so a blocked source's cooldown cannot slow the other source's pacing (also proven negatively by PriceHistoryFetcherTest's zero-call-count assertions on the blocked source).
- Honest gaps / what was NOT independently timing-verified: criterion "兩個來源各自獨立計算請求間隔與退避" is verified structurally (no shared counters exist in the code, SourceAvailabilityTracker is keyed per source, and the per-stock rate-limit sleep in BackfillRunner is unconditional/source-independent) and via the zero-call-count unit test, but no separate wall-clock timing measurement was taken — consistent with this spec's own instruction that "後續標的不再對該來源發出任何請求" must be asserted by request count, not elapsed time, which by extension made a timing-based proof for the closely related "independent pacing" criterion a weaker, not a stronger, form of evidence here.

### Increment 8 — 2026-09-08

本次執行的是「回補母體預設只含上市普通股」與「逐檔並行抓取」兩項增量（Acceptance Criteria 449–465）。

**執行前的實際狀態**：commit `31a4c35`（訊息宣稱已完成本增量）經查只更動 `docs/` 與 `specs/`，`src/main/java` 沒有任何對應變更；`5fb36ab` 則確實完成了先前的雙來源備援增量。因此本增量在程式面是完全未實作的，本次為真正的首次實作。

**已完成（449–464）**：
- 母體篩選：`StockSyncService.prepare()` 於 ALL 模式套用 `CommonStockCodeUtil`，與 `stock-universe-import`／`strategy-scan` 共用同一份判定，程式中無第二套代號篩選邏輯。`commonStocksOnly: false` 可回到全部 `is_active = 1`；指名 `stockIds` 優先於預設母體並回報 `commonStocksOnly: false`。`BackfillResponse` 新增 `commonStocksOnly` 欄位。篩選本身不寫入亦不刪除任何資料（以 `verify(..., never())` 與 `verifyNoInteractions` 斷言）。啟動補齊沿用同一母體規則。
- 並行抓取：`BackfillRunner` 由序列迴圈改寫為 shared-cursor worker pool（`AtomicInteger` cursor + `AtomicBoolean` 停止旗標）。新增 `SourceRateLimiter`（共用 bean、per-source lock）確保節流為全批共用而非每工作者一份，兩來源各自獨立計算間隔。並行度與間隔皆取自 `BackfillProperties`（預設 8 與 500ms），測試以覆寫為 4／150ms 證明設定確實生效，程式中無寫死數值。封鎖狀態經 `SourceAvailabilityTracker` 全批共用；兩來源同時不可用時停止領取新標的，在途標的照常收斂，無 `FAILED`、無 `attempt_count` 累加。冪等性與單檔語意（`catchUp` 跳過條件、`last_synced_date` 認定、`attempt_count` 規則）未變——`processOne()` 邏輯逐字未動，並行只改變派工方式。

**未完成（465，deferred）**：千檔規模的實測總耗時與零 `403`／`429` 未以真實外部 API 驗證。本次全程未對 Yahoo／FinMind 發出任何大量真實請求（刻意避免觸發封鎖）。改以 mock 來源驗證其構成機制：並行確實達到設定上限（以 DB 中 `RUNNING` 檔數斷言，非以總耗時推測）、且並行下每來源間隔仍不小於設定值。這兩點正是該指標成立的前提，但實際生產規模的計時數字仍待日後真實環境觀測。

**驗證**：`mvn -f develop/backend/pom.xml test` — 291/291 通過，連續執行三次無 flakiness；`StockPriceIngestionConcurrencyIntegrationTest` 與 `SourceRateLimiterTest` 另單獨重跑五次皆綠。

**新增／變更檔案**：`AsyncConfig`（worker pool 化，另加單執行緒 dispatch executor 避免協調者佔用工作者名額）、`BackfillProperties`、`BackfillRequest`、`BackfillResponse`、`BackfillRunner`、`StockSyncService`、`PriceHistoryFetcher`、`SourceRateLimiter`（新）、`application.yml`；測試 `StockSyncServiceCommonStocksOnlyTest`（新）、`StockPriceIngestionConcurrencyIntegrationTest`（新）、`SourceRateLimiterTest`（新）、`PriceHistoryFetcherTest`、`StartupCatchUpRunnerTest`、`StockPriceIngestionIntegrationTest`（其 ALL 模式測試原先隱含依賴「無篩選」的舊預設，現明確帶 `commonStocksOnly: false`）。
