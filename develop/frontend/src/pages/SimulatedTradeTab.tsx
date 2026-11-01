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

  // 目標結算日 — screen-local state only (not in the URL / localStorage). Empty = 現價模式.
  const [settleDateValue, setSettleDateValue] = useState('')
  const [settleError, setSettleError] = useState<string | null>(null)
  // The re-fetches after 加入/刪除/重試 run from closures captured earlier, so the latest
  // value also lives in a ref.
  const settleDateRef = useRef('')

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
  const runFetch = (settleDate: string = settleDateRef.current) => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setStatus('loading')
    return fetchSimulatedTrades(controller.signal, settleDate || undefined)
      .then((resp) => {
        if (!mountedRef.current) return
        setData(resp)
        setStatus('success')
        setLoadErrorMessage(null)
        setSettleError(null)
        if (!buyDateInitializedRef.current) {
          buyDateInitializedRef.current = true
          setBuyDateValue(resp.defaultBuyDate ?? '')
        }
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (!mountedRef.current) return
        if (err instanceof SimulatedTradeApiError && err.code === 'INVALID_SETTLE_DATE') {
          // Keep the last good table; only the date input shows the message.
          setStatus('success')
          setSettleError('目標結算日不能晚於今日')
          return
        }
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

  const changeSettleDate = (next: string) => {
    settleDateRef.current = next
    setSettleDateValue(next)
    void runFetch(next)
  }

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
  const settleMode = data?.settleDate != null && settleDateValue !== ''
  const noSettlementCount = settleMode ? items.filter((it) => it.settlement == null).length : 0
  const totalReturn = settleMode ? (data?.settledTotalReturnPercent ?? null) : (data?.totalReturnPercent ?? null)
  const noHoldings = data ? totalReturn == null : true
  const totalCostShown = settleMode ? (data?.settledTotalCost ?? 0) : (data?.totalCost ?? 0)
  const totalProfitShown = settleMode ? (data?.settledTotalProfit ?? 0) : (data?.totalUnrealizedProfit ?? 0)
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
          以所選日期的收盤價買進 1 張
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
              <div className="sim-summary-item sim-settle-item">
                <label className="sim-summary-label" htmlFor="sim-settle-date">
                  目標結算日
                </label>
                <input
                  id="sim-settle-date"
                  type="date"
                  className="sim-add-input sim-date-input"
                  value={settleDateValue}
                  max={data.asOfDate}
                  onChange={(e) => changeSettleDate(e.target.value)}
                />
                <button
                  type="button"
                  className="sl-btn"
                  disabled={settleDateValue === ''}
                  onClick={() => changeSettleDate('')}
                >
                  清除
                </button>
              </div>
              <div className="sim-summary-item">
                <span className="sim-summary-label">總成本</span>
                <span className="sim-summary-value">{noHoldings ? '—' : formatMoney(totalCostShown)}</span>
              </div>
              <div className="sim-summary-item">
                <span className="sim-summary-label">{settleMode ? '總結算損益' : '總未實現損益'}</span>
                <span
                  className={`sim-summary-value${noHoldings ? ' sl-muted' : ` ${gainClass(totalProfitShown)}`}`}
                >
                  {noHoldings ? '—' : formatSignedMoney(totalProfitShown)}
                </span>
              </div>
              <div className="sim-summary-item">
                <span className="sim-summary-label">總報酬率</span>
                <span
                  className={`sim-summary-value${noHoldings ? ' sl-muted' : ` ${gainClass(totalReturn as number)}`}`}
                >
                  {noHoldings ? '—' : formatSignedPercent(totalReturn as number)}
                </span>
              </div>
            </div>
            {settleError ? <div className="sim-add-error">{settleError}</div> : null}
            <div className="sim-fee-note">
              {settleMode
                ? `結算損益已扣手續費 ${data.feeRatePercent}%（買賣各一次）與證交稅 ${data.taxRatePercent}%，每筆以買進日至 ${data.settleDate} 之間的最高價賣出`
                : `未實現損益已扣手續費 ${data.feeRatePercent}%（買賣各一次）與證交稅 ${data.taxRatePercent}%，賣出成本以現價估算`}
            </div>
            {noSettlementCount > 0 ? (
              <div className="sim-fee-note">另 {noSettlementCount} 筆買進日晚於目標結算日，未計入</div>
            ) : null}
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
                    <th>{settleMode ? '賣出日' : '現價日'}</th>
                    <th className="sl-r">{settleMode ? '賣出價' : '現價'}</th>
                    <th className="sl-r">成本</th>
                    <th className="sl-r">{settleMode ? '結算損益' : '未實現損益'}</th>
                    <th className="sl-r">報酬率</th>
                    <th className="sl-r">刪除</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item: SimulatedTradeItem) => {
                    const st = item.settlement
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
                        {settleMode ? (
                          st ? (
                            <>
                              <td>{st.sellDate}</td>
                              <td className="sl-r">{formatPrice(st.sellPrice)}</td>
                              <td className="sl-r">{formatMoney(item.cost)}</td>
                              <td className={`sl-r ${gainClass(st.profit)}`}>{formatSignedMoney(st.profit)}</td>
                              <td className={`sl-r ${gainClass(st.returnPercent)}`}>
                                {formatSignedPercent(st.returnPercent)}
                              </td>
                            </>
                          ) : (
                            <>
                              <td className="sl-muted">—</td>
                              <td className="sl-r sl-muted">—</td>
                              <td className="sl-r">{formatMoney(item.cost)}</td>
                              <td className="sl-r sl-muted">—</td>
                              <td className="sl-r sl-muted">—</td>
                            </>
                          )
                        ) : (
                          <>
                            <td className={currentDateStale ? 'sim-stale-date' : undefined}>{item.currentDate}</td>
                            <td className="sl-r">{formatPrice(item.currentPrice)}</td>
                            <td className="sl-r">{formatMoney(item.cost)}</td>
                            <td className={`sl-r ${gainClass(item.unrealizedProfit)}`}>
                              {formatSignedMoney(item.unrealizedProfit)}
                            </td>
                            <td className={`sl-r ${gainClass(item.returnPercent)}`}>
                              {formatSignedPercent(item.returnPercent)}
                            </td>
                          </>
                        )}
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
