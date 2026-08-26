package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Project
import com.example.data.repository.OpusRepository
import com.example.data.repository.ProcessingStep
import com.example.ui.components.VideoProcessingLoadingDialog
import com.example.ui.theme.OpusBorder
import com.example.ui.theme.OpusDarkCanvas
import com.example.ui.theme.OpusDarkSurface
import com.example.ui.theme.OpusDarkSurfaceHighlight
import com.example.ui.theme.OpusDarkSurfaceVariant
import com.example.ui.theme.OpusElectricCyan
import com.example.ui.theme.OpusHotPink
import com.example.ui.theme.OpusPrimaryViolet
import com.example.ui.theme.OpusTextPrimary
import com.example.ui.theme.OpusTextSecondary
import com.example.ui.theme.OpusViralEmerald
import com.example.ui.theme.OpusVioletGlow
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repository: OpusRepository,
    onProjectCreated: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
    onUploadLocalVideo: () -> Unit = {},
    onOpenApiKeySettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processingStep by repository.processingStep.collectAsState()
    val allProjects by repository.allProjects.collectAsState(initial = emptyList())
    val hasNetwork by rememberNetworkAvailable()

    var videoUrl by remember { mutableStateOf("") }
    var videoTitle by remember { mutableStateOf("") }
    var transcriptPrompt by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (isProcessing || processingStep !is ProcessingStep.Idle) {
        VideoProcessingLoadingDialog(
            processingStep = processingStep,
            videoTitle = videoTitle.ifBlank { "فيديو جديد" }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(OpusDarkCanvas)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.testTag("hero_section")) {
                Text(
                    text = "ابدأ بمقطعك التالي",
                    style = MaterialTheme.typography.displaySmall.copy(
                        color = OpusTextPrimary,
                        fontWeight = FontWeight.Black
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "اختر فيديو، راجع أفضل اللحظات، ثم صدّر نسخة جاهزة للنشر.",
                    style = MaterialTheme.typography.bodyLarge.copy(color = OpusTextSecondary)
                )
            }
        }

        if (!hasNetwork) {
            item {
                StatusBanner(
                    icon = Icons.Default.CloudOff,
                    title = "أنت غير متصل",
                    message = "الروابط الخارجية تحتاج اتصالاً بالإنترنت. يمكنك اختيار فيديو من الجهاز عند توفر المعالجة المحلية.",
                    color = OpusGoldLike,
                    testTag = "offline_state"
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clipper_input_card"),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(OpusPrimaryViolet.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OpusElectricCyan)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "اختر فيديو",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = OpusTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text("رابط عام أو ملف من الهاتف", color = OpusTextSecondary, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = videoUrl,
                        onValueChange = { videoUrl = it; errorMessage = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_url_input"),
                        singleLine = true,
                        label = { Text("رابط الفيديو") },
                        placeholder = { Text("https://youtube.com/...", color = OpusTextSecondary) },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = OpusElectricCyan) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = "لصق الرابط",
                                tint = OpusElectricCyan,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        videoUrl = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                        errorMessage = null
                                    }
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OpusElectricCyan,
                            unfocusedBorderColor = OpusBorder,
                            focusedTextColor = OpusTextPrimary,
                            unfocusedTextColor = OpusTextPrimary,
                            focusedContainerColor = OpusDarkSurfaceHighlight,
                            unfocusedContainerColor = OpusDarkSurfaceVariant,
                            focusedLabelColor = OpusElectricCyan,
                            unfocusedLabelColor = OpusTextSecondary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = videoTitle,
                        onValueChange = { videoTitle = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_title_input"),
                        singleLine = true,
                        label = { Text("عنوان اختياري") },
                        placeholder = { Text("مثال: مقابلة الأسبوع", color = OpusTextSecondary) },
                        leadingIcon = { Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = OpusVioletGlow) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OpusVioletGlow,
                            unfocusedBorderColor = OpusBorder,
                            focusedTextColor = OpusTextPrimary,
                            unfocusedTextColor = OpusTextPrimary,
                            focusedContainerColor = OpusDarkSurfaceHighlight,
                            unfocusedContainerColor = OpusDarkSurfaceVariant,
                            focusedLabelColor = OpusVioletGlow,
                            unfocusedLabelColor = OpusTextSecondary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = transcriptPrompt,
                        onValueChange = { transcriptPrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("transcript_prompt_input"),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("سياق أو تفريغ اختياري") },
                        placeholder = { Text("أضف موضوعاً تريد إبرازَه في المقاطع", color = OpusTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OpusGoldLike,
                            unfocusedBorderColor = OpusBorder,
                            focusedTextColor = OpusTextPrimary,
                            unfocusedTextColor = OpusTextPrimary,
                            focusedContainerColor = OpusDarkSurfaceHighlight,
                            unfocusedContainerColor = OpusDarkSurfaceVariant,
                            focusedLabelColor = OpusGoldLike,
                            unfocusedLabelColor = OpusTextSecondary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        StatusBanner(
                            icon = Icons.Default.Refresh,
                            title = "تعذر بدء التوليد",
                            message = errorMessage!!,
                            color = OpusHotPink,
                            testTag = "error_state"
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            when {
                                !hasNetwork -> errorMessage = "اتصل بالإنترنت لاستخدام رابط خارجي، أو اختر ملفاً من الهاتف."
                                videoUrl.isBlank() -> errorMessage = "الصق رابط فيديو للمتابعة."
                                isProcessing -> Unit
                                else -> {
                                    errorMessage = null
                                    isProcessing = true
                                    scope.launch {
                                        try {
                                            val title = videoTitle.trim().ifBlank { "فيديو جديد" }
                                            val newProjectId = repository.processNewVideo(
                                                title = title,
                                                sourceUrl = videoUrl.trim(),
                                                transcriptOrPrompt = transcriptPrompt.trim(),
                                                durationMinutes = 30,
                                                targetPlatform = "TikTok & Reels (9:16)",
                                                captionTheme = "Opus Neon"
                                            )
                                            isProcessing = false
                                            Toast.makeText(context, "اكتمل التوليد", Toast.LENGTH_SHORT).show()
                                            onProjectCreated(newProjectId)
                                        } catch (error: Exception) {
                                            isProcessing = false
                                            errorMessage = error.localizedMessage?.takeIf { it.isNotBlank() }
                                                ?: "حدث خطأ أثناء تجهيز الفيديو."
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("generate_clips_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OpusPrimaryViolet,
                            disabledContainerColor = OpusPrimaryViolet.copy(alpha = 0.45f)
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("جارٍ التحليل…", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = OpusElectricCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generate clips", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onUploadLocalVideo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("upload_local_video_banner"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OpusElectricCyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OpusElectricCyan.copy(alpha = 0.65f))
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("اختيار فيديو من الهاتف", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (allProjects.isEmpty()) {
            item {
                EmptyLibraryCard()
            }
        } else {
            item {
                Text(
                    text = "آخر المشاريع",
                    style = MaterialTheme.typography.titleMedium.copy(color = OpusTextPrimary, fontWeight = FontWeight.Bold)
                )
            }
            items(allProjects.take(3), key = { it.id }) { project ->
                ProjectRow(project = project, onClick = { onOpenProject(project.id) })
            }
        }

        item { Spacer(modifier = Modifier.height(26.dp)) }
    }
}

@Composable
private fun StatusBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    color: Color,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(message, color = OpusTextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun EmptyLibraryCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("empty_projects_state"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = OpusTextSecondary, modifier = Modifier.size(30.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text("لا توجد مشاريع بعد", color = OpusTextPrimary, fontWeight = FontWeight.Bold)
            Text("ابدأ بفيديو واحد، وستظهر أفضل المقاطع هنا بعد اكتمال المعالجة.", color = OpusTextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(OpusPrimaryViolet.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OpusElectricCyan)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(project.title, color = OpusTextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${project.clipCount} مقاطع • أفضل نتيجة ${project.bestViralityScore}",
                    color = OpusTextSecondary,
                    fontSize = 12.sp
                )
            }
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = OpusViralEmerald, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun rememberNetworkAvailable(): androidx.compose.runtime.State<Boolean> {
    val context = LocalContext.current
    val available = remember { mutableStateOf(isNetworkAvailable(context)) }
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available.value = true }
            override fun onLost(network: Network) { available.value = isNetworkAvailable(context) }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        onDispose { connectivityManager.unregisterNetworkCallback(callback) }
    }
    return available
}

private fun isNetworkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private val OpusGoldLike = Color(0xFFF59E0B)
