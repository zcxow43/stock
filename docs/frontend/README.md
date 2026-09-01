# User Flow Storyboards

Real-looking screen storyboards inferred from `specs/frontend/` — every frame is a rendered, realistically-populated HTML mockup (not a drawn diagram), captured with `_scripts/render.mjs`. No live app required. Regenerate a flow with `/doc-fronend <group>` after its spec changes.

| 分鏡 | 涵蓋流程 | 步驟 |
|---|---|---|
| [stock-chart](stock-chart.md) | 股票總覽 → 日 K → 分 K 的瀏覽流程 | 5 |
| [strategy](strategy.md) | 更新股票清單 → 同步日 K → 勾選三個策略掃描 → 再同步顯示「已是最新」 | 6 |
