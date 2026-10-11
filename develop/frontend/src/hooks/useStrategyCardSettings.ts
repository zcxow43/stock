import { useState } from 'react'

/** 策略選項區的隱藏與順序（specs/frontend/strategy.md「策略設定：隱藏與排序」）。兩個陣列的
 * 元素都是策略 `code`——名稱是 API 的文案、會改，不存。`order` 是設定清單由上而下的順序
 * （含已隱藏的），`hidden` 是其中被隱藏的。 */
export interface StrategyCardSettings {
  order: string[]
  hidden: string[]
}

const STORAGE_KEY = 'stock.strategy.cardSettings'

/** 「沒有設定」——全部顯示、依 API 回應的順序。 */
const NO_SETTINGS: StrategyCardSettings = { order: [], hidden: [] }

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

/** 讀不到、不是合法 JSON、或形狀不符時一律當成沒有設定：這份資料只是版面偏好，壞掉時退回預設
 * 比顯示錯誤合理，頁面不得因此壞掉。損壞的內容留在原處，等下一次變更時被覆寫。 */
function loadSettings(): StrategyCardSettings {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (raw === null) return NO_SETTINGS
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return NO_SETTINGS
    const { order, hidden } = parsed as { order?: unknown; hidden?: unknown }
    if (!isStringArray(order) || !isStringArray(hidden)) return NO_SETTINGS
    return { order, hidden }
  } catch {
    return NO_SETTINGS
  }
}

/** 寫入失敗（儲存空間不可用或已滿）不顯示錯誤：這一次變更在目前這一頁照樣生效，只是重新
 * 整理後不在。 */
function saveSettings(settings: StrategyCardSettings): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
  } catch {
    // 刻意不處理，理由見上。
  }
}

/** 依儲存的 `order` 排列 API 回的策略：API 已不存在的 `code` 忽略、重複的只取第一次；`order`
 * 裡沒有的策略（之後新增的型態）排在最後，彼此之間維持 API 回應的順序。 */
export function applyCardOrder<T extends { code: string }>(catalog: T[], order: string[]): T[] {
  const byCode = new Map(catalog.map((item) => [item.code, item]))
  const placed = new Set<string>()
  const result: T[] = []
  for (const code of order) {
    const item = byCode.get(code)
    if (!item || placed.has(code)) continue
    placed.add(code)
    result.push(item)
  }
  for (const item of catalog) {
    if (!placed.has(item.code)) result.push(item)
  }
  return result
}

/** 進頁時從 `localStorage` 讀一次；之後每一次變更當下就寫回，沒有「儲存」這個步驟。 */
export function useStrategyCardSettings(): [StrategyCardSettings, (next: StrategyCardSettings) => void] {
  const [settings, setSettings] = useState<StrategyCardSettings>(loadSettings)
  const update = (next: StrategyCardSettings) => {
    setSettings(next)
    saveSettings(next)
  }
  return [settings, update]
}
