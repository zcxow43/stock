---
status: pending
title: "策略型態掃描 API"
requirement: "策略分頁 — 勾選策略（底底高、箱型突破、上漲支撐、反彈、累積上漲）對股票掃描並列出命中標的；底底高改以 MA5（5 日收盤均線）平滑線為判定基準找擺動低點、遞增幅度亦以 MA5 值比較，原始最低價僅一併回報供對照；底底高／箱型突破／上漲支撐各可選三種靈敏度且漲幅門檻可自行輸入覆寫；累積上漲自行輸入回看天數與漲幅門檻；反彈改為自行輸入「下跌天數／跌幅門檻」與「反彈天數／反彈幅度」，後者為可關閉的選用條件，關閉時只以跌幅判定，兩者皆不再有靈敏度。掃描母體預設只含上市普通股（排除 ETF／特別股／TDR），掃描區間預設近一個月且可自由指定；`risePercent` 的上限改為逐型態認定——箱型突破／底底高／上漲支撐為 0~20，反彈／累積上漲為 0~50"
depends_on: [stock-price-ingestion, stock-catalog]
---

# 策略型態掃描 API — Backend Spec

## Overview

在既有日線行情上做**型態偵測**，回報哪些股票在指定區間內出現了某個型態。目前支援五個型態：底底高（`HIGHER_LOWS`）、箱型突破（`BOX_BREAKOUT`）、上漲支撐（`RISING_SUPPORT`）、反彈（`REBOUND`）與累積上漲（`CUMULATIVE_RISE`）。

此模組**只讀不寫**：輸入是 `stock_daily_price` 的 OHLCV，輸出是即時算出的命中清單，不落地任何結果表。理由是型態判定完全由參數決定，同一批行情換一組靈敏度就是另一組答案；把結果存起來會立刻面臨「這列是用哪組參數算的、參數改了要不要重算」的問題，而重算成本本來就低（單檔單區間只是一次順序掃描）。

**本模組回報的是型態偵測結果，不是買賣建議。** 回應欄位、UI 文案與產出文件一律以「命中／訊號／型態」表述，不得出現「建議買進」「推薦」等暗示操作的措辭。這是契約的一部分，不是文字風格偏好。

## Requirements

### 型態定義

其中三個型態（底底高、箱型突破、上漲支撐）各有三段靈敏度（`STRICT` / `STANDARD` / `LOOSE`），由呼叫端逐一指定。靈敏度只改門檻，不改判定邏輯。

**累積上漲與反彈都沒有靈敏度**，它們的參數全部由呼叫端直接指定：累積上漲為回看天數 `days` 與漲幅門檻 `risePercent`；反彈為下跌天數 `dropDays`、跌幅門檻 `dropPercent`、反彈天數 `riseDays`、反彈幅度 `risePercent`。理由是這兩個型態的參數本身就是使用者要調的全部，靈敏度在此只是「幾個數字的三組預設組合」——一旦每個數字都能自己填，那三段就不再表達任何判定上的差異，只是多一層要先選、選了又會被覆寫的中介。其餘三個型態的靈敏度仍決定回看長度、擺動根數、量能倍數、確認根數與盤整前提等多項參數，不能以少數幾個輸入取代，因此保持不變。

**有靈敏度的三個型態，其漲幅門檻可由呼叫端逐一覆寫**：請求中該策略的 `risePercent` 有值時，取代靈敏度表裡的漲幅欄位；省略時沿用靈敏度的值。靈敏度仍決定該型態的其餘參數（回看長度、擺動根數、量能倍數、確認根數、盤整前提）。各型態被覆寫的是哪一欄，註明在下方各自的參數表下。

覆寫只改門檻數值，一樣不改判定邏輯——`risePercent` 為 `0` 的效果等同靈敏度表裡漲幅為 0% 的那一段（不驗證漲幅），不是關閉整個型態。

**`risePercent` 的上限逐型態認定，不是全系統一個值**：

| 型態 | `risePercent` 範圍 | 它在該型態代表什麼 |
|---|---|---|
| 箱型突破 | `0` ~ `20` | 突破箱頂的幅度 |
| 底底高 | `0` ~ `20` | 每段擺動低點遞增的幅度 |
| 上漲支撐 | `0` ~ `20` | **單日**漲幅 |
| 反彈 | `0` ~ `50` | 自谷底起算的反彈幅度 |
| 累積上漲 | `0` ~ `50` | 窗口內的累積漲幅 |

上限之所以分成兩級，是因為這五個 `risePercent` 量的不是同一件事。上漲支撐看的是**單日**漲幅，而台股單日漲跌幅限制為 10%——允許填 30 只會讓使用者送出一個**在規則上不可能命中**的掃描，然後拿到零結果卻看不出原因。箱型突破的突破幅度與底底高的每段遞增幅度同理：到了 20% 以上已經不是那個型態在描述的行為。反彈與累積上漲量的是跨多個交易日的累積幅度，50% 在真實行情中確實會出現，因此保留較寬的上限。

**一個永遠不可能命中的請求應該被拒絕，而不是被受理後回零命中**——零命中與「參數填錯」在畫面上長得一模一樣，使用者無從分辨。反彈的 `dropPercent` 維持 `0` ~ `50`，理由同反彈的 `risePercent`。

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

**判定一律在 MA5 平滑線上進行**，不用原始最低價。MA5 為**收盤價**的 5 個交易日簡單移動平均：某交易日 D 的 MA5 是 D 之前（**含** D）連續 5 個交易日收盤價的算術平均；不足 5 根者該日無 MA5，不參與判定。

1. **擺動低點（swing low）**：某交易日 D 的 **MA5**，嚴格低於其左右各 `swingBars` 個交易日的 MA5。左右任一側資料不足 `swingBars` 根、或該範圍內任一日無 MA5 者，D 不列入。
2. 取區間內所有 swing low，依日期排序。
3. **命中**：存在連續 `requiredRises` 段遞增，即連續 `requiredRises + 1` 個 swing low 滿足每一個的 **MA5** 都高於前一個的 MA5，且 `後一個 MA5 ≥ 前一個 MA5 × (1 + risePercent)`。
4. 有多組符合時，取**日期最晚**的那一組回報。

**為什麼改用 MA5 而不是原始最低價**：單日最低價含大量盤中雜訊，一根長下影線就能造出一個假的擺動低點，使「底部逐步墊高」這個本來是趨勢層級的判斷被單日極端值主導。5 日均線把那層雜訊平掉，留下的谷是真的走勢轉折。代價是訊號會晚 2～3 個交易日才成形（均線本身有落後性），這是刻意接受的取捨——這個型態問的是「底部有沒有在墊高」，不是「今天有沒有觸底」。

**MA5 於掃描當下由已讀入的日線即時計算，不落地任何資料表**。本型態只需要每檔多讀 4 根前置行情，而掃描本來就已批次讀入整段區間的收盤價；為此在 `stock_daily_indicator` 加一個欄位，會逼出一次全表回填、把型態掃描綁上指標運算的推進節奏，換來的只是一個五筆加總的平均值。**不得新增資料表欄位、不得改動 `stock_daily_indicator`。**

**MA5 取收盤價、不取最低價**：市面通稱的 5 日均線就是收盤均線，換成最低價均線會讓同一個名詞在本系統裡代表另一條線。判定與回報一律以這條收盤 MA5 為準。

**遞增幅度比較的是 MA5 值，不是原始最低價**：找點與比幅度用同一條線，否則會出現「MA5 判定為抬高、當日原始最低價卻更低」這種自相矛盾的命中。原始最低價仍會一併回報供對照（見回應欄位），但不參與任何判定。

| 參數 | `STRICT` | `STANDARD` | `LOOSE` |
|---|---|---|---|
| `swingBars`（左右各） | 5 | 3 | 2 |
| `requiredRises`（遞增段數） | 3 | 2 | 2 |
| `risePercent` | 2% | 1% | 0%（高過即可） |

請求的 `risePercent` 覆寫本表的 **`risePercent`**（每段遞增的最小幅度）。`swingBars` 與 `requiredRises` 一律由靈敏度決定。**平滑天數固定為 5，不隨靈敏度改變、也不開放呼叫端指定**——本型態的基準線就是 5 日均線這一條，可調的是在這條線上怎麼認定擺動與遞增。

**容忍度不可省略的理由**：`risePercent` 為 0 時，高出 0.01 元也構成「底底高」，雜訊會全部變成訊號。`LOOSE` 刻意允許這件事，其餘兩段不允許。改用 MA5 後雜訊已大幅降低，但沒有消失——均線之間仍可能只差千分之一，因此容忍度照樣保留。

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

#### 反彈 `REBOUND`

反彈由**跌段**與**漲段**兩個條件組成。漲段是**選用**的，由 `requireRise` 開關（預設 `true`）：關閉時只以跌幅判定。

**第一步 — 找出跌段的谷底 T**（對每個交易日 T 判定）：

1. **回看窗口**：T 之前（**含** T）連續 `dropDays` 個交易日。
2. **高點**：窗口內收盤價的最大值 H，其日期為 Hd。
3. **T 必須是低點**：T 的收盤價是 Hd **之後**（不含 Hd）至 T 為止的收盤價最小值。這道限制把谷底鎖在「這段下跌目前的最低那一天」，否則下跌過程中的每一天都會各自算成一個谷底。
4. **跌幅**：`(H − T 收盤) ÷ H` ≥ `dropPercent`。

**第二步 — 找出漲段的達標日 S**（`requireRise` 為 `true` 時才做）：

5. **反彈窗口**：T **之後**（不含 T）最多 `riseDays` 個交易日。
6. **漲幅**：窗口內第一個滿足 `(該日收盤 − T 收盤) ÷ T 收盤` ≥ `risePercent` 的交易日即為 S。
7. 窗口內找不到 S，該谷底就不命中。

**`signalDate` 是 S**，即反彈幅度達標的那一天，不是谷底那一天。`requireRise` 為 `false` 時沒有第二步，`signalDate` 退回為谷底 T 當日。

| 參數 | 預設 | 範圍 | 說明 |
|---|---|---|---|
| `dropDays` | 3 | `1`～`90` 的整數 | 跌段回看的交易日數，**含** T 本身 |
| `dropPercent` | 10% | `0`～`50`，最多一位小數 | 自窗口最高收盤起算的跌幅門檻 |
| `riseDays` | 1 | `1`～`90` 的整數 | 谷底之後容許反彈達標的交易日數，**不含** T 本身 |
| `risePercent` | 5% | `0`～`50`，最多一位小數 | 自谷底收盤起算的反彈幅度門檻 |

**漲段窗口尚未跑滿也照樣判定。** 谷底若落在最近幾個交易日，其 `riseDays` 窗口可能只過了一部分——只要**已達標就命中**，不等窗口跑滿，也不列入 `pendingConfirm`。窗口還沒跑滿且尚未達標的，就是單純未命中，往後的掃描自然會補上。這是為了讓最新、也最有參考價值的訊號看得見：要求等窗口跑滿，會讓它們一律從畫面上消失。

**漲幅以「谷底收盤 → 窗口內最高收盤」量測**，與跌幅的量測方式對稱——窗口內任何一天的收盤達標就算，不要求撐到窗口最後一天。只看最後一天會漏掉「衝上去又拉回」的反彈，而那段反彈確實發生過。

**本型態仍不保證反彈會延續。** 它回報的是「跌幅達標後、反彈幅度也達標」這個已發生的事實，不是「確認落底回升」——S 之後的價格可能再跌回去。使用者要進一步判斷，用結果表的分 K 連結自行檢視當日盤中走勢。

**跌幅以「窗口最高收盤 → 其後最低收盤」量測，而不是「首尾兩日收盤相減」**：後者會漏掉中間先大跌再拉回的形態——首尾恰巧相近時跌幅算出來接近 0，但那段下跌確實發生過。

#### 累積上漲 `CUMULATIVE_RISE`

與反彈完全對稱。對區間內每個交易日 D 判定，**D 是上漲段的最高點，也是回報的 `signalDate`**：

1. **回看窗口**：D 之前（**含** D）連續 `lookback` 個交易日。
2. **低點**：窗口內收盤價的最小值 L，其日期為 Ld。
3. **D 必須是高點**：D 的收盤價是 Ld **之後**（不含 Ld）至 D 為止的收盤價最大值。
4. **漲幅**：`(D 收盤 − L) ÷ L` ≥ `risePercent`。

兩個參數都由請求直接指定，沒有靈敏度可選：

| 參數 | 型別 | 預設 | 範圍 | 說明 |
|---|---|---|---|---|
| `days` | 整數 | `20` | `1` ~ `90` | 回看窗口的交易日數，即上表判定第 1 步的 `lookback` |
| `risePercent` | 數值 | `15` | `0` ~ `50`，最多一位小數 | 判定第 4 步的漲幅門檻，單位為 % |

兩個預設值沿用本型態原本「標準」那一段的值（回看 20 日、漲幅 15%），因此不指定任何參數時的行為與改動前完全相同。

**`days` 算的是交易日，不是日曆日**：窗口為 D 之前（含 D）連續 `days` 個**有行情的交易日**，中間的週末與休市日不佔位。判定所需的前置資料因此是 `startDate` 之前的 `days − 1` 個交易日。

**`days = 1` 是合法值，但幾乎不會命中**：窗口只有 D 一天時，窗口最低收盤 L 就是 D 自己的收盤，漲幅恆為 `0%`，因此只有在 `risePercent` 也是 `0` 時才會命中。這不是缺陷，是這個型態在只看一天時的必然結果——但它必須被寫明，否則使用者填了 `1` 得到零命中會以為是壞掉了。`days = 2` 起才是「與前一日相比」的實際比較。

**窗口長度超過該檔可用行情時，該檔計入 `insufficientData`，不視為未命中。** 目前行情自 `2026-01-01` 起累積（見 `specs/backend/stock-price-ingestion.md`），填入接近上限的天數時，資料較短的股票會落入這一類。

**與上漲支撐的分工**：上漲支撐抓的是**單日爆發**（單日漲幅達標、突破近期高點、其後兩日守住起漲價）；本型態抓的是**多日趨勢**（窗口內累積漲幅達標，每日只漲 1% 也算），且**不要求任何確認**。兩者的命中集合刻意不同——一個是爆發，一個是趨勢。若兩者都勾選，同一檔可能同時命中，這是正常的。

**本型態同樣不驗證漲勢是否延續**，理由與反彈相同。

### 掃描範圍

- `stockIds` 省略或為空陣列 → 掃描 `stock` 表中 `is_active = 1` 的股票，再依 `commonStocksOnly` 決定是否只留普通股。
- `stockIds` 有值 → 只掃描清單內的股票，且清單中允許包含已下市股票與非普通股（**使用者明確指名時不代掃描範圍過濾**，`commonStocksOnly` 一律不套用）。
- 上限 200 檔；超過即拒絕，而非默默截斷。

**普通股篩選（`commonStocksOnly`，預設 `true`）**：只留代號**恰為 4 位數字、且首字元非 `0`** 的股票。這道規則不是本 spec 新訂的，而是沿用 `specs/backend/stock-universe-import.md` 的「只收普通股」定義；同一套判斷同時排除 ETF（`0050`、`00878`）、特別股（`2881A`）與 TDR／存託憑證（`910322`）。兩處必須共用同一個判斷，不得各自實作一份。

預設為 `true` 的理由與該 spec 記載的一致：本模組的三個型態都是為個股價格行為設計的判定規則，ETF 的價格由一籃子成分股加權而成，其「箱型」「突破」「起漲」在意義上與個股不同；把數百檔 ETF 混進母體只會稀釋掃描結果，不會讓使用者多得到可用的訊號。`commonStocksOnly: false` 保留給明確想連 ETF 一起掃的情況。

**本篩選只影響掃描母體，不寫入任何資料表**——`stock` 的內容、`is_active`、以及股票總覽顯示的檔數，都不因這個參數而改變。

### 區間與資料前置需求

- `startDate` / `endDate` 皆省略時，區間為 `endDate = 今日`、`startDate = 今日往前一個日曆月`。
- **判定所需的前置資料取自 `startDate` 之前**：箱型突破需要 `lookback` 個交易日、底底高需要 `swingBars + 4` 個交易日（`swingBars` 供左側比較，另 4 根供最左那一日算出 MA5）、上漲支撐需要 `lookback` 個交易日、反彈需要 `dropDays − 1 + riseDays` 個交易日（谷底最早可落在 `startDate` 往前 `riseDays` 個交易日處，其跌段窗口再往前 `dropDays − 1` 個交易日；`requireRise` 為 `false` 時只需 `dropDays − 1`）、累積上漲需要 `days − 1` 個交易日。這些資料只用於判定，不會被回報為命中。
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
        { "code": "STRICT",   "name": "嚴格", "description": "以 5 日均線為基準，左右各 5 根，需 3 段遞增，每段高過 2%" },
        { "code": "STANDARD", "name": "標準", "description": "以 5 日均線為基準，左右各 3 根，需 2 段遞增，每段高過 1%" },
        { "code": "LOOSE",    "name": "寬鬆", "description": "以 5 日均線為基準，左右各 2 根，需 2 段遞增，高過即計" }
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
    },
    {
      "code": "REBOUND",
      "name": "反彈",
      "description": "先在回看窗口內自最高收盤跌幅達門檻築出谷底，其後指定天數內自谷底反彈幅度達門檻",
      "presets": [],
      "paramGroups": [
        { "code": "rise", "name": "另外要求反彈漲幅", "default": true }
      ],
      "params": [
        { "code": "dropDays",    "name": "下跌天數", "unit": "日", "default": 3,  "min": 1, "max": 90, "step": 1   },
        { "code": "dropPercent", "name": "跌幅門檻", "unit": "%",  "default": 10, "min": 0, "max": 50, "step": 0.1 },
        { "code": "riseDays",    "name": "反彈天數", "unit": "日", "default": 1,  "min": 1, "max": 90, "step": 1,   "group": "rise" },
        { "code": "risePercent", "name": "反彈幅度", "unit": "%",  "default": 5,  "min": 0, "max": 50, "step": 0.1, "group": "rise" }
      ]
    },
    {
      "code": "CUMULATIVE_RISE",
      "name": "累積上漲",
      "description": "回看指定天數，自窗口內最低收盤累積漲幅達門檻的最高點",
      "presets": [],
      "params": [
        { "code": "days",        "name": "天數",     "unit": "日", "default": 20, "min": 1, "max": 90, "step": 1 },
        { "code": "risePercent", "name": "漲幅門檻", "unit": "%",  "default": 15, "min": 0, "max": 50, "step": 0.1 }
      ]
    }
  ]
}
```

前端的策略選單、靈敏度說明文字、以及無靈敏度型態的參數預設值與範圍，一律取自此端點，不在前端寫死——參數改動時只需改後端一處。

`presets` 與 `params` 的關係：
- **有靈敏度的型態**：`presets` 為三段，`params` 為空陣列或不出現；卡片上顯示的說明文字取自目前選定那一段的 `description`。
- **無靈敏度的型態**（目前為累積上漲與反彈）：`presets` 為**空陣列**，`params` 列出該型態的每一個可輸入參數；卡片上顯示的說明文字取自策略層級的 `description`。
- **選用參數群組**：`params` 的項目可帶 `group`，指向 `paramGroups` 中同 `code` 的一筆。同一群組的參數是一組可整組開關的選用條件，`paramGroups[].default` 是開關的預設狀態，`name` 是開關要顯示的文字。不帶 `group` 的參數一律生效、不可關閉。目前只有反彈用到（`rise` 群組），但這個機制不綁任何策略——前端依 `paramGroups` 畫開關即可，同樣不得以策略 `code` 寫死。

前端據此決定卡片長什麼樣：`presets` 非空就畫靈敏度下拉，為空就依 `params` 逐一畫輸入框。**不得以策略 `code` 寫死判斷**（例如「如果是 CUMULATIVE_RISE 就畫天數」）——那會讓下一個改成無靈敏度的型態必須再改一次前端。

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
    { "code": "RISING_SUPPORT", "preset": "STANDARD", "risePercent": 4 },
    { "code": "CUMULATIVE_RISE", "days": 30, "risePercent": 12 }
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
| `strategies[].code` | string | 是 | `BOX_BREAKOUT` / `HIGHER_LOWS` / `RISING_SUPPORT` / `REBOUND` / `CUMULATIVE_RISE` |
| `strategies[].preset` | string | 視型態而定 | `STRICT` / `STANDARD` / `LOOSE`。**有靈敏度的三個型態必填；`CUMULATIVE_RISE` 與 `REBOUND` 不得帶**（兩者沒有靈敏度可選） |
| `strategies[].days` | int | 否 | **只有 `CUMULATIVE_RISE` 接受本欄位**，其餘型態帶了視為無效。回看窗口的交易日數，整數，範圍 `1`～`90`；省略時為 `20` |
| `strategies[].requireRise` | boolean | 否 | **只有 `REBOUND` 接受本欄位**。是否套用漲段條件；省略時為 `true`。為 `false` 時 `riseDays` 與 `risePercent` 不得帶 |
| `strategies[].dropDays` | int | 否 | **只有 `REBOUND` 接受本欄位**。跌段回看的交易日數，整數，範圍 `1`～`90`；省略時為 `3` |
| `strategies[].dropPercent` | number | 否 | **只有 `REBOUND` 接受本欄位**。跌幅門檻，範圍 `0`～`50`，最多一位小數；省略時為 `10` |
| `strategies[].riseDays` | int | 否 | **只有 `REBOUND` 接受本欄位**，且 `requireRise` 為 `true` 時才可帶。反彈窗口的交易日數，整數，範圍 `1`～`90`；省略時為 `1` |
| `strategies[].risePercent` | number | 否 | 幅度門檻，最多一位小數，**上限依型態而定**：箱型突破／底底高／上漲支撐 `0`～`20`，反彈／累積上漲 `0`～`50`（見「型態定義」的上限表）。對**有靈敏度的三個型態**是覆寫該靈敏度的漲幅欄位，省略即沿用靈敏度的值；對 `CUMULATIVE_RISE` 是窗口內的累積漲幅門檻，省略時為 `15`；對 `REBOUND` 是**自谷底起算的反彈幅度門檻**，省略時為 `5`，且 `requireRise` 為 `false` 時不得帶 |
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
              { "tradeDate": "2026-07-08", "ma5": 243.10, "low": 240.00 },
              { "tradeDate": "2026-07-29", "ma5": 251.30, "low": 248.50 },
              { "tradeDate": "2026-08-13", "ma5": 258.80, "low": 256.00 },
              { "tradeDate": "2026-08-25", "ma5": 265.20, "low": 262.50 }
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
    },
    {
      "strategy": "REBOUND",
      "requireRise": true,
      "dropDays": 3,
      "dropPercent": 10,
      "riseDays": 1,
      "risePercent": 5,
      "matchedCount": 1,
      "items": [
        {
          "stockId": "2603",
          "stockName": "長榮",
          "signalDate": "2026-08-26",
          "detail": {
            "peakDate": "2026-08-21",
            "peakClose": 120.00,
            "troughDate": "2026-08-25",
            "troughClose": 100.00,
            "dropPercent": 16.67,
            "risePercent": 6.00
          }
        }
      ],
      "insufficientData": [],
      "pendingConfirm": []
    },
    {
      "strategy": "CUMULATIVE_RISE",
      "days": 20,
      "matchedCount": 1,
      "items": [
        {
          "stockId": "3231",
          "stockName": "緯創",
          "signalDate": "2026-08-28",
          "detail": {
            "troughDate": "2026-08-05",
            "troughClose": 80.00,
            "peakClose": 100.00,
            "risePercent": 25.00
          }
        }
      ],
      "insufficientData": [],
      "pendingConfirm": []
    }
  ]
}
```

- `results` 依 `strategies` 送入的順序回傳，一個策略一筆。每一筆原樣回報該策略**實際採用**的參數（省略時回實際採用的預設值）：有靈敏度的型態回 `preset`；累積上漲回 `days`；反彈回 `requireRise`、`dropDays`、`dropPercent`，並在 `requireRise` 為 `true` 時另回 `riseDays` 與 `risePercent`。`preset` 與這些參數欄位不同時出現。
- `items` 依 `signalDate` 由新到舊排序；同日則依 `stockId` 升冪。
- `signalDate` 為該檔在區間內**最近一次**命中的日期；同一檔在區間內多次命中只回報最近一次。
- `pendingConfirm` 可能非空的情況有二：箱型突破且 `confirmBars = 2`（已突破但確認日尚未到），以及上漲支撐（已上漲但 D+1／D+2 尚未到齊）。兩者都列出該檔的股票代號，且不計入 `matchedCount`。**反彈與累積上漲一律不產生 `pendingConfirm`**，其 `pendingConfirm` 恆為空陣列。反彈雖然有漲段條件，但採「已達標就命中、不等窗口跑滿」（見型態定義），因此不存在待確認狀態。
- `insufficientData` 與 `matchedCount` 互斥：列在前者的股票不會出現在 `items` 中。
- 底底高的 `detail.lows` 每筆有三個欄位：`tradeDate`、`ma5`（判定所依據的 5 日收盤均線值）、`low`（該日原始最低價，僅供對照，不參與判定）。`ma5` 與 `low` 皆為兩位小數。

驗證與錯誤：
- `strategies` 為空或缺漏 → `400`，`{"code":"NO_STRATEGY_SELECTED"}`
- 未知的 `code` 或 `preset` → `400`，`{"code":"UNKNOWN_STRATEGY","unknown":["FOO"]}`
- 有靈敏度的型態缺 `preset` → `400`，`{"code":"UNKNOWN_STRATEGY","unknown":["BOX_BREAKOUT"]}`
- 對**沒有靈敏度的型態**（`CUMULATIVE_RISE`、`REBOUND`）帶了 `preset` → `400`，`{"code":"PRESET_NOT_APPLICABLE","strategy":"REBOUND"}`
- 對 `CUMULATIVE_RISE` 以外的型態帶了 `days` → `400`，`{"code":"DAYS_NOT_APPLICABLE","strategy":"REBOUND"}`
- 對 `REBOUND` 以外的型態帶了 `requireRise`／`dropDays`／`dropPercent`／`riseDays` → `400`，`{"code":"PARAM_NOT_APPLICABLE","strategy":"BOX_BREAKOUT","param":"dropDays"}`。`param` 指出是哪一個欄位不適用。（`days` 沿用既有的 `DAYS_NOT_APPLICABLE` 而非併入本碼：該碼已隨累積上漲上線並有測試涵蓋，改名只是無謂的破壞性變更。）
- 對 `REBOUND` 在 `requireRise` 為 `false` 時仍帶了 `riseDays` 或 `risePercent` → `400`，`{"code":"PARAM_NOT_APPLICABLE","strategy":"REBOUND","param":"riseDays"}`。關掉漲段卻又送漲段參數，代表呼叫端對自己要什麼並不一致，靜默忽略會讓使用者以為那個數字有生效
- `dropDays` 非整數、小於 `1` 或大於 `90` → `400`，`{"code":"INVALID_DROP_DAYS","strategy":"REBOUND"}`
- `riseDays` 非整數、小於 `1` 或大於 `90` → `400`，`{"code":"INVALID_RISE_DAYS","strategy":"REBOUND"}`
- `dropPercent` 小於 `0`、大於 `50`、或小數超過一位 → `400`，`{"code":"INVALID_DROP_PERCENT","strategy":"REBOUND"}`
- `days` 非整數、小於 `1` 或大於 `90` → `400`，`{"code":"INVALID_DAYS","strategy":"CUMULATIVE_RISE"}`。與 `INVALID_RISE_PERCENT` 同理，`strategy` 必須指名是哪一張卡片的值不合法
- 同一 `code` 重複出現 → `400`，`{"code":"DUPLICATE_STRATEGY","duplicated":["BOX_BREAKOUT"]}`
- `stockIds` 含 `stock` 主檔不存在的代號 → `400`，`{"code":"UNKNOWN_STOCK_ID","unknownIds":["9999"]}`
- `stockIds` 超過 200 檔 → `400`，`{"code":"TOO_MANY_STOCKS"}`
- `startDate` 晚於 `endDate` → `400`，`{"code":"INVALID_DATE_RANGE"}`
- `risePercent` 小於 `0`、超過**該型態的上限**（箱型突破／底底高／上漲支撐 `20`，反彈／累積上漲 `50`）、或小數超過一位 → `400`，`{"code":"INVALID_RISE_PERCENT","strategy":"RISING_SUPPORT"}`。`strategy` 必須指出是哪一個策略的值不合法——每張卡片各有一個獨立輸入，不指名的話使用者無從得知該改哪一格

### 處理流程

解析並驗證請求 → 決定目標股票清單（指定清單；或全市場在市再依 `commonStocksOnly` 過濾）→ 對每個策略取判定參數（有靈敏度者取該靈敏度那一組、再以該策略的 `risePercent` 覆寫其漲幅門檻；累積上漲取請求的 `days`／`risePercent` 或其預設值；反彈取請求的 `requireRise`／`dropDays`／`dropPercent`／`riseDays`／`risePercent` 或其預設值） → 對每檔股票讀取 `startDate` 前置區間起算至 `endDate` 的日線 → 逐日套用判定 → 收斂為每檔最近一次命中 → 組裝回應。

**行情讀取必須批次進行**，不得逐檔一次查詢：全市場掃描是 2200 檔，逐檔查詢即 2200 次往返。以單一查詢按 `(stock_id, trade_date)` 主鍵範圍取回目標區間的全部列，再在記憶體中依股票分組判定。

## Acceptance Criteria

### 型態目錄（`GET /api/strategies`）
- [x] `GET /api/strategies` 回傳五個策略：箱型突破、底底高、上漲支撐、反彈、累積上漲；有靈敏度的三個（箱型突破、底底高、上漲支撐）各三段，說明文字與本 spec 的參數表一致
- [x] `CUMULATIVE_RISE` 條目 `presets` 為空陣列，並帶策略層級 `description` 與 `params`（`days`：預設 20、範圍 1～90、step 1；`risePercent`：預設 15、範圍 0～50、step 0.1）
- [x] `REBOUND` 條目 `presets` 為空陣列，帶策略層級 `description`、`paramGroups`（一筆：`rise`，`default` 為 `true`）與四個 `params`：`dropDays`（預設 3、範圍 1～90、step 1）、`dropPercent`（預設 10、範圍 0～50、step 0.1）、`riseDays`（預設 1、範圍 1～90、step 1、`group` 為 `rise`）、`risePercent`（預設 5、範圍 0～50、step 0.1、`group` 為 `rise`）
- [x] 底底高／箱型突破／上漲支撐三個條目完全未變，`CUMULATIVE_RISE` 條目亦完全未變

### 掃描共通行為
- [x] `POST /api/strategies/scan` 省略 `startDate`／`endDate` 時，區間為「今日往前一個日曆月 ~ 今日」
- [x] 指定 `stockIds` 時只掃描清單內股票，且允許包含已下市股票
- [x] 前置資料不足的股票列於 `insufficientData`，且不出現在 `items`，也不計入 `matchedCount`
- [x] 同一檔在區間內多次命中時，`signalDate` 為最近一次
- [x] `items` 依 `signalDate` 由新到舊排序，同日依 `stockId` 升冪
- [x] 型態判定以相鄰交易日比較：區間內含停牌造成的日曆間隔時，判定結果與無間隔時一致（不做插補）
- [x] 全市場掃描時對資料庫的行情查詢為批次查詢，不隨股票數線性增加查詢次數
- [x] 五個策略可於同一次 `POST /api/strategies/scan` 一併送入，`results` 依送入順序回傳五筆

### 掃描母體（`commonStocksOnly`）
- [x] `commonStocksOnly` 省略時視為 `true`：不帶此欄位的全市場掃描，`scannedStocks` 只計代號恰為 4 位數字且首字元非 `0` 的股票
- [x] `commonStocksOnly: true` 時 `0050`、`00878`、`2881A`、`910322` 皆不在掃描母體中，也不出現在任何策略的 `items`、`insufficientData` 或 `pendingConfirm`
- [x] `commonStocksOnly: false` 時掃描母體為全部 `is_active = 1` 的股票，`scannedStocks` 明顯大於 `true` 時的值
- [x] 普通股判斷與 `specs/backend/stock-universe-import.md` 共用同一份實作，不存在第二套代號篩選邏輯
- [x] `stockIds` 有值時 `commonStocksOnly` 不生效：指定 `["0050","2330"]` 且 `commonStocksOnly: true`，兩檔都被掃描
- [x] 本參數不寫任何資料表：掃描前後 `stock` 的列數、內容與 `is_active` 完全不變
- [x] 三個策略同時送出、各帶不同 `risePercent`、且 `commonStocksOnly: true` 時，`results` 仍依送入順序回傳三筆

### 箱型突破
- [x] 箱型突破 `STANDARD`：以一組已知會在箱高 6%、突破 2%、量增 1.8 倍的構造資料驗證命中，且回應的 `boxHigh`／`boxLow`／`breakoutPercent`／`volumeRatio` 與手算相符
- [x] 箱型突破的盤整前提生效：一段穩定上升（箱高 15%）的資料在 `STANDARD` 下不命中，在 `LOOSE` 下命中
- [x] 箱型突破 `STRICT` 的 `confirmBars=2`：突破日為區間最後一天且無次日資料時，該檔列於 `pendingConfirm` 而非 `items`

### 底底高
- [x] 底底高 `STANDARD`：三個遞增幅度各 > 1% 的 swing low 命中，回應的 `lows` 為 3 筆且日期與價格正確
- [x] 底底高的容忍度生效：遞增幅度僅 0.3% 的資料在 `STANDARD` 下不命中，在 `LOOSE` 下命中
- [x] 擺動低點改在 **MA5（5 日收盤簡單移動平均）** 上判定：某日 D 的 MA5 嚴格低於其左右各 `swingBars` 個交易日的 MA5；原始最低價不再參與選點
- [x] MA5 取的是**收盤價**：以構造資料驗證，同一組行情下收盤均線與最低價均線的谷不同日時，命中的是收盤均線那一組
- [x] MA5 為 D 之前（含 D）連續 5 個交易日收盤價的算術平均；不足 5 根者該日無 MA5，不參與判定
- [x] 遞增幅度比較的是 **MA5 值**：`後一個 swing low 的 ma5 ≥ 前一個的 ma5 × (1 + risePercent)`；原始最低價即使反向變動也不影響命中與否
- [x] `detail.lows` 每筆回三個欄位 `tradeDate`／`ma5`／`low`，`ma5` 與 `low` 皆兩位小數；筆數仍為 `requiredRises + 1`
- [x] 平滑天數固定為 5：不隨靈敏度改變，請求也無任何欄位可指定；`GET /api/strategies` 的 `HIGHER_LOWS` 仍為三段 `presets`、無 `params`
- [x] 三段靈敏度的 `swingBars`／`requiredRises`／`risePercent` 數值未變（5/3/2、3/2/2、2%/1%/0%），僅說明文字加上「以 5 日均線為基準」
- [x] `risePercent` 覆寫的仍是每段遞增的最小幅度，範圍與驗證行為未變
- [x] 前置資料需求為 `swingBars + 4` 個交易日：`swingBars` 供左側比較、另 4 根供最左那一日算出 MA5；不足者列於 `insufficientData`，不列入 `items`、也不計入 `matchedCount`
- [x] 平滑降低雜訊的效果可驗證：一段含單日長下影線的行情，以原始最低價會產生擺動低點、改用 MA5 後該日不再成為擺動低點
- [x] MA5 以相鄰交易日計算，跨週末或停牌不做任何日曆插補，與本 spec 其餘型態的取樣規則一致
- [x] 多組符合時仍取日期最晚的那一組回報，此規則未因改用 MA5 而改變

### 上漲支撐
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
- [x] `RISING_SUPPORT` 的 `signalDate` 為上漲日 D 本身，不是確認完成日 D+2

### 反彈
- [x] 五個參數全部省略時，以 `dropDays: 3`、`dropPercent: 10`、`requireRise: true`、`riseDays: 1`、`risePercent: 5` 判定
- [x] `signalDate` 是**反彈達標那天**，不是谷底那天：命中結果的 `signalDate` 與 `detail.troughDate` 不同，且兩者相隔 1～`riseDays` 個交易日
- [x] 漲幅以「谷底收盤 → 窗口內最高收盤」量測：谷底後第 1 天衝高達標、第 2 天回落到門檻以下，`riseDays: 2` 仍命中，且 `signalDate` 為第 1 天
- [x] 窗口內有多天達標時取**最早**那一天為 `signalDate`，不是漲幅最大的那一天
- [x] 跌幅量測維持「窗口最高收盤 → 其後最低收盤」不變：首尾兩日收盤相近但中間曾大跌的資料仍找得出谷底
- [x] 谷底的「T 必須是低點」限制仍生效：一段連續下跌中只有目前最低那天成為谷底
- [x] `requireRise: false` 時只以跌幅判定，`signalDate` 等於谷底當日，`detail` 不含 `risePercent`
- [x] 漲段窗口未跑滿仍照判：谷底落在 `endDate` 前一個交易日、`riseDays: 3` 且已達標 → 命中；同情境未達標 → 未命中，且**不**列入 `pendingConfirm`
- [x] `REBOUND` 的 `pendingConfirm` 在所有情境下恆為空陣列
- [x] `dropDays` 與 `riseDays` 算的都是交易日不是日曆日：窗口跨越週末時，週末不佔窗口長度
- [x] 同一檔在區間內多次命中時仍只回報最近一次 `signalDate`
- [x] 前置資料需求為 `dropDays − 1 + riseDays` 個交易日（`requireRise: false` 時為 `dropDays − 1`）；不足者列於 `insufficientData`，不列入 `items`、也不計入 `matchedCount`
- [x] 掃描回應中 `REBOUND` 那一筆回 `requireRise`、`dropDays`、`dropPercent`（皆為實際採用值），`requireRise` 為 `true` 時另回 `riseDays` 與 `risePercent`，且**不含** `preset`
- [x] `detail` 為六個欄位：`peakDate`／`peakClose`／`troughDate`／`troughClose`／`dropPercent`／`risePercent`；`requireRise` 為 `false` 時不含 `risePercent`
- [x] `risePercent` 對 `REBOUND` 的語意由「覆寫跌幅門檻 `dropPercent`」改為「自谷底起算的反彈幅度門檻」：送 `risePercent: 5` 時以 5% 反彈判定，**不是** 5% 跌幅。要調跌幅改送 `dropPercent`

### 累積上漲
- [x] 累積上漲以預設參數（回看 20 日、漲幅門檻 15%）判定：窗口最低收盤 80（08-05）、其後最高收盤 100（08-28），漲幅 25% ≥ 15% 命中，`signalDate` 為 `2026-08-28`，`troughDate`／`troughClose`／`peakClose`／`risePercent` 與手算相符
- [x] 累積上漲不要求單日漲幅：連續 20 日每日各漲約 1%、無任何一日達 5% 的資料仍命中
- [x] 累積上漲的「D 必須是高點」條件生效：一段連續上漲中只有目前最高的那一天命中
- [x] `POST /api/strategies/scan` 對 `CUMULATIVE_RISE` 送 `days: 30` 時以 30 個交易日的窗口判定，命中結果與送 `days: 10` 時不同
- [x] `CUMULATIVE_RISE` 省略 `days` 時以 `20` 判定，且省略 `risePercent` 時以 `15` 判定
- [x] `days` 算的是交易日不是日曆日：窗口跨越週末時，週末不佔窗口長度
- [x] `days: 1` 為合法請求（不回 `400`）；此時除非 `risePercent` 為 `0`，否則零命中
- [x] `days` 大於某檔可用行情長度時，該檔列於 `insufficientData`，不列入 `items`、也不計入 `matchedCount`
- [x] 掃描回應中 `CUMULATIVE_RISE` 那一筆回 `days`（等於實際採用值）且不含 `preset`
- [x] 反彈與累積上漲的 `pendingConfirm` 恆為空陣列，不因 D 落在區間尾端而產生待確認

### 漲幅門檻覆寫（`risePercent`）
- [x] 三個策略各自的 `risePercent` 可獨立指定：同一次請求對箱型突破送 `2.5`、對上漲支撐送 `4`、底底高省略，三者分別以 2.5%／4%／該靈敏度原值判定
- [x] 省略 `risePercent` 時該策略的判定結果與未加本功能前完全一致（以既有三組構造資料驗證，命中集合不變）
- [x] `risePercent` 覆寫的是各型態參數表指定的那一欄：箱型突破改 `breakoutPercent`、底底高改每段遞增的 `risePercent`、上漲支撐改單日漲幅的 `risePercent`
- [x] `risePercent` 不影響其餘參數：同一策略在 `STRICT` 與 `LOOSE` 下送相同的 `risePercent`，`lookback`／`swingBars`／`volumeMultiple`／`confirmBars` 仍依各自靈敏度取值
- [x] `risePercent` 為 `0` 時等同不驗證漲幅，而非零命中或關閉該型態

### 驗證與錯誤
- [x] 六種錯誤各自回傳指定的 `code`：`NO_STRATEGY_SELECTED`／`UNKNOWN_STRATEGY`／`DUPLICATE_STRATEGY`／`UNKNOWN_STOCK_ID`／`TOO_MANY_STOCKS`／`INVALID_DATE_RANGE`
- [x] `days: 0`、`days: 91`、`days: 20.5` → `400`，`{"code":"INVALID_DAYS","strategy":"CUMULATIVE_RISE"}`
- [x] 對 `CUMULATIVE_RISE` 帶 `preset` → `400`，`{"code":"PRESET_NOT_APPLICABLE","strategy":"CUMULATIVE_RISE"}`
- [x] 對 `REBOUND` 帶 `preset` → `400`，`{"code":"PRESET_NOT_APPLICABLE","strategy":"REBOUND"}`
- [x] 對 `REBOUND`（或其餘三個型態）帶 `days` → `400`，`{"code":"DAYS_NOT_APPLICABLE","strategy":"REBOUND"}`
- [x] 對 `REBOUND` 以外的型態帶 `requireRise`／`dropDays`／`dropPercent`／`riseDays` → `400`，`{"code":"PARAM_NOT_APPLICABLE","strategy":"<該策略 code>","param":"<該欄位名>"}`
- [x] `requireRise: false` 又帶 `riseDays` 或 `risePercent` → `400`，`{"code":"PARAM_NOT_APPLICABLE","strategy":"REBOUND","param":"<該欄位名>"}`
- [x] `dropDays` 為 `0`、`91`、`3.5` → `400`，`{"code":"INVALID_DROP_DAYS","strategy":"REBOUND"}`
- [x] `riseDays` 為 `0`、`91`、`1.5` → `400`，`{"code":"INVALID_RISE_DAYS","strategy":"REBOUND"}`
- [x] `dropPercent` 為 `-1`、`50.1`、`10.55` → `400`，`{"code":"INVALID_DROP_PERCENT","strategy":"REBOUND"}`
- [x] `risePercent` 對 `REBOUND` 超出 `0`～`50` 或小數超過一位 → `400`，`{"code":"INVALID_RISE_PERCENT","strategy":"REBOUND"}`
- [x] `dropDays: 1` 為合法請求（不回 `400`）；此時窗口只有 T 本身、跌幅恆為 0，除非 `dropPercent` 為 `0` 否則零命中
- [x] 有靈敏度的三個型態缺 `preset` 仍為 `400`，其行為未因反彈與累積上漲的改動而放寬

### 用語
- [x] 箱型突破與底底高的回應欄位名與說明文字皆無「建議」「推薦」等暗示買賣操作的措辭
- [x] 上漲支撐的回應欄位名與說明文字皆無「建議」「推薦」等暗示買賣操作的措辭
- [x] 反彈與累積上漲的回應欄位名與說明文字皆無「進場」「出場」「建議」「推薦」等暗示買賣操作的措辭

### `risePercent` 上限改為逐型態（本次新增）
- [ ] 箱型突破／底底高／上漲支撐送 `risePercent: 20` 為合法，送 `20.1` 回 `400`，`{"code":"INVALID_RISE_PERCENT","strategy":"<該策略 code>"}`
- [ ] 反彈／累積上漲送 `risePercent: 50` 為合法，送 `50.1` 回 `400`，`{"code":"INVALID_RISE_PERCENT","strategy":"<該策略 code>"}`
- [ ] 反彈／累積上漲送 `risePercent: 30` 仍為合法（上限未一併收緊）
- [ ] 上漲支撐送 `risePercent: 30` 回 `400` 而非受理後回零命中——`strategy` 指名 `RISING_SUPPORT`
- [ ] 同一次請求中一個策略超限、其餘合法時，回應的 `strategy` 指名的是實際超限的那一個
- [ ] 反彈的 `dropPercent` 上限維持 `50` 不變，且其錯誤仍為 `INVALID_DROP_PERCENT`
- [ ] `risePercent` 為 `0` 與小數位數規則（最多一位）在五個型態上皆未改變
- [ ] `GET /api/strategies` 中 `CUMULATIVE_RISE` 與 `REBOUND` 的 `params[].max` 仍為 `50`，未因本次改動而變

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

### Increment 3 — 2026-09-04

Implements the remaining 27 unchecked Acceptance Criteria: a per-strategy `risePercent` override on `POST /api/strategies/scan`, a `commonStocksOnly` scan-population flag (default `true`, ignored when `stockIds` is given), and two new pattern types — `REBOUND` (反彈) and `CUMULATIVE_RISE` (累積上漲), each a 4th/5th implementation of the existing `PatternDetector` Strategy interface.

- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/service/pattern/PatternDetector.java` — `detect(...)` gained a `BigDecimal risePercentOverride` parameter (whole-percent, e.g. `2.5` for 2.5%, nullable meaning "use the preset's own value"); added a shared `default BigDecimal resolveRatio(BigDecimal risePercentOverride, BigDecimal presetRatio)` so the percent→ratio substitution isn't duplicated across five detectors
  - `develop/backend/src/main/java/com/stock/service/pattern/BoxBreakoutDetector.java`, `HigherLowsDetector.java`, `RisingSupportDetector.java` — updated `detect()` signature; each now calls `resolveRatio(...)` to substitute its own designated field (`breakoutPercent` / per-leg `risePercent` / single-day `risePercent`) — all other preset-driven parameters (`lookback`, `swingBars`, `volumeMultiple`, `confirmBars`) are untouched by the override
  - `develop/backend/src/main/java/com/stock/service/pattern/ReboundDetector.java` (new) — `REBOUND`, `@Order(4)`. For each trading day D (window = `lookback` days including D): find the window's highest close H (date Hd); D must be the lowest close from Hd (exclusive) through D (inclusive) — otherwise every day of an ongoing decline would each independently qualify; drop `(H − D)/H` must clear `dropPercent` (overridden by `risePercent`, reusing the same request field name per the spec's "欄位名沿用同一個以維持請求結構一致"). Never overrides `requiredConfirmTradingDaysAfterEndDate` (stays `0`), so `pendingConfirm` is always empty. `requiredLookbackTradingDays` returns `lookback − 1` since D itself is one of the window's bars.
  - `develop/backend/src/main/java/com/stock/service/pattern/CumulativeRiseDetector.java` (new) — `CUMULATIVE_RISE`, `@Order(5)`, the exact mirror image of `ReboundDetector` (lowest close L / date Ld → D must be the window's highest close from Ld exclusive through D inclusive → rise `(D − L)/L` must clear `risePercent`). Same no-confirm, `lookback − 1` lookback convention.
  - `develop/backend/src/main/java/com/stock/dto/ReboundDetailDto.java`, `CumulativeRiseDetailDto.java` (new) — `{peakDate, peakClose, troughClose, dropPercent}` / `{troughDate, troughClose, peakClose, risePercent}`
  - `develop/backend/src/main/java/com/stock/dto/StrategySelectionDto.java` — added `BigDecimal risePercent` (the per-strategy override)
  - `develop/backend/src/main/java/com/stock/dto/ScanRequestDto.java` — added `Boolean commonStocksOnly` (nullable so omission is distinguishable from an explicit value)
  - `develop/backend/src/main/java/com/stock/dto/ErrorResponse.java` — added a `strategy` field + `invalidRisePercent(String strategy)` factory for `INVALID_RISE_PERCENT`
  - `develop/backend/src/main/java/com/stock/dto/StrategyHitDto.java` — javadoc updated to list the two new detail types
  - `develop/backend/src/main/java/com/stock/exception/InvalidRisePercentException.java` (new)
  - `develop/backend/src/main/java/com/stock/exception/GlobalExceptionHandler.java` — added the `InvalidRisePercentException` handler
  - `develop/backend/src/main/java/com/stock/service/StrategyScanService.java` — `validateStrategies` now also validates each selection's `risePercent` (range `[0, 50]`, at most one decimal digit) *after* the existing unknown/duplicate checks pass, in submission order, so the reported `strategy` always names a real, known strategy; `resolveTargetStocks` gained a `commonStocksOnly` parameter — when `stockIds` is omitted and the flag is `true` (the default), the already-fetched active-stock list is filtered in-memory with `CommonStockCodeUtil.isCommonStockCode`, the exact same predicate the universe import and stock catalog use; the `stockIds`-given branch never reads the flag at all, matching "使用者明確指名時不代掃描範圍過濾"; `runStrategy` now passes `selection.getRisePercent()` into `detect(...)`
  - `develop/backend/src/test/java/com/stock/StrategyScanIntegrationTest.java` — added 34 new tests (listed below) plus 3 small fixes to pre-existing tests: two catalogue tests' stale `strategies.size()` assertions updated `3`→`5` (the catalogue legitimately grew again, same precedent as increment 2), and `scan_omittedStockIds_scansAllActiveStocks_scannedStocksMatchesActualCount` now explicitly sets `commonStocksOnly=false` since its synthetic `"SS..."` stock ids are not 4-digit codes and would otherwise be silently excluded by the new default-`true` filter — the test's own intent (is_active filtering) is unrelated to commonStocksOnly, so this restores it rather than weakening the AC.
- Notes:
  - `design-patterns` skill reviewed before starting: `REBOUND`/`CUMULATIVE_RISE` are a 4th and 5th implementation of the already-established `PatternDetector` Strategy interface — extending an existing seam, not inventing a new abstraction. The skill's guidance also covered the `resolveRatio` default method: since Java has no free functions, a shared `default` method on the interface (rather than a static utility class or five copies of the same three-line conversion) is the idiomatic way to give every implementation one shared, non-duplicated definition of "how a whole-percent override becomes a ratio."
  - `risePercent=0` needed no special-casing in any of the five detectors: because the override simply replaces the preset's own ratio field before the existing comparison runs, `0` naturally reproduces each strategy's own "壓根不驗證" semantics (BOX_BREAKOUT's LOOSE already stores `breakoutPercent=0` for exactly this effect; HIGHER_LOWS's LOOSE already stores `risePercent=0`) — same formula, same code path, no branch added for the zero case.
  - Live-verified `risePercent` genuinely changes outcomes, not just structurally: against real TSMC (2330) data, `RISING_SUPPORT`/`LOOSE` (preset threshold 2%) hit on a rise later hand-confirmed at 9.98% (`signalDate 2026-07-31`); sending `risePercent: 9` still hit (9.98% ≥ 9%), and sending `risePercent: 10` correctly produced zero hits (9.98% < 10%) on the identical fixture.
  - `commonStocksOnly` is implemented as an in-memory filter over the already-fetched active-stock list (one query either way — `stockMapper.findActiveStocks()` was already unfiltered before this increment), not a second SQL-level filter parallel to `StockMapper.findPage`'s `commonStockRegex` bind parameter; both ultimately read the one shared `CommonStockCodeUtil` regex, so there is exactly one filtering *rule*, expressed through two call sites appropriate to their own query shape (a paged catalog query vs. an already-materialized in-memory list for pattern scanning).
  - Live-verified against the real dev DB (1376 active stocks, 1085 of them ordinary/common) and a real `mvn spring-boot:run` on port 8080 (stopped after verification, confirmed via `lsof`):
    - `GET /api/strategies` → 5 strategies, `REBOUND` ("反彈") and `CUMULATIVE_RISE` ("累積上漲") each with the exact 3 preset descriptions from the spec.
    - `commonStocksOnly` omitted → `scannedStocks: 1085`; `commonStocksOnly: false` → `scannedStocks: 1376`; `stockIds: ["0050","2330"]` with `commonStocksOnly: true` → `scannedStocks: 2` (both scanned, flag ignored).
    - `INVALID_RISE_PERCENT` verified live for all three shapes, each naming the correct strategy: `risePercent: -1` on `REBOUND` → `{"code":"INVALID_RISE_PERCENT","strategy":"REBOUND"}`; `risePercent: 50.1` on `CUMULATIVE_RISE` → `..."strategy":"CUMULATIVE_RISE"`; `risePercent: 2.55` on `BOX_BREAKOUT` → `..."strategy":"BOX_BREAKOUT"`; `risePercent: 50` (the boundary) → `200 OK`.
    - All five strategies submitted together (`REBOUND`, `CUMULATIVE_RISE`, `BOX_BREAKOUT`, `HIGHER_LOWS`, `RISING_SUPPORT`, all `LOOSE`) against real 2454/2330/2317 price history returned genuine hits for every strategy computed from real data (e.g. `REBOUND` on 2454: `peakDate 2026-08-13`, `peakClose 4225.00`, `troughClose 3700.00`, `dropPercent 12.43`; `CUMULATIVE_RISE` on 2317: `troughDate 2026-07-30`, `troughClose 229.50`, `peakClose 270.00`, `risePercent 17.65`), with `pendingConfirm: []` for both new strategies on every stock as required, `results` in submitted order.
    - DB left unchanged by the scan calls themselves: `stock` row count and `is_active` distribution matched the pre-scan counts used for the `scannedStocks` assertions above; no `SS`-prefixed rows leaked from the live run (only from the JUnit suite, which cleans up in `@AfterEach`). `stock_daily_price` grew slightly during the session from the app's own independent startup price-sync job, unrelated to any scan request (the scan module issues only `SELECT`s — confirmed by code inspection of `StrategyScanService`/`StockDailyPriceMapper.xml`, and by increments 1–2's own established batched-read-only pattern, which this increment did not touch).
  - `code-quality` skill reviewed before finishing. One deliberate trade-off left as-is: `PatternDetector`'s two new constants (`PERCENT_DIVISOR`, `RATIO_SCALE`) are declared on the interface itself and are therefore implicitly `public static final`, inherited by all five implementing classes — a mild "constant interface" smell. Java interfaces cannot hold `private static` fields in any version (only `private` *methods*, since Java 9), so the only way to avoid this would be a separate tiny utility class purely to hide two numeric constants, which is more machinery than the problem warrants; left as an interface-level constant since it is internal to `com.stock.service.pattern` and never reaches the wire. One minor style fix was made during review: `commonStocksOnly` resolution in `StrategyScanService.scan()` originally called `request.getCommonStocksOnly()` twice — changed to `!Boolean.FALSE.equals(request.getCommonStocksOnly())`, a single call with the same null-means-true semantics.
  - Full suite: `mvn -f develop/backend/pom.xml test` → **224 tests, 0 failures, 0 errors** (166 pre-existing + 34 new + 24 unrelated pre-existing files unaffected). `StrategyScanIntegrationTest` alone: 65 tests, 0 failures.

### Increment 4 — 2026-09-08

本次執行的是「累積上漲改為自訂天數、移除三段靈敏度」增量（Acceptance Criteria 484–497），14 項全數完成。

**執行前的實際狀態**：commit `35519a4`（訊息宣稱已完成本增量）經查只更動 `docs/` 與 `specs/`，`CumulativeRiseDetector`／`StrategyScanService`／相關 DTO 仍是舊的三段靈敏度形狀。本次為真正的首次實作。

**完成內容**：
- `GET /api/strategies` 的 `CUMULATIVE_RISE` 改回 `presets: []`，並帶策略層級 `description` 與 `params`（`days`：預設 20、範圍 1～90、step 1；`risePercent`：預設 15、範圍 0～50、step 0.1）。其餘四個型態的條目未變。
- `days` 以交易日計算，窗口跨週末時週末不佔長度。省略 `days`／`risePercent` 時以 20／15 判定，結果與改動前「標準」段完全相同。
- 驗證：`days` 為 `0`／`91`／`20.5` → `400 INVALID_DAYS`（`days` 宣告為 `BigDecimal` 而非 `Integer`，使 `20.5` 能反序列化後回這個明確錯誤，而非泛用的解析錯誤）；`CUMULATIVE_RISE` 帶 `preset` → `400 PRESET_NOT_APPLICABLE`；其餘四型帶 `days` → `400 DAYS_NOT_APPLICABLE`；其餘四型缺 `preset` 仍為 `400`，未放寬。
- 回應形狀：`CUMULATIVE_RISE` 回 `days` 不回 `preset`，其餘四型回 `preset` 不回 `days`（`StrategyResultDto` 改 `@JsonInclude(NON_NULL)`，服務只填適用的那一個）。行情長度不足 `days` 的標的列入 `insufficientData`，不進 `items` 也不計入 `matchedCount`。

**過程中發現並修正的真實邏輯錯誤**：既有的「D 必須是自谷底以來最高」檢查在 D 本身即為窗口谷底時會無條件跳過，導致 `days: 1` 即使 `risePercent` 為 `0` 也永不命中（違反 AC 489）。已於 `CumulativeRiseDetector.detect` 特判窗口長度為 1 的情形。

**設計取向**：沿用既有的 `PatternDetector` Strategy seam，把 `usesPresets()`／`getDescription()`／`getParams()`／`getDaysMin/Max/Default()` 加為 **default** 介面方法，四個既有的 preset 型偵測器因此零改動，只有 `CumulativeRiseDetector` 覆寫。`StrategyScanService` 改為多型呼叫偵測器取得參數範圍，不再直接引用 `CumulativeRiseDetector` 的常數。

**驗證**：`mvn -f develop/backend/pom.xml test` — 305/305 通過（`StrategyScanIntegrationTest` 由 65 增至 79）。

**新增／變更檔案**：`PatternDetector`、`CumulativeRiseDetector`、`StrategyScanService`、`StrategyCatalogService`、`StrategyDto`、`StrategyResultDto`、`StrategySelectionDto`、`ErrorResponse`、`ParamDto`（新）、`PresetNotApplicableException`／`DaysNotApplicableException`／`InvalidStrategyDaysException`（新）、`GlobalExceptionHandler`、`StrategyScanIntegrationTest`。

### Increment 5 — 2026-09-08

本次一併執行兩個增量：反彈改為雙段參數（AC 40 項中的 28 項）與底底高改以 MA5 為判定基準（12 項），全數完成。

**反彈**：`presets` 改為空陣列，改由 `description`／`paramGroups`（`rise`，預設開啟）／四個 `params` 描述。判定改為兩階段——先在 `dropDays` 窗口內以「窗口最高收盤 → 其後最低收盤」找出谷底 T（「T 必須是低點」的限制保留），再於 T 之後 `riseDays` 個交易日內找出第一個自 T 收盤反彈達 `risePercent` 的日子 S；`signalDate` 為 S，`requireRise` 為 `false` 時退回 T。漲段窗口未跑滿時，已達標即命中、未達標即未命中，不產生 `pendingConfirm`。`detail` 增為六欄含 `troughDate`。新增 `PARAM_NOT_APPLICABLE`（帶 `param`）、`INVALID_DROP_DAYS`、`INVALID_RISE_DAYS`、`INVALID_DROP_PERCENT` 四種驗證錯誤，`PRESET_NOT_APPLICABLE` 擴及反彈，`DAYS_NOT_APPLICABLE` 維持原樣。`risePercent` 對反彈的語意由「覆寫跌幅門檻」改為「反彈幅度門檻」，此破壞性變更有專屬測試。

**底底高**：擺動低點與遞增幅度改在 MA5（收盤價 5 日簡單移動平均）上判定，MA5 於掃描當下由已讀入的收盤價即時計算，**未新增任何資料表欄位、未產生任何 migration**。`detail.lows` 每筆改為 `tradeDate`／`ma5`／`low` 三欄。三段靈敏度的數值完全未變，僅說明文字加上「以 5 日均線為基準」。前置資料需求改為 `swingBars + 4`。另有專屬測試以「長下影線但收盤不變」的構造資料證明該日在改用 MA5 後不再成為擺動低點。

**設計取向**：`PatternDetector` 的 `detect`／`requiredLookbackTradingDays` 重構為接受單一 selection 物件，取代原本「每個選用參數加一組 overload」的形狀——反彈帶進五個彼此獨立的欄位後，舊形狀已無法維持。

**過程中發現並修正的既有缺陷**：新的 `ReboundDetector` 在掃到 `close_price = 0.00` 的真實資料列時會除以零拋出 `ArithmeticException`（開發資料庫中確實存在這種列，例如代號 `1213`），已於跌幅與漲幅兩處除法加上防護。另修正 `paramGroups` 在非反彈策略上被序列化為 `[]` 而非省略的問題（介面 default 改回傳 `null`，與 `getDescription()` 一致）。

**已知且未處理**：`CumulativeRiseDetector` 與 `RisingSupportDetector` 的除數（`trough`／`supportClose`）有同樣的零價格風險。此為既有問題，兩個增量都未觸及，也不在本次任何驗收項範圍內，另行處理。

**驗證**：`mvn -f develop/backend/pom.xml test` — 321/321 通過（本次之前為 305/305；新增與改寫測試後淨增 16，其中包含替換掉數個驗證反彈舊靈敏度行為、已不再適用的測試）。

**變更檔案**：`ReboundDetector`（重寫）、`HigherLowsDetector`（重寫）、`PatternDetector`、`CumulativeRiseDetector`、`BoxBreakoutDetector`、`RisingSupportDetector`、`StrategyScanService`、`ReboundDetailDto`、`LowPointDto`、`StrategySelectionDto`、`StrategyResultDto`、`StrategyDto`、`ParamDto`、`ParamGroupDto`（新）、四個新例外類別、`GlobalExceptionHandler`、`ErrorResponse`、`StrategyScanIntegrationTest`。
