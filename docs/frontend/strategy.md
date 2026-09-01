# 策略型態掃描

從只有種子股票的初始狀態，走過「更新股票清單 → 同步日 K → 勾選策略掃描 → 再同步確認已是最新」的完整流程。

![storyboard](strategy/storyboard.png)

放大瀏覽：[瀏覽器開啟 (storyboard.html)](strategy/storyboard.html) ・ [PDF](strategy/storyboard.pdf)

## 呼叫的後端 API

分鏡上每一格已標註該步驟送出的請求，以下為同一份清單的表格版本。

| 步驟 | 觸發 | 後端 API | 用途 | 契約 |
|---|---|---|---|---|
| 1 | 進入策略分頁 | `GET /api/strategies` | 取策略清單與三段靈敏度說明文字，前端不寫死 | [strategy-scan](../backend/strategy-scan.md) |
| 1 | 進入策略分頁 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 取 `lastSyncedAt` 顯示最後同步時間 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 1 | 進入策略分頁 | `GET /api/stocks?page=1&size=1` | 只取 `total` 顯示常駐的「共 N 檔」 | [stock-catalog](../backend/stock-catalog.md) |
| 2 | 點「更新股票清單」 | `POST /api/stocks/universe/import` | **此端點即是向交易所抓取上市股票清單的起點，同一次呼叫也一併匯入官方產業別**；回應的 `totalActiveCount`／`insertedCount`／`updatedCount`／`industryCount`／`uncategorizedStockCount` 組成「共 N 檔（新增 X、更新 Y）・產業別 P 類，未分類 Q 檔」摘要 | [stock-universe-import](../backend/stock-universe-import.md) |
| 2 | 匯入完成後 | `GET /api/stocks?page=1&size=1` | 重取 `total` 更新常駐檔數 | [stock-catalog](../backend/stock-catalog.md) |
| 3 | 點「同步日 K 至今日」 | `POST /api/stocks/sync/backfill` | **此端點即是向外部來源抓取日線的起點**；不帶 `stockIds` 代表全市場 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 3 | 同步進行中 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 每 5 秒輪詢更新進度條 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 4 | 同步完成 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 重取 `lastSyncedAt` 與完成／失敗／略過筆數 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 5 | 點「開始掃描」 | `POST /api/strategies/scan` | 以三個勾選的策略與各自靈敏度即時掃描，回傳各策略命中清單、資料不足與待確認標的 | [strategy-scan](../backend/strategy-scan.md) |
| 6 | 再點「同步日 K 至今日」 | `POST /api/stocks/sync/backfill` | 全數已補齊時回應 `caughtUpCount` 等於 `targetCount`，本次一檔都不抓取 | [stock-price-ingestion](../backend/stock-price-ingestion.md) |
| 6 | 同步結束 | `GET /api/stocks/sync/progress?jobType=PRICE_BACKFILL` | 取 `lastSyncedAt`，時間為 Asia/Taipei | [stock-price-ingestion](../backend/stock-price-ingestion.md) |

本流程**不呼叫**任何指標運算、分 K 或漲幅排行端點——掃描讀的是同步流程已寫入的日線，指標、分 K 與產業別漲幅由各自的頁面負責。聯集表格與三張策略結果表全部由第 5 步那一次 `POST /api/strategies/scan` 的回應組成，前端不再為每個策略各發一次請求。第 2 步匯入的產業別本頁自己不用，它是給[動態分頁](momentum.md)分組顯示用的——匯入入口只在這裡有一處，動態分頁上刻意沒有同功能的按鈕。
