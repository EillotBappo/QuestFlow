package com.questflow

import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** At-a-glance headset state plus what people do most: recent games, presets, storage and connection. */
@Composable
fun HomeScreen(s: AppState) {
    Column(Modifier.fillMaxSize()) {
        TopBar()
        // Staggered: the panels differ a lot in height.
        LazyVerticalStaggeredGrid(
            StaggeredGridCells.Adaptive(340.dp), Modifier.weight(1f), contentPadding = PaddingValues(bottom = Space.xxl),
            horizontalArrangement = Arrangement.spacedBy(Space.lg), verticalItemSpacing = Space.lg,
        ) {
            item(key = "recent", span = StaggeredGridItemSpan.FullLine) { RecentGames(s) }
            item(key = "headset") { HeadsetPanel(s) }
            item(key = "presets") { PresetsPanel(s) }
            item(key = "storage") { StorageConnectionPanel(s) }
        }
    }
}

/** The last three games played, as the same rows My games uses: Open, star, tap to tune. */
@Composable
private fun RecentGames(s: AppState) {
    val recent = remember(s.apps, s.lastPlayed) {
        s.apps.filter { it.isGame && (s.lastPlayed[it.pkg] ?: 0L) > 0 }.sortedByDescending { s.lastPlayed[it.pkg] }.take(3)
    }
    val latest = remember(s.games) { latestByPkg(s.games) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Heading("Continue playing")
        if (recent.isEmpty()) Text("Games you play show up here.", style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
        recent.forEach { a -> InstalledRow(s, a, latest[a.pkg]) }
    }
}

private data class HeadsetNow(val pct: Int?, val charging: Boolean, val temp: Float?, val thermal: Int, val rate: Int?)

private val THERMAL = listOf("Normal", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown")

@Composable
private fun HeadsetPanel(s: AppState) {
    val power = remember { s.ctx.getSystemService(PowerManager::class.java) }
    fun read() = HeadsetNow(batteryPercent(s.ctx), isCharging(s.ctx), batteryTempC(s.ctx), power.currentThermalStatus, currentRefreshRate(s.ctx))
    // Android sends Compose no cheap change callback for any of these, so poll every 5 s like Settings does for the rate.
    val now by produceState(read()) { while (true) { kotlinx.coroutines.delay(5_000); value = read() } }
    val (pct, charging, temp, thermal, rate) = now
    Panel(MODEL.name, icon = QIcons.Gamepad) {
        pct?.let {
            StatLine("Battery", "$it%" + if (charging) " · charging" else "", when { it < 20 -> Red; it < 50 -> Orange; else -> Green })
        }
        // Same "hot" line as Automation: the system's own thermal status at Moderate or worse.
        val hot = thermal >= PowerManager.THERMAL_STATUS_MODERATE
        StatLine(
            "Heat", listOfNotNull(temp?.let { "%.1f °C".format(java.util.Locale.US, it) }, THERMAL.getOrNull(thermal)).joinToString(" · "),
            if (hot) Orange else Palette.Text,
        )
        rate?.let { StatLine("Refresh rate", "$it Hz") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsPanel(s: AppState) {
    val values = remember { mutableStateMapOf<String, String>().apply { TWEAKS.forEach { put(it.key, TweakStore.get(s.ctx, it)) } } }
    Panel("Performance", hint = "Applies to every game that has no tweaks of its own.", icon = QIcons.Tune) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) { PresetButtons(s, values) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageConnectionPanel(s: AppState) {
    Panel("Storage and connection", icon = QIcons.Storage) {
        StorageBar(s, Modifier.fillMaxWidth())
        StatLine("Free space", formatSize(s.freeSpace()))
        StatLine("ADB", if (s.installer.connected) "Connected" else "Not connected", if (s.installer.connected) Green else Palette.Dim)
        val n = s.queue.activeCount
        StatLine("Downloads", if (n > 0) "$n running" else "None running")
        if (!s.installer.connected) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PillButton("Connect", { connect(s) }, icon = QIcons.Usb, tone = Tone.Accent, enabled = s.busy == null)
            if (!WirelessAdb.isPaired(s.ctx)) PillButton("Pair", { s.pairing = true })
        }
    }
}
