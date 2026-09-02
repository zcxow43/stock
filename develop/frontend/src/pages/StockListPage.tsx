import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import StockOverviewTab from './StockOverviewTab'
import StrategyTab from './StrategyTab'
import MomentumTab from './MomentumTab'
import './StockListPage.css'

type TabKey = 'overview' | 'strategy' | 'momentum'

/** Unrecognised or missing `tab` falls back to 總覽 — spec explicitly forbids a blank screen. */
function resolveTab(raw: string | null): TabKey {
  return raw === 'strategy' || raw === 'momentum' ? raw : 'overview'
}

export default function StockListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tab = resolveTab(searchParams.get('tab'))
  const [total, setTotal] = useState(0)

  const selectTab = (next: TabKey) => {
    // `replace: true` — switching tabs is a view change, not a new history entry to back-button through.
    setSearchParams(next === 'overview' ? {} : { tab: next }, { replace: true })
  }

  return (
    <div className="stock-list-page">
      <div className="sl-head">
        <h1>股票總覽</h1>
        <div className="sl-count">
          共 <b>{total.toLocaleString('en-US')}</b> 檔
        </div>
      </div>

      <div className="sl-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'overview'}
          className={`sl-tab${tab === 'overview' ? ' sl-tab-active' : ''}`}
          onClick={() => selectTab('overview')}
        >
          總覽
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'strategy'}
          className={`sl-tab${tab === 'strategy' ? ' sl-tab-active' : ''}`}
          onClick={() => selectTab('strategy')}
        >
          策略
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'momentum'}
          className={`sl-tab${tab === 'momentum' ? ' sl-tab-active' : ''}`}
          onClick={() => selectTab('momentum')}
        >
          動態
        </button>
      </div>

      {/* All three tabs stay mounted and are toggled with display:none so switching never
          re-fetches, remounts, or flickers the header/tab bar above. */}
      <div data-testid="sl-tabpanel-overview" style={{ display: tab === 'overview' ? 'block' : 'none' }}>
        <StockOverviewTab onTotalChange={setTotal} />
      </div>
      <div data-testid="sl-tabpanel-strategy" style={{ display: tab === 'strategy' ? 'block' : 'none' }}>
        <StrategyTab />
      </div>
      <div data-testid="sl-tabpanel-momentum" style={{ display: tab === 'momentum' ? 'block' : 'none' }}>
        <MomentumTab />
      </div>
    </div>
  )
}
