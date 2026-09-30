import { useEffect, useMemo, useState } from 'react'
import { fetchStockDetail, type StockDetail } from '../api/stocks'
import {
  EXPECTED_HEADER,
  computePosition,
  realTradesParse,
  returnPercent,
  todayString,
  type Position,
  type RealTrade,
} from '../data/realTrades'
import './RealTradeTab.css'

type Status = 'loading' | 'ready'

/** Per distinct stockId: the detail, or `null` when the lookup failed (404, network, …). */
type Quotes = Map<string, StockDetail | null>

interface Row {
  trade: RealTrade
  name: string | null
  currentDate: string | null
  currentPrice: number | null
  position: Position
}

function formatMoney(value: number): string {
  return value.toLocaleString('en-US')
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

function buildRow(trade: RealTrade, detail: StockDetail | null | undefined): Row {
  const close = detail?.latestClose ?? null
  const usable = detail != null && close != null && close > 0 && detail.latestTradeDate != null
  return {
    trade,
    name: detail?.stockName ?? null,
    currentDate: usable ? detail.latestTradeDate : null,
    currentPrice: usable ? close : null,
    position: computePosition(trade.buyPrice, trade.shares, usable ? close : null),
  }
}

/** 真實交易分頁 — specs/frontend/real-trade.md, the 5th tab in `/stocks`. Strictly read-only:
 * the data is a version-controlled CSV bundled with the code, so this component has no add /
 * delete / edit affordance and issues nothing but `GET /api/stocks/{stockId}`, once per
 * distinct stockId. Takes no `commonStocksOnly` prop — the page-level filter doesn't apply. */
export default function RealTradeTab() {
  const parsed = realTradesParse
  const trades = useMemo(() => (parsed.ok ? parsed.trades : []), [parsed])
  const today = useMemo(() => todayString(), [])

  const [quotes, setQuotes] = useState<Quotes>(() => new Map())
  const [status, setStatus] = useState<Status>(trades.length === 0 ? 'ready' : 'loading')

  useEffect(() => {
    if (trades.length === 0) return
    const controller = new AbortController()
    const ids = [...new Set(trades.map((t) => t.stockId))]
    Promise.all(
      ids.map((id) =>
        fetchStockDetail(id, controller.signal).then(
          (detail): [string, StockDetail | null] => [id, detail],
          (err: unknown): [string, StockDetail | null] => {
            if (err instanceof DOMException && err.name === 'AbortError') throw err
            return [id, null]
          },
        ),
      ),
    )
      .then((entries) => {
        if (controller.signal.aborted) return
        setQuotes(new Map(entries))
        setStatus('ready')
      })
      .catch((err: unknown) => {
        // Per-id failures are already folded into `null` above, so only an AbortError (unmount)
        // is expected here; anything else is a bug and must not vanish silently.
        if (err instanceof DOMException && err.name === 'AbortError') return
        console.error('real-trade: unexpected failure loading quotes', err)
        if (!controller.signal.aborted) setStatus('ready')
      })
    return () => controller.abort()
  }, [trades])

  const rows = useMemo(() => trades.map((t) => buildRow(t, quotes.get(t.stockId))), [trades, quotes])

  if (!parsed.ok) {
    return (
      <div className="real-trade-tab">
        <div className="sl-error">
          <p>
            <code>real-trades.csv</code> 的欄位名列必須是 <code>{EXPECTED_HEADER}</code>
          </p>
        </div>
      </div>
    )
  }

  const loading = status === 'loading'
  const count = rows.length
  const hasRows = count > 0
  const totalCost = rows.reduce((sum, r) => sum + r.position.cost, 0)
  const priced = rows.filter((r) => r.position.unrealizedProfit != null)
  const totalProfit = priced.reduce((sum, r) => sum + (r.position.unrealizedProfit ?? 0), 0)
  const pricedCost = priced.reduce((sum, r) => sum + r.position.cost, 0)
  const showTotals = !loading && hasRows
  const showProfit = showTotals && priced.length > 0
  const totalReturn = showProfit ? returnPercent(totalProfit, pricedCost) : null

  return (
    <div className="real-trade-tab">
      <div className="rt-summary">
        <div className="rt-summary-row">
          <div className="rt-summary-count">
            共 <b>{count}</b> 筆
          </div>
          <div className="rt-summary-item">
            <span className="rt-summary-label">總成本</span>
            <span className={`rt-summary-value${showTotals ? '' : ' sl-muted'}`}>
              {showTotals ? formatMoney(totalCost) : '—'}
            </span>
          </div>
          <div className="rt-summary-item">
            <span className="rt-summary-label">總未實現損益</span>
            <span className={`rt-summary-value ${showProfit ? gainClass(totalProfit) : 'sl-muted'}`}>
              {showProfit ? formatSignedMoney(totalProfit) : '—'}
            </span>
          </div>
          <div className="rt-summary-item">
            <span className="rt-summary-label">總報酬率</span>
            <span
              className={`rt-summary-value ${totalReturn != null ? gainClass(totalReturn) : 'sl-muted'}`}
            >
              {totalReturn != null ? formatSignedPercent(totalReturn) : '—'}
            </span>
          </div>
        </div>
        <div className="rt-fee-note">
          未實現損益已扣手續費 0.1425%（買賣各一次）與證交稅 0.3%，賣出成本以現價估算
        </div>
      </div>

      {loading ? (
        <div className="rt-loading">載入中…</div>
      ) : !hasRows ? (
        <div className="sl-table-wrap">
          <div className="sl-empty">
            <p>
              <code>real-trades.csv</code> 裡還沒有任何部位
            </p>
          </div>
        </div>
      ) : (
        <div className="sl-table-wrap">
          <table className="sl-table rt-table">
            <thead>
              <tr>
                <th>代號 / 名稱</th>
                <th>買進日</th>
                <th className="sl-r">買進價</th>
                <th className="sl-r">股數</th>
                <th>現價日</th>
                <th className="sl-r">現價</th>
                <th className="sl-r">成本</th>
                <th className="sl-r">未實現損益</th>
                <th className="sl-r">報酬率</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const p = row.position
                const dash = <span className="sl-muted">—</span>
                const stale = row.currentDate != null && row.currentDate < today
                return (
                  <tr key={row.trade.line}>
                    <td>
                      <div className="rt-stock-cell">
                        <span className="sl-code">{row.trade.stockId}</span>
                        <span className="rt-stock-name">{row.name ?? dash}</span>
                      </div>
                    </td>
                    <td>{row.trade.buyDate}</td>
                    <td className="sl-r">{Number(row.trade.buyPrice).toFixed(2)}</td>
                    <td className="sl-r">{formatMoney(row.trade.shares)}</td>
                    <td className={stale ? 'rt-stale-date' : undefined}>{row.currentDate ?? dash}</td>
                    <td className="sl-r">{row.currentPrice != null ? row.currentPrice.toFixed(2) : dash}</td>
                    <td className="sl-r">{formatMoney(p.cost)}</td>
                    <td
                      className={`sl-r${p.unrealizedProfit != null ? ` ${gainClass(p.unrealizedProfit)}` : ''}`}
                    >
                      {p.unrealizedProfit != null ? formatSignedMoney(p.unrealizedProfit) : dash}
                    </td>
                    <td className={`sl-r${p.returnPercent != null ? ` ${gainClass(p.returnPercent)}` : ''}`}>
                      {p.returnPercent != null ? formatSignedPercent(p.returnPercent) : dash}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {parsed.skipped.length > 0 ? (
        <ul className="rt-skipped">
          {parsed.skipped.map((s) => (
            <li key={s.line}>
              第 {s.line} 行格式有誤，已略過：{s.raw}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
