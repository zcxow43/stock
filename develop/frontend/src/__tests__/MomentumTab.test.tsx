import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MomentumTab from '../pages/MomentumTab'

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

function baseResponse(overrides: Record<string, unknown> = {}) {
  return {
    metric: 'AVERAGE',
    mode: 'DAYS',
    sort: 'MATCH_COUNT',
    startDate: '2026-08-04',
    endDate: '2026-08-29',
    tradingDays: 20,
    minGain: 5,
    scannedStocks: 1365,
    matchedStockCount: 2,
    insufficientDataCount: 0,
    industries: [
      {
        industryId: 7,
        industryName: '半導體業',
        matchedCount: 1,
        avgGain: 18.42,
        items: [
          {
            stockId: '2330',
            stockName: '台積電',
            gain: 18.42,
            tradingDays: 20,
            startClose: 1180.0,
            endClose: 1395.0,
            firstTradeDate: '2026-08-04',
            lastTradeDate: '2026-08-29',
          },
        ],
      },
      {
        industryId: 12,
        industryName: '電子零組件業',
        matchedCount: 1,
        avgGain: -3.2,
        items: [
          {
            stockId: '2454',
            stockName: '聯發科',
            gain: -3.2,
            tradingDays: 15,
            startClose: 900.0,
            endClose: 871.2,
            firstTradeDate: '2026-08-10',
            lastTradeDate: '2026-08-29',
          },
        ],
      },
    ],
    ...overrides,
  }
}

function renderTab(commonStocksOnly?: boolean) {
  return render(
    <MemoryRouter initialEntries={['/momentum']}>
      <Routes>
        <Route path="/momentum" element={<MomentumTab commonStocksOnly={commonStocksOnly} />} />
        <Route path="/stocks/:stockId/daily" element={<div>daily page for the clicked row</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function parseQuery(url: string): URLSearchParams {
  return new URL(url, 'http://x').searchParams
}

describe('MomentumTab', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let responder: (query: URLSearchParams) => { status: number; body: unknown }

  beforeEach(() => {
    // Fake only `Date` (not setTimeout/setInterval) so `waitFor`'s real-timer polling
    // keeps working. Wednesday — Monday of this week is 2026-08-31, Sunday 2026-09-06.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-02T10:00:00'))
    responder = () => ({ status: 200, body: baseResponse() })
    fetchMock = vi.fn().mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/momentum/gain')) {
        const { status, body } = responder(parseQuery(u))
        return Promise.resolve(jsonResponse(status, body))
      }
      return Promise.reject(new Error(`unexpected fetch: ${u}`))
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  function momentumCalls() {
    return fetchMock.mock.calls.filter((c: unknown[]) => String(c[0]).startsWith('/api/momentum/gain'))
  }

  // ---------- initial state / tabs ----------

  it('defaults to 漲幅平均 tab, 近 20 個交易日, 門檻 5, and does not fetch until 查詢 is clicked', async () => {
    renderTab()
    expect(screen.getByRole('tab', { name: '漲幅平均' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: '漲幅加總' })).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByDisplayValue('20')).toBeInTheDocument()
    expect(screen.getByDisplayValue('5')).toBeInTheDocument()
    expect(screen.getByText('設定期間與門檻後按下查詢')).toBeInTheDocument()
    expect(momentumCalls()).toHaveLength(0)
  })

  it('查詢 sends metric=AVERAGE mode=DAYS days=20 minGain=5 by default', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    const q = parseQuery(String(momentumCalls()[0][0]))
    expect(q.get('metric')).toBe('AVERAGE')
    expect(q.get('mode')).toBe('DAYS')
    expect(q.get('days')).toBe('20')
    expect(q.get('minGain')).toBe('5')
    expect(q.has('startDate')).toBe(false)
    expect(q.has('endDate')).toBe(false)
  })

  it('switching to 漲幅加總 for the first time re-queries with metric=SUM, reusing the period', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))

    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))
    const q = parseQuery(String(momentumCalls()[1][0]))
    expect(q.get('metric')).toBe('SUM')
    expect(q.get('mode')).toBe('DAYS')
    expect(q.get('days')).toBe('20')
  })

  it('switching back to a tab that already has results does not refetch', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))

    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))

    fireEvent.click(screen.getByRole('tab', { name: '漲幅平均' }))
    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' }))
    // still just the two initial calls — no new requests from round-tripping
    expect(momentumCalls()).toHaveLength(2)
  })

  function minGainInputEl(container: HTMLElement): HTMLInputElement {
    const el = container.querySelector('.mt-mingain-row input')
    if (!el) throw new Error('minGain input not found')
    return el as HTMLInputElement
  }

  it('threshold does not carry across labels: editing 漲幅加總 to 20 leaves 漲幅平均 at its own 5, and going back shows 20 again', async () => {
    const { container } = renderTab()
    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' }))
    fireEvent.change(minGainInputEl(container), { target: { value: '20' } })
    expect(minGainInputEl(container).value).toBe('20')

    fireEvent.click(screen.getByRole('tab', { name: '漲幅平均' }))
    expect(minGainInputEl(container).value).toBe('5')

    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' }))
    expect(minGainInputEl(container).value).toBe('20')
  })

  // ---------- field validation ----------

  it('rejects days of 0 or 121 without sending a request', async () => {
    renderTab()
    const daysInput = screen.getByDisplayValue('20')
    fireEvent.change(daysInput, { target: { value: '0' } })
    expect(screen.getByText('請輸入 1 – 120 之間的交易日數')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查詢' })).toBeDisabled()

    fireEvent.change(daysInput, { target: { value: '121' } })
    expect(screen.getByText('請輸入 1 – 120 之間的交易日數')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查詢' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    expect(momentumCalls()).toHaveLength(0)
  })

  it('rejects minGain of 1001 or -101, but accepts -100 and 0', async () => {
    renderTab()
    const minGainInput = screen.getByDisplayValue('5')
    fireEvent.change(minGainInput, { target: { value: '1001' } })
    expect(screen.getByText('請輸入 -100 – 1000 之間的數值')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查詢' })).toBeDisabled()

    fireEvent.change(minGainInput, { target: { value: '-101' } })
    expect(screen.getByText('請輸入 -100 – 1000 之間的數值')).toBeInTheDocument()

    fireEvent.change(minGainInput, { target: { value: '-100' } })
    expect(screen.queryByText('請輸入 -100 – 1000 之間的數值')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查詢' })).not.toBeDisabled()

    fireEvent.change(minGainInput, { target: { value: '0' } })
    expect(screen.getByRole('button', { name: '查詢' })).not.toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    expect(parseQuery(String(momentumCalls()[0][0])).get('minGain')).toBe('0')
  })

  // ---------- weeks mode ----------

  it('指定週 mode lists 12 weeks newest-first with MM/DD – MM/DD labels and marks 本週', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '指定週' }))
    expect(screen.getByText('08/31 – 09/06')).toBeInTheDocument()
    expect(screen.getByText('本週')).toBeInTheDocument()
    expect(screen.getByText('08/24 – 08/30')).toBeInTheDocument()
    // 12 checkboxes
    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(12)
    // current week pre-checked
    expect(checkboxes[0]).toBeChecked()
  })

  it('sends startDate as the earliest selected week Monday and endDate as the latest selected week Sunday', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '指定週' }))
    // default: only this week (08/31–09/06) checked; also check the previous week (08/24–08/30)
    fireEvent.click(screen.getByLabelText(/08\/24 – 08\/30/))
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    const q = parseQuery(String(momentumCalls()[0][0]))
    expect(q.get('mode')).toBe('WEEKS')
    expect(q.get('startDate')).toBe('2026-08-24')
    expect(q.get('endDate')).toBe('2026-09-06')
    expect(q.has('days')).toBe(false)
  })

  it('shows a gap warning naming the real computed range when the selection is non-contiguous', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '指定週' }))
    // this week (08/31–09/06, already checked) + skip 08/24–08/30 + check 08/17–08/23
    fireEvent.click(screen.getByLabelText(/08\/17 – 08\/23/))
    const warning = screen.getByText(/實際計算區間/)
    expect(warning.textContent).toContain('2026-08-17')
    expect(warning.textContent).toContain('2026-09-06')
    expect(warning.textContent).toContain('08/24')
  })

  it('disables 查詢 with a hint when no week is checked', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '指定週' }))
    // uncheck the only default-checked week
    fireEvent.click(screen.getByLabelText(/08\/31 – 09\/06/))
    expect(screen.getByRole('button', { name: '查詢' })).toBeDisabled()
    expect(screen.getAllByText('請至少勾選一週').length).toBeGreaterThan(0)
  })

  it('preserves each period mode input when switching modes back and forth', async () => {
    renderTab()
    fireEvent.change(screen.getByDisplayValue('20'), { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: '指定週' }))
    fireEvent.click(screen.getByLabelText(/08\/24 – 08\/30/))
    fireEvent.click(screen.getByRole('button', { name: '近 N 交易日' }))
    expect(screen.getByDisplayValue('30')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '指定週' }))
    expect(screen.getByLabelText(/08\/24 – 08\/30/)).toBeChecked()
  })

  // ---------- summary line ----------

  it('summary line uses the response startDate/endDate/tradingDays, and 命中 uses matchedStockCount not the sum of matchedCount', async () => {
    responder = () => ({
      status: 200,
      body: baseResponse({ matchedStockCount: 2, tradingDays: 20, startDate: '2026-08-04', endDate: '2026-08-29' }),
    })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText(/區間 2026-08-04 ~ 2026-08-29/)).toBeInTheDocument())
    expect(screen.getByText(/20 個交易日/)).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument() // matchedStockCount, not 1+1=2 coincidentally same here but explicit field used
  })

  it('hides 資料不足 entirely when insufficientDataCount is 0', async () => {
    responder = () => ({ status: 200, body: baseResponse({ insufficientDataCount: 0 }) })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText(/命中/)).toBeInTheDocument())
    expect(screen.queryByText(/資料不足/)).not.toBeInTheDocument()
  })

  it('shows 資料不足 K 檔 when insufficientDataCount > 0', async () => {
    responder = () => ({ status: 200, body: baseResponse({ insufficientDataCount: 12 }) })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText(/資料不足 12 檔/)).toBeInTheDocument())
  })

  // ---------- industry blocks ----------

  it('renders industry blocks in the exact response order without re-sorting', async () => {
    responder = () => ({
      status: 200,
      body: baseResponse({
        industries: [
          { industryId: 3, industryName: 'Z產業', matchedCount: 1, avgGain: 1, items: [{ stockId: '1111', stockName: 'A', gain: 1, tradingDays: 20, startClose: 10, endClose: 10.1, firstTradeDate: '2026-08-04', lastTradeDate: '2026-08-29' }] },
          { industryId: 1, industryName: 'A產業', matchedCount: 1, avgGain: 1, items: [{ stockId: '2222', stockName: 'B', gain: 1, tradingDays: 20, startClose: 10, endClose: 10.1, firstTradeDate: '2026-08-04', lastTradeDate: '2026-08-29' }] },
          { industryId: null, industryName: '未分類', matchedCount: 1, avgGain: 1, items: [{ stockId: '3333', stockName: 'C', gain: 1, tradingDays: 20, startClose: 10, endClose: 10.1, firstTradeDate: '2026-08-04', lastTradeDate: '2026-08-29' }] },
        ],
      }),
    })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText('Z產業')).toBeInTheDocument())
    const titles = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(titles[0]).toContain('Z產業')
    expect(titles[1]).toContain('A產業')
    expect(titles[2]).toContain('未分類')
  })

  it('a stock belonging to two industries appears once in each industry table', async () => {
    responder = () => ({
      status: 200,
      body: baseResponse({
        matchedStockCount: 1,
        industries: [
          { industryId: 1, industryName: '半導體業', matchedCount: 1, avgGain: 1, items: [{ stockId: '2330', stockName: '台積電', gain: 5, tradingDays: 20, startClose: 100, endClose: 105, firstTradeDate: '2026-08-04', lastTradeDate: '2026-08-29' }] },
          { industryId: 2, industryName: '電子業', matchedCount: 1, avgGain: 1, items: [{ stockId: '2330', stockName: '台積電', gain: 5, tradingDays: 20, startClose: 100, endClose: 105, firstTradeDate: '2026-08-04', lastTradeDate: '2026-08-29' }] },
        ],
      }),
    })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getAllByText('台積電')).toHaveLength(2))
  })

  it('positive gain renders red (sl-up) and negative gain renders green (sl-down)', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getAllByText('+18.42%').length).toBeGreaterThan(0))
    // "+18.42%"/"−3.20%" each appear twice now (once as the row's 漲幅 cell, once as the
    // industry block's avgGain in its <h3> title) — scope to the table cell specifically.
    const upCell = screen.getAllByText('+18.42%').find((el) => el.tagName === 'TD')!
    expect(upCell).toHaveClass('sl-up')
    const downCell = screen.getAllByText('-3.20%').find((el) => el.tagName === 'TD')!
    expect(downCell).toHaveClass('sl-down')
  })

  it('marks a stock whose tradingDays is below the summary tradingDays with a 停牌 tooltip', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getAllByText('-3.20%').length).toBeGreaterThan(0))
    const marker = screen.getByTitle(/期間內有停牌/)
    expect(marker).toBeInTheDocument()
  })

  it('clicking a row navigates to /stocks/{stockId}/daily', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    fireEvent.click(screen.getByText('台積電').closest('tr')!)
    await waitFor(() => expect(screen.getByText('daily page for the clicked row')).toBeInTheDocument())
  })

  it('shows the 未分類-only guidance pointing at 策略 tab, while still rendering the 未分類 results, and has no import button of its own', async () => {
    responder = () => ({
      status: 200,
      body: baseResponse({
        matchedStockCount: 1,
        industries: [
          { industryId: null, industryName: '未分類', matchedCount: 1, avgGain: 1, items: [{ stockId: '9999', stockName: '未分類股', gain: 5, tradingDays: 20, startClose: 10, endClose: 10.5, firstTradeDate: '2026-08-04', lastTradeDate: '2026-08-29' }] },
        ],
      }),
    })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText(/更新股票清單/)).toBeInTheDocument())
    expect(screen.getByText('未分類股')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更新股票清單' })).not.toBeInTheDocument()
  })

  // ---------- empty / no-trading-day / error / loading states ----------

  it('shows the zero-hit message with a 把門檻調低 button that halves the threshold and re-queries', async () => {
    responder = () => ({ status: 200, body: baseResponse({ matchedStockCount: 0, industries: [], minGain: 5 }) })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText('這段期間沒有股票的漲幅達到 5.00%')).toBeInTheDocument())

    responder = () => ({ status: 200, body: baseResponse({ matchedStockCount: 0, industries: [], minGain: 2.5 }) })
    fireEvent.click(screen.getByRole('button', { name: '把門檻調低' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))
    expect(parseQuery(String(momentumCalls()[1][0])).get('minGain')).toBe('2.5')
  })

  it('shows "所選期間內沒有交易日" and "請改選其他週" when startDate is null, not an error screen', async () => {
    responder = () => ({ status: 200, body: baseResponse({ startDate: null, endDate: null, matchedStockCount: 0, industries: [] }) })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText('所選期間內沒有交易日')).toBeInTheDocument())
    expect(screen.getByText('請改選其他週')).toBeInTheDocument()
    expect(screen.queryByText('查詢失敗，請稍後再試')).not.toBeInTheDocument()
  })

  it('shows an error message with a retry button on request failure, never a fake empty result', async () => {
    responder = () => ({ status: 500, body: { code: 'INTERNAL' } })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText('查詢失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '重新查詢' })).toBeInTheDocument()
    expect(screen.queryByText(/這段期間沒有股票的漲幅達到/)).not.toBeInTheDocument()
  })

  it('disables 查詢 while loading, dims existing results, and does not double-fire on a double click', async () => {
    let resolveFirst!: (v: { ok: boolean; status: number; json: () => Promise<unknown> }) => void
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (!u.startsWith('/api/momentum/gain')) return Promise.reject(new Error('unexpected'))
      return new Promise((resolve) => {
        resolveFirst = resolve
      })
    })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    fireEvent.click(screen.getByRole('button', { name: '查詢中…' }))
    expect(screen.getByRole('button', { name: '查詢中…' })).toBeDisabled()
    expect(momentumCalls()).toHaveLength(1)

    resolveFirst(jsonResponse(200, baseResponse()))
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
  })

  it('never uses wording implying a trading action (建議/推薦/可進場)', async () => {
    responder = () => ({
      status: 200,
      body: baseResponse({
        matchedStockCount: 0,
        industries: [],
      }),
    })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText(/這段期間沒有股票的漲幅達到/)).toBeInTheDocument())
    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/建議|推薦|可進場/)
  })

  // ---------- 普通股母體 (page-level commonStocksOnly) ----------

  it('has no ETF-exclusion control of its own', async () => {
    renderTab()
    expect(screen.queryByText(/只看上市普通股/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更新股票清單' })).not.toBeInTheDocument()
  })

  it('sends commonStocksOnly matching the page-level setting', async () => {
    renderTab(false)
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    expect(parseQuery(String(momentumCalls()[0][0])).get('commonStocksOnly')).toBe('false')
  })

  it('defaults commonStocksOnly to true when the prop is omitted', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    expect(parseQuery(String(momentumCalls()[0][0])).get('commonStocksOnly')).toBe('true')
  })

  it('re-queries immediately with the new commonStocksOnly value when the page-level checkbox changes after a result exists', async () => {
    const { rerender } = renderTab(true)
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    expect(parseQuery(String(momentumCalls()[0][0])).get('commonStocksOnly')).toBe('true')

    rerender(
      <MemoryRouter initialEntries={['/momentum']}>
        <Routes>
          <Route path="/momentum" element={<MomentumTab commonStocksOnly={false} />} />
          <Route path="/stocks/:stockId/daily" element={<div>daily page for the clicked row</div>} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))
    expect(parseQuery(String(momentumCalls()[1][0])).get('commonStocksOnly')).toBe('false')
  })

  it('applies the same commonStocksOnly setting to both 漲幅平均 and 漲幅加總', async () => {
    renderTab(false)
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))
    expect(parseQuery(String(momentumCalls()[1][0])).get('commonStocksOnly')).toBe('false')
  })

  // ---------- 排序切換 (sort toggle) ----------

  it('defaults the sort toggle to 依命中檔數 and can be operated before the first query', async () => {
    renderTab()
    expect(screen.getByRole('button', { name: '依命中檔數' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '依產業漲幅' })).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(screen.getByRole('button', { name: '依產業漲幅' }))
    expect(momentumCalls()).toHaveLength(0) // no query fired yet — page never queried before
    expect(screen.getByRole('button', { name: '依產業漲幅' })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    expect(parseQuery(String(momentumCalls()[0][0])).get('sort')).toBe('AVG_GAIN')
  })

  it('sends sort=MATCH_COUNT by default once queried', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    expect(parseQuery(String(momentumCalls()[0][0])).get('sort')).toBe('MATCH_COUNT')
  })

  it('switching sort re-queries with the new sort value instead of re-sorting on the front end', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))

    fireEvent.click(screen.getByRole('button', { name: '依產業漲幅' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))
    expect(parseQuery(String(momentumCalls()[1][0])).get('sort')).toBe('AVG_GAIN')
  })

  it('sort is shared across both metric tabs: switching to 漲幅加總 after choosing 依產業漲幅 keeps it selected', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: '依產業漲幅' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))

    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(3))
    expect(screen.getByRole('button', { name: '依產業漲幅' })).toHaveAttribute('aria-pressed', 'true')
    expect(parseQuery(String(momentumCalls()[2][0])).get('sort')).toBe('AVG_GAIN')
  })

  it('switching sort invalidates the other tab\'s cached result, so switching to it fires a new request', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' })) // AVERAGE, MATCH_COUNT
    await waitFor(() => expect(momentumCalls()).toHaveLength(1))
    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' })) // SUM auto-queries, MATCH_COUNT
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))
    fireEvent.click(screen.getByRole('tab', { name: '漲幅平均' })) // back to cached AVERAGE result, no fetch

    fireEvent.click(screen.getByRole('button', { name: '依產業漲幅' })) // changes sort while on AVERAGE
    await waitFor(() => expect(momentumCalls()).toHaveLength(3))

    fireEvent.click(screen.getByRole('tab', { name: '漲幅加總' })) // SUM's cache is stale -> requeries
    await waitFor(() => expect(momentumCalls()).toHaveLength(4))
    expect(parseQuery(String(momentumCalls()[3][0])).get('sort')).toBe('AVG_GAIN')
  })

  it('renders industry blocks completely in response order under both sort values, without any front-end re-sort', async () => {
    responder = () => ({
      status: 200,
      body: baseResponse({
        industries: [
          { industryId: 1, industryName: '低平均產業', matchedCount: 5, avgGain: 5.5, items: [] },
          { industryId: 2, industryName: '高平均產業', matchedCount: 1, avgGain: 30.1, items: [] },
          { industryId: null, industryName: '未分類', matchedCount: 1, avgGain: 99, items: [] },
        ],
      }),
    })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText('低平均產業')).toBeInTheDocument())
    let titles = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent ?? '')
    expect(titles[0]).toContain('低平均產業')
    expect(titles[1]).toContain('高平均產業')
    expect(titles[2]).toContain('未分類')

    fireEvent.click(screen.getByRole('button', { name: '依產業漲幅' }))
    await waitFor(() => expect(momentumCalls()).toHaveLength(2))
    // backend would now return avgGain-descending order — front end must render exactly
    // what the response says, not what it computed itself.
    titles = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent ?? '')
    expect(titles[0]).toContain('低平均產業')
  })

  // ---------- 產業別區塊平均 (avgGain) ----------

  it('shows the block-level average with sign/color and a hover explanation on 平均', async () => {
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText('半導體業')).toBeInTheDocument())

    const upTitle = screen.getByText('半導體業').closest('h3')!
    expect(upTitle.textContent).toContain('1 檔')
    expect(upTitle.textContent).toContain('平均')
    expect(upTitle.textContent).toContain('+18.42%')
    const upAvg = within(upTitle).getByText('+18.42%')
    expect(upAvg).toHaveClass('sl-up')

    const downTitle = screen.getByText('電子零組件業').closest('h3')!
    const downAvg = within(downTitle).getByText('-3.20%')
    expect(downAvg).toHaveClass('sl-down')

    const label = within(upTitle).getByText('平均')
    expect(label.getAttribute('title')).toContain('必然 ≥ 門檻')
    expect(label.getAttribute('title')).toContain('不代表整個產業的表現')
  })

  it('shows an average for the 未分類 block too', async () => {
    responder = () => ({
      status: 200,
      body: baseResponse({
        matchedStockCount: 1,
        industries: [
          {
            industryId: null,
            industryName: '未分類',
            matchedCount: 1,
            avgGain: 7.5,
            items: [{ stockId: '9999', stockName: '未分類股', gain: 7.5, tradingDays: 20, startClose: 10, endClose: 10.75, firstTradeDate: '2026-08-04', lastTradeDate: '2026-08-29' }],
          },
        ],
      }),
    })
    renderTab()
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(screen.getByText('未分類股')).toBeInTheDocument())
    const title = screen.getByText('未分類').closest('h3')!
    expect(within(title).getByText('+7.50%')).toBeInTheDocument()
  })
})
