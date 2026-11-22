
package com.example.lutcamera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest

/**
 * CameraX 相机画面 GPU 处理器。
 *
 * 所有 OpenGL 操作都在专用 GL 线程执行。
 */
class LutSurfaceProcessor : SurfaceProcessor, AutoCloseable {

    companion object {
        private const val TAG = "LUTCamera"
        private const val HISTOGRAM_WIDTH = 160
        private const val HISTOGRAM_HEIGHT = 90
        private const val HISTOGRAM_INTERVAL_MS = 200L
    }

    private val glThread = LutGlThread()

    /**
     * 创建 CameraEffect 时使用这个 Executor。
     */
    val executor
        get() = glThread.executor

    private val eglCore = LutEglCore()
    private val renderer = LutRenderer()
    private val glProgram = LutGlProgram()

    private val inputs = linkedSetOf<LutInputState>()
    private val outputs = linkedSetOf<LutOutputState>()
    private val outputSnapshot = ArrayList<LutOutputState>(4)

    private var eglInitialized = false
    private var programInitialized = false
    private var lutUploaded = false

    private var closing = false
    private var resourcesReleased = false

    private var currentLut = CubeLut.identity()
    private var intensity = 1.0f

    private val histogram = LutHistogram()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transformMatrix = FloatArray(16)

    /** 直方图回调，主线程触发。 */
    @Volatile
    var histogramListener: ((IntArray) -> Unit)? = null

    private var overlayFlags = 0
    private var zebraThreshold = 0.9f
    private var lastHistogramAt = 0L
    private var lastRenderErrorAt = 0L

    /**
     * 已成功提交的帧数。
     *
     * 供上层判断「效果管线是否真的出画面」，用于黑屏兜底。
     */
    @Volatile
    var framesDrawn: Int = 0
        private set

    /**
     * 初始化 EGL、着色器和默认 LUT。
     * 只能在 GL 线程调用。
     */
    private fun ensureGlReady() {
        glThread.checkCurrentThread()

        if (eglInitialized) return

        eglCore.initialize()
        eglInitialized = true

        glProgram.initialize()
        programInitialized = true

        // LUT 上传失败不应该让整条预览管线停摆，退回 Identity 继续出图
        try {
            renderer.uploadLut(currentLut)
        } catch (error: Throwable) {
            Log.e(TAG, "LUT 上传失败，回退为 Identity", error)
            currentLut = CubeLut.identity()
            renderer.uploadLut(currentLut)
        }

        lutUploaded = renderer.getTextureId() != 0

        Log.d(TAG, "OpenGL 和默认 LUT 初始化完成")
    }

    /**
     * CameraX 请求相机输入 Surface。
     */
    override fun onInputSurface(request: SurfaceRequest) {
        glThread.executor.execute {
            if (closing) {
                request.willNotProvideSurface()
                return@execute
            }

            try {
                ensureGlReady()

                lateinit var inputState: LutInputState

                inputState = LutInputState(
                    request = request,
                    glThread = glThread,
                    onFrameAvailable = {
                        renderFrame(inputState)
                    },
                    onSurfaceResult = { finishedState, _ ->
                        if (inputs.remove(finishedState)) {
                            finishedState.close()
                        }

                        Log.d(TAG, "相机输入 Surface 已释放")

                        if (closing && inputs.isEmpty()) {
                            releaseGlResources()
                        }
                    }
                )

                inputs.add(inputState)

                Log.d(TAG, "相机输入 Surface 已创建")

            } catch (error: Throwable) {
                Log.e(TAG, "创建相机输入失败", error)
                request.willNotProvideSurface()
            }
        }
    }

    /**
     * CameraX 提供预览输出 Surface。
     */
    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glThread.executor.execute {
            if (closing) {
                surfaceOutput.close()
                return@execute
            }

            try {
                ensureGlReady()

                val outputState = LutOutputState(
                    surfaceOutput = surfaceOutput,
                    eglCore = eglCore,
                    glThread = glThread,
                    onCloseRequested = { state ->
                        outputs.remove(state)
                        state.close()

                        Log.d(TAG, "预览输出 Surface 已关闭")
                    }
                )

                outputState.initialize()
                Log.d(TAG, "EGL 输出 Surface 初始化成功")
                outputs.add(outputState)

                Log.d(TAG, "预览输出 Surface 已创建")

            } catch (error: Throwable) {
                Log.e(TAG, "创建预览输出失败", error)
                surfaceOutput.close()
            }
        }
    }

    /**
     * 收到一帧相机画面后执行 GPU 绘制。
     */
    private fun renderFrame(input: LutInputState) {
        glThread.checkCurrentThread()
        if (closing || !inputs.contains(input)) return
        if (!eglInitialized) return

        val lutTextureId = renderer.getTextureId()
        if (lutTextureId == 0) return

        try {
            // 更新相机纹理
            input.cameraTexture.updateTexImage()

            // 获取相机原始纹理变换矩阵
            val originalMatrix =
                input.cameraTexture.getTransformMatrix()

            var drewAny = false

            // 逐个绘制到 CameraX 输出（预览 / 录像 / 拍照共用同一帧）
            outputSnapshot.clear()
            outputSnapshot.addAll(outputs)

            for (output in outputSnapshot) {
                if (output.width <= 0 || output.height <= 0) {
                    continue
                }

                // 应用 CameraX 的旋转、裁切和镜像变换
                output.surfaceOutput.updateTransformMatrix(
                    transformMatrix,
                    originalMatrix
                )

                output.makeCurrent()

                // 执行 LUT 着色器
                glProgram.draw(
                    width = output.width,
                    height = output.height,
                    cameraTextureId = input.cameraTexture.textureId,
                    lutTextureId = lutTextureId,
                    intensity = intensity,
                    domainMin = currentLut.domainMin,
                    domainMax = currentLut.domainMax,
                    textureMatrix = transformMatrix,
                    overlayFlags = overlayFlags,
                    zebraThreshold = zebraThreshold
                )

                if (!output.swapBuffers()) {
                    Log.w(TAG, "画面提交失败")
                }

                drewAny = true
            }

            if (drewAny) {
                framesDrawn++
                updateHistogram(input, lutTextureId)
            }

        } catch (error: Throwable) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRenderErrorAt > 1000L) {
                lastRenderErrorAt = now
                Log.e(TAG, "GPU 绘制画面失败", error)
            }
        }
    }

    /**
     * 低频离屏渲染一张小图并统计直方图，不影响主渲染路径。
     */
    private fun updateHistogram(input: LutInputState, lutTextureId: Int) {
        val listener = histogramListener ?: return

        val now = SystemClock.uptimeMillis()
        if (now - lastHistogramAt < HISTOGRAM_INTERVAL_MS) return
        lastHistogramAt = now

        try {
            histogram.ensure(HISTOGRAM_WIDTH, HISTOGRAM_HEIGHT)
            histogram.bind()

            glProgram.draw(
                width = HISTOGRAM_WIDTH,
                height = HISTOGRAM_HEIGHT,
                cameraTextureId = input.cameraTexture.textureId,
                lutTextureId = lutTextureId,
                intensity = intensity,
                domainMin = currentLut.domainMin,
                domainMax = currentLut.domainMax,
                textureMatrix = transformMatrix,
                overlayFlags = 0,
                zebraThreshold = zebraThreshold
            )

            val bins = histogram.read()
            histogram.unbind()

            if (bins != null) {
                mainHandler.post { listener(bins) }
            }
        } catch (error: Throwable) {
            histogram.unbind()
            Log.w(TAG, "直方图计算失败", error)
        }
    }

    /**
     * 更换 LUT。
     * 可以从主线程调用。
     */
    fun setLut(lut: CubeLut) {
        glThread.executor.execute {
            if (closing) return@execute

            try {
                ensureGlReady()
                renderer.uploadLut(lut)
                currentLut = lut
                lutUploaded = true

                Log.d(TAG, "LUT 已更新：${lut.title}")

            } catch (error: Throwable) {
                Log.e(TAG, "更新 LUT 失败，保留当前 LUT", error)
            }
        }
    }

    /**
     * 设置滤镜强度，范围 0 到 1。
     */
    fun setIntensity(value: Float) {
        glThread.executor.execute {
            intensity = value.coerceIn(0f, 1f)
        }
    }

    /**
     * 设置监看叠加（斑马纹 / 峰值对焦 / 伪色）。
     *
     * 录制或拍照时传 0，避免叠加被写进成片。
     */
    fun setOverlayFlags(flags: Int, zebraThreshold: Float = 0.9f) {
        glThread.executor.execute {
            overlayFlags = flags
            this.zebraThreshold = zebraThreshold.coerceIn(0.5f, 1f)
        }
    }

    /**
     * 关闭处理器。
     *
     * 输入 Surface 必须等 CameraX 的结果回调后才能释放。
     */
    override fun close() {
        glThread.executor.execute {
            if (closing) return@execute

            closing = true

            outputs.toList().forEach { output ->
                output.close()
            }
            outputs.clear()

            if (inputs.isEmpty()) {
                releaseGlResources()
            }

            Log.d(TAG, "正在关闭 LUT 处理器")
        }
    }

    /**
     * 释放 GPU 资源。
     * 必须等所有输入 Surface 释放后调用。
     */
    private fun releaseGlResources() {
        glThread.checkCurrentThread()

        if (resourcesReleased) return
        resourcesReleased = true

        if (eglInitialized) {
            if (lutUploaded) {
                renderer.release()
                lutUploaded = false
            }

            histogram.release()

            if (programInitialized) {
                glProgram.release()
                programInitialized = false
            }

            eglCore.release()
            eglInitialized = false
        }

        glThread.close()

        Log.d(TAG, "GPU 资源已释放")
    }
}
