package com.questflow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- Tokens

/** Dark slate, like a game tuner's panels. QuestFlow's orange is the one accent; green and red only report state. */
object Palette {
    val Bar = Color(0xFF12151B)
    val Base = Color(0xFF1B1F27)
    val Panel = Color(0xFF262B35)
    val Raised = Color(0xFF343B48)
    /** Controls laid over art or a tinted row: darkens whatever is under it instead of covering it. */
    val Glass = Color(0x4D000000)
    val Text = Color(0xFFF3F5F9)
    val Dim = Color(0xFFA9B1C0)
    val Ink = Color(0xFF1A1000)
}
val Orange = Color(0xFFFFAA28)
val Green = Color(0xFF5BD6A0)
val Red = Color(0xFFFF6B6B)

/** One spacing scale, one radius scale and one control height for every screen, so nothing is sized by eye. */
object Space { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 20.dp; val xxl = 24.dp; val xxxl = 32.dp }
object Radius { val chip = 12.dp; val control = 14.dp; val tile = 16.dp; val card = 20.dp; val row = 22.dp; val sheet = 28.dp; val pill = 100.dp }
object Size {
    /** Pills, pickers, chips and inputs share this height, so a row of mixed controls lines up. Big enough to aim at with a controller. */
    val control = 40.dp
    /** The header and the bottom bar. */
    val bar = 60.dp
    /** A list row; [rowTall] when it carries a third line of details. */
    val row = 96.dp
    val rowTall = 112.dp
}

/** Nunito: heavy, rounded type that stays readable at panel distance. Bundled, since Horizon OS has no downloadable fonts. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun nunito(w: Int) = Font(R.font.nunito, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Rounded = FontFamily(nunito(600), nunito(700), nunito(800), nunito(900))

private fun typography(): Typography {
    val t = Typography()
    fun TextStyle.r(w: Int) = copy(fontFamily = Rounded, fontWeight = FontWeight(w))
    return Typography(
        displayLarge = t.displayLarge.r(900), displayMedium = t.displayMedium.r(900), displaySmall = t.displaySmall.r(900),
        headlineLarge = t.headlineLarge.r(900), headlineMedium = t.headlineMedium.r(900), headlineSmall = t.headlineSmall.r(900),
        titleLarge = t.titleLarge.r(800), titleMedium = t.titleMedium.r(800), titleSmall = t.titleSmall.r(800),
        bodyLarge = t.bodyLarge.r(700), bodyMedium = t.bodyMedium.r(600), bodySmall = t.bodySmall.r(600),
        labelLarge = t.labelLarge.r(800), labelMedium = t.labelMedium.r(700), labelSmall = t.labelSmall.r(700),
    )
}

private val Scheme = darkColorScheme(
    primary = Orange, onPrimary = Palette.Ink,
    primaryContainer = Color(0xFF4A3412), onPrimaryContainer = Color(0xFFFFD9A0),
    secondary = Color(0xFF9FB4D9), onSecondary = Palette.Ink,
    secondaryContainer = Palette.Raised, onSecondaryContainer = Palette.Text,
    tertiary = Green,
    background = Palette.Base, onBackground = Palette.Text,
    surface = Palette.Base, onSurface = Palette.Text,
    surfaceVariant = Palette.Raised, onSurfaceVariant = Palette.Dim,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Palette.Bar, surfaceContainerLow = Color(0xFF20252E),
    surfaceContainer = Palette.Panel, surfaceContainerHigh = Color(0xFF2B313C), surfaceContainerHighest = Palette.Raised,
    outline = Color(0x33FFFFFF), outlineVariant = Color(0x1FFFFFFF),
    error = Red,
)

@Composable
fun QuestTheme(content: @Composable () -> Unit) {
    val type = remember { typography() }
    MaterialTheme(
        colorScheme = Scheme, typography = type,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(Radius.control), small = RoundedCornerShape(Radius.chip), medium = RoundedCornerShape(Radius.tile),
            large = RoundedCornerShape(Radius.card), extraLarge = RoundedCornerShape(Radius.sheet),
        ),
        content = content,
    )
}

// ---------------------------------------------------------------- Focus and touch

/** Outline shown while a D-pad or controller has focus on the element. Touch never shows it. */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(Radius.tile)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(3.dp, Orange, shape) else Modifier)
}

/** Grows a focused element slightly, so a controller user sees where they are beyond the ring. Full-width rows use a smaller [to]. */
fun Modifier.focusScale(to: Float = 1.04f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) to else 1f, label = "focusScale")
    onFocusChanged { focused = it.hasFocus }.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A tap target: clipped to [shape], focus ring, button role. Every pill, chip and row goes through this. */
fun Modifier.tap(shape: Shape, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clip(shape).focusRing(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)

// ---------------------------------------------------------------- Pills, chips, pickers, inputs

/** What a pill is for: [Glass] over art and rows, [Accent] for the one thing to do (Install, Update), [Raised] on plain panels, [Danger] for removing. */
enum class Tone(val bg: Color, val fg: Color) {
    Glass(Palette.Glass, Color.White), Accent(Orange, Palette.Ink), Raised(Palette.Raised, Palette.Text), Danger(Color(0x33FF6B6B), Red),
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: Tone = Tone.Raised, enabled: Boolean = true) {
    Row(
        modifier.height(Size.control).alpha(if (enabled) 1f else .45f).tap(CircleShape, enabled, onClick).background(tone.bg)
            .padding(start = if (icon != null) Space.md else Space.lg, end = Space.lg),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp), tint = tone.fg) }
        Text(text, color = tone.fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A round icon-only button; [label] is what a screen reader says. */
@Composable
fun IconPill(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: Tone = Tone.Glass, tint: Color = tone.fg, enabled: Boolean = true) {
    Box(
        modifier.size(Size.control).alpha(if (enabled) 1f else .45f).tap(CircleShape, enabled, onClick).background(tone.bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp), tint = tint) }
}

/** A filter toggle: filled orange with a tick while on. */
@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    PillButton(text, onClick, modifier.semantics { this.selected = selected }, icon = if (selected) Icons.Default.Check else null, tone = if (selected) Tone.Accent else Tone.Raised)

/** The star on every row: favourites sort and filter in the Store. */
@Composable
fun FavStar(s: AppState, pkg: String) {
    val fav = pkg in s.favourites
    IconPill(if (fav) QIcons.Star else QIcons.StarBorder, if (fav) "Unfavourite" else "Favourite", { s.toggleFavourite(pkg) }, tint = if (fav) Orange else Color.White)
}

class Seg(val label: String, val icon: ImageVector? = null, val badge: Int = 0)

/** One pill holding a few choices, the picked one filled orange: section tabs, view modes, sub-pages. [labels] off shows icons only. */
@Composable
fun Segmented(items: List<Seg>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, labels: Boolean = true) {
    Row(
        modifier.height(Size.control).clip(CircleShape).background(Palette.Glass).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val on = i == selected
            val fg = if (on) Palette.Ink else Palette.Text
            Row(
                Modifier.fillMaxHeight().tap(CircleShape) { onSelect(i) }.background(if (on) Orange else Color.Transparent)
                    .semantics { this.selected = on }.padding(horizontal = if (labels) Space.md else 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.icon?.let { Icon(it, if (labels) null else item.label, Modifier.size(20.dp), tint = fg) }
                if (labels) Text(item.label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                if (item.badge > 0) Text(
                    "${item.badge}", Modifier.clip(CircleShape).background(if (on) Palette.Ink else Orange).padding(horizontal = 6.dp),
                    color = if (on) Orange else Palette.Ink, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** How a list is laid out, QGT style: full-width rows, rows in two columns, or a grid of art tiles. */
enum class ViewMode(val label: String, val icon: ImageVector) {
    List("List", QIcons.ViewList), Columns("Two columns", QIcons.GridView), Icons("Icon grid", QIcons.Apps);

    fun cells() = when (this) { List -> GridCells.Fixed(1); Columns -> GridCells.Fixed(2); Icons -> GridCells.Adaptive(220.dp) }
}

@Composable
fun ViewToggle(mode: ViewMode, onPick: (ViewMode) -> Unit) =
    Segmented(ViewMode.entries.map { Seg(it.label, it.icon) }, mode.ordinal, { onPick(ViewMode.entries[it]) }, labels = false)

/** A dark pill showing the current choice with ▾; tapping lists [options]. [fill] stretches it to its width with ▾ at the end. */
@Composable
fun <T> Picker(value: T, options: List<T>, label: (T) -> String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, fill: Boolean = false, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.height(Size.control).alpha(if (enabled) 1f else .45f).tap(CircleShape, enabled) { open = true }.background(Palette.Glass)
            .padding(start = if (icon != null) Space.md else Space.lg, end = Space.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp), tint = Palette.Dim) }
        Text(label(value), if (fill) Modifier.weight(1f) else Modifier, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.ArrowDropDown, null, tint = Palette.Dim)
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem({ Text(label(o)) }, { open = false; onPick(o) }, leadingIcon = if (o == value) {{ Icon(Icons.Default.Check, null, tint = Orange) }} else null)
            }
        }
    }
}

/** [Picker] over label-to-value pairs, for tweak options. A [selected] value missing from [options] shows as itself. */
@Composable
fun DropPill(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, enabled: Boolean = true, onPick: (String) -> Unit) =
    Picker(selected, options.map { it.second }, { v -> options.firstOrNull { it.second == v }?.first ?: v }, modifier, enabled = enabled, fill = true, onPick = onPick)

/** A one-line text input in a pill. [field] goes on the text field itself (focus requests, focus callbacks). */
@Composable
fun InputPill(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, icon: ImageVector? = null, field: Modifier = Modifier) {
    Row(
        modifier.height(Size.control).clip(CircleShape).background(Palette.Glass).focusRing(CircleShape).padding(start = Space.md, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp), tint = Palette.Dim) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Palette.Dim.copy(alpha = .6f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value, onChange, field.fillMaxWidth(), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text), cursorBrush = SolidColor(Orange),
            )
        }
        if (value.isNotEmpty()) IconPill(Icons.Default.Clear, "Clear", { onChange("") }, Modifier.size(32.dp), tone = Tone.Raised)
        else Spacer(Modifier.width(Space.sm))
    }
}

/**
 * QGT's Search pill. Closed it's a button, so a controller passing over it never pops the keyboard; it opens into a field
 * with focus, and closes again when it loses focus while empty.
 */
@Composable
fun SearchPill(query: String, onChange: (String) -> Unit, placeholder: String = "Search", modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open && query.isEmpty()) {
        PillButton("Search", { open = true }, modifier, icon = Icons.Default.Search, tone = Tone.Glass)
        return
    }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) runCatching { focus.requestFocus() } }
    InputPill(
        query, onChange, placeholder, modifier.widthIn(min = 220.dp, max = 360.dp), icon = Icons.Default.Search,
        field = Modifier.focusRequester(focus).onFocusChanged {
            if (it.isFocused) hadFocus = true
            else if (hadFocus) { hadFocus = false; if (query.isEmpty()) open = false }
        },
    )
}

/** A small label on a row or tile: Installed, Update, Game, OBB… */
@Composable
fun Tag(text: String, color: Color = Palette.Glass, content: Color = Palette.Text) {
    Text(
        text, Modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 3.dp),
        color = content, style = MaterialTheme.typography.labelMedium, maxLines = 1,
    )
}

/** An icon and a value, the unit every stat line is built from. */
@Composable
fun IconText(icon: ImageVector?, text: String, modifier: Modifier = Modifier, color: Color = Palette.Text, style: TextStyle = MaterialTheme.typography.labelLarge, iconSize: Dp = 16.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(it, null, Modifier.size(iconSize), tint = color.copy(alpha = .7f)) }
        Text(text, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Bars

/** A row of a page's controls under the header: pickers and chips first, a dim summary of the list at the end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Toolbar(summary: String = "", modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(bottom = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        content()
        if (summary.isNotEmpty()) Box(Modifier.height(Size.control).padding(start = Space.xs), contentAlignment = Alignment.CenterStart) {
            Text(summary, color = Palette.Dim, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Every page's slim header: section tabs on the left, the app's name in the middle, the page's own [tools] (view toggle,
 * Search) on the right. Narrow windows drop the tab labels so the three parts never collide.
 */
@Composable
fun TopBar(tools: @Composable RowScope.() -> Unit = {}) {
    val nav = LocalNav.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(Size.bar)) {
        val wide = maxWidth > 1100.dp
        if (nav != null) {
            val tabs = Section.entries.filter { it != Section.Settings }
            Segmented(
                tabs.map { Seg(it.label, it.icon, if (it == Section.Downloads) nav.downloads else 0) }, tabs.indexOf(nav.section),
                { nav.go(tabs[it]) }, Modifier.align(Alignment.CenterStart), labels = wide,
            )
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Logo(size = 30.dp)
            Text("QuestFlow", style = MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm), content = tools)
    }
}

// ---------------------------------------------------------------- Rows, tiles, panels

class StatItem(val icon: ImageVector, val text: String, val color: Color = Palette.Text)

/**
 * The QGT row every list is made of: cover art flush left, the row washed in the art's colour, a bold title over one line
 * of icon stats, and on the right the row's buttons over a small status label. [detail] adds a dim third line.
 * [compact] (two-column view) keeps the first two stats. [footer] draws over the row's bottom edge (a progress bar).
 */
@Composable
fun MediaRow(
    title: String,
    art: @Composable (Modifier) -> Unit,
    tint: Color?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    stats: List<StatItem> = emptyList(),
    detail: String = "",
    detailColor: Color = Palette.Dim,
    status: String = "",
    statusColor: Color = Palette.Dim,
    selected: Boolean = false,
    compact: Boolean = false,
    tags: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    footer: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(Radius.row)
    val base = tint ?: Palette.Panel
    Box(
        modifier.fillMaxWidth().height(if (detail.isNotEmpty()) Size.rowTall else Size.row)
            .then(if (onClick != null) Modifier.focusScale(1.012f).tap(shape, onClick = onClick) else Modifier.clip(shape))
            .background(Brush.horizontalGradient(listOf(base, lerpColor(base, Palette.Base, .35f))))
            .border(if (selected) 3.dp else 1.dp, if (selected) Orange else Color.White.copy(alpha = .07f), shape),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(16 / 9f)) { art(Modifier.matchParentSize()) }
            Column(Modifier.weight(1f).padding(horizontal = Space.lg), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    tags()
                }
                if (stats.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    stats.take(if (compact) 2 else 4).forEach { IconText(it.icon, it.text, Modifier.weight(1f), it.color, MaterialTheme.typography.bodyLarge, 18.dp) }
                }
                if (detail.isNotEmpty()) Text(detail, color = detailColor, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(
                Modifier.fillMaxHeight().padding(top = Space.md, bottom = Space.sm, end = Space.md),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                if (status.isNotEmpty()) Text(status, color = statusColor, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.widthIn(max = 220.dp), overflow = TextOverflow.Ellipsis)
            }
        }
        footer()
    }
}

/** The icon-grid view's tile: art on top, title and one dim line under it, the tile washed in the art's colour. */
@Composable
fun ArtTile(
    title: String,
    art: @Composable (Modifier) -> Unit,
    tint: Color?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    subtitle: String = "",
    selected: Boolean = false,
    badges: @Composable RowScope.() -> Unit = {},
    actions: (@Composable RowScope.() -> Unit)? = null,
    footer: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(Radius.card)
    Column(
        modifier.then(if (onClick != null) Modifier.focusScale().tap(shape, onClick = onClick) else Modifier.clip(shape))
            .background(tint ?: Palette.Panel)
            .border(if (selected) 3.dp else 1.dp, if (selected) Orange else Color.White.copy(alpha = .07f), shape),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f)) {
            art(Modifier.matchParentSize())
            Row(Modifier.padding(Space.sm), horizontalArrangement = Arrangement.spacedBy(6.dp), content = badges)
            footer()
        }
        Column(Modifier.padding(horizontal = Space.md, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (actions != null) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** A titled block on a plain slate panel: Home's cards, every Options group. [hintLines] reserves that many lines for the hint, so a grid of panels with short and long hints lines up. */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, hint: String = "", icon: ImageVector? = null, hintLines: Int = 1, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Palette.Panel).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            icon?.let { Icon(it, null, Modifier.size(20.dp), tint = Orange) }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.Dim, minLines = hintLines)
        content()
    }
}

/** A label and its value on one line, for status panels. */
@Composable
fun StatLine(label: String, value: String, tint: Color = Palette.Text) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
    Text(value, style = MaterialTheme.typography.bodyMedium, color = tint)
}

/** A section heading over a list, with optional buttons at its end. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        trailing()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    // Scrolls so the action button stays reachable when the window is short (landscape with the nav bar).
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.Panel), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(36.dp), tint = Orange)
        }
        Spacer(Modifier.height(Space.lg))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(body, Modifier.widthIn(max = 440.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Dim, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(Space.xl)); action() }
    }
}

@Composable
fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = Space.md).clip(RoundedCornerShape(Radius.card)).background(Palette.Panel).padding(start = Space.lg, end = Space.sm, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = Orange)
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), color = Palette.Dim, style = MaterialTheme.typography.bodyMedium)
        if (action != null) PillButton(action, onAction, tone = Tone.Accent)
    }
}

/** Headset storage as a labelled bar. */
@Composable
fun StorageBar(s: AppState, modifier: Modifier = Modifier.width(320.dp)) {
    val total = s.totalSpace().coerceAtLeast(1)
    val used = total - s.freeSpace()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Storage", style = MaterialTheme.typography.labelMedium, color = Palette.Dim)
            Text("${formatSize(used)} of ${formatSize(total)}", style = MaterialTheme.typography.labelMedium, color = Palette.Dim)
        }
        LinearProgressIndicator(
            { used.toFloat() / total }, Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
            color = Orange, trackColor = Palette.Raised, drawStopIndicator = {}, gapSize = 0.dp,
        )
    }
}

@Composable
fun Logo(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(Brush.linearGradient(listOf(Orange, Color(0xFFFF6A3D)))),
        contentAlignment = Alignment.Center,
    ) {
        // A play triangle inside a ring with a tail: a "Q" that reads as "play".
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize(.62f)) {
            val w = this.size.minDimension
            val stroke = w * .13f
            drawCircle(Palette.Ink, radius = w / 2 - stroke / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawLine(Palette.Ink, Offset(w * .68f, w * .68f), Offset(w * .98f, w * .98f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * .41f, w * .32f); lineTo(w * .41f, w * .68f); lineTo(w * .7f, w * .5f); close() }, Palette.Ink)
        }
    }
}

// ---------------------------------------------------------------- Art

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

private val images = LruCache<String, ImageBitmap>(96)

/** Decodes off the main thread and caches, so grids scroll without jank or flicker. */
@Composable
fun rememberImage(key: String?, load: () -> Bitmap?): ImageBitmap? =
    produceState(key?.let { images.get(it) }, key) {
        if (key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull()?.asImageBitmap()?.also { images.put(key, it) } }
    }.value

fun decodeSampled(file: File, maxWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

fun Drawable.toBitmap(size: Int): Bitmap =
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { setBounds(0, 0, size, size); draw(Canvas(it)) }

/**
 * A game's art: its local thumbnail, else [url] fetched once into the cache dir, so a refresh never downloads thousands of icons.
 * [maxWidth] caps the decoded size; full-screen viewers ask for more than rows.
 */
@Composable
fun rememberArt(file: File?, url: String, maxWidth: Int = 640): ImageBitmap? {
    val cacheDir = androidx.compose.ui.platform.LocalContext.current.cacheDir
    return rememberImage((file?.path ?: url.ifEmpty { null })?.let { if (maxWidth == 640) it else "$it@$maxWidth" }) {
        file?.let { decodeSampled(it, maxWidth) } ?: run {
            val cached = File(cacheDir, "art/" + url.hashCode().toUInt().toString(16))
            if (!cached.exists()) {
                cached.parentFile?.mkdirs()
                val tmp = File(cached.path + ".part")
                java.net.URL(url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(cached)
            }
            decodeSampled(cached, maxWidth)
        }
    }
}

/** An installed app's (or APK's) launcher icon, cached under [key]. */
@Composable
fun rememberAppIcon(key: String, load: () -> Drawable?): ImageBitmap? = rememberImage("icon:$key") { load()?.toBitmap(144) }

/** A row's colour from its art: 16×16 pixels into [rowTint]. */
fun tintOf(img: ImageBitmap): Color {
    val n = 16
    val px = IntArray(n * n).also { Bitmap.createScaledBitmap(img.asAndroidBitmap(), n, n, true).getPixels(it, 0, n, 0, 0, n, n) }
    return Color(rowTint(px))
}

@Composable
fun rememberTint(img: ImageBitmap?): Color? = remember(img) { img?.let(::tintOf) }

/** A store game's row colour, from the same art its row shows. */
@Composable
fun gameTint(s: AppState, g: Game?): Color? = rememberTint(g?.let { rememberArt(s.thumbnail(it), it.thumbUrl.ifEmpty { it.iconUrl }) })

/**
 * A title's art: a good wide thumbnail fills the area; otherwise its icon sits on a blurred copy of itself; otherwise a letter.
 * [iconBias] moves that icon up (-1) or down (1), so text laid over the art doesn't cover it.
 */
@Composable
fun GameArt(s: AppState, g: Game, modifier: Modifier = Modifier, iconBias: Float = 0f) = GameArt(s.thumbnail(g), g.name, modifier, url = g.thumbUrl, icon = g.iconUrl, iconBias = iconBias)

@Composable
fun GameArt(file: File?, title: String, modifier: Modifier = Modifier, url: String = "", icon: String = "", iconBias: Float = 0f) {
    val thumb = rememberArt(file, url)
    if (thumb != null && isGoodThumbnail(thumb.width, thumb.height)) {
        Image(thumb, null, modifier.background(Palette.Raised), contentScale = ContentScale.Crop)
        return
    }
    // A thumbnail that isn't wide (e.g. a square cover) still beats no icon.
    IconTile(thumb ?: rememberArt(null, icon), title, modifier, iconBias)
}

/** An installed app or APK as row art: its launcher icon on a blurred copy of itself. */
@Composable
fun AppArt(key: String, title: String, modifier: Modifier = Modifier, load: () -> Drawable?) = IconTile(rememberAppIcon(key, load), title, modifier, 0f)

/** An icon centred on a blurred, darkened copy of itself, so square art never gets cropped into a wide card. No icon: a letter. */
@Composable
fun IconTile(img: ImageBitmap?, title: String, modifier: Modifier, iconBias: Float = 0f) {
    // No art at all: a colour of its own per title, so a row of placeholders doesn't read as one grey block.
    val hue = remember(title) { (title.hashCode() and 0x7fffffff) % 360f }
    val bg = if (img != null) Modifier.background(Palette.Raised)
    else Modifier.background(Brush.linearGradient(listOf(Color.hsv(hue, .55f, .32f), Color.hsv((hue + 40) % 360, .55f, .14f))))
    Box(modifier.then(bg), contentAlignment = Alignment.Center) {
        if (img != null) {
            val soft = remember(img) { haze(img) }
            Image(soft, null, Modifier.matchParentSize().graphicsLayer { alpha = .6f }, contentScale = ContentScale.Crop)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .1f), Color.Black.copy(alpha = .45f)))))
        val shape = RoundedCornerShape(22)
        Box(Modifier.align(androidx.compose.ui.BiasAlignment(0f, iconBias)).fillMaxHeight(.62f).aspectRatio(1f).shadow(10.dp, shape).clip(shape).background(if (img != null) Palette.Raised else Color.hsv(hue, .5f, .5f)), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Text(title.trim().take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
    }
}

/**
 * A soft, blurred backdrop from [img]: shrunk to 16px, box-blurred three times (close to a gaussian), then grown to 64px.
 * Stretched behind an icon it reads as a heavy blur, at a tiny fraction of a blur effect's cost: a blur effect over a
 * full-width header stalled rendering long enough to freeze the app on the software-rendered emulator.
 */
private fun haze(img: ImageBitmap): ImageBitmap {
    val n = 16
    val px = IntArray(n * n).also { Bitmap.createScaledBitmap(img.asAndroidBitmap(), n, n, true).getPixels(it, 0, n, 0, 0, n, n) }
    repeat(3) {
        val out = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (dy in -1..1) for (dx in -1..1) {
                val p = px[(y + dy).coerceIn(0, n - 1) * n + (x + dx).coerceIn(0, n - 1)]
                a += p ushr 24; r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
            }
            out[y * n + x] = (a / 9 shl 24) or (r / 9 shl 16) or (g / 9 shl 8) or (b / 9)
        }
        out.copyInto(px)
    }
    return Bitmap.createScaledBitmap(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), 64, 64, true).asImageBitmap()
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Skeleton block with a moving highlight band, shown while lists load. */
@Composable
fun Shimmer(modifier: Modifier) {
    val x by androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer").animateFloat(
        -1f, 2f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200)), label = "x",
    )
    Box(modifier.drawBehind {
        drawRect(Brush.linearGradient(listOf(Palette.Panel, Palette.Raised, Palette.Panel), start = Offset(size.width * x, 0f), end = Offset(size.width * (x + .6f), size.height)))
    })
}

/** Fade + rise the first time each [id] appears; scrolling back to it never replays. Capped so deep items show at once. */
fun Modifier.staggered(index: Int, id: Any): Modifier = composed {
    val first = remember(id) { seen.add(id) }
    val p = remember(id) { androidx.compose.animation.core.Animatable(if (first && index < 12) 0f else 1f) }
    LaunchedEffect(id) {
        if (p.value < 1f) { kotlinx.coroutines.delay(index * 40L); p.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 300f)) }
    }
    graphicsLayer { alpha = p.value; translationY = (1 - p.value) * 18.dp.toPx() }
}

// ponytail: grows for the session; fine for a few thousand titles.
private val seen = HashSet<Any>()

/** The Material icons the core set lacks, from their published path data, rather than pulling in the whole extended icon library. */
object QIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(androidx.compose.ui.graphics.vector.addPathNodes(path), fill = SolidColor(Color.Black)).build()
    val Gamepad = icon("Gamepad", "M21.58,16.09l-1.09,-7.66C20.21,6.46 18.52,5 16.53,5H7.47C5.48,5 3.79,6.46 3.51,8.43l-1.09,7.66C2.2,17.63 3.39,19 4.94,19c0.68,0 1.32,-0.27 1.8,-0.75L9,16h6l2.25,2.25c0.48,0.48 1.13,0.75 1.8,0.75C20.61,19 21.8,17.63 21.58,16.09zM11,11H9v2H8v-2H6v-1h2V8h1v2h2V11zM15,10c-0.55,0 -1,-0.45 -1,-1c0,-0.55 0.45,-1 1,-1s1,0.45 1,1C16,9.55 15.55,10 15,10zM17,13c-0.55,0 -1,-0.45 -1,-1c0,-0.55 0.45,-1 1,-1s1,0.45 1,1C18,12.55 17.55,13 17,13z")
    val Download = icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val Apps = icon("Apps", "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z")
    val Battery = icon("Battery", "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z")
    val Usb = icon("Usb", "M15,7v4h1v2h-3V5h2l-3,-4 -3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93 0,-1.21 -0.99,-2.2 -2.2,-2.2 -1.21,0 -2.2,0.99 -2.2,2.2 0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95 0,1.22 0.99,2.2 2.2,2.2 1.21,0 2.2,-0.98 2.2,-2.2 0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7h-4z")
    val ViewList = icon("ViewList", "M3,4h18v4H3zM3,10h18v4H3zM3,16h18v4H3z")
    val GridView = icon("GridView", "M3,3h8v8H3zM13,3h8v8h-8zM3,13h8v8H3zM13,13h8v8h-8z")
    val Star = icon("Star", "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z")
    val StarBorder = icon("StarBorder", "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z")
    val OpenInNew = icon("OpenInNew", "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z")
    val Tag = icon("Tag", "M21.41,11.58l-9,-9C12.05,2.22 11.55,2 11,2H4c-1.1,0 -2,0.9 -2,2v7c0,0.55 0.22,1.05 0.59,1.42l9,9c0.36,0.36 0.86,0.58 1.41,0.58 0.55,0 1.05,-0.22 1.41,-0.59l7,-7c0.37,-0.36 0.59,-0.86 0.59,-1.41 0,-0.55 -0.23,-1.06 -0.59,-1.42zM5.5,7C4.67,7 4,6.33 4,5.5S4.67,4 5.5,4 7,4.67 7,5.5 6.33,7 5.5,7z")
    val Storage = icon("Storage", "M2,20h20v-4H2v4zM4,17h2v2H4v-2zM2,4v4h20V4H2zM6,7H4V5h2v2zM2,14h20v-4H2v4zM4,11h2v2H4v-2z")
    val Cloud = icon("Cloud", "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z")
    val Sync = icon("Sync", "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z")
    val Schedule = icon("Schedule", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z")
    val Speed = icon("Speed", "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58L5.07,18A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z")
    val Memory = icon("Memory", "M15,9H9v6h6V9zM13,13h-2v-2h2v2zM21,11V9h-2V7c0,-1.1 -0.9,-2 -2,-2h-2V3h-2v2h-2V3H9v2H7c-1.1,0 -2,0.9 -2,2v2H3v2h2v2H3v2h2v2c0,1.1 0.9,2 2,2h2v2h2v-2h2v2h2v-2h2c1.1,0 2,-0.9 2,-2v-2h2v-2h-2v-2h2zM17,17H7V7h10v10z")
    val Resolution = icon("Resolution", "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z")
    val Thermostat = icon("Thermostat", "M15,13V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v8c-1.21,0.91 -2,2.37 -2,4 0,2.76 2.24,5 5,5s5,-2.24 5,-5c0,-1.63 -0.79,-3.09 -2,-4zM11,5c0,-0.55 0.45,-1 1,-1s1,0.45 1,1h-1v1h1v2h-1v1h1v2h-2V5z")
    val Folder = icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val Tune = icon("Tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")
    val Sort = icon("Sort", "M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z")
    val Image = icon("Image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    val Help = icon("Help", "M11,18h2v-2h-2v2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,6c-2.21,0 -4,1.79 -4,4h2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3,-5 0,-2.21 -1.79,-4 -4,-4z")
}
