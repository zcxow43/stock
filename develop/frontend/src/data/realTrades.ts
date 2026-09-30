// Parser + calculator for the 真實交易 tab — specs/frontend/real-trade.md.
//
// The CSV is bundled with the code (`?raw`), so reading it is not a network request. All
// money maths is integer arithmetic on BigInt (prices in 1/100 yuan) so that e.g.
// 21.55 × 1000 × 0.1425% can never land on 30.7099999… and floor to the wrong yuan — the
// same reason the backend's TradingCostCalculator uses BigDecimal, never double.

import rawCsv from './real-trades.csv?raw'

export const EXPECTED_HEADER = 'stockId,buyDate,buyPrice,shares'

export interface RealTrade {
  /** 1-based file line number (header = 1), used only for stable ordering/keys. */
  line: number
  stockId: string
  buyDate: string
  /** The user's actual fill price as written in the CSV, e.g. "21.55". */
  buyPrice: string
  /** Share count, NOT lots (1 lot = 1000 shares). */
  shares: number
}

export interface SkippedLine {
  line: number
  raw: string
}

export type ParseResult =
  | { ok: true; trades: RealTrade[]; skipped: SkippedLine[] }
  | { ok: false }

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const PRICE_RE = /^\d+(\.\d{1,2})?$/
const SHARES_RE = /^\d+$/

function isRealDate(value: string): boolean {
  if (!DATE_RE.test(value)) return false
  const [y, m, d] = value.split('-').map(Number)
  const dt = new Date(Date.UTC(y, m - 1, d))
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d
}

/** Local calendar date as YYYY-MM-DD. */
export function todayString(now: Date = new Date()): string {
  const mm = String(now.getMonth() + 1).padStart(2, '0')
  const dd = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${mm}-${dd}`
}

function parseLine(raw: string, line: number, today: string): RealTrade | null {
  const cols = raw.split(',').map((c) => c.trim())
  if (cols.length !== 4) return null
  const [stockId, buyDate, buyPrice, sharesText] = cols
  if (stockId === '') return null
  if (!isRealDate(buyDate) || buyDate > today) return null
  if (!PRICE_RE.test(buyPrice) || toCents(buyPrice) <= 0n) return null
  if (!SHARES_RE.test(sharesText)) return null
  const shares = Number(sharesText)
  if (!Number.isSafeInteger(shares) || shares <= 0) return null
  return { line, stockId, buyDate, buyPrice, shares }
}

export function parseRealTrades(text: string, today: string = todayString()): ParseResult {
  const lines = text.replace(/^﻿/, '').split(/\r?\n/)
  if ((lines[0] ?? '').trim() !== EXPECTED_HEADER) return { ok: false }

  const trades: RealTrade[] = []
  const skipped: SkippedLine[] = []
  for (let i = 1; i < lines.length; i++) {
    const raw = lines[i].trim()
    if (raw === '' || raw.startsWith('#')) continue
    const trade = parseLine(raw, i + 1, today)
    if (trade) trades.push(trade)
    else skipped.push({ line: i + 1, raw })
  }

  // buyDate newest first, same day by stockId ascending; file order breaks remaining ties.
  trades.sort((a, b) => {
    if (a.buyDate !== b.buyDate) return a.buyDate < b.buyDate ? 1 : -1
    if (a.stockId !== b.stockId) return a.stockId < b.stockId ? -1 : 1
    return a.line - b.line
  })
  return { ok: true, trades, skipped }
}

/** The bundled file, parsed once at module load. */
export const realTradesParse: ParseResult = parseRealTrades(rawCsv)

// ---------- money maths (same definition as specs/backend/simulated-trade.md) ----------

/** "21.55" -> 2155n. Accepts at most two decimals (validated upstream for the CSV). */
function toCents(price: string): bigint {
  const [whole, frac = ''] = price.split('.')
  return BigInt(whole + frac.padEnd(2, '0'))
}

/** Price from a JSON number -> cents. Prices are DECIMAL(_,2) so this is exact. */
function numberToCents(price: number): bigint {
  return BigInt(Math.round(price * 100))
}

/** Rounds n/d to the nearest integer, ties away from zero (BigDecimal HALF_UP). d > 0. */
function divHalfUp(n: bigint, d: bigint): bigint {
  const neg = n < 0n
  const abs = neg ? -n : n
  const q = (abs * 2n + d) / (2n * d)
  return neg ? -q : q
}

// rate = ppm / 1e6; price in cents -> yuan divides by a further 100
const FEE_PPM = 1425n
const TAX_PPM = 3000n
const FEE_TAX_DIVISOR = 100_000_000n

function feeOrTax(cents: bigint, shares: number, ppm: bigint): bigint {
  return (cents * BigInt(shares) * ppm) / FEE_TAX_DIVISOR // operands are positive: floor
}

export interface Position {
  cost: number
  buyFee: number
  /** null when there is no usable current price. */
  sellFee: number | null
  sellTax: number | null
  unrealizedProfit: number | null
  /** Percent with two decimals, e.g. 6.58. */
  returnPercent: number | null
}

/** 報酬率 = 收益 ÷ 成本 × 100 — scale-10 division then scale-2, both HALF_UP, like the backend. */
export function returnPercent(profit: number, cost: number): number {
  const q10 = divHalfUp(BigInt(profit) * 10_000_000_000n, BigInt(cost))
  // q10 is percent/100 at scale 10; hundredths of a percent = q10 / 1e6
  return Number(divHalfUp(q10, 1_000_000n)) / 100
}

export function computePosition(buyPrice: string, shares: number, currentPrice: number | null): Position {
  const buyCents = toCents(buyPrice)
  const buyFee = feeOrTax(buyCents, shares, FEE_PPM)
  const cost = divHalfUp(buyCents * BigInt(shares) + buyFee * 100n, 100n)

  if (currentPrice == null || !(currentPrice > 0)) {
    return {
      cost: Number(cost),
      buyFee: Number(buyFee),
      sellFee: null,
      sellTax: null,
      unrealizedProfit: null,
      returnPercent: null,
    }
  }
  const sellCents = numberToCents(currentPrice)
  const sellFee = feeOrTax(sellCents, shares, FEE_PPM)
  const sellTax = feeOrTax(sellCents, shares, TAX_PPM)
  const profit = divHalfUp(sellCents * BigInt(shares) - (sellFee + sellTax + cost) * 100n, 100n)
  return {
    cost: Number(cost),
    buyFee: Number(buyFee),
    sellFee: Number(sellFee),
    sellTax: Number(sellTax),
    unrealizedProfit: Number(profit),
    returnPercent: returnPercent(Number(profit), Number(cost)),
  }
}
