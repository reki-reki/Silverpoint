package app.silverpoint.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The look sheet's height, as a fraction of the space a sheet may take: the camera stays visible above it. */
private const val SHEET_HEIGHT = 0.86f

/** The selector's tabs: what kind of look you are after. */
enum class LookFilter(val label: String) {
    Starred("Starred"),
    All("All"),
    Core("Core"),
    Community("Community"),
    Film("Film stocks"),
    Mono("B&W"),
    ;

    fun matches(r: RecipeUi, favourites: Set<String>): Boolean = when (this) {
        Starred -> r.name in favourites
        All -> true
        Core -> r.set == "core"
        Community -> r.set == "popular" && r.group != "Film"
        Film -> r.group == "Film"
        Mono -> r.mono
    }
}

/** The look selector as a sheet over the camera. Picking a look closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(
    recipes: List<RecipeUi>,
    selected: String?,
    favourites: Set<String>,
    thumbs: Map<String, ImageBitmap>,
    onSelect: (String) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Sl.Card,
        contentColor = Sl.Text,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Sl.TextFaint) },
    ) {
        // A fixed height, whatever the tab holds: a sheet sized to its contents jumped up and down
        // with every tab, moving the tabs away from the finger that had just tapped one.
        LookSelector(recipes, selected, favourites, thumbs, onSelect, onToggleFavourite, modifier = Modifier.fillMaxHeight(SHEET_HEIGHT))
    }
}

/**
 * The selector's contents (separate so screenshot tests can show it): the chosen look and what it
 * is for, tabs by kind, and every look as a live thumbnail of the scene in a grid. Tap a look to use
 * it; the star (or a long press) keeps it in the quick row under the viewfinder.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LookSelector(
    recipes: List<RecipeUi>,
    selected: String?,
    favourites: Set<String>,
    thumbs: Map<String, ImageBitmap>,
    onSelect: (String) -> Unit,
    onToggleFavourite: (String) -> Unit,
    /** Must give the selector a height (the grid fills what the header and tabs leave). */
    modifier: Modifier = Modifier,
    initialFilter: LookFilter = LookFilter.All,
) {
    val haptics = rememberHaptics()
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    val filters = LookFilter.entries.filter { f -> recipes.any { f.matches(it, favourites) } || f == LookFilter.Starred }
    val shown = recipes.filter { filter.matches(it, favourites) }
    val current = recipes.firstOrNull { it.name == selected }
    val grid = rememberLazyGridState()
    // A new tab starts at its top, not at the scroll position of the previous one.
    LaunchedEffect(filter) { grid.scrollToItem(0) }

    Column(modifier.fillMaxWidth()) {
        // What is on now, in words: the grid shows what looks do, this says what this one is for.
        current?.let { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LookCircle(r, thumbs[r.name], 54.dp, ring = Sl.Accent, ringWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Sl.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.look, color = Sl.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (r.bestFor.isNotEmpty()) Text("Best for: ${r.bestFor}", color = Sl.TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filters, key = { it.name }) { f ->
                val on = f == filter
                val n = recipes.count { f.matches(it, favourites) }
                Text(
                    "${f.label}  $n",
                    color = if (on) Color.Black else Sl.Text,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).background(if (on) Color(0xF2FFFFFF) else Sl.CardHigh)
                        .clickable { if (!on) { haptics.select(); filter = f } }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (shown.isEmpty()) {
            Text(
                "Tap the star on any look to keep it here and in the quick row under the viewfinder.",
                color = Sl.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = grid,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shown, key = { it.name }) { r ->
                LookCard(
                    r, thumbs[r.name], selected = r.name == selected, starred = r.name in favourites,
                    onClick = { haptics.select(); onSelect(r.name) },
                    onStar = { haptics.toggle(r.name !in favourites); onToggleFavourite(r.name) },
                )
            }
        }
    }
}

/** One look in the grid: the scene through it (3:4, like the photo), its name, and its star. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LookCard(r: RecipeUi, thumb: ImageBitmap?, selected: Boolean, starred: Boolean, onClick: () -> Unit, onStar: () -> Unit) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onStar),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(16.dp))
                .border(if (selected) 2.5.dp else 0.dp, if (selected) Sl.Accent else Color.Transparent, RoundedCornerShape(16.dp)),
        ) {
            LookPicture(r.name, thumb, r.swatch, radius = 16.dp, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp).clip(CircleShape).background(Sl.Scrim).clickable(onClick = onStar),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(if (starred) Glyph.Star else Glyph.StarOutline, if (starred) Sl.Accent else Color(0xCCFFFFFF), size = 16.dp)
            }
        }
        Text(
            r.name,
            color = if (selected) Sl.Accent else Sl.Text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}
