package com.example.captions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.captions.data.CaptionDataProvider
import com.example.captions.data.CaptionTranscript
import com.example.captions.data.CaptionWord
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI state deliberately keeps the playback clock separate from transcript mutations. */
data class CaptionEditorUiState(
    val transcript: CaptionTranscript,
    val waveform: List<Float>,
    val positionMs: Long = 1_480L,
    val isPlaying: Boolean = true,
    val trimStartMs: Long = 800L,
    val trimEndMs: Long = 17_600L,
    val selectedWordId: Int? = 2,
    val isInspectorOpen: Boolean = false
) {
    val durationMs: Long get() = transcript.durationMs
    val selectedWord: CaptionWord? get() = transcript.words.firstOrNull { it.id == selectedWordId }
}

class CaptionEditorViewModel @Inject constructor(
    private val dataProvider: CaptionDataProvider
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CaptionEditorUiState(
            transcript = dataProvider.loadTranscript(),
            waveform = dataProvider.loadWaveform()
        )
    )
    val uiState: StateFlow<CaptionEditorUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null

    init {
        startPlayback()
    }

    fun togglePlayback() {
        val nextPlaying = !_uiState.value.isPlaying
        _uiState.update { it.copy(isPlaying = nextPlaying) }
        if (nextPlaying) startPlayback() else playbackJob?.cancel()
    }

    fun selectWord(wordId: Int) {
        _uiState.update { it.copy(selectedWordId = wordId, isInspectorOpen = true) }
    }

    fun closeInspector() {
        _uiState.update { it.copy(isInspectorOpen = false) }
    }

    fun updateWord(wordId: Int, text: String) {
        _uiState.update { state ->
            state.copy(
                transcript = state.transcript.replaceWord(wordId, text),
                selectedWordId = wordId
            )
        }
    }

    fun seekTo(positionMs: Long) {
        _uiState.update { it.copy(positionMs = positionMs.coerceIn(it.trimStartMs, it.trimEndMs)) }
    }

    fun setTrimStart(positionMs: Long) {
        _uiState.update { state ->
            val next = positionMs.coerceIn(0L, state.trimEndMs - 400L)
            state.copy(trimStartMs = next, positionMs = state.positionMs.coerceAtLeast(next))
        }
    }

    fun setTrimEnd(positionMs: Long) {
        _uiState.update { state ->
            val next = positionMs.coerceIn(state.trimStartMs + 400L, state.durationMs)
            state.copy(trimEndMs = next, positionMs = state.positionMs.coerceAtMost(next))
        }
    }

    private fun startPlayback() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (isActive) {
                delay(32L)
                _uiState.update { state ->
                    if (!state.isPlaying) return@update state
                    val next = state.positionMs + 32L
                    if (next >= state.trimEndMs) state.copy(positionMs = state.trimStartMs)
                    else state.copy(positionMs = next)
                }
            }
        }
    }

    override fun onCleared() {
        playbackJob?.cancel()
        super.onCleared()
    }
}

private fun CaptionTranscript.replaceWord(wordId: Int, text: String): CaptionTranscript =
    copy(lines = lines.map { line ->
        line.copy(words = line.words.map { word ->
            if (word.id == wordId) word.copy(text = text) else word
        })
    })
