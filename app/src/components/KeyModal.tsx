import { useEffect, useState } from 'react'
import { invoke } from '@tauri-apps/api/core'

/** Post-onboarding key management — the onboarding-only input was a gap. */

interface Props {
  onClose: () => void
}

function PexelsField() {
  const [key, setKey] = useState('')
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  return (
    <div className="ig-form">
      <input
        placeholder="Pexels API key (free — pexels.com/api)"
        type="password"
        value={key}
        onChange={(e) => setKey(e.target.value)}
        className="mono"
      />
      <button
        className="btn-secondary"
        disabled={!key.trim() || saving}
        onClick={async () => {
          setSaving(true)
          setError(null)
          try {
            await invoke('save_pexels_key', { key: key.trim() })
            setSaved(true)
          } catch (reason) {
            setSaved(false)
            setError(reason instanceof Error ? reason.message : String(reason))
          } finally {
            setSaving(false)
          }
        }}
      >
        {saving ? 'saving…' : saved ? 'saved ✓' : 'save'}
      </button>
      {error && <p className="ob-error">{error}</p>}
    </div>
  )
}

export default function KeyModal({ onClose }: Props) {
  const [key, setKey] = useState('')
  const [hasKey, setHasKey] = useState<boolean | null>(null)
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    invoke<{ has_gemini_key: boolean }>('get_setup_state').then((s) =>
      setHasKey(s.has_gemini_key)
    ).catch((reason) => setError(reason instanceof Error ? reason.message : String(reason)))
  }, [])

  async function save() {
    if (!key.trim() || saving) return
    setSaving(true)
    setError(null)
    try {
      await invoke('save_gemini_key', { key: key.trim() })
      setSaved(true)
      setHasKey(true)
    } catch (reason) {
      setSaved(false)
      setError(reason instanceof Error ? reason.message : String(reason))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-scrim" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <p className="audit-kicker">THE BRAIN</p>
          <button className="btn-ghost" onClick={onClose}>close ✕</button>
        </header>
        <p className="ig-intro">
          Gemini scores your moments at full quality (~<span className="mono">$0.15</span>/hr
          of source). The key lives in <span className="mono">~/.publikclip/secrets.json</span>,
          chmod 600, and never goes anywhere but Google.{' '}
          {hasKey && <strong>A key is currently saved{saved ? ' — updated ✓' : ''}.</strong>}
        </p>
        <div className="ig-form">
          <input
            placeholder="AIza… (aistudio.google.com → Get API key)"
            type="password"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && save()}
            className="mono"
          />
          <button className="btn-primary" onClick={save} disabled={!key.trim() || saving}>
            {saving ? 'SAVING…' : saved ? 'SAVED ✓' : 'SAVE KEY'}
          </button>
        </div>
        {error && <p className="ob-error">{error}</p>}
        <p className="audit-label" style={{ marginTop: 22 }}>PEXELS (STOCK VISUALS)</p>
        <PexelsField />
        <p className="ig-message mono">
          Applies to new runs; a job mid-flight keeps the brain it started with.
        </p>
      </div>
    </div>
  )
}
