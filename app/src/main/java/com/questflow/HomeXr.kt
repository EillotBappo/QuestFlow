package com.questflow

import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.CylinderShapeOptions
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// This package has a composable called Panel (Ui.kt).
import com.meta.spatial.toolkit.Panel as SpatialPanel

// Where the library floats. None of this has been seen in a headset: these are the numbers to tune there.
/** Metres from the user to the panel. It curves around them at this radius, so every tile is the same distance away. */
private const val DISTANCE = 1.4f
/** The panel's arc length and height in metres. 2.2 m at 1.4 m spans about 90°, readable without turning far. */
private const val ARC = 2.2f
private const val HEIGHT = 1.0f
/** How far under eye level the panel's centre sits. */
private const val DROP = 0.15f
/** Layout density. 640 dp per metre makes the panel 1408 × 640 dp: five of My games' tiles across. */
private const val DP_PER_METER = 640f

/**
 * Home's "Passthrough home" button; false where it can't run. The Spatial SDK needs a Quest 2 or later: with no XR runtime
 * (the emulator, a phone) its activity never draws and Android reports it as not responding, and the Quest 1 has no
 * passthrough for apps. A device this app doesn't know counts when it reports head tracking, so a future Quest isn't locked out.
 * Meta's HybridSample enters its immersive activity the same way; unlike it, the 2D app is left running behind, because
 * its downloads live there.
 */
fun openPassthroughHome(ctx: Context): Boolean {
    val known = MODEL.name != "Headset"
    if (MODEL.device == "monterey" || !known && !ctx.packageManager.hasSystemFeature("android.hardware.vr.headtracking")) return false
    ctx.startActivity(Intent(ctx, HomeXrActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

/**
 * Passthrough home: the installed games on a curved panel floating in the user's own room, on Meta Spatial SDK.
 * It runs in its own process (":xr" in the manifest), so it can't share the 2D app's [AppState]: it reads the same files
 * and preferences instead, and keeps an ADB link of its own, the way [KeepAliveService] does.
 */
class HomeXrActivity : AppSystemActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // No enableTcp: this only uses ADB that is already up, so opening the library never restarts adbd or asks to pair.
    private val installer by lazy { Installer(filesDir) }
    /** The mirror and the extra sources, for their game lists (what counts as a game) and cover art. */
    private val sources by lazy { Rookie(getExternalFilesDir(null)!!).let { listOf(it) + it.extras() } }
    private var panel: Entity? = null
    private var placed = false

    /** Null until the first load. */
    var games by mutableStateOf<List<AppInfo>?>(null)
        private set
    var favourites by mutableStateOf(emptySet<String>())
        private set
    /** The game being started, shown in the header while its tweaks are applied. */
    var starting by mutableStateOf<String?>(null)
        private set

    // The 2D app's process writes the favourites and the tweaks. MODE_MULTI_PROCESS re-reads a file that changed on disk, so
    // a game tuned after this process started still launches with its new profile. Nothing here writes preferences, which
    // is the one use of that flag that is reliable.
    @Suppress("DEPRECATION")
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode or MODE_MULTI_PROCESS)

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ComposeViewPanelRegistration(
            R.id.home_xr_panel,
            composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { XrHome(this@HomeXrActivity) } } },
            settingsCreator = {
                UIPanelSettings(
                    shape = CylinderShapeOptions(radius = DISTANCE, width = ARC, height = HEIGHT),
                    display = DpPerMeterDisplayOptions(dpPerMeter = DP_PER_METER),
                )
            },
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mixed reality as Meta's MixedRealitySample sets it up: the room instead of a skybox, and no thumbstick travel.
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        scene.enablePassthrough(true)
        systemManager.registerSystem(PlacePanel())
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        // The samples' sun, for the controller and hand models: the only lit things here. Their image-based lighting needs
        // an environment file this app doesn't ship.
        scene.setLightingEnvironment(ambientColor = Vector3(0f), sunColor = Vector3(7f, 7f, 7f), sunDirection = -Vector3(1f, 3f, -2f), environmentIntensity = 0.3f)
        // Around the tracking origin at standing eye height, until [PlacePanel] has the headset's pose.
        panel = Entity.create(listOf(SpatialPanel(R.id.home_xr_panel), Transform(Pose(Vector3(0f, 1.5f - DROP, 0f))), Visible(true)))
    }

    // Every way in (first start, back from a game, opened again from the 2D app) gets a fresh list and the library
    // back in front of the user, who may have walked off since.
    override fun onResume() {
        super.onResume()
        placed = false
        starting = null
        scope.launch(Dispatchers.IO) { runCatching { installer.connect() } }
        scope.launch {
            favourites = getSharedPreferences("ui", MODE_PRIVATE).getStringSet("favourites", emptySet()).orEmpty()
            games = withContext(Dispatchers.IO) {
                val played = getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(0, System.currentTimeMillis())
                // My games' list: [AppState.refreshApps] counts the same packages as games and reads the same usage stats.
                homeOrder(installedApps(this@HomeXrActivity, sources.flatMap { it.games() }.mapTo(HashSet()) { it.pkg }), favourites, played.mapValues { it.value.lastTimeUsed })
            }
        }
    }

    /** Recentring moves the tracking origin under the user, and the panel with it. */
    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        placed = false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Waits for the headset's pose, then puts the panel in front of it, as Meta's MixedRealitySample places its panel. */
    private inner class PlacePanel : SystemBase() {
        override fun execute() {
            val p = panel ?: return
            if (placed) return
            val head = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()?.head?.tryGetComponent<Transform>()?.transform ?: return
            if (head == Pose()) return
            // A cylinder panel's entity is the cylinder's axis, and its surface is one radius along the entity's forward
            // (the SDK's own quad-to-cylinder animation moves the entity back by the radius to keep the surface still).
            // So the axis goes through the user, turned the way they face.
            p.setComponent(Transform(Pose(Vector3(head.t.x, head.t.y - DROP, head.t.z), Quaternion.lookRotationAroundY(head.q * Vector3(0f, 0f, 1f)))))
            placed = true
        }
    }

    fun thumbnail(pkg: String) = sources.firstNotNullOfOrNull { it.thumbnail(pkg) }

    /** What [AppState.launch] does, from this process: the game's tweaks over ADB when it's connected, then the game. */
    fun launch(a: AppInfo) {
        val intent = packageManager.getLaunchIntentForPackage(a.pkg) ?: return
        starting = a.label
        scope.launch {
            if (installer.connected) withContext(Dispatchers.IO) { runCatching { TweakStore.applyForLaunch(this@HomeXrActivity, a.pkg, installer) } }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Back to the 2D app, the way Meta's HybridSample leaves immersive mode: Horizon's home opens with the panel in it. */
    fun exit() {
        val app = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("extra_launch_in_home_pending_intent", app))
    }
}

/** The panel: My games' icon grid under a slim header, in the app's own theme. */
@Composable
private fun XrHome(a: HomeXrActivity) = QuestTheme {
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(Radius.sheet), color = Palette.Base, contentColor = Palette.Text) {
        Column(Modifier.padding(horizontal = Space.xxl)) {
            val games = a.games
            Row(Modifier.fillMaxWidth().height(Size.bar), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Logo(size = 30.dp)
                Text("QuestFlow", style = MaterialTheme.typography.headlineSmall)
                Text(
                    a.starting?.let { "Starting $it…" } ?: games?.let { count(it.size, "game") }.orEmpty(), Modifier.weight(1f).padding(start = Space.sm),
                    color = Palette.Dim, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                PillButton("Back to QuestFlow", a::exit, icon = QIcons.OpenInNew, tone = Tone.Accent)
            }
            when {
                games == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
                games.isEmpty() -> EmptyState(QIcons.Gamepad, "No games yet", "Games you install from the Store show up here.")
                else -> LazyVerticalGrid(
                    ViewMode.Icons.cells(), contentPadding = PaddingValues(bottom = Space.xxl),
                    horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md),
                ) { items(games, key = { it.pkg }) { XrTile(a, it) } }
            }
        }
    }
}

/** A game: its mirror cover when there is one, else its launcher icon, like My games. Tapping starts it. */
// ponytail: favourites show but can't be changed here (FavStar needs AppState, and a second process writing the 2D app's
// preferences would lose edits); catalog art that is only a URL falls back to the icon. Add an IPC to the main process if either matters.
@Composable
private fun XrTile(a: HomeXrActivity, g: AppInfo) {
    val thumb = remember(g.pkg) { a.thumbnail(g.pkg) }
    val icon = { a.packageManager.getApplicationIcon(g.pkg) }
    ArtTile(
        title = g.label,
        art = { if (thumb != null) GameArt(thumb, g.label, it) else AppArt(g.pkg, g.label, it, icon) },
        tint = rememberTint(if (thumb != null) rememberArt(thumb, "") else rememberAppIcon(g.pkg, icon)),
        onClick = if (g.launchable) ({ a.launch(g) }) else null,
        badges = { if (g.pkg in a.favourites) Icon(QIcons.Star, "Favourite", Modifier.clip(CircleShape).background(Palette.Glass).padding(6.dp).size(18.dp), tint = Orange) },
    )
}
