import { useEffect, useMemo, useState } from 'react'
import { api, loadProcessingGatewayConfig, type GatewayProviderModel, type GatewayUsageAggregate } from '../api'

interface Props { onBack: () => void }

const numberFormat = new Intl.NumberFormat('en-US')
const moneyFormat = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 6 })

export default function AiUsageDashboard({ onBack }: Props) {
  const saved = loadProcessingGatewayConfig()
  const [gatewayUrl] = useState(saved.url)
  const [gatewayToken] = useState(saved.token)
  const [days, setDays] = useState(30)
  const [aggregates, setAggregates] = useState<GatewayUsageAggregate[]>([])
  const [providers, setProviders] = useState<GatewayProviderModel[]>([])
  const [events, setEvents] = useState(0)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  async function refresh() {
    if (!gatewayUrl.trim()) {
      setNotice('Configure the Processing Gateway URL in Studio first.')
      return
    }
    setBusy(true)
    try {
      const [usage, registry] = await Promise.all([
        api.aiUsageSummary(gatewayUrl, gatewayToken, days),
        api.aiProviderProfiles(gatewayUrl, gatewayToken)
      ])
      setAggregates(usage.aggregates)
      setEvents(usage.events)
      setProviders(registry.providers)
      setNotice(null)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'AI usage is unavailable.')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => { void refresh() }, [days])

  const totals = useMemo(() => aggregates.reduce((sum, row) => ({
    requests: sum.requests + row.requests,
    tokens: sum.tokens + row.total_tokens,
    estimated: sum.estimated + row.estimated_requests,
    cost: sum.cost + row.cost_usd,
    latency: sum.latency + row.average_latency_ms * row.requests
  }), { requests: 0, tokens: 0, estimated: 0, cost: 0, latency: 0 }), [aggregates])

  const averageLatency = totals.requests ? totals.latency / totals.requests : 0

  return (
    <div className="analytics-screen">
      <header className="analytics-head">
        <div><button className="btn-ghost" onClick={onBack}>← studio</button><p className="social-kicker">ISM / AI usage</p><h1 className="social-title">KNOW WHAT AI COSTS<span className="amber">.</span></h1><p className="social-sub">Usage comes from the Gateway provider responses. Missing provider usage is explicitly marked as estimated.</p></div>
        <div className="analytics-controls"><select value={days} onChange={(event) => setDays(Number(event.target.value))}><option value={7}>7 days</option><option value={30}>30 days</option><option value={90}>90 days</option></select><button className="btn-secondary" onClick={() => void refresh()} disabled={busy}>REFRESH</button></div>
      </header>
      {notice && <div className="social-notice">{notice}</div>}
      <main className="analytics-main">
        <section className="analytics-cards"><div><span>REQUESTS</span><b>{numberFormat.format(totals.requests)}</b></div><div><span>TOKENS</span><b>{numberFormat.format(totals.tokens)}</b></div><div><span>COST</span><b>{moneyFormat.format(totals.cost)}</b></div><div><span>AVG LATENCY</span><b>{Math.round(averageLatency)} ms</b></div></section>
        <section className="analytics-panel"><p className="social-label">PROVIDER / MODEL BREAKDOWN</p>{aggregates.length === 0 && <p className="social-empty">No Gateway usage has been recorded in this period.</p>}{aggregates.map((row) => <article className="analytics-account" key={`${row.provider}-${row.model}`}><div><strong>{row.provider} · {row.model}</strong><small>{row.requests} requests · {row.estimated_requests ? `${row.estimated_requests} estimated` : 'usage actual'} · {Math.round(row.average_latency_ms)} ms average</small></div><div className="analytics-account-numbers"><span>{numberFormat.format(row.total_tokens)}<small>tokens</small></span><span>{moneyFormat.format(row.cost_usd)}<small>cost</small></span></div></article>)}</section>
        <section className="analytics-panel"><p className="social-label">PROVIDER REGISTRY</p>{providers.length === 0 && <p className="social-empty">No providers are configured on the Gateway.</p>}{providers.map((provider) => <div className="analytics-row" key={provider.id}><span><strong>{provider.name || provider.id}</strong><small>{provider.type} · {provider.default_model || 'model not set'} · {provider.credential_configured ? 'credential configured' : 'credential missing'}</small></span><span>{provider.input_cost_per_million || provider.output_cost_per_million ? `${moneyFormat.format(provider.input_cost_per_million)} in / ${moneyFormat.format(provider.output_cost_per_million)} out per 1M` : 'pricing not configured'}</span></div>)}</section>
        {totals.estimated > 0 && <p className="social-sub">Estimated records: {numberFormat.format(totals.estimated)}. These are token estimates and must not be treated as provider-billed actuals.</p>}
        <p className="social-sub">Gateway events received: {numberFormat.format(events)}.</p>
      </main>
    </div>
  )
}
