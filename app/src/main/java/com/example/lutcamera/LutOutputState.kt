
package com.example.lutcamera

import androidx.camera.core.SurfaceOutput
import android.view.Surface

/**
 * 管理一个 CameraX 预览输出。
 * 所有 EGL 操作必须在 GL 线程执行。
 */
class LutOutputState(
    val surfaceOutput: SurfaceOutput,
    private val eglCore: LutEglCore,
    private val glThread: LutGlThread,
    private val onCloseRequested: (LutOutputState) -> Unit
) : AutoCloseable {

    private lateinit var outputSurface: Surface
    private lateinit var eglOutput: LutOutputSurface

    private var initialized = false
    private var released = false

    val width: Int
        get() = surfaceOutput.size.width

    val height: Int
        get() = surfaceOutput.size.height

    init {
        glThread.checkCurrentThread()

        outputSurface = surfaceOutput.getSurface(
            glThread.executor
        ) { event ->
            if (
                event.eventCode ==
                SurfaceOutput.Event.EVENT_REQUEST_CLOSE
            ) {
                onCloseRequested(this)
            }
        }

        eglOutput = LutOutputSurface(
            eglCore,
            outputSurface
        )
    }

    fun initialize() {
        glThread.checkCurrentThread()

        check(!released) {
            "预览输出已经释放"
        }

        if (initialized) return

        eglOutput.initialize()
        initialized = true
    }

    fun makeCurrent() {
        glThread.checkCurrentThread()

        check(initialized && !released) {
            "预览输出尚未初始化或已经释放"
        }

        eglOutput.makeCurrent()
    }

    fun swapBuffers(): Boolean {
        glThread.checkCurrentThread()

        check(initialized && !released) {
            "预览输出尚未初始化或已经释放"
        }

        return eglOutput.swapBuffers()
    }

    override fun close() {
        glThread.checkCurrentThread()

        if (released) return

        released = true

        try {
            if (initialized) {
                eglOutput.release()
                initialized = false
            }
        } finally {
            surfaceOutput.close()
        }
    }
}
