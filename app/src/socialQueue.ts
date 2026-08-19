export type PostStatus = 'draft' | 'awaiting_approval' | 'scheduled' | 'publishing' | 'published' | 'failed' | 'cancelled'

export type SocialForm = {
  platform: 'instagram' | 'facebook' | 'tiktok' | 'youtube' | 'x'
  account: string
  mediaUrl: string
  title: string
  caption: string
  description: string
  hashtags: string
  keywords: string
  scheduledAt: string
  autoPublish: boolean
}

export type SocialPost = SocialForm & {
  id: string
  status: PostStatus
  error?: string | null
  providerPostId?: string | null
  permalink?: string | null
  updatedAt?: string | null
}

export type GatewaySocialPost = Partial<SocialPost> & {
  id: string
  platform: SocialPost['platform']
  mediaUrl?: string | null
  scheduledAt?: string | null
  autoPublish?: boolean | number | null
  status?: PostStatus | string
}

function isValidDate(value: Date) {
  return !Number.isNaN(value.getTime())
}

export function toLocalDateTimeValue(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (!isValidDate(date)) return ''
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function normalizeGatewayPost(post: GatewaySocialPost): SocialPost {
  return {
    platform: post.platform,
    account: post.account ?? '',
    mediaUrl: post.mediaUrl ?? '',
    title: post.title ?? '',
    caption: post.caption ?? '',
    description: post.description ?? '',
    hashtags: post.hashtags ?? '',
    keywords: post.keywords ?? '',
    scheduledAt: post.scheduledAt ?? '',
    autoPublish: Boolean(post.autoPublish),
    id: post.id,
    status: (post.status as PostStatus) ?? 'scheduled',
    error: post.error ?? undefined,
    providerPostId: post.providerPostId ?? null,
    permalink: post.permalink ?? null,
    updatedAt: post.updatedAt ?? null,
  }
}

export function mergeGatewayQueue(remote: GatewaySocialPost[], local: SocialPost[]): SocialPost[] {
  const remotePosts = remote.map(normalizeGatewayPost)
  const remoteIds = new Set(remotePosts.map((post) => post.id))
  const localOnly = local.filter((post) => !remoteIds.has(post.id) && ['draft', 'awaiting_approval', 'failed'].includes(post.status))
  return [...remotePosts, ...localOnly].sort((a, b) => (a.scheduledAt || '').localeCompare(b.scheduledAt || ''))
}

export function replaceQueuePost(queue: SocialPost[], updated: GatewaySocialPost): SocialPost[] {
  const normalized = normalizeGatewayPost(updated)
  const found = queue.some((post) => post.id === normalized.id)
  return found ? queue.map((post) => post.id === normalized.id ? normalized : post) : [normalized, ...queue]
}
