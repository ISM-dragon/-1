import { useEffect, useState } from 'react'
import { invoke } from '@tauri-apps/api/core'

/**
 * The Instagram loop, guided. Your OWN Meta app — publikclip never sees
 * your account through anyone's server but Meta's. Steps verified against
 * Meta's Standard Access docs (no App Review needed for self-serving apps).
 */

interface Props {
  onClose: () => void
}

const STEPS: [string, string][] = [
  ['Convert your Instagram to a professional account', 'Instagram app → Settings → Account type → switch to Creator (or Business). Free, reversible.'],
  ['Open developers.facebook.com', 'Log in with the Facebook account you use — it must have two-factor auth enabled.'],
  ['Create App', 'My Apps → Create App. Pick "Other" use case → type "Business". You can skip connecting a business portfolio.'],
  ['Add the Instagram product', 'On the app dashboard, find "Instagram" → Set up. Choose "API setup with Instagram login".'],
  ['Set the redirect URI', 'In Instagram → API setup → Business login settings, add: http://localhost:8137/callback'],
  ['Copy App ID + App Secret', 'Instagram → API setup shows the Instagram App ID and App Secret. Paste them below.'],
  ['Connect', 'Hit connect — your browser opens Meta’s consent screen. Approve, and you’re done. The app stays in Development Mode forever; that’s the point.']
]

export default function IgModal({ onClose }: Props) {
  const [status, setStatus] = useState<{ connected: boolean; username?: string } | null>(null)
  const [appId, setAppId] = useState('')
  const [secret, setSecret] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    invoke<{ connected: boolean; username?: string }>('ig_status').then(setStatus)
  }, [])

  async function connect() {
    setBusy(true)
    setMessage('Finish the approval in your browser…')
    try {
      const result = await invoke<string>('ig_connect', { appId, appSecret: secret })
      setMessage(result)
      const s = await invoke<{ connected: boolean; username?: string }>('ig_status')
      setStatus(s)
    } catch (err) {
      setMessage(String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-scrim" onClick={onClose}>
      <div className="modal ig-modal" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <p className="audit-kicker">THE FEEDBACK LOOP</p>
          <button className="btn-ghost" onClick={onClose}>close ✕</button>
        </header>

        {status?.connected ? (
          <div className="ig-connected">
            <span className="led led-on" />
            <div>
              <p className="ig-user">Connected as @{status.username}</p>
              <p className="ig-hint">
                You're in. Post exported clips as Reels, and the loop screen
                syncs them back — thumbnails, views, and the score-vs-reality
                chart all live there. Syncing also runs by itself while the
                app is open.
              </p>
            </div>
          </div>
        ) : (
          <>
            <p className="ig-intro">
              Optional. Connect your Instagram through <em>your own</em> Meta app and
              publikclip calibrates its virality score against how your clips actually
              perform. Skip it and everything still works — you just keep the
              uncalibrated score. ~10 minutes, once.
            </p>
            <ol className="ig-steps">
              {STEPS.map(([title, body], i) => (
                <li key={i} style={{ animationDelay: `${i * 45}ms` }}>
                  <span className="ig-step-num mono">{String(i + 1).padStart(2, '0')}</span>
                  <div>
                    <p className="ig-step-title">{title}</p>
                    <p className="ig-step-body">{body}</p>
                  </div>
                </li>
              ))}
            </ol>
            <div className="ig-form">
              <input
                placeholder="Instagram App ID"
                value={appId}
                onChange={(e) => setAppId(e.target.value)}
                className="mono"
              />
              <input
                placeholder="App Secret"
                type="password"
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
                className="mono"
              />
              <button
                className="btn-primary"
                onClick={connect}
                disabled={busy || !appId.trim() || !secret.trim()}
              >
                {busy ? 'WAITING FOR BROWSER…' : 'CONNECT'}
              </button>
            </div>
          </>
        )}
        {message && <p className="ig-message mono">{message}</p>}
      </div>
    </div>
  )
}
