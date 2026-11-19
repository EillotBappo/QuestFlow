package com.questflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Per-app storage, plus OBB and save folders left behind by apps that are no longer installed. */
@Composable
fun StorageScreen(s: AppState, picker: @Composable () -> Unit) {
    var removing by remember { mutableStateOf<String?>(null) }
    val rows = remember(s.apps, s.obbSizes, s.dataSizes) {
        s.apps.sortedByDescending { it.apkSize + (s.obbSizes[it.pkg] ?: 0) + (s.dataSizes[it.pkg] ?: 0) }
    }
    // Without the package list every folder looks orphaned, so leftovers only show once it has loaded.
    val leftovers = remember(s.obbSizes, s.dataSizes, s.packages) {
        if (s.packages.isEmpty()) emptyList()
        else (s.obbSizes.keys + s.dataSizes.keys).filter { it !in s.packages && it != s.ctx.packageName }.sorted()
    }
    Column(Modifier.fillMaxSize()) {
        TopBar()
        Toolbar("${formatSize(s.freeSpace())} free") { picker(); StorageBar(s) }
        if (!s.installer.connected) Banner("Connect over ADB to see OBB and save sizes.", "Connect") { connect(s) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.md), contentPadding = PaddingValues(bottom = Space.xxl)) {
            item { Heading("Installed apps") }
            items(rows, key = { it.pkg }) { a ->
                // OBB and save sizes come over ADB; until then they're unknown, not zero.
                val obb = s.obbSizes[a.pkg]
                val saves = s.dataSizes[a.pkg]
                val icon = { s.ctx.packageManager.getApplicationIcon(a.pkg) }
                MediaRow(
                    title = a.label, art = { AppArt(a.pkg, a.label, it, icon) }, tint = rememberTint(rememberAppIcon(a.pkg, icon)),
                    stats = listOfNotNull(StatItem(QIcons.Download, "APK ${formatSize(a.apkSize)}"), obb?.let { StatItem(QIcons.Folder, "OBB ${formatSize(it)}") }, saves?.let { StatItem(QIcons.Storage, "Saves ${formatSize(it)}") }),
                    status = formatSize(a.apkSize + (obb ?: 0) + (saves ?: 0)), statusColor = Palette.Text,
                )
            }
            if (leftovers.isNotEmpty()) {
                item { Heading("Left behind by uninstalled apps") }
                items(leftovers, key = { "left/$it" }) { pkg ->
                    val obb = s.obbSizes[pkg]
                    val saves = s.dataSizes[pkg]
                    MediaRow(
                        title = pkg, art = { IconTile(null, pkg.substringAfterLast('.'), it) }, tint = null,
                        stats = listOfNotNull(obb?.let { StatItem(QIcons.Folder, "OBB ${formatSize(it)}") }, saves?.let { StatItem(QIcons.Storage, "Saves ${formatSize(it)}") }),
                        actions = {
                            if (saves != null) PillButton(
                                "Back up saves", { s.task("Backing up saves for $pkg") { if (s.installer.backupSaves(pkg)) "Saves backed up for $pkg" else "No saves for $pkg" } },
                                tone = Tone.Glass, enabled = s.busy == null,
                            )
                            if (obb != null) PillButton("Remove OBB", { removing = pkg }, tone = Tone.Danger, enabled = s.busy == null)
                        },
                    )
                }
            }
        }
    }
    removing?.let { pkg ->
        AlertDialog(
            onDismissRequest = { removing = null }, containerColor = Palette.Panel,
            title = { Text("Remove OBB for $pkg?") },
            text = { Text("Deletes the leftover OBB folder. Its saves in Android/data are not touched.", color = Palette.Dim) },
            confirmButton = {
                PillButton("Remove", {
                    removing = null
                    s.task("Removing OBB for $pkg") { s.installer.removeObb(pkg); s.refreshApps(); "Removed OBB for $pkg" }
                }, tone = Tone.Danger)
            },
            dismissButton = { PillButton("Cancel", { removing = null }) },
        )
    }
}
