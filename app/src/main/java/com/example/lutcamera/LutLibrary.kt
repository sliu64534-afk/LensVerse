package com.example.lutcamera

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** LUT 列表项。builtIn 表示内置预设，不可删除。 */
data class LutEntry(
    val id: String,
    val name: String,
    val size: Int,
    val builtIn: Boolean
)

/**
 * LUT 资源库：内置预设 + 用户导入的 .cube 文件。
 *
 * 导入的文件会复制到应用私有目录，卸载后一并清除，无需存储权限。
 * 所有方法都会做文件 IO，必须在 IO 线程调用。
 */
class LutLibrary(private val context: Context) {

    private val directory: File by lazy {
        File(context.filesDir, "luts").apply { mkdirs() }
    }

    private val builtInFactories = linkedMapOf<String, () -> CubeLut>(
        "builtin:identity" to { CubeLut.identity() },
        "builtin:rec709" to { CubeLut.rec709() },
        "builtin:mono" to { CubeLut.monochrome() },
        "builtin:warm" to { CubeLut.warm() },
        "builtin:cool" to { CubeLut.cool() },
        "builtin:film" to { CubeLut.film() },
        "builtin:cinema_teal" to { CubeLut.cinemaTeal() },
        "builtin:day_night" to { CubeLut.dayNight() },
        "builtin:dusty" to { CubeLut.dusty() },
        "builtin:nature" to { CubeLut.nature() },
        "builtin:nostalgic" to { CubeLut.nostalgic() },
        "builtin:paloma" to { CubeLut.paloma() },
        "builtin:rift" to { CubeLut.rift() },
        "builtin:vivid" to { CubeLut.vivid() },
        "builtin:warm_fade" to { CubeLut.warmFade() }
    )

    private val builtInCache = HashMap<String, CubeLut>()

    private fun builtIn(id: String): CubeLut =
        builtInCache.getOrPut(id) {
            builtInFactories.getValue(id).invoke()
        }

    fun entries(): List<LutEntry> {
        val result = ArrayList<LutEntry>()

        builtInFactories.keys.forEach { id ->
            val lut = builtIn(id)
            result.add(LutEntry(id, lut.title, lut.size, builtIn = true))
        }

        directory.listFiles { file ->
            file.isFile && file.name.endsWith(".cube", ignoreCase = true)
        }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { file ->
                val (title, size) = runCatching {
                    file.inputStream().use { CubeLut.peek(it) }
                }.getOrElse { file.name to 0 }

                result.add(
                    LutEntry(
                        id = file.name,
                        name = title.ifBlank { file.name },
                        size = size,
                        builtIn = false
                    )
                )
            }

        return result
    }

    fun load(id: String): CubeLut {
        if (builtInFactories.containsKey(id)) return builtIn(id)

        val file = File(directory, id)
        require(file.isFile) { "LUT 文件不存在" }
        return file.inputStream().use { CubeLut.load(it) }
    }

    fun import(uri: Uri): LutEntry {
        val displayName = queryDisplayName(uri)
        require(displayName.endsWith(".cube", ignoreCase = true)) {
            "请选择 .cube 格式的 LUT 文件"
        }

        // 先完整解析校验，坏文件不落盘
        val lut = context.contentResolver.openInputStream(uri)?.use {
            CubeLut.load(it)
        } ?: throw IllegalArgumentException("无法读取 LUT 文件")

        val target = uniqueFile(sanitize(displayName))

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法写入 LUT 文件")

        return LutEntry(
            id = target.name,
            name = lut.title.ifBlank { displayName },
            size = lut.size,
            builtIn = false
        )
    }

    fun delete(id: String) {
        if (builtInFactories.containsKey(id)) return
        File(directory, id).delete()
    }

    private fun queryDisplayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        cursor.getString(index)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment ?: "lut.cube"
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.endsWith(".cube", ignoreCase = true)) cleaned else "$cleaned.cube"
    }

    private fun uniqueFile(name: String): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate

        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "cube")
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "${base}_$index.$extension")
            index++
        }
        return candidate
    }
}
