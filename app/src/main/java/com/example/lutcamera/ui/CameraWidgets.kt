package com.example.lutcamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ── 控制条 ──────────────────────────────────────────────────────────

@Composable
fun ControlChip(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) CamAccent.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.08f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = CamTextSecondary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 12.sp,
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
            .size(74.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = if (enabled) 0.95f else 0.3f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (recording) 26.dp else 56.dp)
                .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                .background(if (enabled) CamRecord else CamRecord.copy(alpha = 0.3f))
        )
    }
}

@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = if (enabled) 0.95f else 0.3f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
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
