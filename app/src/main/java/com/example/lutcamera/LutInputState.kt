
package com.example.lutcamera

import androidx.camera.core.SurfaceRequest

/**
 * 管理一次 CameraX 相机输入请求及其纹理资源。
 *
 * 必须在 GL 线程中创建和释放。
 */
class LutInputState(
    request: SurfaceRequest,
    private val glThread: LutGlThread,
    onFrameAvailable: () -> Unit,
    private val onSurfaceResult:
        (LutInputState, SurfaceRequest.Result) -> Unit
) : AutoCloseable {

    val cameraTexture: LutCameraTexture

    private var released = false

    init {
        glThread.checkCurrentThread()

        // 创建相机外部纹理
        cameraTexture = LutCameraTexture(
            glHandler = glThread.handler,
            onFrameAvailable = onFrameAvailable
        )

        // 设置相机输入尺寸
        cameraTexture.setDefaultBufferSize(
            request.resolution.width,
            request.resolution.height
        )

        // 将纹理 Surface 提供给 CameraX
        request.provideSurface(
            cameraTexture.surface,
            glThread.executor
        ) { result ->
            onSurfaceResult(this, result)
        }
    }

    override fun close() {
        glThread.checkCurrentThread()

        if (released) return

        released = true
        cameraTexture.close()
    }
}
