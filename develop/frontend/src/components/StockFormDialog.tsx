import { useEffect, useRef, useState } from 'react'
import {
  ApiError,
  createStock,
  updateStock,
  type Market,
  type StockDetail,
  type StockListItem,
} from '../api/stocks'
import './StockFormDialog.css'

interface CommonProps {
  onClose: () => void
  onSuccess: (stock: StockDetail, mode: 'create' | 'edit') => void
  /** PUT came back 404 — the row was removed elsewhere; caller closes and reloads the list. */
  onNotFound: () => void
}

/** `edit` requires the row being edited at the type level — no `stock!` non-null assertions needed. */
export type StockFormDialogProps =
  | ({ mode: 'create' } & CommonProps)
  | ({ mode: 'edit'; stock: StockListItem } & CommonProps)

type FieldErrors = { stockId?: string; stockName?: string }

const NAME_MAX_LENGTH = 60
const CODE_MAX_LENGTH = 10

export default function StockFormDialog(props: StockFormDialogProps) {
  const { mode, onClose, onSuccess, onNotFound } = props
  const stock = mode === 'edit' ? props.stock : undefined
  const [stockId, setStockId] = useState(stock?.stockId ?? '')
  const [stockName, setStockName] = useState(stock?.stockName ?? '')
  const [market, setMarket] = useState<Market>(stock?.market ?? 'TSE')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const isMounted = useRef(true)
  useEffect(() => {
    // Set (not just rely on the initial ref value) so React 19 StrictMode's dev-only
    // mount→unmount→remount dance flips this back to true on the second mount instead
    // of leaving it permanently false.
    isMounted.current = true
    return () => {
      isMounted.current = false
    }
  }, [])

  const title = mode === 'create' ? '新增股票' : `編輯股票 — ${stock?.stockId}`

  function validate(): FieldErrors {
    const errors: FieldErrors = {}
    if (mode === 'create') {
      const trimmedId = stockId.trim()
      if (!trimmedId) {
        errors.stockId = '請輸入股票代號'
      } else if (trimmedId.length > CODE_MAX_LENGTH) {
        errors.stockId = `代號長度不可超過 ${CODE_MAX_LENGTH} 字`
      }
    }
    const trimmedName = stockName.trim()
    if (!trimmedName) {
      errors.stockName = '請輸入股票名稱'
    } else if (trimmedName.length > NAME_MAX_LENGTH) {
      errors.stockName = `名稱長度不可超過 ${NAME_MAX_LENGTH} 字`
    }
    return errors
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (submitting) return

    const errors = validate()
    setFieldErrors(errors)
    setFormError(null)
    if (Object.keys(errors).length > 0) return

    setSubmitting(true)
    try {
      const result =
        props.mode === 'create'
          ? await createStock({ stockId: stockId.trim(), stockName: stockName.trim(), market })
          : await updateStock(props.stock.stockId, {
              stockName: stockName.trim(),
              market,
              isActive: props.stock.isActive,
            })
      if (!isMounted.current) return
      onSuccess(result, mode)
    } catch (err) {
      if (!isMounted.current) return
      if (err instanceof ApiError) {
        if (err.code === 'STOCK_ALREADY_EXISTS') {
          setFieldErrors({ stockId: '此代號已存在' })
          return
        }
        if (err.code === 'INVALID_STOCK_PAYLOAD') {
          const next: FieldErrors = {}
          for (const field of err.fields ?? []) {
            if (field === 'stockId') next.stockId = '代號格式不正確'
            if (field === 'stockName') next.stockName = '名稱格式不正確'
          }
          setFieldErrors(next)
          if (Object.keys(next).length === 0) {
            setFormError('資料格式不正確，請確認後重試')
          }
          return
        }
        if (err.code === 'STOCK_NOT_FOUND') {
          onNotFound()
          return
        }
      }
      setFormError('發生錯誤，請稍後再試')
    } finally {
      if (isMounted.current) setSubmitting(false)
    }
  }

  return (
    <div className="sfd-overlay" role="presentation" onMouseDown={() => !submitting && onClose()}>
      <div
        className="sfd-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 className="sfd-title">{title}</h2>
        <form onSubmit={handleSubmit}>
          <div className={`sfd-field${fieldErrors.stockId ? ' sfd-field-invalid' : ''}`}>
            <label htmlFor="sfd-stock-id">股票代號</label>
            {mode === 'edit' ? (
              <div className="sfd-readonly" id="sfd-stock-id">
                {stock?.stockId}
              </div>
            ) : (
              <input
                id="sfd-stock-id"
                type="text"
                value={stockId}
                disabled={submitting}
                onChange={(e) => setStockId(e.target.value)}
              />
            )}
            {fieldErrors.stockId ? <p className="sfd-field-error">{fieldErrors.stockId}</p> : null}
          </div>

          <div className={`sfd-field${fieldErrors.stockName ? ' sfd-field-invalid' : ''}`}>
            <label htmlFor="sfd-stock-name">股票名稱</label>
            <input
              id="sfd-stock-name"
              type="text"
              value={stockName}
              disabled={submitting}
              maxLength={NAME_MAX_LENGTH}
              onChange={(e) => setStockName(e.target.value)}
            />
            {fieldErrors.stockName ? <p className="sfd-field-error">{fieldErrors.stockName}</p> : null}
          </div>

          <div className="sfd-field">
            <label htmlFor="sfd-market">市場別</label>
            <select
              id="sfd-market"
              value={market}
              disabled={submitting}
              onChange={(e) => setMarket(e.target.value as Market)}
            >
              <option value="TSE">上市</option>
              <option value="OTC">上櫃</option>
            </select>
          </div>

          {formError ? <p className="sfd-form-error">{formError}</p> : null}

          <div className="sfd-actions">
            <button type="button" className="sl-btn" disabled={submitting} onClick={onClose}>
              取消
            </button>
            <button type="submit" className="sl-btn sl-btn-primary" disabled={submitting}>
              {submitting ? '送出中…' : '確定'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
