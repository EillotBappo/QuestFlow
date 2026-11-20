package com.questflow

// Pure store logic: no Android classes, so it runs in plain JVM unit tests.

/** A game's tweak profile after setting [key] to [value]. A game's first change starts from the [globals]; "" means Default. */
fun profileWith(profile: Map<String, String>?, globals: Map<String, String>, key: String, value: String) = (profile ?: globals) + (key to value)

/** One short sentence for a failure, never a stack trace. Mirror/HTTP errors arrive as "HTTP <code> for <url>". */
fun friendlyError(e: Throwable): String {
    val msg = e.message.orEmpty()
    val code = Regex("""^HTTP (\d{3})""").find(msg)?.groupValues?.get(1)?.toInt()
    return when {
        // A name that won't resolve usually means no internet; a refused or unreachable address is that one server.
        e is java.net.UnknownHostException -> "No internet connection, or the address is wrong"
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "Couldn't reach the server"
        e is java.net.SocketTimeoutException -> "The server took too long to answer"
        code == 401 || code == 403 -> "The server refused access (HTTP $code)"
        code == 404 -> "Not found on the server (HTTP 404)"
        code != null -> "The server had a problem (HTTP $code)"
        else -> msg.lineSequence().first().trim().ifEmpty { e.javaClass.simpleName }
    }
}

/** Wide and big enough to fill a card. Anything else (square covers, icons, tiny banners) is shown as an icon instead. */
fun isGoodThumbnail(w: Int, h: Int) = w >= 400 && w >= h * 1.3f

/** Lists carry several releases of one game; browsing shows the newest per source. */
fun latestPerTitle(games: List<Game>): List<Game> =
    games.groupBy { it.source to it.pkg }.values.map { l -> l.maxBy { it.version.toLongOrNull() ?: 0 } }

private const val ROW_SIZE = 20

/** The store home's one shelf: the newest titles across every source. F-Droid's phone apps never appear here. */
fun newAndUpdated(games: List<Game>, hideAdult: Boolean) =
    latestPerTitle(games).filter { it.source != FDroid.name && !(hideAdult && it.isAdult) }.sortedByDescending { it.updated }.take(ROW_SIZE)

/**
 * The store home's lists under the shelf, one per source, VRSrc's first and F-Droid's (thousands of phone apps) last.
 * [vrsrc] is the source labels VRSrc's games carry; while [vrsrcSetUp] its list stays even when empty, so the home can say it failed to load.
 */
fun sourceLists(games: List<Game>, vrsrc: Set<String>, vrsrcSetUp: Boolean): List<Pair<String, List<Game>>> {
    val bySource = games.groupBy { if (it.source in vrsrc) "VRSrc" else it.source }
    val own = bySource["VRSrc"].orEmpty()
    return listOfNotNull(("VRSrc" to own).takeIf { vrsrcSetUp || own.isNotEmpty() }) +
        bySource.filterKeys { it != "VRSrc" }.toList().sortedBy { it.first == FDroid.name }
}

/** Browse all's default order: titles made for the headset first, F-Droid's phone apps after; newest first within each. */
fun recommended(games: List<Game>) = games.sortedWith(compareBy<Game> { it.source == FDroid.name }.thenByDescending { it.updated })

/** Passthrough home's order: the installed games, favourites first, then the most recently played. Ties keep [apps]' order. */
fun homeOrder(apps: List<AppInfo>, favourites: Set<String>, lastPlayed: Map<String, Long>) =
    apps.filter { it.isGame }.sortedWith(compareByDescending<AppInfo> { it.pkg in favourites }.thenByDescending { lastPlayed[it.pkg] ?: 0L })

/** "Updated 3 weeks ago" from a yyyy-MM-dd date, store-style; the raw text when it doesn't parse, null when there's none. */
fun updatedAgo(day: String, today: java.time.LocalDate): String? {
    if (day.isBlank()) return null
    val date = runCatching { java.time.LocalDate.parse(day.take(10)) }.getOrNull() ?: return "Updated $day"
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)
    fun ago(n: Long, unit: String) = "Updated $n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        days <= 0 -> "Updated today"
        days == 1L -> "Updated yesterday"
        days < 14 -> ago(days, "day")
        days < 60 -> ago(days / 7, "week")
        days < 365 -> ago(days / 30, "month")
        else -> ago(days / 365, "year")
    }
}

/** "1 title", "4,942 titles": a grouped number and a noun that agrees with it. */
fun count(n: Int, noun: String) = "%,d %s".format(java.util.Locale.US, n, if (n == 1) noun else noun + "s")

/** A temp file name for a title's APK. Catalog keys like "github:owner/repo" hold characters a file name can't. */
fun apkFileName(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"

/** The slate a list row falls back to when its art has no colour to follow. */
const val ROW_FALLBACK = 0xFF3A414E.toInt()

/**
 * A list row's background from its art's pixels (ARGB): the average colour weighted toward saturated pixels, so a vivid
 * cover isn't averaged into grey, scaled to one brightness so white text always reads, and pulled a quarter toward the slate base.
 */
fun rowTint(px: IntArray): Int {
    var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
    for (p in px) {
        // An icon's transparent corners aren't part of its colour.
        if (p ushr 24 < 128) continue
        val pr = p shr 16 and 255; val pg = p shr 8 and 255; val pb = p and 255
        // Chroma, plus a little so all-grey art still averages to its grey.
        val k = (maxOf(pr, pg, pb) - minOf(pr, pg, pb) + 8).toDouble()
        r += pr * k; g += pg * k; b += pb * k; w += k
    }
    if (w == 0.0) return ROW_FALLBACK
    val peak = maxOf(r, g, b) / w
    if (peak < 24) return ROW_FALLBACK
    fun ch(sum: Double, shift: Int) = ((sum / w * 110 / peak) * .75 + (ROW_FALLBACK shr shift and 255) * .25).toInt().coerceIn(0, 255) shl shift
    return (0xFF shl 24) or ch(r, 16) or ch(g, 8) or ch(b, 0)
}
