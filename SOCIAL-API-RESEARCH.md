# Social publishing API findings

## Instagram and Facebook (Meta)

Official Instagram Platform documentation says publishing is available for Instagram Business and Creator accounts, with the Instagram Login or Facebook Login for Business paths. The Facebook Video API supports publishing videos and Reels to Facebook Pages. Facebook Reels publishing uses an upload session, resumable upload to Meta, status polling, and a publish step. The official documentation lists `pages_show_list`, `pages_read_engagement`, and `pages_manage_posts` for the Page publishing flow, and states that Reels API publishing is limited to Facebook Pages and rate-limited to 30 API-published posts per rolling 24-hour period. Local-only files cannot be sent as a URL; the app must upload the file to Meta or an accessible hosting endpoint.

Official sources:
- https://developers.facebook.com/documentation/instagram-platform
- https://developers.facebook.com/documentation/video-api/guides/publishing
- https://developers.facebook.com/documentation/video-api/guides/reels-publishing
- https://developers.facebook.com/documentation/facebook-login/guides/access-tokens

## TikTok

TikTok's official Content Posting API supports both Direct Post to a user's TikTok profile and Upload to TikTok as a draft for later editing and posting. The integration is designed for desktop, cloud, and web applications. The app requires TikTok developer registration, OAuth user access tokens, Content Posting API permissions, and likely app review/sandbox configuration before production use. The product supports a hybrid user experience: automatic upload/direct post where permitted and draft upload when user confirmation or TikTok editing is required.

Official sources:
- https://developers.tiktok.com/products/content-posting-api/
- https://developers.tiktok.com/doc/content-posting-api-get-started
- https://developers.tiktok.com/doc/content-posting-api-reference-direct-post?enter_method=left_navigation
- https://developers.tiktok.com/doc/login-kit-manage-user-access-tokens/

## YouTube

YouTube Data API `videos.insert` supports video upload and metadata, including `status.publishAt` for scheduled publishing. Upload requires OAuth scopes such as `https://www.googleapis.com/auth/youtube.upload`; public applications using sensitive scopes may need verification. Unverified API projects created after 28 July 2020 have uploads restricted to private viewing until the project passes YouTube's audit. OAuth for installed apps uses the system browser and PKCE; client secrets cannot be kept confidential in an installed APK, so refresh tokens and server-side scheduling should be handled carefully.

Official sources:
- https://developers.google.com/youtube/v3/docs/videos/insert
- https://developers.google.com/youtube/v3/guides/uploading_a_video
- https://developers.google.com/youtube/v3/guides/authentication
- https://developers.google.com/youtube/v3/guides/auth/installed-apps

## X

X API v2 video publishing uses a chunked media workflow: INIT, APPEND, FINALIZE, and STATUS when processing is asynchronous, followed by `POST /2/tweets` with the resulting media ID. OAuth 2.0 Authorization Code with PKCE can issue refresh tokens when `offline.access` is requested. Posting requires user-context scopes including `media.write`, `tweet.write`, and usually `users.read`/`tweet.read` as needed. The official documentation lists 512 MB as the video limit for `amplify_video`; the implementation should use resumable chunks and retry individual chunks.

Official sources:
- https://docs.x.com/x-api/media/introduction
- https://docs.x.com/x-api/media/quickstart/media-upload-chunked
- https://docs.x.com/x-api/posts/create-post
- https://docs.x.com/fundamentals/authentication/oauth-2-0/user-access-token

## Architecture implication

The selected hybrid model should keep account connection, preview, content approval, and local export on the Android app, while a secure background service handles scheduled uploads, token refresh, retries, and platform-specific status polling. App secrets and refresh tokens must not be embedded in the APK. Instagram/Facebook and TikTok may require public hosting or platform upload sessions; YouTube and X support direct resumable uploads from a server or controlled client flow. The implementation should expose platform availability and approval status rather than claiming every connected account can publish automatically before the corresponding developer app is approved.
