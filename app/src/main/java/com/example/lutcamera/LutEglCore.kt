
package com.example.lutcamera

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

class LutEglCore {

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_OPENGL_ES3_BIT = 0x0040
        const val EGL_OPENGL_ES2_BIT = 0x0004
        const val TAG = "LUTCamera"
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglRecordableConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // 供 LutOutputSurface 使用
    val display: EGLDisplay
        get() {
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) {
                "EGL 尚未初始化"
            }
            return eglDisplay
        }

    fun initialize() {
        check(eglDisplay == EGL14.EGL_NO_DISPLAY) {
            "EGL 已经初始化"
        }

        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) {
                "无法获取 EGL Display"
            }

            val version = IntArray(2)
            check(
                EGL14.eglInitialize(
                    eglDisplay, version, 0, version, 1
                )
            ) { "EGL 初始化失败" }

            // 预览优先使用普通配置（兼容性最好），recordable 仅作为编码器备选
            eglConfig = chooseConfig(recordable = false)
                ?: chooseConfig(recordable = true)
                ?: error("找不到 OpenGL ES 3.0 EGL 配置")

            eglRecordableConfig = chooseConfig(recordable = true)

            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )

            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                eglConfig,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
            )

            check(eglContext != EGL14.EGL_NO_CONTEXT) {
                "创建 OpenGL ES 3.0 上下文失败"
            }

            val surfaceAttributes = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )

            pbufferSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay,
                eglConfig,
                surfaceAttributes,
                0
            )

            check(pbufferSurface != EGL14.EGL_NO_SURFACE) {
                "创建 EGL Pbuffer 失败"
            }

            makeCurrent()

        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    /**
     * 选择 EGL 配置。
     *
     * 逐级放宽约束：部分设备不提供「带 Alpha 的 ES3 窗口配置」，
     * 一旦 eglChooseConfig 返回 0 个配置就会直接黑屏，因此这里必须有多档回退。
     *
     * recordable 用于把画面送入 MediaCodec 编码器（录制视频）。
     */
    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        val surfaceType = EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT

        val baseSets = listOf(
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceType
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceType
            ),
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceType
            ),
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT
            ),
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceType
            )
        )

        for ((index, base) in baseSets.withIndex()) {
            val attributes = if (recordable) {
                base + intArrayOf(EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            } else {
                base + intArrayOf(EGL14.EGL_NONE)
            }

            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)

            val ok = EGL14.eglChooseConfig(
                eglDisplay,
                attributes,
                0,
                configs,
                0,
                configs.size,
                configCount,
                0
            )

            if (ok && configCount[0] > 0) {
                Log.i(
                    TAG,
                    "EGL 配置选择成功：方案 $index，recordable=$recordable"
                )
                return configs[0]
            }

            // 清掉上一轮的错误码，避免影响下一次判断
            EGL14.eglGetError()
        }

        Log.e(TAG, "没有可用的 EGL 配置（recordable=$recordable）")
        return null
    }

    // 创建连接 CameraX Surface 的 EGL 窗口
    fun createWindowSurface(surface: Surface): EGLSurface {
        check(eglDisplay != EGL14.EGL_NO_DISPLAY)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        check(surface.isValid) { "CameraX 输出 Surface 无效" }

        val config = eglConfig
            ?: error("EGL Config 尚未初始化")

        val attributes = intArrayOf(EGL14.EGL_NONE)

        var result = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            attributes,
            0
        )

        // 编码器 Surface 可能需要 recordable 配置，失败时再试一次
        val fallback = eglRecordableConfig
        if (result == EGL14.EGL_NO_SURFACE && fallback != null && fallback != config) {
            EGL14.eglGetError()
            result = EGL14.eglCreateWindowSurface(
                eglDisplay,
                fallback,
                surface,
                attributes,
                0
            )
        }

        check(result != EGL14.EGL_NO_SURFACE) {
            "创建 EGL Window Surface 失败，错误码：0x${
                Integer.toHexString(EGL14.eglGetError())
            }"
        }

        return result
    }

    // 默认绑定 Pbuffer
    fun makeCurrent() {
        check(pbufferSurface != EGL14.EGL_NO_SURFACE)

        makeCurrent(pbufferSurface)
    }

    // 绑定指定 EGL Surface
    fun makeCurrent(surface: EGLSurface) {
        check(eglDisplay != EGL14.EGL_NO_DISPLAY)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        check(surface != EGL14.EGL_NO_SURFACE)

        check(
            EGL14.eglMakeCurrent(
                eglDisplay,
                surface,
                surface,
                eglContext
            )
        ) { "绑定 EGL 上下文失败" }
    }

    fun release() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return

        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )

        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }

        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }

        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
        eglRecordableConfig = null
    }
}
