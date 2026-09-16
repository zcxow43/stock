---
status: done
title: "三大法人買賣超日表 stock_institutional_trade"
requirement: "法人籌碼策略 — 儲存交易所三大法人買賣超日報（T86）逐檔逐日的買進／賣出／買賣超股數，作為外資（不含外資自營商）與投信的買賣超佔比、連續買超、買超強度排名三個型態的資料來源"
---

# 三大法人買賣超日表 stock_institutional_trade — DBA Spec

## Overview

一列代表「一檔證券在一個交易日的三大法人買賣超」。資料由 `specs/backend/institutional-trade-ingestion.md` 自交易所三大法人買賣超日報寫入，讀取端是 `specs/backend/strategy-scan.md` 的三個法人籌碼型態（法人買賣超佔比、法人連續買超、法人買超強度排名）。

**本 migration 為 V016，接在 V015 之後。** 本表不引用任何其他表（無外鍵，理由見下），因此不受其他 migration 的建立順序約束，接在目前最新的版號之後即可。

## Requirements

### 資料範圍

- 一列 = `(stock_id, trade_date)`。
- **數量單位一律為「股」**（資料源標示「單位：股」），與 `stock_daily_price.volume`（成交股數，見 `specs/dba/stock-daily-price.md`）同一單位。三個型態的佔比與強度都是兩者相除，單位不一致算出來的就是錯的數字。
- **資料源有列才寫入，不補零列。** 資料源只列出當日有法人交易的證券；沒有被列出的證券，本表也不寫入它的列。「該日已抓取、但這一檔沒有列」代表這一檔當日三類法人買賣超皆為 `0`——這個解讀由讀取端依「該日是否有任何一列」判定（見 `specs/backend/strategy-scan.md`），不以補零列表達。補零列會讓每日寫入從約 1,000 列膨脹為主檔全部檔數，而且一列全為 `0` 的列與「根本沒抓」一樣，無法由單列本身區分。

### 為什麼整份日報的欄位都存，而不是只存外資與投信

目前的策略只讀「外陸資（不含外資自營商）」與「投信」兩類的買賣超與成交量。但同一個請求本來就回傳全部十七個數值欄位，多存幾欄的寫入成本相同；而日後若要看自營商或三大法人合計，只存兩類的表必須把整段歷史重抓一次（每個交易日一次請求）。

**欄位按資料源的分類原樣保存，不做任何加總或合併**——「外資要不要含外資自營商」「自營商要看自行買賣還是避險」是讀取端的判定規則，不是資料表該替它決定的事。

### 欄位設計原則

1. **主鍵為 `(stock_id, trade_date)` 複合鍵，不使用 `AUTO_INCREMENT`。** 理由與 `stock_daily_price` 相同：策略掃描以「一批股票 + 日期區間」批次讀取，複合主鍵讓同一檔的列在叢集索引上連續存放。
2. **買進、賣出股數為 `BIGINT UNSIGNED`，買賣超股數為有號 `BIGINT`**（賣超為負值，例如 `-69,992,000`）。與 `stock_daily_price.volume` 同為 `BIGINT`，讀取端把窗口內多日加總後再相除，不會在加總時溢位。
3. **不建外鍵到 `stock`。** 本表與 `stock_daily_price` 同屬行情類事實表，沿用 `specs/dba/stock.md` 不對行情表建外鍵的決定。寫入端本來就只寫主檔已存在的代號（見 `specs/backend/institutional-trade-ingestion.md`），外鍵不會多擋下任何東西。

### 資料源欄位對應

| 資料源 `fields` 欄名 | 欄位 |
|---|---|
| 證券代號 | `stock_id` |
| 證券名稱 | 不儲存（名稱以 `stock` 主檔為準） |
| 外陸資買進股數(不含外資自營商) | `foreign_buy_shares` |
| 外陸資賣出股數(不含外資自營商) | `foreign_sell_shares` |
| 外陸資買賣超股數(不含外資自營商) | `foreign_net_shares` |
| 外資自營商買進股數 | `foreign_dealer_buy_shares` |
| 外資自營商賣出股數 | `foreign_dealer_sell_shares` |
| 外資自營商買賣超股數 | `foreign_dealer_net_shares` |
| 投信買進股數 | `trust_buy_shares` |
| 投信賣出股數 | `trust_sell_shares` |
| 投信買賣超股數 | `trust_net_shares` |
| 自營商買賣超股數 | `dealer_net_shares` |
| 自營商買進股數(自行買賣) | `dealer_self_buy_shares` |
| 自營商賣出股數(自行買賣) | `dealer_self_sell_shares` |
| 自營商買賣超股數(自行買賣) | `dealer_self_net_shares` |
| 自營商買進股數(避險) | `dealer_hedge_buy_shares` |
| 自營商賣出股數(避險) | `dealer_hedge_sell_shares` |
| 自營商買賣超股數(避險) | `dealer_hedge_net_shares` |
| 三大法人買賣超股數 | `total_net_shares` |

## Implementation Details

### Migration SQL — V016__create_stock_institutional_trade.sql

```sql
CREATE TABLE stock_institutional_trade (
    stock_id                   VARCHAR(10)     NOT NULL COMMENT '股票代號，如 2330',
    trade_date                 DATE            NOT NULL COMMENT '交易日期（台北交易日）',
    foreign_buy_shares         BIGINT UNSIGNED NOT NULL COMMENT '外陸資買進股數（不含外資自營商）',
    foreign_sell_shares        BIGINT UNSIGNED NOT NULL COMMENT '外陸資賣出股數（不含外資自營商）',
    foreign_net_shares         BIGINT          NOT NULL COMMENT '外陸資買賣超股數（不含外資自營商），賣超為負',
    foreign_dealer_buy_shares  BIGINT UNSIGNED NOT NULL COMMENT '外資自營商買進股數',
    foreign_dealer_sell_shares BIGINT UNSIGNED NOT NULL COMMENT '外資自營商賣出股數',
    foreign_dealer_net_shares  BIGINT          NOT NULL COMMENT '外資自營商買賣超股數，賣超為負',
    trust_buy_shares           BIGINT UNSIGNED NOT NULL COMMENT '投信買進股數',
    trust_sell_shares          BIGINT UNSIGNED NOT NULL COMMENT '投信賣出股數',
    trust_net_shares           BIGINT          NOT NULL COMMENT '投信買賣超股數，賣超為負',
    dealer_net_shares          BIGINT          NOT NULL COMMENT '自營商買賣超股數（自行買賣＋避險），賣超為負',
    dealer_self_buy_shares     BIGINT UNSIGNED NOT NULL COMMENT '自營商買進股數（自行買賣）',
    dealer_self_sell_shares    BIGINT UNSIGNED NOT NULL COMMENT '自營商賣出股數（自行買賣）',
    dealer_self_net_shares     BIGINT          NOT NULL COMMENT '自營商買賣超股數（自行買賣），賣超為負',
    dealer_hedge_buy_shares    BIGINT UNSIGNED NOT NULL COMMENT '自營商買進股數（避險）',
    dealer_hedge_sell_shares   BIGINT UNSIGNED NOT NULL COMMENT '自營商賣出股數（避險）',
    dealer_hedge_net_shares    BIGINT          NOT NULL COMMENT '自營商買賣超股數（避險），賣超為負',
    total_net_shares           BIGINT          NOT NULL COMMENT '三大法人買賣超股數，賣超為負',
    source                     VARCHAR(20)     NOT NULL COMMENT '資料來源識別，目前固定為 TWSE',
    created_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_id, trade_date),
    KEY idx_trade_date (trade_date)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='三大法人買賣超日報（單位：股）';
```

數值欄位一律 `NOT NULL` 且**不設預設值**：資料源每一列都給齊十七個數值，寫入端漏給任何一欄都應該寫入失敗，而不是被預設值 `0` 靜默吸收成「當天沒有買賣」。

### 索引說明

- `PRIMARY KEY (stock_id, trade_date)`：策略掃描的主要存取路徑——「一批股票 + 日期區間」的範圍讀取。
- `idx_trade_date`：兩個以日期為條件的查詢都走它：抓取端的「區間內哪些交易日已經有資料」（`SELECT DISTINCT trade_date ... WHERE trade_date BETWEEN ...`），以及掃描端的「某日是否已抓取」。

### 寫入語意

以 **UPSERT**（`INSERT ... ON DUPLICATE KEY UPDATE`）寫入，不可先刪後插。重跑同一天必須冪等，且能吸收資料源事後的更正。單日的全部列在同一個交易邊界內寫入——理由在抓取端 spec，此處只規範表的語意。

## Acceptance Criteria

- [x] `stock_institutional_trade` 建立成功，欄位、型別、`NOT NULL`、註解與上述 DDL 完全一致
- [x] 主鍵為 `(stock_id, trade_date)` 複合鍵，表中不存在 `AUTO_INCREMENT` 欄位
- [x] 七個 `*_net_shares` 欄位為有號 `BIGINT`，可寫入負值（如 `-69992000`）；十個 `*_buy_shares`／`*_sell_shares` 欄位為 `BIGINT UNSIGNED`
- [x] 十七個數值欄位皆無預設值：省略任一數值欄位的 `INSERT` 在 strict mode 下失敗
- [x] 表上不存在任何外鍵約束
- [x] 對同一 `(stock_id, trade_date)` 連續 UPSERT 兩次，表中僅一列，且第二次的值覆蓋第一次
- [x] `EXPLAIN` 驗證「多檔股票 + 日期區間」的讀取走主鍵範圍掃描（`key=PRIMARY`），`SELECT DISTINCT trade_date ... WHERE trade_date BETWEEN ...` 走 `idx_trade_date`

## Execution Result

### Increment 1 — 2026-09-16

建立 `stock_institutional_trade` 表（`V016`），7 項驗收全數符合。

**套用**：以 `mysql` CLI 直接對 live 資料庫執行 spec 內嵌的 `CREATE TABLE`，未產生任何獨立的 `.sql` 檔。本次為純 DDL，依 `specs/dba/schema-migration.md` 的規定（只有相對位移類的 migration 需要那道守門）不需寫入 `schema_migration`；該表仍維持最高 `V014`，與 `V015` 的處理一致。

**驗證（皆以 live 資料庫的實際輸出為憑）**：
- `SHOW CREATE TABLE` 的 22 個欄位名稱、型別、`NOT NULL`、註解、`PRIMARY KEY (stock_id, trade_date)`、`KEY idx_trade_date`、引擎與字元集皆與 spec 的 DDL 相同；另以 `HEX()` 比對確認中文註解沒有編碼損壞。
- 複合主鍵成立，且除 `created_at`／`updated_at` 的 `DEFAULT_GENERATED` 外沒有任何 `auto_increment`。
- 有號的 `*_net_shares` 可存負值（實測存入 `-69,992,000`）；無號的 `*_buy/sell_shares` 拒絕負值（`ERROR 1264 Out of range value`）。
- 17 個數值欄位皆無預設值：strict mode 下省略 `total_net_shares` 的 INSERT 被拒（`ERROR 1364`）。
- 無任何外鍵約束（`TABLE_CONSTRAINTS` 只有 `PRIMARY`）。
- 同一 `(stock_id, trade_date)` 連續 UPSERT 兩次後仍只有一列，且值為第二次的內容。
- `EXPLAIN` 證明索引生效：指定多檔加日期區間的查詢走 `PRIMARY` 的 range scan；`DISTINCT trade_date` 的區間查詢走 `idx_trade_date` 且為 covering index（`Using where; Using index`）。

**資料狀態**：驗證用的測試列（`9999`、`6666`）已刪除，表回到原本的空狀態（前後列數皆為 0）。
