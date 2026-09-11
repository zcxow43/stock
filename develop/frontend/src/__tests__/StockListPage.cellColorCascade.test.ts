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
      <table class="sl-table st-result-table st-union-table">
        <tbody>
          <tr class="sl-row">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" checked /></td>
            <td id="st-cell-plain">2330 台積電</td>
            <td class="st-union-hits">箱型突破 2026-08-27</td>
            <td id="st-cell-selldate">2026-09-01</td>
            <td class="sl-r sl-up" id="st-cell-return-up">1.24%</td>
            <td class="sl-r sl-up" id="st-cell-profit-up">30,000</td>
          </tr>
          <tr class="sl-row">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" checked /></td>
            <td>2317 鴻海</td>
            <td class="st-union-hits">底底高 2026-08-28</td>
            <td>2026-09-02</td>
            <td class="sl-r sl-down" id="st-cell-return-down">-4.17%</td>
            <td class="sl-r sl-down" id="st-cell-profit-down">-100,000</td>
          </tr>
          <tr class="sl-row">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" checked /></td>
            <td>2454 聯發科</td>
            <td class="st-union-hits">箱型突破 2026-08-20</td>
            <td><span class="sl-muted" id="st-cell-selldate-null">—</span></td>
            <td class="sl-r sl-muted" id="st-cell-return-null"><span class="sl-muted">—</span></td>
            <td class="sl-r sl-muted" id="st-cell-profit-null"><span class="sl-muted">—</span></td>
          </tr>
          <tr class="sl-row st-row-unchecked">
            <td class="st-checkbox-col"><input type="checkbox" class="st-row-checkbox" /></td>
            <td id="st-cell-unchecked-plain">2454 聯發科</td>
            <td class="st-union-hits">箱型突破 2026-08-20</td>
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
  },
)
