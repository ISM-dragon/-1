package com.example.ui.client

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.GatewayConfig
import com.example.data.model.Project
import com.example.data.model.ProcessingJobEntity
import com.example.data.model.Clip
import com.example.data.repository.OpusRepository
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.OpusDarkCanvas
import com.example.ui.theme.OpusDarkSurface
import com.example.ui.theme.OpusDarkSurfaceHighlight
import com.example.ui.theme.OpusElectricCyan
import com.example.ui.theme.OpusHotPink
import com.example.ui.theme.OpusPrimaryViolet
import com.example.ui.theme.OpusTextPrimary
import com.example.ui.theme.OpusTextSecondary
import com.example.ui.theme.OpusViralEmerald
import com.example.ui.theme.OpusVioletGlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import java.io.File

private enum class ClientScreen {
    HOME, IMPORT, PROCESSING, RESULTS, REVIEW, SETTINGS
}

data class ImportDraft(
    val title: String = "",
    val transcript: String = "",
    val durationMinutes: String = "30",
    val platform: String = "TikTok & Reels (9:16)",
    val captions: String = "classic",
    val mode: String = "balanced"
)

class ClientFlowViewModel(application: Application) : AndroidViewModel(application) {
    val repository = OpusRepository(application)
    val jobs: StateFlow<List<ProcessingJobEntity>> = repository.processingJobs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val gatewayConfig: StateFlow<GatewayConfig> = repository.gatewayConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GatewayConfig())

    private val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _selectedJobId = MutableStateFlow(preferences.getString(KEY_SELECTED_JOB, null))
    val selectedJobId: StateFlow<String?> = _selectedJobId.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectJob(jobId: String) {
        _selectedJobId.value = jobId
        preferences.edit().putString(KEY_SELECTED_JOB, jobId).apply()
    }

    fun enqueue(draft: ImportDraft, sourceUri: Uri, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val minutes = draft.durationMinutes.toIntOrNull()?.coerceAtLeast(1)
                    ?: error("أدخل مدة الفيديو بالدقائق.")
                val title = draft.title.trim().ifBlank { "مشروع فيديو جديد" }
                val jobId = repository.enqueueVideoProcessing(
                    title = title,
                    sourceUri = sourceUri.toString(),
                    transcriptOrPrompt = draft.transcript.trim(),
                    durationMinutes = minutes,
                    targetPlatform = draft.platform,
                    captionTheme = draft.captions,
                    processingMode = draft.mode
                )
                selectJob(jobId)
                onCreated(jobId)
            } catch (error: Exception) {
                _message.value = error.localizedMessage ?: "تعذر إنشاء مهمة المعالجة."
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancel(jobId: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.cancelVideoProcessing(jobId) }
                .onFailure { _message.value = it.localizedMessage ?: "تعذر إلغاء المهمة." }
            _busy.value = false
        }
    }

    fun retry(jobId: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.retryVideoProcessing(jobId) }
                .onFailure { _message.value = it.localizedMessage ?: "تعذر إعادة المحاولة." }
            _busy.value = false
        }
    }

    fun saveGateway(baseUrl: String, token: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.saveGatewayConfig(GatewayConfig(baseUrl.trim(), token.trim())) }
                .onFailure { _message.value = it.localizedMessage ?: "تعذر حفظ إعدادات Gateway." }
            _busy.value = false
        }
    }

    fun clearMessage() { _message.value = null }

    companion object {
        private const val PREFS = "ism_android_client"
        private const val KEY_SELECTED_JOB = "selected_processing_job"
    }
}

@Composable
fun IsmClientApp(flowViewModel: ClientFlowViewModel = viewModel()) {
    MyApplicationTheme {
        ClientFlow(flowViewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientFlow(vm: ClientFlowViewModel) {
    var screenName by rememberSaveable { mutableStateOf(ClientScreen.HOME.name) }
    var selectedClipId by rememberSaveable { mutableStateOf<Long?>(null) }
    val jobs by vm.jobs.collectAsState()
    val selectedJobId by vm.selectedJobId.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val currentJob = jobs.firstOrNull { it.jobId == selectedJobId }
    val context = LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingExportPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    LaunchedEffect(jobs, screenName, selectedJobId) {
        val job = jobs.firstOrNull { it.jobId == selectedJobId }
        if (screenName == ClientScreen.HOME.name && job?.status == ProcessingJobEntity.STATUS_RUNNING) {
            screenName = ClientScreen.PROCESSING.name
        }
        if (screenName == ClientScreen.PROCESSING.name && job?.status == ProcessingJobEntity.STATUS_SUCCEEDED) {
            screenName = ClientScreen.RESULTS.name
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destination ->
        val sourcePath = pendingExportPath
        pendingExportPath = null
        if (destination != null && !sourcePath.isNullOrBlank()) {
            coroutineScope.launch {
                runCatching {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        File(sourcePath).inputStream().use { input ->
                            context.contentResolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                                ?: error("تعذر فتح ملف التصدير")
                        }
                    }
                }.onSuccess { snackbar.showSnackbar("تم تصدير المقطع بنجاح") }
                    .onFailure { snackbar.showSnackbar("تعذر تصدير المقطع: ${it.localizedMessage.orEmpty()}") }
            }
        }
    }
    val requestExport: (Clip) -> Unit = { clip ->
        if (clip.exportPath.isBlank()) {
            coroutineScope.launch { snackbar.showSnackbar("لا يوجد ملف MP4 صالح لهذا المقطع") }
        } else {
            pendingExportPath = clip.exportPath
            exportLauncher.launch("${clip.title.ifBlank { "ism_clip" }}.mp4")
        }
    }

    Scaffold(
        containerColor = OpusDarkCanvas,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (screenName != ClientScreen.IMPORT.name && screenName != ClientScreen.REVIEW.name) {
                ClientBottomBar(
                    screen = ClientScreen.valueOf(screenName),
                    onHome = { screenName = ClientScreen.HOME.name },
                    onSettings = { screenName = ClientScreen.SETTINGS.name }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ClientTopBar(
                screen = ClientScreen.valueOf(screenName),
                onBack = {
                    screenName = when (screenName) {
                        ClientScreen.IMPORT.name -> ClientScreen.HOME.name
                        ClientScreen.PROCESSING.name -> ClientScreen.HOME.name
                        ClientScreen.RESULTS.name -> ClientScreen.HOME.name
                        ClientScreen.REVIEW.name -> ClientScreen.RESULTS.name
                        ClientScreen.SETTINGS.name -> ClientScreen.HOME.name
                        else -> ClientScreen.HOME.name
                    }
                }
            )
            when (ClientScreen.valueOf(screenName)) {
                ClientScreen.HOME -> HomeClientScreen(
                    jobs = jobs,
                    selectedJobId = selectedJobId,
                    onImport = { screenName = ClientScreen.IMPORT.name },
                    onResume = { id -> vm.selectJob(id); screenName = ClientScreen.PROCESSING.name },
                    onResults = { id -> vm.selectJob(id); screenName = ClientScreen.RESULTS.name },
                    onSettings = { screenName = ClientScreen.SETTINGS.name }
                )
                ClientScreen.IMPORT -> ImportVideoScreen(
                    busy = busy,
                    onCancel = { screenName = ClientScreen.HOME.name },
                    onStart = { draft, uri -> vm.enqueue(draft, uri) { screenName = ClientScreen.PROCESSING.name } }
                )
                ClientScreen.PROCESSING -> ProcessingScreen(
                    job = currentJob,
                    busy = busy,
                    onCancel = { currentJob?.let { vm.cancel(it.jobId) } },
                    onRetry = { currentJob?.let { vm.retry(it.jobId) } },
                    onResults = { screenName = ClientScreen.RESULTS.name }
                )
                ClientScreen.RESULTS -> ResultsScreen(
                    vm = vm,
                    job = currentJob,
                    selectedClipId = selectedClipId,
                    onReview = { clip -> selectedClipId = clip.id; screenName = ClientScreen.REVIEW.name },
                    onExport = requestExport,
                    onRetry = { currentJob?.let { vm.retry(it.jobId) }; screenName = ClientScreen.PROCESSING.name },
                    context = context
                )
                ClientScreen.REVIEW -> ClipReviewScreen(
                    vm = vm,
                    projectId = currentJob?.outputProjectId ?: 0L,
                    clipId = selectedClipId ?: 0L,
                    onBack = { screenName = ClientScreen.RESULTS.name },
                    onExport = requestExport
                )
                ClientScreen.SETTINGS -> SettingsScreen(
                    config = vm.gatewayConfig.collectAsState().value,
                    busy = busy,
                    onSave = vm::saveGateway,
                    onBack = { screenName = ClientScreen.HOME.name }
                )
            }
        }
    }
}

@Composable
private fun ClientTopBar(screen: ClientScreen, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (screen != ClientScreen.HOME) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع", tint = OpusTextPrimary) }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("ISM", color = OpusVioletGlow, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(screenTitle(screen), color = OpusTextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (screen == ClientScreen.PROCESSING) {
            CircularProgressIndicator(Modifier.size(22.dp), color = OpusElectricCyan, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ClientBottomBar(screen: ClientScreen, onHome: () -> Unit, onSettings: () -> Unit) {
    Surface(color = OpusDarkSurface, tonalElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 34.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onHome) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Home, "الرئيسية", tint = if (screen == ClientScreen.HOME) OpusElectricCyan else OpusTextSecondary)
                    Text("الرئيسية", color = if (screen == ClientScreen.HOME) OpusElectricCyan else OpusTextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = onSettings) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Settings, "الإعدادات", tint = if (screen == ClientScreen.SETTINGS) OpusElectricCyan else OpusTextSecondary)
                    Text("الإعدادات", color = if (screen == ClientScreen.SETTINGS) OpusElectricCyan else OpusTextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun HomeClientScreen(
    jobs: List<ProcessingJobEntity>,
    selectedJobId: String?,
    onImport: () -> Unit,
    onResume: (String) -> Unit,
    onResults: (String) -> Unit,
    onSettings: () -> Unit
) {
    val active = jobs.firstOrNull { it.jobId == selectedJobId && it.status in setOf(ProcessingJobEntity.STATUS_QUEUED, ProcessingJobEntity.STATUS_RUNNING) }
    val recent = jobs.take(5)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(10.dp))
            Text("حوّل الفيديو إلى مقاطع جاهزة للنشر", color = OpusTextPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("يرسل التطبيق المصدر إلى Gateway ويتابع المهمة بأمان. لا يعمل Python أو WhisperX أو PyTorch داخل Android.", color = OpusTextSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet)
            ) {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(10.dp))
                Text("استيراد فيديو", fontWeight = FontWeight.Bold)
            }
        }
        if (active != null) {
            item {
                JobCard(active, onClick = { onResume(active.jobId) }, actionLabel = "متابعة المعالجة")
            }
        }
        if (recent.isNotEmpty()) {
            item {
                Text("المهام الأخيرة", color = OpusTextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(recent, key = { it.jobId }) { job ->
                JobCard(job, onClick = {
                    if (job.status == ProcessingJobEntity.STATUS_SUCCEEDED) onResults(job.jobId) else onResume(job.jobId)
                }, actionLabel = if (job.status == ProcessingJobEntity.STATUS_SUCCEEDED) "عرض النتائج" else "فتح المهمة")
            }
        }
        item {
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(8.dp))
                Text("إعداد Gateway")
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun JobCard(job: ProcessingJobEntity, onClick: () -> Unit, actionLabel: String) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = OpusDarkSurface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (job.status) {
                        ProcessingJobEntity.STATUS_SUCCEEDED -> Icons.Default.CheckCircle
                        ProcessingJobEntity.STATUS_FAILED -> Icons.Default.ErrorOutline
                        else -> Icons.Default.Refresh
                    }, null, tint = statusColor(job.status)
                )
                Spacer(Modifier.width(10.dp))
                Text(job.title, color = OpusTextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(actionLabel, color = OpusElectricCyan, style = MaterialTheme.typography.labelMedium)
            }
            Text(statusLabel(job.status), color = statusColor(job.status), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(
                progress = { job.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(8.dp)),
                color = statusColor(job.status),
                trackColor = OpusDarkSurfaceHighlight
            )
            Text("${job.progress}% · ${job.currentStage}", color = OpusTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImportVideoScreen(busy: Boolean, onCancel: () -> Unit, onStart: (ImportDraft, Uri) -> Unit) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(ImportDraft()) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            selectedUri = uri.toString()
            selectedName = displayName(context, uri)
            if (draft.title.isBlank()) draft = draft.copy(title = selectedName?.substringBeforeLast('.') ?: "مشروع فيديو جديد")
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("اختر المصدر من جهازك", color = OpusTextSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(selectedName ?: "اختيار ملف فيديو")
            }
        }
        item { ClientTextField("عنوان المشروع", draft.title) { draft = draft.copy(title = it) } }
        item { ClientTextField("المدة بالدقائق", draft.durationMinutes) { draft = draft.copy(durationMinutes = it.filter(Char::isDigit)) } }
        item { ClientTextField("ملاحظات أو سياق اختياري", draft.transcript, minLines = 3) { draft = draft.copy(transcript = it) } }
        item {
            Text("المنصة", color = OpusTextSecondary, style = MaterialTheme.typography.labelMedium)
            ChoiceRow(listOf("TikTok & Reels (9:16)", "YouTube Shorts", "LinkedIn"), draft.platform) { draft = draft.copy(platform = it) }
        }
        item {
            Text("نمط الترجمة", color = OpusTextSecondary, style = MaterialTheme.typography.labelMedium)
            ChoiceRow(listOf("classic", "minimal", "highlight"), draft.captions) { draft = draft.copy(captions = it) }
        }
        item {
            Text("وضع المعالجة", color = OpusTextSecondary, style = MaterialTheme.typography.labelMedium)
            ChoiceRow(listOf("fast", "balanced", "quality"), draft.mode) { draft = draft.copy(mode = it) }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { selectedUri?.let { onStart(draft, Uri.parse(it)) } },
                enabled = selectedUri != null && !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.ArrowForward, null)
                Spacer(Modifier.width(8.dp))
                Text("رفع وإنشاء مهمة", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("إلغاء", color = OpusTextSecondary) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ClientTextField(label: String, value: String, minLines: Int = 1, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = minLines, singleLine = minLines == 1)
}

@Composable
private fun ChoiceRow(options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { value ->
            val chosen = value == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelected(value) },
                shape = RoundedCornerShape(10.dp),
                color = if (chosen) OpusPrimaryViolet else OpusDarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (chosen) OpusVioletGlow else OpusDarkSurfaceHighlight)
            ) { Text(value, Modifier.padding(horizontal = 7.dp, vertical = 10.dp), color = OpusTextPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ProcessingScreen(job: ProcessingJobEntity?, busy: Boolean, onCancel: () -> Unit, onRetry: () -> Unit, onResults: () -> Unit) {
    if (job == null) {
        EmptyState("لا توجد مهمة محددة", "ارجع إلى الرئيسية واختر مهمة أو استورد فيديو جديداً.")
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(job.title, color = OpusTextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${job.progress}%", color = OpusElectricCyan, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { job.progress.coerceIn(0, 100) / 100f }, Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)), color = OpusElectricCyan, trackColor = OpusDarkSurfaceHighlight)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stageLabel(job.currentStage), color = OpusTextPrimary, fontWeight = FontWeight.SemiBold)
                Text("كل التقدم المعروض مصدره Gateway/WorkManager، ولا يتم تخمينه من العميل.", color = OpusTextSecondary, style = MaterialTheme.typography.bodySmall)
                Text("الحالة: ${statusLabel(job.status)}", color = statusColor(job.status), style = MaterialTheme.typography.labelMedium)
                if (job.errorMessage.isNotBlank()) Text(job.errorMessage, color = OpusHotPink, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        when (job.status) {
            ProcessingJobEntity.STATUS_SUCCEEDED -> Button(onClick = onResults, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = OpusViralEmerald)) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("عرض النتائج") }
            ProcessingJobEntity.STATUS_FAILED, ProcessingJobEntity.STATUS_CANCELLED -> {
                Button(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("إعادة المحاولة") }
            }
            else -> OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Default.StopCircle, null); Spacer(Modifier.width(8.dp)); Text("إلغاء المهمة") }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ResultsScreen(vm: ClientFlowViewModel, job: ProcessingJobEntity?, selectedClipId: Long?, onReview: (Clip) -> Unit, onExport: (Clip) -> Unit, onRetry: () -> Unit, context: Context) {
    if (job == null || job.outputProjectId <= 0L) {
        EmptyState("لا توجد نتائج", "لم تُرجع المهمة مشروعاً صالحاً بعد.")
        return
    }
    val project by vm.repository.getProjectById(job.outputProjectId).collectAsState(initial = null)
    val clips by vm.repository.getClipsForProject(job.outputProjectId).collectAsState(initial = emptyList())
    if (project == null) {
        LoadingState("تحميل النتائج…")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ResultSummary(project!!, clips.size) }
        if (clips.isEmpty()) {
            item { EmptyState("اكتملت المهمة دون مقاطع صالحة", "تحقق من نتيجة render في Gateway قبل فتح المراجعة.") }
            item { Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("إعادة المحاولة") } }
        } else {
            item { Text("المقاطع الناتجة", color = OpusTextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(clips, key = { it.id }) { clip ->
                ClipResultCard(clip, onReview = { onReview(clip) }, onExport = { onExport(clip) }, context = context)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ResultSummary(project: Project, clipCount: Int) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("اكتملت المعالجة", color = OpusViralEmerald, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(project.title, color = OpusTextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("$clipCount مقطع · أفضل درجة ${project.bestViralityScore}/100", color = OpusTextSecondary)
            Text("${project.targetPlatform} · ${project.captionTheme}", color = OpusTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ClipResultCard(clip: Clip, onReview: () -> Unit, onExport: () -> Unit, context: Context) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clip.title, color = OpusTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${clip.viralityScore}/100", color = scoreColor(clip.viralityScore), fontWeight = FontWeight.Bold)
            }
            Text("${formatSeconds(clip.startTimeSec)} – ${formatSeconds(clip.endTimeSec)} · ${clip.durationSec} ثانية", color = OpusTextSecondary, style = MaterialTheme.typography.bodySmall)
            if (clip.transcript.isNotBlank()) Text(clip.transcript, color = OpusTextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReview, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("مراجعة") }
                OutlinedButton(onClick = onExport, enabled = clip.exportPath.isNotBlank(), modifier = Modifier.weight(1f)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(5.dp)); Text("تصدير") }
            }
        }
    }
}

@Composable
private fun ClipReviewScreen(vm: ClientFlowViewModel, projectId: Long, clipId: Long, onBack: () -> Unit, onExport: (Clip) -> Unit) {
    val clip by vm.repository.getClipById(clipId).collectAsState(initial = null)
    if (clip == null) {
        LoadingState("تحميل المقطع…")
        return
    }
    val current = clip!!
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (current.exportPath.isNotBlank()) {
                VideoPreview(current.exportPath)
            } else {
                Surface(Modifier.fillMaxWidth().height(210.dp), color = OpusDarkSurface, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.PlayArrow, null, tint = OpusTextSecondary, modifier = Modifier.size(48.dp))
                        Text("لا يوجد ملف MP4 محلي للمعاينة", color = OpusTextSecondary)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(current.title, color = OpusTextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("درجة الانتشار: ${current.viralityScore}/100", color = scoreColor(current.viralityScore), fontWeight = FontWeight.Bold)
                    Text(current.hookExplanation, color = OpusTextSecondary)
                    Divider(color = OpusDarkSurfaceHighlight)
                    Text("النص", color = OpusTextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(current.transcript.ifBlank { "لا يوجد نص متاح من Gateway." }, color = OpusTextSecondary)
                }
            }
        }
        item {
            Button(onClick = { onExport(current) }, enabled = current.exportPath.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = OpusViralEmerald)) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("طلب render / تصدير MP4")
            }
            Text("يستخدم التصدير ملف MP4 الذي نزّله Worker من Gateway. لا ينفّذ Android عملية render محلية.", color = OpusTextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 7.dp))
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun VideoPreview(path: String) {
    val context = LocalContext.current
    AndroidViewVideo(path, context)
}

@Composable
private fun AndroidViewVideo(path: String, context: Context) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            VideoView(context).apply {
                setVideoPath(path)
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnPreparedListener { it.isLooping = false }
            }
        },
        modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp))
    )
}

@Composable
private fun SettingsScreen(config: GatewayConfig, busy: Boolean, onSave: (String, String) -> Unit, onBack: () -> Unit) {
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var token by remember(config.token) { mutableStateOf(config.token) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("إعدادات Gateway", color = OpusTextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("أدخل عنوان Gateway الخاص بك. لا تُخزّن مفاتيح مزوّد المعالجة داخل APK ولا تُرسل إلى الخادم.", color = OpusTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        item { ClientTextField("Base URL", baseUrl) { baseUrl = it } }
        item {
            OutlinedTextField(token, { token = it }, label = { Text("Bearer token (اختياري)") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
        item {
            Button(onClick = { onSave(baseUrl, token) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text("حفظ الإعدادات")
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("حدود Android", color = OpusElectricCyan, fontWeight = FontWeight.Bold)
                    Text("Android مسؤول عن اختيار الفيديو ورفعه ومتابعة job ID وتخزين الحالة والمعاينة والتصدير. Python وuv وWhisperX وPyTorch تبقى على Gateway/engine.", color = OpusTextSecondary)
                    Text("الاتصال خارج الشبكة المحلية يتطلب HTTPS.", color = OpusTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("رجوع", color = OpusTextSecondary) } }
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Column(Modifier.fillMaxSize().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, null, tint = OpusTextSecondary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = OpusTextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(message, color = OpusTextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun LoadingState(label: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = OpusElectricCyan)
        Spacer(Modifier.height(12.dp))
        Text(label, color = OpusTextSecondary)
    }
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0)
    }
    return uri.lastPathSegment ?: "video.mp4"
}

private fun screenTitle(screen: ClientScreen): String = when (screen) {
    ClientScreen.HOME -> "الرئيسية"
    ClientScreen.IMPORT -> "استيراد فيديو"
    ClientScreen.PROCESSING -> "المعالجة"
    ClientScreen.RESULTS -> "النتائج"
    ClientScreen.REVIEW -> "مراجعة المقطع"
    ClientScreen.SETTINGS -> "الإعدادات"
}

private fun statusLabel(status: String): String = when (status) {
    ProcessingJobEntity.STATUS_QUEUED -> "في الانتظار"
    ProcessingJobEntity.STATUS_RUNNING -> "قيد المعالجة"
    ProcessingJobEntity.STATUS_SUCCEEDED -> "اكتملت"
    ProcessingJobEntity.STATUS_FAILED -> "فشلت"
    ProcessingJobEntity.STATUS_CANCELLED -> "أُلغيت"
    else -> status
}

private fun stageLabel(stage: String): String = when (stage.uppercase()) {
    "UPLOADING" -> "رفع الفيديو إلى Gateway"
    "UPLOADED" -> "تم الرفع، بدء المهمة"
    "VALIDATING" -> "التحقق من المصدر"
    "INGEST", "INGESTING" -> "قراءة الفيديو"
    "ASR", "TRANSCRIBING" -> "استخراج النص"
    "DIARIZING" -> "تمييز المتحدثين"
    "CANDIDATES", "CANDIDATES_READY" -> "اكتشاف المرشحين"
    "SCORING" -> "تقييم المقاطع"
    "RENDER", "RENDERING" -> "إخراج ملفات MP4"
    "COMPLETED" -> "اكتملت المعالجة"
    else -> "جاري تنفيذ $stage"
}

private fun statusColor(status: String): Color = when (status) {
    ProcessingJobEntity.STATUS_SUCCEEDED -> OpusViralEmerald
    ProcessingJobEntity.STATUS_FAILED, ProcessingJobEntity.STATUS_CANCELLED -> OpusHotPink
    else -> OpusElectricCyan
}

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> OpusViralEmerald
    score >= 60 -> OpusElectricCyan
    else -> OpusHotPink
}

private fun formatSeconds(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
