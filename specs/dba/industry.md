---
status: done
title: "產業別主檔 industry"
requirement: "動態分頁 — 漲幅命中結果需按產業別分組顯示（例如 光電、半導體、電子零組件），因此需要一份產業別主檔"
---

# 產業別主檔 industry — DBA Spec

## Overview

儲存產業別的清單，作為 `stock_industry`（見 `specs/dba/stock-industry.md`）關聯的一端，並提供動態分頁分組顯示時的名稱來源（`specs/backend/industry-gain-ranking.md`）。

產業別由交易所官方基本資料匯入（`specs/backend/stock-universe-import.md`），不由人工建立。本表因此是**匯入時遇到沒見過的產業別就自動補上**的字典表，而不是預先寫死的列舉。

**本 migration 為 V013，在 V012（`stock_daily_price` 時間戳位移）之後。**

## Requirements

- 一列代表一個產業別。
- 以**代理主鍵**（自增整數）為主鍵，產業別名稱另設唯一鍵。理由：名稱是匯入來源給的字串，交易所日後調整分類名稱時（台股實務上發生過，例如新增「綠能環保」「數位雲端」「運動休閒」三類）以名稱當主鍵會讓既有關聯全數斷開；代理鍵讓改名只是一次 `UPDATE`。
- 名稱唯一，且匯入以名稱比對——匯入來源沒有提供穩定的產業別代碼，名稱是唯一可用的識別依據。
- **不預先種入任何產業別。** 空表是合法的初始狀態；第一次執行產業別匯入時才會被填滿。預先猜一組分類名稱，只要與交易所實際回傳的字串差一個字（「光電業」vs「光電」），就會產生兩列語意相同的產業別而無人察覺。

## Implementation Details

### Migration SQL — V013__create_industry.sql

```sql
CREATE TABLE industry (
    industry_id   INT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '產業別代理主鍵',
    industry_name VARCHAR(40)  NOT NULL                COMMENT '產業別名稱，如 半導體業、光電業',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (industry_id),
    UNIQUE KEY uk_industry_name (industry_name)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='產業別主檔';
```

### 索引說明

- `uk_industry_name`：匯入時以名稱比對既有列（`INSERT ... ON DUPLICATE KEY UPDATE industry_id = LAST_INSERT_ID(industry_id)`）的依據，同時保證不會出現兩列同名產業別。

### 維護語意

只由產業別匯入寫入，以名稱 UPSERT。已存在的名稱不產生新列、`industry_id` 保持不變——`stock_industry` 的關聯掛在 `industry_id` 上，重新匯入若換了主鍵值，等於把全部關聯指向錯誤的產業。

本表**不做刪除**。某個產業別在某次匯入後不再有任何股票歸屬時，該列仍保留（成為沒有成分股的產業）；動態分頁只顯示有命中股票的產業區塊，所以空產業不會在畫面上造成雜訊，而保留它可避免主鍵在下一次匯入時被重新配發。

## Acceptance Criteria
- [x] `industry` 表建立成功，欄位、型別、註解與上述 DDL 一致
- [x] `industry_id` 為自增主鍵，`industry_name` 有唯一約束 `uk_industry_name`
- [x] 插入兩列同名產業別時，第二次因唯一約束失敗（或以 UPSERT 寫入時不產生新列）
- [x] 以 `INSERT ... ON DUPLICATE KEY UPDATE industry_id = LAST_INSERT_ID(industry_id)` 對同一名稱重複寫入，表中僅一列且 `industry_id` 不變
- [x] 中文產業別名稱在資料庫中正確顯示為中文，非亂碼或問號（連線端字元集為 `utf8mb4`）
- [x] 建表後為 0 列（不含任何種子資料）
- [x] V013 的 SQL 完整存在於本 spec 內，專案中不存在對應的獨立 `.sql` 檔

---
## Execution Result
- Status: DONE
- Files changed: `specs/dba/industry.md` (this spec — migration SQL embedded and executed against the live database; no standalone `.sql` file was created anywhere in the repo)
- Migration applied: V013 — `CREATE TABLE industry` executed directly via `mysql --default-character-set=utf8mb4` against `stock` on `127.0.0.1:3306` (user `app`)

### Acceptance Criteria — verification output

1. **`industry` 表建立成功，欄位、型別、註解與上述 DDL 一致**
   `SHOW CREATE TABLE industry` returned:
   ```
   CREATE TABLE `industry` (
     `industry_id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '產業別代理主鍵',
     `industry_name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '產業別名稱，如 半導體業、光電業',
     `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     PRIMARY KEY (`industry_id`),
     UNIQUE KEY `uk_industry_name` (`industry_name`)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='產業別主檔'
   ```
   Matches DDL exactly. PASS.

2. **`industry_id` 為自增主鍵，`industry_name` 有唯一約束 `uk_industry_name`**
   `SHOW INDEX FROM industry` returned `PRIMARY` on `industry_id` and `uk_industry_name` (`Non_unique = 0`) on `industry_name`; `information_schema.COLUMNS` shows `industry_id` `EXTRA = auto_increment`. PASS.

3. **插入兩列同名產業別時，第二次因唯一約束失敗**
   Ran two plain `INSERT INTO industry (industry_name) VALUES ('半導體業')` in sequence:
   ```
   ERROR 1062 (23000) at line 3: Duplicate entry '半導體業' for key 'industry.uk_industry_name'
   ```
   PASS.

4. **以 `INSERT ... ON DUPLICATE KEY UPDATE industry_id = LAST_INSERT_ID(industry_id)` 對同一名稱重複寫入，表中僅一列且 `industry_id` 不變**
   Ran the UPSERT statement twice against `'半導體業'`. `LAST_INSERT_ID()` returned `1` both times. Follow-up query:
   ```
   total_rows  distinct_ids
   1           1
   ```
   Row content: `industry_id=1, industry_name=半導體業`. PASS.

5. **中文產業別名稱在資料庫中正確顯示為中文，非亂碼或問號**
   `SELECT industry_name, HEX(industry_name) FROM industry WHERE industry_name = '半導體業'` returned `hex_stored = E58D8AE5B08EE9AB94E6A5AD`. Independently dumped the raw UTF-8 bytes of the literal `半導體業` from the shell to a file and inspected with `xxd`: `e58d8a e5b08e e9ab94 e6a5ad` — byte-for-byte identical to the stored hex. No `?`/mangled bytes. PASS.

6. **建表後為 0 列（不含任何種子資料）**
   Table was created empty (`row_count = 0`). Verification rows for AC3–AC5 were inserted as a temporary test and then deleted (`DELETE FROM industry WHERE industry_name = '半導體業'`), with `AUTO_INCREMENT` reset back to 1 afterward so the table's persistent state is unaffected by the test. Final check: `SELECT COUNT(*) FROM industry` → `0`. PASS.

7. **V013 的 SQL 完整存在於本 spec 內，專案中不存在對應的獨立 `.sql` 檔**
   `find . -iname "*v013*" -o -iname "*industry*.sql"`, and checks under `*/initdb/*` and `*/db/migration/*`, all returned no results. PASS.

### Notes
- Nothing was deliberately left undone; all Migration SQL in this spec (the `CREATE TABLE industry` statement) was applied as written, with no deviation from the DDL shown above.
- Per the spec's explicit requirement ("不預先種入任何產業別"), no seed data was inserted — the table is left at 0 rows, ready for `specs/backend/stock-universe-import.md`'s import-time UPSERT to populate it.
- `stock_industry` (V014, `specs/dba/stock-industry.md`) was intentionally **not** created by this run — it is a separate spec with its own FK dependency on this table, to be applied by another agent immediately after.
