# مصفوفة الاختبار

| الحالة | الطبقة | الاختبار | الدليل المطلوب | الحالة الحالية |
|---|---|---|---|---|
| فيديو MP4 بصوت | pipeline/Gateway | ingest → render → artifact validation | pytest + smoke video | يحتاج host runtime فعلي |
| فيديو طويل | pipeline | checkpoints وعدم إعادة ASR | checkpoint trace ووقت المراحل | مخطط، يحتاج benchmark |
| ملف كبير | Android/Gateway | upload progress وresume | request offsets وSHA-256 | client main path one-shot؛ التحسين المستقبلي |
| بلا صوت | pipeline | ASR error مصنف ولا crash غامض | error code + regression | تغطية جزئية |
| media تالف | Gateway | `MEDIA_INVALID` | API error envelope | تغطية worker جزئية |
| متحدثون متعددون | pipeline | diarization/camera | score/render evidence | يحتاج models |
| كلام سريع | pipeline | timestamps وcaptions | caption regression | unit coverage موجودة |
| model مفقود | AI runtime | `MODEL_MISSING` وretry | diagnostics + log | يحتاج host setup |
| FFmpeg مفقود | media runtime | `FFMPEG_MISSING` | readiness response | Gateway diagnostics موجود |
| LLM غير متاح | scoring | fallback محلي وعدم crash | scoring output مع confidence | unit coverage جزئية |
| cancellation | Android/Gateway | cancel durable وnotification | Room + Gateway transition | code path موجود |
| resume | Android/Gateway | remote ID محفوظ وresume checkpoint | restart test | client regression مضاف |
| backend restart | Gateway | worker/state recovery | SQLite + worker evidence | موجود كاختبارات Gateway |
| network interruption | Android | WorkManager backoff | log + final state | يحتاج جهاز/خدمة مستقرة |
| Android process death | Android | reopen واستعادة job | device/emulator evidence | غير مثبت في sandbox |
| rendering failure | pipeline/Gateway | `FFMPEG_FAILED` أو render error | artifact absence + state | يحتاج media fixture |
| release APK | Android | lint، unit، assemble، signing/zip | build logs وSHA-256 | SDK غير موجود في sandbox الحالي |

لا تعتبر compilation وحدها قبولًا. كل فشل يجب أن يتبع: reproduce → fix → regression test → verify، مع حفظ السجل في `evidence/` وتحديث `MANUS_HANDOFF.md`.

## المراجع

[1]: ../evidence/ "Existing repository test evidence"
[2]: ../gateway/tests/ "Gateway lifecycle and safety tests"
[3]: ../pipeline/tests/ "Pipeline unit and regression tests"
[4]: ../android/app/src/test/ "Android unit and contract tests"
[5]: ../docs/FINAL-ACCEPTANCE.md "Existing acceptance criteria"

## References

المراجع محلية في المستودع.
