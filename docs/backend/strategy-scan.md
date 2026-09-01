# 策略型態掃描 API

本 API 在既有的股票日線行情上做型態偵測，回報哪些股票在指定區間內出現了特定型態，目前支援箱型突破、底底高、上漲支撐三種型態，每種型態各有嚴格／標準／寬鬆三段靈敏度可選。查詢一律以現有行情資料即時運算，不落地任何掃描結果——同一批行情換一組靈敏度就是另一組答案，把結果存起來反而會產生「這筆是用哪組參數算的、參數改了要不要重算」的問題，而重新掃描一次的成本本來就低。本模組回報的僅是型態命中結果，不是買賣建議，回應內容與文案一律以「命中／訊號／型態」表述。

## 欄位定義

**策略與靈敏度（`GET /api/strategies` 回應）**

| Field | Type/Role | Rule |
|---|---|---|
| code | 策略代碼 | 固定三種之一：`BOX_BREAKOUT`（箱型突破）／`HIGHER_LOWS`（底底高）／`RISING_SUPPORT`（上漲支撐） |
| name | 策略中文名稱 | 對應代碼固定顯示名稱 |
| presets | 靈敏度清單 | 固定三段：`STRICT`（嚴格）／`STANDARD`（標準）／`LOOSE`（寬鬆），僅改判定門檻，不改判定邏輯 |
| presets[].code | 靈敏度代碼 | `STRICT` / `STANDARD` / `LOOSE` |
| presets[].name | 靈敏度中文名稱 | 嚴格／標準／寬鬆 |
| presets[].description | 靈敏度說明文字 | 需與該策略該段靈敏度的參數表數值一致，供前端直接顯示，不得於前端另行寫死 |

**掃描結果項目（`results[].items[]`）**

| Field | Type/Role | Rule |
|---|---|---|
| stockId | 股票代號 | 命中或待確認的股票 |
| stockName | 股票名稱 | 對應股票代號的名稱 |
| signalDate | 訊號日期 | 該檔在區間內最近一次命中的日期；同一檔多次命中只回報最近一次；上漲支撐的訊號日為上漲當日 D，而非確認完成日 |
| detail | 判定明細 | 內容依策略不同而異，見下列各策略明細表 |

**箱型突破判定明細（`detail`，於 `BOX_BREAKOUT`）**

| Field | Type/Role | Rule |
|---|---|---|
| boxHigh | 箱體上緣 | D 之前 `lookback` 個交易日最高價的最大值 |
| boxLow | 箱體下緣 | D 之前 `lookback` 個交易日最低價的最小值 |
| breakoutClose | 突破日收盤價 | D 當日收盤價 |
| breakoutPercent | 突破幅度 | 相對箱體上緣的漲幅百分比 |
| volumeRatio | 量能倍數 | D 成交量相對前 5 日均量的倍數 |

**底底高判定明細（`detail`，於 `HIGHER_LOWS`）**

| Field | Type/Role | Rule |
|---|---|---|
| lows | 遞增低點清單 | 命中組合中連續遞增的 swing low，依日期由舊到新排列，含日期與最低價 |
| lows[].tradeDate | 交易日期 | 該 swing low 發生日 |
| lows[].low | 最低價 | 該日最低價 |

**上漲支撐判定明細（`detail`，於 `RISING_SUPPORT`）**

| Field | Type/Role | Rule |
|---|---|---|
| supportClose | 支撐線價位 | D-1（起漲前一日）收盤價，即這根上漲的起點 |
| riseClose | 上漲日收盤價 | D 當日收盤價 |
| risePercent | 上漲幅度 | D 相對 D-1 的漲幅百分比 |
| priorHighClose | 前段收盤高點 | D 之前 `lookback` 個交易日收盤價的最大值，D 收盤須突破此值 |
| confirmCloses | 確認日收盤清單 | D+1、D+2 兩個交易日的收盤價，兩者皆須高於支撐線才計為命中 |
| confirmCloses[].tradeDate | 交易日期 | 確認日日期 |
| confirmCloses[].close | 收盤價 | 該確認日收盤價 |

## 限制條件

- 三個策略、三段靈敏度皆為固定枚舉，靈敏度只調整門檻數值，不改變判定邏輯本身。
- 箱型突破：盤整前提（箱高需低於門檻）為必要檢查，不可省略，`LOOSE` 才明確關閉此檢查；`confirmBars = 2` 時若確認日資料不存在，該檔標記為待確認、不計入命中。
- 底底高：容忍度（`risePercent`）僅 `LOOSE` 可為 0，其餘兩段皆須有實質漲幅門檻，避免雜訊被誤判為型態；swing low 需左右兩側資料皆足夠 `swingBars` 根才能列入判定。
- 上漲支撐：確認長度固定為 2 個交易日，不隨靈敏度改變；不驗證成交量，僅看價格；支撐線固定取 D-1 收盤，不可替換為前段高點。
- 上漲支撐的確認資料需取自查詢區間結束日之後最多 2 個交易日；該資料尚未存在時，該檔歸入待確認而非命中。
- 待確認可能出現在兩個策略：箱型突破（確認長度為 2 時）與上漲支撐；兩者皆不計入命中數。
- 掃描範圍上限 200 檔股票，超過即拒絕、不做截斷；`stockIds` 省略時僅掃描目前在市股票，若使用者明確指定清單則允許包含已下市股票。
- 前置資料不足以完成判定的股票歸入「資料不足」，不得與「掃描後未命中」混為一談，兩者對使用者是不同訊息。
- 型態判定一律以相鄰交易日比較，不因停牌造成的日曆間隔做任何插補。
- 同一次請求中同一策略代碼不得重複指定。
- 回應欄位名稱與說明文字一律不得出現「建議買進」「推薦」等暗示操作的措辭。

## 跨主題規則

- 型態判定所需的日線 OHLCV 來自既有行情資料，本模組僅讀取、不新增或修改任何行情資料（見 [stock-price-ingestion.md](stock-price-ingestion.md)）。
- 掃描範圍中「全部股票」的認定依股票主檔的在市狀態決定（見 [stock-catalog.md](stock-catalog.md)）。
- 相鄰交易日比較、不對停牌造成的日曆間隔做插補的規則，與跨主題的技術指標判定規則一致，兩者不得各自為政（見 [stock-indicator-statistics.md](stock-indicator-statistics.md)）。

## API 清單

| Method | Path | 用途 | 送審分類 |
|---|---|---|---|
| GET | /api/strategies | 取得目前可用的策略清單與各策略三段靈敏度的說明文字 | Live direct |
| POST | /api/strategies/scan | 依指定策略、靈敏度、股票範圍與區間即時執行型態掃描並回傳命中結果 | Live direct |
