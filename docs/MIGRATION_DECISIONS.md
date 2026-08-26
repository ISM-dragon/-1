# سجل قرارات الترحيل

**النطاق:** SupoClip reference ZIP مقابل ISM / PublikClip.

**تاريخ المراجعة:** 2026-08-26.

| القرار | المكوّن أو الفكرة | القرار المطبق | الدليل |
|---|---|---|---|
| KEEP_CURRENT | Native Android client | الاحتفاظ بمشروع `android/` كعميل APK canonical | Compose، Room، WorkManager، Media3، وGateway client موجودة في المصدر. |
| KEEP_CURRENT | Private Gateway | الاحتفاظ بـ`gateway/` كـcontrol plane لمسار Android | يملك auth، upload، SQLite job state، worker، diagnostics، وmedia delivery. |
| KEEP_CURRENT | PublikClip stages | الاحتفاظ بالـPipeline stages والـcheckpoints | `pipeline/publikclip_pipeline/engine` يعرض contract v1 ويغطي lifecycle والاستئناف. |
| KEEP_CURRENT | ASR/diarization/media runtime | إبقاء Python/WhisperX/FFmpeg على الخادم الخاص | requirement صريح يمنع تشغيل desktop runtime داخل Android. |
| IMPROVE_CURRENT | Job lifecycle | توحيد الحالات والتحكم والـtransition history تدريجيًا | يلزم بقاء jobs بعد restart/network loss وعدم إعادة معالجة checkpoint صالح. |
| IMPROVE_CURRENT | Error handling | جعل error code/retryable/correlation جزءًا من الحدود العامة | يمنع تسرب stack traces ويتيح retry/resume آمنًا. |
| IMPROVE_CURRENT | Captions | فصل caption state عن render logic وإضافة presets versioned | يحقق karaoke/emphasis/readability دون إعادة transcription. |
| IMPROVE_CURRENT | Scoring | إضافة rubric أو signals versioned فقط بعد baseline | يحافظ على scoring الحالي ويمنع اعتماد LLM وحيدًا. |
| ADD_REFERENCE | Editor UX | استعارة trim/split/merge وflow الواضح من الويب كـAndroid UX جديد | لا تُنقل مكونات Next.js أو CSS أو state إلى Kotlin. |
| ADD_REFERENCE | Caption presets | إضافة أفكار قوالب العرض فقط إذا لم تكسر contract | المرجع غني بالقوالب، لكن التنفيذ يجب أن يظل server/render compatible. |
| COMBINE | Progress and worker model | Room/WorkManager على Android مع SQLite/worker على Gateway | لكل طرف lifecycle مختلف؛ لا يُستبدل أحدهما بالآخر. |
| COMBINE | Candidate selection | rubric المرجع كإشارة اختيارية مع scoring الحالي | يسمح بالقياس والرجوع إلى default السابق. |
| IGNORE_REFERENCE | accounts/billing/subscriptions | عدم نقلها | خارج هدف single-user APK ويزيد attack surface. |
| IGNORE_REFERENCE | Redis/ARQ topology | عدم إدخالها في Wave الحالية | SQLite + worker واحد كافيان لمستخدم واحد، والتوسع ليس شرطًا حاليًا. |
| IGNORE_REFERENCE | MCP/social/analytics | إبقاؤها اختيارية ومنفصلة | لا تمنع إنشاء clip ولا ينبغي أن تكون dependency للمسار الأساسي. |
| MANUAL_REVIEW | Copying code | لا نسخ مباشر من ZIP | المرجع AGPL-3.0؛ أي نقل لاحق يحتاج تحديد file-level notice وتوافق ترخيص. |
| MANUAL_REVIEW | External model/provider | لا إضافة provider افتراضي جديد | يتطلب مفاتيح، privacy review، cost/performance baseline، وfailure tests. |
| MANUAL_REVIEW | Tauri generated Android | لا دمج binary أو resources مع native Android | يوجد runtimeان؛ يجب اختيار identity/artifact واحد قبل أي release migration. |

## قرارات غير قابلة للتفاوض

لا يُنقل secret أو API key أو model artifact أو build output من المرجع. لا يُستبدل implementation حالي لمجرد اختلاف الحجم أو أسلوب التصميم. لا يُغلق أي blocker إنتاجي اعتمادًا على compilation أو mocks فقط. وأي دمج مستقبلـي يجب أن يضيف regression test وقياسًا عند تأثيره على correctness أو الأداء.

## References

[1]: REFERENCE_COMPARISON.md "Reference comparison"
[2]: REFERENCE_MIGRATION_PLAN.md "Migration plan"
[3]: ARCHITECTURE.md "Architecture baseline"
[4]: CONTRACTS.md "Contracts"
[5]: ../android/ "Native Android project"
[6]: ../gateway/ "Private Gateway"
[7]: ../pipeline/publikclip_pipeline/engine/ "PublikClip Engine"
