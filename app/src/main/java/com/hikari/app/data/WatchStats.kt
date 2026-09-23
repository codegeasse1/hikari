package com.hikari.app.data

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * What the Stats page is made of: one bucket per calendar day (time spent, videos
 * started, chapters read) plus a running total per title, so the page can print
 * "12m / 1 item / 1 day active", a rank, a favourite title and a heatmap without
 * touching the watch history itself.
 *
 * Why a store of its own rather than arithmetic over [HistoryEntry]:
 *  - history holds ONE row per video (a re-watch overwrites the position), so
 *    "time spent" cannot be recovered from it at all;
 *  - history is the "continue watching" list, and it is cleared/filtered by the
 *    user for reasons that have nothing to do with what they watched;
 *  - a chapter of a manga is not in the video history at all.
 *
 * The whole thing is one small JSON string under a single preference key (see
 * [AppStore.watchStatsFlow]), written through the store's atomic `edit`, so the
 * player, the reader and the Stats page can all touch it concurrently without
 * losing a write.
 *
 * Time is bucketed by the DEVICE's local day ([dayKey]): "days active" and the
 * heatmap are about the user's evenings, not about UTC midnight.
 */
object WatchStats {

    /** The kind of thing a title total belongs to — the favourite-title card's
     *  wording comes from it. */
    const val KIND_MOVIE = "movie"
    const val KIND_SERIES = "series"
    const val KIND_MANGA = "manga"

    /** One day's bucket. */
    data class Day(
        val seconds: Long = 0L,
        val videos: Int = 0,
        val chapters: Int = 0,
        /**
         * What made up that day, per title — so tapping a square on the heatmap
         * can say WHAT was watched and for how long, not just "2m watched".
         * Keyed like [Snapshot.titles] ([titleKey]). Empty for a day that a
         * build predating this field logged, which reads as a plain time with no
         * breakdown rather than as wrong numbers.
         */
        val titles: Map<String, TitleTotal> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = seconds <= 0L && videos == 0 && chapters == 0
    }

    /** A title's running total, for the favourite-title card. */
    data class TitleTotal(
        val title: String,
        val posterUrl: String?,
        val seconds: Long,
        val kind: String,
        /** Videos started on this title (that day, or ever — the same row shape
         *  serves the per-day breakdown and the whole log). */
        val videos: Int = 0,
        /** Chapters read on it. */
        val chapters: Int = 0,
    )

    /** One day of the heatmap. Only days that have already happened appear. */
    data class HeatCell(val dayKey: String, val seconds: Long)

    /** Everything the Stats page reads, decoded once. */
    data class Snapshot(
        val days: Map<String, Day>,
        val titles: Map<String, TitleTotal>,
    ) {
        val totalSeconds: Long get() = days.values.sumOf { it.seconds }
        val totalVideos: Int get() = days.values.sumOf { it.videos }
        val totalChapters: Int get() = days.values.sumOf { it.chapters }

        /** "Items consumed": every video the user started, plus every chapter
         *  they opened. */
        val totalItems: Int get() = totalVideos + totalChapters

        /** Days with anything on them at all. */
        val daysActive: Int get() = days.values.count { !it.isEmpty }

        val averageEpisodesPerDay: Float
            get() = if (daysActive == 0) 0f else totalVideos.toFloat() / daysActive

        val averageChaptersPerDay: Float
            get() = if (daysActive == 0) 0f else totalChapters.toFloat() / daysActive

        /** The title with the most time on it, or null when nothing is logged. */
        val favourite: TitleTotal?
            get() = titles.values.filter { it.seconds > 0L }.maxByOrNull { it.seconds }

        fun secondsOn(day: String): Long = days[day]?.seconds ?: 0L

        /** Everything logged on [day], longest first — the day panel's own list,
         *  and what the headline figures drill into once a day is picked. */
        fun titlesOn(day: String): List<TitleTotal> =
            days[day]?.titles?.values?.sortedByDescending { it.seconds } ?: emptyList()

        /** The whole log, longest first — what the "Time spent" and "Items
         *  consumed" sheets list when no particular day is picked. */
        val allTitles: List<TitleTotal>
            get() = titles.values.sortedByDescending { it.seconds }

        /** Every day with anything on it, newest first, for "Days active". */
        val activeDays: List<Pair<String, Day>>
            get() = days.entries.filter { !it.value.isEmpty }
                .sortedByDescending { it.key }
                .map { it.key to it.value }

        /**
         * Consecutive active days ending today. A day with nothing logged YET
         * does not break the streak (at 09:00 the user has not watched anything
         * today, and "0 day streak" for a run that is still alive is simply
         * wrong), so the count starts at yesterday in that case.
         */
        fun currentStreak(today: String = dayKey()): Int {
            val cursor = parseDay(today) ?: return 0
            if (days[dayKeyOf(cursor)]?.isEmpty != false) cursor.add(Calendar.DAY_OF_MONTH, -1)
            var n = 0
            while (true) {
                val d = days[dayKeyOf(cursor)] ?: break
                if (d.isEmpty) break
                n++
                cursor.add(Calendar.DAY_OF_MONTH, -1)
            }
            return n
        }

        /** The longest run of consecutive active days ever logged. */
        val longestStreak: Int get() {
            var best = 0
            var run = 0
            var prev: Calendar? = null
            for (key in days.keys.sorted()) {
                val d = days[key] ?: continue
                if (d.isEmpty) continue
                val cal = parseDay(key) ?: continue
                run = if (prev != null && isNextDay(prev, cal)) run + 1 else 1
                prev = cal
                if (run > best) best = run
            }
            return best
        }

        /**
         * [weeks] columns of seven days, oldest column first, each row a weekday
         * starting at Sunday (the "S M T W T F S" the heatmap is labelled with).
         * A cell is null for a day that has not happened yet, so the current
         * week's tail stays blank instead of reading as "no activity".
         */
        fun heatmap(weeks: Int = 12, today: String = dayKey()): List<List<HeatCell?>> {
            val todayCal = parseDay(today) ?: return emptyList()
            val sunday = (todayCal.clone() as Calendar).apply {
                // Calendar.SUNDAY is 1, so this lands on the start of this week
                // whatever the phone's first-day-of-week preference is.
                add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) - 1))
            }
            val cols = ArrayList<List<HeatCell?>>(weeks)
            for (col in 0 until weeks) {
                val weekStart = (sunday.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, -(weeks - 1 - col) * 7)
                }
                val daysOfWeek = ArrayList<HeatCell?>(7)
                for (d in 0 until 7) {
                    val day = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, d) }
                    daysOfWeek.add(
                        if (day.after(todayCal)) null
                        else HeatCell(dayKeyOf(day), secondsOn(dayKeyOf(day)))
                    )
                }
                cols.add(daysOfWeek)
            }
            return cols
        }
    }

    // ---- The rank ladder -------------------------------------------------
    //
    // Ten hours of watching is the first rung, and the ladder is deliberately
    // long-tailed: the names stop being flattering somewhere around "Sleepless
    // Archon", which is the point. [Rank.xp] counts 1 per six minutes, so the
    // reference app's "12m · 2 XP" comes out exactly (see [rankFor]).

    private val TIERS: List<Pair<String, Float>> = listOf(
        "Filthy Casual" to 1f,
        "Weekend Warrior" to 5f,
        "Binge Apprentice" to 10f,
        "Certified Binger" to 25f,
        "Otaku" to 50f,
        "Binge Master" to 100f,
        "Sleepless Archon" to 250f,
        "Timeless Entity" to 500f,
        "Beyond Time" to 1000f,
    )

    data class Rank(
        /** The tier's name ("FILTHY CASUAL"). */
        val name: String,
        /** 1-based rung on the ladder. */
        val level: Int,
        val xp: Int,
        /** Hours logged so far. */
        val hours: Float,
        /** The hour count this rung ends at. */
        val targetHours: Float,
        /** 0f..1f through the current rung. */
        val progress: Float,
        /** True on the last rung, where there is nothing left to climb. */
        val isTop: Boolean,
    )

    /** 1 XP per six minutes watched or read. */
    fun rankFor(totalSeconds: Long): Rank {
        val xp = (totalSeconds / 360L).toInt()
        val hours = totalSeconds / 3600f
        var prevTarget = 0f
        for ((i, tier) in TIERS.withIndex()) {
            val target = tier.second
            if (hours < target) {
                val span = (target - prevTarget).coerceAtLeast(0.0001f)
                return Rank(
                    name = tier.first,
                    level = i + 1,
                    xp = xp,
                    hours = hours,
                    targetHours = target,
                    progress = ((hours - prevTarget) / span).coerceIn(0f, 1f),
                    isTop = false,
                )
            }
            prevTarget = target
        }
        val last = TIERS.last()
        return Rank(
            name = last.first,
            level = TIERS.size,
            xp = xp,
            hours = hours,
            targetHours = last.second,
            progress = 1f,
            isTop = true,
        )
    }

    // ---- Day keys --------------------------------------------------------

    /** The `yyyy-MM-dd` key of the day [cal] falls in. */
    fun dayKeyOf(cal: Calendar): String = String.format(
        Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )

    /** The local calendar day [at] falls in, as `yyyy-MM-dd`. */
    fun dayKey(at: Long = System.currentTimeMillis()): String =
        dayKeyOf(Calendar.getInstance().apply { timeInMillis = at })

    /** `yyyy-MM-dd` parsed back into a calendar, or null when it is malformed. */
    fun parseDay(key: String): Calendar? {
        val parts = key.split('-')
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        return Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d)
        }
    }

    /** True when [cal] is the calendar day straight after [prev]. */
    fun isNextDay(prev: Calendar, cal: Calendar): Boolean {
        val next = (prev.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        return next.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
            next.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    }

    /** "September 22, 2026" in the app's language, for the heatmap's caption. */
    fun prettyDay(key: String): String {
        val cal = parseDay(key) ?: return key
        val tag = com.hikari.app.i18n.I18n.currentTag
        val locale = if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)
        return runCatching {
            java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, locale).format(cal.time)
        }.getOrDefault(key)
    }

    /** "12m" / "1h 40m" / "3h" — how a time spent total is printed. */
    fun durationLabel(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val minutes = s / 60L
        if (minutes < 60L) return "${minutes}m"
        val h = minutes / 60L
        val m = minutes % 60L
        return if (m == 0L) "${h}h" else "${h}h ${m}m"
    }

    /** "0.2h" / "1h" — the level-progress figure, which reads in hours. */
    fun hoursLabel(hours: Float): String =
        if (hours >= 10f || hours == hours.toInt().toFloat()) "${hours.toInt()}h"
        else String.format(Locale.US, "%.1fh", hours)

    // ---- Read / write ----------------------------------------------------

    fun decode(json: String?): Snapshot {
        if (json.isNullOrBlank()) return Snapshot(emptyMap(), emptyMap())
        return runCatching {
            val root = JSONObject(json)
            val days = LinkedHashMap<String, Day>()
            root.optJSONObject("days")?.let { obj ->
                for (k in obj.keys()) {
                    val o = obj.optJSONObject(k) ?: continue
                    val perTitle = LinkedHashMap<String, TitleTotal>()
                    o.optJSONObject("tt")?.let { tt ->
                        for (tk in tt.keys()) {
                            tt.optJSONObject(tk)?.let { row -> perTitle[tk] = decodeTitle(row) }
                        }
                    }
                    days[k] = Day(
                        seconds = o.optLong("s", 0L),
                        videos = o.optInt("v", 0),
                        chapters = o.optInt("c", 0),
                        titles = perTitle,
                    )
                }
            }
            val titles = LinkedHashMap<String, TitleTotal>()
            root.optJSONObject("titles")?.let { obj ->
                for (k in obj.keys()) {
                    val o = obj.optJSONObject(k) ?: continue
                    titles[k] = decodeTitle(o)
                }
            }
            Snapshot(days, titles)
        }.getOrElse { Snapshot(emptyMap(), emptyMap()) }
    }

    fun encode(snapshot: Snapshot): String {
        val root = JSONObject()
        root.put("v", 2)
        val daysObj = JSONObject()
        for ((k, d) in snapshot.days) {
            if (d.isEmpty) continue
            val perTitle = JSONObject()
            for ((tk, t) in d.titles) perTitle.put(tk, encodeTitle(t))
            daysObj.put(k, JSONObject().apply {
                put("s", d.seconds)
                put("v", d.videos)
                put("c", d.chapters)
                // The day's own breakdown, so the heatmap can answer "what was
                // that 2m?" without a second document.
                if (perTitle.length() > 0) put("tt", perTitle)
            })
        }
        root.put("days", daysObj)
        val titlesObj = JSONObject()
        for ((k, t) in snapshot.titles) {
            titlesObj.put(k, encodeTitle(t))
        }
        root.put("titles", titlesObj)
        return root.toString()
    }

    /** One title row as it is STORED — shared by the all-time map and by each
     *  day's own breakdown, so the two can never drift apart. */
    private fun decodeTitle(o: JSONObject): TitleTotal = TitleTotal(
        title = o.optString("t"),
        posterUrl = o.optString("p").takeIf { it.isNotBlank() },
        seconds = o.optLong("s", 0L),
        kind = o.optString("k"),
        videos = o.optInt("v", 0),
        chapters = o.optInt("c", 0),
    )

    private fun encodeTitle(t: TitleTotal): JSONObject = JSONObject().apply {
        put("t", t.title)
        if (!t.posterUrl.isNullOrBlank()) put("p", t.posterUrl)
        put("s", t.seconds)
        put("k", t.kind)
        put("v", t.videos)
        put("c", t.chapters)
    }

    /** The key a title is totalled under, when it has one at all. */
    private fun titleKey(key: String?, title: String?): String? =
        key?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }?.let { "title:$it" }

    private fun withTotals(
        json: String?,
        at: Long,
        key: String?,
        title: String?,
        posterUrl: String?,
        kind: String?,
        seconds: Long,
        videos: Int,
        chapters: Int,
    ): String {
        val snapshot = decode(json)
        val days = snapshot.days.toMutableMap()
        val dayKey = dayKey(at)
        val day = days[dayKey] ?: Day()
        val tk = titleKey(key, title)
        // The row this event belongs to, merged over whatever that day/whole-log
        // total already held. Also used for the ONE-TIME events (`addVideo`,
        // `addChapter`, which carry no seconds of their own): a title row is
        // recorded for every event, not only for the ones with time on them —
        // opening a chapter and closing it again is an item consumed, and it used
        // to leave no row at all, so "Items consumed: 9" sat next to a title list
        // that could account for none of them.
        fun bump(prev: TitleTotal?): TitleTotal = TitleTotal(
            title = title?.takeIf { it.isNotBlank() } ?: prev?.title.orEmpty(),
            posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: prev?.posterUrl,
            seconds = (prev?.seconds ?: 0L) + seconds,
            kind = kind?.takeIf { it.isNotBlank() } ?: prev?.kind.orEmpty(),
            videos = (prev?.videos ?: 0) + videos,
            chapters = (prev?.chapters ?: 0) + chapters,
        )
        val dayTitles = if (tk == null) {
            day.titles
        } else {
            day.titles.toMutableMap().apply { this[tk] = bump(day.titles[tk]) }
        }
        days[dayKey] = day.copy(
            seconds = day.seconds + seconds,
            videos = day.videos + videos,
            chapters = day.chapters + chapters,
            titles = dayTitles,
        )
        val titles = snapshot.titles.toMutableMap()
        if (tk != null && (seconds > 0L || videos > 0 || chapters > 0)) {
            titles[tk] = bump(titles[tk])
        }
        return encode(Snapshot(days, titles))
    }

    /** Adds [seconds] of playback/reading to today's bucket and the title total. */
    fun addSeconds(
        json: String?,
        at: Long,
        seconds: Long,
        key: String?,
        title: String?,
        posterUrl: String?,
        kind: String?,
    ): String =
        if (seconds <= 0L) json.orEmpty()
        else withTotals(json, at, key, title, posterUrl, kind, seconds, 0, 0)

    /** Counts one video as consumed (a video the user actually started). */
    fun addVideo(
        json: String?,
        at: Long,
        key: String?,
        title: String?,
        posterUrl: String?,
        kind: String?,
    ): String = withTotals(json, at, key, title, posterUrl, kind, 0L, 1, 0)

    /** Counts one manga chapter as consumed. */
    fun addChapter(
        json: String?,
        at: Long,
        key: String?,
        title: String?,
        posterUrl: String?,
    ): String = withTotals(json, at, key, title, posterUrl, KIND_MANGA, 0L, 0, 1)
}
