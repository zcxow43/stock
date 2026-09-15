---
status: done
title: "分 K 抓取狀態表 stock_minute_fetch_status"
requirement: "前端 K 線瀏覽 — 分 K 為隨點選隨抓取，需記錄每個「股票×交易日」的抓取結果，避免對永遠取不到資料的日期重複請求外部來源；分 K 改由富果補抓 30 天以前的日期後，清除 2023-05-23 以後被誤標為超出範圍的狀態列"
---

# 分 K 抓取狀態表 stock_minute_fetch_status — DBA Spec

## Overview

記錄每一個「股票 × 交易日」的分 K 抓取結果，讓後端能區分三種在 `stock_minute_price` 中**看起來完全一樣（都是查無資料）**的狀況：

| 實際狀況 | 沒有這張表時的行為 | 有這張表時的行為 |
|---|---|---|
| 尚未抓取過 | 向外部來源請求 | 向外部來源請求 |
| 已抓取，該日確實無成交 | **再次**向外部來源請求 | 直接回覆無資料，零外部請求 |
| 該日已超出來源提供的時間窗 | **再次**向外部來源請求 | 直接回覆超出範圍，零外部請求 |

分 K 採隨點選隨抓取（見 `specs/backend/stock-minute-price.md`），使用者每點一次日 K 上的 K 棒就可能觸發一次外部請求。少了這張表，任何一個「取不到資料」的日期都會在每次點擊時重打一次外部 API——冷門股與超窗日期正是最容易被反覆點選的情境，這會直接把速率額度耗盡。

`stock_sync_progress` 無法承擔此職責：其主鍵為 `(stock_id, job_type)`，一檔股票只有一列，記錄的是「批次作業進行到哪一天」；分 K 需要的是「每一天各自的抓取結果」，粒度不同。

**本 migration 為 V006，在 V005（`stock_minute_price`）之後。**

## Requirements

- 一列代表「一檔股票 × 一個交易日」的抓取結果，主鍵 `(stock_id, trade_date)`。
- `status` 必須明確區分下列五種結果，不可合併：

  | 值 | 意義 | 是否應再次請求外部來源 |
  |---|---|---|
  | `AVAILABLE` | 已成功抓取並寫入 K 棒 | 否（當日盤中除外，見下） |
  | `NO_DATA` | 已成功請求，但該日確實無任何分鐘成交資料 | 否 |
  | `OUT_OF_WINDOW` | 該日期早於分 K 資料的最早可取得日（見 `specs/backend/stock-minute-price.md` 的 `availableFrom`），任何來源都不會有此資料 | 否 |
  | `FAILED` | 請求失敗（逾時、HTTP 429、來源錯誤） | 是，但受 `attempt_count` 上限限制 |
  | `NOT_A_TRADING_DAY` | 該日在 `stock_daily_price` 中無對應日線，非該檔的交易日 | 否 |

  `NO_DATA` 與 `FAILED` 若混為一談，重試排程會對永遠不會有資料的日期無限重試；`OUT_OF_WINDOW` 與 `NO_DATA` 若混為一談，則無法向使用者說明「是這天沒交易，還是來源不提供這麼久以前的資料」——兩者在畫面上要顯示不同的訊息。

- 必須保留 `bar_count`：已寫入幾根 K 棒。正常交易日為 **266 根**——09:00–13:24 每分鐘一根加上 13:30 一根，13:25–13:29 為收盤前集合競價、沒有 K 棒（兩個來源皆然，見 `specs/backend/stock-minute-price.md`）。下方 V006 建表時欄位註解寫的 271 為當時的觀測，已套用於資料庫，不另立 migration 修改註解。`AVAILABLE` 但 `bar_count` 明顯偏低（如當日僅 12 根）代表抓取被中途截斷，可據此挑出需重抓的日期。
- 必須保留 `last_error` 與 `attempt_count`，理由同 `stock_sync_progress`：失敗若無原因記錄則無從診斷，無次數上限則單一標的可無限重試。
- `source` 記錄該列由哪個外部來源抓取，目前取值為 `YAHOO`（最近 30 天）或 `FUGLE`（30 天以前）。`VARCHAR(20)` 足以容納，無需改表。
- 必須保留 `fetched_at`（最近一次成功抓取的時間）。**當日盤中的分 K 會持續增長**，後端據此判斷快取是否過期並重抓（判斷規則見 backend spec）；沒有這個時間戳就無法區分「今天早上 09:05 抓的 5 根」與「今天收盤後抓的 266 根」。

## Implementation Details

### Migration SQL — V006__create_stock_minute_fetch_status.sql

```sql
CREATE TABLE stock_minute_fetch_status (
    stock_id      VARCHAR(10)  NOT NULL COMMENT '股票代號',
    trade_date    DATE         NOT NULL COMMENT '交易日期',
    status        VARCHAR(20)  NOT NULL DEFAULT 'FAILED'
                      COMMENT 'AVAILABLE / NO_DATA / OUT_OF_WINDOW / FAILED / NOT_A_TRADING_DAY',
    bar_count     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已寫入的分鐘 K 棒數，正常交易日為 271',
    source        VARCHAR(20)  NULL     COMMENT '資料來源識別，如 YAHOO；尚未成功抓取時為 NULL',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '嘗試次數，用於重試上限判斷',
    last_error    VARCHAR(500) NULL     COMMENT '最近一次失敗原因',
    fetched_at    DATETIME     NULL     COMMENT '最近一次成功抓取完成時間；用於判斷當日盤中資料是否過期',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_id, trade_date),
    KEY idx_status_date (status, trade_date),
    CONSTRAINT chk_smfs_status CHECK (
        status IN ('AVAILABLE','NO_DATA','OUT_OF_WINDOW','FAILED','NOT_A_TRADING_DAY')
    )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='分 K 逐日抓取狀態（避免對無資料日期重複請求外部來源）';
```

### 索引說明
- `PRIMARY KEY (stock_id, trade_date)`：查詢分 K 前的第一個動作即「這檔這天抓過了嗎」，為單列主鍵查找。
- `idx_status_date`：支援維運查詢「挑出所有 `FAILED` 且尚未達重試上限的日期」與「清除超過保存期的狀態列」。

### 與 `stock_minute_price` 的一致性
兩表必須在同一個交易邊界內寫入：K 棒寫入與狀態更新若分屬兩個交易，可能出現「K 棒已寫入但狀態仍為 FAILED」（下次仍重抓，浪費請求）或「狀態為 AVAILABLE 但 K 棒未寫入」（前端拿到空圖且不會重試）。後者是使用者可見的錯誤。

清除舊分 K 分區時，對應日期的狀態列必須一併刪除；否則狀態表會宣稱 `AVAILABLE`，而 K 棒已隨分區消失。

### Migration SQL — V015__clear_stale_out_of_window_status.sql

**Applied-when**: `SELECT COUNT(*) = 0 FROM stock_minute_fetch_status WHERE status = 'OUT_OF_WINDOW' AND trade_date >= '2023-05-23'`

**本 migration 為 V015，在 V014 之後。** 純資料清理，不改表結構。

分 K 原本只有 Yahoo 一個來源，30 天以前的日期一律被寫成 `OUT_OF_WINDOW`，而依抓取決策這個狀態「永不再請求」。改由富果補抓 30 天以前的日期後，其中不早於 2023-05-23 的日期其實拿得到——若不清除，使用者曾經點開過的這些日期會永遠停在「超出範圍」，換了來源也沒用。刪除後它們回到「無對應列」，下一次查詢自然改走富果。

```sql
DELETE FROM stock_minute_fetch_status
 WHERE status = 'OUT_OF_WINDOW'
   AND trade_date >= '2023-05-23';
```

- **只刪 `OUT_OF_WINDOW` 列。** 這類列從未寫入 K 棒（`bar_count` 為 0），刪除不會在 `stock_minute_price` 留下孤兒資料；`AVAILABLE`、`NO_DATA`、`FAILED`、`NOT_A_TRADING_DAY` 一律不動。
- **可安全重跑。** 刪除後符合條件的列為 0；新版後端只會對早於 2023-05-23 的日期寫入 `OUT_OF_WINDOW`，不會再產生符合條件的列，因此 `Applied-when` 會持續為真，重跑刪除 0 列。
- 日後若最早可取得日往前推（例如來源開放更早的分 K），早於 2023-05-23 的 `OUT_OF_WINDOW` 列會再次過時。**不需再另立清理**：後端的抓取決策會以現行 `availableFrom` 重新檢驗每一筆 `OUT_OF_WINDOW` 列，不早於分界者視為過時並重新抓取（見 `specs/backend/stock-minute-price.md` 的「抓取決策」）。本 migration 仍保留，只是讓舊列提前消失，正確性不再依賴它是否執行過。

## Acceptance Criteria
- [x] `stock_minute_fetch_status` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [x] 主鍵為 `(stock_id, trade_date)`
- [x] `status` 寫入五種列舉值以外的值時被 CHECK 約束拒絕
- [x] 對同一 `(stock_id, trade_date)` 重複 UPSERT，表中僅一列
- [x] 以 `WHERE status = 'FAILED' AND trade_date >= ?` 查詢時，`EXPLAIN` 顯示使用 `idx_status_date`
- [x] `fetched_at` 與 `source` 允許為 `NULL`（尚未成功抓取的列可寫入）


### 清除過時的超出範圍狀態列（本次新增）

- [x] 執行 V015 前後比對：`status = 'OUT_OF_WINDOW' AND trade_date >= '2023-05-23'` 的列數由執行前的值變為 0
- [x] 執行 V015 後 `Applied-when` 查詢回傳真值（`1`）
- [x] 其餘列完全未變：執行前後 `AVAILABLE`／`NO_DATA`／`FAILED`／`NOT_A_TRADING_DAY` 各狀態的列數相同，且 `trade_date < '2023-05-23'` 的 `OUT_OF_WINDOW` 列數相同
- [x] 重跑 V015 刪除 0 列，不產生錯誤
- [x] 表結構未變：`SHOW CREATE TABLE stock_minute_fetch_status` 與執行前完全相同

---
## Execution Result
- Status: DONE
- Files changed: `specs/dba/stock-minute-fetch-status.md` (Migration SQL executed against live DB; no standalone `.sql` files created)
- Notes:
  - Applied `V006__create_stock_minute_fetch_status.sql` directly against the live `stock` database via `mysql` CLI. Table did not previously exist (clean slate); creation succeeded with no errors.
  - Verified `SHOW CREATE TABLE` output matches the spec's DDL exactly: all 9 columns, types, comments, `PRIMARY KEY (stock_id, trade_date)`, `idx_status_date (status, trade_date)`, and `chk_smfs_status` CHECK constraint present.
  - Verified CHECK constraint: `INSERT ... status='BOGUS'` failed with `ERROR 3819 (HY000): Check constraint 'chk_smfs_status' is violated.`
  - Verified UPSERT: inserted `(2330, 2026-08-27, FAILED, ...)` then `INSERT ... ON DUPLICATE KEY UPDATE` with `status='AVAILABLE', bar_count=271, source='YAHOO', ...` on the same key; `COUNT(*)` for that key remained 1, and the row reflected the updated values.
  - Verified `EXPLAIN SELECT * FROM stock_minute_fetch_status WHERE status = 'FAILED' AND trade_date >= '2026-08-01'` returned `key: idx_status_date`, `type: range`, `Extra: Using index condition`.
  - Verified NULL acceptance: inserted a row with `status='NOT_A_TRADING_DAY'` omitting `source`/`fetched_at` (defaulting to NULL) — insert succeeded, and a row with explicit `NULL` for `source`/`fetched_at` was also accepted (tested during the UPSERT setup insert as well).
  - All test rows (`2330`/`2026-08-27`, `2330`/`2026-08-26`, `2317`/`2026-08-20`, `2412`/`2026-08-21`, `1101`/`2026-08-22`) were deleted after verification. Table confirmed empty (`COUNT(*) = 0`), left schema-only.

### Increment 2 — 2026-09-15

套用 `V015__clear_stale_out_of_window_status.sql`（純資料清理，無 DDL），5 項驗收全數符合。

**執行**：以 `mysql` CLI 直接對 live 資料庫執行 spec 內嵌的 `DELETE`，未產生任何獨立的 `.sql` 檔。本機資料庫的影響列數為 **0**——執行前 `status = 'OUT_OF_WINDOW' AND trade_date >= '2023-05-23'` 的列數本來就是 0（此前分 K 只被點開過近期日期，從未產生過時的超出範圍列）。這是本環境的預期結果；在曾經點開過 30 天以前日期的其他環境，同一段 SQL 會實際刪除那些列。

**驗證（執行前 → 執行後）**：
- 符合清除條件的列數：0 → 0；`Applied-when` 查詢回傳 `1`。
- 其餘列未變：`AVAILABLE` 7 → 7、`NO_DATA` 0 → 0、`FAILED` 0 → 0、`NOT_A_TRADING_DAY` 0 → 0；`trade_date < '2023-05-23'` 的 `OUT_OF_WINDOW` 0 → 0。
- 重跑 `DELETE` 影響 0 列、無錯誤。
- `SHOW CREATE TABLE stock_minute_fetch_status` 執行前後逐字相同。
