package com.questflow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CapturesTest {
    @Test
    fun scanKeepsPicturesAndVideosNewestFirst() {
        val shots = Files.createTempDirectory("shots").toFile()
        val videos = Files.createTempDirectory("videos").toFile()
        fun file(dir: File, name: String, time: Long) = File(dir, name).apply { writeText("x"); setLastModified(time) }
        file(shots, "old.jpg", 1_000_000)
        file(shots, "new.PNG", 3_000_000)
        file(shots, "notes.txt", 4_000_000)
        file(videos, "clip.mp4", 2_000_000)
        File(videos, "folder.mp4").mkdir()
        // A folder that doesn't exist (no recordings taken yet) is skipped, not an error.
        val found = scanCaptures(listOf(shots, videos, File(shots, "missing")))
        assertEquals(listOf("new.PNG", "clip.mp4", "old.jpg"), found.map { it.file.name })
        assertEquals(listOf(false, true, false), found.map { it.video })
    }

    @Test
    fun overlayTweakTogglesMetricsToolAndStaysOutOfPresets() {
        val overlay = tweaksFor(headsetModel("eureka")).first { it.key == "overlay" }
        assertEquals(listOf("am broadcast -n com.oculus.ovrmonitormetricsservice/.SettingsBroadcastReceiver -a com.oculus.ovrmonitormetricsservice.ENABLE_OVERLAY"), overlay.commands("1"))
        assertEquals(listOf("am broadcast -n com.oculus.ovrmonitormetricsservice/.SettingsBroadcastReceiver -a com.oculus.ovrmonitormetricsservice.DISABLE_OVERLAY"), overlay.commands(""))
    }
}
