package com.hikari.app.dub

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hikari.app.i18n.I18n
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Remembers the last "make subtitle from voice" choices. */
object SubtitleGenPrefs {
    private fun p(c: Context) = c.getSharedPreferences("dub_prefs", Context.MODE_PRIVATE)

    fun size(c: Context): WhisperSize =
        runCatching { WhisperSize.valueOf(p(c).getString("gen_size", "TINY")!!) }.getOrDefault(WhisperSize.TINY)

    /** Spoken language code, "" = find out by listening. */
    fun lang(c: Context): String = p(c).getString("gen_lang", "") ?: ""

    fun save(c: Context, size: WhisperSize, lang: String) {
        p(c).edit().putString("gen_size", size.name).putString("gen_lang", lang).apply()
    }
}

/**
 * "Make subtitle from voice": the person picks how good the speech-to-text
 * should be and which language is spoken (or lets it find out), the speech
 * model is downloaded once when it is not on the phone yet, and then
 * [onStart] is called so the player can listen to the video.
 */
object SubtitleGenUi {

    /** Languages Whisper understands. "" = listen and decide. */
    private val LANGS: List<Pair<String, String>> = listOf(
        "" to "Auto-detect",
        "en" to "English",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "hi" to "Hindi",
        "bn" to "Bengali",
        "ur" to "Urdu",
        "ta" to "Tamil",
        "te" to "Telugu",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "tr" to "Turkish",
        "ar" to "Arabic",
        "id" to "Indonesian",
        "th" to "Thai",
        "vi" to "Vietnamese",
    )

    fun show(activity: Activity, onStart: (WhisperSize, String) -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * d).toInt()

        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 12f
            setPadding(0, pad(12), 0, pad(2))
        }
        fun spinner(items: List<String>, selected: Int) = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(8), pad(20), 0)
        }
        root.addView(
            TextView(activity).apply {
                textSize = 13f
                setPadding(0, pad(8), 0, pad(8))
                text = I18n.t("The app listens to the sound of this video and writes the subtitle for you. It then appears in the subtitle list, and Dub audio can use it. A whole episode takes several minutes; the video keeps playing.")
            }
        )

        val sizes = WhisperSize.values().toList()
        val sizeNames = listOf(
            I18n.t("Fast (smaller model)"),
            I18n.t("Better (slower, more exact)"),
        )
        root.addView(label(I18n.t("Quality")))
        val sizeSpinner = spinner(sizeNames, sizes.indexOf(SubtitleGenPrefs.size(activity)))
        root.addView(sizeSpinner)

        root.addView(label(I18n.t("Spoken language in the video")))
        val langSpinner = spinner(
            LANGS.map { I18n.t(it.second) },
            LANGS.indexOfFirst { it.first == SubtitleGenPrefs.lang(activity) }.coerceAtLeast(0),
        )
        root.addView(langSpinner)

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(I18n.t("Make subtitle from voice"))
            .setView(root)
            .setNegativeButton(I18n.t("Close"), null)
            .setPositiveButton(I18n.t("Make subtitle")) { _, _ ->
                val size = sizes[sizeSpinner.selectedItemPosition.coerceIn(0, sizes.size - 1)]
                val lang = LANGS[langSpinner.selectedItemPosition.coerceIn(0, LANGS.size - 1)].first
                SubtitleGenPrefs.save(activity, size, lang)
                if (SubtitleGenModels.isReady(activity, size)) {
                    onStart(size, lang)
                } else {
                    download(activity, size) { ok -> if (ok) onStart(size, lang) }
                }
            }
            .show()
    }

    /** First use: downloads the speech models once and shows the progress. [onDone] gets true when they are ready. */
    private fun download(activity: Activity, size: WhisperSize, onDone: (Boolean) -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * d).toInt()

        val status = TextView(activity).apply {
            textSize = 13f
            text = I18n.t("Downloading the speech model once. Keep this screen open and stay on Wi-Fi.")
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            setPadding(0, pad(12), 0, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(12), pad(20), pad(4))
            addView(status)
            addView(bar)
        }

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: MainScope()
        var job: Job? = null
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(I18n.t("Speech model"))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(I18n.t("Cancel")) { _, _ -> job?.cancel() }
            .create()
        dlg.show()
        // keep the screen on while the model downloads / unpacks, otherwise the phone sleeps and the work stops
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val running = scope.launch {
            val progress: (Int, Boolean) -> Unit = { pct, unpacking ->
                activity.runOnUiThread {
                    bar.progress = pct
                    status.text = (if (unpacking) I18n.t("Unpacking") else I18n.t("Downloading")) + "… " + pct + "%"
                }
            }
            val err = SubtitleGenModels.install(activity.applicationContext, size, progress)
            runCatching { dlg.dismiss() }
            if (err == null) {
                Toast.makeText(activity, I18n.t("Speech model is ready."), Toast.LENGTH_SHORT).show()
                onDone(true)
            } else {
                Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                onDone(false)
            }
        }
        job = running
        running.invokeOnCompletion { runCatching { activity.runOnUiThread { runCatching { dlg.dismiss() } } } }
    }
}
