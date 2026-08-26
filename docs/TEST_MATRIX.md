# Test Matrix

## Automated checks

| النطاق | الأمر/الدليل | الغرض | الحالة الحالية |
|---|---|---|---|
| Identity | `python3 scripts/check_identity.py` | تثبيت product/version/API/Android ID | PASS في baseline |
| Python syntax | `python3 -m compileall -q pipeline/publikclip_pipeline backend gateway` | كشف أخطاء syntax/import سطحية | PASS |
| Gateway | `python3 -m pytest gateway/tests -q` | auth/upload/job/restart/failure contracts | متوقف مؤقتًا عند غياب pytest في البيئة، ويعاد بعد التثبيت |
| Backend | `python3 -m pytest backend/tests -q` | alternate service behavior | متوقف مؤقتًا عند غياب pytest في البيئة، ويعاد بعد التثبيت |
| Pipeline | `python3 -m pytest pipeline/tests -q` | boundaries/captions/FFmpeg/reframe/pipeline | متوقف مؤقتًا عند غياب pytest في البيئة، ويعاد بعد التثبيت |
| Frontend | `cd app && npm ci && npm run build` | TypeScript/Vite production build | PASS في baseline |
| Android unit | `cd android && ./gradlew :app:testDebugUnitTest` | contracts, repositories, workers, Compose units | يتطلب Android SDK في بيئة التنفيذ |
| Android lint | `./gradlew :app:lint` | static Android checks | يتطلب Android SDK |
| Release APK | `./gradlew :app:assembleRelease` | build artifact | يتطلب Android SDK؛ signing منفصل |

## Required behavior cases

| الحالة | الدليل المطلوب | معيار النجاح |
|---|---|---|
| normal video | job history + MP4 | يمر من ingest إلى export |
| long/large video | timing/RAM/disk log | لا يفشل بسبب حدود غير موثقة |
| no audio | job result/error | fallback واضح أو خطأ مصنف |
| broken media | error response | `MEDIA_INVALID` أو `UNSUPPORTED_FORMAT` |
| multiple speakers | diarization artifact | speakers مرتبطة بالكلمات |
| fast speech | transcript/captions | timestamps قابلة للعرض |
| missing model | diagnostics | `MODEL_MISSING` بلا crash مبهم |
| missing FFmpeg | diagnostics | `FFMPEG_MISSING` |
| LLM unavailable | fallback evidence | deterministic fallback أو `retryable` صحيح |
| cancellation | transition history | `CANCELLED` مع cleanup |
| resume | checkpoint + same job ID | لا تعاد المراحل الصالحة |
| restart | Gateway/Android restart log | استعادة الحالة والـartifact |
| network interruption | loss/recovery log | retry ثم polling لنفس job |
| rendering failure | error artifact | `FFMPEG_FAILED` أو سبب دقيق |
| Android process death | adb/logcat/screenshots | reopen يعرض job state الصحيح |

## Release acceptance

لا يعتبر APK مقبولًا للتوزيع إلا إذا نجحت unit/lint/build، وكان release APK موقعًا بمفتاح خارجي، ونُفّذ E2E حقيقي من اختيار فيديو إلى export على جهاز أو emulator مستقر، مع حفظ SHA-256 وlogs. نتائج mocks أو localhost لا تغلق شرط private production Gateway.
