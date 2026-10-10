package app.silverpoint

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.silverpoint.camera.DiagnosticsRun
import app.silverpoint.camera.MiniJson
import app.silverpoint.camera.ProbeRunner
import app.silverpoint.camera.ReportText
import app.silverpoint.pipeline.GpuDeveloper
import app.silverpoint.pipeline.GpuSelfTest
import app.silverpoint.recipes.RecipeParams
import app.silverpoint.recipes.RecipeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DiagnosticsState { Idle, Running, Done, Failed }

/**
 * Runs the camera capability probe and the GPU pipeline self-test, and keeps the report so it
 * survives screen changes.
 */
class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf(DiagnosticsState.Idle)
        private set
    var verdict by mutableStateOf<String?>(null)
        private set
    var reportText by mutableStateOf("")
        private set
    var reportJson by mutableStateOf("")
        private set
    val log = mutableStateListOf<String>()

    /** The current (or last) run; its ID names the folder, the report and every DNG. */
    var run by mutableStateOf<DiagnosticsRun?>(null)
        private set

    fun start() {
        if (state == DiagnosticsState.Running) return
        state = DiagnosticsState.Running
        verdict = null
        reportText = ""
        reportJson = ""
        log.clear()
        val current = DiagnosticsRun.startingAt(System.currentTimeMillis())
        run = current
        viewModelScope.launch(Dispatchers.IO) {
            try {
                postLog("Run ${current.runId}: files go to ${current.folder}")
                val report = ProbeRunner(getApplication(), BuildConfig.VERSION_NAME, current).run { line -> postLog(line) }.toMutableMap()
                postLog("GPU self-test: developing test frames on the GPU and the CPU")
                val selfTest = runSelfTest()
                report["gpuSelfTest"] = selfTest
                val summary = report["summary"] as? Map<*, *>
                val v2 = report["summaryV2"] as? Map<*, *>
                val lines = listOfNotNull(
                    summary?.get("verdict") as? String,
                    v2?.get("manualControlHonoured")?.let { "Manual ISO/shutter honoured: $it" },
                    (v2?.get("lensSwitch") as? Map<*, *>)?.get("firstFrameMs")?.let { "Lens switch to first frame: $it ms" },
                    "GPU self-test: ${selfTest["verdict"]}",
                    (v2?.get("dngFilesWritten") as? List<*>)?.let { "Run ${current.runId}: ${it.size} DNGs in ${current.folder}" },
                )
                val text = ReportText.render(report)
                val json = MiniJson.encode(report)
                withContext(Dispatchers.Main) {
                    verdict = lines.joinToString("\n")
                    reportText = text
                    reportJson = json
                    state = DiagnosticsState.Done
                }
            } catch (t: Throwable) {
                postLog("Diagnostics crashed: ${t.javaClass.simpleName}: ${t.message}")
                withContext(Dispatchers.Main) {
                    reportText = "Diagnostics crashed: ${t.stackTraceToString()}"
                    state = DiagnosticsState.Failed
                }
            }
        }
    }

    private fun runSelfTest(): Map<String, Any?> = try {
        val look = RecipeStore.load(getApplication()).firstOrNull { it.name == Settings.DEFAULT_RECIPE }?.params ?: RecipeParams()
        GpuDeveloper(getApplication()).use { gpu ->
            val cases = GpuSelfTest.run(gpu, look)
            cases.forEach { postLog("    ${it.name}: " + if (it.passed) "pass (max diff ${it.maxDiff})" else "FAIL ${it.error ?: "max diff ${it.maxDiff}"}") }
            linkedMapOf(
                "verdict" to if (cases.all { it.passed }) "PASS" else "FAIL",
                "renderer" to gpu.renderer,
                "tolerance" to GpuSelfTest.TOLERANCE,
                "cases" to cases.map { it.toMap() },
            )
        }
    } catch (t: Throwable) {
        postLog("    GPU unavailable: ${t.message}")
        linkedMapOf("verdict" to "FAIL (GPU unavailable)", "error" to "${t.javaClass.simpleName}: ${t.message}")
    }

    private fun postLog(line: String) {
        viewModelScope.launch(Dispatchers.Main) { log.add(line) }
    }
}
