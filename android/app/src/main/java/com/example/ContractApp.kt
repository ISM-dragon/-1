package com.example

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private enum class AppRoute { ONBOARDING, HOME, IMPORT, PROCESSING, ERROR, RESULTS, REVIEW, SETTINGS, ABOUT, PRIVACY }
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
    
    val prefs = repository.context.getSharedPreferences("onboarding", 0)
    val hasSeenOnboarding = remember { prefs.getBoolean("seen_v1", false) }
    
    LaunchedEffect(Unit) { 
        repository.recoverActiveJobs()
        if (!hasSeenOnboarding) route = AppRoute.ONBOARDING
    }
    LaunchedEffect(selectedJob?.status, route) {
        if (route == AppRoute.PROCESSING && selectedJob?.status == ProcessingJobEntity.STATUS_SUCCEEDED) route = AppRoute.RESULTS
        if (route == AppRoute.PROCESSING && selectedJob?.status == ProcessingJobEntity.STATUS_FAILED) route = AppRoute.ERROR
    }

    Scaffold(
        topBar = {
            if (route != AppRoute.HOME && route != AppRoute.ONBOARDING) {
                TopAppBar(
                    title = { Text(screenTitle(route), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { route = if (route == AppRoute.ABOUT || route == AppRoute.PRIVACY) AppRoute.SETTINGS else AppRoute.HOME }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (route == AppRoute.HOME || route == AppRoute.RESULTS || route == AppRoute.SETTINGS) {
                NavigationBar {
                    BottomDestination.values().forEach { dest ->
                        NavigationBarItem(
                            selected = route.toDestination() == dest,
                            onClick = { route = dest.toRoute() },
                            icon = {
                                Icon(
                                    when (dest) {
                                        BottomDestination.HOME -> if (route.toDestination() == dest) Icons.Filled.Home else Icons.Outlined.Home
                                        BottomDestination.RESULTS -> if (route.toDestination() == dest) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary
                                        BottomDestination.SETTINGS -> if (route.toDestination() == dest) Icons.Filled.Settings else Icons.Outlined.Settings
                                    }, null
                                )
                            },
                            label = { Text(dest.label()) }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                AppRoute.ONBOARDING -> OnboardingScreen(onComplete = { prefs.edit().putBoolean("seen_v1", true).apply(); route = AppRoute.HOME })
                AppRoute.HOME -> HomeScreenV1(jobs, repository, onImport = { route = AppRoute.IMPORT }, onOpenJob = { job -> selectedJobId = job.jobId; route = when (job.status) { ProcessingJobEntity.STATUS_SUCCEEDED -> AppRoute.RESULTS; ProcessingJobEntity.STATUS_FAILED -> AppRoute.ERROR; else -> AppRoute.PROCESSING } }, onSettings = { route = AppRoute.SETTINGS })
                AppRoute.IMPORT -> ImportVideoScreenV1(onCancel = { route = AppRoute.HOME }, onStart = { title, uri, mode -> scope.launch { try { val jobId = repository.startJob(title, uri, "classic", mode); selectedJobId = jobId; route = AppRoute.PROCESSING } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "تعذر بدء المعالجة") } } })
                AppRoute.PROCESSING -> ProcessingScreenV1(job = selectedJob, onCancel = { selectedJobId?.let { id -> scope.launch { repository.cancel(id) } } }, onRetry = { selectedJobId?.let { id -> scope.launch { repository.retry(id) } } })
                AppRoute.ERROR -> ProcessingErrorScreenV1(job = selectedJob, onRetry = { selectedJobId?.let { id -> scope.launch { repository.retry(id); route = AppRoute.PROCESSING } } }, onResume = { selectedJobId?.let { id -> scope.launch { repository.resume(id); route = AppRoute.PROCESSING } } }, onHome = { route = AppRoute.HOME })
                AppRoute.RESULTS -> ResultsScreenV1(job = selectedJob, jobs = jobs, repository = repository, snackbar = snackbar, onReview = { artifact -> selectedArtifact = artifact; route = AppRoute.REVIEW }, onHome = { route = AppRoute.HOME }, onSelectJob = { job -> selectedJobId = job.jobId })
                AppRoute.REVIEW -> ClipReviewScreenV1(job = selectedJob, artifact = selectedArtifact, repository = repository, onBack = { route = AppRoute.RESULTS })
                AppRoute.SETTINGS -> SettingsScreenV1(repository, snackbar, onAbout = { route = AppRoute.ABOUT }, onPrivacy = { route = AppRoute.PRIVACY })
                AppRoute.ABOUT -> AboutScreenV1()
                AppRoute.PRIVACY -> PrivacyScreenV1()
            }
        }
    }
}

@Composable
private fun OnboardingScreen(onComplete: () -> Unit) {
    var currentPage by remember { mutableStateOf(0) }
    val pages = listOf(
        Triple(Icons.Filled.RocketLaunch, "مرحباً في ISM v1", "محرر المقاطع الذكي - حول الفيديوهات الطويلة إلى مقاطع قصيرة جذابة"),
        Triple(Icons.Filled.Cloud, "بوابة مجانية جاهزة 🆓", "لا حاجة لإعداد خادم! بوابة مجانية على Fly.io و Render تعمل بدون مشاكل"),
        Triple(Icons.Filled.AutoAwesome, "ذكاء اصطناعي متقدم", "تحليل تلقائي، تقييم الجاذبية، وقص ذكي للمقاطع"),
        Triple(Icons.Filled.Share, "نشر تلقائي", "نشر تلقائي على TikTok, Instagram, YouTube Shorts مع جدولة ذكية")
    )
    
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Spacer(Modifier.height(20.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(pages[currentPage].first, null, Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(pages[currentPage].second, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(pages[currentPage].third, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
            if (currentPage == 1) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✅ مجاني 100% - لا حاجة لبطاقة", style = MaterialTheme.typography.bodyMedium)
                        Text("✅ يعمل تلقائياً بدون إعداد", style = MaterialTheme.typography.bodyMedium)
                        Text("✅ Fly.io + Render + Railway", style = MaterialTheme.typography.bodyMedium)
                        Text("✅ معالجة خفيفة بـ Gemini API", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                pages.forEachIndexed { index, _ ->
                    Box(Modifier.padding(4.dp).size(if (index == currentPage) 24.dp else 8.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(if (index == currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (currentPage > 0) OutlinedButton(onClick = { currentPage-- }, modifier = Modifier.weight(1f)) { Text("السابق") }
                Button(onClick = { if (currentPage < pages.size - 1) currentPage++ else onComplete() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text(if (currentPage < pages.size - 1) "التالي" else "ابدأ الآن 🚀", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun HomeScreenV1(jobs: List<ProcessingJobEntity>, repository: ContractJobRepository, onImport: () -> Unit, onOpenJob: (ProcessingJobEntity) -> Unit, onSettings: () -> Unit) {
    val active = jobs.filter { it.status == ProcessingJobEntity.STATUS_QUEUED || it.status == ProcessingJobEntity.STATUS_RUNNING }
    val completed = jobs.filter { it.status == ProcessingJobEntity.STATUS_SUCCEEDED }
    val failed = jobs.filter { it.status == ProcessingJobEntity.STATUS_FAILED }
    val gatewayConfig = remember { repository.loadGatewayConfig() }
    val isFreeGateway = gatewayConfig.baseUrl.contains("fly.dev") || gatewayConfig.baseUrl.contains("onrender.com") || gatewayConfig.baseUrl.contains("free") || gatewayConfig.baseUrl.isBlank()
    
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), MaterialTheme.colorScheme.background))).padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("ISM", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary); Text("محرر المقاطع الذكي v1", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (isFreeGateway) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Filled.Cloud, null, Modifier.size(16.dp), tint = if (isFreeGateway) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (isFreeGateway) "مجاني 🆓" else "مخصص", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isFreeGateway) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("${jobs.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("مهمة", style = MaterialTheme.typography.labelSmall) } }
                        Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("${completed.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("مكتمل", style = MaterialTheme.typography.labelSmall) } }
                        Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("${completed.size * 3}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("مقطع", style = MaterialTheme.typography.labelSmall) } }
                    }
                    Card(onClick = onImport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, null, Modifier.size(28.dp), tint = Color.White) }
                            Column(Modifier.weight(1f)) { Text("استيراد فيديو جديد", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White); Text("حول فيديو طويل إلى مقاطع قصيرة", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f)) }
                            Icon(Icons.Filled.ArrowForward, null, tint = Color.White)
                        }
                    }
                    if (isFreeGateway) {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.1f))) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                                Column { Text("بوابة مجانية نشطة 🆓", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF10B981)); Text("تعمل على Fly.io/Render بدون مشاكل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
        if (active.isNotEmpty()) {
            item { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Filled.HourglassTop, null, tint = Color(0xFFF59E0B)); Text("قيد المعالجة (${active.size})", fontWeight = FontWeight.Bold) } }
            items(active, key = { it.jobId }) { job -> JobCardV1(job, onOpenJob, active = true, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        }
        if (completed.isNotEmpty()) {
            item { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF10B981)); Text("مكتملة (${completed.size})", fontWeight = FontWeight.Bold) } }
            items(completed.take(5), key = { it.jobId }) { job -> JobCardV1(job, onOpenJob, active = false, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        }
        if (failed.isNotEmpty()) {
            item { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Filled.Error, null, tint = Color(0xFFEF4444)); Text("فشلت (${failed.size})", fontWeight = FontWeight.Bold) } }
            items(failed.take(3), key = { it.jobId }) { job -> JobCardV1(job, onOpenJob, active = false, isFailed = true, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        }
        if (jobs.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(100.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.VideoLibrary, null, Modifier.size(50.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
                    Text("لا توجد مهام بعد", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("ابدأ بتحويل أول فيديو", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Button(onClick = onImport, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("استيراد أول فيديو") }
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            Text("إجراءات سريعة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Card(onClick = onSettings, modifier = Modifier.width(120.dp), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp)) { Icon(Icons.Filled.Settings, null, tint = Color(0xFF6366F1)); Spacer(Modifier.height(8.dp)); Text("الإعدادات", fontWeight = FontWeight.Bold); Text("البوابة", style = MaterialTheme.typography.labelSmall) } } }
                item { Card(modifier = Modifier.width(120.dp), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp)) { Icon(Icons.Filled.VideoLibrary, null, tint = Color(0xFF10B981)); Spacer(Modifier.height(8.dp)); Text("المكتبة", fontWeight = FontWeight.Bold); Text("جميع المقاطع", style = MaterialTheme.typography.labelSmall) } } }
                item { Card(modifier = Modifier.width(120.dp), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp)) { Icon(Icons.Filled.Share, null, tint = Color(0xFFF59E0B)); Spacer(Modifier.height(8.dp)); Text("النشر", fontWeight = FontWeight.Bold); Text("تلقائي", style = MaterialTheme.typography.labelSmall) } } }
            }
            Spacer(Modifier.height(100.dp))
        }
    }
}

@Composable
private fun JobCardV1(job: ProcessingJobEntity, onOpenJob: (ProcessingJobEntity) -> Unit, active: Boolean, isFailed: Boolean = false, modifier: Modifier = Modifier) {
    Card(onClick = { onOpenJob(job) }, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = when { isFailed -> Color(0xFFEF4444).copy(alpha = 0.08f); active -> Color(0xFFF59E0B).copy(alpha = 0.08f); else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) })) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(when { isFailed -> Color(0xFFEF4444).copy(alpha = 0.15f); active -> Color(0xFFF59E0B).copy(alpha = 0.15f); else -> Color(0xFF10B981).copy(alpha = 0.15f) }), contentAlignment = Alignment.Center) {
                    Icon(when { isFailed -> Icons.Filled.Error; active -> Icons.Filled.HourglassTop; else -> Icons.Filled.CheckCircle }, null, tint = when { isFailed -> Color(0xFFEF4444); active -> Color(0xFFF59E0B); else -> Color(0xFF10B981) })
                }
                Column(Modifier.weight(1f)) { Text(job.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1); Text(displayStage(job.currentStage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("${job.progress}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            if (active) LinearProgressIndicator(progress = { (job.progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
        }
    }
}

@Composable
private fun ImportVideoScreenV1(onCancel: () -> Unit, onStart: (String, Uri, String) -> Unit) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf("balanced") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { selectedUri = uri; title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "فيديو جديد" } }
    
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("استيراد فيديو", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("اختر فيديو طويل لتحويله إلى مقاطع قصيرة", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Card(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth().height(180.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (selectedUri == null) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color(0xFF10B981).copy(alpha = 0.1f))) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (selectedUri == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.CloudUpload, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) }
                            Text("اضغط لاختيار فيديو", fontWeight = FontWeight.Bold); Text("MP4, MOV, WebM - حتى 500MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(48.dp), tint = Color(0xFF10B981)); Text("تم اختيار الفيديو ✓", fontWeight = FontWeight.Bold, color = Color(0xFF10B981)); TextButton(onClick = { picker.launch(arrayOf("video/*")) }) { Text("تغيير") }
                        }
                    }
                }
            }
        }
        item { OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("اسم المهمة") }, singleLine = true, shape = RoundedCornerShape(12.dp)) }
        item {
            Text("وضع المعالجة", fontWeight = FontWeight.Bold)
            listOf(Triple("fast", "سريع ⚡", "2-3 دقائق"), Triple("balanced", "متوازن ⚖️", "5-7 دقائق - موصى به"), Triple("quality", "جودة عالية 🎯", "10-15 دقيقة")).forEach { (mode, name, desc) ->
                Card(onClick = { selectedMode = mode }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = if (selectedMode == mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = selectedMode == mode, onClick = { selectedMode = mode }); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold); Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("إلغاء") }
                Button(onClick = { selectedUri?.let { onStart(title.ifBlank { "فيديو جديد" }, it, selectedMode) } }, enabled = selectedUri != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Filled.RocketLaunch, null); Spacer(Modifier.width(8.dp)); Text("بدء", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ProcessingScreenV1(job: ProcessingJobEntity?, onCancel: () -> Unit, onRetry: () -> Unit) {
    if (job == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val isWaiting = job.currentStage == "WAITING_FOR_NETWORK" || job.currentStage == ApiJobState.INTERRUPTED.name
    val progressAnim by animateFloatAsState(targetValue = job.progress / 100f, animationSpec = tween(1000), label = "progress")
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { if (isWaiting) Icon(Icons.Default.CloudOff, null) else CircularProgressIndicator(Modifier.size(28.dp)) }
            Column(Modifier.weight(1f)) { Text(job.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(displayStage(job.currentStage), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("التقدم", fontWeight = FontWeight.Bold); Text("${job.progress}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                LinearProgressIndicator(progress = { progressAnim }, modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)))
                Text("يمكنك إغلاق التطبيق - ستستمر المعالجة في الخلفية", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("إلغاء") }
            if (isWaiting) Button(onClick = onRetry, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("إعادة المحاولة") }
        }
    }
}

@Composable
private fun ProcessingErrorScreenV1(job: ProcessingJobEntity?, onRetry: () -> Unit, onResume: () -> Unit, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(100.dp).clip(CircleShape).background(Color(0xFFEF4444).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Error, null, Modifier.size(50.dp), tint = Color(0xFFEF4444)) }
        Text("تعذر إكمال المعالجة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(job?.errorMessage?.ifBlank { "البوابة المجانية قد تكون مشغولة" } ?: "لا توجد تفاصيل", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("إعادة المحاولة") }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("الرئيسية") }
    }
}

@Composable
private fun ResultsScreenV1(job: ProcessingJobEntity?, jobs: List<ProcessingJobEntity>, repository: ContractJobRepository, snackbar: SnackbarHostState, onReview: (ClipArtifact) -> Unit, onHome: () -> Unit, onSelectJob: (ProcessingJobEntity) -> Unit) {
    if (job == null) {
        LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("النتائج", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            items(jobs.filter { it.status == ProcessingJobEntity.STATUS_SUCCEEDED }, key = { it.jobId }) { j -> JobCardV1(j, onSelectJob, active = false, modifier = Modifier.fillMaxWidth()) }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val artifacts by produceState(initialValue = emptyList<ClipArtifact>(), job.jobId) { value = repository.artifacts(job.jobId) }
    val paths = remember { mutableStateMapOf<String, String>() }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF10B981).copy(alpha = 0.15f), MaterialTheme.colorScheme.background))).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(job.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${artifacts.size} مقطع جاهز", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (artifacts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("جاري تحميل المقاطع...") } }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(artifacts, key = { it.id }) { artifact ->
                    val downloaded = paths[artifact.id]?.let(::File)?.isFile == true || repository.artifactFile(job.jobId, artifact).isFile
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = if (artifact.score >= 70) Color(0xFF10B981) else MaterialTheme.colorScheme.surfaceVariant)) {
                                    Text("${artifact.score}%", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold, color = if (artifact.score >= 70) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${artifact.durationSeconds}ث", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(artifact.title, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onReview(artifact) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("معاينة") }
                                Button(onClick = { scope.launch { repository.downloadArtifact(job.jobId, artifact).onSuccess { paths[artifact.id] = it }.onFailure { snackbar.showSnackbar(it.message ?: "تعذر التنزيل") } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text(if (downloaded) "إعادة" else "تنزيل") }
                            }
                        }
                    }
                }
                item { OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("الرئيسية") }; Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun ClipReviewScreenV1(job: ProcessingJobEntity?, artifact: ClipArtifact?, repository: ContractJobRepository, onBack: () -> Unit) {
    if (job == null || artifact == null) return
    val scope = rememberCoroutineScope()
    var localPath by remember { mutableStateOf<String?>(null) }
    val existing = repository.artifactFile(job.jobId, artifact)
    if (localPath == null && existing.isFile) localPath = existing.absolutePath
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(artifact.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${artifact.score}% • ${artifact.durationSeconds} ثانية", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            if (localPath != null) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    AndroidView(factory = { ctx -> VideoView(ctx).apply { setVideoURI(Uri.fromFile(File(localPath!!))); setOnPreparedListener { it.isLooping = true; start() } } }, modifier = Modifier.fillMaxWidth().height(280.dp))
                }
            } else {
                Card(Modifier.fillMaxWidth().height(280.dp), shape = RoundedCornerShape(16.dp)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(onClick = { scope.launch { repository.downloadArtifact(job.jobId, artifact).onSuccess { localPath = it } } }) { Text("تنزيل ومعاينة") } } }
            }
        }
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("العودة") } }
    }
}

@Composable
private fun SettingsScreenV1(repository: ContractJobRepository, snackbar: SnackbarHostState, onAbout: () -> Unit, onPrivacy: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { repository.loadGatewayConfig() }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var autoDiscovering by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val freeGateways = listOf("https://ism-free-gateway.fly.dev" to "Fly.io 🆓 (موصى به)", "https://ism-free-gateway.onrender.com" to "Render 🆓", "https://api.ism.app" to "ISM Cloud", "http://10.0.2.2:8787" to "محلي Emulator", "http://192.168.1.100:8787" to "شبكة محلية")

    LaunchedEffect(Unit) {
        if (initial.baseUrl.isBlank() || initial.baseUrl.contains("example.invalid")) {
            autoDiscovering = true
            status = "🔍 البحث عن بوابة مجانية..."
            val result = repository.autoDiscoverGateway()
            result.fold(onSuccess = { config -> baseUrl = config.baseUrl; status = "✅ تم اكتشاف: ${config.baseUrl}" }, onFailure = { baseUrl = freeGateways[0].first; status = "🆓 تم اختيار بوابة مجانية: ${freeGateways[0].first}"; repository.saveGatewayConfig(GatewayConfig(freeGateways[0].first, "")) })
            autoDiscovering = false
        } else {
            loading = true
            status = ApiContractClient(context.contentResolver).health(initial).fold({ "✅ متصل: $it" }, { "⚠️ ${it.message}" })
            loading = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("الإعدادات", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("إدارة البوابة والحسابات", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.1f))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Filled.Cloud, null, tint = Color(0xFF10B981)); Text("بوابات مجانية جاهزة 🆓", fontWeight = FontWeight.Bold, color = Color(0xFF10B981)) }
                    freeGateways.forEach { (url, name) ->
                        Card(onClick = { baseUrl = url; scope.launch { repository.saveGatewayConfig(GatewayConfig(url, token)); snackbar.showSnackbar("تم اختيار $name"); loading = true; status = ApiContractClient(context.contentResolver).health(GatewayConfig(url, token)).fold({ "✅ متصل: $name" }, { "⚠️ $name: ${it.message}" }); loading = false } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = if (baseUrl == url) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RadioButton(selected = baseUrl == url, onClick = { baseUrl = url }); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall); Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !autoDiscovering && !loading, onClick = { scope.launch { autoDiscovering = true; val result = repository.autoDiscoverGateway(); result.fold(onSuccess = { config -> baseUrl = config.baseUrl; status = "✅ تم اكتشاف: ${config.baseUrl}" }, onFailure = { err -> status = "❌ فشل: ${err.message}" }); autoDiscovering = false } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    if (autoDiscovering) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White) else Text("اكتشاف تلقائي")
                }
                OutlinedButton(enabled = !loading && !autoDiscovering, onClick = { scope.launch { loading = true; val cfg = GatewayConfig(baseUrl.ifBlank { repository.loadGatewayConfig().baseUrl }, token); repository.saveGatewayConfig(cfg); status = ApiContractClient(context.contentResolver).health(cfg).fold({ "✅ متصل: $it" }, { "❌ فشل: ${it.message}" }); loading = false } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp)) else Text("اختبار")
                }
            }
        }
        item { status?.let { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) } } }
        item {
            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "إخفاء المتقدم" else "إعدادات متقدمة") }
            if (showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Gateway URL") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                    Button(onClick = { scope.launch { repository.saveGatewayConfig(GatewayConfig(baseUrl, token)); snackbar.showSnackbar("تم الحفظ") } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("حفظ") }
                }
            }
        }
        item {
            HorizontalDivider(); Spacer(Modifier.height(8.dp))
            Card(onClick = onAbout, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Filled.Info, null); Text("حول التطبيق", Modifier.weight(1f)); Icon(Icons.Filled.ChevronRight, null) } }
            Card(onClick = onPrivacy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Filled.PrivacyTip, null); Text("الخصوصية", Modifier.weight(1f)); Icon(Icons.Filled.ChevronRight, null) } }
        }
    }
}

@Composable
private fun AboutScreenV1() {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(20.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))), contentAlignment = Alignment.Center) { Text("ISM v1.0.0-free 🆓", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) } }
        item { Text("محرر المقاطع الذكي", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge); Text("حول الفيديوهات الطويلة إلى مقاطع قصيرة جذابة", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("🆓 مجاني 100% - Fly.io, Render, Railway"); Text("🚀 سريع - 2-5 دقائق"); Text("🤖 ذكاء اصطناعي - Gemini API"); Text("📤 نشر تلقائي - TikTok, Reels, Shorts") } } }
    }
}

@Composable
private fun PrivacyScreenV1() {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("الخصوصية", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp)) { Text("📹 الفيديوهات تُحذف بعد 7 أيام", fontWeight = FontWeight.Bold); Text("🔑 المفاتيح مشفرة بـ Keystore", style = MaterialTheme.typography.bodySmall) } } }
    }
}

private fun screenTitle(route: AppRoute) = when (route) {
    AppRoute.ONBOARDING -> "مرحباً"; AppRoute.IMPORT -> "استيراد"; AppRoute.PROCESSING -> "المعالجة"; AppRoute.ERROR -> "خطأ"; AppRoute.RESULTS -> "النتائج"; AppRoute.REVIEW -> "معاينة"; AppRoute.SETTINGS -> "الإعدادات"; AppRoute.ABOUT -> "حول"; AppRoute.PRIVACY -> "الخصوصية"; AppRoute.HOME -> "ISM"
}
private fun displayStage(stage: String) = when (stage.uppercase()) {
    "UPLOADING" -> "رفع الفيديو"; "QUEUED" -> "في الانتظار"; "PROCESSING", "ANALYZING" -> "تحليل ذكي"; "FINALIZING" -> "إنهاء"; "COMPLETED" -> "مكتمل"; "FAILED" -> "فشل"; "CANCELLED" -> "ملغى"; "WAITING_FOR_NETWORK" -> "بانتظار الشبكة"; else -> stage
}
private fun AppRoute.toDestination() = when (this) { AppRoute.SETTINGS, AppRoute.ABOUT, AppRoute.PRIVACY -> BottomDestination.SETTINGS; AppRoute.RESULTS, AppRoute.REVIEW -> BottomDestination.RESULTS; else -> BottomDestination.HOME }
private fun BottomDestination.toRoute() = when (this) { BottomDestination.HOME -> AppRoute.HOME; BottomDestination.RESULTS -> AppRoute.RESULTS; BottomDestination.SETTINGS -> AppRoute.SETTINGS }
private fun BottomDestination.label() = when (this) { BottomDestination.HOME -> "الرئيسية"; BottomDestination.RESULTS -> "النتائج"; BottomDestination.SETTINGS -> "الإعدادات" }
