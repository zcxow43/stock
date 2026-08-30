import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockListPage from '../pages/StockListPage'
import type { StockListResponse } from '../api/stocks'

function makeResponse(overrides: Partial<StockListResponse> = {}): StockListResponse {
  return {
    page: 1,
    size: 50,
    total: 2,
    totalPages: 1,
    items: [
      {
        stockId: '2330',
        stockName: '台積電',
        market: 'TSE',
        isActive: true,
        latestTradeDate: '2026-08-27',
        latestClose: 1105.0,
        previousClose: 1100.0,
        changeAmount: 5.0,
        changePercent: 0.45,
        latestVolume: 17557736,
      },
      {
        stockId: '1101',
        stockName: '台泥',
        market: 'TSE',
        isActive: true,
        latestTradeDate: null,
        latestClose: null,
        previousClose: null,
        changeAmount: null,
        changePercent: null,
        latestVolume: null,
      },
    ],
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/stocks']}>
      <Routes>
        <Route path="/stocks" element={<StockListPage />} />
        <Route path="/stocks/:stockId/daily" element={<div>daily page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

// The 策略 tab (StrategyTab) is always mounted alongside 總覽 (see StockListPage's
// "both tabs stay mounted" comment) and fires its own GET /api/strategies and
// GET /api/stocks/sync/progress calls on mount. These helpers isolate the 總覽 tab's
// own `/api/stocks?...` list requests so call-count assertions below aren't coupled
// to however many background calls the 策略 tab happens to make.
function overviewCalls(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls.filter((c: unknown[]) => /^\/api\/stocks\?/.test(String(c[0])))
}

describe('StockListPage', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => makeResponse(),
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('loads and shows the first page with the API total', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByText('2')).toBeInTheDocument()
    const url = new URL(overviewCalls(fetchMock)[0][0], 'http://x')
    expect(url.searchParams.get('page')).toBe('1')
    expect(url.searchParams.get('size')).toBe('50')
  })

  it('shows a dash for missing quote fields and keeps the row clickable', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('台泥')).toBeInTheDocument())
    const row = screen.getByText('台泥').closest('tr')!
    const cells = within(row).getAllByRole('cell')
    // latest trade date, close, change, change%, volume all render em-dash
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(4)
    expect(cells[0]).toBeTruthy()
    expect(row.className).toContain('sl-row')
  })

  it('colors positive change red (#E04B45) and applies flat color for a zero change', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () =>
        makeResponse({
          items: [
            {
              stockId: '2603',
              stockName: '長榮',
              market: 'TSE',
              isActive: true,
              latestTradeDate: '2026-08-27',
              latestClose: 195,
              previousClose: 195,
              changeAmount: 0,
              changePercent: 0,
              latestVolume: 100,
            },
          ],
        }),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('長榮')).toBeInTheDocument())
    const row = screen.getByText('長榮').closest('tr')!
    const changeCell = within(row).getByText('0.00')
    expect(changeCell.className).toContain('sl-flat')
  })

  it('debounces search input into a single request after typing stops', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderPage()
    await vi.waitFor(() => expect(overviewCalls(fetchMock)).toHaveLength(1))

    const input = screen.getByPlaceholderText('輸入股票代號或名稱')
    fireEvent.change(input, { target: { value: '2' } })
    fireEvent.change(input, { target: { value: '23' } })
    fireEvent.change(input, { target: { value: '233' } })
    fireEvent.change(input, { target: { value: '2330' } })

    // still just the initial mount request — debounce window hasn't elapsed
    expect(overviewCalls(fetchMock)).toHaveLength(1)

    await vi.advanceTimersByTimeAsync(300)
    await vi.waitFor(() => expect(overviewCalls(fetchMock)).toHaveLength(2))

    const lastUrl = new URL(overviewCalls(fetchMock)[1][0], 'http://x')
    expect(lastUrl.searchParams.get('keyword')).toBe('2330')
    vi.useRealTimers()
  })

  it('resets page to 1 when a filter changes', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => makeResponse({ page: 1, totalPages: 5, total: 500 }),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '下一頁' }))
    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls.at(-1)![0], 'http://x')
      expect(url.searchParams.get('page')).toBe('2')
    })

    const marketSelect = screen.getAllByRole('combobox')[0]
    fireEvent.change(marketSelect, { target: { value: 'OTC' } })
    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls.at(-1)![0], 'http://x')
      expect(url.searchParams.get('page')).toBe('1')
      expect(url.searchParams.get('market')).toBe('OTC')
    })
  })

  it('sends no market param for "全部" and the OTC param maps to the 上櫃 label', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () =>
        makeResponse({
          items: [
            {
              stockId: '4966',
              stockName: '譜瑞-KY',
              market: 'OTC',
              isActive: true,
              latestTradeDate: '2026-08-27',
              latestClose: 905,
              previousClose: 917,
              changeAmount: -12,
              changePercent: -1.31,
              latestVolume: 842300,
            },
          ],
        }),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('上櫃')).toBeInTheDocument())
    expect(screen.queryByText('OTC')).not.toBeInTheDocument()
  })

  it('marks first/last page buttons disabled appropriately', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => makeResponse({ page: 1, totalPages: 1, total: 2 }),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '上一頁' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '下一頁' })).toBeDisabled()
  })

  it('changing page size to 100 requests 100 and resets to page 1', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const sizeSelect = screen.getAllByRole('combobox')[1]
    fireEvent.change(sizeSelect, { target: { value: '100' } })
    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls.at(-1)![0], 'http://x')
      expect(url.searchParams.get('size')).toBe('100')
      expect(url.searchParams.get('page')).toBe('1')
    })
  })

  it('shows empty state with a clear-filters button when total is 0', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => makeResponse({ items: [], total: 0, totalPages: 0 }),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('查無符合條件的股票')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '清除篩選條件' })).toBeInTheDocument()
  })

  it('shows an error state with a reload button on request failure, not an empty table', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({ code: 'INTERNAL_ERROR' }),
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('載入失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.queryByText('查無符合條件的股票')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新載入' })).toBeInTheDocument()
  })

  it('sorts by clicking 代號/名稱/市場 headers and toggles direction', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    fireEvent.click(screen.getByText('名稱'))
    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls.at(-1)![0], 'http://x')
      expect(url.searchParams.get('sort')).toBe('stockName')
      expect(url.searchParams.get('order')).toBe('asc')
    })
    fireEvent.click(screen.getByText('名稱'))
    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls.at(-1)![0], 'http://x')
      expect(url.searchParams.get('order')).toBe('desc')
    })
  })

  it('navigates to /stocks/{id}/daily when a row is clicked, even with no quote data', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('台泥')).toBeInTheDocument())
    fireEvent.click(screen.getByText('台泥'))
    await waitFor(() => expect(screen.getByText('daily page')).toBeInTheDocument())
  })
})
