import { Fragment, useEffect, useRef, useState } from 'react'
import {
  RealTradeApiError,
  createRealTrade,
  deleteRealTrade,
  fetchRealTrades,
  updateRealTrade,
  type RealTradeItem,
  type RealTradeListResponse,
  type RealTradePatch,
} from '../api/realTrades'
import './RealTradeTab.css'

type Status = 'loading' | 'success' | 'error' | 'malformed'

const MALFORMED_LOAD_MESSAGE = '交易紀錄檔的欄位名列不正確，請修好 data/real-trades.csv 的第一行'
const MALFORMED_ADD_MESSAGE = '交易紀錄檔的欄位名列不正確，這一筆沒有寫入'
const INVALID_TARGET_MESSAGE = '目標賣價必須大於 0，最多兩位小數'
const NOT_FOUND_MESSAGE = '這一筆已經不存在了'
/** 不統計 + 代號/名稱 + 買進日 + 買進價 + 股數 + 現價日 + 現價 + 目標賣價 + 成本 + 未實現損益 + 報酬率 + 刪除 */
const COLUMN_COUNT = 12

function formatMoney(value: number): string {
  return value.toLocaleString('en-US')
}

function formatPrice(value: number): string {
  return value.toFixed(2)
}

function formatSignedMoney(value: number): string {
  return `${value > 0 ? '+' : ''}${value.toLocaleString('en-US')}`
}

function formatSignedPercent(value: number): string {
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
}

/** Positive→上漲紅, negative→下跌綠, zero→平盤灰 — the site-wide 3-way rule. */
function gainClass(value: number): string {
  if (value === 0) return 'sl-flat'
  return value > 0 ? 'sl-up' : 'sl-down'
}

/** Local calendar date as YYYY-MM-DD; only a fallback bound for the 買進日 check before the
 * first successful GET has supplied `asOfDate`. */
function localToday(): string {
  const now = new Date()
  const mm = String(now.getMonth() + 1).padStart(2, '0')
  const dd = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${mm}-${dd}`
}

const PRICE_PATTERN = /^\d+(\.\d{1,2})?$/
const SHARES_PATTERN = /^\d+$/

/** The same basic checks the backend makes, so a typo is caught without a round trip
 * (specs/frontend/real-trade.md「前端對四格做與後端相同的基本檢查並先擋下」). Returns the
 * message to show, or `null` when the four inputs are acceptable. */
function validateInputs(buyDate: string, price: string, shares: string, asOfDate: string): string | null {
  if (buyDate > asOfDate) return '買進日不能晚於今日'
  if (!PRICE_PATTERN.test(price) || Number(price) <= 0) return '買進價必須大於 0，最多兩位小數'
  if (!SHARES_PATTERN.test(shares) || Number(shares) <= 0) return '股數必須是大於 0 的整數'
  return null
}

function addErrorMessage(err: unknown): string {
  if (err instanceof RealTradeApiError) {
    switch (err.code) {
      case 'INVALID_STOCK_ID':
        return '請輸入股票代號'
      case 'UNKNOWN_STOCK_ID':
        return `找不到股票代號 ${err.unknownIds?.[0] ?? ''}`.trim()
      case 'INVALID_BUY_DATE':
        return '買進日不能晚於今日'
      case 'INVALID_BUY_PRICE':
        return '買進價必須大於 0，最多兩位小數'
      case 'INVALID_SHARES':
        return '股數必須是大於 0 的整數'
      case 'MALFORMED_TRADE_FILE':
        return MALFORMED_ADD_MESSAGE
      default:
        return '加入失敗，請稍後再試'
    }
  }
  return '加入失敗，請稍後再試'
}

function patchErrorMessage(err: unknown): string {
  if (err instanceof RealTradeApiError && err.code === 'INVALID_TARGET_SELL_PRICE') return INVALID_TARGET_MESSAGE
  return '更新失敗，請稍後再試'
}

function targetText(value: number | null): string {
  return value == null ? '' : formatPrice(value)
}

/** What a commit did: `'revert'` means nothing was (successfully) changed, so the field goes
 * back to the stored value; `'ok'` means the new value is on its way back via the re-fetch. */
type CommitResult = 'ok' | 'revert'

interface TargetSellPriceInputProps {
  value: number | null
  disabled: boolean
  onCommit: (raw: string, badInput: boolean) => Promise<CommitResult>
}

/** 目標賣價 cell — commits on Enter or blur, abandons on Esc. Holds its own draft text so a
 * half-typed value never reaches the backend. */
function TargetSellPriceInput({ value, disabled, onCommit }: TargetSellPriceInputProps) {
  const [draft, setDraft] = useState(targetText(value))
  // A new stored value (after the re-fetch) replaces whatever is in the field.
  const [seenValue, setSeenValue] = useState(value)
  if (value !== seenValue) {
    setSeenValue(value)
    setDraft(targetText(value))
  }

  const commit = async (input: HTMLInputElement) => {
    const result = await onCommit(input.value, input.validity.badInput)
    if (result === 'revert') setDraft(targetText(value))
  }

  return (
    <input
      type="number"
      inputMode="decimal"
      step="0.01"
      className="rt-target-input"
      placeholder="—"
      aria-label="目標賣價"
      value={draft}
      disabled={disabled}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={(e) => void commit(e.currentTarget)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          void commit(e.currentTarget)
        } else if (e.key === 'Escape') {
          // Reset the draft first: the blur that follows then finds draft === stored value and
          // sends nothing.
          e.preventDefault()
          e.currentTarget.value = targetText(value)
          setDraft(targetText(value))
          e.currentTarget.blur()
        }
      }}
    />
  )
}

/** 真實交易分頁 — specs/frontend/real-trade.md, the 5th tab in `/stocks`. Mirrors
 * SimulatedTradeTab, but the data lives in a CSV the backend owns, so this component only
 * ever talks to /api/real-trades. Mounted only while the tab is active (see StockListPage.tsx)
 * so every entry re-fetches; takes no `commonStocksOnly` prop. */
export default function RealTradeTab() {
  const [data, setData] = useState<RealTradeListResponse | null>(null)
  const [status, setStatus] = useState<Status>('loading')

  const [stockIdValue, setStockIdValue] = useState('')
  const [buyDateValue, setBuyDateValue] = useState('')
  const [buyPriceValue, setBuyPriceValue] = useState('')
  const [sharesValue, setSharesValue] = useState('')
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)

  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [tableMessage, setTableMessage] = useState<string | null>(null)
  const [flashId, setFlashId] = useState<number | null>(null)
  // Per-row PATCH state: which control is in flight ("<id>:excluded" / "<id>:target") and the
  // error to show under that row. Only the changed control is disabled.
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set())
  const [rowErrors, setRowErrors] = useState<Record<number, string>>({})

  const stockIdRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  // Re-set to `true` inside the effect (not just at declaration): StrictMode's dev-only
  // mount→unmount→remount would otherwise leave a stale `false` on the real mount.
  const mountedRef = useRef(true)
  // 買進日 defaults to the backend's `asOfDate` once, on the first successful GET. Later
  // refetches (加入 / 刪除 / 重試) must never overwrite what the user has in the field.
  const buyDateDefaultedRef = useRef(false)

  /** Fetches the list and replaces `data`/`status`. Used for the initial load, 重試, and the
   * mandatory re-fetch after every successful 加入/刪除 — the table is never patched in place,
   * because deleting one row renumbers the others' `id`. */
  const runFetch = (silent = false) => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    // `silent` keeps the table mounted (a PATCH refetch must not tear down the control the
    // user is in the middle of using); the data is still replaced wholesale.
    if (!silent) setStatus('loading')
    return fetchRealTrades(controller.signal)
      .then((resp) => {
        if (!mountedRef.current) return
        setData(resp)
        setRowErrors({})
        setStatus('success')
        if (!buyDateDefaultedRef.current) {
          buyDateDefaultedRef.current = true
          setBuyDateValue((current) => (current === '' ? resp.asOfDate : current))
        }
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (!mountedRef.current) return
        setData(null)
        setStatus(err instanceof RealTradeApiError && err.code === 'MALFORMED_TRADE_FILE' ? 'malformed' : 'error')
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

  // One-shot flash on the just-added row.
  useEffect(() => {
    if (flashId == null) return
    const timer = setTimeout(() => setFlashId(null), 1300)
    return () => clearTimeout(timer)
  }, [flashId])

  const malformed = status === 'malformed'
  const allFilled =
    stockIdValue.trim() !== '' && buyDateValue !== '' && buyPriceValue.trim() !== '' && sharesValue.trim() !== ''
  const canAdd = allFilled && !adding && !malformed

  const handleAdd = async () => {
    const stockId = stockIdValue.trim()
    if (!allFilled || adding || malformed) return
    const invalid = validateInputs(
      buyDateValue,
      buyPriceValue.trim(),
      sharesValue.trim(),
      data?.asOfDate ?? localToday(),
    )
    if (invalid) {
      setAddError(invalid)
      return
    }
    setAdding(true)
    try {
      const created = await createRealTrade(stockId, buyDateValue, Number(buyPriceValue), Number(sharesValue))
      if (!mountedRef.current) return
      // Only 代號 and 買進價 are cleared; 買進日 and 量 stay for the next entry of the batch.
      setStockIdValue('')
      setBuyPriceValue('')
      setAddError(null)
      setTableMessage(null)
      setFlashId(created.id)
      await runFetch()
    } catch (err) {
      if (!mountedRef.current) return
      setAddError(addErrorMessage(err))
    } finally {
      if (mountedRef.current) {
        setAdding(false)
        stockIdRef.current?.focus()
      }
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      void handleAdd()
    }
  }

  const handleDelete = async (id: number) => {
    if (deletingId != null) return
    setDeletingId(id)
    try {
      await deleteRealTrade(id)
      if (!mountedRef.current) return
      setTableMessage(null)
      await runFetch()
    } catch (err) {
      if (!mountedRef.current) return
      if (err instanceof RealTradeApiError && err.code === 'REAL_TRADE_NOT_FOUND') {
        setTableMessage(NOT_FOUND_MESSAGE)
        await runFetch()
      } else {
        setTableMessage('刪除失敗，請稍後再試')
      }
    } finally {
      if (mountedRef.current) setDeletingId(null)
    }
  }

  const setControlPending = (key: string, on: boolean) => {
    setPending((current) => {
      const next = new Set(current)
      if (on) next.add(key)
      else next.delete(key)
      return next
    })
  }

  const setRowError = (id: number, message: string | null) => {
    setRowErrors((current) => {
      const { [id]: _previous, ...rest } = current
      return message == null ? rest : { ...rest, [id]: message }
    })
  }

  /** Sends one PATCH, then re-fetches (totals are backend-computed and ids may shift) — never
   * edits the list in place. A failure leaves `data` untouched, so the control snaps back. */
  const patchItem = async (item: RealTradeItem, patch: RealTradePatch, key: string): Promise<CommitResult> => {
    if (pending.has(key)) return 'revert'
    setControlPending(key, true)
    setRowError(item.id, null)
    try {
      await updateRealTrade(item.id, patch)
      if (!mountedRef.current) return 'ok'
      setTableMessage(null)
      await runFetch(true)
      return 'ok'
    } catch (err) {
      if (!mountedRef.current) return 'revert'
      if (err instanceof RealTradeApiError && err.code === 'REAL_TRADE_NOT_FOUND') {
        setTableMessage(NOT_FOUND_MESSAGE)
        await runFetch(true)
      } else {
        setRowError(item.id, patchErrorMessage(err))
      }
      return 'revert'
    } finally {
      if (mountedRef.current) setControlPending(key, false)
    }
  }

  const handleToggleExcluded = (item: RealTradeItem) => {
    void patchItem(item, { excluded: !item.excluded }, `${item.id}:excluded`)
  }

  const handleTargetCommit = async (item: RealTradeItem, raw: string, badInput: boolean): Promise<CommitResult> => {
    const text = raw.trim()
    if (badInput) {
      setRowError(item.id, INVALID_TARGET_MESSAGE)
      return 'revert'
    }
    const valid = PRICE_PATTERN.test(text) && Number(text) > 0
    // 值沒變就不送: blank over blank, or the same number as stored.
    if (text === '' ? item.targetSellPrice == null : valid && Number(text) === item.targetSellPrice) return 'revert'
    if (text === '') return patchItem(item, { targetSellPrice: null }, `${item.id}:target`)
    if (!valid) {
      setRowError(item.id, INVALID_TARGET_MESSAGE)
      return 'revert'
    }
    return patchItem(item, { targetSellPrice: Number(text) }, `${item.id}:target`)
  }

  const items = data?.items ?? []
  const skippedLines = data?.skippedLines ?? []
  const excludedCount = items.filter((item) => item.excluded).length
  // Totals are the backend's; the only decision made here is that "nothing counted" (empty
  // list, or every row 不統計) shows — rather than 0 / 0%.
  const hasTotals = status === 'success' && data != null && items.length - excludedCount > 0
  const isEmpty = status === 'success' && data != null && items.length === 0
  const inputsDisabled = malformed

  return (
    <div className="real-trade-tab">
      <div className="rt-add-bar">
        <div className="rt-add-row">
          <input
            ref={stockIdRef}
            type="text"
            className="rt-add-input rt-code-input"
            placeholder="輸入股票代號，例如 2330"
            aria-label="代號"
            value={stockIdValue}
            disabled={inputsDisabled}
            onChange={(e) => setStockIdValue(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          <input
            type="date"
            className="rt-add-input rt-date-input"
            aria-label="買進日"
            value={buyDateValue}
            max={data?.asOfDate}
            disabled={inputsDisabled}
            onChange={(e) => setBuyDateValue(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          <input
            type="number"
            inputMode="decimal"
            step="0.01"
            min="0"
            className="rt-add-input rt-num-input"
            placeholder="成交價"
            aria-label="買進價"
            value={buyPriceValue}
            disabled={inputsDisabled}
            onChange={(e) => setBuyPriceValue(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          <input
            type="number"
            inputMode="numeric"
            step="1"
            min="1"
            className="rt-add-input rt-num-input"
            placeholder="股數"
            aria-label="量（股數）"
            value={sharesValue}
            disabled={inputsDisabled}
            onChange={(e) => setSharesValue(e.target.value)}
            onKeyDown={handleKeyDown}
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
        {addError ? <div className="rt-add-error">{addError}</div> : null}
        <div className="rt-add-hint">買進價與股數為你實際的成交價與成交量；股數不是張數，1 張 = 1,000 股</div>
      </div>

      {tableMessage ? <div className="sl-toast sl-toast-error">{tableMessage}</div> : null}

      {status === 'error' ? (
        <div className="sl-error">
          <p>載入失敗，請稍後再試</p>
          <button type="button" className="sl-btn sl-btn-primary" onClick={() => void runFetch()}>
            重試
          </button>
        </div>
      ) : malformed ? (
        <div className="sl-error">
          <p>{MALFORMED_LOAD_MESSAGE}</p>
        </div>
      ) : (
        <>
          <div className="rt-summary">
            <div className="rt-summary-row">
              <div className="rt-summary-count">
                共 <b>{items.length}</b> 筆
                {excludedCount > 0 ? <span className="rt-summary-excluded">（{excludedCount} 筆未統計）</span> : null}
              </div>
              <div className="rt-summary-item">
                <span className="rt-summary-label">總成本</span>
                <span className={`rt-summary-value${hasTotals && data ? '' : ' sl-muted'}`}>
                  {hasTotals && data ? formatMoney(data.totalCost) : '—'}
                </span>
              </div>
              <div className="rt-summary-item">
                <span className="rt-summary-label">總未實現損益</span>
                <span
                  className={`rt-summary-value ${hasTotals && data ? gainClass(data.totalUnrealizedProfit) : 'sl-muted'}`}
                >
                  {hasTotals && data ? formatSignedMoney(data.totalUnrealizedProfit) : '—'}
                </span>
              </div>
              <div className="rt-summary-item">
                <span className="rt-summary-label">總報酬率</span>
                <span
                  className={`rt-summary-value ${
                    hasTotals && data?.totalReturnPercent != null ? gainClass(data.totalReturnPercent) : 'sl-muted'
                  }`}
                >
                  {hasTotals && data?.totalReturnPercent != null ? formatSignedPercent(data.totalReturnPercent) : '—'}
                </span>
              </div>
            </div>
            {data ? (
              <div className="rt-fee-note">
                未實現損益已扣手續費 {data.feeRatePercent}%（買賣各一次）與證交稅 {data.taxRatePercent}%，賣出成本以現價估算
              </div>
            ) : null}
          </div>

          {status === 'loading' ? (
            <div className="rt-loading">載入中…</div>
          ) : isEmpty ? (
            <div className="sl-table-wrap">
              <div className="sl-empty">
                <p>還沒有任何部位，用上面那一列加入</p>
              </div>
            </div>
          ) : data ? (
            <div className="sl-table-wrap">
              <table className="sl-table rt-table">
                <thead>
                  <tr>
                    <th className="rt-center">不統計</th>
                    <th>代號 / 名稱</th>
                    <th>買進日</th>
                    <th className="sl-r">買進價</th>
                    <th className="sl-r">股數</th>
                    <th>現價日</th>
                    <th className="sl-r">現價</th>
                    <th className="sl-r">目標賣價</th>
                    <th className="sl-r">成本</th>
                    <th className="sl-r">未實現損益</th>
                    <th className="sl-r">報酬率</th>
                    <th className="sl-r">刪除</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item: RealTradeItem) => {
                    const stale = item.currentDate != null && item.currentDate < data.asOfDate
                    const rowError = rowErrors[item.id]
                    return (
                      <Fragment key={item.id}>
                      <tr className={flashId === item.id ? 'rt-row-flash' : undefined}>
                        <td className="rt-center">
                          <input
                            type="checkbox"
                            className="rt-exclude-box"
                            aria-label="不統計"
                            checked={item.excluded}
                            disabled={pending.has(`${item.id}:excluded`)}
                            onChange={() => handleToggleExcluded(item)}
                          />
                        </td>
                        <td>
                          <div className="rt-stock-cell">
                            <span className="sl-code">{item.stockId}</span>
                            <span className="rt-stock-name">{item.stockName ?? ''}</span>
                          </div>
                        </td>
                        <td>{item.buyDate}</td>
                        <td className="sl-r">{formatPrice(item.buyPrice)}</td>
                        <td className="sl-r">{formatMoney(item.shares)}</td>
                        <td className={item.currentDate == null ? 'sl-muted' : stale ? 'rt-stale-date' : undefined}>
                          {item.currentDate ?? '—'}
                        </td>
                        <td className={`sl-r${item.currentPrice == null ? ' sl-muted' : ''}`}>
                          {item.currentPrice != null ? formatPrice(item.currentPrice) : '—'}
                        </td>
                        <td className="sl-r">
                          <TargetSellPriceInput
                            value={item.targetSellPrice}
                            disabled={pending.has(`${item.id}:target`)}
                            onCommit={(raw, badInput) => handleTargetCommit(item, raw, badInput)}
                          />
                        </td>
                        <td className="sl-r">{formatMoney(item.cost)}</td>
                        <td
                          className={`sl-r ${item.unrealizedProfit != null ? gainClass(item.unrealizedProfit) : 'sl-muted'}`}
                        >
                          {item.unrealizedProfit != null ? formatSignedMoney(item.unrealizedProfit) : '—'}
                        </td>
                        <td
                          className={`sl-r ${item.returnPercent != null ? gainClass(item.returnPercent) : 'sl-muted'}`}
                        >
                          {item.returnPercent != null ? formatSignedPercent(item.returnPercent) : '—'}
                        </td>
                        <td className="sl-r">
                          <button
                            type="button"
                            className="rt-del-btn"
                            disabled={deletingId != null}
                            onClick={() => void handleDelete(item.id)}
                          >
                            刪除
                          </button>
                        </td>
                      </tr>
                      {rowError ? (
                        <tr className="rt-row-error">
                          <td colSpan={COLUMN_COUNT}>
                            <div className="rt-row-error-msg">{rowError}</div>
                          </td>
                        </tr>
                      ) : null}
                      </Fragment>
                    )
                  })}
                </tbody>
              </table>
            </div>
          ) : null}

          {skippedLines.length > 0 ? (
            <ul className="rt-skipped">
              {skippedLines.map((line) => (
                <li key={line.lineNumber}>
                  第 {line.lineNumber} 行格式有誤，已略過：{line.content}
                </li>
              ))}
            </ul>
          ) : null}
        </>
      )}
    </div>
  )
}
