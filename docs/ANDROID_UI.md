# Android UI and lifecycle

The Android experience is intentionally mobile-first and personal. It does not replicate the desktop navigation tree or expose SaaS concepts.

## Primary flow

```text
Home → Import → Generate → Processing → Results → Clip Review → Edit → Render → Export
```

Home shows the last project and connection status. Import uses Android Photo Picker/GetContent and copies the selected URI into app-private storage before background work begins. Processing shows server-reported stage/progress and supports cancel. Results lists the returned clip manifest. Review previews one clip and exposes trim/caption/style intent. Render asks the Gateway for the canonical output. Export saves/downloads the validated artifact to a user-visible destination.

## Lifecycle requirements

| Event | Required behavior |
|---|---|
| Activity closes | Work continues through WorkManager; state is in Room/local store. |
| Process death | On next launch, restore the local projection and reconcile with Gateway. |
| Network loss | Keep the job, show reconnect state, and retry with bounded exponential backoff. |
| Gateway restart | Poll the same immutable job ID; server recovery requeues resumable work. |
| User cancellation | Persist cancellation locally and remotely; do not silently requeue. |
| Recoverable failure | Offer retry/resume with the stored correlation ID and error category. |
| Completed job | Keep the manifest and downloaded clips available for review/export. |

## Security and accessibility

The client must not display or log bearer tokens. It must provide readable status text, clear error categories, progress that reflects the server rather than animation, touch targets appropriate for mobile, and an explicit connection/settings surface for the private Gateway.
