---
status: done
title: "上市股票開發種子資料（已併入 stock.md）"
requirement: "產生一些上市股票資料，讓股票總覽的搜尋列可以搜到資料"
---

# 上市股票開發種子資料 — 歷史紀錄

種子資料的現行契約在 [`specs/dba/stock.md`](stock.md) 的 `### Migration SQL — V007__seed_listed_stocks.sql`。本檔只保留最初那次實作的紀錄，不再定義任何內容——要看種子有哪些股票、怎麼寫入，請看 `stock.md`。

## 為何併回 stock.md

種子是對 `stock` 一張表的純資料異動，沒有結構變更。依 `.claude/rules/dba.md`，這種一次性的 data-only migration 屬於它所異動的那張表的 spec，不該自成一個檔案；SQL 也必須寫在 spec 的 `## Migration SQL` 區段裡，而不是獨立的 `.sql` 檔。

最初的實作違反了這兩點，另外還把資料庫帳密寫進了載入指令，而該指令假設服務跑在容器裡（`docker exec`），在以本機原生 MySQL 開發的機器上根本無法執行——這正是種子資料遲遲沒有真正進到資料庫的原因之一。併入 `stock.md` 後由 `/dev` 的 dba agent 直接對現行資料庫套用，兩種環境都適用，spec 內也不再出現任何連線資訊。

## 原始驗收條件（首次實作時已通過）

- [x] 搜尋 `2330` 可找到台積電。
- [x] 搜尋 `台積` 可找到台積電。
- [x] 搜尋 `鴻海`、`聯發科`、`中華電` 等名稱可找到對應股票。
- [x] 重複執行腳本不會增加重複股票。
- [x] 所有種子股票的 `market` 均為 `TSE`、`is_active` 均為 `1`。

同等的驗收條件已改列於 `stock.md` 的 V007 項下，並補上「SQL 必須內嵌於 spec、專案中不存在獨立 `.sql` 檔」一條。
