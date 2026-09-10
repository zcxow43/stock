# User Flow Storyboards

Real-looking screen storyboards inferred from `specs/frontend/` — every frame is a rendered, realistically-populated HTML mockup (not a drawn diagram), captured with `_scripts/render.mjs`. No live app required. Regenerate a flow with `/doc-fronend <group>` after its spec changes.

| 分鏡 | 涵蓋流程 | 步驟 |
|---|---|---|
| [stock-chart](stock-chart.md) | 股票總覽 → 日 K → 分 K 的瀏覽流程 | 5 |
| [strategy](strategy.md) | 更新股票清單 → 同步日 K → 勾選五個策略掃描（單一命中彙總表）→ 對命中清單按回測 → 取消勾選一檔重算總計 | 7 |
| [momentum](momentum.md) | 查漲幅平均 → 切漲幅加總 → 改用指定週挑週 → 再查一次 → 改依產業漲幅排序，結果按產業別分組並附各區塊平均漲幅 | 6 |
