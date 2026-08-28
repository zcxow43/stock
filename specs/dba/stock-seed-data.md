---
status: done
title: "上市股票開發種子資料"
requirement: "產生一些上市股票資料，讓股票總覽的搜尋列可以搜到資料"
---

# 上市股票開發種子資料

## 需求

- 提供一批常見上市（`TSE`）股票，讓空白開發資料庫也能測試股票代號與中文名稱搜尋。
- 種子腳本必須可重複執行，不得因主鍵重複而失敗或產生重複資料。
- 中文名稱以 `utf8mb4` 寫入，避免終端機預設字元集造成亂碼。
- 種子資料只建立股票主檔，不虛構每日成交價格；尚無行情的股票由既有 API 回傳 `null` 行情欄位。

## 實作

- SQL：`docker/seed-listed-stocks.sql`
- 筆數：34 檔
- 市場：全部為 `TSE`
- 狀態：全部為交易中（`is_active = 1`）
- 寫入方式：`INSERT ... ON DUPLICATE KEY UPDATE`

## 驗收條件

- [x] 搜尋 `2330` 可找到台積電。
- [x] 搜尋 `台積` 可找到台積電。
- [x] 搜尋 `鴻海`、`聯發科`、`中華電` 等名稱可找到對應股票。
- [x] 重複執行腳本不會增加重複股票。
- [x] 所有種子股票的 `market` 均為 `TSE`、`is_active` 均為 `1`。

## 載入方式

在專案根目錄執行：

```bash
docker exec -i stock-mysql mysql --default-character-set=utf8mb4 -uapp -p1234 stock < docker/seed-listed-stocks.sql
```
