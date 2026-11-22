package com.example.lutcamera

import androidx.camera.video.Quality

/** 拍摄模式。 */
enum class CameraMode { VIDEO, PHOTO }

/** 录制帧率。 */
enum class VideoFrameRate(val value: Int) {
    FPS24(24),
    FPS30(30),
    FPS60(60),
    FPS120(120)
}

/** 录制画质。 */
enum class VideoQuality(val label: String, val quality: Quality) {
    FHD("1080p", Quality.FHD),
    UHD("4K", Quality.UHD)
}

/**
 * 色彩空间标签。
 *
 * 与 Blackmagic Camera 的 Color Space Tag 对应，随文件一起写入媒体信息，
 * 用于后期软件识别素材色彩空间，不改变传感器实际输出格式。
 */
enum class ColorSpaceTag(val label: String) {
    REC709("Rec.709"),
    REC2020("Rec.2020"),
    P3D65("P3 D65"),
    LOG("Log / HDR")
}

/**
 * 手动曝光 / 白平衡 / 对焦参数。
 *
 * 通过 Camera2CameraControl 下发，不重建用例，拖动滑块不会闪屏。
 */
data class ManualSettings(
    val manualExposure: Boolean = false,
    val iso: Int = 800,
    val shutterDenominator: Int = 48,
    val manualWhiteBalance: Boolean = false,
    val kelvin: Int = 5600,
    val tint: Float = 0f,
    val manualFocus: Boolean = false,
    val focusDiopters: Float = 0f,
    val zoom: Float = 1f,
    val torch: Boolean = false
) {
    val shutterNanos: Long
        get() = 1_000_000_000L / shutterDenominator.coerceAtLeast(1)

    val shutterLabel: String
        get() = "1/${shutterDenominator}"
}

/** 监看叠加。 */
data class OverlaySettings(
    val grid: Boolean = true,
    val histogram: Boolean = true,
    val zebra: Boolean = false,
    val zebraThreshold: Float = 0.90f,
    val peaking: Boolean = false,
    val falseColor: Boolean = false
) {
    val shaderFlags: Int
        get() = (if (zebra) LutShader.OVERLAY_ZEBRA else 0) or
                (if (peaking) LutShader.OVERLAY_PEAKING else 0) or
                (if (falseColor) LutShader.OVERLAY_FALSE_COLOR else 0)
}

/** 常用档位，供 UI 滑块使用。 */
object CameraStops {
    val iso = intArrayOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
        800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400
    )

    val shutterDenominators = intArrayOf(
        2000, 1000, 500, 250, 125, 60, 48, 30, 24, 15, 8, 4, 2
    )
}
