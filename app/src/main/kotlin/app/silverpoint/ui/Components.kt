package app.silverpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A One UI page: a large title in the top third of the screen (the "viewing area") that scrolls
 * away, and a small title bar that fades in once it has. [content] fills the list below the title.
 */
@Composable
fun OneUiPage(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Sl.Black)) {
        val headerHeight = (maxHeight * 0.3f).coerceIn(180.dp, 300.dp)
        val headerPx = with(LocalDensity.current) { headerHeight.toPx() }
        val barPx = with(LocalDensity.current) { 56.dp.toPx() }
        // 0 while the large title shows, 1 once it has scrolled under the bar.
        val collapsed by remember(headerPx) {
            derivedStateOf {
                if (list.firstVisibleItemIndex > 0) 1f
                else ((list.firstVisibleItemScrollOffset - (headerPx - 2.2f * barPx)) / barPx).coerceIn(0f, 1f)
            }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().statusBarsPadding().height(headerHeight)) {
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 28.dp, bottom = 28.dp).alpha(1f - collapsed)) {
                        Text(title, style = MaterialTheme.typography.displaySmall)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Sl.TextDim, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            }
            content()
        }
        Row(
            Modifier.fillMaxWidth().background(Sl.Black.copy(alpha = collapsed)).statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Back, Sl.Text, size = 22.dp)
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 6.dp).alpha(collapsed),
            )
        }
    }
}

/** Small grey header above a card. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Sl.TextDim,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 8.dp),
    )
}

/** One UI's grouped list: rows on a rounded dark card. */
@Composable
fun OneUiCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Sl.Card),
        content = content,
    )
}

/** Hairline between rows of a card, inset like One UI's. */
@Composable
fun CardDivider() {
    Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(0.6.dp).background(Sl.Divider))
}

/**
 * A row on a card: title, optional summary, and on the right a switch ([checked] not null), a
 * value, or a chevron when [onClick] opens something.
 */
@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    checked: Boolean? = null,
    value: String? = null,
    summaryIsValue: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    // Switches feel on or off; other rows a light tap.
    val click = if (enabled && onClick != null) {
        { if (checked != null) haptics.toggle(!checked) else haptics.tap(); onClick() }
    } else null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
            .padding(horizontal = 22.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (summaryIsValue) Sl.Accent else Sl.TextDim,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        when {
            checked != null -> {
                Spacer(Modifier.size(14.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = if (click != null) { _ -> click() } else null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Sl.Accent,
                        checkedBorderColor = Sl.Accent,
                        uncheckedThumbColor = Color(0xFFC9C9CF),
                        uncheckedTrackColor = Color(0xFF3A3A3F),
                        uncheckedBorderColor = Color(0xFF3A3A3F),
                    ),
                )
            }
            value != null -> Text(value, style = MaterialTheme.typography.bodyMedium, color = Sl.TextDim, modifier = Modifier.padding(start = 14.dp))
            chevron -> GlyphIcon(Glyph.Forward, Sl.TextFaint, Modifier.padding(start = 10.dp), size = 18.dp)
        }
    }
}

/** A filled accent button, the One UI way: a pill with dark text. */
@Composable
fun AccentPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).background(if (enabled) Sl.Accent else Sl.CardHigh)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Sl.OnAccent else Sl.TextFaint)
    }
}

/** A quiet button: a dark grey pill with light text. */
@Composable
fun GhostPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).background(Sl.CardHigh)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Sl.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Evenly spaced row of [content] with One UI's 12 dp gutters. */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
