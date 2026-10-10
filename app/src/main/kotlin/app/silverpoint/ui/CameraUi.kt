package app.silverpoint.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import app.silverpoint.AspectRatio
import app.silverpoint.FlashMode
import app.silverpoint.MergeMode

/** A look (recipe) as the camera screen shows it. [swatch] is skin, foliage and sky through it: the stand-in for the moment before the sample stills are made. */
@Immutable
data class RecipeUi(
    val name: String,
    val look: String,
    val bestFor: String,
    val set: String,
    val group: String,
    val swatch: List<Color>,
    /** Black and white. */
    val mono: Boolean = false,
)

/** A lens button: its physical ID and label ("0.6", "1", "3", "5"). */
@Immutable
data class LensUi(val id: String, val text: String)

/** Pro mode's values: null fields are automatic; `live` values are what auto-exposure is doing now. */
@Immutable
data class ProUi(
    val isoStops: List<Int>,
    val iso: Int?,
    val liveIso: Int?,
    val shutterStops: List<Long>,
    val shutterNs: Long?,
    val liveShutterNs: Long?,
    val hasAutofocus: Boolean,
    /** Closest focus in diopters (the dial's right end). */
    val maxFocus: Float,
    val focus: Float?,
    val liveFocus: Float?,
    val kelvin: Int?,
    val tint: Int,
    /** ISO and shutter both set: exposure compensation does nothing. */
    val fullManual: Boolean,
)

/**
 * Everything the camera screen shows, as plain values. `CameraScreen` builds it from the view
 * model; screenshot tests build it by hand, so the layout can be checked without a camera.
 */
@Immutable
data class CameraUi(
    val permission: Boolean,
    val recipes: List<RecipeUi>,
    val recipe: String?,
    val favourites: Set<String>,
    /** Each look on the bundled sample picture, by name (the still look previews; live ones are drawn over them through [LocalLiveLooks]). */
    val lookThumbs: Map<String, ImageBitmap>,
    val lenses: List<LensUi>,
    val lens: String?,
    val pro: Boolean,
    val proValues: ProUi?,
    val evThirds: Int,
    val flash: FlashMode,
    /** The lens can fire a flash. */
    val hasFlash: Boolean,
    val aspect: AspectRatio,
    val grid: Boolean,
    val histogramOn: Boolean,
    val zebras: Boolean,
    val peaking: Boolean,
    val level: Boolean,
    val timerSeconds: Int,
    val histogram: IntArray?,
    val focusPoint: Pair<Float, Float>?,
    val focusLocked: Boolean,
    val countdown: Int,
    val busy: Boolean,
    val busyLabel: String?,
    val developing: Int,
    val merge: MergeMode,
    val message: String?,
    /** Counts photos captured this session: each new one is felt (a confirmation haptic). */
    val shots: Int,
    val thumbnail: ImageBitmap?,
    val plainPreview: Boolean,
    val tilt: Float,
    val deviceAngle: Int,
)

/** What the camera screen can ask for. */
class CameraActions(
    val onShutter: () -> Unit,
    val onSettings: () -> Unit,
    val onGallery: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onRecipe: (String) -> Unit,
    val onToggleFavourite: (String) -> Unit,
    val onLens: (String) -> Unit,
    val onProMode: (Boolean) -> Unit,
    val onEv: (Int) -> Unit,
    val onIso: (Int?) -> Unit,
    val onShutterSpeed: (Long?) -> Unit,
    val onFocus: (Float?) -> Unit,
    val onWhiteBalance: (Int?, Int) -> Unit,
    val onFlash: (FlashMode) -> Unit,
    val onAspect: (AspectRatio) -> Unit,
    val onTimer: (Int) -> Unit,
    val onTapFocus: (Float, Float) -> Unit,
    val onResetFocus: () -> Unit,
    val onToggleGrid: () -> Unit,
    val onToggleHistogram: () -> Unit,
    val onToggleZebras: () -> Unit,
    val onTogglePeaking: () -> Unit,
    val onToggleLevel: () -> Unit,
    val onDismissMessage: () -> Unit,
)
