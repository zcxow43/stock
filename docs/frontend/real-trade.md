# 真實交易

從空的真實交易分頁開始，加入兩筆實際持有的部位、再刪掉其中一筆，看未實現損益與三個總計怎麼跟著變。

![storyboard](real-trade/storyboard.png)

放大瀏覽：[瀏覽器開啟 (storyboard.html)](real-trade/storyboard.html) ・ [PDF](real-trade/storyboard.pdf)

## 呼叫的後端 API

分鏡上每一格已標出該步驟送出的請求，這裡是同一份清單的文字版，一列一個呼叫。

| 步驟 | 觸發 | 後端 API | 用途 | 契約 |
|---|---|---|---|---|
| 1 | 切到「真實交易」頁籤 | `GET /api/real-trades` | 取整份清單、三個總計、費率與 `skippedLines`；此時全為空，畫面顯示三個「—」。回應的 `asOfDate` 同時是買進日輸入的上限 | [real-trade](../backend/real-trade.md) |
| 1 | 同上（頁面層級，非本分頁） | `GET /api/stocks?page=1&size=1` | `/stocks` 頁首列常駐的「共 N 檔」，由頁面容器發出，與本分頁的持股無關 | [stock-catalog](../backend/stock-catalog.md) |
| 2 | 填完四格輸入 | — | 純前端：四格都有值時「加入」才由 disabled 轉為可按，不送任何請求 | — |
| 3 | 按「加入」 | `POST /api/real-trades` | 以 `{ stockId, buyDate, buyPrice, shares }` 四欄全帶建立一筆，回 201 | [real-trade](../backend/real-trade.md) |
| 3 | 加入成功後 | `GET /api/real-trades` | 重新取得列表——排序、三個總計與各筆 `id` 一律由後端決定，不就地改動畫面上的陣列 | [real-trade](../backend/real-trade.md) |
| 4 | 在輸入列按 Enter | `POST /api/real-trades` | 與按「加入」同一支端點；同檔同日可以有多筆，不做唯一性檢查 | [real-trade](../backend/real-trade.md) |
| 4 | 加入成功後 | `GET /api/real-trades` | 同上；新寫入的是檔案第 2 筆資料行（`id` 2），但依買進日由新到舊排在畫面第一列 | [real-trade](../backend/real-trade.md) |
| 5 | 按某一列的「刪除」 | `DELETE /api/real-trades/1` | 不彈確認對話框直接送出，`id` 是該列在資料檔中的**資料行序號**（不是畫面上的第幾列），成功回 204。舊頁面放久了才按、`id` 已超出當下行數時回 404 `REAL_TRADE_NOT_FOUND` | [real-trade](../backend/real-trade.md) |
| 5 | 刪除成功後 | `GET /api/real-trades` | 重新取得列表，其餘各筆的 `id` 依新回應重新編號 | [real-trade](../backend/real-trade.md) |

本流程**不呼叫** `GET /api/simulated-trades`——那是另一份資料，兩個分頁的數字永遠各算各的。也**不呼叫**任何逐檔查詢端點（`GET /api/stocks/{stockId}`）：股票名稱、現價日與現價都由 `GET /api/real-trades` 自己在後端算好一起回來。本頁沒有任何上傳、圖片解析或匯入入口，持股資料進入後端資料檔的另一條路徑發生在程式之外。
