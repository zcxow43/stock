# User Flow Storyboards

Real-looking screen storyboards inferred from `specs/frontend/` — every frame is a rendered, realistically-populated HTML mockup (not a drawn diagram), captured with `_scripts/render.mjs`. No live app required. Regenerate a flow with `/doc-fronend <group>` after its spec changes.

| 分鏡 | 涵蓋流程 | 步驟 |
|---|---|---|
| [stock-chart](stock-chart.md) | 股票總覽 → 日 K → 分 K 的瀏覽流程 | 5 |
| [strategy](strategy.md) | 更新股票清單 → 同步日 K → 勾選十個策略（含三個法人籌碼型態與 MACD／KDJ 兩個技術指標型態）掃描（單一命中彙總表）並自動回測 → 點「報酬率」表頭排序 → 展開一檔的多個買進日 → 取消其中一筆重算總計（排序位置不動） → 勾選「取消全選」 → 全部勾回 → 勾選「取消買進價高於 500 元」 → 往下捲動、三個總計浮動跟隨 | 13 |
| [momentum](momentum.md) | 查漲幅平均 → 切漲幅加總 → 改用指定週挑週 → 再查一次 → 改依產業漲幅排序，結果按產業別分組並附各區塊平均漲幅 | 6 |
