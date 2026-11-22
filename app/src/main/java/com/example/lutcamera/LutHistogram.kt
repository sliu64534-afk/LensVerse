package com.example.lutcamera

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 离屏直方图采样器。
 *
 * 把当前帧以低分辨率渲染到 FBO，再读回像素统计 RGB 直方图。
 * 必须在 GL 线程使用。
 */
class LutHistogram {

    private var framebufferId = 0
    private var textureId = 0
    private var width = 0
    private var height = 0
    private var buffer: ByteBuffer? = null

    private val bins = IntArray(BIN_COUNT * 3)

    fun ensure(w: Int, h: Int) {
        if (framebufferId != 0 && width == w && height == h) return

        release()

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        check(textureId != 0) { "创建直方图纹理失败" }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            w,
            h,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        framebufferId = framebuffers[0]
        check(framebufferId != 0) { "创建直方图 FBO 失败" }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            release()
            error("直方图 FBO 不完整：0x${Integer.toHexString(status)}")
        }

        width = w
        height = h
        buffer = ByteBuffer.allocateDirect(w * h * 4)
            .order(ByteOrder.nativeOrder())
    }

    fun bind() {
        check(framebufferId != 0) { "直方图尚未初始化" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
    }

    fun unbind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * 读回像素并统计。返回 [r0..r255, g0..g255, b0..b255]。
     */
    fun read(): IntArray? {
        val pixels = buffer ?: return null
        if (framebufferId == 0) return null

        pixels.position(0)
        GLES30.glReadPixels(
            0,
            0,
            width,
            height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels
        )
        pixels.position(0)

        bins.fill(0)

        // 每 2 个像素采样一次，进一步降低 CPU 开销
        var index = 0
        val step = 8
        val total = width * height * 4
        while (index + 2 < total) {
            bins[pixels.get(index).toInt() and 0xFF]++
            bins[BIN_COUNT + (pixels.get(index + 1).toInt() and 0xFF)]++
            bins[BIN_COUNT * 2 + (pixels.get(index + 2).toInt() and 0xFF)]++
            index += step
        }

        return bins.copyOf()
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (framebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            framebufferId = 0
        }
        width = 0
        height = 0
        buffer = null
    }

    companion object {
        const val BIN_COUNT = 256
    }
}
