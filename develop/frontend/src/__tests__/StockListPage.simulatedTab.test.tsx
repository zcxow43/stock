import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import StockListPage from '../pages/StockListPage'
import type { StockListResponse } from '../api/stocks'
import type { SimulatedTradeListResponse } from '../api/simulatedTrades'

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

function overviewResponse(): StockListResponse {
  return {
    page: 1,
    size: 50,
    total: 1,
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
    ],
  }
}

function simulatedResponse(): SimulatedTradeListResponse {
  return {
    asOfDate: '2026-09-18',
    lotSize: 1000,
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    totalCost: 0,
    totalUnrealizedProfit: 0,
    totalReturnPercent: null,
    items: [],
  }
}

const CATALOG = { strategies: [] }
const PROGRESS = {
  jobType: 'PRICE_BACKFILL',
  total: 0,
  pending: 0,
  running: 0,
  done: 0,
  failed: 0,
  skipped: 0,
  lastSyncedAt: null,
  failedItems: [],
}

function renderPage(fetchMock: ReturnType<typeof vi.fn>, initial = '/stocks') {
  vi.stubGlobal('fetch', fetchMock)
  return render(
    <MemoryRouter initialEntries={[initial]}>
      <Routes>
        <Route path="/stocks" element={<StockListPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function buildFetchMock() {
  return vi.fn().mockImplementation((url: string) => {
    const u = String(url)
    if (u.startsWith('/api/simulated-trades')) return Promise.resolve(jsonResponse(200, simulatedResponse()))
    if (u.startsWith('/api/strategies/scan')) {
      return Promise.resolve(jsonResponse(200, { startDate: '2026-08-01', endDate: '2026-08-30', scannedStocks: 0, results: [] }))
    }
    if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
    if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, PROGRESS))
    if (u.startsWith('/api/stocks?')) return Promise.resolve(jsonResponse(200, overviewResponse()))
    return Promise.resolve(jsonResponse(404, {}))
  })
}

function simulatedCalls(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls.filter((c: unknown[]) => String(c[0]).startsWith('/api/simulated-trades'))
}

describe('StockListPage — 第四個頁籤「模擬交易」', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shows 模擬交易 as the 4th tab after 動態, navigates to ?tab=simulated, and a direct URL lands there', async () => {
    const fetchMock = buildFetchMock()
    const first = renderPage(fetchMock)
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual(['總覽', '策略', '動態', '模擬交易'])

    fireEvent.click(screen.getByRole('tab', { name: '模擬交易' }))
    await waitFor(() => expect(screen.getByTestId('sl-tabpanel-simulated')).toHaveStyle({ display: 'block' }))
    expect(screen.getByRole('tab', { name: '模擬交易' })).toHaveAttribute('aria-selected', 'true')
    first.unmount()

    const fetchMock2 = buildFetchMock()
    renderPage(fetchMock2, '/stocks?tab=simulated')
    await waitFor(() => expect(screen.getByTestId('sl-tabpanel-simulated')).toHaveStyle({ display: 'block' }))
    expect(screen.getByRole('tab', { name: '模擬交易' })).toHaveAttribute('aria-selected', 'true')
  })

  it('falls back to 總覽 for an unrecognised tab value even with 模擬交易 in the mix', async () => {
    const fetchMock = buildFetchMock()
    renderPage(fetchMock, '/stocks?tab=bogus')
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByRole('tab', { name: '總覽' })).toHaveAttribute('aria-selected', 'true')
  })

  it('fetches GET /api/simulated-trades once on entering the tab, and again on returning to it', async () => {
    const fetchMock = buildFetchMock()
    renderPage(fetchMock)
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('tab', { name: '模擬交易' }))
    await waitFor(() => expect(simulatedCalls(fetchMock)).toHaveLength(1))

    fireEvent.click(screen.getByRole('tab', { name: '總覽' }))
    fireEvent.click(screen.getByRole('tab', { name: '模擬交易' }))
    await waitFor(() => expect(simulatedCalls(fetchMock)).toHaveLength(2))
  })

  it('does not requery 模擬交易, and its request never carries commonStocksOnly, when the page-level checkbox is toggled', async () => {
    const fetchMock = buildFetchMock()
    renderPage(fetchMock)
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('tab', { name: '模擬交易' }))
    await waitFor(() => expect(simulatedCalls(fetchMock)).toHaveLength(1))
    const calledUrl = String(simulatedCalls(fetchMock)[0][0])
    expect(calledUrl).not.toContain('commonStocksOnly')

    const callsBefore = simulatedCalls(fetchMock).length
    fireEvent.click(screen.getByRole('checkbox', { name: /只看上市普通股/ }))
    // give any (unwanted) effect a tick to fire
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(simulatedCalls(fetchMock)).toHaveLength(callsBefore)
  })
})
