import { useEffect, useMemo, useState } from 'react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { api } from '../api'

type Platform = 'instagram' | 'facebook' | 'tiktok' | 'youtube' | 'x'
type PostStatus = 'draft' | 'awaiting_approval' | 'scheduled' | 'published' | 'failed'

interface SocialPost {
  id: string
  platform: Platform
  account: string
  mediaUrl: string
  title: string
  caption: string
  description: string
  hashtags: string
  keywords: string
  scheduledAt: string
  status: PostStatus
  error?: string
}

interface Props { onBack: () => void }

const PLATFORMS: { id: Platform; label: string; hint: string }[] = [
  { id: 'instagram', label: 'Instagram', hint: 'Business / Creator' },
  { id: 'facebook', label: 'Facebook', hint: 'Page / Reels' },
  { id: 'tiktok', label: 'TikTok', hint: 'Direct Post / Draft' },
  { id: 'youtube', label: 'YouTube', hint: 'Video / Short' },
  { id: 'x', label: 'X', hint: 'Video post' }
]

const STORAGE_KEY = 'publikclip.social-hub.queue.v1'
const DEFAULT_FORM = {
  platform: 'instagram' as Platform,
  account: '',
  mediaUrl: '',
  title: '',
  caption: '',
  description: '',
  hashtags: '',
  keywords: '',
  scheduledAt: ''
}

function loadQueue(): SocialPost[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as SocialPost[]) : []
  } catch {
    return []
  }
}

function makeCopy(title: string, caption: string, keywords: string) {
  const cleanTitle = title.trim() || 'New vertical clip'
  const cleanCaption = caption.trim() || `Watch ${cleanTitle}.`
  const tags = keywords
    .split(',')
    .map((item) => item.trim().replace(/^#/, '').replace(/\s+/g, ''))
    .filter(Boolean)
    .slice(0, 8)
  const hashtags = tags.length ? tags.map((tag) => `#${tag}`).join(' ') : '#shorts #viralvideo #contentcreator'
  return {
    title: cleanTitle,
    caption: cleanCaption,
    description: `${cleanCaption}\n\nCreated with publikclip.`,
    hashtags,
    keywords: tags.join(', ')
  }
}

export default function SocialHub({ onBack }: Props) {
  const [gatewayUrl, setGatewayUrl] = useState('')
  const [gatewayToken, setGatewayToken] = useState('')
  const [queue, setQueue] = useState<SocialPost[]>(loadQueue)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(queue))
  }, [queue])

  const sortedQueue = useMemo(
    () => [...queue].sort((a, b) => (a.scheduledAt || '').localeCompare(b.scheduledAt || '')),
    [queue]
  )

  function updateForm<K extends keyof typeof DEFAULT_FORM>(key: K, value: (typeof DEFAULT_FORM)[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  function generateCopy() {
    const copy = makeCopy(form.title, form.caption, form.keywords)
    setForm((current) => ({ ...current, ...copy }))
    setNotice('تم توليد العنوان والوصف والوسوم محليًا. عند ضبط Gateway API يمكن تفويضها إلى نموذج SEO خارجي.')
  }

  async function connect(platform: Platform) {
    if (!gatewayUrl.trim()) {
      setNotice('أدخل رابط Gateway API أولًا، مثل https://your-domain.example.')
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      const result = await api.socialOAuthStart(gatewayUrl, gatewayToken, platform)
      if (!result.url) throw new Error('لم يُرجع Gateway رابط OAuth.')
      await openUrl(result.url)
      setNotice(`تم فتح تسجيل الدخول لـ ${platform}. أكمل الموافقة في المتصفح ثم ارجع للتطبيق.`)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'تعذر بدء تسجيل الدخول.')
    } finally {
      setBusy(false)
    }
  }

  async function addPost(status: PostStatus) {
    if (!form.mediaUrl.trim()) {
      setNotice('أدخل رابط الفيديو العام حتى يستطيع Gateway رفعه إلى المنصات عند الجدولة.')
      return
    }
    const post: SocialPost = {
      ...form,
      id: crypto.randomUUID(),
      mediaUrl: form.mediaUrl.trim(),
      status
    }
    setQueue((current) => [post, ...current])
    setForm(DEFAULT_FORM)
    setNotice(status === 'awaiting_approval' ? 'أضيف المنشور إلى طابور الموافقة.' : 'أضيف المنشور إلى الجدولة المحلية.')

    if (gatewayUrl.trim() && status === 'scheduled') {
      setBusy(true)
      try {
        await api.socialSchedule(gatewayUrl, gatewayToken, post)
        setNotice('تم إرسال المنشور إلى Gateway API للجدولة.')
      } catch (error) {
        setQueue((current) => current.map((item) => item.id === post.id ? { ...item, status: 'failed', error: String(error) } : item))
        setNotice(error instanceof Error ? error.message : 'فشل إرسال المنشور إلى Gateway API.')
      } finally {
        setBusy(false)
      }
    }
  }

  async function approve(post: SocialPost) {
    if (!gatewayUrl.trim()) {
      setNotice('أدخل Gateway API لإرسال المنشور بعد الموافقة.')
      return
    }
    setBusy(true)
    try {
      await api.socialPublish(gatewayUrl, gatewayToken, { ...post, status: 'scheduled' })
      setQueue((current) => current.map((item) => item.id === post.id ? { ...item, status: 'scheduled', error: undefined } : item))
      setNotice('تمت الموافقة وإرسال المنشور إلى Gateway API.')
    } catch (error) {
      setQueue((current) => current.map((item) => item.id === post.id ? { ...item, status: 'failed', error: String(error) } : item))
      setNotice(error instanceof Error ? error.message : 'فشل نشر المنشور.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="social-hub">
      <div className="grain" />
      <header className="social-head">
        <button className="btn-ghost" onClick={onBack}>← studio</button>
        <div>
          <p className="social-kicker">publikclip / social hub</p>
          <h1 className="social-title">PUBLISH ON YOUR TERMS<span className="amber">.</span></h1>
          <p className="social-sub">Connect accounts, prepare SEO copy, review, and schedule through your own publishing API.</p>
        </div>
      </header>

      <main className="social-grid">
        <section className="social-panel">
          <p className="social-label">01 / CONNECTED ACCOUNTS</p>
          <p className="social-help">The app opens OAuth in the system browser. The Gateway stores refresh tokens; this APK keeps only the session token in memory.</p>
          <label className="social-field">Gateway API URL<input value={gatewayUrl} onChange={(e) => setGatewayUrl(e.target.value)} placeholder="https://your-publishing-api.example" /></label>
          <label className="social-field">Session token <input type="password" value={gatewayToken} onChange={(e) => setGatewayToken(e.target.value)} placeholder="Optional bearer token" /></label>
          <div className="social-platforms">
            {PLATFORMS.map((platform) => (
              <div className="social-platform" key={platform.id}>
                <div><strong>{platform.label}</strong><span>{platform.hint}</span></div>
                <button className="btn-secondary" onClick={() => connect(platform.id)} disabled={busy}>Connect</button>
              </div>
            ))}
          </div>
        </section>

        <section className="social-panel">
          <p className="social-label">02 / COMPOSE & SCHEDULE</p>
          <div className="social-form-grid">
            <label className="social-field">Platform<select value={form.platform} onChange={(e) => updateForm('platform', e.target.value as Platform)}>{PLATFORMS.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
            <label className="social-field">Account<input value={form.account} onChange={(e) => updateForm('account', e.target.value)} placeholder="@account or page name" /></label>
          </div>
          <label className="social-field">Public media URL<input value={form.mediaUrl} onChange={(e) => updateForm('mediaUrl', e.target.value)} placeholder="https://cdn.example.com/clip.mp4" /></label>
          <label className="social-field">Title<input value={form.title} onChange={(e) => updateForm('title', e.target.value)} placeholder="Short searchable title" /></label>
          <label className="social-field">Caption<textarea value={form.caption} onChange={(e) => updateForm('caption', e.target.value)} rows={3} placeholder="Hook and call to action" /></label>
          <label className="social-field">Description<textarea value={form.description} onChange={(e) => updateForm('description', e.target.value)} rows={3} placeholder="Long description for the platform" /></label>
          <div className="social-form-grid">
            <label className="social-field">Hashtags<input value={form.hashtags} onChange={(e) => updateForm('hashtags', e.target.value)} placeholder="#shorts #creator" /></label>
            <label className="social-field">SEO keywords<input value={form.keywords} onChange={(e) => updateForm('keywords', e.target.value)} placeholder="podcast, clips, advice" /></label>
          </div>
          <label className="social-field">Scheduled time<input type="datetime-local" value={form.scheduledAt} onChange={(e) => updateForm('scheduledAt', e.target.value)} /></label>
          <div className="social-actions"><button className="btn-secondary" onClick={generateCopy}>Generate copy / SEO</button><button className="btn-secondary" onClick={() => addPost('awaiting_approval')} disabled={busy}>Add for approval</button><button className="btn-primary" onClick={() => addPost('scheduled')} disabled={busy}>Schedule</button></div>
        </section>

        <section className="social-panel social-queue-panel">
          <p className="social-label">03 / APPROVAL QUEUE</p>
          {sortedQueue.length === 0 && <p className="social-empty">No scheduled posts yet.</p>}
          {sortedQueue.map((post) => (
            <article className="social-queue-item" key={post.id}>
              <div className="social-queue-main"><strong>{post.title || post.platform}</strong><span>{post.platform} · {post.account || 'account not selected'}</span><small>{post.scheduledAt || 'no time set'} · {post.status}</small></div>
              {post.status === 'awaiting_approval' && <button className="btn-secondary" onClick={() => approve(post)} disabled={busy}>Approve</button>}
              {post.error && <p className="social-error">{post.error}</p>}
            </article>
          ))}
        </section>
      </main>
      {notice && <div className="social-notice">{notice}</div>}
    </div>
  )
}
