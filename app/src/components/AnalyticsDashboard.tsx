import { useEffect, useMemo, useState } from 'react'
import { api, loadProcessingGatewayConfig } from '../api'

type AnalyticsAccount = {
  account_id: string
  platform: string
  account_name: string
  data_available: boolean
  days: Array<{ metric_date: string; views: number; likes: number; comments: number; followers?: number | null; watch_time_seconds?: number | null; source: string; fetched_at: string }>
}

interface Props { onBack: () => void }

const numberFormat = new Intl.NumberFormat('en-US')

export default function AnalyticsDashboard({ onBack }: Props) {
  const saved = loadProcessingGatewayConfig()
  const [gatewayUrl] = useState(saved.url)
  const [gatewayToken] = useState(saved.token)
  const [accounts, setAccounts] = useState<AnalyticsAccount[]>([])
  const [totals, setTotals] = useState({ views: 0, likes: 0, comments: 0 })
  const [days, setDays] = useState(30)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  async function refresh() {
    if (!gatewayUrl.trim()) {
      setNotice('Configure Gateway API in Social Hub first.')
      return
    }
    setBusy(true)
    try {
      const result = await api.analyticsSummary(gatewayUrl, gatewayToken, days)
      setAccounts(result.accounts)
      setTotals(result.totals)
      setNotice(null)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'Analytics unavailable.')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => { void refresh() }, [days])

  const recentRows = useMemo(() => accounts.flatMap((account) => account.days.slice(-7).map((day) => ({ ...day, account: `${account.platform} · ${account.account_name}` }))).sort((a, b) => b.metric_date.localeCompare(a.metric_date)), [accounts])

  return (
    <div className="analytics-screen">
      <header className="analytics-head">
        <div><button className="btn-ghost" onClick={onBack}>← social hub</button><p className="social-kicker">ISM / analytics</p><h1 className="social-title">MEASURE WHAT IS REAL<span className="amber">.</span></h1><p className="social-sub">Daily account and post metrics from approved provider APIs. Missing data is shown as missing, not as zero.</p></div>
        <div className="analytics-controls"><select value={days} onChange={(event) => setDays(Number(event.target.value))}><option value={7}>7 days</option><option value={30}>30 days</option><option value={90}>90 days</option></select><button className="btn-secondary" onClick={() => void refresh()} disabled={busy}>REFRESH</button></div>
      </header>
      {notice && <div className="social-notice">{notice}</div>}
      <main className="analytics-main">
        <section className="analytics-cards"><div><span>VIEWS</span><b>{numberFormat.format(totals.views)}</b></div><div><span>LIKES</span><b>{numberFormat.format(totals.likes)}</b></div><div><span>COMMENTS</span><b>{numberFormat.format(totals.comments)}</b></div><div><span>ACCOUNTS</span><b>{accounts.length}</b></div></section>
        <section className="analytics-panel"><p className="social-label">ACCOUNT BREAKDOWN</p>{accounts.length === 0 && <p className="social-empty">No connected accounts or no snapshots have been synchronized.</p>}{accounts.map((account) => { const latest = account.days[account.days.length - 1]; return <article className="analytics-account" key={account.account_id}><div><strong>{account.platform} · {account.account_name}</strong><small>{account.data_available ? `last sync ${latest?.fetched_at ?? 'unknown'} · source ${latest?.source}` : 'No approved analytics snapshot yet.'}</small></div><div className="analytics-account-numbers"><span>{account.data_available ? numberFormat.format(account.days.reduce((sum, item) => sum + item.views, 0)) : '—'}<small>views</small></span><span>{account.data_available ? numberFormat.format(account.days.reduce((sum, item) => sum + item.likes, 0)) : '—'}<small>likes</small></span></div></article> })}</section>
        <section className="analytics-panel"><p className="social-label">RECENT DAILY SNAPSHOTS</p>{recentRows.length === 0 && <p className="social-empty">The Gateway has no verified metrics in this period.</p>}{recentRows.map((row) => <div className="analytics-row" key={`${row.account}-${row.metric_date}`}><span><strong>{row.account}</strong><small>{row.metric_date} · {row.source}</small></span><span>{numberFormat.format(row.views)} views · {numberFormat.format(row.likes)} likes · {numberFormat.format(row.comments)} comments</span></div>)}</section>
      </main>
    </div>
  )
}
