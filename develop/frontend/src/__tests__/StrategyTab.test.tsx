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
    backfillResponder = () => ({
      status: 202,
      body: { jobType: 'PRICE_BACKFILL', targetCount: 34, caughtUpCount: 0, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' },
    })

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
    const card = screen.getByText('箱型突破').closest('.st-strategy-card') as HTMLElement
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

  it('applies each of the three date-range shortcuts to the exact expected calendar dates', async () => {
    // Pinned "today" so every expected date below is a literal, independently
    // verified constant — not a re-derivation of the component's own monthsAgo()
    // formula, which would pass even if that formula were wrong.
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())

    const dateInputs = () => screen.getAllByDisplayValue(/\d{4}-\d{2}-\d{2}/) as HTMLInputElement[]

    // 近一個月: 2026-08-30 minus 1 calendar month = 2026-07-30 (July has 30 days, no overflow).
    fireEvent.click(screen.getByRole('button', { name: '近一個月' }))
    let [startInput, endInput] = dateInputs()
    expect(startInput.value).toBe('2026-07-30')
    expect(endInput.value).toBe('2026-08-30')
    expect(screen.getByRole('button', { name: '近一個月' }).className).toContain('st-shortcut-active')

    // 近三個月: 2026-08-30 minus 3 calendar months = 2026-05-30 (May has 31 days, no overflow).
    fireEvent.click(screen.getByRole('button', { name: '近三個月' }))
    ;[startInput, endInput] = dateInputs()
    expect(startInput.value).toBe('2026-05-30')
    expect(endInput.value).toBe('2026-08-30')
    expect(screen.getByRole('button', { name: '近三個月' }).className).toContain('st-shortcut-active')

    // 近半年: 2026-08-30 minus 6 calendar months lands on "Feb 30", which doesn't
    // exist — 2026 is not a leap year, so February has 28 days. The calendar-correct
    // result clamps to the last day of February: 2026-02-28, not an overflow into March.
    fireEvent.click(screen.getByRole('button', { name: '近半年' }))
    ;[startInput, endInput] = dateInputs()
    expect(startInput.value).toBe('2026-02-28')
    expect(endInput.value).toBe('2026-08-30')
    expect(screen.getByRole('button', { name: '近半年' }).className).toContain('st-shortcut-active')
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

    // 代號 and 名稱 render as sibling text nodes inside one <td> ("2330 台積電"), so
    // match the combined text rather than a bare "2330" substring (exact match by default).
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
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

  it('disables the stock-search input and shows a hint once 200 stocks are selected', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, boxScanResponse()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      if (u.startsWith('/api/stocks?')) {
        const keyword = new URL(u, 'http://localhost').searchParams.get('keyword') ?? ''
        return Promise.resolve(
          jsonResponse(200, {
            page: 1,
            size: 20,
            total: 1,
            totalPages: 1,
            items: [
              {
                stockId: keyword,
                stockName: `股票${keyword}`,
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
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, {}))
    })

    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('radio', { name: '指定股票' }))
    const input = screen.getByPlaceholderText('輸入代號或名稱搜尋加入')

    for (let i = 0; i < 200; i++) {
      const code = String(1000 + i)
      fireEvent.change(input, { target: { value: code } })
      await vi.advanceTimersByTimeAsync(300)
      await vi.waitFor(() => expect(screen.getByText(`${code} 股票${code}`)).toBeInTheDocument())
      fireEvent.click(screen.getByText(`${code} 股票${code}`))
    }

    expect(screen.getByText('最多 200 檔')).toBeInTheDocument()
    expect((screen.getByPlaceholderText('輸入代號或名稱搜尋加入') as HTMLInputElement).disabled).toBe(true)
    vi.useRealTimers()
  }, 20000)

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
      // Call #1 is the on-mount fetch, before the user has clicked anything — it must
      // report idle, or the tab's "already running?" auto-detect would flip the button
      // to 同步中 before the click even happens.
      if (pollCount === 1) return progressResponse({ total: 34, pending: 0, running: 0, done: 34 })
      return pollCount === 2
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
    let progressCallCount = 0
    progressResponder = () => {
      progressCallCount += 1
      // Call #1 is the on-mount fetch — idle, so the click below is what drives the
      // running state, not a mount-time auto-detect racing ahead of it. Later polls
      // (post-click) report an in-flight job, matching the 409's "someone else is
      // already running it" semantics, and deliberately never report `failed > 0` so
      // the "not an error" assertion below can't collide with a completion summary.
      return progressCallCount === 1
        ? progressResponse({ pending: 0, running: 0, done: 34 })
        : progressResponse({ pending: 5, running: 1, done: 29 })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '同步中…' })).toBeInTheDocument())
    expect(screen.queryByText(/失敗/)).not.toBeInTheDocument()
  })

  it('shows lastSyncedAt exactly as returned with no timezone conversion (no 8-hour shift)', async () => {
    // `lastSyncedAt` already arrives as Asia/Taipei local time from the backend
    // (specs/backend/stock-price-ingestion.md 「時區」). A naive `new Date(str)` +
    // locale-aware formatting would reinterpret this as UTC and shift it by the local
    // runtime's offset — e.g. this exact value would render as `2026-08-31 07:15` under
    // an implementation that mistakenly re-converts it. The frontend must do a plain
    // string format only.
    progressResponder = () => progressResponse({ lastSyncedAt: '2026-08-30T23:15:00' })
    renderTab()
    await waitFor(() => expect(screen.getByText('最後同步：2026-08-30 23:15')).toBeInTheDocument())
    expect(screen.queryByText(/最後同步：2026-08-31/)).not.toBeInTheDocument()
  })

  it('shows 已是最新，無需更新（N 檔） — not 完成 N 檔 — when caughtUpCount equals targetCount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    backfillResponder = () => ({
      status: 202,
      body: { jobType: 'PRICE_BACKFILL', targetCount: 34, caughtUpCount: 34, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      // Call #1 is the on-mount fetch (idle). From call #2 (the poll right after the
      // click) onward the job is already finished — catchUp skipped every target, so
      // zero external requests were made and there is nothing to be "running" about.
      if (pollCount === 1) return progressResponse({ total: 34, pending: 0, running: 0, done: 34 })
      return progressResponse({
        total: 34,
        pending: 0,
        running: 0,
        done: 34,
        failed: 0,
        skipped: 0,
        lastSyncedAt: '2026-08-30T13:05:00',
      })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))

    await vi.waitFor(() => expect(screen.getByText('已是最新，無需更新（34 檔）')).toBeInTheDocument())
    expect(screen.queryByText(/^完成 /)).not.toBeInTheDocument()
    expect(screen.getByText('最後同步：2026-08-30 13:05')).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('shows 完成／失敗／略過 counts plus an 另 N 檔已是最新 note when only some targets were already caught up', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    backfillResponder = () => ({
      status: 202,
      body: { jobType: 'PRICE_BACKFILL', targetCount: 34, caughtUpCount: 10, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      if (pollCount === 1) return progressResponse({ total: 34, pending: 0, running: 0, done: 34 })
      if (pollCount === 2) return progressResponse({ total: 34, pending: 15, running: 1, done: 8, failed: 0, skipped: 0 })
      return progressResponse({
        total: 34,
        pending: 0,
        running: 0,
        done: 20,
        failed: 2,
        skipped: 2,
        lastSyncedAt: '2026-08-30T13:10:00',
      })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await vi.waitFor(() => expect(screen.getByText('已完成 8 / 34 檔')).toBeInTheDocument())

    await vi.advanceTimersByTimeAsync(5000)
    await vi.waitFor(() => expect(document.body.textContent).toContain('完成 20 檔／失敗 2 檔／略過 2 檔'))
    expect(document.body.textContent).toContain('另 10 檔已是最新')
    vi.useRealTimers()
  })

  it('keeps the completion summary visible after a near-instant sync instead of reverting to an unchanged-looking screen', async () => {
    // Reproduces the reported bug directly: every target already caught up means the
    // whole job (accept -> catchUp-skip everything -> finish) can complete inside a
    // single poll cycle. The running state is never observably "in progress" for more
    // than an instant, so the completion summary is the only signal the user gets that
    // anything happened at all — it must not disappear once the running flash passes.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    backfillResponder = () => ({
      status: 202,
      body: { jobType: 'PRICE_BACKFILL', targetCount: 34, caughtUpCount: 34, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      if (pollCount === 1) return progressResponse({ total: 34, pending: 0, running: 0, done: 34 })
      return progressResponse({ total: 34, pending: 0, running: 0, done: 34, failed: 0, skipped: 0, lastSyncedAt: '2026-08-30T13:20:00' })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await vi.waitFor(() => expect(screen.getByText('已是最新，無需更新（34 檔）')).toBeInTheDocument())

    // Nothing else happens afterward (no further polling once idle) — the summary must
    // still be there well after the fact, and the button must have reverted to its
    // normal label rather than staying stuck showing "同步中…".
    await vi.advanceTimersByTimeAsync(15000)
    expect(screen.getByText('已是最新，無需更新（34 檔）')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '同步中…' })).not.toBeInTheDocument()
    vi.useRealTimers()
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
