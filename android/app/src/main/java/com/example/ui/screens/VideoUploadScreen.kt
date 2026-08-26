package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AiTemplateRecommendation
import com.example.data.model.ProcessingJobEntity
import com.example.data.repository.OpusRepository
import com.example.data.repository.ProcessingStep
import com.example.ui.components.VideoProcessingLoadingDialog
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
import com.example.ui.theme.OpusVioletGlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024

@Composable
fun VideoUploadScreen(
    repository: OpusRepository,
    onBack: () -> Unit,
    onProjectCreated: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processingStep by repository.processingStep.collectAsState()
    val hasNetwork by rememberNetworkAvailableForUpload()

    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileSizeBytes by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingMetadata by remember { mutableStateOf(false) }
    var metadataError by remember { mutableStateOf<String?>(null) }
    var selectedCaptionTheme by remember { mutableStateOf("Opus Neon") }
    var selectedLayout by remember { mutableStateOf("9:16 Full Screen") }
    var detectedAiRecommendation by remember { mutableStateOf<AiTemplateRecommendation?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var activeProcessingJobId by remember { mutableStateOf<String?>(null) }
    var jobError by remember { mutableStateOf<String?>(null) }

    val processingJobFlow = remember(activeProcessingJobId) { activeProcessingJobId?.let(repository::observeProcessingJob) }
    val processingJob by processingJobFlow?.collectAsState(initial = null)
        ?: remember { mutableStateOf<ProcessingJobEntity?>(null) }

    LaunchedEffect(processingJob?.status, processingJob?.outputProjectId) {
        val job = processingJob
        when {
            job?.status == ProcessingJobEntity.STATUS_SUCCEEDED && job.outputProjectId > 0L -> {
                isProcessing = false
                activeProcessingJobId = null
                onProjectCreated(job.outputProjectId)
            }
            job?.status == ProcessingJobEntity.STATUS_FAILED -> {
                isProcessing = false
                jobError = job.errorMessage?.takeIf { it.isNotBlank() } ?: "تعذرت معالجة الفيديو."
            }
        }
    }

    if (isProcessing || processingStep !is ProcessingStep.Idle) {
        VideoProcessingLoadingDialog(
            processingStep = processingStep,
            videoTitle = fileName ?: "فيديو من الهاتف",
            actualProgressPercent = processingJob?.progress,
            actualStage = processingJob?.currentStage
        )
    }

    val isOverSizeLimit = fileSizeBytes > MAX_FILE_SIZE_BYTES
    val isValidDuration = durationMs in 5_000..7_200_000
    val isReady = selectedVideoUri != null && !isLoadingMetadata && metadataError == null && !isOverSizeLimit && isValidDuration

    val chooseVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            isLoadingMetadata = true
            metadataError = null
            thumbnailBitmap = null
            loadSelectedVideo(context, uri, scope) { name, size, duration, width, height, bitmap, error ->
            selectedVideoUri = uri
            fileName = name
            fileSizeBytes = size
            durationMs = duration
            videoWidth = width
            videoHeight = height
            thumbnailBitmap = bitmap
            metadataError = error
            isLoadingMetadata = false
            }
        }
    }
    val fallbackPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            isLoadingMetadata = true
            metadataError = null
            thumbnailBitmap = null
            loadSelectedVideo(context, uri, scope) { name, size, duration, width, height, bitmap, error ->
            selectedVideoUri = uri
            fileName = name
            fileSizeBytes = size
            durationMs = duration
            videoWidth = width
            videoHeight = height
            thumbnailBitmap = bitmap
            metadataError = error
            isLoadingMetadata = false
            }
        }
    }

    fun openPicker() {
        try {
            chooseVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        } catch (_: Exception) {
            fallbackPicker.launch("video/*")
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(OpusDarkCanvas).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = OpusElectricCyan, modifier = Modifier.size(22.dp).clickable(onClick = onBack).testTag("video_upload_back_button"))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("اختيار فيديو", style = MaterialTheme.typography.titleLarge.copy(color = OpusTextPrimary, fontWeight = FontWeight.Black))
                    Text("الخطوة 1 من 2 · ثم Generate", color = OpusTextSecondary, fontSize = 12.sp)
                }
            }
        }

        if (!hasNetwork) {
            item { UploadStatusCard(Icons.Default.CloudOff, "أنت غير متصل", "الملف المحلي لا يحتاج إلى رفع قبل أن يبدأ الطابور، لكن بعض المسارات قد تتطلب اتصالاً.", OpusGold, "upload_offline_state") }
        }

        if (selectedVideoUri == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("video_file_dropzone"),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OpusVioletGlow.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(OpusPrimaryViolet.copy(alpha = 0.5f), OpusDarkSurfaceHighlight))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = OpusElectricCyan, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("اختر فيديو من الهاتف", color = OpusTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("MP4 أو MOV · حتى 500 MB · من 5 ثوانٍ إلى ساعتين", color = OpusTextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = ::openPicker, colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet), modifier = Modifier.testTag("choose_video_button"), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تصفح الملفات", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("video_preview_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Preview", color = OpusTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            TextButton(onClick = ::openPicker) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = OpusElectricCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("تغيير", color = OpusElectricCyan, fontWeight = FontWeight.Bold)
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(OpusDarkSurfaceHighlight), contentAlignment = Alignment.Center) {
                            when {
                                isLoadingMetadata -> CircularProgressIndicator(color = OpusElectricCyan)
                                thumbnailBitmap != null -> Image(thumbnailBitmap!!.asImageBitmap(), contentDescription = "معاينة الفيديو", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else -> Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = OpusVioletGlow, modifier = Modifier.size(44.dp))
                            }
                            if (!isLoadingMetadata && thumbnailBitmap != null) {
                                Box(modifier = Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White) }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(fileName ?: "فيديو محدد", color = OpusTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetadataTag("الحجم", formatFileSize(fileSizeBytes), isOverSizeLimit)
                            MetadataTag("المدة", formatDuration(durationMs), durationMs > 0 && !isValidDuration)
                            MetadataTag("الأبعاد", if (videoWidth > 0) "${videoWidth}×$videoHeight" else "—", false)
                        }
                    }
                }
            }

            item {
                when {
                    isLoadingMetadata -> UploadStatusCard(Icons.Default.Refresh, "جارٍ قراءة الفيديو", "نستخرج المدة والأبعاد قبل بدء المعالجة.", OpusElectricCyan, "metadata_loading_state")
                    metadataError != null -> UploadStatusCard(Icons.Default.Error, "الفيديو غير صالح", metadataError!!, OpusHotPink, "metadata_error_state")
                    isReady -> UploadStatusCard(Icons.Default.CheckCircle, "الفيديو جاهز", "يمكنك بدء توليد أفضل المقاطع الآن.", OpusViralEmerald, "video_ready_state")
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = OpusDarkSurface), border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("إعداد التوليد", color = OpusTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("سيحدد المحرك أفضل اللحظات، ثم يمكنك تعديل الكابشن والقص في الاستوديو.", color = OpusTextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("القالب: $selectedCaptionTheme", color = OpusElectricCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Opus Neon", "Clean", "Bold").forEach { theme ->
                                val selected = selectedCaptionTheme == theme
                                Text(
                                    text = theme,
                                    color = if (selected) Color.White else OpusTextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) OpusPrimaryViolet else OpusDarkSurfaceVariant).border(1.dp, if (selected) OpusElectricCyan else OpusBorder, RoundedCornerShape(10.dp)).clickable { selectedCaptionTheme = theme }.padding(horizontal = 11.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            jobError?.let { error ->
                item { UploadStatusCard(Icons.Default.Error, "فشلت المعالجة", error, OpusHotPink, "processing_error_state") }
            }

            item {
                Button(
                    onClick = {
                        if (!isReady || isProcessing) return@Button
                        val title = fileName?.substringBeforeLast(".") ?: "Local Video Repurposed"
                        val durationMinutes = maxOf(1, (durationMs / 60000).toInt())
                        isProcessing = true
                        jobError = null
                        scope.launch {
                            try {
                                var appliedCaptionTheme = selectedCaptionTheme
                                var appliedLayout = selectedLayout
                                try {
                                    val aiRec = repository.determineOptimalTemplate(
                                        title = title,
                                        transcript = "Local uploaded video: $title",
                                        durationSec = maxOf(30, (durationMs / 1000).toInt())
                                    )
                                    detectedAiRecommendation = aiRec
                                    appliedCaptionTheme = aiRec.recommendedCaptionTheme
                                    appliedLayout = aiRec.recommendedPlatform
                                } catch (_: Exception) {}
                                val jobId = repository.enqueueVideoProcessing(
                                    title = title,
                                    sourceUri = selectedVideoUri!!.toString(),
                                    transcriptOrPrompt = "Local uploaded video: $title",
                                    durationMinutes = durationMinutes,
                                    targetPlatform = appliedLayout,
                                    captionTheme = appliedCaptionTheme
                                )
                                activeProcessingJobId = jobId
                            } catch (error: Exception) {
                                isProcessing = false
                                jobError = error.localizedMessage?.takeIf { it.isNotBlank() } ?: "تعذر إضافة الفيديو إلى الطابور."
                            }
                        }
                    },
                    enabled = isReady && !isProcessing,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("extract_viral_clips_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet, disabledContainerColor = OpusDarkSurfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("جارٍ التحليل…", color = Color.White, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate أفضل المقاطع", color = if (isReady) Color.White else OpusTextSecondary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(26.dp)) }
    }
}

private fun loadSelectedVideo(
    context: Context,
    uri: Uri,
    scope: kotlinx.coroutines.CoroutineScope,
    onResult: (String, Long, Long, Int, Int, Bitmap?, String?) -> Unit
) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    scope.launch {
        extractVideoMetadata(context, uri) { name, size, duration, width, height, bitmap ->
            onResult(name, size, duration, width, height, bitmap, validateVideoMetadata(size, duration, width, height))
        }
    }
}

@Composable
private fun UploadStatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, color: Color, testTag: String) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp).testTag(testTag), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column { Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp); Spacer(modifier = Modifier.height(2.dp)); Text(message, color = OpusTextSecondary, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun MetadataTag(label: String, value: String, isError: Boolean) {
    Text("$label: $value", color = if (isError) OpusHotPink else OpusTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (isError) OpusHotPink.copy(alpha = 0.13f) else OpusDarkSurfaceVariant).border(1.dp, if (isError) OpusHotPink else OpusBorder, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 6.dp))
}

@Composable
private fun rememberNetworkAvailableForUpload(): androidx.compose.runtime.State<Boolean> {
    val context = LocalContext.current
    val available = remember { mutableStateOf(isNetworkAvailableForUpload(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available.value = true }
            override fun onLost(network: Network) { available.value = isNetworkAvailableForUpload(context) }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        manager.registerNetworkCallback(request, callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return available
}

private fun isNetworkAvailableForUpload(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private suspend fun extractVideoMetadata(
    context: Context,
    uri: Uri,
    onResult: (String, Long, Long, Int, Int, Bitmap?) -> Unit
) = withContext(Dispatchers.IO) {
    var fileName = "video_${System.currentTimeMillis()}.mp4"
    var fileSize = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { fileName = it }
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }
    } catch (_: Exception) {}

    var duration = 0L
    var width = 0
    var height = 0
    var thumbnail: Bitmap? = null
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        thumbnail = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
    } catch (_: Exception) {
    } finally {
        runCatching { retriever.release() }
    }
    withContext(Dispatchers.Main) { onResult(fileName, fileSize, duration, width, height, thumbnail) }
}

private fun validateVideoMetadata(size: Long, duration: Long, width: Int, height: Int): String? = when {
    size <= 0L -> "تعذر قراءة حجم الملف. اختر الفيديو مرة أخرى."
    size > MAX_FILE_SIZE_BYTES -> "حجم الفيديو يتجاوز 500 MB."
    duration !in 5_000L..7_200_000L -> "مدة الفيديو يجب أن تكون بين 5 ثوانٍ وساعتين."
    width <= 0 || height <= 0 -> "تعذر قراءة أبعاد الفيديو أو أن الملف غير صالح."
    else -> null
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1000) String.format(Locale.US, "%.2f GB", mb / 1024) else String.format(Locale.US, "%.1f MB", mb)
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60, seconds) else String.format(Locale.US, "%d:%02d", minutes, seconds)
}
