# 策略型態掃描 API API

此 API 在既有日線行情與三大法人買賣超資料上做型態偵測，回報哪些股票在指定區間內出現特定型態，目前支援五個價格型態（箱型突破、底底高、上漲支撐、反彈、累積上漲）與三個法人籌碼型態（法人買賣超佔比、法人連續買超、法人買超強度排名）。整個模組只讀不寫：輸入是日線 OHLCV 與三大法人買賣超，輸出是即時算出的命中清單，不落地任何結果表，理由是型態判定完全由參數決定，換一組門檻就是另一組答案，重算成本本身就很低。法人籌碼型態額外複選外資（不含外資自營商）與投信、各自獨立判定，且因三大法人日報收盤後才發布，其進場日一律為訊號日的下一個交易日，與價格型態多半當日即可進場不同。回應內容一律以「命中／訊號／型態」表述，不得帶有「建議買進」「推薦」等操作性措辭，這是契約的一部分。

## 欄位定義

**策略目錄項目（`GET /api/strategies` 回應）**

| Field | Type/Role | Rule |
|---|---|---|
| `code` | string | 策略代碼，八個固定列舉之一 |
| `name` | string | 策略中文名稱 |
| `description` | string | 策略層級說明文字；僅無靈敏度型態（累積上漲、反彈、三個法人籌碼型態）使用，卡片說明取自此欄 |
| `presets` | array | 有靈敏度型態（箱型突破／底底高／上漲支撐）固定三段；無靈敏度型態為空陣列 |
| `params` | array | 僅無靈敏度型態列出；每筆為數字參數或複選參數 |
| `params[].type` | string | 省略時為數字參數（帶 `unit`／`default`／`min`／`max`／`step`）；為 `multiSelect` 時為複選參數，需帶 `options`／`default`／`minSelected`，請求以 `code` 陣列送出。目前僅法人籌碼型態的 `investors` 使用，但此機制不綁定任何策略 |
| `params[].group` | string | 指向 `paramGroups` 中同 code 的一筆，代表此參數屬於可整組開關的選用條件；不帶則一律生效、不可關閉 |
| `paramGroups` | array | 選用參數群組定義，帶 `code`／`name`／`default`（開關預設狀態）；目前僅反彈的 `rise` 群組使用 |

**掃描請求（`POST /api/strategies/scan`）**

| Field | Type/Role | Rule |
|---|---|---|
| `strategies` | array | 至少一筆；同一 `code` 不得重複 |
| `strategies[].code` | string | 八個策略代碼之一 |
| `strategies[].preset` | string | 有靈敏度的三個型態必填；累積上漲、反彈、三個法人籌碼型態不得帶 |
| `strategies[].risePercent` | number | 幅度門檻，最多一位小數，上限依型態分兩級（箱型突破／底底高／上漲支撐 0～20，反彈／累積上漲 0～50）；對有靈敏度型態是覆寫漲幅門檻，對累積上漲是累積漲幅門檻，對反彈是反彈幅度門檻；三個法人籌碼型態不接受 |
| `strategies[].days` | int | 僅累積上漲接受，回看交易日數，1～90，預設 20 |
| `strategies[].requireRise` | boolean | 僅反彈接受，是否套用漲段條件，預設 `true` |
| `strategies[].dropDays` / `dropPercent` / `riseDays` | int/number | 僅反彈接受，跌段與漲段的天數與幅度門檻 |
| `strategies[].investors` | string[] | 僅三個法人籌碼型態接受，`FOREIGN`／`TRUST` 至少一個、不重複，省略為兩者皆選 |
| `strategies[].windowDays` | int | 僅法人買賣超佔比與法人買超強度排名接受，1～20，預設 5，含訊號日 |
| `strategies[].ratioPercent` | number | 僅法人買賣超佔比接受，0～100 最多一位小數，預設 10 |
| `strategies[].buyDays` | int | 僅法人連續買超接受，1～20，預設 5，含訊號日 |
| `strategies[].topN` | int | 僅法人買超強度排名接受，1～50，預設 10 |
| `stockIds` | string[] | 省略／空陣列為全市場在市股票；上限 200 檔；有值時允許已下市或非普通股，且不受 `commonStocksOnly` 過濾 |
| `commonStocksOnly` | boolean | 預設 `true`，只留代碼恰 4 位數字且首字元非 0 的普通股；`stockIds` 有值時不生效 |
| `startDate` / `endDate` | date | 省略時為「今日往前一個日曆月」～「今日」 |

**掃描結果**

| Field | Type/Role | Rule |
|---|---|---|
| `scannedStocks` | int | 實際掃描母體檔數 |
| `results[]` | array | 依 `strategies` 送入順序一一對應回傳，每策略一筆 |
| `results[].preset` / `days` / `investors` 等 | — | 回報該策略實際採用的參數，與 `preset` 互斥 |
| `results[].dataThroughDate` | date | 僅法人籌碼型態，法人資料實際涵蓋到的最晚交易日；期間內完全無資料時為 `null` |
| `items[].signalDate` | date | 區間內最近一次命中日期 |
| `items[].buyDate` | date | 進場日：上漲支撐為 D+2；箱型突破／底底高／反彈／累積上漲等於 `signalDate`；三個法人籌碼型態為 D 之後下一個交易日 |
| `insufficientData` | string[] | 前置資料不足以判定的股票，不視為未命中 |
| `pendingConfirm` | string[] | 已命中但確認資料／進場日尚未到齊的股票，不計入 `matchedCount`；反彈與累積上漲恆為空陣列 |

**法人籌碼型態判定明細（`detail`）**

| Field | Type/Role | Rule |
|---|---|---|
| `matchedInvestors` | string[] | 實際達標的一方，依 `FOREIGN`、`TRUST` 固定順序 |
| `foreign` / `trust` | object/null | 該方判定數值；未勾選或未達標時為 `null` |
| `windowStartDate` | date | 窗口第一個交易日 |
| `volumeShares` | number | 窗口成交股數合計（佔比與強度排名型態使用） |
| `direction`（僅法人買賣超佔比） | string | `BUY`／`SELL`，依窗口買賣超合計正負決定 |
| `netShares`（僅法人買賣超佔比） | number | 窗口買賣超合計，可為負 |
| `ratioPercent`（僅法人買賣超佔比） | number | 佔比，百分比兩位小數 |
| `netBuyShares`（法人連續買超／強度排名） | number | 窗口內買超股數合計 |
| `rank` / `strengthPercent`（僅強度排名） | int/number | 當日名次與強度百分比；未進前 `topN` 時該方為 `null` |

## 限制條件

- 掃描母體上限 200 檔，超過即拒絕，不做默默截斷。
- `risePercent` 上限依型態分兩級：箱型突破／底底高／上漲支撐 0～20，反彈／累積上漲 0～50；三個法人籌碼型態一律不接受 `risePercent`。
- 底底高判定一律以 MA5（收盤 5 日均線）為基準，不接受呼叫端指定平滑天數，也不落地任何新資料表欄位。
- 上漲支撐的確認天數固定為 2 日，不隨靈敏度改變、不可覆寫。
- 有靈敏度的三個型態必填 `preset`；累積上漲、反彈、三個法人籌碼型態一律不得帶 `preset`。
- 法人籌碼型態的 `investors` 至少勾選一方、不得重複；`windowDays`／`buyDays` 範圍 1～20 的整數，`topN` 範圍 1～50 的整數，`ratioPercent` 範圍 0～100 且最多一位小數。
- 法人籌碼型態窗口內任一日法人資料尚未抓取時，該檔在該日不判定，也不參與當日排名；已抓取但無該檔資料時視為當日買賣超為 0。
- 法人籌碼型態的比率與強度一律為「窗口合計 ÷ 窗口合計」，不是每日比率取平均；法人買賣超佔比先加總再取絕對值，淨買超與淨賣超皆可命中；法人買超強度排名只有淨買超者參與，每個交易日外資與投信各取前 `topN`，名次在本次掃描母體內計算。
- 同一檔在區間內多次命中時只回報最近一次；法人籌碼型態收斂為「已有 `buyDate` 的命中中最近一次」。
- `insufficientData` 與命中結果互斥，資料不足與掃過無命中是兩種不同狀態，不得混為一談。
- 一個必然不可能命中的請求（如超出型態上限的漲跌幅門檻）應直接拒絕，不可受理後回零命中。
- 型態判定一律以相鄰交易日比較，不因停牌造成的日曆間隔做插補。
- 本模組只讀不寫，`commonStocksOnly` 等篩選參數不影響任何資料表內容。

## 跨主題規則

- 判定所用的日線 OHLCV 取自既有行情資料，未涵蓋的區間會使股票落入 `insufficientData`（見 stock-price-ingestion.md）。
- 成交量口徑取自日線的成交股數欄位，歷史列由 Yahoo 寫入者系統性偏低，會使法人籌碼型態的佔比與強度被高估，本模組不為此另做修正（見 stock-price-ingestion.md）。
- 法人籌碼型態的外資／投信買賣超股數取自三大法人買賣超資料，由另一模組在日線回補後寫入，本模組只讀不寫（見 institutional-trade-ingestion.md）。
- `commonStocksOnly` 的普通股篩選定義（代碼恰 4 位數字且首字元非 0）沿用既有股票池匯入規則，兩處須共用同一套判斷，不得各自實作（見 stock-universe-import.md）。
- `stockIds` 的存在性驗證與全市場掃描時的在市篩選依據股票主檔內容（見 stock-catalog.md）。
- 每一筆命中回報的 `buyDate` 即為回測進場買進的交易日，回測以其收盤價買進、且不區分型態，法人買賣超佔比的淨賣超命中亦同（見 strategy-backtest.md）。

## API 清單

| Method | Path | 用途 | 送審分類 |
|---|---|---|---|
| GET | /api/strategies | 取得可用策略目錄（含靈敏度說明、無靈敏度型態的參數定義與複選參數定義），供前端動態畫出策略卡片 | Direct |
| POST | /api/strategies/scan | 執行一次多策略型態掃描，回報命中清單、待確認清單與資料不足清單 | Direct |
