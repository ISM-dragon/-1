# ISM v0.6–v0.9 Feature Research Notes

## Official platform findings

### Instagram / Meta
Source: https://developers.facebook.com/documentation/instagram-platform/content-publishing

The Instagram Platform content-publishing guide is updated June 30, 2026. It supports publishing single images, videos, Reels, and carousels for Instagram professional accounts. Publishing requires a Meta login flow, suitable permissions, and media hosted on a publicly accessible server at publish time. The documented flow creates a media container, optionally uploads a video through resumable upload, checks container status, then calls the media-publish endpoint. The guide states a 100 API-published-post limit in a moving 24-hour period for Instagram accounts and recommends enforcing the limit in the application.

### TikTok
Source: https://developers.tiktok.com/products/content-posting-api/

TikTok's official Content Posting API supports applications that let creators post directly to a TikTok profile or upload a video as a draft for further editing. The product page explicitly describes compatibility with desktop, cloud, and web applications. The v0.7 design should therefore support both Direct Post and Draft modes rather than pretending every account can be silently auto-published.

### YouTube upload
Source: https://developers.google.com/youtube/v3/guides/uploading_a_video

Google's official upload guide uses OAuth 2.0 and the `videos.insert` method. The guide states that the upload request can set title, description, keywords, category, and privacy status. v0.7 should use resumable uploads, explicit privacy selection, retry handling, and token refresh rather than storing channel credentials in the app.

### YouTube Analytics
Source: https://developers.google.com/youtube/analytics

The YouTube Reporting and Analytics APIs support automated reporting and custom dashboards. The Analytics API is intended for targeted queries, while the Reporting API supports bulk reports. Channel-owner reports can provide viewing statistics and trends, with dimensions and metrics used to aggregate user activity. v0.8 can therefore show daily views, watch time, likes, subscribers, and per-video trends when the account grants the required OAuth access.

### X media and metrics
Sources: https://docs.x.com/x-api/media/introduction and https://docs.x.com/x-api/fundamentals/metrics

X documents chunked uploads for videos and attaching returned media IDs to posts. The media upload page lists a 512 MB video limit for the `amplify_video` category. X metrics distinguish public metrics available with bearer authentication from non-public, organic, and promoted metrics that require user context; non-public/organic/promoted metrics are limited to posts created in the last 30 days. v0.7 should implement chunked upload plus post creation, while v0.8 should label which dashboard metrics are public versus private and handle the 30-day window explicitly.

### Instagram Insights
Source: https://developers.facebook.com/documentation/instagram-platform/insights

Meta documents media and account insights for Instagram professional accounts. The integration needs a Meta login flow, appropriate permissions, and a webhooks server. The guide states that personal accounts are not supported for these insights, some metrics may be unavailable below 100 followers, and user metrics are retained for up to 90 days. v0.8 should display the account type, permissions, data freshness, and missing metrics instead of showing zeros as if they were real measurements.

### TikTok Display API
Source: https://developers.tiktok.com/doc/display-api-overview

TikTok's Display API provides `/v2/user/info/`, `/v2/video/list/`, and `/v2/video/query/` for profile information and metadata of a user's videos, with `user.info.basic` and `video.list` permissions. This can support account identity and published-video inventory. Full account analytics should be implemented only through an approved TikTok product/API scope; the dashboard must mark unsupported metrics clearly rather than scraping TikTok pages.

## Design implications

The app can add official OAuth account connections, provider-specific publishing modes, public media hosting, scheduling, idempotency, rate limits, and analytics synchronization. It should not rotate IP addresses to evade bans. Safer controls are per-account quotas, cooldowns, token-expiry checks, provider error handling, audit logs, and a manual approval mode.

## Planned follow-up sources

- YouTube Data API upload and YouTube Analytics API reporting.
- Meta Insights API for Instagram and Facebook Pages.
- TikTok Display/Business analytics capabilities and permissions.
- X API media upload and post publishing constraints.
- yt-dlp and FFmpeg project documentation for compliant user-provided source downloading and media processing.
