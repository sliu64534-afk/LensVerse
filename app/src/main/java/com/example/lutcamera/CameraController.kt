package com.example.lutcamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * CameraX 会话封装。
 *
 * 预览、录像、拍照三个 UseCase 共享同一个 [LutSurfaceProcessor]，
 * 因此 LUT 会同时烧录进照片和视频。
 *
 * 绑定采用「逐级降级 + 出帧看门狗」策略：
 * 只要 LUT 管线在某个机型上不可用，就自动退回普通预览，
 * 绝不会停在黑屏。
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val processor: LutSurfaceProcessor
) {

    companion object {
        private const val TAG = "LUTCamera"
        private const val ALBUM = "LUTCamera"

        /** 绑定后多久还没有画面就认为 LUT 管线失效。 */
        private const val FRAME_WATCHDOG_MS = 4000L
    }

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var camera2Control: Camera2CameraControl? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var bindGeneration = 0
    private var watchdogGeneration = 0
    private var boundPreview: PreviewView? = null
    private var boundFacing = CameraSelector.LENS_FACING_BACK
    private var boundQuality = VideoQuality.FHD
    private var lastSettings = ManualSettings()

    /** 设备支持的自动模式，避免下发机型不支持的 AE/AWB/AF 模式。 */
    private var aeAutoMode: Int? = CaptureRequest.CONTROL_AE_MODE_ON
    private var awbAutoMode: Int? = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var afAutoMode: Int? = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

    /** LUT 效果管线一旦失败就永久停用，避免反复重绑导致黑屏循环。 */
    private var effectDisabled = false

    var onReady: ((Float) -> Unit)? = null
    var onVideoEvent: ((VideoRecordEvent) -> Unit)? = null
    var onPhotoSaved: ((Uri) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isRecording: Boolean
        get() = recording != null

    // ── 绑定 ────────────────────────────────────────────────────────

    fun bind(
        previewView: PreviewView,
        facing: Int,
        quality: VideoQuality,
        force: Boolean = false
    ) {
        if (!force &&
            provider != null &&
            boundPreview === previewView &&
            boundFacing == facing &&
            boundQuality == quality
        ) {
            return
        }

        boundPreview = previewView
        boundFacing = facing
        boundQuality = quality

        val generation = ++bindGeneration
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            if (generation != bindGeneration) return@addListener

            val cameraProvider = runCatching { future.get() }.getOrElse {
                onError?.invoke("相机初始化失败")
                return@addListener
            }

            provider = cameraProvider
            bindUseCases(cameraProvider, previewView, facing, quality)
        }, mainExecutor)
    }

    private fun bindUseCases(
        cameraProvider: ProcessCameraProvider,
        previewView: PreviewView,
        facing: Int,
        quality: VideoQuality
    ) {
        val selector = runCatching {
            CameraSelector.Builder().requireLensFacing(facing).build()
        }.getOrElse {
            onError?.invoke("该设备没有可用的摄像头")
            return
        }

        // 每一档都重建全新的 UseCase。
        // 复用「绑定失败过」的实例会让后续降级一起失败 —— 这正是黑屏的根源。
        val plans = ArrayList<BindPlan>(5)

        if (!effectDisabled) {
            plans += BindPlan(
                withPhoto = true,
                withVideo = true,
                effectTargets = CameraEffect.PREVIEW or
                        CameraEffect.IMAGE_CAPTURE or
                        CameraEffect.VIDEO_CAPTURE
            )
            plans += BindPlan(
                withPhoto = true,
                withVideo = false,
                effectTargets = CameraEffect.PREVIEW or CameraEffect.IMAGE_CAPTURE
            )
            plans += BindPlan(
                withPhoto = false,
                withVideo = false,
                effectTargets = CameraEffect.PREVIEW
            )
        }

        // 兜底：完全绕开 CameraEffect，至少保证取景画面正常
        plans += BindPlan(withPhoto = true, withVideo = true, effectTargets = 0)
        plans += BindPlan(withPhoto = false, withVideo = false, effectTargets = 0)

        for (plan in plans) {
            cameraProvider.unbindAll()

            val bound = attemptBind(cameraProvider, selector, previewView, quality, plan)
            if (bound != null) {
                onBound(bound)
                return
            }
        }

        Log.e(TAG, "所有绑定方案都失败")
        onError?.invoke("相机绑定失败，请重启应用")
    }

    private fun attemptBind(
        cameraProvider: ProcessCameraProvider,
        selector: CameraSelector,
        previewView: PreviewView,
        quality: VideoQuality,
        plan: BindPlan
    ): BoundSession? {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val capture = if (plan.withPhoto) {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
        } else {
            null
        }

        val video = if (plan.withVideo) {
            VideoCapture.withOutput(
                Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            quality.quality,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
            )
        } else {
            null
        }

        val group = UseCaseGroup.Builder().addUseCase(preview)
        capture?.let { group.addUseCase(it) }
        video?.let { group.addUseCase(it) }
        if (plan.effectTargets != 0) {
            group.addEffect(createEffect(plan.effectTargets))
        }

        return runCatching {
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, group.build())
        }.map { BoundSession(it, capture, video, plan.effectTargets) }
            .onFailure {
                Log.w(
                    TAG,
                    "绑定失败 effect=${plan.effectTargets} " +
                            "photo=${plan.withPhoto} video=${plan.withVideo}",
                    it
                )
            }
            .getOrNull()
    }

    private fun createEffect(targets: Int): CameraEffect =
        object : CameraEffect(
            targets,
            processor.executor,
            processor,
            { error -> onEffectFailure(error) }
        ) {}

    /**
     * CameraEffect 运行期报错（驱动不支持、Surface 创建失败等）。
     * 立刻退回无 LUT 的直通预览，而不是留下黑屏。
     */
    private fun onEffectFailure(error: Throwable) {
        Log.e(TAG, "CameraEffect 运行失败，回退直通预览", error)

        if (effectDisabled) return
        effectDisabled = true

        mainExecutor.execute {
            val view = boundPreview ?: return@execute
            bind(view, boundFacing, boundQuality, force = true)
            onError?.invoke("LUT 渲染不可用，已切换为直通预览")
        }
    }

    /**
     * 绑定成功但迟迟没有画面：说明效果管线静默失败。
     */
    private fun startFrameWatchdog() {
        val generation = ++watchdogGeneration
        val baseline = processor.framesDrawn

        mainHandler.postDelayed({
            if (generation != watchdogGeneration) return@postDelayed
            if (processor.framesDrawn > baseline) return@postDelayed
            if (recording != null) return@postDelayed

            Log.w(TAG, "CameraEffect 在 ${FRAME_WATCHDOG_MS}ms 内没有出帧，回退直通预览")

            effectDisabled = true
            val view = boundPreview ?: return@postDelayed
            bind(view, boundFacing, boundQuality, force = true)
            onError?.invoke("LUT 未出帧，已切换为直通预览")
        }, FRAME_WATCHDOG_MS)
    }

    private fun onBound(bound: BoundSession) {
        watchdogGeneration++

        camera = bound.camera
        imageCapture = bound.capture
        videoCapture = bound.video
        camera2Control = runCatching {
            Camera2CameraControl.from(bound.camera.cameraControl)
        }.getOrNull()

        readSupportedModes(bound.camera)

        val maxZoom = bound.camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        onReady?.invoke(maxZoom)

        applyManualSettings(lastSettings)

        runCatching { bound.camera.cameraControl.setZoomRatio(lastSettings.zoom) }
        runCatching { bound.camera.cameraControl.enableTorch(lastSettings.torch) }

        if (bound.effectTargets != 0) {
            startFrameWatchdog()
        } else if (!effectDisabled) {
            // 效果管线在绑定阶段就失败了，已经退到直通预览
            effectDisabled = true
            onError?.invoke("LUT 效果不可用，已切换为直通预览")
        }
    }

    private fun readSupportedModes(boundCamera: Camera) {
        aeAutoMode = CaptureRequest.CONTROL_AE_MODE_ON
        awbAutoMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
        afAutoMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

        val info = runCatching {
            Camera2CameraInfo.from(boundCamera.cameraInfo)
        }.getOrNull() ?: return

        val aeModes = runCatching {
            info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES
            )
        }.getOrNull()

        aeAutoMode = pickMode(
            aeModes,
            CaptureRequest.CONTROL_AE_MODE_ON,
            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH,
            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        )

        val awbModes = runCatching {
            info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
            )
        }.getOrNull()

        awbAutoMode = pickMode(
            awbModes,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )

        val afModes = runCatching {
            info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
            )
        }.getOrNull()

        afAutoMode = pickMode(
            afModes,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AF_MODE_AUTO
        )
    }

    private fun pickMode(available: IntArray?, vararg preferred: Int): Int? =
        preferred.firstOrNull { candidate -> available?.contains(candidate) == true }

    fun release() {
        bindGeneration++
        watchdogGeneration++
        mainHandler.removeCallbacksAndMessages(null)

        runCatching { recording?.stop() }
        recording = null

        provider?.unbindAll()
        provider = null
        camera = null
        camera2Control = null
        imageCapture = null
        videoCapture = null
        boundPreview = null
        effectDisabled = false
    }

    // ── 手动参数 ────────────────────────────────────────────────────

    fun applyManualSettings(settings: ManualSettings) {
        lastSettings = settings
        val control = camera2Control ?: return

        val builder = CaptureRequestOptions.Builder()
        var hasOption = false

        if (settings.manualExposure) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                settings.iso
            )
            builder.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                settings.shutterNanos
            )
            hasOption = true
        } else {
            aeAutoMode?.let {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, it)
                hasOption = true
            }
        }

        if (settings.manualWhiteBalance) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                whiteBalanceGains(settings.kelvin, settings.tint)
            )
            hasOption = true
        } else {
            awbAutoMode?.let {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, it)
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_FAST
                )
                hasOption = true
            }
        }

        if (settings.manualFocus) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                settings.focusDiopters.coerceIn(0f, 20f)
            )
            hasOption = true
        } else {
            afAutoMode?.let {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, it)
                hasOption = true
            }
        }

        if (!hasOption) return

        runCatching { control.setCaptureRequestOptions(builder.build()) }
            .onFailure { Log.w(TAG, "手动参数下发失败", it) }
    }

    /**
     * 色温 / 色调 → RGGB 白平衡增益。
     *
     * 基于黑体辐射近似，属于"可用级"而非传感器标定级。
     */
    private fun whiteBalanceGains(kelvin: Int, tint: Float): RggbChannelVector {
        val rgb = kelvinToRgb(kelvin)

        var red = 1f / rgb[0]
        var blue = 1f / rgb[2]
        val green = 1f / rgb[1]

        val magenta = 1f + 0.35f * tint.coerceIn(-1f, 1f)
        red *= magenta
        blue *= magenta

        red /= green
        blue /= green

        return RggbChannelVector(
            red.coerceIn(0.1f, 7.9f),
            1f,
            1f,
            blue.coerceIn(0.1f, 7.9f)
        )
    }

    fun setZoom(ratio: Float) {
        lastSettings = lastSettings.copy(zoom = ratio)
        runCatching { camera?.cameraControl?.setZoomRatio(ratio) }
    }

    fun setTorch(enabled: Boolean) {
        lastSettings = lastSettings.copy(torch = enabled)
        runCatching { camera?.cameraControl?.enableTorch(enabled) }
    }

    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        // 手动对焦模式下不再自动对焦，避免与 LENS_FOCUS_DISTANCE 冲突
        if (lastSettings.manualFocus) return

        val boundCamera = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()

        runCatching { boundCamera.cameraControl.startFocusAndMetering(action) }
    }

    // ── 拍照 ────────────────────────────────────────────────────────

    fun takePhoto() {
        val capture = imageCapture ?: run {
            onError?.invoke("当前设备不支持拍照")
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileStamp("jpg"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                )
            }
        }

        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { onPhotoSaved?.invoke(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError?.invoke("拍照失败：${exception.message}")
                }
            }
        )
    }

    // ── 录像 ────────────────────────────────────────────────────────

    fun startRecording(withAudio: Boolean) {
        if (recording != null) return

        val video = videoCapture ?: run {
            onError?.invoke("当前设备不支持录像")
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileStamp("mp4"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM"
                )
            }
        }

        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = video.output.prepareRecording(context, options)
        if (withAudio &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }

        runCatching {
            pending.start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                }
                onVideoEvent?.invoke(event)
            }
        }.onSuccess {
            recording = it
        }.onFailure {
            onError?.invoke("无法开始录制：${it.message}")
        }
    }

    fun stopRecording() {
        runCatching { recording?.stop() }
    }

    private fun fileStamp(extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "LUTCamera_$stamp.$extension"
    }

    // ── 内部数据 ────────────────────────────────────────────────────

    private data class BindPlan(
        val withPhoto: Boolean,
        val withVideo: Boolean,
        val effectTargets: Int
    )

    private data class BoundSession(
        val camera: Camera,
        val capture: ImageCapture?,
        val video: VideoCapture<Recorder>?,
        val effectTargets: Int
    )
}
