import { useEffect, useState } from 'react'
import type { JobSummary } from '../types'
import KeyModal from './KeyModal'
import { api, loadProcessingGatewayConfig, saveProcessingGatewayConfig, type GeminiDiagnostic, type ProcessingCapabilities } from '../api'

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
  onOpenProviders: () => void
  onOpenJob: (id: string) => void
  onResume: (id: string, llm?: string) => void
  isAndroid: boolean
}

export default function Studio({ jobs, running, stages, error, onRun, onOpenLoop, onOpenSocial, onOpenSources, onOpenProviders, onOpenJob, onResume, isAndroid }: Props) {
  const [source, setSource] = useState('')
  const [llm, setLlm] = useState('gemini')
  const [captions, setCaptions] = useState('classic')
  const [showKey, setShowKey] = useState(false)
  const [processingGatewayUrl, setProcessingGatewayUrl] = useState(() => loadProcessingGatewayConfig().url)
  const [processingGatewayToken, setProcessingGatewayToken] = useState(() => loadProcessingGatewayConfig().token)
  const [gatewaySaved, setGatewaySaved] = useState(false)
  const [engineState, setEngineState] = useState<'idle' | 'checking' | 'connected' | 'failed'>('idle')
  const [engineMessage, setEngineMessage] = useState('Not tested yet.')
  const [engineCapabilities, setEngineCapabilities] = useState<ProcessingCapabilities | null>(null)
  const [geminiDiagnostic, setGeminiDiagnostic] = useState<GeminiDiagnostic | null>(null)

  const gatewayReady = !isAndroid || processingGatewayUrl.trim().length > 0

  async function testEngine() {
    if (!processingGatewayUrl.trim()) {
      setEngineState('failed')
      setEngineMessage('Enter the Gateway URL first.')
      return
    }
    setEngineState('checking')
    setEngineMessage('Checking Gateway, Pipeline, FFmpeg, and Gemini…')
    setEngineCapabilities(null)
    setGeminiDiagnostic(null)
    persistGateway()
    try {
      const health = await api.gatewayHealth(processingGatewayUrl, processingGatewayToken)
      const capabilities = await api.processingCapabilities(processingGatewayUrl, processingGatewayToken)
      const pipeline = await api.pipelineDiagnostic(processingGatewayUrl, processingGatewayToken)
      const gemini = llm === 'gemini' ? await api.geminiDiagnostic(processingGatewayUrl, processingGatewayToken) : null
      const merged = { ...capabilities, ...pipeline }
      setEngineCapabilities(merged)
      setGeminiDiagnostic(gemini)
      const ready = health.ok && merged.ready !== false && merged.pipeline !== false && merged.ffmpeg_usable !== false && (llm !== 'gemini' || gemini?.reachable)
      setEngineState(ready ? 'connected' : 'failed')
      setEngineMessage(ready ? 'READY — remote processing can start.' : `Not ready: ${gemini?.error_code || (!merged.pipeline ? 'PIPELINE_UNAVAILABLE' : !merged.ffmpeg_usable ? 'FFMPEG_UNAVAILABLE' : 'GATEWAY_DEGRADED')}`)
    } catch (error) {
      setEngineState('failed')
      setEngineMessage(error instanceof Error ? error.message : 'Gateway test failed.')
    }
  }

  useEffect(() => {
    if (isAndroid && processingGatewayUrl.trim()) void testEngine()
  }, [])

  function persistGateway(url = processingGatewayUrl, token = processingGatewayToken) {
    saveProcessingGatewayConfig({ url: url.trim(), token: token.trim() })
  }

  function saveGateway() {
    persistGateway()
    setGatewaySaved(true)
    window.setTimeout(() => setGatewaySaved(false), 2200)
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
            ◈ gemini key
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
          <button className="btn-ghost" onClick={onOpenProviders}>
            ◇ ai providers
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
              <p className="gateway-help">The Android build sends the YouTube URL to your private Gateway, which runs the Python pipeline and returns real clips. Use HTTPS publicly or a LAN address such as http://192.168.1.10:8787 in Debug.</p>
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
                placeholder="Gateway token (optional)"
                disabled={running}
              />
              <div className="gateway-actions">
                <button className="btn-secondary" onClick={saveGateway} disabled={running || !processingGatewayUrl.trim()}>
                  {gatewaySaved ? 'GATEWAY SAVED ✓' : 'SAVE GATEWAY'}
                </button>
                <button className="btn-secondary" onClick={() => void testEngine()} disabled={running || engineState === 'checking' || !processingGatewayUrl.trim()}>
                  {engineState === 'checking' ? 'TESTING…' : 'TEST SYSTEM'}
                </button>
              </div>
              <div className={`engine-status engine-${engineState}`}>
                <strong>PROCESSING ENGINE: {engineState === 'connected' ? 'CONNECTED' : engineState === 'checking' ? 'CHECKING' : engineState === 'failed' ? 'DISCONNECTED' : 'NOT TESTED'}</strong>
                <span>{engineMessage}</span>
                {engineCapabilities && <small>Pipeline {engineCapabilities.pipeline ? '✓' : '✕'} · FFmpeg {engineCapabilities.ffmpeg ? '✓' : '✕'} · Storage {engineCapabilities.storage ? '✓' : '✕'} · Gemini {geminiDiagnostic ? (geminiDiagnostic.reachable ? '✓' : `✕ ${geminiDiagnostic.error_code ?? ''}`) : engineCapabilities.gemini_configured ? 'configured' : 'not configured'}</small>}
              </div>
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
