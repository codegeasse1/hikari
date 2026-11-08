package com.hikari.app.dub

import java.util.Locale

/**
 * How a line should be delivered (decided from the subtitle, no AI needed).
 * The audio editor ([DubFx]) turns each one into a different way of speaking:
 * pitch movement, speed, loudness, breath, roughness, trembling.
 */
enum class Emotion { NORMAL, SHOUT, WHISPER, SAD, EXCITED, ANGRY, HAPPY, FEAR, SURPRISE }

/**
 * One timed line of a subtitle file.
 *
 * [speaker] is the character name when the subtitle writes it ("NARUTO: ...",
 * "[Sakura] ...", WebVTT `<v Sakura>`), otherwise null. [turn] is the position
 * of the line inside its cue, used to alternate two unnamed voices in a
 * "- Hi / - Hello" exchange.
 */
data class DubCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
    val turn: Int = 0,
    val emotion: Emotion = Emotion.NORMAL,
)

/**
 * Parses SRT and WebVTT text into [DubCue]s.
 *
 * Only the spoken words are kept: markup (`<i>`, `<font ...>`), ASS override
 * blocks (`{\an8}`), HTML entities, sound descriptions (`[music]`, `(sighs)`)
 * and music-note lines are removed, because the text is read out loud.
 *
 * On top of that it:
 *  - finds who is speaking ("NAME:", "[Name]", "(Name)", `<v Name>`), and
 *    splits a cue that holds two speakers into two timed lines;
 *  - turns "- line / - line" exchanges into separate turns;
 *  - reads delivery hints ("[whispering]", "(shouting)", ALL CAPS, "!!");
 *  - joins a sentence that the subtitle broke over two cues, so the voice
 *    reads it as one sentence with natural intonation.
 */
object SubtitleCues {

    private const val FORM_VTT = 0
    private const val FORM_COLON = 1
    private const val FORM_BRACKET = 2

    private val TIME = Regex(
        """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})"""
    )
    private val ASS = Regex("""\{\\[^}]*\}""")
    private val KEEP_V_TAGS = Regex("""<(?!/?v[\s.>])[^>]*>""")
    private val ANY_TAG = Regex("""<[^>]*>""")
    private val VTT_VOICE = Regex("""^\s*<v(?:\.[^\s>]*)*\s+([^>]+)>(.*)$""", RegexOption.IGNORE_CASE)
    private val PAREN_TAIL = Regex("""\s*[(\[]([^)\]]*)[)\]]\s*$""")
    private val NAME_OK = Regex("""[\p{Lu}\p{Lo}][\p{L}\p{M}'’.\- ]{0,26}""")
    private val BRACKET_LABEL = Regex("""^[\[(]([^\])]{1,24})[\])]\s*:?\s*(.+)$""")
    private val DASH_TURN = Regex("""^\s*[-–—‐]\s+""")
    private val DASH_LEAD = Regex("""^\s*[-–—‐]+\s*""")
    private val BRACKETS_HINT = Regex("""\[([^\]]*)\]|\(([^)]*)\)""")
    private val BRACKET_ALL = Regex("""\[[^\]]*\]""")
    private val PAREN = Regex("""\(([^)]*)\)""")
    private val SPACES = Regex("""\s+""")
    private val BLOCK_SPLIT = Regex("""\n{2,}""")
    private val STUTTER = Regex("""(?i)\b(\p{L})-(\1\p{L}*)""")

    private val DESCRIPTOR = Regex(
        """(?i)\b(?:laugh|chuckl|giggl|sigh|gasp|groan|moan|cough|sneez|scream|shout|yell|cry|cries|crying|sob|weep|whisper|mutter|murmur|sing|hum|music|applaus|cheer|knock|ring|beep|buzz|bell|footstep|door|phone|thunder|wind|breath|sniff|snicker|growl|grunt|scoff|clears? throat|inaudible|indistinct|silence|pause|static|crowd|speaking|speaks|says|continues|o\.s|v\.o|off-screen|narrat|gunshot|explosion|crash|slam|screech|chatter|echo|sfx|sound|noise)"""
    )

    // Delivery hints written in the subtitle: "[angrily]", "(crying)", "(whispers)" ...
    private val H_WHISPER = Regex("""whisper|murmur|mutter|softly|quietly|hushed|low voice""")
    private val H_ANGRY = Regex("""angry|angrily|furious|furiously|growl|snarl|seeth|enraged|rage|hiss|sneer|scoff|coldly|bitter|irritated|annoyed|snap""")
    private val H_SHOUT = Regex("""shout|yell|scream|roar|bellow|exclaim|cry out|call out|battle cry""")
    private val H_FEAR = Regex("""scared|afraid|fear|terrified|frighten|panic|gasp|trembl|nervous|stammer|stutter|shak|horrif|whimper""")
    private val H_SURPRISE = Regex("""surpris|shock|astonish|stunned|disbelie|startl|incredul|amaz""")
    private val H_SAD = Regex("""cry|sob|weep|tearful|sniffl|sorrow|sadly|sigh|mourn|grie|heartbroken|choked|voice breaking|lament|despair|dejected""")
    private val H_EXCITED = Regex("""excitedly|eager|enthusias|thrilled|energetic|hyped""")
    private val H_HAPPY = Regex("""cheerfully|happily|joyful|giggl|laughing|laughs|laugh|chuckl|smil|warmly|playful|teasing|amused|delighted|gleeful""")

    // Feelings read from the words themselves (used when there is no hint).
    private val T_ANGRY = Regex("""(?i)\b(?:damn|damned|bastard|idiot|stupid|shut up|how dare|i(?:'ll| will) kill|you fool|hate you|get out|go away|don'?t touch|bullshit|asshole|screw you|liar|traitor|disgusting|pathetic|worthless|monster|scum|trash|die)\b""")
    private val T_FEAR = Regex("""(?i)\b(?:help|run|please don'?t|don'?t kill|stay away|get away|don'?t hurt|ghost|scared|afraid|terrified|oh god|oh no|can'?t be|behind you|look out|watch out|it'?s coming|we'?re going to die|no no)\b""")
    private val T_SURPRISE = Regex("""(?i)\b(?:no way|what the|you'?re kidding|are you serious|impossible|unbelievable|can'?t believe|since when)\b""")
    private val T_SURPRISE_SHORT = Regex("""(?i)\b(?:what|huh|really|seriously|eh|ehh)\b""")
    private val T_SAD = Regex("""(?i)\b(?:miss you|i miss|lonely|never see|goodbye|farewell|funeral|tears|crying|it hurts|my fault|forgive me|i failed|too late|don'?t leave|please don'?t go|regret|so sorry|passed away|died)\b""")
    private val T_HAPPY = Regex("""(?i)\b(?:yay|yes|awesome|great|wonderful|amazing|thank you|thanks|congratulations|we did it|we won|finally|love you|so happy|nice|wow|fantastic|beautiful|perfect|good job|well done)\b""")
    private val T_LAUGH = Regex("""(?i)\b(?:(?:ha){2,}|(?:he){2,}|ha ha|he he|lol)\b""")

    private val LEAD_ELLIPSIS = Regex("""^\s*(?:\.{2,}|…)\s*""")
    private val TRAIL_ELLIPSIS = Regex("""\s*(?:\.{2,}|…)\s*$""")
    private val MID_ELLIPSIS = Regex("""\s*(?:\.{2,}|…)\s*""")
    private val SPACED_DASH = Regex("""\s+[-–—]+\s+""")
    private val TIGHT_DASH = Regex("""(?<=\p{L})[–—](?=\p{L})""")
    private val LEAD_DASH = Regex("""^[\s\-–—]+""")
    private val COMMA_RUN = Regex(""",(\s*,)+""")

    /** Words that are followed by a colon but are not a character. */
    private val STOP: Set<String> = (
        "note notes warning sign signs title subtitle subtitles translator translators translation " +
        "caption captions song songs lyrics text chapter episode part scene time date place location " +
        "listen look remember hey yes no well so but and see think wait stop please sorry thanks okay ok " +
        "now then here there why what how who when where first second third one two answer question " +
        "example reminder attention caution danger fact tip hint rule rules news breaking update " +
        "imagine hmm huh um uh ah oh ahem sound music sfx tl editor encoder timer subs " +
        "translator's tl:note"
    ).split(" ").filter { it.isNotEmpty() }.toHashSet()

    private class RawCue(val start: Long, val end: Long, val lines: List<String>)
    private class Lbl(val name: String, val hint: String, val rest: String, val form: Int)
    private class Seg(val speaker: String?) {
        val sb = StringBuilder()
        val hint = StringBuilder()
    }

    /**
     * [multi] = true: find who is speaking and split "- Hi / - Hello" turns.
     * [multi] = false: the names / dashes are only removed, every line is
     * read in one plain voice.
     */
    fun parse(raw: String, multi: Boolean = true): List<DubCue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

        // 1) blocks -> raw cues
        val cues = ArrayList<RawCue>()
        for (block in text.split(BLOCK_SPLIT)) {
            val lines = block.split('\n')
            val timeIdx = lines.indexOfFirst { "-->" in it }
            if (timeIdx < 0) continue
            val m = TIME.find(lines[timeIdx]) ?: continue
            val start = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val end = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            if (end <= start) continue
            cues += RawCue(start, end, lines.drop(timeIdx + 1))
        }

        // 2) how often each "NAME:" label occurs (a label seen again and again is a character)
        val counts = HashMap<String, Int>()
        for (c in cues) {
            for (l in c.lines) {
                val lbl = labelOf(prepare(l)) ?: continue
                val key = CastDirector.keyOf(lbl.name)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }

        // 3) cues -> speaker segments
        val out = ArrayList<DubCue>()
        for (c in cues) {
            val segs = ArrayList<Seg>()
            var cur: Seg? = null
            for (rawLine in c.lines) {
                var line = prepare(rawLine)
                if (line.isBlank()) continue
                var newTurn = false
                if (DASH_TURN.containsMatchIn(line)) {
                    line = DASH_LEAD.replace(line, "")
                    newTurn = multi
                }
                var speaker: String? = null
                var body = line
                var hint = ""
                val lbl = labelOf(line)
                if (lbl != null && accepted(lbl, counts)) {
                    speaker = if (multi) lbl.name else null
                    body = lbl.rest
                    hint = lbl.hint
                }
                val existing = cur
                val seg: Seg = if (existing != null && speaker == null && !newTurn) {
                    existing
                } else {
                    val fresh = Seg(speaker)
                    segs.add(fresh)
                    cur = fresh
                    fresh
                }
                if (seg.sb.isNotEmpty()) seg.sb.append(' ')
                seg.sb.append(body)
                seg.hint.append(' ').append(hint)
                for (bm in BRACKETS_HINT.findAll(body)) {
                    seg.hint.append(' ').append(bm.groupValues[1]).append(' ').append(bm.groupValues[2])
                }
            }

            val kept = ArrayList<Seg>()
            val texts = ArrayList<String>()
            for (s in segs) {
                val t = clean(s.sb.toString())
                if (t.isNotBlank()) {
                    kept.add(s)
                    texts.add(t)
                }
            }
            if (kept.isEmpty()) continue

            // Two speakers in one cue: share its time in proportion to the text.
            val total = texts.sumOf { it.length }.coerceAtLeast(1)
            val dur = c.end - c.start
            var acc = 0
            for (i in kept.indices) {
                val s0 = c.start + dur * acc / total
                acc += texts[i].length
                val e0 = c.start + dur * acc / total
                out.add(
                    DubCue(s0, e0, texts[i], kept[i].speaker, i, emotionOf(kept[i].hint.toString(), texts[i]))
                )
            }
        }
        out.sortBy { it.startMs }
        return merge(out)
    }

    // ───────────────────────── speaker labels ─────────────────────────

    /** Strips styling markup but keeps WebVTT voice tags (`<v Name>`). */
    private fun prepare(line: String): String =
        KEEP_V_TAGS.replace(ASS.replace(line, " "), "").trim()

    private fun validName(label: String): Boolean {
        if (label.length < 2) return false
        if (!NAME_OK.matches(label)) return false
        val words = label.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 3) return false
        if (label.lowercase(Locale.ROOT).trimEnd('.') in STOP) return false
        if (words[0].lowercase(Locale.ROOT).trimEnd('.') in STOP) return false
        return true
    }

    private fun labelOf(line: String): Lbl? {
        val v = VTT_VOICE.find(line)
        if (v != null) {
            val name = v.groupValues[1].trim()
            if (name.isNotEmpty()) return Lbl(name, "", v.groupValues[2], FORM_VTT)
        }
        val plain = ANY_TAG.replace(line, "").trim()

        // NAME: text   /   Name (whispering): text   /   JOHN (O.S.): text
        val ci = plain.indexOfAny(charArrayOf(':', '：'))
        if (ci in 1..32) {
            val rest = plain.substring(ci + 1).trim()
            if (rest.isNotEmpty() && !rest.startsWith("//")) {
                var label = plain.substring(0, ci).trim()
                var hint = ""
                val pm = PAREN_TAIL.find(label)
                if (pm != null) {
                    hint = pm.groupValues[1]
                    label = label.substring(0, pm.range.first).trim()
                }
                if (validName(label)) return Lbl(label, hint, rest, FORM_COLON)
            }
        }

        // [Name] text   /   (Name) text
        val bm = BRACKET_LABEL.find(plain)
        if (bm != null) {
            val label = bm.groupValues[1].trim()
            val rest = bm.groupValues[2].trim()
            if (rest.isNotEmpty() && !label.contains("..") && validName(label)) {
                val g = NameGender.guess(label)
                val oneWord = !label.contains(' ')
                val descriptor = DESCRIPTOR.containsMatchIn(label) ||
                    (oneWord && (label.endsWith("ing", ignoreCase = true) || label.endsWith("ly", ignoreCase = true)))
                if (g != Gender.UNKNOWN || !descriptor) return Lbl(label, "", rest, FORM_BRACKET)
            }
        }
        return null
    }

    private fun accepted(l: Lbl, counts: Map<String, Int>): Boolean {
        if (l.form == FORM_VTT) return true
        if (NameGender.guess(l.name) != Gender.UNKNOWN) return true
        val allCaps = l.name.any { it.isLetter() && it.isUpperCase() } &&
            l.name == l.name.uppercase(Locale.ROOT) &&
            l.name.count { it.isLetter() } >= 2
        if (allCaps) return true
        return (counts[CastDirector.keyOf(l.name)] ?: 0) >= 3
    }

    // ───────────────────────── text cleaning ─────────────────────────

    private fun clean(s: String): String {
        var t = ASS.replace(s, " ")
        t = ANY_TAG.replace(t, " ")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        t = BRACKET_ALL.replace(t, " ")
        t = PAREN.replace(t) { m ->
            if (m.value.length <= 32 && DESCRIPTOR.containsMatchIn(m.value)) " " else " " + m.groupValues[1] + " "
        }
        t = SPACES.replace(t, " ").trim()
        // A line that is only music notes is not speech.
        if (t.all { it == '♪' || it == '♫' || it == '#' || it.isWhitespace() }) return ""
        t = t.trim('♪', '♫', ' ')
        t = destutter(t)
        if (!t.any { it.isLetterOrDigit() }) return ""
        return t.trim()
    }

    /** "W-what" -> "what", "I-I don't" -> "I don't" (a voice would spell them out). */
    private fun destutter(s: String): String {
        var t = s
        for (i in 0 until 3) {
            val n = STUTTER.replace(t, "\$2")
            if (n == t) break
            t = n
        }
        return t
    }

    /**
     * Last touch before the text goes to the voice: pauses instead of "...",
     * commas instead of dashes. Applied to the TRANSLATED text.
     */
    fun forSpeech(s: String): String {
        var t = s.replace('\u00A0', ' ')
        t = LEAD_ELLIPSIS.replace(t, "")
        t = TRAIL_ELLIPSIS.replace(t, ".")
        t = MID_ELLIPSIS.replace(t, ", ")
        t = SPACED_DASH.replace(t, ", ")
        t = TIGHT_DASH.replace(t, ", ")
        t = LEAD_DASH.replace(t, "")
        t = t.replace("♪", " ").replace("♫", " ")
        t = COMMA_RUN.replace(t, ",")
        t = SPACES.replace(t, " ").trim()
        t = t.replace(" ,", ",")
        return t
    }

    // ───────────────────────── delivery ─────────────────────────

    private fun emotionOf(hint: String, text: String): Emotion {
        // 1) the subtitle says it ("[angrily]", "(crying)" ...): believe it
        val h = hint.lowercase(Locale.ROOT)
        if (h.isNotBlank()) {
            if (H_WHISPER.containsMatchIn(h)) return Emotion.WHISPER
            if (H_ANGRY.containsMatchIn(h)) return Emotion.ANGRY
            if (H_SHOUT.containsMatchIn(h)) return Emotion.SHOUT
            if (H_FEAR.containsMatchIn(h)) return Emotion.FEAR
            if (H_SURPRISE.containsMatchIn(h)) return Emotion.SURPRISE
            if (H_SAD.containsMatchIn(h)) return Emotion.SAD
            if (H_EXCITED.containsMatchIn(h)) return Emotion.EXCITED
            if (H_HAPPY.containsMatchIn(h)) return Emotion.HAPPY
        }

        // 2) read it from the words and the punctuation
        val letters = text.filter { it.isLetter() }
        val bang = text.contains('!')
        val ask = text.contains('?')
        val dots = text.contains("...") || text.contains('\u2026')
        val allCaps = letters.length >= 4 && letters.any { it.isUpperCase() } && letters.none { it.isLowerCase() }
        val angryWords = T_ANGRY.containsMatchIn(text)

        if (allCaps || text.contains("!!")) {
            return if (angryWords) Emotion.ANGRY else Emotion.SHOUT
        }
        if (bang && angryWords) return Emotion.ANGRY
        if ((bang || dots) && T_FEAR.containsMatchIn(text)) return Emotion.FEAR
        if (text.contains("?!") || text.contains("!?")) return Emotion.SURPRISE
        if ((bang || ask) && T_SURPRISE.containsMatchIn(text)) return Emotion.SURPRISE
        if ((bang || ask) && text.length <= 14 && T_SURPRISE_SHORT.containsMatchIn(text)) return Emotion.SURPRISE
        if (T_LAUGH.containsMatchIn(text)) return Emotion.HAPPY
        if (T_SAD.containsMatchIn(text) && !angryWords) return Emotion.SAD
        if (bang && T_HAPPY.containsMatchIn(text)) return Emotion.HAPPY
        if (bang) return Emotion.EXCITED
        return Emotion.NORMAL
    }

    // ───────────────────────── joining broken sentences ─────────────────────────

    private fun merge(list: List<DubCue>): List<DubCue> {
        val res = ArrayList<DubCue>()
        for (c in list) {
            val p = res.lastOrNull()
            if (p != null && canMerge(p, c)) {
                res[res.size - 1] = DubCue(
                    p.startMs,
                    c.endMs,
                    p.text + " " + c.text,
                    p.speaker,
                    p.turn,
                    if (c.emotion != Emotion.NORMAL) c.emotion else p.emotion,
                )
            } else {
                res.add(c)
            }
        }
        return res
    }

    private fun canMerge(p: DubCue, c: DubCue): Boolean {
        if (p.turn != c.turn) return false
        val ps = p.speaker
        val cs = c.speaker
        // An unnamed line that continues an unfinished sentence belongs to the
        // same speaker; a line with its own name only joins the same name.
        if (cs != null) {
            if (ps == null) return false
            if (CastDirector.keyOf(ps) != CastDirector.keyOf(cs)) return false
        }
        val gap = c.startMs - p.endMs
        if (gap < -200L || gap > 450L) return false
        if (p.text.length + c.text.length > 220) return false
        if (c.endMs - p.startMs > 14_000L) return false
        return !endsSentence(p.text)
    }

    private fun endsSentence(t: String): Boolean {
        val s = t.trimEnd('"', '\'', '”', '’', ')', ' ')
        if (s.endsWith("...") || s.endsWith("…")) return false
        val last = s.lastOrNull() ?: return true
        return last in ".!?。！？؟"
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val hours = h.toLongOrNull() ?: 0L
        // "5" is .500, "05" is .050 — pad on the right to three digits.
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }
}
