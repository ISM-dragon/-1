# Test Matrix

**قاعدة القبول:** compilation وحده لا يثبت اكتمال feature. يجب ربط كل PASS بدليل قابل لإعادة الفحص، ولا تُحتسب mocks كدليل production.

| الفئة | السيناريو | الاختبار/الدليل الحالي | الحالة |
|---|---|---|---:|
| Build | Frontend typecheck وVite build | `npm run build` | PASS |
| Python | Gateway + pipeline regression | `python3 -m pytest -q` | PASS: 164، skipped: 1 |
| Engine | lifecycle/checkpoints/progress | `pipeline/tests/test_engine.py`, `test_queue.py` | PASS |
| Media | invalid/corrupt source | `gateway/tests/test_media_lifecycle.py`, smoke evidence | PASS |
| Upload | SHA-256 session/chunk/offset/complete | `ProcessingGatewayClient.uploadResumable`, Gateway `/v1/sources/uploads` contract | STATIC PASS؛ interruption E2E مطلوب على جهاز/Gateway فعلي |
| Failure | missing provider/model/FFmpeg/render | targeted failure suite | PASS للـclassification، لا يثبت production readiness |
| Resilience | Gateway restart | `evidence/gateway_restart_recovery.json` | PASS جزئيًا على Gateway |
| Resilience | network loss/recovery | `evidence/network_loss_observation.json` و`network_loss_recovery.json` | PASS جزئيًا على Gateway |
| Control | active cancel | `evidence/active_cancel.json` | PASS على Gateway |
| Control | retry/resume | retry evidence وcontract tests | PASS جزئيًا؛ لا يثبت E2E من Android |
| Upload recovery | network interruption during chunk upload | Gateway session dedupe by `(bytes, sha256)` + WorkManager retry | NOT RUN: يحتاج Gateway وجهاز فعلي |
| Android | unit tests | `:app:testDebugUnitTest` | يجب إعادة التشغيل في بيئة Android SDK/JDK مكتملة |
| Android | lint | `:app:lint` | يجب إعادة التشغيل في بيئة Android SDK/JDK مكتملة |
| Android | release assembly | `:app:assembleRelease` | يجب إعادة التشغيل في بيئة Android SDK/JDK مكتملة |
| Device | install/open/picker/preview/export | ADB device + screenshots/logcat | BLOCKED: لا جهاز متصل |
| E2E | APK → upload → full pipeline → export | job ID + stages + artifact hashes | BLOCKED: provider/device readiness |
| Release | signing verification | `apksigner verify --verbose` | BLOCKED حتى توفير release keystore |
| Large media | 100MB/500MB/1GB+ | `RUN_LARGE_MEDIA_TESTS=1` | SKIPPED عمدًا بسبب الموارد |

## سيناريوهات يجب إغلاقها قبل release

يجب تشغيل فيديو عادي وطويل وكبير، ومصدر بلا audio، وmedia فاسدة، وmulti-speaker، وfast speech، وmissing model، وmissing FFmpeg، وLLM unavailable، وcancellation، وresume، وbackend restart، وnetwork interruption، وAndroid process death، وrender failure. لكل failure يجب حفظ reproduction ثم fix ثم regression test ثم verification.

### المراجع

[1]: ../evidence/ "Verification evidence"
[2]: FINAL_ACCEPTANCE.md "Acceptance decision"
[3]: RELEASE_BLOCKERS.md "Open blockers"
[4]: ../.github/workflows/quality-gate.yml "CI quality gate"
[5]: ../.github/workflows/android-build.yml "Android CI"

## References

[1]: ../evidence/ "Verification evidence"
[2]: FINAL_ACCEPTANCE.md "Acceptance decision"
[3]: RELEASE_BLOCKERS.md "Open blockers"
[4]: ../.github/workflows/quality-gate.yml "CI quality gate"
[5]: ../.github/workflows/android-build.yml "Android CI"
