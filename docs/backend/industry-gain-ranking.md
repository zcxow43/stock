# 產業別漲幅排行 API

產業別漲幅排行 API 針對指定期間內每檔股票的逐日收盤價計算漲跌幅，篩出達到門檻的股票，並依其所屬產業別分組回傳，供動態分頁畫面呈現。使用者可選擇「近 N 交易日」或「指定週」兩種期間模式，並可選擇以漲幅加總或漲幅平均兩種度量方式篩選與排名。此模組僅讀取既有行情與產業別關聯做即時運算，不落地任何計算結果，回應內容也一律以漲幅統計表述，不提供任何買賣建議。

## 欄位定義

**`GET /api/momentum/gain` 查詢參數**

| Field | Type/Role | Rule |
|---|---|---|
| metric | 必填 | SUM（漲幅加總）或 AVERAGE（漲幅平均） |
| mode | 必填 | DAYS（近 N 交易日）或 WEEKS（指定週） |
| days | mode=DAYS 時必填 | 1–120；於 WEEKS 模式下被忽略 |
| startDate／endDate | mode=WEEKS 時必填 | YYYY-MM-DD；於 DAYS 模式下被忽略 |
| minGain | 選填，預設 5 | 百分比門檻，範圍 -100 ~ 1000，允許負值與 0 |
| commonStocksOnly | 選填，預設 true | 省略時視為 true；計算母體只含代號恰為 4 位數字且首字元非 0 的普通股，排除 ETF、特別股與 TDR |

**回應摘要欄位**

| Field | Type/Role | Rule |
|---|---|---|
| metric／mode | 回傳確認 | 原樣回傳對應本次請求所用的參數 |
| startDate／endDate | 實際採用區間 | 由系統決定的實際期間端點；區間內無交易日時皆為 null |
| tradingDays | 統計 | 區間內全市場相異交易日數 |
| minGain | 回傳確認 | 實際採用的門檻，2 位小數 |
| scannedStocks | 統計 | 計算母體檔數（在市且符合普通股篩選條件的總數） |
| matchedStockCount | 統計 | 去重後的命中檔數，前端顯示總數一律採用此值 |
| insufficientDataCount | 統計 | 因期間前置資料不足或期間內無有效行情而未參與計算的檔數 |

**產業別區塊欄位（`industries[]`）**

| Field | Type/Role | Rule |
|---|---|---|
| industryId | 識別欄位 | null 代表「未分類」區塊，永遠排在所有產業別之後 |
| industryName | 顯示名稱 | 未分類固定顯示為「未分類」 |
| matchedCount | 統計 | 該產業別下命中檔數，等於該區塊 items 長度；各產業加總會大於 matchedStockCount |
| items | 命中股票清單 | 見下表 |

**命中股票項目欄位（`items[]`）**

| Field | Type/Role | Rule |
|---|---|---|
| stockId／stockName | 識別欄位 | 一檔股票屬於多個產業別時，在每個產業別下各出現一次 |
| gain | 度量結果 | metric 對應之值，2 位小數，與門檻比較所用的值為同一個已四捨五入的值 |
| tradingDays | 統計 | 該檔在區間內實際有行情的交易日數，可能小於區間層級的 tradingDays（停牌所致） |
| startClose／endClose | 參考行情 | 該檔區間內第一個／最後一個有行情交易日的收盤價，不參與門檻判定 |
| firstTradeDate／lastTradeDate | 參考日期 | 對應 startClose／endClose 的日期 |

## 限制條件

- metric 與 mode 皆為必填，缺漏或非允許值即拒絕並說明原因（400）。
- days 僅接受 1–120，超出範圍即拒絕並說明原因（400）。
- WEEKS 模式下 startDate 須不晚於 endDate，否則拒絕並說明原因（400）。
- minGain 僅接受 -100 ~ 1000 之間的值，否則拒絕並說明原因（400）。
- 計算母體預設只含普通股，需明確帶 `commonStocksOnly=false` 才會納入 ETF、特別股與 TDR；本參數只影響計算母體，不寫入任何資料表。
- DAYS 模式的期間錨點為資料中最新的交易日，不是今日；WEEKS 模式下若區間內無任何交易日，回傳空結果而非錯誤。
- 收盤價為 0 的資料視為資料不足，不參與計算、也不視為未達門檻，以避免除以零造成整體查詢失敗。
- 掃描母體固定為在市股票（可再依 commonStocksOnly 篩選），不提供指定股票清單的參數。
- 相鄰交易日之間不因停牌造成的日曆間隔做任何插補。
- 沒有命中股票的產業別不出現在回應中；命中但無產業別關聯的股票一律歸入固定的「未分類」區塊。
- 回應欄位與文案一律以漲幅／命中／統計表述，不得出現建議、推薦等暗示買賣操作的措辭。

## 跨主題規則

- `commonStocksOnly` 的「只收普通股」判斷邏輯與股票清單查詢 API 共用同一份實作，全系統只有一套代號篩選規則（見 [stock-universe-import.md](stock-universe-import.md)、[stock-catalog.md](stock-catalog.md)）。
- 單日漲跌幅一律以未經除權息還原的原始收盤價計算，與策略掃描、指標統計採同一份資料來源，避免同一檔股票在不同功能顯示互相矛盾的漲跌（見 [strategy-scan.md](strategy-scan.md)、[stock-indicator-statistics.md](stock-indicator-statistics.md)）。
- 產業別分組所依據的股票與產業關聯資料，其建立與維護不在本 API 範圍內（見 `specs/dba/stock-industry.md`）。
- 本 API「回報漲幅統計而非買賣建議」的用語限制，與策略掃描為同一條契約（見 [strategy-scan.md](strategy-scan.md)）。

## API 清單

| Method | Path | 用途 | 送審分類 |
|---|---|---|---|
| GET | /api/momentum/gain | 依期間與門檻計算漲幅，按產業別分組回傳命中股票 | Live direct |
