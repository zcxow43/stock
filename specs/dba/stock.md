---
status: done
title: "股票主檔 stock"
requirement: "統計兩個月股票資料含 MACD/KD 指標 — 全市場範圍需要一份股票universe，供回補排程列舉標的與統計結果顯示名稱；並內建一批上市股票種子資料，讓空白開發資料庫能立即在總覽頁選到標的"
---

# 股票主檔 stock — DBA Spec

## Overview

儲存全市場股票的代號與名稱，作為兩件事的依據：

1. **回補排程的標的清單（universe）** — 逐檔回補需要知道「有哪些檔要補」，這份清單就是本表。
2. **統計結果的名稱來源** — 行情表只存代號，API 回傳需要中文名稱時由本表取得。

本表由每日行情抓取時順帶維護（資料源同時回傳代號與名稱），不需要獨立的維護介面。

**本 migration 為 V002，在 V001（`stock_daily_price`）之後。**

## Requirements

- 一列代表一檔股票，以股票代號為主鍵。
- 需區分市場別（上市 / 上櫃），因為兩者的資料來源端點不同，回補排程要據此分流。
- 需要 `is_active` 旗標標記已下市／終止交易的標的：下市股票不應再進入每日抓取與回補排程，但其歷史行情必須保留（不可刪除 `stock_daily_price` 既有資料）。
- **不與 `stock_daily_price` 建立外鍵約束。** 理由：新上市股票可能在主檔同步之前就出現在當日行情資料中，外鍵會讓該筆行情寫入失敗而遺失資料。行情抓取的健壯性優先於參照完整性；孤兒代號由對帳查詢檢出，而非由約束阻擋。

## Implementation Details

### Migration SQL — V002__create_stock.sql

```sql
CREATE TABLE stock (
    stock_id     VARCHAR(10)  NOT NULL                COMMENT '股票代號，如 2330',
    stock_name   VARCHAR(60)  NOT NULL                COMMENT '股票名稱，如 台積電',
    market       VARCHAR(10)  NOT NULL                COMMENT '市場別：TSE=上市, OTC=上櫃',
    is_active    TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否仍在交易：1=是, 0=已下市／終止交易',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_id),
    KEY idx_market_active (market, is_active),
    CONSTRAINT chk_stock_market CHECK (market IN ('TSE','OTC'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='股票主檔（全市場 universe）';
```

### 索引說明
- `idx_market_active`：回補與每日排程列舉標的時的查詢條件即 `WHERE market = ? AND is_active = 1`。

### 維護語意
以 UPSERT 寫入。股票更名（台股實務上會發生）時，`stock_name` 直接覆蓋為最新值——本表存的是「現況」，不保留名稱異動歷史。

### Migration SQL — V007__seed_listed_stocks.sql

開發用種子資料：一批常見上市（`TSE`）股票，讓剛建好、還沒跑過任何行情抓取的資料庫也能在股票總覽頁搜尋並點選標的。接在 `V006`（`stock_minute_fetch_status`）之後，是本表繼 `V002` 之後擁有的第二個版本；它只寫資料、不改結構。

以 `INSERT ... ON DUPLICATE KEY UPDATE` 寫入，因此可重複執行：既有列被更新為最新名稱，不會因主鍵重複而失敗，也不會產生重複股票。`SET NAMES utf8mb4` 是必要的——少了它，中文名稱會依連線端預設字元集寫成亂碼。

```sql
SET NAMES utf8mb4;

INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES
    ('1101', '台泥',       'TSE', 1),
    ('1102', '亞泥',       'TSE', 1),
    ('1216', '統一',       'TSE', 1),
    ('1301', '台塑',       'TSE', 1),
    ('1303', '南亞',       'TSE', 1),
    ('2002', '中鋼',       'TSE', 1),
    ('2207', '和泰車',     'TSE', 1),
    ('2303', '聯電',       'TSE', 1),
    ('2308', '台達電',     'TSE', 1),
    ('2317', '鴻海',       'TSE', 1),
    ('2327', '國巨',       'TSE', 1),
    ('2330', '台積電',     'TSE', 1),
    ('2345', '智邦',       'TSE', 1),
    ('2357', '華碩',       'TSE', 1),
    ('2379', '瑞昱',       'TSE', 1),
    ('2382', '廣達',       'TSE', 1),
    ('2395', '研華',       'TSE', 1),
    ('2412', '中華電',     'TSE', 1),
    ('2454', '聯發科',     'TSE', 1),
    ('2603', '長榮',       'TSE', 1),
    ('2609', '陽明',       'TSE', 1),
    ('2615', '萬海',       'TSE', 1),
    ('2881', '富邦金',     'TSE', 1),
    ('2882', '國泰金',     'TSE', 1),
    ('2886', '兆豐金',     'TSE', 1),
    ('2891', '中信金',     'TSE', 1),
    ('3008', '大立光',     'TSE', 1),
    ('3034', '聯詠',       'TSE', 1),
    ('3231', '緯創',       'TSE', 1),
    ('3661', '世芯-KY',    'TSE', 1),
    ('3711', '日月光投控', 'TSE', 1),
    ('4904', '遠傳',       'TSE', 1),
    ('6505', '台塑化',     'TSE', 1),
    ('6669', '緯穎',       'TSE', 1)
ON DUPLICATE KEY UPDATE
    stock_name = VALUES(stock_name),
    market     = VALUES(market),
    is_active  = VALUES(is_active);
```

共 34 檔，全部為 `TSE`、`is_active = 1`。

**種子只建立股票主檔，不虛構任何成交價格。** 這是刻意的：捏造的 OHLC 會讓日 K 圖與 MACD／KD 顯示看似合理實則不存在的走勢，比空白更難察覺是假的。因此點進種子股票的日 K 頁時，在跑過行情抓取之前圖表是空的——真實行情由 `specs/backend/stock-price-ingestion.md` 的回補流程寫入 `stock_daily_price`，指標再由 `specs/backend/stock-indicator-statistics.md` 從那份 OHLC 推導。種子資料與行情抓取是互補的兩步，不是替代關係。

種子清單是開發樣本，不是完整上市清單。全市場 universe 一律由行情抓取流程以 UPSERT 補齊——兩者寫入同一張表、同一種語意，所以先跑哪個都不會衝突。

### Migration SQL — V011__shift_stock_timestamps_to_taipei.sql

一次性資料位移，不改結構。接在 `V009` 之後，理由與 `specs/dba/stock-sync-progress.md` 的 V009 完全相同：`created_at` / `updated_at` 由資料庫時鐘寫入，容器時區從 `UTC` 改為 `Asia/Taipei` 之前寫入的列比台北時間早 8 小時。

```sql
UPDATE stock t
JOIN (SELECT COUNT(*) AS c FROM schema_migration WHERE version = 'V011') g
SET t.created_at = t.created_at + INTERVAL 8 HOUR,
    t.updated_at = t.updated_at + INTERVAL 8 HOUR
WHERE g.c = 0;

INSERT IGNORE INTO schema_migration (version) VALUES ('V011');
```

守門走 `schema_migration`（見 `specs/dba/schema-migration.md`），理由與 V010 相同：位移後的值仍滿足任何以資料為準的條件，只有記錄「跑過了」才擋得住重跑。本表的兩個欄位皆為稽核用途、目前沒有任何 API 對外回傳，位移是為了避免同一欄位在同一張表裡同時存在兩種時區語意——那是日後查問題時最難察覺的一類陷阱。

## Acceptance Criteria
- [x] `stock` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [x] `market` 欄位寫入 `'TSE'`/`'OTC'` 以外的值時被 CHECK 約束拒絕
- [x] 對同一 `stock_id` 重複 UPSERT，表中僅一列且名稱被更新為最新值
- [x] `stock` 與 `stock_daily_price` 之間**不存在**外鍵約束（`SHOW CREATE TABLE stock_daily_price` 無 FOREIGN KEY）
- [x] 將某檔設為 `is_active = 0` 後，其在 `stock_daily_price` 的歷史資料仍完整存在
- [x] V007 套用後，`SELECT COUNT(*) FROM stock` 為 34，且全部 `market = 'TSE'`、`is_active = 1`
- [x] 搜尋 `2330` 與 `台積` 皆可找到台積電；`鴻海`、`聯發科`、`中華電` 亦各自可由名稱找到
- [x] 中文名稱在資料庫中正確顯示為中文，非亂碼或問號（連線端字元集為 `utf8mb4`）
- [x] V007 連續執行兩次後，`stock` 的列數不變，且名稱為最新值
- [x] V007 的 SQL 完整存在於本 spec 內，專案中不存在對應的獨立 `.sql` 檔

---

- [x] V011 執行後，既有列的 `created_at` / `updated_at` 與執行前相比正好增加 8 小時
- [x] V011 連續執行兩次，第二次影響 0 列，且 `schema_migration` 中 `V011` 仍只有一列（位移為冪等）

## Execution Result
- Status: DONE
- Files changed: `specs/dba/stock.md` (this spec — SQL applied live, no standalone .sql file created)
- Notes:
  - Applied `CREATE TABLE stock` (V002) against the live `stock` database via `mysql` CLI. `SHOW CREATE TABLE stock` / `DESCRIBE stock` confirmed columns, types, comments, PK, `idx_market_active` index, and `chk_stock_market` CHECK constraint all match the spec DDL exactly.
  - Verified CHECK constraint: `INSERT ... market='XXX'` raised `ERROR 3819 (HY000) Check constraint 'chk_stock_market' is violated`; no row was inserted.
  - Verified UPSERT semantics: inserted `stock_id='2330'` with name `台積電`, then `INSERT ... ON DUPLICATE KEY UPDATE` with name `台積電新名`; table retained exactly one row for `2330` with the updated name.
  - Verified no FK: `stock_daily_price` (created concurrently by the `stock_daily_price` DBA agent, confirmed present) has no `FOREIGN KEY` clause in its `SHOW CREATE TABLE` output — only its own `chk_sdp_high_low` CHECK and `idx_trade_date` index.
  - Verified `is_active=0` does not lose history: inserted a `stock_daily_price` row for `2330` (plus 3 pre-existing rows from the concurrent agent were visible at the time), set `stock.is_active=0` for `2330`, and confirmed all `stock_daily_price` rows for `2330` remained intact afterward.
  - Cleanup: deleted my own test rows (`stock.stock_id IN ('2330','9999')` — note `'9999'` was never actually inserted since the CHECK constraint rejected it; and the one `stock_daily_price` row I inserted for `2330`/`2026-08-01`/`source='TEST'`). Final state: `stock` table is empty (schema-only, 0 rows). `stock_daily_price` is also empty at time of writing — the concurrent agent appears to have cleaned up its own test rows independently during this verification.

### Increment 2 — 2026-08-28（V007 種子資料）

由 `/reset-env` 對空資料庫重建時套用，V002 建表後緊接 V007。

- 套用方式：spec 的 `## Migration SQL` 區段直接以 `mysql` CLI 送進現行資料庫，未產生任何獨立 `.sql` 檔（`find . -name "*.sql"` 在 repo 中無結果）。
- **執行環境問題（根因已記錄於 `.claude/agents/dba.md`）**：Windows 的 `mysql` client 若未加 `--default-character-set=utf8mb4`，會以 OS codepage 編碼送出，把中文欄位註解與中文股名靜默寫成 `?` 位元組——語句仍然成功，肉眼也看不出異常。V001 首次套用即踩到此問題（已 DROP 重做），其後所有 migration 一律帶上該參數。
- 驗證（皆為實際查詢結果，非代理回報）：`COUNT(*) = 34`，34 檔全為 `market='TSE'`、`is_active=1`；`HEX(stock_name)` 對 `2330` 為 `E58FB0E7A98DE99BBB`，與 `台積電` 的 UTF-8 位元組完全相符，確認非亂碼；`台積`／`鴻海`／`聯發科`／`中華電` 皆可由名稱命中。
- 冪等性：自 spec 抽出 V007 的 SQL 再套用一次，列數仍為 34、名稱不變——同時也證明 spec 內嵌的 SQL 可直接執行。
- 本次未寫入任何行情：`stock_daily_price` 為 0 列。日 K 需先由 `specs/backend/stock-price-ingestion.md` 的回補流程取得真實 OHLC。

### Increment 3 — 2026-08-31

套用 V011（時間戳由 UTC 位移為 Asia/Taipei），以 `schema_migration` 守門。

| | `created_at` / `updated_at`（stock_id 1101） | 列數 |
|---|---|---|
| 位移前 | `2026-08-31 02:36:42` | 34 |
| 位移後 | `2026-08-31 10:36:42` | 34 |

第一次執行影響 34 列，正好 +8 小時，列數不變。第二次執行影響 **0** 列，時間戳維持 `10:36:42` 未再位移。
