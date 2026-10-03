package com.hikari.app.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * The app-wide font (Settings → Appearance & Theme → App font).
 *
 * Two halves, because Hikari is two apps in one:
 *
 *  * the Compose half takes the chosen [FontFamily] through the theme's
 *    typography — see [com.hikari.app.ui.theme.typographyWith] — and that
 *    repaints every screen at once;
 *  * the View-based half (the player, its dialogs, the WebView) is outside
 *    that tree, so it is repainted by [applyToViewTree], which walks the
 *    inflated hierarchy and re-types every TextView.
 *
 * The choice is mirrored into plain SharedPreferences for the same reason
 * [UiScale] and [AccentStore] are: the player must be able to read it
 * SYNCHRONOUSLY while its Activity is being created, before DataStore has
 * emitted anything.
 *
 * Besides the platform's own families the user can import a font file off the
 * device — it is copied into `filesDir/fonts` so the choice survives the
 * document-picker permission going away, and so the player can read it too.
 */
object AppFonts {

    const val DEFAULT = "system"
    const val IMPORTED = "imported"

    /**
     * One pickable font. [family] is the platform family name handed to
     * `Typeface.create` — the built-in Android families (which are Noto on most
     * devices and Roboto on some, hence the shared label), plus the classic
     * serif/monospace/casual/cursive faces.
     */
    data class Choice(val key: String, val label: String, val family: String)

    val CHOICES: List<Choice> = listOf(
        Choice(DEFAULT, "System default", ""),
        Choice("sans", "Sans (Roboto / Noto)", "sans-serif"),
        Choice("sans-light", "Sans Light", "sans-serif-light"),
        Choice("sans-medium", "Sans Medium", "sans-serif-medium"),
        Choice("sans-black", "Sans Black", "sans-serif-black"),
        Choice("condensed", "Sans Condensed", "sans-serif-condensed"),
        Choice("condensed-light", "Sans Condensed Light", "sans-serif-condensed-light"),
        Choice("serif", "Serif (Noto Serif)", "serif"),
        Choice("mono", "Monospace", "monospace"),
        Choice("casual", "Casual", "casual"),
        Choice("cursive", "Cursive", "cursive"),
        Choice("smallcaps", "Small Caps", "sans-serif-smallcaps"),
    )

    fun byKey(key: String?): Choice = CHOICES.firstOrNull { it.key == key } ?: CHOICES.first()

    /** The label to show for the saved choice (the imported font's own name
     *  when [IMPORTED] is the choice). */
    fun labelFor(key: String?, importedLabel: String): String =
        if (key == IMPORTED) {
            importedLabel.ifBlank { "Imported font" }
        } else {
            byKey(key).label
        }

    // ---- Synchronous mirror ----

    private const val PREFS = "hikari_app_font"
    private const val KEY_FONT = "font"
    private const val KEY_FILE = "file"

    @Volatile private var loaded = false
    @Volatile private var key: String = DEFAULT
    @Volatile private var file: String = ""

    /** Persist + cache the choice. Called from the store setter and from
     *  MainActivity whenever the DataStore flow emits, so the mirror self-heals. */
    fun sync(context: Context, key: String, fileName: String) {
        this.key = key.ifBlank { DEFAULT }
        this.file = fileName
        loaded = true
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FONT, this.key)
                .putString(KEY_FILE, this.file)
                .apply()
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        runCatching {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            key = p.getString(KEY_FONT, DEFAULT) ?: DEFAULT
            file = p.getString(KEY_FILE, "") ?: ""
        }.onFailure {
            key = DEFAULT
            file = ""
        }
        loaded = true
    }

    /** The saved choice, readable without suspending. */
    data class Saved(val key: String, val fileName: String)

    fun current(context: Context): Saved {
        ensureLoaded(context)
        return Saved(key, file)
    }

    // ---- Typeface resolution ----

    /** Where imported fonts are copied. */
    fun importedDir(context: Context): File = File(context.filesDir, "fonts").apply { mkdirs() }

    fun importedFile(context: Context, name: String): File? {
        if (name.isBlank()) return null
        val f = File(importedDir(context), name)
        return if (f.isFile) f else null
    }

    /**
     * The [Typeface] for a saved choice, or null when the app should keep the
     * platform's own. Never throws: a family the device does not ship falls back
     * to the platform default, and a broken imported file is ignored rather than
     * crashing a screen.
     */
    fun typeface(context: Context, key: String, importedName: String = ""): Typeface? {
        if (key == IMPORTED) {
            val f = importedFile(context, importedName) ?: return null
            return runCatching { Typeface.createFromFile(f) }.getOrNull()
        }
        val family = byKey(key).family
        if (family.isBlank()) return null
        return runCatching { Typeface.create(family, Typeface.NORMAL) }.getOrNull()
    }

    /** The app font as the current choice resolves it — what the View-based
     *  screens use. Null means "leave the platform font alone". */
    fun appTypeface(context: Context): Typeface? {
        val saved = current(context)
        return typeface(context, saved.key, saved.fileName)
    }

    /** The Compose [FontFamily] for a choice (null = the theme's own). */
    fun fontFamily(context: Context, key: String, importedName: String = ""): FontFamily? =
        typeface(context, key, importedName)?.let { FontFamily(it) }

    /** The Compose family for the CURRENT choice. */
    fun appFontFamily(context: Context): FontFamily? {
        val saved = current(context)
        return fontFamily(context, saved.key, saved.fileName)
    }

    /** Re-types every TextView under [root]. The player inflates plain XML, so
     *  this is how it gets the app font at all. */
    fun applyToViewTree(root: View?, tf: Typeface?) {
        if (root == null || tf == null) return
        if (root is TextView) {
            val style = root.typeface?.style ?: Typeface.NORMAL
            runCatching { root.setTypeface(tf, style) }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyToViewTree(root.getChildAt(i), tf)
        }
    }

    /** Convenience for an Activity: resolve the app font and apply it to the
     *  whole content view. */
    fun applyToContent(context: Context, root: View?) {
        applyToViewTree(root, appTypeface(context))
    }

    // ---- Importing a font off the device ----

    /** Copy [uri] into `filesDir/fonts` and return `(fileName, label)`, or null
     *  when the file could not be read. */
    fun import(context: Context, uri: Uri): Pair<String, String>? = runCatching {
        val display = queryName(context, uri)
        val ext = display.substringAfterLast('.', "").ifBlank { "ttf" }
        val base = display.substringBeforeLast('.', display)
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .ifBlank { "font" }
        val target = File(importedDir(context), "imported.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        // Prove the file is a font BEFORE saving the choice: an unreadable or
        // unsupported file must not leave the app with no typeface at all.
        if (runCatching { Typeface.createFromFile(target) }.getOrNull() == null) {
            target.delete()
            return null
        }
        target.name to base
    }.getOrNull()

    private fun queryName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
}
