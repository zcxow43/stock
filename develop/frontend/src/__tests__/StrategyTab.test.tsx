import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StrategyTab from '../pages/StrategyTab'

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

const CATALOG = {
  strategies: [
    {
      code: 'BOX_BREAKOUT',
      name: '箱型突破',
      presets: [
        { code: 'STRICT', name: '嚴格', description: '回看 60 根，箱高 < 5%' },
        { code: 'STANDARD', name: '標準', description: '回看 20 根，箱高 < 8%' },
        { code: 'LOOSE', name: '寬鬆', description: '不驗證盤整' },
      ],
    },
    {
      code: 'HIGHER_LOWS',
      name: '底底高',
      presets: [
        { code: 'STRICT', name: '嚴格', description: '左右各 5 根，需 3 段遞增' },
        { code: 'STANDARD', name: '標準', description: '左右各 3 根，需 2 段遞增' },
        { code: 'LOOSE', name: '寬鬆', description: '左右各 2 根，高過即計' },
      ],
    },
  ],
}

function progressResponse(overrides: Record<string, unknown> = {}) {
  return {
    jobType: 'PRICE_BACKFILL',
    total: 34,
    pending: 0,
    running: 0,
    done: 34,
    failed: 0,
    skipped: 0,
    lastSyncedAt: '2026-08-30T12:00:00',
    failedItems: [],
    ...overrides,
  }
}

function boxScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'BOX_BREAKOUT',
        preset: 'STANDARD',
        matchedCount: 1,
        items: [
          {
            stockId: '2330',
            stockName: '台積電',
            signalDate: '2026-08-27',
            detail: { boxHigh: 2380.0, boxLow: 2250.0, breakoutClose: 2420.0, breakoutPercent: 1.68, volumeRatio: 1.82 },
          },
        ],
        insufficientData: ['6669'],
        pendingConfirm: ['1101'],
      },
    ],
  }
}

function higherLowsScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 2,
    results: [
      {
        strategy: 'HIGHER_LOWS',
        preset: 'STRICT',
        matchedCount: 1,
        items: [
          {
            stockId: '2317',
            stockName: '鴻海',
            signalDate: '2026-08-25',
            detail: {
              lows: [
                { tradeDate: '2026-07-08', low: 240.0 },
                { tradeDate: '2026-08-25', low: 262.5 },
              ],
            },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

function zeroHitResponse() {
  return {
    startDate: '2026-08-25',
    endDate: '2026-08-27',
    scannedStocks: 34,
    results: [
      { strategy: 'BOX_BREAKOUT', preset: 'STRICT', matchedCount: 0, items: [], insufficientData: [], pendingConfirm: [] },
    ],
  }
}

function bothStrategiesResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      { ...boxScanResponse().results[0] },
      { ...higherLowsScanResponse().results[0] },
    ],
  }
}

function renderTab() {
  return render(
    <MemoryRouter initialEntries={['/stocks?tab=strategy']}>
      <Routes>
        <Route path="/stocks" element={<StrategyTab />} />
        <Route path="/stocks/:stockId/daily" element={<div>daily page for the clicked row</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('StrategyTab', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let scanResponder: () => unknown
  let progressResponder: () => unknown
  let backfillResponder: () => { status: number; body: unknown }

  beforeEach(() => {
    scanResponder = () => boxScanResponse()
    progressResponder = () => progressResponse()
    backfillResponder = () => ({ status: 202, body: { jobType: 'PRICE_BACKFILL', targetCount: 34, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' } })

    fetchMock = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) {
        return Promise.resolve(jsonResponse(200, scanResponder()))
      }
      if (u.startsWith('/api/strategies')) {
        return Promise.resolve(jsonResponse(200, CATALOG))
      }
      if (u.startsWith('/api/stocks/sync/progress')) {
        return Promise.resolve(jsonResponse(200, progressResponder()))
      }
      if (u.startsWith('/api/stocks/sync/backfill')) {
        const { status, body } = backfillResponder()
        return Promise.resolve(jsonResponse(status, body))
      }
      if (u.startsWith('/api/stocks?')) {
        return Promise.resolve(
          jsonResponse(200, {
            page: 1,
            size: 20,
            total: 1,
            totalPages: 1,
            items: [
              {
                stockId: '2317',
                stockName: '鴻海',
                market: 'TSE',
                isActive: true,
                latestTradeDate: '2026-08-28',
                latestClose: 253,
                previousClose: 252,
                changeAmount: 1,
                changePercent: 0.4,
                latestVolume: 100,
              },
            ],
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, {}))
      void init
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('renders strategy names and preset descriptions from GET /api/strategies, not hard-coded', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByText('底底高')).toBeInTheDocument()
    // STANDARD is the default preset for both cards — its description shows immediately.
    expect(screen.getByText('回看 20 根，箱高 < 8%')).toBeInTheDocument()
    expect(screen.getByText('左右各 3 根，需 2 段遞增')).toBeInTheDocument()
  })

  it('disables the preset dropdown for an unchecked strategy card and enables it once checked', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const card = screen.getByText('箱型突破').closest('.st-strategy-card')!
    const select = within(card).getByRole('combobox') as HTMLSelectElement
    expect(select.disabled).toBe(true)

    fireEvent.click(within(card).getByRole('checkbox'))
    expect(select.disabled).toBe(false)
    expect(select.value).toBe('STANDARD')
  })

  it('disables 開始掃描 and shows a hint when no strategy is selected', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()
    expect(screen.getByText('請至少勾選一個策略')).toBeInTheDocument()
  })

  it('defaults the date range to one calendar month back through today', async () => {
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const [startInput, endInput] = screen.getAllByDisplayValue(/\d{4}-\d{2}-\d{2}/) as HTMLInputElement[]
    expect(startInput.value).toBe('2026-07-30')
    expect(endInput.value).toBe('2026-08-30')
  })

  it('applies the 近三個月 shortcut and highlights it', async () => {
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '近三個月' }))
    const [startInput] = screen.getAllByDisplayValue(/\d{4}-\d{2}-\d{2}/) as HTMLInputElement[]
    expect(startInput.value).toBe('2026-05-30')
    expect(screen.getByRole('button', { name: '近三個月' }).className).toContain('st-shortcut-active')
  })

  it('blocks the scan and shows an inline message when start date is after end date', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))

    const [startInput, endInput] = screen.getAllByDisplayValue(/\d{4}-\d{2}-\d{2}/) as HTMLInputElement[]
    fireEvent.change(startInput, { target: { value: '2026-09-01' } })
    fireEvent.change(endInput, { target: { value: '2026-08-01' } })

    expect(screen.getByText('起日不可晚於迄日')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()

    const scanCallsBefore = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
    expect(scanCallsBefore).toBe(0)
  })

  it('sends no stockIds for the default 全市場 scope', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('2330')).toBeInTheDocument())
    const scanCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan'))!
    const body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.stockIds).toBeUndefined()
  })

  it('lets the user search and add a stock under 指定股票, shown as a removable tag', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: '指定股票' }))
    const input = screen.getByPlaceholderText('輸入代號或名稱搜尋加入')
    fireEvent.change(input, { target: { value: '2317' } })
    await vi.advanceTimersByTimeAsync(300)

    await vi.waitFor(() => expect(screen.getByText('2317 鴻海')).toBeInTheDocument())
    fireEvent.click(screen.getByText('2317 鴻海'))

    expect(screen.getByLabelText('移除 2317')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('移除 2317'))
    expect(screen.queryByLabelText('移除 2317')).not.toBeInTheDocument()
    vi.useRealTimers()
  })

  it('shows two result blocks in selection order when two strategies are scanned', async () => {
    scanResponder = () => bothStrategiesResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())

    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getAllByText(/命中 1 檔/)).toHaveLength(2))
    const titles = screen.getAllByText(/命中 1 檔/).map((el) => el.textContent)
    expect(titles[0]).toContain('箱型突破')
    expect(titles[1]).toContain('底底高')
  })

  it('renders the box-breakout table with formatted range/percent/multiple, and insufficientData/pendingConfirm stay out of the hit table', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    expect(screen.getByText('2250.00 ~ 2380.00')).toBeInTheDocument()
    expect(screen.getByText('1.68%')).toBeInTheDocument()
    expect(screen.getByText('1.82×')).toBeInTheDocument()

    // insufficientData/pendingConfirm summaries appear, but their stock ids are not in the hit table
    expect(screen.getByText('另有 1 檔因區間前的歷史資料不足而未納入判定')).toBeInTheDocument()
    expect(screen.getByText('另有 1 檔已突破，但確認日尚未到')).toBeInTheDocument()
    expect(screen.queryByText('6669')).not.toBeInTheDocument()
    expect(screen.queryByText('1101')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('另有 1 檔因區間前的歷史資料不足而未納入判定'))
    expect(screen.getByText('6669')).toBeInTheDocument()
  })

  it('renders the 底底高 table with the low-point sequence and cumulative rise', async () => {
    scanResponder = () => higherLowsScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('底底高')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('2317 鴻海')).toBeInTheDocument())
    expect(screen.getByText('07-08 240.00→08-25 262.50')).toBeInTheDocument()
    // (262.50 / 240.00 - 1) * 100 = 9.375 -> 9.38%
    expect(screen.getByText('9.38%')).toBeInTheDocument()
  })

  it('navigates to /stocks/{stockId}/daily when a result row is clicked', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    fireEvent.click(screen.getByText('2330 台積電'))
    await waitFor(() => expect(screen.getByText('daily page for the clicked row')).toBeInTheDocument())
  })

  it('shows the zero-hit message together with the last-synced time, not an empty table', async () => {
    scanResponder = () => zeroHitResponse()
    progressResponder = () => progressResponse({ lastSyncedAt: '2026-08-30T09:15:00' })
    renderTab()
    await waitFor(() => expect(screen.getByText('最後同步：2026-08-30 09:15')).toBeInTheDocument())

    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('此區間內沒有命中的股票')).toBeInTheDocument())
    expect(within(screen.getByText('此區間內沒有命中的股票').closest('.st-result-zero')!).getByText(/最後同步/)).toBeInTheDocument()
  })

  it('shows 尚未同步 when the backend has never completed a sync', async () => {
    progressResponder = () => progressResponse({ lastSyncedAt: null })
    renderTab()
    await waitFor(() => expect(screen.getByText('最後同步：尚未同步')).toBeInTheDocument())
  })

  it('shows a running state with completed/total counts after clicking 同步日 K 至今日, without blocking scanning', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      return pollCount <= 1
        ? progressResponse({ total: 34, pending: 20, running: 1, done: 13 })
        : progressResponse({ total: 34, pending: 0, running: 0, done: 30, failed: 2, skipped: 2, lastSyncedAt: '2026-08-30T13:00:00' })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    expect(screen.getByRole('button', { name: '同步中…' })).toBeDisabled()

    // sync running must not disable the scan button
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()

    await vi.waitFor(() => expect(screen.getByText('已完成 13 / 34 檔')).toBeInTheDocument())

    await vi.advanceTimersByTimeAsync(5000)
    await vi.waitFor(() => expect(screen.getByText('完成 30 檔／失敗 2 檔／略過 2 檔')).toBeInTheDocument())
    expect(screen.getByText('最後同步：2026-08-30 13:00')).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('treats a 409 JOB_ALREADY_RUNNING response as "already running", not an error', async () => {
    backfillResponder = () => ({ status: 409, body: { code: 'JOB_ALREADY_RUNNING' } })
    progressResponder = () => progressResponse({ pending: 5, running: 1 })
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '同步中…' })).toBeInTheDocument())
    expect(screen.queryByText(/失敗/)).not.toBeInTheDocument()
  })

  it('shows an error message with a retry button on scan failure, never an empty table', async () => {
    scanResponder = () => {
      throw new Error('network down')
    }
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) return Promise.reject(new Error('network down'))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('掃描失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '重試' })).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('never renders 建議/推薦/可進場 wording anywhere on the page', async () => {
    scanResponder = () => bothStrategiesResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getAllByText(/命中 1 檔/)).toHaveLength(2))

    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/建議|推薦|可進場/)
  })
})
