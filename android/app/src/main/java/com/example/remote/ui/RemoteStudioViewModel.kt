package com.example.remote.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.remote.data.RemoteProcessingCoordinator
import com.example.remote.data.RemoteProcessingStore
import com.example.remote.model.GatewayJobState
import com.example.remote.model.LocalProcessingJob
import com.example.remote.model.PickedVideo
import com.example.remote.model.RemoteClip
import com.example.remote.model.RemoteScreen
import com.example.remote.model.RemoteUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RemoteStudioViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = RemoteProcessingCoordinator(application)
    private val store = RemoteProcessingStore(application)
    private val _state = MutableStateFlow(
        RemoteUiState(
            screen = initialScreen(coordinator.job.value)
        )
    )
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            coordinator.job.collect { job ->
                _state.update { current ->
                    current.copy(
                        job = job,
                        screen = when {
                            current.screen == RemoteScreen.SETTINGS -> RemoteScreen.SETTINGS
                            job?.state == GatewayJobState.FAILED -> RemoteScreen.ERROR
                            job?.state == GatewayJobState.COMPLETED -> RemoteScreen.RESULTS
                            job?.isActive == true -> RemoteScreen.PROCESSING
                            else -> current.screen
                        },
                        isBusy = false
                    )
                }
            }
        }
    }

    fun openImport() = _state.update { it.copy(screen = RemoteScreen.IMPORT, notice = null) }
    fun openHome() = _state.update { it.copy(screen = RemoteScreen.HOME, notice = null) }
    fun openSettings() = _state.update { it.copy(screen = RemoteScreen.SETTINGS, notice = null) }
    fun savedBaseUrl(): String = store.lastBaseUrl()
    fun hasSavedToken(): Boolean = store.hasSavedToken()
    fun openProcessing() = _state.update { it.copy(screen = RemoteScreen.PROCESSING) }
    fun openResults() = _state.update { it.copy(screen = RemoteScreen.RESULTS) }
    fun openReview() = _state.update { it.copy(screen = RemoteScreen.REVIEW) }
    fun openError() = _state.update { it.copy(screen = RemoteScreen.ERROR) }
    fun selectClip(clipId: String) = _state.update { it.copy(selectedClipId = clipId, screen = RemoteScreen.REVIEW) }

    fun selectVideo(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        _state.update { it.copy(pickedVideo = readVideo(resolver, uri), screen = RemoteScreen.IMPORT, notice = null) }
    }

    fun startProcessing() {
        val picked = state.value.pickedVideo ?: return showNotice("اختر فيديو قبل البدء")
        if (state.value.job?.isActive == true) {
            _state.update { it.copy(screen = RemoteScreen.PROCESSING, notice = "هناك مهمة قيد التنفيذ بالفعل") }
            return
        }
        _state.update { it.copy(isBusy = true, notice = null) }
        coordinator.start(picked.displayName, picked.uri)
        _state.update { it.copy(screen = RemoteScreen.PROCESSING, isBusy = false) }
    }

    fun saveSettings(baseUrl: String, token: String) {
        runCatching { store.saveGatewayConfig(baseUrl, token) }
            .onFailure { showNotice(it.localizedMessage ?: "تعذر حفظ الإعدادات") }
            .onSuccess { showNotice("تم حفظ إعدادات Gateway بأمان") }
    }

    fun testConnection() {
        _state.update { it.copy(isBusy = true, notice = null) }
        viewModelScope.launch {
            coordinator.checkConnection()
                .onSuccess { health -> _state.update { it.copy(connection = health, isBusy = false, notice = health.message) } }
                .onFailure { error -> _state.update { it.copy(connection = null, isBusy = false, notice = error.localizedMessage ?: "تعذر الاتصال بـ Gateway") } }
        }
    }

    fun cancel() {
        _state.update { it.copy(isBusy = true, notice = null) }
        viewModelScope.launch {
            coordinator.cancel()
                .onSuccess { _state.update { it.copy(isBusy = false, screen = RemoteScreen.HOME, notice = "تم إرسال طلب الإلغاء") } }
                .onFailure { error -> _state.update { it.copy(isBusy = false, notice = error.localizedMessage ?: "تعذر إلغاء المهمة") } }
        }
    }

    fun retry() {
        _state.update { it.copy(isBusy = true, notice = null) }
        viewModelScope.launch {
            coordinator.retry()
                .onSuccess { _state.update { it.copy(isBusy = false, screen = RemoteScreen.PROCESSING, notice = "ستُعاد معالجة الفيديو") } }
                .onFailure { error -> _state.update { it.copy(isBusy = false, notice = error.localizedMessage ?: "تعذر إعادة المحاولة") } }
        }
    }

    fun resume() {
        _state.update { it.copy(isBusy = true, notice = null) }
        viewModelScope.launch {
            coordinator.resume()
                .onSuccess { _state.update { it.copy(isBusy = false, screen = RemoteScreen.PROCESSING, notice = "تمت جدولة استئناف المهمة") } }
                .onFailure { error -> _state.update { it.copy(isBusy = false, notice = error.localizedMessage ?: "تعذر استئناف المهمة") } }
        }
    }

    fun updateClip(clipId: String, start: Int, end: Int) {
        val current = state.value.job ?: return
        val clips = current.clips.map { clip -> if (clip.id == clipId) clip.withTrim(start, end) else clip }
        store.saveJob(current.copy(clips = clips))
    }

    fun forgetCompletedJob() = coordinator.forgetCompletedJob().also { openHome() }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun showNotice(message: String) = _state.update { it.copy(notice = message) }

    private fun readVideo(resolver: ContentResolver, uri: Uri): PickedVideo {
        var name = "video.mp4"
        var bytes = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { bytes = cursor.getLong(it).coerceAtLeast(0L) }
            }
        }
        return PickedVideo(uri, name, bytes, durationSeconds = null)
    }

    private fun initialScreen(job: LocalProcessingJob?): RemoteScreen = when {
        job?.state == GatewayJobState.FAILED -> RemoteScreen.ERROR
        job?.state == GatewayJobState.COMPLETED -> RemoteScreen.RESULTS
        job?.isActive == true -> RemoteScreen.PROCESSING
        else -> RemoteScreen.HOME
    }
}
