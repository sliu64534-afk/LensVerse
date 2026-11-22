
package com.example.lutcamera

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LutRenderer {

    private var lutTextureId = 0

    /**
     * 将 CubeLut 数据上传为 OpenGL ES 3D 纹理
     */
    fun uploadLut(lut: CubeLut): Int {
        require(lut.size >= 2) {
            "LUT 尺寸不能小于 2"
        }

        require(lut.data.size == lut.size * lut.size * lut.size) {
            "LUT 数据数量不正确"
        }

        // 每个颜色使用 RGBA 四个字节
        val buffer = ByteBuffer.allocateDirect(
            lut.size * lut.size * lut.size * 4
        ).order(ByteOrder.nativeOrder())

        // .cube 文件中红色通道变化最快
        for (color in lut.data) {
            buffer.put(toByte(color[0]))
            buffer.put(toByte(color[1]))
            buffer.put(toByte(color[2]))
            buffer.put(255.toByte())
        }

        buffer.position(0)

        // 先创建新纹理，成功后再删除旧纹理。
        // 否则上传失败会留下一个空纹理，画面直接变黑。
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)

        val newTextureId = textures[0]

        check(newTextureId != 0) {
            "创建 LUT 纹理失败"
        }

        try {
            GLES30.glBindTexture(
                GLES30.GL_TEXTURE_3D,
                newTextureId
            )

            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_3D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )

            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_3D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )

            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_3D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )

            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_3D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )

            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_3D,
                GLES30.GL_TEXTURE_WRAP_R,
                GLES30.GL_CLAMP_TO_EDGE
            )

            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D,
                0,
                GLES30.GL_RGBA8,
                lut.size,
                lut.size,
                lut.size,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                buffer
            )

            GLES30.glBindTexture(
                GLES30.GL_TEXTURE_3D,
                0
            )

            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) {
                "LUT 纹理上传失败：0x${Integer.toHexString(error)}"
            }
        } catch (throwable: Throwable) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
            GLES30.glDeleteTextures(1, intArrayOf(newTextureId), 0)
            throw throwable
        }

        // 新纹理就绪，替换旧纹理
        if (lutTextureId != 0) {
            GLES30.glDeleteTextures(
                1,
                intArrayOf(lutTextureId),
                0
            )
        }

        lutTextureId = newTextureId
        return lutTextureId
    }

    fun getTextureId(): Int {
        return lutTextureId
    }

    fun release() {
        if (lutTextureId != 0) {
            GLES30.glDeleteTextures(
                1,
                intArrayOf(lutTextureId),
                0
            )
            lutTextureId = 0
        }
    }

    private fun toByte(value: Float): Byte =
        (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            .coerceIn(0, 255)
            .toByte()
}
