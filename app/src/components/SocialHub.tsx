import { useEffect, useMemo, useState } from 'react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { api } from '../api'

type Platform = 'instagram' | 'facebook' | 'tiktok' | 'youtube' | 'x'
type PostStatus = 'draft' | 'awaiting_approval' | 'scheduled' | 'published' | 'failed' | 'cancelled'

type SocialForm = {
  platform: Platform
  account: string
  mediaUrl: string
  title: string
  caption: string
  description: string
  hashtags: string
  keywords: string
  scheduledAt: string
}

interface SocialPost extends SocialForm {
  id: string
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
const DEFAULT_FORM: SocialForm = {
  platform: 'instagram', account: '', mediaUrl: '', title: '', caption: '', description: '', hashtags: '', keywords: '', scheduledAt: ''
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
  const tags = keywords.split(',').map((item) => item.trim().replace(/^#/, '').replace(/\s+/g, '')).filter(Boolean).slice(0, 8)
  const hashtags = tags.length ? tags.map((tag) => `#${tag}`).join(' ') : '#shorts #viralvideo #contentcreator'
  return { title: cleanTitle, caption: cleanCaption, description: `${cleanCaption}\n\nCreated with ISM.`, hashtags, keywords: tags.join(', ') }
}

function dateKey(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function copyForm(post: SocialPost): SocialForm {
  return {
    platform: post.platform,
    account: post.account,
    mediaUrl: post.mediaUrl,
    title: post.title,
    caption: post.caption,
    description: post.description,
    hashtags: post.hashtags,
    keywords: post.keywords,
    scheduledAt: post.scheduledAt
  }
}

export default function SocialHub({ onBack }: Props) {
  const [gatewayUrl, setGatewayUrl] = useState('')
  const [gatewayToken, setGatewayToken] = useState('')
  const [queue, setQueue] = useState<SocialPost[]>(loadQueue)
  const [form, setForm] = useState<SocialForm>(DEFAULT_FORM)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [calendarCursor, setCalendarCursor] = useState(() => new Date(new Date().getFullYear(), new Date().getMonth(), 1))
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(queue))
  }, [queue])

  const sortedQueue = useMemo(() => [...queue].sort((a, b) => (a.scheduledAt || '').localeCompare(b.scheduledAt || '')), [queue])
  const calendarDays = useMemo(() => {
    const year = calendarCursor.getFullYear()
    const month = calendarCursor.getMonth()
    const leading = new Date(year, month, 1).getDay()
    const total = new Date(year, month + 1, 0).getDate()
    return Array.from({ length: leading + total }, (_, index) => index < leading ? null : new Date(year, month, index - leading + 1))
  }, [calendarCursor])
  const postsByDay = useMemo(() => {
    const map = new Map<string, SocialPost[]>()
    for (const post of queue) {
      if (!post.scheduledAt) continue
      const key = post.scheduledAt.slice(0, 10)
      map.set(key, [...(map.get(key) ?? []), post])
    }
    return map
  }, [queue])

  function updateForm<K extends keyof SocialForm>(key: K, value: SocialForm[K]) {
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
    if (!form.mediaUrl.trim()) return setNotice('أدخل رابط الفيديو العام حتى يستطيع Gateway رفعه إلى المنصات عند الجدولة.')
    if (!form.scheduledAt) return setNotice('اختر تاريخًا ووقتًا للمنشور حتى يظهر في الجدول.')
    const post: SocialPost = { ...form, id: crypto.randomUUID?.() ?? `post-${Date.now()}`, mediaUrl: form.mediaUrl.trim(), status }
    setQueue((current) => [post, ...current])
    setForm(DEFAULT_FORM)
    setNotice(status === 'awaiting_approval' ? 'أضيف المنشور إلى طابور الموافقة.' : 'أضيف المنشور إلى الجدول المحلي.')
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

  function startEdit(post: SocialPost) {
    setEditingId(post.id)
    setForm(copyForm(post))
    const parsed = post.scheduledAt ? new Date(post.scheduledAt) : null
    if (parsed && !Number.isNaN(parsed.getTime())) setCalendarCursor(new Date(parsed.getFullYear(), parsed.getMonth(), 1))
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  async function saveEdit() {
    if (!editingId) return
    if (!form.scheduledAt) return setNotice('اختر تاريخًا ووقتًا جديدًا للمنشور.')
    const current = queue.find((post) => post.id === editingId)
    if (!current) return
    const updated: SocialPost = { ...current, ...form, status: current.status === 'cancelled' ? 'scheduled' : current.status, error: undefined }
    setQueue((items) => items.map((item) => item.id === editingId ? updated : item))
    setEditingId(null)
    setForm(DEFAULT_FORM)
    if (gatewayUrl.trim()) {
      setBusy(true)
      try {
        await api.socialUpdate(gatewayUrl, gatewayToken, updated)
        setNotice('تم تحديث موعد المنشور في Gateway API.')
      } catch (error) {
        setQueue((items) => items.map((item) => item.id === updated.id ? { ...item, status: 'failed', error: String(error) } : item))
        setNotice(error instanceof Error ? error.message : 'فشل تحديث موعد المنشور.')
      } finally {
        setBusy(false)
      }
    } else {
      setNotice('تم تحديث الموعد محليًا. أضف Gateway API لمزامنته مع النشر الفعلي.')
    }
  }

  async function cancelPost(post: SocialPost) {
    if (post.status === 'published' || post.status === 'cancelled') return
    setBusy(true)
    try {
      if (gatewayUrl.trim()) await api.socialCancel(gatewayUrl, gatewayToken, post.id)
      setQueue((items) => items.map((item) => item.id === post.id ? { ...item, status: 'cancelled', error: undefined } : item))
      setNotice('تم إلغاء المنشور من الجدول.')
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'فشل إلغاء المنشور.')
    } finally {
      setBusy(false)
    }
  }

  async function approve(post: SocialPost) {
    if (!gatewayUrl.trim()) return setNotice('أدخل Gateway API لإرسال المنشور بعد الموافقة.')
    setBusy(true)
    try {
      await api.socialPublish(gatewayUrl, gatewayToken, { ...post, status: 'scheduled' })
      setQueue((items) => items.map((item) => item.id === post.id ? { ...item, status: 'scheduled', error: undefined } : item))
      setNotice('تمت الموافقة وإرسال المنشور إلى Gateway API.')
    } catch (error) {
      setQueue((items) => items.map((item) => item.id === post.id ? { ...item, status: 'failed', error: String(error) } : item))
      setNotice(error instanceof Error ? error.message : 'فشل نشر المنشور.')
    } finally {
      setBusy(false)
    }
  }

  const monthLabel = calendarCursor.toLocaleDateString('ar', { month: 'long', year: 'numeric' })
  const todayKey = dateKey(new Date())

  return (
    <div className="social-hub">
      <div className="grain" />
      <header className="social-head">
        <button className="btn-ghost" onClick={onBack}>← studio</button>
        <div>
          <p className="social-kicker">ISM / social hub</p>
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
          <div className="social-platforms">{PLATFORMS.map((platform) => <div className="social-platform" key={platform.id}><div><strong>{platform.label}</strong><span>{platform.hint}</span></div><button className="btn-secondary" onClick={() => connect(platform.id)} disabled={busy}>Connect</button></div>)}</div>
        </section>

        <section className="social-panel">
          <p className="social-label">{editingId ? '02 / EDIT SCHEDULED POST' : '02 / COMPOSE & SCHEDULE'}</p>
          <div className="social-form-grid">
            <label className="social-field">Platform<select value={form.platform} onChange={(e) => updateForm('platform', e.target.value as Platform)}>{PLATFORMS.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
            <label className="social-field">Account<input value={form.account} onChange={(e) => updateForm('account', e.target.value)} placeholder="@account or page name" /></label>
          </div>
          <label className="social-field">Public media URL<input value={form.mediaUrl} onChange={(e) => updateForm('mediaUrl', e.target.value)} placeholder="https://cdn.example.com/clip.mp4" /></label>
          <label className="social-field">Title<input value={form.title} onChange={(e) => updateForm('title', e.target.value)} placeholder="Short searchable title" /></label>
          <label className="social-field">Caption<textarea value={form.caption} onChange={(e) => updateForm('caption', e.target.value)} rows={3} placeholder="Hook and call to action" /></label>
          <label className="social-field">Description<textarea value={form.description} onChange={(e) => updateForm('description', e.target.value)} rows={3} placeholder="Long description for the platform" /></label>
          <div className="social-form-grid"><label className="social-field">Hashtags<input value={form.hashtags} onChange={(e) => updateForm('hashtags', e.target.value)} placeholder="#shorts #creator" /></label><label className="social-field">SEO keywords<input value={form.keywords} onChange={(e) => updateForm('keywords', e.target.value)} placeholder="podcast, clips, advice" /></label></div>
          <label className="social-field">Scheduled time<input type="datetime-local" value={form.scheduledAt} onChange={(e) => updateForm('scheduledAt', e.target.value)} /></label>
          <div className="social-actions"><button className="btn-secondary" onClick={generateCopy}>Generate copy / SEO</button>{editingId ? <><button className="btn-secondary" onClick={() => { setEditingId(null); setForm(DEFAULT_FORM) }}>Cancel edit</button><button className="btn-primary" onClick={saveEdit} disabled={busy}>Save schedule</button></> : <><button className="btn-secondary" onClick={() => addPost('awaiting_approval')} disabled={busy}>Add for approval</button><button className="btn-primary" onClick={() => addPost('scheduled')} disabled={busy}>Schedule</button></>}</div>
        </section>

        <section className="social-panel social-calendar-panel">
          <div className="social-calendar-head"><div><p className="social-label">03 / PUBLISHING CALENDAR</p><h2 className="social-month">{monthLabel}</h2></div><div className="social-calendar-actions"><button className="btn-ghost" onClick={() => setCalendarCursor(new Date(calendarCursor.getFullYear(), calendarCursor.getMonth() - 1, 1))}>←</button><button className="btn-ghost" onClick={() => setCalendarCursor(new Date())}>today</button><button className="btn-ghost" onClick={() => setCalendarCursor(new Date(calendarCursor.getFullYear(), calendarCursor.getMonth() + 1, 1))}>→</button></div></div>
          <div className="social-calendar-weekdays">{['أحد', 'اثن', 'ثلا', 'أرب', 'خمي', 'جمع', 'سبت'].map((day) => <span key={day}>{day}</span>)}</div>
          <div className="social-calendar-grid">{calendarDays.map((day, index) => { const key = day ? dateKey(day) : `empty-${index}`; const dayPosts = day ? (postsByDay.get(key) ?? []) : []; return <div className={`social-calendar-day ${day && key === todayKey ? 'today' : ''} ${day ? '' : 'empty'}`} key={key}>{day && <><span className="social-day-number">{day.getDate()}</span><div className="social-day-posts">{dayPosts.slice(0, 3).map((post) => <button className={`social-day-post platform-${post.platform} status-${post.status}`} key={post.id} onClick={() => startEdit(post)} title="Edit or reschedule"><span>{post.title || post.platform}</span><small>{post.scheduledAt.slice(11, 16)}</small></button>)}{dayPosts.length > 3 && <small className="social-more">+{dayPosts.length - 3} more</small>}</div></>}</div> })}</div>
        </section>

        <section className="social-panel social-queue-panel">
          <p className="social-label">04 / APPROVAL QUEUE & UPCOMING</p>
          {sortedQueue.length === 0 && <p className="social-empty">No scheduled posts yet.</p>}
          {sortedQueue.map((post) => <article className={`social-queue-item status-row-${post.status}`} key={post.id}><div className="social-queue-main"><strong>{post.title || post.platform}</strong><span>{post.platform} · {post.account || 'account not selected'}</span><small>{post.scheduledAt || 'no time set'} · {post.status}</small></div><div className="social-queue-actions">{post.status === 'awaiting_approval' && <button className="btn-secondary" onClick={() => approve(post)} disabled={busy}>Approve</button>}{post.status !== 'published' && post.status !== 'cancelled' && <><button className="btn-ghost" onClick={() => startEdit(post)} disabled={busy}>Edit</button><button className="btn-ghost danger" onClick={() => cancelPost(post)} disabled={busy}>Cancel</button></>}{post.error && <p className="social-error">{post.error}</p>}</div></article>)}
        </section>
      </main>
      {notice && <div className="social-notice">{notice}</div>}
    </div>
  )
}
