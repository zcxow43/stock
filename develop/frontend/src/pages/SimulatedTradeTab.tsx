import { useEffect, useRef, useState } from 'react'
import {
  SimulatedTradeApiError,
  createSimulatedTrade,
  deleteSimulatedTrade,
  fetchSimulatedTrades,
  type SimulatedTradeItem,
  type SimulatedTradeListResponse,
} from '../api/simulatedTrades'
import './SimulatedTradeTab.css'

type Status = 'loading' | 'success' | 'error'

function formatMoney(value: number): string {
  return value.toLocaleString('en-US')
}

function formatPrice(value: number): string {
  return value.toFixed(2)
}

function formatSignedMoney(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toLocaleString('en-US')}`
}

function formatSignedPercent(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

/** Positive→上漲紅, negative→下跌綠, zero→平盤灰 — same 3-way rule as every other table on
 * this site (specs/frontend/simulated-trade.md「漲跌色沿用全站規則」). */
function gainClass(value: number): string {
  if (value === 0) return 'sl-flat'
  return value > 0 ? 'sl-up' : 'sl-down'
}

/** Maps a failed 加入 to the exact copy specs/frontend/simulated-trade.md「錯誤處理」requires,
 * falling back to the request's own `stockId`/`buyDate` when the error body doesn't echo one
 * back. */
function addErrorMessage(err: unknown, attemptedStockId: string, attemptedBuyDate: string): string {
  if (err instanceof SimulatedTradeApiError) {
    switch (err.code) {
      case 'INVALID_STOCK_ID':
        return '請輸入股票代號'
      case 'UNKNOWN_STOCK_ID':
        return `找不到代號 ${err.unknownIds?.[0] ?? attemptedStockId}`
      case 'NO_PRICE_BEFORE_TODAY':
        return `${err.stockId ?? attemptedStockId} 在今日以前沒有可用的收盤價，無法加入`
      case 'INVALID_BUY_DATE':
        return '買進日不能晚於今日'
      case 'NO_PRICE_ON_BUY_DATE':
        return `${err.stockId ?? attemptedStockId} 在 ${err.buyDate ?? attemptedBuyDate} 沒有收盤價（可能是假日或停牌），請換一天`
      case 'DUPLICATE_SIMULATED_TRADE':
        return `${err.stockId ?? attemptedStockId} 在 ${err.buyDate ?? attemptedBuyDate} 已經加過了`
      default:
        return '加入失敗，請稍後再試'
    }
  }
  return '加入失敗，請稍後再試'
}

/** 模擬交易分頁 — specs/frontend/simulated-trade.md, the 4th tab in `/stocks`. Mounted only
 * while `tab === 'simulated'` (see StockListPage.tsx) rather than kept permanently mounted
 * like the other three tabs — this tab's own spec explicitly requires a fresh `GET` every
 * time the user returns to it, which a plain mount effect gives us for free. It takes no
 * `commonStocksOnly` prop and never reads the page-level setting: this page has no
 * population filter, so toggling that checkbox elsewhere can't affect it. */
export default function SimulatedTradeTab() {
  const [data, setData] = useState<SimulatedTradeListResponse | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [loadErrorMessage, setLoadErrorMessage] = useState<string | null>(null)

  const [inputValue, setInputValue] = useState('')
  const [buyDateValue, setBuyDateValue] = useState('')
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)

  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [tableMessage, setTableMessage] = useState<string | null>(null)

  const [flashId, setFlashId] = useState<number | null>(null)

  const inputRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  // Guards setState calls that resolve after unmount (e.g. the user switches tabs mid
  // 加入/刪除) — set back to `true` inside the effect body, not just at declaration, since
  // React 19 StrictMode's dev-only mount→unmount→remount double-invoke otherwise leaves a
  // stale `false` on the real mount (see specs/frontend/stock-list.md Increment 2's note).
  const mountedRef = useRef(true)
  // Set once, on the first successful GET — the 買進日 input then defaults to
  // `defaultBuyDate` but is never overwritten by a later refetch (after 加入/刪除/重試),
  // since 「加入成功後買進日維持不變」 and the user may already have changed it by hand.
  const buyDateInitializedRef = useRef(false)

  /** Fetches the list and replaces `data`/`status` with the result. Used for the initial
   * load, 重試, and the required re-fetch after every successful 加入/刪除. */
  const runFetch = () => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setStatus('loading')
    return fetchSimulatedTrades(controller.signal)
      .then((resp) => {
        if (!mountedRef.current) return
        setData(resp)
        setStatus('success')
        setLoadErrorMessage(null)
        if (!buyDateInitializedRef.current) {
          buyDateInitializedRef.current = true
          setBuyDateValue(resp.defaultBuyDate ?? '')
        }
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (!mountedRef.current) return
        // `data` is deliberately left untouched here — see 畫面狀態's「載入失敗…保留上一次
        // 成功的資料不清空」. Clearing it would also make `items` fall back to `[]`, which
        // would misrender as the empty state instead of the error state.
        setStatus('error')
        setLoadErrorMessage('載入失敗，請稍後再試')
      })
  }

  useEffect(() => {
    mountedRef.current = true
    void runFetch()
    return () => {
      mountedRef.current = false
      abortRef.current?.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // One-shot row-flash on the just-added row; clears itself so a later re-fetch (e.g. from
  // deleting a different row) doesn't replay the animation on this row again.
  useEffect(() => {
    if (flashId == null) return
    const timer = setTimeout(() => setFlashId(null), 1300)
    return () => clearTimeout(timer)
  }, [flashId])

  const handleAdd = async () => {
    const stockId = inputValue.trim()
    const buyDate = buyDateValue
    if (!stockId || !buyDate || adding) return
    setAdding(true)
    try {
      const created = await createSimulatedTrade(stockId, buyDate)
      if (!mountedRef.current) return
      // 買進日 deliberately left as-is — only the code input is cleared (specs/frontend
      // /simulated-trade.md「加入成功後買進日維持不變」: adding several stocks for the
      // same day is the common case).
      setInputValue('')
      setAddError(null)
      setTableMessage(null)
      setFlashId(created.id)
      await runFetch()
    } catch (err) {
      if (!mountedRef.current) return
      setAddError(addErrorMessage(err, stockId, buyDate))
    } finally {
      if (mountedRef.current) {
        setAdding(false)
        inputRef.current?.focus()
      }
    }
  }

  const handleInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      void handleAdd()
    }
  }

  const handleDelete = async (id: number) => {
    if (deletingId != null) return
    setDeletingId(id)
    try {
      await deleteSimulatedTrade(id)
      if (!mountedRef.current) return
      setTableMessage(null)
      await runFetch()
    } catch (err) {
      if (!mountedRef.current) return
      if (err instanceof SimulatedTradeApiError && err.code === 'SIMULATED_TRADE_NOT_FOUND') {
        setTableMessage('這筆持股已經不存在')
        await runFetch()
      } else {
        setTableMessage('刪除失敗，請稍後再試')
      }
    } finally {
      if (mountedRef.current) setDeletingId(null)
    }
  }

  const canAdd = inputValue.trim() !== '' && buyDateValue !== '' && !adding
  const items = data?.items ?? []
  const noHoldings = data ? data.totalReturnPercent == null : true
  const isEmpty = status === 'success' && data != null && items.length === 0

  return (
    <div className="simulated-trade-tab">
      <div className="sim-add-bar">
        <div className="sim-add-row">
          <input
            ref={inputRef}
            type="text"
            className="sim-add-input"
            placeholder="輸入股票代號，例如 2330"
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            onKeyDown={handleInputKeyDown}
          />
          <input
            type="date"
            className="sim-add-input sim-date-input"
            aria-label="買進日"
            value={buyDateValue}
            max={data?.asOfDate}
            onChange={(e) => setBuyDateValue(e.target.value)}
            onKeyDown={handleInputKeyDown}
          />
          <button
            type="button"
            className="sl-btn sl-btn-primary"
            disabled={!canAdd}
            onClick={() => void handleAdd()}
          >
            {adding ? '加入中…' : '加入'}
          </button>
        </div>
        {addError ? <div className="sim-add-error">{addError}</div> : null}
        <div className="sim-add-hint">
          以今日以前最後一個交易日的收盤價買進 1 張
          {data ? `（每筆 ${data.lotSize.toLocaleString('en-US')} 股）` : null}
        </div>
      </div>

      {tableMessage ? <div className="sl-toast sl-toast-error">{tableMessage}</div> : null}

      {status === 'error' ? (
        <div className="sl-error">
          <p>{loadErrorMessage}</p>
          <button type="button" className="sl-btn sl-btn-primary" onClick={() => void runFetch()}>
            重試
          </button>
        </div>
      ) : status === 'loading' ? (
        <div className="sim-loading">載入中…</div>
      ) : data ? (
        <>
          <div className="sim-summary">
            <div className="sim-summary-row">
              <div className="sim-summary-count">
                共 <b>{items.length}</b> 筆
              </div>
              <div className="sim-summary-item">
                <span className="sim-summary-label">總成本</span>
                <span className="sim-summary-value">{noHoldings ? '—' : formatMoney(data.totalCost)}</span>
              </div>
              <div className="sim-summary-item">
                <span className="sim-summary-label">總未實現損益</span>
                <span
                  className={`sim-summary-value${noHoldings ? ' sl-muted' : ` ${gainClass(data.totalUnrealizedProfit)}`}`}
                >
                  {noHoldings ? '—' : formatSignedMoney(data.totalUnrealizedProfit)}
                </span>
              </div>
              <div className="sim-summary-item">
                <span className="sim-summary-label">總報酬率</span>
                <span
                  className={`sim-summary-value${noHoldings ? ' sl-muted' : ` ${gainClass(data.totalReturnPercent as number)}`}`}
                >
                  {noHoldings ? '—' : formatSignedPercent(data.totalReturnPercent as number)}
                </span>
              </div>
            </div>
            <div className="sim-fee-note">
              未實現損益已扣手續費 {data.feeRatePercent}%（買賣各一次）與證交稅 {data.taxRatePercent}%，賣出成本以現價估算
            </div>
          </div>

          {isEmpty ? (
            <div className="sl-table-wrap">
              <div className="sl-empty">
                <p>尚無模擬持股，輸入股票代號加入第一筆</p>
              </div>
            </div>
          ) : (
            <div className="sl-table-wrap">
              <table className="sl-table sim-holdings-table">
                <thead>
                  <tr>
                    <th>代號 / 名稱</th>
                    <th>買進日</th>
                    <th className="sl-r">買進價</th>
                    <th>現價日</th>
                    <th className="sl-r">現價</th>
                    <th className="sl-r">成本</th>
                    <th className="sl-r">未實現損益</th>
                    <th className="sl-r">報酬率</th>
                    <th className="sl-r">刪除</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item: SimulatedTradeItem) => {
                    const currentDateStale = item.currentDate !== data.asOfDate
                    return (
                      <tr
                        key={item.id}
                        className={flashId === item.id ? 'sim-row-flash' : undefined}
                        onAnimationEnd={() => {
                          if (flashId === item.id) setFlashId(null)
                        }}
                      >
                        <td>
                          <div className="sim-stock-cell">
                            <span className="sl-code">{item.stockId}</span>
                            <span className="sim-stock-name">{item.stockName}</span>
                          </div>
                        </td>
                        <td>{item.buyDate}</td>
                        <td className="sl-r">{formatPrice(item.buyPrice)}</td>
                        <td className={currentDateStale ? 'sim-stale-date' : undefined}>{item.currentDate}</td>
                        <td className="sl-r">{formatPrice(item.currentPrice)}</td>
                        <td className="sl-r">{formatMoney(item.cost)}</td>
                        <td className={`sl-r ${gainClass(item.unrealizedProfit)}`}>
                          {formatSignedMoney(item.unrealizedProfit)}
                        </td>
                        <td className={`sl-r ${gainClass(item.returnPercent)}`}>
                          {formatSignedPercent(item.returnPercent)}
                        </td>
                        <td className="sl-r">
                          <button
                            type="button"
                            className="sim-del-btn"
                            disabled={deletingId === item.id}
                            onClick={() => void handleDelete(item.id)}
                          >
                            刪除
                          </button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : null}
    </div>
  )
}
