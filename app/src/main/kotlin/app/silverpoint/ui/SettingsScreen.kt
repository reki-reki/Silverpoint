package app.silverpoint.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.silverpoint.CameraViewModel
import app.silverpoint.MergeMode
import app.silverpoint.Settings

/**
 * Settings, One UI style: a large title that scrolls away and grouped cards. [livePreview] is
 * whether the recipe preview is on (it can be off because it failed, see [previewFailed]).
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    version: String,
    livePreview: Boolean,
    previewFailed: Boolean,
    onLivePreview: (Boolean) -> Unit,
    onOverlaysChanged: () -> Unit,
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
    onIntro: () -> Unit,
    /** How the last photo was developed and how long it took (the camera screen does not show it). */
    lastPhoto: String? = null,
) {
    val s = settings
    OneUiPage(title = "Settings", onBack = onBack, subtitle = "Silverpoint $version") {
        item(key = "viewfinder") {
            SectionHeader("Viewfinder")
            OneUiCard {
                SettingRow(
                    "Live recipe preview",
                    if (previewFailed) "Failed on this phone and switched off. Turn on to try again."
                    else "Shows the recipe on the viewfinder. Close to, not exactly, the saved photo.",
                    checked = livePreview,
                ) { onLivePreview(!livePreview) }
                CardDivider()
                SettingRow(
                    "Live look previews",
                    "The look selector shows your scene live through every look. Off: the looks are shown on a sample picture, which uses less battery.",
                    checked = s.liveLookPreviews,
                ) { s.liveLookPreviews = !s.liveLookPreviews }
                CardDivider()
                SettingRow("Histogram", checked = s.histogram) { s.histogram = !s.histogram; onOverlaysChanged() }
                CardDivider()
                SettingRow("Grid", "Rule-of-thirds lines.", checked = s.grid) { s.grid = !s.grid }
                CardDivider()
                SettingRow("Level", "A horizon line that turns green when the phone is level.", checked = s.level) { s.level = !s.level }
                CardDivider()
                SettingRow("Zebras", "Stripes over areas about to clip to white.", checked = s.zebras) { s.zebras = !s.zebras; onOverlaysChanged() }
                CardDivider()
                SettingRow("Focus peaking", "Outlines what is sharp; best with manual focus.", checked = s.peaking) { s.peaking = !s.peaking; onOverlaysChanged() }
            }
        }
        item(key = "capture") {
            SectionHeader("Capture")
            OneUiCard {
                SettingRow("Multi-frame merge", s.merge.label, summaryIsValue = true)
                Text(
                    "Takes ${CameraViewModel.MERGE_FRAMES} RAW frames and merges them for about 1.3 stops less noise. Auto does it in low light.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Sl.TextDim,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 0.dp, bottom = 6.dp),
                )
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                    for (m in MergeMode.entries) {
                        Row(
                            Modifier.fillMaxWidth().clickable { s.merge = m }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = s.merge == m,
                                onClick = { s.merge = m },
                                colors = RadioButtonDefaults.colors(selectedColor = Sl.Accent, unselectedColor = Sl.TextFaint),
                            )
                            Text(m.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                CardDivider()
                SettingRow("Shutter sound", checked = s.shutterSound) { s.shutterSound = !s.shutterSound }
                CardDivider()
                SettingRow(
                    "Haptics",
                    "A tick for every dial stop, a tap on the shutter, a confirmation when the photo is taken. Works even with the phone's touch vibration off.",
                    checked = s.haptics,
                ) { s.haptics = !s.haptics }
            }
        }
        item(key = "output") {
            SectionHeader("Output")
            OneUiCard {
                SettingRow("Ultra HDR", "Brighter highlights on HDR screens. Other screens show the normal photo.", checked = s.ultraHdr) { s.ultraHdr = !s.ultraHdr }
                CardDivider()
                SettingRow("Also save the DNG", "Developer option: the RAW frame (about 25 MB) next to each photo.", checked = s.saveDng) { s.saveDng = !s.saveDng }
            }
        }
        item(key = "about") {
            SectionHeader("About")
            OneUiCard {
                SettingRow("Version", value = version)
                CardDivider()
                lastPhoto?.let {
                    SettingRow("Last photo", it)
                    CardDivider()
                }
                SettingRow("Diagnostics", "Check the camera and the GPU developer; save a report.", chevron = true, onClick = onDiagnostics)
                CardDivider()
                SettingRow("Show the introduction again", chevron = true, onClick = onIntro)
            }
            Text(
                "Apache License 2.0. Recipe names refer to products of their owners; Silverpoint is not affiliated with them.",
                style = MaterialTheme.typography.bodyMedium,
                color = Sl.TextFaint,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 14.dp),
            )
        }
    }
}
