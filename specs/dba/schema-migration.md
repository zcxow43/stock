---
status: done
title: "已套用 migration 紀錄表 schema_migration"
requirement: "資料位移類的 migration 必須可重複執行而不重複套用 — 記錄哪些 migration 已經跑過，作為位移類 SQL 的執行守門"
---

# 已套用 migration 紀錄表 schema_migration — DBA Spec

## Overview

記錄哪些 migration 已經在這個資料庫上套用過。只有一個用途：讓**資料位移類**的 migration 具備冪等性。

`/dev` 每次執行一份 DBA spec，都會把該 spec `### Migration SQL` 區塊裡的 SQL 直接對正式資料庫再跑一次。對 DDL 而言這是安全的（`CREATE TABLE` 撞到既有表就是報錯，不會改壞資料）；對**相對位移**的 DML 而言則不然——`SET t = t + INTERVAL 8 HOUR` 這種語句每跑一次就再位移一次，跑兩次資料就錯 16 小時，而且錯得沒有任何跡象可循。

以資料本身的條件當守門是行不通的：位移後的值依然滿足「位移前」的條件（例如 `WHERE updated_at < '2026-09-01'` 在把 `2026-08-31 03:07` 推成 `11:07` 之後仍然成立），所以第二次執行照樣會命中同一批列。**唯一可靠的守門是把「這支 migration 跑過了」這件事本身記下來**，這張表就是那筆紀錄。

只有相對位移類的 migration 需要這道守門。冪等的 DDL（`CREATE TABLE IF NOT EXISTS`、`ALTER ... MODIFY` 註解）與冪等的 UPSERT 型種子資料不需要登錄，不必為了形式一致而全部塞進來。

## Requirements

- 每支需要守門的 migration 以其版本號（`V0xx`）作為唯一鍵，最多只能有一列。
- 記錄套用時間，供日後追查。
- 表本身的建立必須可重複執行（`CREATE TABLE IF NOT EXISTS`），因為它自己就是被 `/dev` 反覆執行的對象。
- 版本號在所有 migration 中排最前，確保任何要用到它的 migration 執行時它已經存在。

## Implementation Details

### Migration SQL — V009__create_schema_migration.sql

```sql
CREATE TABLE IF NOT EXISTS schema_migration (
    version    VARCHAR(20) NOT NULL COMMENT 'migration 版本號，例如 V010',
    applied_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '套用時間',
    PRIMARY KEY (version)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='已套用的 migration 紀錄，供非冪等的資料位移類 migration 守門';
```

### 守門寫法

需要守門的 migration 一律寫成「條件式 UPDATE + 登錄」兩句，兩句都可以重複執行：

```sql
UPDATE <table> t
JOIN (SELECT COUNT(*) AS c FROM schema_migration WHERE version = '<V0xx>') g
SET <相對位移的欄位指派>
WHERE g.c = 0;

INSERT IGNORE INTO schema_migration (version) VALUES ('<V0xx>');
```

第一次執行時 `g.c` 為 0，`UPDATE` 命中目標列，接著登錄版本號。第二次執行時 `g.c` 為 1，`WHERE g.c = 0` 不成立，`UPDATE` 影響 0 列，`INSERT IGNORE` 也不再新增——整組語句變成無操作。

以 `JOIN` 子查詢而非 `EXISTS` 表達，是因為多表 `UPDATE` 語法可以把守門條件寫進同一句 SQL，不需要預存程序或應用程式端的判斷；`/dev` 直接把這段 SQL 餵給 `mysql` CLI 就能跑。

## Acceptance Criteria
- [x] `schema_migration` 表存在，`version` 為主鍵，`applied_at` 有預設值
- [x] V009 連續執行兩次不報錯（`CREATE TABLE IF NOT EXISTS`）
- [x] 以守門寫法包裝的 migration 第一次執行會命中目標列，且在 `schema_migration` 留下一列
- [x] 同一支守門 migration 第二次執行影響 0 列，`schema_migration` 的列數不變

## Execution Result
- Status: DONE — 2026-08-31

V009 建表完成，`version` 為主鍵、`applied_at` 預設 `CURRENT_TIMESTAMP`。連續執行兩次不報錯（`CREATE TABLE IF NOT EXISTS`）。

這張表是本次修正過程中發現的必要品，不是預先規劃的。最初的三支位移 migration 以資料條件守門（`WHERE updated_at < '2026-09-01'`），在 V010 實際套用後才驗證出這個守門根本擋不住重跑——把 `03:07` 推成 `11:07` 之後，該條件依然成立，第二次執行會再推 8 小時、資料變成錯 16 小時，而且錯得沒有任何跡象。`/dev` 每次執行 DBA spec 都會把 `### Migration SQL` 的內容重跑一次，所以這個缺陷必然會被觸發。改以本表守門後，三支位移 migration 全部驗證為重跑影響 0 列。

守門寫法實測有效（見 `stock-sync-progress.md`、`stock.md`、`stock-daily-price.md` 各自的 Increment 紀錄）：第一次執行命中目標列並登錄版本號，第二次執行 `UPDATE` 影響 0 列、`INSERT IGNORE` 不新增。

| version | applied_at |
|---|---|
| V010 | 2026-08-31 11:30:44 |
| V011 | 2026-08-31 11:31:00 |
| V012 | 2026-08-31 11:31:10 |
