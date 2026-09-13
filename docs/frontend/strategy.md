# 策略型態掃描與回測

從只有種子股票的初始狀態，走過「更新股票清單 → 同步日 K → 勾選五個策略掃描並自動回測 → 展開一檔的多個訊號日 → 取消其中一筆重算總計 → 勾選「取消全選」」的完整流程。

![storyboard](strategy/storyboard.png)

放大瀏覽：[瀏覽器開啟 (storyboard.html)](strategy/storyboard.html) ・ [PDF](strategy/storyboard.pdf)

## 呼叫的後端 API

分鏡上每一格已標出該步驟送出的請求，這裡是同一份清單的文字版，一列一個呼叫。

| 步驟 | 觸發 | 後端 API | 用途 | 契約 |
|---|---|---|---|---|
| 1 | 進頁載入 | `GET /api/strategies` | 取策略清單、靈敏度選項與說明文字，以及反彈／累積上漲的參數定義 | [strategy-scan](../backend/strategy-scan.md) |
| 1 | 進頁載入 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 取 `lastSyncedAt` 顯示最後同步時間 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 1 | 進頁載入 | `GET /api/stocks?page=1&size=1` | 只取 `total` 顯示常駐的「共 N 檔」 | [stock-catalog](../backend/stock-catalog.md) |
| 2 | 按「更新股票清單」 | `POST /api/stocks/universe/import` | **此端點即是向交易所抓取上市股票清單的起點**，同時匯入官方產業別 | [stock-universe-import](../backend/stock-universe-import.md) |
| 2 | 匯入完成 | `GET /api/stocks?page=1&size=1` | 重取 `total` 更新「共 N 檔」 | [stock-catalog](../backend/stock-catalog.md) |
| 3 | 按「同步日 K 至今日」 | `POST /api/stocks/sync/backfill` | **此端點即是抓取歷史日 K 的起點**；全市場模式逐交易日各向交易所取一次當日全市場快照 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 3 | 同步執行中（每 5 秒） | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 輪詢進度，更新已完成檔數 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 4 | 同步完成 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 重取 `lastSyncedAt` | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 5 | 按「開始掃描」 | `POST /api/strategies/scan` | 一次送入五個策略，回應組成單一命中彙總表與標題下的採用參數那一行 | [strategy-scan](../backend/strategy-scan.md) |
| 5 | 掃描成功且命中 ≥ 1 檔時**自動送出**（無按鈕） | `POST /api/strategies/backtest` | 對命中清單**逐買進日**算買賣結果（一檔有幾個相異買進日就送幾筆；上漲支撐的買進日為確認完成日 D+2）；失敗時「重試回測」以同一份內容重送 | [strategy-backtest](../backend/strategy-backtest.md) |
| 6 | 自動回測完成 | —（不發請求） | 第 5 步的回測回應抵達，填入表格右側六欄與標題右側兩個標籤 | [strategy-backtest](../backend/strategy-backtest.md) |
| 7 | 點展開鈕 | —（不發請求） | 展開狀態純前端；該檔各訊號日的數字在第 6 步的回應裡已經全部拿到了 | [strategy-backtest](../backend/strategy-backtest.md) |
| 8 | 點選某一筆的列（等同點其勾選框） | —（不發請求） | 勾選狀態純前端，父列合計與標題總計就地重算；回應已含每筆 `buyPrice`／`profit` 與 `lotSize`，不需再打一次端點 | [strategy-backtest](../backend/strategy-backtest.md) |
| 9 | 勾選「取消全選」 | —（不發請求） | 一次取消全部筆的勾選，父列合計與標題總計就地重算為「—」；勾選狀態純前端 | [strategy-backtest](../backend/strategy-backtest.md) |

本流程**不呼叫**任何指標運算、分 K 或漲幅排行端點——掃描與回測讀的都是同步流程已寫入的日線，指標、分 K 與產業別漲幅由各自的頁面負責。整張命中彙總表由第 5 步那一次 `POST /api/strategies/scan` 的回應組成，前端不再為每個策略各發一次請求；回測是另一支獨立端點，掃描本身不附帶回測結果，而是第 5 步掃描成功後由前端自動接著送出；第 7～9 步的展開、勾選切換與「取消全選」則完全不觸網——一檔的每個訊號日在第 6 步就已各自回了一筆，這是回測回應保留逐筆 `buyPrice` 與 `lotSize` 的用途。第 2 步匯入的產業別本頁自己不用，它是給[動態分頁](momentum.md)分組顯示用的。
