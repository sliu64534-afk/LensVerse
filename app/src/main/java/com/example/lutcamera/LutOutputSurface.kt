
package com.example.lutcamera

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.view.Surface

class LutOutputSurface(
    private val eglCore: LutEglCore,
    private val outputSurface: Surface
) {

    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun initialize() {
        check(eglSurface == EGL14.EGL_NO_SURFACE)

        eglSurface = eglCore.createWindowSurface(outputSurface)
        eglCore.makeCurrent(eglSurface)
    }

    fun makeCurrent() {
        check(eglSurface != EGL14.EGL_NO_SURFACE)
        eglCore.makeCurrent(eglSurface)
    }

    fun swapBuffers(): Boolean {
        check(eglSurface != EGL14.EGL_NO_SURFACE)

        return EGL14.eglSwapBuffers(
            eglCore.display,
            eglSurface
        )
    }

    fun release() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {

            // 先切回 Pbuffer
            eglCore.makeCurrent()

            // 再销毁窗口 Surface
            EGL14.eglDestroySurface(
                eglCore.display,
                eglSurface
            )

            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}
