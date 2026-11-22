
package com.example.lutcamera

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface

/**
 * 管理相机输入用的 OpenGL 外部纹理
 *
 * 必须在已经创建 EGL 上下文的 GL 线程中创建和释放。
 */
class LutCameraTexture(
    private val glHandler: Handler,
    private val onFrameAvailable: () -> Unit
) : AutoCloseable {

    val textureId: Int

    val surfaceTexture: SurfaceTexture

    val surface: Surface

    private var released = false

    private val transformMatrix = FloatArray(16)

    init {
        val textures = IntArray(1)

        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        check(textureId != 0) {
            "创建相机外部纹理失败"
        }

        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            textureId
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            0
        )

        surfaceTexture = SurfaceTexture(textureId)

        surfaceTexture.setOnFrameAvailableListener(
            {
                if (!released) {
                    onFrameAvailable()
                }
            },
            glHandler
        )

        surface = Surface(surfaceTexture)
    }

    /**
     * 设置相机输入画面的尺寸
     */
    fun setDefaultBufferSize(width: Int, height: Int) {
        check(!released) {
            "相机纹理已经释放"
        }

        surfaceTexture.setDefaultBufferSize(width, height)
    }

    /**
     * 更新最新相机画面
     */
    fun updateTexImage() {
        check(!released) {
            "相机纹理已经释放"
        }

        surfaceTexture.updateTexImage()
    }

    /**
     * 获取相机纹理变换矩阵。
     *
     * 返回复用缓冲区，仅在 GL 线程内立即使用，不要持有引用。
     */
    fun getTransformMatrix(): FloatArray {
        check(!released) {
            "相机纹理已经释放"
        }

        surfaceTexture.getTransformMatrix(transformMatrix)
        return transformMatrix
    }

    /**
     * 释放资源
     *
     * 必须在创建纹理的 GL 线程中调用。
     */
    override fun close() {
        if (released) return

        released = true

        surface.release()
        surfaceTexture.release()

        GLES20.glDeleteTextures(
            1,
            intArrayOf(textureId),
            0
        )
    }
}
