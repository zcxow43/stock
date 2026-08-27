---
status: pending
title: "股票分鐘 K 線表 stock_minute_price"
requirement: "前端 K 線瀏覽 — 點選日 K 的某一天後需展示當日分 K，需要一張獨立於日線的分鐘級行情表"
---

# 股票分鐘 K 線表 stock_minute_price — DBA Spec

## Overview

儲存個股在單一交易日內的**每分鐘 OHLCV**，供前端分 K 圖表讀取。

`specs/dba/stock-daily-price.md` 已明訂「日線與分線必須是不同的表」，不可用單一表加 `period` 欄位混存——本表即該規定所指的分線表。日線是收斂後的事實（每檔每日一列），分線是每檔每日約 271 列的高頻序列，兩者的資料量級、寫入時機、保存週期完全不同。

**本表只存 1 分鐘 K 棒。** 5／15／30／60 分 K 一律由 1 分 K 於查詢時聚合產生（見 `specs/backend/stock-minute-price.md`），不另外落地。理由：粗週期完全可由細週期推導，落地多份等同儲存同一事實的多個副本，且任一份修正時必須同步全部——這是重複資料而非快取。

**本 migration 為 V005，在 V004（`stock_sync_progress`）之後。**

## Requirements

### 資料範圍

- 一列代表「一檔股票 × 一個交易日 × 一個分鐘 K 棒」。
- 僅存台股正常交易時段 **09:00–13:30** 的分鐘 K 棒（含 13:30 收盤那一根，單日至多 271 根）。盤前試撮與盤後定價交易不寫入本表。
- 價格與日線一致，一律為**原始成交價（未經除權息還原）**。全系統對此的處理必須一致，否則同一檔的日 K 與分 K 會落在不同價格基準上，圖表切換時出現無法解釋的價格跳動。
- **該分鐘無成交即不寫入該列，不得補零。** 冷門股在多數分鐘沒有成交，補零會在分 K 圖上畫出一整排跌到 0 的假 K 棒。缺漏的分鐘由圖表以「該時間無 K 棒」呈現，而非以 0 值列表示。

### 欄位設計原則

1. **主鍵為 `(stock_id, trade_date, bar_time)` 複合鍵，禁止 `AUTO_INCREMENT` 代理鍵。**
   與日線表同一理由：主鍵即叢集索引，此順序讓「單檔單日的 271 根 K 棒」在磁碟上連續存放，分 K 查詢退化為一次順序掃描。這正是本表唯一的存取型態。

2. **`bar_time` 使用 `TIME`，不與 `trade_date` 合併為單一 `DATETIME`。**
   分區鍵必須是 `trade_date`（見下），而 MySQL 要求分區欄位出現在每一個唯一索引中。日期與時間拆為兩欄，可讓 `trade_date` 同時擔任主鍵前綴與分區鍵；合併為 `DATETIME` 則需對其取函數才能分區，且主鍵前綴無法單獨用於日期範圍裁剪。

3. **所有價格欄位使用 `DECIMAL(10,2)`，嚴禁 `FLOAT` / `DOUBLE`。** 與日線表同一標準，型別跨表必須一致，否則兩表比對時會出現表示誤差造成的假差異。

4. **`volume` 為該分鐘的成交股數，非累計值。** 資料源部分端點回傳當日累計量；正規化階段必須轉為單根增量後才寫入。存累計值會讓成交量副圖畫成單調遞增的階梯而非柱狀圖。

### 分區與生命週期

以 `RANGE (TO_DAYS(trade_date))` 依年度分區。

分區的目的是**生命週期管理**，不是為了查詢加速——查詢加速已由主鍵順序達成。分 K 是所有資料中最龐大且價值衰減最快的一類（歷史分 K 幾乎只在近期被查看），必須能整段丟棄。有分區時清理舊資料是 `ALTER TABLE ... DROP PARTITION`（毫秒級的檔案操作）；無分區時是 `DELETE FROM ... WHERE trade_date < ?`（逐列刪除、產生大量 undo log、且不歸還磁碟空間）。

分區表不支援外鍵——本表本就不與 `stock` 建立外鍵，理由同 `specs/dba/stock.md`。

### 寫入語意

一律 UPSERT（`INSERT ... ON DUPLICATE KEY UPDATE`），不可先刪後插。當日盤中重複抓取同一檔會取得同一批 K 棒加上新增的幾根，UPSERT 使其為冪等操作；先刪後插則會在刪除與插入之間留下一段前端可能讀到空資料的空窗。

## Implementation Details

### Migration SQL — V005__create_stock_minute_price.sql

```sql
CREATE TABLE stock_minute_price (
    stock_id     VARCHAR(10)     NOT NULL           COMMENT '股票代號，如 2330',
    trade_date   DATE            NOT NULL           COMMENT '交易日期',
    bar_time     TIME            NOT NULL           COMMENT 'K 棒起始時間（09:00:00 ~ 13:30:00）',
    open_price   DECIMAL(10,2)   NOT NULL           COMMENT '該分鐘開盤價（原始成交價，未還原）',
    high_price   DECIMAL(10,2)   NOT NULL           COMMENT '該分鐘最高價',
    low_price    DECIMAL(10,2)   NOT NULL           COMMENT '該分鐘最低價',
    close_price  DECIMAL(10,2)   NOT NULL           COMMENT '該分鐘收盤價',
    volume       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '該分鐘成交股數（單根增量，非當日累計）',
    source       VARCHAR(20)     NOT NULL           COMMENT '資料來源識別，如 YAHOO',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_id, trade_date, bar_time),
    CONSTRAINT chk_smp_high_low CHECK (high_price >= low_price)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='股票分鐘 K 線（1 分鐘 OHLCV，原始成交價）'
PARTITION BY RANGE (TO_DAYS(trade_date)) (
    PARTITION p2025 VALUES LESS THAN (TO_DAYS('2026-01-01')),
    PARTITION p2026 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p2027 VALUES LESS THAN (TO_DAYS('2028-01-01')),
    PARTITION pmax  VALUES LESS THAN MAXVALUE
);
```

### 索引說明

**刻意不建任何次要索引。** 本表唯一的查詢型態是「單檔 + 單一交易日」，主鍵前兩欄即完全涵蓋。分 K 沒有「某一分鐘的全市場」這種存取需求；每多一個次要索引，都會讓寫入時多維護一棵 B+ 樹，而本表是全系統寫入量最大的表。

### 分區維護

`pmax` 是安全網而非常態去處——資料落入 `pmax` 就失去了依年度丟棄的能力。每年年底必須以 `ALTER TABLE stock_minute_price REORGANIZE PARTITION pmax INTO (PARTITION pYYYY VALUES LESS THAN (...), PARTITION pmax VALUES LESS THAN MAXVALUE)` 補上次年分區。

## Acceptance Criteria
- [ ] `stock_minute_price` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [ ] 主鍵為 `(stock_id, trade_date, bar_time)` 複合鍵且順序正確，表中不存在 `AUTO_INCREMENT` 欄位
- [ ] 四個價格欄位型別皆為 `DECIMAL(10,2)`，表中不存在任何 `FLOAT` 或 `DOUBLE` 欄位
- [ ] `SHOW CREATE TABLE stock_minute_price` 顯示 `PARTITION BY RANGE (TO_DAYS(trade_date))` 且包含 `p2025`/`p2026`/`p2027`/`pmax` 四個分區
- [ ] 插入 `trade_date = '2026-03-15'` 的列後，`EXPLAIN` 對該日期的查詢顯示只掃描 `p2026` 單一分區（分區裁剪生效）
- [ ] 對同一 `(stock_id, trade_date, bar_time)` 重複 UPSERT 兩次，表中僅一列且第二次的值覆蓋第一次
- [ ] 插入 `high_price < low_price` 的資料時被 CHECK 約束拒絕
- [ ] `EXPLAIN` 驗證「單檔 + 單一交易日」查詢使用主鍵範圍掃描（`type=range`, `key=PRIMARY`）
- [ ] 表中除 `PRIMARY` 外不存在其他索引
