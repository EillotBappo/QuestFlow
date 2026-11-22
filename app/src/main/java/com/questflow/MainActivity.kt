package com.questflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Shortcuts.handle(this, intent)) return finish()
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val rookie = Rookie(getExternalFilesDir(null)!!, insecureTls = debuggable).apply { runCatching { loadConfig() } }
        val installer = newInstaller(this)
        setContent {
            QuestTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Base, contentColor = Palette.Text) { App(rookie, installer) }
            }
        }
    }

    // Settings → App size scales the activity's density itself, so dialogs, menus and popups (each its own window)
    // match the page. Text shrinks by the scale again through fontScale, as it always has here.
    override fun attachBaseContext(base: android.content.Context) {
        val scale = base.getSharedPreferences("ui", MODE_PRIVATE).getFloat("scale", 0.8f)
        val config = android.content.res.Configuration(base.resources.configuration).apply {
            densityDpi = (densityDpi * scale).toInt()
            fontScale *= scale
            // The screen holds more dp at a smaller density; left alone, full-screen dialogs size to 80% of it.
            screenWidthDp = (screenWidthDp / scale).toInt()
            screenHeightDp = (screenHeightDp / scale).toInt()
            smallestScreenWidthDp = (smallestScreenWidthDp / scale).toInt()
        }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    /** Y cycles tabs whatever has focus; set by [App]. Unhandled, Android turns Y into Back (Virtual.kcm) or Space (Generic.kcm). */
    var onButtonY: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handler = onButtonY
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_Y && handler != null) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) handler()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (Shortcuts.handle(this, intent)) moveTaskToBack(true)
    }
}


/** The app's pages. Settings has no tab: the bottom bar's Options button opens it, like a tuner's. */
enum class Section(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Store("Store", Icons.Default.ShoppingCart),
    MyGames("My games", QIcons.Gamepad),
    Downloads("Downloads", QIcons.Download),
    Settings("Options", QIcons.Tune),
}

/** The open page, a way to switch, and the Downloads badge, for the tabs in every page's [TopBar]. Provided once by [App]. */
class Nav(val section: Section, val downloads: Int, val go: (Section) -> Unit)

val LocalNav = compositionLocalOf<Nav?> { null }

@Composable
fun App(rookie: Rookie, installer: Installer) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val s = remember { AppState(ctx, rookie, installer, scope) }
    // setprops reset on reboot; re-apply saved tweaks once per launch if ADB is reachable.
    LaunchedEffect(Unit) {
        val n = withContext(Dispatchers.IO) { runCatching { TweakStore.applySaved(ctx, installer) }.getOrNull() }
        if (n != null && n > 0) s.status = "Re-applied ${count(n, "tweak")}"
        // Game-tweak automation watches the foreground from the service, so it works when games start from Home.
        if (Automation.wanted(ctx)) KeepAliveService.start(ctx)
    }

    var section by rememberSaveable { mutableStateOf(Section.Home) }
    var help by remember { mutableStateOf(false) }
    // Back (and the controller's B, which Android turns into Back) goes to Store before it leaves the app.
    BackHandler(enabled = section != Section.Store) { section = Section.Store }
    val activity = LocalContext.current as? MainActivity
    DisposableEffect(activity) {
        activity?.onButtonY = { section = Section.entries[(section.ordinal + 1) % Section.entries.size] }
        onDispose { activity?.onButtonY = null }
    }
    val snackbar = remember { SnackbarHostState() }
    // Each new status becomes a snackbar; failures stay until dismissed and offer Retry.
    LaunchedEffect(s.status) {
        if (s.status.isEmpty()) return@LaunchedEffect
        val retry = s.retry
        val result = snackbar.showSnackbar(
            s.status, actionLabel = if (retry != null) "Retry" else null, withDismissAction = retry != null,
            duration = if (retry != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) retry?.invoke()
        s.status = ""
    }
    Scaffold(
        containerColor = Palette.Base, contentColor = Palette.Text,
        snackbarHost = {
            SnackbarHost(snackbar) {
                Snackbar(it, containerColor = Palette.Raised, contentColor = Palette.Text, actionColor = Orange, dismissActionContentColor = Palette.Dim)
            }
        },
        bottomBar = { BottomBar(s, section, { section = it }) { help = true } },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(horizontal = Space.xxl)) {
            CompositionLocalProvider(LocalNav provides Nav(section, s.queue.activeCount) { section = it }) {
                when (section) {
                    Section.Home -> HomeScreen(s)
                    Section.Store -> LibraryScreen(s)
                    Section.MyGames -> MyGamesScreen(s)
                    Section.Downloads -> DownloadsScreen(s)
                    Section.Settings -> TweaksScreen(s)
                }
            }
        }
    }
    if (s.pairing) PairDialog(s)
    if (help) HelpDialog(s) { help = false }
}

/**
 * QGT's bottom bar, on every page: what's happening now (the running download, a task, or the headset's current
 * settings when idle; tap for Downloads), the headset's vitals, then ? help and Options.
 */
@Composable
private fun BottomBar(s: AppState, section: Section, go: (Section) -> Unit, onHelp: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Size.bar).background(Palette.Bar).padding(horizontal = Space.xxl),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        NowBlock(s, Modifier.weight(1f)) { go(Section.Downloads) }
        Vitals(s)
        IconPill(QIcons.Help, "Help", onHelp)
        PillButton("Options", { go(Section.Settings) }, icon = QIcons.Tune, tone = if (section == Section.Settings) Tone.Accent else Tone.Glass)
    }
}

@Composable
private fun NowBlock(s: AppState, modifier: Modifier, onOpen: () -> Unit) {
    val items = s.queue.items
    val running = items.firstOrNull { it.state == QState.Running }
    val waiting = items.filter { it.state == QState.Waiting }
    // The display rate has no change callback; poll it like Settings does.
    val rate by produceState(currentRefreshRate(s.ctx)) { while (true) { kotlinx.coroutines.delay(5_000); value = currentRefreshRate(s.ctx) } }
    Row(
        modifier.height(Size.control).tap(RoundedCornerShape(Radius.control), onClick = onOpen).padding(end = Space.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text("Now", Modifier.fillMaxHeight().clip(RoundedCornerShape(Radius.control)).background(Palette.Glass).padding(horizontal = Space.lg).wrapContentHeight(), style = MaterialTheme.typography.titleMedium)
        when {
            running != null -> {
                Text(running.title, Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                QueueProgress(running, Modifier.width(140.dp).height(6.dp).clip(CircleShape))
                Text(
                    listOf(running.stage, running.detail).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.weight(1f, fill = false),
                    color = Palette.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (waiting.isNotEmpty()) Tag("+${waiting.size} queued")
            }
            // Held back by the Wi-Fi or charger rule: the stage says which.
            waiting.isNotEmpty() -> IconText(QIcons.Download, count(waiting.size, "download") + " waiting · " + waiting.first().stage, Modifier.weight(1f, fill = false), Palette.Dim)
            s.busy == null -> {
                IconText(QIcons.Speed, rate?.let { "$it Hz" } ?: "—")
                IconText(QIcons.Tune, "Preset: " + (matchingPreset { TweakStore.get(s.ctx, it) } ?: "Custom"))
                IconText(QIcons.Gamepad, MODEL.name, color = Palette.Dim)
            }
        }
        s.busy?.let { label ->
            CircularProgressIndicator(Modifier.size(16.dp), color = Orange, strokeWidth = 2.dp)
            Text(label, Modifier.weight(1f, fill = false), color = Palette.Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** ADB (a Connect button while it's down), battery, clock and free space. */
@Composable
private fun Vitals(s: AppState) {
    val ctx = s.ctx
    // Polled every 30 s: Android sends Compose no cheap change callback for the clock, battery or storage.
    fun read() = Triple(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date()), batteryPercent(ctx) to isCharging(ctx), s.freeSpace())
    val now by produceState(read()) { while (true) { kotlinx.coroutines.delay(30_000); value = read() } }
    val (time, battery, free) = now
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
        if (s.installer.connected) IconText(QIcons.Usb, "ADB", color = Green)
        else PillButton("Connect ADB", { connect(s) }, icon = QIcons.Usb, tone = Tone.Glass, enabled = s.busy == null)
        battery.first?.let { pct ->
            // Red under 20%, orange under 50%; "+" while charging.
            IconText(QIcons.Battery, "$pct%" + if (battery.second) "+" else "", color = when { pct < 20 -> Red; pct < 50 -> Orange; else -> Green })
        }
        IconText(QIcons.Storage, "${formatSize(free)} free")
        Text(time, style = MaterialTheme.typography.titleMedium)
    }
}

/** The "?" button: what each part does and the controller shortcuts, plus a way to pair the headset. */
@Composable
private fun HelpDialog(s: AppState, onClose: () -> Unit) {
    @Composable
    fun Item(icon: ImageVector, title: String, body: String) = Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = Orange)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
        }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Palette.Panel,
        title = { Text("How QuestFlow works") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Item(Icons.Default.ShoppingCart, "Store", "Every source's games and apps. Install, Update or Open right from a row; tap a row for its page, trailer and screenshots. The star marks a favourite.")
                Item(QIcons.ViewList, "List, two columns or icons", "The buttons beside Search change how the Store and My games lay out. Sort, source and filters sit in the row under the header.")
                Item(QIcons.Gamepad, "My games", "What's installed, what uses the space, and APK files on the headset. Tap a game to tune it: refresh rate, resolution, CPU/GPU level and more, applied each time it starts.")
                Item(QIcons.Download, "Downloads", "Installs run one after another. The Now bar at the bottom shows the current one; tap it to see the queue.")
                Item(QIcons.Tune, "Options", "Headset tweaks for every game without its own, presets, automation, sources, the headset's username and app size.")
                Item(QIcons.Usb, "ADB", "Installing OBB data, uninstalling, backups and tweaks need ADB. Pair once with Wireless debugging and QuestFlow connects to itself, no PC needed.")
                Item(QIcons.Gamepad, "Controller", "Y switches page. B goes back to the Store. The D-pad or thumbstick moves between buttons; the focused one has an orange ring.")
            }
        },
        confirmButton = { PillButton("Close", onClose, tone = Tone.Accent) },
        dismissButton = { if (!s.installer.connected) PillButton("Pair headset", { onClose(); s.pairing = true }, icon = QIcons.Usb) },
    )
}

/**
 * Android's own Developer options, never Meta's: Horizon OS hands the bare settings action to its own Settings app, which
 * has no Wireless debugging. So every try names com.android.settings, most direct first: the page by its Android 12+
 * name, then its Android 10–11 name, then whatever native page takes the action, and last Android's main Settings page.
 * Wireless debugging itself has no activity of its own to open. False when none would open.
 */
private fun openNativeDeveloperOptions(ctx: android.content.Context): Boolean {
    val native = "com.android.settings"
    return listOf(
        android.content.Intent().setClassName(native, "$native.Settings\$DevelopmentSettingsActivity"),
        android.content.Intent().setClassName(native, "$native.Settings\$DevelopmentSettingsDashboardActivity"),
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).setPackage(native),
        android.content.Intent().setClassName(native, "$native.Settings"),
    ).any { runCatching { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
}

/** One-time Wireless debugging pairing, so the headset never needs a PC for `adb tcpip`. */
@Composable
private fun PairDialog(s: AppState) {
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { s.pairing = false }, containerColor = Palette.Panel,
        title = { Text("Pair with headset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text("1. Open Android's Developer options (if it opens on Android's main Settings page: System → Developer options) and turn on Wireless debugging.\n2. Tap \"Pair device with pairing code\".\n3. Enter the IP address & port it shows (or just the port) and the 6-digit code here.", color = Palette.Dim)
                PillButton("Open Android Developer options", {
                    if (!openNativeDeveloperOptions(s.ctx)) s.status = "Couldn't open Android's Developer options. Enable Developer mode in the Meta Horizon app first"
                }, icon = QIcons.OpenInNew)
                InputPill(port, { port = it.filter { c -> c.isDigit() || c == '.' || c == ':' } }, "IP address & port, e.g. 192.168.1.20:37123", Modifier.fillMaxWidth())
                InputPill(code, { code = it.filter(Char::isDigit) }, "Pairing code (6 digits)", Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            PillButton("Pair", {
                s.pairing = false
                s.task("Pairing") {
                    WirelessAdb(s.ctx).pairWith(port.substringBeforeLast(':', "127.0.0.1"), port.substringAfterLast(':').toInt(), code)
                    s.installer.connect()
                    s.refreshApps()
                    "Paired and connected"
                }
            }, tone = Tone.Accent, enabled = port.substringAfterLast(':').toIntOrNull() != null && code.length == 6)
        },
        dismissButton = { PillButton("Cancel", { s.pairing = false }) },
    )
}
