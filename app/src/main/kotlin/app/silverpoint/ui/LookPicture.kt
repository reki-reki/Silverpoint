package app.silverpoint.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Draws look thumbnails live from the camera (the GL preview renders each one into its own
 * surface, about 30 times a second). Provided by the camera screen while live look previews are
 * on and the GL preview runs; null otherwise, and the thumbnails are stills.
 */
interface LiveLooks {
    fun attach(key: Any, surface: Surface, width: Int, height: Int, look: String, radius: Float)
    fun resize(key: Any, width: Int, height: Int)
    /** Returns once [key]'s surface is no longer drawn into. */
    fun detach(key: Any)
}

val LocalLiveLooks = compositionLocalOf<LiveLooks?> { null }

/**
 * What [look] does, as a picture: the bundled sample picture through it ([still]), and over that,
 * when live previews are on, the camera through it. The still shows until the first live frame,
 * so the card is never empty; [swatch] stands in for the few moments before the stills are made.
 * [radius] is the corner radius the picture is clipped to (half its width for a circle).
 */
@Composable
fun LookPicture(look: String, still: ImageBitmap?, swatch: List<Color>, radius: Dp, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (still != null) {
            Image(still, look, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(swatch.ifEmpty { listOf(Sl.CardHigh, Sl.CardHigh) })))
        }
        val live = LocalLiveLooks.current
        if (live != null) LiveThumb(live, look, radius, Modifier.matchParentSize())
    }
}

/** A transparent TextureView that the renderer draws [look] into; the rounded corners are cut in the shader. */
@Composable
private fun LiveThumb(live: LiveLooks, look: String, radius: Dp, modifier: Modifier) {
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    // A new view (and surface) when the look changes: the renderer draws one look per surface.
    key(live, look, radiusPx) {
        val id = remember { Any() }
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null

                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            val s = Surface(st)
                            surface = s
                            live.attach(id, s, width, height, look, radiusPx)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = live.resize(id, width, height)

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            live.detach(id)
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = modifier,
        )
    }
}
