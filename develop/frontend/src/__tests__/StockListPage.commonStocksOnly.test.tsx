import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockListPage from '../pages/StockListPage'
import type { StockListResponse } from '../api/stocks'

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

function listResponse(total: number): StockListResponse {
  return {
    page: 1,
    size: 50,
    total,
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

function momentumResponse() {
  return {
    metric: 'AVERAGE',
    mode: 'DAYS',
    startDate: '2026-08-04',
    endDate: '2026-08-29',
    tradingDays: 20,
    minGain: 5,
    scannedStocks: 10,
    matchedStockCount: 0,
    insufficientDataCount: 0,
    industries: [],
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

/** Isolates 總覽's own list requests (never `size=1`) from 策略's background 常駐「共 N 檔」call. */
function overviewCalls(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls.filter((c: unknown[]) => {
    const url = String(c[0])
    return /^\/api\/stocks\?/.test(url) && new URL(url, 'http://x').searchParams.get('size') !== '1'
  })
}

function lastQuery(fetchMock: ReturnType<typeof vi.fn>, predicate: (url: string) => boolean): URLSearchParams {
  const calls = fetchMock.mock.calls.filter((c: unknown[]) => predicate(String(c[0])))
  const last = calls[calls.length - 1]
  return new URL(String(last[0]), 'http://x').searchParams
}

describe('StockListPage — 頁面層級「只看上市普通股」設定', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let overviewTotal: number

  beforeEach(() => {
    overviewTotal = 1085
    fetchMock = vi.fn().mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/momentum/gain')) return Promise.resolve(jsonResponse(200, momentumResponse()))
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, { startDate: '2026-08-01', endDate: '2026-08-30', scannedStocks: 0, results: [] }))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, PROGRESS))
      if (u.startsWith('/api/stocks?')) {
        const params = new URL(u, 'http://x').searchParams
        if (params.get('size') === '1') {
          return Promise.resolve(jsonResponse(200, { page: 1, size: 1, total: 1366, totalPages: 1366, items: [] }))
        }
        const commonOnly = params.get('commonStocksOnly') !== 'false'
        return Promise.resolve(jsonResponse(200, listResponse(commonOnly ? overviewTotal : 1377)))
      }
      return Promise.resolve(jsonResponse(404, {}))
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders above the tab bar, right of the header, checked by default', async () => {
    renderPage()
    const checkbox = await screen.findByRole('checkbox', { name: /只看上市普通股/ })
    expect(checkbox).toBeChecked()
    expect(screen.getByText(/只看上市普通股（排除 ETF、特別股、TDR）/)).toBeInTheDocument()
  })

  it("overview's GET /api/stocks carries commonStocksOnly=true by default", async () => {
    renderPage()
    await waitFor(() => expect(overviewCalls(fetchMock).length).toBeGreaterThan(0))
    const params = lastQuery(fetchMock, (u) => /^\/api\/stocks\?/.test(u) && new URL(u, 'http://x').searchParams.get('size') !== '1')
    expect(params.get('commonStocksOnly')).toBe('true')
    expect(await screen.findByText('1,085')).toBeInTheDocument()
  })

  it('unchecking immediately requeries overview with commonStocksOnly=false and a larger total', async () => {
    renderPage()
    await screen.findByText('1,085')

    const checkbox = screen.getByRole('checkbox', { name: /只看上市普通股/ })
    const callsBefore = overviewCalls(fetchMock).length
    fireEvent.click(checkbox)

    await waitFor(() => expect(overviewCalls(fetchMock).length).toBeGreaterThan(callsBefore))
    const params = lastQuery(fetchMock, (u) => /^\/api\/stocks\?/.test(u) && new URL(u, 'http://x').searchParams.get('size') !== '1')
    expect(params.get('commonStocksOnly')).toBe('false')
    expect(await screen.findByText('1,377')).toBeInTheDocument()
  })

  it('stays outside every tab panel: still checked and visible after switching tabs', async () => {
    renderPage()
    await screen.findByText('1,085')
    fireEvent.click(screen.getByRole('tab', { name: '動態' }))
    expect(screen.getByRole('checkbox', { name: /只看上市普通股/ })).toBeChecked()
    fireEvent.click(screen.getByRole('tab', { name: '策略' }))
    expect(screen.getByRole('checkbox', { name: /只看上市普通股/ })).toBeChecked()
  })

  it('toggling only requeries the current (overview) tab, not the hidden 動態 tab', async () => {
    renderPage()
    await screen.findByText('1,085')
    const momentumCallsBefore = fetchMock.mock.calls.filter((c: unknown[]) => String(c[0]).startsWith('/api/momentum/gain')).length

    fireEvent.click(screen.getByRole('checkbox', { name: /只看上市普通股/ }))
    await screen.findByText('1,377')

    const momentumCallsAfter = fetchMock.mock.calls.filter((c: unknown[]) => String(c[0]).startsWith('/api/momentum/gain')).length
    expect(momentumCallsAfter).toBe(momentumCallsBefore)
  })

  it('shares one state across tabs: unchecking in overview then switching to 動態 sends commonStocksOnly=false on its next query', async () => {
    renderPage()
    await screen.findByText('1,085')

    fireEvent.click(screen.getByRole('checkbox', { name: /只看上市普通股/ }))
    await screen.findByText('1,377')

    fireEvent.click(screen.getByRole('tab', { name: '動態' }))
    fireEvent.click(screen.getByRole('button', { name: '查詢' }))

    await waitFor(() => expect(fetchMock.mock.calls.some((c: unknown[]) => String(c[0]).startsWith('/api/momentum/gain'))).toBe(true))
    const params = lastQuery(fetchMock, (u) => u.startsWith('/api/momentum/gain'))
    expect(params.get('commonStocksOnly')).toBe('false')
  })

  it('never writes to localStorage and never appears in the URL', async () => {
    renderPage()
    await screen.findByText('1,085')
    fireEvent.click(screen.getByRole('checkbox', { name: /只看上市普通股/ }))
    await screen.findByText('1,377')
    expect(window.localStorage.length).toBe(0)
    expect(window.location.search).not.toContain('commonStocksOnly')
    expect(window.location.search).not.toContain('common')
  })
})
