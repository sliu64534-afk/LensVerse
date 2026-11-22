package com.example.lutcamera.ui

import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lutcamera.CameraMode
import com.example.lutcamera.CameraStops
import com.example.lutcamera.CameraUiState
import com.example.lutcamera.CameraViewModel
import com.example.lutcamera.LutEntry
import com.example.lutcamera.VideoQuality
import com.example.lutcamera.ui.theme.CamAccent
import com.example.lutcamera.ui.theme.CamRecord
import com.example.lutcamera.ui.theme.CamSurfaceHigh
import com.example.lutcamera.ui.theme.CamTextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

enum class CameraPanel { LUT, WB, ISO, SHUTTER, FOCUS, ZOOM, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPanelSheet(
    panel: CameraPanel,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit,
    onImportLut: () -> Unit
) {
    val state = viewModel.state

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CamSurfaceHigh,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            when (panel) {
                CameraPanel.LUT -> LutPanel(state, viewModel, onImportLut)
                CameraPanel.WB -> WhiteBalancePanel(state, viewModel)
                CameraPanel.ISO -> IsoPanel(state, viewModel)
                CameraPanel.SHUTTER -> ShutterPanel(state, viewModel)
                CameraPanel.FOCUS -> FocusPanel(state, viewModel)
                CameraPanel.ZOOM -> ZoomPanel(state, viewModel)
                CameraPanel.SETTINGS -> SettingsPanel(state, viewModel)
            }
        }
    }
}

// ── LUT ─────────────────────────────────────────────────────────────

@Composable
private fun LutPanel(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onImport: () -> Unit
) {
    PanelHeader("LUT", "实时 3D 调色查找表")

    ToggleRow(
        title = "启用 LUT",
        checked = state.lutEnabled,
        onCheckedChange = { viewModel.toggleLut() }
    )

    Slider(
        value = state.lutIntensity,
        onValueChange = viewModel::setIntensity,
        valueRange = 0f..1f
    )
    Text(
        text = "强度 ${(state.lutIntensity * 100).roundToInt()}%",
        fontSize = 12.sp,
        color = CamTextSecondary
    )

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = Color.White.copy(alpha = 0.08f)
    )

    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
        items(state.entries, key = { it.id }) { entry ->
            LutRow(
                entry = entry,
                selected = entry.id == state.selectedLutId,
                onSelect = { viewModel.selectLut(entry.id) },
                onDelete = { viewModel.deleteLut(entry) }
            )
        }
    }

    Button(
        onClick = onImport,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Text("导入 .cube 文件")
    }
}

@Composable
private fun LutRow(
    entry: LutEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) CamAccent.copy(alpha = 0.16f) else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 14.sp,
                color = if (selected) CamAccent else Color.White,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = if (entry.builtIn) "内置预设" else "已导入 · ${entry.size}³",
                fontSize = 10.sp,
                color = CamTextSecondary
            )
        }

        if (!entry.builtIn) {
            Text(
                text = "删除",
                fontSize = 11.sp,
                color = CamRecord,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (selected) {
            Text(text = "✓", fontSize = 15.sp, color = CamAccent)
        }
    }
}

// ── 白平衡 ──────────────────────────────────────────────────────────

@Composable
private fun WhiteBalancePanel(state: CameraUiState, viewModel: CameraViewModel) {
    val settings = state.settings

    PanelHeader("白平衡", if (settings.manualWhiteBalance) "手动" else "自动")

    ToggleRow(
        title = "手动白平衡",
        checked = settings.manualWhiteBalance,
        onCheckedChange = { value ->
            viewModel.updateSettings { it.copy(manualWhiteBalance = value) }
        }
    )

    ValueLabel("色温", "${settings.kelvin}K")
    Slider(
        value = settings.kelvin.toFloat(),
        onValueChange = { value ->
            viewModel.updateSettings { it.copy(kelvin = value.roundToInt()) }
        },
        valueRange = 2000f..10000f,
        steps = 79,
        enabled = settings.manualWhiteBalance
    )

    ValueLabel("色调", if (settings.tint >= 0f) "+${(settings.tint * 100).roundToInt()}" else "${(settings.tint * 100).roundToInt()}")
    Slider(
        value = settings.tint,
        onValueChange = { value ->
            viewModel.updateSettings { it.copy(tint = value) }
        },
        valueRange = -1f..1f,
        enabled = settings.manualWhiteBalance
    )
}

// ── ISO ─────────────────────────────────────────────────────────────

@Composable
private fun IsoPanel(state: CameraUiState, viewModel: CameraViewModel) {
    val settings = state.settings
    val stops = CameraStops.iso
    val index = nearestIndex(stops.map { it.toFloat() }.toFloatArray(), settings.iso.toFloat())

    PanelHeader("ISO", if (settings.manualExposure) "ISO ${settings.iso}" else "自动")

    ToggleRow(
        title = "手动曝光",
        checked = settings.manualExposure,
        onCheckedChange = { value ->
            viewModel.updateSettings { it.copy(manualExposure = value) }
        }
    )

    Slider(
        value = index.toFloat(),
        onValueChange = { value ->
            val next = stops[value.roundToInt().coerceIn(0, stops.lastIndex)]
            viewModel.updateSettings { it.copy(iso = next) }
        },
        valueRange = 0f..(stops.size - 1).toFloat(),
        steps = stops.size - 2,
        enabled = settings.manualExposure
    )

    Text(
        text = "ISO ${settings.iso}",
        fontSize = 20.sp,
        color = CamAccent,
        fontWeight = FontWeight.Bold
    )
}

// ── 快门 ────────────────────────────────────────────────────────────

@Composable
private fun ShutterPanel(state: CameraUiState, viewModel: CameraViewModel) {
    val settings = state.settings
    val stops = CameraStops.shutterDenominators
    val index = nearestIndex(stops.map { it.toFloat() }.toFloatArray(), settings.shutterDenominator.toFloat())

    PanelHeader("快门", settings.shutterLabel)

    ToggleRow(
        title = "手动曝光",
        checked = settings.manualExposure,
        onCheckedChange = { value ->
            viewModel.updateSettings { it.copy(manualExposure = value) }
        }
    )

    Slider(
        value = index.toFloat(),
        onValueChange = { value ->
            val next = stops[value.roundToInt().coerceIn(0, stops.lastIndex)]
            viewModel.updateSettings { it.copy(shutterDenominator = next) }
        },
        valueRange = 0f..(stops.size - 1).toFloat(),
        steps = stops.size - 2,
        enabled = settings.manualExposure
    )

    Text(
        text = settings.shutterLabel + " s",
        fontSize = 20.sp,
        color = CamAccent,
        fontWeight = FontWeight.Bold
    )
}

// ── 对焦 ────────────────────────────────────────────────────────────

@Composable
private fun FocusPanel(state: CameraUiState, viewModel: CameraViewModel) {
    val settings = state.settings

    PanelHeader("对焦", focusLabel(settings.focusDiopters))

    ToggleRow(
        title = "手动对焦",
        checked = settings.manualFocus,
        onCheckedChange = { value ->
            viewModel.updateSettings { it.copy(manualFocus = value) }
        }
    )

    Slider(
        value = settings.focusDiopters,
        onValueChange = { value ->
            viewModel.updateSettings { it.copy(focusDiopters = value) }
        },
        valueRange = 0f..10f,
        enabled = settings.manualFocus
    )

    Text(
        text = focusLabel(settings.focusDiopters),
        fontSize = 20.sp,
        color = CamAccent,
        fontWeight = FontWeight.Bold
    )
}

private fun focusLabel(diopters: Float): String =
    if (diopters < 0.05f) "∞" else "%.2f m".format(1f / diopters)

// ── 变焦 ────────────────────────────────────────────────────────────

@Composable
private fun ZoomPanel(state: CameraUiState, viewModel: CameraViewModel) {
    PanelHeader("变焦", "%.1f×".format(state.settings.zoom))

    Slider(
        value = state.settings.zoom,
        onValueChange = { value ->
            viewModel.updateSettings { it.copy(zoom = value) }
        },
        valueRange = 1f..state.maxZoom.coerceAtLeast(1f)
    )
}

// ── 设置 ────────────────────────────────────────────────────────────

@Composable
private fun SettingsPanel(state: CameraUiState, viewModel: CameraViewModel) {
    PanelHeader("设置", "监看与录制")

    SectionLabel("监看叠加（不写入成片）")

    ToggleRow("构图网格", state.overlays.grid) { value ->
        viewModel.updateOverlays { it.copy(grid = value) }
    }
    ToggleRow("直方图", state.overlays.histogram) { value ->
        viewModel.updateOverlays { it.copy(histogram = value) }
    }
    ToggleRow("斑马纹", state.overlays.zebra) { value ->
        viewModel.updateOverlays { it.copy(zebra = value) }
    }
    ToggleRow("峰值对焦", state.overlays.peaking) { value ->
        viewModel.updateOverlays { it.copy(peaking = value) }
    }
    ToggleRow("伪色曝光", state.overlays.falseColor) { value ->
        viewModel.updateOverlays { it.copy(falseColor = value) }
    }

    ValueLabel("斑马纹阈值", "${(state.overlays.zebraThreshold * 100).roundToInt()}%")
    Slider(
        value = state.overlays.zebraThreshold,
        onValueChange = { value ->
            viewModel.updateOverlays { it.copy(zebraThreshold = value) }
        },
        valueRange = 0.7f..1f,
        enabled = state.overlays.zebra
    )

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = Color.White.copy(alpha = 0.08f)
    )

    SectionLabel("录制")

    ToggleRow("录制声音", state.audioEnabled) { viewModel.setAudioEnabled(it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VideoQuality.entries.forEach { quality ->
            FilterChip(
                selected = state.quality == quality,
                onClick = { viewModel.setQuality(quality) },
                label = { Text(quality.label) }
            )
        }
    }

    SectionLabel("镜头")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = state.lensFacing == CameraSelector.LENS_FACING_BACK,
            onClick = { viewModel.setLensFacing(CameraSelector.LENS_FACING_BACK) },
            label = { Text("后置") }
        )
        FilterChip(
            selected = state.lensFacing == CameraSelector.LENS_FACING_FRONT,
            onClick = { viewModel.setLensFacing(CameraSelector.LENS_FACING_FRONT) },
            label = { Text("前置") }
        )
        FilterChip(
            selected = state.mode == CameraMode.VIDEO,
            onClick = { viewModel.setMode(CameraMode.VIDEO) },
            label = { Text("视频") }
        )
        FilterChip(
            selected = state.mode == CameraMode.PHOTO,
            onClick = { viewModel.setMode(CameraMode.PHOTO) },
            label = { Text("照片") }
        )
    }

    ToggleRow(
        title = "手电筒",
        checked = state.settings.torch,
        onCheckedChange = { value ->
            viewModel.updateSettings { it.copy(torch = value) }
        }
    )
}

// ── 通用小组件 ──────────────────────────────────────────────────────

@Composable
private fun PanelHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "  $subtitle",
            fontSize = 12.sp,
            color = CamTextSecondary,
            modifier = Modifier.padding(bottom = 3.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = CamTextSecondary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun ValueLabel(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = CamTextSecondary)
        Text(text = value, fontSize = 12.sp, color = Color.White)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun nearestIndex(values: FloatArray, target: Float): Int {
    var best = 0
    var bestDistance = Float.MAX_VALUE
    values.forEachIndexed { index, value ->
        val distance = abs(value - target)
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}
