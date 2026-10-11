import { useEffect, useRef, useState, type DragEvent } from 'react'
import './StrategyCardSettingsList.css'

export interface StrategyCardSettingsItem {
  code: string
  name: string
  hidden: boolean
}

export interface StrategyCardSettingsListProps {
  /** 全部策略（含已隱藏的），依目前的排列順序由上而下。 */
  items: StrategyCardSettingsItem[]
  /** 把第 `fromIndex` 列移到第 `toIndex` 列的位置（兩者皆為移動前清單的索引）。 */
  onMove: (fromIndex: number, toIndex: number) => void
  onToggleHidden: (code: string) => void
}

type MoveDirection = 'up' | 'down'

/** 策略設定清單（specs/frontend/strategy.md「策略設定：隱藏與排序」）：一列一個策略，可拖拉
 * 排序、可用「上移」「下移」排序、可隱藏／解除隱藏。只呈現與回報操作，不持有順序本身。 */
export default function StrategyCardSettingsList({ items, onMove, onToggleHidden }: StrategyCardSettingsListProps) {
  const listRef = useRef<HTMLUListElement>(null)
  const [draggingCode, setDraggingCode] = useState<string | null>(null)
  // 拖拉中的落點：放開後該列會插在第幾列「之前」（等於列數時代表插在最後）。
  const [dropIndex, setDropIndex] = useState<number | null>(null)

  // 「上移」「下移」移動後，鍵盤焦點要留在同一個策略的同一顆按鈕上才能連按；列在 DOM 中被
  // 搬動時瀏覽器會讓它失焦，所以記下該回到哪一顆，於重繪後補回。該鈕因到頂／到底而 disabled
  // 時改落在同一列的另一顆。
  const moveButtonRefs = useRef(new Map<string, HTMLButtonElement>())
  const pendingFocus = useRef<{ code: string; direction: MoveDirection } | null>(null)
  useEffect(() => {
    const pending = pendingFocus.current
    if (!pending) return
    pendingFocus.current = null
    const same = moveButtonRefs.current.get(`${pending.code}:${pending.direction}`)
    const other = moveButtonRefs.current.get(`${pending.code}:${pending.direction === 'up' ? 'down' : 'up'}`)
    const target = same && !same.disabled ? same : other
    target?.focus()
  }, [items])

  const setMoveButtonRef = (code: string, direction: MoveDirection) => (el: HTMLButtonElement | null) => {
    const key = `${code}:${direction}`
    if (el) moveButtonRefs.current.set(key, el)
    else moveButtonRefs.current.delete(key)
  }

  const moveByButton = (index: number, direction: MoveDirection) => {
    pendingFocus.current = { code: items[index].code, direction }
    onMove(index, direction === 'up' ? index - 1 : index + 1)
  }

  /** 指標落在某一列的上半 → 插在它前面，下半 → 插在它後面；列與列之間的空隙同樣算得出來，
   * 所以以整份清單而非單一列來判定。 */
  const insertionIndexAt = (clientY: number): number => {
    const rows = listRef.current?.querySelectorAll<HTMLLIElement>(':scope > li') ?? []
    let index = 0
    for (const row of rows) {
      const rect = row.getBoundingClientRect()
      if (clientY > rect.top + rect.height / 2) index += 1
    }
    return index
  }

  const endDrag = () => {
    setDraggingCode(null)
    setDropIndex(null)
  }

  const handleDragStart = (e: DragEvent<HTMLLIElement>, code: string) => {
    e.dataTransfer.effectAllowed = 'move'
    // Firefox 沒有 setData 就不會開始拖拉。
    e.dataTransfer.setData('text/plain', code)
    setDraggingCode(code)
  }

  const handleDragOver = (e: DragEvent<HTMLUListElement>) => {
    if (draggingCode === null) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    const next = insertionIndexAt(e.clientY)
    setDropIndex((current) => (current === next ? current : next))
  }

  const handleDragLeave = (e: DragEvent<HTMLUListElement>) => {
    // 只有真的離開整份清單才清掉落點；在列與列之間移動也會觸發 dragleave。
    if (e.relatedTarget instanceof Node && e.currentTarget.contains(e.relatedTarget)) return
    setDropIndex(null)
  }

  const handleDrop = (e: DragEvent<HTMLUListElement>) => {
    if (draggingCode === null) return
    e.preventDefault()
    const fromIndex = items.findIndex((item) => item.code === draggingCode)
    const insertion = insertionIndexAt(e.clientY)
    endDrag()
    if (fromIndex < 0) return
    // 插在自己前面或後面都等於沒動。
    const toIndex = insertion > fromIndex ? insertion - 1 : insertion
    if (toIndex !== fromIndex) onMove(fromIndex, toIndex)
  }

  return (
    <ul
      ref={listRef}
      className="scs-list"
      aria-label="策略設定"
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {items.map((item, index) => {
        const classes = ['scs-row']
        if (item.hidden) classes.push('scs-row-hidden')
        if (item.code === draggingCode) classes.push('scs-row-dragging')
        if (dropIndex === index) classes.push('scs-row-drop-before')
        if (dropIndex === items.length && index === items.length - 1) classes.push('scs-row-drop-after')
        return (
          <li
            key={item.code}
            className={classes.join(' ')}
            data-code={item.code}
            draggable
            onDragStart={(e) => handleDragStart(e, item.code)}
            onDragEnd={endDrag}
          >
            <span className="scs-grip" aria-hidden="true">
              ⠿
            </span>
            <span className="scs-name">{item.name}</span>
            {item.hidden ? <span className="scs-hidden-mark">已隱藏</span> : null}
            <span className="scs-actions">
              <button
                type="button"
                className="sl-btn sl-btn-sm"
                ref={setMoveButtonRef(item.code, 'up')}
                disabled={index === 0}
                aria-label={`上移 ${item.name}`}
                onClick={() => moveByButton(index, 'up')}
              >
                上移
              </button>
              <button
                type="button"
                className="sl-btn sl-btn-sm"
                ref={setMoveButtonRef(item.code, 'down')}
                disabled={index === items.length - 1}
                aria-label={`下移 ${item.name}`}
                onClick={() => moveByButton(index, 'down')}
              >
                下移
              </button>
              <button
                type="button"
                className="sl-btn sl-btn-sm"
                aria-label={`${item.hidden ? '解除隱藏' : '隱藏'} ${item.name}`}
                onClick={() => onToggleHidden(item.code)}
              >
                {item.hidden ? '解除隱藏' : '隱藏'}
              </button>
            </span>
          </li>
        )
      })}
    </ul>
  )
}
