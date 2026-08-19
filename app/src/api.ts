import { invoke, convertFileSrc } from '@tauri-apps/api/core'
import { openUrl } from '@tauri-apps/plugin-opener'
import type { JobResults, JobSummary, LoopOverview, SetupState, SyncSummary } from './types'

export const PROCESSING_GATEWAY_STORAGE_KEY = 'ism.processing-gateway.v1'

export type ProcessingGatewayConfig = { url: string; token: string }

export function loadProcessingGatewayConfig(): ProcessingGatewayConfig {
  try {
    const raw = localStorage.getItem(PROCESSING_GATEWAY_STORAGE_KEY)
    const parsed = raw ? (JSON.parse(raw) as Partial<ProcessingGatewayConfig>) : {}
    return { url: parsed.url ?? '', token: parsed.token ?? '' }
  } catch {
    return { url: '', token: '' }
  }
}

export function saveProcessingGatewayConfig(config: ProcessingGatewayConfig) {
  localStorage.setItem(PROCESSING_GATEWAY_STORAGE_KEY, JSON.stringify(config))
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
  if (parsed.protocol !== 'https:' && !localHost) {
    throw new Error('Gateway API must use HTTPS. HTTP is allowed only for localhost development.')
  }
  return `${value}${path}`
}

export const api = {
  runJob: (source: string, llm: string, captions: string) =>
    invoke<void>('run_job', { source, llm, captions }),
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
  processingStatus: async (baseUrl: string, token: string, jobId: string) => {
    const response = await fetch(gatewayEndpoint(baseUrl, `/v1/processing/jobs/${encodeURIComponent(jobId)}`), {
      headers: token.trim() ? { Authorization: `Bearer ${token.trim()}` } : undefined
    })
    if (!response.ok) throw new Error(`Processing status returned ${response.status}.`)
    return (await response.json()) as {
      id: string
      status: 'queued' | 'running' | 'done' | 'failed'
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
