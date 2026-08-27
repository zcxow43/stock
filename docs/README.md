# 文件索引

## Blueprints (integrated architecture specs)
- [backend](blueprint/backend.md) — 整合 `stock-price-ingestion` 與 `stock-indicator-statistics`：行情入庫到指標推導與統計查詢的完整系統圖景。大方向／圖表導向——欄位、限制條件與 API 契約細節見下方 `docs/backend/` 各文件。

## Backend API 詳細定義
- [stock-price-ingestion](backend/stock-price-ingestion.md) — 股票行情抓取與回補：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出
- [stock-indicator-statistics](backend/stock-indicator-statistics.md) — MACD／KD 指標運算與兩個月統計：欄位定義、限制條件、跨主題規則、完整 API 清單，`/doc-backend` 產出

## ER Model (full schema)
- [er-model](db/er-model.md) — 全 schema 全景圖，加上每個主要功能（market-data / indicator / sync-job）一張完整欄位細節圖；因設計上無實體外鍵，跨群組關聯以 context entity 呈現為邏輯關聯
