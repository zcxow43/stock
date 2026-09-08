# 策略型態掃描 API

本 API 讓使用者對已入庫的股票日線資料做「型態偵測」，勾選一種或多種型態（底底高、箱型突破、上漲支撐、反彈、累積上漲）搭配各自的判定參數，掃描指定股票或全市場普通股，找出在指定區間內符合型態條件的個股與最近一次命中日期。整個模組只讀日線行情、即時運算，不落地任何結果表，因為型態命中與否完全取決於呼叫端傳入的參數，同一批行情換一組門檻就是另一組答案。回應內容一律以「命中／訊號／型態」表述，不得使用暗示買進或推薦操作的措辭。掃描母體預設只涵蓋上市普通股，區間預設為近一個日曆月，兩者皆可由呼叫端覆寫。

## 欄位定義

**掃描請求 — 策略參數物件（`strategies[]`）**

| Field | Type/Role | Rule |
|---|---|---|
| `code` | 列舉（必填） | 五選一：`BOX_BREAKOUT`／`HIGHER_LOWS`／`RISING_SUPPORT`／`REBOUND`／`CUMULATIVE_RISE`；同一次請求內不得重複出現 |
| `preset` | 列舉（依型態而定） | 僅「有靈敏度」的三個型態（箱型突破、底底高、上漲支撐）必填，值為 `STRICT`／`STANDARD`／`LOOSE`；累積上漲與反彈皆無靈敏度，不得帶此欄位 |
| `days` | 整數（選填） | 僅累積上漲接受；回看窗口的交易日數，範圍 1～90，省略時預設 20；其餘型態帶此欄位視為不適用 |
| `requireRise` | 布林（選填） | 僅反彈接受；是否套用漲段（反彈）條件的開關，省略時預設 `true`；為 `false` 時代表只以跌幅判定 |
| `dropDays` | 整數（選填） | 僅反彈接受；跌段回看的交易日數（含谷底當日），範圍 1～90，省略時預設 3 |
| `dropPercent` | 數值（選填） | 僅反彈接受；自窗口最高收盤起算的跌幅門檻，單位 %，範圍 0～50、最多一位小數，省略時預設 10 |
| `riseDays` | 整數（選填） | 僅反彈於 `requireRise` 為 `true` 時接受；谷底之後容許反彈達標的交易日數（不含谷底當日），範圍 1～90，省略時預設 1 |
| `risePercent` | 數值（選填） | 對有靈敏度的三型態：覆寫該型態靈敏度表指定的漲幅／突破幅度門檻，省略則沿用靈敏度值；對累積上漲：窗口內累積漲幅門檻，省略時預設 15；對反彈：`requireRise` 為 `true` 時是自谷底收盤起算的反彈幅度門檻，省略時預設 5，`requireRise` 為 `false` 時不得帶；三種情境範圍皆為 0～50、最多一位小數 |

**掃描回應 — 策略結果物件（`results[]`）**

| Field | Type/Role | Rule |
|---|---|---|
| `strategy` | 列舉 | 該筆結果對應的型態代碼 |
| `preset` | 列舉（條件出現） | 僅有靈敏度的三型態回報，為實際採用的靈敏度；與下列反彈／累積上漲的參數群組互斥，不同時出現 |
| `days` | 整數（條件出現） | 僅累積上漲回報，為實際採用的回看天數 |
| `requireRise`／`dropDays`／`dropPercent`／`riseDays`／`risePercent` | （條件出現） | 僅反彈回報，皆為實際採用值；`riseDays`／`risePercent` 只在 `requireRise` 為 `true` 時一併回報 |
| `matchedCount` | 整數 | `items` 的命中檔數；不含 `insufficientData` 與 `pendingConfirm` 中的股票 |
| `items` | 陣列 | 命中清單，依 `signalDate` 由新到舊排序、同日依 `stockId` 升冪；每筆含 `stockId`、`stockName`、`signalDate`（該檔在區間內最近一次命中日）與 `detail` |
| `insufficientData` | 股票代號陣列 | 前置資料不足以完成判定的股票；不出現在 `items`，不計入 `matchedCount` |
| `pendingConfirm` | 股票代號陣列 | 已符合主要條件但確認資料尚未到齊、暫不計入命中的股票；僅箱型突破與上漲支撐可能非空，反彈與累積上漲恆為空陣列 |

**箱型突破 `BOX_BREAKOUT` — `detail`**

| Field | Type/Role | Rule |
|---|---|---|
| `boxHigh` | 數值 | 箱體上緣，即突破日之前 `lookback` 個交易日內最高價的最大值 |
| `boxLow` | 數值 | 箱體下緣，即同一區間內最低價的最小值 |
| `breakoutClose` | 數值 | 突破日（`signalDate`）收盤價 |
| `breakoutPercent` | 數值 | 突破日收盤相對箱體上緣的超出幅度（%） |
| `volumeRatio` | 數值 | 突破日成交量相對前 5 個交易日均量的倍數 |

**底底高 `HIGHER_LOWS` — `detail`**

| Field | Type/Role | Rule |
|---|---|---|
| `lows` | 陣列 | 構成本次命中的連續遞增擺動低點清單，筆數為 `requiredRises + 1` |
| `lows[].tradeDate` | 日期 | 該擺動低點所在交易日 |
| `lows[].ma5` | 數值（兩位小數） | 該日的 5 日收盤均線（MA5）值；擺動低點的判定與遞增幅度的比較皆以此值為準 |
| `lows[].low` | 數值（兩位小數） | 該日原始最低價，僅供對照顯示，不參與任何判定 |

**上漲支撐 `RISING_SUPPORT` — `detail`**

| Field | Type/Role | Rule |
|---|---|---|
| `supportClose` | 數值 | 支撐線，即上漲日前一交易日（D-1）的收盤價 |
| `riseClose` | 數值 | 上漲日 D（即 `signalDate`）的收盤價 |
| `risePercent` | 數值 | D 相對 D-1 的漲幅（%） |
| `priorHighClose` | 數值 | D 之前 `lookback` 個交易日收盤價的最大值，即被突破的近期高點 |
| `confirmCloses` | 陣列 | D+1、D+2 兩個交易日的收盤價，用以驗證支撐線是否守住 |
| `confirmCloses[].tradeDate`／`close` | 日期／數值 | 各確認日的交易日與收盤價 |

**反彈 `REBOUND` — `detail`**

| Field | Type/Role | Rule |
|---|---|---|
| `peakDate` | 日期 | 跌段回看窗口內收盤價最高的交易日 |
| `peakClose` | 數值 | 該日收盤價 |
| `troughDate` | 日期 | 谷底交易日 T |
| `troughClose` | 數值 | T 的收盤價 |
| `dropPercent` | 數值 | 自 `peakClose` 至 `troughClose` 的實際跌幅（%） |
| `risePercent` | 數值（`requireRise` 為 `false` 時不出現） | 自谷底收盤至反彈達標日（`signalDate`）收盤的實際反彈幅度（%） |

**累積上漲 `CUMULATIVE_RISE` — `detail`**

| Field | Type/Role | Rule |
|---|---|---|
| `troughDate` | 日期 | 回看窗口內收盤價最低的交易日 |
| `troughClose` | 數值 | 該日收盤價 |
| `peakClose` | 數值 | 判定日 D（即 `signalDate`）的收盤價，為窗口內的最高點 |
| `risePercent` | 數值 | 自 `troughClose` 至 `peakClose` 的實際累積漲幅（%） |

## 限制條件

**型態與參數的對應（互斥規則）**
- `preset` 僅箱型突破、底底高、上漲支撐三個「有靈敏度」型態可帶且必填；累積上漲與反彈帶了即視為不適用型態的參數。
- `days` 僅累積上漲接受，其餘型態帶此欄位視為不適用。
- `requireRise`／`dropDays`／`dropPercent`／`riseDays` 僅反彈接受，其餘型態帶了視為不適用。
- `risePercent` 三種語意互斥於不同型態群組：對有靈敏度型態是覆寫其漲幅／突破幅度門檻；對累積上漲是累積漲幅門檻；對反彈是反彈幅度門檻，且只在 `requireRise` 為 `true` 時可帶。
- 反彈的 `requireRise` 為 `false` 時不得再帶 `riseDays` 或 `risePercent`。

**底底高的判定基準**
- 擺動低點與遞增幅度一律在 **MA5（5 日收盤簡單移動平均）** 上判定，原始最低價不參與任何判定，只在 `detail.lows[].low` 一併回報供對照。
- 平滑天數固定為 5，不隨靈敏度改變，請求也無任何欄位可指定；靈敏度只決定 `swingBars`／`requiredRises`／`risePercent`。
- MA5 於掃描當下由已讀入的日線即時計算，不落地任何資料表欄位。

**數值範圍**
- `dropDays`、`riseDays`、`days` 皆為 1～90 的整數。
- `dropPercent`、`risePercent`（三種情境皆同）皆為 0～50、最多一位小數。
- 靈敏度三型態的 `risePercent` 為 `0` 等同不驗證漲幅，並非關閉整個型態。

**前置資料（判定所需的區間前置行情）**
- 箱型突破：需 `lookback` 個交易日。
- 底底高：需 `swingBars + 4` 個交易日（`swingBars` 供左側擺動比較，另 4 根供最左一日算出 MA5）。
- 上漲支撐：需 `lookback` 個交易日；另需 `endDate` 之後最多 2 個交易日供 D+1／D+2 確認，取不到即列 `pendingConfirm`。
- 反彈：需 `dropDays − 1 + riseDays` 個交易日（`requireRise` 為 `false` 時僅需 `dropDays − 1`）。
- 累積上漲：需 `days − 1` 個交易日。
- 前置資料不足者列入該策略的 `insufficientData`，與「掃過但未命中」明確區分，不列入 `items` 也不計入 `matchedCount`。

**最近一次命中規則**
- 同一檔股票在區間內若多次命中，`signalDate` 僅回報日期最晚的一次；底底高在有多組符合遞增條件時，同樣只取日期最晚的一組回報。
- 反彈的 `signalDate` 是反彈幅度達標當日，不是谷底當日；`requireRise` 為 `false` 時才退回為谷底當日。

**pendingConfirm 規則**
- 僅箱型突破（`confirmBars = 2` 且確認日尚未到）與上漲支撐（D+1／D+2 尚未到齊）可能非空。
- 反彈與累積上漲恆為空陣列：反彈採「已達標即命中、不等窗口跑滿」，因此不存在待確認狀態；累積上漲本就不要求任何確認。

**掃描母體與範圍**
- `stockIds` 省略或空陣列時掃描全部在市股票，再依 `commonStocksOnly` 過濾；`stockIds` 有值時只掃清單內股票且不受 `commonStocksOnly` 過濾（可含已下市股票與非普通股）。
- `stockIds` 上限 200 檔，超過即拒絕而非截斷。
- `commonStocksOnly` 省略時預設 `true`，只留代號恰為 4 位數字且首字元非 `0` 的股票；此參數只影響掃描母體，不寫入任何資料表。

**區間預設**
- `startDate`／`endDate` 皆省略時，區間為「今日往前一個日曆月 ～ 今日」。
- 型態判定一律以相鄰交易日比較，不因停牌造成的日曆間隔做插補。

## 跨主題規則

- 判定所讀的日線 OHLCV 行情來自既有的股票行情資料，窗口不足時對應股票落入 `insufficientData`（見 [stock-price-ingestion.md](stock-price-ingestion.md)）。
- `commonStocksOnly` 的普通股判斷（代號恰為 4 位數字且首字元非 `0`，藉此排除 ETF／特別股／TDR）沿用既有的普通股定義，與掃描母體共用同一份判斷，不得各自實作第二套（見 [stock-universe-import.md](stock-universe-import.md)）。
- `stockIds` 是否存在、`is_active` 狀態的判斷，依賴股票主檔資料（見 [stock-catalog.md](stock-catalog.md)）。
- 型態判定一律以相鄰交易日比較、不因停牌的日曆間隔做插補，此取樣規則與另一個指標主題的交叉判定規則一致，兩者不得各自為政（見 [stock-indicator-statistics.md](stock-indicator-statistics.md)）。

## API 清單

| Method | Path | 用途 | 送審分類 |
|---|---|---|---|
| GET | `/api/strategies` | 取得目前支援的五個型態清單，含有靈敏度型態的三段預設說明，以及無靈敏度型態（累積上漲、反彈）的可輸入參數定義與選用參數群組 | Direct |
| POST | `/api/strategies/scan` | 依傳入的策略與參數，對指定股票或全市場普通股即時掃描型態命中結果，不落地任何資料 | Direct |
