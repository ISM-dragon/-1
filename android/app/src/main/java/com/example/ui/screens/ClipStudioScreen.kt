package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Clip
import com.example.data.repository.OpusRepository
import com.example.ui.components.AutoCaptionStudioCard
import com.example.ui.components.VideoSimPlayer
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
fun ClipStudioScreen(
    repository: OpusRepository,
    initialProjectId: Long?,
    modifier: Modifier = Modifier,
    onOpenComparison: ((Long) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allClips by repository.allClips.collectAsState(initial = emptyList())
    val displayedClips = remember(allClips, initialProjectId) {
        if (initialProjectId != null && initialProjectId > 0) allClips.filter { it.projectId == initialProjectId } else emptyList()
    }

    var selectedClipIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(initialProjectId, displayedClips.size) {
        if (selectedClipIndex !in displayedClips.indices) selectedClipIndex = 0
    }
    val activeClip = displayedClips.getOrNull(selectedClipIndex)

    var activeTab by remember { mutableIntStateOf(0) }
    var selectedCaptionTheme by remember { mutableStateOf("Opus Neon") }
    var selectedLayout by remember { mutableStateOf(activeClip?.layoutType ?: "9:16 Full Screen") }
    var captionPosition by remember { mutableStateOf("Bottom (Safe Zone)") }
    var fontSizeSp by remember { mutableIntStateOf(14) }
    var showAutoEmojis by remember { mutableStateOf(true) }
    var isUppercase by remember { mutableStateOf(false) }
    var externalSeekSec by remember { mutableStateOf<Float?>(null) }
    var showExportModal by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableIntStateOf(0) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportCompleted by remember { mutableStateOf(false) }

    if (activeClip == null) {
        EmptyStudioState(modifier = modifier)
        return
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
            Text(
                text = "أفضل المقاطع",
                style = MaterialTheme.typography.displaySmall.copy(color = OpusTextPrimary, fontWeight = FontWeight.Black)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("اختر مقطعاً لمعاينته وتحريره ثم تصديره.", color = OpusTextSecondary, fontSize = 14.sp)
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("best_clips_row")) {
                items(displayedClips.indices.toList(), key = { displayedClips[it].id }) { index ->
                    ClipChoiceCard(
                        clip = displayedClips[index],
                        selected = displayedClips[index].id == activeClip.id,
                        onClick = {
                            selectedClipIndex = index
                            selectedLayout = displayedClips[index].layoutType
                        }
                    )
                }
            }
        }

        item {
            ScoreCard(clip = activeClip)
        }

        item {
            SectionLabel(title = "Preview", supporting = "المعاينة الفعلية للمقطع المحدد")
            VideoSimPlayer(
                clip = activeClip,
                selectedCaptionTheme = selectedCaptionTheme,
                layoutType = selectedLayout,
                onLayoutChange = { layout ->
                    selectedLayout = layout
                    scope.launch { repository.updateLayoutType(activeClip.id, layout) }
                },
                captionPosition = captionPosition,
                fontSizeSp = fontSizeSp,
                showAutoEmojis = showAutoEmojis,
                isUppercase = isUppercase,
                externalSeekSec = externalSeekSec,
                onSeekComplete = { externalSeekSec = null },
                modifier = Modifier.testTag("clip_preview")
            )
        }

        item {
            TranscriptCard(clip = activeClip)
        }

        item {
            ScrollableTabRow(
                selectedTabIndex = activeTab,
                containerColor = OpusDarkSurface,
                contentColor = OpusElectricCyan,
                edgePadding = 0.dp,
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[activeTab]), color = OpusElectricCyan)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, OpusBorder, RoundedCornerShape(14.dp))
                    .testTag("edit_tabs")
            ) {
                listOf("Captions", "Crop").forEachIndexed { index, title ->
                    Tab(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        text = {
                            Text(title, fontSize = 13.sp, fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Medium)
                        },
                        icon = {
                            Icon(
                                if (index == 0) Icons.Default.Subtitles else Icons.Default.Crop,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                        },
                        modifier = Modifier.testTag("edit_tab_$index")
                    )
                }
            }
        }

        item {
            if (activeTab == 0) {
                AutoCaptionStudioCard(
                    clip = activeClip,
                    selectedCaptionTheme = selectedCaptionTheme,
                    onThemeSelect = { selectedCaptionTheme = it },
                    captionPosition = captionPosition,
                    onPositionChange = { captionPosition = it },
                    fontSizeSp = fontSizeSp,
                    onFontSizeChange = { fontSizeSp = it },
                    showAutoEmojis = showAutoEmojis,
                    onShowAutoEmojisChange = { showAutoEmojis = it },
                    isUppercase = isUppercase,
                    onUppercaseChange = { isUppercase = it },
                    onSeekToSec = { externalSeekSec = it },
                    repository = repository
                )
            } else {
                CropCard(
                    selectedLayout = selectedLayout,
                    onLayoutChange = { layout ->
                        selectedLayout = layout
                        scope.launch { repository.updateLayoutType(activeClip.id, layout) }
                    }
                )
            }
        }

        item {
            Button(
                onClick = {
                    exportError = null
                    exportCompleted = false
                    showExportModal = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("open_export_modal_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Render & export", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        item { Spacer(modifier = Modifier.height(26.dp)) }
    }

    if (showExportModal) {
        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportModal = false },
            containerColor = OpusDarkSurface,
            title = { Text("تصدير المقطع", color = OpusTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("سيتم استخدام القص ${selectedLayout} مع ترجمة ${selectedCaptionTheme}.", color = OpusTextSecondary, fontSize = 13.sp)
                    if (isExporting) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("جارٍ التصيير… $exportProgress%", color = OpusElectricCyan, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { exportProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                            color = OpusElectricCyan,
                            trackColor = OpusDarkSurfaceHighlight
                        )
                    }
                    exportError?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        StatusMessage(icon = Icons.Default.Error, title = "فشل التصدير", message = it, color = OpusHotPink)
                    }
                    if (exportCompleted) {
                        Spacer(modifier = Modifier.height(12.dp))
                        StatusMessage(icon = Icons.Default.CheckCircle, title = "اكتمل التصدير", message = "حُفظ الفيديو في المعرض.", color = OpusViralEmerald)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isExporting) return@Button
                        isExporting = true
                        exportProgress = 0
                        exportError = null
                        scope.launch {
                            try {
                                val output = repository.exportClipToFile(
                                    clipId = activeClip.id,
                                    burnInSubtitles = true,
                                    removeWatermark = false,
                                    aspectRatioName = selectedLayout,
                                    onProgress = { exportProgress = it }
                                )
                                repository.saveExportToMediaStore(output)
                                isExporting = false
                                exportCompleted = true
                                Toast.makeText(context, "حُفظ المقطع في المعرض", Toast.LENGTH_SHORT).show()
                            } catch (error: Exception) {
                                isExporting = false
                                exportError = error.localizedMessage?.takeIf { it.isNotBlank() } ?: "تعذر تصيير الفيديو."
                            }
                        }
                    },
                    enabled = !isExporting,
                    colors = ButtonDefaults.buttonColors(containerColor = OpusPrimaryViolet),
                    modifier = Modifier.testTag("confirm_export_button")
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (isExporting) "جارٍ التصيير" else "ابدأ التصدير", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!isExporting) TextButton(onClick = { showExportModal = false }) { Text("إغلاق", color = OpusTextSecondary) }
            }
        )
    }
}

@Composable
private fun EmptyStudioState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(OpusDarkCanvas).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag("empty_clips_state")) {
            Box(
                modifier = Modifier.size(68.dp).clip(RoundedCornerShape(20.dp)).background(OpusDarkSurface),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OpusElectricCyan, modifier = Modifier.size(32.dp)) }
            Spacer(modifier = Modifier.height(14.dp))
            Text("لا توجد مقاطع جاهزة", color = OpusTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("بعد اكتمال المعالجة ستظهر أفضل المقاطع هنا للمعاينة والتحرير.", color = OpusTextSecondary, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ClipChoiceCard(clip: Clip, selected: Boolean, onClick: () -> Unit) {
    val scoreColor = if (clip.viralityScore >= 80) OpusViralEmerald else OpusElectricCyan
    Column(
        modifier = Modifier
            .width(172.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) OpusDarkSurfaceHighlight else OpusDarkSurface)
            .border(if (selected) 2.dp else 1.dp, if (selected) OpusElectricCyan else OpusBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("clip_item_card_${clip.id}")
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${clip.viralityScore}", color = scoreColor, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text("${clip.durationSec}s", color = OpusTextSecondary, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(clip.title, color = if (selected) OpusTextPrimary else OpusTextSecondary, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(if (selected) "محدد للمعاينة" else "اضغط للفتح", color = if (selected) OpusElectricCyan else OpusTextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun ScoreCard(clip: Clip) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("score_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Virality score", color = OpusTextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text("${clip.viralityScore}/100", color = OpusTextPrimary, fontWeight = FontWeight.Black, fontSize = 30.sp)
                Text(clip.hookExplanation.ifBlank { "نتيجة مبنية على الهوك والاحتفاظ وقابلية المشاركة." }, color = OpusTextSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ScoreRing(score = clip.viralityScore)
        }
    }
}

@Composable
private fun ScoreRing(score: Int) {
    Box(
        modifier = Modifier.size(74.dp).clip(RoundedCornerShape(37.dp)).background(OpusViralEmerald.copy(alpha = 0.14f)).border(2.dp, OpusViralEmerald.copy(alpha = 0.75f), RoundedCornerShape(37.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(if (score >= 80) "قوي" else "جيد", color = OpusViralEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun TranscriptCard(clip: Clip) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("transcript_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionLabel(title = "Transcript", supporting = "النص الذي بُنيت عليه الترجمة")
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = clip.transcript.ifBlank { "لا يتوفر تفريغ نصي لهذا المقطع." },
                color = if (clip.transcript.isBlank()) OpusTextSecondary else OpusTextPrimary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CropCard(selectedLayout: String, onLayoutChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("crop_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionLabel(title = "Crop & framing", supporting = "اختر أبعاد الإخراج المناسبة للمنصة")
            Spacer(modifier = Modifier.height(12.dp))
            listOf("9:16 Full Screen" to "عمودي", "Split Screen" to "مقسّم", "1:1 Square" to "مربّع").forEach { (layout, label) ->
                val selected = layout == selectedLayout
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) OpusPrimaryViolet.copy(alpha = 0.2f) else OpusDarkSurfaceVariant)
                        .border(1.dp, if (selected) OpusElectricCyan else OpusBorder, RoundedCornerShape(12.dp))
                        .clickable { onLayoutChange(layout) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Crop, contentDescription = null, tint = if (selected) OpusElectricCyan else OpusTextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, color = OpusTextPrimary, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        Text(layout, color = OpusTextSecondary, fontSize = 11.sp)
                    }
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = OpusElectricCyan)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, supporting: String) {
    Column {
        Text(title, color = OpusTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(supporting, color = OpusTextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun StatusMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(message, color = OpusTextSecondary, fontSize = 12.sp)
        }
    }
}
