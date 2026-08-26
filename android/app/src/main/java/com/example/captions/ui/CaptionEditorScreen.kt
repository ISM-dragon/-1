package com.example.captions.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.captions.data.CaptionLine
import com.example.captions.data.CaptionTranscript
import com.example.captions.data.CaptionWord
import com.example.captions.di.CaptionDataGraph
import com.example.ui.theme.OpusBorder
import com.example.ui.theme.OpusDarkCanvas
import com.example.ui.theme.OpusDarkSurface
import com.example.ui.theme.OpusDarkSurfaceHighlight
import com.example.ui.theme.OpusElectricCyan
import com.example.ui.theme.OpusGold
import com.example.ui.theme.OpusPrimaryViolet
import com.example.ui.theme.OpusTextPrimary
import com.example.ui.theme.OpusTextSecondary
import com.example.ui.theme.OpusTextTertiary
import com.example.ui.theme.OpusVioletGlow
import kotlin.math.abs
import kotlin.math.roundToLong

private val CaptionAccent = Color(0xFFB8FF5C)
private val CaptionWarm = Color(0xFFFFD166)
private val CaptionPanel = Color(0xFF151725)
private const val ProsodicEnergyThreshold = 0.78f

/**
 * Word-level karaoke renderer. Each token paints its own progress on a Canvas behind the text,
 * which keeps the animation local and avoids relayout while the playback clock advances.
 */
@Composable
fun CaptionOverlay(
    transcript: CaptionTranscript,
    positionMs: Long,
    modifier: Modifier = Modifier,
    maxLines: Int = 2
) {
    val activeIndex = transcript.words.indexOfFirst { positionMs in it.startMs until it.endMs }
    val visibleWords = transcript.words
        .drop((activeIndex - 5).coerceAtLeast(0))
        .take(maxLines * 5)
    val activeLineIds = transcript.lines
        .filter { line -> line.words.any { it.id == transcript.words.getOrNull(activeIndex)?.id } }
        .map(CaptionLine::id)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        transcript.lines
            .filter { it.id in activeLineIds || (activeLineIds.isEmpty() && it.words.any { word -> word in visibleWords }) }
            .takeLast(maxLines)
            .forEach { line ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    line.words.forEach { word ->
                        val isActive = positionMs in word.startMs until word.endMs
                        val progress = when {
                            isActive -> ((positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                            positionMs >= word.endMs -> 1f
                            else -> 0f
                        }
                        CaptionWordToken(
                            word = word,
                            isActive = isActive,
                            karaokeProgress = progress
                        )
                    }
                }
            }
    }
}

@Composable
private fun CaptionWordToken(
    word: CaptionWord,
    isActive: Boolean,
    karaokeProgress: Float
) {
    val animatedFill by animateFloatAsState(
        targetValue = karaokeProgress,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "karaoke_fill_${word.id}"
    )
    val activeColor by animateColorAsState(
        targetValue = if (isActive || karaokeProgress > 0f) CaptionAccent else Color.White,
        animationSpec = tween(120),
        label = "karaoke_text_${word.id}"
    )
    Text(
        text = word.text,
        color = if (isActive) Color(0xFF10130D) else activeColor,
        fontSize = 19.sp,
        fontWeight = if (isActive || word.energy >= ProsodicEnergyThreshold) FontWeight.Black else FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .prosodicAnimation(word.energy)
            .drawBehind {
                if (animatedFill > 0f) {
                    drawRoundRect(
                        color = CaptionAccent.copy(alpha = 0.96f),
                        size = size.copy(width = size.width * animatedFill),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
                    )
                }
                if (isActive) {
                    drawRoundRect(
                        color = CaptionAccent.copy(alpha = 0.18f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
                    )
                }
            }
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = "Caption word ${word.text}" }
    )
}

/** Adds a subtle scale and micro-shake to high-energy words without affecting layout. */
fun Modifier.prosodicAnimation(
    energy: Float,
    threshold: Float = ProsodicEnergyThreshold
): Modifier = composed {
    val isEmphatic = energy >= threshold
    val scale by animateFloatAsState(
        targetValue = if (isEmphatic) 1.08f + ((energy - threshold).coerceAtLeast(0f) * 0.08f) else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "prosodic_scale"
    )
    val shake = remember(isEmphatic) {
        Animatable(0f)
    }
    LaunchedEffect(isEmphatic) {
        if (isEmphatic) {
            shake.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 520
                        0f at 0
                        1f at 75
                        -1f at 150
                        0.7f at 240
                        -0.45f at 330
                        0f at 520
                    },
                    repeatMode = RepeatMode.Restart
                )
            )
        } else {
            shake.snapTo(0f)
        }
    }
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = if (isEmphatic) shake.value * 1.35f else 0f
    }
}

@Composable
fun VideoTimelineScrubber(
    durationMs: Long,
    positionMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    waveform: List<Float>,
    onSeek: (Long) -> Unit,
    onTrimStartChanged: (Long) -> Unit,
    onTrimEndChanged: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalDuration = durationMs.coerceAtLeast(1L)
    val activeHandle = remember { mutableStateOf(TimelineHandle.PLAYHEAD) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0D0F18))
            .pointerInput(totalDuration, trimStartMs, trimEndMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val startX = size.width * trimStartMs / totalDuration
                        val endX = size.width * trimEndMs / totalDuration
                        activeHandle.value = when {
                            abs(offset.x - startX) < 34f -> TimelineHandle.START
                            abs(offset.x - endX) < 34f -> TimelineHandle.END
                            else -> TimelineHandle.PLAYHEAD
                        }
                    },
                    onDrag = { change, _ ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val nextX = change.position.x.coerceIn(0f, width)
                        val nextMs = (nextX / width * totalDuration).roundToLong()
                        when (activeHandle.value) {
                            TimelineHandle.START -> onTrimStartChanged(nextMs)
                            TimelineHandle.END -> onTrimEndChanged(nextMs)
                            TimelineHandle.PLAYHEAD -> onSeek(nextMs)
                        }
                    }
                )
            }
            .pointerInput(totalDuration, trimStartMs, trimEndMs) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width * totalDuration).roundToLong())
                }
            }
            .semantics { contentDescription = "Video timeline with draggable trim handles" }
    ) {
        val startX = size.width * trimStartMs / totalDuration
        val endX = size.width * trimEndMs / totalDuration
        val playheadX = size.width * positionMs / totalDuration
        val barWidth = (size.width / waveform.size.coerceAtLeast(1)) * 0.62f
        val centerY = size.height / 2f

        drawRoundRect(
            color = Color(0xFF1B1E2B),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
        )
        waveform.forEachIndexed { index, amplitude ->
            val x = size.width * (index + 0.5f) / waveform.size
            val barHeight = (size.height * 0.58f * amplitude).coerceAtLeast(5.dp.toPx())
            val inRange = x in startX..endX
            drawLine(
                color = if (inRange) OpusVioletGlow.copy(alpha = 0.92f) else OpusTextTertiary.copy(alpha = 0.44f),
                start = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2),
                end = androidx.compose.ui.geometry.Offset(x, centerY + barHeight / 2),
                strokeWidth = barWidth.coerceAtLeast(2.dp.toPx()),
                cap = StrokeCap.Round
            )
        }
        drawRect(color = CaptionAccent.copy(alpha = 0.08f), topLeft = androidx.compose.ui.geometry.Offset(startX, 0f), size = androidx.compose.ui.geometry.Size(endX - startX, size.height))
        drawLine(CaptionAccent, androidx.compose.ui.geometry.Offset(startX, 8.dp.toPx()), androidx.compose.ui.geometry.Offset(startX, size.height - 8.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(CaptionAccent, androidx.compose.ui.geometry.Offset(endX, 8.dp.toPx()), androidx.compose.ui.geometry.Offset(endX, size.height - 8.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(CaptionAccent, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(startX, size.height / 2))
        drawCircle(CaptionAccent, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(endX, size.height / 2))
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(playheadX, 5.dp.toPx()), androidx.compose.ui.geometry.Offset(playheadX, size.height - 5.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.White, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(playheadX, 5.dp.toPx()))
    }
}

private enum class TimelineHandle { START, END, PLAYHEAD }
@Composable
fun CaptionEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CaptionEditorViewModel = rememberCaptionEditorViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OpusDarkCanvas)
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CaptionEditorHeader(onBack = onBack)
        PreviewCard(state = state, viewModel = viewModel)
        TimelineCard(state = state, viewModel = viewModel)
        TranscriptEditorCard(state = state, viewModel = viewModel)
        StyleStrip()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun rememberCaptionEditorViewModel(): CaptionEditorViewModel = remember {
    CaptionDataGraph.component.captionEditorViewModel()
}

@Composable
private fun CaptionEditorHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close caption lab", tint = OpusTextSecondary)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CAPTION LAB", color = CaptionAccent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
                Spacer(Modifier.width(7.dp))
                Box(Modifier.size(4.dp).clip(RoundedCornerShape(50)).background(OpusVioletGlow))
                Spacer(Modifier.width(7.dp))
                Text("ADVANCED CAPTIONS", color = OpusTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            }
            Text("Make every word land.", color = OpusTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp))
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(OpusDarkSurfaceHighlight)
                .border(1.dp, OpusBorder, RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text("DRAFT", color = OpusTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    }
}

@Composable
private fun PreviewCard(state: CaptionEditorUiState, viewModel: CaptionEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Movie, contentDescription = null, tint = OpusElectricCyan, modifier = Modifier.size(18.dp))
            Text("Preview", color = OpusTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
            Spacer(Modifier.weight(1f))
            Text("9:16  •  1080p", color = OpusTextTertiary, fontSize = 11.sp)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.76f)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF20264A), Color(0xFF10131E), Color(0xFF3B1F51))))
                .border(1.dp, OpusBorder, RoundedCornerShape(22.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val step = size.width / 7f
                for (i in 0..7) {
                    drawLine(Color.White.copy(alpha = 0.035f), androidx.compose.ui.geometry.Offset(i * step, 0f), androidx.compose.ui.geometry.Offset(i * step, size.height), strokeWidth = 1.dp.toPx())
                }
                for (i in 0..12) {
                    val y = size.height * i / 12f
                    drawLine(Color.White.copy(alpha = 0.035f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                drawCircle(OpusVioletGlow.copy(alpha = 0.17f), radius = size.width * 0.42f, center = androidx.compose.ui.geometry.Offset(size.width * 0.74f, size.height * 0.24f))
                drawCircle(OpusElectricCyan.copy(alpha = 0.10f), radius = size.width * 0.55f, center = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.78f))
            }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(CaptionAccent))
                    Text(" LIVE CAPTION", color = Color.White.copy(alpha = 0.82f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 7.dp))
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.VolumeUp, contentDescription = "Audio enabled", tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                CaptionOverlay(
                    transcript = state.transcript,
                    positionMs = state.positionMs,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.92f))
                            .clickable { viewModel.togglePlayback() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (state.isPlaying) "Pause preview" else "Play preview", tint = OpusDarkCanvas, modifier = Modifier.size(25.dp))
                    }
                    Column(modifier = Modifier.padding(start = 11.dp)) {
                        Text(state.transcript.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Auto-synced • high energy markers on", color = Color.White.copy(alpha = 0.62f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(state: CaptionEditorUiState, viewModel: CaptionEditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CaptionPanel)
            .border(1.dp, OpusBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ContentCut, contentDescription = null, tint = CaptionAccent, modifier = Modifier.size(18.dp))
            Text("Clip timing", color = OpusTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
            Spacer(Modifier.weight(1f))
            Text("Drag handles to trim", color = OpusTextTertiary, fontSize = 10.sp)
        }
        VideoTimelineScrubber(
            durationMs = state.durationMs,
            positionMs = state.positionMs,
            trimStartMs = state.trimStartMs,
            trimEndMs = state.trimEndMs,
            waveform = state.waveform,
            onSeek = viewModel::seekTo,
            onTrimStartChanged = viewModel::setTrimStart,
            onTrimEndChanged = viewModel::setTrimEnd
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TimecodeChip(label = "IN", value = formatTime(state.trimStartMs), accent = CaptionAccent)
            Text("${formatTime(state.trimEndMs - state.trimStartMs)} selected", color = OpusTextSecondary, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
            TimecodeChip(label = "OUT", value = formatTime(state.trimEndMs), accent = OpusVioletGlow)
        }
    }
}

@Composable
private fun TimecodeChip(label: String, value: String, accent: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp)
        Text(value, color = OpusTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun TranscriptEditorCard(state: CaptionEditorUiState, viewModel: CaptionEditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CaptionPanel)
            .border(1.dp, OpusBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TextFields, contentDescription = null, tint = OpusVioletGlow, modifier = Modifier.size(18.dp))
            Text("Word-by-word transcript", color = OpusTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
            Spacer(Modifier.weight(1f))
            Text("Tap a word to edit", color = OpusTextTertiary, fontSize = 10.sp)
        }
        state.transcript.lines.forEach { line ->
            CaptionLineEditor(
                line = line,
                positionMs = state.positionMs,
                selectedWordId = state.selectedWordId,
                onSelectWord = viewModel::selectWord,
                onUpdateWord = viewModel::updateWord
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CaptionLineEditor(
    line: CaptionLine,
    positionMs: Long,
    selectedWordId: Int?,
    onSelectWord: (Int) -> Unit,
    onUpdateWord: (Int, String) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        line.words.forEach { word ->
            val selected = selectedWordId == word.id
            val active = positionMs in word.startMs until word.endMs
            val background by animateColorAsState(
                if (selected) OpusPrimaryViolet.copy(alpha = 0.42f) else if (active) CaptionAccent.copy(alpha = 0.16f) else OpusDarkSurfaceHighlight,
                label = "editor_word_background_${word.id}"
            )
            var isEditing by remember(word.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(background)
                    .border(1.dp, if (selected) OpusVioletGlow else OpusBorder, RoundedCornerShape(9.dp))
                    .clickable {
                        onSelectWord(word.id)
                        isEditing = true
                    }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditing) {
                    BasicTextField(
                        value = word.text,
                        onValueChange = { onUpdateWord(word.id, it) },
                        singleLine = true,
                        textStyle = TextStyle(color = OpusTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.width((word.text.length.coerceIn(2, 10) * 8 + 14).dp)
                    )
                    Icon(Icons.Default.Check, contentDescription = "Finish editing ${word.text}", tint = CaptionAccent, modifier = Modifier.size(14.dp).clickable { isEditing = false })
                } else {
                    Text(word.text, color = OpusTextPrimary, fontSize = 12.sp, fontWeight = if (active) FontWeight.Black else FontWeight.Medium)
                    if (word.energy >= ProsodicEnergyThreshold) {
                        Icon(Icons.Default.Bolt, contentDescription = "High energy word", tint = CaptionWarm, modifier = Modifier.size(12.dp).padding(start = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleStrip() {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = OpusGold, modifier = Modifier.size(18.dp))
            Text("Caption style", color = OpusTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
            Spacer(Modifier.weight(1f))
            Text("Customizable", color = OpusTextTertiary, fontSize = 10.sp)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            StyleChoice("NEON POP", CaptionAccent, selected = true)
            StyleChoice("CLEAN", Color.White, selected = false)
            StyleChoice("PUNCHY", CaptionWarm, selected = false)
        }
    }
}

@Composable
private fun StyleChoice(label: String, accent: Color, selected: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else OpusDarkSurface)
            .border(1.dp, if (selected) accent else OpusBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(accent))
        Text(label, color = if (selected) accent else OpusTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp, modifier = Modifier.padding(start = 7.dp))
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
