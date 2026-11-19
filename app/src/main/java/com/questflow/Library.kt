package com.questflow

import android.text.format.DateUtils
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class Sort(val label: String) { Recommended("Recommended"), Name("Name"), Popular("Popular"), Rating("Top rated"), Updated("Recently updated"), Size("Largest"), LastPlayed("Last played") }
private enum class Filter(val label: String) { Installed("Installed"), Updates("Updates"), Favourites("Favourites") }

val Game.sizeLabel get() = sizeMb.toDoubleOrNull()?.let { formatSize((it * 1_048_576).toLong()) } ?: "$sizeMb MB"

/** With more than one source, how many of each source's titles the store home shows before its "See all". */
private const val HOME_CAP = 12

@Composable
fun LibraryScreen(s: AppState) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.Recommended) }
    // null = no filter; the chips toggle, so there's no "All" chip.
    var filter by rememberSaveable { mutableStateOf<Filter?>(null) }
    var source by rememberSaveable { mutableStateOf("All") }
    var view by rememberSaveable { mutableStateOf(ViewMode.List) }
    var open by remember { mutableStateOf<Game?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    fun toggle(g: Game) { if (g.release in picked) picked.remove(g.release) else picked.add(g.release) }
    val games = s.games
    val versions = s.versions
    val sources = remember(games) { games.map { it.source }.distinct() }
    val shown = remember(games, query, sort, filter, source, versions, s.lastPlayed, s.favourites, s.hideAdult) {
        // Browsing shows the newest release per title; searching shows them all.
        val pool = if (query.isNotEmpty()) games else latestPerTitle(games)
        pool.filter { !(s.hideAdult && it.isAdult) }
            .filter { it.name.contains(query, true) || it.pkg.contains(query, true) }
            .filter { source == "All" || it.source == source }
            .filter {
                when (filter) {
                    null -> true
                    Filter.Installed -> s.stateOf(it) != GameState.NotInstalled
                    Filter.Updates -> s.stateOf(it) == GameState.Update
                    Filter.Favourites -> it.pkg in s.favourites
                }
            }
            .let { l ->
                when (sort) {
                    Sort.Recommended -> recommended(l)
                    Sort.Name -> l.sortedBy { it.name.lowercase() }
                    Sort.Popular -> l.sortedByDescending { it.popularity }
                    Sort.Rating -> l.sortedWith(compareByDescending<Game> { it.rating }.thenByDescending { it.ratingCount })
                    Sort.Updated -> l.sortedByDescending { it.updated }
                    Sort.Size -> l.sortedByDescending { it.sizeMb.toDoubleOrNull() ?: 0.0 }
                    Sort.LastPlayed -> l.sortedByDescending { s.lastPlayed[s.installedPkg(it)] ?: 0L }
                }
            }
    }
    val updates = remember(games, versions) { games.count { s.stateOf(it) == GameState.Update } }
    // The store home shows a shelf above each source's list; any search, filter, source or "See all" switches to the plain list, and Back returns.
    val home = !browsing && query.isEmpty() && filter == null && source == "All" && !picking
    fun goHome() { browsing = false; query = ""; filter = null; source = "All"; sort = Sort.Recommended; picking = false; picked.clear() }
    BackHandler(enabled = !home) { goHome() }
    val newest = remember(games, s.hideAdult) { newAndUpdated(games, s.hideAdult) }
    val lists = remember(shown) { sourceLists(shown, setOf(s.rookie.label, TheVRSrc.name), s.rookie.mirrors.isNotEmpty()) }
    // A source list's See all: that source, or everything when the list mixes several (VRSrc's mirror and its catalog).
    fun seeAll(list: List<Game>) { browsing = true; list.map { it.source }.distinct().singleOrNull()?.let { source = it } }
    // Controllers start on the first game, not the search box, which would open the keyboard.
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(games.isNotEmpty()) { runCatching { firstCard.requestFocus() } }
    // Newest of the mirror list and the SideQuest cache; 0 (never) when neither exists.
    val listTime = remember(games) { maxOf(s.rookie.metaDir.lastModified(), java.io.File(s.rookie.root, "sidequest").lastModified()) }
    val listAge = if (listTime == 0L) "" else " · list updated " + DateUtils.getRelativeTimeSpanString(listTime).toString().lowercase()

    Column(Modifier.fillMaxSize()) {
        TopBar {
            ViewToggle(view) { view = it }
            if (games.isNotEmpty()) SearchPill(query, { query = it }, "Search %,d games and apps".format(games.size))
        }
        // Skeleton rows while the built-in lists load from cache, so the page has its shape from the first frame.
        if (games.isEmpty() && s.loadingCatalogs) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                repeat(8) { Shimmer(Modifier.fillMaxWidth().height(Size.row).clip(RoundedCornerShape(Radius.row))) }
            }
            return@Column
        }
        if (games.isEmpty()) {
            EmptyState(Icons.Default.Home, "No games yet", "Download the game list to get started.") {
                PillButton("Download game list", { refresh(s) }, icon = Icons.Default.Refresh, tone = Tone.Accent, enabled = s.busy == null)
            }
            return@Column
        }
        // The toolbar tucks away while scrolling down the list and comes back on any scroll up, so more games fit.
        val grid = rememberLazyGridState()
        // A new view (home, See all, another filter, search or layout) starts at the top, not wherever the last one was scrolled to.
        LaunchedEffect(home, query, filter, source, sort, view) { grid.scrollToItem(0) }
        AnimatedVisibility(!grid.canScrollBackward || grid.lastScrolledBackward) {
            Toolbar(count(shown.size, "title") + listAge) {
                if (!home) PillButton("Store", ::goHome, icon = Icons.AutoMirrored.Filled.ArrowBack, tone = Tone.Glass)
                Picker(sort, Sort.entries, { it.label }, icon = QIcons.Sort) { sort = it }
                // Sources are a dropdown, not a chip each: the list grows with every source added.
                if (sources.size > 1) Picker(source, listOf("All") + sources, { if (it == "All") "All sources" else it }, icon = QIcons.Cloud) { source = it }
                Filter.entries.forEach { f ->
                    ToggleChip(if (f == Filter.Updates && updates > 0) "${f.label} ($updates)" else f.label, filter == f, { filter = if (filter == f) null else f })
                }
                ToggleChip(if (picking) "Done selecting" else "Select", picking, { picking = !picking; picked.clear() })
                if (picking && picked.isNotEmpty()) PillButton("Install (${picked.size})", {
                    val chosen = picked.toList()
                    games.filter { it.release in chosen && s.stateOf(it) != GameState.Installed }.forEach(s::install)
                    picked.clear(); picking = false
                }, icon = QIcons.Download, tone = Tone.Accent)
                if (updates > 0) PillButton("Update all ($updates)", { s.updateAll() }, icon = QIcons.Sync, tone = Tone.Accent, enabled = s.busy == null)
                IconPill(Icons.Default.Refresh, "Refresh game list", { refresh(s) }, tone = Tone.Raised, enabled = s.busy == null)
                // Occasional actions live behind one button.
                Box {
                    var more by remember { mutableStateOf(false) }
                    IconPill(Icons.Default.MoreVert, "More", { more = true }, tone = Tone.Raised)
                    DropdownMenu(more, { more = false }) {
                        // StreamFlow's Surprise Me: a random title from whatever the filters show. On the home that's everything, and
                        // F-Droid's phone apps would win nearly every time, so the home picks among headset titles you don't have yet.
                        val pool = if (home) shown.filter { it.source != FDroid.name && s.stateOf(it) == GameState.NotInstalled }.ifEmpty { shown } else shown
                        if (pool.isNotEmpty()) DropdownMenuItem({ Text("Surprise me") }, { more = false; open = pool.random() })
                        DropdownMenuItem({ Text("Sources") }, { more = false; sourcesOpen = true })
                    }
                }
            }
        }
        if (shown.isEmpty()) EmptyState(Icons.Default.Search, "No matches", "Try a different search or filter.")
        else LazyVerticalGrid(
            view.cells(), Modifier.weight(1f), state = grid, contentPadding = PaddingValues(bottom = Space.xxl),
            horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            if (home) {
                // Controllers start on the shelf's first tile.
                if (newest.isNotEmpty()) item(key = "new", span = { GridItemSpan(maxLineSpan) }) {
                    Shelf(s, "New & updated", newest, Modifier.focusRequester(firstCard), { browsing = true }) { open = it }
                }
                // Each source's own list, VRSrc first, F-Droid last. One source needs no heading: it's the whole store.
                lists.forEach { (name, list) ->
                    val many = lists.size > 1
                    if (many) item(key = "src-$name", span = { GridItemSpan(maxLineSpan) }) {
                        SourceHeading(s, name, list.size, failed = list.isEmpty(), onSeeAll = if (list.size > HOME_CAP) {{ seeAll(list) }} else null)
                    }
                    itemsIndexed(if (many) list.take(HOME_CAP) else list, key = { _, g -> "$name/${g.release}" }) { i, g ->
                        StoreItem(s, g, view, Modifier.staggered(i, g.release), selected = false) { open = g }
                    }
                }
            } else itemsIndexed(shown, key = { _, g -> g.release }) { i, g ->
                StoreItem(s, g, view, (if (i == 0) Modifier.focusRequester(firstCard) else Modifier).staggered(i, g.release), selected = g.release in picked) {
                    if (picking) toggle(g) else open = g
                }
            }
        }
    }
    open?.let { GameDetail(s, it, onOpen = { next -> open = next }) { open = null } }
    if (sourcesOpen) SourcesDialog(s) { sourcesOpen = false }
}

/** A titled sideways row of tiles with "See all", like a store shelf. [first] lands on the row's first tile. */
@Composable
private fun Shelf(s: AppState, title: String, games: List<Game>, first: Modifier, onSeeAll: () -> Unit, onOpen: (Game) -> Unit) {
    Column {
        Heading(title) { PillButton("See all", onSeeAll, tone = Tone.Glass) }
        // Vertical padding leaves room for the tiles' focus scale, which a lazy row would clip.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.lg), contentPadding = PaddingValues(vertical = Space.md)) {
            rowItemsIndexed(games, key = { _, g -> g.release }) { i, g ->
                StoreTile(s, g, (if (i == 0) first else Modifier).width(260.dp), selected = false) { onOpen(g) }
            }
        }
    }
}

/** A source's heading above its list, with See all when the home shows only part of it. Only VRSrc's list is ever empty: it [failed] to load, so it says why. */
@Composable
private fun SourceHeading(s: AppState, name: String, size: Int, failed: Boolean, onSeeAll: (() -> Unit)?) {
    Column(Modifier.padding(top = Space.sm)) {
        Heading(name) { if (onSeeAll != null) PillButton("See all %,d".format(size), onSeeAll, tone = Tone.Glass) }
        if (failed) Row(Modifier.padding(vertical = Space.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val error = s.vrsrcError
            Text("Failed to load" + (error?.let { ": " + friendlyError(it) } ?: ""), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
            // The full error, URL included, for pasting into a bug report or chat.
            if (error != null) {
                val clipboard = LocalClipboardManager.current
                PillButton("Copy error", { clipboard.setText(AnnotatedString(error.toString())); s.status = "Error copied" })
                PillButton("Try again", { s.refreshVrsrc() }, icon = Icons.Default.Refresh, tone = Tone.Accent)
            }
        }
    }
}

/** A game in the current layout: a row in the list views, a tile in the icon grid. */
@Composable
private fun StoreItem(s: AppState, g: Game, view: ViewMode, modifier: Modifier, selected: Boolean, onClick: () -> Unit) =
    if (view == ViewMode.Icons) StoreTile(s, g, modifier, selected, onClick) else StoreRow(s, g, modifier, view == ViewMode.Columns, selected, onClick)

/** Install / Update / Open, or Cancel while it's queued: what a row's main pill does. */
@Composable
private fun StoreAction(s: AppState, g: Game, st: GameState, job: QueueItem?, modifier: Modifier = Modifier) = when {
    job != null -> PillButton("Cancel", { s.queue.cancel(job) }, modifier, icon = Icons.Default.Close, tone = Tone.Glass)
    st == GameState.Installed -> PillButton("Open", { s.launch(g.pkg) }, modifier, icon = QIcons.OpenInNew, tone = Tone.Glass)
    st == GameState.Update -> PillButton("Update", { s.install(g) }, modifier, icon = QIcons.Sync, tone = Tone.Accent)
    else -> PillButton("Install", { s.install(g) }, modifier, icon = QIcons.Download, tone = Tone.Accent)
}

private fun stateStat(st: GameState, job: QueueItem?) = when {
    job != null -> StatItem(QIcons.Download, if (job.state == QState.Waiting) "Queued" else "Installing", Orange)
    st == GameState.Installed -> StatItem(Icons.Default.Check, "Installed", Green)
    st == GameState.Update -> StatItem(QIcons.Sync, "Update", Orange)
    else -> StatItem(QIcons.Download, "Not installed", Palette.Dim)
}

@Composable
fun StateTag(st: GameState) {
    when (st) {
        GameState.Installed -> Tag("Installed", Green, Color(0xFF062015))
        GameState.Update -> Tag("Update", Orange, Palette.Ink)
        GameState.NotInstalled -> {}
    }
}

@Composable
private fun StoreRow(s: AppState, g: Game, modifier: Modifier, compact: Boolean, selected: Boolean, onClick: () -> Unit) {
    val st = s.stateOf(g)
    val job = s.queue.active(g.release)
    MediaRow(
        title = g.name, art = { GameArt(s, g, it) }, tint = gameTint(s, g), modifier = modifier, onClick = onClick, selected = selected, compact = compact,
        stats = listOf(StatItem(QIcons.Tag, "v${g.version}"), StatItem(QIcons.Storage, g.sizeLabel), StatItem(QIcons.Cloud, g.source), stateStat(st, job)),
        status = when {
            job != null -> if (job.state == QState.Waiting) "Queued" else job.stage
            g.rating > 0 -> "★ %.1f".format(g.rating)
            else -> updatedAgo(g.updated, java.time.LocalDate.now()).orEmpty()
        },
        statusColor = if (job != null) Orange else Palette.Dim,
        tags = { if (selected) Tag("Selected", Orange, Palette.Ink) },
        actions = { StoreAction(s, g, st, job); if (!compact) FavStar(s, g.pkg) },
        footer = { if (job != null) QueueProgress(job, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)) },
    )
}

@Composable
private fun StoreTile(s: AppState, g: Game, modifier: Modifier, selected: Boolean, onClick: () -> Unit) {
    val job = s.queue.active(g.release)
    ArtTile(
        g.name, { GameArt(s, g, it) }, gameTint(s, g), modifier, onClick, selected = selected,
        subtitle = listOfNotNull(g.source, g.sizeLabel, if (g.rating > 0) "★ %.1f".format(g.rating) else null).joinToString(" · "),
        badges = {
            if (selected) Tag("Selected", Orange, Palette.Ink)
            StateTag(s.stateOf(g))
            if (job != null) Tag(if (job.state == QState.Waiting) "Queued" else job.stage, Palette.Glass, Orange)
        },
        footer = { if (job != null) QueueProgress(job, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)) },
    )
}

/** Built-in catalogs always refresh; each configured source refreshes on its own, and one failing keeps its last list without hiding the rest. */
private fun refresh(s: AppState) = s.task("Downloading game list") {
    val catalogs = s.catalogs.flatMap { s.busy = "Downloading ${it.name} list"; it.refresh(s.rookie.root) }
    val failed = mutableListOf<String>()
    val fromSources = (listOf(s.rookie) + s.extras).filter { it.mirrors.isNotEmpty() }.flatMap { src ->
        runCatching {
            src.refreshMeta { stage, done, total ->
                s.busy = "${src.label}: " + if (total > 0) "$stage · ${formatSize(done)} of ${formatSize(total)}" else stage
            }.map { it.copy(source = src.label) }.also { if (src === s.rookie) s.vrsrcError = null }
        }.getOrElse { if (src === s.rookie) s.vrsrcError = it; failed += "${src.label} unavailable: ${friendlyError(it).replaceFirstChar(Char::lowercase)}. Showing your saved list"; src.games() }
    }
    s.games = fromSources + catalogs
    count(s.games.size, "title") + failed.joinToString("") { " · $it" }
}

/** One source in the Sources dialog: its name, where it comes from, and a button when it can be removed. */
@Composable
private fun SourceLine(name: String, where: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        Text(where, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Palette.Dim, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** A field's label over its input, for forms in dialogs. */
@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "") = Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.Dim)
    InputPill(value, onChange, placeholder, Modifier.fillMaxWidth())
}

/** Lists extra sources and adds new ones, e.g. a vrhub-server at http://<pi-ip>:39457/config.json. */
@Composable
internal fun SourcesDialog(s: AppState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Palette.Panel,
        title = { Text("Sources") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SourceLine("Mirror", if (s.rookie.mirrors.isEmpty()) "not set up" else s.rookie.baseUri)
                CATALOGS.forEach { SourceLine(it.name, "built in") }
                (s.catalogs.filterIsInstance<RepoCatalog>().map { it.name to it.url } + s.extras.map { it.label to it.baseUri }).forEach { (label, where) ->
                    SourceLine(label, where) { PillButton("Remove", { s.removeSource(label) }, tone = Tone.Danger) }
                }
                HorizontalDivider(Modifier.padding(vertical = Space.xs), color = Palette.Raised)
                Text("Add a source", style = MaterialTheme.typography.titleSmall)
                // Up here, not under the fields: below them it scrolls out of sight on a short window.
                if (error.isNotEmpty()) Text(error, color = Red, style = MaterialTheme.typography.bodyMedium)
                Field("Name", name, { name = it }, "e.g. Pi")
                Field("Config URL or JSON, or a repo's index-v1.json link", input, { input = it }, "http://192.168.1.50:39457/config.json")
                Field("API key (optional)", key, { key = it })
            }
        },
        confirmButton = {
            PillButton("Add", {
                error = ""
                s.task("Adding $name") {
                    // A failure stays in the dialog; a snackbar would sit behind it, out of sight.
                    val added = try { s.addSource(name.trim(), input, key) } catch (e: Exception) { error = "Couldn't add $name: " + friendlyError(e).replaceFirstChar(Char::lowercase); return@task "" }
                    onClose()
                    added
                }
            }, tone = Tone.Accent, enabled = name.isNotBlank() && input.isNotBlank() && s.busy == null)
        },
        dismissButton = { PillButton("Close", onClose) },
    )
}

/** A game's store page, full screen over the Store. Back (or B) closes it; [onOpen] swaps in another game from "More from". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameDetail(s: AppState, g: Game, onOpen: (Game) -> Unit, onDismiss: () -> Unit) {
    var trailer by remember(g) { mutableStateOf(false) }
    // Store text and screenshots load off the main thread; OculusDB may need the network the first time.
    val info by produceState<Details?>(null, g) { value = withContext(Dispatchers.IO) { runCatching { s.details(g) }.getOrNull() } }
    var viewing by remember(g) { mutableStateOf<Int?>(null) }
    val st = s.stateOf(g)
    val job = s.queue.active(g.release)
    // Mirror games have no icon of their own; OculusDB's square store cover stands in.
    val icon = g.iconUrl.ifEmpty { info?.cover.orEmpty() }
    val more = remember(g, s.games) { latestPerTitle(s.games).filter { it.source == g.source && it.pkg != g.pkg }.sortedByDescending { it.updated }.take(10) }
    // Controllers land on the primary action when the page opens.
    val primary = remember { FocusRequester() }
    LaunchedEffect(g) { runCatching { primary.requestFocus() } }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Palette.Base, contentColor = Palette.Text) { key(g) {
            val tint = gameTint(s, g) ?: Palette.Panel
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xxxl)) {
                Box(Modifier.fillMaxWidth().height(340.dp)) {
                    if (trailer) Box(Modifier.matchParentSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Trailer(g.name, Modifier.fillMaxHeight().aspectRatio(16 / 9f))
                    } else {
                        GameArt(s.thumbnail(g), g.name, Modifier.matchParentSize(), url = g.thumbUrl, icon = icon)
                        // Fades into the page so the action strip reads as part of the art.
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, .5f to Color.Transparent, 1f to Palette.Base)))
                        PillButton("Trailer", { trailer = true }, Modifier.align(Alignment.BottomEnd).padding(Space.xxxl), icon = Icons.Default.PlayArrow, tone = Tone.Glass)
                    }
                    IconPill(Icons.AutoMirrored.Filled.ArrowBack, "Back", onDismiss, Modifier.align(Alignment.TopStart).padding(Space.lg))
                }
                // The game's row, large: in its art's colour, with its actions on the right.
                Row(
                    Modifier.padding(horizontal = Space.xxxl).fillMaxWidth().clip(RoundedCornerShape(Radius.sheet))
                        .background(Brush.horizontalGradient(listOf(tint, lerp(tint, Palette.Base, .35f)))).padding(Space.lg),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                    rememberArt(null, icon)?.let {
                        Image(it, null, Modifier.size(72.dp).clip(RoundedCornerShape(22)).background(Palette.Raised), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Text(g.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            StateTag(st)
                        }
                        val byline = listOf(info?.publisher.orEmpty().ifEmpty { g.source }) + info?.genres.orEmpty().take(3)
                        Text(byline.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .75f))
                    }
                    if (job != null) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        PillButton("Cancel", { s.queue.cancel(job) }, Modifier.focusRequester(primary), icon = Icons.Default.Close, tone = Tone.Glass)
                        Text(listOf(job.stage, job.detail).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .75f))
                        QueueProgress(job, Modifier.width(180.dp))
                    } else PillButton(
                        when (st) { GameState.NotInstalled -> "Install"; GameState.Update -> "Update"; GameState.Installed -> "Reinstall" },
                        { s.install(g) }, Modifier.focusRequester(primary), icon = if (st == GameState.Update) QIcons.Sync else QIcons.Download, tone = Tone.Accent,
                    )
                    if (st != GameState.NotInstalled) PillButton("Open", { s.launch(g.pkg) }, icon = QIcons.OpenInNew, tone = Tone.Glass)
                    FavStar(s, g.pkg)
                }
                Column(Modifier.padding(horizontal = Space.xxxl).padding(top = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        if (g.rating > 0) IconText(QIcons.Star, "%.1f".format(g.rating) + if (g.ratingCount > 0) " (${g.ratingCount})" else "", style = MaterialTheme.typography.bodyLarge)
                        IconText(QIcons.Storage, g.sizeLabel, style = MaterialTheme.typography.bodyLarge)
                        IconText(QIcons.Tag, "Version ${g.version}", style = MaterialTheme.typography.bodyLarge)
                        updatedAgo(g.updated, java.time.LocalDate.now())?.let { IconText(QIcons.Schedule, it, style = MaterialTheme.typography.bodyLarge) }
                        IconText(QIcons.Cloud, g.source, style = MaterialTheme.typography.bodyLarge)
                        if (st == GameState.Update) IconText(QIcons.Sync, "You have ${s.versions[s.installedPkg(g)]}", color = Orange, style = MaterialTheme.typography.bodyLarge)
                    }
                    info?.let { d ->
                        if (d.summary.isNotEmpty() || d.description.isNotEmpty()) PageSection("About") {
                            if (d.summary.isNotEmpty() && d.summary != d.description) Text(d.summary, style = MaterialTheme.typography.bodyLarge)
                            if (d.description.isNotEmpty()) ExpandableText(richText(d.description))
                        }
                        if (d.screenshots.isNotEmpty()) PageSection("Screenshots", wide = true) { Screenshots(d.screenshots) { viewing = it } }
                        if (d.whatsNew.isNotEmpty()) PageSection("What's new") { ExpandableText(AnnotatedString(d.whatsNew)) }
                    }
                    if (more.isNotEmpty()) PageSection("More from ${g.source}", wide = true) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.lg), contentPadding = PaddingValues(vertical = Space.md)) {
                            rowItemsIndexed(more, key = { _, m -> m.release }) { _, m -> StoreTile(s, m, Modifier.width(240.dp), selected = false) { onOpen(m) } }
                        }
                    }
                    Text("Package ${g.pkg.removePrefix("github:")} · ${g.release}", style = MaterialTheme.typography.labelSmall, color = Palette.Dim)
                }
            }
        } }
    }
    val shots = info?.screenshots.orEmpty()
    viewing?.let { start -> ScreenshotViewer(shots, start) { viewing = null } }
}

/** A heading and its content, the shape every block on a game page shares. Text stays readable-width; [wide] rows of tiles use it all. */
@Composable
private fun PageSection(title: String, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column((if (wide) Modifier.fillMaxWidth() else Modifier.widthIn(max = 960.dp)).padding(top = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

/** F-Droid descriptions are HTML; other sources send plain text with line breaks that HTML parsing would collapse. */
private fun richText(t: String) = if (Regex("<[a-zA-Z/][^>]*>").containsMatchIn(t)) AnnotatedString.fromHtml(t.trim()) else AnnotatedString(t)

/** Four lines, with More/Less when the text runs longer. */
@Composable
private fun ExpandableText(text: AnnotatedString) {
    var more by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Text(
        text, Modifier.clickable { more = !more }, style = MaterialTheme.typography.bodyMedium, color = Palette.Dim,
        maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!more) overflows = it.hasVisualOverflow },
    )
    if (overflows || more) PillButton(if (more) "Less" else "More", { more = !more }, tone = Tone.Glass)
}

/** A sideways strip at a fixed height, each shot at its own shape (phone screenshots are tall, VR ones wide). */
@Composable
private fun Screenshots(urls: List<String>, onOpen: (Int) -> Unit) {
    // A plain scrolling Row: a game has a handful of screenshots, not enough to need a lazy list.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        urls.forEachIndexed { i, url ->
            val img = rememberArt(null, url)
            val m = Modifier.height(180.dp).tap(RoundedCornerShape(Radius.tile)) { onOpen(i) }
            if (img == null) Shimmer(m.aspectRatio(9 / 16f))
            else Image(img, "Screenshot ${i + 1}", m.aspectRatio(img.width.toFloat() / img.height), contentScale = ContentScale.Crop)
        }
    }
}

/** Full-screen screenshots; swipe or use the D-pad between them, Back closes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenshotViewer(urls: List<String>, start: Int, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(start) { urls.size }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .92f))) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                val img = rememberArt(null, urls[i], maxWidth = 1600)
                // Bottom gap keeps the page counter off the image.
                Box(Modifier.fillMaxSize().padding(start = Space.xxl, top = Space.xxl, end = Space.xxl, bottom = 56.dp), contentAlignment = Alignment.Center) {
                    if (img == null) CircularProgressIndicator(color = Orange)
                    else Image(img, "Screenshot ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            Text("${pager.currentPage + 1} / ${urls.size}", Modifier.align(Alignment.BottomCenter).padding(Space.lg), color = Color.White, style = MaterialTheme.typography.titleMedium)
            IconPill(Icons.Default.Close, "Close", onClose, Modifier.align(Alignment.TopEnd).padding(Space.lg))
        }
    }
}

@Composable
private fun Trailer(name: String, modifier: Modifier) {
    // "" = still searching, null = nothing found
    val id by produceState<String?>("", name) { value = withContext(Dispatchers.IO) { runCatching { youtubeId("$name VR trailer") }.getOrNull() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val v = id) {
            "" -> CircularProgressIndicator()
            null -> Text("No trailer found", color = Color.White)
            else -> AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        setBackgroundColor(android.graphics.Color.BLACK)
                        // YouTube refuses embeds without a referrer, so load from an https base URL instead of the raw embed URL.
                        loadDataWithBaseURL("https://questflow.app/", embedHtml(v), "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.matchParentSize(),
                onRelease = { it.destroy() },
            )
        }
    }
}

private fun embedHtml(id: String) = """<html><body style="margin:0;background:#000">
<iframe style="position:fixed;inset:0;width:100%;height:100%;border:0" src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0"
 allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>"""

/** First hit of a YouTube search. */
// ponytail: scrapes the results page (no API key); swap to the YouTube Data API if the markup changes.
private fun youtubeId(query: String): String? {
    val c = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
    c.setRequestProperty("Accept-Language", "en-US")
    c.setRequestProperty("Cookie", "CONSENT=YES+1")
    val html = c.inputStream.bufferedReader().use { it.readText() }
    return Regex("\"videoId\":\"([\\w-]{11})\"").find(html)?.groupValues?.get(1)
}
