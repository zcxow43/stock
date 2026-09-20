---
status: done
title: "模擬交易持股表 simulated_trade"
requirement: "模擬交易分頁 — 使用者只輸入股票代號即建立一筆模擬持股，買進日固定為今日以前最後一個有收盤價的交易日、買進價為該日收盤、部位固定 1 張；資料需長期保存，重新整理或換瀏覽器都還在"
---

# 模擬交易持股表 simulated_trade — DBA Spec

## Overview

一列代表「使用者在模擬交易分頁建立的一筆持股」。寫入與讀取端都是 `specs/backend/simulated-trade.md`，畫面是 `specs/frontend/simulated-trade.md`。未實現損益不存在本表——它隨最新收盤價每天改變，存下來就會是一個過期的數字（理由見下方「只存建立當下的事實」）。

**本 migration 為 V017，接在 V016 之後。** 本表不引用任何其他表（無外鍵，理由見下），因此不受其他 migration 的建立順序約束。

## Requirements

### 只存建立當下的事實

本表只存三件在建立那一刻就決定、之後不該再變的事：**哪一檔、哪一天買的、買在什麼價**（以及固定的股數）。

- **未實現損益、報酬率、現價、手續費與證交稅都不存**：它們是「這筆持股 + 今天的收盤價」算出來的，每個交易日都不一樣。存進表裡就必須有人負責每天更新，而漏更新的那一天，畫面上會出現一個看起來精確、實際上是昨天的數字。讀取時即時算，成本只是一次收盤價查詢。
- **買進價要存，不能每次查行情表重算**：`stock_daily_price` 的同一天收盤價可能被來源事後更正（見 `specs/backend/stock-price-ingestion.md` 的 UPSERT 語意）。使用者建立的部位是「我當時以這個價格買進」，不該因為行情被更正而悄悄改成另一個成本。存下來，畫面上的數字才對得起使用者自己記下的那一筆。
- **股數也存**：目前一律 `1000`（1 張，與 `specs/backend/strategy-backtest.md` 的部位約定相同），但它是一筆持股的性質而不是全系統常數；存進去，將來開放輸入張數時舊資料仍然說得出自己是幾股。

### 不設外鍵

`stock_id` 不對 `stock`（見 `specs/dba/stock.md`）建外鍵，與 `specs/dba/stock_institutional_trade` 同一個理由，另加一條本表特有的：**股票下市或從主檔被刪掉時，使用者的模擬紀錄不該跟著消失**。代號是否存在由寫入端在建立時檢查一次（見 `specs/backend/simulated-trade.md`），那是「輸入是否有效」的問題，不是資料庫該連動刪除的關係。

### 同一檔同一買進日只能有一筆

`(stock_id, buy_date)` 唯一。使用者只輸入代號、買進日與買進價都由系統決定，所以同一天把同一檔加兩次，產生的會是兩列完全相同的資料——那不是兩個部位，是一次重複點擊。以唯一鍵擋在資料庫層，寫入端才有辦法把它回報成一個明確的錯誤而不是靜默地多一列。

## Implementation Details

### Migration SQL — V017__create_simulated_trade.sql

```sql
CREATE TABLE simulated_trade (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '流水號，供刪除單筆使用',
    stock_id   VARCHAR(10)     NOT NULL COMMENT '股票代號，如 2330',
    buy_date   DATE            NOT NULL COMMENT '買進日＝建立當下今日以前最後一個有收盤價的交易日',
    buy_price  DECIMAL(10,2)   NOT NULL COMMENT '買進價＝買進日收盤價，建立時寫入後不再隨行情更正而變動',
    shares     INT UNSIGNED    NOT NULL COMMENT '股數，目前固定 1000（1 張）',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_buy_date (stock_id, buy_date)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='模擬交易持股（使用者自行加入，未實現損益即時計算不落地）';
```

`buy_price` 用 `DECIMAL(10,2)`，與 `stock_daily_price` 的價格欄位同型別同精度——它就是從那裡抄來的一個值，型別不一致會在比對與顯示時產生看不出來的捨入差。

### 索引說明

- `PRIMARY KEY (id)`：刪除單筆的存取路徑。列數是使用者手動加入的量級（數十到數百），列表查詢直接全表掃描即可，不需要其他索引。
- `UNIQUE KEY uk_stock_buy_date`：同時是「同一檔同一買進日只能有一筆」的約束與重複檢查的索引。

### 寫入語意

- 新增以 `INSERT` 寫入，**不得使用 UPSERT**：重複的 `(stock_id, buy_date)` 必須讓寫入失敗，由寫入端回報成錯誤。UPSERT 會把第二次加入變成無聲地覆蓋，使用者看不出自己剛才什麼都沒有新增。
- 刪除以 `id` 刪一列，**硬刪除**，不做軟刪除旗標：這是使用者自己加進來、也自己拿掉的暫時資料，留著墓碑只會讓列表查詢每次都得多一個條件。
- `buy_price` 與 `shares` 建立後不更新；本表目前沒有任何更新路徑（`updated_at` 仍保留，與其他表一致）。

## Acceptance Criteria

- [x] `simulated_trade` 表存在，欄位、型別、`NOT NULL`、預設值與註解與上方 SQL 完全一致
- [x] `PRIMARY KEY (id)` 為自增，`UNIQUE KEY uk_stock_buy_date (stock_id, buy_date)` 存在
- [x] 插入兩列相同 `(stock_id, buy_date)` 時第二列失敗（`Duplicate entry`），同一檔不同 `buy_date` 可各自插入
- [x] `buy_price` 為 `DECIMAL(10,2)`：寫入 `1150.005` 之類的值不會被存成更高精度，與 `stock_daily_price.close_price` 同型別
- [x] 表上沒有任何外鍵：刪除 `stock` 中對應代號的列後，本表的列仍在
- [x] 以 `id` 刪除一列後該列消失，其餘列不受影響

---
## Execution Result
- Status: DONE — 2026-09-20

在 live 資料庫（`127.0.0.1:3306/stock`，MySQL 8.0.27，帳號 `app`）套用 V017（`CREATE TABLE simulated_trade ...`，SQL 與上方 Migration SQL 區塊逐字相同），一次成功、無錯誤。套用前以 `SHOW TABLES LIKE 'simulated_trade'` 確認表原本不存在。

**`SHOW CREATE TABLE simulated_trade` 實際輸出：**

```sql
CREATE TABLE `simulated_trade` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '流水號，供刪除單筆使用',
  `stock_id` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '股票代號，如 2330',
  `buy_date` date NOT NULL COMMENT '買進日＝建立當下今日以前最後一個有收盤價的交易日',
  `buy_price` decimal(10,2) NOT NULL COMMENT '買進價＝買進日收盤價，建立時寫入後不再隨行情更正而變動',
  `shares` int unsigned NOT NULL COMMENT '股數，目前固定 1000（1 張）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_buy_date` (`stock_id`,`buy_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模擬交易持股（使用者自行加入，未實現損益即時計算不落地）'
```

逐欄比對 `information_schema.COLUMNS`（`COLUMN_TYPE`／`IS_NULLABLE`／`COLUMN_DEFAULT`／`EXTRA`／`COLUMN_COMMENT`）與 spec SQL 完全一致。另以 `HEX(COLUMN_COMMENT)` 比對 `stock_id` 欄位註解的位元組序列（`E882A1E7A5A8E4BBA3E8999FEFBC8CE5A6822032333330`）與該中文字串的預期 UTF-8 編碼相符，確認客戶端未發生字元集損壞（本次所有 `mysql` 呼叫皆帶 `--default-character-set=utf8mb4`）。

**驗證過程（逐條對應 Acceptance Criteria）：**

1. **欄位/型別/NOT NULL/預設值/註解**：如上，`SHOW CREATE TABLE` 與 `information_schema.COLUMNS` 均與 spec SQL 逐字一致。
2. **PK 自增 + UNIQUE KEY**：`PRIMARY KEY (id)` 帶 `AUTO_INCREMENT`（`EXTRA` 欄位確認）；`UNIQUE KEY uk_stock_buy_date (stock_id, buy_date)` 存在於 `SHOW CREATE TABLE` 輸出。
3. **重複 `(stock_id, buy_date)` 拒絕**：插入 `('2330','2026-09-18',1150.005,1000)` 成功後，再插入 `('2330','2026-09-18',1160.00,1000)` 得到 `ERROR 1062 (23000): Duplicate entry '2330-2026-09-18' for key 'simulated_trade.uk_stock_buy_date'`；改插 `('2330','2026-09-17',1140.00,1000)`（同檔不同 `buy_date`）成功寫入。
4. **`DECIMAL(10,2)` 精度**：寫入 `1150.005` 被存成 `1150.01`（四捨五入至二位小數，未以更高精度存放），型別與 `stock_daily_price.close_price` 相同（`DECIMAL(10,2)`，見 `specs/dba/stock-daily-price.md`）。
5. **無外鍵**：`information_schema.TABLE_CONSTRAINTS`／`REFERENTIAL_CONSTRAINTS` 對 `simulated_trade` 查無任何 `FOREIGN KEY` 約束。以交易包住的實測：`START TRANSACTION; DELETE FROM stock WHERE stock_id='2330';` 成功執行（無 `RESTRICT` 報錯），刪除後 `simulated_trade` 中代號 `2330` 的兩列仍在（無 `CASCADE`），確認後 `ROLLBACK` 還原 `stock` 表，未對正式資料造成任何影響。
6. **以 `id` 刪除**：`DELETE FROM simulated_trade WHERE id = 1` 後該列消失，其餘列（`id=3`）不受影響。

**收尾**：所有驗證用列（`id=1` 已於步驟 6 刪除，其餘由 `DELETE FROM simulated_trade;` 清空）皆已移除，`SELECT COUNT(*) FROM simulated_trade` 確認為 `0`，表已清空。

**`schema_migration` 登錄**：依 `specs/dba/schema-migration.md`，該表只用來為「相對位移類」的 DML migration 把關（例如 `SET t = t + INTERVAL 8 HOUR` 這種一跑多次會累加錯誤的語句），純 DDL（`CREATE TABLE`）明確排除在外（"冪等的 DDL（`CREATE TABLE IF NOT EXISTS`、`ALTER ... MODIFY` 註解）...不需要登錄，不必為了形式一致而全部塞進來"）。V017 是一次性的 `CREATE TABLE`，本身冪等地由 `information_schema` 可自證是否已套用，因此**未**在 `schema_migration` 插入 `V017` 這一列，也确认目前這台 live 資料庫的 `schema_migration` 仍為空表（與 `specs/dba/schema-migration.md` Increment 2 所記錄的狀態一致——V010–V012 的位移類 migration 因缺 `Applied-when` 探針而未被判定套用，未在本次任務範圍內處理）。
