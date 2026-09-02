import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockListPage from '../pages/StockListPage'
import type { StockListResponse } from '../api/stocks'

function TabParamProbe() {
  const [params] = useSearchParams()
  return <div data-testid="tab-param">{params.get('tab') ?? ''}</div>
}

function makeResponse(): StockListResponse {
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

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/stocks" element={<StockListPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

// The 策略 tab stays mounted alongside 總覽 and fires its own GET /api/strategies,
// GET /api/stocks/sync/progress, and GET /api/stocks?...&size=1 (常駐「共 N 檔」) calls
// on mount — isolate 總覽's `/api/stocks?...` list calls (which never use `size=1`) so
// these assertions aren't coupled to that background traffic.
function overviewCalls(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls.filter((c: unknown[]) => {
    const url = String(c[0])
    return /^\/api\/stocks\?/.test(url) && new URL(url, 'http://x').searchParams.get('size') !== '1'
  })
}

describe('StockListPage tabs', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => makeResponse() })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('defaults to 總覽 when no tab param is present', async () => {
    renderAt('/stocks')
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByRole('tab', { name: '總覽' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'block' })
    expect(screen.getByTestId('sl-tabpanel-strategy')).toHaveStyle({ display: 'none' })
  })

  it('lands on 策略 when the URL already has ?tab=strategy', async () => {
    renderAt('/stocks?tab=strategy')
    await waitFor(() => expect(screen.getByTestId('sl-tabpanel-strategy')).toHaveStyle({ display: 'block' }))
    expect(screen.getByRole('tab', { name: '策略' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'none' })
  })

  it('falls back to 總覽 for an unrecognised tab value instead of a blank screen', async () => {
    renderAt('/stocks?tab=nonsense')
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByRole('tab', { name: '總覽' })).toHaveAttribute('aria-selected', 'true')
  })

  it('shows three tabs in order 總覽／策略／動態', async () => {
    renderAt('/stocks')
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual(['總覽', '策略', '動態'])
  })

  it('lands on 動態 when the URL already has ?tab=momentum', async () => {
    renderAt('/stocks?tab=momentum')
    await waitFor(() => expect(screen.getByTestId('sl-tabpanel-momentum')).toHaveStyle({ display: 'block' }))
    expect(screen.getByRole('tab', { name: '動態' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'none' })
  })

  it('falls back to 總覽 for a value other than momentum/strategy instead of a blank screen', async () => {
    renderAt('/stocks?tab=foo')
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByRole('tab', { name: '總覽' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('sl-tabpanel-momentum')).toHaveStyle({ display: 'none' })
  })

  it('clicking 動態 updates the ?tab= URL param to momentum', async () => {
    render(
      <MemoryRouter initialEntries={['/stocks']}>
        <Routes>
          <Route
            path="/stocks"
            element={
              <>
                <StockListPage />
                <TabParamProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('tab', { name: '動態' }))
    expect(screen.getByTestId('tab-param').textContent).toBe('momentum')
  })

  it('switches to 動態 without refetching or unmounting the overview content, and header total is unchanged', async () => {
    renderAt('/stocks')
    await waitFor(() => expect(overviewCalls(fetchMock)).toHaveLength(1))
    await waitFor(() => expect(screen.getAllByText('1').length).toBeGreaterThan(0))

    fireEvent.click(screen.getByRole('tab', { name: '動態' }))
    expect(screen.getByTestId('sl-tabpanel-momentum')).toHaveStyle({ display: 'block' })
    expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'none' })
    // header total stays the overview tab's last value while parked on 動態
    expect(screen.getByText('股票總覽')).toBeInTheDocument()
    expect(screen.getAllByText('1').length).toBeGreaterThan(0)
    // still there, just hidden — proves it wasn't unmounted/reloaded
    expect(screen.getByText('台積電')).toBeInTheDocument()
    expect(overviewCalls(fetchMock)).toHaveLength(1)

    fireEvent.click(screen.getByRole('tab', { name: '總覽' }))
    expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'block' })
    expect(overviewCalls(fetchMock)).toHaveLength(1)
  })

  it('round-trips through all three tabs and back to 總覽 without refetching or losing filters/page', async () => {
    renderAt('/stocks')
    await waitFor(() => expect(overviewCalls(fetchMock)).toHaveLength(1))

    fireEvent.click(screen.getByRole('tab', { name: '策略' }))
    fireEvent.click(screen.getByRole('tab', { name: '動態' }))
    fireEvent.click(screen.getByRole('tab', { name: '總覽' }))

    expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'block' })
    expect(screen.getByText('台積電')).toBeInTheDocument()
    // no additional /api/stocks list requests were made by the round trip
    expect(overviewCalls(fetchMock)).toHaveLength(1)
  })

  it('switches tabs without refetching or unmounting the overview content', async () => {
    renderAt('/stocks')
    await waitFor(() => expect(overviewCalls(fetchMock)).toHaveLength(1))

    fireEvent.click(screen.getByRole('tab', { name: '策略' }))
    expect(screen.getByTestId('sl-tabpanel-strategy')).toHaveStyle({ display: 'block' })
    expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'none' })
    // still there, just hidden — proves it wasn't unmounted/reloaded
    expect(screen.getByText('台積電')).toBeInTheDocument()
    expect(overviewCalls(fetchMock)).toHaveLength(1)

    fireEvent.click(screen.getByRole('tab', { name: '總覽' }))
    expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'block' })
    expect(overviewCalls(fetchMock)).toHaveLength(1)
  })

  it('keeps the header total visible and unchanged across a tab switch', async () => {
    renderAt('/stocks')
    await waitFor(() => expect(screen.getByText('股票總覽')).toBeInTheDocument())
    await waitFor(() => expect(screen.getAllByText('1').length).toBeGreaterThan(0))
    fireEvent.click(screen.getByRole('tab', { name: '策略' }))
    expect(screen.getByText('股票總覽')).toBeInTheDocument()
  })

  it('keeps the 策略 tab sync progress alive across a round-trip through 總覽 (state does not live only inside the unmounted tab)', async () => {
    const CATALOG = {
      strategies: [
        { code: 'BOX_BREAKOUT', name: '箱型突破', presets: [{ code: 'STANDARD', name: '標準', description: '標準說明' }] },
        { code: 'HIGHER_LOWS', name: '底底高', presets: [{ code: 'STANDARD', name: '標準', description: '標準說明' }] },
      ],
    }
    let progressCallCount = 0
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies')) return Promise.resolve({ ok: true, json: async () => CATALOG })
      if (u.startsWith('/api/stocks/sync/backfill')) {
        return Promise.resolve({ ok: true, status: 202, json: async () => ({ jobType: 'PRICE_BACKFILL', targetCount: 34, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' }) })
      }
      if (u.startsWith('/api/stocks/sync/progress')) {
        progressCallCount += 1
        // Stays "running" for every poll in this test — we only care that the running
        // state survives a tab round-trip, not that it eventually completes.
        return Promise.resolve({
          ok: true,
          json: async () => ({
            jobType: 'PRICE_BACKFILL',
            total: 34,
            pending: 10,
            running: 1,
            done: 23,
            failed: 0,
            skipped: 0,
            lastSyncedAt: null,
            failedItems: [],
          }),
        })
      }
      return Promise.resolve({ ok: true, json: async () => makeResponse() })
    })

    renderAt('/stocks?tab=strategy')
    // The mocked progress response is already "running" on the very first load (as it
    // would be for the startup catch-up job), so the tab auto-detects it and starts
    // polling without the user clicking the button.
    await waitFor(() => expect(screen.getByRole('button', { name: '同步中…' })).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('已完成 23 / 34 檔')).toBeInTheDocument())
    const callsBeforeSwitch = progressCallCount

    // Switch to 總覽 and back — the strategy tab panel unmounts from view (display:none)
    // but must not lose its in-flight sync state.
    fireEvent.click(screen.getByRole('tab', { name: '總覽' }))
    await waitFor(() => expect(screen.getByTestId('sl-tabpanel-overview')).toHaveStyle({ display: 'block' }))

    fireEvent.click(screen.getByRole('tab', { name: '策略' }))
    await waitFor(() => expect(screen.getByTestId('sl-tabpanel-strategy')).toHaveStyle({ display: 'block' }))

    expect(screen.getByRole('button', { name: '同步中…' })).toBeInTheDocument()
    expect(screen.getByText('已完成 23 / 34 檔')).toBeInTheDocument()
    // Polling kept running the whole time it was hidden, rather than resetting on remount.
    await waitFor(() => expect(progressCallCount).toBeGreaterThan(callsBeforeSwitch), { timeout: 7000 })
  }, 10000)

  it('syncs the tab switch into the ?tab= URL param', async () => {
    render(
      <MemoryRouter initialEntries={['/stocks']}>
        <Routes>
          <Route
            path="/stocks"
            element={
              <>
                <StockListPage />
                <TabParamProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByTestId('tab-param').textContent).toBe('')

    fireEvent.click(screen.getByRole('tab', { name: '策略' }))
    expect(screen.getByTestId('tab-param').textContent).toBe('strategy')

    fireEvent.click(screen.getByRole('tab', { name: '總覽' }))
    expect(screen.getByTestId('tab-param').textContent).toBe('')
  })
})
