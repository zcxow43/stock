import { Navigate, Route, Routes } from 'react-router-dom'
import StockDailyChartPage from './pages/StockDailyChartPage'
import StockListPage from './pages/StockListPage'
import StockMinuteChartPage from './pages/StockMinuteChartPage'

function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/stocks" replace />} />
      <Route path="/stocks" element={<StockListPage />} />
      <Route path="/stocks/:stockId/daily" element={<StockDailyChartPage />} />
      <Route path="/stocks/:stockId/minute/:tradeDate" element={<StockMinuteChartPage />} />
    </Routes>
  )
}

export default App
