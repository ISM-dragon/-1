import { useEffect, useState } from 'react'
import { api } from '../api'

/**
 * Three beats: what this is → pick the brain (Gemini key or local Ollama) →
 * go. The optional Instagram feedback module gets its own guided flow later
 * (Settings → Connect Instagram), so first-run stays under a minute.
 */

interface Props {
  onDone: () => Promise<void>
  isAndroid?: boolean
}

export default function Onboarding({ onDone, isAndroid = false }: Props) {
  const [step, setStep] = useState(0)
  const [key, setKey] = useState('')
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [finishing, setFinishing] = useState(false)
  const [finishError, setFinishError] = useState<string | null>(null)
  const [ollama, setOllama] = useState<{ running: boolean; models: string[] } | null>(null)

  useEffect(() => {
    api.checkOllama().then(setOllama).catch(() => setOllama({ running: false, models: [] }))
  }, [])

  async function saveKey() {
    const trimmed = key.trim()
    if (!trimmed || saving) return
    setSaving(true)
    setSaveError(null)
    try {
      const ok = await api.saveGeminiKey(trimmed)
      if (!ok) throw new Error('The API key was not saved.')
      setSaved(true)
    } catch (error) {
      setSaved(false)
      setSaveError(error instanceof Error ? error.message : 'Could not save the API key.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="onboarding">
      <div className="grain" />
      {step === 0 && (
        <section className="ob-step" key="s0">
          <p className="ob-kicker">ISM</p>
          <h1 className="ob-title">
            THE CLIPPER
            <br />
            THAT SHOWS
            <br />
            ITS WORK<span className="amber">.</span>
          </h1>
          <p className="ob-body">
            Long video in, vertical clips out. Desktop runs the Python Pipeline locally;
            Android sends only a source URL to your personal Gateway. Gemini credentials
            stay with the environment that runs the Pipeline, and every score comes with
            the full audit trail of how it was made.
          </p>
          <button className="btn-primary" onClick={() => setStep(1)}>
            Set it up
          </button>
        </section>
      )}
      {step === 1 && (
        <section className="ob-step" key="s1">
          <p className="ob-kicker">01 / the scoring brain</p>
          <h2 className="ob-h2">Pick how moments get judged</h2>
          <div className="ob-cards">
            <div className={`ob-card ${saved ? 'done' : ''}`}>
              <h3>Local desktop Gemini key <span className="chip chip-amber">recommended</span></h3>
              <p>
                Bring your own key (aistudio.google.com). Costs roughly{' '}
                <span className="mono">$0.15</span> per hour of source video. Best
                humor and shock judgment.
              </p>
              <div className="ob-key-row">
                <input
                  type="password"
                  placeholder="AIza…"
                  value={key}
                  onChange={(e) => setKey(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && saveKey()}
                />
                <button className="btn-secondary" onClick={saveKey} disabled={!key.trim() || saving}>
                  {saving ? 'Saving…' : saved ? 'Saved ✓' : 'Save'}
                </button>
              </div>
              {saveError && <p className="ob-error">{saveError}</p>}
              {saved && <p className="ob-success">Your local desktop key is stored locally. Android uses the Gateway key instead.</p>}
            </div>
            <div className={`ob-card ${ollama?.running ? '' : 'dim'}`}>
              <h3>
                Ollama <span className={`led ${ollama?.running ? 'led-on' : 'led-off'}`} />
              </h3>
              <p>
                {ollama === null
                  ? 'Checking…'
                  : ollama.running
                    ? `Running locally (${ollama.models.filter((m) => !m.includes('embed')).slice(0, 2).join(', ') || 'no chat models'}). Zero cost, fully offline — scores are labeled "local estimate" because small models judge humor less reliably.`
                    : 'Not detected. Install ollama.com and pull a model (e.g. llama3.1:8b) to run fully offline.'}
              </p>
            </div>
          </div>
          <p className="ob-fine">
            You can switch per-run. On Android, configure the personal Processing Gateway
            in Studio; its server-side GEMINI_API_KEY is never sent from the phone.
          </p>
          <button
            className="btn-primary"
            onClick={() => setStep(2)}
            disabled={!isAndroid && !saved && !ollama?.running}
          >
            Continue
          </button>
        </section>
      )}
      {step === 2 && (
        <section className="ob-step" key="s2">
          <p className="ob-kicker">02 / one honest warning</p>
          <h2 className="ob-h2">First run downloads the models</h2>
          <p className="ob-body">
            {isAndroid ? 'The personal Gateway downloads the speech and audio models once on the processing server. Android keeps the job remote and polls its honest stage progress.' : <>About <span className="mono">2.5 GB</span> of open speech and audio models, fetched once into <span className="mono">~/.publikclip</span>. An hour-long podcast then takes a while on-device — the progress bar never lies to you, and every stage checkpoints, so you can quit and resume anytime.</>}
          </p>
          <button className="btn-primary" disabled={finishing} onClick={async () => {
            setFinishing(true)
            setFinishError(null)
            try {
              await onDone()
            } catch (error) {
              setFinishError(error instanceof Error ? error.message : 'Could not finish setup.')
              setFinishing(false)
            }
          }}>
            {finishing ? 'Opening…' : 'Open the studio'}
          </button>
          {finishError && <p className="ob-error">{finishError}</p>}
        </section>
      )}
    </div>
  )
}
