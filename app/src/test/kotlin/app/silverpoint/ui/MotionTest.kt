package app.silverpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Filmstrips of the camera screen's transitions: the animation clock is stopped, the control is
 * tapped, and a frame is captured every [STEP_MS] ms, so a transition can be checked frame by frame
 * without a phone (one motion, nothing drawn twice, nothing jumping). Dev-only, like
 * [ScreenshotTest]: runs when SILVERPOINT_SCREENSHOTS names a folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w384dp-h832dp-night-xxhdpi")
class MotionTest {
    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getProperty("silverpoint.screenshots")
    private val fixtures by lazy { ScreenshotTest() }

    @Before
    fun onlyWhenAsked() = assumeTrue(dir != null)

    private fun film(name: String, frames: Int = 8, tap: () -> Unit) {
        settle()
        tap()
        compose.waitForIdle()
        for (i in 0..frames) {
            val file = File("$dir/motion/${name}_%03d.png".format(i * STEP_MS)).also { it.parentFile?.mkdirs() }
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.mainClock.advanceTimeBy(STEP_MS)
        }
    }

    /** Runs the clock by hand from here on and lets everything finish moving. */
    private fun settle() {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
    }

    /** Taps SPEED and lets its dial finish opening (the tap is handled before the clock jumps). */
    private fun openShutterDial() {
        settle()
        compose.onNodeWithText("1/125").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun camera(pro: Boolean) = compose.setContent {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            SilverpointTheme {
                Box(Modifier.fillMaxSize().background(Sl.Black).padding(top = 40.dp, bottom = 24.dp)) {
                    val u = fixtures.ui(pro = pro)
                    CameraLayout(u, fixtures.noop) { fixtures.Scene(u.recipe ?: "Reportage") }
                }
            }
        }
    }

    /** Pro mode: tap SPEED, the lens row gives way to the shutter dial. */
    @Test
    fun proOpenDial() {
        camera(pro = true)
        film("pro_open") { compose.onNodeWithText("1/125").performClick() }
    }

    /** Pro mode: SPEED open, tap ISO, the dials swap in place. */
    @Test
    fun proSwitchDial() {
        camera(pro = true)
        openShutterDial()
        film("pro_switch") { compose.onAllNodesWithText("160").onFirst().performClick() }
    }

    /** Pro mode: SPEED open, tap it again, the dial gives way to the lens row. */
    @Test
    fun proCloseDial() {
        camera(pro = true)
        openShutterDial()
        // The SPEED button's value (the last match: the dial's header above it repeats it).
        film("pro_close") { compose.onAllNodesWithText("1/125").onLast().performClick() }
    }

    /** The look selector: All (21 looks) to B&W (2): the tabs must not move. */
    @Test
    fun looksSwitchTab() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SilverpointTheme {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f)
                                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Sl.Card).padding(top = 28.dp),
                        ) {
                            LookSelector(fixtures.recipes, "Reportage", fixtures.favourites, fixtures.thumbs, {}, {}, modifier = Modifier.fillMaxHeight())
                        }
                    }
                }
            }
        }
        film("looks_tab", frames = 2) { compose.onAllNodesWithText("Core", substring = true).onFirst().performClick() }
    }

    /** First launch: the start-up film's last frame (the mark at the centre) gliding into the first page. */
    @Test
    fun introReveal() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SilverpointTheme { Box(Modifier.fillMaxSize().background(Sl.Black)) { IntroScreen(onDone = {}) } }
            }
        }
        // 50 frames a second, so the strip can be joined to the film as a preview video.
        for (i in 0..65) {
            val file = File("$dir/motion/intro_reveal_%04d.png".format(i * 20)).also { it.parentFile?.mkdirs() }
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.mainClock.advanceTimeBy(20)
        }
    }

    companion object {
        const val STEP_MS = 40L
    }
}
