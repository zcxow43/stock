# 策略型態掃描

走一遍「先把行情同步到今日，再勾選型態掃描全市場」的完整流程，從尚未同步的空狀態到兩個策略各自的命中清單。

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
| 4 | 點「開始掃描」 | `POST /api/strategies/scan` | 即時運算兩個策略的命中清單，不落地任何結果 | [strategy-scan](../backend/strategy-scan.md) |

本流程**不呼叫**指標統計端點——策略判定直接讀日線 OHLCV，與 MACD／KD 無關。切「指定股票」時會另外打 `GET /api/stocks` 取候選清單，此分鏡走的是全市場路徑，故未出現。
