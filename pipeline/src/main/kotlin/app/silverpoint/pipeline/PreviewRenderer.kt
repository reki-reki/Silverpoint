package app.silverpoint.pipeline

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws the live viewfinder: camera frames arrive in a [SurfaceTexture], go through
 * `shaders/preview.frag` (recipe look table, vignette, grain, zebras, focus peaking) and are drawn
 * onto the on-screen [Surface]. Also measures a luminance histogram a few times a second, and draws
 * the live look thumbnails: the same camera frame through other looks' tables, into small surfaces
 * of the look selector ([attachThumb]), about 30 times a second.
 *
 * One instance per camera session: [start] once, [close] when the camera closes. If [start] throws,
 * nothing is left attached to the window surface, so the caller can hand it to the camera directly.
 */
class PreviewRenderer(context: Context) : Closeable {

    /** How the preview should look: the recipe's preview table and its position-dependent effects. */
    class Style(
        val look: LookLut,
        val vignette: Float = 0f,
        val vignetteStart: Float = 0.3f,
        val vignetteEnd: Float = 1f,
        val grainAmount: Float = 0f,
        val grainSize: Float = 25f,
        val grainRoughness: Float = 0.5f,
    )

    private val assets = context.applicationContext.assets
    private val thread = HandlerThread("silverpoint-preview").also { it.start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var cameraTex = 0
    private var lookTex = 0
    private var quad = 0
    private var histFbo = 0
    private var histTex = 0
    /** A 2³ identity look, for thumbnails whose look table has not arrived yet. */
    private var identityTex = 0
    private var thumbProgram = 0
    private var config: EGLConfig? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var frames = 0
    private var drawFailed = false
    /** Read-back buffer for the histogram, reused (it is read five times a second). */
    private val histBuffer: ByteBuffer = ByteBuffer.allocateDirect(HIST_W * HIST_H * 4).order(ByteOrder.nativeOrder())
    private val texMatrix = FloatArray(16)
    private val started = SystemClock.elapsedRealtime()

    @Volatile private var style: Style? = null
    @Volatile private var styleDirty = true
    @Volatile var zebras = false
    @Volatile var peaking = false

    /** Called on the preview thread with 64 luminance bins, about five times a second. */
    @Volatile var onHistogram: ((IntArray) -> Unit)? = null

    /** One live thumbnail: a [surface] (a TextureView's) showing the camera through [look]. Preview thread only. */
    private class ThumbTarget(val surface: Surface, var width: Int, var height: Int, var look: String, var radius: Float) {
        var egl: EGLSurface = EGL14.EGL_NO_SURFACE
        /** The surface could not be attached (already released); not retried. */
        var failed = false
    }

    private val thumbs = HashMap<Any, ThumbTarget>()
    private val thumbLooks = HashMap<String, Pair<Int, Int>>()
    private val pendingLooks = ConcurrentLinkedQueue<Pair<String, LookLut>>()
    private var lastThumbsAt = 0L

    /**
     * Shows the camera through [look] (a name given to [addThumbLooks]) in [surface], [width] x
     * [height] pixels, cut to a rounded rectangle of corner [radius] pixels (half the width for a
     * circle; the corners are transparent). Replaces whatever [key] showed before. The surface can be
     * attached before [start]; it is drawn once the renderer runs.
     */
    fun attachThumb(key: Any, surface: Surface, width: Int, height: Int, look: String, radius: Float) {
        handler.post {
            thumbs.remove(key)?.let(::destroyThumb)
            thumbs[key] = ThumbTarget(surface, width, height, look, radius)
        }
    }

    /** The surface of [key] changed size. */
    fun resizeThumb(key: Any, width: Int, height: Int) {
        handler.post { thumbs[key]?.let { it.width = width; it.height = height } }
    }

    /** Stops drawing into [key]'s surface; returns once the surface is no longer used, so it can be released. */
    fun detachThumb(key: Any) {
        if (Looper.myLooper() == thread.looper) {
            thumbs.remove(key)?.let(::destroyThumb)
            return
        }
        val done = CountDownLatch(1)
        if (!handler.post { thumbs.remove(key)?.let(::destroyThumb); done.countDown() }) return
        // Bounded: a thumbnail's swap can wait for the view's thread (usually the caller) to take its
        // last frame. Once the caller releases the surface, that swap fails and the target is dropped.
        done.await(DETACH_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /** Look tables for the thumbnails, by name (small ones: 17³ is plenty for a thumbnail). */
    fun addThumbLooks(looks: Map<String, LookLut>) {
        looks.forEach { (name, lut) -> pendingLooks.add(name to lut) }
    }

    /**
     * Attaches to [output] (the viewfinder, [width] x [height] pixels) and returns the surface the
     * camera should draw into, sized [cameraWidth] x [cameraHeight].
     */
    fun start(output: Surface, width: Int, height: Int, cameraWidth: Int, cameraHeight: Int): Surface = onThread {
        try {
            setUp(output, width, height, cameraWidth, cameraHeight)
        } catch (t: Throwable) {
            tearDown()
            throw t
        }
    }

    fun setStyle(s: Style?) {
        style = s
        styleDirty = true
    }

    override fun close() {
        runCatching { onThread { tearDown() } }
        thread.quitSafely()
    }

    private fun <T> onThread(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val done = CountDownLatch(1)
        var outcome: Result<T>? = null
        // A closed renderer's thread takes no more work (a second close); waiting would block forever.
        check(handler.post {
            outcome = runCatching(block)
            done.countDown()
        }) { "preview renderer is closed" }
        done.await()
        return outcome!!.getOrThrow()
    }

    private fun setUp(output: Surface, width: Int, height: Int, cameraWidth: Int, cameraHeight: Int): Surface {
        viewWidth = width
        viewHeight = height
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            // Alpha for the thumbnails' transparent corners; the viewfinder writes 1 and is opaque.
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL window config" }
        config = configs[0]
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        window = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(display, window, window, eglContext)) { "eglMakeCurrent failed" }

        val vertex = ShaderSource.load(assets, "shaders/preview.vert")
        program = linkProgram(vertex, ShaderSource.load(assets, "shaders/preview.frag"))
        thumbProgram = linkProgram(vertex, ShaderSource.load(assets, "shaders/thumb.frag"))

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        cameraTex = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        GLES30.glGenBuffers(1, ids, 0)
        quad = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        vb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES30.GL_STATIC_DRAW)

        // Small offscreen target for the histogram.
        GLES30.glGenTextures(1, ids, 0)
        histTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, histTex)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, HIST_W, HIST_H)
        GLES30.glGenFramebuffers(1, ids, 0)
        histFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, histTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        identityTex = lutTexture(LookLut.identity(2))
        checkGl("set up")

        val st = SurfaceTexture(cameraTex)
        st.setDefaultBufferSize(cameraWidth, cameraHeight)
        st.setOnFrameAvailableListener({
            // A failing frame must not stop the camera; log the first failure so a frozen viewfinder has a cause.
            runCatching { draw() }.onFailure { if (!drawFailed) { drawFailed = true; Log.w(TAG, "preview frame failed", it) } }
        }, handler)
        surfaceTexture = st
        return Surface(st).also { cameraSurface = it }
    }

    private fun draw() {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        if (styleDirty) uploadStyle()
        frames++
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        render(viewWidth, viewHeight, overlays = true)
        EGL14.eglSwapBuffers(display, window)
        val listener = onHistogram
        if (listener != null && frames % 6 == 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo)
            render(HIST_W, HIST_H, overlays = false)
            val buf = histBuffer.also { it.clear() }
            GLES30.glReadPixels(0, 0, HIST_W, HIST_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            listener(Histogram.ofRgba(buf, HIST_W * HIST_H))
        }
        renderThumbs()
    }

    /**
     * Draws every attached thumbnail from the frame just shown, at most every [THUMB_FRAME_MS]. Each
     * surface gets the frame at the viewfinder's shape, scaled to cover it and centred, through its
     * look, with the corners cut away. Swapping a thumbnail never waits for the display.
     */
    private fun renderThumbs() {
        while (true) {
            val (name, lut) = pendingLooks.poll() ?: break
            thumbLooks.remove(name)?.let { GLES30.glDeleteTextures(1, intArrayOf(it.first), 0) }
            thumbLooks[name] = lutTexture(lut) to lut.size
        }
        if (thumbs.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastThumbsAt < THUMB_FRAME_MS) return
        lastThumbsAt = now
        val frameRatio = if (viewHeight > 0) viewWidth.toFloat() / viewHeight else 0.75f
        for (t in thumbs.values) {
            if (t.failed || t.width <= 0 || t.height <= 0) continue
            if (t.egl == EGL14.EGL_NO_SURFACE) {
                t.egl = runCatching { EGL14.eglCreateWindowSurface(display, config, t.surface, intArrayOf(EGL14.EGL_NONE), 0) }
                    .getOrDefault(EGL14.EGL_NO_SURFACE)
                if (t.egl == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, t.egl, t.egl, eglContext)) {
                    t.failed = true
                    continue
                }
                EGL14.eglSwapInterval(display, 0)
            } else if (!EGL14.eglMakeCurrent(display, t.egl, t.egl, eglContext)) {
                continue
            }
            GLES30.glViewport(0, 0, t.width, t.height)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            // Cover the surface with the frame's shape: the overflow falls outside the surface.
            val w = t.width.toFloat()
            val h = t.height.toFloat()
            val (vw, vh) = if (w / h > frameRatio) w to w / frameRatio else h * frameRatio to h
            GLES30.glViewport(((w - vw) / 2f).toInt(), ((h - vh) / 2f).toInt(), vw.toInt(), vh.toInt())
            GLES30.glUseProgram(thumbProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
            GLES30.glUniform1i(thumbLoc("uCamera"), 0)
            val (lut, size) = thumbLooks[t.look] ?: (identityTex to 2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut)
            GLES30.glUniform1i(thumbLoc("uLook"), 1)
            GLES30.glUniform1f(thumbLoc("uLookSize"), size.toFloat())
            GLES30.glUniformMatrix4fv(thumbLoc("uTexMatrix"), 1, false, texMatrix, 0)
            GLES30.glUniform4f(thumbLoc("uRect"), 0f, 0f, w, h)
            GLES30.glUniform1f(thumbLoc("uRadius"), t.radius.coerceIn(0f, minOf(w, h) / 2f))
            drawQuad(thumbProgram)
            if (!EGL14.eglSwapBuffers(display, t.egl)) {
                // The view let go of its surface without detaching first (it is being destroyed).
                EGL14.eglMakeCurrent(display, window, window, eglContext)
                destroyThumb(t)
                t.failed = true
            }
        }
        EGL14.eglMakeCurrent(display, window, window, eglContext)
    }

    private fun destroyThumb(t: ThumbTarget) {
        if (t.egl != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(display, t.egl)
        }
        t.egl = EGL14.EGL_NO_SURFACE
    }

    private fun drawQuad(p: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        val pos = attribLocations.getOrPut(p) { GLES30.glGetAttribLocation(p, "aPosition") }
        GLES30.glEnableVertexAttribArray(pos)
        GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(pos)
    }

    private fun render(w: Int, h: Int, overlays: Boolean) {
        val s = style
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        GLES30.glUniform1i(loc("uCamera"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lookTex)
        GLES30.glUniform1i(loc("uLook"), 1)
        GLES30.glUniform1f(loc("uLookSize"), (s?.look?.size ?: 2).toFloat())
        GLES30.glUniformMatrix4fv(loc("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform2f(loc("uViewSize"), w.toFloat(), h.toFloat())
        GLES30.glUniform1f(loc("uVignette"), s?.vignette ?: 0f)
        GLES30.glUniform1f(loc("uVignetteStart"), s?.vignetteStart ?: 0.3f)
        GLES30.glUniform1f(loc("uVignetteEnd"), s?.vignetteEnd ?: 1f)
        GLES30.glUniform1f(loc("uGrainAmount"), if (overlays) s?.grainAmount ?: 0f else 0f)
        GLES30.glUniform1f(loc("uGrainCell"), Grain.cellPixels(s?.grainSize ?: 25f, maxOf(w, h)))
        GLES30.glUniform1f(loc("uGrainRoughness"), s?.grainRoughness ?: 0f)
        GLES30.glUniform1i(loc("uGrainSeed"), frames)
        GLES30.glUniform1i(loc("uZebras"), if (overlays && zebras) 1 else 0)
        GLES30.glUniform1i(loc("uPeaking"), if (overlays && peaking) 1 else 0)
        GLES30.glUniform1f(loc("uTime"), (SystemClock.elapsedRealtime() - started) / 1000f)
        drawQuad(program)
    }

    /** Uploads the look table as an RGBA16F 3D texture with linear filtering (identity if no style). */
    private fun uploadStyle() {
        styleDirty = false
        if (lookTex != 0) GLES30.glDeleteTextures(1, intArrayOf(lookTex), 0)
        lookTex = lutTexture(style?.look ?: LookLut.identity(2))
    }

    /** [look] as a new RGBA16F 3D texture with linear filtering; returns its name. */
    private fun lutTexture(look: LookLut): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        val n = look.size
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        for (wrap in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, wrap, GLES30.GL_CLAMP_TO_EDGE)
        }
        val rgba = FloatArray(n * n * n * 4)
        for (i in 0 until n * n * n) {
            rgba[i * 4] = look.data[i * 3]
            rgba[i * 4 + 1] = look.data[i * 3 + 1]
            rgba[i * 4 + 2] = look.data[i * 3 + 2]
            rgba[i * 4 + 3] = 1f
        }
        val buf = ByteBuffer.allocateDirect(rgba.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(rgba)
        buf.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        checkGl("look upload")
        return tex
    }

    private fun tearDown() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        cameraSurface?.release()
        surfaceTexture?.release()
        cameraSurface = null
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, window, window, eglContext)
                val tex = (intArrayOf(cameraTex, lookTex, histTex, identityTex) + thumbLooks.values.map { it.first })
                    .filter { it != 0 }.toIntArray()
                if (tex.isNotEmpty()) GLES30.glDeleteTextures(tex.size, tex, 0)
                if (quad != 0) GLES30.glDeleteBuffers(1, intArrayOf(quad), 0)
                if (histFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(histFbo), 0)
                if (program != 0) GLES30.glDeleteProgram(program)
                if (thumbProgram != 0) GLES30.glDeleteProgram(thumbProgram)
            }
            thumbs.values.forEach(::destroyThumb)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        }
        cameraTex = 0
        lookTex = 0
        histTex = 0
        quad = 0
        histFbo = 0
        identityTex = 0
        program = 0
        thumbProgram = 0
        locations.clear()
        thumbLocations.clear()
        attribLocations.clear()
        thumbs.clear()
        thumbLooks.clear()
        pendingLooks.clear()
        config = null
        window = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        styleDirty = true
    }

    private fun linkProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                GLES30.glDeleteShader(s)
                throw IllegalStateException("preview shader failed to compile: $log")
            }
            return s
        }
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw IllegalStateException("preview program failed to link: $log")
        }
        return p
    }

    // Uniform and attribute locations, looked up once per program instead of on every frame.
    private val locations = HashMap<String, Int>()
    private val thumbLocations = HashMap<String, Int>()
    private val attribLocations = HashMap<Int, Int>()
    private fun loc(name: String) = locations.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }
    private fun thumbLoc(name: String) = thumbLocations.getOrPut(name) { GLES30.glGetUniformLocation(thumbProgram, name) }

    private fun checkGl(stage: String) {
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(err)} during $stage" }
    }

    companion object {
        const val HIST_W = 128
        const val HIST_H = 96
        /** Live thumbnails are redrawn at most this often: about 30 frames a second. */
        const val THUMB_FRAME_MS = 30L
        private const val DETACH_WAIT_MS = 300L
        private const val TAG = "SilverpointPreview"

        /** The preview's look table for a recipe: its tone and colour plus exposure and saturation, without the base contrast. */
        fun previewLook(params: app.silverpoint.recipes.RecipeParams): LookLut =
            RecipeLook(params, baseContrast = 0f, includeExposureAndSaturation = true).bake()
    }
}

/** Luminance histograms for the viewfinder. */
object Histogram {
    const val BINS = 64

    /** [count] RGBA8 pixels → [BINS] bins of Rec. 709 luminance of the encoded values. */
    fun ofRgba(buf: ByteBuffer, count: Int): IntArray {
        val bins = IntArray(BINS)
        for (i in 0 until count) {
            val r = buf.get(i * 4).toInt() and 0xFF
            val g = buf.get(i * 4 + 1).toInt() and 0xFF
            val b = buf.get(i * 4 + 2).toInt() and 0xFF
            val y = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            bins[minOf((y * BINS).toInt(), BINS - 1)]++
        }
        return bins
    }
}
