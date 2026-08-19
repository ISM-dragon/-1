import { useState } from 'react'
import { api, loadProcessingGatewayConfig, saveProcessingGatewayConfig, type SourceItem, type SourceJob } from '../api'

interface Props {
  onClose: () => void
}

function formatDuration(value?: number | null) {
  if (!value) return 'duration unknown'
  const seconds = Math.round(value)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

export default function SourceLibrary({ onClose }: Props) {
  const saved = loadProcessingGatewayConfig()
  const [gatewayUrl, setGatewayUrl] = useState(saved.url)
  const [gatewayToken, setGatewayToken] = useState(saved.token)
  const [source, setSource] = useState('')
  const [maxItems, setMaxItems] = useState('0')
  const [preview, setPreview] = useState<SourceItem[]>([])
  const [job, setJob] = useState<SourceJob | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  function maxCount() {
    const parsed = Number.parseInt(maxItems, 10)
    return Number.isFinite(parsed) && parsed >= 0 ? Math.min(parsed, 1000) : 0
  }

  function saveGateway() {
    saveProcessingGatewayConfig({ url: gatewayUrl.trim(), token: gatewayToken.trim() })
  }

  async function inspect() {
    if (!source.trim() || !gatewayUrl.trim()) {
      setNotice('Enter a Gateway URL and a video, channel, or playlist URL.')
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      saveGateway()
      const result = await api.sourceInspect(gatewayUrl, gatewayToken, source.trim(), maxCount())
      setPreview(result.items)
      setNotice(`${result.count} downloadable item(s) found. Review the list before starting the download.`)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'Source inspection failed.')
    } finally {
      setBusy(false)
    }
  }

  async function download() {
    if (!source.trim() || !gatewayUrl.trim()) {
      setNotice('Enter a Gateway URL and a video, channel, or playlist URL.')
      return
    }
    setBusy(true)
    setNotice(null)
    setJob(null)
    try {
      saveGateway()
      const started = await api.sourceDownload(gatewayUrl, gatewayToken, source.trim(), maxCount())
      let current = await api.sourceStatus(gatewayUrl, gatewayToken, started.id)
      setJob(current)
      while (current.status === 'queued' || current.status === 'running') {
        await new Promise((resolve) => window.setTimeout(resolve, 1500))
        current = await api.sourceStatus(gatewayUrl, gatewayToken, started.id)
        setJob(current)
      }
      if (current.status === 'failed') throw new Error(current.error || 'Source download failed.')
      setPreview(current.items ?? [])
      setNotice(`${current.completed} file(s) are ready in the Gateway library.`)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'Source download failed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="source-library">
      <div className="source-library-head">
        <div>
          <p className="opt-label">v0.6 SOURCE LIBRARY</p>
          <h1 className="review-title">Download a video or a full channel.</h1>
          <p className="gateway-help">Use a public video, channel, or playlist URL. Set 0 for all available entries, or a smaller number to preview and limit the batch.</p>
        </div>
        <button className="btn-ghost" onClick={onClose}>← studio</button>
      </div>

      <section className="source-panel">
        <label className="source-field">Gateway URL<input value={gatewayUrl} onChange={(event) => setGatewayUrl(event.target.value)} placeholder="https://your-gateway.example" /></label>
        <label className="source-field">Gateway token<input type="password" value={gatewayToken} onChange={(event) => setGatewayToken(event.target.value)} placeholder="optional" /></label>
        <label className="source-field source-wide">Video, channel, or playlist URL<input value={source} onChange={(event) => setSource(event.target.value)} placeholder="https://www.youtube.com/@channel or /playlist?list=..." /></label>
        <label className="source-field">Max items<input value={maxItems} onChange={(event) => setMaxItems(event.target.value)} inputMode="numeric" /></label>
        <div className="source-actions">
          <button className="btn-secondary" onClick={inspect} disabled={busy}>INSPECT</button>
          <button className="btn-primary" onClick={download} disabled={busy || !source.trim()}>DOWNLOAD BATCH</button>
        </div>
      </section>

      {notice && <section className="source-notice">{notice}</section>}
      {job && <section className="source-job mono">{job.status.toUpperCase()} · {job.completed}/{job.total || '…'} · {job.message ?? ''}</section>}

      {preview.length > 0 && (
        <section className="source-results">
          {preview.map((item) => (
            <article className="source-card" key={`${item.id}-${item.index}`}>
              {item.thumbnail && <img src={item.thumbnail} alt="" />}
              <div className="source-card-body">
                <strong>{item.title}</strong>
                <span className="mono">{formatDuration(item.duration)}</span>
                {item.media_url ? <a href={item.media_url} target="_blank" rel="noreferrer">OPEN / DOWNLOAD MP4</a> : <span className="source-dim">preview only</span>}
              </div>
            </article>
          ))}
        </section>
      )}
    </div>
  )
}
