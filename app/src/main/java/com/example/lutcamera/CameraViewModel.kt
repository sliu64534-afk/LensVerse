package com.example.lutcamera

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CameraUiState(
    val activeLut: CubeLut? = null,
    val lutName: String = "Identity",
    val lutEnabled: Boolean = true,
    val lutIntensity: Float = 1f,
    val selectedLutId: String = "builtin:identity",
    val entries: List<LutEntry> = emptyList(),
    val settings: ManualSettings = ManualSettings(),
    val overlays: OverlaySettings = OverlaySettings(),
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val quality: VideoQuality = VideoQuality.FHD,
    val mode: CameraMode = CameraMode.VIDEO,
    val recording: Boolean = false,
    val recordingStartedAt: Long = 0L,
    val capturing: Boolean = false,
    val maxZoom: Float = 1f,
    val ready: Boolean = false,
    val audioEnabled: Boolean = true,
    val message: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val PREFS = "lut_camera"
        const val KEY_LUT = "selected_lut"
        const val DEFAULT_LUT = "builtin:identity"
    }

    private val library = LutLibrary(application)
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var state by mutableStateOf(CameraUiState())
        private set

    /** 直方图独立成 state，避免每秒多次触发整个界面重组。 */
    var histogram by mutableStateOf<IntArray?>(null)
        private set

    init {
        refreshEntries()
        selectLut(prefs.getString(KEY_LUT, DEFAULT_LUT) ?: DEFAULT_LUT, announce = false)
    }

    // ── LUT ─────────────────────────────────────────────────────────

    private fun refreshEntries() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { library.entries() }
            state = state.copy(entries = entries)
        }
    }

    fun selectLut(id: String, announce: Boolean = true) {
        viewModelScope.launch {
            val lut = runCatching {
                withContext(Dispatchers.IO) { library.load(id) }
            }.getOrNull()

            if (lut == null) {
                state = state.copy(message = "LUT 读取失败")
                return@launch
            }

            prefs.edit().putString(KEY_LUT, id).apply()

            state = state.copy(
                activeLut = lut,
                lutName = lut.title,
                selectedLutId = id,
                lutEnabled = true,
                message = if (announce) "已应用 ${lut.title}" else state.message
            )
        }
    }

    fun importLut(uri: Uri) {
        viewModelScope.launch {
            state = state.copy(message = "正在导入 LUT…")

            runCatching {
                withContext(Dispatchers.IO) { library.import(uri) }
            }.onSuccess { entry ->
                val entries = withContext(Dispatchers.IO) { library.entries() }
                state = state.copy(entries = entries)
                selectLut(entry.id, announce = false)
                state = state.copy(message = "已导入 ${entry.name}")
            }.onFailure { error ->
                state = state.copy(message = error.message ?: "导入失败")
            }
        }
    }

    fun deleteLut(entry: LutEntry) {
        if (entry.builtIn) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.delete(entry.id) }

            if (state.selectedLutId == entry.id) {
                selectLut(DEFAULT_LUT, announce = false)
            }

            val entries = withContext(Dispatchers.IO) { library.entries() }
            state = state.copy(entries = entries, message = "已删除 ${entry.name}")
        }
    }

    fun setIntensity(value: Float) {
        state = state.copy(lutIntensity = value.coerceIn(0f, 1f))
    }

    fun toggleLut() {
        state = state.copy(lutEnabled = !state.lutEnabled)
    }

    // ── 拍摄参数 ─────────────────────────────────────────────────────

    fun updateSettings(transform: (ManualSettings) -> ManualSettings) {
        state = state.copy(settings = transform(state.settings))
    }

    fun updateOverlays(transform: (OverlaySettings) -> OverlaySettings) {
        state = state.copy(overlays = transform(state.overlays))
    }

    fun setMode(mode: CameraMode) {
        state = state.copy(mode = mode)
    }

    fun setLensFacing(facing: Int) {
        if (state.recording) {
            state = state.copy(message = "录制中不能切换镜头")
            return
        }
        state = state.copy(lensFacing = facing)
    }

    fun setQuality(quality: VideoQuality) {
        if (state.recording) {
            state = state.copy(message = "录制中不能切换画质")
            return
        }
        state = state.copy(quality = quality)
    }

    fun setMaxZoom(maxZoom: Float) {
        state = state.copy(maxZoom = maxZoom.coerceAtLeast(1f), ready = true)
    }

    fun setAudioEnabled(enabled: Boolean) {
        state = state.copy(audioEnabled = enabled)
    }

    fun updateHistogram(bins: IntArray) {
        histogram = bins
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    // ── 拍摄事件 ─────────────────────────────────────────────────────

    fun onCaptureStarted() {
        state = state.copy(capturing = true)
    }

    fun onCaptureFinished() {
        state = state.copy(capturing = false)
    }

    fun onPhotoSaved(uri: Uri) {
        state = state.copy(capturing = false, message = "照片已保存")
    }

    fun onVideoEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> state = state.copy(
                recording = true,
                recordingStartedAt = System.currentTimeMillis(),
                message = null
            )

            is VideoRecordEvent.Finalize -> {
                val message = when (event.error) {
                    VideoRecordEvent.Finalize.ERROR_NONE -> "视频已保存"
                    VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "录制时间太短，未生成文件"
                    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "存储空间不足"
                    else -> "录制结束（错误码 ${event.error}）"
                }
                state = state.copy(
                    recording = false,
                    recordingStartedAt = 0L,
                    message = message
                )
            }

            else -> Unit
        }
    }

    fun onError(message: String) {
        state = state.copy(capturing = false, message = message)
    }
}
