# 策略型態掃描

走一遍「先把行情同步到今日，再勾選型態掃描全市場」的完整流程，從尚未同步的空狀態，到聯集的命中彙總與兩個策略各自的命中清單，最後示範再按一次同步時「已是最新」的呈現。

![storyboard](strategy/storyboard.png)

放大瀏覽：[瀏覽器開啟 (storyboard.html)](strategy/storyboard.html) ・ [PDF](strategy/storyboard.pdf)

## 呼叫的後端 API

分鏡上每一步都標了它打的端點，以下是同一份清單的文字版。

| 步驟 | 觸發 | 後端 API | 用途 | 契約 |
|---|---|---|---|---|
| 1 | 進入策略分頁 | `GET /api/strategies` | 取策略清單與三段靈敏度的說明文字，前端不寫死 | [strategy-scan](../backend/strategy-scan.md) |
| 1 | 進入策略分頁 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 取 `lastSyncedAt` 顯示最後同步時間 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 2 | 點「同步日 K 至今日」 | `POST /api/stocks/sync/backfill` | 以 `catchUp=true` 把全市場補到今日；已補到今日的股票完全跳過 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 2 | 同步執行中（每 5 秒） | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 輪詢進度更新「18 / 34」 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 3 | 同步完成 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 重取 `lastSyncedAt` 更新最後同步時間 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 4 | 點「開始掃描」 | `POST /api/strategies/scan` | 即時運算兩個策略的命中清單，不落地任何結果；最上方的聯集表格由這份回應在前端衍生，不另外呼叫端點 | [strategy-scan](../backend/strategy-scan.md) |
| 5 | 再點一次「同步日 K 至今日」 | `POST /api/stocks/sync/backfill` | 回應的 `caughtUpCount` 等於 `targetCount`，代表 34 檔全部已是最新、本次一檔都沒抓 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 5 | 同上 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 取 `lastSyncedAt`；其值為 Asia/Taipei 時間，前端不再做時區換算 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |

本流程**不呼叫**指標統計端點——策略判定直接讀日線 OHLCV，與 MACD／KD 無關。聯集表格（命中彙總）也**不呼叫任何額外端點**：它是把同一份掃描回應中各策略的 `items` 依 `stockId` 去重合併而成，純前端衍生。切「指定股票」時會另外打 `GET /api/stocks` 取候選清單，此分鏡走的是全市場路徑，故未出現。

第 2 步與第 5 步打的是同一個端點，差別只在標的是否已補齊：第 2 步 34 檔全部要抓，逐檔受速率限制，因此看得到進度；第 5 步 `caughtUpCount` 等於 `targetCount`，一次外部請求都不發，作業在毫秒內結束。這也是為什麼摘要必須分辨這兩種情況——否則一次「什麼都沒做」的同步會顯示成剛完成了 34 檔。
