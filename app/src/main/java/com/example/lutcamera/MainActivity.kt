package com.example.lutcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.example.lutcamera.ui.CameraScreen
import com.example.lutcamera.ui.theme.LUTCameraTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LUTCameraTheme {
                KeepScreenOn()
                CameraScreen(lifecycleOwner = this@MainActivity)
            }
        }
    }
}

/** 取景时保持屏幕常亮。 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current

    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
