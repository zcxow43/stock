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

// The 策略 tab stays mounted alongside 總覽 and fires its own GET /api/strategies and
// GET /api/stocks/sync/progress calls on mount — isolate 總覽's `/api/stocks?...` list
// calls so these assertions aren't coupled to that background traffic.
function overviewCalls(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls.filter((c: unknown[]) => /^\/api\/stocks\?/.test(String(c[0])))
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
