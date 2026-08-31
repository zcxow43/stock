# 股票瀏覽：總覽 → 日 K → 分 K

從股票總覽搜尋標的、點進日 K（蠟燭圖），再連點兩下鑽進單一交易日的分 K 現價流線圖，走完一次完整的瀏覽路徑。兩層主圖型態不同是刻意的：日線看每天的開高低收，日內看的是價格往哪走。總覽頁另有「總覽／策略」兩個頁籤與每列的維護操作。

![storyboard](stock-chart/storyboard.png)

放大瀏覽：[瀏覽器開啟 (storyboard.html)](stock-chart/storyboard.html) ・ [PDF](stock-chart/storyboard.pdf)

## 呼叫的後端 API

分鏡上每一步都標了它打的端點，以下是同一份清單的文字版。

| 步驟 | 觸發 | 後端 API | 用途 | 契約 |
|---|---|---|---|---|
| 1 | 進頁 | `GET /api/stocks?page=1&size=50` | 載入第 1 頁清單 | [stock-catalog](../backend/stock-catalog.md) |
| 2 | 搜尋框輸入「台積」 | `GET /api/stocks?keyword=台積&page=1&size=50` | 以關鍵字重新查詢 | [stock-catalog](../backend/stock-catalog.md) |
| 3 | 點擊 2330 該列 | `GET /api/stocks/2330` | 取頁首名稱、市場、最新收盤與漲跌 | [stock-catalog](../backend/stock-catalog.md) |
| 3 | 點擊 2330 該列 | `GET /api/stocks/statistics?stockIds=2330&includeSeries=true` | 取日 K 序列與 MACD／KD | [stock-indicator-statistics](../backend/stock-indicator-statistics.md) |
| 4 | 連點兩下 08/25 K 棒 | `GET /api/stocks/2330/minute-bars?tradeDate=2025-08-25&interval=1` | **首次呼叫即向外部來源抓取該日分 K**（隨選抓取） | [stock-minute-price](../backend/stock-minute-price.md) |
| 4 | 連點兩下 08/25 K 棒 | `GET /api/stocks/statistics?stockIds=2330&includeSeries=true` | 僅為取得相鄰交易日，供前／後日按鈕 | [stock-indicator-statistics](../backend/stock-indicator-statistics.md) |
| 5 | 點「5 分」 | `GET /api/stocks/2330/minute-bars?tradeDate=2025-08-25&interval=5` | 已抓過的日期直接讀庫，不再外抓 | [stock-minute-price](../backend/stock-minute-price.md) |

本流程**不呼叫**任何寫入端點。總覽頁的新增／修改／下市（`POST` / `PUT` / `DELETE /api/stocks`）與策略分頁的掃描不在此分鏡的路徑上，前者的契約見 [stock-catalog](../backend/stock-catalog.md)，後者見 [策略型態掃描分鏡](strategy.md)。
