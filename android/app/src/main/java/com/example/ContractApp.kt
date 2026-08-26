package com.example

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.contract.ApiContractClient
import com.example.data.contract.ApiJobState
import com.example.data.contract.ClipArtifact
import com.example.data.model.GatewayConfig
import com.example.data.model.ProcessingJobEntity
import com.example.data.repository.ContractJobRepository
import kotlinx.coroutines.launch
import java.io.File

private enum class AppRoute { HOME, IMPORT, PROCESSING, ERROR, RESULTS, REVIEW, SETTINGS }
private enum class BottomDestination { HOME, RESULTS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractApp(repository: ContractJobRepository) {
    var route by remember { mutableStateOf(AppRoute.HOME) }
    var selectedJobId by remember { mutableStateOf<String?>(null) }
    var selectedArtifact by remember { mutableStateOf<ClipArtifact?>(null) }
    val jobs by repository.processingJobs.collectAsState(initial = emptyList())
    val selectedJob = jobs.firstOrNull { it.jobId == selectedJobId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { repository.recoverActiveJobs() }
    LaunchedEffect(selectedJob?.status, route) {
        if (route == AppRoute.PROCESSING && selectedJob?.status == ProcessingJobEntity.STATUS_SUCCEEDED) route = AppRoute.RESULTS
        if (route == AppRoute.PROCESSING && selectedJob?.status == ProcessingJobEntity.STATUS_FAILED) route = AppRoute.ERROR
    }

    Scaffold(
        topBar = {
            if (route != AppRoute.HOME) {
                TopAppBar(
                    title = { Text(screenTitle(route)) },
                    navigationIcon = {
                        IconButton(onClick = { route = AppRoute.HOME }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                        }
                    }
                )
            } else {
                TopAppBar(title = { Text("ISM") })
            }
        },
        bottomBar = {
            if (route == AppRoute.HOME || route == AppRoute.RESULTS || route == AppRoute.SETTINGS) {
                NavigationBar {
                    BottomDestination.values().forEach { destination ->
                        NavigationBarItem(
                            selected = route.toDestination() == destination,
                            onClick = { route = destination.toRoute() },
                            icon = {
                                Icon(
                                    imageVector = when (destination) {
                                        BottomDestination.HOME -> Icons.Default.Home
                                        BottomDestination.RESULTS -> Icons.Default.Movie
                                        BottomDestination.SETTINGS -> Icons.Default.Settings
                                    },
                                    contentDescription = destination.name
                                )
                            },
                            label = { Text(destination.label()) }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                AppRoute.HOME -> HomeScreen(
                    jobs = jobs,
                    onImport = { route = AppRoute.IMPORT },
                    onOpenJob = { job ->
                        selectedJobId = job.jobId
                        route = when (job.status) {
                            ProcessingJobEntity.STATUS_SUCCEEDED -> AppRoute.RESULTS
                            ProcessingJobEntity.STATUS_FAILED -> AppRoute.ERROR
                            else -> AppRoute.PROCESSING
                        }
                    }
                )
                AppRoute.IMPORT -> ImportVideoScreen(
                    onCancel = { route = AppRoute.HOME },
                    onStart = { title, uri ->
                        scope.launch {
                            val jobId = repository.startJob(title, uri, "classic", "balanced")
                            selectedJobId = jobId
                            route = AppRoute.PROCESSING
                        }
                    }
                )
                AppRoute.PROCESSING -> ProcessingScreen(
                    job = selectedJob,
                    onCancel = {
                        selectedJobId?.let { id -> scope.launch { repository.cancel(id) } }
                    },
                    onRetry = {
                        selectedJobId?.let { id -> scope.launch { repository.retry(id) } }
                    }
                )
                AppRoute.ERROR -> ProcessingErrorScreen(
                    job = selectedJob,
                    onRetry = { selectedJobId?.let { id -> scope.launch { repository.retry(id); route = AppRoute.PROCESSING } } },
                    onResume = { selectedJobId?.let { id -> scope.launch { repository.resume(id); route = AppRoute.PROCESSING } } },
                    onHome = { route = AppRoute.HOME }
                )
                AppRoute.RESULTS -> ResultsScreen(
                    job = selectedJob,
                    repository = repository,
                    onReview = { artifact -> selectedArtifact = artifact; route = AppRoute.REVIEW },
                    onHome = { route = AppRoute.HOME }
                )
                AppRoute.REVIEW -> ClipReviewScreen(
                    job = selectedJob,
                    artifact = selectedArtifact,
                    repository = repository,
                    onBack = { route = AppRoute.RESULTS }
                )
                AppRoute.SETTINGS -> SettingsScreen(repository, snackbar)
            }
        }
    }
}

@Composable
private fun HomeScreen(jobs: List<ProcessingJobEntity>, onImport: () -> Unit, onOpenJob: (ProcessingJobEntity) -> Unit) {
    val active = jobs.filter { it.status == ProcessingJobEntity.STATUS_QUEUED || it.status == ProcessingJobEntity.STATUS_RUNNING }
    val completed = jobs.filter { it.status == ProcessingJobEntity.STATUS_SUCCEEDED || it.status == ProcessingJobEntity.STATUS_FAILED }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("محرر المقاطع الشخصي", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("ارفع فيديو، تابع المهمة بأمان، ثم راجع النتائج ونزّل المقاطع.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Movie, null)
                Spacer(Modifier.size(8.dp))
                Text("استيراد فيديو")
            }
        }
        if (active.isNotEmpty()) item { Text("قيد المعالجة", style = MaterialTheme.typography.titleLarge) }
        items(active, key = { it.jobId }) { job -> JobCard(job, onOpenJob, active = true) }
        if (completed.isNotEmpty()) item { Text("المهام السابقة", style = MaterialTheme.typography.titleLarge) }
        items(completed, key = { it.jobId }) { job -> JobCard(job, onOpenJob, active = false) }
        if (jobs.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text("لا توجد مهام بعد. ابدأ باستيراد أول فيديو.", Modifier.padding(18.dp))
            }
        }
    }
}

@Composable
private fun JobCard(job: ProcessingJobEntity, onOpenJob: (ProcessingJobEntity) -> Unit, active: Boolean) {
    Card(onClick = { onOpenJob(job) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (active) Icons.Default.Refresh else if (job.status == ProcessingJobEntity.STATUS_SUCCEEDED) Icons.Default.CheckCircle else Icons.Default.Error, null)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium)
                    Text(displayStage(job.currentStage), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${job.progress}%")
            }
            if (active) LinearProgressIndicator({ (job.progress / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            else Text(if (job.status == ProcessingJobEntity.STATUS_SUCCEEDED) "عرض النتائج" else "عرض الخطأ", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ImportVideoScreen(onCancel: () -> Unit, onStart: (String, Uri) -> Unit) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "فيديو جديد"
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("اختر ملف فيديو من الجهاز. سيبقى محرك المعالجة على Gateway فقط.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Movie, null); Spacer(Modifier.size(8.dp)); Text(if (selectedUri == null) "اختيار فيديو" else "تغيير الفيديو")
        }
        selectedUri?.let { uri ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("تم اختيار الملف"); Text(uri.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("اسم المهمة") }, singleLine = true)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("إلغاء") }
            Button(onClick = { selectedUri?.let { onStart(title, it) } }, enabled = selectedUri != null, modifier = Modifier.weight(1f)) { Text("بدء المعالجة") }
        }
    }
}

@Composable
private fun ProcessingScreen(job: ProcessingJobEntity?, onCancel: () -> Unit, onRetry: () -> Unit) {
    if (job == null) return
    val isWaiting = job.currentStage == "WAITING_FOR_NETWORK" || job.currentStage == ApiJobState.INTERRUPTED.name
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(job.title, style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isWaiting) Icon(Icons.Default.CloudOff, null) else CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayStage(job.currentStage), style = MaterialTheme.typography.titleMedium)
                        Text(if (job.errorMessage.isBlank()) "يتم تحديث الحالة من API…" else job.errorMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${job.progress}%")
                }
                LinearProgressIndicator({ (job.progress / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text("يمكنك إغلاق التطبيق؛ ستستعيد المهمة حالتها عند العودة.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), enabled = job.status != ProcessingJobEntity.STATUS_CANCELLED) {
                Icon(Icons.Default.StopCircle, null); Spacer(Modifier.size(6.dp)); Text("إلغاء")
            }
            if (isWaiting) OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("إعادة المحاولة") }
        }
    }
}

@Composable
private fun ProcessingErrorScreen(job: ProcessingJobEntity?, onRetry: () -> Unit, onResume: () -> Unit, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.Error, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.error)
        Text("تعذر إكمال المعالجة", style = MaterialTheme.typography.headlineSmall)
        Text(job?.errorMessage?.ifBlank { "خطأ غير معروف من Gateway" } ?: "لا توجد تفاصيل متاحة.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        job?.currentStage?.let { Text("المرحلة: ${displayStage(it)}") }
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("إعادة المحاولة") }
        if (!job?.remoteGatewayJobId.isNullOrBlank()) OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("استئناف من آخر checkpoint") }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("العودة للرئيسية") }
    }
}

@Composable
private fun ResultsScreen(job: ProcessingJobEntity?, repository: ContractJobRepository, onReview: (ClipArtifact) -> Unit, onHome: () -> Unit) {
    if (job == null) return
    val scope = rememberCoroutineScope()
    val artifacts by androidx.compose.runtime.produceState(initialValue = emptyList<ClipArtifact>(), job.jobId) { value = repository.artifacts(job.jobId) }
    val paths = remember { mutableStateMapOf<String, String>() }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(job.title, style = MaterialTheme.typography.headlineSmall)
        Text("${artifacts.size} مقطع متاح من المهمة ${job.remoteGatewayJobId.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        if (artifacts.isEmpty()) {
            Text("لم يُرجع Gateway أي artifact قابل للعرض.", color = MaterialTheme.colorScheme.error)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(artifacts, key = { it.id }) { artifact ->
                    val downloaded = paths[artifact.id]?.let(::File)?.isFile == true || repository.artifactFile(job.jobId, artifact).isFile
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(artifact.title, style = MaterialTheme.typography.titleMedium); Text("النتيجة ${artifact.score}%") }
                                Text("${artifact.durationSeconds}ث")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onReview(artifact) }) { Text("مراجعة") }
                                Button(onClick = { scope.launch { repository.downloadArtifact(job.jobId, artifact).onSuccess { paths[artifact.id] = it } } }) {
                                    Icon(Icons.Default.Download, null); Spacer(Modifier.size(5.dp)); Text(if (downloaded) "إعادة تنزيل" else "تنزيل")
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("العودة للرئيسية") }
    }
}

@Composable
private fun ClipReviewScreen(job: ProcessingJobEntity?, artifact: ClipArtifact?, repository: ContractJobRepository, onBack: () -> Unit) {
    if (job == null || artifact == null) return
    val scope = rememberCoroutineScope()
    var localPath by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableFloatStateOf(artifact.startSeconds.toFloat()) }
    var end by remember { mutableFloatStateOf(artifact.endSeconds.toFloat().coerceAtLeast(start + 1)) }
    var muted by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val existing = repository.artifactFile(job.jobId, artifact)
    if (localPath == null && existing.isFile) localPath = existing.absolutePath
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(artifact.title, style = MaterialTheme.typography.headlineSmall)
        Text("${artifact.score}% score · ${artifact.durationSeconds} ثانية", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (localPath != null) {
            AndroidView(
                factory = { context -> VideoView(context).apply { setVideoURI(Uri.fromFile(File(localPath!!))); setOnPreparedListener { player -> player.isLooping = true; if (!muted) start() } } },
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )
        } else {
            Card(Modifier.fillMaxWidth().height(220.dp)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("نزّل المقطع لمعاينته") } }
            Button(onClick = { scope.launch { repository.downloadArtifact(job.jobId, artifact).onSuccess { localPath = it } } }, modifier = Modifier.fillMaxWidth()) { Text("تنزيل ومعاينة") }
        }
        HorizontalDivider()
        Text("قص البداية: ${start.toInt()}ث")
        Slider(value = start, onValueChange = { start = it.coerceAtMost(end - 1f); saved = false }, valueRange = artifact.startSeconds.toFloat()..(end - 1f).coerceAtLeast(artifact.startSeconds.toFloat() + 1f))
        Text("قص النهاية: ${end.toInt()}ث")
        Slider(value = end, onValueChange = { end = it.coerceAtLeast(start + 1f); saved = false }, valueRange = (start + 1f).coerceAtMost(artifact.endSeconds.toFloat())..artifact.endSeconds.toFloat().coerceAtLeast(start + 1f))
        Row(verticalAlignment = Alignment.CenterVertically) { Text("كتم صوت المعاينة", Modifier.weight(1f)); Switch(muted, { muted = it; saved = false }) }
        Text("هذه عناصر تحكم معاينة محلية؛ لا تُرسل تعديلات غير مدعومة إلى API.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { saved = true }, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "تم حفظ إعدادات المعاينة" else "حفظ إعدادات المعاينة") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("العودة للنتائج") }
    }
}

@Composable
private fun SettingsScreen(repository: ContractJobRepository, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { repository.loadGatewayConfig() }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("اتصال Gateway", style = MaterialTheme.typography.headlineSmall)
        Text("يحتاج التطبيق إلى HTTPS في الإنتاج وBearer session token. لا تُحفظ أسرار مزودي الذكاء الاصطناعي على Android.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Gateway URL") }, singleLine = true)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Session token") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scope.launch { repository.saveGatewayConfig(GatewayConfig(baseUrl, token)); snackbar.showSnackbar("تم حفظ الإعدادات") } }, modifier = Modifier.weight(1f)) { Text("حفظ") }
            OutlinedButton(enabled = !loading, onClick = {
                scope.launch {
                    loading = true
                    repository.saveGatewayConfig(GatewayConfig(baseUrl, token))
                    status = ApiContractClient(context.contentResolver).health(repository.loadGatewayConfig()).fold({ "متصل: $it" }, { "فشل الاتصال: ${it.message}" })
                    loading = false
                }
            }, modifier = Modifier.weight(1f)) { if (loading) CircularProgressIndicator(Modifier.size(18.dp)) else Text("اختبار API") }
        }
        status?.let { Text(it, color = if (it.startsWith("متصل")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        Text("النسخة 1 · API prefix /v1", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun screenTitle(route: AppRoute) = when (route) {
    AppRoute.IMPORT -> "استيراد فيديو"
    AppRoute.PROCESSING -> "المعالجة"
    AppRoute.ERROR -> "خطأ المعالجة"
    AppRoute.RESULTS -> "النتائج"
    AppRoute.REVIEW -> "مراجعة المقطع"
    AppRoute.SETTINGS -> "الإعدادات"
    AppRoute.HOME -> "ISM"
}

private fun displayStage(stage: String) = when (stage.uppercase()) {
    "UPLOADING" -> "رفع الفيديو"
    "WAITING_FOR_NETWORK" -> "بانتظار الاتصال"
    "QUEUED" -> "في قائمة الانتظار"
    "PREPARING" -> "تحضير المصدر"
    "DOWNLOADING" -> "تنزيل المصدر"
    "INGESTING" -> "قراءة الفيديو"
    "TRANSCRIBING" -> "تفريغ الصوت"
    "ANALYZING" -> "تحليل المحتوى"
    "SCORING" -> "تقييم المقاطع"
    "EDITING" -> "تحرير المقاطع"
    "RENDERING" -> "إخراج المقاطع"
    "FINALIZING", "COMPLETED" -> "إنهاء النتائج"
    "FAILED" -> "فشلت المعالجة"
    "CANCELLED" -> "تم الإلغاء"
    else -> stage.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private fun AppRoute.toDestination() = when (this) {
    AppRoute.SETTINGS -> BottomDestination.SETTINGS
    AppRoute.RESULTS -> BottomDestination.RESULTS
    else -> BottomDestination.HOME
}
private fun BottomDestination.toRoute() = when (this) {
    BottomDestination.HOME -> AppRoute.HOME
    BottomDestination.RESULTS -> AppRoute.RESULTS
    BottomDestination.SETTINGS -> AppRoute.SETTINGS
}
private fun BottomDestination.label() = when (this) {
    BottomDestination.HOME -> "الرئيسية"
    BottomDestination.RESULTS -> "النتائج"
    BottomDestination.SETTINGS -> "الإعدادات"
}
