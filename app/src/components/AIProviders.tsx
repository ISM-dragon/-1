import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, loadProcessingGatewayConfig, type AIProviderSnapshot } from '../api'

type Props = { onBack: () => void }

type FormState = {
  id: string
  name: string
  base_url: string
  credential_ref: string
  api_key: string
  model_id: string
}

const initialForm: FormState = { id: '', name: '', base_url: '', credential_ref: '', api_key: '', model_id: '' }

function healthClass(state: string) {
  if (state === 'READY') return 'health-ready'
  if (state === 'NOT_CONFIGURED') return 'health-muted'
  if (state === 'AUTH_ERROR' || state === 'RATE_LIMITED') return 'health-danger'
  return 'health-warning'
}

export default function AIProviders({ onBack }: Props) {
  const [snapshots, setSnapshots] = useState<AIProviderSnapshot[]>([])
  const [form, setForm] = useState<FormState>(initialForm)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const gateway = loadProcessingGatewayConfig()

  const load = useCallback(async () => {
    if (!gateway.url) {
      setError('Set the Processing Gateway URL in Studio before managing AI providers.')
      return
    }
    setBusy(true)
    setError('')
    try {
      const data = await api.aiProviders(gateway.url, gateway.token)
      setSnapshots(data.providers)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }, [gateway.token, gateway.url])

  useEffect(() => { void load() }, [load])

  async function addProvider(event: FormEvent) {
    event.preventDefault()
    if (!gateway.url) return
    setBusy(true)
    setError('')
    setMessage('')
    try {
      await api.createAIProvider(gateway.url, gateway.token, {
        id: form.id,
        name: form.name,
        type: 'openai_compatible',
        base_url: form.base_url,
        credential_ref: form.credential_ref || undefined,
        api_key: form.api_key || undefined,
        capabilities: ['json', 'structured_output'],
        models: form.model_id ? [{ model_id: form.model_id, display_name: form.model_id, capabilities: ['json', 'structured_output'], supports_structured_output: true }] : []
      })
      setForm(initialForm)
      setMessage('Provider registered. The API key is stored by Gateway and is not returned to the app.')
      await load()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  async function check(providerId: string) {
    if (!gateway.url) return
    setBusy(true)
    setError('')
    try {
      const updated = await api.aiProviderHealth(gateway.url, gateway.token, providerId)
      setSnapshots((current) => current.map((item) => item.provider.id === providerId ? updated : item))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  async function remove(providerId: string) {
    if (!gateway.url || !window.confirm(`Remove provider ${providerId}?`)) return
    setBusy(true)
    setError('')
    try {
      await api.deleteAIProvider(gateway.url, gateway.token, providerId)
      setSnapshots((current) => current.filter((item) => item.provider.id !== providerId))
      setMessage('Custom provider removed. Built-in providers can only be disabled.')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="screen providers-screen">
      <header className="screen-head">
        <div><span className="eyebrow">ISM / AI CONTROL</span><h1>Provider Registry</h1><p className="muted">Health, models, and owner-supplied credentials.</p></div>
        <button className="btn-ghost" onClick={onBack}>BACK</button>
      </header>
      <div className="notice-panel">
        <strong>Gateway-owned secrets.</strong> API keys are sent only to the configured Gateway and never returned in provider metadata. Use HTTPS for a remote Gateway; local HTTP is intended only for a private LAN.
      </div>
      {error && <div className="error-banner">{error}</div>}
      {message && <div className="success-banner">{message}</div>}
      <section className="provider-layout">
        <div className="panel provider-list-panel">
          <div className="panel-head"><h2>Configured providers</h2><button className="btn-ghost" onClick={() => void load()} disabled={busy}>REFRESH</button></div>
          {snapshots.map(({ provider, health }) => (
            <article className="provider-card" key={provider.id}>
              <div className="provider-card-head"><div><strong>{provider.name}</strong><small>{provider.id} · {provider.type}</small></div><span className={`health-pill ${healthClass(health.state)}`}>{health.state}</span></div>
              <div className="provider-meta"><span>Config {health.configured ? 'yes' : 'no'}</span><span>Network {health.reachable ? 'yes' : 'no'}</span><span>Auth {health.authenticated === null || health.authenticated === undefined ? '—' : health.authenticated ? 'yes' : 'no'}</span><span>Models {provider.models.length}</span></div>
              {health.error && <small className="muted">{health.error}</small>}
              <div className="provider-actions"><button className="btn-ghost" onClick={() => void check(provider.id)} disabled={busy}>CHECK HEALTH</button>{!['gemini', 'openai', 'anthropic', 'openrouter', 'ollama'].includes(provider.id) && <button className="btn-danger" onClick={() => void remove(provider.id)} disabled={busy}>REMOVE</button>}</div>
            </article>
          ))}
          {!snapshots.length && !busy && <p className="muted">No providers returned. Check the Gateway URL and token.</p>}
        </div>
        <form className="panel provider-form" onSubmit={addProvider}>
          <h2>Add OpenAI-compatible provider</h2>
          <p className="muted">Custom providers are stored as data. No source-code change is needed for a new compatible endpoint.</p>
          <label className="field"><span>ID</span><input required pattern="[a-z][a-z0-9_-]*" value={form.id} onChange={(event) => setForm({ ...form, id: event.target.value })} placeholder="local-vllm" /></label>
          <label className="field"><span>Name</span><input required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} placeholder="Local vLLM" /></label>
          <label className="field"><span>Base URL</span><input required type="url" value={form.base_url} onChange={(event) => setForm({ ...form, base_url: event.target.value })} placeholder="https://example.test/v1" /></label>
          <label className="field"><span>Credential reference</span><input value={form.credential_ref} onChange={(event) => setForm({ ...form, credential_ref: event.target.value })} placeholder="CUSTOM_LOCAL_VLLM_KEY" /></label>
          <label className="field"><span>API key <small>(sent once to Gateway)</small></span><input type="password" autoComplete="off" value={form.api_key} onChange={(event) => setForm({ ...form, api_key: event.target.value })} /></label>
          <label className="field"><span>Default model</span><input value={form.model_id} onChange={(event) => setForm({ ...form, model_id: event.target.value })} placeholder="model-name" /></label>
          <button className="btn-primary" type="submit" disabled={busy || !gateway.url}>{busy ? 'SAVING…' : 'REGISTER PROVIDER'}</button>
        </form>
      </section>
    </main>
  )
}
