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
    /** 是否把 LUT 烧录进成片；关闭时 LUT 仅用于监看。 */
    val recordLutToClip: Boolean = true,
    /** 成片色彩空间标签。 */
    val colorSpaceTag: ColorSpaceTag = ColorSpaceTag.REC709,
    /** 是否在取景画面右上角显示 3D LUT 状态提示。 */
    val lutHintVisible: Boolean = true,
    /** 是否显示横向悬浮 LUT 选择条。 */
    val lutStripVisible: Boolean = false,
    val selectedLutId: String = "builtin:identity",
    val entries: List<LutEntry> = emptyList(),
    val settings: ManualSettings = ManualSettings(),
    val overlays: OverlaySettings = OverlaySettings(),
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val quality: VideoQuality = VideoQuality.FHD,
    val frameRate: VideoFrameRate = VideoFrameRate.FPS30,
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
        const val KEY_RECORD_LUT = "record_lut_to_clip"
        const val KEY_COLOR_SPACE = "color_space_tag"
        const val KEY_LUT_HINT = "lut_hint_visible"
        const val KEY_FRAME_RATE = "frame_rate"
    }

    private val library = LutLibrary(application)
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var state by mutableStateOf(CameraUiState())
        private set

    /** 直方图独立成 state，避免每秒多次触发整个界面重组。 */
    var histogram by mutableStateOf<IntArray?>(null)
        private set

    init {
        state = state.copy(
            recordLutToClip = prefs.getBoolean(KEY_RECORD_LUT, true),
            colorSpaceTag = runCatching {
                ColorSpaceTag.valueOf(
                    prefs.getString(KEY_COLOR_SPACE, ColorSpaceTag.REC709.name)
                        ?: ColorSpaceTag.REC709.name
                )
            }.getOrDefault(ColorSpaceTag.REC709),
            lutHintVisible = prefs.getBoolean(KEY_LUT_HINT, true),
            frameRate = runCatching {
                VideoFrameRate.valueOf(
                    prefs.getString(KEY_FRAME_RATE, VideoFrameRate.FPS30.name)
                        ?: VideoFrameRate.FPS30.name
                )
            }.getOrDefault(VideoFrameRate.FPS30)
        )
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

    /**
     * 切换烧录 LUT。
     *
     * 关闭时 LUT 只作用于监看输出，成片保持原始 Log / Rec.709 画面，
     * 后期可以重新调色。切换后需要重新绑定相机用例，录制中禁止操作。
     */
    fun setRecordLutToClip(value: Boolean) {
        if (state.recording) {
            state = state.copy(message = "录制中不能修改 LUT 烧录设置")
            return
        }
        prefs.edit().putBoolean(KEY_RECORD_LUT, value).apply()
        state = state.copy(recordLutToClip = value)
    }

    fun setColorSpaceTag(tag: ColorSpaceTag) {
        prefs.edit().putString(KEY_COLOR_SPACE, tag.name).apply()
        state = state.copy(colorSpaceTag = tag)
    }

    fun setLutHintVisible(value: Boolean) {
        prefs.edit().putBoolean(KEY_LUT_HINT, value).apply()
        state = state.copy(lutHintVisible = value)
    }

    fun toggleLutStrip() {
        state = state.copy(lutStripVisible = !state.lutStripVisible)
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

    fun setFrameRate(frameRate: VideoFrameRate) {
        if (state.recording) {
            state = state.copy(message = "录制中不能切换帧率")
            return
        }
        prefs.edit().putString(KEY_FRAME_RATE, frameRate.name).apply()
        state = state.copy(frameRate = frameRate)
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
