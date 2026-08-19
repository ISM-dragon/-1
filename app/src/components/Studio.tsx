import { useState } from 'react'
import type { JobSummary } from '../types'
import KeyModal from './KeyModal'
import { api, loadProcessingGatewayConfig, saveProcessingGatewayConfig } from '../api'
import type { GeminiDiagnostic, ProcessingCapabilities } from '../api'

const STAGE_ORDER = [
  'ingest', 'asr', 'diarize', 'events', 'candidates', 'score', 'camera', 'render'
]

const STAGE_LABELS: Record<string, string> = {
  ingest: 'INGEST',
  asr: 'TRANSCRIBE',
  diarize: 'SPEAKERS',
  events: 'LISTEN',
  candidates: 'SCAN',
  score: 'JUDGE',
  camera: 'DIRECT',
  render: 'RENDER'
}

const CAPTION_PRESETS = ['classic', 'beast', 'hormozi', 'minimal', 'karaoke-pop']

interface Props {
  jobs: JobSummary[]
  running: boolean
  stages: Record<string, { fraction: number; message: string }>
  error: string | null
  onRun: (source: string, llm: string, captions: string) => void
  onOpenLoop: () => void
  onOpenSocial: () => void
  onOpenSources: () => void
  onOpenJob: (id: string) => void
  onResume: (id: string, llm?: string) => void
  isAndroid: boolean
}

export default function Studio({ jobs, running, stages, error, onRun, onOpenLoop, onOpenSocial, onOpenSources, onOpenJob, onResume, isAndroid }: Props) {
  const [source, setSource] = useState('')
  const [llm, setLlm] = useState('gemini')
  const [captions, setCaptions] = useState('classic')
  const [showKey, setShowKey] = useState(false)
  const [processingGatewayUrl, setProcessingGatewayUrl] = useState(() => loadProcessingGatewayConfig().url)
  const [processingGatewayToken, setProcessingGatewayToken] = useState(() => loadProcessingGatewayConfig().token)
  const [gatewaySaved, setGatewaySaved] = useState(false)
  const [engine, setEngine] = useState<{ gateway: 'idle' | 'ready' | 'failed'; pipeline: 'idle' | 'ready' | 'failed'; gemini: 'idle' | 'ready' | 'failed'; ffmpeg: 'idle' | 'ready' | 'failed'; message: string }>({ gateway: 'idle', pipeline: 'idle', gemini: 'idle', ffmpeg: 'idle', message: 'Run TEST SYSTEM before CUT IT.' })
  const [testingEngine, setTestingEngine] = useState(false)

  const gatewayReady = !isAndroid || processingGatewayUrl.trim().length > 0

  function persistGateway(url = processingGatewayUrl, token = processingGatewayToken) {
    saveProcessingGatewayConfig({ url: url.trim(), token: token.trim() })
  }

  function saveGateway() {
    persistGateway()
    setGatewaySaved(true)
    window.setTimeout(() => setGatewaySaved(false), 2200)
  }

  async function testSystem() {
    const gateway = loadProcessingGatewayConfig()
    if (!gateway.url.trim()) {
      setEngine({ gateway: 'failed', pipeline: 'idle', gemini: 'idle', ffmpeg: 'idle', message: 'Enter the personal Gateway URL first.' })
      return
    }
    setTestingEngine(true)
    setEngine({ gateway: 'idle', pipeline: 'idle', gemini: 'idle', ffmpeg: 'idle', message: 'Checking Gateway…' })
    try {
      await api.health(gateway.url, gateway.token)
      const capabilities: ProcessingCapabilities = await api.processingCapabilities(gateway.url, gateway.token)
      let diagnostic: GeminiDiagnostic | null = null
      if (llm === 'gemini') diagnostic = await api.geminiDiagnostic(gateway.url, gateway.token)
      const geminiReady = llm !== 'gemini' || diagnostic?.status === 'ready'
      const gatewayState = { gateway: 'ready' as const, pipeline: capabilities.pipeline ? 'ready' as const : 'failed' as const, gemini: geminiReady ? 'ready' as const : 'failed' as const, ffmpeg: capabilities.ffmpeg ? 'ready' as const : 'failed' as const, message: geminiReady && capabilities.pipeline && capabilities.ffmpeg ? 'Processing engine is ready.' : (diagnostic?.message ?? capabilities.details?.pipeline?.message ?? capabilities.details?.ffmpeg?.message ?? 'One or more processing services are not ready.') }
      setEngine(gatewayState)
    } catch (error) {
      setEngine({ gateway: 'failed', pipeline: 'failed', gemini: 'failed', ffmpeg: 'failed', message: error instanceof Error ? error.message : 'Gateway could not be reached.' })
    } finally {
      setTestingEngine(false)
    }
  }

  return (
    <div className="studio">
      <div className="grain" />
      {showKey && <KeyModal onClose={() => setShowKey(false)} />}
      <aside className="rail">
        <header className="rail-brand">
          <span className="rail-logo">ISM</span>
          <span className="rail-sub">the clipper that shows its work</span>
        </header>
        <div className="rail-jobs">
          <p className="rail-label">SESSIONS</p>
          {jobs.length === 0 && <p className="rail-empty">nothing yet</p>}
          {jobs.map((job) => (
            <button
              key={job.id}
              className={`rail-job ${job.rendered ? '' : 'partial'}`}
              onClick={() => (job.rendered ? onOpenJob(job.id) : onResume(job.id))}
              disabled={running}
              title={job.rendered ? 'open results' : 'resume from checkpoint'}
            >
              <span className={`led ${job.rendered ? 'led-on' : 'led-half'}`} />
              <span className="rail-job-title">{job.title ?? job.id}</span>
              <span className="rail-job-hint">{job.rendered ? 'open' : 'resume'}</span>
            </button>
          ))}
        </div>
        <footer className="rail-foot">
          <button className="btn-ghost" onClick={() => setShowKey(true)}>
            ◈ local gemini key
          </button>
          <button className="btn-ghost" onClick={onOpenLoop}>
            ⟳ instagram loop
          </button>
          <button className="btn-ghost" onClick={onOpenSocial}>
            ◎ social hub
          </button>
          <button className="btn-ghost" onClick={onOpenSources}>
            ▣ source library
          </button>
        </footer>
      </aside>

      <main className="stage-area">
        <section className="input-block">
          <h1 className="input-heading">
            FEED IT<span className="amber"> AN HOUR.</span>
          </h1>
          <div className="input-row">
            <input
              value={source}
              onChange={(e) => setSource(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && source.trim() && !running && gatewayReady && onRun(source.trim(), llm, captions)}
              placeholder="YouTube URL or a path to a video file"
              disabled={running}
            />
            <button
              className="btn-primary"
              onClick={() => onRun(source.trim(), llm, captions)}
              disabled={running || !source.trim() || !gatewayReady}
            >
              {running ? 'WORKING' : isAndroid && !gatewayReady ? 'SET GATEWAY FIRST' : 'CUT IT'}
            </button>
          </div>
          {isAndroid && (
            <div className="remote-gateway-block">
              <p className="opt-label">ANDROID PROCESSING GATEWAY</p>
              <p className="gateway-help">Android sends only the source URL and Gateway token. The personal Gateway stores GEMINI_API_KEY, runs Python/FFmpeg, and returns safe clip URLs.</p>
              <input
                value={processingGatewayUrl}
                onChange={(e) => { setProcessingGatewayUrl(e.target.value); persistGateway(e.target.value, processingGatewayToken) }}
                placeholder="https://your-gateway.example"
                disabled={running}
              />
              <input
                type="password"
                value={processingGatewayToken}
                onChange={(e) => { setProcessingGatewayToken(e.target.value); persistGateway(processingGatewayUrl, e.target.value) }}
                placeholder="Gateway token (required for remote use)"
                disabled={running}
              />
              <button className="btn-secondary" onClick={saveGateway} disabled={running || !processingGatewayUrl.trim()}>
                {gatewaySaved ? 'GATEWAY SAVED ✓' : 'SAVE GATEWAY'}
              </button>
              <section className="processing-engine-card">
                <div className="processing-engine-head"><span className="opt-label">PROCESSING ENGINE</span><button className="btn-secondary" onClick={testSystem} disabled={testingEngine || running || !processingGatewayUrl.trim()}>{testingEngine ? 'TESTING…' : 'TEST SYSTEM'}</button></div>
                <div className="engine-grid">
                  {([['Gateway', engine.gateway], ['Pipeline', engine.pipeline], ['Gemini', engine.gemini], ['FFmpeg', engine.ffmpeg]] as const).map(([label, state]) => <div className="engine-row" key={label}><span>{label}</span><strong className={state === 'ready' ? 'engine-ready' : state === 'failed' ? 'engine-failed' : 'engine-idle'}>{state === 'ready' ? '✓ READY' : state === 'failed' ? '✕ FAILED' : '○ CHECK'}</strong></div>)}
                </div>
                <p className="gateway-help">{engine.message}</p>
              </section>
            </div>
          )}
          <div className="run-options">
            <div className="opt-group">
              <span className="opt-label">brain</span>
              {['gemini', 'ollama'].map((mode) => (
                <button
                  key={mode}
                  className={`opt ${llm === mode ? 'opt-on' : ''}`}
                  onClick={() => setLlm(mode)}
                  disabled={running}
                >
                  {mode}
                </button>
              ))}
            </div>
            <div className="opt-group">
              <span className="opt-label">captions</span>
              {CAPTION_PRESETS.map((preset) => (
                <button
                  key={preset}
                  className={`opt ${captions === preset ? 'opt-on' : ''}`}
                  onClick={() => setCaptions(preset)}
                  disabled={running}
                >
                  {preset}
                </button>
              ))}
            </div>
          </div>
        </section>

        {(running || Object.keys(stages).length > 0) && (
          <section className="deck">
            {STAGE_ORDER.filter((s) => stages[s] || running).map((name, i) => {
              const st = stages[name]
              const state = !st ? 'idle' : st.fraction >= 1 ? 'done' : 'live'
              return (
                <div className={`deck-row ${state}`} key={name} style={{ animationDelay: `${i * 40}ms` }}>
                  <span className="deck-name mono">{STAGE_LABELS[name] ?? name.toUpperCase()}</span>
                  <div className="deck-bar">
                    <div
                      className={`deck-fill ${st && st.fraction < 0 ? 'indeterminate' : ''}`}
                      style={st && st.fraction >= 0 ? { width: `${Math.min(100, st.fraction * 100)}%` } : undefined}
                    />
                  </div>
                  <span className="deck-msg">{st?.message ?? ''}</span>
                </div>
              )
            })}
          </section>
        )}

        {error && (
          <section className="error-block">
            <span className="led led-err" />
            {error}
          </section>
        )}
      </main>
    </div>
  )
}
