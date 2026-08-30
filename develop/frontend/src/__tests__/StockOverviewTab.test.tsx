import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockListPage from '../pages/StockListPage'
import type { StockListItem, StockListResponse } from '../api/stocks'

const ACTIVE_ITEM: StockListItem = {
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
}

const DELISTED_ITEM: StockListItem = {
  stockId: '9999',
  stockName: '測試下市股',
  market: 'TSE',
  isActive: false,
  latestTradeDate: null,
  latestClose: null,
  previousClose: null,
  changeAmount: null,
  changePercent: null,
  latestVolume: null,
}

function listResponse(items: StockListItem[]): StockListResponse {
  return { page: 1, size: 50, total: items.length, totalPages: 1, items }
}

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
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

describe('StockOverviewTab — maintenance', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shows 編輯/下市 for an active row and 編輯/重新上架 for a delisted row', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, listResponse([ACTIVE_ITEM, DELISTED_ITEM])))
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    const activeRow = screen.getByText('台積電').closest('tr')!
    expect(within(activeRow).getByRole('button', { name: '編輯' })).toBeInTheDocument()
    expect(within(activeRow).getByRole('button', { name: '下市' })).toBeInTheDocument()
    expect(within(activeRow).queryByRole('button', { name: '重新上架' })).not.toBeInTheDocument()

    const delistedRow = screen.getByText('測試下市股').closest('tr')!
    expect(within(delistedRow).getByRole('button', { name: '編輯' })).toBeInTheDocument()
    expect(within(delistedRow).getByRole('button', { name: '重新上架' })).toBeInTheDocument()
    expect(within(delistedRow).queryByRole('button', { name: '下市' })).not.toBeInTheDocument()
  })

  it('opens the create dialog with an editable code field and creates a stock', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return Promise.resolve(
          jsonResponse(201, {
            stockId: '6488',
            stockName: '環球晶',
            market: 'TSE',
            isActive: true,
            latestTradeDate: null,
            latestClose: null,
            previousClose: null,
            changeAmount: null,
            changePercent: null,
            latestVolume: null,
            firstTradeDate: null,
            tradingDayCount: 0,
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, listResponse([ACTIVE_ITEM])))
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '新增股票' }))
    const dialog = screen.getByRole('dialog')
    const codeInput = within(dialog).getByLabelText('股票代號') as HTMLInputElement
    expect(codeInput.tagName).toBe('INPUT')
    expect(codeInput.disabled).toBe(false)

    fireEvent.change(codeInput, { target: { value: '6488' } })
    fireEvent.change(within(dialog).getByLabelText('股票名稱'), { target: { value: '環球晶' } })
    fireEvent.click(within(dialog).getByRole('button', { name: '確定' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const postCall = fetchMock.mock.calls.find((c) => c[1]?.method === 'POST')!
    expect(postCall[0]).toBe('/api/stocks')
    expect(JSON.parse(postCall[1].body)).toEqual({ stockId: '6488', stockName: '環球晶', market: 'TSE' })
    await waitFor(() => expect(screen.getByText(/已新增/)).toBeInTheDocument())
  })

  it('blocks submit and shows a field error when the name is left blank', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, listResponse([ACTIVE_ITEM])))
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '新增股票' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('股票代號'), { target: { value: '6488' } })
    const callsBefore = fetchMock.mock.calls.length
    fireEvent.click(within(dialog).getByRole('button', { name: '確定' }))

    expect(await within(dialog).findByText('請輸入股票名稱')).toBeInTheDocument()
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
  })

  it('opens the edit dialog with a read-only, still-visible code field', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, listResponse([ACTIVE_ITEM])))
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    const row = screen.getByText('台積電').closest('tr')!
    fireEvent.click(within(row).getByRole('button', { name: '編輯' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('2330')).toBeInTheDocument()
    expect(within(dialog).queryByLabelText('股票代號')?.tagName).not.toBe('INPUT')
  })

  it('keeps the dialog open and flags the code field on STOCK_ALREADY_EXISTS', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return Promise.resolve(jsonResponse(409, { code: 'STOCK_ALREADY_EXISTS' }))
      }
      return Promise.resolve(jsonResponse(200, listResponse([ACTIVE_ITEM])))
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '新增股票' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('股票代號'), { target: { value: '2330' } })
    fireEvent.change(within(dialog).getByLabelText('股票名稱'), { target: { value: '台積電' } })
    fireEvent.click(within(dialog).getByRole('button', { name: '確定' }))

    expect(await within(dialog).findByText('此代號已存在')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('disables the submit button while a create request is in flight', async () => {
    let resolvePost: (v: unknown) => void = () => {}
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return new Promise((resolve) => {
          resolvePost = resolve
        })
      }
      return Promise.resolve(jsonResponse(200, listResponse([ACTIVE_ITEM])))
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '新增股票' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('股票代號'), { target: { value: '6488' } })
    fireEvent.change(within(dialog).getByLabelText('股票名稱'), { target: { value: '環球晶' } })
    const submitBtn = within(dialog).getByRole('button', { name: '確定' })
    fireEvent.click(submitBtn)

    await waitFor(() => expect(within(dialog).getByRole('button', { name: '送出中…' })).toBeDisabled())
    const postCallsDuring = fetchMock.mock.calls.filter((c) => c[1]?.method === 'POST').length
    expect(postCallsDuring).toBe(1)

    resolvePost(
      jsonResponse(201, {
        stockId: '6488',
        stockName: '環球晶',
        market: 'TSE',
        isActive: true,
        latestTradeDate: null,
        latestClose: null,
        previousClose: null,
        changeAmount: null,
        changePercent: null,
        latestVolume: null,
        firstTradeDate: null,
        tradingDayCount: 0,
      }),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('requires explicit confirmation to delist, with copy stating history is preserved', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === 'DELETE') {
        return Promise.resolve(jsonResponse(200, { stockId: '2330', stockName: '台積電', isActive: false }))
      }
      return Promise.resolve(jsonResponse(200, listResponse([ACTIVE_ITEM])))
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())

    const row = screen.getByText('台積電').closest('tr')!
    fireEvent.click(within(row).getByRole('button', { name: '下市' }))

    const confirm = screen.getByRole('alertdialog')
    expect(confirm.textContent).toContain('歷史行情會完整保留')
    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'DELETE')).toBe(false)

    fireEvent.click(within(confirm).getByRole('button', { name: '確定下市' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'DELETE')).toBe(true)
    await waitFor(() => expect(screen.getByText(/已下市/)).toBeInTheDocument())
  })

  it('reactivates a delisted stock immediately, without a confirmation dialog', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        return Promise.resolve(
          jsonResponse(200, { ...DELISTED_ITEM, isActive: true }),
        )
      }
      return Promise.resolve(jsonResponse(200, listResponse([DELISTED_ITEM])))
    })
    renderPage()
    await waitFor(() => expect(screen.getByText('測試下市股')).toBeInTheDocument())

    const row = screen.getByText('測試下市股').closest('tr')!
    fireEvent.click(within(row).getByRole('button', { name: '重新上架' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'PUT')).toBe(true))
    const putCall = fetchMock.mock.calls.find((c) => c[1]?.method === 'PUT')!
    expect(JSON.parse(putCall[1].body)).toEqual({
      stockName: '測試下市股',
      market: 'TSE',
      isActive: true,
    })
    await waitFor(() => expect(screen.getByText(/已.*重新上架/)).toBeInTheDocument())
  })
})
