
package com.example.lutcamera

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 管理 LUT 着色器程序和 GPU 绘制
 *
 * 必须在 EGL 上下文有效的 GL 线程中调用。
 */
class LutGlProgram {

    private var programId = 0

    private var cameraTextureLocation = -1
    private var lutTextureLocation = -1
    private var intensityLocation = -1
    private var domainMinLocation = -1
    private var domainMaxLocation = -1
    private var textureMatrixLocation = -1
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var texelSizeLocation = -1
    private var zebraLocation = -1
    private var peakingLocation = -1
    private var falseColorLocation = -1
    private var zebraThresholdLocation = -1

    private val positions: FloatBuffer = createBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
        )
    )

    private val texCoords: FloatBuffer = createBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    )

    private fun createBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }

    /**
     * 编译并创建 GPU 程序
     */
    fun initialize() {
        check(programId == 0) {
            "着色器程序已经初始化"
        }

        val vertexShader = compileShader(
            GLES30.GL_VERTEX_SHADER,
            LutShader.VERTEX
        )

        val fragmentShader = compileShader(
            GLES30.GL_FRAGMENT_SHADER,
            LutShader.FRAGMENT
        )

        val program = GLES30.glCreateProgram()

        check(program != 0) {
            "创建 OpenGL 程序失败"
        }

        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(
            program,
            GLES30.GL_LINK_STATUS,
            linkStatus,
            0
        )

        val linkLog = GLES30.glGetProgramInfoLog(program)

        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        check(linkStatus[0] == GLES30.GL_TRUE) {
            GLES30.glDeleteProgram(program)
            "着色器链接失败：$linkLog"
        }

        programId = program

        positionLocation = GLES30.glGetAttribLocation(
            programId,
            "aPosition"
        )

        texCoordLocation = GLES30.glGetAttribLocation(
            programId,
            "aTexCoord"
        )

        cameraTextureLocation = GLES30.glGetUniformLocation(
            programId,
            "uCameraTexture"
        )

        lutTextureLocation = GLES30.glGetUniformLocation(
            programId,
            "uLutTexture"
        )

        intensityLocation = GLES30.glGetUniformLocation(
            programId,
            "uIntensity"
        )

        domainMinLocation = GLES30.glGetUniformLocation(
            programId,
            "uDomainMin"
        )

        domainMaxLocation = GLES30.glGetUniformLocation(
            programId,
            "uDomainMax"
        )
        textureMatrixLocation = GLES30.glGetUniformLocation(
            programId,
            "uTextureMatrix"
        )

        texelSizeLocation = GLES30.glGetUniformLocation(
            programId,
            "uTexelSize"
        )

        zebraLocation = GLES30.glGetUniformLocation(
            programId,
            "uZebra"
        )

        peakingLocation = GLES30.glGetUniformLocation(
            programId,
            "uPeaking"
        )

        falseColorLocation = GLES30.glGetUniformLocation(
            programId,
            "uFalseColor"
        )

        zebraThresholdLocation = GLES30.glGetUniformLocation(
            programId,
            "uZebraThreshold"
        )
        check(
            positionLocation >= 0 &&
                    texCoordLocation >= 0 &&
                    cameraTextureLocation >= 0 &&
                    lutTextureLocation >= 0 &&
                    intensityLocation >= 0 &&
                    domainMinLocation >= 0 &&
                    domainMaxLocation >= 0
                    && textureMatrixLocation >= 0) {
            "无法获取着色器变量位置"
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)

        check(shader != 0) {
            "创建着色器失败"
        }

        // GLSL 要求 #version 必须是源码第一行（前面只能有空白）。
        // 原始字符串字面量以换行开头，trimIndent 去掉首行换行和缩进。
        GLES30.glShaderSource(shader, source.trimIndent())
        GLES30.glCompileShader(shader)

        val status = IntArray(1)

        GLES30.glGetShaderiv(
            shader,
            GLES30.GL_COMPILE_STATUS,
            status,
            0
        )

        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("着色器编译失败：$log")
        }

        return shader
    }

    /**
     * 绘制一帧经过 LUT 处理的画面
     */
    fun draw(
        width: Int,
        height: Int,
        cameraTextureId: Int,
        lutTextureId: Int,
        intensity: Float,
        domainMin: FloatArray,
        domainMax: FloatArray,
        textureMatrix: FloatArray,
        overlayFlags: Int = 0,
        zebraThreshold: Float = 0.9f) {
        check(programId != 0) {
            "请先初始化着色器程序"
        }

        require(width > 0 && height > 0)
        require(domainMin.size >= 3 && domainMax.size >= 3)
        require(textureMatrix.size == 16)

        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(
            textureMatrixLocation,
            1,
            false,
            textureMatrix,
            0
        )
        // 绑定相机外部纹理
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            cameraTextureId
        )
        GLES30.glUniform1i(cameraTextureLocation, 0)

        // 绑定 3D LUT 纹理
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_3D,
            lutTextureId
        )
        GLES30.glUniform1i(lutTextureLocation, 1)

        // 设置 LUT 强度
        GLES30.glUniform1f(
            intensityLocation,
            intensity.coerceIn(0f, 1f)
        )

        // 设置 LUT 输入范围
        GLES30.glUniform3f(
            domainMinLocation,
            domainMin[0],
            domainMin[1],
            domainMin[2]
        )

        GLES30.glUniform3f(
            domainMaxLocation,
            domainMax[0],
            domainMax[1],
            domainMax[2]
        )

        // 叠加监看参数（屏幕像素尺寸用于峰值对焦采样）
        GLES30.glUniform2f(
            texelSizeLocation,
            1f / width.toFloat(),
            1f / height.toFloat()
        )
        GLES30.glUniform1f(
            zebraLocation,
            if (overlayFlags and LutShader.OVERLAY_ZEBRA != 0) 1f else 0f
        )
        GLES30.glUniform1f(
            peakingLocation,
            if (overlayFlags and LutShader.OVERLAY_PEAKING != 0) 1f else 0f
        )
        GLES30.glUniform1f(
            falseColorLocation,
            if (overlayFlags and LutShader.OVERLAY_FALSE_COLOR != 0) 1f else 0f
        )
        GLES30.glUniform1f(zebraThresholdLocation, zebraThreshold)

        // 设置顶点坐标
        positions.position(0)
        GLES30.glEnableVertexAttribArray(positionLocation)
        GLES30.glVertexAttribPointer(
            positionLocation,
            2,
            GLES30.GL_FLOAT,
            false,
            0,
            positions
        )

        // 设置纹理坐标
        texCoords.position(0)
        GLES30.glEnableVertexAttribArray(texCoordLocation)
        GLES30.glVertexAttribPointer(
            texCoordLocation,
            2,
            GLES30.GL_FLOAT,
            false,
            0,
            texCoords
        )

        // 绘制全屏矩形
        GLES30.glDrawArrays(
            GLES30.GL_TRIANGLE_STRIP,
            0,
            4
        )

        GLES30.glDisableVertexAttribArray(positionLocation)
        GLES30.glDisableVertexAttribArray(texCoordLocation)

        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_3D,
            0
        )

        GLES30.glBindTexture(
            android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            0
        )

        GLES30.glUseProgram(0)
    }

    /**
     * 释放着色器程序
     */
    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }
}
