import { useCallback, useEffect, useRef, useState } from 'react'
import { listen } from '@tauri-apps/api/event'
import { api, loadProcessingGatewayConfig } from './api'
import type { JobResults, JobSummary, PipelineEvent, SetupState } from './types'
import Onboarding from './components/Onboarding'
import Studio from './components/Studio'
import Review from './components/Review'
import Loop from './components/Loop'
import SocialHub from './components/SocialHub'
import SourceLibrary from './components/SourceLibrary'
import AnalyticsDashboard from './components/AnalyticsDashboard'
import './styles.css'

type View = 'boot' | 'onboarding' | 'studio' | 'review' | 'loop' | 'social' | 'sources' | 'analytics'

export default function App() {
  const [view, setView] = useState<View>('boot')
  const [setup, setSetup] = useState<SetupState | null>(null)
  const [jobs, setJobs] = useState<JobSummary[]>([])
  const [activeJob, setActiveJob] = useState<string | null>(null)
  const [results, setResults] = useState<JobResults | null>(null)
  const [stages, setStages] = useState<Record<string, { fraction: number; message: string }>>({})
  const [running, setRunning] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)
  const [bootError, setBootError] = useState<string | null>(null)
  const unlistenRef = useRef<(() => void) | null>(null)
  const activeJobRef = useRef<string | null>(null)
  activeJobRef.current = activeJob
  const isAndroid = /Android/i.test(navigator.userAgent)

  const refreshJobs = useCallback(() => {
    api.listJobs().then(setJobs).catch(() => setJobs([]))
  }, [])

  useEffect(() => {
    api.setupState().then((s) => {
      setSetup(s)
      setBootError(null)
      setView(s.onboarded ? 'studio' : 'onboarding')
    }).catch((error) => {
      setBootError(error instanceof Error ? error.message : 'Could not read the local app settings.')
    })
    refreshJobs()
  }, [refreshJobs])

  // Instagram loop: opportunistic sync on launch + hourly while open
  // (decision #12 — no background process, the app's own uptime is the
  // schedule). Fire-and-forget; the Loop screen re-reads on entry.
  useEffect(() => {
    const kick = () => {
      api
        .igStatus()
        .then((s) => (s.connected ? api.igSync() : null))
        .catch(() => null)
    }
    kick()
    const timer = window.setInterval(kick, 60 * 60 * 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    let disposed = false
    listen<PipelineEvent>('pipeline-event', ({ payload }) => {
      if (payload.event === 'job' && payload.job_id) {
        setActiveJob(payload.job_id)
        setResults(null)
      } else if (payload.event === 'progress' && payload.stage) {
        setStages((prev) => ({
          ...prev,
          [payload.stage!]: {
            fraction: payload.fraction ?? -1,
            message: payload.message ?? ''
          }
        }))
      } else if (payload.event === 'result') {
        setRunning(false)
        refreshJobs()
        if (payload.ok && activeJobRef.current) {
          api.jobResults(activeJobRef.current).then((r) => {
            setResults(r)
            setView('review')
          })
        } else if (!payload.ok) {
          setRunError(String(payload.error ?? 'Pipeline failed'))
        }
      } else if (payload.event === 'exited') {
        setRunning(false)
        setRunError('The pipeline exited unexpectedly. Resume the job to continue from its last checkpoint.')
      }
    }).then((un) => {
      if (disposed) un()
      else unlistenRef.current = un
    })
    return () => {
      disposed = true
      unlistenRef.current?.()
    }
  }, [refreshJobs])

  const startRun = useCallback(
    async (source: string, llm: string, captions: string) => {
      setRunning(true)
      setRunError(null)
      setStages({})
      setResults(null)
      setActiveJob(null)
      try {
        if (!isAndroid) {
          await api.runJob(source, llm, captions)
          return
        }

        const gateway = loadProcessingGatewayConfig()
        if (!gateway.url.trim()) {
          throw new Error('Android needs a Processing Gateway URL. Open Social Hub, enter the HTTPS Gateway URL, and save it first.')
        }
        if (!/^https?:\/\//i.test(source.trim())) {
          throw new Error('Android remote processing accepts a YouTube or HTTPS video URL, not a local file path.')
        }
        const started = await api.processingStart(gateway.url, gateway.token, source.trim(), llm, captions)
        setActiveJob(started.id)
        let lastStage = ''
        for (;;) {
          await new Promise((resolve) => window.setTimeout(resolve, 1500))
          const status = await api.processingStatus(gateway.url, gateway.token, started.id)
          if (status.stage && status.stage !== lastStage) {
            lastStage = status.stage
            setStages((prev) => ({
              ...prev,
              [status.stage!]: { fraction: status.fraction ?? -1, message: status.message ?? '' }
            }))
          }
          if (status.status === 'failed') throw new Error(status.error || 'Remote processing failed.')
          if (status.status === 'done') {
            if (!status.results) throw new Error('Gateway completed without returning clip results.')
            setResults(status.results)
            setRunning(false)
            setView('review')
            return
          }
        }
      } catch (error) {
        setRunning(false)
        setRunError(error instanceof Error ? error.message : String(error))
      }
    },
    [isAndroid]
  )

  const openJob = useCallback(async (jobId: string) => {
    const r = await api.jobResults(jobId)
    setActiveJob(jobId)
    setResults(r)
    if (r.render?.outputs?.length) setView('review')
  }, [])

  if (view === 'boot') return bootError ? <div className="boot boot-failed"><div><h1>ISM could not start</h1><p>{bootError}</p><button className="btn-primary" onClick={() => window.location.reload()}>RETRY</button></div></div> : <div className="boot" />

  if (view === 'onboarding' && setup) {
    return (
      <Onboarding
        onDone={async () => {
          await api.markOnboarded()
          setSetup({ ...setup, onboarded: true })
          setView('studio')
        }}
      />
    )
  }

  if (view === 'loop') {
    return <Loop onBack={() => setView('studio')} />
  }

  if (view === 'social') {
    return <SocialHub onBack={() => setView('studio')} onOpenAnalytics={() => setView('analytics')} />
  }

  if (view === 'sources') {
    return <SourceLibrary onClose={() => setView('studio')} />
  }

  if (view === 'analytics') {
    return <AnalyticsDashboard onBack={() => setView('social')} />
  }

  if (view === 'review' && results) {
    return (
      <Review
        results={results}
        onBack={() => {
          setView('studio')
          refreshJobs()
        }}
        onRestyle={(captions, camera) => {
          setRunning(true)
          setRunError(null)
          setStages({})
          setActiveJob(results.job_id)
          setView('studio')
          api.resumeJob(results.job_id, undefined, captions, camera).catch((error) => {
            setRunning(false)
            setRunError(error instanceof Error ? error.message : String(error))
          })
        }}
      />
    )
  }

  return (
          <Studio
        jobs={jobs}
        isAndroid={isAndroid}

      running={running}
      stages={stages}
      error={runError}
      onRun={startRun}
      onOpenLoop={() => setView('loop')}
              onOpenSocial={() => setView('social')}
        onOpenSources={() => setView('sources')}

      onOpenJob={openJob}
      onResume={(id, llm) => {
        if (isAndroid) {
          setRunError('Android cannot resume a local desktop Pipeline job. Start the YouTube URL again through Processing Gateway.')
          return
        }
        setRunning(true)
        setRunError(null)
        setStages({})
        setActiveJob(id)
        api.resumeJob(id, llm).catch((error) => {
          setRunning(false)
          setRunError(error instanceof Error ? error.message : String(error))
        })
      }}
    />
  )
}
