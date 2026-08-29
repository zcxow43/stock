# 文件索引

## Blueprints (integrated architecture specs)
- [backend](blueprint/backend.md) — 整合 `stock-price-ingestion`、`stock-indicator-statistics`、`stock-catalog` 與 `stock-minute-price`：從應用程式啟動時的自動補齊與指標重算，經行情入庫、指標推導，到前端清單／日 K／分 K 讀取的完整系統圖景。大方向／圖表導向——欄位、限制條件與 API 契約細節見下方 `docs/backend/` 各文件。

## Backend API 詳細定義
- [stock-price-ingestion](backend/stock-price-ingestion.md) — 股票行情抓取與回補：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [stock-indicator-statistics](backend/stock-indicator-statistics.md) — MACD／KD 指標運算與兩個月統計：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [stock-catalog](backend/stock-catalog.md) — 股票清單查詢 API：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [stock-minute-price](backend/stock-minute-price.md) — 分 K 隨選抓取與查詢 API：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出

## ER Model (full schema)
- [er-model](db/er-model.md) — 全 schema 全景圖，加上每個主要功能（market-data / indicator / minute-data / sync-job）一張完整欄位細節圖；因設計上無實體外鍵，跨群組關聯以 context entity 呈現為邏輯關聯

## User Flow Storyboards
- [frontend/README](frontend/README.md) — 由 `specs/frontend/` 推導的真實畫面分鏡，`/doc-fronend` 產出
  - [stock-chart](frontend/stock-chart.md) — 股票總覽 → 日 K → 分 K 的瀏覽流程
