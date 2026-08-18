import { invoke, convertFileSrc } from '@tauri-apps/api/core'
import type { JobResults, JobSummary, LoopOverview, SetupState, SyncSummary } from './types'

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
  resumeJob: (jobId: string, llm?: string, captions?: string, camera?: string) =>
    invoke<void>('resume_job', { jobId, llm, captions, camera }),
  jobResults: (jobId: string) => invoke<JobResults>('job_results', { jobId }),
  listJobs: () => invoke<JobSummary[]>('list_job_dirs'),
  saveGeminiKey: (key: string) => invoke<boolean>('save_gemini_key', { key }),
  setupState: () => invoke<SetupState>('get_setup_state'),
  markOnboarded: () => invoke<void>('mark_onboarded'),
  checkOllama: () => invoke<{ running: boolean; models: string[] }>('check_ollama'),
  exportClip: (path: string, title?: string) =>
    invoke<string>('export_clip', { path, title }),
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
  fileUrl: (path: string) => convertFileSrc(path),
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
