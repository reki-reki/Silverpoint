package app.silverpoint

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.silverpoint.ui.CameraScreen
import app.silverpoint.ui.DiagnosticsScreen
import app.silverpoint.ui.IntroScreen
import app.silverpoint.ui.SilverpointTheme
import app.silverpoint.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val camera: CameraViewModel by viewModels()
    private val diagnostics: DiagnosticsViewModel by viewModels()

    private enum class Screen { Camera, Settings, Diagnostics }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always light status and navigation bar icons: every screen is dark, whatever the phone's theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            SilverpointTheme(haptics = camera.settings.haptics) {
                var screen by rememberSaveable { mutableStateOf(Screen.Camera) }
                when {
                    !camera.settings.onboarded -> IntroScreen(onDone = { camera.settings.onboarded = true })
                    screen == Screen.Settings -> {
                        BackHandler { screen = Screen.Camera }
                        SettingsScreen(
                            settings = camera.settings,
                            version = BuildConfig.VERSION_NAME,
                            livePreview = camera.glPreview,
                            previewFailed = camera.glPreviewFailed,
                            lastPhoto = camera.lastTiming,
                            onLivePreview = camera::setLivePreview,
                            onOverlaysChanged = camera::refreshOverlays,
                            onBack = { screen = Screen.Camera },
                            onDiagnostics = { screen = Screen.Diagnostics },
                            onIntro = { camera.settings.onboarded = false; screen = Screen.Camera },
                        )
                    }
                    screen == Screen.Diagnostics -> {
                        BackHandler { screen = Screen.Settings }
                        DiagnosticsScreen(diagnostics, onBack = { screen = Screen.Settings })
                    }
                    else -> CameraScreen(camera, onSettings = { screen = Screen.Settings })
                }
            }
        }
    }
}
