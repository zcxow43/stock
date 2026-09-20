// @vitest-environment node
/// <reference types="node" />
//
// specs/frontend/stock-list.md「漲跌色實際生效（本次新增）」guards against a bug where
// `.sl-table tbody td { color: #e6edf5 }` (specificity 0,1,2) always outranked
// `.sl-up`/`.sl-down`/`.sl-neutral`/`.sl-flat` (specificity 0,1,0) put directly on the
// same `<td>`, so every 漲跌/漲跌幅 cell painted the default text color no matter which
// class it carried. The pre-existing component tests only asserted the *class name* was
// present on the cell, which passed even while the rendered color was wrong.
//
// jsdom's `getComputedStyle` does not reliably apply real CSS cascade/specificity from
// an imported stylesheet (there is no layout/paint engine backing it), so a jsdom test
// asserting on a rendered React component could not have caught this bug and cannot be
// trusted to catch a regression of it either. This suite instead loads the *real*
// StockListPage.css into a real Chromium page (via Playwright, already a project
// devDependency) with markup shaped like the actual table output, and reads the
// genuinely-painted `getComputedStyle(td).color` — the same technique used to originally
// diagnose the bug.
//
// specs/frontend/momentum.md「漲幅欄漲跌色實際生效（本次新增）」 also rides on this same
// shared fix: `MomentumTab.tsx` renders its 漲幅 column as a plain `<td>` inside
// `<table class="sl-table mt-industry-table">`, nested under the page's
// `.stock-list-page` container (see `StockListPage.tsx`), with `MomentumTab.css` loaded
// alongside `StockListPage.css` in the same document (both are imported and mounted
// together — `MomentumTab` is always rendered inside `StockListPage`). `MomentumTab.css`
// declares no `td`/`color` rule of its own (`.mt-industry-table` only resets
// border/border-radius), so this suite loads both stylesheets together and asserts the
// same computed-color check against markup shaped like `renderIndustryBlock`'s actual
// output, including the non-table 產業別區塊標題 average-gain `<span>` (not a `<td>`, and
// not expected to have been affected by the bug or its fix).
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { chromium, type Browser } from 'playwright'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const pagesDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../pages')
const css = readFileSync(path.join(pagesDir, 'StockListPage.css'), 'utf-8')
const momentumCss = readFileSync(path.join(pagesDir, 'MomentumTab.css'), 'utf-8')

// Rgb equivalents of the literal hex values this spec pins down, so failures show a
// value directly comparable to `## Visual Style`'s table instead of a raw `rgb(...)`.
const RED_UP = 'rgb(224, 75, 69)' // #E04B45
const GREEN_DOWN = 'rgb(22, 167, 92)' // #16A75C
const FLAT = 'rgb(147, 164, 184)' // #93A4B8
const PRIMARY_TEXT = 'rgb(230, 237, 245)' // #E6EDF5
const WEAK_TEXT = 'rgb(107, 124, 144)' // #6B7C90
const CHECKED_BG = 'rgb(62, 143, 216)' // #3E8FD8
const CHECK_MARK_WHITE = 'rgb(255, 255, 255)' // #FFFFFF
const UNCHECKED_BG = 'rgb(15, 22, 32)' // #0F1620
const UNCHECKED_BORDER = 'rgb(38, 51, 63)' // #26333F
const LABEL_TEXT = 'rgb(147, 164, 184)' // #93A4B8
// specs/frontend/strategy.md「掃描後自動回測」— 「重試回測」reuses the page's shared
// secondary/disabled button colors (`## Visual Style`「次要按鈕背景／文字／邊框」/
// 「Disabled 按鈕背景／文字」), not a bespoke color of its own.
const SECONDARY_BTN_BG = 'rgb(27, 40, 54)' // #1B2836
const SECONDARY_BTN_BORDER = 'rgb(38, 51, 63)' // #26333F
const DISABLED_BTN_BG = 'rgb(22, 32, 44)' // #16202C
const DISABLED_BTN_TEXT = 'rgb(74, 88, 102)' // #4A5866
// specs/frontend/strategy.md「取消買進價高於 N 元」勾選框 — the amount input's own literal
// colors (「輸入框」row's values, not new ones) and its invalid-input hint text.
const INPUT_TEXT = 'rgb(230, 237, 245)' // #E6EDF5
const ERROR_TEXT = 'rgb(240, 154, 148)' // #F09A94
// specs/frontend/strategy.md「總計浮動跟隨」— the floating block's own background/border.
const PANEL_BG = 'rgb(22, 32, 44)' // #16202C
const PANEL_BORDER = 'rgb(38, 51, 63)' // #26333F

function pageHtml(): string {
  return `<!doctype html>
<html>
<head><meta charset="utf-8"><style>${css}\n${momentumCss}</style></head>
<body>
  <div class="stock-list-page">
    <div class="sl-table-wrap">
      <table class="sl-table">
        <tbody>
          <tr class="sl-row">
            <td class="sl-code" id="cell-plain">2330</td>
            <td class="sl-r sl-up" id="cell-up">+1.23</td>
            <td class="sl-r sl-down" id="cell-down">-1.23</td>
            <td class="sl-r sl-neutral" id="cell-neutral">0.00</td>
            <td class="sl-r sl-flat" id="cell-flat">0.00</td>
          </tr>
          <tr class="sl-row st-row-unchecked">
            <td class="sl-code" id="cell-unchecked-plain">2330</td>
            <td class="sl-r sl-up" id="cell-unchecked-up">+1.23</td>
            <td class="sl-r sl-down" id="cell-unchecked-down">-1.23</td>
          </tr>
        </tbody>
      </table>
    </div>
    <!-- specs/frontend/strategy.md「命中彙總表」的合併表格 — markup lifted verbatim from
         StrategyTab.tsx's renderMergedTable (checkbox col + 賣出日/報酬率/收益 cells, the
         null-value 「—」 wrapped in a nested <span class="sl-muted">, and the unchecked-row
         class on <tr>), not a hand-simplified stand-in. -->
    <div class="strategy-tab">
      <!-- specs/frontend/strategy.md「滑鼠游標」— 回測前（沒有勾選框欄）沒有 st-union-table-
           backtested 這個修飾 class，列本體維持預設箭頭；hover 背景規則不受影響。 -->
      <table class="sl-table st-result-table st-union-table">
        <tbody>
          <tr class="sl-row" id="cursor-row-pre-backtest">
            <td>2330 台積電</td>
            <td><div class="st-union-hits">箱型突破 2026-08-27</div></td>
          </tr>
        </tbody>
      </table>
      <table class="sl-table st-result-table st-union-table st-union-table-backtested">
        <tbody>
          <tr class="sl-row" id="cursor-row-post-backtest">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" checked /></td>
            <td id="st-cell-plain">2330 台積電</td>
            <td><div class="st-union-hits">箱型突破 2026-08-27</div></td>
            <td id="st-cell-selldate">2026-09-01</td>
            <td class="sl-r sl-up" id="st-cell-return-up">1.24%</td>
            <td class="sl-r sl-up" id="st-cell-profit-up">30,000</td>
          </tr>
          <tr class="sl-row">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" checked /></td>
            <td>2317 鴻海</td>
            <td><div class="st-union-hits">底底高 2026-08-28</div></td>
            <td>2026-09-02</td>
            <td class="sl-r sl-down" id="st-cell-return-down">-4.17%</td>
            <td class="sl-r sl-down" id="st-cell-profit-down">-100,000</td>
          </tr>
          <tr class="sl-row">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" checked /></td>
            <td>2454 聯發科</td>
            <td><div class="st-union-hits">箱型突破 2026-08-20</div></td>
            <td><span class="sl-muted" id="st-cell-selldate-null">—</span></td>
            <td class="sl-r sl-muted" id="st-cell-return-null"><span class="sl-muted">—</span></td>
            <td class="sl-r sl-muted" id="st-cell-profit-null"><span class="sl-muted">—</span></td>
          </tr>
          <tr class="sl-row st-row-unchecked">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" /></td>
            <td id="st-cell-unchecked-plain">2454 聯發科</td>
            <td><div class="st-union-hits">箱型突破 2026-08-20</div></td>
            <td id="st-cell-unchecked-selldate">2026-09-01</td>
            <td class="sl-r sl-up" id="st-cell-unchecked-return">1.24%</td>
            <td class="sl-r sl-up" id="st-cell-unchecked-profit">30,000</td>
          </tr>
        </tbody>
      </table>
      <!-- 標題右側總報酬率／總收益標籤 — same st-total-value + signColorClass structure. -->
      <span class="st-total-value sl-up" id="st-total-up">2.48%</span>
      <span class="st-total-value sl-down" id="st-total-down">-3.60%</span>
      <span class="st-total-value sl-neutral" id="st-total-neutral">0.00%</span>
      <!-- specs/frontend/strategy.md「取消全選」勾選框 — markup lifted from
           StrategyTab.tsx's renderMergedTable: reuses .st-row-checkbox verbatim (no
           second custom-checkbox look invented), label text in .st-total-label. -->
      <label class="st-total-item st-selectall-item">
        <input type="checkbox" class="st-row-checkbox" id="selectall-checked" checked />
        <span class="st-total-label" id="selectall-label-checked">取消全選</span>
      </label>
      <label class="st-total-item st-selectall-item">
        <input type="checkbox" class="st-row-checkbox" id="selectall-unchecked" />
        <span class="st-total-label" id="selectall-label-unchecked">取消全選</span>
      </label>
      <!-- specs/frontend/strategy.md「掃描後自動回測」— 回測中… hint text beside 開始掃描
           (auto-backtest in flight), and the 「重試回測」button (secondary style, in its
           enabled and its disabled/回測中… states). Markup lifted verbatim from
           StrategyTab.tsx's own JSX for these three elements. -->
      <span class="st-backtest-hint" id="backtest-hint">回測中…</span>
      <button type="button" class="sl-btn" id="retry-btn-enabled">重試回測</button>
      <button type="button" class="sl-btn" id="retry-btn-disabled" disabled>回測中…</button>
      <!-- specs/frontend/strategy.md「父列逐筆日期」— one line per position in the parent
           row's 買進日/賣出日 cells (checked inherits the default primary text color,
           unchecked reuses .sl-muted verbatim), plus the parent row's always-muted-dash
           賣出價 cell compared against a normal row's own 賣出價 in the same column, to
           verify the two right-align identically (「與同欄其他列的賣出價同樣靠右對齊」). -->
      <table class="sl-table st-result-table st-union-table st-union-table-backtested">
        <tbody>
          <tr class="sl-row">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" checked /></td>
            <td>2330 台積電</td>
            <td><div class="st-union-hits">箱型突破 2026-08-27・底底高 2026-08-25</div></td>
            <td>
              <div id="parent-buydate-checked">2026-08-27</div>
              <div class="sl-muted" id="parent-buydate-unchecked">2026-08-20</div>
            </td>
            <td class="sl-r">5,050.00</td>
            <td>
              <div id="parent-selldate-checked">2026-08-28</div>
              <div class="sl-muted" id="parent-selldate-unchecked">2026-08-25</div>
            </td>
            <td class="sl-r" id="parent-sellprice-cell"><span class="sl-muted">—</span></td>
            <td class="sl-r sl-down">-9.80%</td>
            <td class="sl-r sl-down">-990,000</td>
          </tr>
          <tr class="sl-row">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" checked /></td>
            <td>2317 鴻海</td>
            <td><div class="st-union-hits">底底高 2026-08-28</div></td>
            <td>2026-08-28</td>
            <td class="sl-r">100.00</td>
            <td>2026-09-01</td>
            <td class="sl-r" id="normal-sellprice-cell">110.00</td>
            <td class="sl-r sl-up">10.00%</td>
            <td class="sl-r sl-up">10,000</td>
          </tr>
        </tbody>
      </table>
      <!-- specs/frontend/strategy.md「買進價與報酬率欄排序」— the two sortable headers'
           idle (「↕」, #6B7C90 icon / #93A4B8 inherited label) and active-sorted (「▼」/「▲」,
           #3E8FD8 icon / #E6EDF5 inherited label) states. Markup lifted verbatim from
           StrategyTab.tsx's renderSortableHeader. -->
      <table class="sl-table st-result-table st-union-table st-union-table-backtested">
        <thead>
          <tr>
            <th class="sl-r st-sortable" id="sort-header-idle">
              <span class="st-sort-label" id="sort-label-idle">買進價</span><span class="st-sort-icon st-sort-icon-idle" id="sort-icon-idle">↕</span>
            </th>
            <th class="sl-r st-sortable st-sort-header-active" id="sort-header-active">
              <span class="st-sort-label" id="sort-label-active">報酬率</span><span class="st-sort-icon st-sort-icon-active" id="sort-icon-active">▼</span>
            </th>
          </tr>
        </thead>
      </table>
      <!-- specs/frontend/strategy.md「總成本」— the third totals label, between 取消全選
           and 總報酬率. Value never carries a sign color (always inherits the primary text
           color); the no-value dash reuses .sl-muted verbatim like the other two totals. -->
      <div class="st-total-item">
        <span class="st-total-label" id="total-cost-label">總成本（每筆 1 張）</span>
        <span class="st-total-value" id="total-cost-value">4,850,000</span>
      </div>
      <div class="st-total-item">
        <span class="st-total-label">總成本（每筆 1 張）</span>
        <span class="st-total-value"><span class="sl-muted" id="total-cost-dash">—</span></span>
      </div>
      <!-- specs/frontend/strategy.md「取消買進價高於 N 元」勾選框 — checked/unchecked reuse
           .st-row-checkbox verbatim (same literal colors already pinned down above for
           取消全選), plus this control's own disabled state (checkbox + label text) and the
           amount input's own background/text/border/focus-border, and its own #F09A94
           validation hint. Markup lifted verbatim from StrategyTab.tsx's renderMergedTable. -->
      <div class="st-total-item st-price-threshold-item">
        <div class="st-price-threshold-row">
          <input type="checkbox" class="st-row-checkbox" id="price-threshold-checked" checked />
          <span class="st-total-label" id="price-threshold-label">取消買進價高於</span>
          <input type="number" min="0" step="0.01" class="st-price-threshold-input" id="price-threshold-input" value="500" />
          <span class="st-total-label">元</span>
        </div>
      </div>
      <div class="st-total-item st-price-threshold-item">
        <div class="st-price-threshold-row">
          <input type="checkbox" class="st-row-checkbox" id="price-threshold-unchecked" />
        </div>
      </div>
      <div class="st-total-item st-price-threshold-item">
        <div class="st-price-threshold-row">
          <input type="checkbox" class="st-row-checkbox" id="price-threshold-disabled" disabled />
          <span class="st-total-label st-total-label-disabled" id="price-threshold-label-disabled">取消買進價高於</span>
        </div>
        <p class="st-inline-error st-price-threshold-hint" id="price-threshold-hint">金額需為 0 以上、最多兩位小數</p>
      </div>
      <!-- specs/frontend/strategy.md「批次勾選框列」/「僅選取報酬率 n% 以上」勾選框 — the
           amount input's own literal colors and disabled-state text, reusing
           .st-price-threshold-* verbatim (「與『取消買進價高於 N 元』同欄位同值」). -->
      <div class="st-total-item st-price-threshold-item">
        <div class="st-price-threshold-row">
          <input type="checkbox" class="st-row-checkbox" id="return-threshold-checked" checked />
          <span class="st-total-label" id="return-threshold-label">僅選取報酬率</span>
          <input type="number" min="-100" max="100" step="0.01" class="st-price-threshold-input" id="return-threshold-input" value="2" />
          <span class="st-total-label">% 以上</span>
        </div>
      </div>
      <div class="st-total-item st-price-threshold-item">
        <div class="st-price-threshold-row">
          <input type="checkbox" class="st-row-checkbox" id="return-threshold-unchecked" />
        </div>
      </div>
      <div class="st-total-item st-price-threshold-item">
        <div class="st-price-threshold-row">
          <input type="checkbox" class="st-row-checkbox" id="return-threshold-disabled" disabled />
          <span class="st-total-label st-total-label-disabled" id="return-threshold-label-disabled">僅選取報酬率</span>
        </div>
        <p class="st-inline-error st-price-threshold-hint" id="return-threshold-hint">報酬率需介於 -100 ~ 100、最多兩位小數</p>
      </div>
      <!-- specs/frontend/strategy.md「批次勾選框列」/「隱藏資料不齊（無賣出日）」勾選框 —
           the real four-item .st-batch-controls row (取消全選／取消買進價高於／僅選取報酬率
           ／隱藏資料不齊), markup lifted verbatim from StrategyTab.tsx's renderMergedTable,
           used both for this row's own equal-height/vertical-center layout check and
           (separately below) 隱藏資料不齊's own checked/unchecked/disabled painted colors. -->
      <div class="st-batch-controls" id="batch-controls-row">
        <label class="st-total-item st-selectall-item" id="batch-cancel-all-item">
          <input type="checkbox" class="st-row-checkbox" id="batch-cancel-all-checkbox" checked />
          <span class="st-total-label">取消全選</span>
        </label>
        <div class="st-total-item st-price-threshold-item" id="batch-price-threshold-item">
          <div class="st-price-threshold-row">
            <input type="checkbox" class="st-row-checkbox" id="batch-price-threshold-checkbox" />
            <span class="st-total-label">取消買進價高於</span>
            <input type="number" min="0" step="0.01" class="st-price-threshold-input" value="500" />
            <span class="st-total-label">元</span>
          </div>
        </div>
        <div class="st-total-item st-price-threshold-item" id="batch-return-threshold-item">
          <div class="st-price-threshold-row">
            <input type="checkbox" class="st-row-checkbox" id="batch-return-threshold-checkbox" />
            <span class="st-total-label">僅選取報酬率</span>
            <input type="number" min="-100" max="100" step="0.01" class="st-price-threshold-input" value="2" />
            <span class="st-total-label">% 以上</span>
          </div>
        </div>
        <label class="st-selectall-item st-incomplete-item" id="batch-incomplete-item">
          <input type="checkbox" class="st-row-checkbox" id="batch-incomplete-checkbox" checked />
          <span class="st-total-label">隱藏資料不齊（無賣出日）</span>
        </label>
      </div>
      <label class="st-selectall-item st-incomplete-item">
        <input type="checkbox" class="st-row-checkbox" id="incomplete-unchecked" />
        <span class="st-total-label" id="incomplete-label-unchecked">隱藏資料不齊（無賣出日）</span>
      </label>
      <label class="st-selectall-item st-incomplete-item">
        <input type="checkbox" class="st-row-checkbox" id="incomplete-disabled" disabled />
        <span class="st-total-label st-total-label-disabled" id="incomplete-label-disabled">隱藏資料不齊（無賣出日）</span>
      </label>
      <!-- specs/frontend/strategy.md「隱藏的列」— 「另 K 筆已隱藏」, same .st-uncounted-note
           class as the other 未計入 notes. -->
      <p class="st-uncounted-note" id="hidden-count-note">另 3 筆已隱藏</p>
      <!-- specs/frontend/strategy.md「總計浮動跟隨」— the fixed-position floating copy of
           the totals triplet; reuses .st-total-item/.st-total-value verbatim (same
           computation, same color rules, per spec), this block only adds its own
           background/border. -->
      <div class="st-floating-totals" id="floating-totals">
        <div class="st-total-item">
          <span class="st-total-label">總成本（每筆 1 張）</span>
          <span class="st-total-value" id="floating-total-cost">4,850,000</span>
        </div>
        <div class="st-total-item">
          <span class="st-total-label">總報酬率</span>
          <span class="st-total-value sl-up" id="floating-total-return">2.48%</span>
        </div>
      </div>
      <!-- specs/frontend/strategy.md「以週選擇區間」— the 週選擇 control (background/text/
           border, and its focus-within border) and the「實際區間」hint text, plus the two
           快捷區間鈕 states. Markup lifted verbatim from StrategyTab.tsx's WeekSelector +
           shortcut buttons. -->
      <div class="st-week-select" id="week-select">
        <button type="button" class="st-week-step" id="week-step-prev">‹</button>
        <span class="st-week-caption">2026 第 31 週（07/27–08/02）</span>
        <button type="button" class="st-week-step">›</button>
      </div>
      <div class="st-range-hint" id="range-hint">實際區間 2026-07-27 ~ 2026-08-30</div>
      <button type="button" class="st-shortcut" id="shortcut-inactive">近三個月</button>
      <button type="button" class="st-shortcut st-shortcut-active" id="shortcut-active">近一個月</button>
    </div>
    <!-- specs/frontend/momentum.md「漲幅欄漲跌色實際生效」— markup lifted from
         MomentumTab.tsx's renderIndustryBlock: the 產業別區塊標題 avgGain span (not a
         table cell) and the 漲幅 column td inside table.sl-table.mt-industry-table,
         each carrying the same gainClass() output the component actually emits. -->
    <div class="momentum-tab">
      <div class="mt-industry-block">
        <h3 class="mt-industry-title">
          <span>鋼鐵工業</span>
          <span class="mt-industry-count">　22 檔　</span>
          <span class="mt-industry-count" title="...">平均</span>
          <span class="sl-up" id="mt-title-avg-up">+2.32%</span>
        </h3>
        <table class="sl-table mt-industry-table">
          <tbody>
            <tr class="sl-row">
              <td class="sl-code">2330</td>
              <td>台積電</td>
              <td class="sl-r sl-up" id="mt-cell-up">+2.32%</td>
              <td class="sl-r">20</td>
              <td class="sl-r">100.00</td>
              <td class="sl-r">102.32</td>
            </tr>
            <tr class="sl-row">
              <td class="sl-code">2317</td>
              <td>鴻海</td>
              <td class="sl-r sl-down" id="mt-cell-down">-1.04%</td>
              <td class="sl-r">20</td>
              <td class="sl-r">100.00</td>
              <td class="sl-r">98.96</td>
            </tr>
            <tr class="sl-row">
              <td class="sl-code">2454</td>
              <td>聯發科</td>
              <td class="sl-r sl-flat" id="mt-cell-zero">0.00%</td>
              <td class="sl-r">20</td>
              <td class="sl-r">100.00</td>
              <td class="sl-r">100.00</td>
            </tr>
            <tr class="sl-row">
              <td class="sl-code" id="mt-cell-plain">2412</td>
              <td>中華電</td>
              <td class="sl-r sl-flat" id="mt-cell-null">—</td>
              <td class="sl-r">20</td>
              <td class="sl-r">100.00</td>
              <td class="sl-r">100.00</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="mt-industry-block">
        <h3 class="mt-industry-title">
          <span class="mt-unclassified-name">未分類</span>
          <span class="mt-industry-count">　3 檔　</span>
          <span class="mt-industry-count" title="...">平均</span>
          <span class="sl-flat" id="mt-title-avg-flat">0.00%</span>
        </h3>
      </div>
    </div>
  </div>
</body>
</html>`
}

let browser: Browser

beforeAll(async () => {
  browser = await chromium.launch()
})

afterAll(async () => {
  await browser.close()
})

describe.each(['dark', 'light'] as const)(
  '.sl-table cell colors under prefers-color-scheme: %s',
  (colorScheme) => {
    async function computedColors() {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const ids = [
          'cell-plain',
          'cell-up',
          'cell-down',
          'cell-neutral',
          'cell-flat',
          'cell-unchecked-plain',
          'cell-unchecked-up',
          'cell-unchecked-down',
          // specs/frontend/strategy.md「報酬率／收益 must actually render red/green」 —
          // same markup shape StrategyTab.tsx's merged 命中彙總 table actually emits.
          'st-cell-plain',
          'st-cell-selldate',
          'st-cell-return-up',
          'st-cell-profit-up',
          'st-cell-return-down',
          'st-cell-profit-down',
          'st-cell-selldate-null',
          'st-cell-return-null',
          'st-cell-profit-null',
          'st-cell-unchecked-plain',
          'st-cell-unchecked-selldate',
          'st-cell-unchecked-return',
          'st-cell-unchecked-profit',
          'st-total-up',
          'st-total-down',
          'st-total-neutral',
          // specs/frontend/momentum.md「漲幅欄漲跌色實際生效」
          'mt-cell-up',
          'mt-cell-down',
          'mt-cell-zero',
          'mt-cell-null',
          'mt-cell-plain',
          'mt-title-avg-up',
          'mt-title-avg-flat',
          // specs/frontend/strategy.md「掃描後自動回測」/「父列逐筆日期」
          'backtest-hint',
          'parent-buydate-checked',
          'parent-buydate-unchecked',
          'parent-selldate-checked',
          'parent-selldate-unchecked',
          // specs/frontend/strategy.md「買進價與報酬率欄排序」
          'sort-label-idle',
          'sort-icon-idle',
          'sort-label-active',
          'sort-icon-active',
          // specs/frontend/strategy.md「總成本」
          'total-cost-label',
          'total-cost-value',
          'total-cost-dash',
          // specs/frontend/strategy.md「取消買進價高於 N 元」勾選框
          'price-threshold-label',
          'price-threshold-label-disabled',
          'price-threshold-hint',
          // specs/frontend/strategy.md「僅選取報酬率 n% 以上」勾選框
          'return-threshold-label',
          'return-threshold-label-disabled',
          'return-threshold-hint',
          // specs/frontend/strategy.md「總計浮動跟隨」
          'floating-total-cost',
          'floating-total-return',
          // specs/frontend/strategy.md「以週選擇區間」
          'range-hint',
          // specs/frontend/strategy.md「隱藏資料不齊（無賣出日）」勾選框 / 「另 K 筆已隱藏」
          'incomplete-label-unchecked',
          'incomplete-label-disabled',
          'hidden-count-note',
        ] as const
        return await page.evaluate((cellIds) => {
          const out: Record<string, string> = {}
          for (const id of cellIds) {
            const el = document.getElementById(id)
            if (!el) throw new Error(`missing #${id} in fixture markup`)
            out[id] = getComputedStyle(el).color
          }
          return out
        }, ids)
      } finally {
        await context.close()
      }
    }

    it('colors 漲跌/漲跌幅 cells by sign, and leaves other columns at the primary text color', async () => {
      const colors = await computedColors()
      expect(colors['cell-up']).toBe(RED_UP)
      expect(colors['cell-down']).toBe(GREEN_DOWN)
      expect(colors['cell-neutral']).toBe(FLAT)
      expect(colors['cell-flat']).toBe(FLAT)
      // Other columns (代號/名稱/收盤價/...) must stay the primary text color — the
      // fix must not be a blanket color removal that accidentally greys everything.
      expect(colors['cell-plain']).toBe(PRIMARY_TEXT)
    })

    it('still lets an unchecked row (st-row-unchecked) grey out its own up/down cells', async () => {
      const colors = await computedColors()
      expect(colors['cell-unchecked-plain']).toBe(WEAK_TEXT)
      expect(colors['cell-unchecked-up']).toBe(WEAK_TEXT)
      expect(colors['cell-unchecked-down']).toBe(WEAK_TEXT)
    })

    // specs/frontend/strategy.md「勾選框改為回測後才出現、報酬率與收益的漲跌色實際生效」
    // — verifies the strategy tab's merged 命中彙總 table specifically, by computed color,
    // not by which class is present on the cell (the old bug shipped with the class always
    // present but the wrong color painted).
    it('colors the strategy table 報酬率／收益 cells by sign, and leaves 代號/名稱 and 賣出日 at the primary text color', async () => {
      const colors = await computedColors()
      expect(colors['st-cell-return-up']).toBe(RED_UP)
      expect(colors['st-cell-profit-up']).toBe(RED_UP)
      expect(colors['st-cell-return-down']).toBe(GREEN_DOWN)
      expect(colors['st-cell-profit-down']).toBe(GREEN_DOWN)
      expect(colors['st-cell-plain']).toBe(PRIMARY_TEXT)
      expect(colors['st-cell-selldate']).toBe(PRIMARY_TEXT)
    })

    it('keeps the strategy table\'s 「—」 on a non-backtestable row at the weak text color', async () => {
      const colors = await computedColors()
      expect(colors['st-cell-selldate-null']).toBe(WEAK_TEXT)
      expect(colors['st-cell-return-null']).toBe(WEAK_TEXT)
      expect(colors['st-cell-profit-null']).toBe(WEAK_TEXT)
    })

    it('still greys out an unchecked strategy-table row, including its own up-colored 報酬率／收益', async () => {
      const colors = await computedColors()
      expect(colors['st-cell-unchecked-plain']).toBe(WEAK_TEXT)
      expect(colors['st-cell-unchecked-selldate']).toBe(WEAK_TEXT)
      expect(colors['st-cell-unchecked-return']).toBe(WEAK_TEXT)
      expect(colors['st-cell-unchecked-profit']).toBe(WEAK_TEXT)
    })

    it('colors the 總報酬率／總收益 total labels by sign the same way as the row cells', async () => {
      const colors = await computedColors()
      expect(colors['st-total-up']).toBe(RED_UP)
      expect(colors['st-total-down']).toBe(GREEN_DOWN)
      expect(colors['st-total-neutral']).toBe(FLAT)
    })

    // specs/frontend/momentum.md「漲幅欄漲跌色實際生效（本次新增）」— MomentumTab.tsx's
    // 漲幅 column `<td>` sits under the exact same `.sl-table tbody td` rule as the
    // overview/strategy tables, with MomentumTab.css loaded alongside StockListPage.css
    // and declaring no competing `td`/`color` rule of its own. Verified by computed
    // color, not by which class the cell carries.
    it('colors the momentum table 漲幅 cells by sign, and leaves 代號 at the primary text color', async () => {
      const colors = await computedColors()
      expect(colors['mt-cell-up']).toBe(RED_UP)
      expect(colors['mt-cell-down']).toBe(GREEN_DOWN)
      expect(colors['mt-cell-zero']).toBe(FLAT)
      expect(colors['mt-cell-null']).toBe(FLAT)
      expect(colors['mt-cell-plain']).toBe(PRIMARY_TEXT)
    })

    // The 產業別區塊標題 average-gain `<span>` is not a table cell and was never affected
    // by the `.sl-table tbody td` specificity bug — confirm the shared fix left it alone.
    it('still colors the 產業別區塊標題 average-gain span by sign (unaffected by the td fix)', async () => {
      const colors = await computedColors()
      expect(colors['mt-title-avg-up']).toBe(RED_UP)
      expect(colors['mt-title-avg-flat']).toBe(FLAT)
    })

    // specs/frontend/strategy.md「取消全選」勾選框 — reuses .st-row-checkbox verbatim, so
    // this pins down the same rendered colors the merged table's own row checkboxes
    // already rely on, plus the always-「取消全選」label text color.
    // specs/frontend/strategy.md「滑鼠游標」— jsdom does not apply real CSS
    // cascade/specificity from an imported stylesheet, so this must be verified the same
    // way as the color checks above: load the real stylesheet into a real Chromium page
    // and read the genuinely-computed `cursor`, not just which class the `<tr>` carries.
    it('renders cursor: default on a merged-table row before 回測, and cursor: pointer once the checkbox column exists after 回測', async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const preRow = document.getElementById('cursor-row-pre-backtest')!
          const postRow = document.getElementById('cursor-row-post-backtest')!
          return {
            preCursor: getComputedStyle(preRow).cursor,
            postCursor: getComputedStyle(postRow).cursor,
          }
        })
        expect(result.preCursor).toBe('default')
        expect(result.postCursor).toBe('pointer')
      } finally {
        await context.close()
      }
    })

    // specs/frontend/strategy.md「掃描後自動回測」— the 回測中… hint text beside 開始掃描
    // (auto-backtest in flight, no button) must actually paint at the secondary text
    // color, not the primary text color it would inherit without its own class.
    it('colors the 回測中… hint text at the secondary text color', async () => {
      const colors = await computedColors()
      expect(colors['backtest-hint']).toBe(FLAT)
    })

    // specs/frontend/strategy.md「父列逐筆日期」— each line in the parent row's collapsed
    // 買進日／賣出日 cells is either the default primary text color (checked, inherited —
    // no class of its own) or the reused .sl-muted weak text color (unchecked), verified by
    // actual computed color rather than by which class the line's own `<div>` carries.
    it('colors each parent-row date line by its own checked state: primary text when checked, weak text (.sl-muted) when unchecked', async () => {
      const colors = await computedColors()
      expect(colors['parent-buydate-checked']).toBe(PRIMARY_TEXT)
      expect(colors['parent-buydate-unchecked']).toBe(WEAK_TEXT)
      expect(colors['parent-selldate-checked']).toBe(PRIMARY_TEXT)
      expect(colors['parent-selldate-unchecked']).toBe(WEAK_TEXT)
    })

    // specs/frontend/strategy.md「父列賣出價恆顯示 #6B7C90 的「—」，且與同欄其他列的賣出價
    // 同樣靠右對齊」— the bug this guards against: the live app rendered this cell
    // left-aligned (missing the `sl-r` class), a real layout defect only visible at the
    // actually-painted position, not from source alone. Verified by comparing the two
    // cells' genuinely-rendered right edge (`getBoundingClientRect().right`), not just
    // that both carry the same class name.
    it("right-aligns the parent row's muted 賣出價 dash exactly like a normal row's own 賣出價 in the same column", async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const parentCell = document.getElementById('parent-sellprice-cell')!
          const normalCell = document.getElementById('normal-sellprice-cell')!
          return {
            parentTextAlign: getComputedStyle(parentCell).textAlign,
            parentRight: parentCell.getBoundingClientRect().right,
            normalRight: normalCell.getBoundingClientRect().right,
          }
        })
        expect(result.parentTextAlign).toBe('right')
        expect(result.parentRight).toBe(result.normalRight)
      } finally {
        await context.close()
      }
    })

    // specs/frontend/strategy.md「掃描後自動回測」— 「重試回測」reuses the shared secondary
    // button look in its clickable state, and the shared disabled-button look while a retry
    // is in flight (回測中…) — verified by actual computed `backgroundColor`/`color`/
    // `borderColor`, not by which class the `<button>` carries.
    it('renders 「重試回測」with the secondary button colors when enabled, and the disabled button colors while retrying (回測中…)', async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const enabled = document.getElementById('retry-btn-enabled')!
          const disabled = document.getElementById('retry-btn-disabled')!
          return {
            enabledBg: getComputedStyle(enabled).backgroundColor,
            enabledText: getComputedStyle(enabled).color,
            enabledBorder: getComputedStyle(enabled).borderTopColor,
            disabledBg: getComputedStyle(disabled).backgroundColor,
            disabledText: getComputedStyle(disabled).color,
          }
        })
        expect(result.enabledBg).toBe(SECONDARY_BTN_BG)
        expect(result.enabledText).toBe(PRIMARY_TEXT)
        expect(result.enabledBorder).toBe(SECONDARY_BTN_BORDER)
        expect(result.disabledBg).toBe(DISABLED_BTN_BG)
        expect(result.disabledText).toBe(DISABLED_BTN_TEXT)
      } finally {
        await context.close()
      }
    })

    it("renders the 「取消全選」checkbox's actual painted colors: checked (#3E8FD8 bg / white mark), unchecked (#0F1620 bg / #26333F border), and #93A4B8 label text", async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const checked = document.getElementById('selectall-checked') as HTMLInputElement
          const unchecked = document.getElementById('selectall-unchecked') as HTMLInputElement
          const labelChecked = document.getElementById('selectall-label-checked')!
          const labelUnchecked = document.getElementById('selectall-label-unchecked')!
          return {
            checkedBg: getComputedStyle(checked).backgroundColor,
            checkedMark: getComputedStyle(checked, '::after').borderRightColor,
            uncheckedBg: getComputedStyle(unchecked).backgroundColor,
            uncheckedBorder: getComputedStyle(unchecked).borderTopColor,
            labelCheckedColor: getComputedStyle(labelChecked).color,
            labelUncheckedColor: getComputedStyle(labelUnchecked).color,
          }
        })
        expect(result.checkedBg).toBe(CHECKED_BG)
        expect(result.checkedMark).toBe(CHECK_MARK_WHITE)
        expect(result.uncheckedBg).toBe(UNCHECKED_BG)
        expect(result.uncheckedBorder).toBe(UNCHECKED_BORDER)
        expect(result.labelCheckedColor).toBe(LABEL_TEXT)
        expect(result.labelUncheckedColor).toBe(LABEL_TEXT)
      } finally {
        await context.close()
      }
    })

    // specs/frontend/strategy.md「買進價與報酬率欄排序」表頭呈現 — idle 「↕」 is weak text
    // (#6B7C90) with the label at the header's own default color (#93A4B8, inherited, no
    // class of its own); the actively-sorted header's direction icon is #3E8FD8 and its
    // label switches to the primary text color (#E6EDF5) via `.st-sort-header-active` on
    // the `<th>` itself — verified by actual computed color, not by which class either
    // element carries.
    it('colors the sortable headers: idle 「↕」 weak / default label, sorted 「▼」 blue / primary label', async () => {
      const colors = await computedColors()
      expect(colors['sort-icon-idle']).toBe(WEAK_TEXT)
      expect(colors['sort-label-idle']).toBe(FLAT)
      expect(colors['sort-icon-active']).toBe(CHECKED_BG)
      expect(colors['sort-label-active']).toBe(PRIMARY_TEXT)
    })

    // specs/frontend/strategy.md「總成本」— the value is always the primary text color
    // (never a sign color, even though it sits beside 總報酬率／總收益 which do get one),
    // the label matches the other two totals' label color, and the no-value dash reuses
    // the shared weak-text `.sl-muted` class.
    it('renders 總成本 with a fixed primary-text value (never a sign color), a secondary-text label, and a weak-text dash', async () => {
      const colors = await computedColors()
      expect(colors['total-cost-label']).toBe(FLAT)
      expect(colors['total-cost-value']).toBe(PRIMARY_TEXT)
      expect(colors['total-cost-dash']).toBe(WEAK_TEXT)
    })

    // specs/frontend/strategy.md「取消買進價高於 N 元」勾選框 — checked/unchecked reuse
    // .st-row-checkbox's already-pinned-down colors (same values as 取消全選), plus this
    // control's own disabled state (checkbox background/border + label text) and the
    // amount input's own background/text/border/focus-border.
    it("renders the 「取消買進價高於 N 元」checkbox's painted colors: checked/unchecked (shared with 取消全選), disabled (checkbox + label), and its amount input", async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const checked = document.getElementById('price-threshold-checked') as HTMLInputElement
          const unchecked = document.getElementById('price-threshold-unchecked') as HTMLInputElement
          const disabled = document.getElementById('price-threshold-disabled') as HTMLInputElement
          const input = document.getElementById('price-threshold-input') as HTMLInputElement
          const before = {
            checkedBg: getComputedStyle(checked).backgroundColor,
            checkedMark: getComputedStyle(checked, '::after').borderRightColor,
            uncheckedBg: getComputedStyle(unchecked).backgroundColor,
            uncheckedBorder: getComputedStyle(unchecked).borderTopColor,
            disabledBg: getComputedStyle(disabled).backgroundColor,
            disabledBorder: getComputedStyle(disabled).borderTopColor,
            inputBg: getComputedStyle(input).backgroundColor,
            inputText: getComputedStyle(input).color,
            inputBorder: getComputedStyle(input).borderTopColor,
          }
          input.focus()
          return { ...before, inputFocusedBorder: getComputedStyle(input).borderTopColor }
        })
        expect(result.checkedBg).toBe(CHECKED_BG)
        expect(result.checkedMark).toBe(CHECK_MARK_WHITE)
        expect(result.uncheckedBg).toBe(UNCHECKED_BG)
        expect(result.uncheckedBorder).toBe(UNCHECKED_BORDER)
        expect(result.disabledBg).toBe(DISABLED_BTN_BG) // #16202C
        expect(result.disabledBorder).toBe(UNCHECKED_BORDER) // #26333F
        expect(result.inputBg).toBe(UNCHECKED_BG) // #0F1620
        expect(result.inputText).toBe(INPUT_TEXT) // #E6EDF5
        expect(result.inputBorder).toBe(UNCHECKED_BORDER) // #26333F
        expect(result.inputFocusedBorder).toBe(CHECKED_BG) // #3E8FD8
      } finally {
        await context.close()
      }
    })

    it('colors the 「取消買進價高於 N 元」label text (enabled/disabled) and its invalid-amount hint', async () => {
      const colors = await computedColors()
      expect(colors['price-threshold-label']).toBe(LABEL_TEXT) // #93A4B8
      expect(colors['price-threshold-label-disabled']).toBe(DISABLED_BTN_TEXT) // #4A5866
      expect(colors['price-threshold-hint']).toBe(ERROR_TEXT) // #F09A94
    })

    // specs/frontend/strategy.md「僅選取報酬率 n% 以上」勾選框 — 「與『取消買進價高於 N 元』
    // 同欄位同值」: checked/unchecked/disabled reuse the exact same literal colors (no new
    // color introduced), plus its own amount input and #F09A94 validation hint.
    it("renders the 「僅選取報酬率 n% 以上」checkbox's painted colors: checked/unchecked/disabled (identical to 「取消買進價高於 N 元」) and its amount input", async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const checked = document.getElementById('return-threshold-checked') as HTMLInputElement
          const unchecked = document.getElementById('return-threshold-unchecked') as HTMLInputElement
          const disabled = document.getElementById('return-threshold-disabled') as HTMLInputElement
          const input = document.getElementById('return-threshold-input') as HTMLInputElement
          const before = {
            checkedBg: getComputedStyle(checked).backgroundColor,
            checkedMark: getComputedStyle(checked, '::after').borderRightColor,
            uncheckedBg: getComputedStyle(unchecked).backgroundColor,
            uncheckedBorder: getComputedStyle(unchecked).borderTopColor,
            disabledBg: getComputedStyle(disabled).backgroundColor,
            disabledBorder: getComputedStyle(disabled).borderTopColor,
            inputBg: getComputedStyle(input).backgroundColor,
            inputText: getComputedStyle(input).color,
            inputBorder: getComputedStyle(input).borderTopColor,
          }
          input.focus()
          return { ...before, inputFocusedBorder: getComputedStyle(input).borderTopColor }
        })
        expect(result.checkedBg).toBe(CHECKED_BG)
        expect(result.checkedMark).toBe(CHECK_MARK_WHITE)
        expect(result.uncheckedBg).toBe(UNCHECKED_BG)
        expect(result.uncheckedBorder).toBe(UNCHECKED_BORDER)
        expect(result.disabledBg).toBe(DISABLED_BTN_BG) // #16202C
        expect(result.disabledBorder).toBe(UNCHECKED_BORDER) // #26333F
        expect(result.inputBg).toBe(UNCHECKED_BG) // #0F1620
        expect(result.inputText).toBe(INPUT_TEXT) // #E6EDF5
        expect(result.inputBorder).toBe(UNCHECKED_BORDER) // #26333F
        expect(result.inputFocusedBorder).toBe(CHECKED_BG) // #3E8FD8
      } finally {
        await context.close()
      }
    })

    it('colors the 「僅選取報酬率 n% 以上」label text (enabled/disabled) and its invalid-threshold hint', async () => {
      const colors = await computedColors()
      expect(colors['return-threshold-label']).toBe(LABEL_TEXT) // #93A4B8
      expect(colors['return-threshold-label-disabled']).toBe(DISABLED_BTN_TEXT) // #4A5866
      expect(colors['return-threshold-hint']).toBe(ERROR_TEXT) // #F09A94
    })

    // specs/frontend/strategy.md「總計浮動跟隨」— the floating copy's own background/border
    // (opaque, so a row scrolled underneath never shows through) and that it reuses the
    // exact same 總成本／總報酬率／總收益 rendering (`.st-total-value`) the in-flow totals
    // already use, not a second, differently-colored implementation.
    it('renders 浮動總計 with an opaque #16202C background, #26333F border, and the same total-value colors as the in-flow totals', async () => {
      const colors = await computedColors()
      expect(colors['floating-total-cost']).toBe(PRIMARY_TEXT)
      expect(colors['floating-total-return']).toBe(RED_UP)
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const el = document.getElementById('floating-totals')!
          const style = getComputedStyle(el)
          return { bg: style.backgroundColor, border: style.borderTopColor, position: style.position }
        })
        expect(result.bg).toBe(PANEL_BG)
        expect(result.border).toBe(PANEL_BORDER)
        expect(result.position).toBe('fixed')
      } finally {
        await context.close()
      }
    })

    // specs/frontend/strategy.md「隱藏資料不齊（無賣出日）」勾選框 — checked/unchecked reuse
    // .st-row-checkbox's already-pinned-down colors (same values as 取消全選／取消買進價高於),
    // plus this control's own disabled state (checkbox background/border + label text) and
    // its plain (enabled) label text color.
    it("renders the 「隱藏資料不齊（無賣出日）」checkbox's painted colors: checked/unchecked (shared with the other two batch checkboxes), disabled state, and its label text", async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const checked = document.getElementById('batch-incomplete-checkbox') as HTMLInputElement
          const unchecked = document.getElementById('incomplete-unchecked') as HTMLInputElement
          const disabled = document.getElementById('incomplete-disabled') as HTMLInputElement
          return {
            checkedBg: getComputedStyle(checked).backgroundColor,
            checkedMark: getComputedStyle(checked, '::after').borderRightColor,
            uncheckedBg: getComputedStyle(unchecked).backgroundColor,
            uncheckedBorder: getComputedStyle(unchecked).borderTopColor,
            disabledBg: getComputedStyle(disabled).backgroundColor,
            disabledBorder: getComputedStyle(disabled).borderTopColor,
          }
        })
        expect(result.checkedBg).toBe(CHECKED_BG)
        expect(result.checkedMark).toBe(CHECK_MARK_WHITE)
        expect(result.uncheckedBg).toBe(UNCHECKED_BG)
        expect(result.uncheckedBorder).toBe(UNCHECKED_BORDER)
        expect(result.disabledBg).toBe(DISABLED_BTN_BG) // #16202C
        expect(result.disabledBorder).toBe(UNCHECKED_BORDER) // #26333F
      } finally {
        await context.close()
      }
    })

    it('colors the 「隱藏資料不齊（無賣出日）」label text (enabled/disabled) and the 「另 K 筆已隱藏」note', async () => {
      const colors = await computedColors()
      expect(colors['incomplete-label-unchecked']).toBe(LABEL_TEXT) // #93A4B8
      expect(colors['incomplete-label-disabled']).toBe(DISABLED_BTN_TEXT) // #4A5866
      expect(colors['hidden-count-note']).toBe(FLAT) // #93A4B8
    })

    // specs/frontend/strategy.md「批次勾選框列」— the four batch checkboxes (取消全選／取消
    // 買進價高於／僅選取報酬率／隱藏資料不齊) must render at equal box height and share the
    // same vertical center line (≤ 1px), which only an actual layout engine can prove — jsdom
    // has none.
    it('renders the four batch checkboxes at equal height with the same vertical center (≤ 1px)', async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const [cancelAll, priceThreshold, returnThreshold, incomplete] = await Promise.all([
          page.locator('#batch-cancel-all-checkbox').boundingBox(),
          page.locator('#batch-price-threshold-checkbox').boundingBox(),
          page.locator('#batch-return-threshold-checkbox').boundingBox(),
          page.locator('#batch-incomplete-checkbox').boundingBox(),
        ])
        expect(cancelAll).not.toBeNull()
        expect(priceThreshold).not.toBeNull()
        expect(returnThreshold).not.toBeNull()
        expect(incomplete).not.toBeNull()
        const boxes = [cancelAll!, priceThreshold!, returnThreshold!, incomplete!]
        const heights = boxes.map((box) => box.height)
        const centers = boxes.map((box) => box.y + box.height / 2)
        expect(Math.max(...heights) - Math.min(...heights)).toBeLessThanOrEqual(1)
        expect(Math.max(...centers) - Math.min(...centers)).toBeLessThanOrEqual(1)
      } finally {
        await context.close()
      }
    })

    // specs/frontend/strategy.md「以週選擇區間」— the 週選擇 control's own background/text/
    // border colors, its focus-within border, the「實際區間」hint text, and the two 快捷
    // 區間鈕 states (unselected／selected).
    it('renders the 週選擇 control, 「實際區間」hint, and 快捷區間鈕 states with their literal Visual Style colors', async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(pageHtml())
        const result = await page.evaluate(() => {
          const weekSelect = document.getElementById('week-select')!
          const prevBtn = document.getElementById('week-step-prev') as HTMLButtonElement
          const before = {
            bg: getComputedStyle(weekSelect).backgroundColor,
            text: getComputedStyle(weekSelect).color,
            border: getComputedStyle(weekSelect).borderTopColor,
          }
          prevBtn.focus()
          const focusedBorder = getComputedStyle(weekSelect).borderTopColor
          const rangeHint = getComputedStyle(document.getElementById('range-hint')!).color
          const shortcutInactive = document.getElementById('shortcut-inactive')!
          const shortcutActive = document.getElementById('shortcut-active')!
          return {
            ...before,
            focusedBorder,
            rangeHint,
            shortcutInactiveBg: getComputedStyle(shortcutInactive).backgroundColor,
            shortcutInactiveText: getComputedStyle(shortcutInactive).color,
            shortcutActiveBg: getComputedStyle(shortcutActive).backgroundColor,
            shortcutActiveText: getComputedStyle(shortcutActive).color,
          }
        })
        expect(result.bg).toBe(UNCHECKED_BG) // #0F1620
        expect(result.text).toBe(PRIMARY_TEXT) // #E6EDF5
        expect(result.border).toBe(UNCHECKED_BORDER) // #26333F
        expect(result.focusedBorder).toBe(CHECKED_BG) // #3E8FD8 (focus-within)
        expect(result.rangeHint).toBe(FLAT) // #93A4B8
        expect(result.shortcutInactiveBg).toBe(SECONDARY_BTN_BG) // #1B2836
        expect(result.shortcutInactiveText).toBe(FLAT) // #93A4B8
        expect(result.shortcutActiveBg).toBe(UNCHECKED_BORDER) // #26333F
        expect(result.shortcutActiveText).toBe(PRIMARY_TEXT) // #E6EDF5
      } finally {
        await context.close()
      }
    })

    // specs/frontend/strategy.md「複選參數（type: multiSelect）」— the three 法人籌碼 cards'
    // 「法人」row (.st-multiselect-row/.st-multiselect-option, new classes this increment
    // added). Markup lifted verbatim from StrategyTab.tsx's renderParamRow multiSelect
    // branch. Reuses the exact same param-label text color and checkbox accent color as
    // every other card control on this page — no new colors introduced — plus the
    // 「請至少勾選一個法人」message, which reuses .st-inline-error verbatim.
    it('renders the 法人 multiSelect row (label/option text, checkbox accent) and its 請至少勾選一個法人 message with existing Visual Style colors', async () => {
      const context = await browser.newContext({ colorScheme })
      const page = await context.newPage()
      try {
        await page.setContent(`<!doctype html>
<html>
<head><meta charset="utf-8"><style>${css}</style></head>
<body>
  <div class="stock-list-page">
    <div class="st-strategy-card st-strategy-card-selected">
      <div class="st-param-block">
        <div class="st-multiselect-row">
          <span class="st-param-label" id="ms-label">法人</span>
          <label class="st-multiselect-option" id="ms-option-checked">
            <input type="checkbox" id="ms-checkbox-checked" checked />
            外資
          </label>
          <label class="st-multiselect-option" id="ms-option-unchecked">
            <input type="checkbox" id="ms-checkbox-unchecked" />
            投信
          </label>
        </div>
        <div class="st-inline-error" id="ms-error">請至少勾選一個法人</div>
      </div>
    </div>
  </div>
</body>
</html>`)
        const result = await page.evaluate(() => {
          const label = getComputedStyle(document.getElementById('ms-label')!).color
          const optionChecked = getComputedStyle(document.getElementById('ms-option-checked')!).color
          const optionUnchecked = getComputedStyle(document.getElementById('ms-option-unchecked')!).color
          const checkboxCheckedAccent = getComputedStyle(document.getElementById('ms-checkbox-checked')!).accentColor
          const checkboxUncheckedAccent = getComputedStyle(document.getElementById('ms-checkbox-unchecked')!).accentColor
          const error = getComputedStyle(document.getElementById('ms-error')!).color
          return { label, optionChecked, optionUnchecked, checkboxCheckedAccent, checkboxUncheckedAccent, error }
        })
        expect(result.label).toBe(FLAT) // #93A4B8 — 「參數輸入標籤與單位文字」
        expect(result.optionChecked).toBe(FLAT) // #93A4B8 — same as every other param/group checkbox label
        expect(result.optionUnchecked).toBe(FLAT)
        expect(result.checkboxCheckedAccent).toBe(CHECKED_BG) // #3E8FD8 — 「勾選框已勾選背景／勾記」
        expect(result.checkboxUncheckedAccent).toBe(CHECKED_BG) // accent-color is a static per-element declaration, same value regardless of checked state
        expect(result.error).toBe(ERROR_TEXT) // #F09A94 — reuses .st-inline-error verbatim, not a new color
      } finally {
        await context.close()
      }
    })
  },
)
