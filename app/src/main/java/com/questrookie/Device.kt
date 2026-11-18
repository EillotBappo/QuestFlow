package com.questrookie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppInfo(
    val pkg: String, val label: String, val versionName: String, val versionCode: Long,
    val apks: List<String>, val apkSize: Long, val launchable: Boolean, val isGame: Boolean,
    /** When the current build was installed or last updated. */
    val installedAt: Long,
)

/** A package is a game if the mirror lists it or Android tags it as one. */
fun isGame(ai: ApplicationInfo, mirrorPkgs: Set<String>) = ai.packageName in mirrorPkgs || ai.category == ApplicationInfo.CATEGORY_GAME

fun installedApps(ctx: Context, mirrorPkgs: Set<String>): List<AppInfo> {
    val pm = ctx.packageManager
    return pm.getInstalledPackages(0).mapNotNull { p ->
        val ai = p.applicationInfo ?: return@mapNotNull null
        if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 || p.packageName == ctx.packageName) return@mapNotNull null
        val apks = listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
        AppInfo(
            p.packageName, ai.loadLabel(pm).toString(), p.versionName.orEmpty(), p.longVersionCode,
            apks, apks.sumOf { File(it).length() }, pm.getLaunchIntentForPackage(p.packageName) != null, isGame(ai, mirrorPkgs),
            p.lastUpdateTime,
        )
    }.sortedBy { it.label.lowercase() }
}

class NeedsPairing : java.io.IOException("Pair with Wireless debugging first")

/** The headset's own adbd over loopback; turned on through Wireless debugging once the headset is paired. */
fun newInstaller(ctx: Context) = Installer(ctx.filesDir) { if (WirelessAdb.isPaired(ctx)) WirelessAdb(ctx).enableTcpip() else throw NeedsPairing() }

fun connect(s: AppState) = s.task("Connecting to headset") {
    try {
        s.installer.connect()
    } catch (e: NeedsPairing) {
        s.pairing = true
        throw e
    }
    s.refreshApps(); "Headset connected"
}

/** Headset battery level in percent, or null if Android doesn't report one. */
fun batteryPercent(ctx: Context): Int? {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else level * 100 / scale
}

/** Battery temperature in °C, or null if Android doesn't report one. */
fun batteryTempC(ctx: Context): Float? {
    val t = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: return null
    return if (t == Int.MIN_VALUE) null else t / 10f
}

/** Minutes under an hour, otherwise hours to one decimal place. */
fun formatHours(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes} min" else "%.1f h".format(ms / 3_600_000.0)
}

/** True when the library lists a newer build than the installed one. */
private fun hasUpdate(a: AppInfo, game: Game?) = game != null && (game.version.toLongOrNull() ?: 0) > a.versionCode

private enum class Kind(val label: String) { All("All"), Games("Games"), Apps("Apps") }

private enum class AppSort(val label: String) {
    Name("Name"), Size("Largest"), LastPlayed("Last played"), Installed("Recently installed"),
}

/** Each package's newest release across the store's lists, to tell what an installed app can update to. */
fun latestByPkg(games: List<Game>): Map<String, Game> = games.groupBy { it.pkg }.mapValues { (_, l) -> l.maxBy { it.version.toLongOrNull() ?: 0 } }

@Composable
fun InstalledScreen(s: AppState, picker: @Composable () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(Kind.All) }
    var sort by rememberSaveable { mutableStateOf(AppSort.Name) }
    var updatesOnly by rememberSaveable { mutableStateOf(false) }
    var hiddenOnly by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(ViewMode.List) }
    val latest = remember(s.games) { latestByPkg(s.games) }
    val updates = remember(s.apps, latest) { s.apps.count { hasUpdate(it, latest[it.pkg]) } }
    val hidden = remember(s.apps, s.disabled) { s.apps.count { it.pkg in s.disabled } }
    val shown = remember(s.apps, query, kind, sort, updatesOnly, hiddenOnly, latest, s.lastPlayed, s.playtime, s.obbSizes, s.disabled) {
        s.apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
            .filter { kind == Kind.All || (kind == Kind.Games) == it.isGame }
            .filter { !updatesOnly || hasUpdate(it, latest[it.pkg]) }
            .filter { !hiddenOnly || it.pkg in s.disabled }
            .let { l ->
                when (sort) {
                    AppSort.Size -> l.sortedByDescending { it.apkSize + (s.obbSizes[it.pkg] ?: 0) }
                    AppSort.LastPlayed -> l.sortedByDescending { s.lastPlayed[it.pkg] ?: 0L }
                    AppSort.Installed -> l.sortedByDescending { it.installedAt }
                    AppSort.Name -> l
                }
            }
    }
    val total = remember(s.apps, s.obbSizes) { s.apps.sumOf { it.apkSize + (s.obbSizes[it.pkg] ?: 0) } }
    Column(Modifier.fillMaxSize()) {
        TopBar {
            ViewToggle(view) { view = it }
            SearchPill(query, { query = it }, "Search apps")
        }
        Toolbar("${count(s.apps.size, "app")} · ${formatSize(total)}" + (if (s.weeklyPlaytime > 0) " · ${formatHours(s.weeklyPlaytime)} played this week" else "")) {
            picker()
            Picker(sort, AppSort.entries, { it.label }, icon = QIcons.Sort) { sort = it }
            Segmented(Kind.entries.map { Seg(it.label) }, kind.ordinal, { kind = Kind.entries[it] })
            ToggleChip("Updates ($updates)", updatesOnly, { updatesOnly = !updatesOnly })
            ToggleChip("Hidden ($hidden)", hiddenOnly, { hiddenOnly = !hiddenOnly })
        }
        if (!s.hasUsageAccess()) Banner("Grant usage access to sort by last played.", "Grant over ADB") { s.grantUsageAccess() }
        if (!s.installer.connected) Banner("Connect over ADB to back up, uninstall and see OBB sizes.", "Connect") { connect(s) }
        if (shown.isEmpty()) EmptyState(
            Icons.Default.CheckCircle, "No apps",
            when {
                s.apps.isEmpty() -> "Nothing sideloaded yet."
                query.isNotEmpty() -> "No apps match \"$query\"."
                else -> "None of your apps match these filters."
            },
        )
        else LazyVerticalGrid(
            view.cells(), Modifier.weight(1f), contentPadding = PaddingValues(bottom = Space.xxl),
            horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            items(shown, key = { it.pkg }) { a ->
                if (view == ViewMode.Icons) InstalledTile(s, a, latest[a.pkg]) else InstalledRow(s, a, latest[a.pkg], compact = view == ViewMode.Columns)
            }
        }
    }
}

/** An installed app's art: the store's art when a list has it, else its launcher icon. */
@Composable
private fun InstalledArt(s: AppState, a: AppInfo, game: Game?, modifier: Modifier) =
    if (game != null) GameArt(s, game, modifier) else AppArt(a.pkg, a.label, modifier) { s.ctx.packageManager.getApplicationIcon(a.pkg) }

@Composable
private fun installedTint(s: AppState, a: AppInfo, game: Game?): Color? =
    if (game != null) gameTint(s, game) else rememberTint(rememberAppIcon(a.pkg) { s.ctx.packageManager.getApplicationIcon(a.pkg) })

/**
 * The tuner row: a game shows what it launches with (refresh rate, resolution, CPU/GPU) and its profile; tapping it opens
 * [TuneDialog]. Other apps show version, size and playtime and have no tap action.
 */
@Composable
fun InstalledRow(s: AppState, a: AppInfo, game: Game?, compact: Boolean = false) {
    val obb = s.obbSizes[a.pkg]
    val update = hasUpdate(a, game)
    var tune by remember { mutableStateOf(false) }
    if (tune) TuneDialog(s, a, game) { tune = false }
    val size = StatItem(QIcons.Storage, formatSize(a.apkSize + (obb ?: 0)))
    val stats = if (a.isGame) listOf("refresh" to QIcons.Speed, "texture" to QIcons.Resolution, "perf" to QIcons.Memory).mapNotNull { (k, icon) ->
        TWEAKS.firstOrNull { it.key == k }?.let { t ->
            val v = TweakStore.getFor(s.ctx, a.pkg, t)
            StatItem(icon, t.options.firstOrNull { it.second == v }?.first ?: v)
        }
    } + size
    else listOfNotNull(StatItem(QIcons.Tag, "v${a.versionName}"), size, s.playtime[a.pkg]?.takeIf { it > 0 }?.let { StatItem(QIcons.Schedule, formatHours(it)) })
    // Measured, not guessed: the last session's frame stats and what to change, from Automation.
    val perf = Automation.lastPerf(s.ctx, a.pkg)
    MediaRow(
        title = a.label, art = { InstalledArt(s, a, game, it) }, tint = installedTint(s, a, game),
        onClick = if (a.isGame) ({ tune = true }) else null, stats = stats, compact = compact,
        detail = "${a.pkg} · v${a.versionName} · APK ${formatSize(a.apkSize)}" + (obb?.let { " · OBB ${formatSize(it)}" } ?: "") +
            (s.playtime[a.pkg]?.takeIf { it > 0 }?.let { " · played ${formatHours(it)}" } ?: "") + (perf?.let { " · last session $it" } ?: ""),
        detailColor = if (perf != null && "—" in perf) Orange else Palette.Dim,
        status = if (a.isGame) profileName(s.ctx, a.pkg) else "App",
        tags = {
            if (update) Tag("Update v${game!!.version}", Orange, Palette.Ink)
            if (a.pkg in s.disabled) Tag("Disabled")
        },
        actions = {
            if (update) PillButton("Update", { s.install(game!!) }, icon = QIcons.Sync, tone = Tone.Accent)
            if (a.launchable && a.pkg !in s.disabled && !(compact && update)) PillButton("Open", { s.launch(a.pkg) }, icon = QIcons.OpenInNew, tone = Tone.Glass)
            if (a.isGame && !compact) FavStar(s, a.pkg)
            AppMenu(s, a)
        },
    )
}

@Composable
private fun InstalledTile(s: AppState, a: AppInfo, game: Game?) {
    val update = hasUpdate(a, game)
    var tune by remember { mutableStateOf(false) }
    if (tune) TuneDialog(s, a, game) { tune = false }
    ArtTile(
        title = a.label, art = { InstalledArt(s, a, game, it) }, tint = installedTint(s, a, game),
        onClick = if (a.isGame) ({ tune = true }) else null,
        subtitle = "v${a.versionName} · ${formatSize(a.apkSize + (s.obbSizes[a.pkg] ?: 0))}" + if (a.isGame) " · " + profileName(s.ctx, a.pkg) else "",
        badges = {
            if (update) Tag("Update", Orange, Palette.Ink)
            if (a.pkg in s.disabled) Tag("Disabled")
        },
        actions = {
            if (update) PillButton("Update", { s.install(game!!) }, tone = Tone.Accent)
            else if (a.launchable && a.pkg !in s.disabled) PillButton("Open", { s.launch(a.pkg) }, icon = QIcons.OpenInNew, tone = Tone.Glass)
            Spacer(Modifier.weight(1f))
            if (a.isGame) FavStar(s, a.pkg)
            AppMenu(s, a)
        },
    )
}

/** The ⋮ on every installed app: backups, enable/disable, restore saves, uninstall (confirmed). All need ADB. */
@Composable
private fun AppMenu(s: AppState, a: AppInfo) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Box {
        IconPill(Icons.Default.MoreVert, "More", { menu = true })
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text("Back up APK, OBB + saves") }, enabled = s.installer.connected,
                onClick = { menu = false; s.task("Backing up ${a.label}") { "Saved to ${s.installer.backup(a)}" } },
            )
            val off = a.pkg in s.disabled
            DropdownMenuItem(
                text = { Text(if (off) "Enable app" else "Disable app") }, enabled = s.installer.connected,
                onClick = {
                    menu = false
                    s.task(if (off) "Enabling ${a.label}" else "Disabling ${a.label}") {
                        s.installer.setEnabled(a.pkg, enabled = off)
                        s.refreshApps()
                        if (off) "Enabled ${a.label}" else "Disabled ${a.label}"
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Restore saves") }, enabled = s.installer.connected,
                onClick = {
                    menu = false
                    s.task("Restoring saves for ${a.label}") {
                        if (s.installer.restoreSaves(a.pkg)) "Saves restored for ${a.label}" else "No save backup for ${a.label}"
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Uninstall", color = if (s.installer.connected) Red else Color.Unspecified) },
                enabled = s.installer.connected, onClick = { menu = false; confirm = true },
            )
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Palette.Panel,
        title = { Text("Uninstall ${a.label}?") },
        text = { Text("This removes the app and its OBB from the headset. Its saves in Android/data are backed up first; use Restore saves after reinstalling.", color = Palette.Dim) },
        confirmButton = {
            PillButton("Uninstall", {
                confirm = false
                s.task("Uninstalling ${a.label}") { s.installer.uninstall(a.pkg); s.refreshApps(); "Uninstalled ${a.label}" }
            }, tone = Tone.Danger)
        },
        dismissButton = { PillButton("Cancel", { confirm = false }) },
    )
}

private const val GLOBAL = "Use global tweaks"
private const val CUSTOM = "Custom"

/** A label over its control, a tuner panel's building block. */
@Composable
private fun TuneField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.Dim)
        content()
    }

/**
 * One game's own headset tweaks, tuner style: a profile pick (global, a preset, or Custom) over a two-column grid
 * of pickers. Saved on each pick and applied the next time the game launches. [game] tints the panel with its art.
 */
@Composable
fun TuneDialog(s: AppState, a: AppInfo, game: Game? = null, onDismiss: () -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply { TWEAKS.forEach { put(it.key, TweakStore.getFor(s.ctx, a.pkg, it)) } } }
    var own by remember { mutableStateOf(TweakStore.hasProfile(s.ctx, a.pkg)) }
    val tint = installedTint(s, a, game)
    val profile = if (!own) GLOBAL else matchingPreset { values[it.key].orEmpty() } ?: CUSTOM
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tint?.let { lerp(Palette.Panel, it, .5f) } ?: Palette.Panel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                InstalledArt(s, a, game, Modifier.height(48.dp).aspectRatio(16 / 9f).clip(RoundedCornerShape(Radius.chip)))
                Text(a.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Automation.lastPerf(s.ctx, a.pkg)?.let { Text("Last session $it", style = MaterialTheme.typography.bodySmall, color = if ("—" in it) Orange else Palette.Dim) }
                TuneField("Profile") {
                    val options = listOf(GLOBAL) + PRESETS.map { it.first } + listOfNotNull(CUSTOM.takeIf { profile == CUSTOM })
                    DropPill(options.map { it to it }, profile, Modifier.fillMaxWidth()) { pick ->
                        when (pick) {
                            GLOBAL -> { TweakStore.clearProfile(s.ctx, a.pkg); TWEAKS.forEach { values[it.key] = TweakStore.get(s.ctx, it) }; own = false }
                            CUSTOM -> Unit
                            else -> {
                                val preset = PRESETS.first { it.first == pick }.second
                                TWEAKS.forEach { t -> preset[t.key].orEmpty().let { v -> TweakStore.setFor(s.ctx, a.pkg, t, v); values[t.key] = v } }
                                own = true
                            }
                        }
                    }
                }
                // Two per row, like a tuner's manual-tuning panel.
                TWEAKS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        pair.forEach { t ->
                            TuneField(t.label, Modifier.weight(1f)) {
                                DropPill(t.options, values[t.key].orEmpty(), Modifier.fillMaxWidth()) { v -> TweakStore.setFor(s.ctx, a.pkg, t, v); values[t.key] = v; own = true }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            if (a.launchable && a.pkg !in s.disabled) PillButton("Open", { onDismiss(); s.launch(a.pkg) }, icon = QIcons.OpenInNew, tone = Tone.Accent)
            else PillButton("Close", onDismiss)
        },
        dismissButton = { if (a.launchable && a.pkg !in s.disabled) PillButton("Close", onDismiss) },
    )
}

class LocalApk(
    val pkg: String, val label: String, val versionName: String, val versionCode: Long,
    val files: List<File>, val obb: File?, val size: Long, val info: ApplicationInfo,
)

fun hasStorageAccess(ctx: Context) =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** APKs on shared storage; APKs of one package in one folder install together (splits), a <pkg>/ folder next to them is the OBB. */
fun scanLocal(ctx: Context): List<LocalApk> {
    val root = Environment.getExternalStorageDirectory()
    val pm = ctx.packageManager
    return root.walkTopDown().maxDepth(5)
        .onEnter { it == root || !(it.name.startsWith('.') || (it.parentFile == root && it.name == "Android")) }
        .filter { it.isFile && it.extension.equals("apk", true) }
        .mapNotNull { f -> pm.getPackageArchiveInfo(f.path, 0)?.let { f to it } }
        .groupBy { (f, p) -> f.parent + "|" + p.packageName }
        .mapNotNull { (_, group) ->
            val (base, info) = group.firstOrNull { !it.first.name.startsWith("split_") } ?: group.first()
            val ai = info.applicationInfo?.apply { sourceDir = base.path; publicSourceDir = base.path } ?: return@mapNotNull null
            val files = group.map { it.first }
            val obb = File(base.parentFile, info.packageName).takeIf { it.isDirectory }
            val size = files.sumOf { it.length() } + (obb?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0)
            LocalApk(info.packageName, ai.loadLabel(pm).toString(), info.versionName.orEmpty(), info.longVersionCode, files, obb, size, ai)
        }.sortedBy { it.label.lowercase() }
}

@Composable
fun LocalScreen(s: AppState, picker: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(hasStorageAccess(s.ctx)) }
    var scan by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf("") }
    val found by produceState<List<LocalApk>?>(null, granted, scan) {
        value = null
        if (granted) value = withContext(Dispatchers.IO) { runCatching { scanLocal(s.ctx) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar()
        Toolbar("APKs already on the headset: Download, QuestRookie/Backups and other folders") {
            picker()
            PillButton("Rescan", { scan++ }, icon = Icons.Default.Refresh, enabled = granted)
            InputPill(link, { link = it }, "Paste an APK link (https://…)", Modifier.width(380.dp), icon = QIcons.Download)
            PillButton("Install link", { s.installUrl(link.trim()); link = "" }, tone = Tone.Accent, enabled = link.isNotBlank())
        }
        if (granted && !s.installer.connected) Banner("Without ADB, Android asks you to confirm each APK; games with OBB data still need ADB.", "Connect") { connect(s) }
        val list = found
        when {
            !granted -> EmptyState(
                Icons.Default.Lock, "Storage access needed",
                "Quest Rookie needs access to shared storage to find APK files. It can grant this to itself over ADB.",
            ) {
                PillButton("Grant over ADB", {
                    s.task("Granting storage access") {
                        val pkg = s.ctx.packageName
                        s.installer.shell("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow; pm grant $pkg android.permission.READ_EXTERNAL_STORAGE; true")
                        granted = hasStorageAccess(s.ctx)
                        if (granted) "Storage access granted" else "Couldn't grant storage access"
                    }
                }, tone = Tone.Accent, enabled = s.busy == null)
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
            list.isEmpty() -> EmptyState(Icons.Default.Search, "No APKs found", "Copy APKs to the headset's Download folder, or back up an installed app.")
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.md), contentPadding = PaddingValues(bottom = Space.xxl)) {
                items(list, key = { it.files.first().path }) { LocalRow(s, it) }
            }
        }
    }
}

@Composable
private fun LocalRow(s: AppState, a: LocalApk) {
    val installed = s.versions[a.pkg]
    val key = "local:" + a.files.first().path
    val icon = { a.info.loadIcon(s.ctx.packageManager) }
    val folder = a.files.first().parent.orEmpty().removePrefix(Environment.getExternalStorageDirectory().path).ifEmpty { "/" }
    val (status, color) = when {
        installed == null -> "" to Palette.Dim
        installed < a.versionCode -> "Newer than installed" to Orange
        installed == a.versionCode -> "Installed" to Green
        else -> "Older than installed" to Palette.Dim
    }
    MediaRow(
        title = a.label, art = { AppArt(key, a.label, it, icon) }, tint = rememberTint(rememberAppIcon(key, icon)),
        stats = listOf(StatItem(QIcons.Tag, "v${a.versionName}"), StatItem(QIcons.Storage, formatSize(a.size)), StatItem(QIcons.Folder, folder)),
        detail = a.pkg, status = status, statusColor = color,
        tags = {
            if (a.info.category == ApplicationInfo.CATEGORY_GAME) Tag("Game")
            if (a.obb != null) Tag("OBB")
        },
        actions = { PillButton("Install", { s.installLocal(a) }, icon = QIcons.Download, tone = Tone.Accent) },
    )
}

/** Installed apps, storage and APK files on the headset: three sub-pages behind one switch in the toolbar. */
@Composable
fun MyGamesScreen(s: AppState) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val picker: @Composable () -> Unit = {
        Segmented(listOf(Seg("Installed", QIcons.Gamepad), Seg("Storage", QIcons.Storage), Seg("Local files", QIcons.Folder)), page, { page = it })
    }
    when (page) { 0 -> InstalledScreen(s, picker); 1 -> StorageScreen(s, picker); else -> LocalScreen(s, picker) }
}
