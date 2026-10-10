package app.silverpoint.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import app.silverpoint.AspectRatio
import app.silverpoint.DiagnosticsState
import app.silverpoint.FlashMode
import app.silverpoint.MergeMode
import app.silverpoint.RecipeSwatch
import app.silverpoint.Settings
import app.silverpoint.camera.ExposureMath
import app.silverpoint.pipeline.LookThumbnails
import app.silverpoint.pipeline.PreviewRenderer
import app.silverpoint.recipes.Recipe
import app.silverpoint.recipes.RecipeStore
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the screens on the JVM (Robolectric) the way the S25 Ultra shows them, for checking the
 * layout without a phone: its default screen size in dp (384 x 832 at the default FHD+ resolution;
 * an estimate) with the status bar and gesture bar drawn in, since Robolectric
 * has no system bars. Dev-only, like the Lightroom fit tools: skipped unless SILVERPOINT_SCREENSHOTS
 * names a folder for the PNGs (ROBOLECTRIC_REPO_URL can point Robolectric at a Maven mirror).
 *
 * The viewfinder is the committed sofa scene, developed with no look (`src/test/resources/screens/sofa.png`,
 * made by the pipeline's ViewfinderFixtureTest), with each look's own preview table applied, so the screenshots
 * and the look thumbnails show what the looks really do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w384dp-h832dp-night-xxhdpi")
class ScreenshotTest {
    private val dir: String? = System.getProperty("silverpoint.screenshots")
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun onlyWhenAsked() = assumeTrue(dir != null)

    private val recipeModels: List<Recipe> by lazy { RecipeStore.load(app) }

    internal val recipes by lazy {
        recipeModels.map { r -> RecipeUi(r.name, r.look, r.bestFor, r.set, r.group, RecipeSwatch.of(r.params).map { Color(it) }, mono = r.params.grayscale) }
    }

    private val neutral: Bitmap by lazy { BitmapFactory.decodeFile(File("src/test/resources/screens/sofa.png").path) }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The scene through a look's viewfinder table, as the live preview shows it. */
    private fun viewfinder(name: String): ImageBitmap {
        val r = recipeModels.first { it.name == name }
        val out = LookThumbnails.apply(pixels(neutral), PreviewRenderer.previewLook(r.params))
        return Bitmap.createBitmap(out, neutral.width, neutral.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** [source] through every look's thumbnail table (17³), as the app draws the thumbnails. */
    private fun thumbsOf(source: Bitmap): Map<String, ImageBitmap> {
        val px = pixels(source)
        return recipeModels.associate { r ->
            r.name to Bitmap.createBitmap(LookThumbnails.apply(px, LookThumbnails.lut(r.params)), source.width, source.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    /** Live thumbnails: the scene through each look (on the phone they are drawn from the camera, about 30 times a second). */
    internal val thumbs by lazy { thumbsOf(Bitmap.createScaledBitmap(neutral, 288, 384, true)) }

    /** Still thumbnails (Settings → Live look previews off): the bundled sample picture through each look. */
    private val stills by lazy { thumbsOf(BitmapFactory.decodeFile(File("src/main/assets/look_sample.jpg").path)) }

    internal val noop = CameraActions(
        onShutter = {}, onSettings = {}, onGallery = {}, onRequestPermission = {}, onRecipe = {}, onToggleFavourite = {},
        onLens = {}, onProMode = {}, onEv = {}, onIso = {}, onShutterSpeed = {}, onFocus = {}, onWhiteBalance = { _, _ -> },
        onFlash = {}, onAspect = {}, onTimer = {}, onTapFocus = { _, _ -> }, onResetFocus = {}, onToggleGrid = {}, onToggleHistogram = {},
        onToggleZebras = {}, onTogglePeaking = {}, onToggleLevel = {}, onDismissMessage = {},
    )

    internal val favourites = setOf("Reportage", "Natural Portrait 400", "Sunny 200", "Vivid Slide", "Pacific Blues", "Tungsten 800", "Mono Yellow Filter")

    internal fun ui(
        pro: Boolean = false,
        permission: Boolean = true,
        recipe: String = "Reportage",
        aspect: AspectRatio = AspectRatio.R3x4,
        manual: Boolean = false,
    ) = CameraUi(
        permission = permission,
        recipes = recipes,
        recipe = recipe,
        favourites = favourites,
        lookThumbs = thumbs,
        lenses = listOf(LensUi("2", "0.6"), LensUi("5", "1"), LensUi("6", "3"), LensUi("7", "5")),
        lens = "5",
        pro = pro,
        proValues = ProUi(
            isoStops = ExposureMath.isoStops(12, 3200), iso = if (manual) 200 else null, liveIso = 160,
            shutterStops = ExposureMath.shutterStops(43_219, 255_098_784), shutterNs = if (manual) 4_000_000L else null, liveShutterNs = 8_000_000,
            hasAutofocus = true, maxFocus = 10f, focus = null, liveFocus = 0.4f, kelvin = if (manual) 4300 else null, tint = if (manual) 6 else 0,
            fullManual = manual,
        ),
        evThirds = if (pro) 0 else 1,
        flash = FlashMode.Auto,
        hasFlash = true,
        aspect = aspect,
        grid = false,
        histogramOn = false,
        zebras = false,
        peaking = false,
        level = false,
        timerSeconds = 0,
        histogram = HISTOGRAM,
        focusPoint = 0.62f to 0.40f,
        focusLocked = true,
        countdown = 0,
        busy = false,
        busyLabel = null,
        developing = 0,
        merge = MergeMode.Auto,
        message = null,
        shots = 0,
        thumbnail = thumbs["Reportage"],
        plainPreview = false,
        tilt = 0f,
        deviceAngle = 0,
    )

    @Composable
    internal fun Scene(name: String = "Reportage") = Image(viewfinder(name), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())

    private fun camera(name: String, u: CameraUi = ui(), initial: Initial = Initial()) =
        shot(name) { CameraLayout(u, noop, initial) { Scene(u.recipe ?: "Reportage") } }

    @Test fun cameraPhoto() = camera("camera_photo")
    @Test fun cameraPhotoEv() = camera("camera_photo_ev", initial = Initial(dial = DialParam.Ev))
    @Test fun cameraPhotoLook() = camera("camera_photo_vivid_slide", ui(recipe = "Vivid Slide"))
    @Test fun cameraPro() = camera("camera_pro", ui(pro = true))
    @Test fun cameraProIso() = camera("camera_pro_iso", ui(pro = true), Initial(dial = DialParam.Iso))
    @Test fun cameraProShutter() = camera("camera_pro_shutter", ui(pro = true, manual = true), Initial(dial = DialParam.Shutter))
    @Test fun cameraProFocus() = camera("camera_pro_focus", ui(pro = true), Initial(dial = DialParam.Focus))
    @Test fun cameraProWhiteBalance() = camera("camera_pro_wb", ui(pro = true, manual = true), Initial(dial = DialParam.WhiteBalance))
    @Test fun topFlash() = camera("top_flash", initial = Initial(top = TopOption.Flash))
    @Test fun topTimer() = camera("top_timer", initial = Initial(top = TopOption.Timer))
    @Test fun topAspect() = camera("top_aspect", initial = Initial(top = TopOption.Aspect))
    @Test fun topAids() = camera("top_aids", ui().copy(grid = true, histogramOn = true), Initial(top = TopOption.Aids))
    @Test fun aspect4x5() = camera("aspect_4x5", ui(aspect = AspectRatio.R4x5))
    @Test fun aspect1x1() = camera("aspect_1x1", ui(aspect = AspectRatio.R1x1))
    @Test fun aspect9x16() = camera("aspect_9x16", ui(aspect = AspectRatio.R9x16))
    @Test fun aspect9x16Pro() = camera("aspect_9x16_pro", ui(pro = true, aspect = AspectRatio.R9x16), Initial(dial = DialParam.Iso))

    @Test
    fun cameraBusy() = camera("camera_message", ui().copy(message = "Capture failed: timed out waiting for the photo", developing = 2, busyLabel = "merging 6 frames…"))

    /** A shorter screen (3-button navigation, smaller phones): the picture keeps its width, the controls fade over it. */
    @Test
    @Config(qualifiers = "w384dp-h760dp-night-xxhdpi")
    fun cameraShortScreen() = camera("camera_short", ui(pro = true), Initial(dial = DialParam.Shutter))

    /** The largest default size (WQHD+ resolution or a smaller screen zoom): what 0.9.6's screenshots assumed. */
    @Test
    @Config(qualifiers = "w412dp-h892dp-night-xxhdpi")
    fun cameraLargeScreen() = camera("camera_412dp", ui(pro = true), Initial(dial = DialParam.Iso))

    /** Bigger system text (Settings → Font size one step up): labels must still fit. */
    @Test
    @Config(qualifiers = "w384dp-h832dp-night-xxhdpi", fontScale = 1.15f)
    fun cameraLargeText() = camera("camera_large_text", ui(pro = true), Initial(dial = DialParam.Shutter))

    @Test fun cameraPermission() = shot("camera_permission") { CameraLayout(ui(permission = false), noop) {} }

    /** The look selector as it opens over the camera (the sheet itself needs a window, so it is drawn in place). */
    private fun looks(name: String, filter: LookFilter, pictures: Map<String, ImageBitmap> = thumbs) = shot(name) {
        Box(Modifier.fillMaxSize()) {
            CameraLayout(ui().copy(lookThumbs = pictures), noop) { Scene() }
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Sl.Card).padding(top = 28.dp),
            ) {
                LookSelector(recipes, "Reportage", favourites, pictures, {}, {}, initialFilter = filter, modifier = Modifier.fillMaxHeight())
            }
        }
    }

    @Test fun looksAll() = looks("looks_all", LookFilter.All)
    @Test fun looksFilm() = looks("looks_film", LookFilter.Film)
    @Test fun looksStarred() = looks("looks_starred", LookFilter.Starred)
    @Test fun looksStill() = looks("looks_still", LookFilter.All, stills)

    @Test
    fun settings() = shot("settings") {
        SettingsScreen(Settings(app), "0.9.7", livePreview = true, previewFailed = false, {}, {}, {}, {}, {}, lastPhoto = "GPU: developed in 742 ms, saved in 1180 ms, Ultra HDR")
    }

    @Test fun intro() = shot("intro") { IntroScreen(onDone = {}, playStartup = false) }

    @Test
    fun diagnostics() = shot("diagnostics") {
        DiagnosticsLayout(
            DiagnosticsState.Done,
            "GO: RAW on 4 lenses\nManual ISO/shutter honoured: true\nGPU self-test: PASS",
            "Run 20261009_101500: files go to Download/Silverpoint/diag_20261009_101500\nFound 3 camera IDs: 0, 1, 3\n...",
            permissionDenied = false, {}, {}, {}, {}, {},
        )
    }

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("$dir/$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) { SilverpointTheme { Phone(content) } }
        }

    /**
     * The phone's system bars around [content], which Robolectric does not draw: the status bar
     * (with the camera cut-out) and the gesture bar, at the S25 Ultra's sizes (estimated).
     */
    @Composable
    private fun Phone(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Sl.Black)) {
            Box(Modifier.fillMaxSize().padding(top = STATUS_BAR, bottom = GESTURE_BAR)) { content() }
            Row(
                Modifier.fillMaxWidth().height(STATUS_BAR).padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("12:45", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("5G  ▮▮▮  82%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(14.dp).clip(CircleShape).background(Color(0xFF050505)))
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp).width(108.dp).height(4.dp)
                    .clip(CircleShape).background(Color(0xCCFFFFFF)),
            )
        }
    }

    companion object {
        private val STATUS_BAR = 40.dp
        private val GESTURE_BAR = 24.dp
        private val HISTOGRAM = IntArray(64) { i -> (900 * Math.exp(-((i - 30.0) / 11).let { it * it }) + 250 * Math.exp(-((i - 52.0) / 5).let { it * it })).toInt() + 20 }
    }
}
