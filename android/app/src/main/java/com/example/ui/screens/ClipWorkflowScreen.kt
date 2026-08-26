package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.CardDefaults.outlinedCardColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.model.Clip
import com.example.data.model.GatewayConfig
import com.example.data.model.ProcessingJobEntity
import com.example.data.model.Project
import com.example.data.repository.OpusRepository
import com.example.domain.model.ClipEditState
import com.example.ui.components.DeviceGalleryVideoPicker
import com.example.ui.components.SelectedVideoData
import com.example.ui.theme.OpusBorder
import com.example.ui.theme.OpusDarkCanvas
import com.example.ui.theme.OpusDarkSurface
import com.example.ui.theme.OpusDarkSurfaceHighlight
import com.example.ui.theme.OpusDarkSurfaceVariant
import com.example.ui.theme.OpusElectricCyan
import com.example.ui.theme.OpusGold
import com.example.ui.theme.OpusHotPink
import com.example.ui.theme.OpusPrimaryViolet
import com.example.ui.theme.OpusTextPrimary
import com.example.ui.theme.OpusTextSecondary
import com.example.ui.theme.OpusViralEmerald
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

private enum class FlowScreen { HOME, IMPORT, PROCESSING, RESULTS, REVIEW, EDITOR, SETTINGS }

@Composable
fun OpusProApp(repository: OpusRepository) {
    var screenName by rememberSaveable { mutableStateOf(FlowScreen.HOME.name) }
    var selectedProjectId by rememberSaveable { mutableStateOf(0L) }
    var selectedClipId by rememberSaveable { mutableStateOf(0L) }
    var activeJobId by rememberSaveable { mutableStateOf("") }
    var selectedVideo by remember { mutableStateOf<SelectedVideoData?>(null) }
    var importError by rememberSaveable { mutableStateOf("") }
    var isEnqueuing by rememberSaveable { mutableStateOf(false) }
    val projects by repository.allProjects.collectAsStateWithLifecycle(initialValue = emptyList())
    val clips by repository.allClips.collectAsStateWithLifecycle(initialValue = emptyList())
    val jobs by repository.processingJobs.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
    val selectedClip = clips.firstOrNull { it.id == selectedClipId }
    val activeJob = jobs.firstOrNull { it.jobId == activeJobId }

    fun openProject(projectId: Long) {
        selectedProjectId = projectId
        selectedClipId = clips.firstOrNull { it.projectId == projectId }?.id ?: 0L
        screenName = FlowScreen.RESULTS.name
    }

    fun startProcessing(video: SelectedVideoData) {
        if (isEnqueuing) return
        selectedVideo = video
        importError = ""
        isEnqueuing = true
        scope.launch {
            runCatching {
                repository.enqueueVideoProcessing(
                    title = video.fileName.substringBeforeLast('.').ifBlank { "فيديو جديد" },
                    sourceUri = video.uri.toString(),
                    transcriptOrPrompt = "",
                    durationMinutes = (video.durationMs / 60_000L).toInt().coerceAtLeast(1),
                    targetPlatform = "TikTok & Reels (9:16)",
                    captionTheme = "Opus Neon",
                    processingMode = "balanced"
                )
            }.onSuccess { jobId ->
                activeJobId = jobId
                screenName = FlowScreen.PROCESSING.name
            }.onFailure { error ->
                importError = error.localizedMessage ?: "تعذر بدء المعالجة."
            }
            isEnqueuing = false
        }
    }

    fun navigate(screen: FlowScreen) { screenName = screen.name }

    Scaffold(
        containerColor = OpusDarkCanvas,
        topBar = {
            WorkflowHeader(
                screen = FlowScreen.valueOf(screenName),
                onBack = {
                    screenName = when (FlowScreen.valueOf(screenName)) {
                        FlowScreen.IMPORT, FlowScreen.SETTINGS -> FlowScreen.HOME.name
                        FlowScreen.PROCESSING -> FlowScreen.IMPORT.name
                        FlowScreen.RESULTS -> FlowScreen.HOME.name
                        FlowScreen.REVIEW -> FlowScreen.RESULTS.name
                        FlowScreen.EDITOR -> FlowScreen.REVIEW.name
                        FlowScreen.HOME -> FlowScreen.HOME.name
                    }
                },
                onHome = { navigate(FlowScreen.HOME) },
                onSettings = { navigate(FlowScreen.SETTINGS) }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (FlowScreen.valueOf(screenName)) {
                FlowScreen.HOME -> HomeWorkflowScreen(
                    projects = projects,
                    jobs = jobs,
                    onImport = { navigate(FlowScreen.IMPORT) },
                    onOpenProject = ::openProject,
                    onOpenJob = { jobId -> activeJobId = jobId; navigate(FlowScreen.PROCESSING) }
                )
                FlowScreen.IMPORT -> ImportWorkflowScreen(
                    selectedVideo = selectedVideo,
                    errorMessage = importError,
                    isEnqueuing = isEnqueuing,
                    onVideoSelected = { selectedVideo = it; importError = "" },
                    onStartProcessing = ::startProcessing
                )
                FlowScreen.PROCESSING -> ProcessingWorkflowScreen(
                    job = activeJob,
                    onCancel = { job -> scope.launch { repository.cancelVideoProcessing(job.jobId) } },
                    onRetry = { job -> scope.launch { repository.retryVideoProcessing(job.jobId) } },
                    onOpenResults = { projectId -> selectedProjectId = projectId; navigate(FlowScreen.RESULTS) }
                )
                FlowScreen.RESULTS -> ResultsWorkflowScreen(
                    project = selectedProject,
                    clips = clips.filter { it.projectId == selectedProjectId },
                    onReview = { clipId -> selectedClipId = clipId; navigate(FlowScreen.REVIEW) },
                    onImport = { navigate(FlowScreen.IMPORT) }
                )
                FlowScreen.REVIEW -> if (selectedProject != null && selectedClip != null) {
                    ClipReviewScreen(
                        project = selectedProject,
                        clip = selectedClip,
                        repository = repository,
                        onEdit = { navigate(FlowScreen.EDITOR) },
                        onBack = { navigate(FlowScreen.RESULTS) },
                        onExport = { file ->
                            scope.launch {
                                runCatching { repository.saveExportToMediaStore(file) }
                                    .onSuccess { uri ->
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "تصدير المقطع"))
                                    }
                            }
                        }
                    )
                } else EmptyStateScreen("لا يوجد مقطع محدد", "ارجع إلى النتائج واختر مقطعًا للمراجعة.")
                FlowScreen.EDITOR -> if (selectedProject != null && selectedClip != null) {
                    EditorWorkflowScreen(
                        project = selectedProject,
                        clip = selectedClip,
                        repository = repository,
                        onBack = { navigate(FlowScreen.REVIEW) },
                        onRendered = { navigate(FlowScreen.REVIEW) }
                    )
                } else EmptyStateScreen("لا يوجد مقطع للتحرير", "اختر مقطعًا من شاشة النتائج أولًا.")
                FlowScreen.SETTINGS -> SettingsWorkflowScreen(repository)
            }
        }
    }
}

@Composable
private fun WorkflowHeader(
    screen: FlowScreen,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit
) {
    val isRoot = screen == FlowScreen.HOME
    Row(
        Modifier.fillMaxWidth().background(OpusDarkCanvas).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isRoot) IconButton(onClick = onBack, modifier = Modifier.testTag("workflow_back")) {
            Icon(Icons.Default.ArrowBack, "رجوع", tint = OpusTextPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text("ISM", color = OpusElectricCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                when (screen) {
                    FlowScreen.HOME -> "حوّل فيديو طويلًا إلى clips جاهزة"
                    FlowScreen.IMPORT -> "استيراد فيديو"
                    FlowScreen.PROCESSING -> "المعالجة"
                    FlowScreen.RESULTS -> "النتائج"
                    FlowScreen.REVIEW -> "مراجعة المقطع"
                    FlowScreen.EDITOR -> "المحرر"
                    FlowScreen.SETTINGS -> "الإعدادات"
                },
                color = OpusTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
            )
        }
        if (!isRoot) IconButton(onClick = onHome) { Icon(Icons.Default.Home, "الرئيسية", tint = OpusTextSecondary) }
        IconButton(onClick = onSettings, modifier = Modifier.testTag("settings_button")) {
            Icon(Icons.Default.Settings, "الإعدادات", tint = OpusTextSecondary)
        }
    }
}

@Composable
private fun HomeWorkflowScreen(
    projects: List<Project>,
    jobs: List<ProcessingJobEntity>,
    onImport: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenJob: (String) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth().testTag("home_hero"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = OpusDarkSurfaceVariant)
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = OpusPrimaryViolet.copy(alpha = .22f), modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.AutoAwesome, null, tint = OpusVioletGlow(), modifier = Modifier.padding(12.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("من الطويل إلى القصير", color = OpusTextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Text("تحليل، اختيار، وتحرير في مسار واحد.", color = OpusTextSecondary)
                        }
                    }
                    Button(onClick = onImport, Modifier.fillMaxWidth().height(52.dp).testTag("home_import_cta"), colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet)) {
                        Icon(Icons.Default.VideoFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("اختيار فيديو والبدء")
                    }
                }
            }
        }
        if (jobs.any { it.status == ProcessingJobEntity.STATUS_RUNNING || it.status == ProcessingJobEntity.STATUS_QUEUED }) {
            item {
                val job = jobs.first { it.status == ProcessingJobEntity.STATUS_RUNNING || it.status == ProcessingJobEntity.STATUS_QUEUED }
                ProcessingMiniCard(job, onClick = { onOpenJob(job.jobId) })
            }
        }
        item { SectionTitle("المشاريع الأخيرة") }
        if (projects.isEmpty()) {
            item { EmptyStateScreen("المكتبة فارغة", "ابدأ باستيراد فيديو طويل، وستظهر clips هنا بعد اكتمال المعالجة.") }
        } else {
            items(projects.take(8), key = { it.id }) { project ->
                ProjectRow(project, onClick = { onOpenProject(project.id) })
            }
        }
    }
}

@Composable
private fun ImportWorkflowScreen(
    selectedVideo: SelectedVideoData?,
    errorMessage: String,
    isEnqueuing: Boolean,
    onVideoSelected: (SelectedVideoData) -> Unit,
    onStartProcessing: (SelectedVideoData) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("أحضر المصدر من الهاتف", color = OpusTextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("يُثبّت التطبيق الملف أولًا ثم يسلّمه إلى Worker في الخلفية.", color = OpusTextSecondary)
        }
        item {
            DeviceGalleryVideoPicker(
                onVideoSelected = onVideoSelected,
                onStartProcessing = onStartProcessing,
                isProcessing = isEnqueuing,
                modifier = Modifier.testTag("import_picker")
            )
        }
        item { WorkflowInfoCard("المسار", "المحرك المحلي للملفات المحلية، أو Gateway عند ضبطه من الإعدادات.") }
        if (errorMessage.isNotBlank()) item { ErrorCard(errorMessage, onRetry = null) }
        if (selectedVideo == null) item { EmptyStateScreen("لم تختر فيديو بعد", "الصيغ المدعومة تُفحص تلقائيًا قبل بدء المهمة.") }
    }
}

@Composable
private fun ProcessingWorkflowScreen(
    job: ProcessingJobEntity?,
    onCancel: (ProcessingJobEntity) -> Unit,
    onRetry: (ProcessingJobEntity) -> Unit,
    onOpenResults: (Long) -> Unit
) {
    LaunchedEffect(job?.status, job?.outputProjectId) {
        if (job?.status == ProcessingJobEntity.STATUS_SUCCEEDED && job.outputProjectId > 0L) onOpenResults(job.outputProjectId)
    }
    if (job == null) {
        LoadingStateScreen("جاري استعادة حالة المهمة…", "تُقرأ الحالة المحفوظة من Room.")
        return
    }
    val isActive = job.status == ProcessingJobEntity.STATUS_RUNNING || job.status == ProcessingJobEntity.STATUS_QUEUED
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth().testTag("processing_screen"), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isActive) CircularProgressIndicator(modifier = Modifier.size(42.dp), color = OpusElectricCyan, strokeWidth = 4.dp)
                        else Icon(if (job.status == ProcessingJobEntity.STATUS_FAILED) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, tint = if (job.status == ProcessingJobEntity.STATUS_FAILED) OpusHotPink else OpusViralEmerald, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(job.title, color = OpusTextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stageLabel(job.currentStage), color = OpusTextSecondary)
                        }
                        Text("${job.progress}%", color = OpusElectricCyan, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { (job.progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("processing_progress"), color = OpusElectricCyan, trackColor = OpusDarkSurfaceHighlight)
                    Text(job.errorMessage.ifBlank { "الحالة محفوظة ويمكن استعادتها عند إعادة فتح التطبيق." }, color = if (job.errorMessage.isBlank()) OpusTextSecondary else OpusHotPink)
                }
            }
        }
        item { WorkflowInfoCard("المرحلة الحالية", stageLabel(job.currentStage)) }
        if (job.status == ProcessingJobEntity.STATUS_FAILED) item { Button(onClick = { onRetry(job) }, modifier = Modifier.fillMaxWidth().testTag("processing_retry")) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("إعادة المحاولة") } }
        if (isActive) item { OutlinedButton(onClick = { onCancel(job) }, modifier = Modifier.fillMaxWidth().testTag("processing_cancel")) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text("إلغاء المعالجة") } }
    }
}

@Composable
private fun ResultsWorkflowScreen(project: Project?, clips: List<Clip>, onReview: (Long) -> Unit, onImport: () -> Unit) {
    if (project == null) {
        EmptyStateScreen("لا توجد نتائج محددة", "اختر مشروعًا من الرئيسية أو ابدأ باستيراد فيديو.", onAction = onImport)
        return
    }
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(project.title, color = OpusTextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${clips.size} clips • ${project.targetPlatform}", color = OpusTextSecondary)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill("الأفضل", "${project.bestViralityScore}", OpusGold, Modifier.weight(1f))
                MetricPill("النتائج", clips.size.toString(), OpusElectricCyan, Modifier.weight(1f))
                MetricPill("النمط", project.captionTheme, OpusVioletGlow(), Modifier.weight(1f))
            }
        }
        if (clips.isEmpty()) item { EmptyStateScreen("لم تُنتج clips بعد", "قد تكون المهمة ما زالت قيد المعالجة أو لم تُرجع نتائج صالحة.") }
        else items(clips, key = { it.id }) { clip -> ResultClipCard(clip, onClick = { onReview(clip.id) }) }
    }
}

@Composable
private fun ClipReviewScreen(
    project: Project,
    clip: Clip,
    repository: OpusRepository,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onExport: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportError by rememberSaveable(clip.id) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { VideoPreview(project.sourceUrl, clip.startTimeSec) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill("Score", "${clip.viralityScore}/100", scoreColor(clip.viralityScore), Modifier.weight(1f))
                MetricPill("الثقة", "غير متاحة", OpusTextSecondary, Modifier.weight(1f))
                MetricPill("المدة", formatDuration(clip.durationSec), OpusElectricCyan, Modifier.weight(1f))
            }
        }
        item {
            WorkflowInfoCard("ثقة التحليل", "لا يحمل عقد Clip الحالية قيمة confidence؛ لا يتم اشتقاق أو اختلاق نسبة بديلة.")
        }
        item { DetailCard("النص المستخرج", clip.transcript.ifBlank { "لا يوجد transcript محفوظ لهذا المقطع." }) }
        item { DetailCard("التوصية", project.targetPlatform, supporting = "التوصية المحفوظة مع المشروع من مسار التحليل.") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onEdit, Modifier.weight(1f).testTag("review_edit")) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(6.dp)); Text("تحرير") }
                OutlinedButton(
                    onClick = {
                        if (clip.exportPath.isBlank()) exportError = "صَيّر المقطع من المحرر أولًا قبل التصدير."
                        else scope.launch { runCatching { File(clip.exportPath) }.onSuccess(onExport).onFailure { exportError = "ملف التصدير غير متاح." } }
                    },
                    Modifier.weight(1f).testTag("review_export")
                ) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("تصدير") }
            }
        }
        if (exportError.isNotBlank()) item { ErrorCard(exportError, onRetry = null) }
        item { TextButton(onClick = onBack) { Text("العودة إلى النتائج") } }
    }
}

@Composable
private fun EditorWorkflowScreen(project: Project, clip: Clip, repository: OpusRepository, onBack: () -> Unit, onRendered: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val total = maxOf(project.sourceDurationSec, clip.endTimeSec, clip.durationSec + clip.startTimeSec, 1)
    var range by rememberSaveable(clip.id) { mutableStateOf(clip.startTimeSec.toFloat()..clip.endTimeSec.toFloat()) }
    var aspectRatio by rememberSaveable(clip.id) { mutableStateOf("9:16") }
    var cropCenter by rememberSaveable(clip.id) { mutableFloatStateOf(0f) }
    var captionsEnabled by rememberSaveable(clip.id) { mutableStateOf(true) }
    var captionPreset by rememberSaveable(clip.id) { mutableStateOf("Neon Pop") }
    var captionPosition by rememberSaveable(clip.id) { mutableStateOf("Bottom safe zone") }
    var captionStyle by rememberSaveable(clip.id) { mutableStateOf("Bold karaoke") }
    var renderId by rememberSaveable(clip.id) { mutableStateOf("") }
    var renderError by rememberSaveable(clip.id) { mutableStateOf("") }
    var renderProgress by rememberSaveable(clip.id) { mutableIntStateOf(0) }
    var isRendering by rememberSaveable(clip.id) { mutableStateOf(false) }

    LaunchedEffect(renderId) {
        if (renderId.isBlank()) return@LaunchedEffect
        val workId = runCatching { UUID.fromString(renderId) }.getOrNull() ?: return@LaunchedEffect
        WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).collect { info ->
            if (info == null) return@collect
            renderProgress = info.progress.getInt(com.example.data.worker.ClipRenderWorker.KEY_PROGRESS, renderProgress)
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> { isRendering = false; renderProgress = 100; onRendered() }
                WorkInfo.State.FAILED -> { isRendering = false; renderError = info.outputData.getString(com.example.data.worker.ClipRenderWorker.KEY_ERROR).orEmpty().ifBlank { "فشل تصيير المقطع." } }
                WorkInfo.State.CANCELLED -> { isRendering = false; renderError = "تم إلغاء التصيير." }
                else -> Unit
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item { VideoPreview(project.sourceUrl, range.start.roundToInt()) }
        item {
            EditorSection("1. البداية والنهاية", Icons.Default.ContentCut) {
                Text("${formatDuration(range.start.roundToInt())} — ${formatDuration(range.endInclusive.roundToInt())}", color = OpusElectricCyan, fontWeight = FontWeight.Bold)
                RangeSlider(value = range, onValueChange = { new -> range = new.start..new.endInclusive }, valueRange = 0f..total.toFloat(), steps = (total / 5).coerceAtMost(1000), modifier = Modifier.testTag("editor_trim_range"))
                Text("اسحب المقبضين لتحديد نافذة clip. لا يحدث render داخل الواجهة.", color = OpusTextSecondary, fontSize = 12.sp)
            }
        }
        item {
            EditorSection("2. Crop / Framing", Icons.Default.Tune) {
                ChoiceRow(listOf("9:16", "1:1", "4:5", "16:9"), aspectRatio, { aspectRatio = it }, "editor_aspect_ratio")
                Spacer(Modifier.height(4.dp))
                Text("موضع الإطار", color = OpusTextSecondary)
                Slider(value = cropCenter, onValueChange = { cropCenter = it }, valueRange = -1f..1f, modifier = Modifier.testTag("editor_crop_slider"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("يسار", color = OpusTextSecondary, fontSize = 12.sp); Text("وسط", color = OpusTextSecondary, fontSize = 12.sp); Text("يمين", color = OpusTextSecondary, fontSize = 12.sp) }
            }
        }
        item {
            EditorSection("3. Captions", Icons.Default.AutoAwesome) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("إظهار الكابشن", color = OpusTextPrimary); Switch(checked = captionsEnabled, onCheckedChange = { captionsEnabled = it }) }
                Text("Preset", color = OpusTextSecondary)
                ChoiceRow(listOf("Neon Pop", "Minimal", "Creator Bold"), captionPreset, { captionPreset = it }, "editor_caption_preset")
                Text("الموضع", color = OpusTextSecondary)
                ChoiceRow(listOf("Top safe zone", "Center", "Bottom safe zone"), captionPosition, { captionPosition = it }, "editor_caption_position")
                Text("الأسلوب", color = OpusTextSecondary)
                ChoiceRow(listOf("Bold karaoke", "Clean subtitle", "Highlight words"), captionStyle, { captionStyle = it }, "editor_caption_style")
            }
        }
        item {
            Button(
                enabled = !isRendering && range.endInclusive > range.start,
                onClick = {
                    renderError = ""
                    isRendering = true
                    renderProgress = 0
                    scope.launch {
                        runCatching {
                            repository.enqueueClipRender(
                                clip.id,
                                ClipEditState(
                                    startTimeSec = range.start.roundToInt(),
                                    endTimeSec = range.endInclusive.roundToInt().coerceAtLeast(range.start.roundToInt() + 1),
                                    aspectRatio = aspectRatio,
                                    cropCenterX = cropCenter,
                                    captionsEnabled = captionsEnabled,
                                    captionPreset = captionPreset,
                                    captionPosition = captionPosition,
                                    captionStyle = captionStyle
                                )
                            )
                        }.onSuccess { renderId = it.toString() }.onFailure { isRendering = false; renderError = it.localizedMessage ?: "تعذر جدولة التصيير." }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp).testTag("editor_render"),
                colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet)
            ) {
                if (isRendering) { CircularProgressIndicator(progress = { renderProgress / 100f }, modifier = Modifier.size(22.dp), color = OpusTextPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("تصيير $renderProgress%") }
                else { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Render في الخلفية") }
            }
        }
        if (renderError.isNotBlank()) item { ErrorCard(renderError, onRetry = null) }
        item { Text("يُرسل هذا edit state إلى ClipEditEngine ثم ClipRenderWorker؛ لا تُنفذ العمليات الثقيلة على Main/UI thread.", color = OpusTextSecondary, fontSize = 12.sp) }
    }
}

@Composable
private fun SettingsWorkflowScreen(repository: OpusRepository) {
    val scope = rememberCoroutineScope()
    val currentConfig by repository.gatewayConfig.collectAsStateWithLifecycle()
    var baseUrl by rememberSaveable(currentConfig.baseUrl) { mutableStateOf(currentConfig.baseUrl) }
    var token by rememberSaveable(currentConfig.token) { mutableStateOf(currentConfig.token) }
    var message by rememberSaveable { mutableStateOf("") }
    var isBusy by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item { Text("الإعدادات", color = OpusTextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("تحكم في مسار Gateway، مع بقاء الأسرار خارج الواجهة والـ logs.", color = OpusTextSecondary) }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Processing Gateway اختياري", color = OpusTextPrimary, fontWeight = FontWeight.Bold)
                    TextField(value = baseUrl, onValueChange = { baseUrl = it }, modifier = Modifier.fillMaxWidth().testTag("settings_gateway_url"), label = { Text("Base URL") }, singleLine = true)
                    TextField(value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth().testTag("settings_gateway_token"), label = { Text("Session token") }, singleLine = true)
                    Button(enabled = !isBusy, onClick = { isBusy = true; scope.launch { repository.saveGatewayConfig(GatewayConfig(baseUrl.trim(), token.trim())); val result = repository.testGatewayConnection(); message = result.fold({ "تم الاتصال: $it" }, { "تعذر الاتصال: ${it.localizedMessage}" }); isBusy = false } }, modifier = Modifier.fillMaxWidth().testTag("settings_save_gateway")) { Text(if (isBusy) "جارٍ الاختبار…" else "حفظ واختبار الاتصال") }
                }
            }
        }
        item { WorkflowInfoCard("الخصوصية", "يخزن Android إعداد Gateway القصير فقط. مفاتيح مزودي AI لا تُرسل إلى Gateway.") }
        if (message.isNotBlank()) item { DetailCard("نتيجة الاتصال", message) }
    }
}

@Composable
private fun VideoPreview(source: String, startSeconds: Int) {
    val context = LocalContext.current
    val player = remember(source) { ExoPlayer.Builder(context).build() }
    LaunchedEffect(source, startSeconds) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(source)))
        player.prepare()
        player.seekTo(startSeconds * 1000L)
        player.playWhenReady = false
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(context).apply { this.player = player; useController = true } },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black).testTag("video_preview")
    )
}

@Composable
private fun ResultClipCard(clip: Clip, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("result_clip_${clip.id}"), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = scoreColor(clip.viralityScore).copy(alpha = .18f), modifier = Modifier.size(42.dp)) { Text("${clip.viralityScore}", color = scoreColor(clip.viralityScore), modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(clip.title, color = OpusTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${formatDuration(clip.durationSec)} • ${clip.layoutType}", color = OpusTextSecondary, fontSize = 12.sp) }
                Icon(Icons.Default.ArrowForward, null, tint = OpusTextSecondary)
            }
            Text(clip.transcript.ifBlank { "لا يوجد نص محفوظ" }, color = OpusTextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, Modifier.fillMaxWidth().testTag("project_${project.id}"), colors = outlinedCardColors(containerColor = OpusDarkSurface, contentColor = OpusTextPrimary), border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VideoFile, null, tint = OpusElectricCyan, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(project.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${project.clipCount} clips • ${project.status}", color = OpusTextSecondary, fontSize = 12.sp) }; Icon(Icons.Default.ArrowForward, null, tint = OpusTextSecondary)
        }
    }
}

@Composable
private fun ProcessingMiniCard(job: ProcessingJobEntity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("active_job_card"), colors = CardDefaults.cardColors(containerColor = OpusDarkSurfaceHighlight), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp), color = OpusElectricCyan, strokeWidth = 3.dp); Spacer(Modifier.width(10.dp)); Text("معالجة مستمرة: ${job.title}", color = OpusTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }; LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = OpusElectricCyan) }
    }
}

@Composable
private fun EditorSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = OpusElectricCyan); Spacer(Modifier.width(8.dp)); Text(title, color = OpusTextPrimary, fontWeight = FontWeight.Bold) }; content() } }
}

@Composable
private fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit, tag: String) {
    Row(Modifier.horizontalScroll(rememberScrollState()).testTag(tag), horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { option -> FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option, fontSize = 12.sp) }) } }
}

@Composable
private fun MetricPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .12f)) { Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = OpusTextSecondary, fontSize = 11.sp); Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
}

@Composable
private fun SectionTitle(title: String) { Text(title, color = OpusTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) }

@Composable
private fun WorkflowInfoCard(title: String, body: String) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OpusDarkSurfaceVariant), shape = RoundedCornerShape(15.dp)) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, color = OpusElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(body, color = OpusTextSecondary, fontSize = 13.sp) } } }

@Composable
private fun DetailCard(title: String, body: String, supporting: String? = null) { OutlinedCard(Modifier.fillMaxWidth(), colors = outlinedCardColors(containerColor = OpusDarkSurface), border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, color = OpusTextPrimary, fontWeight = FontWeight.Bold); Text(body, color = OpusTextSecondary); if (supporting != null) Text(supporting, color = OpusTextSecondary, fontSize = 12.sp) } } }

@Composable
private fun ErrorCard(message: String, onRetry: (() -> Unit)?) { Card(Modifier.fillMaxWidth().testTag("error_state"), colors = CardDefaults.cardColors(containerColor = OpusHotPink.copy(alpha = .12f)), shape = RoundedCornerShape(15.dp)) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.WarningAmber, null, tint = OpusHotPink); Spacer(Modifier.width(9.dp)); Text(message, Modifier.weight(1f), color = OpusTextPrimary); if (onRetry != null) TextButton(onClick = onRetry) { Text("إعادة") } } } }

@Composable
private fun EmptyStateScreen(title: String, message: String, onAction: (() -> Unit)? = null) { Box(Modifier.fillMaxWidth().padding(vertical = 26.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) { Icon(Icons.Default.PauseCircle, null, tint = OpusTextSecondary, modifier = Modifier.size(40.dp)); Text(title, color = OpusTextPrimary, fontWeight = FontWeight.Bold); Text(message, color = OpusTextSecondary, fontSize = 13.sp); if (onAction != null) Button(onClick = onAction) { Text("استيراد فيديو") } } } }

@Composable
private fun LoadingStateScreen(title: String, message: String) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(color = OpusElectricCyan); Text(title, color = OpusTextPrimary, fontWeight = FontWeight.Bold); Text(message, color = OpusTextSecondary) } } }

private fun stageLabel(stage: String): String = when (stage.uppercase()) { "QUEUED" -> "في قائمة الانتظار"; "VALIDATING" -> "التحقق من المصدر"; "IMPORT" -> "استيراد الفيديو"; "TRANSCRIBING", "TRANSCRIPTION" -> "تحويل الصوت إلى نص"; "ANALYZING", "SEMANTIC_ANALYSIS" -> "تحليل المحتوى"; "CANDIDATES_READY", "CLIP_DETECTION" -> "اختيار المقاطع"; "SCORING", "VIRALITY_SCORING" -> "حساب الدرجات"; "RENDERING", "RENDERING_EXPORT" -> "تجهيز التصدير"; "COMPLETED" -> "اكتملت المعالجة"; "FAILED" -> "فشلت المعالجة"; "CANCELLED" -> "أُلغيت"; else -> stage.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } }

private fun formatDuration(seconds: Int): String { val safe = seconds.coerceAtLeast(0); return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}" }
private fun scoreColor(score: Int): Color = when { score >= 80 -> OpusViralEmerald; score >= 60 -> OpusGold; else -> OpusHotPink }
@Composable private fun OpusVioletGlow(): Color = Color(0xFFA855F7)
