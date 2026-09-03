---
status: pending
title: "策略型態掃描 API"
requirement: "策略分頁 — 勾選策略（底底高、箱型突破、上漲支撐）對股票掃描並列出命中標的，每個策略可選三種靈敏度且漲幅門檻可自行輸入覆寫，掃描母體預設只含上市普通股（排除 ETF／特別股／TDR），掃描區間預設近一個月且可自由指定"
depends_on: [stock-price-ingestion, stock-catalog]
---

# 策略型態掃描 API — Backend Spec

## Overview

在既有日線行情上做**型態偵測**，回報哪些股票在指定區間內出現了某個型態。目前支援三個型態：底底高（`HIGHER_LOWS`）、箱型突破（`BOX_BREAKOUT`）與上漲支撐（`RISING_SUPPORT`）。

此模組**只讀不寫**：輸入是 `stock_daily_price` 的 OHLCV，輸出是即時算出的命中清單，不落地任何結果表。理由是型態判定完全由參數決定，同一批行情換一組靈敏度就是另一組答案；把結果存起來會立刻面臨「這列是用哪組參數算的、參數改了要不要重算」的問題，而重算成本本來就低（單檔單區間只是一次順序掃描）。

**本模組回報的是型態偵測結果，不是買賣建議。** 回應欄位、UI 文案與產出文件一律以「命中／訊號／型態」表述，不得出現「建議買進」「推薦」等暗示操作的措辭。這是契約的一部分，不是文字風格偏好。

## Requirements

### 型態定義

三個型態各有三段靈敏度（`STRICT` / `STANDARD` / `LOOSE`），由呼叫端逐一指定。靈敏度只改門檻，不改判定邏輯。

**每個型態的漲幅門檻可由呼叫端逐一覆寫**：請求中該策略的 `risePercent` 有值時，取代靈敏度表裡的漲幅欄位；省略時沿用靈敏度的值。靈敏度仍決定該型態的其餘參數（回看長度、擺動根數、量能倍數、確認根數、盤整前提）。各型態被覆寫的是哪一欄，註明在下方各自的參數表下。

覆寫只改門檻數值，一樣不改判定邏輯——`risePercent` 為 `0` 的效果等同靈敏度表裡漲幅為 0% 的那一段（不驗證漲幅），不是關閉整個型態。

#### 箱型突破 `BOX_BREAKOUT`

對區間內每個交易日 D 判定：

1. **箱體**：取 D **之前**（不含 D）連續 `lookback` 個交易日，上緣 = 該區間最高價的最大值，下緣 = 最低價的最小值。
2. **盤整前提**：`(上緣 − 下緣) ÷ ((上緣 + 下緣) ÷ 2)` 必須小於 `rangeMaxPercent`。不通過即非箱型，D 不算命中。
3. **突破**：D 的收盤價 > `上緣 × (1 + breakoutPercent)`。
4. **量能**：D 的成交量 > `D 之前 5 個交易日成交量平均 × volumeMultiple`。
5. **確認**：`confirmBars` 為 2 時，D 與 D 的下一個交易日都必須收在上緣之上；D 為區間最後一天而無下一日資料時，該檔標記為 `PENDING_CONFIRM`，不計入命中。

| 參數 | `STRICT` | `STANDARD` | `LOOSE` |
|---|---|---|---|
| `lookback`（根） | 60 | 20 | 20 |
| `rangeMaxPercent` | 5% | 8% | 不驗證 |
| `breakoutPercent` | 2% | 1.5% | 0%（收盤 > 上緣即可） |
| `volumeMultiple` | 2.0 | 1.5 | 不驗證 |
| `confirmBars` | 2 | 1 | 1 |

請求的 `risePercent` 覆寫本表的 **`breakoutPercent`**（突破幅度）。其餘四項一律由靈敏度決定。

**盤整前提不可省略的理由**：不驗證箱高就掃描，一段穩定上升趨勢的任意區間都會被視為「箱型」，其每一根新高都成為「突破」。`LOOSE` 明確關掉這道檢查，因此它的命中數本來就會偏高，這是使用者選擇該靈敏度時應該預期的行為，不是缺陷。

#### 底底高 `HIGHER_LOWS`

1. **擺動低點（swing low）**：某交易日 D 的最低價，嚴格低於其左右各 `swingBars` 個交易日的最低價。左右任一側資料不足 `swingBars` 根者不列入。
2. 取區間內所有 swing low，依日期排序。
3. **命中**：存在連續 `requiredRises` 段遞增，即連續 `requiredRises + 1` 個 swing low 滿足每一個都高於前一個，且 `後一個低點 ≥ 前一個低點 × (1 + risePercent)`。
4. 有多組符合時，取**日期最晚**的那一組回報。

| 參數 | `STRICT` | `STANDARD` | `LOOSE` |
|---|---|---|---|
| `swingBars`（左右各） | 5 | 3 | 2 |
| `requiredRises`（遞增段數） | 3 | 2 | 2 |
| `risePercent` | 2% | 1% | 0%（高過即可） |

請求的 `risePercent` 覆寫本表的 **`risePercent`**（每段遞增的最小幅度）。`swingBars` 與 `requiredRises` 一律由靈敏度決定。

**容忍度不可省略的理由**：`risePercent` 為 0 時，高出 0.01 元也構成「底底高」，雜訊會全部變成訊號。`LOOSE` 刻意允許這件事，其餘兩段不允許。

#### 上漲支撐 `RISING_SUPPORT`

對區間內每個交易日 D 判定。**D 是上漲日，也是回報的 `signalDate`**：

1. **突破近期區間**：D 的收盤價 > D **之前**（不含 D）連續 `lookback` 個交易日**收盤價**的最大值。
2. **上漲幅度**：`D 收盤 ÷ D-1 收盤 − 1` ≥ `risePercent`。
3. **支撐線**：即 D-1 的收盤價——這根上漲的起點。
4. **支撐守住**：D+1 與 D+2 的收盤價**都**必須 > 支撐線。任一日收在支撐線之下（或等於）即不命中。
5. **確認**：確認長度固定為 2 個交易日，**不隨靈敏度改變**。D+1 或 D+2 的資料尚未存在時（D 落在可用行情的尾端），該檔標記為 `PENDING_CONFIRM`，不計入命中。
6. **不驗證量能。**

| 參數 | `STRICT` | `STANDARD` | `LOOSE` |
|---|---|---|---|
| `lookback`（根） | 20 | 10 | 5 |
| `risePercent` | 5% | 3% | 2% |
| `confirmBars` | 2 | 2 | 2 |

請求的 `risePercent` 覆寫本表的 **`risePercent`**（上漲日相對前一日的最小漲幅）。`lookback` 由靈敏度決定，`confirmBars` 固定為 2、兩者皆不受覆寫影響。

**支撐線取 D-1 收盤、而非前 `lookback` 日高點的理由**：本型態問的是「這根漲勢有沒有被守住」，而起漲點就是 D-1 的收盤——跌回它以下，代表這根上漲被完全吃掉。改用前段高點當支撐，判定會退化成箱型突破的變形，兩個策略的命中集合大量重疊，使用者同時勾選時看不出差別。

**突破近期區間這道條件不可省略的理由**：只看單日漲幅的話，任何一根大漲都算「忽然間一個上漲」，包含一段已經連漲多日的趨勢中的又一根。加上「收盤需高於前 `lookback` 日的全部收盤」，才把型態限縮在「從一段相對平緩的行情中忽然跳出來」，這正是原始需求「忽然間」三個字要求的東西。

**不驗證量能是有意的選擇**，不是遺漏：本型態只看價。日後若要求帶量確認，那是新增一個參數的增量，不是改寫既有判定。

### 掃描範圍

- `stockIds` 省略或為空陣列 → 掃描 `stock` 表中 `is_active = 1` 的股票，再依 `commonStocksOnly` 決定是否只留普通股。
- `stockIds` 有值 → 只掃描清單內的股票，且清單中允許包含已下市股票與非普通股（**使用者明確指名時不代掃描範圍過濾**，`commonStocksOnly` 一律不套用）。
- 上限 200 檔；超過即拒絕，而非默默截斷。

**普通股篩選（`commonStocksOnly`，預設 `true`）**：只留代號**恰為 4 位數字、且首字元非 `0`** 的股票。這道規則不是本 spec 新訂的，而是沿用 `specs/backend/stock-universe-import.md` 的「只收普通股」定義；同一套判斷同時排除 ETF（`0050`、`00878`）、特別股（`2881A`）與 TDR／存託憑證（`910322`）。兩處必須共用同一個判斷，不得各自實作一份。

預設為 `true` 的理由與該 spec 記載的一致：本模組的三個型態都是為個股價格行為設計的判定規則，ETF 的價格由一籃子成分股加權而成，其「箱型」「突破」「起漲」在意義上與個股不同；把數百檔 ETF 混進母體只會稀釋掃描結果，不會讓使用者多得到可用的訊號。`commonStocksOnly: false` 保留給明確想連 ETF 一起掃的情況。

**本篩選只影響掃描母體，不寫入任何資料表**——`stock` 的內容、`is_active`、以及股票總覽顯示的檔數，都不因這個參數而改變。

### 區間與資料前置需求

- `startDate` / `endDate` 皆省略時，區間為 `endDate = 今日`、`startDate = 今日往前一個日曆月`。
- **判定所需的前置資料取自 `startDate` 之前**：箱型突破需要 `lookback` 個交易日、底底高需要 `swingBars` 個交易日、上漲支撐需要 `lookback` 個交易日。這些資料只用於判定，不會被回報為命中。
- **上漲支撐的確認資料取自 `endDate` 之後**：判定 D 是否命中需要 D+1 與 D+2 的收盤。掃描時應一併讀入 `endDate` 之後最多 2 個交易日的行情；若該資料尚未存在，D 落入 `pendingConfirm`。
- 某檔的前置資料不足以完成判定時，該檔列入該策略的 `insufficientData`，**不視為未命中**。兩者必須分開：「掃過了沒有型態」與「資料不夠所以沒掃」對使用者是完全不同的訊息。
- 型態判定一律以**相鄰交易日**比較，不因停牌造成的日曆間隔做任何插補。此規則與 `specs/backend/stock-indicator-statistics.md` 的交叉判定一致，不得各自為政。

## Implementation Details

### API 契約

#### 1. 可用策略清單

```
GET /api/strategies
```

Response `200`：
```json
{
  "strategies": [
    {
      "code": "BOX_BREAKOUT",
      "name": "箱型突破",
      "presets": [
        { "code": "STRICT",   "name": "嚴格", "description": "回看 60 根，箱高 < 5%，突破 2% 且量增 2 倍，需連 2 根確認" },
        { "code": "STANDARD", "name": "標準", "description": "回看 20 根，箱高 < 8%，突破 1.5% 且量增 1.5 倍" },
        { "code": "LOOSE",    "name": "寬鬆", "description": "回看 20 根，不驗證盤整，收盤突破上緣即計" }
      ]
    },
    {
      "code": "HIGHER_LOWS",
      "name": "底底高",
      "presets": [
        { "code": "STRICT",   "name": "嚴格", "description": "左右各 5 根，需 3 段遞增，每段高過 2%" },
        { "code": "STANDARD", "name": "標準", "description": "左右各 3 根，需 2 段遞增，每段高過 1%" },
        { "code": "LOOSE",    "name": "寬鬆", "description": "左右各 2 根，需 2 段遞增，高過即計" }
      ]
    },
    {
      "code": "RISING_SUPPORT",
      "name": "上漲支撐",
      "presets": [
        { "code": "STRICT",   "name": "嚴格", "description": "收盤突破前 20 日收盤高點且單日漲幅 ≥ 5%，其後 2 日不跌破起漲收盤" },
        { "code": "STANDARD", "name": "標準", "description": "收盤突破前 10 日收盤高點且單日漲幅 ≥ 3%，其後 2 日不跌破起漲收盤" },
        { "code": "LOOSE",    "name": "寬鬆", "description": "收盤突破前 5 日收盤高點且單日漲幅 ≥ 2%，其後 2 日不跌破起漲收盤" }
      ]
    }
  ]
}
```

前端的策略選單與靈敏度說明文字一律取自此端點，不在前端寫死——參數改動時只需改後端一處。

#### 2. 執行掃描

```
POST /api/strategies/scan
```

Request：
```json
{
  "strategies": [
    { "code": "BOX_BREAKOUT", "preset": "STANDARD", "risePercent": 2.5 },
    { "code": "HIGHER_LOWS",  "preset": "STRICT" },
    { "code": "RISING_SUPPORT", "preset": "STANDARD", "risePercent": 4 }
  ],
  "stockIds": ["2330", "2317"],
  "commonStocksOnly": true,
  "startDate": "2026-07-30",
  "endDate": "2026-08-30"
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `strategies` | array | 是 | 至少一個；同一 `code` 不得重複出現 |
| `strategies[].code` | string | 是 | `BOX_BREAKOUT` / `HIGHER_LOWS` / `RISING_SUPPORT` |
| `strategies[].preset` | string | 是 | `STRICT` / `STANDARD` / `LOOSE` |
| `strategies[].risePercent` | number | 否 | 覆寫該策略的漲幅門檻；省略即沿用 `preset` 的值。範圍 `0`～`20`，最多一位小數 |
| `stockIds` | string[] | 否 | 省略或空陣列 = 全部在市股票；上限 200 |
| `commonStocksOnly` | boolean | 否 | **省略時視為 `true`**；只掃代號恰為 4 位數字且首字元非 `0` 的普通股。`stockIds` 有值時本欄位不生效 |
| `startDate` | date | 否 | 預設為 `endDate` 往前一個日曆月 |
| `endDate` | date | 否 | 預設為今日 |

Response `200`：
```json
{
  "startDate": "2026-07-30",
  "endDate": "2026-08-30",
  "scannedStocks": 34,
  "results": [
    {
      "strategy": "BOX_BREAKOUT",
      "preset": "STANDARD",
      "matchedCount": 2,
      "items": [
        {
          "stockId": "2330",
          "stockName": "台積電",
          "signalDate": "2026-08-27",
          "detail": {
            "boxHigh": 2380.00,
            "boxLow": 2250.00,
            "breakoutClose": 2420.00,
            "breakoutPercent": 1.68,
            "volumeRatio": 1.82
          }
        }
      ],
      "insufficientData": ["6669"],
      "pendingConfirm": []
    },
    {
      "strategy": "HIGHER_LOWS",
      "preset": "STRICT",
      "matchedCount": 1,
      "items": [
        {
          "stockId": "2317",
          "stockName": "鴻海",
          "signalDate": "2026-08-25",
          "detail": {
            "lows": [
              { "tradeDate": "2026-07-08", "low": 240.00 },
              { "tradeDate": "2026-07-29", "low": 248.50 },
              { "tradeDate": "2026-08-13", "low": 256.00 },
              { "tradeDate": "2026-08-25", "low": 262.50 }
            ]
          }
        }
      ],
      "insufficientData": [],
      "pendingConfirm": []
    },
    {
      "strategy": "RISING_SUPPORT",
      "preset": "STANDARD",
      "matchedCount": 1,
      "items": [
        {
          "stockId": "2454",
          "stockName": "聯發科",
          "signalDate": "2026-08-26",
          "detail": {
            "supportClose": 1200.00,
            "riseClose": 1296.00,
            "risePercent": 8.00,
            "priorHighClose": 1236.00,
            "confirmCloses": [
              { "tradeDate": "2026-08-27", "close": 1272.00 },
              { "tradeDate": "2026-08-28", "close": 1248.00 }
            ]
          }
        }
      ],
      "insufficientData": [],
      "pendingConfirm": ["3008"]
    }
  ]
}
```

- `results` 依 `strategies` 送入的順序回傳，一個策略一筆。
- `items` 依 `signalDate` 由新到舊排序；同日則依 `stockId` 升冪。
- `signalDate` 為該檔在區間內**最近一次**命中的日期；同一檔在區間內多次命中只回報最近一次。
- `pendingConfirm` 可能非空的情況有二：箱型突破且 `confirmBars = 2`（已突破但確認日尚未到），以及上漲支撐（已上漲但 D+1／D+2 尚未到齊）。兩者都列出該檔的股票代號，且不計入 `matchedCount`。
- `insufficientData` 與 `matchedCount` 互斥：列在前者的股票不會出現在 `items` 中。

驗證與錯誤：
- `strategies` 為空或缺漏 → `400`，`{"code":"NO_STRATEGY_SELECTED"}`
- 未知的 `code` 或 `preset` → `400`，`{"code":"UNKNOWN_STRATEGY","unknown":["FOO"]}`
- 同一 `code` 重複出現 → `400`，`{"code":"DUPLICATE_STRATEGY","duplicated":["BOX_BREAKOUT"]}`
- `stockIds` 含 `stock` 主檔不存在的代號 → `400`，`{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}`
- `stockIds` 超過 200 檔 → `400`，`{"code":"TOO_MANY_STOCKS"}`
- `startDate` 晚於 `endDate` → `400`，`{"code":"INVALID_DATE_RANGE"}`
- `risePercent` 小於 `0`、大於 `20`、或小數超過一位 → `400`，`{"code":"INVALID_RISE_PERCENT","strategy":"RISING_SUPPORT"}`。`strategy` 必須指出是哪一個策略的值不合法——三個策略各有一個獨立輸入，不指名的話使用者無從得知該改哪一格

### 處理流程

解析並驗證請求 → 決定目標股票清單（指定清單；或全市場在市再依 `commonStocksOnly` 過濾）→ 對每個策略取靈敏度參數、並以該策略的 `risePercent` 覆寫其漲幅門檻 → 對每檔股票讀取 `startDate` 前置區間起算至 `endDate` 的日線 → 逐日套用判定 → 收斂為每檔最近一次命中 → 組裝回應。

**行情讀取必須批次進行**，不得逐檔一次查詢：全市場掃描是 2200 檔，逐檔查詢即 2200 次往返。以單一查詢按 `(stock_id, trade_date)` 主鍵範圍取回目標區間的全部列，再在記憶體中依股票分組判定。

## Acceptance Criteria
- [x] `GET /api/strategies` 回傳兩個策略、各三段靈敏度，說明文字與本 spec 的參數表一致
- [x] `POST /api/strategies/scan` 省略 `startDate`／`endDate` 時，區間為「今日往前一個日曆月 ~ 今日」
- [x] 省略 `stockIds` 時掃描 `is_active = 1` 的全部股票，`scannedStocks` 等於實際掃描檔數
- [x] 指定 `stockIds` 時只掃描清單內股票，且允許包含已下市股票
- [x] 箱型突破 `STANDARD`：以一組已知會在箱高 6%、突破 2%、量增 1.8 倍的構造資料驗證命中，且回應的 `boxHigh`／`boxLow`／`breakoutPercent`／`volumeRatio` 與手算相符
- [x] 箱型突破的盤整前提生效：一段穩定上升（箱高 15%）的資料在 `STANDARD` 下不命中，在 `LOOSE` 下命中
- [x] 箱型突破 `STRICT` 的 `confirmBars=2`：突破日為區間最後一天且無次日資料時，該檔列於 `pendingConfirm` 而非 `items`
- [x] 底底高 `STANDARD`：三個遞增幅度各 > 1% 的 swing low 命中，回應的 `lows` 為 3 筆且日期與價格正確
- [x] 底底高的容忍度生效：遞增幅度僅 0.3% 的資料在 `STANDARD` 下不命中，在 `LOOSE` 下命中
- [x] swing low 判定需左右各 `swingBars` 根皆嚴格高於它；左右資料不足者不列入
- [x] 前置資料不足的股票列於 `insufficientData`，且不出現在 `items`，也不計入 `matchedCount`
- [x] 同一檔在區間內多次命中時，`signalDate` 為最近一次
- [x] `items` 依 `signalDate` 由新到舊排序，同日依 `stockId` 升冪
- [x] 型態判定以相鄰交易日比較：區間內含停牌造成的日曆間隔時，判定結果與無間隔時一致（不做插補）
- [x] 全市場掃描時對資料庫的行情查詢為批次查詢，不隨股票數線性增加查詢次數
- [x] 六種錯誤各自回傳指定的 `code`：`NO_STRATEGY_SELECTED`／`UNKNOWN_STRATEGY`／`DUPLICATE_STRATEGY`／`UNKNOWN_STOCK_ID`／`TOO_MANY_STOCKS`／`INVALID_DATE_RANGE`
- [x] 回應欄位名與說明文字皆無「建議」「推薦」等暗示買賣操作的措辭

---

- [x] `GET /api/strategies` 回傳三個策略，新增的 `RISING_SUPPORT` 名稱為「上漲支撐」，三段靈敏度說明文字與本 spec 的參數表一致
- [x] 上漲支撐 `STANDARD`：以構造資料驗證命中——前 10 日收盤最高 1236、D-1 收盤 1200、D 收盤 1296（漲幅 8%）、D+1 收盤 1272、D+2 收盤 1248，回應的 `supportClose`／`riseClose`／`risePercent`／`priorHighClose`／`confirmCloses` 與手算相符
- [x] 支撐線為 D-1 收盤：D+1 或 D+2 任一日收盤 ≤ D-1 收盤即不命中（以恰好等於 D-1 收盤的構造資料驗證不命中）
- [x] 突破近期區間條件生效：D 漲幅達門檻但收盤未高於前 `lookback` 日全部收盤時不命中（以連漲趨勢中的一根大漲驗證）
- [x] 漲幅門檻生效：漲幅 2.5% 的同一組資料在 `STANDARD`（3%）下不命中，在 `LOOSE`（2%）下命中
- [x] `lookback` 隨靈敏度改變：同一組資料在 `LOOSE`（前 5 日）下命中，在 `STRICT`（前 20 日）下因未突破更長區間的收盤高點而不命中
- [x] 確認長度固定為 2 日且不隨靈敏度改變：三段靈敏度都要求 D+1 與 D+2 皆守住
- [x] D+1 或 D+2 尚無資料時該檔列於 `pendingConfirm`，不出現在 `items`、不計入 `matchedCount`
- [x] 確認資料可取自 `endDate` 之後：D 為 `endDate` 當日且 D+1／D+2 已存在於資料庫時，該檔正常命中而非落入 `pendingConfirm`
- [x] 上漲支撐前置資料不足（`startDate` 前不足 `lookback` 個交易日）的股票列於 `insufficientData`
- [x] 上漲支撐不驗證量能：僅成交量不同、價格完全相同的兩組資料判定結果一致
- [x] 三個策略可於同一次 `POST /api/strategies/scan` 一併送入，`results` 依送入順序回傳三筆
- [x] `RISING_SUPPORT` 的 `signalDate` 為上漲日 D 本身，不是確認完成日 D+2
- [x] 上漲支撐的回應欄位名與說明文字皆無「建議」「推薦」等暗示買賣操作的措辭

---

- [ ] 三個策略各自的 `risePercent` 可獨立指定：同一次請求對箱型突破送 `2.5`、對上漲支撐送 `4`、底底高省略，三者分別以 2.5%／4%／該靈敏度原值判定
- [ ] 省略 `risePercent` 時該策略的判定結果與未加本功能前完全一致（以既有三組構造資料驗證，命中集合不變）
- [ ] `risePercent` 覆寫的是各型態參數表指定的那一欄：箱型突破改 `breakoutPercent`、底底高改每段遞增的 `risePercent`、上漲支撐改單日漲幅的 `risePercent`
- [ ] `risePercent` 不影響其餘參數：同一策略在 `STRICT` 與 `LOOSE` 下送相同的 `risePercent`，`lookback`／`swingBars`／`volumeMultiple`／`confirmBars` 仍依各自靈敏度取值
- [ ] `risePercent` 為 `0` 時等同不驗證漲幅，而非零命中或關閉該型態
- [ ] `risePercent` 小於 `0`、大於 `20`、或小數超過一位 → `400`，`{"code":"INVALID_RISE_PERCENT","strategy":"<該策略 code>"}`，且 `strategy` 指出的是實際不合法的那一個策略
- [ ] `commonStocksOnly` 省略時視為 `true`：不帶此欄位的全市場掃描，`scannedStocks` 只計代號恰為 4 位數字且首字元非 `0` 的股票
- [ ] `commonStocksOnly: true` 時 `0050`、`00878`、`2881A`、`910322` 皆不在掃描母體中，也不出現在任何策略的 `items`、`insufficientData` 或 `pendingConfirm`
- [ ] `commonStocksOnly: false` 時掃描母體為全部 `is_active = 1` 的股票，`scannedStocks` 明顯大於 `true` 時的值
- [ ] 普通股判斷與 `specs/backend/stock-universe-import.md` 共用同一份實作，不存在第二套代號篩選邏輯
- [ ] `stockIds` 有值時 `commonStocksOnly` 不生效：指定 `["0050","2330"]` 且 `commonStocksOnly: true`，兩檔都被掃描
- [ ] 本參數不寫任何資料表：掃描前後 `stock` 的列數、內容與 `is_active` 完全不變
- [ ] 三個策略同時送出、各帶不同 `risePercent`、且 `commonStocksOnly: true` 時，`results` 仍依送入順序回傳三筆

---
## Execution Result
- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/controller/StrategyController.java` (new)
  - `develop/backend/src/main/java/com/stock/service/StrategyCatalogService.java` (new)
  - `develop/backend/src/main/java/com/stock/service/StrategyScanService.java` (new)
  - `develop/backend/src/main/java/com/stock/service/pattern/PatternDetector.java` (new)
  - `develop/backend/src/main/java/com/stock/service/pattern/PatternDetectionOutcome.java` (new)
  - `develop/backend/src/main/java/com/stock/service/pattern/BoxBreakoutDetector.java` (new)
  - `develop/backend/src/main/java/com/stock/service/pattern/HigherLowsDetector.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/StrategyCatalogResponseDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/StrategyDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/PresetDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/ScanRequestDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/StrategySelectionDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/ScanResponseDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/StrategyResultDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/StrategyHitDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/BoxBreakoutDetailDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/HigherLowsDetailDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/LowPointDto.java` (new)
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` (added `unknown`/`duplicated` fields + `unknownStrategy`/`duplicateStrategy`/`tooManyStocks` factories)
  - `develop/backend/src/main/java/com/stock/exception/NoStrategySelectedException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/UnknownStrategyException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/DuplicateStrategyException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/TooManyStocksException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` (added 4 handlers)
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` (added `findByStockIdsAndDateRange`, `findRecentBeforeDateByStockIds`)
  - `develop/backend/src/main/resources/mapper/StockDailyPriceMapper.xml` (added the two batched queries)
  - `develop/backend/src/test/java/com/stock/StrategyScanIntegrationTest.java` (new, 22 tests)
  - `develop/backend/src/test/java/com/stock/support/QueryCountInterceptor.java` (new, test-only MyBatis interceptor)
- Notes:
  - Implemented as a Strategy-pattern set of `PatternDetector`s (`BoxBreakoutDetector`, `HigherLowsDetector`), each owning its own STRICT/STANDARD/LOOSE parameter table *and* the catalogue description text for that preset, so numbers and wording can't drift apart silently and `GET /api/strategies` reuses the exact same objects the scan uses.
  - Price reads are batched into exactly two queries per scan regardless of stock count: one `ROW_NUMBER() OVER (PARTITION BY stock_id ...)` query for the lookback bars strictly before `startDate` (capped at the max lookback needed across the selected strategies), and one range query for `[startDate, endDate]`. Verified with a test-only MyBatis interceptor asserting the query count is identical for 3 vs. 12 scanned stocks (exactly 2 both times).
  - Adjacency (box breakout's confirm-day check, the 5-day volume average, and swing-low neighbor checks) is always done by list position, never by calendar-date arithmetic, so a suspension gap is skipped rather than interpolated — verified by a fixture with a deliberate 1-day calendar gap between the breakout day and its confirming day.
  - `insufficientData` is computed once per stock from the count of bars strictly before `startDate` (bounded by each detector's own required lookback), independent from and prior to any per-day pattern evaluation, keeping it strictly separate from "scanned but no match."
  - Full suite: `mvn -f develop/backend/pom.xml test` → 120 tests, 0 failures, 0 errors (98 pre-existing + 22 new). Live DB verified unchanged after the run: `stock`=34 (all `is_active=1`), `stock_daily_price`=5372, `stock_daily_indicator`=5372, `stock_sync_progress`=68.
  - Nothing deliberately left unfixed.

### Increment 2 — 2026-09-02

Implements the remaining 14 unchecked Acceptance Criteria: a third pattern, `RISING_SUPPORT` (上漲支撐), added as a third `PatternDetector` implementation alongside the existing two, with no structural change to the scan pipeline other than optionally fetching a small confirmation window *after* `endDate`.

- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/service/pattern/RisingSupportDetector.java` (new) — the third `PatternDetector`. For each trading day D in range: D's close must exceed the highest close of the `lookback` trading days strictly before D; the rise from D-1's close must meet `risePercent`; D-1's close is the support line; D+1 and D+2 (fixed at 2 trading days, never varying by preset) must both close strictly above it. No volume check.
  - `develop/backend/src/main/java/com/stock/service/pattern/PatternDetector.java` — added a `default int requiredConfirmTradingDaysAfterEndDate(String presetCode)` method (default `0`, overridden only by `RisingSupportDetector` to return the fixed confirm length) so the scan pipeline knows to fetch confirmation bars after `endDate`; existing implementations (`BoxBreakoutDetector`, `HigherLowsDetector`) needed no changes
  - `develop/backend/src/main/java/com/stock/service/pattern/PatternDetectionOutcome.java` — javadoc updated to note RISING_SUPPORT also produces `pendingConfirm`
  - `develop/backend/src/main/java/com/stock/service/StrategyScanService.java` — `loadSeries` now also computes `maxConfirmAfter` across the selected strategies and, only when non-zero, issues one additional batched window-function query for the nearest trading days strictly after `endDate` (appended to each stock's series so index-adjacency still holds); query count stays at 2 for scans that don't include `RISING_SUPPORT`, unchanged from increment 1
  - `develop/backend/src/main/java/com/stock/mapper/StockDailyPriceMapper.java` + `develop/backend/src/main/resources/mapper/StockDailyPriceMapper.xml` — added `findRecentAfterDateByStockIds`, the mirror-image of the existing `findRecentBeforeDateByStockIds` (`ROW_NUMBER() OVER (PARTITION BY stock_id ORDER BY trade_date ASC)`, batched across all target stock ids)
  - `develop/backend/src/main/java/com/stock/dto/RisingSupportDetailDto.java` (new) — `supportClose`/`riseClose`/`risePercent`/`priorHighClose`/`confirmCloses`
  - `develop/backend/src/main/java/com/stock/dto/ConfirmCloseDto.java` (new) — one `{tradeDate, close}` entry of `confirmCloses`
  - `develop/backend/src/main/java/com/stock/dto/StrategyDto.java`, `develop/backend/src/main/java/com/stock/dto/StrategyHitDto.java` — javadoc updated for the third strategy/detail type
  - `develop/backend/src/test/java/com/stock/StrategyScanIntegrationTest.java` — added 14 tests for RISING_SUPPORT (catalogue wording, hand-calculated hit, support-line-equality rejection, breakout-above-lookback-high condition, risePercent/lookback varying by preset, confirmation fixed at 2 days, pendingConfirm when confirm data is missing vs. available after `endDate`, insufficientData, no-volume-check, three-strategies-together ordering, signalDate-is-D-not-D+2, no advice wording); also corrected the pre-existing catalogue test's now-stale `assertEquals(2, ...)` strategy count to `3` (renamed to `catalog_containsBoxBreakoutAndHigherLowsWithThreePresetsEachMatchingSpecWording`) since the catalogue legitimately grew — its BOX_BREAKOUT/HIGHER_LOWS wording assertions were left untouched

- Notes:
  - `design-patterns` skill reviewed before starting: `RISING_SUPPORT` is a third implementation of the existing `PatternDetector` Strategy interface — no new abstraction was introduced, matching the skill's guidance to extend an established seam rather than invent a parallel one.
  - Followed the increment-1 convention exactly: `RisingSupportDetector` owns both its STRICT/STANDARD/LOOSE parameter table *and* the literal catalogue description text per preset in one `Params` record, so `GET /api/strategies` and the scan can never drift apart. `confirmBars` is stored in `Params` too even though it is the same value (2) for every preset — the fact that it does *not* vary by sensitivity is itself part of the parameter table, not an implicit assumption.
  - The support line is deliberately D-1's close (the rise's own launch point), never the lookback high — implemented as `bars.get(i - 1).getClosePrice()`, read fresh per D rather than reusing the lookback-window max computed for the breakout check.
  - `D+1`/`D+2` must close *strictly* above the support line — implemented with `compareTo(supportClose) <= 0` failing the check, so an exact match does not count as holding; covered by a dedicated test constructing D+1's close exactly equal to D-1's close.
  - Confirmation data may come from after `endDate`: the batched read fetches up to 2 trading days after `endDate` only when a selected detector declares `requiredConfirmTradingDaysAfterEndDate() > 0`, keeping the box-breakout/higher-lows-only path's query count at 2 (verified: the pre-existing AC15 query-count test, which only selects `HIGHER_LOWS`, still passes unchanged). D itself is still only ever evaluated within `[startDate, endDate]` — the detector's per-day loop `break`s the moment a bar's `tradeDate` is after `endDate`, so a post-`endDate` bar is used only to confirm an earlier D and is never itself treated as a candidate D.
  - Every trading day in range is evaluated as a candidate D independently (matching increment 1's `BoxBreakoutDetector` design) — a stock can have several candidate D's within one scanned range, some of which might independently be `pendingConfirm` while an earlier D in the same range already completed as a real hit. Per the existing `PatternDetectionOutcome` contract (unchanged from increment 1), a real hit always wins: the final outcome only reports `pendingConfirm` when *no* D in the whole range produced a hit. This surfaced during test-writing (`risingSupport_confirmEqualToSupportClose_doesNotCount` initially failed because its own D+2 accidentally formed a second, pending-confirm candidate) and was fixed by adjusting the *test fixture*, not the detection logic, since the behavior matches the spec and increment 1's precedent.
  - No volume field is read or compared anywhere in `RisingSupportDetector` — verified with a dedicated test running two otherwise-identical fixtures differing only in `volume` (100 vs. 999999) and asserting identical `matchedCount`.
  - Full suite: `mvn -f develop/backend/pom.xml test` → **166 tests, 0 failures, 0 errors** (152 pre-existing + 14 new).
  - Live-verified against the real dev DB and a real `mvn spring-boot:run` on port 8080 (stopped before finishing, confirmed via `netstat`):
    - `GET /api/strategies` → 3 strategies, `RISING_SUPPORT` named "上漲支撐" with the exact 3 preset descriptions from the spec.
    - `POST /api/strategies/scan` with `{"strategies":[{"code":"RISING_SUPPORT","preset":"LOOSE"},{"code":"BOX_BREAKOUT","preset":"STANDARD"},{"code":"HIGHER_LOWS","preset":"LOOSE"}],"stockIds":["2330"],"startDate":"2026-06-01","endDate":"2026-08-30"}` against real TSMC price history returned a genuine `RISING_SUPPORT` hit (`signalDate: 2026-07-31`, `supportClose: 2205.00`, `riseClose: 2425.00`, `risePercent: 9.98`, `priorHighClose: 2350.00`, `confirmCloses` on 2026-08-03/08-04) computed entirely from real data, plus results for all three strategies in submitted order.
    - Error paths re-verified live: unknown preset (`RISING_SUPPORT:BOGUS`) → `400 UNKNOWN_STRATEGY`; duplicate `RISING_SUPPORT` selections → `400 DUPLICATE_STRATEGY`.
    - DB left unchanged: no `SS`-prefixed test rows remain (`SELECT COUNT(*) FROM stock WHERE stock_id LIKE 'SS%'` → `0`); `stock`/`stock_daily_price` row counts grew only from the app's own pre-existing startup master-sync job (unrelated to this change, confirmed present before this session per the git log), not from anything this increment wrote.
  - Nothing deliberately left unfixed.
