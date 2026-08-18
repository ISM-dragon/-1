import { useCallback, useEffect, useMemo, useState } from 'react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { api } from '../api'
import type { LoopClip, LoopLinked, LoopOverview, LoopUnlinked } from '../types'
import IgModal from './IgModal'

/**
 * The Loop — predicted score vs what Instagram actually did.
 *
 * Decision #11: raw views is the outcome shown. Decision #12: syncs are
 * opportunistic and every number carries its snapshot age, so gaps are
 * visible instead of papered over. Decision #13: calibration applies itself
 * and announces it here — the changelog is part of the product.
 */

interface Props {
  onBack: () => void
}

const SUB_ABBR: [string, string][] = [
  ['hook', 'HK'],
  ['funniness', 'FN'],
  ['shock', 'SH'],
  ['curiosity_gap', 'CG'],
  ['value', 'VL']
]

function fmtViews(v: number | null | undefined): string {
  if (v == null) return '—'
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`
  if (v >= 10_000) return `${Math.round(v / 1000)}k`
  if (v >= 1_000) return `${(v / 1000).toFixed(1)}k`
  return String(v)
}

function fmtAgo(epoch: number | null | undefined): string {
  if (!epoch) return 'never'
  const mins = Math.max(0, (Date.now() / 1000 - epoch) / 60)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${Math.round(mins)}m ago`
  if (mins < 48 * 60) return `${Math.round(mins / 60)}h ago`
  return `${Math.round(mins / 1440)}d ago`
}

function fmtDate(epoch: number | null | undefined): string {
  if (!epoch) return '?'
  return new Date(epoch * 1000).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function watchLabel(row: LoopLinked): string {
  const ms = row.metrics?.ig_reels_avg_watch_time
  if (ms == null) return '— watch'
  const secs = ms / 1000
  if (row.clip_duration) return `${secs.toFixed(1)}s (${Math.round((secs / row.clip_duration) * 100)}%)`
  return `${secs.toFixed(1)}s`
}

function Sparkline({ points }: { points: { age_hours: number | null; views: number | null }[] }) {
  const usable = points.filter((p) => p.age_hours != null && p.views != null)
  if (usable.length < 2) return <span className="loop-spark-empty mono">·</span>
  const xs = usable.map((p) => p.age_hours as number)
  const ys = usable.map((p) => p.views as number)
  const xMin = Math.min(...xs)
  const xMax = Math.max(...xs)
  const yMax = Math.max(...ys, 1)
  const path = usable
    .map((p, i) => {
      const x = xMax > xMin ? ((p.age_hours! - xMin) / (xMax - xMin)) * 56 + 2 : 30
      const y = 16 - ((p.views! / yMax) * 13 + 1)
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
  return (
    <svg className="loop-spark" width="60" height="18" viewBox="0 0 60 18">
      <path d={path} fill="none" stroke="var(--amber)" strokeWidth="1.5" />
    </svg>
  )
}

function Scatter({ rows }: { rows: LoopLinked[] }) {
  const points = rows.filter((r) => r.metrics?.views != null && !r.media_deleted)
  if (points.length < 2) return null
  const W = 660
  const H = 300
  const PAD = { l: 52, r: 16, t: 14, b: 34 }
  const views = points.map((p) => Math.max(1, p.metrics!.views!))
  const yLo = Math.floor(Math.log10(Math.min(...views)))
  const yHi = Math.ceil(Math.log10(Math.max(...views) * 1.2))
  const yTicks: number[] = []
  for (let e = yLo; e <= yHi; e++) yTicks.push(e)
  const x = (score: number) => PAD.l + (score / 100) * (W - PAD.l - PAD.r)
  const y = (v: number) =>
    H - PAD.b - ((Math.log10(Math.max(1, v)) - yLo) / Math.max(0.001, yHi - yLo)) * (H - PAD.t - PAD.b)
  return (
    <svg className="loop-scatter-svg" viewBox={`0 0 ${W} ${H}`} role="img"
      aria-label="predicted reels score versus actual views">
      {[0, 25, 50, 75, 100].map((s) => (
        <g key={s}>
          <line x1={x(s)} y1={PAD.t} x2={x(s)} y2={H - PAD.b} stroke="var(--line)" strokeWidth="1" />
          <text x={x(s)} y={H - 14} textAnchor="middle" className="loop-axis">{s}</text>
        </g>
      ))}
      {yTicks.map((e) => (
        <g key={e}>
          <line x1={PAD.l} y1={y(10 ** e)} x2={W - PAD.r} y2={y(10 ** e)} stroke="var(--line)" strokeWidth="1" />
          <text x={PAD.l - 8} y={y(10 ** e) + 4} textAnchor="end" className="loop-axis">
            {fmtViews(10 ** e)}
          </text>
        </g>
      ))}
      <text x={(W + PAD.l - PAD.r) / 2} y={H - 2} textAnchor="middle" className="loop-axis-label">
        predicted reels score
      </text>
      {points.map((p) => {
        const thumb = p.ig_thumb ?? p.clip_thumb
        const cx = x(p.reels_score)
        const cy = y(p.metrics!.views!)
        return (
          <g key={p.media_id}>
            <title>{`${p.summary || p.media_id} — score ${p.reels_score}, ${fmtViews(p.metrics!.views)} views`}</title>
            {thumb ? (
              <image
                href={api.fileUrl(thumb)}
                x={cx - 10}
                y={cy - 17}
                width="20"
                height="34"
                preserveAspectRatio="xMidYMid slice"
                clipPath="inset(0 round 3px)"
              />
            ) : (
              <circle cx={cx} cy={cy} r="5" fill="var(--amber)" />
            )}
          </g>
        )
      })}
    </svg>
  )
}

export default function Loop({ onBack }: Props) {
  const [overview, setOverview] = useState<LoopOverview | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [syncing, setSyncing] = useState(false)
  const [syncNote, setSyncNote] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [showConnect, setShowConnect] = useState(false)
  const [showChangelog, setShowChangelog] = useState(false)
  const [pickerFor, setPickerFor] = useState<LoopUnlinked | null>(null)

  const refresh = useCallback(async () => {
    try {
      setOverview(await api.igOverview())
      setLoadError(null)
    } catch (err) {
      setLoadError(String(err))
    }
  }, [])

  const doSync = useCallback(async () => {
    setSyncing(true)
    setSyncNote(null)
    try {
      const summary = await api.igSync()
      if (!summary.ok) {
        setSyncNote(summary.error === 'not_connected' ? null : `sync failed: ${summary.error}`)
      } else {
        const bits = [
          summary.new_media ? `${summary.new_media} new` : null,
          summary.snapshots_pulled ? `${summary.snapshots_pulled} snapshots` : null,
          summary.fit?.applied ? `calibration v${summary.fit.version} applied` : null
        ].filter(Boolean)
        setSyncNote(bits.length ? bits.join(' · ') : 'up to date')
      }
    } catch (err) {
      setSyncNote(`sync failed: ${err}`)
    } finally {
      setSyncing(false)
      refresh()
    }
  }, [refresh])

  useEffect(() => {
    refresh().then(() => undefined)
  }, [refresh])

  // Opportunistic (decision #12): entering the screen syncs when stale.
  useEffect(() => {
    if (!overview?.connected) return
    const stale = !overview.last_synced_at || Date.now() / 1000 - overview.last_synced_at > 3600
    if (stale && !syncing) doSync()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [overview?.connected])

  const link = useCallback(
    async (jobId: string, clip: number, mediaId: string, source: 'manual' | 'match_confirmed') => {
      setBusy(mediaId)
      try {
        await api.igLink(jobId, clip, mediaId, source)
        setPickerFor(null)
        await refresh()
      } finally {
        setBusy(null)
      }
    },
    [refresh]
  )

  const reject = useCallback(
    async (mediaId: string, jobId: string, clip: number) => {
      setBusy(mediaId)
      try {
        await api.igReject(mediaId, jobId, clip)
        await refresh()
      } finally {
        setBusy(null)
      }
    },
    [refresh]
  )

  const unlink = useCallback(
    async (mediaId: string) => {
      setBusy(mediaId)
      try {
        await api.igUnlink(mediaId)
        await refresh()
      } finally {
        setBusy(null)
      }
    },
    [refresh]
  )

  const calib = overview?.calibration
  const activeConstants = calib?.active.constants ?? {}
  const defaults = calib?.history[0]?.constants ?? {}
  const calibrated = (calib?.active.version ?? 1) > 1
  const unlinkedClips = useMemo(
    () => overview?.clip_library.filter((c) => !c.linked) ?? [],
    [overview]
  )

  if (loadError) {
    return (
      <div className="loop">
        <div className="grain" />
        <header className="loop-head">
          <button className="btn-ghost" onClick={onBack}>← studio</button>
        </header>
        <section className="error-block"><span className="led led-err" />{loadError}</section>
      </div>
    )
  }

  if (!overview) return <div className="boot" />

  return (
    <div className="loop">
      <div className="grain" />
      {showConnect && (
        <IgModal
          onClose={() => {
            setShowConnect(false)
            refresh()
          }}
        />
      )}
      {pickerFor && (
        <div className="modal-scrim" onClick={() => setPickerFor(null)}>
          <div className="modal loop-picker" onClick={(e) => e.stopPropagation()}>
            <header className="modal-head">
              <p className="audit-kicker">LINK THIS REEL TO A CLIP</p>
              <button className="btn-ghost" onClick={() => setPickerFor(null)}>close ✕</button>
            </header>
            <div className="loop-picker-media">
              {pickerFor.thumb && <img src={api.fileUrl(pickerFor.thumb)} alt="" />}
              <div>
                <p className="mono loop-dim">{fmtDate(pickerFor.posted_at)}</p>
                <p className="loop-caption">{pickerFor.caption || '(no caption)'}</p>
              </div>
            </div>
            {unlinkedClips.length === 0 && (
              <p className="loop-empty">Every rendered clip is already linked.</p>
            )}
            <div className="loop-picker-grid">
              {unlinkedClips.map((clip: LoopClip) => (
                <button
                  key={`${clip.job_id}:${clip.clip_index}`}
                  className="loop-pick"
                  disabled={busy != null}
                  onClick={() => link(clip.job_id, clip.clip_index, pickerFor.media_id, 'manual')}
                >
                  {clip.thumb ? (
                    <img src={api.fileUrl(clip.thumb)} alt="" />
                  ) : (
                    <span className="loop-thumb-blank" />
                  )}
                  <span className="loop-pick-meta">
                    <span className="mono">{clip.reels_score != null ? clip.reels_score.toFixed(0) : '—'}</span>
                    <span>{clip.summary}</span>
                  </span>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      <header className="loop-head">
        <button className="btn-ghost" onClick={onBack}>← studio</button>
        <div className="loop-head-title">
          <p className="audit-kicker">THE FEEDBACK LOOP</p>
          <h1 className="loop-title">
            SCORE VS <span className="amber">REALITY.</span>
          </h1>
        </div>
        <div className="loop-head-status">
          {overview.connected ? (
            <>
              <span className="mono loop-dim">
                <span className="led led-on" /> @{overview.username} · synced {fmtAgo(overview.last_synced_at)}
              </span>
              <button className="btn-primary" onClick={doSync} disabled={syncing}>
                {syncing ? 'SYNCING…' : 'SYNC NOW'}
              </button>
            </>
          ) : (
            <button className="btn-primary" onClick={() => setShowConnect(true)}>
              CONNECT INSTAGRAM
            </button>
          )}
        </div>
      </header>
      {syncNote && <p className="loop-sync-note mono">{syncNote}</p>}

      {/* calibration strip — decision #13, loudly */}
      <section className="loop-calib">
        <div className="loop-calib-main">
          <p className="loop-calib-head mono">
            CONSTANTS v{calib?.active.version ?? 1}
            {calibrated
              ? ` — calibrated from ${calib?.active.fitted_from_n} of your Reels`
              : ' — research defaults'}
          </p>
          <div className="loop-consts">
            {Object.entries(activeConstants).map(([name, value]) => (
              <span className="loop-const mono" key={name}>
                {name} <b className={value !== defaults[name] ? 'amber' : ''}>×{Number(value).toFixed(2)}</b>
                {value !== defaults[name] && (
                  <s className="loop-dim">×{Number(defaults[name] ?? 0).toFixed(2)}</s>
                )}
              </span>
            ))}
          </div>
        </div>
        <div className="loop-calib-side">
          {overview.report.ready ? (
            <p className="mono">
              ρ <b className="amber">{overview.report.spearman_rho ?? '—'}</b>
              {' · '}pairwise <b>{overview.report.pairwise_accuracy ?? '—'}</b>
              {' · '}n={overview.report.pairs}
            </p>
          ) : (
            <div className="loop-progress">
              <p className="mono loop-dim">
                {calib?.qualifying_outcomes ?? 0}/{calib?.threshold ?? 20} outcomes — calibration
                applies automatically at {calib?.threshold ?? 20}
              </p>
              <div className="loop-progress-bar">
                <div
                  style={{
                    width: `${Math.min(100, ((calib?.qualifying_outcomes ?? 0) / (calib?.threshold ?? 20)) * 100)}%`
                  }}
                />
              </div>
            </div>
          )}
          {(calib?.history.length ?? 0) > 1 && (
            <button className="btn-ghost" onClick={() => setShowChangelog(!showChangelog)}>
              {showChangelog ? 'hide' : 'show'} changelog
            </button>
          )}
        </div>
      </section>
      {showChangelog && calib && (
        <section className="loop-changelog">
          {[...calib.history].reverse().map((v) => (
            <p key={v.version} className="mono">
              <b>v{v.version}</b> {v.note}
              {v.pairwise_acc != null && ` · pairwise ${v.pairwise_acc}`}
              {v.spearman_rho != null && ` · ρ ${v.spearman_rho}`}
              {' — '}
              {Object.entries(v.constants).map(([k, val]) => `${k} ×${Number(val).toFixed(2)}`).join(', ')}
            </p>
          ))}
        </section>
      )}

      {/* unlinked tray — decision #10 */}
      {overview.connected && overview.unlinked.length > 0 && (
        <section className="loop-tray">
          <p className="loop-section-label mono">
            UNLINKED REELS <span className="loop-dim">— link them to clips so their numbers count</span>
          </p>
          <div className="loop-tray-scroll">
            {overview.unlinked.map((media) => (
              <div className="loop-card" key={media.media_id}>
                <div className="loop-card-media">
                  {media.thumb ? (
                    <img src={api.fileUrl(media.thumb)} alt="" />
                  ) : (
                    <span className="loop-thumb-blank" />
                  )}
                  <div className="loop-card-meta">
                    <p className="mono loop-dim">{fmtDate(media.posted_at)}</p>
                    <p className="loop-caption">{media.caption || '(no caption)'}</p>
                    {media.permalink && (
                      <button className="btn-ghost" onClick={() => openUrl(media.permalink!)}>
                        open ↗
                      </button>
                    )}
                  </div>
                </div>
                {media.suggestion ? (
                  <div className="loop-suggest">
                    <div className="loop-suggest-pair">
                      {media.suggestion.clip_thumb ? (
                        <img src={api.fileUrl(media.suggestion.clip_thumb)} alt="" />
                      ) : (
                        <span className="loop-thumb-blank" />
                      )}
                      <div>
                        <p className="mono loop-dim">
                          match {(media.suggestion.confidence * 100).toFixed(0)}%
                          {media.suggestion.clip_reels_score != null &&
                            ` · scored ${media.suggestion.clip_reels_score.toFixed(0)}`}
                        </p>
                        <p className="loop-caption">{media.suggestion.clip_summary}</p>
                      </div>
                    </div>
                    <div className="loop-suggest-actions">
                      <button
                        className="btn-secondary"
                        disabled={busy === media.media_id}
                        onClick={() =>
                          link(
                            media.suggestion!.job_id,
                            media.suggestion!.clip_index,
                            media.media_id,
                            'match_confirmed'
                          )
                        }
                      >
                        ✓ confirm
                      </button>
                      <button
                        className="btn-ghost"
                        disabled={busy === media.media_id}
                        onClick={() =>
                          reject(media.media_id, media.suggestion!.job_id, media.suggestion!.clip_index)
                        }
                      >
                        not this
                      </button>
                    </div>
                  </div>
                ) : (
                  <button className="btn-secondary" onClick={() => setPickerFor(media)}>
                    link a clip…
                  </button>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {/* the money shot — predicted vs actual */}
      {overview.linked.length > 0 ? (
        <>
          <section className="loop-scatter-block">
            <p className="loop-section-label mono">
              PREDICTED SCORE → ACTUAL VIEWS
              <span className="loop-dim"> — each marker is the Reel's own cover</span>
            </p>
            <Scatter rows={overview.linked} />
          </section>

          <section className="loop-table">
            <div className="loop-row loop-row-head mono">
              <span className="loop-col-thumbs">clip / reel</span>
              <span className="loop-col-summary">moment</span>
              <span className="loop-col-score">scored</span>
              <span className="loop-col-views">views</span>
              <span className="loop-col-detail">reach · watch · skip</span>
              <span className="loop-col-trend">trend</span>
              <span className="loop-col-status" />
            </div>
            {overview.linked.map((row) => (
              <div className={`loop-row ${row.media_deleted ? 'loop-row-dead' : ''}`} key={`${row.job_id}:${row.clip_index}`}>
                <span className="loop-col-thumbs">
                  {row.clip_thumb ? (
                    <img src={api.fileUrl(row.clip_thumb)} alt="" title="your clip (hook frame)" />
                  ) : (
                    <span className="loop-thumb-blank" />
                  )}
                  {row.ig_thumb && row.ig_thumb !== row.clip_thumb && (
                    <img src={api.fileUrl(row.ig_thumb)} alt="" title="the cover viewers saw" />
                  )}
                </span>
                <span className="loop-col-summary">
                  <p className="loop-caption">{row.summary || row.caption || row.media_id}</p>
                  <span className="loop-subchips mono">
                    {SUB_ABBR.map(([key, abbr]) =>
                      row.subscores?.[key] != null ? (
                        <span key={key} title={key}>
                          {abbr} {row.subscores[key].toFixed(1)}
                        </span>
                      ) : null
                    )}
                    <span className="loop-dim" title="scoring constants version">v{row.config_version}</span>
                  </span>
                </span>
                <span className="loop-col-score mono">
                  <b>{row.reels_score.toFixed(0)}</b>
                  <span className="loop-dim">reels</span>
                </span>
                <span className="loop-col-views mono">
                  <b>{fmtViews(row.metrics?.views)}</b>
                  {row.metrics?.reach != null && (
                    <span className="loop-dim">{fmtViews(row.metrics.reach)} reach</span>
                  )}
                </span>
                <span className="loop-col-detail mono loop-dim">
                  {watchLabel(row)}
                  {' · '}
                  {row.metrics?.reels_skip_rate != null
                    ? `${Number(row.metrics.reels_skip_rate).toFixed(0)}% skip`
                    : '— skip'}
                </span>
                <span className="loop-col-trend">
                  <Sparkline points={row.snapshots} />
                  <span className="mono loop-dim">
                    {row.snapshot_count} pull{row.snapshot_count === 1 ? '' : 's'}
                  </span>
                </span>
                <span className="loop-col-status">
                  {row.media_deleted ? (
                    <span className="mono loop-dim">deleted</span>
                  ) : row.settling ? (
                    <span className="mono loop-settling" title="Instagram metrics stabilize within ~48h">
                      <span className="led led-half" /> settling
                    </span>
                  ) : (
                    <span className="led led-on" />
                  )}
                  {row.permalink && (
                    <button className="btn-ghost" onClick={() => openUrl(row.permalink!)}>↗</button>
                  )}
                  {row.media_id && (
                    <button
                      className="btn-ghost"
                      title="unlink"
                      disabled={busy === row.media_id}
                      onClick={() => unlink(row.media_id!)}
                    >
                      ✕
                    </button>
                  )}
                </span>
              </div>
            ))}
          </section>
        </>
      ) : (
        overview.connected && (
          <section className="loop-empty-state">
            <p>
              No linked Reels yet. Export a clip, post it on Instagram, hit{' '}
              <span className="mono">SYNC NOW</span> — it appears in the tray above, one click links
              it, and its real numbers start reporting against the score.
            </p>
          </section>
        )
      )}
    </div>
  )
}
