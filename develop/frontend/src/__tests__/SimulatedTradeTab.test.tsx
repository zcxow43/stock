import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SimulatedTradeTab from '../pages/SimulatedTradeTab'
import type { SimulatedTradeItem, SimulatedTradeListResponse } from '../api/simulatedTrades'

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

function item(overrides: Partial<SimulatedTradeItem> = {}): SimulatedTradeItem {
  return {
    id: 12,
    stockId: '2330',
    stockName: '台積電',
    buyDate: '2026-09-17',
    buyPrice: 1150.0,
    shares: 1000,
    buyFee: 1638,
    cost: 1151638,
    currentDate: '2026-09-18',
    currentPrice: 1205.0,
    sellFee: 1717,
    sellTax: 3615,
    unrealizedProfit: 48030,
    returnPercent: 4.17,
    ...overrides,
  }
}

function listResponse(overrides: Partial<SimulatedTradeListResponse> = {}): SimulatedTradeListResponse {
  return {
    asOfDate: '2026-09-18',
    lotSize: 1000,
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    totalCost: 1151638,
    totalUnrealizedProfit: 48030,
    totalReturnPercent: 4.17,
    items: [item()],
    ...overrides,
  }
}

function emptyResponse(): SimulatedTradeListResponse {
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

describe('SimulatedTradeTab', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let getResponder: () => { status: number; body: unknown }

  beforeEach(() => {
    getResponder = () => ({ status: 200, body: emptyResponse() })
    fetchMock = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      const method = init?.method ?? 'GET'
      if (u === '/api/simulated-trades' && method === 'GET') {
        const { status, body } = getResponder()
        return Promise.resolve(jsonResponse(status, body))
      }
      return Promise.reject(new Error(`unexpected fetch: ${method} ${u}`))
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  function getCalls() {
    return fetchMock.mock.calls.filter((c: unknown[]) => {
      const init = c[1] as RequestInit | undefined
      return String(c[0]) === '/api/simulated-trades' && (init?.method ?? 'GET') === 'GET'
    })
  }

  // ---------- 分頁與加入 ----------

  it('fetches the list once on mount', async () => {
    getResponder = () => ({ status: 200, body: emptyResponse() })
    render(<SimulatedTradeTab />)
    await waitFor(() => expect(getCalls()).toHaveLength(1))
  })

  it('renders only a stockId input and 加入 button — no date/price/quantity input anywhere', async () => {
    getResponder = () => ({ status: 200, body: listResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('台積電')
    // The only <input> on the page is the stock-code text box.
    const inputs = screen.getAllByRole('textbox')
    expect(inputs).toHaveLength(1)
    expect(inputs[0]).toHaveAttribute('placeholder', '輸入股票代號，例如 2330')
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
  })

  it('hint text uses lotSize from the response, not a hardcoded value', async () => {
    getResponder = () => ({ status: 200, body: emptyResponse() })
    const first = render(<SimulatedTradeTab />)
    await screen.findByText('以今日以前最後一個交易日的收盤價買進 1 張（每筆 1,000 股）')
    first.unmount()

    fetchMock.mockClear()
    getResponder = () => ({ status: 200, body: { ...emptyResponse(), lotSize: 100 } })
    render(<SimulatedTradeTab />)
    await screen.findByText('以今日以前最後一個交易日的收盤價買進 1 張（每筆 100 股）')
  })

  it('disables 加入 when input is empty, enables it once text is entered, and Enter submits POST with trimmed body', async () => {
    getResponder = () => ({ status: 200, body: emptyResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('尚無模擬持股，輸入股票代號加入第一筆')

    const addButton = screen.getByRole('button', { name: '加入' })
    expect(addButton).toBeDisabled()

    const input = screen.getByPlaceholderText('輸入股票代號，例如 2330')
    fireEvent.change(input, { target: { value: '  2330  ' } })
    expect(addButton).not.toBeDisabled()

    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      const method = init?.method ?? 'GET'
      if (u === '/api/simulated-trades' && method === 'POST') {
        expect(JSON.parse(String(init?.body))).toEqual({ stockId: '2330' })
        return Promise.resolve(jsonResponse(201, item()))
      }
      if (u === '/api/simulated-trades' && method === 'GET') {
        return Promise.resolve(jsonResponse(200, listResponse()))
      }
      return Promise.reject(new Error(`unexpected fetch: ${method} ${u}`))
    })
    fireEvent.keyDown(input, { key: 'Enter' })
    await screen.findByText('台積電')
  })

  it('加入 success clears the input, keeps focus on it, refetches, and the new row appears at the top', async () => {
    getResponder = () => ({ status: 200, body: emptyResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('尚無模擬持股，輸入股票代號加入第一筆')

    const input = screen.getByPlaceholderText('輸入股票代號，例如 2330') as HTMLInputElement
    fireEvent.change(input, { target: { value: '2317' } })

    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      const method = init?.method ?? 'GET'
      if (u === '/api/simulated-trades' && method === 'POST') {
        return Promise.resolve(jsonResponse(201, item({ id: 99, stockId: '2317', stockName: '鴻海' })))
      }
      if (u === '/api/simulated-trades' && method === 'GET') {
        return Promise.resolve(
          jsonResponse(
            200,
            listResponse({
              items: [item({ id: 99, stockId: '2317', stockName: '鴻海' }), item()],
            }),
          ),
        )
      }
      return Promise.reject(new Error(`unexpected fetch: ${method} ${u}`))
    })

    fireEvent.click(screen.getByRole('button', { name: '加入' }))
    await waitFor(() => expect(input.value).toBe(''))
    await screen.findByText('鴻海')
    expect(document.activeElement).toBe(input)

    const rows = screen.getAllByRole('row').slice(1) // drop header row
    expect(within(rows[0]).getByText('2317')).toBeInTheDocument()
    expect(within(rows[1]).getByText('2330')).toBeInTheDocument()
  })

  // ---------- 表格與數值 ----------

  it('renders columns in spec order with correct formatting', async () => {
    getResponder = () => ({ status: 200, body: listResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('台積電')

    const headerCells = screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headerCells).toEqual(['代號 / 名稱', '買進日', '買進價', '現價日', '現價', '成本', '未實現損益', '報酬率', '刪除'])

    const row = screen.getAllByRole('row')[1]
    expect(within(row).getByText('2330')).toBeInTheDocument()
    expect(within(row).getByText('台積電')).toBeInTheDocument()
    expect(within(row).getByText('2026-09-17')).toBeInTheDocument()
    expect(within(row).getByText('1150.00')).toBeInTheDocument()
    expect(within(row).getByText('2026-09-18')).toBeInTheDocument()
    expect(within(row).getByText('1205.00')).toBeInTheDocument()
    expect(within(row).getByText('1,151,638')).toBeInTheDocument()
    expect(within(row).getByText('+48,030')).toBeInTheDocument()
    expect(within(row).getByText('+4.17%')).toBeInTheDocument()
  })

  it('applies sl-up/sl-down/sl-flat by sign on unrealized profit and return percent; cost/prices are never colored', async () => {
    getResponder = () => ({
      status: 200,
      body: listResponse({
        items: [
          item({ id: 1, stockId: '1001', unrealizedProfit: 100, returnPercent: 1.5 }),
          item({ id: 2, stockId: '1002', unrealizedProfit: -100, returnPercent: -1.5 }),
          item({ id: 3, stockId: '1003', unrealizedProfit: 0, returnPercent: 0 }),
        ],
      }),
    })
    render(<SimulatedTradeTab />)
    await screen.findByText('1001')

    const rows = screen.getAllByRole('row').slice(1)
    const [up, down, flat] = rows
    expect(within(up).getByText('+100').className).toContain('sl-up')
    expect(within(up).getByText('+1.50%').className).toContain('sl-up')
    expect(within(down).getByText('-100').className).toContain('sl-down')
    expect(within(down).getByText('-1.50%').className).toContain('sl-down')
    expect(within(flat).getByText('0').className).toContain('sl-flat')
    expect(within(flat).getByText('0.00%').className).toContain('sl-flat')

    // cost/buyPrice/currentPrice cells never carry a gain class
    for (const row of rows) {
      const cost = within(row).getByText(/^1,151,638$/)
      expect(cost.className).not.toMatch(/sl-up|sl-down|sl-flat/)
    }
  })

  it('marks 現價日 with the secondary color class only when it differs from asOfDate', async () => {
    getResponder = () => ({
      status: 200,
      body: listResponse({
        asOfDate: '2026-09-18',
        items: [
          item({ id: 1, stockId: '1001', currentDate: '2026-09-18' }),
          item({ id: 2, stockId: '1002', currentDate: '2026-09-16' }),
        ],
      }),
    })
    render(<SimulatedTradeTab />)
    await screen.findByText('1001')
    const rows = screen.getAllByRole('row').slice(1)
    expect(within(rows[0]).getByText('2026-09-18', { selector: 'td' }).className).not.toContain('sim-stale-date')
    expect(within(rows[1]).getByText('2026-09-16').className).toContain('sim-stale-date')
  })

  it('rows are not clickable and carry no navigation handler / pointer-cursor class', async () => {
    getResponder = () => ({ status: 200, body: listResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('台積電')
    const row = screen.getAllByRole('row')[1]
    expect(row.className).not.toMatch(/sl-row/)
  })

  it('table has no sortable header interaction', async () => {
    getResponder = () => ({ status: 200, body: listResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('台積電')
    for (const th of screen.getAllByRole('columnheader')) {
      expect(th.className).not.toMatch(/sortable/)
    }
  })

  it('totals come directly from the response even when they do not equal the sum of the rows', async () => {
    getResponder = () => ({
      status: 200,
      body: listResponse({
        items: [
          item({ id: 1, stockId: '1001', cost: 100, unrealizedProfit: 10, returnPercent: 10 }),
          item({ id: 2, stockId: '1002', cost: 200, unrealizedProfit: 20, returnPercent: 20 }),
        ],
        totalCost: 999999, // deliberately not 100+200
        totalUnrealizedProfit: 888888,
        totalReturnPercent: 77.77,
      }),
    })
    render(<SimulatedTradeTab />)
    await screen.findByText('1001')
    expect(screen.getByText('999,999')).toBeInTheDocument()
    expect(screen.getByText('+888,888')).toBeInTheDocument()
    expect(screen.getByText('+77.77%')).toBeInTheDocument()
  })

  it('fee-rate line reflects feeRatePercent/taxRatePercent from the response', async () => {
    getResponder = () => ({ status: 200, body: listResponse() })
    const first = render(<SimulatedTradeTab />)
    await screen.findByText('未實現損益已扣手續費 0.1425%（買賣各一次）與證交稅 0.3%，賣出成本以現價估算')
    first.unmount()

    fetchMock.mockClear()
    getResponder = () => ({ status: 200, body: listResponse({ feeRatePercent: 0.1 }) })
    render(<SimulatedTradeTab />)
    await screen.findByText('未實現損益已扣手續費 0.1%（買賣各一次）與證交稅 0.3%，賣出成本以現價估算')
  })

  // ---------- 刪除與狀態 ----------

  it('刪除 fires DELETE with no confirm dialog, then refetches and totals update', async () => {
    getResponder = () => ({ status: 200, body: listResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('台積電')

    let deleteCalled = false
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      const method = init?.method ?? 'GET'
      if (u === '/api/simulated-trades/12' && method === 'DELETE') {
        deleteCalled = true
        return Promise.resolve({ ok: true, status: 204, json: async () => undefined })
      }
      if (u === '/api/simulated-trades' && method === 'GET') {
        return Promise.resolve(jsonResponse(200, emptyResponse()))
      }
      return Promise.reject(new Error(`unexpected fetch: ${method} ${u}`))
    })

    fireEvent.click(screen.getByRole('button', { name: '刪除' }))
    await waitFor(() => expect(deleteCalled).toBe(true))
    await screen.findByText('尚無模擬持股，輸入股票代號加入第一筆')
    expect(screen.queryByText('確定')).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('empty items shows the empty message and — for all three totals, not 0/0%', async () => {
    getResponder = () => ({ status: 200, body: emptyResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('尚無模擬持股，輸入股票代號加入第一筆')
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(3)
    expect(screen.queryByText('0.00%')).not.toBeInTheDocument()
  })

  it('shows 載入中… while loading, and the add row stays usable', async () => {
    let resolveGet: (v: unknown) => void = () => {}
    fetchMock.mockImplementation(() => new Promise((resolve) => { resolveGet = resolve }))
    render(<SimulatedTradeTab />)
    await screen.findByText('載入中…')
    expect(screen.getByPlaceholderText('輸入股票代號，例如 2330')).not.toBeDisabled()
    resolveGet(jsonResponse(200, emptyResponse()))
    await screen.findByText('尚無模擬持股，輸入股票代號加入第一筆')
  })

  it('shows 載入失敗 + 重試 when the very first GET fails', async () => {
    fetchMock.mockImplementation(() => Promise.resolve({ ok: false, status: 500, json: async () => ({ code: 'INTERNAL' }) }))
    render(<SimulatedTradeTab />)
    await screen.findByText('載入失敗，請稍後再試')
    expect(screen.getByRole('button', { name: '重試' })).toBeInTheDocument()
  })

  // ---------- 錯誤處理（加入） ----------

  const addErrorCases: { code: string; body: Record<string, unknown>; expected: string }[] = [
    { code: 'INVALID_STOCK_ID', body: { code: 'INVALID_STOCK_ID' }, expected: '請輸入股票代號' },
    { code: 'UNKNOWN_STOCK_ID', body: { code: 'UNKNOWN_STOCK_ID', unknownIds: ['9999'] }, expected: '找不到代號 9999' },
    {
      code: 'NO_PRICE_BEFORE_TODAY',
      body: { code: 'NO_PRICE_BEFORE_TODAY', stockId: '9999' },
      expected: '9999 在今日以前沒有可用的收盤價，無法加入',
    },
    {
      code: 'DUPLICATE_SIMULATED_TRADE',
      body: { code: 'DUPLICATE_SIMULATED_TRADE', stockId: '2330', buyDate: '2026-09-18' },
      expected: '2330 在 2026-09-18 已經加過了',
    },
  ]

  for (const { code, body, expected } of addErrorCases) {
    it(`加入失敗 ${code} shows "${expected}" under the input without clearing it`, async () => {
      getResponder = () => ({ status: 200, body: emptyResponse() })
      render(<SimulatedTradeTab />)
      await screen.findByText('尚無模擬持股，輸入股票代號加入第一筆')

      const input = screen.getByPlaceholderText('輸入股票代號，例如 2330') as HTMLInputElement
      const attempted = (body.stockId as string) ?? '9999'
      fireEvent.change(input, { target: { value: attempted } })

      const status = code === 'DUPLICATE_SIMULATED_TRADE' ? 409 : 400
      fetchMock.mockImplementation((url: string, init?: RequestInit) => {
        const u = String(url)
        const method = init?.method ?? 'GET'
        if (u === '/api/simulated-trades' && method === 'POST') {
          return Promise.resolve(jsonResponse(status, body))
        }
        return Promise.reject(new Error(`unexpected fetch: ${method} ${u}`))
      })

      fireEvent.click(screen.getByRole('button', { name: '加入' }))
      await screen.findByText(expected)
      expect(input.value).toBe(attempted)
    })
  }

  it('刪除 404 shows "這筆持股已經不存在" and refetches the list', async () => {
    getResponder = () => ({ status: 200, body: listResponse() })
    render(<SimulatedTradeTab />)
    await screen.findByText('台積電')

    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      const method = init?.method ?? 'GET'
      if (u === '/api/simulated-trades/12' && method === 'DELETE') {
        return Promise.resolve(jsonResponse(404, { code: 'SIMULATED_TRADE_NOT_FOUND', id: 12 }))
      }
      if (u === '/api/simulated-trades' && method === 'GET') {
        return Promise.resolve(jsonResponse(200, emptyResponse()))
      }
      return Promise.reject(new Error(`unexpected fetch: ${method} ${u}`))
    })

    fireEvent.click(screen.getByRole('button', { name: '刪除' }))
    await screen.findByText('這筆持股已經不存在')
    await screen.findByText('尚無模擬持股，輸入股票代號加入第一筆')
  })

  it('contains no advice/recommendation wording anywhere on the page', async () => {
    getResponder = () => ({ status: 200, body: listResponse() })
    const { container } = render(<SimulatedTradeTab />)
    await screen.findByText('台積電')
    const text = container.textContent ?? ''
    expect(text).not.toMatch(/建議|推薦|可進場/)
  })
})
