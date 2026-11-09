package com.hikari.app.dub

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.hikari.app.data.Translator
import com.hikari.app.i18n.I18n

/** Remembers the last dub choices. */
object DubPrefs {
    private fun p(c: Context) = c.getSharedPreferences("dub_prefs", Context.MODE_PRIVATE)
    fun lang(c: Context): String = p(c).getString("lang", "en") ?: "en"
    fun mode(c: Context): VoiceMode =
        runCatching { VoiceMode.valueOf(p(c).getString("mode", "AUTO")!!) }.getOrDefault(VoiceMode.AUTO)
    /** How much of the original is left while the dub speaks. Silent (0) by default:
     *  the original dialogue is taken out, the music and effects stay. The key is
     *  "duck2" so the old default (Quiet) does not carry over. */
    fun duck(c: Context): Float = p(c).getFloat("duck2", 0f)
    /** Own voice for every character + edited audio (on by default). */
    fun smart(c: Context): Boolean = p(c).getBoolean("smart", true)
    fun saveSmart(c: Context, v: Boolean) { p(c).edit().putBoolean("smart", v).apply() }
    /** Listen to the ORIGINAL voices first, to tell men from women and to find the characters (on by default). */
    fun scan(c: Context): Boolean = p(c).getBoolean("scan", true)
    fun saveScan(c: Context, v: Boolean) { p(c).edit().putBoolean("scan", v).apply() }
    /**
     * Fast dub (on by default): every character is spoken by the quick Piper voices. Off = the two
     * slower cloned women's voices (Myira, Kyra) are used as well when the phone can keep up.
     */
    fun fast(c: Context): Boolean = p(c).getBoolean("fast", true)
    fun saveFast(c: Context, v: Boolean) { p(c).edit().putBoolean("fast", v).apply() }
    fun save(c: Context, lang: String, mode: VoiceMode, duck: Float) {
        p(c).edit().putString("lang", lang).putString("mode", mode.name).putFloat("duck2", duck).apply()
    }
}

/**
 * What the dub panel will read: the subtitle that is switched on in the player.
 *
 *  - [label]      name shown to the person ("English · Full")
 *  - [raw]        the subtitle text when it is available (exact timing),
 *                 or null when it lives inside the video
 *  - [live]       true when the lines are read as the captions appear
 *  - [imageBased] true for picture subtitles (PGS / DVB), which cannot be read
 */
data class DubPlan(
    val label: String,
    val raw: String?,
    val live: Boolean,
    val imageBased: Boolean = false,
)

/**
 * The "Dub audio" panel: shows which subtitle will be read, and lets the
 * person pick the language and the voice. [plan] is null when subtitles are
 * off; [onUpload] opens the file picker so the person can add their own
 * subtitle file; [onStop] stops a running dub.
 */
object DubDialog {

    fun show(
        activity: Activity,
        engine: DubEngine,
        plan: DubPlan?,
        onUpload: () -> Unit,
        onStop: () -> Unit,
        onStart: (lang: String, mode: VoiceMode, duck: Float, multi: Boolean) -> Unit,
        onGenerate: (() -> Unit)? = null,
    ) {
        val d = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * d).toInt()

        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 12f
            setPadding(0, pad(12), 0, pad(2))
        }
        fun note(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setPadding(0, pad(8), 0, pad(8))
        }
        fun spinner(items: List<String>, selected: Int) = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(8), pad(20), 0)
        }

        // 1) which subtitle is read
        val canStart = plan != null && !plan.imageBased
        when {
            plan == null -> root.addView(
                note(I18n.t("No subtitle is switched on. Turn on a subtitle in the player, or upload a subtitle file (.srt / .vtt), and it will be read out in the language you choose."))
            )
            plan.imageBased -> root.addView(
                note(I18n.t("This subtitle is made of pictures, so it cannot be read out. Pick a text subtitle, or upload a subtitle file (.srt / .vtt).") + "\n\n" + plan.label)
            )
            else -> {
                root.addView(label(I18n.t("Read this subtitle")))
                root.addView(note(plan.label))
                if (plan.raw == null) {
                    root.addView(note(I18n.t("This subtitle is inside the video, so each line is read when it appears on screen.")))
                }
            }
        }

        // No usable subtitle: the voice of the video can write one.
        if (!canStart && onGenerate != null) {
            root.addView(
                note(I18n.t("No subtitle for this video? Tap \"Make subtitle from voice\": the app listens to the sound of the video and writes the subtitle, then you can dub it."))
            )
        }

        // Which characters the subtitle names (only when its text is available).
        val castInfo = TextView(activity).apply {
            textSize = 12f
            setPadding(0, pad(6), 0, 0)
        }
        val rawText = plan?.raw
        if (canStart && rawText != null) {
            castInfo.text = runCatching {
                val cues = SubtitleCues.parse(rawText)
                val desc = CastDirector.assign(cues).describe()
                if (desc.isBlank()) {
                    I18n.t("No character names found in this subtitle. Each new speaker turn gets a man or a woman voice at random; two-person exchanges (- Hi / - Hello) always get one of each.")
                } else {
                    I18n.t("Characters found") + ": " + desc
                }
            }.getOrDefault("")
            root.addView(castInfo)
        }

        // The neural voice stopped last time: say why (so it can be fixed).
        engine.neuralReport()?.let { r ->
            root.addView(
                note(I18n.t("Last neural voice problem") + ": " + r)
            )
        }

        // 2) language
        val langs = Translator.LANGUAGES
        root.addView(label(I18n.t("Dub language")))
        val langSpinner = spinner(
            langs.map { it.second },
            langs.indexOfFirst { it.first == DubPrefs.lang(activity) }.coerceAtLeast(0),
        )
        root.addView(langSpinner)

        // 3) voice
        val voiceNames = listOf(
            I18n.t("Automatic (Google, then phone voice)"),
            I18n.t("Google Translate voice (online)"),
            I18n.t("Phone voice (offline)"),
            I18n.t("Neural voice - offline (Hindi: 2 cloned women + 2 men, English: 2 voices)"),
        )
        root.addView(label(I18n.t("Voice")))
        val voiceSpinner = spinner(voiceNames, DubPrefs.mode(activity).ordinal)
        root.addView(voiceSpinner)

        // The neural voices speak Hindi (and English) only. Choosing the neural voice while
        // another language is selected puts Hindi there, so nobody starts a dub that cannot
        // use the voice they just picked. (Also runs once when the panel opens with the neural
        // voice remembered from last time.)
        val hindiPos = langs.indexOfFirst { it.first == "hi" }
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = VoiceMode.values().getOrNull(position) ?: return
                if (mode != VoiceMode.NEURAL || hindiPos < 0) return
                val cur = langs.getOrNull(langSpinner.selectedItemPosition)?.first ?: return
                if (!SherpaModels.supportsNeural(cur)) {
                    langSpinner.setSelection(hindiPos)
                    Toast.makeText(
                        activity,
                        I18n.t("The neural voice speaks Hindi and English, so Hindi is selected."),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 4) original sound while the dub speaks
        val duckValues = listOf(0f, 0.15f, 0.35f)
        val duckNames = listOf(I18n.t("Silent"), I18n.t("Quiet"), I18n.t("Medium"))
        root.addView(label(I18n.t("Original sound while dub speaks")))
        val duckSpinner = spinner(
            duckNames,
            duckValues.indexOfFirst { it == DubPrefs.duck(activity) }.takeIf { it >= 0 } ?: 1,
        )
        root.addView(duckSpinner)

        // 5) own voice per character + audio polish
        val smartBox = CheckBox(activity).apply {
            text = I18n.t("Different voice for each character (auto)")
            isChecked = DubPrefs.smart(activity)
            setPadding(0, pad(12), 0, 0)
        }
        root.addView(smartBox)

        // 5b) fast dub: only the quick voices (the cloned women are slow)
        val fastBox = CheckBox(activity).apply {
            text = I18n.t("Fast dub (recommended): use the quick voices for every character. Turn off to also use the 2 slower cloned women's voices.")
            isChecked = DubPrefs.fast(activity)
            setPadding(0, pad(8), 0, 0)
        }
        root.addView(fastBox)

        // 6) listen to the original voices (man / woman and who is who)
        val scanBox = CheckBox(activity).apply {
            text = I18n.t("Listen to the original voices first (tells men from women; downloads a ~40 MB model once)")
            isChecked = DubPrefs.scan(activity)
            setPadding(0, pad(8), 0, 0)
        }
        root.addView(scanBox)

        val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(I18n.t("Dub audio"))
            // The panel is taller than a small (landscape) dialog: without a
            // scroller the lower half (voice options, ducking, buttons' top
            // part) is cut off with no way to reach it. The dialog scrolls now.
            .setView(ScrollView(activity).apply {
                addView(root, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            })
            .setNegativeButton(I18n.t("Close"), null)
            .setNeutralButton(I18n.t("Upload subtitle…")) { _, _ -> onUpload() }

        if (engine.active) {
            builder.setPositiveButton(I18n.t("Stop dubbing")) { _, _ -> onStop() }
        } else if (canStart) {
            builder.setPositiveButton(I18n.t("Start dubbing")) { _, _ ->
                val lang = langs[langSpinner.selectedItemPosition].first
                val mode = VoiceMode.values()[voiceSpinner.selectedItemPosition]
                val duck = duckValues[duckSpinner.selectedItemPosition]
                val multi = smartBox.isChecked
                DubPrefs.save(activity, lang, mode, duck)
                DubPrefs.saveSmart(activity, multi)
                DubPrefs.saveScan(activity, scanBox.isChecked)
                DubPrefs.saveFast(activity, fastBox.isChecked)
                when {
                    mode != VoiceMode.NEURAL -> onStart(lang, mode, duck, multi)
                    !SherpaModels.supportsNeural(lang) -> {
                        // The neural voices speak Hindi and English for now.
                        Toast.makeText(
                            activity,
                            I18n.t("The neural voice is Hindi and English only for now, so the normal voice is used."),
                            Toast.LENGTH_LONG,
                        ).show()
                        onStart(lang, VoiceMode.AUTO, duck, multi)
                    }
                    SherpaModels.isFullyInstalledFor(activity, lang) -> onStart(lang, mode, duck, multi)
                    // Fast dub does not use the cloned women, so their big model need not be downloaded
                    fastBox.isChecked && SherpaModels.isFastReadyFor(activity, lang) -> onStart(lang, mode, duck, multi)
                    else -> NeuralDownload.show(activity, lang) { ok ->
                        if (ok) onStart(lang, mode, duck, multi)
                    }
                }
            }
        } else if (onGenerate != null) {
            builder.setPositiveButton(I18n.t("Make subtitle from voice")) { _, _ -> onGenerate.invoke() }
        }
        builder.show()
    }
}

/**
 * First use of the neural voice: downloads what is missing once (Hindi: the two men and the
 * cloning model for the cloned women, about 360 MB; English: two voices, about 130 MB,
 * from GitHub / Hugging Face) and shows the progress. [onDone] gets true when the dub can
 * start (everything is ready, or at least the part that matters is: see below).
 */
internal object NeuralDownload {

    fun show(activity: Activity, lang: String, onDone: (Boolean) -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * d).toInt()
        val base = lang.substringBefore('-').lowercase()
        val english = base == "en"
        val hindi = base == "hi"

        val info = TextView(activity).apply {
            textSize = 13f
            text = when {
                english -> I18n.t("Downloading the English voices once (2 voices, about 130 MB). Keep this screen open and stay on Wi-Fi.")
                hindi -> I18n.t("Downloading the Hindi voices once (2 men + Priyamvada + the cloning model for the 2 cloned women, about 360 MB). Keep this screen open and stay on Wi-Fi.")
                else -> I18n.t("Downloading the cloning model once (about 170 MB). Keep this screen open and stay on Wi-Fi.")
            }
        }
        // "Downloading… 37%" / "Unpacking… 52%": the bar is one bar for the whole job and only goes forward
        val status = TextView(activity).apply {
            textSize = 13f
            setPadding(0, pad(10), 0, 0)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            setPadding(0, pad(12), 0, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(12), pad(20), pad(4))
            addView(info)
            addView(status)
            addView(bar)
        }

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: MainScope()
        var job: Job? = null
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(
                when {
                    english -> I18n.t("Neural voice (English)")
                    hindi -> I18n.t("Neural voice (Hindi)")
                    else -> I18n.t("Neural voice")
                }
            )
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(I18n.t("Cancel")) { _, _ -> job?.cancel() }
            .create()
        dlg.show()
        // keep the screen on while the voice pack downloads / unpacks, otherwise the phone
        // sleeps and the work stops
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val running = scope.launch {
            val progress: (Int, Boolean) -> Unit = { pct, unpacking ->
                activity.runOnUiThread {
                    bar.progress = pct
                    status.text = (if (unpacking) I18n.t("Unpacking") else I18n.t("Downloading")) + "… " + pct + "%"
                }
            }
            val step: (String) -> Unit = { text ->
                activity.runOnUiThread {
                    info.text = text + "\n" + I18n.t("Keep this screen open and stay on Wi-Fi.")
                }
            }
            val err = SherpaModels.installFor(activity.applicationContext, lang, progress, step)
            runCatching { dlg.dismiss() }

            // The whole error text, in a box that stays until the person closes it
            // (a toast cuts it short, so the real reason was never visible).
            fun problem(title: String, text: String, canDub: Boolean) {
                if (activity.isFinishing) { onDone(canDub); return }
                val b = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage(text)
                    .setCancelable(false)
                    // the click is set again below, so Copy does not close the box
                    .setNeutralButton(I18n.t("Copy"), null)
                    .setNegativeButton(I18n.t("Try again")) { _, _ -> show(activity, lang, onDone) }
                if (canDub) b.setPositiveButton(I18n.t("Dub with the ready voices")) { _, _ -> onDone(true) }
                else b.setPositiveButton(I18n.t("Close")) { _, _ -> onDone(false) }
                val box2 = b.create()
                box2.show()
                box2.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                    runCatching {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("voice download", text))
                        Toast.makeText(activity, I18n.t("Copied"), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            when {
                err == null -> {
                    Toast.makeText(activity, I18n.t("Neural voice is ready."), Toast.LENGTH_SHORT).show()
                    onDone(true)
                }
                // Part of it failed (for Hindi: the cloned women could not be fetched) but
                // there are neural voices on the phone: dub with those, and say what happened.
                SherpaModels.isInstalledFor(activity.applicationContext, lang) -> problem(
                    I18n.t("Some voices could not be downloaded, so the dub uses the voices that are ready."),
                    err.orEmpty(),
                    true,
                )
                else -> problem(I18n.t("The voice download did not finish"), err.orEmpty(), false)
            }
        }
        job = running
        running.invokeOnCompletion { runCatching { activity.runOnUiThread { runCatching { dlg.dismiss() } } } }
    }
}
