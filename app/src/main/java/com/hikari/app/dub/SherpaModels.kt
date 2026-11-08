package com.hikari.app.dub

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import com.hikari.app.net.DohDns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One offline voice (run by sherpa-onnx):
 *  - a Hindi Piper voice (a fixed speaker),
 *  - an English Kokoro voice ([kokoro] = true; [sid] is its speaker number inside the Kokoro pack),
 *  - a CLONED voice ([clone] = true): the Pocket TTS model speaks in the voice of the
 *    reference recording [ref] (an asset path like "dub/voices/myira.wav", or an absolute
 *    file path). The voice of the recording is what you hear, in every language the
 *    model speaks. See [SherpaModels.CLONE_VOICES].
 */
data class PiperVoice(
    val id: String,
    val gender: Gender,
    val label: String,
    val sid: Int = 0,
    val kokoro: Boolean = false,
    val clone: Boolean = false,
    val ref: String = "",
)

/**
 * The offline neural voices (run through sherpa-onnx):
 *
 *  - Hindi men: two real Piper speakers (Rohan, Pratham). Piper is MUCH faster than
 *    the Kokoro pack that used to be here (a line is made in a fraction of the time it
 *    takes to say it).
 *  - Hindi women: one real Piper speaker (Priyamvada, fast) and two CLONED voices, Myira
 *    and Kyra. The cloned ones are made by the Pocket TTS multilingual model from the two
 *    reference recordings that ship inside the APK (assets/dub/voices/myira.wav and
 *    kyra.wav), so they sound like those recordings.
 *  - English: two Kokoro speakers (unchanged).
 *
 * Nothing big is inside the APK. The first time, [install] downloads what is missing
 * (about 60 MB per Piper voice, about 190 MB for the cloning model) and unpacks it
 * into the app's private folder:
 *
 *   dub_models/piper-hi/espeak-ng-data/          (shared by the Piper voices)
 *   dub_models/piper-hi/voices/<id>/model.onnx + tokens.txt
 *   dub_models/pocket-multi/lm_main.int8.onnx ... (the cloning model, see [CLONE_FILES])
 *
 * The first Piper voice (Rohan) is required; the second one is a bonus (if it cannot
 * be downloaded the dub simply has one actor fewer). The cloning model is also
 * downloaded, but if that fails the dub still works with the Piper men (see [isInstalledFor]).
 *
 * More languages later: the cloning model already speaks Marathi, Tamil, Telugu, Kannada
 * and Malayalam too. Put a language code into [CLONE_LANGS] to switch it on.
 */
object SherpaModels {

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val ESPEAK_URL = BASE + "espeak-ng-data.tar.bz2"

    /** The full text of the last download problem (null after a good run). The dub panel shows it. */
    @Volatile
    var lastError: String? = null
        private set

    // Size of the parts, roughly in MB. Only used to place the progress bar: a part that is
    // downloaded AND unpacked owns one slice of the bar, split into a download and an unpack
    // share, so the bar only ever moves forward (it used to restart at 0% for every phase).
    private const val V_DL = 60
    private const val V_UN = 30
    private const val ESPEAK_DL = 10
    private const val ESPEAK_UN = 5
    private const val CLONE_W = 200
    private const val KOKORO_DL = 130
    private const val KOKORO_UN = 70

    /** Turns (percent of the current phase, unpacking?) into percent of a download+unpack part. */
    private fun twoPhase(dl: Int, un: Int, p: Int, unpacking: Boolean): Int {
        val whole = (dl + un).coerceAtLeast(1)
        return if (!unpacking) p * dl / whole else (dl * 100 + p * un) / whole
    }

    /**
     * ONE progress bar for the whole job. Finished parts add up and the shown value never
     * goes back, whatever order the phases report in.
     */
    private class Overall(private val total: Int, private val out: (Int, Boolean) -> Unit) {
        private var done = 0
        private var shown = 0
        private var shownUnpacking = false

        @Synchronized
        private fun emit(units: Int, unpacking: Boolean) {
            val pct = (units * 100 / total.coerceAtLeast(1)).coerceIn(0, 100).coerceAtLeast(shown)
            if (pct != shown || unpacking != shownUnpacking || units == 0) {
                shown = pct
                shownUnpacking = unpacking
                out(pct, unpacking)
            }
        }

        /** Runs one part worth [weight]; [work] reports 0..100 of that part. */
        fun part(weight: Int, work: ((Int, Boolean) -> Unit) -> String?): String? {
            val err = work { pct, unpacking -> emit(done + weight * pct.coerceIn(0, 100) / 100, unpacking) }
            done += weight
            emit(done, false)
            return err
        }
    }

    /**
     * Hindi Piper voices: two men and one woman. The first one is required, the others are
     * bonuses (if one cannot be downloaded the dub simply has one actor fewer). Priyamvada is
     * listed first among the women, so she is the main (unnamed) woman's voice: she is fast and
     * never makes the dub fall behind, unlike the cloned women.
     */
    val VOICES: List<PiperVoice> = listOf(
        PiperVoice("vits-piper-hi_IN-rohan-medium", Gender.MALE, "Rohan"),
        PiperVoice("vits-piper-hi_IN-pratham-medium", Gender.MALE, "Pratham"),
        PiperVoice("vits-piper-hi_IN-priyamvada-medium", Gender.FEMALE, "Priyamvada"),
    )
    private const val REQUIRED = 1

    /** Voices that were shipped before and are gone for good (deleted from the phone when found). */
    private val REMOVED_VOICE_IDS: List<String> = emptyList()

    // ───────────────────────── Cloned voices (Pocket TTS) ─────────────────────────

    /**
     * Cloned actors. [PiperVoice.ref] is the recording whose voice is copied. To add a
     * voice: put a clean 24 kHz mono 16-bit WAV (6 to 12 seconds of one person talking)
     * into app/src/main/assets/dub/voices/ and add a line here.
     */
    val CLONE_VOICES: List<PiperVoice> = listOf(
        PiperVoice("clone-myira", Gender.FEMALE, "Myira", clone = true, ref = "dub/voices/myira.wav"),
        PiperVoice("clone-kyra", Gender.FEMALE, "Kyra", clone = true, ref = "dub/voices/kyra.wav"),
    )

    /**
     * Languages the cloned voices speak in the dub. The Pocket TTS multilingual model can do
     * hi, en, mr, ta, te, kn and ml. Only Hindi is switched on now; to add one, add its code
     * here (English keeps its own Kokoro voices).
     */
    val CLONE_LANGS: Set<String> = setOf("hi")

    /**
     * Where the cloning model is downloaded from. It is the open model
     * Thanushttk/pocket-tts-multilingual (Hindi, English, Marathi, Tamil, Telugu, Kannada,
     * Malayalam), converted to sherpa-onnx files by .github/workflows/clone-model.yml and kept in
     * the "clone-model" release of YOUR repository, so nobody else's upload can take it away.
     * Each folder is tried in order. The old Hugging Face copy (dhruvs99/...) answers 401
     * now and was removed on purpose.
     */
    private val CLONE_BASES: List<String> by lazy {
        val repos = ArrayList<String>()
        val built = runCatching { com.hikari.app.BuildConfig.BUILD_REPO }.getOrDefault("")
        if (built.isNotBlank()) repos.add(built)
        if (com.hikari.app.net.Updater.REPO !in repos) repos.add(com.hikari.app.net.Updater.REPO)
        repos.map { "https://github.com/$it/releases/download/clone-model/" }
    }

    /**
     * Smallest believable size (bytes) of each cloning file. A saved file below this is an
     * error page or a cut file, never the model, so it is thrown away instead of being kept.
     */
    private val CLONE_MIN_BYTES: Map<String, Long> = mapOf(
        "lm_main.int8.onnx" to 40_000_000L,
        "lm_flow.int8.onnx" to 5_000_000L,
        "encoder.onnx" to 15_000_000L,
        "decoder.int8.onnx" to 10_000_000L,
        "text_conditioner.onnx" to 8_000_000L,
        "vocab.json" to 1_000L,
        "token_scores.json" to 1_000L,
    )

    /** The files of the cloning model and their approximate size in MB (for the progress bar). */
    private val CLONE_FILES: List<Pair<String, Int>> = listOf(
        "lm_main.int8.onnx" to 76,
        "lm_flow.int8.onnx" to 10,
        "encoder.onnx" to 73,
        "decoder.int8.onnx" to 23,
        "text_conditioner.onnx" to 16,
        "vocab.json" to 1,
        "token_scores.json" to 1,
    )
    private const val CLONE_DIR = "pocket-multi"

    private fun cloneRoot(c: Context): File = File(File(c.filesDir, "dub_models"), CLONE_DIR)

    /** Folder of the cloning model on the phone. */
    fun cloneDir(c: Context): File = cloneRoot(c)

    fun isCloneReady(c: Context): Boolean {
        val d = cloneRoot(c)
        return File(d, ".ok").exists() && CLONE_FILES.all { File(d, it.first).length() > 0 }
    }

    /** The cloned voices that can be used right now for [lang]. */
    fun readyCloneVoices(c: Context, lang: String): List<PiperVoice> =
        if (lang.substringBefore('-').lowercase() in CLONE_LANGS && isCloneReady(c)) CLONE_VOICES else emptyList()

    /** True when the neural voices speak [lang] at all (the dub panel asks this). */
    fun supportsNeural(lang: String): Boolean {
        val b = lang.substringBefore('-').lowercase()
        return b == "hi" || b == "en" || b in CLONE_LANGS
    }

    /** Throws away voices that are not shipped any more (frees the space they used). */
    fun cleanupRemoved(c: Context) {
        runCatching {
            for (id in REMOVED_VOICE_IDS) {
                File(File(root(c), "voices"), id).deleteRecursively()
                File(File(root(c), "voices"), "$id.tmp").deleteRecursively()
                File(c.cacheDir, "$id.tar.bz2.part").delete()
                File(c.cacheDir, "$id.tar.bz2.done").delete()
            }
        }
    }

    // ───────────────────────── English voices (Kokoro) ─────────────────────────

    /** Kokoro v1.0, 8-bit (about 130 MB). One pack holds many speakers. */
    private const val KOKORO_URL = BASE + "kokoro-int8-multi-lang-v1_0.tar.bz2"
    private const val KOKORO_DIR = "kokoro-en-pack"

    /** English actors: one woman (af_heart = speaker 3) and one man (am_michael = speaker 16). */
    val KOKORO_VOICES: List<PiperVoice> = listOf(
        PiperVoice("kokoro-af_heart", Gender.FEMALE, "Heart", sid = 3, kokoro = true),
        PiperVoice("kokoro-am_michael", Gender.MALE, "Michael", sid = 16, kokoro = true),
    )

    private fun kokoroRoot(c: Context): File = File(File(c.filesDir, "dub_models"), KOKORO_DIR)

    fun kokoroDir(c: Context): File = kokoroRoot(c)

    fun kokoroEspeakDir(c: Context): File = File(kokoroRoot(c), "espeak-ng-data")

    private fun kokoroModelFile(c: Context): File {
        val int8 = File(kokoroRoot(c), "model.int8.onnx")
        return if (int8.length() > 0) int8 else File(kokoroRoot(c), "model.onnx")
    }

    fun isKokoroReady(c: Context): Boolean {
        val d = kokoroRoot(c)
        return File(d, ".ok").exists() &&
            kokoroModelFile(c).length() > 0 &&
            File(d, "voices.bin").length() > 0 &&
            File(d, "tokens.txt").length() > 0 &&
            File(d, "lexicon-us-en.txt").length() > 0 &&
            File(kokoroEspeakDir(c), "phontab").exists()
    }

    /**
     * The voices that speak [lang] and are on the phone:
     * Hindi = Piper men + cloned women, English = Kokoro.
     */
    fun readyVoicesFor(c: Context, lang: String): List<PiperVoice> {
        val b = lang.substringBefore('-').lowercase()
        return when {
            b == "hi" -> readyVoices(c) + readyCloneVoices(c, lang)
            b == "en" -> if (isKokoroReady(c)) KOKORO_VOICES else emptyList()
            else -> readyCloneVoices(c, lang)
        }
    }

    /**
     * True when there is something to dub [lang] with. For Hindi that is the Piper men OR
     * the cloned women, so a dub still works when only one of the two downloads finished.
     */
    fun isInstalledFor(c: Context, lang: String): Boolean {
        val b = lang.substringBefore('-').lowercase()
        return when {
            b == "hi" -> isInstalled(c) || isCloneReady(c)
            b == "en" -> isKokoroReady(c)
            b in CLONE_LANGS -> isCloneReady(c)
            else -> false
        }
    }

    /** True when EVERYTHING for [lang] is on the phone (nothing left to download). */
    fun isFullyInstalledFor(c: Context, lang: String): Boolean {
        val b = lang.substringBefore('-').lowercase()
        return when {
            // every Piper voice (men and Priyamvada) AND the cloning model
            b == "hi" -> isInstalled(c) && VOICES.all { isVoiceReady(c, it) } && isCloneReady(c)
            b == "en" -> isKokoroReady(c)
            b in CLONE_LANGS -> isCloneReady(c)
            else -> false
        }
    }

    private fun root(c: Context): File = File(File(c.filesDir, "dub_models"), "piper-hi")

    /** Folder with the espeak-ng data (shared by every voice). */
    fun espeakDir(c: Context): File = File(root(c), "espeak-ng-data")

    fun voiceDir(c: Context, v: PiperVoice): File = File(File(root(c), "voices"), v.id)

    private fun espeakReady(c: Context): Boolean =
        File(espeakDir(c), ".ok").exists() && File(espeakDir(c), "phontab").exists()

    fun isVoiceReady(c: Context, v: PiperVoice): Boolean {
        val d = voiceDir(c, v)
        return File(d, ".ok").exists() &&
            File(d, "model.onnx").length() > 0 &&
            File(d, "tokens.txt").length() > 0
    }

    /** The voices that are on the phone and usable right now. */
    fun readyVoices(c: Context): List<PiperVoice> = VOICES.filter { isVoiceReady(c, it) }

    fun isInstalled(c: Context): Boolean = espeakReady(c) && readyVoices(c).size >= REQUIRED

    fun delete(c: Context) {
        root(c).deleteRecursively()
        kokoroRoot(c).deleteRecursively()
        cloneRoot(c).deleteRecursively()
        File(File(c.filesDir, "dub_models"), "$CLONE_DIR.tmp").deleteRecursively()
    }

    /**
     * Downloads (resuming a half-finished download) and unpacks the English
     * voices (Kokoro, about 130 MB). Same contract as [install].
     */
    suspend fun installKokoro(
        c: Context,
        onProgress: (Int, Boolean) -> Unit,
        onStep: (String) -> Unit = {},
    ): String? =
        withContext(Dispatchers.IO) {
            if (isKokoroReady(c)) return@withContext null
            onStep("English voices")
            val base = kokoroRoot(c).parentFile ?: return@withContext "Could not use the phone storage."
            base.mkdirs()
            if (base.usableSpace < 500_000_000L) {
                return@withContext "Not enough free space (about 400 MB is needed)."
            }
            try {
                installKokoroPack(c) { p, u -> onProgress(twoPhase(KOKORO_DL, KOKORO_UN, p, u), u) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                "Voice download failed: " + describe(t)
            }
        }

    private fun CoroutineScope.installKokoroPack(c: Context, onProgress: (Int, Boolean) -> Unit): String? {
        val part = File(c.cacheDir, "dubk-en.tar.bz2.part")
        val marker = File(c.cacheDir, "dubk-en.tar.bz2.done")
        val finalDir = kokoroRoot(c)
        val staging = File(finalDir.parentFile, KOKORO_DIR + ".tmp")
        try {
            val whole = part.exists() && part.length() > 0 && marker.exists()
            if (!whole) {
                val err = download(KOKORO_URL, part, marker, onProgress)
                if (err != null) return err
            }
            staging.deleteRecursively()
            staging.mkdirs()
            // The pack has one top folder: drop it, keep everything under it as it is.
            unpack(part, onProgress) { parts ->
                val rel = if (parts.size > 1) parts.drop(1) else parts
                File(staging, rel.joinToString("/"))
            }
            val hasModel = File(staging, "model.int8.onnx").length() > 0 ||
                File(staging, "model.onnx").length() > 0
            if (!hasModel ||
                File(staging, "voices.bin").length() <= 0 ||
                File(staging, "tokens.txt").length() <= 0 ||
                File(staging, "lexicon-us-en.txt").length() <= 0 ||
                !File(File(staging, "espeak-ng-data"), "phontab").exists()
            ) {
                staging.deleteRecursively()
                part.delete(); marker.delete()
                return "The English voice pack was incomplete. Tap again to retry."
            }
            File(staging, ".ok").writeText("1")
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.deleteRecursively()
                return "Could not save the English voices."
            }
            part.delete(); marker.delete()
            return null
        } catch (ce: CancellationException) {
            staging.deleteRecursively()
            throw ce
        } catch (t: Throwable) {
            staging.deleteRecursively()
            val noSpace = t is OutOfMemoryError ||
                t.message.orEmpty().contains("space", ignoreCase = true) ||
                finalDir.parentFile?.usableSpace.let { it != null && it < 100_000_000L }
            if (!noSpace) { part.delete(); marker.delete() }
            return "Voice download failed: " + describe(t)
        }
    }

    /**
     * Downloads (resuming a half-finished download) and unpacks what is missing for Hindi:
     * the Piper men and the cloning model (the cloned women).
     * [onProgress] gets (percent 0..100 of the WHOLE job, unpacking?) from a
     * background thread. Returns null on success, otherwise a short message.
     *
     * The Piper men are required. If only the cloning model fails, the message is returned
     * but the men are already on the phone, so [isInstalledFor] is still true and the dub
     * can start without the cloned women.
     */
    suspend fun install(
        c: Context,
        onProgress: (Int, Boolean) -> Unit,
        onStep: (String) -> Unit = {},
    ): String? =
        withContext(Dispatchers.IO) {
            val base = root(c).apply { mkdirs() }
            // The first neural voice (Kokoro, 130-330 MB) is not used any more: free the space.
            runCatching {
                val models = File(c.filesDir, "dub_models")
                File(models, "kokoro-int8-multi-lang-v1_0").deleteRecursively()
                File(models, "kokoro-int8-multi-lang-v1_0.tmp").deleteRecursively()
                File(models, "kokoro-multi-lang-v1_0").deleteRecursively()
                c.cacheDir.listFiles()?.filter { it.name.startsWith("kokoro") }?.forEach { it.delete() }
            }
            cleanupRemoved(c)

            val needPiper = !isInstalled(c) || VOICES.any { !isVoiceReady(c, it) }
            val needClone = !isCloneReady(c)
            val needBytes = (if (needPiper) 300_000_000L else 0L) + (if (needClone) 350_000_000L else 0L)
            if (needBytes > 0L && base.usableSpace < needBytes) {
                return@withContext "Not enough free space (about " + (needBytes / 1_000_000L) + " MB is needed)."
            }

            val todo = VOICES.filter { !isVoiceReady(c, it) }
            // The voice packs carry the espeak data themselves; it is only fetched on its own
            // when every voice is there but the data is not.
            val needEspeakAlone = todo.isEmpty() && !espeakReady(c)
            val total = todo.size * (V_DL + V_UN) +
                (if (needEspeakAlone) ESPEAK_DL + ESPEAK_UN else 0) +
                (if (needClone) CLONE_W else 0)
            val bar = Overall(total.coerceAtLeast(1), onProgress)
            val steps = todo.size + (if (needEspeakAlone) 1 else 0) + (if (needClone) 1 else 0)
            var stepNo = 0

            try {
                for (v in todo) {
                    stepNo++
                    onStep("Step $stepNo of $steps: voice ${v.label}")
                    val required = VOICES.indexOf(v) < REQUIRED
                    val err = bar.part(V_DL + V_UN) { cb ->
                        installVoice(c, v) { p, u -> cb(twoPhase(V_DL, V_UN, p, u), u) }
                    }
                    if (err != null && required) return@withContext err
                    // a bonus voice that cannot be fetched is skipped
                }
                if (needEspeakAlone) {
                    stepNo++
                    onStep("Step $stepNo of $steps: language data")
                    val err = bar.part(ESPEAK_DL + ESPEAK_UN) { cb ->
                        installEspeak(c) { p, u -> cb(twoPhase(ESPEAK_DL, ESPEAK_UN, p, u), u) }
                    }
                    if (err != null) return@withContext err
                }
                if (!isInstalled(c)) return@withContext "The voice pack was incomplete. Tap again to retry."
                if (needClone) {
                    stepNo++
                    onStep("Step $stepNo of $steps: cloning model (Myira, Kyra)")
                    val err = bar.part(CLONE_W) { cb -> installClonePack(c, cb) }
                    if (err != null) return@withContext err
                }
                null
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                "Voice download failed: " + describe(t)
            }
        }

    /** Downloads whatever [lang] still needs (the dub panel calls this). Same contract as [install]. */
    suspend fun installFor(
        c: Context,
        lang: String,
        onProgress: (Int, Boolean) -> Unit,
        onStep: (String) -> Unit = {},
    ): String? {
        val err = when (lang.substringBefore('-').lowercase()) {
            "en" -> installKokoro(c, onProgress, onStep)
            "hi" -> install(c, onProgress, onStep)
            else -> installCloneOnly(c, onProgress, onStep)
        }
        lastError = err
        return err
    }

    /** Only the cloning model (for a language that has no Piper voices). Same contract as [install]. */
    suspend fun installCloneOnly(
        c: Context,
        onProgress: (Int, Boolean) -> Unit,
        onStep: (String) -> Unit = {},
    ): String? =
        withContext(Dispatchers.IO) {
            if (isCloneReady(c)) return@withContext null
            onStep("Cloning model")
            val base = cloneRoot(c).parentFile ?: return@withContext "Could not use the phone storage."
            base.mkdirs()
            if (base.usableSpace < 350_000_000L) {
                return@withContext "Not enough free space (about 350 MB is needed)."
            }
            try {
                installClonePack(c, onProgress)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                "Voice download failed: " + describe(t)
            }
        }

    // ───────────────────────── the cloning model ─────────────────────────

    /**
     * Downloads the cloning model (a handful of files, about 170 MB) into a staging folder
     * and moves it into place when every file is whole. A file that was finished earlier is
     * not fetched again and a half file is continued, so a cut connection costs nothing.
     */
    private fun CoroutineScope.installClonePack(c: Context, onProgress: (Int, Boolean) -> Unit): String? {
        val finalDir = cloneRoot(c)
        val staging = File(finalDir.parentFile, "$CLONE_DIR.tmp")
        try {
            staging.mkdirs()
            var totalMb = 0
            for (f in CLONE_FILES) totalMb += f.second
            totalMb = totalMb.coerceAtLeast(1)
            var doneMb = 0
            for ((name, mb) in CLONE_FILES) {
                val dest = File(staging, name)
                val finished = File(staging, "$name.finished")
                val minBytes = CLONE_MIN_BYTES[name] ?: 1L
                if (dest.length() >= minBytes && finished.exists()) {
                    doneMb += mb
                    onProgress(doneMb * 100 / totalMb, false)
                    continue
                }
                // a file marked finished that is too small is junk: start it again
                dest.delete(); finished.delete()
                val part = File(staging, "$name.part")
                val marker = File(staging, "$name.part.whole")
                val problems = ArrayList<String>()
                var ok = false
                for (b in CLONE_BASES) {
                    val err = try {
                        download(b + name, part, marker) { p, _ ->
                            onProgress((doneMb + mb * p / 100) * 100 / totalMb, false)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        "Voice download failed: " + describe(t)
                    }
                    if (err == null) {
                        // the file must really be the model, not an error page or a cut file
                        if (part.length() >= minBytes) { ok = true; break }
                        problems.add(hostOf(b) + ": the file was too small (" + part.length() + " bytes)")
                        part.delete(); marker.delete()
                    } else {
                        problems.add(hostOf(b) + ": " + err)
                    }
                }
                if (!ok) {
                    return "Could not download $name. " + problems.joinToString(" | ") +
                        " | The cloning model is kept in the clone-model release of the app's GitHub repository " +
                        "(it must be public). If it is not there yet, run the \"Build clone model\" workflow once."
                }
                dest.delete()
                if (!part.renameTo(dest)) return "Could not save the voice file $name."
                marker.delete()
                finished.writeText("1")
                doneMb += mb
                onProgress(doneMb * 100 / totalMb, false)
            }
            for ((name, _) in CLONE_FILES) {
                val f = File(staging, name)
                if (f.length() < (CLONE_MIN_BYTES[name] ?: 1L)) {
                    // something is wrong with what was saved: start clean next time
                    staging.deleteRecursively()
                    return "The cloning model was incomplete. Tap again to retry."
                }
            }
            staging.listFiles()?.forEach {
                if (it.name.endsWith(".finished") || it.name.endsWith(".part") || it.name.endsWith(".whole")) it.delete()
            }
            File(staging, ".ok").writeText("1")
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.deleteRecursively()
                return "Could not save the cloning model."
            }
            return null
        } catch (ce: CancellationException) {
            throw ce      // the half-finished files stay, the next try continues
        } catch (t: Throwable) {
            return "Voice download failed: " + describe(t)
        }
    }

    // ───────────────────────── one voice ─────────────────────────

    private fun CoroutineScope.installVoice(
        c: Context,
        v: PiperVoice,
        onProgress: (Int, Boolean) -> Unit,
    ): String? {
        val part = File(c.cacheDir, v.id + ".tar.bz2.part")
        val marker = File(c.cacheDir, v.id + ".tar.bz2.done")
        val staging = File(File(root(c), "voices"), v.id + ".tmp")
        val espeakStaging = File(root(c), "espeak-ng-data.tmp")
        try {
            val whole = part.exists() && part.length() > 0 && marker.exists()
            if (!whole) {
                val err = download(BASE + v.id + ".tar.bz2", part, marker, onProgress)
                if (err != null) return err
            }
            val needEspeak = !espeakReady(c)
            staging.deleteRecursively()
            staging.mkdirs()
            if (needEspeak) {
                espeakStaging.deleteRecursively()
                espeakStaging.mkdirs()
            }
            var haveEspeakFiles = false
            unpack(part, onProgress) { parts ->
                val i = parts.indexOf("espeak-ng-data")
                when {
                    i >= 0 -> {
                        if (!needEspeak || i == parts.size - 1) null
                        else {
                            haveEspeakFiles = true
                            File(espeakStaging, parts.drop(i + 1).joinToString("/"))
                        }
                    }
                    parts.last().endsWith(".onnx") -> File(staging, "model.onnx")
                    parts.last() == "tokens.txt" -> File(staging, "tokens.txt")
                    else -> null
                }
            }
            if (File(staging, "model.onnx").length() <= 0 || File(staging, "tokens.txt").length() <= 0) {
                staging.deleteRecursively()
                espeakStaging.deleteRecursively()
                part.delete(); marker.delete()
                return "The voice ${v.label} was incomplete. Tap again to retry."
            }
            if (needEspeak && haveEspeakFiles && File(espeakStaging, "phontab").exists()) {
                File(espeakStaging, ".ok").writeText("1")
                val finalEspeak = espeakDir(c)
                finalEspeak.deleteRecursively()
                if (!espeakStaging.renameTo(finalEspeak)) espeakStaging.deleteRecursively()
            } else {
                espeakStaging.deleteRecursively()
            }
            File(staging, ".ok").writeText("1")
            val finalDir = voiceDir(c, v)
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.deleteRecursively()
                return "Could not save the voice ${v.label}."
            }
            part.delete(); marker.delete()
            return null
        } catch (ce: CancellationException) {
            staging.deleteRecursively()
            espeakStaging.deleteRecursively()
            throw ce
        } catch (t: Throwable) {
            staging.deleteRecursively()
            espeakStaging.deleteRecursively()
            val noSpace = t is OutOfMemoryError ||
                t.message.orEmpty().contains("space", ignoreCase = true) ||
                root(c).usableSpace < 100_000_000L
            if (!noSpace) { part.delete(); marker.delete() }
            return "Voice download failed: " + describe(t)
        }
    }

    /** Only needed when the voice packs did not carry the espeak data themselves. */
    private fun CoroutineScope.installEspeak(c: Context, onProgress: (Int, Boolean) -> Unit): String? {
        val part = File(c.cacheDir, "espeak-ng-data.tar.bz2.part")
        val marker = File(c.cacheDir, "espeak-ng-data.tar.bz2.done")
        val staging = File(root(c), "espeak-ng-data.tmp")
        try {
            if (!(part.exists() && part.length() > 0 && marker.exists())) {
                val err = download(ESPEAK_URL, part, marker, onProgress)
                if (err != null) return err
            }
            staging.deleteRecursively()
            staging.mkdirs()
            unpack(part, onProgress) { parts ->
                val i = parts.indexOf("espeak-ng-data")
                val rel = if (i >= 0) parts.drop(i + 1) else parts
                if (rel.isEmpty()) null else File(staging, rel.joinToString("/"))
            }
            if (!File(staging, "phontab").exists()) {
                staging.deleteRecursively(); part.delete(); marker.delete()
                return "The language data was incomplete. Tap again to retry."
            }
            File(staging, ".ok").writeText("1")
            val finalDir = espeakDir(c)
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.deleteRecursively()
                return "Could not save the language data."
            }
            part.delete(); marker.delete()
            return null
        } catch (ce: CancellationException) {
            staging.deleteRecursively()
            throw ce
        } catch (t: Throwable) {
            staging.deleteRecursively()
            part.delete(); marker.delete()
            return "Voice download failed: " + describe(t)
        }
    }

    // ───────────────────────── download / unpack ─────────────────────────

    /** One client for every voice download: the app's own DNS (with the DNS-over-HTTPS fallback). */
    private val voiceNet: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(DohDns)
            .build()
    }

    private const val TRIES = 4

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

    /** A short, readable reason for the person (and for me when they send it back). */
    private fun describe(t: Throwable): String {
        val name = t.javaClass.simpleName
        val msg = t.message?.takeIf { it.isNotBlank() }
        return if (msg != null && !msg.startsWith(name)) "$name: $msg" else name
    }

    private fun CoroutineScope.sleepAlive(ms: Long) {
        var left = ms
        while (left > 0) {
            ensureActive()
            val step = minOf(left, 200L)
            Thread.sleep(step)
            left -= step
        }
    }

    /**
     * Downloads [url] into [part], resuming when a part is there, and tries again (continuing
     * from what is already saved) when the connection drops or times out.
     * Returns null when the whole file is on the phone (and writes [marker]),
     * otherwise a short message for the person that says what went wrong.
     */
    private fun CoroutineScope.download(
        url: String,
        part: File,
        marker: File,
        onProgress: (Int, Boolean) -> Unit,
    ): String? {
        var last = "Download failed."
        for (attempt in 1..TRIES) {
            ensureActive()
            try {
                return downloadOnce(url, part, marker, onProgress)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: IOException) {
                last = "Network problem (" + describe(e) + ")"
                if (attempt < TRIES) sleepAlive(1500L * attempt)
            }
        }
        return "$last after $TRIES tries. What is saved is kept, tap again to continue."
    }

    /**
     * One try. Returns null (whole file saved) or a message for a failure that is no use
     * retrying (the file does not exist, a web page came back...). Throws [IOException]
     * for a failure that is worth another try (dropped connection, timeout, server busy).
     */
    private fun CoroutineScope.downloadOnce(
        url: String,
        part: File,
        marker: File,
        onProgress: (Int, Boolean) -> Unit,
    ): String? {
        marker.delete()
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Hikari")
            // no gzip: with a Range request the sizes stop matching and the file gets damaged
            .header("Accept-Encoding", "identity")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        return voiceNet.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code == 416) {
                // "Range not satisfiable": the saved file is already whole (or damaged).
                val serverTotal = resp.header("Content-Range")
                    ?.substringAfter('/', "")?.trim()?.toLongOrNull()
                if (serverTotal == null || serverTotal == have) {
                    marker.writeText("1")
                    return@use null
                }
                part.delete()
                throw IOException("the saved part did not fit, starting that file again")
            }
            if (code == 429 || code >= 500) throw IOException("server answered $code")
            if (code != 200 && code != 206) {
                return@use "Download failed (" + hostOf(url) + " answered $code for " + url.substringAfterLast('/') + ")."
            }
            val type = resp.header("Content-Type").orEmpty()
            if (type.startsWith("text/html", ignoreCase = true)) {
                return@use "Download blocked (" + hostOf(url) + " sent a web page instead of the file)."
            }
            val body = resp.body
            val append = code == 206 && have > 0
            val len = body.contentLength()
            val total = if (len > 0) len + (if (append) have else 0L) else -1L
            var received = if (append) have else 0L
            var lastPct = -1
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        if (total > 0) {
                            val p = (received * 100 / total).toInt().coerceIn(0, 100)
                            if (p != lastPct) {
                                lastPct = p
                                onProgress(p, false)
                            }
                        }
                    }
                }
            }
            if (total > 0 && part.length() < total) throw IOException("the download was cut off")
            marker.writeText("1")
            null
        }
    }

    /**
     * Unpacks a .tar.bz2. [target] gets the entry's path split into parts
     * (e.g. ["vits-piper-x", "tokens.txt"]) and returns where to write it, or
     * null to skip the entry. Nothing is ever written outside the folder that
     * [target]'s files live in.
     */
    private fun CoroutineScope.unpack(
        part: File,
        onProgress: (Int, Boolean) -> Unit,
        target: (List<String>) -> File?,
    ) {
        val size = part.length().coerceAtLeast(1L)
        var lastUnpack = -1
        var lastTick = 0L
        val buf = ByteArray(128 * 1024)
        FileInputStream(part).use { fis ->
            val channel = fis.channel
            fun report() {
                val p = (channel.position() * 100 / size).toInt().coerceIn(0, 100)
                if (p != lastUnpack) {
                    lastUnpack = p
                    onProgress(p, true)
                }
            }
            BZip2CompressorInputStream(BufferedInputStream(fis, 256 * 1024)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        ensureActive()
                        val e = tar.nextEntry ?: break
                        if (e.isDirectory) continue
                        val parts = e.name.replace('\\', '/').split('/')
                            .filter { it.isNotEmpty() && it != "." }
                        if (parts.isEmpty() || parts.any { it == ".." }) continue
                        val dest = target(parts) ?: continue
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            while (true) {
                                ensureActive()
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                val now = System.nanoTime()
                                if (now - lastTick > 300_000_000L) {
                                    lastTick = now
                                    report()
                                }
                            }
                        }
                        report()
                    }
                }
            }
        }
        onProgress(100, true)
    }
}
