# User Flow Storyboards

Real-looking screen storyboards inferred from `specs/frontend/` — every frame is a rendered, realistically-populated HTML mockup (not a drawn diagram), captured with `_scripts/render.mjs`. No live app required. Regenerate a flow with `/doc-fronend <group>` after its spec changes.

| 分鏡 | 涵蓋流程 | 步驟 |
|---|---|---|
| [stock-chart](stock-chart.md) | 股票總覽 → 日 K → 分 K 的瀏覽流程 | 5 |
| [strategy](strategy.md) | 更新股票清單 → 同步日 K → 於十一張卡片中勾選三個策略（箱型突破、MACD 黃金交叉、站上均線）掃描（單一命中彙總表，依命中策略數由多到少排序）並自動回測（五個批次框預設勾選，一出現就是報酬率降冪） → 取消勾選「取消買進價高於 500 元」讓被藏起來的筆勾回 → 展開一檔的多個買進日 → 取消其中一筆重算總計（排序位置不動） → 勾選「取消全選」 → 全部勾回 → 勾選「僅選取全符合」只留三個策略都命中過的那幾檔 → 往下捲動、三個總計浮動跟隨 | 13 |
| [momentum](momentum.md) | 查漲幅平均 → 切漲幅加總 → 改用指定週挑週 → 再查一次 → 改依產業漲幅排序，結果按產業別分組並附各區塊平均漲幅 | 6 |
| [simulated-trade](simulated-trade.md) | 空的模擬交易分頁 → 輸入代號 2330 → 加入第一筆持股（表格與三個總計出現） → 再輸入 2317 按 Enter 加入第二筆，兩列依後端排序、總計更新 | 4 |
| [real-trade](real-trade.md) | 空的真實交易分頁 → 填完代號／買進日／買進價／量四格 → 加入第一筆部位（表格與三個總計出現） → 按 Enter 加入第二筆，依買進日由新到舊排序 → 刪除一筆，其餘各筆的 `id` 由後端重新編號 | 5 |
