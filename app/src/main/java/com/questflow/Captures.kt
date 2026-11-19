package com.questflow

import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Environment
import android.text.format.DateUtils
import android.util.Size as PxSize
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A screenshot or recording the headset saved. */
class Capture(val file: File, val video: Boolean)

/** Where Horizon OS saves captures, under shared storage. */
private val CAPTURE_DIRS = listOf("Oculus/Screenshots", "Oculus/VideoShots")
private val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp")
private val VIDEO_TYPES = setOf("mp4", "webm", "mkv")

/** Everything in [dirs] that is a picture or a video, newest first. Other files (thumbnails' sidecars, partial recordings) are left out. */
fun scanCaptures(dirs: List<File>): List<Capture> = dirs.flatMap { it.listFiles().orEmpty().toList() }
    .filter { it.isFile }
    .mapNotNull { f ->
        when (f.extension.lowercase()) {
            in IMAGE_TYPES -> Capture(f, video = false)
            in VIDEO_TYPES -> Capture(f, video = true)
            else -> null
        }
    }
    .sortedByDescending { it.file.lastModified() }

private enum class CaptureKind(val label: String) { All("All"), Screenshots("Screenshots"), Videos("Videos") }

/** The headset's screenshots and recordings: look at them, copy one to Download, or delete it. */
@Composable
fun CapturesScreen(s: AppState, picker: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(hasStorageAccess(s.ctx)) }
    var scan by remember { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf(CaptureKind.All) }
    var viewing by remember { mutableStateOf<Capture?>(null) }
    var deleting by remember { mutableStateOf<Capture?>(null) }
    val root = remember { Environment.getExternalStorageDirectory() }
    val found by produceState<List<Capture>?>(null, granted, scan) {
        if (granted) value = withContext(Dispatchers.IO) { runCatching { scanCaptures(CAPTURE_DIRS.map { File(root, it) }) }.getOrDefault(emptyList()) }
    }
    val shown = remember(found, kind) { found?.filter { kind == CaptureKind.All || (kind == CaptureKind.Videos) == it.video } }
    Column(Modifier.fillMaxSize()) {
        TopBar()
        Toolbar(shown?.let { l -> "${count(l.size, "capture")} · ${formatSize(l.sumOf { it.file.length() })}" }.orEmpty()) {
            picker()
            Segmented(CaptureKind.entries.map { Seg(it.label) }, kind.ordinal, { kind = CaptureKind.entries[it] })
            PillButton("Rescan", { scan++ }, icon = Icons.Default.Refresh, enabled = granted)
        }
        when {
            !granted -> StorageAccessNeeded(s, "QuestFlow needs access to shared storage to show the headset's screenshots and recordings. It can grant this to itself over ADB.") { granted = it }
            shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
            shown.isEmpty() -> EmptyState(QIcons.Image, "No captures", "Screenshots and recordings you take in the headset show up here.")
            else -> LazyVerticalGrid(
                GridCells.Adaptive(240.dp), Modifier.weight(1f), contentPadding = PaddingValues(bottom = Space.xxl),
                horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                items(shown, key = { it.file.path }) { c -> CaptureTile(s, c, { viewing = c }) { deleting = c } }
            }
        }
    }
    viewing?.let { CaptureViewer(it) { viewing = null } }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Palette.Panel,
            title = { Text("Delete ${c.file.name}?") },
            text = { Text("This removes the ${if (c.video) "recording" else "screenshot"} from the headset. It can't be undone.", color = Palette.Dim) },
            confirmButton = {
                PillButton("Delete", {
                    deleting = null
                    s.task("Deleting ${c.file.name}") {
                        check(c.file.delete()) { "Couldn't delete ${c.file.name}" }
                        // The headset's own gallery reads the media index, so tell it the file is gone.
                        MediaScannerConnection.scanFile(s.ctx, arrayOf(c.file.path), null, null)
                        scan++
                        "Deleted ${c.file.name}"
                    }
                }, tone = Tone.Danger)
            },
            dismissButton = { PillButton("Cancel", { deleting = null }) },
        )
    }
}

/** A capture's picture: the screenshot itself, or a frame of the recording. */
@Composable
private fun rememberCaptureThumb(c: Capture) = rememberImage("capture:${c.file.path}:${c.file.lastModified()}") {
    if (c.video) ThumbnailUtils.createVideoThumbnail(c.file, PxSize(640, 360), null) else decodeSampled(c.file, 640)
}

@Composable
private fun CaptureTile(s: AppState, c: Capture, onOpen: () -> Unit, onDelete: () -> Unit) {
    val thumb = rememberCaptureThumb(c)
    ArtTile(
        title = c.file.name,
        art = { m ->
            if (thumb != null) Image(thumb, null, m.background(Palette.Raised), contentScale = ContentScale.Crop) else Shimmer(m)
        },
        tint = rememberTint(thumb), onClick = onOpen,
        subtitle = DateUtils.getRelativeTimeSpanString(c.file.lastModified()).toString() + " · " + formatSize(c.file.length()),
        badges = { if (c.video) Tag("Video") },
        actions = {
            PillButton("Copy to Download", {
                s.task("Copying ${c.file.name}") {
                    val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), c.file.name)
                    c.file.copyTo(dest, overwrite = true)
                    MediaScannerConnection.scanFile(s.ctx, arrayOf(dest.path), null, null)
                    "Copied to Download/${c.file.name}"
                }
            }, tone = Tone.Glass, enabled = s.busy == null)
            Spacer(Modifier.weight(1f))
            IconPill(Icons.Default.Delete, "Delete", onDelete)
        },
    )
}

/** A capture full screen: a screenshot fitted to the window, or a recording that plays on a loop (tap to pause). Back closes. */
@Composable
private fun CaptureViewer(c: Capture, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .92f)), contentAlignment = Alignment.Center) {
            if (c.video) {
                var paused by remember { mutableStateOf(false) }
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(c.file.path)
                            setOnPreparedListener { it.isLooping = true; start() }
                            setOnClickListener { if (isPlaying) pause() else start(); paused = !isPlaying }
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(Space.xxl),
                    onRelease = { it.stopPlayback() },
                )
                if (paused) Icon(Icons.Default.PlayArrow, "Paused", Modifier.size(72.dp), tint = Color.White)
            } else {
                val img = rememberImage("capture-full:${c.file.path}:${c.file.lastModified()}") { decodeSampled(c.file, 2048) }
                if (img == null) CircularProgressIndicator(color = Orange)
                else Image(img, c.file.name, Modifier.fillMaxSize().padding(Space.xxl), contentScale = ContentScale.Fit)
            }
            Text(c.file.name, Modifier.align(Alignment.BottomCenter).padding(Space.lg), color = Color.White, style = MaterialTheme.typography.titleMedium)
            IconPill(Icons.Default.Close, "Close", onClose, Modifier.align(Alignment.TopEnd).padding(Space.lg))
        }
    }
}
