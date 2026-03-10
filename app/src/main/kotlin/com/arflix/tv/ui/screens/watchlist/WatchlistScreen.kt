package com.arflix.tv.ui.screens.watchlist

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.components.LoadingIndicator
import com.arflix.tv.ui.components.MediaCard
import com.arflix.tv.ui.components.Sidebar
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.Toast
import com.arflix.tv.ui.components.ToastType as ComponentToastType
import com.arflix.tv.ui.components.TopBarClock
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundDark
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watchlist screen — Movies and TV Shows in separate horizontal rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel = hiltViewModel(),
    currentProfile: com.arflix.tv.data.model.Profile? = null,
    onNavigateToDetails: (MediaType, Int) -> Unit = { _, _ -> },
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToTv: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp
    val isCompactHeight = screenHeight <= 780
    val itemWidth = when {
        screenHeight <= 600 -> 170.dp
        screenHeight <= 700 -> 188.dp
        else -> 210.dp
    }

    var isSidebarFocused by remember { mutableStateOf(false) }
    val hasProfile = currentProfile != null
    val maxSidebarIndex = if (hasProfile) SidebarItem.entries.size else SidebarItem.entries.size - 1
    var sidebarFocusIndex by remember { mutableIntStateOf(if (hasProfile) 3 else 2) } // WATCHLIST
    val rootFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Row-based focus state (0 = Movies, 1 = TV Shows)
    var currentRowIndex by remember { mutableIntStateOf(0) }
    var movieItemIndex by remember { mutableIntStateOf(0) }
    var tvItemIndex by remember { mutableIntStateOf(0) }
    val movieRowState = rememberTvLazyListState()
    val tvRowState = rememberTvLazyListState()
    // Filter toggle focus state
    var isFilterFocused by remember { mutableStateOf(false) }

    // Determine which rows are visible
    val hasMovies = uiState.movieItems.isNotEmpty()
    val hasTv = uiState.tvItems.isNotEmpty()
    val hasContent = hasMovies || hasTv

    // Helper: get the list for the current row
    fun currentRowItems(): List<MediaItem> = when {
        currentRowIndex == 0 && hasMovies -> uiState.movieItems
        currentRowIndex == 1 && hasTv -> uiState.tvItems
        // If current row is empty, try the other one
        hasMovies -> uiState.movieItems
        hasTv -> uiState.tvItems
        else -> emptyList()
    }

    fun currentItemIndex(): Int = if (currentRowIndex == 0) movieItemIndex else tvItemIndex

    fun setCurrentItemIndex(index: Int) {
        if (currentRowIndex == 0) movieItemIndex = index else tvItemIndex = index
    }

    LaunchedEffect(Unit) {
        rootFocusRequester.requestFocus()
    }

    LaunchedEffect(uiState.isLoading, hasContent) {
        if (!uiState.isLoading && !hasContent) {
            isSidebarFocused = true
            sidebarFocusIndex = if (hasProfile) 3 else SidebarItem.WATCHLIST.ordinal
        }
    }

    // Ensure currentRowIndex points to a row that has items
    LaunchedEffect(hasMovies, hasTv) {
        if (currentRowIndex == 0 && !hasMovies && hasTv) currentRowIndex = 1
        if (currentRowIndex == 1 && !hasTv && hasMovies) currentRowIndex = 0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .focusRequester(rootFocusRequester)
            .onFocusChanged {
                if (it.hasFocus && !hasContent) {
                    isSidebarFocused = true
                }
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Back, Key.Escape -> {
                            if (isSidebarFocused) {
                                onBack()
                            } else {
                                isFilterFocused = false
                                isSidebarFocused = true
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            if (isFilterFocused) {
                                isFilterFocused = false
                                isSidebarFocused = true
                                true
                            } else if (!isSidebarFocused) {
                                val idx = currentItemIndex()
                                if (idx <= 0) {
                                    isSidebarFocused = true
                                } else {
                                    setCurrentItemIndex(idx - 1)
                                }
                                true
                            } else {
                                true // Consume to prevent focus leaving
                            }
                        }
                        Key.DirectionRight -> {
                            if (isSidebarFocused) {
                                if (hasContent) {
                                    isSidebarFocused = false
                                    isFilterFocused = false
                                    // Point to whichever row has items
                                    if (!hasMovies && hasTv) currentRowIndex = 1
                                    else if (hasMovies) currentRowIndex = 0
                                }
                                true
                            } else {
                                val items = currentRowItems()
                                val idx = currentItemIndex()
                                if (idx < items.lastIndex) {
                                    setCurrentItemIndex(idx + 1)
                                }
                                true
                            }
                        }
                        Key.DirectionUp -> {
                            if (isSidebarFocused) {
                                sidebarFocusIndex = (sidebarFocusIndex - 1).coerceIn(0, maxSidebarIndex)
                                true
                            } else if (isFilterFocused) {
                                isFilterFocused = false
                                isSidebarFocused = true
                                true
                            } else {
                                if (currentRowIndex == 1 && hasMovies) {
                                    currentRowIndex = 0
                                    true
                                } else {
                                    // At top row — go to filter toggle
                                    isFilterFocused = true
                                    true
                                }
                            }
                        }
                        Key.DirectionDown -> {
                            if (isSidebarFocused) {
                                sidebarFocusIndex = (sidebarFocusIndex + 1).coerceIn(0, maxSidebarIndex)
                                true
                            } else if (isFilterFocused) {
                                isFilterFocused = false
                                true
                            } else {
                                if (currentRowIndex == 0 && hasTv) {
                                    currentRowIndex = 1
                                    true
                                } else {
                                    true // Already at bottom row
                                }
                            }
                        }
                        Key.Enter, Key.DirectionCenter -> {
                            if (isFilterFocused) {
                                viewModel.toggleUnwatchedFilter()
                                true
                            } else if (isSidebarFocused) {
                                if (hasProfile && sidebarFocusIndex == 0) {
                                    onSwitchProfile()
                                } else {
                                    val itemIndex = if (hasProfile) sidebarFocusIndex - 1 else sidebarFocusIndex
                                    when (SidebarItem.entries[itemIndex]) {
                                        SidebarItem.SEARCH -> onNavigateToSearch()
                                        SidebarItem.HOME -> onNavigateToHome()
                                        SidebarItem.WATCHLIST -> { }
                                        SidebarItem.TV -> onNavigateToTv()
                                        SidebarItem.SETTINGS -> onNavigateToSettings()
                                    }
                                }
                                true
                            } else {
                                val items = currentRowItems()
                                val idx = currentItemIndex().coerceIn(0, items.lastIndex.coerceAtLeast(0))
                                items.getOrNull(idx)?.let { item ->
                                    onNavigateToDetails(item.mediaType, item.id)
                                }
                                true
                            }
                        }
                        else -> if (isSidebarFocused) true else false
                    }
                } else if (event.type == KeyEventType.KeyUp && isSidebarFocused) {
                    true
                } else false
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Sidebar
            Sidebar(
                selectedItem = SidebarItem.WATCHLIST,
                isSidebarFocused = isSidebarFocused,
                focusedIndex = sidebarFocusIndex,
                profile = currentProfile,
                onProfileClick = onSwitchProfile,
                onItemSelected = { item ->
                    when (item) {
                        SidebarItem.SEARCH -> onNavigateToSearch()
                        SidebarItem.HOME -> onNavigateToHome()
                        SidebarItem.WATCHLIST -> { }
                        SidebarItem.TV -> onNavigateToTv()
                        SidebarItem.SETTINGS -> onNavigateToSettings()
                    }
                }
            )

            // Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(start = 24.dp, top = 32.dp, end = 48.dp)
            ) {
                // Header with pink bookmark icon + filter toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bookmark,
                        contentDescription = null,
                        tint = Pink,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "MY WATCHLIST",
                        style = ArflixTypography.sectionTitle,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Unwatched-only toggle
                    WatchlistFilterToggle(
                        showUnwatchedOnly = uiState.showUnwatchedOnly,
                        isFocused = isFilterFocused,
                        onToggle = { viewModel.toggleUnwatchedFilter() }
                    )
                }

                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator(color = Pink, size = 64.dp)
                        }
                    }
                    uiState.items.isEmpty() -> {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Bookmark,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.2f),
                                    modifier = Modifier.size(80.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Your watchlist is empty",
                                    style = ArflixTypography.body,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Add movies and shows to watch later",
                                    style = ArflixTypography.caption,
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                    else -> {
                        Column(modifier = Modifier.weight(1f)) {
                            if (hasMovies) {
                                WatchlistRow(
                                    title = "Movies",
                                    items = uiState.movieItems,
                                    rowState = movieRowState,
                                    isCurrentRow = !isSidebarFocused && currentRowIndex == 0,
                                    focusedItemIndex = movieItemIndex,
                                    isCompactHeight = isCompactHeight,
                                    itemWidth = itemWidth,
                                    onItemClick = { item -> onNavigateToDetails(item.mediaType, item.id) }
                                )
                                if (hasTv) {
                                    Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 16.dp))
                                }
                            }
                            if (hasTv) {
                                WatchlistRow(
                                    title = "TV Shows",
                                    items = uiState.tvItems,
                                    rowState = tvRowState,
                                    isCurrentRow = !isSidebarFocused && currentRowIndex == 1,
                                    focusedItemIndex = tvItemIndex,
                                    isCompactHeight = isCompactHeight,
                                    itemWidth = itemWidth,
                                    onItemClick = { item -> onNavigateToDetails(item.mediaType, item.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Clock in top right
        TopBarClock(modifier = Modifier.align(Alignment.TopEnd))

        // Toast notification
        uiState.toastMessage?.let { message ->
            Toast(
                message = message,
                type = when (uiState.toastType) {
                    ToastType.SUCCESS -> ComponentToastType.SUCCESS
                    ToastType.ERROR -> ComponentToastType.ERROR
                    ToastType.INFO -> ComponentToastType.INFO
                },
                isVisible = true,
                onDismiss = { viewModel.dismissToast() }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WatchlistRow(
    title: String,
    items: List<MediaItem>,
    rowState: androidx.tv.foundation.lazy.list.TvLazyListState,
    isCurrentRow: Boolean,
    focusedItemIndex: Int,
    isCompactHeight: Boolean,
    itemWidth: androidx.compose.ui.unit.Dp,
    onItemClick: (MediaItem) -> Unit
) {
    val itemSpacing = 16.dp
    val rowStartPadding = if (isCompactHeight) 12.dp else 16.dp
    val rowEndPadding = if (isCompactHeight) 120.dp else 160.dp
    val density = LocalDensity.current
    val peekOffsetPx = remember(density) { with(density) { 35.dp.roundToPx() } }
    var lastScrollIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(isCurrentRow) {
        if (!isCurrentRow) lastScrollIndex = -1
    }
    LaunchedEffect(isCurrentRow, focusedItemIndex, items.size) {
        if (!isCurrentRow || items.isEmpty()) return@LaunchedEffect
        val targetIndex = focusedItemIndex.coerceIn(0, items.lastIndex)
        if (lastScrollIndex == targetIndex) return@LaunchedEffect
        val peek = if (targetIndex > 0) -peekOffsetPx else 0
        rowState.animateScrollToItem(index = targetIndex, scrollOffset = peek)
        lastScrollIndex = targetIndex
    }

    Column {
        // Row title with count
        Text(
            text = "$title (${items.size})",
            style = ArvioSkin.typography.sectionTitle,
            color = ArvioSkin.colors.textPrimary,
            modifier = Modifier.padding(bottom = if (isCompactHeight) 6.dp else 10.dp)
        )

        // Horizontal card row
        Box(modifier = Modifier.fillMaxWidth()) {
            TvLazyRow(
                state = rowState,
                contentPadding = PaddingValues(
                    start = rowStartPadding,
                    end = rowEndPadding,
                    top = if (isCompactHeight) 6.dp else 12.dp,
                    bottom = if (isCompactHeight) 12.dp else 16.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                pivotOffsets = androidx.tv.foundation.PivotOffsets(
                    parentFraction = 0.0f,
                    childFraction = 0.0f
                )
            ) {
                itemsIndexed(items, key = { index, it -> "${it.mediaType.name}-${it.id}-$index" }) { index, item ->
                    val yearValue = item.year.ifBlank { item.releaseDate?.take(4).orEmpty() }
                    val yearDisplay = if (yearValue.isNotBlank()) " | $yearValue" else ""
                    val mediaTypeLabel = when (item.mediaType) {
                        MediaType.TV -> "TV Show"
                        MediaType.MOVIE -> "Movie"
                    }
                    val displayItem = item.copy(subtitle = "$mediaTypeLabel$yearDisplay")
                    MediaCard(
                        item = displayItem,
                        width = itemWidth,
                        isLandscape = true,
                        showProgress = false,
                        isFocusedOverride = isCurrentRow && index == focusedItemIndex,
                        enableSystemFocus = false,
                        onFocused = { },
                        onClick = { onItemClick(item) }
                    )
                }
            }
        }
    }
}

/**
 * Animated toggle for "All" vs "Unwatched Only" filter.
 */
@Composable
private fun WatchlistFilterToggle(
    showUnwatchedOnly: Boolean,
    isFocused: Boolean = false,
    onToggle: () -> Unit
) {
    val animatedOffset by animateDpAsState(
        targetValue = if (showUnwatchedOnly) 56.dp else 0.dp,
        animationSpec = tween(durationMillis = 250),
        label = "toggleOffset"
    )
    val borderColor = if (isFocused) Pink else Color.Transparent
    val bgAlpha = if (isFocused) 0.15f else 0.08f

    Box(
        modifier = Modifier
            .height(32.dp)
            .background(
                color = Color.White.copy(alpha = bgAlpha),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )
            .then(
                if (isFocused) Modifier.border(
                    width = 2.dp,
                    color = borderColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .padding(horizontal = 3.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Sliding indicator
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(if (showUnwatchedOnly) 82.dp else 40.dp)
                .height(26.dp)
                .background(
                    color = Pink.copy(alpha = 0.9f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)
                )
        )
        // Labels
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "All",
                    style = ArflixTypography.caption,
                    color = if (!showUnwatchedOnly) Color.White else Color.White.copy(alpha = 0.5f)
                )
            }
            Box(
                modifier = Modifier
                    .width(82.dp)
                    .height(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Unwatched",
                    style = ArflixTypography.caption,
                    color = if (showUnwatchedOnly) Color.White else Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}
