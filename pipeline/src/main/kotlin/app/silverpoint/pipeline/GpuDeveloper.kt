package app.silverpoint.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Develops RAW frames on the GPU with GL ES 3.1 compute passes: `prepare.comp` (black level, shading,
 * white balance), `lowres.comp` (quarter-resolution luminance), `blur.comp` (Clarity/Texture
 * surroundings) and `develop.comp` (everything else). All GL work happens on one dedicated thread
 * that owns an offscreen EGL context; public methods block until the result is ready, so call them
 * off the main thread.
 */
class GpuDeveloper(context: Context) : Closeable {

    /** How long each step took, for the debug overlay and the Diagnostics screen. */
    data class Timing(val uploadMs: Long, val prepareMs: Long, val developMs: Long, val readbackMs: Long) {
        val totalMs get() = uploadMs + prepareMs + developMs + readbackMs
    }

    /**
     * [lowLinear] is the quarter-resolution linear luminance before the highlight shoulder (sensor
     * orientation, [lowWidth] x [lowHeight]), read back only when asked for (Ultra HDR gain map).
     */
    class Result(val bitmap: Bitmap, val timing: Timing, val lowLinear: FloatArray? = null, val lowWidth: Int = 0, val lowHeight: Int = 0)

    private val assets = context.applicationContext.assets
    private val thread = HandlerThread("silverpoint-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var prepareProgram = 0
    private var lowresProgram = 0
    private var blurProgram = 0
    private var developProgram = 0
    private var framebuffer = 0

    // Textures are reused between shots of the same size.
    private var rawTex = 0
    private var cfaTex = 0
    private var outTex = 0
    private var shadingTex = 0
    private var lookTex = 0
    private var lowTex = 0
    private var tmpTex = 0
    private var blurSTex = 0
    private var blurLTex = 0
    private var weightsSTex = 0
    private var weightsLTex = 0
    private var texSize = 0 to 0
    private var outSize = 0 to 0

    /** GL_RENDERER string, for diagnostics. */
    var renderer: String = ""
        private set

    init {
        // A failed set-up (no GL ES 3.1, a shader that does not compile) must not leave its thread and
        // EGL context behind: the developer retries, and every Diagnostics run makes a new one.
        try {
            onGl { setUp() }
        } catch (t: Throwable) {
            runCatching { close() }
            throw t
        }
    }

    /**
     * Develops one frame. [raw] holds 16-bit little-endian samples (an `Image` plane buffer works
     * directly), [rowStrideBytes] bytes per row.
     */
    fun develop(raw: ByteBuffer, rowStrideBytes: Int, p: DevelopParams, readLowRes: Boolean = false): Result =
        onGl { run(raw, rowStrideBytes, p, readLowRes) }

    /** Convenience for tests and the self-test: samples in a ShortArray with [stride] samples per row. */
    fun develop(raw: ShortArray, stride: Int, p: DevelopParams, readLowRes: Boolean = false): Result {
        val buf = ByteBuffer.allocateDirect(raw.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(raw)
        return develop(buf, stride * 2, p, readLowRes)
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onGl {
            deleteTextures()
            for (prog in intArrayOf(prepareProgram, lowresProgram, blurProgram, developProgram)) {
                if (prog != 0) GLES31.glDeleteProgram(prog)
            }
            if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }

    private fun <T> onGl(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val done = CountDownLatch(1)
        var outcome: kotlin.Result<T>? = null
        // A closed developer's thread takes no more work; waiting for it would block forever.
        check(handler.post {
            outcome = runCatching(block)
            done.countDown()
        }) { "GPU developer is closed" }
        done.await()
        return outcome!!.getOrThrow()
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no EGL config with OpenGL ES 3"
        }
        eglContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "eglMakeCurrent failed" }

        renderer = "${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)}"
        val major = IntArray(1)
        val minor = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major, 0)
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor, 0)
        check(major[0] > 3 || (major[0] == 3 && minor[0] >= 1)) { "OpenGL ES 3.1 required, got ${major[0]}.${minor[0]}" }

        prepareProgram = computeProgram("shaders/prepare.comp")
        lowresProgram = computeProgram("shaders/lowres.comp")
        blurProgram = computeProgram("shaders/blur.comp")
        developProgram = computeProgram("shaders/develop.comp")
        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        framebuffer = fb[0]
    }

    private fun computeProgram(asset: String): Int {
        val source = ShaderSource.load(assets, asset)
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, source)
        GLES31.glCompileShader(shader)
        val ok = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(shader)
            GLES31.glDeleteShader(shader)
            throw IllegalStateException("$asset failed to compile: $log")
        }
        val program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, shader)
        GLES31.glLinkProgram(program)
        GLES31.glDeleteShader(shader)
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(program)
            GLES31.glDeleteProgram(program)
            throw IllegalStateException("$asset failed to link: $log")
        }
        return program
    }

    private fun run(raw: ByteBuffer, rowStrideBytes: Int, p: DevelopParams, readLowRes: Boolean): Result {
        val w = p.width
        val h = p.height
        val lw = LocalContrast.lowSize(w)
        val lh = LocalContrast.lowSize(h)
        ensureTextures(w, h, p.outWidth, p.outHeight)

        var t = SystemClock.elapsedRealtime()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowStrideBytes / 2)
        raw.position(0)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, raw)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        uploadShading(p.lensShading)
        uploadLook(p.look)
        val ws = LocalContrast.weights(LocalContrast.SMALL_SIGMA)
        val wl = LocalContrast.weights(LocalContrast.largeSigma(lw, lh))
        weightsSTex = uploadRow(weightsSTex, ws)
        weightsLTex = uploadRow(weightsLTex, wl)
        checkGl("upload")
        GLES30.glFinish()
        val uploadMs = SystemClock.elapsedRealtime() - t

        // Pass 1: prepare the CFA plane.
        t = SystemClock.elapsedRealtime()
        GLES31.glUseProgram(prepareProgram)
        bindTexture(0, rawTex)
        bindTexture(1, shadingTex)
        GLES31.glBindImageTexture(0, cfaTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
        uniform2i(prepareProgram, "uSize", w, h)
        GLES31.glUniform4f(loc(prepareProgram, "uBlack"), p.blackLevel[0], p.blackLevel[1], p.blackLevel[2], p.blackLevel[3])
        GLES31.glUniform1f(loc(prepareProgram, "uWhite"), p.whiteLevel)
        GLES31.glUniform3f(loc(prepareProgram, "uWb"), p.wbGains[0], p.wbGains[1], p.wbGains[2])
        uniformPattern(prepareProgram, p.cfa)
        GLES31.glUniform1i(loc(prepareProgram, "uHasShading"), if (p.lensShading != null) 1 else 0)
        uniform2i(prepareProgram, "uShadingSize", p.lensShading?.columns ?: 2, p.lensShading?.rows ?: 2)
        GLES31.glDispatchCompute(groups(w), groups(h), 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        checkGl("prepare")

        // Pass 2: quarter-resolution luminance.
        GLES31.glUseProgram(lowresProgram)
        GLES31.glBindImageTexture(0, cfaTex, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F)
        GLES31.glBindImageTexture(1, lowTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32F)
        uniform2i(lowresProgram, "uSize", w, h)
        uniform2i(lowresProgram, "uLowSize", lw, lh)
        uniformPattern(lowresProgram, p.cfa)
        uniformColour(lowresProgram, p)
        GLES31.glDispatchCompute(groups(lw), groups(lh), 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        checkGl("lowres")

        // Pass 3: blurred surroundings for Clarity and Texture.
        if (p.hasDetail) {
            blur(lowTex, tmpTex, weightsSTex, ws.size - 1, true, lw, lh)
            blur(tmpTex, blurSTex, weightsSTex, ws.size - 1, false, lw, lh)
            blur(lowTex, tmpTex, weightsLTex, wl.size - 1, true, lw, lh)
            blur(tmpTex, blurLTex, weightsLTex, wl.size - 1, false, lw, lh)
        }
        GLES30.glFinish()
        val prepareMs = SystemClock.elapsedRealtime() - t

        // Pass 4: demosaic, colour, look, rotate.
        t = SystemClock.elapsedRealtime()
        GLES31.glUseProgram(developProgram)
        GLES31.glBindImageTexture(0, cfaTex, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F)
        GLES31.glBindImageTexture(1, outTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lookTex)
        bindTexture(3, blurSTex)
        bindTexture(4, blurLTex)
        uniform2i(developProgram, "uSize", w, h)
        uniformPattern(developProgram, p.cfa)
        uniformColour(developProgram, p)
        GLES31.glUniform1i(loc(developProgram, "uLookSize"), p.look.size)
        GLES31.glUniform1i(loc(developProgram, "uRotation"), p.rotation)
        uniform2i(developProgram, "uLowSize", lw, lh)
        GLES31.glUniform1f(loc(developProgram, "uClarity"), p.clarity)
        GLES31.glUniform1f(loc(developProgram, "uTexture"), p.texture)
        GLES31.glUniform1f(loc(developProgram, "uVignette"), p.vignette)
        GLES31.glUniform1f(loc(developProgram, "uGrainAmount"), p.grainAmount)
        GLES31.glUniform1f(loc(developProgram, "uGrainCell"), p.grainCell)
        GLES31.glUniform1f(loc(developProgram, "uGrainRoughness"), p.grainRoughness)
        GLES31.glUniform1i(loc(developProgram, "uGrainSeed"), p.grainSeed)
        GLES31.glUniform1f(loc(developProgram, "uSharpen"), p.sharpen)
        GLES31.glUniform1f(loc(developProgram, "uChromaSmoothing"), p.chromaSmoothing)
        GLES31.glUniform1f(loc(developProgram, "uSharpenMask"), p.sharpenMask)
        GLES31.glUniform1f(loc(developProgram, "uVignetteStart"), p.vignetteStart)
        GLES31.glUniform1f(loc(developProgram, "uVignetteEnd"), p.vignetteEnd)
        GLES31.glDispatchCompute(groups(w), groups(h), 1)
        GLES31.glMemoryBarrier(GLES31.GL_ALL_BARRIER_BITS)
        checkGl("develop")
        GLES30.glFinish()
        val developMs = SystemClock.elapsedRealtime() - t

        // Read back.
        t = SystemClock.elapsedRealtime()
        val ow = p.outWidth
        val oh = p.outHeight
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "output framebuffer incomplete" }
        val pixels = ByteBuffer.allocateDirect(ow * oh * 4).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glReadPixels(0, 0, ow, oh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        var lowLinear: FloatArray? = null
        if (readLowRes) {
            // Reading float pixels needs EXT_color_buffer_float (Adreno has it); without it there is
            // simply no gain map.
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, lowTex, 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                val low = ByteBuffer.allocateDirect(lw * lh * 16).order(ByteOrder.nativeOrder())
                GLES30.glReadPixels(0, 0, lw, lh, GLES30.GL_RGBA, GLES30.GL_FLOAT, low)
                if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                    val f = low.asFloatBuffer()
                    lowLinear = FloatArray(lw * lh) { f.get(it * 4 + 1) }
                }
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        checkGl("readback")
        val bitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        pixels.position(0)
        bitmap.copyPixelsFromBuffer(pixels)
        val readbackMs = SystemClock.elapsedRealtime() - t
        return Result(bitmap, Timing(uploadMs, prepareMs, developMs, readbackMs), lowLinear, lw, lh)
    }

    private fun blur(src: Int, dst: Int, weights: Int, radius: Int, horizontal: Boolean, lw: Int, lh: Int) {
        GLES31.glUseProgram(blurProgram)
        bindTexture(0, src)
        bindTexture(1, weights)
        GLES31.glBindImageTexture(0, dst, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
        uniform2i(blurProgram, "uSize", lw, lh)
        GLES31.glUniform1i(loc(blurProgram, "uRadius"), radius)
        GLES31.glUniform1i(loc(blurProgram, "uHorizontal"), if (horizontal) 1 else 0)
        GLES31.glDispatchCompute(groups(lw), groups(lh), 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        checkGl("blur")
    }

    /** Colour matrix, exposure, saturation, vibrance and knee, shared by lowres.comp and develop.comp. */
    private fun uniformColour(program: Int, p: DevelopParams) {
        val m = p.cameraToSrgb
        GLES31.glUniform3f(loc(program, "uRow0"), m[0], m[1], m[2])
        GLES31.glUniform3f(loc(program, "uRow1"), m[3], m[4], m[5])
        GLES31.glUniform3f(loc(program, "uRow2"), m[6], m[7], m[8])
        GLES31.glUniform1f(loc(program, "uExposure"), p.exposureGain)
        GLES31.glUniform1f(loc(program, "uSaturation"), p.saturation)
        GLES31.glUniform1f(loc(program, "uVibrance"), p.vibrance)
        GLES31.glUniform1f(loc(program, "uKnee"), p.knee)
    }

    private fun ensureTextures(w: Int, h: Int, ow: Int, oh: Int) {
        if (texSize == w to h && outSize == ow to oh) return
        deleteTextures()
        rawTex = storage(GLES30.GL_R16UI, w, h)
        cfaTex = storage(GLES30.GL_R32F, w, h)
        outTex = storage(GLES30.GL_RGBA8, ow, oh)
        val lw = LocalContrast.lowSize(w)
        val lh = LocalContrast.lowSize(h)
        lowTex = storage(GLES30.GL_RGBA32F, lw, lh)
        tmpTex = storage(GLES30.GL_R32F, lw, lh)
        blurSTex = storage(GLES30.GL_R32F, lw, lh)
        blurLTex = storage(GLES30.GL_R32F, lw, lh)
        texSize = w to h
        outSize = ow to oh
        checkGl("allocate")
    }

    private fun deleteTextures() {
        val ids = intArrayOf(rawTex, cfaTex, outTex, shadingTex, lookTex, lowTex, tmpTex, blurSTex, blurLTex, weightsSTex, weightsLTex)
            .filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids, 0)
        rawTex = 0
        cfaTex = 0
        outTex = 0
        shadingTex = 0
        lookTex = 0
        lowTex = 0
        tmpTex = 0
        blurSTex = 0
        blurLTex = 0
        weightsSTex = 0
        weightsLTex = 0
        texSize = 0 to 0
        outSize = 0 to 0
    }

    private fun storage(format: Int, w: Int, h: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h)
        // Integer and float textures are only complete with NEAREST filtering; all reads use texelFetch anyway.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        return id[0]
    }

    private fun uploadShading(shading: LensShading?) {
        val cols = shading?.columns ?: 2
        val rows = shading?.rows ?: 2
        val gains = shading?.gains ?: FloatArray(cols * rows * 4) { 1f }
        if (shadingTex != 0) GLES30.glDeleteTextures(1, intArrayOf(shadingTex), 0)
        shadingTex = storage(GLES30.GL_RGBA32F, cols, rows)
        val buf = ByteBuffer.allocateDirect(gains.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(gains)
        buf.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, cols, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
    }

    /** The look table as an RGBA32F 3D texture (alpha unused). */
    private fun uploadLook(look: LookLut) {
        if (lookTex != 0) GLES30.glDeleteTextures(1, intArrayOf(lookTex), 0)
        val n = look.size
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        lookTex = id[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lookTex)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, GLES30.GL_RGBA32F, n, n, n)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
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
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, n, n, n, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
    }

    /** A one-row R32F texture holding [values]; replaces [old]. */
    private fun uploadRow(old: Int, values: FloatArray): Int {
        if (old != 0) GLES30.glDeleteTextures(1, intArrayOf(old), 0)
        val tex = storage(GLES30.GL_R32F, values.size, 1)
        val buf = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(values)
        buf.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, values.size, 1, GLES30.GL_RED, GLES30.GL_FLOAT, buf)
        return tex
    }

    private fun bindTexture(unit: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    }

    private fun uniformPattern(program: Int, cfa: Cfa) {
        val p = cfa.pattern
        GLES31.glUniform4i(loc(program, "uPattern"), p[0], p[1], p[2], p[3])
    }

    private fun uniform2i(program: Int, name: String, a: Int, b: Int) = GLES31.glUniform2i(loc(program, name), a, b)

    private fun loc(program: Int, name: String): Int = GLES31.glGetUniformLocation(program, name)

    private fun groups(n: Int) = (n + 15) / 16

    private fun checkGl(stage: String) {
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(err)} during $stage" }
    }
}
