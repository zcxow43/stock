---
status: done
title: "MySQL 服務容器"
requirement: "全系統時區統一為 Asia/Taipei — MySQL 容器目前設 TZ: UTC，使所有由資料庫時鐘寫入的時間戳比本地時間慢 8 小時"
---

# MySQL 服務容器 — Infra Spec

## Overview

`docker/docker-compose.yml` 中 `mysql` 服務的定義。本 spec 只描述服務定義本身，不負責啟動——啟動一律由 `/infra start` 執行。

連線參數（host、port、資料庫名、帳密）一律以 `env.md` 的 `## Database` 區塊為準，本 spec 不重複記載，也不得與其漂移。

## Requirements

### 時區：`Asia/Taipei`

容器的 `TZ` 必須是 **`Asia/Taipei`**，不是 `UTC`。

理由不是偏好，而是正確性：本專案六張表的每一個時間戳欄位都由**資料庫時鐘**寫入（`DEFAULT CURRENT_TIMESTAMP`、`ON UPDATE CURRENT_TIMESTAMP`，以及各 mapper 的 `NOW()`），而應用程式端的日期運算（例如回補的 `endDate` 取「今日」）走的是應用程式所在主機的本地時區。兩者不一致會造成兩類問題：

1. **顯示錯誤**：畫面上的「最後同步」直接取自資料庫寫入的 `finished_at`，容器為 UTC 時會固定比使用者的時鐘慢 8 小時，讓一次剛成功的同步看起來像是幾小時前的舊資料。
2. **跨日邊界的判斷錯誤**：在本地時間 00:00～08:00 之間，UTC 的日期仍停留在前一天。任何混用「應用程式算出的今日」與「資料庫算出的今日」的邏輯，在這段時間內會對同一個時刻得到相差一天的答案。

本系統處理的是台股，`trade_date`、`last_synced_date`、回補區間的起迄日全部都是**台北日曆日**，因此 `Asia/Taipei` 是唯一與資料語意一致的選擇。

### 其餘定義

- 服務名稱維持 `mysql`，容器名稱維持 `stock-mysql`。
- 映像檔、連接埠對應、具名資料卷、健康檢查維持現狀，本次不更動。
- 資料卷內既有的資料**必須保留**——本 spec 不得以重建容器的方式套用變更而清空資料。

## Implementation Details

`docker/docker-compose.yml` 的 `mysql` 服務，`environment` 區塊中的 `TZ` 由 `UTC` 改為 `Asia/Taipei`：

```yaml
    environment:
      TZ: Asia/Taipei
```

其餘 `environment` 項目（root 密碼、資料庫名、帳號、密碼）不變，且必須繼續與 `env.md` 的 `## Database` 區塊一致。

套用方式：改完定義後由 `/infra start` 重建該服務容器。具名資料卷不隨容器移除，既有資料保留。

**既有資料列的時間戳不會因為容器時區改變而自動位移**——它們是以 UTC 寫入的裸 `DATETIME` 值。位移由 DBA 的一次性 migration 負責，見 `specs/dba/stock-sync-progress.md`（V010）、`specs/dba/stock.md`（V011）、`specs/dba/stock-daily-price.md`（V012）。順序上必須**先改時區並重啟容器，再跑那三支 migration**，否則位移後的值又會被新的 UTC 寫入混進來。

## Acceptance Criteria
- [x] `docker/docker-compose.yml` 的 `mysql` 服務 `TZ` 為 `Asia/Taipei`
- [x] 服務重啟後 `SELECT NOW();` 的結果與執行機器的本地時間相差在一分鐘內，不再相差 8 小時
- [x] 服務重啟後 `SELECT @@system_time_zone;` 回報台北時區（`CST`，UTC+8）
- [x] 重啟後既有資料仍在：`stock`、`stock_daily_price`、`stock_sync_progress` 的列數與重啟前相同
- [x] `env.md` 的 `## Database` 連線參數未被更動，且仍可用該帳密連上

## Execution Result
- Status: DONE — 2026-08-31

`docker/docker-compose.yml` 的 `mysql` 服務 `TZ` 由 `UTC` 改為 `Asia/Taipei`，單行變更，其餘定義（image、container_name、ports、具名資料卷、healthcheck、帳密環境變數）一字未動，`docker compose config` 驗證通過。

容器以 `docker compose -p stock -f docker/docker-compose.yml up -d` 重建。**專案名稱必須是 `stock`**：既有容器是以 `-p stock` 建立的，若從 `docker/` 目錄以預設專案名（`docker`）執行，compose 會認不得既有容器而嘗試新建，撞上 `stock-mysql` 名稱衝突而失敗。

驗證（重建後即時量測）：

| 項目 | 結果 |
|---|---|
| `SELECT NOW()` | `2026-08-31 11:27:39` |
| 執行機本地時間 | `2026-08-31 11:27:39 CST` |
| `@@system_time_zone` | `CST` |
| `stock` 列數 | 34（與重建前相同） |
| `stock_daily_price` 列數 | 5372（與重建前相同） |
| `stock_sync_progress` 列數 | 34（與重建前相同） |

資料庫時鐘與本地時鐘完全一致，先前的 8 小時偏移消失；具名資料卷未隨容器移除，三張有資料的表列數與重建前一致。`env.md` 未更動，同一組帳密仍可連線。
