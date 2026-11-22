package com.example.lutcamera.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lutcamera.CameraController
import com.example.lutcamera.CameraMode
import com.example.lutcamera.CameraViewModel
import com.example.lutcamera.LutSurfaceProcessor
import com.example.lutcamera.ui.theme.CamAccent
import com.example.lutcamera.ui.theme.CamTextSecondary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CameraScreen(
    lifecycleOwner: LifecycleOwner,
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val state = viewModel.state

    val processor = remember { LutSurfaceProcessor() }

    val controller = remember(processor) {
        CameraController(context.applicationContext, lifecycleOwner, processor).apply {
            onReady = { maxZoom -> viewModel.setMaxZoom(maxZoom) }
            onVideoEvent = { event -> viewModel.onVideoEvent(event) }
            onPhotoSaved = { uri -> viewModel.onPhotoSaved(uri) }
            onError = { message -> viewModel.onError(message) }
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView 版本对自定义 SurfaceProcessor / 多输出管线的兼容性更好，
            // SurfaceView 版本在部分机型上会出现「绑定成功但黑屏」。
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var panel by remember { mutableStateOf<CameraPanel?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        cameraGranted = hasCameraPermission(context)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 直接复制进私有目录，不需要持久化 URI 权限
        if (uri != null) viewModel.importLut(uri)
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(requiredPermissions())
    }

    DisposableEffect(controller, processor) {
        onDispose {
            controller.release()
            processor.close()
        }
    }

    // 绑定 / 重新绑定
    LaunchedEffect(
        state.lensFacing,
        state.quality,
        state.recordLutToClip,
        state.frameRate,
        cameraGranted
    ) {
        if (cameraGranted) {
            controller.bind(
                previewView,
                state.lensFacing,
                state.quality,
                state.recordLutToClip,
                state.frameRate
            )
        }
    }

    // 参数与 LUT 同步
    LaunchedEffect(state.settings) {
        controller.applyManualSettings(state.settings)
    }
    LaunchedEffect(state.settings.zoom) {
        controller.setZoom(state.settings.zoom)
    }
    LaunchedEffect(state.settings.torch) {
        controller.setTorch(state.settings.torch)
    }
    LaunchedEffect(state.activeLut) {
        state.activeLut?.let { processor.setLut(it) }
    }
    LaunchedEffect(state.lutIntensity, state.lutEnabled) {
        processor.setIntensity(if (state.lutEnabled) state.lutIntensity else 0f)
    }
    LaunchedEffect(state.overlays, state.recording, state.capturing) {
        val flags = if (state.recording || state.capturing) 0 else state.overlays.shaderFlags
        processor.setOverlayFlags(flags, state.overlays.zebraThreshold)
    }
    LaunchedEffect(state.overlays.histogram) {
        processor.histogramListener = if (state.overlays.histogram) {
            { bins -> viewModel.updateHistogram(bins) }
        } else {
            null
        }
    }

    // 提示信息自动消失
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2600)
            viewModel.clearMessage()
        }
    }

    // 拍照兜底复位，避免回调丢失导致快门一直禁用
    LaunchedEffect(state.capturing) {
        if (state.capturing) {
            delay(5000)
            viewModel.onCaptureFinished()
        }
    }

    val battery = rememberBatteryPercent()
    val freeBytes = rememberFreeBytes()

    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.recording) {
        while (state.recording) {
            clock = System.currentTimeMillis()
            delay(250)
        }
    }
    val elapsed = if (state.recording) {
        (clock - state.recordingStartedAt).coerceAtLeast(0L)
    } else {
        0L
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (cameraGranted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // 点击对焦
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            focusPoint = offset
                            controller.focusAt(previewView, offset.x, offset.y)
                        }
                    }
            )

            if (state.overlays.grid) {
                GridOverlay(Modifier.fillMaxSize())
            }

            // 左下角直方图（Blackmagic 布局）
            if (state.overlays.histogram) {
                HistogramOverlay(
                    bins = viewModel.histogram,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 12.dp, bottom = 108.dp)
                        .size(112.dp, 60.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }

            // 右上角 3D LUT 状态提示：白色仅监看，红色已烧录到片段
            if (state.lutHintVisible && state.lutEnabled && state.activeLut != null) {
                LutStatusBadge(
                    burned = state.recordLutToClip,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 52.dp, end = 12.dp)
                )
            }

            FocusRing(focusPoint) { focusPoint = null }
        } else {
            PermissionPrompt {
                permissionLauncher.launch(requiredPermissions())
            }
        }

        // 顶部状态
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            StatusBar(
                qualityLabel = state.quality.label,
                lutName = if (state.lutEnabled) state.lutName else "LUT 关闭",
                recording = state.recording,
                elapsedMillis = elapsed,
                batteryPercent = battery,
                freeBytes = freeBytes
            )
        }

        // 提示
        if (state.message != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = state.message.orEmpty(),
                    fontSize = 12.sp,
                    color = CamAccent
                )
            }
        }

        // 底部控制区：胶片条 + 横向参数栏 + 录制按钮
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 悬浮滤镜窗与滤镜开启键同一行，展开时带过渡动画
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ControlChip(
                    label = "滤镜",
                    value = if (state.lutStripVisible) "收起" else "展开",
                    active = state.lutStripVisible,
                    onClick = { viewModel.toggleLutStrip() },
                    modifier = Modifier.width(58.dp)
                )

                AnimatedVisibility(
                    visible = state.lutStripVisible,
                    enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.CenterVertically),
                    exit = fadeOut(tween(180)) + shrinkVertically(shrinkTowards = Alignment.CenterVertically),
                    modifier = Modifier.weight(1f)
                ) {
                    LutFilmstrip(
                        entries = state.entries,
                        selectedId = state.selectedLutId,
                        onSelect = { viewModel.selectLut(it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 横向功能控制栏（按键填满整行）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                LutToggleButton(
                    enabled = state.lutEnabled,
                    onClick = { viewModel.toggleLut() },
                    modifier = Modifier.weight(1f)
                )
                ControlChip(
                    label = "LUT",
                    value = if (state.lutEnabled) state.lutName else "关闭",
                    active = state.lutEnabled,
                    onClick = { panel = CameraPanel.LUT },
                    modifier = Modifier.weight(1f)
                )
                ControlChip(
                    label = "ISO",
                    value = if (state.settings.manualExposure) "${state.settings.iso}" else "自动",
                    active = state.settings.manualExposure,
                    onClick = { panel = CameraPanel.ISO },
                    modifier = Modifier.weight(1f)
                )
                ControlChip(
                    label = "快门",
                    value = if (state.settings.manualExposure) state.settings.shutterLabel else "自动",
                    active = state.settings.manualExposure,
                    onClick = { panel = CameraPanel.SHUTTER },
                    modifier = Modifier.weight(1f)
                )
                ControlChip(
                    label = "WB",
                    value = if (state.settings.manualWhiteBalance) "${state.settings.kelvin}K" else "自动",
                    active = state.settings.manualWhiteBalance,
                    onClick = { panel = CameraPanel.WB },
                    modifier = Modifier.weight(1f)
                )
                ControlChip(
                    label = "对焦",
                    value = if (state.settings.manualFocus) "手动" else "自动",
                    active = state.settings.manualFocus,
                    onClick = { panel = CameraPanel.FOCUS },
                    modifier = Modifier.weight(1f)
                )
            }

            // 第二行：帧率、变焦、监看、设置（填满整行）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                ControlChip(
                    label = "帧率",
                    value = "${state.frameRate.value}fps",
                    active = true,
                    onClick = { panel = CameraPanel.FRAME_RATE },
                    modifier = Modifier.weight(1f)
                )
                ControlChip(
                    label = "变焦",
                    value = "%.1f×".format(state.settings.zoom),
                    active = state.settings.zoom > 1.01f,
                    onClick = { panel = CameraPanel.ZOOM },
                    modifier = Modifier.weight(1f)
                )
                ControlChip(
                    label = "监看",
                    value = if (state.overlays.grid || state.overlays.zebra ||
                        state.overlays.peaking || state.overlays.falseColor
                    ) "开启" else "关闭",
                    active = state.overlays.grid || state.overlays.zebra ||
                            state.overlays.peaking || state.overlays.falseColor,
                    onClick = { panel = CameraPanel.SETTINGS },
                    modifier = Modifier.weight(1f)
                )
                ControlChip(
                    label = "设置",
                    value = state.quality.label,
                    active = false,
                    onClick = { panel = CameraPanel.SETTINGS },
                    modifier = Modifier.weight(1f)
                )
            }

            // 录制控制行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ModeSwitch(state.mode) { viewModel.setMode(it) }

                if (state.mode == CameraMode.VIDEO) {
                    RecordButton(
                        recording = state.recording,
                        enabled = state.ready || state.recording,
                        onClick = {
                            if (state.recording) {
                                controller.stopRecording()
                            } else {
                                // 立即关闭监看叠加，避免写进前几帧
                                processor.setOverlayFlags(0, state.overlays.zebraThreshold)
                                controller.startRecording(
                                    state.audioEnabled,
                                    state.colorSpaceTag
                                )
                            }
                        }
                    )
                } else {
                    ShutterButton(enabled = !state.capturing) {
                        processor.setOverlayFlags(0, state.overlays.zebraThreshold)
                        viewModel.onCaptureStarted()
                        controller.takePhoto()
                    }
                }

                // 右侧占位，保持录制按钮水平居中
                Spacer(Modifier.width(52.dp))
            }
        }
    }

    panel?.let { current ->
        CameraPanelSheet(
            panel = current,
            viewModel = viewModel,
            onDismiss = { panel = null },
            onImportLut = { importLauncher.launch(arrayOf("*/*")) }
        )
    }
}

// ── 辅助组件 ────────────────────────────────────────────────────────

@Composable
private fun ModeSwitch(mode: CameraMode, onModeChange: (CameraMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ModeText("视频", mode == CameraMode.VIDEO) { onModeChange(CameraMode.VIDEO) }
        ModeText("照片", mode == CameraMode.PHOTO) { onModeChange(CameraMode.PHOTO) }
    }
}

@Composable
private fun ModeText(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = if (selected) CamAccent else CamTextSecondary,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) CamAccent.copy(alpha = 0.16f) else Color.Transparent
            )
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
    )
}

@Composable
private fun FocusRing(point: Offset?, onFinished: () -> Unit) {
    if (point == null) return

    val alpha = remember { Animatable(1f) }

    LaunchedEffect(point) {
        alpha.snapTo(1f)
        alpha.animateTo(0f, tween(durationMillis = 900))
        onFinished()
    }

    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            color = Color.White.copy(alpha = alpha.value * 0.9f),
            radius = 34.dp.toPx(),
            center = point,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "需要相机权限才能显示画面",
                color = Color.White,
                fontSize = 14.sp
            )
            Button(onClick = onRequest) {
                Text("授予相机权限")
            }
        }
    }
}

// ── 系统信息 ────────────────────────────────────────────────────────

@Composable
private fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var level by remember { mutableStateOf(-1) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val raw = intent?.getIntExtra("level", -1) ?: -1
                val scale = intent?.getIntExtra("scale", 100) ?: 100
                if (raw >= 0 && scale > 0) level = raw * 100 / scale
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose { context.unregisterReceiver(receiver) }
    }

    return level
}

@Composable
private fun rememberFreeBytes(): Long {
    val context = LocalContext.current
    var free by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            free = runCatching {
                val path = context.getExternalFilesDir(null)?.path
                    ?: Environment.getDataDirectory().path
                StatFs(path).availableBytes
            }.getOrDefault(0L)
            delay(5000)
        }
    }

    return free
}

// ── 权限 ────────────────────────────────────────────────────────────

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    return permissions.toTypedArray()
}
