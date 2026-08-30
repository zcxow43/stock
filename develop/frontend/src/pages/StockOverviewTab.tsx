import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  ApiError,
  PROGRAMMER_ERROR_CODES,
  deactivateStock,
  fetchStocks,
  updateStock,
  type MarketFilter,
  type SortField,
  type SortOrder,
  type StockDetail,
  type StockListItem,
  type StockListParams,
  type StockListResponse,
} from '../api/stocks'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import ConfirmDialog from '../components/ConfirmDialog'
import StockFormDialog from '../components/StockFormDialog'
import './StockListPage.css'

const DEFAULT_QUERY: StockListParams = {
  keyword: '',
  market: 'ALL',
  includeInactive: false,
  page: 1,
  size: 50,
  sort: 'stockId',
  order: 'asc',
}

const PAGE_SIZE_OPTIONS = [20, 50, 100]

const SORTABLE_COLUMNS: { field: SortField; label: string }[] = [
  { field: 'stockId', label: '代號' },
  { field: 'stockName', label: '名稱' },
  { field: 'market', label: '市場' },
]

const SUCCESS_MESSAGE_MS = 4000

function formatPrice(value: number | null): string {
  return value == null ? '—' : value.toFixed(2)
}

function formatSigned(value: number | null, suffix = ''): string {
  if (value == null) return '—'
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}${suffix}`
}

function formatVolume(value: number | null): string {
  return value == null ? '—' : value.toLocaleString('en-US')
}

function changeClass(value: number | null): string {
  if (value == null) return 'sl-muted'
  if (value > 0) return 'sl-up'
  if (value < 0) return 'sl-down'
  return 'sl-flat'
}

function marketLabel(market: string): string {
  return market === 'TSE' ? '上市' : '上櫃'
}

function marketTagClass(market: string): string {
  return market === 'TSE' ? 'sl-tse' : 'sl-otc'
}

type DialogState = { mode: 'create' } | { mode: 'edit'; stock: StockListItem } | null

export interface StockOverviewTabProps {
  /** Lifted so the shared page header ("共 N 檔") can render outside the tab content. */
  onTotalChange: (total: number) => void
}

export default function StockOverviewTab({ onTotalChange }: StockOverviewTabProps) {
  const navigate = useNavigate()

  const [rawSearch, setRawSearch] = useState('')
  const debouncedSearch = useDebouncedValue(rawSearch, 300)

  const [query, setQuery] = useState<StockListParams>(DEFAULT_QUERY)
  const [data, setData] = useState<StockListResponse | null>(null)
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [retryToken, setRetryToken] = useState(0)
  const hasLoadedOnce = useRef(false)

  const [dialogState, setDialogState] = useState<DialogState>(null)
  const [delistTarget, setDelistTarget] = useState<StockListItem | null>(null)
  const [delistSubmitting, setDelistSubmitting] = useState(false)
  const [reactivatingId, setReactivatingId] = useState<string | null>(null)
  const [notFoundMessage, setNotFoundMessage] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  // Search box: debounce 300ms, then fold into the query (and reset to page 1) atomically
  // so the fetch effect below only fires once per real query change.
  useEffect(() => {
    setQuery((q) => (q.keyword === debouncedSearch ? q : { ...q, keyword: debouncedSearch, page: 1 }))
  }, [debouncedSearch])

  useEffect(() => {
    const controller = new AbortController()
    setStatus('loading')
    fetchStocks(query, controller.signal)
      .then((resp) => {
        setData(resp)
        setStatus('success')
        setErrorMessage(null)
        hasLoadedOnce.current = true
        onTotalChange(resp.total)
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (err instanceof ApiError && err.code && PROGRAMMER_ERROR_CODES.has(err.code)) {
          setStatus('error')
          setErrorMessage('發生錯誤，已重設篩選條件')
          setRawSearch('')
          setQuery(DEFAULT_QUERY)
          return
        }
        setStatus('error')
        setErrorMessage('載入失敗，請稍後再試')
      })
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, retryToken])

  // Success/not-found banners auto-dismiss so they don't linger over the table forever.
  useEffect(() => {
    if (!successMessage) return
    const timer = setTimeout(() => setSuccessMessage(null), SUCCESS_MESSAGE_MS)
    return () => clearTimeout(timer)
  }, [successMessage])

  useEffect(() => {
    if (!notFoundMessage) return
    const timer = setTimeout(() => setNotFoundMessage(null), SUCCESS_MESSAGE_MS)
    return () => clearTimeout(timer)
  }, [notFoundMessage])

  const setMarket = (market: MarketFilter) => setQuery((q) => ({ ...q, market, page: 1 }))
  const setIncludeInactive = (includeInactive: boolean) =>
    setQuery((q) => ({ ...q, includeInactive, page: 1 }))
  const setSize = (size: number) => setQuery((q) => ({ ...q, size, page: 1 }))
  const setPage = (page: number) => setQuery((q) => ({ ...q, page }))
  const toggleSort = (field: SortField) =>
    setQuery((q) =>
      q.sort === field
        ? { ...q, order: q.order === 'asc' ? 'desc' : ('asc' as SortOrder) }
        : { ...q, sort: field, order: 'asc' },
    )

  const clearFilters = () => {
    setRawSearch('')
    setQuery(DEFAULT_QUERY)
  }

  const reload = () => setRetryToken((t) => t + 1)

  const handleDialogSuccess = (stock: StockDetail, mode: 'create' | 'edit') => {
    setDialogState(null)
    setSuccessMessage(mode === 'create' ? `已新增 ${stock.stockId} ${stock.stockName}` : `已更新 ${stock.stockId} ${stock.stockName}`)
    reload()
  }

  const handleDialogNotFound = () => {
    setDialogState(null)
    setNotFoundMessage('此股票已不存在')
    reload()
  }

  const handleDelistConfirm = async () => {
    if (!delistTarget) return
    setDelistSubmitting(true)
    try {
      await deactivateStock(delistTarget.stockId)
      setSuccessMessage(`已將 ${delistTarget.stockId} ${delistTarget.stockName} 下市，歷史行情已保留`)
      setDelistTarget(null)
      reload()
    } catch (err) {
      if (err instanceof ApiError && err.code === 'STOCK_NOT_FOUND') {
        setDelistTarget(null)
        setNotFoundMessage('此股票已不存在')
        reload()
        return
      }
      setNotFoundMessage('下市失敗，請稍後再試')
    } finally {
      setDelistSubmitting(false)
    }
  }

  const handleReactivate = async (item: StockListItem) => {
    if (reactivatingId) return
    setReactivatingId(item.stockId)
    try {
      await updateStock(item.stockId, { stockName: item.stockName, market: item.market, isActive: true })
      setSuccessMessage(`已將 ${item.stockId} ${item.stockName} 重新上架`)
      reload()
    } catch (err) {
      if (err instanceof ApiError && err.code === 'STOCK_NOT_FOUND') {
        setNotFoundMessage('此股票已不存在')
        reload()
        return
      }
      setNotFoundMessage('重新上架失敗，請稍後再試')
    } finally {
      setReactivatingId(null)
    }
  }

  const isFirstLoading = status === 'loading' && !hasLoadedOnce.current
  const isRefetching = status === 'loading' && hasLoadedOnce.current
  const total = data?.total ?? 0
  const totalPages = data?.totalPages ?? 0
  const isEmpty = status === 'success' && total === 0

  return (
    <div className="stock-overview-tab">
      {successMessage ? <div className="sl-toast sl-toast-success">{successMessage}</div> : null}
      {notFoundMessage ? <div className="sl-toast sl-toast-error">{notFoundMessage}</div> : null}

      <div className="sl-filters">
        <label className="sl-search">
          <span className="sl-ico">⌕</span>
          <input
            type="text"
            placeholder="輸入股票代號或名稱"
            value={rawSearch}
            onChange={(e) => setRawSearch(e.target.value)}
          />
        </label>
        <select
          className="sl-select"
          value={query.market}
          onChange={(e) => setMarket(e.target.value as MarketFilter)}
        >
          <option value="ALL">全部</option>
          <option value="TSE">上市</option>
          <option value="OTC">上櫃</option>
        </select>
        <label className="sl-checkbox">
          <input
            type="checkbox"
            checked={query.includeInactive}
            onChange={(e) => setIncludeInactive(e.target.checked)}
          />
          顯示已下市
        </label>
        <div className="sl-filters-spacer" />
        <button type="button" className="sl-btn sl-btn-primary" onClick={() => setDialogState({ mode: 'create' })}>
          新增股票
        </button>
      </div>

      {status === 'error' ? (
        <div className="sl-error">
          <p>{errorMessage}</p>
          <button type="button" className="sl-btn sl-btn-primary" onClick={reload}>
            重新載入
          </button>
        </div>
      ) : isEmpty ? (
        <div className="sl-table-wrap">
          <div className="sl-empty">
            <p>查無符合條件的股票</p>
            <button type="button" className="sl-btn" onClick={clearFilters}>
              清除篩選條件
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className={`sl-table-wrap${isRefetching ? ' sl-dimmed' : ''}`}>
            <table className="sl-table">
              <thead>
                <tr>
                  {SORTABLE_COLUMNS.map(({ field, label }) => (
                    <th
                      key={field}
                      className="sl-sortable"
                      onClick={() => toggleSort(field)}
                      aria-sort={
                        query.sort === field ? (query.order === 'asc' ? 'ascending' : 'descending') : 'none'
                      }
                    >
                      {label}
                      {query.sort === field ? (
                        <span className="sl-sort-indicator sl-active">
                          {query.order === 'asc' ? '↑' : '↓'}
                        </span>
                      ) : (
                        <span className="sl-sort-indicator sl-inactive">↕</span>
                      )}
                    </th>
                  ))}
                  <th>最新交易日</th>
                  <th className="sl-r">收盤價</th>
                  <th className="sl-r">漲跌</th>
                  <th className="sl-r">漲跌幅</th>
                  <th className="sl-r">成交量</th>
                  <th>狀態</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {isFirstLoading
                  ? Array.from({ length: 8 }).map((_, i) => (
                      <tr key={i}>
                        {Array.from({ length: 10 }).map((__, j) => (
                          <td key={j}>
                            <div className="sl-skeleton-bar" />
                          </td>
                        ))}
                      </tr>
                    ))
                  : (data?.items ?? []).map((item) => (
                      <tr
                        key={item.stockId}
                        className="sl-row"
                        onClick={() => navigate(`/stocks/${item.stockId}/daily`)}
                      >
                        <td className="sl-code">{item.stockId}</td>
                        <td>{item.stockName}</td>
                        <td>
                          <span className={`sl-tag ${marketTagClass(item.market)}`}>
                            {marketLabel(item.market)}
                          </span>
                        </td>
                        <td className={item.latestTradeDate == null ? 'sl-muted' : undefined}>
                          {item.latestTradeDate ?? '—'}
                        </td>
                        <td className={`sl-r ${item.latestClose == null ? 'sl-muted' : ''}`}>
                          {formatPrice(item.latestClose)}
                        </td>
                        <td className={`sl-r ${changeClass(item.changeAmount)}`}>
                          {formatSigned(item.changeAmount)}
                        </td>
                        <td className={`sl-r ${changeClass(item.changePercent)}`}>
                          {formatSigned(item.changePercent, '%')}
                        </td>
                        <td className={`sl-r ${item.latestVolume == null ? 'sl-muted' : ''}`}>
                          {formatVolume(item.latestVolume)}
                        </td>
                        <td>
                          {item.isActive === false ? (
                            <span className="sl-tag sl-inactive">已下市</span>
                          ) : null}
                        </td>
                        <td className="sl-actions" onClick={(e) => e.stopPropagation()}>
                          <button
                            type="button"
                            className="sl-btn sl-btn-sm"
                            onClick={() => setDialogState({ mode: 'edit', stock: item })}
                          >
                            編輯
                          </button>
                          {item.isActive === false ? (
                            <button
                              type="button"
                              className="sl-btn sl-btn-sm"
                              disabled={reactivatingId === item.stockId}
                              onClick={() => handleReactivate(item)}
                            >
                              {reactivatingId === item.stockId ? '處理中…' : '重新上架'}
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="sl-btn sl-btn-sm sl-btn-danger"
                              onClick={() => setDelistTarget(item)}
                            >
                              下市
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
              </tbody>
            </table>
          </div>

          <div className="sl-pager">
            <div className="sl-psize">
              每頁
              <select
                className="sl-select"
                value={query.size}
                onChange={(e) => setSize(Number(e.target.value))}
              >
                {PAGE_SIZE_OPTIONS.map((n) => (
                  <option key={n} value={n}>
                    {n} 筆
                  </option>
                ))}
              </select>
            </div>
            <div className="sl-pbtns">
              <span>
                第 {query.page} / {Math.max(totalPages, 1)} 頁
              </span>
              <button
                type="button"
                className="sl-btn"
                disabled={query.page <= 1}
                onClick={() => setPage(query.page - 1)}
              >
                上一頁
              </button>
              <button
                type="button"
                className="sl-btn"
                disabled={query.page >= totalPages}
                onClick={() => setPage(query.page + 1)}
              >
                下一頁
              </button>
            </div>
          </div>
        </>
      )}

      {dialogState?.mode === 'create' ? (
        <StockFormDialog
          mode="create"
          onClose={() => setDialogState(null)}
          onSuccess={handleDialogSuccess}
          onNotFound={handleDialogNotFound}
        />
      ) : null}
      {dialogState?.mode === 'edit' ? (
        <StockFormDialog
          mode="edit"
          stock={dialogState.stock}
          onClose={() => setDialogState(null)}
          onSuccess={handleDialogSuccess}
          onNotFound={handleDialogNotFound}
        />
      ) : null}

      {delistTarget ? (
        <ConfirmDialog
          title="確認下市"
          message={`確定將 ${delistTarget.stockId} ${delistTarget.stockName} 下市？歷史行情會完整保留，之後可重新上架。`}
          confirmLabel="確定下市"
          destructive
          submitting={delistSubmitting}
          onConfirm={handleDelistConfirm}
          onCancel={() => setDelistTarget(null)}
        />
      ) : null}
    </div>
  )
}
