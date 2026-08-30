import './ConfirmDialog.css'

export interface ConfirmDialogProps {
  title: string
  message: string
  confirmLabel: string
  cancelLabel?: string
  /** Renders the confirm button in the destructive (red) style — per Visual Style. */
  destructive?: boolean
  submitting?: boolean
  onConfirm: () => void
  onCancel: () => void
}

export default function ConfirmDialog({
  title,
  message,
  confirmLabel,
  cancelLabel = '取消',
  destructive = false,
  submitting = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <div className="cfd-overlay" role="presentation" onMouseDown={() => !submitting && onCancel()}>
      <div
        className="cfd-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 className="cfd-title">{title}</h2>
        <p className="cfd-message">{message}</p>
        <div className="cfd-actions">
          <button type="button" className="sl-btn" disabled={submitting} onClick={onCancel}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={destructive ? 'sl-btn sl-btn-danger' : 'sl-btn sl-btn-primary'}
            disabled={submitting}
            onClick={onConfirm}
          >
            {submitting ? '送出中…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
