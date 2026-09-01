# 文件索引

## Blueprints (integrated architecture specs)
- [backend](blueprint/backend.md) — 整合 `stock-price-ingestion`、`stock-universe-import`、`stock-catalog`、`stock-indicator-statistics`、`stock-minute-price` 與 `strategy-scan`：從匯入上市股票清單、啟動自動補齊、行情入庫、指標推導、策略型態掃描，到前端清單／日 K／分 K 讀取的完整系統圖景。大方向／圖表導向——欄位、限制條件與 API 契約細節見下方 `docs/backend/` 各文件。

## Backend API 詳細定義
- [stock-price-ingestion](backend/stock-price-ingestion.md) — 股票行情抓取與回補：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [stock-indicator-statistics](backend/stock-indicator-statistics.md) — MACD／KD 指標運算與兩個月統計：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [stock-catalog](backend/stock-catalog.md) — 股票清單查詢 API：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [stock-universe-import](backend/stock-universe-import.md) — 上市股票 universe 匯入 API：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [stock-minute-price](backend/stock-minute-price.md) — 分 K 隨選抓取與查詢 API：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [strategy-scan](backend/strategy-scan.md) — 策略型態掃描 API：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出

## ER Model (full schema)
- [er-model](db/er-model.md) — 全 schema 全景圖，加上每個主要功能（market-data / indicator / minute-data / sync-job）一張完整欄位細節圖；因設計上無實體外鍵，跨群組關聯以 context entity 呈現為邏輯關聯

## User Flow Storyboards
- [frontend/README](frontend/README.md) — 由 `specs/frontend/` 推導的真實畫面分鏡，`/doc-fronend` 產出
  - [stock-chart](frontend/stock-chart.md) — 股票總覽 → 日 K → 分 K 的瀏覽流程
  - [strategy](frontend/strategy.md) — 同步日 K → 勾選策略 → 掃描出命中標的
