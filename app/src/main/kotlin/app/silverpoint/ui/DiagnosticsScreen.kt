package app.silverpoint.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.silverpoint.DiagnosticsState
import app.silverpoint.DiagnosticsViewModel
import app.silverpoint.ReportExporter

@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) vm.start()
    }

    DiagnosticsLayout(
        state = vm.state,
        verdict = vm.verdict,
        report = if (vm.reportText.isNotEmpty()) vm.reportText else vm.log.joinToString("\n"),
        permissionDenied = permissionDenied,
        onBack = onBack,
        onRun = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.start() else permission.launch(Manifest.permission.CAMERA)
        },
        onSave = {
            val path = vm.run?.let { ReportExporter.saveJson(context, vm.reportJson, it) }
            toast(context, if (path != null) "Saved $path" else "Could not save the report")
        },
        onShare = { ReportExporter.shareText(context, vm.reportText) },
        onCopy = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Silverpoint probe report", vm.reportText))
            toast(context, "Copied")
        },
    )
}

/** The Diagnostics screen's layout, without the view model (screenshot tests use it directly). */
@Composable
fun DiagnosticsLayout(
    state: DiagnosticsState,
    verdict: String?,
    report: String,
    permissionDenied: Boolean,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Sl.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Back, Sl.Text, size = 22.dp)
            }
        }
        Text("Diagnostics", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 6.dp))
        Text(
            "Checks which RAW sizes, lenses and controls this phone gives apps, then compares the GPU developer with the CPU " +
                "reference. About a minute: point the phone at a well-lit scene and hold it still. Each run gets its own folder, " +
                "Download/Silverpoint/diag_<run ID>, with one DNG per lens (about 25 MB each). Nothing goes to your Gallery.",
            style = MaterialTheme.typography.bodyMedium,
            color = Sl.TextDim,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(18.dp))
        AccentPill(
            when (state) {
                DiagnosticsState.Running -> "Running…"
                DiagnosticsState.Idle -> "Run diagnostics"
                else -> "Run again"
            },
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            enabled = state != DiagnosticsState.Running,
            onClick = onRun,
        )
        if (permissionDenied) {
            Text("Camera permission is needed to probe the camera.", color = Sl.Danger, modifier = Modifier.padding(start = 28.dp, top = 10.dp))
        }
        verdict?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = Sl.Text,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp)).background(Sl.AccentDeep).padding(18.dp),
            )
        }
        if (state == DiagnosticsState.Done) {
            Spacer(Modifier.height(12.dp))
            ButtonRow {
                GhostPill("Save JSON", Modifier.weight(1f), onClick = onSave)
                GhostPill("Share", Modifier.weight(1f), onClick = onShare)
                GhostPill("Copy", Modifier.weight(1f), onClick = onCopy)
            }
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer(
            Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(22.dp)).background(Sl.Card),
        ) {
            Text(
                text = report.ifEmpty { "The report appears here." },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = if (report.isEmpty()) Sl.TextFaint else Sl.TextDim,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
