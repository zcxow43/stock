# 股票清單查詢 API

股票清單查詢 API 提供前端讀取股票主檔與最新行情的唯一管道，同時也承擔單一標的的人工維護（新增、修改基本資料、下市）。清單查詢採「先分頁後查行情」的方式，確保效能不受全市場檔數影響，並可用關鍵字、市場別、是否含已下市股票、是否僅列普通股等條件篩選；單檔查詢則額外提供上市以來的彙總資訊。寫入類端點（新增／修改／下市）皆為即時生效，其中下市採軟性下架、不刪除任何歷史行情，以維持資料的可回溯性。

## 欄位定義

**`GET /api/stocks` 查詢參數**

| Field | Type/Role | Rule |
|---|---|---|
| keyword | 查詢條件（選填） | 對股票代號與名稱做包含比對，任一命中即回傳 |
| market | 查詢條件（選填） | TSE 或 OTC；省略代表不限市場別 |
| includeInactive | 查詢條件（選填，預設 false） | false 只回在市股票；true 連同已下市股票一併回傳 |
| commonStocksOnly | 查詢條件（選填，預設 true） | 省略時視為 true；只回代號恰為 4 位數字且首字元非 0 的普通股，排除 ETF、特別股與 TDR |
| page | 分頁（選填，預設 1） | 1-based 頁碼 |
| size | 分頁（選填，預設 50） | 每頁筆數，上限 200 |
| sort | 排序欄位（選填，預設 stockId） | 限股票代號／股票名稱／市場別 |
| order | 排序方向（選填，預設 asc） | asc／desc |

**股票清單項目／單檔基本資料（回應）**

| Field | Type/Role | Rule |
|---|---|---|
| stockId | 識別欄位 | 股票代號，唯一主鍵，建立後不可修改 |
| stockName | 基本資料 | 股票名稱 |
| market | 基本資料 | TSE 或 OTC |
| isActive | 狀態 | 是否在市；僅由新增／修改／下市端點變更 |
| latestTradeDate／latestClose／latestVolume | 最新行情 | 取自該檔最近一個有行情的交易日；無任何行情時為 null |
| previousClose／changeAmount／changePercent | 漲跌計算 | 需要兩個交易日的行情才有值，僅有單一交易日資料時為 null |
| firstTradeDate／tradingDayCount | 彙總資訊（僅單檔查詢回傳） | 該檔在庫中累積的行情起始日與總交易日數；無任何行情時分別為 null 與 0 |

**新增股票（請求）**

| Field | Type/Role | Rule |
|---|---|---|
| stockId | 必填 | 1–10 字元，去除前後空白後不得為空；不得與既有代號重複 |
| stockName | 必填 | 1–60 字元，去除前後空白後不得為空 |
| market | 必填 | TSE 或 OTC |

（`isActive` 由系統固定為 true，不接受請求指定）

**修改股票（請求）**

| Field | Type/Role | Rule |
|---|---|---|
| stockName | 必填 | 同新增規則 |
| market | 必填 | 同新增規則 |
| isActive | 必填 | true 可使已下市股票重新上架；false 等同下市 |

（`stockId` 為路徑參數，不可修改）

**下市回應**

| Field | Type/Role | Rule |
|---|---|---|
| stockId／stockName | 識別欄位 | 回傳被下市股票的代號與名稱 |
| isActive | 狀態 | 下市後固定為 false |

## 限制條件

- 清單查詢預設只顯示在市股票，需明確帶 `includeInactive=true` 才會看到已下市標的。
- 清單查詢預設只顯示普通股，需明確帶 `commonStocksOnly=false` 才會看到 ETF、特別股與 TDR。
- 本篩選只影響查詢結果，不寫入任何資料表——股票主檔的內容與在市狀態皆不因此改變。
- 單檔查詢不受普通股篩選影響：直接以代號查詢一檔 ETF 仍正常回傳。
- 清單一次最多回傳 200 筆，超過即拒絕並說明原因（400）。
- 清單排序欄位僅限股票代號、股票名稱、市場別，不支援依漲跌幅排序。
- 新增股票時代號重複會拒絕並說明原因（409），不會靜默覆寫既有股票的名稱。
- 股票代號一經建立不可修改；如需變更代號須以新增一檔並將舊代號下市的方式處理。
- 下市為軟性下架，僅改變在市狀態，不刪除任何歷史行情或指標資料，且重複呼叫仍視為成功（冪等）。
- 查無代號的單檔查詢、修改、下市皆拒絕並說明原因（404）。

## 跨主題規則

- `commonStocksOnly` 的「只收普通股」判斷邏輯由 [stock-universe-import.md](stock-universe-import.md) 定義，全系統共用同一份實作，本 API 僅套用該定義做結果篩選（見 [stock-universe-import.md](stock-universe-import.md)）。
- 已下市標的的歷史行情必須保留、不得刪除，該規則由 DBA 的股票主檔 spec 規範，本 API 的下市端點刻意不做實體刪除以配合此規則（見 `specs/dba/stock.md`）。
- 清單顯示的最新收盤與漲跌，其行情資料由每日抓取流程負責維護，本 API 僅讀取、不負責行情的取得或修正（見 [stock-price-ingestion.md](stock-price-ingestion.md)）。

## API 清單

| Method | Path | 用途 | 送審分類 |
|---|---|---|---|
| GET | /api/stocks | 查詢股票清單（含最新收盤與漲跌，可篩選／分頁／排序） | Live direct |
| GET | /api/stocks/{stockId} | 查詢單一股票基本資料與彙總行情 | Live direct |
| POST | /api/stocks | 新增一檔股票主檔 | Direct |
| PUT | /api/stocks/{stockId} | 修改股票名稱、市場別或在市狀態 | Direct |
| DELETE | /api/stocks/{stockId} | 下市股票（軟刪除，不刪除任何歷史資料） | Direct |
