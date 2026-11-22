package com.example.lutcamera

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.pow

/**
 * 3D LUT 数据。
 *
 * data 顺序遵循 .cube 约定：红色通道变化最快，其次是绿色，最后是蓝色。
 */
data class CubeLut(
    val title: String,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val data: List<FloatArray>
) {
    companion object {

        const val IDENTITY_TITLE = "Identity"

        /** 允许的最大边长，防止恶意/超大文件把内存打爆。 */
        private const val MAX_SIZE = 65

        private val WHITESPACE = Regex("\\s+")

        // ── 内置预设 ────────────────────────────────────────────────

        fun identity(): CubeLut = linear("Identity") { r, g, b ->
            floatArrayOf(r, g, b)
        }

        fun monochrome(): CubeLut = linear("黑白") { r, g, b ->
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            floatArrayOf(l, l, l)
        }

        fun warm(): CubeLut = linear("暖调") { r, g, b ->
            floatArrayOf(
                (r * 1.10f).coerceAtMost(1f),
                (g * 1.02f).coerceAtMost(1f),
                (b * 0.88f).coerceAtLeast(0f)
            )
        }

        fun cool(): CubeLut = linear("冷调") { r, g, b ->
            floatArrayOf(
                (r * 0.88f).coerceAtLeast(0f),
                (g * 1.00f).coerceAtMost(1f),
                (b * 1.12f).coerceAtMost(1f)
            )
        }

        /** 轻微 S 曲线 + 分离色调的胶片感，非线性，需要较大采样网格。 */
        fun film(): CubeLut = build(17, "胶片") { r, g, b ->
            floatArrayOf(
                curve(r, 0.035f),
                curve(g, 0f),
                curve(b, -0.035f)
            )
        }

        private fun curve(v: Float, lift: Float): Float {
            val s = v * v * (3f - 2f * v)
            return (s + lift * (1f - v)).coerceIn(0f, 1f)
        }

        /** 线性变换用 2³ 网格即可精确还原（三线性插值对线性函数是精确的）。 */
        private fun linear(
            title: String,
            f: (Float, Float, Float) -> FloatArray
        ): CubeLut = build(2, title, f)

        private fun build(
            size: Int,
            title: String,
            f: (Float, Float, Float) -> FloatArray
        ): CubeLut {
            val values = ArrayList<FloatArray>(size * size * size)
            val denom = (size - 1).toFloat()
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        values.add(f(r / denom, g / denom, b / denom))
                    }
                }
            }
            return CubeLut(
                title = title,
                size = size,
                domainMin = floatArrayOf(0f, 0f, 0f),
                domainMax = floatArrayOf(1f, 1f, 1f),
                data = values
            )
        }

        // ── 解析 ────────────────────────────────────────────────────

        /**
         * 完整解析 .cube 文件。必须在 IO 线程调用。
         *
         * @throws IllegalArgumentException 文件格式不受支持
         */
        fun load(input: InputStream): CubeLut {
            var title = "未命名"
            var size = 0
            val domainMin = floatArrayOf(0f, 0f, 0f)
            val domainMax = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<FloatArray>()

            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { originalLine ->
                    val line = originalLine.substringBefore('#').trim()
                    if (line.isEmpty()) return@forEachLine

                    val parts = line.split(WHITESPACE)
                    when (parts[0].uppercase(Locale.US)) {
                        "TITLE" -> title = line.substring(5).trim().trim('"')
                            .ifEmpty { "未命名" }

                        "LUT_3D_SIZE" -> {
                            size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            require(size in 2..MAX_SIZE) {
                                "LUT 尺寸 $size 不受支持（支持 2–$MAX_SIZE）"
                            }
                        }

                        "LUT_1D_SIZE" -> throw IllegalArgumentException(
                            "暂不支持 1D LUT（LUT_1D_SIZE），请导出为 3D LUT"
                        )

                        "DOMAIN_MIN" -> readVec3(parts, domainMin, "DOMAIN_MIN")

                        "DOMAIN_MAX" -> readVec3(parts, domainMax, "DOMAIN_MAX")

                        "LUT_3D_INPUT_RANGE" -> {
                            val lo = parts.getOrNull(1)?.toFloatOrNull()
                            val hi = parts.getOrNull(2)?.toFloatOrNull()
                            if (lo != null && hi != null) {
                                domainMin.fill(lo)
                                domainMax.fill(hi)
                            }
                        }

                        else -> {
                            val r = parts.getOrNull(0)?.toFloatOrNull()
                            val g = parts.getOrNull(1)?.toFloatOrNull()
                            val b = parts.getOrNull(2)?.toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values.add(floatArrayOf(r, g, b))
                            }
                        }
                    }
                }
            }

            require(size > 0) { "文件中没有找到 LUT_3D_SIZE" }
            for (i in 0..2) {
                require(domainMax[i] > domainMin[i]) { "LUT 输入范围错误" }
            }

            val expected = size * size * size
            require(values.size == expected) {
                "LUT 数据数量不正确：需要 $expected 条，实际读取到 ${values.size} 条"
            }

            return CubeLut(title, size, domainMin, domainMax, values)
        }

        /**
         * 只读取头部元信息，用于 LUT 列表展示，避免完整解析大文件。
         */
        fun peek(input: InputStream): Pair<String, Int> {
            var title = "未命名"
            var size = 0

            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                var count = 0
                while (line != null && count < 128) {
                    val text = line.substringBefore('#').trim()
                    if (text.isNotEmpty()) {
                        val parts = text.split(WHITESPACE)
                        when (parts[0].uppercase(Locale.US)) {
                            "TITLE" -> title = text.substring(5).trim().trim('"')
                                .ifEmpty { "未命名" }

                            "LUT_3D_SIZE" ->
                                size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        }
                    }
                    count++
                    line = reader.readLine()
                }
            }
            return title to size
        }

        private fun readVec3(parts: List<String>, out: FloatArray, name: String) {
            val a = parts.getOrNull(1)?.toFloatOrNull()
            val b = parts.getOrNull(2)?.toFloatOrNull()
            val c = parts.getOrNull(3)?.toFloatOrNull()
            require(a != null && b != null && c != null) { "$name 格式错误" }
            out[0] = a
            out[1] = b
            out[2] = c
        }
    }
}

/** 色温（Kelvin）→ 近似黑体 RGB，供白平衡增益计算使用。 */
internal fun kelvinToRgb(kelvin: Int): FloatArray {
    val temp = kelvin.coerceIn(1000, 40000) / 100f

    val r: Float
    val g: Float
    val b: Float

    if (temp <= 66f) {
        r = 255f
        g = (99.4708025861 * kotlin.math.ln(temp.toDouble()) - 161.1195681661)
            .toFloat()
        b = if (temp <= 19f) {
            0f
        } else {
            (138.5177312231 * kotlin.math.ln((temp - 10).toDouble()) - 305.0447927307)
                .toFloat()
        }
    } else {
        r = (329.698727446 * (temp - 60).toDouble().pow(-0.1332047592)).toFloat()
        g = (288.1221695283 * (temp - 60).toDouble().pow(-0.0755148492)).toFloat()
        b = 255f
    }

    return floatArrayOf(
        (r / 255f).coerceIn(0.01f, 1f),
        (g / 255f).coerceIn(0.01f, 1f),
        (b / 255f).coerceIn(0.01f, 1f)
    )
}
