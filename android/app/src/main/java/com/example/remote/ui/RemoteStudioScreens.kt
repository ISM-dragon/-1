package com.example.remote.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.remote.model.GatewayJobState
import com.example.remote.model.LocalProcessingJob
import com.example.remote.model.RemoteClip
import com.example.remote.model.RemoteScreen
import com.example.remote.model.RemoteUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteStudioApp(viewModel: RemoteStudioViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            viewModel.getApplication<android.app.Application>().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.selectVideo(uri)
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbarHostState.showSnackbar(it); viewModel.dismissNotice() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ISM Studio", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (state.screen != RemoteScreen.HOME) IconButton(onClick = viewModel::openHome) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.screen == RemoteScreen.HOME,
                    onClick = viewModel::openHome,
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("الرئيسية") }
                )
                NavigationBarItem(
                    selected = state.screen == RemoteScreen.IMPORT,
                    onClick = viewModel::openImport,
                    icon = { Icon(Icons.Default.CloudUpload, null) },
                    label = { Text("استيراد") }
                )
                NavigationBarItem(
                    selected = state.screen == RemoteScreen.SETTINGS,
                    onClick = viewModel::openSettings,
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("الإعدادات") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            when (state.screen) {
                RemoteScreen.HOME -> HomeScreen(state, viewModel)
                RemoteScreen.IMPORT -> ImportVideoScreen(state, viewModel, onPick = { picker.launch(arrayOf("video/*")) })
                RemoteScreen.PROCESSING -> ProcessingScreen(state, viewModel)
                RemoteScreen.ERROR -> ProcessingErrorScreen(state, viewModel)
                RemoteScreen.RESULTS -> ResultsScreen(state, viewModel)
                RemoteScreen.REVIEW -> ClipReviewScreen(state, viewModel)
                RemoteScreen.SETTINGS -> SettingsScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun HomeScreen(state: RemoteUiState, viewModel: RemoteStudioViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("حوّل فيديوك إلى مقاطع جاهزة", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("اختر فيديو، ارفعه إلى Gateway الخاص بك، وتابع التقدم من أي جهاز دون تشغيل محرك محلي.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = viewModel::openImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CloudUpload, null)
            Spacer(Modifier.width(8.dp))
            Text("استيراد فيديو جديد")
        }
        state.job?.let { job ->
            JobSummaryCard(job, onOpen = {
                when (job.state) {
                    GatewayJobState.COMPLETED -> viewModel.openResults()
                    GatewayJobState.FAILED -> viewModel.openError()
                    else -> viewModel.openProcessing()
                }
            }) }
        if (state.connection == null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("اربط Gateway قبل بدء المعالجة", fontWeight = FontWeight.SemiBold)
                        Text("يمكن ضبط العنوان والرمز من الإعدادات.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = viewModel::openSettings) { Text("ضبط") }
                }
            }
        }
    }
}

@Composable
private fun ImportVideoScreen(state: RemoteUiState, viewModel: RemoteStudioViewModel, onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("استيراد فيديو", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("يُرفع الملف مباشرة إلى Gateway. لا تتم معالجة الفيديو داخل Android.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Movie, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.pickedVideo == null) "اختيار ملف فيديو" else "اختيار فيديو آخر")
        }
        state.pickedVideo?.let { video ->
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(video.displayName, fontWeight = FontWeight.SemiBold)
                    Text(if (video.bytes > 0) formatBytes(video.bytes) else "حجم الملف غير متاح", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(
            onClick = viewModel::startProcessing,
            enabled = state.pickedVideo != null && !state.isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("بدء المعالجة")
        }
    }
}

@Composable
private fun ProcessingScreen(state: RemoteUiState, viewModel: RemoteStudioViewModel) {
    val job = state.job
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("المعالجة جارية", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(job?.title ?: "فيديو جديد", maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stateLabel(job?.state ?: GatewayJobState.QUEUED), fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { (job?.progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${job?.progress ?: 0}%", style = MaterialTheme.typography.titleLarge)
                Text(job?.message?.takeIf { it.isNotBlank() } ?: "يتم تحديث الحالة من Gateway…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("المرحلة: ${job?.stage ?: "QUEUED"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("يمكنك إغلاق التطبيق؛ ستبقى المهمة محفوظة ويُستأنف الاستعلام عند عودة الاتصال.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth(), enabled = !state.isBusy) {
            Icon(Icons.Default.Cancel, null)
            Spacer(Modifier.width(8.dp))
            Text("إلغاء المهمة")
        }
    }
}

@Composable
private fun ProcessingErrorScreen(state: RemoteUiState, viewModel: RemoteStudioViewModel) {
    val job = state.job
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Text("تعذر إكمال المعالجة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(job?.errorMessage ?: "حدث خطأ غير متوقع.")
        job?.errorCode?.let { Text("رمز الخطأ: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (job?.recoverable == true) {
            Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth(), enabled = !state.isBusy) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("إعادة المحاولة")
            }
        }
        OutlinedButton(onClick = viewModel::openSettings, modifier = Modifier.fillMaxWidth()) { Text("فحص إعدادات Gateway") }
        TextButton(onClick = viewModel::openHome) { Text("العودة للرئيسية") }
    }
}

@Composable
private fun ResultsScreen(state: RemoteUiState, viewModel: RemoteStudioViewModel) {
    val clips = state.job?.clips.orEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("النتائج", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${clips.size} مقطع تم تنزيله والتحقق منه", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = viewModel::openImport) { Text("فيديو جديد") }
        }
        if (clips.isEmpty()) {
            Text("لم يُرجع Gateway مقاطع صالحة لهذه الوظيفة.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(clips, key = { it.id }) { clip ->
                    ClipCard(clip, onReview = { viewModel.selectClip(clip.id) })
                }
                item {
                    OutlinedButton(onClick = viewModel::forgetCompletedJob, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("إزالة نتائج هذه الجلسة")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ClipReviewScreen(state: RemoteUiState, viewModel: RemoteStudioViewModel) {
    val clip = state.job?.clips?.firstOrNull { it.id == state.selectedClipId } ?: state.job?.clips?.firstOrNull()
    if (clip == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) { Text("لا يوجد مقطع للمراجعة") }
        return
    }
    var start by remember(clip.id, clip.startSeconds) { mutableFloatStateOf(clip.startSeconds.toFloat()) }
    var end by remember(clip.id, clip.endSeconds) { mutableFloatStateOf(clip.endSeconds.toFloat()) }
    val max = maxOf(clip.endSeconds.toFloat(), clip.startSeconds.toFloat() + 1f, clip.durationSeconds.toFloat())
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("مراجعة المقطع", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (clip.localPath != null) {
                    AndroidView(
                        factory = { context ->
                            android.widget.VideoView(context).apply {
                                setVideoPath(clip.localPath)
                                setMediaController(android.widget.MediaController(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(150.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(52.dp))
                    }
                }
                Text(clip.title, fontWeight = FontWeight.SemiBold)
                Text("النتيجة: ${clip.score}/100", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("بداية المقطع: ${start.roundToInt()} ثانية")
        androidx.compose.material3.Slider(value = start, onValueChange = { start = it.coerceAtMost(end - 1f) }, valueRange = 0f..max)
        Text("نهاية المقطع: ${end.roundToInt()} ثانية")
        androidx.compose.material3.Slider(value = end, onValueChange = { end = it.coerceAtLeast(start + 1f) }, valueRange = 1f..max)
        clip.transcript.takeIf { it.isNotBlank() }?.let {
            Text("النص", fontWeight = FontWeight.SemiBold)
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { viewModel.updateClip(clip.id, start.roundToInt(), end.roundToInt()) }) { Text("حفظ القص") }
            OutlinedButton(onClick = viewModel::openResults) { Text("تم") }
        }
    }
}

@Composable
private fun SettingsScreen(state: RemoteUiState, viewModel: RemoteStudioViewModel) {
    var baseUrl by remember { mutableStateOf(viewModel.savedBaseUrl()) }
    var token by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("إعدادات Gateway", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("هذه هي بيانات جلسة Gateway فقط. لا تُدخل مفتاح Gemini هنا؛ يبقى على الخادم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(baseUrl, { baseUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Gateway URL") }, singleLine = true, placeholder = { Text("https://gateway.example") })
        OutlinedTextField(token, { token = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (viewModel.hasSavedToken()) "Gateway token (اتركه كما هو)" else "Gateway token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { viewModel.saveSettings(baseUrl, token) }, modifier = Modifier.weight(1f)) { Text("حفظ") }
            OutlinedButton(onClick = viewModel::testConnection, enabled = !state.isBusy, modifier = Modifier.weight(1f)) { Text("اختبار الاتصال") }
        }
        state.connection?.let { health ->
            Card(colors = CardDefaults.cardColors(containerColor = if (health.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (health.ok) "الاتصال ناجح" else "الاتصال غير جاهز", fontWeight = FontWeight.SemiBold)
                    Text(health.message)
                    Text("Gateway: ${health.gatewayReady} · Pipeline: ${health.pipelineReady} · FFmpeg: ${health.ffmpegReady}")
                }
            }
        }
        Text("الرمز محفوظ باستخدام Android Keystore ولا تُعرض قيمته في الواجهة.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JobSummaryCard(job: LocalProcessingJob, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("آخر مهمة", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(job.title, fontWeight = FontWeight.SemiBold)
            Text(stateLabel(job.state), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (job.isActive) LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text(if (job.state == GatewayJobState.COMPLETED) "فتح النتائج" else "فتح حالة المهمة", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ClipCard(clip: RemoteClip, onReview: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Movie, null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(clip.title, fontWeight = FontWeight.SemiBold)
                Text("${clip.startSeconds}s – ${clip.endSeconds}s · ${clip.score}/100", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onReview) { Text("مراجعة") }
        }
    }
}

private fun stateLabel(state: GatewayJobState): String = when (state) {
    GatewayJobState.QUEUED, GatewayJobState.RETRY_WAIT -> "في الانتظار"
    GatewayJobState.INTERRUPTED -> "توقفت مؤقتًا وستُستأنف"
    GatewayJobState.COMPLETED -> "اكتملت المعالجة"
    GatewayJobState.FAILED -> "فشلت المعالجة"
    GatewayJobState.CANCELLED -> "أُلغيت المهمة"
    else -> "جاري المعالجة"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
