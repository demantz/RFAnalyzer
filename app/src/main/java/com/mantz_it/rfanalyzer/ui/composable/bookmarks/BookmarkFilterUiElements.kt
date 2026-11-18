package com.mantz_it.rfanalyzer.ui.composable.bookmarks

import com.mantz_it.rfanalyzer.database.shown
import com.mantz_it.rfanalyzer.digiext.shown
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.database.BandFilter
import com.mantz_it.rfanalyzer.database.BookmarkList
import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.database.StationFilter
import kotlin.collections.plus
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.FrequencyChooser
import com.mantz_it.rfanalyzer.ui.composable.IconFrequencyFilter
import com.mantz_it.rfanalyzer.ui.composable.ScrollableColumnWithFadingEdge
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit

/**
 * <h1>RF Analyzer - BookmarkFilterUiElements</h1>
 *
 * Module:      BookmarkFilterUiElements.kt
 * Description: Composables for providing the filter bar which is shown in the bookmark manager
 *              (BookmarkManagerScreen)
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

sealed interface FilterSpec {
    val label: String

    data class FuzzySearchSpec(
        override val label: String,
        val helpText: String,
        val search: String,
        val onChange: (String) -> Unit
    ) : FilterSpec

    data class ListSpec<T>(
        override val label: String,
        val items: List<T>,
        val selected: Set<T>,
        val single: Boolean = false,
        val itemLabel: (T) -> String = { it.toString() },
        val icon: ImageVector,
        val onChange: (Set<T>) -> Unit
    ) : FilterSpec

    data class FrequencySpec(
        override val label: String,
        val min: Long,
        val max: Long,
        val icon: ImageVector,
        val onChange: (Long, Long) -> Unit
    ) : FilterSpec

    data class BooleanSpec(
        override val label: String,
        val active: Boolean,
        val icon: ImageVector,
        val onToggle: () -> Unit
    ) : FilterSpec

    /** Escape hatch for chips whose UX doesn't fit the other variants (e.g. a tri-state
     *  selector) - still participates in FilterChipRow's active-chips-float-to-front sorting
     *  and expand/collapse behavior, it just renders arbitrary content instead of a fixed shape. */
    data class CustomSpec(
        override val label: String,
        val active: Boolean,
        val content: @Composable () -> Unit,
    ) : FilterSpec
}

/**
 * One line of filter chips: scrolling sideways while collapsed, wrapping onto further lines when
 * [expanded].
 *
 * A [FilterSpec.FuzzySearchSpec] is the leading chip rather than a text field of its own, so an
 * idle search costs no extra row. Tapping it puts the text field in place of the chips, on the
 * same line - with the keyboard up that line is all the room there is - and leaving the field
 * turns it back into a chip showing the term. [trailingActions] stay at the end of the line,
 * outside the scrolling part.
 */
@Composable
fun FilterChipRow(
    specs: List<FilterSpec>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysExpanded: Boolean = false,
    /** Start in the search field, with the keyboard up - for a row the user opened to search. */
    initiallySearching: Boolean = false,
    trailingActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val fuzzySpec = specs.filterIsInstance<FilterSpec.FuzzySearchSpec>().firstOrNull()
    var searching by rememberSaveable { mutableStateOf(initiallySearching) }
    val scrollState = rememberScrollState()

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (searching && fuzzySpec != null) {
            SearchField(spec = fuzzySpec, onClose = { searching = false }, modifier = Modifier.weight(1f))
        } else {
            FlowRow(
                modifier = Modifier
                    .weight(1f)
                    .wrapContentHeight()
                    .then(if (!expanded) Modifier.fadingEndEdge(scrollState).horizontalScroll(scrollState) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                maxLines = if (!expanded) 1 else Int.MAX_VALUE
            ) {
                val isAnyFilterActive = specs.any { isFilterActive(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    // Expand button
                    if (!alwaysExpanded) {
                        IconButton(onClick = onToggleExpand) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expanded) "Collapse Filters" else "Expand Filters",
                                modifier = Modifier.graphicsLayer(rotationZ = if (expanded) 180f else 0f)
                            )
                        }
                    }

                    // Clear All Chip
                    if (isAnyFilterActive) {
                        IconButton(onClick = onClear) { Icon(Icons.Default.Clear, contentDescription = "Clear all filters") }
                    }
                }

                if (!alwaysExpanded || isAnyFilterActive) {
                    VerticalDivider(Modifier.height(32.dp).align(Alignment.CenterVertically).padding(end = 16.dp))
                }

                // Always leading, active or not: it is the one chip that is looked for by position.
                if (fuzzySpec != null) SearchChip(spec = fuzzySpec, onClick = { searching = true })

                // ACTIVE CHIPS FIRST
                specs.sortedBy { isFilterActive(it).not() }.forEach { spec ->
                    key(spec.label) {  // important for compose to keep track after sorting
                        when (spec) {
                            is FilterSpec.FuzzySearchSpec -> Unit  // the leading search chip above
                            is FilterSpec.ListSpec<*> -> {
                                val s = spec as FilterSpec.ListSpec<Any?>
                                ListFilterChip(
                                    label = s.label,
                                    items = s.items,
                                    selectedItems = s.selected,
                                    itemLabel = s.itemLabel,
                                    onSelectionChanged = s.onChange,
                                    singleSelection = s.single,
                                    customIcon = s.icon
                                )
                            }
                            is FilterSpec.FrequencySpec ->
                                FrequencyRangeFilterChip(
                                    label = spec.label,
                                    startFrequency = spec.min,
                                    endFrequency = spec.max,
                                    onRangeChanged = spec.onChange,
                                    customIcon = spec.icon
                                )
                            is FilterSpec.BooleanSpec ->
                                FilterChip(
                                    selected = spec.active,
                                    onClick = spec.onToggle,
                                    label = { Text(spec.label) },
                                    leadingIcon = { Icon(spec.icon, null) }
                                )
                            is FilterSpec.CustomSpec -> spec.content()
                        }
                    }
                }
            }
        }
        trailingActions?.let { actions ->
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), content = actions)
        }
    }
}

/** The search as a chip: its label while idle, the term (with a clear button) while one is set. */
@Composable
private fun SearchChip(spec: FilterSpec.FuzzySearchSpec, onClick: () -> Unit) {
    val active = spec.search.isNotBlank()
    FilterChip(
        selected = active,
        onClick = onClick,
        label = {
            Text(
                text = if (active) spec.search else spec.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (active) {
            {
                Icon(
                    Icons.Default.Clear,
                    contentDescription = "Clear search",
                    modifier = Modifier.size(18.dp).clickable { spec.onChange("") },
                )
            }
        } else null,
    )
}

/**
 * The search text field in the chips' place. Chip-height rather than a full `OutlinedTextField`,
 * so the line doesn't grow when it appears. It takes the focus (and with it the keyboard) as soon
 * as it is shown; the back arrow, the keyboard's search key, the system back gesture (which is also
 * what dismisses the keyboard) and losing the focus all return to the chips, keeping the term.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchField(spec: FilterSpec.FuzzySearchSpec, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BackHandler(onBack = onClose)
    // The field starts out unfocused, so only a focus it actually had counts as lost.
    var hadFocus by remember { mutableStateOf(false) }

    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .height(40.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
        }
        BasicTextField(
            value = spec.search,
            onValueChange = spec.onChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onClose() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                // While the keyboard is up it gets the back press first, and BackHandler none.
                // Not consumed, so the keyboard still closes.
                .onPreInterceptKeyBeforeSoftKeyboard { event ->
                    if (event.key == Key.Back && event.type == KeyEventType.KeyUp) onClose()
                    false
                }
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true else if (hadFocus) onClose()
                },
            decorationBox = { innerTextField ->
                if (spec.search.isEmpty()) {
                    Text(
                        spec.helpText,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            },
        )
        if (spec.search.isNotEmpty()) {
            IconButton(onClick = { spec.onChange("") }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Clear, contentDescription = "Clear search")
            }
        }
    }
}

/** Fades the trailing edge out while there is more to scroll to, as the hint that there is. */
private fun Modifier.fadingEndEdge(scrollState: ScrollState, width: Dp = 24.dp): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        if (scrollState.canScrollForward) {
            val fade = width.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - fade,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

private fun isFilterActive(spec: FilterSpec): Boolean = when (spec) {
    is FilterSpec.FuzzySearchSpec -> spec.search.isNotBlank()
    is FilterSpec.ListSpec<*> -> {
        if(spec.single) spec.selected.first() != spec.items.first()
        else spec.selected.isNotEmpty()
    }
    is FilterSpec.FrequencySpec -> spec.min != 0L || spec.max != 0L
    is FilterSpec.BooleanSpec -> spec.active
    is FilterSpec.CustomSpec -> spec.active
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ListFilterChip(
    label: String,
    items: List<T>,
    selectedItems: Set<T>,
    onSelectionChanged: (Set<T>) -> Unit,
    modifier: Modifier = Modifier,
    itemLabel: (T) -> String = { it.toString() },
    singleSelection: Boolean = false,
    customIcon: ImageVector = Icons.Default.Check,
) {
    var showSheet by remember { mutableStateOf(false) }
    val isActive = if (singleSelection) selectedItems.firstOrNull() != items.firstOrNull() else selectedItems.isNotEmpty()
    var tempSelection by remember(selectedItems) { mutableStateOf(selectedItems) }

    val displayLabel = when {
        selectedItems.isEmpty() -> label
        selectedItems.size < 4 -> selectedItems.joinToString(",") { itemLabel(it) }
        else -> "${selectedItems.size} selected"
    }

    FilterChip(
        selected = isActive,
        onClick = { showSheet = true },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayLabel)
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        leadingIcon = { Icon(customIcon, contentDescription = null) },
        modifier = modifier
    )

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                onSelectionChanged(tempSelection)
                showSheet = false
            }
        ) {
            ScrollableColumnWithFadingEdge(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                items.forEach { item ->
                    val checked = tempSelection.contains(item)
                    fun onToggle() {
                        tempSelection = if (singleSelection) {
                            setOf(item)
                        } else {
                            if (checked) tempSelection.minus(item)
                            else tempSelection.plus(item)
                        }
                        if (singleSelection) {
                            onSelectionChanged(tempSelection)
                            showSheet = false
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle() }
                            .padding(vertical = 8.dp),
                    ) {
                        if(singleSelection)
                            RadioButton(selected = checked, onClick = { onToggle() })
                        else
                            Checkbox(checked = checked, onCheckedChange = { onToggle() })
                        Text(itemLabel(item), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrequencyRangeFilterChip(
    label: String,
    startFrequency: Long,
    endFrequency: Long,
    onRangeChanged: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
    customIcon: ImageVector = Icons.Default.Check
) {
    var showSheet by remember { mutableStateOf(false) }
    val isActive = startFrequency != 0L || endFrequency != 0L
    var start by remember(startFrequency) { mutableLongStateOf(startFrequency) }
    var end by remember(endFrequency) { mutableLongStateOf(endFrequency) }


    val displayLabel =
        if (startFrequency != 0L && endFrequency != 0L) "${startFrequency.asStringWithUnit("Hz")}-${endFrequency.asStringWithUnit("Hz")}"
        else if (startFrequency != 0L) "≥${startFrequency.asStringWithUnit("Hz")}"
        else if (endFrequency != 0L) "≤${endFrequency.asStringWithUnit("Hz")}"
        else label

    FilterChip(
        selected = isActive,
        onClick = { showSheet = true },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayLabel)
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        leadingIcon = { Icon(customIcon, contentDescription = null) },
        modifier = modifier
    )

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                onRangeChanged(start, end)
                showSheet = false
            }
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Select Frequency Range", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                FrequencyChooser(
                    label = "Start Frequency",
                    currentFrequency = start,
                    onFrequencyChanged = { start = it },
                    maxFrequency = if(end == 0L) 999_999_999_999L else end,
                    liveUpdate = true,
                    unit = "Hz",
                    background = MaterialTheme.colorScheme.surfaceContainerLow
                )
                FrequencyChooser(
                    label = "End Frequency",
                    currentFrequency = end,
                    onFrequencyChanged = { end = it },
                    minFrequency = start,
                    liveUpdate = true,
                    unit = "Hz",
                    background = MaterialTheme.colorScheme.surfaceContainerLow
                )
            }
        }
    }
}

// Helper to combine station bookmarkLists and SourceProviders into a single filter
sealed interface StationGroup {
    data class BookmarkListGroup(val bookmarkList: BookmarkList) : StationGroup
    data class SourceGroup(val provider: SourceProvider) : StationGroup
}

@Composable
fun StationsFilterRow(
    filter: StationFilter,
    allBookmarkLists: List<BookmarkList>,
    expanded: Boolean,
    onExpandChange: () -> Unit,
    onFilterChanged: (StationFilter) -> Unit,
    modifier: Modifier = Modifier,
    alwaysExpanded: Boolean = false
) {
    val specs = listOf(
        FilterSpec.FuzzySearchSpec(
            label = "Search",
            helpText = "Search in Name, Notes, Call, …",
            search = filter.search,
            onChange = { onFilterChanged(filter.copy(search = it)) }
        ),
        FilterSpec.ListSpec(
            label = "Lists",
            items = allBookmarkLists.map { cat -> StationGroup.BookmarkListGroup(cat) } +
                    SourceProvider.entries.filter { it != SourceProvider.BOOKMARK && it.shown }.map { sp -> StationGroup.SourceGroup(sp) },
            selected = buildSet {
                filter.bookmarkLists.mapNotNull { catId -> allBookmarkLists.find { it.id == catId } }.forEach { add(StationGroup.BookmarkListGroup(it)) }
                filter.sources
                    .filter { it != SourceProvider.BOOKMARK }
                    .forEach { add(StationGroup.SourceGroup(it)) }
            },
            itemLabel = {
                when (it) {
                    is StationGroup.BookmarkListGroup -> it.bookmarkList.name
                    is StationGroup.SourceGroup -> it.provider.displayName
                }
            },
            icon = Icons.Default.List,
            onChange = { new ->
                val newBookmarkLists = new.filterIsInstance<StationGroup.BookmarkListGroup>().map { it.bookmarkList }.toSet()
                val newSourcesTmp = new.filterIsInstance<StationGroup.SourceGroup>().map { it.provider }.toSet()
                // Add or remove BOOKMARK Source depending if any bookmarkList was selected:
                val newSources = if (newBookmarkLists.isEmpty()) newSourcesTmp - SourceProvider.BOOKMARK else newSourcesTmp + SourceProvider.BOOKMARK
                onFilterChanged(filter.copy(bookmarkLists = newBookmarkLists.map { it.id }.toSet(), sources = newSources))
            }
        ),
        FilterSpec.FrequencySpec(
            label = "Frequency",
            min = filter.minFrequency,
            max = filter.maxFrequency,
            icon = IconFrequencyFilter,
            onChange = { min, max -> onFilterChanged(filter.copy(minFrequency = min, maxFrequency = max)) }
        ),
        FilterSpec.ListSpec(
            label = "Mode",
            items = DemodulationMode.entries.filter { it != DemodulationMode.OFF && it.shown() },
            selected = filter.mode,
            itemLabel = { it.displayName },
            icon = Icons.Default.Tune,
            onChange = { onFilterChanged(filter.copy(mode = it)) }
        ),
        FilterSpec.BooleanSpec(
            label = "Favorites",
            active = filter.onlyFavorites,
            icon = Icons.Default.Favorite,
            onToggle = { onFilterChanged(filter.copy(onlyFavorites = !filter.onlyFavorites)) }
        ),
        FilterSpec.ListSpec(
            label = "Schedule",
            items = listOf("All", "Currently On-Air"),
            selected = setOf(if (filter.onlyOnAirNow) "Currently On-Air" else "All"),
            single = true,
            icon = Icons.Default.AccessTime,
            onChange = {
                val selectedItem = it.firstOrNull()
                val onAir = selectedItem != null && selectedItem == "Currently On-Air"
                onFilterChanged(filter.copy(onlyOnAirNow = onAir))
            }
        )
    )

    FilterChipRow(
        specs = specs,
        expanded = expanded,
        onToggleExpand = onExpandChange,
        onClear = { onFilterChanged(StationFilter()) },
        modifier = modifier,
        alwaysExpanded = alwaysExpanded
    )
}

@Composable
fun BandFilterRow(
    filter: BandFilter,
    allBookmarkLists: List<BookmarkList>,
    expanded: Boolean,
    onExpandChange: () -> Unit,
    onFilterChanged: (BandFilter) -> Unit,
    modifier: Modifier = Modifier,
    alwaysExpanded: Boolean = false
) {
    val specs = listOf(
        FilterSpec.FuzzySearchSpec(
            label = "Search",
            helpText = "Search in Name, Notes, …",
            search = filter.search,
            onChange = { onFilterChanged(filter.copy(search = it)) }
        ),
        FilterSpec.ListSpec(
            label = "Lists",
            items = allBookmarkLists,
            selected = filter.bookmarkLists.mapNotNull { catId -> allBookmarkLists.find { it.id == catId } }.toSet(),
            itemLabel = { it.name },
            icon = Icons.Default.List,
            onChange = { onFilterChanged(filter.copy(bookmarkLists = it.map{ it.id }.toSet())) }
        ),
        FilterSpec.FrequencySpec(
            label = "Frequency",
            min = filter.minFrequency,
            max = filter.maxFrequency,
            icon = IconFrequencyFilter,
            onChange = { min, max -> onFilterChanged(filter.copy(minFrequency = min, maxFrequency = max)) }
        ),
        FilterSpec.BooleanSpec(
            label = "Favorites",
            active = filter.onlyFavorites,
            icon = Icons.Default.Favorite,
            onToggle = { onFilterChanged(filter.copy(onlyFavorites = !filter.onlyFavorites)) }
        )
    )
    FilterChipRow(
        specs = specs,
        expanded = expanded,
        onToggleExpand = onExpandChange,
        onClear = { onFilterChanged(BandFilter()) },
        modifier = modifier,
        alwaysExpanded = alwaysExpanded
    )
}
