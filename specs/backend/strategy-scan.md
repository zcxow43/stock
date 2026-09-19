---
status: done
title: "策略型態掃描 API"
requirement: "策略分頁 — 勾選策略（底底高、箱型突破、上漲支撐、反彈、累積上漲）對股票掃描並列出命中標的；底底高改以 MA5（5 日收盤均線）平滑線為判定基準找擺動低點、遞增幅度亦以 MA5 值比較，原始最低價僅一併回報供對照；底底高／箱型突破／上漲支撐各可選三種靈敏度且漲幅門檻可自行輸入覆寫；累積上漲自行輸入回看天數與漲幅門檻；反彈改為自行輸入「下跌天數／跌幅門檻」與「反彈天數／反彈幅度」，後者為可關閉的選用條件，關閉時只以跌幅判定，兩者皆不再有靈敏度。掃描母體預設只含上市普通股（排除 ETF／特別股／TDR），掃描區間預設近一個月且可自由指定；`risePercent` 的上限改為逐型態認定——箱型突破／底底高／上漲支撐為 0~20，反彈／累積上漲為 0~50；每一筆命中另回報 `buyDate`（進場日）：上漲支撐為確認完成日 D+2，其餘型態等於訊號日；新增三個法人籌碼型態，皆可複選外資（不含外資自營商）與投信、各自判定且任一方達標即命中：法人買賣超佔比（近 windowDays 日買賣超合計取絕對值 ÷ 成交股數合計 ≥ ratioPercent，預設 5 日、10%）、法人連續買超（連續 buyDays 日每日買超，預設 5）、法人買超強度排名（近 windowDays 日買超合計 ÷ 成交股數合計，只有合計為買超者參與，每個交易日各取前 topN 名，預設 5 日、10 名）；三大法人日報收盤後才發布，三者的 buyDate 皆為訊號日的下一個交易日；新增兩個技術指標型態：MACD 黃金交叉（短期／長期 EMA 天數可自訂，預設 5／20，訊號線固定 9，DIF 由下往上穿越 DEA 當日為訊號日）與 KDJ 黃金交叉（KD 固定 9,3,3，J 由下往上同時穿越 K 與 D 當日為訊號日，且前一交易日 J 須低於可自訂門檻 jThreshold，預設 40）；兩者於掃描當下由日線即時運算、取 startDate 前最多 250 個交易日暖身，訊號日之前不足 100 個交易日者不判定，buyDate 等於訊號日"
depends_on: [stock-price-ingestion, stock-catalog, institutional-trade-ingestion, stock-indicator-statistics]
---

# 策略型態掃描 API — Backend Spec

## Overview

在既有日線行情上做**型態偵測**，回報哪些股票在指定區間內出現了某個型態。目前支援十個型態：五個價格型態——底底高（`HIGHER_LOWS`）、箱型突破（`BOX_BREAKOUT`）、上漲支撐（`RISING_SUPPORT`）、反彈（`REBOUND`）、累積上漲（`CUMULATIVE_RISE`）；三個法人籌碼型態——法人買賣超佔比（`INSTITUTIONAL_NET_RATIO`）、法人連續買超（`INSTITUTIONAL_CONSECUTIVE_BUY`）、法人買超強度排名（`INSTITUTIONAL_STRENGTH_RANK`）；以及兩個技術指標型態——MACD 黃金交叉（`MACD_GOLDEN_CROSS`）、KDJ 黃金交叉（`KDJ_GOLDEN_CROSS`）。

此模組**只讀不寫**：輸入是 `stock_daily_price` 的 OHLCV，以及法人籌碼型態另外讀取的 `stock_institutional_trade`（三大法人買賣超，見 `specs/dba/stock-institutional-trade.md`，由 `specs/backend/institutional-trade-ingestion.md` 寫入）；輸出是即時算出的命中清單，不落地任何結果表。理由是型態判定完全由參數決定，同一批行情換一組靈敏度就是另一組答案；把結果存起來會立刻面臨「這列是用哪組參數算的、參數改了要不要重算」的問題，而重算成本本來就低（單檔單區間只是一次順序掃描）。

**本模組回報的是型態偵測結果，不是買賣建議。** 回應欄位、UI 文案與產出文件一律以「命中／訊號／型態」表述，不得出現「建議買進」「推薦」等暗示操作的措辭。這是契約的一部分，不是文字風格偏好。

## Requirements

### 型態定義

其中三個型態（底底高、箱型突破、上漲支撐）各有三段靈敏度（`STRICT` / `STANDARD` / `LOOSE`），由呼叫端逐一指定。靈敏度只改門檻，不改判定邏輯。

**累積上漲、反彈、三個法人籌碼型態與兩個技術指標型態都沒有靈敏度**，它們的參數全部由呼叫端直接指定：累積上漲為回看天數 `days` 與漲幅門檻 `risePercent`；反彈為下跌天數 `dropDays`、跌幅門檻 `dropPercent`、反彈天數 `riseDays`、反彈幅度 `risePercent`；法人籌碼型態的參數見「法人籌碼型態」一節，技術指標型態的參數見「技術指標型態」一節（**這五者皆不接受 `risePercent`**——它們不看漲幅）。理由是這兩個型態的參數本身就是使用者要調的全部，靈敏度在此只是「幾個數字的三組預設組合」——一旦每個數字都能自己填，那三段就不再表達任何判定上的差異，只是多一層要先選、選了又會被覆寫的中介。其餘三個型態的靈敏度仍決定回看長度、擺動根數、量能倍數、確認根數與盤整前提等多項參數，不能以少數幾個輸入取代，因此保持不變。

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

### 法人籌碼型態

三個型態都以三大法人買賣超判定，**共用下列規則**，各自的判定條件見其下各小節。

#### 共通規則

**口徑**：

| 項目 | 取自 |
|---|---|
| 外資買賣超股數 | `stock_institutional_trade.foreign_net_shares`——外陸資買賣超，**不含外資自營商** |
| 投信買賣超股數 | `stock_institutional_trade.trust_net_shares` |
| 成交股數 | `stock_daily_price.volume` |

外資自營商與自營商的欄位不參與任何判定。

**法人複選（`investors`）**：每個法人籌碼型態都帶 `investors`，值為 `FOREIGN`（外資）、`TRUST`（投信）其中之一或兩者，至少一個；省略時為兩者。**勾選的每一方各自獨立判定，任一方達標即命中**；兩方都達標時仍只是一筆命中。命中筆的 `detail.matchedInvestors` 列出實際達標的一方（依 `FOREIGN`、`TRUST` 的固定順序），`detail.foreign`／`detail.trust` 為該方的判定數值，該方未勾選或未達標時為 `null`。

**窗口**：對區間內每個交易日 D、每一檔股票，窗口為該檔 D 之前（**含** D）連續 N 個交易日，N 依型態取 `windowDays` 或 `buyDays`。交易日依該檔 `stock_daily_price` 的相鄰資料列認定，與本 spec 其餘型態相同。

**每日買賣超**：窗口內每一日取該方當日的買賣超股數。該日法人資料**已抓取**（`stock_institutional_trade` 在該日有任何一列）、但沒有這一檔的列時，當日買賣超為 `0`——資料源只列出當日有法人交易的證券（見 `specs/backend/institutional-trade-ingestion.md`）。

**法人資料尚未抓取的日期**：窗口內任一日的法人資料尚未抓取（該日 `stock_institutional_trade` 沒有任何一列），該檔在 D **不判定**：不視為未達標，也不參與 D 的排名。這通常發生在最新的一兩個交易日（日報收盤後才發布）。每一筆結果以 `dataThroughDate` 表明法人資料實際涵蓋到哪一天，讓「最近兩天沒有命中」與「最近兩天還沒有法人資料」分得開。

**進場日**：`buyDate` 為該檔在 D **之後的下一個交易日**（下一筆日線資料列，停牌缺列不計，可晚於 `endDate`）。

**收斂**：同一檔在區間內多次命中時，回報**已有 `buyDate` 的命中中最近的一次**，`signalDate` 為該次的 D。

**`insufficientData` 與 `pendingConfirm`**：
- 區間內**沒有任何一個 D 能判定**的股票（D 之前的日線不足 N − 1 個交易日，或每一個窗口都含尚未抓取的日期）→ 列入 `insufficientData`。
- 至少命中過一次、但**每一次命中都還沒有下一個交易日的日線**（命中日就是該檔最新的一筆日線）的股票 → 列入 `pendingConfirm`，不出現在 `items`、不計入 `matchedCount`。這是日常最常見的情形：掃到今天，今天命中的股票要等明天收盤才有進場日。
- 同一檔若有較早、已有 `buyDate` 的命中，就以那一次列入 `items`，**不**再列入 `pendingConfirm`。

**為什麼 `buyDate` 是下一個交易日，不是 D 本身。** 五個價格型態都以收盤價判定，D 收盤的當下就已知道成立，在 D 收盤買進不含未來資訊。三大法人買賣超日報是**收盤後**才由交易所發布，D 收盤時看不到當天的法人進出；在 D 收盤買進，回測等於用了收盤後才公布的資料挑股票。最早能不使用未來資料的買點是下一個交易日的收盤（回測以 `buyDate` 收盤價買進，見 `specs/backend/strategy-backtest.md`）。

**比率一律是「合計 ÷ 合計」，不是每日比率的平均。** 成交量大的日子權重較高——法人在爆量日進出一百萬股與在量縮日進出一百萬股，對籌碼的意義本來就不同。每日比率取平均會讓一個量極小的日子把整段窗口的比率拉高，而那一天的絕對股數微不足道。比率回應時以百分比四捨五入至小數第二位，**判定與排名一律使用未經四捨五入的原值**。窗口成交股數合計為 `0`（資料異常）時不成立。

**成交量口徑會影響比率。** 分母取自 `stock_daily_price.volume`，而由 Yahoo 寫入的歷史列成交量系統性偏低（量比中位數約 `0.864`，見 `specs/backend/stock-price-ingestion.md` 的「價格處理」），落在這些列上的佔比與強度會被高估約一成多。三個型態都不為此做任何修正；消除方式是該 spec 已記載的一次性全市場重補（完成後 `source` 全數為 `TWSE`）。

**這三個型態回報的是籌碼面的偵測結果，不是買賣建議**，也不驗證法人進出之後股價是否上漲。

#### 法人買賣超佔比 `INSTITUTIONAL_NET_RATIO`

對每一個勾選的法人：

1. **窗口買賣超合計**：窗口內每日買賣超股數加總（買超為正、賣超為負，**先加總，互相抵銷**）。
2. **佔比**：`|窗口買賣超合計| ÷ 窗口成交股數合計`。
3. **命中**：佔比 **≥** `ratioPercent`，且窗口買賣超合計不為 `0`。
4. **方向**：合計為正回報 `BUY`（淨買超），為負回報 `SELL`（淨賣超）。

| 參數 | 型別 | 預設 | 範圍 | 說明 |
|---|---|---|---|---|
| `investors` | 字串陣列 | `["FOREIGN","TRUST"]` | `FOREIGN`／`TRUST`，至少一個、不重複 | 要判定的法人 |
| `windowDays` | 整數 | `5` | `1` ~ `20` | 窗口交易日數，**含** D |
| `ratioPercent` | 數值 | `10` | `0` ~ `100`，最多一位小數 | 佔比門檻，單位 % |

**買超與賣超都算命中。** 本型態偵測的是「法人在這檔的淨進出佔成交量的比重夠大」，不區分方向；方向由 `direction` 回報。**先加總再取絕對值**，所以窗口內先大買後大賣、淨額接近零的股票不會命中——它回答的是淨方向夠不夠強，不是進出活不活躍。

**命中清單照常進入回測。** 淨賣超方向的命中同樣會以 `buyDate` 收盤買進計算回測（`specs/backend/strategy-backtest.md` 不區分型態），回測數字對這類命中代表的是「在法人大賣之後買進」的結果。

`detail`：`windowStartDate`、`volumeShares`（窗口成交股數合計）、`matchedInvestors`、`foreign`／`trust`（各為 `{ netShares, ratioPercent, direction }`：窗口買賣超合計（可為負）、佔比百分比兩位小數、`BUY`／`SELL`）。

#### 法人連續買超 `INSTITUTIONAL_CONSECUTIVE_BUY`

對每一個勾選的法人：窗口內**每一日**的買賣超股數都 **> 0** 即命中。任一日為 `0` 或賣超即不成立。

| 參數 | 型別 | 預設 | 範圍 | 說明 |
|---|---|---|---|---|
| `investors` | 字串陣列 | `["FOREIGN","TRUST"]` | `FOREIGN`／`TRUST`，至少一個、不重複 | 要判定的法人 |
| `buyDays` | 整數 | `5` | `1` ~ `20` | 連續買超的交易日數，**含** D |

**`buyDays = 1` 是合法值**：窗口只有 D 當日，意思是「當日買超」。

`detail`：`windowStartDate`、`matchedInvestors`、`foreign`／`trust`（各為 `{ netBuyShares }`：窗口內買超股數合計）。

#### 法人買超強度排名 `INSTITUTIONAL_STRENGTH_RANK`

對每一個勾選的法人：

1. **強度**：`窗口買賣超合計 ÷ 窗口成交股數合計`。
2. **參與排名**：只有窗口買賣超合計 **> 0**（淨買超）的股票參與；合計為 `0` 或淨賣超者不排名。
3. **每日排名**：同一個 D 上，所有參與排名的股票依強度由高到低排序，同強度依 `stockId` 升冪，取前 `topN` 名即命中。**名次在本次掃描母體內計算**：指定 `stockIds` 時是在那幾檔之中排名，`commonStocksOnly` 改變母體也就改變名次。
4. 外資與投信**各自排名、各取前 `topN`**，一個交易日因此最多有 `2 × topN` 檔命中；參與排名的股票不足 `topN` 檔時，全數命中。

| 參數 | 型別 | 預設 | 範圍 | 說明 |
|---|---|---|---|---|
| `investors` | 字串陣列 | `["FOREIGN","TRUST"]` | `FOREIGN`／`TRUST`，至少一個、不重複 | 要判定的法人 |
| `windowDays` | 整數 | `5` | `1` ~ `20` | 強度計算的交易日數，**含** D |
| `topN` | 整數 | `10` | `1` ~ `50` | 每一方每個交易日取的名次數 |

**為什麼每個交易日各自排名，而不是整段區間排一次。** 區間內的每一天都是一份「當天收盤後看得到的名單」：D 那天的前幾名只能和 D 那天同樣參與排名的股票比，不能和兩週後的股票比，後者在 D 當時還不存在。這也讓本型態與其餘型態一樣是「逐日判定、回報最近一次」，回測才能以每一筆的 `buyDate` 獨立計算。

`detail`：`windowStartDate`、`volumeShares`、`matchedInvestors`、`foreign`／`trust`（各為 `{ rank, strengthPercent, netBuyShares }`：該方在 `signalDate` 當日的名次、強度百分比兩位小數、窗口買超股數合計；該方當日**未進前 `topN`** 時為 `null`）。

### 技術指標型態

兩個型態都以日線推導的技術指標判定，**共用下列規則**，各自的判定條件見其下各小節。

#### 共通規則

**公式沿用系統既有的唯一契約**：MACD 與 KD 的公式、初始值與輸出欄位以 `specs/dba/stock-daily-indicator.md` 的「指標定義」為準——KD 為台股慣例 (9,3,3)、RMA 平滑、K／D 初始值 50，`J = 3K − 2D`；MACD 為 `DIF = EMA快(收盤) − EMA慢(收盤)`、`DEA = EMA9(DIF)`、`OSC = DIF − DEA`。黃金交叉的定義沿用 `specs/backend/stock-indicator-statistics.md` 的交叉定義表，以相鄰兩個交易日比較。本 spec 唯一放寬的是 **MACD 的快、慢 EMA 天數改由呼叫端指定**；KD 的 (9,3,3) 與 MACD 的訊號線天數 `9` 固定，不開放指定。

**必須與指標運算共用同一套公式實作，不得另寫一份。** 以同一段行情、同一組參數（12／26／9 與 9／3／3）為輸入時，兩者算出的 DIF／DEA／OSC／K／D／J 必須一致。兩份各自維護的遞迴公式遲早會在初始值或捨入點上分歧，使用者就會在日 K 圖的指標與策略命中之間看到對不起來的交叉日。

**於掃描當下由日線即時運算，不讀、不寫 `stock_daily_indicator`。** 該表只存一組固定參數（`MACD_12_26_9__KD_9_3_3`），回答不了自訂 EMA 天數的 MACD；它的涵蓋範圍又取決於指標重建何時跑過，拿它判定會讓掃描結果隨一個與掃描無關的排程改變。即時運算的成本低——每檔每個交易日只是一次遞迴推進，而掃描本來就已批次讀入日線。理由與底底高的 MA5 相同。**不得新增資料表或欄位。**

**暖身**：MACD 與 KD 是遞迴指標，序列開頭的值取決於初始值、尚未收斂。

- 運算自該檔 `startDate` 之前**最多 250 個交易日**起（250 為 `specs/backend/stock-indicator-statistics.md` 實測的完全收斂長度）；可用行情不足 250 根時，自該檔**最早的一筆日線**起。
- **D 之前（不含 D）至少有 100 個交易日的日線，D 才判定**；不足者不判定，不算未命中。依指標 spec 的實測，暖身 102 根時 DIF 與完全收斂值相差不到 0.01、KD 已完全收斂，不足以改變交叉發生在哪一天；再短，序列就還被初始值主導。
- 區間內**沒有任何一個 D 可判定**的股票列入 `insufficientData`。
- **不以 250 根為判定門檻**：系統行情自 `2026-01-01` 起累積（見 `specs/backend/stock-price-ingestion.md`），至 2026-09 約 170 個交易日。要求 250 根會讓每一檔都落入 `insufficientData`，這兩個型態要到約 2027 年初才會有第一筆命中。

**進場日**：兩者皆以收盤價推導的指標判定，D 收盤的當下就已知道是否成立，`buyDate` 等於 `signalDate`。沒有確認日，`pendingConfirm` 恆為空陣列——D 為該檔最新一筆日線時照常命中。

**收斂**：同一檔在區間內多次命中時，回報最近一次，與其餘價格型態相同。

**這兩個型態回報的是指標交叉的偵測結果，不是買賣建議**，也不驗證交叉之後股價是否上漲。

#### MACD 黃金交叉 `MACD_GOLDEN_CROSS`

以呼叫端指定的快、慢 EMA 天數算出 DIF 與 DEA（DEA 固定為 DIF 的 9 日 EMA），對區間內每個交易日 D 判定：

1. **前一日未在上方**：D−1 的 `DIF ≤ DEA`（即 `OSC ≤ 0`）。
2. **當日穿越**：D 的 `DIF > DEA`（即 `OSC > 0`）。

兩者皆成立即命中，D 為 `signalDate`。這正是 `specs/backend/stock-indicator-statistics.md` 定義的「MACD 黃金交叉」，只是 EMA 天數可調。

| 參數 | 型別 | 預設 | 範圍 | 說明 |
|---|---|---|---|---|
| `fastPeriod` | 整數 | `5` | `2` ~ `50` | 短期 EMA 的天數（交易日） |
| `slowPeriod` | 整數 | `20` | `3` ~ `100` | 長期 EMA 的天數（交易日） |

**`fastPeriod` 必須小於 `slowPeriod`。** 兩者相等時 DIF 恆為 0、永遠不會交叉；快線天數大於慢線時 DIF 的正負號整個反轉，「黃金交叉」實際上變成死亡交叉。兩者都是永遠得不到使用者要的結果的請求，依本 spec「永遠不可能命中的請求應該被拒絕」的原則回 `400`。

預設為 5／20，**與系統既有指標參數（12／26／9，日 K 圖的 MACD 副圖即以此繪製，見 `specs/dba/stock-daily-indicator.md`）不同**：不指定參數時回報的是 5／20 的交叉，交叉日不必與日 K 圖上看到的 MACD 交叉日相同。要與日 K 圖一致，呼叫端須明確指定 `fastPeriod: 12`、`slowPeriod: 26`。訊號線天數仍固定為 `9`。

**不附加零軸條件**：交叉發生在零軸之上或之下都算命中。`detail` 回報交叉當日的 `dif`，使用者可據以自行區分。

`detail`：`dif`、`dea`、`osc`（D 當日）、`prevOsc`（D−1 的 OSC），皆四位小數。

#### KDJ 黃金交叉 `KDJ_GOLDEN_CROSS`

以 KD (9,3,3) 算出 K、D、J，對區間內每個交易日 D 判定：

1. **J 向上突破 K 與 D**：D−1 的 J **未同時高於** K 與 D，D 的 J **同時高於** K 與 D。
2. **突破前的 J 夠低**：D−1 的 J **<** `jThreshold`（嚴格小於；等於不成立）。

兩者皆成立即命中，D 為 `signalDate`。

**J 突破 K 與 D 必然與 K 突破 D 同一天發生**：`J − K = 2(K − D)`、`J − D = 3(K − D)`，三者之差同正負號。因此第 1 步等價於 `specs/backend/stock-indicator-statistics.md` 的 KD 黃金交叉（`k` 由 ≤ `d` 轉為 > `d`），**判定一律以 K 與 D 比較**，不以 J 與 K、D 的數值比較——J 由 K、D 導出，直接比較會因捨入誤差在交叉點上出現「K 已穿越 D、J 卻還等於 K」的不一致。對外的說法仍是「J 向上突破 K 與 D」，那是使用者看圖時看到的現象。

| 參數 | 型別 | 預設 | 範圍 | 說明 |
|---|---|---|---|---|
| `jThreshold` | 數值 | `40` | `-100` ~ `100`，最多一位小數 | 交叉前一交易日 J 的上限（不含） |

J 由 `3K − 2D` 算出，沒有上下界，會低於 0 也會高於 100，因此範圍含負值。

`detail`：`k`、`d`、`j`（D 當日）、`prevK`、`prevD`、`prevJ`（D−1），皆四位小數。

### 掃描範圍

- `stockIds` 省略或為空陣列 → 掃描 `stock` 表中 `is_active = 1` 的股票，再依 `commonStocksOnly` 決定是否只留普通股。
- `stockIds` 有值 → 只掃描清單內的股票，且清單中允許包含已下市股票與非普通股（**使用者明確指名時不代掃描範圍過濾**，`commonStocksOnly` 一律不套用）。
- 上限 200 檔；超過即拒絕，而非默默截斷。

**普通股篩選（`commonStocksOnly`，預設 `true`）**：只留代號**恰為 4 位數字、且首字元非 `0`** 的股票。這道規則不是本 spec 新訂的，而是沿用 `specs/backend/stock-universe-import.md` 的「只收普通股」定義；同一套判斷同時排除 ETF（`0050`、`00878`）、特別股（`2881A`）與 TDR／存託憑證（`910322`）。兩處必須共用同一個判斷，不得各自實作一份。

預設為 `true` 的理由與該 spec 記載的一致：本模組的三個型態都是為個股價格行為設計的判定規則，ETF 的價格由一籃子成分股加權而成，其「箱型」「突破」「起漲」在意義上與個股不同；把數百檔 ETF 混進母體只會稀釋掃描結果，不會讓使用者多得到可用的訊號。`commonStocksOnly: false` 保留給明確想連 ETF 一起掃的情況。

**本篩選只影響掃描母體，不寫入任何資料表**——`stock` 的內容、`is_active`、以及股票總覽顯示的檔數，都不因這個參數而改變。

### 區間與資料前置需求

- `startDate` / `endDate` 皆省略時，區間為 `endDate = 今日`、`startDate = 今日往前一個日曆月`。
- **判定所需的前置資料取自 `startDate` 之前**：箱型突破需要 `lookback` 個交易日、底底高需要 `swingBars + 4` 個交易日（`swingBars` 供左側比較，另 4 根供最左那一日算出 MA5）、上漲支撐需要 `lookback` 個交易日、反彈需要 `dropDays − 1 + riseDays` 個交易日（谷底最早可落在 `startDate` 往前 `riseDays` 個交易日處，其跌段窗口再往前 `dropDays − 1` 個交易日；`requireRise` 為 `false` 時只需 `dropDays − 1`）、累積上漲需要 `days − 1` 個交易日、法人買賣超佔比與法人買超強度排名需要 `windowDays − 1` 個交易日、法人連續買超需要 `buyDays − 1` 個交易日（法人籌碼型態的日線與法人資料皆需涵蓋這段）、MACD 黃金交叉與 KDJ 黃金交叉讀取最多 250 個交易日作為指標暖身（見「技術指標型態」的共通規則）。這些資料只用於判定，不會被回報為命中。
- **上漲支撐的確認資料取自 `endDate` 之後**：判定 D 是否命中需要 D+1 與 D+2 的收盤。掃描時應一併讀入 `endDate` 之後最多 2 個交易日的行情；若該資料尚未存在，D 落入 `pendingConfirm`。
- **法人籌碼型態的進場日取自 `endDate` 之後**：D 為 `endDate` 當日時，`buyDate` 是 `endDate` 之後的下一個交易日。掃描時應一併讀入 `endDate` 之後最多 1 個交易日的日線；尚未存在時依共通規則的收斂方式處理。法人資料本身只讀到 `endDate` 為止。
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
    },
    {
      "code": "INSTITUTIONAL_NET_RATIO",
      "name": "法人買賣超佔比",
      "description": "外資（不含外資自營商）或投信近指定天數買賣超合計取絕對值 ÷ 成交股數合計，達門檻即命中，淨買超與淨賣超皆計",
      "presets": [],
      "params": [
        { "code": "investors", "name": "法人", "type": "multiSelect",
          "options": [ { "code": "FOREIGN", "name": "外資" }, { "code": "TRUST", "name": "投信" } ],
          "default": ["FOREIGN", "TRUST"], "minSelected": 1 },
        { "code": "windowDays",   "name": "天數",     "unit": "日", "default": 5,  "min": 1, "max": 20,  "step": 1 },
        { "code": "ratioPercent", "name": "佔比門檻", "unit": "%",  "default": 10, "min": 0, "max": 100, "step": 0.1 }
      ]
    },
    {
      "code": "INSTITUTIONAL_CONSECUTIVE_BUY",
      "name": "法人連續買超",
      "description": "外資（不含外資自營商）或投信連續指定天數每日買超",
      "presets": [],
      "params": [
        { "code": "investors", "name": "法人", "type": "multiSelect",
          "options": [ { "code": "FOREIGN", "name": "外資" }, { "code": "TRUST", "name": "投信" } ],
          "default": ["FOREIGN", "TRUST"], "minSelected": 1 },
        { "code": "buyDays", "name": "連續買超天數", "unit": "日", "default": 5, "min": 1, "max": 20, "step": 1 }
      ]
    },
    {
      "code": "INSTITUTIONAL_STRENGTH_RANK",
      "name": "法人買超強度排名",
      "description": "外資（不含外資自營商）或投信近指定天數買超合計 ÷ 成交股數合計為強度，只有淨買超者參與，每個交易日外資與投信各取強度前幾名",
      "presets": [],
      "params": [
        { "code": "investors", "name": "法人", "type": "multiSelect",
          "options": [ { "code": "FOREIGN", "name": "外資" }, { "code": "TRUST", "name": "投信" } ],
          "default": ["FOREIGN", "TRUST"], "minSelected": 1 },
        { "code": "windowDays", "name": "天數",     "unit": "日", "default": 5,  "min": 1, "max": 20, "step": 1 },
        { "code": "topN",       "name": "取前幾名", "unit": "名", "default": 10, "min": 1, "max": 50, "step": 1 }
      ]
    },
    {
      "code": "MACD_GOLDEN_CROSS",
      "name": "MACD 黃金交叉",
      "description": "DIF（短期 EMA − 長期 EMA）由下往上穿越 DEA（DIF 的 9 日 EMA）當日為訊號日",
      "presets": [],
      "params": [
        { "code": "fastPeriod", "name": "短期 EMA", "unit": "日", "default": 5, "min": 2, "max": 50,  "step": 1, "lessThan": "slowPeriod" },
        { "code": "slowPeriod", "name": "長期 EMA", "unit": "日", "default": 20, "min": 3, "max": 100, "step": 1 }
      ]
    },
    {
      "code": "KDJ_GOLDEN_CROSS",
      "name": "KDJ 黃金交叉",
      "description": "KD(9,3,3) 的 J 由下往上同時穿越 K 與 D 當日為訊號日，且前一交易日 J 低於門檻",
      "presets": [],
      "params": [
        { "code": "jThreshold", "name": "J 門檻", "unit": "", "default": 40, "min": -100, "max": 100, "step": 0.1 }
      ]
    }
  ]
}
```

前端的策略選單、靈敏度說明文字、以及無靈敏度型態的參數預設值與範圍，一律取自此端點，不在前端寫死——參數改動時只需改後端一處。

`presets` 與 `params` 的關係：
- **有靈敏度的型態**：`presets` 為三段，`params` 為空陣列或不出現；卡片上顯示的說明文字取自目前選定那一段的 `description`。
- **參數型別**：`params` 的項目不帶 `type` 時為數字參數（`unit`／`default`／`min`／`max`／`step`；`unit` 可為空字串，表示沒有單位，目前為 KDJ 黃金交叉的 `jThreshold`；`min` 可為負數）；`type` 為 `multiSelect` 時為複選參數，帶 `options`（每筆 `code` 與 `name`）、`default`（預設勾選的 `code` 陣列）與 `minSelected`（至少勾選幾個），請求中以 `code` 陣列送出。目前只有法人籌碼型態的 `investors` 用到複選，但這個機制不綁任何策略——前端依 `type` 決定畫數字輸入還是勾選框，同樣不得以策略或參數 `code` 寫死。
- **無靈敏度的型態**（目前為累積上漲、反彈、三個法人籌碼型態與兩個技術指標型態）：`presets` 為**空陣列**，`params` 列出該型態的每一個可輸入參數；卡片上顯示的說明文字取自策略層級的 `description`。
- **選用參數群組**：`params` 的項目可帶 `group`，指向 `paramGroups` 中同 `code` 的一筆。同一群組的參數是一組可整組開關的選用條件，`paramGroups[].default` 是開關的預設狀態，`name` 是開關要顯示的文字。不帶 `group` 的參數一律生效、不可關閉。目前只有反彈用到（`rise` 群組），但這個機制不綁任何策略——前端依 `paramGroups` 畫開關即可，同樣不得以策略 `code` 寫死。
- **參數間的大小限制**：數字參數可帶 `lessThan`，值為同一策略另一個數字參數的 `code`，表示本參數必須**嚴格小於**該參數。目前只有 MACD 黃金交叉的 `fastPeriod`（`lessThan: "slowPeriod"`）用到，但這個機制不綁任何策略——前端依 `lessThan` 擋下違反的輸入，同樣不得以策略或參數 `code` 寫死。不帶 `lessThan` 的參數沒有這類限制。

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
| `strategies[].code` | string | 是 | `BOX_BREAKOUT` / `HIGHER_LOWS` / `RISING_SUPPORT` / `REBOUND` / `CUMULATIVE_RISE` / `INSTITUTIONAL_NET_RATIO` / `INSTITUTIONAL_CONSECUTIVE_BUY` / `INSTITUTIONAL_STRENGTH_RANK` / `MACD_GOLDEN_CROSS` / `KDJ_GOLDEN_CROSS` |
| `strategies[].preset` | string | 視型態而定 | `STRICT` / `STANDARD` / `LOOSE`。**有靈敏度的三個型態必填；`CUMULATIVE_RISE`、`REBOUND`、三個法人籌碼型態與兩個技術指標型態不得帶**（它們沒有靈敏度可選） |
| `strategies[].investors` | string[] | 否 | **只有三個法人籌碼型態接受本欄位**。`FOREIGN`／`TRUST`，至少一個、不得重複；省略時為 `["FOREIGN","TRUST"]` |
| `strategies[].windowDays` | int | 否 | **只有 `INSTITUTIONAL_NET_RATIO` 與 `INSTITUTIONAL_STRENGTH_RANK` 接受本欄位**。窗口交易日數（含訊號日），整數，範圍 `1`～`20`；省略時為 `5` |
| `strategies[].ratioPercent` | number | 否 | **只有 `INSTITUTIONAL_NET_RATIO` 接受本欄位**。佔比門檻，範圍 `0`～`100`，最多一位小數；省略時為 `10` |
| `strategies[].buyDays` | int | 否 | **只有 `INSTITUTIONAL_CONSECUTIVE_BUY` 接受本欄位**。連續買超的交易日數（含訊號日），整數，範圍 `1`～`20`；省略時為 `5` |
| `strategies[].topN` | int | 否 | **只有 `INSTITUTIONAL_STRENGTH_RANK` 接受本欄位**。每一方每個交易日取的名次數，整數，範圍 `1`～`50`；省略時為 `10` |
| `strategies[].fastPeriod` | int | 否 | **只有 `MACD_GOLDEN_CROSS` 接受本欄位**。短期 EMA 天數，整數，範圍 `2`～`50`，且須小於 `slowPeriod`（其一省略時以其預設值比較）；省略時為 `5` |
| `strategies[].slowPeriod` | int | 否 | **只有 `MACD_GOLDEN_CROSS` 接受本欄位**。長期 EMA 天數，整數，範圍 `3`～`100`；省略時為 `20` |
| `strategies[].jThreshold` | number | 否 | **只有 `KDJ_GOLDEN_CROSS` 接受本欄位**。交叉前一交易日 J 的上限（不含），範圍 `-100`～`100`，最多一位小數；省略時為 `40` |
| `strategies[].days` | int | 否 | **只有 `CUMULATIVE_RISE` 接受本欄位**，其餘型態帶了視為無效。回看窗口的交易日數，整數，範圍 `1`～`90`；省略時為 `20` |
| `strategies[].requireRise` | boolean | 否 | **只有 `REBOUND` 接受本欄位**。是否套用漲段條件；省略時為 `true`。為 `false` 時 `riseDays` 與 `risePercent` 不得帶 |
| `strategies[].dropDays` | int | 否 | **只有 `REBOUND` 接受本欄位**。跌段回看的交易日數，整數，範圍 `1`～`90`；省略時為 `3` |
| `strategies[].dropPercent` | number | 否 | **只有 `REBOUND` 接受本欄位**。跌幅門檻，範圍 `0`～`50`，最多一位小數；省略時為 `10` |
| `strategies[].riseDays` | int | 否 | **只有 `REBOUND` 接受本欄位**，且 `requireRise` 為 `true` 時才可帶。反彈窗口的交易日數，整數，範圍 `1`～`90`；省略時為 `1` |
| `strategies[].risePercent` | number | 否 | 幅度門檻，最多一位小數，**上限依型態而定**：箱型突破／底底高／上漲支撐 `0`～`20`，反彈／累積上漲 `0`～`50`（見「型態定義」的上限表）。對**有靈敏度的三個型態**是覆寫該靈敏度的漲幅欄位，省略即沿用靈敏度的值；對 `CUMULATIVE_RISE` 是窗口內的累積漲幅門檻，省略時為 `15`；對 `REBOUND` 是**自谷底起算的反彈幅度門檻**，省略時為 `5`，且 `requireRise` 為 `false` 時不得帶。**三個法人籌碼型態與兩個技術指標型態不接受本欄位** |
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
          "buyDate": "2026-08-27",
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
          "buyDate": "2026-08-25",
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
          "buyDate": "2026-08-28",
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
          "buyDate": "2026-08-26",
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
          "buyDate": "2026-08-28",
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
    },
    {
      "strategy": "INSTITUTIONAL_NET_RATIO",
      "investors": ["FOREIGN", "TRUST"],
      "windowDays": 5,
      "ratioPercent": 10,
      "dataThroughDate": "2026-08-28",
      "matchedCount": 1,
      "items": [
        {
          "stockId": "2609",
          "stockName": "陽明",
          "signalDate": "2026-08-27",
          "buyDate": "2026-08-28",
          "detail": {
            "windowStartDate": "2026-08-21",
            "volumeShares": 67500000,
            "matchedInvestors": ["FOREIGN"],
            "foreign": { "netShares": -12500000, "ratioPercent": 18.52, "direction": "SELL" },
            "trust": null
          }
        }
      ],
      "insufficientData": [],
      "pendingConfirm": []
    },
    {
      "strategy": "INSTITUTIONAL_CONSECUTIVE_BUY",
      "investors": ["TRUST"],
      "buyDays": 5,
      "dataThroughDate": "2026-08-28",
      "matchedCount": 1,
      "items": [
        {
          "stockId": "2317",
          "stockName": "鴻海",
          "signalDate": "2026-08-26",
          "buyDate": "2026-08-27",
          "detail": {
            "windowStartDate": "2026-08-20",
            "matchedInvestors": ["TRUST"],
            "foreign": null,
            "trust": { "netBuyShares": 3150000 }
          }
        }
      ],
      "insufficientData": [],
      "pendingConfirm": ["2330"]
    },
    {
      "strategy": "INSTITUTIONAL_STRENGTH_RANK",
      "investors": ["FOREIGN", "TRUST"],
      "windowDays": 5,
      "topN": 10,
      "dataThroughDate": "2026-08-28",
      "matchedCount": 1,
      "items": [
        {
          "stockId": "3231",
          "stockName": "緯創",
          "signalDate": "2026-08-27",
          "buyDate": "2026-08-28",
          "detail": {
            "windowStartDate": "2026-08-21",
            "volumeShares": 67500000,
            "matchedInvestors": ["FOREIGN", "TRUST"],
            "foreign": { "rank": 2, "strengthPercent": 18.52, "netBuyShares": 12500000 },
            "trust":   { "rank": 7, "strengthPercent": 3.10,  "netBuyShares": 2092500 }
          }
        }
      ],
      "insufficientData": [],
      "pendingConfirm": []
    },
    {
      "strategy": "MACD_GOLDEN_CROSS",
      "fastPeriod": 5,
      "slowPeriod": 20,
      "signalPeriod": 9,
      "matchedCount": 1,
      "items": [
        {
          "stockId": "2330",
          "stockName": "台積電",
          "signalDate": "2026-08-26",
          "buyDate": "2026-08-26",
          "detail": { "dif": -1.2034, "dea": -1.3120, "osc": 0.1086, "prevOsc": -0.0452 }
        }
      ],
      "insufficientData": ["6949"],
      "pendingConfirm": []
    },
    {
      "strategy": "KDJ_GOLDEN_CROSS",
      "jThreshold": 40,
      "matchedCount": 1,
      "items": [
        {
          "stockId": "2317",
          "stockName": "鴻海",
          "signalDate": "2026-08-27",
          "buyDate": "2026-08-27",
          "detail": { "k": 28.4410, "d": 27.9025, "j": 29.5180, "prevK": 24.1037, "prevD": 26.6333, "prevJ": 19.0445 }
        }
      ],
      "insufficientData": [],
      "pendingConfirm": []
    }
  ]
}
```

- `results` 依 `strategies` 送入的順序回傳，一個策略一筆。每一筆原樣回報該策略**實際採用**的參數（省略時回實際採用的預設值）：有靈敏度的型態回 `preset`；累積上漲回 `days`；反彈回 `requireRise`、`dropDays`、`dropPercent`，並在 `requireRise` 為 `true` 時另回 `riseDays` 與 `risePercent`；法人買賣超佔比回 `investors`、`windowDays`、`ratioPercent`；法人連續買超回 `investors`、`buyDays`；法人買超強度排名回 `investors`、`windowDays`、`topN`；MACD 黃金交叉回 `fastPeriod`、`slowPeriod` 與 `signalPeriod`（恆為 `9`，一併回報，呼叫端不必寫死）；KDJ 黃金交叉回 `jThreshold`；三個法人籌碼型態另回 `dataThroughDate`。`preset` 與這些參數欄位不同時出現。
- `dataThroughDate`（僅法人籌碼型態）：`startDate` 前置區間起至 `endDate` 之間，`stock_institutional_trade` 有資料的**最晚交易日**；這段期間完全沒有法人資料時為 `null`。它說明的是法人資料涵蓋到哪一天，不是任何一檔的命中日。
- `items` 依 `signalDate` 由新到舊排序；同日則依 `stockId` 升冪。
- `signalDate` 為該檔在區間內**最近一次**命中的日期；同一檔在區間內多次命中只回報最近一次。
- `buyDate` 為**這一次命中的進場日**：型態確認成立、可以不使用未來資料買進的那一個交易日，回測以其**收盤價**買進（見 `specs/backend/strategy-backtest.md`）。**上漲支撐為 D+2**——D 之後的第二個交易日，即該筆 `detail.confirmCloses` 最後一筆的 `tradeDate`；箱型突破、底底高、反彈、累積上漲、MACD 黃金交叉、KDJ 黃金交叉的 `buyDate` 等於 `signalDate`；**三個法人籌碼型態為 D 之後的下一個交易日**（三大法人日報收盤後才發布，理由見「法人籌碼型態」的共通規則），其 `signalDate` 為已有 `buyDate` 的命中中最近的一次。「交易日」依相鄰的資料列認定，停牌缺列不計，不以日曆日推算。

**上漲支撐的訊號日與進場日刻意不同。** `signalDate` 維持為上漲日 D——它回答「型態是哪一天起漲的」，排序與畫面上的「命中策略與訊號日」都依它。但這個型態要到 D+2 收盤、D+1 與 D+2 都守住起漲收盤才成立，在 D 收盤時根本還不知道它會不會成立；在 D 收盤買進，等於用 D+1、D+2 兩天的未來收盤價挑股票，回測會系統性美化。**進場日由掃描回報，而不是由呼叫端或回測端推算**：「型態何時確認成立」是型態定義的一部分，放到別處推算，就等於在掃描之外再寫第二份型態規則，兩份遲早會對不起來。
- `pendingConfirm` 可能非空的情況有三：箱型突破且 `confirmBars = 2`（已突破但確認日尚未到）、上漲支撐（已上漲但 D+1／D+2 尚未到齊），以及三個法人籌碼型態（已命中但每一次命中都還沒有下一個交易日）。都列出該檔的股票代號，且不計入 `matchedCount`。
- 法人籌碼型態的 `detail` 形狀見各型態小節；三者共有 `windowStartDate`（窗口第一個交易日）、`matchedInvestors`、`foreign`、`trust`。**反彈、累積上漲與兩個技術指標型態一律不產生 `pendingConfirm`**，其 `pendingConfirm` 恆為空陣列。反彈雖然有漲段條件，但採「已達標就命中、不等窗口跑滿」（見型態定義），因此不存在待確認狀態。
- `insufficientData` 與 `matchedCount` 互斥：列在前者的股票不會出現在 `items` 中。
- 底底高的 `detail.lows` 每筆有三個欄位：`tradeDate`、`ma5`（判定所依據的 5 日收盤均線值）、`low`（該日原始最低價，僅供對照，不參與判定）。`ma5` 與 `low` 皆為兩位小數。
- MACD 黃金交叉的 `detail` 為 `dif`、`dea`、`osc`（訊號日當日）與 `prevOsc`（前一交易日）；KDJ 黃金交叉的 `detail` 為 `k`、`d`、`j`（訊號日當日）與 `prevK`、`prevD`、`prevJ`（前一交易日）。皆四位小數，與 `specs/backend/stock-indicator-statistics.md` 回應指標值的精度相同；判定一律使用未經四捨五入的原值。

驗證與錯誤：
- `strategies` 為空或缺漏 → `400`，`{"code":"NO_STRATEGY_SELECTED"}`
- 未知的 `code` 或 `preset` → `400`，`{"code":"UNKNOWN_STRATEGY","unknown":["FOO"]}`
- 有靈敏度的型態缺 `preset` → `400`，`{"code":"UNKNOWN_STRATEGY","unknown":["BOX_BREAKOUT"]}`
- 對**沒有靈敏度的型態**（`CUMULATIVE_RISE`、`REBOUND`、三個法人籌碼型態、兩個技術指標型態）帶了 `preset` → `400`，`{"code":"PRESET_NOT_APPLICABLE","strategy":"REBOUND"}`
- 對不接受的型態帶了 `investors`／`windowDays`／`ratioPercent`／`buyDays`／`topN`／`fastPeriod`／`slowPeriod`／`jThreshold`（見請求欄位表各欄的適用型態）→ `400`，`{"code":"PARAM_NOT_APPLICABLE","strategy":"BOX_BREAKOUT","param":"investors"}`
- 對三個法人籌碼型態或兩個技術指標型態帶了 `risePercent` → `400`，`{"code":"PARAM_NOT_APPLICABLE","strategy":"INSTITUTIONAL_NET_RATIO","param":"risePercent"}`。這些型態不看漲幅，靜默忽略會讓使用者以為那個數字有生效。帶 `days` 沿用下方的 `DAYS_NOT_APPLICABLE`，帶反彈的參數沿用下方的 `PARAM_NOT_APPLICABLE`
- `investors` 為空陣列、含 `FOREIGN`／`TRUST` 以外的值、或有重複 → `400`，`{"code":"INVALID_INVESTORS","strategy":"INSTITUTIONAL_CONSECUTIVE_BUY"}`
- `windowDays` 非整數、小於 `1` 或大於 `20` → `400`，`{"code":"INVALID_WINDOW_DAYS","strategy":"INSTITUTIONAL_NET_RATIO"}`
- `ratioPercent` 小於 `0`、大於 `100`、或小數超過一位 → `400`，`{"code":"INVALID_RATIO_PERCENT","strategy":"INSTITUTIONAL_NET_RATIO"}`
- `buyDays` 非整數、小於 `1` 或大於 `20` → `400`，`{"code":"INVALID_BUY_DAYS","strategy":"INSTITUTIONAL_CONSECUTIVE_BUY"}`
- `topN` 非整數、小於 `1` 或大於 `50` → `400`，`{"code":"INVALID_TOP_N","strategy":"INSTITUTIONAL_STRENGTH_RANK"}`
- `fastPeriod` 非整數、小於 `2` 或大於 `50` → `400`，`{"code":"INVALID_FAST_PERIOD","strategy":"MACD_GOLDEN_CROSS"}`
- `slowPeriod` 非整數、小於 `3` 或大於 `100` → `400`，`{"code":"INVALID_SLOW_PERIOD","strategy":"MACD_GOLDEN_CROSS"}`
- `fastPeriod` ≥ `slowPeriod`（其一省略時以其預設值比較）→ `400`，`{"code":"INVALID_MACD_PERIODS","strategy":"MACD_GOLDEN_CROSS"}`。**各欄自身的範圍錯誤優先**：任一欄超出範圍或非整數時回該欄的錯誤碼，不回本碼
- `jThreshold` 小於 `-100`、大於 `100`、或小數超過一位 → `400`，`{"code":"INVALID_J_THRESHOLD","strategy":"KDJ_GOLDEN_CROSS"}`
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

解析並驗證請求 → 決定目標股票清單（指定清單；或全市場在市再依 `commonStocksOnly` 過濾）→ 對每個策略取判定參數（有靈敏度者取該靈敏度那一組、再以該策略的 `risePercent` 覆寫其漲幅門檻；累積上漲取請求的 `days`／`risePercent` 或其預設值；反彈取請求的 `requireRise`／`dropDays`／`dropPercent`／`riseDays`／`risePercent` 或其預設值；MACD 黃金交叉取 `fastPeriod`／`slowPeriod` 或其預設值；KDJ 黃金交叉取 `jThreshold` 或其預設值） → 對每檔股票讀取 `startDate` 前置區間起算至 `endDate` 的日線 → 逐日套用判定 → 收斂為每檔最近一次命中 → 組裝回應。

**行情讀取必須批次進行**，不得逐檔一次查詢：全市場掃描是 2200 檔，逐檔查詢即 2200 次往返。以單一查詢按 `(stock_id, trade_date)` 主鍵範圍取回目標區間的全部列，再在記憶體中依股票分組判定。

**技術指標型態不另讀資料**：指標暖身所需的最多 250 個交易日併入同一個批次行情查詢的前置區間（前置區間取所選策略中最長者），MACD 與 KD 在記憶體中逐檔遞迴算出；兩個型態同時送出時共用同一份行情。

**法人籌碼型態另有兩個批次讀取，同樣不隨檔數增加查詢次數，且同一次掃描中三個型態共用同一份讀取結果**：以單一查詢按主鍵範圍取回目標股票自前置區間起至 `endDate` 的 `stock_institutional_trade`，並以一次查詢取得同一段期間內「已抓取的交易日」集合（相異 `trade_date`）。法人買超強度排名的判定分兩段：先逐檔算出每個 D 上各方的強度；再**依 D 分組**，在每一天內分別對外資、投信排序取前 `topN`；最後逐檔收斂。排名必須在全部股票都算完之後才能做——單看一檔無從得知它在當天排第幾。

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
- [x] 每一筆命中的 `items[]` 皆含 `buyDate`（date），五個型態皆然
- [x] `RISING_SUPPORT` 的 `buyDate` 為 D+2，且等於該筆 `detail.confirmCloses` 最後一筆的 `tradeDate`；以前述 1296／1272／1248 構造資料驗證 `buyDate` 為 D+2 那一列的交易日，同時 `signalDate` 仍為 D
- [x] D 與 D+2 之間有停牌（缺列）時，`RISING_SUPPORT` 的 `buyDate` 為 D 之後第二個**有資料列**的交易日，不以日曆日加 2 推算
- [x] D 為 `endDate` 且 D+1／D+2 取自 `endDate` 之後時，`buyDate` 可晚於 `endDate`，照常回報
- [x] `BOX_BREAKOUT`／`HIGHER_LOWS`／`REBOUND`／`CUMULATIVE_RISE` 的 `buyDate` 等於 `signalDate`

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
- [x] 箱型突破／底底高／上漲支撐送 `risePercent: 20` 為合法，送 `20.1` 回 `400`，`{"code":"INVALID_RISE_PERCENT","strategy":"<該策略 code>"}`
- [x] 反彈／累積上漲送 `risePercent: 50` 為合法，送 `50.1` 回 `400`，`{"code":"INVALID_RISE_PERCENT","strategy":"<該策略 code>"}`
- [x] 反彈／累積上漲送 `risePercent: 30` 仍為合法（上限未一併收緊）
- [x] 上漲支撐送 `risePercent: 30` 回 `400` 而非受理後回零命中——`strategy` 指名 `RISING_SUPPORT`
- [x] 同一次請求中一個策略超限、其餘合法時，回應的 `strategy` 指名的是實際超限的那一個
- [x] 反彈的 `dropPercent` 上限維持 `50` 不變，且其錯誤仍為 `INVALID_DROP_PERCENT`
- [x] `risePercent` 為 `0` 與小數位數規則（最多一位）在五個型態上皆未改變
- [x] `GET /api/strategies` 中 `CUMULATIVE_RISE` 與 `REBOUND` 的 `params[].max` 仍為 `50`，未因本次改動而變

### 法人籌碼型態

型態目錄：
- [x] `GET /api/strategies` 回傳八個策略；三個法人籌碼型態的 `name`、`description`、`presets`（空陣列）與 `params` 與本 spec 的 JSON 範例完全一致
- [x] 三者的 `investors` 參數為 `type: "multiSelect"`，`options` 為外資／投信、`default` 為 `["FOREIGN","TRUST"]`、`minSelected` 為 `1`；其餘數字參數不帶 `type`
- [x] 其餘五個策略的條目與本次改動前完全相同

共通規則：
- [x] 外資口徑不含外資自營商：構造 `foreign_net_shares` 每日為 `0`、`foreign_dealer_net_shares` 每日 > 0 的資料 → 三個型態的外資皆不達標
- [x] 窗口內某日法人資料已抓取（該日有其他股票的列）但沒有這一檔的列 → 該日買賣超視為 `0`
- [x] 窗口內有尚未抓取法人資料的日期 → 該檔在該 D 不判定、不參與該 D 的排名
- [x] `investors: ["TRUST"]` 時只判定投信：外資達標、投信未達標的資料不命中，且 `detail.foreign` 為 `null`
- [x] 兩方皆勾選時各自判定：只有投信達標 → 命中、`matchedInvestors` 為 `["TRUST"]`；兩方皆達標 → 只有一筆 `items`，`matchedInvestors` 為 `["FOREIGN","TRUST"]`
- [x] 省略 `investors` 時以兩者判定，且回應的 `investors` 為 `["FOREIGN","TRUST"]`
- [x] `buyDate` 為 D 之後該檔的下一筆日線：D 之後停牌兩日時為停牌後第一個有列的交易日；D 為 `endDate` 且其後已有日線時，`buyDate` 晚於 `endDate` 照常回報
- [x] 某檔唯一一次命中在它最新的一筆日線 → 列入 `pendingConfirm`，不在 `items`、不計入 `matchedCount`
- [x] 某檔較早有一次已有 `buyDate` 的命中、最新一次命中尚無下一個交易日 → `items` 回報較早那一次，且該檔**不**在 `pendingConfirm`
- [x] `dataThroughDate` 為期間內 `stock_institutional_trade` 有資料的最晚交易日；期間內完全沒有法人資料時為 `null`，且所有股票列於 `insufficientData`
- [x] D 之前的日線不足窗口長度 − 1 個交易日、使區間內沒有任何可判定的 D → 該檔列於 `insufficientData`
- [x] 比率為合計相除：窗口五日買超 `[1,2,3,4,5]` 百萬股、成交 `[10,10,10,10,60]` 百萬股 → 法人買賣超佔比的 `ratioPercent` 與法人買超強度排名的 `strengthPercent` 皆為 `15.00`（每日比率平均會是 `21.67`，驗證不是後者）
- [x] 法人資料以批次查詢讀取：全市場掃描時，`stock_institutional_trade` 的查詢次數不隨股票數增加；三個法人籌碼型態同一次送出時不重複讀取
- [x] 八個策略同一次送出時 `results` 依送入順序回傳八筆，五個價格型態的命中結果與未加法人籌碼型態時相同

法人買賣超佔比：
- [x] 先加總再取絕對值：外資五日買賣超 `[+8,−8,+8,−8,+1]` 百萬股、成交合計 50 百萬股 → 合計 +1、佔比 `2.00%`，`ratioPercent: 10` 不命中（若逐日取絕對值會是 66%，驗證不是後者）
- [x] 淨賣超命中：五日合計 −12.5 百萬股、成交合計 67.5 百萬股 → 佔比 `18.52`、`direction` 為 `SELL`、`netShares` 為 `-12500000`
- [x] 佔比恰等於 `ratioPercent` 時命中（≥）；窗口買賣超合計為 `0` 時即使 `ratioPercent: 0` 也不命中
- [x] 回應回 `investors`、`windowDays`、`ratioPercent`、`dataThroughDate`，不含 `preset`；省略時回 `5` 與 `10`

法人連續買超：
- [x] 投信連續 5 個交易日 `trust_net_shares` 皆 > 0 → 命中；其中任一日為 `0` 或負值 → 不命中
- [x] `buyDays: 1` 為合法請求：窗口只有 D 當日
- [x] `detail.trust.netBuyShares` 等於窗口內投信買超股數合計
- [x] 回應回 `investors`、`buyDays`、`dataThroughDate`，不含 `preset`；省略時回 `5`

法人買超強度排名：
- [x] 每日排名：同一 D 有 12 檔外資淨買超且強度各不相同、`topN: 10` → 恰前 10 檔在 D 命中，第 11、12 名不命中
- [x] 窗口淨賣超或合計為 `0` 的股票不參與排名：同一 D 只有 3 檔淨買超、`topN: 10` → 恰 3 檔命中
- [x] 同強度依 `stockId` 升冪決定名次；兩檔強度四捨五入至兩位小數後相同、原值不同時，依原值排名
- [x] 排名逐日計算：某檔在 D1 排第 11、在 D2 排第 3，`topN: 10` → 命中且 `signalDate` 為 D2
- [x] 外資條件參與排名但未進前 `topN`、投信進前 `topN` → 命中，`detail.foreign` 為 `null`
- [x] 名次在掃描母體內計算：`stockIds` 指定 3 檔、兩方皆勾選、`topN: 1` → 任一交易日最多 2 檔命中
- [x] 回應回 `investors`、`windowDays`、`topN`、`dataThroughDate`，不含 `preset`；省略時回 `5` 與 `10`

驗證與用語：
- [x] 三個法人籌碼型態帶 `preset` → `400 PRESET_NOT_APPLICABLE`；帶 `risePercent` → `400 PARAM_NOT_APPLICABLE`（`param` 為 `risePercent`）；帶 `days` → `400 DAYS_NOT_APPLICABLE`
- [x] 其他型態帶 `investors`／`windowDays`／`ratioPercent`／`buyDays`／`topN` → `400 PARAM_NOT_APPLICABLE`，`param` 指名該欄位；法人連續買超帶 `windowDays`、法人買賣超佔比帶 `topN` 亦同
- [x] `investors` 為 `[]`、`["DEALER"]`、`["FOREIGN","FOREIGN"]` → `400 INVALID_INVESTORS`，`strategy` 指名該型態
- [x] `windowDays` 為 `0`、`21`、`5.5` → `400 INVALID_WINDOW_DAYS`；`ratioPercent` 為 `-1`、`100.1`、`10.55` → `400 INVALID_RATIO_PERCENT`；`buyDays` 為 `0`、`21`、`5.5` → `400 INVALID_BUY_DAYS`；`topN` 為 `0`、`51`、`10.5` → `400 INVALID_TOP_N`；皆帶 `strategy`
- [x] 三個型態的名稱、說明文字與回應欄位名皆無「建議」「推薦」「進場」等暗示買賣操作的措辭

### 技術指標型態（MACD／KDJ，本次新增）

型態目錄：
- [x] `GET /api/strategies` 回傳十個策略，既有八個之後依序為 `MACD_GOLDEN_CROSS`、`KDJ_GOLDEN_CROSS`；兩者的 `name`、`description`、`presets`（空陣列）與 `params` 與本 spec 的 JSON 範例完全一致
- [x] `MACD_GOLDEN_CROSS` 的 `fastPeriod` 帶 `lessThan: "slowPeriod"`，`slowPeriod` 不帶；`KDJ_GOLDEN_CROSS` 的 `jThreshold` 之 `unit` 為空字串、`min` 為 `-100`
- [x] 既有八個策略的條目與本次改動前完全相同（皆不帶 `lessThan`）

共通規則：
- [x] 公式共用：同一段行情以 `fastPeriod: 12`、`slowPeriod: 26` 運算時，逐日 DIF／DEA／OSC 與 K／D／J 和指標運算（`specs/backend/stock-indicator-statistics.md`）自同一起點運算的結果到小數第四位一致；程式中只有一套 MACD／KD 遞迴公式實作，EMA 天數以參數傳入
- [x] 不讀 `stock_daily_indicator`：刪除某檔在該表的全部列後掃描，該檔命中結果不變；掃描前後該表列數不變
- [x] 暖身上限：某檔在 `startDate` 之前有 400 個交易日行情時，只從 `startDate` 之前第 250 個交易日起運算——改動更早（第 251 根之前）的行情，命中與 `detail` 完全不變
- [x] 暖身下限：D 之前恰有 99 個交易日時 D 不判定、恰 100 個時 D 照常判定；區間內所有 D 皆不足 100 根的股票列於 `insufficientData`，不在 `items`、不計入 `matchedCount`
- [x] 行情自 `2026-01-01` 起、不足 250 根但超過 100 根的股票不列入 `insufficientData`，照常判定
- [x] `buyDate` 等於 `signalDate`；`pendingConfirm` 恆為空陣列；交叉發生在該檔最新一筆日線時照常命中
- [x] 同一檔在區間內兩次交叉 → 只回報最近一次
- [x] 以相鄰交易日比較：D−1 與 D 之間有停牌造成的日曆間隔時，判定結果與無間隔時一致
- [x] 行情以批次查詢讀取：全市場掃描時查詢次數不隨股票數增加；MACD 與 KDJ 同一次送出時行情查詢次數與只送其中一個時相同
- [x] 十個策略同一次送出時 `results` 依送入順序回傳十筆，既有八個型態的命中結果與未加本功能時相同

MACD 黃金交叉：
- [x] D−1 `OSC < 0`、D `OSC > 0` → 命中，`signalDate` 為 D；D−1 `OSC = 0`、D `OSC > 0` → 命中（前一日為 ≤ 0）；D−1 與 D 皆 > 0 → 不命中；D−1 < 0、D `OSC = 0` → 不命中（當日須 > 0）
- [x] 零軸之上與零軸之下的交叉皆命中（構造 DIF 為正、為負的交叉各驗一次）
- [x] 自訂天數生效：同一段行情以 `fastPeriod: 5, slowPeriod: 10` 與預設 `5／20` 掃描得到不同的命中日，且各自等於以該組天數依公式手算的交叉日
- [x] `detail` 為 `dif`／`dea`／`osc`／`prevOsc` 四位小數，`osc` 與 `dif − dea` 相差不超過捨入誤差 `0.0001`
- [x] 回應回 `fastPeriod`、`slowPeriod`、`signalPeriod`（`9`），不含 `preset`；省略參數時回 `5` 與 `20`

KDJ 黃金交叉：
- [x] 構造 D−1 `K < D`、`J = 19.04`，D 當日 `K > D`，`jThreshold: 40` → 命中，`signalDate` 為 D
- [x] 同一交叉、D−1 的 J 恰等於 `jThreshold` → 不命中（嚴格小於）；D−1 的 J 高於 `jThreshold` → 不命中
- [x] `jThreshold` 可為負：D−1 的 `J = -12.3` 的交叉，`jThreshold: -10` 命中、`jThreshold: -15` 不命中
- [x] 判定以 K、D 比較：D−1 `K = D`（此時 J = K = D，未「同時高於」）、D 當日 `K > D` → 命中；D−1 `K > D` → 不命中（前一日已在上方，不是穿越）
- [x] `detail` 的 `j` 與 `3k − 2d`、`prevJ` 與 `3·prevK − 2·prevD` 相差不超過捨入誤差 `0.0003`
- [x] 回應回 `jThreshold`，不含 `preset`；省略時回 `40`

驗證與用語：
- [x] 兩型態帶 `preset` → `400 PRESET_NOT_APPLICABLE`；帶 `risePercent` → `400 PARAM_NOT_APPLICABLE`（`param` 為 `risePercent`）；帶 `days` → `400 DAYS_NOT_APPLICABLE`
- [x] 其他型態帶 `fastPeriod`／`slowPeriod`／`jThreshold`，或 MACD 帶 `jThreshold`、KDJ 帶 `fastPeriod` → `400 PARAM_NOT_APPLICABLE`，`param` 指名該欄位
- [x] `fastPeriod` 為 `1`、`51`、`12.5` → `400 INVALID_FAST_PERIOD`；`slowPeriod` 為 `2`、`101`、`26.5` → `400 INVALID_SLOW_PERIOD`；皆帶 `strategy: "MACD_GOLDEN_CROSS"`
- [x] `fastPeriod: 26, slowPeriod: 26`、`fastPeriod: 30, slowPeriod: 20`、只送 `fastPeriod: 30`（`slowPeriod` 取預設 20）→ `400 INVALID_MACD_PERIODS`；`fastPeriod: 60, slowPeriod: 20` 回 `INVALID_FAST_PERIOD`（範圍錯誤優先）
- [x] `jThreshold` 為 `-100.1`、`100.1`、`40.25` → `400 INVALID_J_THRESHOLD`；`-100`、`100`、`-12.5` 為合法請求
- [x] 兩型態的名稱、說明文字與回應欄位名皆無「建議」「推薦」「進場」等暗示買賣操作的措辭

### MACD 預設天數改為 5／20

- [x] `GET /api/strategies` 中 `MACD_GOLDEN_CROSS` 的 `fastPeriod.default` 為 `5`、`slowPeriod.default` 為 `20`；兩者的 `min`／`max`／`step`／`lessThan` 與 `KDJ_GOLDEN_CROSS`、其餘八個策略的條目皆不變
- [x] `MACD_GOLDEN_CROSS` 不帶 `fastPeriod`／`slowPeriod` 掃描，結果與明確送 `fastPeriod: 5, slowPeriod: 20` 完全相同（`items`、`insufficientData`、`matchedCount`），回應回 `fastPeriod: 5`、`slowPeriod: 20`、`signalPeriod: 9`
- [x] 明確送 `fastPeriod: 12, slowPeriod: 26` 時，命中日仍與日 K 圖所用指標（`specs/backend/stock-indicator-statistics.md`）的 MACD 黃金交叉日一致——改預設不影響指定 12／26 的結果
- [x] 以其一省略時的預設值比較 `lessThan`：只送 `slowPeriod: 5`（`fastPeriod` 取預設 5）或 `slowPeriod: 3` → `400 INVALID_MACD_PERIODS`；只送 `slowPeriod: 6` → 合法；只送 `fastPeriod: 19` → 合法、只送 `fastPeriod: 20` → `400 INVALID_MACD_PERIODS`

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

### Increment 6 — 2026-09-09

本次執行的是「`risePercent` 上限逐型態認定」增量：箱型突破／底底高／上漲支撐由 0~50 收緊為 0~20，反彈／累積上漲維持 0~50 不變；反彈的 `dropPercent`（0~50、`INVALID_DROP_PERCENT`）未觸碰。

**設計取向**：延續既有的 `PatternDetector` Strategy 介面（各實作各自宣告自己的參數表），新增一個 default 方法 `getRisePercentMax()`，預設回傳 `50`（REBOUND／CUMULATIVE_RISE 沿用預設，不覆寫）；`BoxBreakoutDetector`／`HigherLowsDetector`／`RisingSupportDetector` 三者覆寫為 `20`。`StrategyScanService.validateRisePercent` 原本用一個系統級常數 `RISE_PERCENT_MAX` 比對，改為向該次選到的 `detector` 詢問 `getRisePercentMax()`。原本身兼二職（同時撐住 `risePercent` 與 `dropPercent` 兩種驗證）的常數，拆成兩個：`risePercent` 的上限改由各 detector 決定，`dropPercent` 專屬的 `DROP_PERCENT_MAX`（固定 `50`，REBOUND 專用、不隨型態變動）留在 service 層。`validatePercentInRange` 因此多一個 `max` 參數，呼叫端（目前只有 `dropPercent`）自行傳入。

**`GET /api/strategies` 端點未變動**：箱型突破／底底高／上漲支撐三型態走 `presets`（無 `params` 陣列），本來就沒有把 `risePercent` 的數值上下限放上線，因此收緊上限不需要跟著改這三筆的 wire 內容；`CUMULATIVE_RISE`／`REBOUND` 的 `params[].risePercent.max` 本來就寫死在各自 detector 建構子裡的 `ParamDto`（`"50"`），本次未觸碰，仍是 `50`（新增測試 `getStrategies_cumulativeRiseAndReboundParamMax_stillFifty` 直接對這兩筆做斷言）。因此「廣告值與實際驗證一致」對這三型態而言，實際上是「未廣告任何數值，故無不一致可言」；驗證端的收緊本身即是本增量要做的事。

**新增測試（10 個，均在 `StrategyScanIntegrationTest`）**：
- `boxBreakout_risePercentUpperBoundTightenedTo20_20ValidBut20Point1Rejected`
- `higherLows_risePercentUpperBoundTightenedTo20_20ValidBut20Point1Rejected`
- `risingSupport_risePercentUpperBoundTightenedTo20_20ValidBut20Point1Rejected`
- `risingSupport_risePercent30Rejected_notSilentlyAcceptedThenZeroHits`（AC「上漲支撐送 30 回 400 而非受理後回零命中」）
- `rebound_and_cumulativeRise_risePercent30StillValid_upperBoundNotTightenedAlongside`
- `risePercent_perStrategyBound_mixedRequest_namesTheActuallyOffendingStrategy`（`HIGHER_LOWS: 15`〔合法〕排在前、`BOX_BREAKOUT: 25`〔在新制下超限、在舊制下曾經合法〕排在後，斷言回應的 `strategy` 是 `BOX_BREAKOUT`，不是先被檢查到的那筆）
- `rebound_dropPercentUpperBoundUnchanged_50ValidRemains`（`dropPercent=50.1` 的既有測試已涵蓋上界拒絕；本測試補上 `50` 本身仍合法的邊界）
- `risingSupport_risePercentZero_andSingleDecimalDigitRule_unchanged`
- `cumulativeRise_risePercentMoreThanOneDecimalDigit_stillRejected`
- `getStrategies_cumulativeRiseAndReboundParamMax_stillFifty`

**驗證**：`mvn -f develop/backend/pom.xml test` — **351/351 通過**（本次之前為 341/341；`StrategyScanIntegrationTest` 由 95 增至 105，淨增 10）。`mvn -f develop/backend/pom.xml compile`／`test-compile` 皆先行確認過乾淨編譯。全程未啟動即時伺服器；執行前後皆確認 8080 埠是空的。

**已驗證的驗收項對照**（供人工勾選）：
- 箱型突破／底底高／上漲支撐送 `risePercent: 20` 合法、`20.1` 回 `400 INVALID_RISE_PERCENT` 且 `strategy` 指名該策略 —— 三個新測試各自驗證。
- 反彈／累積上漲送 `risePercent: 50` 合法（既有測試 `risePercent_upperBoundRaisedTo50_...`／`cumulativeRise_risePercentValidationUnchanged_...` 覆蓋）、`50.1` 回 `400` 且 `strategy` 指名該策略（同上，未改動、重跑仍綠燈）。
- 反彈／累積上漲送 `risePercent: 30` 仍合法 —— `rebound_and_cumulativeRise_risePercent30StillValid_...` 驗證。
- 上漲支撐送 `risePercent: 30` 回 `400`、`strategy` 為 `RISING_SUPPORT` —— `risingSupport_risePercent30Rejected_...` 驗證。
- 同一請求一個策略超限、其餘合法時，`strategy` 指名實際超限者 —— `risePercent_perStrategyBound_mixedRequest_namesTheActuallyOffendingStrategy` 驗證（刻意使用「舊制合法、新制超限」的 25，且把超限者排在合法者之後，排除「永遠回報第一筆」的假陽性）。
- 反彈 `dropPercent` 上限維持 `50`、錯誤仍為 `INVALID_DROP_PERCENT` —— 既有測試 `rebound_invalidDropPercent_rejected`（`50.1` 拒絕）加新測試 `rebound_dropPercentUpperBoundUnchanged_50ValidRemains`（`50` 合法）共同驗證；`StrategyScanService`／`ReboundDetector` 均未改動 `dropPercent` 的驗證路徑。
- `risePercent` 為 `0` 與小數位數規則（最多一位）在五個型態上未改變 —— `HIGHER_LOWS`／`BOX_BREAKOUT`／`REBOUND`／`CUMULATIVE_RISE` 原有測試重跑仍綠燈；新增 `risingSupport_risePercentZero_andSingleDecimalDigitRule_unchanged` 補上 `RISING_SUPPORT` 這一型態原本缺的覆蓋，`cumulativeRise_risePercentMoreThanOneDecimalDigit_stillRejected` 補上 `CUMULATIVE_RISE` 的小數規則覆蓋。
- `GET /api/strategies` 中 `CUMULATIVE_RISE`／`REBOUND` 的 `params[].max` 仍為 `50` —— `getStrategies_cumulativeRiseAndReboundParamMax_stillFifty` 新增驗證；既有 catalog 測試（斷言 `params[].max` 為 `"50"`）亦重跑仍綠燈。

**`code-quality` skill 自我審查**：檢視本次 diff（`PatternDetector` 新 default 方法、三個 detector 的覆寫、`StrategyScanService` 的參數化）——無 null 安全問題（`getRisePercentMax()` 一律回傳非 null 的 `BigDecimal`）、無資源生命週期或原子性疑慮（純讀取、無狀態變更）、無重複邏輯（上限值單一來源即各 detector 自身）。審查中順手把 `DROP_PERCENT_MAX` 常數的註解重寫一次，讓「`dropPercent` 上限固定不隨型態變動」與「`risePercent` 上限逐型態」的對比更清楚，屬單純措辭調整、無行為變更。未發現需要修正之處。

**未能驗證的部分**：無。本增量純屬驗證邏輯改動，Testing constraints 要求的「100% mock-driven、不打真實 Yahoo/FinMind/TWSE」與既有 `StrategyScanIntegrationTest` 的既有慣例一致（該測試類別本身只操作測試資料庫的 `stock`/`stock_daily_price`，不涉外部 API），未新增任何違反此限制的測試。

**變更檔案**：`PatternDetector`（新增 `getRisePercentMax()` default 方法）、`BoxBreakoutDetector`／`HigherLowsDetector`／`RisingSupportDetector`（各自覆寫為 `20`）、`StrategyScanService`（`validateRisePercent` 改為詢問 detector；`validatePercentInRange` 新增 `max` 參數；新增 `DROP_PERCENT_MAX` 常數取代原本身兼二職的 `RISE_PERCENT_MAX`）、`StrategyScanIntegrationTest`（新增 10 個測試）。

### Increment 7 — 2026-09-13

本次執行的是「每一筆命中回報 `buyDate`（進場日）」增量：上漲支撐 `Acceptance Criteria` 表中的 5 項未勾選項目（每筆 `items[]` 皆含 `buyDate`；上漲支撐 `buyDate` 為 D+2 且等於 `confirmCloses` 最後一筆的 `tradeDate`；停牌缺列時仍以相鄰交易列認定 D+2，不以日曆日加 2；`buyDate` 可晚於 `endDate`；其餘四型 `buyDate` 等於 `signalDate`）全數完成。未觸碰回測端點（`StrategyBacktestService` 及其 DTO／測試），依 scope boundary 保留給後續 spec。

- Status: DONE
- Files changed:
  - `develop/backend/src/main/java/com/stock/service/pattern/PatternDetectionOutcome.java` — 新增 `buyDate` 欄位與 `getBuyDate()`；原本的 `hit(LocalDate signalDate, Object detail)` 保留為「`buyDate` 等於 `signalDate`」的便利多載（BOX_BREAKOUT／HIGHER_LOWS／REBOUND／CUMULATIVE_RISE 四個既有呼叫點因此零改動），新增 `hit(LocalDate signalDate, LocalDate buyDate, Object detail)` 供 RISING_SUPPORT 明確回報兩個不同日期
  - `develop/backend/src/main/java/com/stock/service/pattern/RisingSupportDetector.java` — 在既有迴圈中新增 `lastBuyDate`，取 `confirmCloses` 最後一筆（即 `bars.get(i + confirmBars)`）的 `tradeDate`；由於 `confirmCloses` 本身已經是依相鄰交易日列（`bars` 的 list index）取值、從不做日曆日運算，`buyDate` 自然滿足「停牌缺列時仍取相鄰交易列」與「可晚於 `endDate`」兩項規則，不需額外程式碼
  - `develop/backend/src/main/java/com/stock/dto/StrategyHitDto.java` — 新增 `buyDate` 欄位、建構子參數與 getter/setter
  - `develop/backend/src/main/java/com/stock/service/StrategyScanService.java` — `runStrategy` 建立 `StrategyHitDto` 時多傳入 `outcome.getBuyDate()`
  - `develop/backend/src/test/java/com/stock/StrategyScanIntegrationTest.java` — 新增 8 個測試（對應 5 項驗收項；其中「其餘四型 `buyDate` 等於 `signalDate`」拆成四個獨立測試）：
    - `risingSupport_buyDateIsDPlus2_equalsLastConfirmCloseTradeDate_signalDateStaysD` — 沿用 spec 本身的 1296/1272/1248 手算構造資料，驗證 `buyDate` 為 D+2 那一列的交易日且等於 `confirmCloses` 最後一筆的 `tradeDate`，同時 `signalDate` 仍為 D
    - `risingSupport_buyDateSkipsSuspensionGap_usesAdjacentTradingRowNotCalendarArithmetic` — D+1 之後刻意留一個日曆缺列（停牌），D+2 落在 D+1 之後 3 個日曆日而非 1 個；驗證 `buyDate` 仍等於該相鄰交易列的實際日期，且明確斷言不等於 `d.plusDays(2)`
    - `risingSupport_buyDateCanBeAfterEndDate_reportedNormally` — `endDate` 設為 D 當日，D+1／D+2 取自資料庫中 `endDate` 之後已存在的列；驗證 `buyDate`（= D+2）晚於 `endDate` 仍正常回報
    - `boxBreakout_buyDateEqualsSignalDate`／`higherLows_buyDateEqualsSignalDate`／`rebound_buyDateEqualsSignalDate`／`cumulativeRise_buyDateEqualsSignalDate` — 各自沿用該型態既有的手算命中構造資料，斷言 `buyDate` 與 `signalDate` 相等
- Notes:
  - 未使用 `design-patterns` skill：本次是既有 `PatternDetector`/`PatternDetectionOutcome` seam 上加一個欄位，不是新增可替換的實作或多變體行為，屬於「plain field addition」，不構成該 skill 觸發條件所述的擴充點。
  - `buyDate` 的「等於 `signalDate`」預設走便利多載，`RISING_SUPPORT` 走明確雙日期多載——兩者在型別層級就區分開，不會有第三個型態不小心漏設 `buyDate` 而序列化出 `null`（`hit(...)` 是建立命中結果的唯一入口，兩個多載都保證回傳非 null 的 `buyDate`）。
  - `code-quality` skill 自我審查（詳見流程）：`buyDate` 在 `RisingSupportDetector` 中讀自已建好、保證非空的 `confirmCloses` 清單最後一筆，無 null 風險；`StrategyHitDto` 只新增一個欄位與對應 getter/setter，未變動既有欄位語意；純記憶體運算，無資源生命週期、原子性或效能疑慮（不新增查詢、不在迴圈內做重複工作）。唯一既有呼叫點（`StrategyScanService.runStrategy`）已同步更新。未發現需要修正之處，也沒有刻意留下未處理的問題。
  - 驗證：先跑 `mvn -f develop/backend/pom.xml test -Dtest=StrategyScanIntegrationTest` → **113 tests, 0 failures, 0 errors**（105 既有 + 8 新增）；再跑全量 `mvn -f develop/backend/pom.xml -o test` → **400 tests, 0 failures, 0 errors**。過程中僅執行測試，未啟動即時伺服器；回測端點（`StrategyBacktestService`/`StrategyBacktestIntegrationTest`）完全未觸碰，其既有 41 個測試在全量跑中原樣通過。

### Increment 8 — 2026-09-16

新增三個法人籌碼型態（法人買賣超佔比、法人連續買超、法人買超強度排名），37 項驗收全數完成。策略目錄由五個增為八個。

**實作方式**：沿用既有的 `PatternDetector` seam 與「`presets` 為空即由 `params` 驅動」的目錄形狀新增三個偵測器，未另立平行架構；法人資料以批次查詢讀取，與既有掃描讀行情的作法一致。`ParamDto` 新增 `type`（`multiSelect`）與 `options`／`minSelected`，讓前端能通用地畫出複選框而不必認得 `investors` 這個名字。

**執行過程**：本增量的實作在第一次執行時因用量限制中途中斷。中斷後程式與測試已在磁碟上且全套測試通過，因此第二輪不是重做，而是**逐項稽核**：對 37 項驗收各自指認實作位置與證明它的測試，並判斷該測試是否真的證明了驗收所寫的內容（而非較弱的版本）。稽核結論為 37 項全部已達成、**未再修改任何程式**。

**特別確認的四項**（這些驗收本身就是為了抓出「看似正確」的實作）：
- 窗口比率為**合計相除**：日買超 `[1,2,3,4,5]` 百萬股對成交 `[10,10,10,10,60]` 百萬股得 `15.00`，逐日比率平均會得 `21.67`——測試以手算值斷言前者。
- **先加總再取絕對值**：`[+8,−8,+8,−8,+1]` 百萬股對成交合計 50 百萬股得 `+1`、`2.00%`，門檻 10% 不命中；逐日取絕對值會得 66%。
- **法人資料批次讀取**：以查詢計數器實測，`stock_institutional_trade` 的查詢次數在股票數 2→6、法人型態 1→3 時皆不變。
- **排名同分處理**：以 10.004% 與 10.001%（四捨五入後同為 10.00）驗證依原值排名，原值相同時再依 `stockId` 升冪。

**外資口徑**：不含外資自營商（`foreign_dealer_*`），寫入端與讀取端皆已確認一致。

**資料**：以資料庫中 30,878 筆真實法人資料（1,328 檔、24 個交易日）為背景執行，稽核前後列數不變，未寫入或刪除任何真實資料。

**驗證**：`mvn -f develop/backend/pom.xml test` — **510/510 通過、0 失敗**（本增量開始前為 473），由我另外獨立重跑確認，並抽查其中 9 個關鍵測試方法確實存在於測試檔中。

### Increment 9 — 2026-09-19

本次執行的是「MACD 黃金交叉 / KDJ 黃金交叉」增量：`技術指標型態` 一節的 30 項未勾選驗收全數完成。策略目錄由八個增為十個。

**執行前的實際狀態**：commit `3825736`「Add MACD and KDJ golden-cross strategy scans」（已在 `main` 分支上）已經實作了 `MacdGoldenCrossDetector`、`KdjGoldenCrossDetector`、相關 DTO／例外／驗證邏輯，並在同一次提交中新增了 955 行測試，但未勾選任何驗收項、也未寫 Execution Result。本次的工作因此主要是**逐項稽核**：對照 30 項驗收，逐一確認實作與測試是否真的證明了驗收所寫的內容，而不是重做整個功能。

**稽核方式**：逐一讀過 `MacdGoldenCrossDetector`／`KdjGoldenCrossDetector`／`IndicatorCalculationService`／`PatternDetector`／`StrategyScanService`（驗證與批次讀取部分）／`StrategyCatalogService`／相關 DTO 與例外類別，對照 spec 的「技術指標型態」共通規則與兩個型態各自的判定規則，並執行既有的 25 個 MACD/KDJ 專屬測試方法逐一核對其斷言是否對應到驗收項本身（而非較弱的版本）。

**稽核中發現並修正的三個真實缺陷**（皆非"稽核字面正確、實測卻失敗"式問題，而是先跑過全套測試才發現的）：

1. **`jThreshold` 的實際 wire 欄位名是 `jthreshold`（全小寫），不是 spec 要求的 `jThreshold`**——這是本次稽核中唯一真正影響 API 契約的缺陷。`StrategySelectionDto`／`StrategyResultDto` 的 getter/setter 命名為 `getJThreshold()`/`setJThreshold()`；因為方法名去掉 `get`/`set` 前綴後前兩個字元都是大寫（`JT`），Jackson 預設（legacy）的 bean 屬性命名演算法對這種「開頭連續兩個大寫字母」的名稱會整段轉小寫，產生 `jthreshold` 而非一般情況下的 `jThreshold`（對照 `getFastPeriod()`→`fastPeriod`、`getK()`→`k` 皆無此問題，因為它們的開頭不是連續兩個大寫字母）。由於本專案的整合測試是用同一個 Java `RestTemplate` 物件序列化請求、又用同一套 Jackson 規則反序列化，兩邊用的是同一個（錯誤的）欄位名，因此「送 `jThreshold` 給後端、後端真的照這個值判定」這條路徑「碰巧」測試綠燈；但任何照 spec 送出字面 `{"jThreshold": 90}` 的真實用戶端（例如前端），這個值都不會被後端讀到（靜默改用預設值 `40`），而後端回應中也不會出現 `jThreshold` 這個鍵，只有 `jthreshold`。已在 `StrategySelectionDto`／`StrategyResultDto` 的 `getJThreshold`/`setJThreshold` 加上 `@JsonProperty("jThreshold")`，把 wire 名稱釘死，不依賴 Jackson 的命名演算法。
2. `kdjGoldenCross_detailJFormula_matchesThreeKMinusTwoD_echoesJThreshold` 與 `kdjGoldenCross_invalidJThreshold_boundariesAndValidValues` 兩個既有測試原本應該會抓到上述缺陷（皆會讀取回應的 `jThreshold` 欄位），但實際執行時拋出 `NullPointerException` 而非有意義的斷言失敗——這正是本次全套測試跑出來、而非稽核閱讀程式碼能單靠肉眼發現的問題。修正後兩者皆綠燈，不需改動測試本身的斷言邏輯。
3. `macdGoldenCross_zeroAxisAboveAndBelow_bothCountAsHit` 與 `kdjGoldenCross_jThresholdCanBeNegative_hitsAndDoesNotHit` 兩個測試的**夾具**（既有 commit 自帶的測試碼，非本次新寫）用的是單一週期性正弦波行情；正弦波的 MACD/KD 遞迴一旦收斂進入穩態，同一相位會不斷重複，導致同一種黃金交叉（同一種 DIF 正負號、同一個 `prevJ` 值）在整段序列裡永遠只出現一種，另一種（DIF 為負／`prevJ` 為負）永遠不會出現——用手算（Python 重現同一套 `IndicatorCalculationService` 遞迴公式）驗證後確認：400 天、振幅 20、週期 34 天的正弦波在 [100,400) 內的 9 次黃金交叉 DIF 恆為負；振幅 45、週期 8 天的正弦波的 37 次交叉 `prevJ` 恆為 +25.02。這不是實作缺陷，是這兩個測試自己的夾具設計缺陷（無法達成它們自己宣稱要驗證的兩種情境）。已將夾具改為：
   - MACD 測試改用兩檔獨立的「持續趨勢 + 中途短暫逆勢回檔」序列（漲勢配合回檔製造 DIF 為正的交叉；跌勢配合反彈製造 DIF 為負的交叉），取代單一正弦波、單一股票。
   - KDJ 測試改用「120 日走平（讓 K/D 收斂至中性 50）→ 10 日持續破底下跌（K 因直接反應 RSV 而比 D 跌得快，拉出負值 J）→ 急彈 + 短暫回升」的構造序列，取代原本的正弦波，穩定產生 `prevJ` 為負（實測 `prevJ ≈ -4.91`）的交叉。
   - 兩個新夾具的產生方式與其不可行的原因，皆已記錄在新增的 `buildTrendWithDip`／`buildDeclineThenBounceSeries` 方法註解中。

**其餘 27 項驗收**：逐一核對後皆已由既有測試正確涵蓋，不需改動實作或測試（詳見下方測試對照）。

**測試對照**（30 項 → 測試方法，*標記為本次修正過的測試）：
- 型態目錄 3 項 → `catalog_returnsTenStrategies_macdAndKdjAppendedInOrderMatchingSpecJson`（含 lessThan／unit／min 斷言）、`catalog_existingEightStrategies_unaffected_noLessThanFieldAnywhere`
- 共通規則 9 項 → `macdAndKdj_sharedFormula_scanDetailMatchesIndicatorCalculationServiceToFourDecimals`、`macdAndKdj_doNotReadOrWriteStockDailyIndicatorTable`、`macdAndKdj_warmupCap250_changingBarsBeforeTheCutoffDoesNotChangeResult`、`macdAndKdj_warmupFloor_99DaysNotJudged_100DaysJudged`、`macdAndKdj_between100And250Days_notInsufficientData_judgesNormally`、`macdAndKdj_buyDateEqualsSignalDate_pendingConfirmAlwaysEmpty_evenAtLatestBar`、`macdAndKdj_sameStockTwoCrossesInRange_reportsLatestOnly`、`macdAndKdj_adjacentTradingDayComparison_calendarGapDoesNotChangeResult`、`macdAndKdj_priceQueriesAreBatched_macdAndKdjTogetherSameCountAsEitherAlone`、`tenStrategiesTogether_resultsInSubmittedOrder_existingEightStrategiesUnaffected`
- MACD 5 項 → `macdGoldenCross_flatThenJump_prevOscZeroOrNegativeAndCurrPositive_hits_bothPositiveOrZero_doesNotHit`、`macdGoldenCross_zeroAxisAboveAndBelow_bothCountAsHit`*、`macdGoldenCross_customPeriods_differFromDefault_matchHandCalc`、`macdGoldenCross_detailOscMatchesDifMinusDea_withinRoundingTolerance`、`macdGoldenCross_responseEchoesFastSlowSignalPeriod_defaultsWhenOmitted`
- KDJ 6 項 → `kdjGoldenCross_realCrossing_prevJBelowThreshold_hits_signalDateIsD`、`kdjGoldenCross_prevJEqualOrAboveThreshold_doesNotHit_strictlyLessThan`、`kdjGoldenCross_jThresholdCanBeNegative_hitsAndDoesNotHit`*、`kdjGoldenCross_judgedByKDComparison_notJDirectly`、`kdjGoldenCross_detailJFormula_matchesThreeKMinusTwoD_echoesJThreshold`*
- 驗證與用語 6 項 → `macdAndKdj_presetRisePercentDaysNotApplicable`、`otherStrategiesRejectFastSlowJThreshold_macdRejectsJThreshold_kdjRejectsFastPeriod`、`macdGoldenCross_invalidFastPeriodAndSlowPeriod`、`macdGoldenCross_invalidMacdPeriods_fastNotStrictlyLessThanSlow`、`kdjGoldenCross_invalidJThreshold_boundariesAndValidValues`*、`macdAndKdj_noAdviceOrEntryExitWording`

**額外修正（與本增量的產品程式碼無關，純測試維護）**：`catalog_containsBoxBreakoutAndHigherLowsWithThreePresetsEachMatchingSpecWording`／`catalog_returnsThreeStrategiesIncludingRisingSupportMatchingSpecWording`／`catalog_returnsFiveStrategiesIncludingReboundAndCumulativeRiseMatchingSpecWording`／`catalog_returnsEightStrategiesIncludingInstitutionalPatternsMatchingSpecWording`／`catalog_cumulativeRise_hasEmptyPresetsPlusDescriptionAndParams` 五個既有測試斷言策略總數為 `8`，本次新增兩個型態後理應為 `10`；沿用 increment 2～4 的既有慣例（型態目錄合法成長時更新過期的計數斷言）更新為 `10`，未改動這些測試原本要驗證的其餘內容。另外 `institutional_noDataAtAllInPeriod_dataThroughDateNull_allInsufficientData` 原本寫死 `2026-03-10` 為「保證沒有法人資料」的日期，但隨真實資料日復一日累積，該日期已經有真實法人資料（`SELECT COUNT(*) FROM stock_institutional_trade WHERE trade_date BETWEEN '2026-03-06' AND '2026-03-11'` → 5203 筆），導致此測試變得脆弱；改為 `LocalDate.now().plusYears(5)`，使其不受時間推移影響。

**`design-patterns` skill**：未使用。`MacdGoldenCrossDetector`／`KdjGoldenCrossDetector` 是既有 `PatternDetector` Strategy 介面的第 9、10 個實作，延續既有的擴充點，非新設計；本次工作也主要是稽核既有程式碼與修正測試，沒有新增需要套用設計模式的結構。

**`code-quality` skill 自我審查**：這次審查直接發現了上述缺陷 1（`jThreshold` 的 wire 命名）——這正是「API contract」一類要檢查的項目：回應/請求的欄位名稱是否真的等於文件宣稱的名稱，而不是「程式碼看起來對」就假設沒問題。修正方式是在 getter/setter 加上明確的 `@JsonProperty`，把 wire 契約與 Java 方法命名脫鉤，不依賴 Jackson 演算法的隱含行為；其餘 DTO 逐一檢查過，沒有其他「開頭連續兩個大寫字母」的 getter/setter（`getK`/`getD`/`getJ`/`getDif`/`getDea`/`getOsc`/`getPrevOsc`/`getPrevK`/`getPrevD`/`getPrevJ`/`getFastPeriod`/`getSlowPeriod`/`getSignalPeriod` 皆無此問題）。另確認 `StockDailyIndicator.getKValue()`/`getDValue()`/`getJValue()`（同樣命名形狀）**不會**被序列化到任何回應——它是 MyBatis 內部 domain 物件，對外的 K/D/J 走的是 `StatisticsSeriesRowDto` 的獨立 `k`/`d`/`j` 欄位；記錄於此供未來若真的把 `StockDailyIndicator` 直接序列化時參考，本次未改動該類別。空值安全、例外處理、資源生命週期、原子性、效能（批次查詢）皆已在既有實作中妥善處理，無需修正。

**驗證**：`mvn -f develop/backend/pom.xml -o test-compile` 乾淨編譯；`mvn -f develop/backend/pom.xml -o test -Dtest=StrategyScanIntegrationTest` → **178/178 通過**；全量 `mvn -f develop/backend/pom.xml -o test` → **538 之中 532 通過、6 個失敗**，失敗全部集中在 `StockInstitutionalTradeIngestionIntegrationTest`（`specs/backend/institutional-trade-ingestion.md` 的測試，非本 spec 範圍），確認為既有、與本次改動無關的日期漂移問題——該測試以 `LocalDate.now()` 與寫死在 2026-03 的 `MockRestServiceServer` 期望值搭配使用，隨著系統日期推進到 2026-09-19，`findMissingTradeDates` 計算出的「缺漏日期」範圍已超出測試當初寫死的期望請求集合，導致「Further request(s) expected」。此檔案自 commit `a657d0a`（不屬於本次任何一個 strategy-scan 增量）後未再變動，且與 MACD/KDJ 的程式碼、DTO、驗證邏輯完全無交集（不同的 controller、service、mapper），故判定為超出本次任務範圍，予以記錄但未修正。

**未能驗證的部分**：無（就本 spec 的 30 項驗收而言）。上述 `StockInstitutionalTradeIngestionIntegrationTest` 的 6 個失敗屬於另一份 spec 的既有缺陷，留待該 spec 的下一次 `/dev` 執行處理。

**變更檔案**：
- `develop/backend/src/main/java/com/stock/dto/StrategySelectionDto.java`（修正：`getJThreshold`/`setJThreshold` 加上 `@JsonProperty("jThreshold")`，修正 wire 命名缺陷）
- `develop/backend/src/main/java/com/stock/dto/StrategyResultDto.java`（同上）
- `develop/backend/src/test/java/com/stock/StrategyScanIntegrationTest.java`（新增 `buildTrendWithDip`／`buildDeclineThenBounceSeries`／`closeOnlyRow` 三個測試輔助方法；重寫 `macdGoldenCross_zeroAxisAboveAndBelow_bothCountAsHit`／`kdjGoldenCross_jThresholdCanBeNegative_hitsAndDoesNotHit` 的夾具；更新 5 個既有型態目錄測試的過期策略總數斷言（`8`→`10`）；`institutional_noDataAtAllInPeriod_dataThroughDateNull_allInsufficientData` 改用相對未來日期）
- `specs/backend/strategy-scan.md`（本檔：勾選 30 項驗收、frontmatter 改為 `status: done`、新增本節）

未變動 `MacdGoldenCrossDetector`／`KdjGoldenCrossDetector`／`IndicatorCalculationService`／`PatternDetector`／`StrategyScanService`／`StrategyCatalogService`／`ParamDto`／`ErrorResponse`／`GlobalExceptionHandler`／四個新例外類別——commit `3825736` 原本的產品程式碼（`jThreshold` 命名缺陷所在的兩個 DTO 除外）經逐項稽核與全套測試驗證後判定正確，無需重寫。

### Increment 10 — 2026-09-19

本次執行的是「MACD 黃金交叉預設天數由 12／26 改為 5／20」增量：`### MACD 預設天數改為 5／20` 一節的 4 項驗收，以及「驗證與用語」清單中因此改寫的 3 項（自訂天數生效；省略參數回 5／20；只送 `fastPeriod: 30` → `INVALID_MACD_PERIODS`，`slowPeriod` 取新預設 20），共 7 項全數完成。訊號線固定為 9、`fastPeriod`／`slowPeriod` 的範圍與 `lessThan` 機制、KDJ 與其餘八個型態皆未改動。

**實作**：`MacdGoldenCrossDetector.FAST_PERIOD_DEFAULT`／`SLOW_PERIOD_DEFAULT` 原本直接借用 `IndicatorCalculationService.DEFAULT_FAST_PERIOD`／`DEFAULT_SLOW_PERIOD`（即 `12`／`26`）。這兩個 `IndicatorCalculationService` 常數是 `stock_daily_indicator` 表固定寫入的日 K 圖 MACD 副圖參數（`specs/dba/stock-daily-indicator.md`），必須維持 `12`／`26` 不變；本次把 `MacdGoldenCrossDetector` 的掃描預設改為它自己的常數 `5`／`20`，與指標表的持久化預設解耦，不再共用同一組常數。`StrategyScanService` 的 `fastPeriod`／`slowPeriod` 範圍驗證與 `lessThan` 比較（`fast >= slow` 時擲 `InvalidMacdPeriodsException`）本就呼叫 `detector.getFastPeriodDefault()`／`getSlowPeriodDefault()` 而非寫死數字，`StrategyCatalogService` 的 `GET /api/strategies` 也只是原樣回傳 `detector.getParams()`；兩者因此不需要任何程式碼改動，改常數後自動生效。

**測試**：`StrategyScanIntegrationTest` 中原本有 9 處測試以 `groundTruth(rows, 12, 26)` 搭配 `macdSelection(null, null)`（省略參數走預設值）比對命中日／`detail`，這些原本隱含假設「預設 = 12／26」，改預設後全部需要更新為 `groundTruth(rows, 5, 20)`：`catalog_returnsTenStrategies_macdAndKdjAppendedInOrderMatchingSpecJson`（`fastPeriod.default`／`slowPeriod.default` 斷言改為 `5`／`20`）、`macdAndKdj_between100And250Days_notInsufficientData_judgesNormally`、`macdAndKdj_buyDateEqualsSignalDate_pendingConfirmAlwaysEmpty_evenAtLatestBar`、`macdAndKdj_sameStockTwoCrossesInRange_reportsLatestOnly`、`macdAndKdj_adjacentTradingDayComparison_calendarGapDoesNotChangeResult`、`macdGoldenCross_zeroAxisAboveAndBelow_bothCountAsHit`、`macdGoldenCross_customPeriods_differFromDefault_matchHandCalc`（`defaultGround`）、`macdGoldenCross_detailOscMatchesDifMinusDea_withinRoundingTolerance`、`macdGoldenCross_responseEchoesFastSlowSignalPeriod_defaultsWhenOmitted`（省略時的回應斷言改為 `5`／`20`）、`macdAndKdj_noAdviceOrEntryExitWording`。`macdAndKdj_sharedFormula_scanDetailMatchesIndicatorCalculationServiceToFourDecimals` 原本也以省略參數依賴舊預設 12／26 來驗證公式共用，本次改為明確送 `macdSelection(12, 26)`，讓它繼續驗證「明確指定 12／26 時公式仍與指標運算逐日一致到小數第四位」這件事，而不是巧合地依賴預設值。

新增 4 個測試方法，逐一對應本次改寫的驗收：
- `macdGoldenCross_customPeriods_differFromDefault_matchHandCalc` 內補上 `assertNotEquals(defaultDate, customDate)` 斷言——原測試只各自驗證命中日符合手算交叉日，未斷言兩者確實不同，這正是「自訂天數生效」要求的「得到不同的命中日」。
- `macdGoldenCross_omittedPeriods_matchesExplicitFiveTwenty_itemsInsufficientDataMatchedCount`：省略參數與明確送 `fastPeriod: 5, slowPeriod: 20` 比對 `items`／`insufficientData`／`matchedCount` 完全相同，並確認回應欄位為 `5`／`20`／`9`。
- `macdGoldenCross_explicitTwelveTwentySix_unaffectedByDefaultChange`：明確送 `12`／`26` 時命中日與指標運算的 ground truth 一致，證明改預設不影響指定 12／26 的結果。
- `macdGoldenCross_lessThanComparesAgainstNewDefaults_whenOtherFieldOmitted`：只送 `slowPeriod: 5`／`slowPeriod: 3` → `INVALID_MACD_PERIODS`（`fastPeriod` 取新預設 `5`）；只送 `slowPeriod: 6`／`fastPeriod: 19` → 200（`fastPeriod`／`slowPeriod` 取新預設後仍嚴格小於）；只送 `fastPeriod: 20` → `INVALID_MACD_PERIODS`（取新預設 `slowPeriod: 20` 後相等）。

`macdGoldenCross_invalidMacdPeriods_fastNotStrictlyLessThanSlow` 的既有斷言（`fastPeriod: 26/slowPeriod: 26`、`fastPeriod: 30/slowPeriod: 20`、只送 `fastPeriod: 30`、`fastPeriod: 60/slowPeriod: 20`）本身邏輯不受預設改動影響（`30 >= 20` 新舊預設皆成立），僅更正一行過期註解（`// slowPeriod defaults to 26` → `20`），不影響斷言結果，對應驗收「只送 `fastPeriod: 30`（`slowPeriod` 取預設 20）→ `400 INVALID_MACD_PERIODS`」。

**驗證**：`mvn -f develop/backend/pom.xml compile` 與 `test-compile` 皆乾淨通過；`mvn -f develop/backend/pom.xml test` → **541 之中 535 通過、6 個失敗**，全部集中在 `StockInstitutionalTradeIngestionIntegrationTest`（`applyDay_midBatchFailure_rollsBackWholeDay_dayStaysMissingForNextTrigger`、`masterFilter_unknownIdNeverWritten_stockRowCountUnchanged_inactiveAndEtfLikeIdsWrittenNormally`、`nonTradingDayAndMalformedDates_bothSkippedWithoutFailure_validDateStillWritten`、`priceBackfillCompletion_triggersInstitutionalCatchUp_writesTheNewlyBackfilledDate`、`realT86Fixture_2609And00632R_matchDocumentedValues_sourceIsTwse`、`timeout_retriedThenSkipped_continuesToNextDate`）——與 Increment 9 記錄的成因相同（測試以寫死在 2026-03 的 `MockRestServiceServer` 期望值搭配 `LocalDate.now()`，隨系統日期推進到 2026-09-19 而失準），與本次 MACD 預設值改動無交集，依任務範圍界定不予修正。`StrategyScanIntegrationTest` 本身 **181/181 全數通過**。

**`design-patterns` skill**：未使用。本次只是把既有 `PatternDetector` 實作內的兩個預設值常數改指向新字面值，並解除與另一服務常數的耦合，沒有新增結構或擴充點。

**`code-quality` skill 自我審查**：確認改動後 `MacdGoldenCrossDetector` 的預設值常數不再與 `IndicatorCalculationService`（持久化指標表用）共用，避免「改一個地方、兩種語意的常數一起變動」的耦合風險；`StrategyScanService` 的驗證邏輯本就透過 `detector.getFastPeriodDefault()`／`getSlowPeriodDefault()` 存取，未寫死數字，因此範圍檢查與 `lessThan` 比較在改常數後行為正確、無需修改。未發現需要修正的空值安全、例外處理或效能問題。

**變更檔案**：
- `develop/backend/src/main/java/com/stock/service/pattern/MacdGoldenCrossDetector.java`（`FAST_PERIOD_DEFAULT`／`SLOW_PERIOD_DEFAULT` 由借用 `IndicatorCalculationService` 的 `12`／`26` 改為自有常數 `5`／`20`）
- `develop/backend/src/test/java/com/stock/StrategyScanIntegrationTest.java`（更新 9 處依賴舊預設 12／26 的既有測試；`macdAndKdj_sharedFormula_...` 改為明確送 12／26；新增 4 個測試方法；更正 1 行過期註解）
- `specs/backend/strategy-scan.md`（本檔：勾選 7 項驗收、frontmatter 改為 `status: done`、新增本節）
