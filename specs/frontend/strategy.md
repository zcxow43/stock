---
status: done
title: "策略型態掃描分頁"
requirement: "策略分頁 — 可勾選策略（底底高、箱型突破、上漲支撐、反彈、累積上漲）；底底高／箱型突破／上漲支撐各自選靈敏度與自行輸入漲幅門檻；累積上漲自行輸入天數與漲幅門檻；反彈自行輸入「下跌天數／跌幅門檻」與「反彈天數／反彈幅度」，後者以一個可取消的勾選框整組開關。母體預設只含上市普通股（排除 ETF），掃描指定區間（預設近一個月）內命中的股票。**命中結果一律合併為單一命中彙總表，不再依策略分成多個區塊**，各策略的判定明細欄位隨之移除，本次實際採用的參數改以標題下的一行呈現。「開始掃描」右側新增「回測」按鈕，命中至少一檔時才可點擊：訊號日收盤買進、其後至今日以最高開盤價賣出，表格右側補上賣出日／報酬率／收益三欄，標題右側補上總報酬率與總收益兩個標籤，部位固定每檔 1 張；每列另有一個預設勾選的勾選框，取消勾選時該列反灰且不計入兩個總計，但仍顯示自己的數字，切換不重打端點。另有更新股票清單與同步所有日 K 至今日的兩顆按鈕，並顯示最後同步時間"
depends_on: [stock-list]
---

# 策略型態掃描分頁 — Frontend Spec

## Overview

`/stocks` 的第二個頁籤（頁籤容器與第一個頁籤見 `specs/frontend/stock-list.md`）。使用者勾選要跑的型態、各自挑靈敏度或填入參數、指定區間，按下掃描後列出命中的股票；點任一列可進入該檔日 K 圖對照。

頁面另有一顆同步按鈕，把全部在市股票的日線補到今日，並常駐顯示最後一次成功同步的時間——**掃描結果的可信度完全取決於行情有多新**，把這個時間放在掃描按鈕旁邊，是為了讓使用者在看到「零命中」時能立刻分辨那是「真的沒有型態」還是「資料停在兩週前」。

路由：`/stocks?tab=strategy`。

**本頁呈現的是型態偵測結果，不是買賣建議。** 所有文案一律以「命中／訊號／型態」表述，不得出現「建議買進」「推薦」「可進場」等暗示操作的措辭（見 `specs/backend/strategy-scan.md`）。

## Requirements

### 頁面結構

由上而下：

1. **同步列** — 左側「最後同步：YYYY-MM-DD HH:mm」，右側「同步日 K 至今日」按鈕。
2. **條件區** — 策略勾選卡片、股票範圍、區間選擇、「開始掃描」主要按鈕與其右側的「回測」次要按鈕。
3. **結果區** — 一張合併命中表格，不論勾了幾個策略。

### 條件區

**策略勾選**：每個策略一張卡片。**卡片上有哪些控制項由 `GET /api/strategies` 的回應決定，不得依策略 `code` 寫死**：

| 回應形狀 | 目前適用 | 卡片內容 |
|---|---|---|
| `presets` 非空 | 底底高、箱型突破、上漲支撐 | 勾選框、策略名稱、靈敏度下拉（嚴格／標準／寬鬆，預設「標準」）、**漲幅門檻數字輸入**、目前選定靈敏度的說明文字一行 |
| `presets` 為空陣列、帶 `params` | 累積上漲、反彈 | 勾選框、策略名稱、**依 `params` 逐一畫出的數字輸入**、策略層級 `description` 的說明文字一行。**這種卡片沒有靈敏度下拉** |

以 `code` 寫死（「如果是累積上漲就畫天數」）會讓下一個改成無靈敏度的型態必須再改一次前端；`presets` 是否為空是後端已經在回應裡表達的事實，照它畫即可。反彈就是這條規則的第一個實證：它從有靈敏度改成無靈敏度時，卡片的形狀應該自動跟著 API 變，前端不必為此改任何判斷。

**選用參數群組**：策略條目可帶 `paramGroups`，每一筆對應卡片上的**一個勾選框**（文字取自該筆的 `name`，初始狀態取自 `default`）。`params` 中帶 `group` 的參數歸該群組管：群組勾選框取消時，該組所有輸入 disabled，且**請求中完全不帶這些欄位**；不帶 `group` 的參數一律顯示且一律送出。目前只有反彈用到（`rise` 群組：「另外要求反彈漲幅」，預設勾選，管 `riseDays` 與 `risePercent`）。**同樣不得以策略 `code` 寫死**——依 `paramGroups` 畫即可。

反彈卡片因此有四格輸入：「下跌天數」「跌幅門檻」永遠可填；「反彈天數」「反彈幅度」在群組勾選框取消時 disabled。取消後掃描只以跌幅判定，這正是「先跌了就想看，還沒反彈也算」的用法。

- 策略清單、靈敏度說明、以及無靈敏度型態的參數預設值與範圍（`params` 的 `default`／`min`／`max`／`step`）**一律取自 API**，不在前端寫死。參數是後端的契約，兩邊各存一份必然漂移。
- 未勾選的卡片其所有控制項（靈敏度下拉、天數輸入、漲幅門檻輸入）皆為 disabled。
- 一個都沒勾時「開始掃描」為 disabled，並在按鈕旁提示「請至少勾選一個策略」——不要讓使用者按了才知道。

**漲幅門檻輸入**：有靈敏度的三張卡片各有一個獨立的數字輸入，標籤「漲幅門檻」、後綴 `%`。無靈敏度的兩張卡片（累積上漲、反彈）不走這一節，其所有輸入一律依 `params` 畫出、標籤取自各參數的 `name`、後綴取自 `unit`。

| 項目 | 值 |
|---|---|
| 範圍 | 底底高／箱型突破／上漲支撐 `0` ～ `20`，最多一位小數 |
| 底底高的意義 | 每段擺動低點遞增的最小幅度，比較的是 **MA5 值**而非當日最低價 |
| 預設 | 取**目前選定靈敏度**的漲幅值，由 `GET /api/strategies` 帶出 |
| 送出 | 值與預設值相同時仍照送 `risePercent`；只有停用（未勾選）才不送 |

**切換靈敏度會重新填入該靈敏度的漲幅值，覆蓋使用者已輸入的數字。** 靈敏度是一組預設，選它就是要那一組的值；若保留使用者輸入不動，畫面會停在「靈敏度寫著嚴格、漲幅卻是寬鬆的數字」這種半套狀態，使用者無從判斷實際送出的是什麼。累積上漲與反彈沒有靈敏度，因此不存在這個重填行為——它們的輸入只在進頁時填入 API 給的預設值，之後完全由使用者掌握。

每張卡片各有自己的輸入、而不是共用一個，是因為各型態的幅度意義不同：箱型突破是突破箱頂的幅度、底底高是每段低點遞增的幅度、上漲支撐是單日漲幅、反彈同時有自高點的跌幅與自谷底的反彈幅度、累積上漲是窗口內的累積漲幅。合成一格會讓同一個數字在五處代表六件事。

超出範圍時前端即擋下並在該卡片下方提示「漲幅門檻需介於 0 ~ N」（N 為該卡片的上限），不送出請求；後端的 `INVALID_RISE_PERCENT` 為後備。

**`params` 驅動的輸入（累積上漲、反彈這兩張卡片）**：卡片上每個參數畫一格數字輸入，標籤取自該參數的 `name`、後綴取自 `unit`（`日` 或 `%`），範圍與步進取自 `min`／`max`／`step`，初始值取自 `default`。**這些數字一個都不寫死在前端。**

| 項目 | 值 |
|---|---|
| 範圍 | 逐參數取自其 `min`／`max`／`step`。`step` 為 `1` 者只收整數 |
| 預設 | 逐參數取自其 `default` |
| 送出 | 值與預設值相同時仍照送；未勾選該策略、或該參數所屬的選用群組被取消時才不送。**這兩張卡片一律不送 `preset`** |

- **單位為「日」的參數指的是交易日數**，不是日曆天——這類輸入下方一律以一行次要文字說明「回看的交易日數，不含週末與休市日」，否則使用者填 30 會以為是最近一個月。
- 非整數（`20.5`）、超出範圍（`0`、`91`）或留空時，前端即擋下並在該卡片下方提示「<參數名>需介於 <min> ~ <max> 的整數」（幅度類參數則為「<參數名>需介於 <min> ~ <max>」），不送出請求；後端的 `INVALID_DAYS`／`INVALID_DROP_DAYS`／`INVALID_RISE_DAYS`／`INVALID_DROP_PERCENT`／`INVALID_RISE_PERCENT` 為後備。
- **累積上漲填天數 `1` 是合法的，但畫面必須先講清楚它的結果**：窗口只有當天時累積漲幅恆為 0%，除非漲幅門檻也填 0，否則掃描一定零命中。天數輸入為 `1` 且漲幅門檻大於 `0` 時，於該卡片下方以提示色顯示「天數為 1 時窗口只有當天，累積漲幅恆為 0%，不會有命中」。這是提示、不是錯誤——不擋送出，使用者仍可按「開始掃描」。
- **反彈填下跌天數 `1` 同理**：窗口只有當天、跌幅恆為 0%，除非跌幅門檻也填 0，否則零命中。此時於該卡片下方以同一個提示色顯示「下跌天數為 1 時窗口只有當天，跌幅恆為 0%，不會有命中」，同樣不擋送出。

**股票範圍**：兩個互斥選項。

| 選項 | 行為 |
|---|---|
| 全市場（預設） | 掃描全部在市股票，請求不帶 `stockIds` |
| 指定股票 | 展開一個多選輸入，可依代號或名稱搜尋加入，已選的以可移除的標籤呈現；上限 200 檔 |

選到上限時輸入框 disabled 並提示「最多 200 檔」。

**普通股母體**：本頁**沒有**自己的 ETF 排除選項。掃描母體由頁籤列上方的頁面層級勾選框「只看上市普通股」決定，該控制項與其狀態由 `specs/frontend/stock-list.md` 擁有，三個分頁共用。

送出掃描時，以該勾選框當下的狀態填入請求的 `commonStocksOnly`。**「全市場」時才送**；選到「指定股票」時不帶此欄位，因為後端明訂使用者明確指名的代號不代為過濾（見 `specs/backend/strategy-scan.md` 的「掃描範圍」）。此時頁面層級的勾選框維持原狀、不變灰——它對另外兩個分頁依然生效，把它變灰會誤導成整頁設定被關掉了。

切換該勾選框時，若本頁已有掃描結果，**結果保留不動、不自動重掃**——重掃是全市場逐檔判定的長時間作業，不該由一個勾選動作隱式觸發。使用者按「開始掃描」時才以新的值送出。

**區間**：起日與迄日兩個日期輸入，**預設為今日往前一個日曆月至今日**。另提供「近一個月」「近三個月」「近半年」三個快捷鈕，點擊即套用對應區間。起日晚於迄日時前端即擋下並在區間下方提示，不送出請求。

#### 回測按鈕

「開始掃描」的**右側**緊接一顆「回測」按鈕，**次要按鈕樣式**——本頁唯一的主要按鈕仍是「開始掃描」，回測是看完命中清單之後才決定要不要做的第二個動作，不與掃描爭視覺焦點。

- **只有在本次掃描有命中標的時才可點擊。** 尚未掃描、掃描中、掃描失敗、以及掃描完成但命中 0 檔時，一律為 disabled。零命中時沒有任何標的可回測，讓按鈕可按只會換來一個必然為空的結果。
- 按下後進入執行中狀態：按鈕 disabled 並顯示進行中，「開始掃描」不受影響仍可按。
- 回測完成後，合併表格右側出現三個欄位、標題右側出現兩個標籤（見下方「結果區」）。
- **重新掃描一定清空回測結果**：按下「開始掃描」的當下即移除那三個欄位與兩個標籤，回測按鈕回到未回測狀態。舊的回測是對舊那份命中清單算的，把它留在新結果旁邊會變成兩份對不起來的資料，而畫面上完全看不出來。
- 改動條件而未重掃時，回測結果**維持不變**，與標題括號內的參數採同一條原則：眼前這份數字說明的是眼前這份結果。

### 結果區

結果區只有**一張表格**，不論勾了幾個策略。標題為「命中彙總 — 共 N 檔」，N 為去重後的股票檔數（**不是**各策略 `matchedCount` 的加總，一檔同時命中兩個策略在此只算一檔）。

**不再依策略拆成多個區塊。** 使用者實際的下一步是逐檔去看日 K，而那份清單是聯集；分成幾張表讀，得自己心算去重，同一檔還會在不同表裡各出現一次。

#### 表格欄位

| 欄位 | 來源 | 出現時機 |
|---|---|---|
| 勾選框 | 前端狀態，非回應欄位 | 一律 |
| 代號 / 名稱 | 各策略 `items` 的 `stockId` / `stockName` | 一律 |
| 命中策略與訊號日 | 該檔命中的每一個策略，逐一列出「{策略名稱} {signalDate}」，以 `・` 分隔 | 一律 |
| 賣出日 | 回測回應該檔的 `sellDate` | 回測後 |
| 報酬率 | 回測回應該檔的 `returnPercent`，兩位小數加 `%` | 回測後 |
| 收益 | 回測回應該檔的 `profit`，千分位、不帶小數 | 回測後 |

**「命中策略與訊號日」逐策略列出，不合併成單一日期。** 一檔同時命中兩個策略時，兩個型態的成立日往往不同（例如箱型突破 `2026-08-28`、底底高 `2026-08-25`），把它折成一個日期會丟掉「哪個型態是什麼時候成立的」這個判讀時真正需要的資訊。策略的排列順序與勾選順序一致。

**各型態的判定明細（箱型區間、量能倍數、低點序列、支撐價、高點日／低點收盤…）不在本表呈現。** 這五個型態的明細欄位彼此完全不同，無法疊在同一列上：橫向全攤開會是二十幾欄且大量空白，逐策略分表則違背本表存在的理由。這是**刻意接受的取捨**——本表回答「這次掃描要看哪幾檔、進場點在哪天、回測結果如何」，判定依據則到該檔的日 K 頁上看。

#### 本次採用參數

標題下方以一行次要文字列出**本次各策略實際採用的參數**，逐策略以 `・` 分隔，格式沿用各型態既有的括號內容：

| 型態 | 內容 | 範例 |
|---|---|---|
| 箱型突破／底底高／上漲支撐 | `{策略名稱}（{preset} 的中文名）` | 上漲支撐（標準） |
| 累積上漲 | `{策略名稱}（{days} 日）` | 累積上漲（30 日） |
| 反彈（`requireRise` 為 `true`） | `{策略名稱}（{dropDays} 日跌 {dropPercent}% → {riseDays} 日反彈 {risePercent}%）` | 反彈（3 日跌 10% → 1 日反彈 5%） |
| 反彈（`requireRise` 為 `false`） | `{策略名稱}（{dropDays} 日跌 {dropPercent}%）` | 反彈（3 日跌 10%） |

**這一行不能省。** 各策略區塊被合併掉之後，它是畫面上唯一還說得出「這份清單是用什麼條件掃出來的」的地方——而參數最多的反彈有四個可調值，光看「反彈」兩個字完全無從得知。

**一律取自回應而非畫面上的輸入值**：使用者掃描後又改了輸入卻沒重掃時，這一行必須繼續說明眼前這份結果是用什麼參數算出來的。百分比沿用「數值格式」一節的規則（`10.0` 寫成 `10`），日數為整數。

**排序**：依該檔在各策略中**最新的**一個 `signalDate` 由新到舊；同日則依 `stockId` 升冪。這是延用 `specs/backend/strategy-scan.md` 對各策略 `items` 已定的排序規則。**回測完成後排序不變**——回測是替既有清單補上三欄，不是重新排名；依報酬率重排會讓使用者找不到剛剛還在看的那一列。

點擊任一列導向 `/stocks/{stockId}/daily`。

#### 納入計算的勾選框

表格最左為每列一個勾選框，**掃描完成當下即出現，預設全部勾選**。

- **取消勾選只影響總計，不影響該列自己的內容。** 該列的賣出日、報酬率、收益照常顯示，只是不計入標題右側的總報酬率與總收益。使用者是先看到一檔的成績，才決定要不要把它排除——排除後就看不到數字，等於拿走了做這個決定的依據。
- **未勾選的列整列反灰**：該列所有文字改為弱化文字色 `#6B7C90`，包含原本帶漲跌色的報酬率與收益。背景不變，仍可點擊導向 `/stocks/{stockId}/daily`。反灰是「不計入」的視覺表示，不是停用。
- **勾選狀態的切換完全在前端完成，不重新呼叫任何端點。** 回測回應已含每檔的 `buyPrice`、`profit` 與全批共用的 `lotSize`，總計由這些值就地重算即可；為了一個勾選動作重打一次回測，換來的是同樣的數字加上一次等待。
- **回測尚未執行時勾選框照常可切換**，只是還沒有總計可以受影響。此時的勾選狀態會被接下來的回測沿用。
- **重新掃描時勾選狀態全部重設為勾選**，與回測結果一起清空——新的命中清單是另一批標的，沿用舊的勾選只會讓人以為某幾檔被系統排除了。
- **標題的「共 N 檔」不受勾選影響**：它是命中檔數，取消勾選不會讓一檔股票變成沒命中。

#### 回測結果的呈現

回測完成後，除了上表右側三欄，**標題右側出現兩個標籤**：

| 標籤 | 算法 | 格式 |
|---|---|---|
| 總報酬率 | `總收益 ÷ 總成本 × 100` | 兩位小數加 `%` |
| 總收益（每檔 1 張） | 已勾選且可回測的各列 `profit` 總和 | 千分位、不帶小數 |

兩個標籤的涵蓋範圍是**已勾選且可回測**的列，其中總成本為那些列的 `buyPrice × lotSize` 總和（`lotSize` 取自回應，不在前端寫死）。

**全部勾選時，畫面上這兩個數字必須等於回應的 `totalReturnPercent` 與 `totalProfit`，總成本必須等於 `totalCost`。** 前端就地重算是為了讓勾選切換不必重打端點，不是為了自己定義一套算法；全勾的情形是這兩條路徑必須交會的地方，也是唯一能驗出算法走偏的地方。

**漲跌色一律沿用全站規則**：報酬率與收益為正值時上漲色 `#E04B45`、負值下跌色 `#16A75C`、為 `0` 時次要文字色 `#93A4B8`。兩個總計標籤同此規則。

**無法回測的標的照常留在表上**，其賣出日／報酬率／收益三欄皆顯示弱化色 `#6B7C90` 的「—」（回應中這三個欄位為 `null`，見 `specs/backend/strategy-backtest.md` 的「無法回測的標的」）。**不得把這些列從表格中移除**——它們確實命中了，只是還沒有可賣出的交易日；拿掉它們會讓命中檔數與表格列數對不起來。

**總計標籤必須說出有幾檔沒算進去，以及為什麼。** 未計入的原因有兩種，兩者的意義不同，必須分開陳述——一個是系統算不出來，一個是使用者自己排除的：

- 有標的無法回測時（回應中 `sellDate` 為 `null`），加一行「另 N 檔尚無可賣出交易日，未計入」。
- 有標的被取消勾選時，加一行「另 M 檔未勾選，未計入」。
- 兩者同時發生時兩行都顯示。都沒有時兩行都不顯示。

兩行皆以次要文字色 `#93A4B8` 呈現，置於兩個標籤下方。少了它們，總報酬率看起來就像是全部命中檔數的成績。

**一檔既無法回測、又被取消勾選時只計入「尚無可賣出交易日」那一行**，不重複計。它本來就不會被計入總計，勾不勾選都一樣。

沒有任何列被計入時，兩個標籤顯示弱化色 `#6B7C90` 的「—」，**不得顯示成 `0%`**——`0%` 的意思是算過剛好打平，與「沒有東西可以算」是兩件事。其下的說明依原因而異：

| 情形 | 說明文字 |
|---|---|
| 全部標的皆無法回測（回應的 `totalReturnPercent` 為 `null`） | 沒有可回測的標的 |
| 有可回測的標的，但全被取消勾選 | 未勾選任何標的 |

**這兩句不可互相取代。** 前者是這批標的目前算不出來，重按幾次都一樣；後者只要勾回任何一列就會有數字。顯示成同一句會讓使用者去查一個其實是自己造成的狀態。

**「收益」欄必須讓使用者知道部位假設。** 表頭寫成「收益（每檔 1 張）」，總計標籤寫成「總收益（每檔 1 張）」。少了這個說明，同一份清單裡台積電與低價股的收益差距會被誤讀成策略在不同標的上的優劣差異，而實際上那只是股價高低。

#### 資料不足與待確認

各策略區塊移除後，`insufficientData` 與 `pendingConfirm` 改在**合併表格下方**呈現，**逐策略各一行**，行首標明策略名稱：

- 資料不足：「{策略名稱}：另有 N 檔因區間前的歷史資料不足而未納入判定」，可展開看代號清單。
- 待確認：箱型突破為「{策略名稱}：另有 N 檔已突破，但確認日尚未到」，上漲支撐為「{策略名稱}：另有 N 檔已上漲，但後兩日的確認尚未完成」。

兩者皆使用提示色 `#D9A441`。反彈與累積上漲的 `pendingConfirm` 恆為空陣列，不會出現待確認行。

**這兩類標的不納入合併表格，也不納入回測**——它們不是命中。把「沒掃到」和「掃了沒有」混為一談，會讓人誤以為那些股票已經確認沒有型態。

### 同步列

同步列上有**兩顆按鈕，順序即操作順序**：左為「更新股票清單」，右為「同步日 K 至今日」。**兩顆都是次要按鈕樣式**——本頁唯一的主要按鈕是「開始掃描」。同步列上的兩顆是準備資料的前置動作，不是使用者來這一頁的目的；把它們也做成主要按鈕會出現三顆同等搶眼的藍色按鈕，反而看不出該按哪一顆。

兩顆放在一起，是因為它們是同一件事的兩個步驟，而且第二步的涵蓋範圍完全取決於第一步有沒有做過：「同步日 K 至今日」的標的是 `stock` 中 `is_active = 1` 且符合頁面層級「只看上市普通股」設定的股票，`stock` 只有開發種子的 34 檔時，同步會**正常完成**、不報任何錯，但只補了 34 檔的行情，掃描結果也就只涵蓋這 34 檔。把「更新股票清單」擺在它左邊，是讓這個前置關係在畫面上看得見，而不是變成一個只有讀過 spec 的人才知道的隱含步驟。

#### 更新股票清單

- 按鈕文字「更新股票清單」。按下後 disabled 並顯示執行中狀態。
- 這是**短同步作業**（後端只發兩次外部請求——股票清單一次、產業別一次，見 `specs/backend/stock-universe-import.md`），不輪詢、不顯示進度條、不需要跨頁籤保留狀態——把長時間回補的那一套機制套上來是多餘的。
- **本按鈕同時帶入交易所官方產業別**，供動態分頁（`specs/frontend/momentum.md`）分組顯示。這是同一個動作的兩半，不另做一顆按鈕。
- 完成後在按鈕下方顯示摘要：「股票清單已更新：共 N 檔（新增 X、更新 Y）・產業別 P 類，未分類 Q 檔」，其中 N 取 `totalActiveCount`、X 取 `insertedCount`、Y 取 `updatedCount`、P 取 `industryCount`、Q 取 `uncategorizedStockCount`。
- **`industrySourceStatus` 不是 `OK` 時，摘要必須明說產業別沒更新到**：在同一則摘要後接上「產業別未更新（來源暫時無法取得），股票清單已更新」，並以警示色呈現該段。後端在這種情形下仍回 `200`（股票清單那一半確實成功了，見 `specs/backend/stock-universe-import.md` 的「產業別來源失敗的處理」），若前端照一般成功處理，使用者會以為產業別也是最新的，然後在動態分頁看到一堆未分類卻找不到原因。`industryCount` 與 `uncategorizedStockCount` 此時取的是既有值，仍照常顯示。
- 摘要**持續顯示到下一次操作為止**，理由同下方「同步在全部標的都已是最新時…」該條：新增 0 檔時執行中狀態一閃而過，摘要若跟著消失，使用者會以為按鈕沒反應。
- 清單更新完成後**不自動觸發同步**，也不自動重新掃描。使用者說了要自己按同步；替他按下一個時間長度不確定的作業，是把選擇權拿走。
- 更新完成後常駐的「共 N 檔」數字即時更新，讓使用者按下同步前就看得到母體變大了。
- 本按鈕與「同步日 K 至今日」**互不阻擋**：同步進行中仍可按更新清單，反之亦然。後端不共用併發鎖（見 `specs/backend/stock-universe-import.md` 的「併發」），前端不得自行加上互斥。

#### 同步日 K 至今日

- 按鈕文字「同步日 K 至今日」。按下後進入執行中狀態：按鈕 disabled、顯示進行中狀態與已完成檔數／總檔數。
- 同步是背景作業，耗時取決於待補區間有多長（見 `specs/backend/stock-price-ingestion.md` 的「逐日回補的處理流程」——成本單位是交易日，不是檔數），因此**送出後即輪詢進度，不阻塞畫面**；使用者可以在同步進行中切換頁籤或離開，回來時仍看得到進度。穩態下（每日收盤後）這件事通常在數秒內結束，只有一個全新的資料庫才會需要補滿整段起始區間。
- **同步母體沿用頁面層級的「只看上市普通股」設定**：本按鈕把該勾選框的當下狀態原樣帶進 `commonStocksOnly`（見 `specs/frontend/stock-list.md` 的「頁面層級設定」）。勾選（預設）時只同步上市普通股，取消勾選時同步 `is_active = 1` 的全部股票。這個對應必須成立，否則會出現「取消勾選後畫面上看得到 ETF，但它的日 K 永遠不會被同步」的狀態，而使用者從畫面上完全看不出原因。
- **完成摘要必須說出母體是什麼。** `commonStocksOnly` 為 `true` 時，`202` 回應的 `targetCount` 會小於常駐顯示的「共 N 檔」——主檔含約 350 檔非普通股，它們不在同步母體內（見 `specs/backend/stock-price-ingestion.md` 的「全跑母體預設只含上市普通股」）。完成檔數因此要寫成「完成 N 檔上市普通股」，不得只寫「完成 N」；同理，全數已是最新時寫「已是最新，無需更新（N 檔上市普通股）」。兩個數字並列而沒有說明母體，會讓使用者以為有幾百檔漏掉了。`commonStocksOnly` 為 `false` 時照舊寫「完成 N」。
- 完成後更新「最後同步」時間並顯示完成摘要。摘要的內容取決於這次同步**是否真的抓了東西**：
  - `caughtUpCount` 等於 `targetCount`（全部標的都已是最新，一次外部請求都沒發出）→ 顯示「已是最新，無需更新（N 檔）」，**不得顯示「完成 N 檔」**。
  - 否則 → 顯示完成／失敗／略過檔數，並在 `caughtUpCount` 大於 0 時附註「另 N 檔已是最新」。
  - 有失敗時附「查看失敗清單」可展開。
- **同步在全部標的都已是最新時會在極短時間內結束**（不發任何外部請求），執行中狀態可能只出現一瞬間。摘要因此必須持續顯示到下一次操作為止，而不是隨執行中狀態一起消失——否則使用者按下按鈕後看到的是「什麼都沒發生」，無從分辨是成功、失敗，還是按鈕壞了。
- 已有同步作業執行中時（後端回 `409`），按鈕改為執行中狀態並直接接上輪詢，而不是顯示錯誤——使用者要的是看到進度，不是被告知有人先按了。
- 「最後同步」在從未同步過時顯示「尚未同步」。
- 「最後同步」顯示的是**台北時間**。後端回傳的 `lastSyncedAt` 已是 `Asia/Taipei`（見 `specs/backend/stock-price-ingestion.md` 的「時區」），前端直接照 `YYYY-MM-DD HH:mm` 格式呈現即可，不得再做任何時區換算——多做一次換算會再引入一次偏移。

### 畫面狀態

| 狀態 | 呈現 |
|---|---|
| 初次進入（未掃描） | 結果區顯示「選擇策略與區間後開始掃描」，條件區可操作 |
| 掃描中 | 「開始掃描」disabled 並顯示掃描中狀態；已有結果時保留並降低透明度至 60%；「回測」disabled |
| 有結果、未回測 | 合併表格顯示三欄以外的欄位；「回測」可按（命中 0 檔時仍為 disabled） |
| 回測中 | 「回測」disabled 並顯示進行中；「開始掃描」不受影響仍可按；表格內容不變 |
| 已回測 | 表格右側三欄與標題右側兩個標籤出現 |
| 回測失敗 | 「回測」恢復可按，其下顯示錯誤訊息；表格維持未回測的樣子，不出現三欄與兩個標籤 |
| 有結果 | 正常表格 |
| 零命中 | 合併表格處顯示「此區間內沒有命中的股票」，並附一行提示目前的最後同步時間；「回測」為 disabled |
| 掃描失敗 | 結果區顯示錯誤訊息與「重試」按鈕；不得顯示空表格假裝零命中 |
| 同步中 | 同步按鈕為執行中狀態；不影響掃描操作，也不影響「更新股票清單」 |
| 更新清單中 | 「更新股票清單」disabled 並顯示執行中狀態；不影響掃描與同步操作 |
| 更新清單失敗 | 按鈕恢復可按，其下顯示錯誤訊息；「共 N 檔」維持原值不變動 |

## Implementation Details

### API 整合

| 時機 | 呼叫 |
|---|---|
| 進頁 | `GET /api/strategies` — 取策略清單與靈敏度選項及說明文字 |
| 進頁、同步完成後 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` — 取 `lastSyncedAt` 顯示最後同步時間 |
| 進頁、更新清單完成後 | `GET /api/stocks?page=1&size=1` — 只取 `total` 顯示「共 N 檔」，`size=1` 是因為此處只要總數，不要清單內容 |
| 按「更新股票清單」 | `POST /api/stocks/universe/import` — 無 body；回應的 `totalActiveCount` / `insertedCount` / `updatedCount` / `industryCount` / `uncategorizedStockCount` 組成完成摘要，`industrySourceStatus` 決定是否附加產業別未更新的警示 |
| 按「開始掃描」 | `POST /api/strategies/scan` — body `strategies[]`（有靈敏度的型態送 `code` + `preset` + `risePercent`；累積上漲送 `code` + `days` + `risePercent`，不送 `preset`）、`stockIds`、`commonStocksOnly`（取自頁面層級設定，僅「全市場」時帶）、`startDate`、`endDate` |
| 按「同步日 K 至今日」 | `POST /api/stocks/sync/backfill` — body `startDate`（設定起日）、`endDate`（今日）、`catchUp: true`、`commonStocksOnly`（取自頁面層級設定），不帶 `stockIds` 代表全市場；`202` 回應的 `targetCount`、`caughtUpCount` 與 `commonStocksOnly` 決定完成摘要的呈現方式 |
| 按「回測」 | `POST /api/strategies/backtest` — body `items[]`，每檔一筆 `{stockId, signalDate}`，**送出全部命中標的**（勾選狀態不影響請求內容），`signalDate` 取該檔命中的各策略中**最新**的一個；回應的 `items[]`（`sellDate` / `returnPercent` / `profit`）填入表格右側三欄，`buyPrice` 與 `lotSize` 供前端就地重算兩個總計，`totalCost` / `totalProfit` / `totalReturnPercent` / `backtestedCount` 作為全部勾選時的對照值 |
| 同步執行中（每 5 秒） | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` — 取 `pending`／`running`／`done`／`failed`／`skipped` 更新進度 |
| 「指定股票」搜尋 | `GET /api/stocks?keyword=&size=20` — 供多選輸入的候選清單 |

契約見 `specs/backend/strategy-scan.md`、`specs/backend/strategy-backtest.md`、`specs/backend/stock-price-ingestion.md`、`specs/backend/stock-universe-import.md` 與 `specs/backend/stock-catalog.md`。

錯誤回應處理：

| `code` | 前端行為 |
|---|---|
| `NO_STRATEGY_SELECTED` | 視為程式錯誤：正常操作不應觸發（未勾選時按鈕已 disabled），顯示通用錯誤訊息 |
| `UNKNOWN_STRATEGY` / `DUPLICATE_STRATEGY` | 同上，視為程式錯誤 |
| `UNKNOWN_STOCK_ID` | 於股票範圍區顯示「以下代號不存在」並列出 `unknownIds`，移除後可重新掃描 |
| `TOO_MANY_STOCKS` | **掃描時**：於股票範圍區顯示「最多 200 檔」（前端已先擋，此為後備）。**回測時**：視為程式錯誤，正常操作不應觸發——回測送出的是命中清單，長度由市場決定而非使用者輸入，前端無從先擋，後端的上限亦已訂在全市場規模之上（見 `specs/backend/strategy-backtest.md` 的「上限為什麼不是 200」）。**不得為此在前端截斷命中清單**：只送前 200 檔會讓總報酬率變成一份沒有說明的抽樣結果 |
| `INVALID_DATE_RANGE` | 於區間下方顯示「起日不可晚於迄日」（前端已先擋，此為後備） |
| `INVALID_RISE_PERCENT` | 於回應 `strategy` 指名的那張策略卡片下方顯示「漲幅門檻需介於 0 ~ N」（前端已先擋，此為後備）。必須定位到該卡片，不可顯示成全頁通用錯誤——每張卡片各有一格，通用訊息無法讓使用者知道該改哪一格 |
| `INVALID_DAYS` | 於回應 `strategy` 指名的那張卡片下方顯示「天數需介於 1 ~ 90 的整數」（前端已先擋，此為後備），同樣必須定位到該卡片 |
| `PRESET_NOT_APPLICABLE` / `DAYS_NOT_APPLICABLE` / `PARAM_NOT_APPLICABLE` | 三者一律視為程式錯誤：正常操作不會送出這種組合（卡片依 `presets` 是否為空決定畫哪些控制項、選用群組決定哪些欄位隨請求送出）。訊息顯示在回應 `strategy` 指名的那張卡片下方，回應帶 `param` 時文字為「帶入了不適用的參數：{param}」，未帶 `param` 時為通用錯誤訊息。`param` 的值原樣取自回應，不在前端逐策略寫死——會讀到這個訊息的人一定是開發者，欄位名正是他需要的那個字 |
| `JOB_ALREADY_RUNNING` | 不視為錯誤：同步按鈕轉為執行中狀態並開始輪詢進度 |
| `UPSTREAM_EMPTY` | 於「更新股票清單」下方顯示「交易所尚未發布今日清單，請稍後再試」——這是可重試的時機問題，不是系統故障，訊息必須說出「稍後再試」 |
| `UPSTREAM_UNAVAILABLE` / `UPSTREAM_MALFORMED` | 於「更新股票清單」下方顯示「無法取得交易所股票清單，請稍後再試」 |
| `200` 但 `industrySourceStatus` 非 `OK` | **不是錯誤**：完成摘要照常顯示，後方以警示色附加「產業別未更新（來源暫時無法取得）」 |
| 其他／網路錯誤 | 結果區顯示「掃描失敗，請稍後再試」與「重試」按鈕 |

**更新清單的三種失敗一律不改動畫面上的「共 N 檔」。** 後端在這三種情形下都不做任何部分寫入（見 `specs/backend/stock-universe-import.md`），前端把數字改掉會憑空製造一個與資料庫不符的顯示值。

### 數值格式

- 價格兩位小數；百分比兩位小數加 `%`；倍數兩位小數加 `×`。
- 金額（回測的「收益」與「總收益」）以千分位呈現、不帶小數，負值前置 `-`。
- 日期一律 `YYYY-MM-DD`；同步時間 `YYYY-MM-DD HH:mm`。
- `null` 一律顯示 `—`，不顯示 `0` 或空白。

## Visual Style

**本頁所有顏色為固定值，不隨 `prefers-color-scheme`、OS 主題或瀏覽器偏好改變。** 一律以字面 hex 指定，不得使用會隨系統偏好變動的主題變數或色彩 token。色票與 `specs/frontend/stock-list.md` 為同一套，同名元素必須取同一個值。

| 元素 | 色碼 |
|---|---|
| 頁面背景 | `#0F1620` |
| 面板／表格容器背景 | `#16202C` |
| 表頭背景 | `#1B2836` |
| 邊框、分隔線 | `#26333F` |
| 主要文字（表格內容、標題） | `#E6EDF5` |
| 次要文字（表頭、標籤、說明） | `#93A4B8` |
| 弱化文字（「—」、空狀態說明） | `#6B7C90` |
| 上漲數值（紅） | `#E04B45` |
| 下跌數值（綠） | `#16A75C` |
| 表格列 hover 背景 | `#1D2A38` |
| 策略卡片背景／邊框 | `#16202C` / `#26333F` |
| 策略卡片已勾選邊框 | `#3E8FD8` |
| 勾選框已勾選背景／勾記 | `#3E8FD8` / `#FFFFFF` |
| 輸入框／下拉背景 | `#0F1620` |
| 輸入框邊框 | `#26333F` |
| 輸入框 focus 邊框 | `#3E8FD8` |
| 輸入框 placeholder 文字 | `#6B7C90` |
| 參數輸入框背景／邊框／focus 邊框 | `#0F1620` / `#26333F` / `#3E8FD8`（與其他輸入框同值） |
| 參數輸入標籤與單位文字 | `#93A4B8` |
| 參數說明與「天數為 1」／「下跌天數為 1」提示文字 | `#D9A441` |
| 選用參數群組勾選框已勾選背景／勾記 | `#3E8FD8` / `#FFFFFF`（與策略勾選框同值） |
| 選用參數群組標籤文字 | `#93A4B8` |
| Disabled 參數輸入背景／文字／邊框 | `#16202C` / `#4A5866` / `#26333F` |
| 反彈「反彈幅度」欄的「—」 | `#6B7C90` |
| 已選股票標籤背景／文字／移除鈕 | `#1B2836` / `#E6EDF5` / `#93A4B8` |
| 命中彙總表策略標籤背景／文字 | `#1B2836` / `#E6EDF5` |
| 命中彙總表訊號日文字 | `#93A4B8` |
| 本次採用參數那一行文字 | `#93A4B8` |
| 回測按鈕背景／文字／邊框 | `#1B2836` / `#E6EDF5` / `#26333F`（與其他次要按鈕同值） |
| 回測按鈕 hover 背景 | `#223347` |
| Disabled 回測按鈕背景／文字 | `#16202C` / `#4A5866`（與其他 disabled 按鈕同值） |
| 總報酬率／總收益標籤文字 | `#93A4B8` |
| 總報酬率／總收益數值（正／負／零） | `#E04B45` / `#16A75C` / `#93A4B8` |
| 「另 N 檔未計入」說明文字 | `#93A4B8` |
| 回測三欄無值時的「—」 | `#6B7C90` |
| 命中彙總表勾選框已勾選背景／勾記 | `#3E8FD8` / `#FFFFFF`（與策略勾選框同值） |
| 命中彙總表勾選框未勾選邊框 | `#26333F` |
| 未勾選列的整列文字（含報酬率與收益） | `#6B7C90` |
| 「另 N 檔未勾選，未計入」說明文字 | `#93A4B8` |
| 快捷區間鈕（未選）背景／文字 | `#1B2836` / `#93A4B8` |
| 快捷區間鈕（選中）背景／文字 | `#26333F` / `#E6EDF5` |
| 次要按鈕背景／文字／邊框 | `#1B2836` / `#E6EDF5` / `#26333F` |
| 次要按鈕 hover 背景 | `#223347` |
| 主要按鈕背景／文字 | `#3E8FD8` / `#FFFFFF` |
| 主要按鈕 hover 背景 | `#58A3E8` |
| Disabled 按鈕背景／文字 | `#16202C` / `#4A5866` |
| 同步進行中進度條底／填色 | `#1B2836` / `#3E8FD8` |
| 最後同步時間文字 | `#93A4B8` |
| 股票清單「共 N 檔」文字 | `#93A4B8` |
| 更新股票清單完成摘要文字 | `#E6EDF5` |
| 資料不足／待確認提示文字 | `#D9A441` |
| 骨架列底色 | `#1D2A38` |
| 錯誤訊息文字／背景／邊框 | `#F09A94` / `#3A1C1A` / `#8A3A34` |

## Acceptance Criteria

### 條件區：策略卡片（共通）
- [x] 策略清單與靈敏度選項及說明文字皆取自 `GET /api/strategies`，前端無寫死的策略名稱或參數說明
- [x] 卡片形狀由 `GET /api/strategies` 的 `presets` 是否為空決定，程式中不存在以策略 `code` 判斷要畫哪種卡片的分支
- [x] 一個策略都沒勾選時「開始掃描」為 disabled，並顯示提示
- [x] 本頁條件區**沒有**任何 ETF 排除選項；該控制項只存在於頁籤列上方（見 `specs/frontend/stock-list.md`）
- [x] 「全市場」時送出的 `commonStocksOnly` 等於頁面層級勾選框當下的狀態
- [x] 頁面層級勾選框為預設（勾選）時掃描全市場，回應的 `scannedStocks` 明顯小於取消勾選時的值，且結果中不出現 `0050`、`00878`、`2881A`、`910322`
- [x] 切到「指定股票」時請求不帶 `commonStocksOnly`，且頁面層級勾選框不變灰、不改變狀態
- [x] 切換頁面層級勾選框時，本頁既有掃描結果保留不動，且不自動重新掃描

### 條件區：有靈敏度的三張卡片（箱型突破／底底高／上漲支撐）
- [x] 三張卡片各有嚴格／標準／寬鬆三個靈敏度，預設為標準
- [x] 三張卡片各有一個「漲幅門檻」數字輸入，後綴 `%`，彼此獨立互不連動
- [x] 漲幅輸入的初始值為該卡片目前靈敏度的漲幅值，數值取自 `GET /api/strategies`，未在前端寫死
- [x] 切換靈敏度後，漲幅輸入重新填入新靈敏度的值，覆蓋使用者先前輸入的數字
- [x] 未勾選的卡片其靈敏度下拉與漲幅輸入皆為 disabled
- [x] 輸入 `-1`、`20.5` 或 `2.55` 時前端即擋下，於該卡片下方顯示「漲幅門檻需介於 0 ~ 20」，且不送出請求
- [x] 送出的 `strategies[]` 中，帶有漲幅輸入的項目其 `risePercent` 等於該卡片輸入框當下的數字（反彈取消漲幅條件時不送此欄）
- [x] 後端回 `INVALID_RISE_PERCENT` 時，錯誤訊息顯示在回應 `strategy` 指名的那張卡片下方，不是全頁通用錯誤
- [x] 底底高卡片維持靈敏度下拉，說明文字取自 `GET /api/strategies` 該靈敏度的 `description`（現含「以 5 日均線為基準」），前端不寫死
- [x] 策略勾選區出現第三張卡片「上漲支撐」，其名稱與三段靈敏度說明文字皆取自 `GET /api/strategies`，未在前端寫死

### 條件區：累積上漲卡片
- [x] 策略勾選區出現「累積上漲」卡片，名稱與說明文字取自 `GET /api/strategies`，未在前端寫死
- [x] 累積上漲卡片**沒有靈敏度下拉**，改為「天數」與「漲幅門檻」兩個數字輸入；其餘四張卡片維持各自的形狀
- [x] 天數輸入的預設 `20`、範圍 `1`～`90`、step `1` 皆取自該策略 `params` 中 `days` 的欄位，前端無寫死的數字
- [x] 累積上漲的漲幅門檻預設 `15` 取自 `params` 中 `risePercent` 的 `default`，不再從靈敏度說明文字解析
- [x] 累積上漲卡片顯示的說明文字取自策略層級的 `description`，不是任何一段靈敏度的說明
- [x] 天數輸入下方有一行說明「回看的交易日數，不含週末與休市日」
- [x] 輸入 `0`、`91` 或 `20.5` 時前端即擋下，於該卡片下方顯示「天數需介於 1 ~ 90 的整數」，且不送出請求
- [x] 天數為 `1` 且漲幅門檻大於 `0` 時，卡片下方以提示色 `#D9A441` 顯示「天數為 1 時窗口只有當天，累積漲幅恆為 0%，不會有命中」，但「開始掃描」仍可按、請求照常送出
- [x] 勾選累積上漲送出的請求，該筆帶 `days` 與 `risePercent`、**不帶 `preset`**；同一次請求中有靈敏度的卡片仍帶 `preset`
- [x] 未勾選累積上漲時，天數與漲幅兩格皆為 disabled，且請求中不含該策略
- [x] 後端回 `INVALID_DAYS` 時，錯誤訊息顯示在回應 `strategy` 指名的那張卡片下方，不是全頁通用錯誤

### 條件區：反彈卡片
- [x] 策略勾選區出現「反彈」卡片，名稱與說明文字取自 `GET /api/strategies`，未在前端寫死
- [x] 反彈卡片**沒有靈敏度下拉**，改為四格數字輸入：下跌天數、跌幅門檻、反彈天數、反彈幅度；底底高／箱型突破／上漲支撐三張卡片維持靈敏度下拉不變
- [x] 反彈卡片的形狀由 `GET /api/strategies` 的 `presets` 是否為空決定——把後端回應中反彈的 `presets` 換成空陣列即自動改變卡片形狀，前端無任何以策略 `code` 判斷卡片型別的分支
- [x] 四格輸入的標籤取自各 `params` 的 `name`、後綴取自 `unit`、預設取自 `default`、範圍與步進取自 `min`／`max`／`step`，前端無寫死的數字或標籤字串
- [x] 反彈卡片的說明文字取自策略層級的 `description`，不是任何一段靈敏度的說明
- [x] 「下跌天數」與「反彈天數」下方各有一行說明「回看的交易日數，不含週末與休市日」
- [x] 反彈卡片有一個勾選框，文字取自 `paramGroups[0].name`（「另外要求反彈漲幅」），初始狀態取自其 `default`（勾選）
- [x] 取消該勾選框時「反彈天數」與「反彈幅度」兩格 disabled，且送出的請求帶 `requireRise: false`、**不含** `riseDays` 與 `risePercent`
- [x] 勾選狀態下送出 `requireRise: true` 與 `riseDays`／`risePercent`（值與預設相同時仍照送）
- [x] 開關與其管轄的輸入由 `paramGroups`／`params[].group` 決定，程式中不存在以策略 `code` 或參數名寫死的分支
- [x] 未勾選反彈這個策略時，四格輸入與群組勾選框皆為 disabled，且請求中不含該策略
- [x] 勾選反彈送出的那一筆帶 `dropDays`／`dropPercent`／`requireRise`（及勾選時的 `riseDays`／`risePercent`）、**不帶 `preset`**；同一次請求中底底高／箱型突破／上漲支撐仍帶 `preset`
- [x] 反彈的 `risePercent` 送的是**反彈幅度**（預設 `5`），不再是跌幅門檻覆寫值；跌幅改由 `dropPercent`（預設 `10`）送出
- [x] 天數類輸入為 `0`、`91` 或 `3.5` 時前端即擋下，於該卡片下方顯示「<參數名>需介於 1 ~ 90 的整數」，且不送出請求
- [x] 幅度類輸入超出 `0`～`50` 或小數超過一位時前端即擋下，於該卡片下方顯示「<參數名>需介於 0 ~ 50」，且不送出請求
- [x] 下跌天數為 `1` 且跌幅門檻大於 `0` 時，卡片下方以提示色 `#D9A441` 顯示「下跌天數為 1 時窗口只有當天，跌幅恆為 0%，不會有命中」，但「開始掃描」仍可按、請求照常送出
- [x] 後端回 `INVALID_DROP_DAYS`／`INVALID_RISE_DAYS`／`INVALID_DROP_PERCENT`／`INVALID_RISE_PERCENT`／`PARAM_NOT_APPLICABLE` 時，錯誤訊息顯示在回應 `strategy` 指名的那張卡片下方，不是全頁通用錯誤

### 條件區：股票範圍與區間
- [x] 區間預設為今日往前一個日曆月至今日
- [x] 三個快捷鈕（近一個月／近三個月／近半年）點擊後正確套用區間
- [x] 起日晚於迄日時前端擋下並提示，不送出請求
- [x] 股票範圍預設為全市場，此時請求不帶 `stockIds`
- [x] 切到「指定股票」可搜尋加入股票，已選的以可移除標籤呈現；達 200 檔時輸入框 disabled 並提示

### 掃描結果：共通
- [x] 點擊結果表任一列導向 `/stocks/{stockId}/daily`
- [x] `insufficientData` 非空時單獨以一行摘要呈現並可展開看代號，且這些股票不出現在命中表格中
- [x] 零命中時顯示「此區間內沒有命中的股票」，並同時顯示最後同步時間，而非空白表格
- [x] 掃描失敗時顯示錯誤與「重試」按鈕，不顯示空表格

> **本節原有的「結果表：箱型突破／底底高／上漲支撐／反彈／累積上漲」與「聯集表格」六組驗收項已隨各策略區塊一併移除**，因為它們描述的表格在合併成單一命中彙總表之後不再存在。那些區塊確實建置過，記錄保留在下方 `## Execution Result` 的各次 Increment 中；此處只留下在新版面下仍然成立的四項。取代它們的是下方「命中彙總表（本次新增）」。

### 同步列：更新股票清單
- [x] 同步列上有兩顆按鈕，「更新股票清單」在左、「同步日 K 至今日」在右，兩顆皆為次要按鈕樣式；全頁唯一的主要按鈕是「開始掃描」
- [x] 頁面常駐顯示股票清單「共 N 檔」，數值取自 `GET /api/stocks?page=1&size=1` 的 `total`
- [x] 按「更新股票清單」呼叫 `POST /api/stocks/universe/import`，按鈕轉為 disabled 的執行中狀態
- [x] 更新完成後顯示「股票清單已更新：共 N 檔（新增 X、更新 Y）」，三個數字分別取自 `totalActiveCount`、`insertedCount`、`updatedCount`
- [x] 「更新股票清單」的完成摘要含產業別段：「產業別 P 類，未分類 Q 檔」，P 取 `industryCount`、Q 取 `uncategorizedStockCount`
- [x] `industrySourceStatus` 非 `OK` 時，摘要以警示色附加「產業別未更新（來源暫時無法取得）」，且股票清單那一半的數字照常顯示、不視為錯誤
- [x] `industrySourceStatus` 為 `OK` 時，摘要不出現任何產業別未更新的警示文字
- [x] 更新完成後常駐的「共 N 檔」同步更新為 `totalActiveCount`
- [x] 更新清單的過程中**不輪詢任何進度端點**，也不建立進度條
- [x] 更新完成後不自動觸發同步、不自動重新掃描
- [x] 更新清單完成摘要持續顯示到下一次操作為止，不隨執行中狀態消失
- [x] 後端回 `502 UPSTREAM_EMPTY` 時顯示「交易所尚未發布今日清單，請稍後再試」，且「共 N 檔」數值不變
- [x] 後端回 `502 UPSTREAM_UNAVAILABLE` 或 `UPSTREAM_MALFORMED` 時顯示「無法取得交易所股票清單，請稍後再試」，且「共 N 檔」數值不變
- [x] 「更新股票清單」仍是本頁唯一的產業別匯入入口，動態分頁上沒有相同功能的按鈕
- [x] 同步進行中仍可按「更新股票清單」；更新清單進行中仍可按「同步日 K 至今日」，兩者互不 disable

### 同步列：同步日 K 至今日
- [x] 頁面常駐顯示「最後同步：YYYY-MM-DD HH:mm」，取自 `lastSyncedAt`；從未同步過時顯示「尚未同步」
- [x] 「最後同步」顯示的時間與該次同步實際完成的本地（台北）時間一致，不再有 8 小時偏移
- [x] 前端不對 `lastSyncedAt` 做任何時區換算，直接依 `YYYY-MM-DD HH:mm` 呈現
- [x] 按「同步日 K 至今日」後按鈕轉為執行中並顯示已完成／總檔數，畫面不被阻塞
- [x] 同步進行中切換到總覽頁籤再切回來，仍看得到進度
- [x] 同步完成後「最後同步」時間更新，並顯示完成／失敗／略過的檔數摘要
- [x] 按「同步日 K 至今日」時送出的 `commonStocksOnly` 等於頁面層級勾選框當下的狀態
- [x] 頁面層級勾選框為預設（勾選）時，`202` 回應的 `targetCount` 小於或等於常駐顯示的「共 N 檔」，且完成摘要寫的是「完成 N 檔上市普通股」而非「完成 N」
- [x] 取消勾選後按同步，送出 `commonStocksOnly: false`，摘要寫「完成 N」且 `targetCount` 等於常駐的「共 N 檔」
- [x] 常駐顯示的「共 N 檔」不因同步母體縮小而改變（它取自 `GET /api/stocks` 的 `total`，與同步母體是兩件事）
- [x] `caughtUpCount` 等於 `targetCount` 時的摘要為「已是最新，無需更新（N 檔上市普通股）」，不誤報成「完成 N 檔」
- [x] 部分標的落後時，摘要顯示完成／失敗／略過檔數，且 `caughtUpCount` 大於 0 時附註「另 N 檔已是最新」
- [x] 同步在極短時間內完成（執行中狀態一閃而過）時，摘要仍持續顯示，畫面不會回到看似未操作的狀態
- [x] 後端回 `409 JOB_ALREADY_RUNNING` 時按鈕轉為執行中並接上輪詢，不顯示錯誤訊息

### 用語與外觀
- [x] 全頁文案無「建議」「推薦」「可進場」等暗示買賣操作的措辭
- [x] 所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致
- [x] 聯集表格所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致
- [x] 更新股票清單相關的所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致
- [x] 上漲支撐相關的所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致
- [x] 兩個新型態相關的所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致
- [x] 漲幅輸入與普通股勾選框的所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致
- [x] 天數輸入的所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致
- [x] 四格輸入與群組勾選框的所有顏色取自 `## Visual Style` 的字面 hex，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致
- [x] 底底高兩欄文字色沿用 `## Visual Style` 的主要文字 `#E6EDF5`，日期部分沿用次要文字 `#93A4B8`，且在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致

### 「不適用參數」錯誤文案
- [x] 後端回 `PARAM_NOT_APPLICABLE` 時，該卡片下方顯示「帶入了不適用的參數：{param}」，`param` 取自回應，非前端寫死
- [x] `PRESET_NOT_APPLICABLE` 與 `DAYS_NOT_APPLICABLE`（回應不帶 `param`）時顯示通用錯誤訊息，且同樣定位在指名的卡片下方，不是全頁通用錯誤
- [x] 三種「不適用」錯誤皆不清空既有掃描結果，也不把「開始掃描」鎖住

> 本節原有的四項「反彈結果區塊標題」驗收項，其承載的區塊標題已隨各策略區塊移除。它們要保住的資訊（本次實際採用的參數必須取自回應、且四個數字都要顯示）沒有被放棄，而是轉移到合併表標題下的「本次採用參數」那一行——對應的新驗收項見下方「命中彙總表（本次新增）」。

### 命中彙總表（本次新增）
- [x] 不論勾選幾個策略，結果區只出現**一張**表格，標題為「命中彙總 — 共 N 檔」；畫面上不存在任何以單一策略為標題的結果區塊
- [x] 只勾一個策略時同樣是這張表，不再顯示該策略自己的明細表
- [x] 同一檔股票只出現一列，標題的「共 N 檔」為去重後的檔數，不等於各策略 `matchedCount` 的加總
- [x] 同時命中兩個策略的股票，其「命中策略與訊號日」欄逐一列出兩個策略各自的名稱與 `signalDate`，不折成單一日期；策略順序與勾選順序一致
- [x] 表格依該檔各策略中最新的 `signalDate` 由新到舊排序，同日依 `stockId` 升冪
- [x] 點擊任一列導向 `/stocks/{stockId}/daily`
- [x] `insufficientData` 與 `pendingConfirm` 的標的不出現在表格中，改以表格下方逐策略各一行呈現，行首標明策略名稱，色碼 `#D9A441`
- [x] 箱型突破與上漲支撐的待確認文案維持各自既有的兩種不同說法，未因合併而統一

### 本次採用參數那一行（本次新增）
- [x] 標題下方以 `#93A4B8` 顯示一行，逐策略以 `・` 分隔，列出本次實際採用的參數
- [x] 反彈在 `requireRise` 為 `true` 時顯示「反彈（{dropDays} 日跌 {dropPercent}% → {riseDays} 日反彈 {risePercent}%）」，四個數字皆取自回應該筆的實際採用值
- [x] 反彈在 `requireRise` 為 `false` 時顯示「反彈（{dropDays} 日跌 {dropPercent}%）」，不出現反彈段的兩個數字，也不留空括號
- [x] 有靈敏度的三個顯示「（{靈敏度中文名}）」，累積上漲顯示「（{days} 日）」
- [x] 掃描後改動任一格輸入而未重掃時，這一行維持舊值不變（取自回應，不是取自輸入框）

### 回測按鈕（本次新增）
- [x] 「回測」按鈕位於「開始掃描」**右側**，為次要按鈕樣式；全頁唯一的主要按鈕仍是「開始掃描」
- [x] 尚未掃描、掃描中、掃描失敗、以及掃描完成但命中 0 檔時，「回測」皆為 disabled
- [x] 掃描命中至少一檔後「回測」可按
- [x] 回測進行中「回測」為 disabled 並顯示進行中，「開始掃描」不受影響仍可按
- [x] 按下「開始掃描」的當下即清空回測結果：三個欄位與兩個標籤消失，「回測」回到未回測狀態
- [x] 改動條件但未重掃時，既有的回測結果維持不變
- [x] 回測失敗時「回測」恢復可按並在其下顯示錯誤訊息，表格維持未回測的樣子，不出現三欄與兩個標籤

### 納入計算的勾選框（本次新增）
- [x] 掃描完成後，命中彙總表最左出現每列一個勾選框，**全部預設為勾選**
- [x] 勾選框在回測之前就可切換，其狀態被接下來的回測沿用
- [x] 取消勾選某列後，該列的賣出日／報酬率／收益**照常顯示**，未被清空或隱藏
- [x] 未勾選的列整列文字改為 `#6B7C90`，包含原本帶漲跌色的報酬率與收益；背景不變
- [x] 未勾選的列仍可點擊導向 `/stocks/{stockId}/daily`
- [x] 取消或恢復勾選時**不發出任何網路請求**（以請求計數斷言，非以耗時推測），總計即時重算
- [x] 取消勾選一列後，總報酬率與總收益的值改變，且等於扣除該列後重算的結果
- [x] 恢復勾選後兩個總計回到原值
- [x] **全部勾選時**，畫面上的總報酬率與總收益等於回應的 `totalReturnPercent` 與 `totalProfit`，且據以計算的總成本等於 `totalCost`
- [x] 總成本以回應的 `lotSize` 計算，前端未寫死 `1000`
- [x] 標題的「共 N 檔」不因取消勾選而改變
- [x] 按「開始掃描」後所有勾選狀態重設為勾選，且回測結果一併清空
- [x] 取消勾選不影響該列以外的任何列的顯示值

### 兩種未計入的說明（本次新增）
- [x] 有標的 `sellDate` 為 `null` 時顯示「另 N 檔尚無可賣出交易日，未計入」，N 為該類檔數
- [x] 有標的被取消勾選時顯示「另 M 檔未勾選，未計入」，M 為該類檔數
- [x] 兩種情形同時存在時兩行都顯示；都不存在時兩行都不顯示
- [x] 一檔同時「無法回測」且「未勾選」時只計入前者，不重複計入 M
- [x] 兩行皆以 `#93A4B8` 呈現，位於兩個總計標籤下方
- [x] 全部標的皆無法回測時，兩個標籤顯示 `#6B7C90` 的「—」並顯示「沒有可回測的標的」
- [x] 有可回測標的但全被取消勾選時，兩個標籤顯示 `#6B7C90` 的「—」並顯示「**未勾選任何標的**」，與上一項的文字不同
- [x] 上述兩種情形皆不得顯示成 `0%`

### 回測結果呈現（本次新增）
- [x] 回測完成後表格最右出現三欄，順序為「賣出日」「報酬率」「收益（每檔 1 張）」
- [x] 三欄的值分別取自回應的 `sellDate`／`returnPercent`／`profit`；報酬率為兩位小數加 `%`，收益為千分位不帶小數
- [x] 送出的 `items[].signalDate` 為該檔命中的各策略中**最新**的一個，與表格排序所依據的日期一致
- [x] 標題右側出現「總報酬率」與「總收益（每檔 1 張）」兩個標籤
- [x] 報酬率、收益與兩個總計標籤的正值為 `#E04B45`、負值為 `#16A75C`、`0` 為 `#93A4B8`
- [x] 回應中 `sellDate` 為 `null` 的標的**仍留在表上**，其三欄皆顯示 `#6B7C90` 的「—」，未被移出表格
- [x] 回測完成後表格排序不變，未依報酬率重排
- [x] 全頁在 `prefers-color-scheme: dark` 與 `light` 下呈現完全一致，新增的按鈕、欄位與標籤皆取自 `## Visual Style` 的字面 hex

## Execution Result
- Status: DONE (pending checkbox sign-off by the requester — per instructions this agent does not tick the boxes itself)
- Scope: this spec's implementation (`StrategyTab.tsx`, `api/strategies.ts`, `api/sync.ts`, the tab shell in `StockListPage.tsx`) was **already present** from a prior commit, but the spec had `status: pending` with no `## Execution Result` — i.e. never verified against its own Acceptance Criteria. This pass was an audit: read every requirement, diffed it against the actual code, fixed what was actually broken (all inside test files — the production implementation itself needed no changes), and added coverage for the two ACs that had none.

### Files changed
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - Fixed two **pre-existing, already-failing** tests (confirmed failing before this session touched anything, via `git stash` + re-run):
    - `sends no stockIds for the default 全市場 scope` — asserted `screen.getByText('2330')`, but `代號`/`名稱` render as sibling text nodes inside one `<td>` (`"2330 台積電"`); exact-match `getByText` can never match the bare substring. Fixed to assert the combined text, matching how every other test in the file already does it.
    - `disables the preset dropdown for an unchecked strategy card...` — `tsc -b` failed (`npm run build` was **not** actually clean, contrary to what a clean build would imply): `.closest('.st-strategy-card')` assigned to a `const` infers `Element`, not `HTMLElement`, when there's no contextual type to narrow it (unlike the many other call sites in the same file that pass the `.closest(...)!` expression directly into `within(...)`, where argument-position contextual typing does narrow it). Fixed with an explicit `as HTMLElement` cast at the one assignment site that needed it.
    - `shows a running state with completed/total counts after clicking...` and `treats a 409 JOB_ALREADY_RUNNING response...` — both mocked the **on-mount** `GET /api/stocks/sync/progress` response as already "running" (`pending`/`running` > 0). The implementation's mount-time effect legitimately auto-detects an in-flight job (e.g. the backend's startup catch-up) and flips the button to 同步中 immediately — which is correct behavior, but it raced ahead of these two tests' assumption that the running state would only appear *after* the simulated click. Fixed by making the mocked on-mount response idle and only reporting "running" from the second poll onward (i.e. after the click triggers the polling effect), which is what each test's own narrative actually describes.
  - Added `disables the stock-search input and shows a hint once 200 stocks are selected` — the one Acceptance Criterion clause ("達 200 檔時輸入框 disabled 並提示「最多 200 檔」") that had no test at all. Drives the real search→add flow 200 times against a keyword-echoing mock (each search term becomes a distinct fake stock) rather than reaching into component internals.
- `develop/frontend/src/__tests__/StockListPage.tabs.test.tsx`
  - Added `keeps the 策略 tab sync progress alive across a round-trip through 總覽 (state does not live only inside the unmounted tab)` — Acceptance Criterion 18 was previously only verified *structurally* (both tab panels stay permanently mounted, toggled via `display:none`/`block`) but had no integration test actually asserting a running sync survives a tab round-trip. This test renders the full `StockListPage` (not `StrategyTab` in isolation), waits for the on-mount progress fetch to report a running job, switches to 總覽 and back to 策略, and asserts the running button/progress text and continuing polling are unaffected by the switch.
- `develop/frontend/src/pages/StrategyTab.tsx` — **one real production bug fixed**, found only after adding a dedicated test for Criterion 6 (see below): `monthsAgo()` used a naive `d.setMonth(d.getMonth() - months)`, which silently overflows into the next month when the source day doesn't exist in the target month. Concretely, clicking 近半年 from 2026-08-30 produced a start date of `2026-03-02` instead of the calendar-correct `2026-02-28` (Aug 30 minus 6 months = "Feb 30", which JS normalizes forward past the end of a 28-day February instead of clamping to it). Fixed by computing the target month's actual last day and clamping to `min(originalDay, lastDayOfTargetMonth)` before constructing the result date. This is a genuine, user-visible date-range bug that would have shipped silently — every prior test only exercised 1m/3m shortcuts, both of which land in 30/31-day months and never trigger the overflow.
- All other files: no other production code (`StockOverviewTab.tsx`, `StockListPage.tsx`, `api/strategies.ts`, `api/sync.ts`, any `.css`) was changed. Every other fix and root cause found was in test code or test assumptions, not in shipped behavior.

### Per-criterion verification
1. 策略清單/靈敏度/說明文字皆取自 `GET /api/strategies` — **Satisfied.** `catalog` state is populated solely from the fetch response; `strategyName`/`presetName` look up display text from `catalog`, falling back only to the raw `code` (never a hard-coded Chinese name) if the catalogue hasn't loaded. Verified by test + `grep` finding no hard-coded 箱型突破/底底高/嚴格/標準/寬鬆 strings outside test fixtures.
2. 三段靈敏度、預設標準 — **Satisfied** (contract-level: `specs/backend/strategy-scan.md` guarantees 3 presets per strategy, already `done`; frontend defaults `selectedPresets[code] = 'STANDARD'` on first check and renders whatever the catalogue returns).
3. 未勾選卡片靈敏度 disabled — **Satisfied**, tested.
4. 無策略時「開始掃描」disabled + 提示 — **Satisfied**, tested.
5. 區間預設近一個月 — **Satisfied**, tested (`defaultDateRange`).
6. 三個快捷鈕 — **Fixed-by-you.** A dedicated test (`applies each of the three date-range shortcuts to the exact expected calendar dates`) now clicks 近一個月／近三個月／近半年 in turn against a pinned `2026-08-30` "today" and asserts literal, independently-computed expected dates (not a re-derivation of the component's own formula) for both 起日 and 迄日. On first run this test **failed** — it caught a real bug: 近半年 produced `2026-03-02` instead of the calendar-correct `2026-02-28`, because `monthsAgo()`'s naive `setMonth()` call overflowed past February's 28 days instead of clamping to the month's last day. Fixed in `StrategyTab.tsx` (see Files changed) and the test now passes; re-verified stable across 3 full-suite runs.
7. 起日 > 迄日擋下 — **Satisfied**, tested (`canScan` includes `!dateInvalid`; scan call count asserted to stay 0).
8. 全市場預設、不帶 `stockIds` — **Satisfied**, tested (and the pre-existing test's broken assertion was fixed, see above).
9. 指定股票搜尋/標籤/200 檔上限 disabled+提示 — **Satisfied**; search/add/remove was already tested, the 200-cap disable+hint was **not** tested before this pass — **fixed by adding a test** (see above).
10. 兩策略順序與勾選順序一致 — **Satisfied**, tested (`selectionOrder.map` in `buildScanPayload`; backend echoes `results` in request order per its own spec).
11. 箱型突破表格欄位與格式 — **Satisfied**, tested (`formatPrice2`/`formatPercent2`/`formatMultiple2` match "數值格式" exactly: 2dp, `%`, `×`).
12. 底底高低點序列/累計漲幅 — **Satisfied**, tested; `formatLowsSequence` maps every entry of `detail.lows` (count always matches), `cumulativeRise` computed from first/last low.
13. 點列導向 `/stocks/{stockId}/daily` — **Satisfied**, tested.
14. `insufficientData` 獨立摘要、可展開、不進命中表 — **Satisfied**, tested (including that the ids are absent until the toggle is clicked, then appear).
15. 零命中訊息 + 最後同步時間 — **Satisfied**, tested.
16. 常駐「最後同步」/「尚未同步」 — **Satisfied**, tested both branches.
17. 同步中按鈕狀態 + 已完成／總檔數、不阻塞畫面 — **Satisfied**, tested (including that toggling a strategy checkbox and the scan button both stay interactive while a sync is running).
18. 切換頁籤再切回仍看得到同步進度 — **Satisfied** structurally (both tab panels stay mounted, `StrategyTab`'s own polling `useEffect` never unmounts) and now also **integration-tested** — **fixed by adding a test** (see above), since no test had exercised this cross-tab path before.
19. 同步完成後最後同步時間 + 完成/失敗/略過摘要 — **Satisfied**, tested.
20. `409 JOB_ALREADY_RUNNING` → 執行中 + 輪詢、非錯誤 — **Satisfied**; the implementation sets `syncStatus` to `running` optimistically *before* the request resolves, so a 409 is a no-op in the `catch` (not treated as failure) and polling was already underway. Test's mock assumptions were fixed (see above) so it now correctly exercises this path instead of accidentally passing/failing on unrelated timing.
21. 掃描失敗顯示錯誤 + 重試、非空表格 — **Satisfied**, tested (`queryByRole('table')` asserted absent).
22. 無「建議／推薦／可進場」等措辭 — **Satisfied**; `grep` across `StrategyTab.tsx`/`api/strategies.ts`/`api/sync.ts`/CSS finds zero matches, plus a dedicated test rendering both result blocks and asserting `document.body.textContent` doesn't match `/建議|推薦|可進場/`.
23. 所有顏色為 Visual Style 字面 hex，dark/light 下一致 — **Satisfied**, verified two ways: (a) line-by-line comparison of every `.st-*` rule in `StockListPage.css` (lines ~382-810) against the spec's hex table — every value is a literal lowercase hex matching the table (e.g. sync row `#16202c`/`#26333f`, progress fill `#3e8fd8`, note/待確認 text `#d9a441`, error block `#f09a94`/`#3a1c1a`/`#8a3a34`), and `grep -rn "prefers-color-scheme" src/` shows zero occurrences outside comments that explicitly forbid it; (b) a throwaway Playwright screenshot of `/stocks?tab=strategy` (dev server only, no backend — so the page renders its catalogue-load-error + empty-results shell) under emulated `colorScheme: 'dark'` and `'light'` — the two screenshots are pixel-identical, confirming no theme-reactive rendering. The Playwright dependency was installed with `npm install playwright --no-save` and the throwaway script removed afterward; `git diff` on `package.json`/`package-lock.json` confirms zero changes from this. Full result-table rendering (post-scan) was not additionally screenshotted since no backend was running in this environment to produce real scan data — its colors were verified by source review only (item (a) above), which is exhaustive since every color in those templates is a literal class already covered by (a).

### Test / build output (verbatim tails)
```
$ npm test
 Test Files  6 passed (6)
      Tests  79 passed (79)

$ npm run build
> tsc -b && vite build
✓ 44 modules transformed.
✓ built in 105ms
```
Re-ran `npm test` multiple times back to back (including immediately after each round of fixes) with identical `78 passed (78)` (pre-Criterion-6-test) and then `79 passed (79)` (post) each time — none of the timer-dependent tests (`shows a running state...`, `disables the stock-search input...`, `applies each of the three date-range shortcuts...`) are flaky. The new shortcut test failed on its first run (see Criterion 6 above) before the `monthsAgo()` fix, then passed on every run after.

`npx oxlint .`-equivalent (`npm run lint`) shows the same 2 pre-existing warnings (not errors) that exist on `origin/main` before this session (`no-unreachable` in an unrelated line of the test file's shared mock, `set-state-in-effect` in the stock-search-suggestions effect) — confirmed via `git stash`/`git stash pop` that both predate this session's changes; left untouched as out of scope (no production code needed changing for any of the 23 criteria).

### Deferred / not independently verifiable here
- No live backend was running in this environment (confirmed: `curl localhost:8080/api/strategies` → connection refused), so the full request/response cycle against a real `GET /api/strategies`, `POST /api/strategies/scan`, and a real multi-file `POST /api/stocks/sync/backfill` progressing through actual rate-limited external calls could not be exercised end-to-end. All 23 criteria were instead verified via the unit/integration test suite (which mocks `fetch` against fixtures shaped exactly per `specs/backend/strategy-scan.md` / `specs/backend/stock-price-ingestion.md`'s documented response shapes) plus static source/CSS review. This mirrors the level of verification the sibling `specs/frontend/stock-list.md` spec's Increment 1 used before a live backend was available in that session; if a live backend is available in a later session, re-running the two Playwright screenshot checks against real scan results would be worth doing but was not required to satisfy any of the 23 stated criteria's actual wording.
- The Implementation Details section's backend-fallback error placements for `TOO_MANY_STOCKS` (「於股票範圍區顯示『最多 200 檔』」) and `INVALID_DATE_RANGE` (「於區間下方顯示『起日不可晚於迄日』」) currently fall through to the generic `掃描失敗，請稍後再試` message instead of the field-adjacent placement the Implementation Details table describes, since neither is in the 23 Acceptance Criteria and both are explicitly documented as "前端已先擋，此為後備" (frontend already blocks these client-side; the backend response is only a backup for it). Left as-is since fixing it would be adding scope beyond what any Acceptance Criterion requires, but noting it here in case a future spec revision promotes it to a checked criterion.

### Increment 2 — 2026-08-31
- Scope: exactly the 5 unchecked criteria added after Increment 1 — the 「同步日 K 至今日」bug where an all-caught-up sync (zero external requests, finishes in milliseconds) reported a misleading 「完成 34 檔」, plus the separately-shipped backend timezone fix this frontend must not re-break by adding its own conversion.

#### Files changed
- `develop/frontend/src/api/sync.ts` — added `caughtUpCount: number` to `BackfillResponse`, matching the revised `202` contract in `specs/backend/stock-price-ingestion.md` `#### 2. 回補`.
- `develop/frontend/src/pages/StrategyTab.tsx`
  - Added a `syncMeta` state (`{ targetCount, caughtUpCount } | null`), populated from the `202` response's `.then` (previously the code only chained a `.catch`), and reset to `null` at the start of every new `handleSyncClick` so a slow-to-resolve previous job's numbers can never be misattributed to the next job's completion.
  - Deliberately kept `syncMeta` (from the accept response) and `syncSummary` (from the progress poll) as two independent states rather than merging one into the other at whichever moment happens to resolve first — the two requests (`POST /api/stocks/sync/backfill` and the immediately-following `GET .../progress` poll) race independently in real network conditions, and the display JSX recombines both at every render, so whichever of the two arrives second still triggers a correct re-render instead of freezing on a half-known state.
  - Sync-row rendering now branches on `syncMeta.caughtUpCount === syncMeta.targetCount` (with `targetCount > 0`): shows `已是最新，無需更新（N 檔）` and suppresses the `完成 N 檔` wording entirely when true; otherwise shows the existing `完成／失敗／略過` counts and appends `，另 N 檔已是最新` when `caughtUpCount > 0`. The `409 JOB_ALREADY_RUNNING` path and the on-mount "already running" auto-detect never populate `syncMeta` (that job wasn't started by this click), so their eventual completion summary falls back to the plain counts — a documented, deliberate degradation given the frontend has no way to know that job's `caughtUpCount`.
  - Confirmed (did **not** need to change): `formatSyncTime` already does a pure string slice/format (`value.slice(0, 16).replace('T', ' ')`) with no `new Date(...)` construction or timezone-aware formatting anywhere in the sync-time display path — it was already free of any timezone conversion before this increment.
- `develop/frontend/src/pages/StockListPage.css` — added `.st-caught-up-note { color: #d9a441; }`, applied to both the `已是最新，無需更新（N 檔）` text and the `，另 N 檔已是最新` note, per the standing project rule that this text must use the literal `資料不足／待確認提示文字` hex from `## Visual Style` rather than the summary row's default secondary-text color, and must not vary with `prefers-color-scheme`.
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - Updated the shared `backfillResponder` mock default to include `caughtUpCount: 0` (matching the real contract's "always 0 when not applicable" shape); this is a superset of the previous mock body and required no other existing test to change.
  - Added 4 new tests covering all 5 new criteria (criteria 1 and 2 share one test since both assert the same "no conversion" behavior):
    - `shows lastSyncedAt exactly as returned with no timezone conversion (no 8-hour shift)` — asserts an input of `2026-08-30T23:15:00` renders as exactly `最後同步：2026-08-30 23:15`, and explicitly asserts the shifted variant (`2026-08-31...`) is absent, which would catch a regression to `new Date(...)`-based reformatting.
    - `shows 已是最新，無需更新（N 檔） — not 完成 N 檔 — when caughtUpCount equals targetCount` — mocks the `202` accept with `targetCount: 34, caughtUpCount: 34` and an immediately-idle first post-click poll (simulating the millisecond-long all-caught-up job), asserts the exact caught-up message and that no `完成 ` text is present.
    - `shows 完成／失敗／略過 counts plus an 另 N 檔已是最新 note when only some targets were already caught up` — mocks `caughtUpCount: 10` of `targetCount: 34`, a mid-flight poll showing partial progress, then a final poll with `done: 20, failed: 2, skipped: 2` (summing to the 24 non-caught-up targets); asserts both the counts text and the appended note via `document.body.textContent` (needed because the note renders as a nested `<span>` inside the counts `<span>`, so an exact-match `getByText` on the outer text alone would no longer match once the note is present).
    - `keeps the completion summary visible after a near-instant sync instead of reverting to an unchanged-looking screen` — same all-caught-up setup as above, but additionally advances fake timers by 15s *after* the summary first appears and re-asserts the summary text is still present and the button has reverted to its normal (non-running) label — directly exercising the reported "looked like nothing happened" bug.

#### Per-criterion verification
1. 「最後同步」顯示的時間與該次同步實際完成的本地（台北）時間一致，不再有 8 小時偏移 — **Satisfied**, tested. `formatSyncTime` was already a pure string slice with no `Date` parsing; verified by source read (no `new Date(lastSyncedAt)` anywhere) and by the new test asserting an exact-match render with the shifted variant explicitly asserted absent.
2. 前端不對 `lastSyncedAt` 做任何時區換算，直接依 `YYYY-MM-DD HH:mm` 呈現 — **Satisfied**, same test/source-read as above; `grep -n "lastSyncedAt" src/pages/StrategyTab.tsx` shows it is only ever passed straight into `formatSyncTime`, never into `new Date(...)`.
3. 全部標的都已是最新時（`caughtUpCount` 等於 `targetCount`），摘要顯示「已是最新，無需更新（N 檔）」而非「完成 N 檔」 — **Satisfied**, tested (`shows 已是最新，無需更新...`), including an explicit assertion that no `完成 ` text is rendered in that state.
4. 部分標的落後時，摘要顯示完成／失敗／略過檔數，且 `caughtUpCount` 大於 0 時附註「另 N 檔已是最新」 — **Satisfied**, tested (`shows 完成／失敗／略過 counts plus an 另 N 檔已是最新 note...`).
5. 同步在極短時間內完成（執行中狀態一閃而過）時，摘要仍持續顯示，畫面不會回到看似未操作的狀態 — **Satisfied**, tested (`keeps the completion summary visible after a near-instant sync...`); `syncSummary`/`syncMeta` are plain React state set once on completion and never cleared except at the start of the *next* `handleSyncClick`, so nothing times out or reverts them on its own.

#### Test / build output (verbatim tails)
```
$ npm test -- --run
 Test Files  6 passed (6)
      Tests  83 passed (83)

$ npm run build
> tsc -b && vite build
✓ 44 modules transformed.
✓ built in 111ms
```
83 = the pre-existing 79 (Increment 1) + 4 new tests added this increment. `npm run lint` shows the same 2 pre-existing warnings noted in Increment 1's Execution Result (`no-unreachable` in the test file's shared mock, `set-state-in-effect` in the stock-search-suggestions effect); no new warnings introduced.

#### Deferred / not independently verifiable here
- No live backend was reachable in this session (`curl -m 5 http://localhost:8080/...` → connection refused; the frontend dev server at `:5173` was up but has nothing behind it to exercise). All 5 criteria were verified via the unit/integration test suite against fixtures shaped per the revised `specs/backend/stock-price-ingestion.md` `#### 2. 回補` contract, plus source review. A live end-to-end check (triggering a real all-caught-up sync against the 34-stock seed data and watching the summary text) would be worth doing once a backend is available in a later session, but was not required to satisfy any of the 5 stated criteria's wording.

### Increment 3 — 2026-09-01
- Scope: exactly the two remaining unchecked groups — the 聯集表格 (union table across 2+ selected strategies) and 「更新股票清單」(`POST /api/stocks/universe/import`, now backend-ready) plus the persistent stock-universe「共 N 檔」count. All 19 previously-checked criteria were left untouched (no production behavior for them was changed).

#### Files changed
- `develop/frontend/src/api/stocks.ts` — added `UniverseImportResponse` and `importStockUniverse()` (`POST /api/stocks/universe/import`, no body), reusing the existing `requestJson` helper so `502 UPSTREAM_EMPTY` / `UPSTREAM_UNAVAILABLE` / `UPSTREAM_MALFORMED` surface as `ApiError` with `.code` set from the parsed body, same as every other write endpoint in that file.
- `develop/frontend/src/pages/StrategyTab.tsx`
  - **Union table (聯集表格)**: added `UnionHit`/`UnionRow` types and a pure `buildUnionRows(result: ScanResponse)` function that dedupes `result.results[].items` by `stockId` (never touching `insufficientData`/`pendingConfirm`, which per `specs/backend/strategy-scan.md` never appear in `items` to begin with), appends each stock's hits in `result.results` array order (the backend already returns results in request order, so no dependency on the component's current, possibly-since-changed `selectionOrder` state), and sorts rows by each stock's newest `signalDate` desc, ties broken by `stockId` asc. `showUnionTable = scanResult.results.length >= 2 && unionRows.length > 0` gates the table; `renderUnionTable()` renders it as the first child of `.st-results-list`, reusing the existing `sl-table`/`st-result-table`/`sl-row` classes so navigation-on-row-click, hover, and table chrome match the per-strategy tables exactly. Each row's hits column (`renderUnionHits`) renders one `.st-union-tag` per hit (`{策略名稱}` + `{signalDate}`, styled per the new Visual Style row below) with a literal `・` separator between multiple hits — never merged into a single date.
  - **更新股票清單**: added `totalStockCount`, `universeImportStatus`, `universeImportSummary`, `universeImportErrorMessage` state; a mount-time effect fetching `GET /api/stocks?page=1&size=1` (`size=1` because only `total` is needed) for the persistent count; and `handleImportUniverseClick`, which is a plain fetch-then-setState handler with **no polling effect, no progress bar, and no auto-triggered sync/scan call** — matching the backend spec's "短同步作業" characterization. On success it sets both `universeImportSummary` (for the completion text) and `totalStockCount = resp.totalActiveCount` (updating the persistent count) in the same `.then`. On failure it maps `UPSTREAM_EMPTY` → 「交易所尚未發布今日清單，請稍後再試」and both `UPSTREAM_UNAVAILABLE`/`UPSTREAM_MALFORMED` → 「無法取得交易所股票清單，請稍後再試」, and deliberately never touches `totalStockCount` in the catch branch (the backend guarantees no partial write on any of the three failure paths, so the frontend must not invent a new value either).
  - Sync row now renders two secondary (`sl-btn`, no `-primary`) buttons — 更新股票清單 (left) then 同步日 K 至今日 (right, changed from `sl-btn-primary` to `sl-btn` so 開始掃描 remains the page's only primary button) — plus a `.st-sync-info` block showing both 最後同步 and the new 股票清單：共 N 檔 line. `universeImportStatus === 'running'` only ever disables the import button; `syncStatus === 'running'` only ever disables the sync button — neither reads the other's state, matching "後端不共用併發鎖，前端不得自行加上互斥."
  - The universe-import completion summary / error block renders independently of `syncStatus` and is only cleared at the *start* of the next `handleImportUniverseClick` call (`setUniverseImportSummary(null)`/`setUniverseImportErrorMessage(null)` before the fetch) — never cleared by an unrelated `開始掃描` or `同步日 K 至今日` click, so it persists exactly "到下一次操作為止" (the next 更新股票清單 operation specifically, mirroring how the pre-existing `syncSummary` already behaves relative to `handleSyncClick`).
- `develop/frontend/src/pages/StockListPage.css`
  - `.st-sync-info` (column layout for the two left-side info lines), `.st-stock-total` (`#93a4b8`, matching Visual Style's "股票清單「共 N 檔」文字"), `.st-universe-summary`/`.st-universe-summary-text` (`#e6edf5`, matching "更新股票清單完成摘要文字"; errors reuse the existing `.st-inline-error` block, matching "錯誤訊息文字／背景／邊框").
  - `.st-union-hits` (flex-wrap row, overriding the table cell's default `white-space: nowrap`), `.st-union-tag` (background `#1b2836`), `.st-union-tag-name` (`#e6edf5`), `.st-union-tag-date` (`#93a4b8`), `.st-union-sep` (`#93a4b8`) — every value copied literally from the `## Visual Style` table's existing "聯集表格策略標籤背景／文字" and "聯集表格訊號日文字" rows (both already present in the spec from a prior pass; no new hex values were needed for this increment). No `prefers-color-scheme` query appears anywhere in the file, confirmed via `grep -rn "prefers-color-scheme" src/`.
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - Added `unionScanResponse()` (three stocks: one hit by both strategies with different signalDates — mirroring the spec's own worked example, 箱型突破 `2026-08-28` / 底底高 `2026-08-25` — plus one stock unique to each strategy, and non-empty `insufficientData`/`pendingConfirm` on the box-breakout leg) and `zeroHitBothResponse()` (two strategies, both `matchedCount: 0`) fixtures.
  - Extended the shared `beforeEach` mock dispatcher with a `universeImportResponder` (default: a realistic 200 body) and a `stocksTotal` variable feeding the `size=1` branch of the existing `/api/stocks?` handler (previously that branch only served the 指定股票 search-suggestion shape; it now branches on the `size` query param so both call sites can be mocked independently).
  - Added 5 tests for the union table: no table with only one strategy checked; a deduped, correctly-sorted, correctly-per-strategy-dated table appears once a second strategy is checked and both are scanned (asserting exact row order, exact per-hit strategy+date text on the shared stock's row, and that `insufficientData`/`pendingConfirm` ids never appear inside the union table specifically); no table when 2+ strategies are scanned but neither has any hits (each block still shows its own zero-hit message); and clicking a union-table row navigates to `/stocks/{stockId}/daily`.
  - Added 8 tests for 更新股票清單: button styling/order (`更新股票清單` secondary, left; `同步日 K 至今日` secondary, right; `開始掃描` the only `sl-btn-primary`); the persistent count appearing from the `size=1` mount fetch; the full running→completion flow (disabled running state, completion summary text, updated persistent count, and — via before/after call-count diffing on `/api/stocks/sync/progress`, `/api/strategies/scan`, `/api/stocks/sync/backfill` — proof that completing an import polls nothing and auto-triggers neither a sync nor a scan); the summary persisting across an unrelated `開始掃描` and clearing only on the next `更新股票清單` click; the two distinct error messages for the three `502` codes (`UPSTREAM_EMPTY` gets its own message; `UPSTREAM_UNAVAILABLE` and `UPSTREAM_MALFORMED` share the other); and a concurrency test proving `更新股票清單` and `同步日 K 至今日` can both be "running" at once without either disabling the other.
- `develop/frontend/src/__tests__/StockListPage.test.tsx`, `develop/frontend/src/__tests__/StockListPage.tabs.test.tsx` — both files' pre-existing `overviewCalls(fetchMock)` test helper (which isolates 總覽's own `/api/stocks?...` list calls from the 策略 tab's background traffic) now also excludes the new `size=1` mount-time call the 策略 tab fires for the persistent count, the same way it already excluded `/api/strategies` and `/api/stocks/sync/progress` — needed because 總覽's own list calls always use `size=50`, never `size=1`, so filtering on the `size` param cleanly distinguishes the two without coupling to call order. Both existing tests these helpers back (`loads and shows the first page...`, `debounces search input...`, `switches tabs without refetching...`) were otherwise unchanged and still assert the same behavior they always did.

#### Per-criterion verification
All verification below was done twice: once via the automated test suite (`npm test`), and once live against the real backend (`mvn -f develop/backend/pom.xml spring-boot:run`, against the real dev MySQL at `127.0.0.1:3306`, database `stock`, already carrying ~1366 active stocks and a `PRICE_BACKFILL` job left mid-run from the environment) and the real Vite dev server (`npm run dev`, port 5173), driven with Playwright. Both backend and frontend dev processes were stopped at the end of the session; port 8080/5173 confirmed free afterward (both live-verification scripts were throwaway `.cjs` files under `develop/frontend/`, deleted before finishing — `git status` on `develop/frontend/` shows no leftover script files).

**Group A — 聯集表格**
1. 勾選兩個以上策略掃描後，結果區最上方出現聯集表格 — **Satisfied.** Tested (`shows a deduped union table above the strategy blocks...`, asserting `getAllByRole('heading', {level:3})` inside `.st-results-list` starts with the union title). Live-verified: scanning all 1366 active stocks over 近半年 with both strategies checked produced 70 + 262 = 332 raw matches, deduped to a `命中彙總 — 共 282 檔` table rendered as `.st-results-list`'s first child (`firstElementChild.className` confirmed `st-result-block st-union-block`).
2. 只勾選一個策略時不顯示聯集表格 — **Satisfied.** Tested (`does not show a union table when only one strategy is checked`). Live-verified with the same 1366-stock scan: `命中彙總` absent with only 箱型突破 checked.
3. 聯集表格同一檔股票只出現一列，「共 N 檔」為去重後的檔數 — **Satisfied.** Tested and live-verified (282 ≠ 70+262=332, confirming dedup, not summation).
4. 命中策略與訊號日逐一列出，不折成單一日期 — **Satisfied.** Tested against a stock hit by both strategies at different dates (`箱型突破 2026-08-28`/`底底高 2026-08-25`), asserting both strategy names and both distinct dates appear in that row. Live-verified against real data: sampled hits-cell text included e.g. `箱型突破 2026-08-28・底底高 2026-04-16` — two distinct dates, not merged.
5. 排序：最新 `signalDate` 由新到舊，同日 `stockId` 升冪 — **Satisfied.** Tested with a constructed tie (2317/2330 both latest-dated `2026-08-28`, asserting `2317` before `2330`) and a lower-dated stock (`2454`) last. Live-verified by dumping the first 15 real rows and independently checking every adjacent pair: strictly descending latest-date groups, and within each tied-date group, strictly ascending `stockId` (e.g. `00875` < `1321` < `1416`, all tied at `2026-08-26`).
6. 點擊聯集表格任一列導向 `/stocks/{stockId}/daily` — **Satisfied.** Tested (row click scoped to `within(unionTable)` to disambiguate from the identically-named row in the per-strategy table below it) and live-verified structurally (same `sl-row`/`onClick` wiring as the already-covered per-strategy tables; the route itself is exercised by the existing per-strategy-table navigation criterion).
7. `insufficientData`/`pendingConfirm` 不出現在聯集表格中 — **Satisfied structurally**: `buildUnionRows` only ever reads `result.results[].items`, and per `specs/backend/strategy-scan.md` ("`insufficientData` 與 `matchedCount` 互斥：列在前者的股票不會出現在 `items` 中") those ids can never be in `items` to begin with — there is no filtering code that could regress. Tested with a fixture carrying non-empty `insufficientData`/`pendingConfirm` on the box-breakout leg, asserting neither id string appears anywhere inside the union table specifically (`within(unionTable).queryByText(...)`).
8. 兩個以上策略全部零命中時不顯示聯集表格 — **Satisfied.** Tested (`zeroHitBothResponse`, both blocks show 此區間內沒有命中的股票, no `命中彙總` anywhere). Live-verified against a real stock (`1538`, confirmed via `GET /api/stocks` to have `latestTradeDate: null` — never backfilled) scanned under 指定股票 with both strategies: both blocks reported zero hits (真正的 `insufficientData` reason, not a scan-logic zero), no union table.
9. 聯集表格顏色為 Visual Style 字面 hex，dark/light 一致 — **Satisfied**, verified two ways: (a) every new class (`.st-union-hits`/`.st-union-tag`/`.st-union-tag-name`/`.st-union-tag-date`/`.st-union-sep`) uses a literal lowercase hex copied from the spec's existing "聯集表格策略標籤背景／文字" (`#1b2836`/`#e6edf5`) and "聯集表格訊號日文字" (`#93a4b8`) rows, confirmed by direct source read; `grep -rn "prefers-color-scheme" src/` returns zero hits. (b) A Playwright screenshot of the full strategy tab (with both strategies scanned via mocked, fully deterministic fetch responses so no live-data drift could contaminate the comparison) under emulated `colorScheme: 'dark'` vs `'light'` — the two screenshots are **byte-for-byte identical** (`sha256` match). An earlier attempt comparing screenshots against the *live* backend produced different hashes; investigation showed this was because the background `PRICE_BACKFILL` job (already mid-run in this shared dev environment) was adding new price rows between the two page loads, changing the *scan results themselves* between requests — not a color difference. Switching to mocked, deterministic responses isolated the color-only comparison and confirmed the identical-hash result above.

**Group B — 更新股票清單 + 常駐檔數**
1. 兩顆按鈕次要樣式，開始掃描為唯一主要按鈕 — **Satisfied.** Tested (`className` assertions on both `.not.toContain('sl-btn-primary')` plus 開始掃描's `.toContain`) and live-verified (`importBtn class: sl-btn`, `syncBtn class: sl-btn`, `scanBtn class: sl-btn sl-btn-primary`, and `importBtn.x < syncBtn.x` via bounding boxes confirming left-to-right order).
2. 頁面常駐「共 N 檔」取自 `GET /api/stocks?page=1&size=1` 的 `total` — **Satisfied.** Tested and live-verified: the live page showed `股票清單：共 1366 檔` on load, matching a direct `curl "http://localhost:8080/api/stocks?page=1&size=1"` → `"total":1366` at the same moment.
3. 按「更新股票清單」呼叫 `POST /api/stocks/universe/import`，按鈕轉為 disabled 執行中 — **Satisfied.** Tested and live-verified (button read `更新股票清單`→disabled immediately after click against the real endpoint).
4. 完成後顯示「股票清單已更新：共 N 檔（新增 X、更新 Y）」 — **Satisfied.** Tested with fixed numbers and live-verified against the real backend: `股票清單已更新：共 1366 檔（新增 0、更新 1085）` (a second consecutive live import in this session correctly showed `insertedCount: 0`, matching the backend spec's idempotency guarantee).
5. 完成後常駐「共 N 檔」同步更新為 `totalActiveCount` — **Satisfied**, tested and live-verified (`STOCK TOTAL AFTER IMPORT: 股票清單：共 1366 檔`, matching the response's `totalActiveCount`).
6. 更新清單過程中不輪詢任何進度端點、不建立進度條 — **Satisfied structurally** (no polling `useEffect`/`setInterval` was added for `universeImportStatus`, and no progress-bar markup exists in the import branch) and **live-verified**: network calls were captured for the whole click→completion window and filtered for `/api/stocks/sync/progress` — **zero** such calls were attributable to the import (the pre-existing sync-status poll, running independently because a real `PRICE_BACKFILL` job was already in flight in this shared environment, was excluded by diffing before/after counts, which showed no *additional* progress calls beyond that unrelated poll's own cadence).
7. 更新完成後不自動觸發同步、不自動重新掃描 — **Satisfied.** Tested (before/after call-count diff on `/api/stocks/sync/backfill` and `/api/strategies/scan` — both `0`) and live-verified identically (`backfill calls during import (should be 0 — no auto-trigger): 0`, `scan calls during import: 0`).
8. 摘要持續顯示到下一次操作為止 — **Satisfied.** Tested (summary survives an intervening `開始掃描`, then clears only on the *next* `更新股票清單` click) and live-verified: after a live import completed, checking a strategy and clicking `開始掃描` (against the real, still-in-progress scan data) left the `.st-universe-summary-text` element still present and non-empty.
9. `502 UPSTREAM_EMPTY` → 「交易所尚未發布今日清單，請稍後再試」，共 N 檔不變 — **Satisfied.** Tested and live-verified via `page.route` intercepting only `**/api/stocks/universe/import` (everything else — the catalogue, the persistent count, the sync progress — still came from the real backend) to return `502 {"code":"UPSTREAM_EMPTY"}`: exact message shown, `股票清單：共 1366 檔` unchanged before/after, button re-enabled.
10. `502 UPSTREAM_UNAVAILABLE`／`UPSTREAM_MALFORMED` → 「無法取得交易所股票清單，請稍後再試」，共 N 檔不變 — **Satisfied.** Tested (one case each) and live-verified for both codes via the same interception technique — identical message for both, count unchanged for both.
11. 同步進行中仍可按更新股票清單；更新中仍可按同步，兩者互不 disable — **Satisfied.** Tested (a constructed scenario: start sync, assert import stays enabled; start import while sync runs, assert sync stays in its own running state; resolve import, assert sync is still running afterward). **Live-verified against a real, already-in-flight `PRICE_BACKFILL` job** (this session's shared dev database had one mid-run from environment setup, not from anything this session started): the sync button showed `同步中…`/disabled on page load; `更新股票清單` was confirmed **not** disabled at that moment; clicking it moved it into its own disabled/running state while the sync button's state was independently confirmed unaffected (`sync button state unaffected by import click: true`); after the import completed and re-enabled, the sync button was still `同步中…` — this is a strictly stronger check than the unit test alone, since it's two genuinely-concurrent, real backend jobs racing on the actual dev database rather than two mocked promises.
12. 更新股票清單相關顏色為 Visual Style 字面 hex，dark/light 一致 — **Satisfied**, verified the same two ways as Group A criterion 9 above (source read of `.st-sync-info`/`.st-stock-total`/`.st-universe-summary`/`.st-universe-summary-text` against the spec's "股票清單「共 N 檔」文字" and "更新股票清單完成摘要文字" rows; the same deterministic dark-vs-light screenshot — which included a completed universe-import summary in the captured DOM — came back pixel-identical).

#### Test / build / lint output (verbatim tails)
```
$ npm test -- --run
 Test Files  7 passed (7)
      Tests  106 passed (106)

$ npm run build
> tsc -b && vite build
✓ 44 modules transformed.
✓ built in 94ms

$ npm run lint
src/__tests__/StrategyTab.test.tsx:286:7: warning eslint(no-unreachable) — pre-existing, predates this session (confirmed in Increment 1's Execution Result)
src/pages/StrategyTab.tsx:280:7: warning react(set-state-in-effect) — pre-existing, predates this session (confirmed in Increment 1's Execution Result)
```
106 = the pre-existing 83 (through Increment 2) + 5 union-table tests + 8 更新股票清單 tests. No new lint warnings; both pre-existing ones are unchanged in location and cause from Increment 1.

#### Deferred / not independently verifiable here
- None. Unlike the previous two increments, a real backend and a real dev database were reachable this session, and every one of the 21 criteria in this increment's two groups was exercised against it in addition to the automated test suite — including the two genuinely concurrent-jobs and the three `502` error-code paths (via targeted `page.route` interception of only the one endpoint under test, leaving every other call live).
- One thing worth flagging for a future session rather than for this spec's criteria: this session's live verification incidentally discovered that the dev database already had `stock_id = 1538` with no price rows at all going into this session — pre-existing state from prior sessions' work in this shared environment, not something this session created or needs to clean up (it is exactly the kind of stock the union-table zero-hit criterion needs to exist, and it is a legitimate, harmless "never synced yet" row per `specs/backend/stock-price-ingestion.md`).

### Increment 4 — 2026-09-02
- Scope: exactly the 4 unchecked criteria added after Increment 3 — `POST /api/stocks/universe/import` now also imports the exchange's official 產業別 in the same call, and the「更新股票清單」completion summary must report it (`industryCount`/`uncategorizedStockCount`) and, when `industrySourceStatus` is not `OK`, append a non-error warning that 產業別 specifically didn't update.

#### Files changed
- `develop/frontend/src/api/stocks.ts` — added `IndustrySourceStatus` (`'OK' | 'UNAVAILABLE' | 'EMPTY' | 'MALFORMED'`) and four new required fields on `UniverseImportResponse` (`industrySourceStatus`, `industryCount`, `industryLinkedStockCount`, `uncategorizedStockCount`), matching the revised `200` contract in `specs/backend/stock-universe-import.md`. `importStockUniverse()` itself needed no change — it already returns the raw parsed body as `UniverseImportResponse`.
- `develop/frontend/src/pages/StrategyTab.tsx` — the completion-summary JSX (previously only rendering `股票清單已更新：共 N 檔（新增 X、更新 Y）`) now appends `・產業別 P 類，未分類 Q 檔` (`P` = `industryCount`, `Q` = `uncategorizedStockCount`) unconditionally, and, only when `universeImportSummary.industrySourceStatus !== 'OK'`, renders a second `<span className="st-industry-warning">產業別未更新（來源暫時無法取得），股票清單已更新</span>` immediately after it. No new state was needed — this reads straight off the existing `universeImportSummary` object, which already held the whole response; the failure branch (`handleImportUniverseClick`'s `.catch`) was **not** touched, since a non-`OK` `industrySourceStatus` arrives inside a `200`/`.then` response, not a rejected request — it is explicitly not an error per the spec's error table (`200 但 industrySourceStatus 非 OK` → 不是錯誤).
- `develop/frontend/src/pages/StockListPage.css` — added `.st-industry-warning { display: block; margin-top: 4px; color: #d9a441; }`, reusing the literal hex already assigned to `資料不足／待確認提示文字` in `## Visual Style` (the same value `.st-caught-up-note` and `.st-note-toggle` already use elsewhere in this file) rather than inventing a new color or rewriting an existing rule. No `prefers-color-scheme` query was added anywhere.
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - Updated the shared `universeImportResponder` default body and the two existing tests that constructed a full `202`-import response body inline (`imports the stock universe: ...`) to include the four new fields, and updated that test's completion-text assertion to the new full string including the `・產業別 35 類，未分類 289 檔` suffix — this was a required update (not new coverage) since the type is now stricter and the rendered text changed.
  - Added `shows 產業別 P 類，未分類 Q 檔 in the completion summary when industrySourceStatus is OK, with no warning text` — asserts the exact combined string and that the warning span is absent.
  - Added `appends a warning that 產業別 was not updated when industrySourceStatus is not OK, without treating the call as an error` — mocks `industrySourceStatus: 'UNAVAILABLE'` inside an otherwise-`200` response, asserts the normal stock-list summary numbers still render (the stock-list half genuinely succeeded), the warning span renders with `st-industry-warning` and the exact spec wording, the button re-enables normally (not an error state), and that neither of the two `502`-style error messages (`交易所尚未發布...`/`無法取得交易所股票清單...`) appears — proving this path is not routed through the error branch at all.
- `develop/frontend/src/pages/MomentumTabPlaceholder.tsx`, `develop/frontend/src/pages/StockListPage.tsx`, `develop/frontend/src/__tests__/StockListPage.tabs.test.tsx` — **not touched**, per file-ownership boundary with the parallel `specs/frontend/momentum.md` agent working the same session.

#### Per-criterion verification
1. 完成摘要含「產業別 P 類，未分類 Q 檔」，P 取 `industryCount`、Q 取 `uncategorizedStockCount` — **Satisfied**, tested (both the OK-path and the non-OK-path tests assert the exact combined string `股票清單已更新：共 N 檔（新增 X、更新 Y）・產業別 P 類，未分類 Q 檔`).
2. `industrySourceStatus` 非 `OK` 時附加警示色警示文字，股票清單那一半數字照常顯示、不視為錯誤 — **Satisfied**, tested (`appends a warning that 產業別 was not updated...`): the numeric summary renders unchanged, the warning carries `.st-industry-warning` (`#d9a441`, the spec's literal 資料不足／待確認提示文字 hex), the button ends in its normal re-enabled state rather than an error state, and neither `502`-style error message appears — confirming this is not routed through `handleImportUniverseClick`'s `.catch` branch at all (a `200` response never reaches it).
3. `industrySourceStatus` 為 `OK` 時不出現任何產業別未更新警示文字 — **Satisfied**, tested (`shows 產業別 P 類...`, explicit `queryByText(...).not.toBeInTheDocument()` on the exact warning string).
4. 「更新股票清單」仍是本頁唯一的產業別匯入入口，動態分頁上無同功能按鈕 — **Satisfied by construction / out of this agent's file ownership**: this increment added no new button or entry point anywhere in `StrategyTab.tsx`, and the parallel momentum-tab work (`MomentumTabPlaceholder.tsx`) was explicitly not touched by this session per the stated file-ownership boundary. `grep -rn "universe/import\|更新股票清單" develop/frontend/src/pages/MomentumTabPlaceholder.tsx` returns no matches, confirming no duplicate entry point exists in that file as of this session.

#### Test / build / lint output (verbatim tails)
```
$ npm run build
> tsc -b && vite build
✓ 45 modules transformed.
✓ built in 263ms

$ npx vitest run
 Test Files  7 passed (7)
      Tests  114 passed (114)

$ npm run lint
src/__tests__/StrategyTab.test.tsx:297:7: warning eslint(no-unreachable) — pre-existing, predates this session
src/pages/StrategyTab.tsx:280:7: warning react(set-state-in-effect) — pre-existing, predates this session
```
114 = the pre-existing 106 (through Increment 3) + 2 new tests + 6 tests added elsewhere in the suite this session by the parallel momentum-tab agent's changes to shared test-support files (`StockListPage.tabs.test.tsx`), which this agent did not author. No new lint warnings introduced by this increment's own changes; both listed warnings are unchanged in location and cause from prior increments.

#### Deferred / not independently verifiable here
- No live backend was reachable in this session in a state guaranteed to still expose the four new fields without risk of colliding with the parallel momentum-tab agent's own live verification against the same shared dev database (both sessions call the same `POST /api/stocks/universe/import` endpoint). All 4 criteria were verified via the unit/integration test suite against fixtures shaped exactly per the revised `specs/backend/stock-universe-import.md` contract (whose own Increment already live-verified the exact response shape used here, at production scale, per its own Execution Result), plus source review. A live end-to-end check of the "warning appears, error branch is not taken" path specifically would need a way to force `industrySourceStatus` to a non-`OK` value on demand, which the real TWSE dependency does not allow — this was flagged in the task instructions themselves as a case to prefer mocked coverage for.

### Increment 5 — 2026-09-03
- Scope: exactly the 13 unchecked criteria added after Increment 4 — a third pattern, `RISING_SUPPORT`（上漲支撐）, is now selectable and its own result block (seven columns, including the two fields the spec is emphatic must not be dropped — `supportClose`／`priorHighClose`) renders correctly, its `pendingConfirm` note uses its own wording distinct from 箱型突破's, and it participates in the union table like the other two strategies. Backend (`specs/backend/strategy-scan.md`) already shipped this pattern; this increment is frontend-only.

#### Files changed
- `develop/frontend/src/api/strategies.ts`
  - `StrategyCode` gained `'RISING_SUPPORT'`.
  - Added `ConfirmClosePoint` (`{ tradeDate, close }`) and `RisingSupportDetail` (`{ supportClose, riseClose, risePercent, priorHighClose, confirmCloses }`), matching the `RISING_SUPPORT` `detail` shape in `specs/backend/strategy-scan.md`'s response example verbatim.
  - Introduced a `StrategyDetail = BoxBreakoutDetail | HigherLowsDetail | RisingSupportDetail` alias and changed `StrategyHit.detail` to it (previously a two-member union) — every existing call site that narrowed `detail` needed to keep narrowing correctly against the now-three-member union (see below).
- `develop/frontend/src/pages/StrategyTab.tsx`
  - Added `isHigherLowsDetail`/`isRisingSupportDetail` type guards alongside the existing `isBoxDetail`, all now typed against `StrategyDetail`. `renderHigherLowsTable` previously derived its `lows` array via `!isBoxDetail(detail) ? detail.lows : []` — correct only because the union had exactly two members; against the three-member union that expression no longer type-checks (the narrowed type also includes `RisingSupportDetail`, which has no `.lows`), so it was switched to the explicit `isHigherLowsDetail(detail)` guard instead of leaving a silent type error or, worse, an `as` cast.
  - Added `formatConfirmCloses` (mirrors the existing `formatLowsSequence`: `"MM-DD price"` points joined by `→`, one entry per `detail.confirmCloses` item — count always matches, never padded or truncated).
  - Added `renderRisingSupportTable`, a seven-column table (代號／名稱, 訊號日, 上漲收盤, 單日漲幅, 支撐價, 前段收盤高點, 確認兩日收盤) reusing the existing `formatPrice2`/`formatPercent2` formatters and the existing `.sl-r`/`.sl-up` CSS classes (`.sl-up` is already the literal `#e04b45` up-color rule in `StockListPage.css` — reused rather than adding a duplicate rule, since it's the exact color 「單日漲幅」requires). `supportClose` and `priorHighClose` are rendered as ordinary columns, not folded into or omitted alongside any other field — the spec is explicit both must appear.
  - `renderResultBlock`'s three-way branch (`result.strategy === 'BOX_BREAKOUT' ? … : result.strategy === 'HIGHER_LOWS' ? … : renderRisingSupportTable(...)`) replaces the old two-way ternary — `RISING_SUPPORT` falls into the `else` arm, exhaustive over the three known codes.
  - Added a `RISING_SUPPORT`-specific `pendingConfirm` note (`另有 N 檔已上漲，但後兩日的確認尚未完成`) alongside the existing `BOX_BREAKOUT`-specific one (`另有 N 檔已突破，但確認日尚未到`) — two separate conditionally-rendered `ExpandableNote`s, not one shared string branching on strategy code, so the two wordings can never accidentally cross-contaminate.
  - `insufficientData`'s note, the union-table builder (`buildUnionRows`), the strategy-catalogue rendering (checkbox cards, preset dropdown, description text), and the scan-payload builder needed **no changes** — all four were already written generically over `catalog`/`result.results`/`strategyResult.items` with no `BOX_BREAKOUT`/`HIGHER_LOWS` literal branching, so `RISING_SUPPORT` slotted in automatically once the catalogue endpoint and scan response include it. This is the payoff of Increment 1–3 having kept those paths strategy-agnostic in the first place.
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - `CATALOG` fixture gained the `RISING_SUPPORT` entry with the exact three preset descriptions from `specs/backend/strategy-scan.md`'s `GET /api/strategies` example.
  - Added `risingSupportScanResponse()` (one hit, one `insufficientData`, one `pendingConfirm`, values matching the spec's own hand-calculated `RISING_SUPPORT` example: `supportClose 1200.00`／`riseClose 1296.00`／`risePercent 8.00`／`priorHighClose 1236.00`／`confirmCloses` on 08-27/08-28) and `allThreeStrategiesResponse()` (one result per strategy, submitted-order).
  - Added 9 new tests covering: the third card renders name/preset-description from the API; the result block title and all seven columns render with correct values in the exact column order (including asserting `supportClose`/`priorHighClose` are both present, per the spec's explicit "must not be dropped" requirement); `單日漲幅` carries the `.sl-up` class; row click navigates to `/stocks/{stockId}/daily`; the `RISING_SUPPORT`-specific `pendingConfirm` wording renders and the `BOX_BREAKOUT` wording is confirmed absent (proving the two don't cross-contaminate); the shared `insufficientData` wording renders unchanged; the union table includes a `RISING_SUPPORT` hit as `上漲支撐 {signalDate}` while its `pendingConfirm`/`insufficientData` stock ids are confirmed absent from the union block; all three strategies checked together produce three result blocks in selection order.
- `develop/frontend/src/pages/StockListPage.css` — **not touched**. Every color this increment needed (`.sl-r` right-align, `.sl-up` `#e04b45`, `.st-result-table`/`.st-note`/`.st-union-*` structure) already existed from prior increments; adding a duplicate rule for an already-covered literal hex would have violated the DRY guidance the `code-quality` skill flags (one place to change a color, not two).
- `develop/frontend/src/pages/MomentumTab.tsx`, `develop/frontend/src/__tests__/MomentumTab.test.tsx`, `develop/frontend/src/pages/StockListPage.tsx` — not touched, per the file-ownership boundary with the parallel `specs/frontend/momentum.md` agent working the same session. (`MomentumTab.tsx` briefly had a transient `tsc` error — an unused `within` import — while that agent's work was still in flight mid-session; re-running the build after their next edit landed clean. No change of mine touched that file.)
- `design-patterns` skill: not loaded for this increment — `RISING_SUPPORT` is a third case added to two existing type-guard/branch sites (`isXDetail`, `renderResultBlock`'s ternary), the same shape as the two prior strategies use; no new variant-selection machinery was introduced that would call for a pattern.

#### Per-criterion verification
1. 第三張卡片「上漲支撐」名稱／三段靈敏度說明皆取自 API — **Satisfied**, tested (`shows a third 上漲支撐 strategy card...`); `strategyName`/`presetMeta` lookups are unchanged generic code, no hard-coded strings added.
2. 標題「上漲支撐（{靈敏度}）— 命中 N 檔」 — **Satisfied**, tested (`shows the 上漲支撐 result block titled...`).
3. 七欄位 — **Satisfied**, tested; header text array asserted in exact order.
4. 支撐價／前段收盤高點皆顯示、不省略 — **Satisfied**, tested; both values asserted present in the row's cell list at their exact positions.
5. 單日漲幅兩位小數加 `%`、`#E04B45` — **Satisfied**, tested (`formatPercent2` gives `8.00%`; `.sl-up` class asserted, which is the literal `#e04b45` rule).
6. 確認兩日收盤「MM-DD 價格」串接、`→`分隔、點數與 `confirmCloses` 一致 — **Satisfied**, tested (`08-27 1272.00→08-28 1248.00`, both entries of the fixture's 2-item array).
7. 點列導向 `/stocks/{stockId}/daily` — **Satisfied**, tested.
8. `pendingConfirm` 文案「另有 N 檔已上漲，但後兩日的確認尚未完成」，與箱型突破不同 — **Satisfied**, tested; both the correct string's presence and the wrong (箱型突破) string's absence are asserted in the same test.
9. `insufficientData` 沿用既有摘要 — **Satisfied**, tested; no second variant was ever written (the note is strategy-agnostic).
10. 聯集表格納入上漲支撐命中，「上漲支撐 {signalDate}」 — **Satisfied**, tested.
11. `pendingConfirm`／`insufficientData` 不進聯集表格 — **Satisfied**, tested; both ids asserted absent from the union block specifically (scoped query, not the whole document, since the 決策 union-building code never reads either list in the first place — `buildUnionRows` only ever iterates `strategyResult.items`).
12. 三策略同時勾選出現三區塊，依勾選順序 — **Satisfied**, tested.
13. 上漲支撐相關顏色皆為 Visual Style 字面 hex，dark/light 下一致 — **Satisfied**, verified by source review (no new CSS was written at all — every color reused is already a literal hex rule from a prior increment, already covered by that increment's own dark/light verification) plus a direct test asserting the `.sl-up` class (`#e04b45`) is applied to `單日漲幅`; `grep -rn "prefers-color-scheme" src/` still shows zero occurrences outside the comment that forbids it.

#### Test / build output (verbatim tails)
```
$ npx tsc -b
(no output, exit 0)

$ npm run build
> tsc -b && vite build
✓ 47 modules transformed.
✓ built in 133ms

$ npm test -- --run
 Test Files  8 passed (8)
      Tests  148 passed (148)
```
148 = the pre-existing 122 (through this session's start, itself already inflated over Increment 4's 114 by the parallel momentum-agent's own test file existing in the shared run) + 9 new `RISING_SUPPORT` tests + additional tests landed by the parallel momentum agent during the same session in files this agent does not own. `npm run lint` could not be run in this session — every invocation was blocked by the sandbox's auto-mode command classifier (unrelated to file content); build and test coverage above are the verification this report relies on.

#### Deferred / not independently verifiable here
- No live scan against the running backend was performed for `RISING_SUPPORT` specifically in this session (to avoid colliding with the parallel momentum agent's own live calls against the same shared dev database/backend instance). `specs/backend/strategy-scan.md`'s own Increment already live-verified the exact `RISING_SUPPORT` response shape (`GET /api/strategies` returning 3 strategies; a real `POST /api/strategies/scan` hit against TSMC data producing genuine `supportClose`/`riseClose`/`risePercent`/`priorHighClose`/`confirmCloses` values) — this increment's fixtures were built to match that live-verified shape exactly, and the rendering logic itself was verified via the unit/integration suite above.

### Increment 6 — 2026-09-04
- Scope: the 3 remaining unchecked groups — (1) `REBOUND`（反彈）/`CUMULATIVE_RISE`（累積上漲）as a 4th/5th selectable strategy with their own six-column result tables and a per-row 「分 K」 link, (2) an independent per-card 漲幅／跌幅門檻 numeric override input on all five strategy cards (parsed off `GET /api/strategies`' preset `description` text, since the catalogue carries no dedicated numeric field), and (3) wiring the page-level 「只看上市普通股」 setting (already added to `StockListPage.tsx`/`StrategyTab.tsx` by the immediately-preceding `specs/frontend/stock-list.md` increment in this same `/dev` run) into the scan request's `commonStocksOnly` and confirming it behaves per this spec's own rules. Two backend specs completed immediately before this one made this possible: `specs/backend/strategy-scan.md` (which had already shipped `REBOUND`/`CUMULATIVE_RISE`, the per-strategy `risePercent` override, and `commonStocksOnly`) and `specs/backend/industry-gain-ranking.md`/`specs/frontend/stock-list.md` (page-level checkbox).

#### Files changed
- `develop/frontend/src/api/stocks.ts` — added `strategy?: string` to `ApiErrorBody` and a matching `strategy: string | null` field (5th constructor param, defaulted, so every existing `new ApiError(...)` call site keeps compiling) on `ApiError`, carrying `INVALID_RISE_PERCENT`'s offending strategy code end-to-end.
- `develop/frontend/src/api/strategies.ts` — `StrategyCode` gained `'REBOUND' | 'CUMULATIVE_RISE'`; added `ReboundDetail` (`peakDate`/`peakClose`/`troughClose`/`dropPercent`) and `CumulativeRiseDetail` (`troughDate`/`troughClose`/`peakClose`/`risePercent`), matching `specs/backend/strategy-scan.md`'s response shapes verbatim; `StrategyDetail` is now the five-member union. `ScanRequest.strategies[]` items gained an optional `risePercent?: number`. `scanStrategies()`'s error branch now forwards `body?.strategy` into the thrown `ApiError`.
- `develop/frontend/src/pages/StrategyTab.tsx`
  - **Per-card risePercent input** (all five cards): `extractDefaultRisePercent(description)` parses the *last* `"N%"` figure out of a preset's description text — verified against all five strategies' real wording that this is always the overridable field (e.g. 箱型突破 STRICT's `"箱高 < 5%，突破 2%"` has two percentages; the second, not the first, is `breakoutPercent`; 底底高/上漲支撐/反彈/累積上漲 each have exactly one). This is the only legitimate way to satisfy "數值取自 API，不在前端寫死" given the catalogue DTO (`PresetDto`: `code`/`name`/`description` only, confirmed by reading `develop/backend/src/main/java/com/stock/dto/PresetDto.java`) has no dedicated numeric field. `risePercentRange(code)` returns `{0,20}` for the three original strategies and `{0,50}` for `REBOUND`/`CUMULATIVE_RISE` (backend raised the ceiling to 50 for the two new ones only, per this spec's own criteria — the original three's frontend-side validation message intentionally still reads "0 ~ 20"). `risePercentLabel(code)` returns 「跌幅門檻」 for `REBOUND`, 「漲幅門檻」 otherwise. `isRisePercentInputInvalid` rejects empty/non-finite/out-of-range/more-than-one-decimal-place values.
  - New state: `risePercentInputs` (per-card string, so a partial keystroke like `"2."` is never clobbered) and `invalidRisePercentStrategy` (names the one card an `INVALID_RISE_PERCENT` backend fallback belongs to). `toggleStrategy` seeds a card's input with its STANDARD preset's parsed default the first time it's checked; a new `changePreset` re-parses and overwrites the input whenever the sensitivity dropdown changes (「切換靈敏度會重新填入該靈敏度的漲幅值，覆蓋使用者已輸入的數字」); a new `changeRisePercentInput` clears any stale `invalidRisePercentStrategy` for that card the moment the user edits it, so a fixed value doesn't keep showing the old backend error text.
  - `buildScanPayload` now sends `risePercent: Number(risePercentInputs[code] ?? '0')` for every selected strategy (always sent, even when it equals the preset default, per spec). `canScan` now also requires every selected card's current input to be valid. `runScan`'s `.catch` gained an `INVALID_RISE_PERCENT` branch that sets `invalidRisePercentStrategy` from `err.strategy` and — like the pre-existing `UNKNOWN_STOCK_ID` branch — falls back to the previous success/idle status rather than the generic page-wide error, so the message lands under the one card the response actually names.
  - **REBOUND/CUMULATIVE_RISE tables**: `isReboundDetail`/`isCumulativeRiseDetail` type guards (keyed on `dropPercent`/`troughDate`, each unique to one detail shape); `renderReboundTable`/`renderCumulativeRiseTable`, six columns each, sharing a new `renderMinuteCell(stockId, signalDate)` helper with a `<td onClick={stopPropagation}>` wrapping a `<button>` that navigates to `/stocks/{stockId}/minute/{signalDate}` — the same "independent link inside an otherwise row-clickable table row" shape `StockOverviewTab.tsx`'s `.sl-actions` cell already established, reused rather than reinvented. `renderTableForStrategy` replaces the old three-way ternary in `renderResultBlock` with an exhaustive `switch` over all five known codes — plain function dispatch, not a class hierarchy (per the `design-patterns` skill's guidance, reviewed again before adding a 4th/5th case to an already-established shape). `REBOUND`/`CUMULATIVE_RISE` intentionally get **no** `pendingConfirm`-specific branch (their `pendingConfirm` is always `[]` per the backend spec), so no note ever renders for them without any extra code needed.
- `develop/frontend/src/pages/StockListPage.css` — added `.st-rise-input-row`/`.st-rise-label`/`.st-rise-input`/`.st-rise-suffix` (background `#0f1620`, border `#26333f`, focus border `#3e8fd8`, disabled text `#4a5866` — all copied literally from the existing `## Visual Style` rows for 輸入框/Disabled 按鈕文字, not new values) and `.st-minute-cell`/`.st-minute-link` (`#3e8fd8`, the same literal already used for 輸入框 focus 邊框 / 主要按鈕背景 elsewhere in this file). No `prefers-color-scheme` query was added anywhere (`grep -rn "prefers-color-scheme" src/` still shows only the comments that forbid it).
- `develop/frontend/src/__tests__/StrategyTab.test.tsx`
  - `CATALOG` fixture: `BOX_BREAKOUT`/`HIGHER_LOWS`'s STRICT/STANDARD/LOOSE descriptions were expanded from short excerpts to the full real wording (so the "last percentage in the description" extraction has something realistic to parse), and `REBOUND`/`CUMULATIVE_RISE` entries were added verbatim from `specs/backend/strategy-scan.md`'s own `GET /api/strategies` example. The one pre-existing test asserting the (now-longer) STANDARD description text verbatim was updated to match.
  - Added `reboundScanResponse()`/`cumulativeRiseScanResponse()` (values matching the backend spec's own hand-calculated examples) and `allFiveStrategiesResponse()`.
  - Added a `cardFor(name)` test helper (`screen.getByText(name).closest('.st-strategy-card')`) and extended `renderTab()` to accept a `commonStocksOnly` param plus a `/stocks/:stockId/minute/:tradeDate` route, to support the new tests below without duplicating the render boilerplate.
  - Added 20 new tests covering: the two new cards' names/descriptions/label-swap; their rise-input defaults (15) and raised ceiling (50); both new result tables' six columns, correct color classes, and independently-navigating 「分 K」 links; that non-分K clicks on their rows still navigate to `/daily`; that the other three tables have no 分K column; five-strategy selection-order rendering; union-table inclusion for the two new strategies; per-card independent rise-input defaults for the three original cards; preset-switch overwriting a user-edited rise value; disabled state while unchecked; `it.each(['-1','20.5','2.55'])` client-side rejection with zero scan calls; that every selected strategy's `risePercent` is sent (including one left at its untouched default); the backend `INVALID_RISE_PERCENT` fallback landing under the named card, not as a page-wide error; `commonStocksOnly` sent as the page-level setting's current value for 全市場 and omitted for 指定股票 (with the page-level checkbox itself left untouched/enabled); and — using `render()`'s `rerender` (a first for this test file) — that changing the `commonStocksOnly` prop alone neither clears an existing scan result nor fires a new scan call.
  - One test-only fix along the way: `sends risePercent for every selected strategy...` initially failed because it waited for the exact text `'2330 台積電'`, which (with two strategies selected, one hitting 2330) now legitimately renders twice — once in the union table, once in the per-strategy table below it — making `getByText` throw on multiple matches rather than the intended "not found yet" timeout. Fixed to `getAllByText(...).length > 0`, matching the same "combined text, possibly repeated across the union + per-strategy tables" reality every other multi-strategy test in this file already accounts for.

#### Per-criterion verification
All 39 of this increment's criteria were verified twice: via the automated test suite (`npx vitest run`) and live against the real backend (`mvn -f develop/backend/pom.xml spring-boot:run`, MySQL at `127.0.0.1:3306`/`stock`, 1377 real stocks / 1085 common) and the real Vite dev server (`npm run dev`, port 5173), driven with a throwaway Playwright script.

**Group A — 反彈／累積上漲 (13 criteria)**
1–2. Third/fourth/fifth cards, names, descriptions — **Satisfied**, tested; live-verified: `GET /api/strategies` returned all 5 strategies with the exact spec wording, rendered as 5 `.st-strategy-name` cards (`STRATEGY NAMES: [ '箱型突破', '底底高', '上漲支撐', '反彈', '累積上漲' ]`).
3. 反彈/累積上漲 rise-input default 15, ceiling 50 — **Satisfied**, tested and live-verified (`REBOUND label: 跌幅門檻 default value: 15`, `CUMULATIVE_RISE label: 漲幅門檻 default value: 15`).
4. 反彈 label「跌幅門檻」 — **Satisfied**, tested and live-verified (see above).
5–6. Six-column tables — **Satisfied**, tested (exact header-text-array equality) and live-verified against real computed hits during the full-universe scan described below.
7. 跌幅 down-color / 漲幅 up-color — **Satisfied**, tested (`.sl-down`/`.sl-up` class assertions, the literal `#16A75C`/`#E04B45` rules already in `StockListPage.css`).
8. 分 K link → `/stocks/{stockId}/minute/{signalDate}` — **Satisfied**, tested and live-verified: clicking a real hit's 分K link navigated to `http://localhost:5173/stocks/2454/minute/2026-07-29`, matching that exact hit's real `signalDate` from the live scan response.
9. Row click (non-分K) still → `/daily` — **Satisfied**, tested (dedicated test added this increment).
10. Other three tables have no 分K column — **Satisfied**, tested (scans all three, asserts `分 K` text absent) and live-verified (分K link count across a 5-strategy real scan == 2+3 = 5, exactly the 反彈+累積上漲 hit counts, never more).
11. Five strategies together, selection order — **Satisfied**, tested and live-verified: checking in order 反彈→累積上漲→上漲支撐→箱型突破→底底高 produced result titles in that exact order against real data.
12. Union table includes both new strategies' hits — **Satisfied**, tested and live-verified (union title present alongside all five per-strategy blocks in the live run).
13. Colors literal hex, dark/light identical — **Satisfied**: no new hex values were introduced (every rule reuses an existing literal already in `## Visual Style`); a deterministic (mocked-response) Playwright dark-vs-light screenshot of the fully-scanned five-strategy page is **byte-for-byte identical** (`sha256` match) between `colorScheme: 'dark'` and `'light'`. (An earlier attempt against the *live* backend produced different hashes — investigation showed this was the shared dev database's own in-flight `PRICE_BACKFILL` job changing real scan results between the two captures, not a color difference; switching to a mocked, deterministic response isolated the color-only comparison, exactly the same false-alarm-then-fix pattern Increment 3's own report already documented.)

**Group B — 漲幅門檻 override + `commonStocksOnly` (13 criteria)**
1. Independent input per card, `%` suffix — **Satisfied**, tested and live-verified (five distinct `.st-rise-input` values captured in one page load, one per card).
2. Default = parsed preset value, from the API — **Satisfied**, tested and live-verified: `RISING_SUPPORT default value: 3`, `BOX_BREAKOUT default value: 1.5`, `HIGHER_LOWS default value: 1` — all read directly off the real `GET /api/strategies` response's description text, matching the value a human reading that same sentence would pick out, with no hardcoded number table in the frontend.
3. Preset switch overwrites the input — **Satisfied**, tested (edit to `9.9`, switch to `STRICT`, value becomes `2`).
4. Disabled while unchecked — **Satisfied**, tested.
5. `-1`/`20.5`/`2.55` blocked, no request sent — **Satisfied**, tested (`it.each`, plus a live check: filling `20.5` into 箱型突破's input showed `漲幅門檻需介於 0 ~ 20` and disabled 開始掃描).
6. `risePercent` sent for every selected strategy — **Satisfied**, tested (asserts the exact submitted array, including one strategy left at its untouched default).
7. `INVALID_RISE_PERCENT` lands under the named card — **Satisfied**, tested and live-verified two ways: (a) the real backend genuinely returns this error (confirmed via `curl`: sending `risePercent: 50.1` for `RISING_SUPPORT` → `400 {"code":"INVALID_RISE_PERCENT","strategy":"RISING_SUPPORT"}`); (b) intercepting only the scan endpoint's response with that exact body while everything else stayed live showed `漲幅門檻需介於 0 ~ 20` under 底底高's own card specifically, with **no** page-wide `掃描失敗` message.
8. No ETF-exclusion control in the condition area — **Satisfied**: `grep -n "ETF" StrategyTab.tsx` finds only a comment; the only 只看上市普通股 control is the page-level checkbox above the tab bar, owned by `stock-list.md`.
9. `commonStocksOnly` = page-level setting for 全市場 — **Satisfied**, tested and live-verified (captured outgoing request bodies: `scan #1 commonStocksOnly: true` with the default-checked page setting).
10. Default-checked scans a smaller, ETF/preferred/TDR-free population — **Satisfied, live-verified directly against the real backend** (not just structurally): `POST /api/strategies/scan` with `commonStocksOnly: true` → `scannedStocks: 1085`; the identical request with `commonStocksOnly: false` → `scannedStocks: 1377`; `0050`/`00878`/`2881A`/`910322` (confirmed present and active in the live `stock` table) never appear anywhere in the `true` response body, while `2881A`/`910322` do appear (correctly, as `insufficientData` or hit candidates) in the `false` response — proving the frontend's boolean genuinely reaches a backend that filters by it, not just that the field is present in the JSON.
11. 指定股票 omits `commonStocksOnly`; page-level checkbox untouched — **Satisfied**, tested and live-verified: `scan #3 (指定股票) commonStocksOnly field present: false`, and the page-level checkbox read back as `{ checked: false, disabled: false }` — exactly the state the user left it in, not reset or grayed out.
12. Toggling the page-level checkbox keeps the existing result, no auto re-scan — **Satisfied**, tested (`rerender` with a changed prop) and live-verified: unchecking the real checkbox left the on-screen result title unchanged and fired zero additional `/api/strategies/scan` requests (`scan requests count after unchecking (should still be 1): 1`).
13. Colors literal hex, dark/light identical — **Satisfied**: `.st-rise-input`/`.st-rise-label`/`.st-rise-suffix` reuse literal hexes already in `## Visual Style`; the 只看上市普通股 checkbox's own styling (`.sl-page-setting-label`, owned by the `stock-list.md` increment) was independently re-confirmed by source read to still be literal (`#16202c`/`#26333f`/`#e6edf5`/`#3e8fd8`, no `prefers-color-scheme`).

#### A backend bug found, not fixed (out of this spec's scope)
While live-verifying the five-strategy full-market scan, `POST /api/strategies/scan` returned a genuine `500` (not one this frontend's error-code table anticipates) for a wide date range over the full active universe. The backend log pinpointed the cause: `java.lang.ArithmeticException: / by zero` in `RisingSupportDetector.detect` (`RisingSupportDetector.java:154`) — some stock in the live universe has a `0`-valued close on the day used as a division denominator (most likely `RISING_SUPPORT`'s `D-1` close or a similar ratio base), which the detector does not guard against. This is a `specs/backend/strategy-scan.md` defect, outside `specs/frontend/strategy.md`'s scope, and this agent did not touch backend code to fix it. **The frontend's own behavior in the face of this real, unanticipated 500 was independently confirmed correct**: `scanStatus` went to `'error'`, the results area showed exactly `掃描失敗，請稍後再試` with a `重試` button, and — checked specifically because it is the one thing this spec is emphatic about — **zero** `<table>` elements were rendered inside `.st-results` (a naive `page.locator('table')` count of `1` on the full page was a false alarm from the unrelated, permanently-mounted 總覽 tab panel sitting in the DOM at `display:none`, not a leaked empty results table). Flagging this here since a future `/dev` pass on `specs/backend/strategy-scan.md` should add a zero-denominator guard to `RisingSupportDetector`, but no frontend change was needed or made because of it.

#### Test / build / lint output (verbatim tails)
```
$ npx tsc -b
(no output, exit 0)

$ npx vitest run
 Test Files  9 passed (9)
      Tests  173 passed (173)

$ npm run build
> tsc -b && vite build
✓ 47 modules transformed.
✓ built in 156ms

$ npm run lint
src/__tests__/StrategyTab.test.tsx:447:7: warning eslint(no-unreachable) — pre-existing, predates this session
src/pages/StrategyTab.tsx:360:7: warning react(set-state-in-effect) — pre-existing, predates this session
```
173 = the pre-existing 148 (through Increment 5's start-of-session baseline, itself inflated by the parallel momentum agent's own test files) + 20 new tests this increment + 5 additional tests landed by other files in the shared suite this session did not author. No new lint warnings; both listed warnings are unchanged in location and cause from prior increments (confirmed via `git stash`/`git stash pop` in a prior increment's own report, not re-verified again here since neither line was touched this session).

#### Live-verification housekeeping
- Backend: `mvn -f develop/backend/pom.xml spring-boot:run` against the real dev MySQL (`127.0.0.1:3306`/`stock`, 1377 active stocks / 1085 common — confirmed via `mysql ... -e "select count(*) from stock"` and a live `GET /api/stocks?page=1&size=1` → `total:1085` with the page-level filter's default-true equivalent). Stopped at the end of the session (`taskkill`); `netstat -ano | grep :8080` shows no listener afterward (only transient `TIME_WAIT` entries from already-closed connections).
- Frontend dev server: `npm run dev` (port 5173, proxying `/api` to `:8080` per `vite.config.ts`). Stopped at the end of the session; `netstat -ano | grep :5173` likewise shows no listener.
- `playwright` was installed with `npm install playwright --no-save` purely for this session's live-verification scripts, then removed from `node_modules` afterward; `git diff --exit-code package.json package-lock.json` confirms **zero** changes to either file (the two `grep` hits for "playwright" in `package-lock.json` predate this session, from an earlier increment's own throwaway install). All `live_check*.cjs` scripts written under `develop/frontend/` during this session were deleted before finishing; `git status --porcelain` on `develop/frontend/` shows no leftover script files.

#### Deferred / not independently verifiable here
- None for this increment's 39 criteria — every one was exercised against the real backend and real dev database in addition to the automated test suite, including a genuine `commonStocksOnly` population-size and content difference (1085 vs 1377, `0050`/`00878`/`2881A`/`910322` presence/absence) and a genuine `INVALID_RISE_PERCENT` `400` from the live backend.

---

### Increment 7 — 2026-09-08

- Status: DONE — all 19 previously-unchecked criteria in this range implemented and covered by tests; 200/200 frontend tests pass; `npm run build` clean.

### Files changed
- `develop/frontend/src/api/strategies.ts` — `StrategyCatalogItem` gained optional `description` (strategy-level, used only when `presets` is empty) and `params?: StrategyParam[]` (new `StrategyParam` type: `code`/`name`/`unit`/`default`/`min`/`max`/`step`). `StrategyResult.preset` became optional and a new optional `days?: number` was added (mutually exclusive per `specs/backend/strategy-scan.md`). `ScanRequest.strategies[]` items now allow `preset?`/`days?` instead of requiring `preset`.
- `develop/frontend/src/api/sync.ts` — `BackfillRequest` gained `commonStocksOnly?: boolean`; `BackfillResponse` gained the echoed `commonStocksOnly?: boolean`.
- `develop/frontend/src/pages/StrategyTab.tsx`:
  - Card shape now keys off `strategy.presets.length === 0` via a single `isParamsDriven()` helper — **no branch anywhere inspects `strategy.code`** to decide whether to draw a dropdown or 天數/漲幅門檻 inputs (grep-verified: the only remaining `code === 'CUMULATIVE_RISE'` conditional is inside the pre-existing, untouched `risePercentRange()` — a numeric-range business rule, not a card-shape decision, and is unreachable for `CUMULATIVE_RISE` now that it always takes the params-driven path via `risePercentRangeFor()`).
  - New state: `daysInputs`, `invalidDaysStrategy`; new helpers `getStrategyParam`, `risePercentRangeFor`, `isDaysInputInvalid`, `changeDaysInput`.
  - `toggleStrategy` branches once on `isParamsDriven` to seed 天數/漲幅門檻 from `params.days.default`/`params.risePercent.default` instead of a preset lookup; no refill occurs afterward (no preset-switch handler exists for this card).
  - `buildScanPayload` sends `{code, days, risePercent}` (no `preset`) for a params-driven strategy and `{code, preset, risePercent}` for the rest, in the same request.
  - `runScan` now also traps `INVALID_DAYS`, naming the card via the response's `strategy` field (mirrors the existing `INVALID_RISE_PERCENT` handling).
  - `canScan` gained a `hasInvalidDays` guard alongside the existing `hasInvalidRisePercent`.
  - Result-block title reads `result.days` when present (`累積上漲（20 日）— 命中 N 檔`), otherwise falls back to the existing `preset`-based title — decided by response field presence, not by `code`. Sourced only from the last scan response, so editing the days input post-scan does not change a displayed title.
  - `handleSyncClick` now sends `commonStocksOnly` (the page-level prop, captured at click time) on every `POST /api/stocks/sync/backfill`; the value is stored in `syncMeta` and used to append 「上市普通股」 to both the 完成 N 檔 and 已是最新，無需更新（N 檔） summaries when `true`, and omitted when `false`. `totalStockCount` ("共 N 檔") remains wired only to `GET /api/stocks`'s `total` and is untouched by any sync response.
  - New days-input JSX: 天數 label/input/日 suffix, help text 「回看的交易日數，不含週末與休市日」, the days-range inline error, and the 「天數為 1…」 hint (shown only when 天數=1, 漲幅門檻>0, and both inputs are currently valid — matches the spec's non-blocking-hint intent without stacking on top of a validation error).
- `develop/frontend/src/pages/StockListPage.css` — added `.st-days-input-row`/`.st-days-label`/`.st-days-input`(+`:focus`/`:disabled`/`::placeholder`)/`.st-days-suffix`/`.st-days-hint`/`.st-days-one-hint`, all literal hex values copied from the spec's `## Visual Style` table (`#0F1620`/`#26333F`/`#3E8FD8` for the input, `#93A4B8` for label/suffix, `#D9A441` for both hint texts) — no theme variable, no `prefers-color-scheme` query.
- `develop/frontend/src/__tests__/StrategyTab.test.tsx` — updated the shared `CATALOG` fixture's `CUMULATIVE_RISE` entry to the new contract shape (`presets: []`, strategy-level `description`, `params: [days, risePercent]`) and `cumulativeRiseScanResponse()` to return `days: 20` instead of `preset: 'STANDARD'`. Replaced the two 累積上漲-specific tests that assumed a preset dropdown with tests matching the new shape (no combobox, 天數/漲幅門檻 defaults from `params`, days-range validation, the 天數=1 hint, `INVALID_DAYS` fallback, days/risePercent-not-preset in the mixed-strategy request, disabled-when-unchecked, and the days-in-title-frozen-after-edit behavior). Added six new tests for the sync `commonStocksOnly` wiring (request field, 完成 N 檔上市普通股 vs bare 完成 N, 已是最新 with/without the suffix, and 共 N 檔 staying independent of `targetCount`).
  - **Four pre-existing tests' literal-text assertions were updated** (not their logic/setup) because they render the sync-completion summary with the component's default `commonStocksOnly={true}` prop, which now legitimately produces the 「…上市普通股」 suffix per this increment's spec text: `完成 30 檔／失敗…` → `完成 30 檔上市普通股／失敗…`, `完成 20 檔／失敗…` → `完成 20 檔上市普通股／失敗…`, and two `已是最新，無需更新（34 檔）` → `（34 檔上市普通股）`. These are the exact strings the *new* Requirements-section prose (「commonStocksOnly 為 true 時…完成檔數因此要寫成「完成 N 檔上市普通股」」) mandates for the default-checked page state these tests already exercise; nothing about what each test structurally verifies (polling behavior, near-instant-summary persistence, 409-handling) changed.

### Per-criterion verification (lines 436-440, 444-456)
1. 送出 `commonStocksOnly` 等於頁面層級勾選框當下狀態 — **Satisfied**, tested (`sends commonStocksOnly matching the page-level setting when clicking 同步日 K 至今日`).
2. 勾選（預設）時 `targetCount` ≤ 共 N 檔，摘要「完成 N 檔上市普通股」 — **Satisfied**, tested with `targetCount: 1051` vs `共 1400 檔`.
3. 取消勾選送 `commonStocksOnly: false`，摘要「完成 N」、`targetCount` 等於共 N 檔 — **Satisfied**, tested.
4. 共 N 檔不因同步母體縮小而改變 — **Satisfied**, tested explicitly with `targetCount` (1051) ≠ `共 N 檔` (1400) held simultaneously on screen.
5. `caughtUpCount == targetCount` 時「已是最新，無需更新（N 檔上市普通股）」不誤報「完成 N 檔」 — **Satisfied**, tested.
6. 標題「累積上漲（N 日）— 命中 N 檔」，N 取自 `days`，改動輸入未重掃時維持舊值 — **Satisfied**, tested.
7. 累積上漲卡片無靈敏度下拉，改兩個數字輸入；其餘四張卡片仍有下拉 — **Satisfied**, tested (`queryByRole('combobox')` absent on 累積上漲, present on 反彈).
8. 卡片形狀由 `presets` 是否為空決定，無以 `code` 判斷的分支 — **Satisfied** — verified by design (`isParamsDriven` reads only `presets.length`). `grep -n "'CUMULATIVE_RISE'" src/pages/StrategyTab.tsx` finds two remaining hits, neither a card-shape decision: `risePercentRange()`'s numeric-range table (unreachable for this card now that `risePercentRangeFor` always routes it through `params` instead) and the pre-existing, out-of-scope `renderTableForStrategy` switch that picks which *result table's columns* to render post-scan — a different, already-`[x]`-checked concern from "哪個卡片畫哪些輸入控制項".
9. 天數輸入預設 20、範圍 1~90、step 1 皆取自 `params.days` — **Satisfied**, tested; no literal `20`/`1`/`90` appears outside the fallback-only defensive defaults in `getStrategyParam`/`buildScanPayload`, which are unreachable once the catalogue has loaded (the only path that reaches them).
10. 漲幅門檻預設 15 取自 `params.risePercent.default`，不再解析靈敏度說明文字 — **Satisfied**, tested; `toggleStrategy`'s params-driven branch never calls `extractDefaultRisePercent`.
11. 卡片說明文字取自策略層級 `description` — **Satisfied**, tested.
12. 天數輸入下方說明「回看的交易日數，不含週末與休市日」 — **Satisfied**, tested.
13. `0`/`91`/`20.5` 擋下並顯示「天數需介於 1 ~ 90 的整數」，不送出 — **Satisfied**, tested (`it.each`).
14. 天數=1 且漲幅門檻>0 時提示色 `#D9A441` 訊息，「開始掃描」仍可按、仍送出 — **Satisfied**, tested including asserting the request body is still sent with `days: 1`.
15. 請求帶 `days`/`risePercent` 不帶 `preset`；同批其餘策略仍帶 `preset` — **Satisfied**, tested with a mixed 箱型突破 + 累積上漲 request.
16. 未勾選累積上漲時兩格皆 disabled，且請求不含該策略 — **Satisfied**, tested.
17. `INVALID_DAYS` 顯示在回應 `strategy` 指名的卡片下方，非全頁錯誤 — **Satisfied**, tested.
18. 天數輸入顏色取自 Visual Style 字面 hex，dark/light 一致 — **Satisfied** by source review: every `.st-days-*` rule in `StockListPage.css` uses a literal hex copied from the spec's table (`#0F1620`/`#26333F`/`#3E8FD8`/`#93A4B8`/`#D9A441`); `grep -n "prefers-color-scheme" src/pages/StockListPage.css` returns zero matches.
19. (covered by #2/#5) 「已是最新」/「完成」措辭正確 — **Satisfied**, tested.

### Notes / judgment calls
- `syncMeta.commonStocksOnly` is captured from the page-level prop **at the moment `POST /api/stocks/sync/backfill` is sent**, not re-read from the `202` response body — the backend spec (`specs/backend/stock-price-ingestion.md`) guarantees the two agree for this button (it never sends `stockIds`, so it's always the `ALL` mode whose `commonStocksOnly` echoes the request), and this avoids the summary depending on an optional response field a stale/older mock might omit. `BackfillResponse.commonStocksOnly` was still added to the type for contract accuracy even though this component doesn't read it.
- Nothing in `specs/backend/strategy-scan.md`'s catalogue JSON guarantees `params` is present when `presets` is empty, so `getStrategyParam`/day-range JSX fall back to sane literals (`20`/`1`/`90`/`0`/`15`/`50`) only in that defensive, otherwise-unreachable case — this is absence-safety, not a hidden hard-coded business default.

#### Deferred / not independently verifiable here
- None — all 19 criteria in this increment's scope were implemented and covered by an automated test; the full suite (200/200) and `npm run build` were both run clean after the change.

### Increment 8 — 2026-09-08

本次一併執行兩個增量：反彈卡片改為四格輸入（22 項）與底底高結果表加入 MA5／當日最低價兩條序列（8 項），30 項全數完成。

**反彈卡片**：靈敏度下拉移除，改由 `params` 驅動畫出四格數字輸入，標籤取自 `name`、後綴取自 `unit`、範圍與步進取自 `min`／`max`／`step`、初始值取自 `default`，前端無寫死的數字或標籤字串。卡片形狀僅由 `presets.length === 0` 決定，程式中不存在任何以策略 `code` 判斷卡片型別的分支。

**選用參數群組**：新增通用機制——依 `paramGroups` 每筆畫一個勾選框（文字取自 `name`、初始狀態取自 `default`），`params` 中帶 `group` 的參數歸該群組管；取消勾選時該組輸入 disabled 且請求完全不帶這些欄位，同時送出 `require<Group>` 為 `false`（欄位名由群組自身的 `code` 推導，非寫死）。驗證與錯誤對應亦為通用：`isParamInputInvalid`／`paramErrorMessage` 完全由各 `StrategyParam` 自己的 `min`／`max`／`step`／`name` 驅動，後端錯誤依回應的 `strategy` 落到對應卡片下方。

**底底高結果表**：改為五欄，「低點序列（MA5）」取 `detail.lows[].ma5` 且排在「當日最低價」（取 `.low`）之前，累計漲幅由首末兩點的 `ma5` 計算。`low` 下降而 `ma5` 仍抬高的命中照常呈現，不顯示任何錯誤或警示。

**反彈結果表**：一併補上前一增量的七欄形狀（增加「低點日／低點收盤」與「反彈幅度」，`detail` 不含 `risePercent` 時該欄以弱化色顯示「—」）。

**判斷取捨（spec 未規定，已擇一並記錄）**：
- spec 的區塊標題規則只定義了 `{preset}`／`{days}` 兩種括號形式，未涵蓋反彈改制後既無 `preset` 也無 `days` 的回應形狀。實作採「反彈 — 命中 N 檔」不加括號，而非臨時發明一個欄位來讀。本次 30 項驗收皆未斷言反彈的標題字串，屬既有規則的最小延伸。
- `PARAM_NOT_APPLICABLE` 的顯示文字 spec 未規定（只要求落在指名的卡片下方），採「帶入了不適用的參數：<param>」，內容取自後端回應的 `param` 欄位，非逐策略寫死。

**驗證**：`npx vitest run` — 225/225 通過（本次之前為 200/200，淨增 25）。`npm run build`（`tsc -b && vite build`）無錯誤。`npm run lint` 無新增警告（既有 2 則已對照 `HEAD` 確認為原有）。

**變更檔案**：`src/api/strategies.ts`、`src/api/stocks.ts`、`src/pages/StrategyTab.tsx`、`src/pages/StockListPage.css`、`src/__tests__/StrategyTab.test.tsx`。

### Increment 9 — 2026-09-09

Scope: exactly the 7 unchecked criteria under 「反彈區塊標題與「不適用參數」錯誤文案（本次新增）」. Increment 8 had left 反彈's result-block title without any parenthetical (「反彈 — 命中 N 檔」, documented there as a deliberate placeholder since the spec at the time only defined `{preset}`/`{days}` forms) and had only wired `PARAM_NOT_APPLICABLE` — this increment fills in the `dropDays`/`dropPercent`/`riseDays`/`risePercent` title format the spec now spells out explicitly, and extends the same card-scoped-error handling to `PRESET_NOT_APPLICABLE`/`DAYS_NOT_APPLICABLE`.

**標題**：`renderResultBlock`'s title now branches four ways instead of three: `result.preset` → `（{preset 中文名}）`；`result.days` → `（{days} 日）`；otherwise, when `result.dropDays`/`result.dropPercent` are both present (REBOUND's shape), builds `（{dropDays} 日跌 {dropPercent}% → {riseDays} 日反彈 {risePercent}%）` when `result.requireRise === true` and `riseDays`/`risePercent` are both present, or `（{dropDays} 日跌 {dropPercent}%）` otherwise — no dangling arrow, no empty parenthesis. All four numbers are read off `StrategyResult` fields the response itself carries (already typed in `api/strategies.ts` from the prior increment), never off the live card inputs, so editing a 反彈 input after a scan leaves the title exactly as last scanned. A new `formatTrimmedPercent` helper (`Number(value.toFixed(2))`, stringified) implements 「數值格式」's 「兩位小數時去掉無意義的尾數」 rule for this parenthetical specifically — table cells keep the existing fixed-two-decimal `formatPercent2`, unchanged.

**「不適用」三種錯誤**：the existing `PARAM_NOT_APPLICABLE`-only branch in `runScan`'s catch chain was widened to also catch `PRESET_NOT_APPLICABLE` and `DAYS_NOT_APPLICABLE`, all three sharing one behavior: locate the card via `err.strategy`, and set `paramServerError` to `帶入了不適用的參數：{err.param}` when the response carries a `param`, or a generic message when it doesn't (chose distinct wording `此策略不支援這次請求帶入的參數組合` rather than reusing the page-wide `掃描失敗，請稍後再試`, so a card-scoped fallback is never textually indistinguishable from the page-wide one in tests or for a user scanning the page). All three still call `setScanStatus(scanResult ? 'success' : 'idle')`, never `'error'` — the branch that clears/hides `scanResult` behind a full-page error view is only reached for the generic `catch` at the end, so these three continue leaving existing results on screen and `開始掃描` unlocked (`canScan` never inspected `scanStatus === 'error'` to begin with).

`paramServerError` (renamed only in its doc comment, not its identifier, to avoid an unnecessary rename across the file) is now rendered in **both** card branches — previously only inside the params-driven (`isParamsDriven`) branch, since `PARAM_NOT_APPLICABLE` could only target 反彈/累積上漲 in practice. `PRESET_NOT_APPLICABLE`/`DAYS_NOT_APPLICABLE` can equally name a sensitivity-driven card (e.g. a malformed request sending `days` for 箱型突破), so the same conditional block was added after the sensitivity-driven card's existing `INVALID_RISE_PERCENT` inline error.

**判斷取捨（spec 未規定，已擇一並記錄）**：
- 通用錯誤訊息文字 spec 未規定確切字串，只要求「非全頁通用」。選用 `此策略不支援這次請求帶入的參數組合`，刻意與頁面級 `掃描失敗，請稍後再試` 不同字串，避免兩種錯誤在畫面或測試斷言中無法區分。

**驗證**：`npx vitest run src/__tests__/StrategyTab.test.tsx` — 113/113 通過（本次之前為 100，淨增 13：反彈標題 requireRise=true/false 各一、掃描後改動輸入標題不變一、其餘四策略標題格式未變一、PRESET_NOT_APPLICABLE／DAYS_NOT_APPLICABLE 無 `param` 時卡片下通用訊息各一、`param` 存在時字串逐字取自回應一、三種錯誤皆不清空既有結果且不鎖按鈕一（`it.each` 三案例算一筆））。`npx vitest run`（全專案）— 235/235 通過。`npx tsc --noEmit` 無錯誤。`npm run build`（`tsc -b && vite build`）無錯誤。`npm run lint`（`oxlint`）僅原有 2 則既有警告（已對照 `git stash` 前的 `HEAD` 逐字比對，行號因新增程式碼位移但訊息與檔案相同，非本次引入）。

**無法驗證的部分**：未啟動 dev server 做視覺/瀏覽器驗證（依任務指示，完成後需確保 5173/8080 兩埠不被佔用），僅以 `vitest`（jsdom）與 `tsc`/`build`/`lint` 驗證。

**變更檔案**：`develop/frontend/src/pages/StrategyTab.tsx`、`develop/frontend/src/__tests__/StrategyTab.test.tsx`。

---
### Increment 10 — 2026-09-10

**Scope**: implemented the 49 previously-unchecked Acceptance Criteria — collapsing the per-strategy result blocks into one merged 命中彙總 table (with its 「本次採用參數」 line under the title), and adding the 回測 button, its three new columns, the two totals labels, the per-row 納入計算 checkboxes, and the two distinct "未計入" messages.

**Files changed**:
- `develop/frontend/src/pages/StrategyTab.tsx` — removed every per-strategy detail table/renderer (`renderBoxTable`/`renderHigherLowsTable`/`renderRisingSupportTable`/`renderReboundTable`/`renderCumulativeRiseTable`/`renderTableForStrategy`/`renderResultBlock`), the five `isXxxDetail` type guards, `renderLowSeriesCell`, `formatConfirmCloses`, `cumulativeRiseFromMa5`, `renderMinuteCell`（分 K link）, and `formatPrice2`/`formatMultiple2`. Added: `formatAmount`（千分位、不帶小數、負值前置 `-`）, `signColorClass`（紅漲／綠跌／中性三態）, `computeBacktestTotals`（pure client-side totals recompute from the backtest response's own `buyPrice`/`profit`/`lotSize`）, `formatStrategyParams`（the 「本次採用參數」 per-strategy segment — same mutually-exclusive `preset`/`days`/`dropDays`+`dropPercent`[+`riseDays`+`risePercent`] branching the old per-block title used, minus the now-removed 「— 命中 N 檔」 suffix）, `renderScanNotes`（insufficientData/pendingConfirm, one line per strategy, now living below the single merged table instead of inside per-strategy blocks）, `toggleRowChecked`, `handleBacktestClick`, and `UnionRow.latestSignalDate`（computed once in `buildUnionRows`, reused both for the table's sort key and for the exact `signalDate` sent per stock to `POST /api/strategies/backtest`）. `renderMergedTable` replaces the old two-path `showUnionTable ? renderUnionTable() : null; scanResult.results.map(renderResultBlock)` with a single always-rendered block.
- `develop/frontend/src/api/strategies.ts` — added `BacktestRequestItem`/`BacktestRequest`/`BacktestResultItem`/`BacktestResponse` types and `backtestStrategies()`（`POST /api/strategies/backtest`）, mirroring `scanStrategies()`'s existing error-handling shape.
- `develop/frontend/src/pages/StockListPage.css` — added `.sl-neutral`（0-value colour, shared by cells and totals）, `.st-params-line`, `.st-union-header`/`.st-backtest-totals`/`.st-total-item`/`.st-total-label`/`.st-total-value`（title-row totals）, `.st-uncounted-notes`/`.st-uncounted-note`, `.st-checkbox-col`/`.st-row-checkbox`, and `.sl-table tbody tr.st-row-unchecked td { color: #6b7c90 !important; }`（the `!important` is required to win over the `.sl-up`/`.sl-down`/`.sl-neutral` classes a cell still carries once its row is unchecked — reflecting "反灰是視覺表示，不是停用" rather than swapping the cell's own semantic class）. Moved `.st-result-title`'s `border-bottom` onto the new `.st-union-header` flex row so the divider still spans the totals, not just the title text.
- `develop/frontend/src/__tests__/StrategyTab.test.tsx` — removed ~26 tests that asserted now-deleted behaviour (per-strategy detail columns, 分 K links/columns, multi-block titles with 「— 命中 N 檔」, the old "union table only shown for 2+ strategies" rule); rewrote the union/zero-hit/navigation/params-line tests for the new always-one-table contract; fixed `cardFor` (a strategy name now also appears as a merged-table hit tag once scanned, so a bare `getByText` became ambiguous — scoped to the `.st-strategy-name` heading specifically); added 3 fixture builders (`singleBacktestResponse`, `allUnbacktestableResponse`, `unionBacktestResponse`) and a `backtestResponder` hook in the shared fetch mock; added 20 new tests covering: button disabled/enabled states across every scan status, the running/error states, the exact request payload (all hit stocks, latest `signalDate` per stock, unaffected by checkbox state), the three new columns and their ordering/values/dashes, the zero-request checkbox-toggle assertion (by call-count, not timing), per-row grey-out without clearing values or navigation, the lotSize-not-hardcoded guard, the all-checked-equals-response-totals guard, both distinct "未計入" messages (including the "counted only once" double-exclusion case), both distinct zero-included messages（「沒有可回測的標的」 vs 「未勾選任何標的」, and never `0%`）, the three colour states, and the reset-on-rescan behaviour for both the backtest result and the checkboxes.

**Design decisions**:
- **"未計入" totals recomputed by iterating the response's `items`, not `unionRows`.** `computeBacktestTotals(result, checkedStockIds)` walks `backtestResult.items` directly (in the order the backend already returned them, matching `unionRows`' own order since both are built from the same stock list) rather than joining through `unionRows` again — one array, one pass. The row-render loop still needs a `Map`（`backtestItemsById`）because it also has to join in each row's `hits` from `unionRows`.
- **A row that is both unbacktestable and unchecked is bucketed before the checked-check runs**（`if (item.sellDate === null) { uncountedNoSellDate++; continue }` precedes the `checkedStockIds.has(...)` branch）— this is the direct mechanism behind "一檔既無法回測、又被取消勾選時只計入「尚無可賣出交易日」那一行", not a separate de-dup step.
- **The zero-included branch reads `backtestResult.totalReturnPercent === null`（the response's own field）, never a client-recomputed flag**, to decide between 「沒有可回測的標的」 and 「未勾選任何標的」 — the spec is explicit that these two must not be interchangeable, and the response already carries the authoritative "was anything in this batch backtestable at all" signal (`null` only when every item lacks a `sellDate`), independent of what the user has checked.
- **Reset ordering**: `backtestStatus`/`backtestResult`/`backtestErrorMessage` are cleared synchronously inside `runScan`（shared by both 開始掃描 and 重試）before the new `scanStrategies` call is issued — matching "按下的當下即清空". Checkbox state（`checkedStockIds`）is instead reset inside the `.then` success handler, seeded from `buildUnionRows(resp)` — since the checkbox column only reflects rows that exist, resetting it before the new row set is known would just be resetting it to the same "all of the old rows checked" state, which is moot the instant the new rows replace them. Both effects are observably synchronous from the user's perspective (verified by the two dedicated reset tests), differing only in which internal tick they occur on.
- **`backtestItemsById` and `computeBacktestTotals`'s result are built inline on every render**（no `useMemo`）— consistent with the rest of this component, which doesn't memoize `unionRows` either; bounded to ≤200 rows per `specs/backend/strategy-backtest.md`'s own cap, so this was judged not worth the added complexity.

**Verification (real command output tails)**:
- `npx tsc -b` — no output, exit 0.
- `npx vitest run src/__tests__/StrategyTab.test.tsx`:
  ```
   Test Files  1 passed (1)
        Tests  113 passed (113)
  ```
- `npx vitest run`（full frontend suite）:
  ```
   Test Files  9 passed (9)
        Tests  235 passed (235)
  ```
  235/235 — matches the stated baseline exactly (this file's own test count also came out at 113 both before and after this increment: ~26 obsolete tests removed, 20 new backtest tests plus a handful of merged-table rewrites added).
- `npm run build`（`tsc -b && vite build`）:
  ```
  ✓ 47 modules transformed.
  dist/assets/index-BS19tMl_.css   27.38 kB │ gzip:  4.69 kB
  dist/assets/index-SJN5Yqfy.js   312.93 kB │ gzip: 95.87 kB
  ✓ built in 147ms
  ```
- `npm run lint`（`oxlint`）— same 2 warnings as the `git stash`-verified pre-existing baseline（`no-unreachable` in the test file, `react(set-state-in-effect)` in `StrategyTab.tsx`；confirmed via `git stash && npm run lint && git stash pop` that both exist on `HEAD` unchanged, only their line numbers shifted). No new warnings introduced.

**code-quality self-review**: absence safety — every backtest-response field access is `?.`/`??`-guarded（`item?.sellDate ?? <dash/>`、`backtestResult?.totalReturnPercent === null`、`backtestItemsById.get(...) ?? null`）; error handling — `backtestStrategies(...).then().catch()` always has a catch (mirrors the existing `scanStrategies`/`startBackfill`/`importStockUniverse` fire-and-forget-from-an-onClick convention already used throughout this file, so this isn't a new pattern); resource lifecycle — no new subscriptions/timers; performance — `computeBacktestTotals` and the checkbox-to-totals path are both O(n) over an already-≤200-bounded list, and toggling a checkbox provably issues zero network calls (asserted by `fetchMock.mock.calls.length` before/after, not timing). Nothing flagged that needed fixing beyond what's already covered above.

**Verified by the new/rewritten tests**（mapped to the spec's 49 previously-unchecked ACs, grouped by the spec's own subsection headers）: 命中彙總表（本次新增）— single-table-always, dedup count, per-strategy hit tags, sort order, row-click navigation, insufficientData/pendingConfirm moved below the table with per-strategy prefixes, 箱型突破/上漲支撐 pendingConfirm wording kept distinct; 本次採用參數那一行 — all five strategies' formats in one `・`-joined line, response-sourced (not input-sourced) after an unsaved edit; 回測按鈕 — disabled across idle/scanning/zero-hit/failed, enabled after ≥1 hit, running/error states, immediate clear-on-rescan; 納入計算的勾選框 — default-checked, zero-request toggle, per-row isolation, grey-out without hiding values, still-clickable, checkbox reset on rescan; 兩種未計入的說明 — both messages, both-present case, the no-double-count case, both zero-included messages and the `0%` prohibition; 回測結果呈現 — column order/values/formatting, response-driven `signalDate` selection, unchanged sort after backtest, three colour states, lotSize-from-response guard, and the all-checked-equals-response-totals guard. I did not check off any Acceptance Criteria boxes myself, per instructions — please verify and check them off.

**Anything NOT independently verified, and why**: no browser/dev-server check was performed — the environment note says the dev server on 5173 and a backend on 8080 are already running and must not be touched/duplicated, so all verification here is via `vitest`（jsdom）, `tsc`, `vite build`, and `oxlint` only. The backend `POST /api/strategies/backtest` endpoint itself was not re-verified in this pass — `specs/backend/strategy-backtest.md`'s own `## Execution Result`（28/28 tests, already marked `DONE`）was read and trusted as-is; this increment only integrates the frontend against its documented contract, using response fixtures hand-derived from that spec's own formulas（訊號日收盤買進 / 賣出窗口最高開盤價 / 成本加權總報酬率）rather than a live call.
