# ISM Security Boundary

## Reporting

Report suspected vulnerabilities privately to the repository maintainers. Do not publish credentials, OAuth tokens, private media URLs, or unredacted diagnostic exports in issues.

## Runtime rules

Production Gateway deployments must use HTTPS, set `GATEWAY_TOKEN`, and enable `REQUIRE_GATEWAY_TOKEN=true`. Provider and OAuth secrets belong on the Gateway or its secret manager; they must not be placed in Android preferences, APK resources, BuildConfig, desktop bundles, or source control.

Externally supplied URLs are validated before source inspection or download. Loopback, private, link-local, reserved, and multicast destinations are rejected. Any future redirect-following downloader must revalidate the destination after every redirect and protect against DNS rebinding.

Uploaded and rendered artifacts are served only after path containment checks under the authorized source/job directory. File names are reduced to safe basenames. Media artifacts require existence, size, container, stream, and playback validation before publication.

Error responses and structured logs must omit authorization headers, provider secrets, refresh tokens, raw stack traces, private filesystem paths, and unredacted provider payloads. Unavailable provider metrics remain unavailable rather than being converted to fabricated values.

## Development mode

`PROVIDER_MODE=mock` is for local development only. Mock account and publication routes are explicitly labelled and disabled outside mock mode. A production deployment must fail or report `NOT_CONFIGURED` when live OAuth/provider prerequisites are absent; it must never display fake `CONNECTED`, `PUBLISHED`, or `COMPLETED` states.
