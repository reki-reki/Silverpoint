package app.silverpoint

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** When to merge a burst of RAW frames into one photo. */
enum class MergeMode(val label: String) {
    Off("Off"),
    Auto("Auto (low light)"),
    Always("Always"),
}

/** The flash for stills. The preview never lights it; [On] and [Auto] fire it for the photo. */
enum class FlashMode(val label: String) {
    Off("Flash off"),
    Auto("Flash auto"),
    On("Flash on"),
}

/**
 * The photo's shape, as the long side over the short side; portrait names, as the camera is held
 * upright. A landscape photo gets the same shape turned (16:9 for 9:16). [ratio] 4:3 is the whole sensor.
 */
enum class AspectRatio(val label: String, val ratio: Float) {
    R3x4("3:4", 4f / 3f), // whole sensor, most detail
    R4x5("4:5", 5f / 4f), // portrait posts
    R1x1("1:1", 1f), // square
    R9x16("9:16", 16f / 9f), // stories and wallpapers
}

/**
 * Everything the app remembers between launches, as Compose state backed by SharedPreferences.
 * Reads are instant; writes go to disk in the background.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("silverpoint", Context.MODE_PRIVATE)

    var recipe by string("recipe", DEFAULT_RECIPE)
    var favourites by stringSet("favourites", DEFAULT_FAVOURITES)

    /** Viewfinder shows the recipe (GL preview); off = the camera's plain preview. */
    var livePreview by boolean("livePreview", true)
    var grid by boolean("grid", false)
    var histogram by boolean("histogram", false)
    var zebras by boolean("zebras", false)
    var peaking by boolean("peaking", false)
    var level by boolean("level", false)
    var ultraHdr by boolean("ultraHdr", true)
    var saveDng by boolean("saveDng", false)
    var merge by enum("merge", MergeMode.Auto)
    var timerSeconds by int("timerSeconds", 0)
    var flash by enum("flash", FlashMode.Off)
    var aspect by enum("aspect", AspectRatio.R3x4)
    var proMode by boolean("proMode", false)
    var shutterSound by boolean("shutterSound", true)
    /** Ticks, taps and confirmations from the vibrator. */
    var haptics by boolean("haptics", true)
    /** Look previews from the live camera (30 fps); off shows the looks on a sample picture. */
    var liveLookPreviews by boolean("liveLookPreviews", true)
    var onboarded by boolean("onboarded", false)
    /** Physical camera ID of the last lens used. */
    var lensId by string("lensId", "")

    fun toggleFavourite(name: String) {
        favourites = if (name in favourites) favourites - name else favourites + name
    }

    private fun boolean(key: String, default: Boolean) = pref(default, { prefs.getBoolean(key, default) }) { putBoolean(key, it) }

    private fun int(key: String, default: Int) = pref(default, { prefs.getInt(key, default) }) { putInt(key, it) }

    private fun string(key: String, default: String) = pref(default, { prefs.getString(key, default) ?: default }) { putString(key, it) }

    private fun stringSet(key: String, default: Set<String>) =
        pref(default, { prefs.getStringSet(key, default)?.toSet() ?: default }) { putStringSet(key, it) }

    private inline fun <reified E : Enum<E>> enum(key: String, default: E) = pref(
        default,
        { prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default },
    ) { putString(key, it.name) }

    private fun <T> pref(default: T, read: () -> T, write: SharedPreferences.Editor.(T) -> Unit): ReadWriteProperty<Any?, T> =
        object : ReadWriteProperty<Any?, T> {
            private val state: MutableState<T> = mutableStateOf(runCatching(read).getOrDefault(default))

            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                state.value = value
                prefs.edit { write(value) }
            }
        }

    companion object {
        const val DEFAULT_RECIPE = "Reportage"
        val DEFAULT_FAVOURITES = setOf("Reportage", "Natural Portrait 400", "Sunny 200", "Vivid Slide", "Mono Yellow Filter", "Tungsten 800")
    }
}
