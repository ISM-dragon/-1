import { invoke, convertFileSrc } from '@tauri-apps/api/core'
import { openUrl } from '@tauri-apps/plugin-opener'
import type { JobResults, JobSummary, LoopOverview, SetupState, SyncSummary } from './types'

export type SourceItem = { index: number; id: string; title: string; duration?: number | null; url: string; thumbnail?: string | null; path?: string; media_url?: string }
export type SourceJob = { id: string; status: 'queued' | 'running' | 'done' | 'failed'; total: number; completed: number; message?: string | null; error?: string | null; items?: SourceItem[] }

export const PROCESSING_GATEWAY_STORAGE_KEY = 'ism.processing-gateway.v1'
export const PROCESSING_GATEWAY_SESSION_KEY = 'ism.processing-gateway.session.v1'
export const REMOTE_PROCESSING_JOB_SESSION_KEY = 'ism.remote-processing-job.v1'

export type ProcessingGatewayConfig = { url: string; token: string }
export type GatewayHealth = {
  status?: string
  ok: boolean
  provider_mode?: string
  auth_configured?: boolean
  auth_required?: boolean
  pipeline?: boolean
  python?: boolean
  ffmpeg?: boolean
  gemini_configured?: boolean
  storage?: boolean
  processing_active?: number
  source_active?: number
}

export type ProcessingCapabilities = {
  gateway: boolean
  pipeline: boolean
  gemini: boolean
  gemini_configured?: boolean
  ffmpeg: boolean
  storage?: boolean
  python?: boolean
  uv?: boolean
  ffprobe?: boolean
  ollama?: boolean
  youtube_urls?: boolean
  https_urls?: boolean
  android_remote_processing?: boolean
  details?: {
    pipeline?: { ready: boolean; message?: string }
    gemini?: { configured: boolean; provider: string; status: string }
    ffmpeg?: { ready: boolean; captions: boolean; message?: string }
  }
}

export type GeminiDiagnostic = {
  status?: 'ready' | 'not_configured' | 'auth_failed' | 'quota' | 'network_error' | 'timeout' | 'pipeline_unavailable' | 'unknown_error'
  configured?: boolean
  reachable?: boolean
  code?: string
  error_code?: string | null
  provider?: string
  model?: string
  latency_ms?: number | null
  message?: string
}
export type AIModel = { id: string; provider_id: string; model_id: string; display_name: string; capabilities: string[]; context_window?: number | null; supports_structured_output: boolean; supports_vision: boolean; enabled: boolean }
export type AIProvider = { id: string; name: string; type: string; enabled: boolean; base_url?: string | null; credential_ref?: string | null; capabilities: string[]; models: AIModel[]; created_at: string; updated_at: string }
export type AIHealth = { provider_id: string; state: string; configured: boolean; reachable: boolean; authenticated?: boolean | null; selected_model_available?: boolean | null; required_capabilities: string[]; checked_at: string; latency_ms?: number | null; error?: string | null }
export type AIProviderSnapshot = { provider: AIProvider; health: AIHealth }
export type GatewayUsageAggregate = { provider: string; model: string; requests: number; input_tokens: number; output_tokens: number; total_tokens: number; estimated_requests: number; average_latency_ms: number; cost_usd: number }
export type GatewayUsageSummary = { days: number; from: string; to: string; events: number; aggregates: GatewayUsageAggregate[] }
export type GatewayProviderModel = { id: string; name: string; type: string; base_url?: string; default_model: string; fallback_model?: string; enabled: boolean; credential_configured: boolean; capabilities: Record<string, boolean>; input_cost_per_million: number; output_cost_per_million: number }

export function loadProcessingGatewayConfig(): ProcessingGatewayConfig {
  try {
    const raw = localStorage.getItem(PROCESSING_GATEWAY_STORAGE_KEY)
    const parsed = raw ? (JSON.parse(raw) as Partial<ProcessingGatewayConfig>) : {}
    const sessionToken = sessionStorage.getItem(PROCESSING_GATEWAY_SESSION_KEY) ?? ''
    if (!sessionToken && parsed.token) sessionStorage.setItem(PROCESSING_GATEWAY_SESSION_KEY, parsed.token)
    if (parsed.token) localStorage.setItem(PROCESSING_GATEWAY_STORAGE_KEY, JSON.stringify({ url: parsed.url ?? '' }))
    return { url: parsed.url ?? '', token: sessionToken || parsed.token || '' }
  } catch {
    return { url: '', token: '' }
  }
}

export function saveProcessingGatewayConfig(config: ProcessingGatewayConfig) {
  localStorage.setItem(PROCESSING_GATEWAY_STORAGE_KEY, JSON.stringify({ url: config.url.trim() }))
  if (config.token.trim()) sessionStorage.setItem(PROCESSING_GATEWAY_SESSION_KEY, config.token.trim())
  else sessionStorage.removeItem(PROCESSING_GATEWAY_SESSION_KEY)
}

function gatewayEndpoint(baseUrl: string, path: string) {
  const value = baseUrl.trim().replace(/\/+$/, '')
  let parsed: URL
  try {
    parsed = new URL(value)
  } catch {
    throw new Error('Gateway API URL is invalid.')
  }
  const localHost = ['localhost', '127.0.0.1', '[::1]', '::1'].includes(parsed.hostname)
  const privateNetwork = /^10\./.test(parsed.hostname) || /^192\.168\./.test(parsed.hostname) || /^172\.(1[6-9]|2\d|3[01])\./.test(parsed.hostname)
  if (parsed.protocol !== 'https:' && !localHost && !privateNetwork) {
    throw new Error('Gateway API must use HTTPS. HTTP is allowed only for localhost or a private LAN address in debug builds.')
  }
  return `${value}${path}`
}

async function gatewayJson<T>(baseUrl: string, token: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(gatewayEndpoint(baseUrl, path), {
    ...init,
    headers: { ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}), ...(init?.headers ?? {}) }
  })
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 240)
    throw new Error(`Gateway ${response.status}: ${detail || response.statusText}`)
  }
  return (await response.json()) as T
}

export const api = {
  gatewayHealth: (baseUrl: string, token: string) => gatewayJson<GatewayHealth>(baseUrl, token, '/health'),
  processingCapabilities: (baseUrl: string, token: string) => gatewayJson<ProcessingCapabilities>(baseUrl, token, '/v1/processing/capabilities'),
  pipelineDiagnostic: (baseUrl: string, token: string) => gatewayJson<ProcessingCapabilities & { pipeline_importable?: boolean; ffmpeg_usable?: boolean; ready?: boolean }>(baseUrl, token, '/v1/diagnostics/pipeline', { method: 'POST' }),
  geminiDiagnostic: (baseUrl: string, token: string) => gatewayJson<GeminiDiagnostic>(baseUrl, token, '/v1/diagnostics/gemini', { method: 'POST' }),
  aiProviders: (baseUrl: string, token: string) => gatewayJson<{ providers: AIProviderSnapshot[]; secret_names: string[] }>(baseUrl, token, '/v1/ai/providers'),
  aiUsageSummary: (baseUrl: string, token: string, days = 30) => gatewayJson<GatewayUsageSummary>(baseUrl, token, `/v1/ai/usage?days=${days}`),
  aiProviderProfiles: (baseUrl: string, token: string) => gatewayJson<{ providers: GatewayProviderModel[] }>(baseUrl, token, '/v1/ai/providers'),
  aiProviderHealth: (baseUrl: string, token: string, providerId: string) => gatewayJson<AIProviderSnapshot>(baseUrl, token, `/v1/ai/providers/${encodeURIComponent(providerId)}/health`, { method: 'POST' }),
  aiModels: (baseUrl: string, token: string, providerId?: string) => gatewayJson<{ models: AIModel[] }>(baseUrl, token, `/v1/ai/models${providerId ? `?provider_id=${encodeURIComponent(providerId)}` : ''}`),
  createAIProvider: (baseUrl: string, token: string, payload: unknown) => gatewayJson<AIProviderSnapshot>(baseUrl, token, '/v1/ai/providers', { method: 'POST', body: JSON.stringify(payload) }),
  updateAIProvider: (baseUrl: string, token: string, providerId: string, payload: unknown) => gatewayJson<AIProviderSnapshot>(baseUrl, token, `/v1/ai/providers/${encodeURIComponent(providerId)}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  deleteAIProvider: (baseUrl: string, token: string, providerId: string) => gatewayJson<{ id: string; status: string }>(baseUrl, token, `/v1/ai/providers/${encodeURIComponent(providerId)}`, { method: 'DELETE' }),
  runJob: (source: string, llm: string, captions: string) =>
    invoke<void>('run_job', { source, llm, captions }),
  health: async (baseUrl: string, token: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/health'), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) {
      const detail = (await response.text()).slice(0, 240)
      throw new Error(`Gateway health returned ${response.status}: ${detail}`)
    }
    return (await response.json()) as GatewayHealth
  },
  processingStart: async (baseUrl: string, token: string, source: string, llm: string, captions: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/processing/jobs'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}) },
      body: JSON.stringify({ source, llm, captions })
    })
    if (!response.ok) {
      const detail = await response.text()
      throw new Error(`Processing Gateway returned ${response.status}: ${detail.slice(0, 240)}`)
    }
    return (await response.json()) as { id: string; status: string }
  },
  sourceInspect: async (baseUrl: string, token: string, source: string, maxItems: number) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/sources/inspect'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}) },
      body: JSON.stringify({ source, max_items: maxItems })
    })
    if (!response.ok) throw new Error(`Source inspection returned ${response.status}: ${(await response.text()).slice(0, 240)}`)
    return (await response.json()) as { source: string; count: number; items: SourceItem[] }
  },
  sourceDownload: async (baseUrl: string, token: string, source: string, maxItems: number) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/sources/download'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}) },
      body: JSON.stringify({ source, max_items: maxItems })
    })
    if (!response.ok) throw new Error(`Source download returned ${response.status}: ${(await response.text()).slice(0, 240)}`)
    return (await response.json()) as { id: string; status: string }
  },
  analyticsSummary: async (baseUrl: string, token: string, days = 30) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/analytics/summary?days=${days}`), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Analytics request returned ${response.status}.`)
    return (await response.json()) as { days: number; from: string; to: string; totals: { views: number; likes: number; comments: number }; accounts: Array<{ account_id: string; platform: string; account_name: string; data_available: boolean; days: Array<{ metric_date: string; views: number; likes: number; comments: number; followers?: number | null; watch_time_seconds?: number | null; source: string; fetched_at: string }> }> }
  },
  socialCapabilities: async (baseUrl: string, token: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/social/capabilities'), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Provider capabilities returned ${response.status}.`)
    return (await response.json()) as { mode: string; providers: Array<{ platform: string; configured: boolean; publish_mode: string; analytics: string; note: string }> }
  },
  accounts: async (baseUrl: string, token: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/accounts'), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Accounts request returned ${response.status}.`)
    return (await response.json()) as Array<{ id: string; platform: string; account_name: string; status: string; daily_limit: number; min_gap_seconds: number; publish_count: number; last_publish_at?: string | null; pause_reason?: string | null; cooldown_until?: string | null }>
  },
  saveAnalyticsSnapshot: async (baseUrl: string, token: string, payload: { account_id: string; metric_date: string; views: number; likes: number; comments: number; followers?: number | null; watch_time_seconds?: number | null; source: string }) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/analytics/snapshots'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}) },
      body: JSON.stringify(payload)
    })
    if (!response.ok) throw new Error(`Analytics snapshot returned ${response.status}.`)
    return (await response.json()) as { status: string; account_id: string; metric_date: string }
  },
  deleteAccount: async (baseUrl: string, token: string, accountId: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/accounts/${encodeURIComponent(accountId)}`), {
      method: 'DELETE',
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Account disconnect returned ${response.status}.`)
    return (await response.json()) as { id: string; status: string }
  },
  dashboardSummary: async (baseUrl: string, token: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/dashboard/summary'), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Dashboard request returned ${response.status}.`)
    return (await response.json()) as { accounts: number; posts: Record<string, number>; recent: Array<Record<string, unknown>> }
  },
  sourceStatus: async (baseUrl: string, token: string, jobId: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/sources/jobs/${encodeURIComponent(jobId)}`), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Source status returned ${response.status}.`)
    return (await response.json()) as SourceJob
  },
  processingCancel: async (baseUrl: string, token: string, jobId: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/processing/jobs/${encodeURIComponent(jobId)}/cancel`), {
      method: 'POST',
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Processing cancel returned ${response.status}: ${(await response.text()).slice(0, 240)}`)
    return (await response.json()) as { id: string; status: 'cancelled' }
  },
  processingStatus: async (baseUrl: string, token: string, jobId: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/processing/jobs/${encodeURIComponent(jobId)}`), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Processing status returned ${response.status}.`)
    return (await response.json()) as {
      id: string
      status: 'queued' | 'running' | 'done' | 'failed' | 'cancelled'
      stage?: string | null
      fraction?: number | null
      message?: string | null
      error?: string | null
      results?: JobResults | null
    }
  },
  resumeJob: (jobId: string, llm?: string, captions?: string, camera?: string) =>
    invoke<void>('resume_job', { jobId, llm, captions, camera }),
  jobResults: (jobId: string) => invoke<JobResults>('job_results', { jobId }),
  listJobs: () => invoke<JobSummary[]>('list_job_dirs'),
  saveGeminiKey: (key: string) => invoke<boolean>('save_gemini_key', { key }),
  setupState: () => invoke<SetupState>('get_setup_state'),
  markOnboarded: () => invoke<void>('mark_onboarded'),
  checkOllama: () => invoke<{ running: boolean; models: string[] }>('check_ollama'),
  igStatus: () => invoke<{ connected: boolean; username?: string }>('ig_status'),
  igSync: () => invoke<SyncSummary>('ig_tool', { args: ['sync'] }),
  igOverview: () => invoke<LoopOverview>('ig_tool', { args: ['overview'] }),
  igLink: (jobId: string, clip: number, mediaId: string, source: 'manual' | 'match_confirmed') =>
    invoke<{ ok: boolean }>('ig_tool', {
      args: ['link', jobId, String(clip), mediaId, '--source', source]
    }),
  igUnlink: (mediaId: string) =>
    invoke<{ ok: boolean }>('ig_tool', { args: ['unlink', mediaId] }),
  igReject: (mediaId: string, jobId: string, clip: number) =>
    invoke<{ ok: boolean }>('ig_tool', { args: ['reject', mediaId, jobId, String(clip)] }),
  fileUrl: (path: string) => /^https?:\/\//i.test(path) ? path : convertFileSrc(path),
  exportClip: async (path: string, title?: string) => {
    if (/^https?:\/\//i.test(path)) {
      await openUrl(path)
      return path
    }
    return invoke<string>('export_clip', { path, title })
  },
  socialOAuthStart: async (baseUrl: string, token: string, platform: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/social/oauth/${platform}/start`), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`OAuth gateway returned ${response.status}.`)
    return (await response.json()) as { url: string }
  },
  socialScheduleList: async (baseUrl: string, token: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/social/schedule'), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Schedule list request returned ${response.status}.`)
    return (await response.json()) as Array<Record<string, unknown>>
  },
  socialSchedule: async (baseUrl: string, token: string, post: unknown) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/social/schedule'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}) },
      body: JSON.stringify(post)
    })
    if (!response.ok) throw new Error(`Schedule gateway returned ${response.status}.`)
    return response.json()
  },
  socialPublish: async (baseUrl: string, token: string, post: unknown) => {
    const response = await fetch(gatewayEndpoint(baseUrl, '/v1/social/publish'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}) },
      body: JSON.stringify(post)
    })
    if (!response.ok) throw new Error(`Publish gateway returned ${response.status}.`)
    return response.json()
  },
  socialUpdate: async (baseUrl: string, token: string, post: unknown) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/social/schedule/${encodeURIComponent(String((post as { id?: string }).id ?? ''))}`), {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}) },
      body: JSON.stringify(post)
    })
    if (!response.ok) throw new Error(`Schedule update gateway returned ${response.status}.`)
    return response.json()
  },
  socialCancel: async (baseUrl: string, token: string, postId: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/social/schedule/${encodeURIComponent(postId)}`), {
      method: 'DELETE',
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Schedule cancel gateway returned ${response.status}.`)
    return response.json()
  }
}
