# Social Hub API contract

`Social Hub` is the in-app control surface for account connections, copy preparation, approval, and scheduling. It delegates OAuth, token storage, media upload, platform-specific publishing, and retry handling to a user-controlled HTTPS Gateway API.

The Gateway must be configured in the Social Hub screen. The Android app sends only the post metadata and a public media URL; it must not receive or store platform app secrets. The Gateway should store refresh tokens encrypted at rest and should expose HTTPS endpoints only.

## Endpoints

### Start OAuth

```http
GET /v1/social/oauth/{platform}/start
Authorization: Bearer <session-token>
```

`platform` is one of `instagram`, `facebook`, `tiktok`, `youtube`, or `x`.

Response:

```json
{ "url": "https://provider.example/oauth/authorize?..." }
```

The Gateway owns the OAuth callback and must validate the `state` value before storing the resulting account connection.

### Schedule a post

```http
POST /v1/social/schedule
Authorization: Bearer <session-token>
Content-Type: application/json
```

Example payload:

```json
{
  "id": "local-post-id",
  "platform": "youtube",
  "account": "channel-name",
  "mediaUrl": "https://cdn.example.com/clip.mp4",
  "title": "Searchable clip title",
  "caption": "Short hook and call to action",
  "description": "Long-form description",
  "hashtags": "#shorts #creator",
  "keywords": "podcast, clips, advice",
  "scheduledAt": "2026-08-18T19:30:00Z",
  "status": "scheduled"
}
```

Response:

```json
{ "id": "gateway-job-id", "status": "scheduled" }
```

### Publish or approve a post

```http
POST /v1/social/publish
Authorization: Bearer <session-token>
Content-Type: application/json
```

The payload has the same shape as the schedule request. The Gateway should upload the media, call the selected platform's official API, persist the platform post ID, and return a normalized status:

```json
{
  "id": "gateway-job-id",
  "status": "published",
  "platformPostId": "provider-post-id",
  "permalink": "https://provider.example/post/123"
}
```

## Provider responsibilities

The Gateway is responsible for platform-specific OAuth scopes, refresh-token rotation, resumable uploads, rate limits, status polling, and provider errors. Instagram and Facebook accounts must satisfy Meta's professional-account/Page requirements. TikTok may require Content Posting API approval. YouTube may require Google OAuth verification before public uploads. X video publishing uses chunked media upload followed by post creation.

## Security requirements

The APK must never contain Meta app secrets, TikTok client secrets, Google client secrets, X client secrets, or platform refresh tokens. Use short-lived session tokens for the Gateway, rotate them, validate HTTPS certificates, rate-limit the endpoints, and require explicit user approval when the account or content policy requires it.
