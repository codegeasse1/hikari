package com.hikari.app.dub

import java.util.Locale

/** Who is speaking, as far as the subtitle lets us tell. */
enum class Gender { MALE, FEMALE, CHILD, NEUTRAL, UNKNOWN }

/**
 * How one character should sound, relative to the base voice that the speech
 * engine produces (Google Translate's voice is a female-sounding voice).
 *
 *  - [semitones] pitch shift (negative = deeper). The shift is done by
 *    resampling, so the formants move with it: a deeper voice also sounds like
 *    a bigger speaker, which is what makes a male voice read as male.
 *  - [tempo] speaking speed (1.0 = unchanged, pitch is not affected by it).
 */
data class VoiceProfile(
    val gender: Gender,
    val semitones: Float,
    val tempo: Float,
    val name: String,
) {
    /** Stable text used in cache file names. */
    val key: String get() = gender.name + "|" + semitones + "|" + tempo
}

/**
 * Guesses a character's gender from the name written in the subtitle
 * ("NARUTO:", "[Sakura]", "Mrs. Tanaka", "Kakashi-sensei", "Grandma" ...).
 * No network, no API: a built-in name list plus honorific / ending rules.
 * Returns [Gender.UNKNOWN] when it genuinely cannot tell.
 */
object NameGender {

    private fun words(s: String): Set<String> =
        s.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toHashSet()

    private val MALE_NAMES: Set<String> = words(
        """
        john james michael david robert william richard thomas charles joseph daniel matthew mark paul
        steven kevin brian george edward ronald anthony jason jeff gary ryan jacob nicholas eric jonathan
        justin scott brandon frank benjamin gregory samuel raymond patrick alexander jack dennis jerry
        tyler aaron henry douglas peter adam nathan zachary walter kyle harold carl arthur gerald roger
        keith jeremy terry lawrence sean christian albert joe ethan austin jesse willie billy bruce
        bryan ralph roy eugene randy vincent russell louis philip bobby johnny bradley tom bob jim mike
        dave steve tim tony harry ron fred ted chuck ben dan max leo oscar felix victor hugo oliver
        liam noah mason logan lucas jayden luke elijah owen wyatt carter julian leonardo marco carlos
        juan jose miguel pedro diego pablo antonio fernando jorge luis andre pierre francois hans klaus
        otto karl fritz dmitri ivan sergei vladimir boris igor alexei nikolai yuri
        naruto sasuke kakashi itachi madara obito minato jiraiya orochimaru gaara neji shikamaru choji
        kiba sai tobirama hashirama asuma guy shino lee
        luffy zoro sanji usopp chopper franky sabo shanks kidd garp roger
        goku vegeta gohan piccolo krillin trunks goten frieza beerus whis broly yamcha tien
        eren armin levi erwin reiner bertholdt connie hanji zeke
        ichigo renji byakuya uryu chad kenpachi toshiro aizen urahara
        tanjiro zenitsu inosuke giyu rengoku tengen muichiro sanemi obanai gyomei akaza muzan
        izuku deku bakugo todoroki shoto iida
        kirito ryuk mello lelouch suzaku kira athrun shinji kaworu gendo
        edward alphonse roy mustang hughes natsu gray gajeel laxus jellal saitama genos jinwoo
        yuji gojo satoru sukuna itadori fushiguro nanami geto toji yuta inuyasha sesshomaru miroku
        shippo kenshin sasori deidara hidan kakuzu nagato yahiko jotaro dio joseph josuke giorno
        okabe rintaro daru itaru subaru natsuki kazuma
        mohammed muhammad ahmed ahmad ali omar umar khaled khalid youssef yusuf hassan hussein hussain
        ibrahim abdullah mustafa mahmoud karim sami tarek tariq yasser majed fahd sultan saad ziad
        walid hisham
        rahul amit raj rohan arjun vikram sanjay ajay ravi suresh ramesh anil sunil manoj deepak karan
        aman sumon rafiq rahim sakib tamim mahmud jamal kabir salman shahrukh aryan
        """
    )

    private val FEMALE_NAMES: Set<String> = words(
        """
        tanya mary patricia jennifer linda elizabeth barbara susan jessica sarah karen nancy lisa betty
        margaret sandra ashley kimberly emily donna michelle carol amanda dorothy melissa deborah
        stephanie rebecca sharon laura cynthia kathleen amy angela shirley anna brenda pamela emma
        nicole helen samantha katherine christine debra rachel carolyn janet catherine maria heather
        diane ruth julie olivia joyce virginia victoria kelly lauren christina joan evelyn judith
        megan andrea cheryl hannah jacqueline martha gloria teresa ann sara madison frances kathryn
        janice abigail alice julia judy sophia grace denise amber doris marilyn danielle beverly
        isabella theresa diana natalie brittany charlotte marie kayla alexis lori anne kate kathy
        jane jenny lucy ella chloe zoe mia lily ava amelia isabel eva ellie molly daisy rose violet
        hazel ivy luna stella nora lena sophie claire clara elsa kristen beatrice carmen rosa lucia
        sofia valentina camila paula elena irina olga natasha tatiana svetlana anastasia katya yulia
        sakura hinata tsunade ino tenten temari konan kushina mikasa historia ymir annie sasha petra
        nami robin vivi hancock boa perona bulma chichi videl launch rukia orihime rangiku yoruichi
        soifon nelliel nezuko kanao mitsuri kanae asuna sinon yui rem ram emilia echidna megumin aqua
        darkness misaki kaguya chika kana akane mahiru shiina tohru kyoko usagi ami minako tohka
        kurumi kotori nino miku ichika yotsuba itsuki mai tomoe yuno mirai kagome sango kikyo kaede
        asuka misato ritsuko winry riza lust olivier erza lucy wendy juvia levy mirajane lisanna cana
        fatima fatimah aisha aysha ayesha maryam mariam sarah sara layla laila leila noor nour zainab
        zaynab huda salma rana dina yasmin yasmine amal mona souad khadija ruqayya hind reem lina
        priya anjali pooja neha sneha kavita sunita anita meera radha sita gita rina rupa nusrat
        sumaiya farhana jannat tania mim sharmin
        """
    )

    /** Titles / family words that are clearly male. */
    private val MALE_WORDS: Set<String> = words(
        """
        mr mister sir lord king prince man guy gentleman father dad daddy papa pa brother bro uncle
        grandpa grandfather gramps husband son boyfriend duke emperor gentlemen
        oniisan onii nii oni otousan otosan ojisan ojiisan jiji aniki
        أبو ابو الأب الاب رجل السيد ولد
        """
    )

    /** Titles / family words that are clearly female. */
    private val FEMALE_WORDS: Set<String> = words(
        """
        mrs miss ms madam madame lady queen princess woman mother mom mum mama mommy sister sis aunt
        auntie grandma grandmother granny wife daughter girlfriend duchess empress maid witch
        onee oneesan nee okaasan okasan obasan obaasan baba
        أم ام الأم الام بنت فتاة السيدة الآنسة
        """
    )

    /** Young speakers get a higher voice. */
    private val CHILD_WORDS: Set<String> = words(
        "kid kids child children baby boy girl little youngster toddler"
    )

    /** Voices that are not a person with a gender. */
    private val NEUTRAL_WORDS: Set<String> = words(
        """
        narrator narration announcer voiceover vo tv radio system computer robot crowd everyone all
        both audience students chorus together
        """
    )

    /** Endings that are almost always one gender when the name is not in the lists. */
    private val AMBIGUOUS: Set<String> = words(
        """
        yuki haru akira makoto sasha alex sam chris kim jun hikaru kaoru rei ren mizuki shinobu nao
        masaru rin hikari jean ai taylor jordan casey
        """
    )
    private val FEMALE_ENDINGS = listOf(
        "ko", "mi", "ka", "na", "ne", "ri", "ia", "ina", "ine", "ella", "elle", "ette", "ika", "sa",
    )
    private val MALE_ENDINGS = listOf(
        "ro", "ru", "ta", "to", "shi", "suke", "ke", "maru", "kazu", "hiko", "ichi", "taro", "tarou",
    )

    private val SPLIT = Regex("""[^\p{L}']+""")

    fun guess(label: String): Gender {
        val tokens = label.lowercase(Locale.ROOT)
            .split(SPLIT)
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Gender.UNKNOWN

        if (tokens.any { it in NEUTRAL_WORDS }) return Gender.NEUTRAL
        if (tokens.any { it in CHILD_WORDS }) return Gender.CHILD

        var male = 0
        var female = 0
        for (t in tokens) {
            if (t in MALE_NAMES || t in MALE_WORDS) male++
            if (t in FEMALE_NAMES || t in FEMALE_WORDS) female++
        }
        if (male > female) return Gender.MALE
        if (female > male) return Gender.FEMALE

        // Japanese honorifics: -kun is for boys / men, -chan mostly for girls.
        if ("kun" in tokens) return Gender.MALE
        if ("chan" in tokens) return Gender.FEMALE

        // Last resort: the way the first name ends.
        val first = tokens[0]
        if (first.length >= 4 && first !in AMBIGUOUS) {
            for (e in MALE_ENDINGS) if (first.endsWith(e)) return Gender.MALE
            for (e in FEMALE_ENDINGS) if (first.endsWith(e)) return Gender.FEMALE
        }
        return Gender.UNKNOWN
    }
}

/**
 * The voices chosen for one subtitle: every named character gets a voice of
 * its own (same character = same voice for the whole video).
 */
class Cast(
    val roster: List<Pair<String, VoiceProfile>>,
    /** Voice of every line that has no character name (decided in advance, see
     *  [CastDirector.assign]). Keyed by the cue object itself. */
    private val anon: Map<DubCue, VoiceProfile> = emptyMap(),
) {

    private val byKey: Map<String, VoiceProfile> =
        roster.associate { (name, profile) -> CastDirector.keyOf(name) to profile }

    /** Counts the lines handed out in live mode (no cue list to plan from). */
    private val liveTurn = java.util.concurrent.atomic.AtomicInteger(0)

    fun profileFor(cue: DubCue): VoiceProfile {
        val s = cue.speaker
        if (s != null) {
            val p = byKey[CastDirector.keyOf(s)]
            if (p != null) return p
        }
        // Lines with no name: a man or a woman, planned for the whole subtitle.
        anon[cue]?.let { return it }
        // Live captions (no list to plan from): man and woman take turns.
        val n = liveTurn.getAndIncrement()
        return CastDirector.anonVoice(n % 2 == 1, n / 2)
    }

    /** "Naruto ♂, Sakura ♀" — shown in the dub panel. Empty when no names. */
    fun describe(): String =
        roster.joinToString(", ") { (name, profile) ->
            val mark = when (profile.gender) {
                Gender.MALE -> " \u2642"
                Gender.FEMALE -> " \u2640"
                Gender.CHILD -> " (child)"
                Gender.NEUTRAL -> " (narrator)"
                Gender.UNKNOWN -> ""
            }
            name + mark
        }
}

object CastDirector {

    /** Main / unnamed voice: the base voice, untouched. */
    val NARRATOR = VoiceProfile(Gender.NEUTRAL, 0f, 1f, "Narrator")

    /** Second unnamed voice of a two-person exchange (the old man's voice). */
    val SECOND = VoiceProfile(Gender.MALE, -6.0f, 1f, "Voice B")

    /** New artists, added next to the two old ones: another woman ("Voice C")
     *  and another man ("Voice D", lighter than "Voice B"). */
    val FEMALE_B = VoiceProfile(Gender.FEMALE, 2.0f, 1.03f, "Voice C")
    val MALE_B = VoiceProfile(Gender.MALE, -4.5f, 1.04f, "Voice D")

    private val ANON_FEMALE = listOf(NARRATOR, FEMALE_B)
    private val ANON_MALE = listOf(SECOND, MALE_B)

    /** The [k]-th unnamed woman / man voice (old artist first, then the new one). */
    fun anonVoice(male: Boolean, k: Int): VoiceProfile =
        (if (male) ANON_MALE else ANON_FEMALE)[k % 2]

    /** A pause this long after a finished sentence is taken as someone else talking. */
    private const val TURN_GAP_MS = 600L

    // The first male is a normal deep voice, the next ones differ a little in
    // depth and speed so two men in one scene never sound identical.
    private val MALE_PITCH = floatArrayOf(-5.0f, -3.5f, -6.5f, -2.5f, -5.8f, -4.2f)
    private val MALE_TEMPO = floatArrayOf(1.00f, 1.04f, 0.95f, 1.06f, 0.97f, 1.02f)
    private val FEMALE_PITCH = floatArrayOf(1.0f, 2.5f, 0.3f, 3.5f, 1.8f, 3.0f)
    private val FEMALE_TEMPO = floatArrayOf(1.00f, 1.03f, 0.96f, 1.05f, 0.98f, 1.01f)

    private val HONORIFIC = Regex("""[-\s](kun|chan|san|sama|senpai|sensei|dono)$""")
    private val SPACES = Regex("""\s+""")

    /** Same character, however it is written ("NARUTO", "Naruto-kun"). */
    fun keyOf(name: String): String =
        HONORIFIC.replace(
            SPACES.replace(name.lowercase(Locale.ROOT), " ").trim(),
            "",
        )

    /**
     * [scan] = what listening to the ORIGINAL voices found (see
     * [VoiceScanResult]); null = no scan, the old name / random rules only.
     * With a scan, a named character whose name does not show the sex gets
     * the sex of its real voice, and every line without a name gets the voice
     * of the real speaker (same person = same dub voice, a man's line is
     * read by a man).
     */
    fun assign(cues: List<DubCue>, scan: VoiceScanResult? = null): Cast {
        val seen = LinkedHashMap<String, Pair<String, VoiceProfile>>()
        var males = 0
        var females = 0
        for (cue in cues) {
            val name = cue.speaker ?: continue
            val key = keyOf(name)
            if (seen.containsKey(key)) continue

            var g = NameGender.guess(name)
            if (g == Gender.UNKNOWN && scan != null) g = scannedSex(cues, name, scan)
            if (g == Gender.UNKNOWN) {
                // Cannot tell: keep the cast balanced so neighbours differ.
                g = if (males <= females) Gender.MALE else Gender.FEMALE
            }
            val profile = when (g) {
                Gender.MALE -> {
                    val i = males % MALE_PITCH.size
                    males++
                    VoiceProfile(Gender.MALE, MALE_PITCH[i], MALE_TEMPO[i], name)
                }
                Gender.FEMALE -> {
                    val i = females % FEMALE_PITCH.size
                    females++
                    VoiceProfile(Gender.FEMALE, FEMALE_PITCH[i], FEMALE_TEMPO[i], name)
                }
                Gender.CHILD -> VoiceProfile(Gender.CHILD, 5.5f, 1.06f, name)
                else -> VoiceProfile(Gender.NEUTRAL, 0f, 1f, name)
            }
            seen[key] = name to profile
        }
        val plan = planUnnamed(cues)
        if (scan != null) overrideFromScan(cues, scan, plan)
        return Cast(seen.values.toList(), plan)
    }

    /** The sex of the real voice behind a named character (most lines win), or UNKNOWN. */
    private fun scannedSex(cues: List<DubCue>, name: String, scan: VoiceScanResult): Gender {
        val key = keyOf(name)
        var men = 0
        var women = 0
        for (i in cues.indices) {
            val s = cues[i].speaker ?: continue
            if (keyOf(s) != key) continue
            when (scan.genderOf(i)) {
                Gender.MALE -> men++
                Gender.FEMALE -> women++
                else -> {}
            }
        }
        return when {
            men > women -> Gender.MALE
            women > men -> Gender.FEMALE
            else -> Gender.UNKNOWN
        }
    }

    /**
     * Replaces the random guess of [plan] by the real voices found by the scan.
     * Every scanned person gets a voice of its own (a man the next free man
     * look, a woman the next free woman look); a line whose person could not be
     * told but whose sex is known gets the main voice of that sex; a line with
     * neither keeps the old random guess.
     */
    private fun overrideFromScan(
        cues: List<DubCue>,
        scan: VoiceScanResult,
        plan: MutableMap<DubCue, VoiceProfile>,
    ) {
        val byVoice = HashMap<Int, VoiceProfile>()
        var males = 0
        var females = 0
        val mainOf = HashMap<Gender, VoiceProfile>()

        fun profileOf(voice: Int, g: Gender): VoiceProfile {
            val have = byVoice[voice]
            if (have != null) return have
            val p = if (g == Gender.MALE) {
                val i = males % MALE_PITCH.size
                males++
                VoiceProfile(Gender.MALE, MALE_PITCH[i], MALE_TEMPO[i], "Speaker " + (voice + 1))
            } else {
                val i = females % FEMALE_PITCH.size
                females++
                VoiceProfile(Gender.FEMALE, FEMALE_PITCH[i], FEMALE_TEMPO[i], "Speaker " + (voice + 1))
            }
            byVoice[voice] = p
            if (!mainOf.containsKey(g)) mainOf[g] = p
            return p
        }

        // first the people the scan could tell apart ...
        for (i in cues.indices) {
            val v = scan.voiceOf(i)
            val g = scan.genderOf(i)
            if (v >= 0 && (g == Gender.MALE || g == Gender.FEMALE) && cues[i].speaker == null) {
                plan[cues[i]] = profileOf(v, g)
            }
        }
        // ... then the lines where only the sex is known
        for (i in cues.indices) {
            val v = scan.voiceOf(i)
            val g = scan.genderOf(i)
            if (cues[i].speaker != null) continue
            if (v < 0 && (g == Gender.MALE || g == Gender.FEMALE)) {
                val main = mainOf[g] ?: profileOf(-1 - (if (g == Gender.MALE) 0 else 1), g)
                plan[cues[i]] = main
            }
        }
    }

    private fun endsSentence(t: String): Boolean {
        val last = t.trimEnd('"', '\'', '\u201D', '\u2019', ')', ' ').lastOrNull() ?: return true
        return last in ".!?\u3002\uFF01\uFF1F\u061F"
    }

    /**
     * Subtitles usually do not say who talks. The speaker cannot be known, so
     * every new "turn" (a pause after a finished sentence, or the second line
     * of a "- Hi / - Hello" exchange) gets a man or a woman at random. The
     * draw is repeatable (same subtitle = same voices), it never gives the
     * same sex three turns in a row, and the answer in a "- Hi / - Hello"
     * exchange is always the other sex. A wrong guess (a man's line read by a
     * woman) is accepted; what matters is that both kinds of voice are heard.
     */
    private fun planUnnamed(cues: List<DubCue>): MutableMap<DubCue, VoiceProfile> {
        val out = java.util.IdentityHashMap<DubCue, VoiceProfile>()
        val rnd = java.util.Random(31L * cues.size + (cues.firstOrNull()?.startMs ?: 0L))
        var male = false
        var run = 0
        val taken = intArrayOf(0, 0)          // turns given so far: [women, men]
        var current = NARRATOR
        var prev: DubCue? = null
        for (cue in cues) {
            if (cue.speaker != null) {        // a named character has its own voice
                prev = cue
                continue
            }
            val p = prev
            val newTurn = p == null ||
                cue.turn > 0 ||
                (cue.startMs - p.endMs >= TURN_GAP_MS && endsSentence(p.text))
            if (newTurn) {
                val was = male
                male = when {
                    p == null -> rnd.nextBoolean()
                    cue.turn > 0 -> !was
                    run >= 2 -> !was
                    else -> rnd.nextBoolean()
                }
                run = if (p != null && male == was) run + 1 else 1
                val idx = if (male) 1 else 0
                current = anonVoice(male, taken[idx])
                taken[idx] = taken[idx] + 1
            }
            out[cue] = current
            prev = cue
        }
        return out
    }
}
