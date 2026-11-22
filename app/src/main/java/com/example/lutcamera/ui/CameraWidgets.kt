package com.example.lutcamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lutcamera.LutEntry
import com.example.lutcamera.ui.theme.CamAccent
import com.example.lutcamera.ui.theme.CamRecord
import com.example.lutcamera.ui.theme.CamTextSecondary
import java.util.Locale

// ── 构图网格 ────────────────────────────────────────────────────────

@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.28f)

        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }

        // 中心十字
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = 10.dp.toPx()
        drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke)
        drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke)
    }
}

// ── 直方图 ──────────────────────────────────────────────────────────

@Composable
fun HistogramOverlay(bins: IntArray?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color.Black.copy(alpha = 0.45f))

        if (bins == null || bins.size < 768) return@Canvas

        var max = 1
        for (i in 0 until 768) {
            if (bins[i] > max) max = bins[i]
        }

        val channelColors = intArrayOf(
            0xFFFF4D4D.toInt(),
            0xFF4DFF88.toInt(),
            0xFF4DA6FF.toInt()
        )

        val stroke = 1.2.dp.toPx()

        for (channel in 0 until 3) {
            val path = Path()
            for (i in 0 until 256) {
                val value = bins[channel * 256 + i].toFloat() / max
                val x = size.width * i / 255f
                val y = size.height * (1f - value.coerceIn(0f, 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Color(channelColors[channel]).copy(alpha = 0.85f),
                style = Stroke(width = stroke),
                blendMode = BlendMode.Plus
            )
        }
    }
}

// ── 3D LUT 状态提示 ─────────────────────────────────────────────────

/**
 * 取景画面右上角的 3D LUT 状态图标。
 *
 * 白色立方体：LUT 仅用于监看，成片是原始 Log 画面。
 * 红色立方体：LUT 已烧录进片段。
 */
@Composable
fun LutStatusBadge(burned: Boolean, modifier: Modifier = Modifier) {
    val tint = if (burned) CamRecord else Color.White

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = tint.copy(alpha = 0.65f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.4.dp.toPx()
            val path = Path().apply {
                moveTo(w * 0.5f, 0f)
                lineTo(w, h * 0.26f)
                lineTo(w, h * 0.74f)
                lineTo(w * 0.5f, h)
                lineTo(0f, h * 0.74f)
                lineTo(0f, h * 0.26f)
                close()
                moveTo(0f, h * 0.26f)
                lineTo(w * 0.5f, h * 0.52f)
                lineTo(w, h * 0.26f)
                moveTo(w * 0.5f, h * 0.52f)
                lineTo(w * 0.5f, h)
            }
            drawPath(path, tint, style = Stroke(width = stroke))
        }
        Text(
            text = "3D LUT",
            fontSize = 9.sp,
            color = tint,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── 横向悬浮 LUT 选择条 ─────────────────────────────────────────────

/**
 * 横向悬浮的 LUT 选择胶片条。
 *
 * 每个预设显示名称缩略图，点击即可切换，选中的项高亮显示。
 * 参照相机 App 中常见的滤镜横向选择器。
 */
@Composable
fun LutFilmstrip(
    entries: List<LutEntry>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var dragAccumulated by androidx.compose.runtime.remember { mutableStateOf(0f) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .horizontalScroll(scrollState)
            .pointerInput(selectedId) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccumulated = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumulated += dragAmount
                        val threshold = 48.dp.toPx()
                        if (dragAccumulated >= threshold) {
                            val index = entries.indexOfFirst { it.id == selectedId }
                            entries.getOrNull(index + 1)?.let { onSelect(it.id) }
                            dragAccumulated = 0f
                        } else if (dragAccumulated <= -threshold) {
                            val index = entries.indexOfFirst { it.id == selectedId }
                            entries.getOrNull(index - 1)?.let { onSelect(it.id) }
                            dragAccumulated = 0f
                        }
                    }
                )
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        entries.forEach { entry ->
            val selected = entry.id == selectedId
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) CamAccent.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.06f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) CamAccent else Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(entry.id) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 缩略图色块：用一组代表色暗示 LUT 风格
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(lutSwatchColor(entry.name))
                )
                Text(
                    text = entry.name,
                    fontSize = 8.sp,
                    color = if (selected) CamAccent else Color.White,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** 根据 LUT 名称返回代表性色块颜色，用于胶片条缩略图。 */
private fun lutSwatchColor(name: String): Color = when {
    name.contains("mono", true) || name.contains("黑白", true) ||
            name.contains("monochrome", true) -> Color(0xFF8E8E93)
    name.contains("warm", true) || name.contains("暖", true) -> Color(0xFFD9885F)
    name.contains("cool", true) || name.contains("冷", true) ||
            name.contains("teal", true) -> Color(0xFF4FA6B8)
    name.contains("film", true) || name.contains("胶片", true) -> Color(0xFFB8A06A)
    name.contains("night", true) || name.contains("夜", true) -> Color(0xFF3B4A6B)
    name.contains("vivid", true) || name.contains("鲜艳", true) -> Color(0xFFC0568A)
    else -> Color(0xFF6E8B74)
}

// ── LUT 开关快捷键 ──────────────────────────────────────────────────

/**
 * 横向控制栏中的 LUT 开关快捷键。
 *
 * 点击直接切换 LUT 启用状态，无需进入 LUT 面板。
 */
@Composable
fun LutToggleButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) CamAccent
                else Color.White.copy(alpha = 0.1f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (enabled) "ON" else "OFF",
                fontSize = 10.sp,
                color = if (enabled) Color.Black else Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "LUT",
                fontSize = 7.sp,
                color = if (enabled) Color.Black.copy(alpha = 0.75f) else CamTextSecondary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── 控制条 ──────────────────────────────────────────────────────────

@Composable
fun ControlChip(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) CamAccent.copy(alpha = 0.2f)
                else Color.White.copy(alpha = 0.06f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            color = CamTextSecondary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = if (active) CamAccent else Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── 录制 / 快门按钮 ─────────────────────────────────────────────────

@Composable
fun RecordButton(
    recording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White.copy(alpha = if (enabled) 0.95f else 0.3f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (recording) 22.dp else 48.dp)
                .clip(if (recording) RoundedCornerShape(5.dp) else CircleShape)
                .background(if (enabled) CamRecord else CamRecord.copy(alpha = 0.3f))
        )
    }
}

@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White.copy(alpha = if (enabled) 0.95f else 0.3f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled) 1f else 0.4f))
        )
    }
}

// ── 顶部状态栏 ──────────────────────────────────────────────────────

@Composable
fun StatusBar(
    qualityLabel: String,
    lutName: String,
    recording: Boolean,
    elapsedMillis: Long,
    batteryPercent: Int,
    freeBytes: Long,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusItem(qualityLabel)

        Text(
            text = lutName,
            fontSize = 11.sp,
            color = CamAccent,
            fontWeight = FontWeight.SemiBold
        )

        StatusItem(formatBytes(freeBytes))

        StatusItem(if (batteryPercent >= 0) "$batteryPercent%" else "--%")

        if (recording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CamRecord)
                )
                Text(
                    text = formatDuration(elapsedMillis),
                    fontSize = 12.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatusItem(text: String) {
    Text(text = text, fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
}

// ── 格式化 ──────────────────────────────────────────────────────────

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "--"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.1fG", gb)
    } else {
        val mb = bytes / (1024.0 * 1024.0)
        String.format(Locale.US, "%.0fM", mb)
    }
}
