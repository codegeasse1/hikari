package com.hikari.app.ui.components

import com.hikari.app.data.Profiles
import com.hikari.app.ui.components.LocalHideHelp
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavHostController
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.cs3.Cs3PluginManager
import com.hikari.app.hiki.HikariPluginManager
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.data.RepoLoadState
import com.hikari.app.data.RepoProvenance
import com.hikari.app.data.Site
import com.hikari.app.data.SourceUrls
import com.hikari.app.net.Http
import com.hikari.app.net.PromoGuard
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderManager
import com.hikari.app.ui.FlagIcon
import com.hikari.app.ui.ProviderFlags
import com.hikari.app.ui.ProviderPack
import com.hikari.app.ui.ProviderPacks
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.SettingsThemeTile
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.rememberNsfwEnabled
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.hikari.app.tv.tvPress
import com.hikari.app.tv.tvToggle
import com.hikari.app.tv.tvTextFieldKeys
/**
 * What a schema-driven settings screen produced.
 *
 * Three engines describe their settings as DATA for the app to draw — a nuvio
 * scraper's `onSettings()`, a Vega provider's `settings.js`, an Aniyomi/Manga
 * extension's own `setupPreferenceScreen` — so the dialog below takes a loader
 * instead of reaching for one engine's runtime itself.
 */
sealed interface SchemaLoad {
    class Ok(
        val elements: JSONArray,
        val values: Map<String, String>,
        val toggles: Map<String, Boolean>,
    ) : SchemaLoad

    /** The extension really declares no settings — say so, naming it. */
    data object None : SchemaLoad

    /** Reading the screen failed; [message] is the reason to show instead. */
    class Failed(val message: String) : SchemaLoad
}

/**
 * A settings screen the APP draws from a schema the extension itself declares.
 *
 * [load] runs once when the dialog opens (off the main thread — it can boot a
 * JS engine or instantiate an extension), [save] when the user confirms. The
 * elements keep the shape the older nuvio-only dialog established —
 * `{type, key, label, description, defaultValue, options, isPassword}` with
 * types header/info/toggle/select/multi/number/text — because that is what
 * [SettingsElementRow] draws, and it now draws every engine's schema.
 */
@Composable
fun SchemaSettingsDialog(
    title: String,
    load: suspend () -> SchemaLoad,
    save: suspend (JSONArray, Map<String, String>, Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var none by remember { mutableStateOf(false) }
    var elements by remember { mutableStateOf<JSONArray?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var toggles by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val result = runCatching { load() }
            .getOrElse { SchemaLoad.Failed(it.message ?: I18n.t("Could not read the settings")) }
        when (result) {
            is SchemaLoad.Ok -> {
                elements = result.elements
                values = result.values
                toggles = result.toggles
            }
            is SchemaLoad.Failed -> error = result.message
            SchemaLoad.None -> none = true
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(tr("Loading settings…"))
                }
                error != null -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                none -> Text(
                    tr("This extension declares no settings of its own."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    val arr = elements ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val el = arr.optJSONObject(i) ?: continue
                        SettingsElementRow(
                            el = el,
                            values = values,
                            toggles = toggles,
                            onValue = { key, v -> values = values + (key to v) },
                            onToggle = { key, b -> toggles = toggles + (key to b) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && !saving && elements != null,
                onClick = {
                    val arr = elements
                    if (arr != null && !saving) {
                        saving = true
                        // Saved off the main thread: a save writes a settings
                        // file (nuvio), a provider's own kv.json (Vega) or the
                        // extension's SharedPreferences through its OWN
                        // preference listeners (Aniyomi/Manga) — none of which
                        // belongs on the UI thread.
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { save(arr, values, toggles) }
                            }
                            saving = false
                            onDismiss()
                        }
                    }
                }
            ) { Text(tr("Save")) }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) { Text(tr("Cancel")) }
        },
    )
}

/**
 * The values a schema dialog starts with: every element's own `defaultValue`,
 * with anything already saved merged over it. [saved] is the store the engine
 * keeps its values in (a nuvio settings file, a Vega provider's kv.json) — the
 * only thing that differs between the engines.
 */
fun schemaDefaults(
    elements: JSONArray,
    saved: JSONObject?,
): Pair<Map<String, String>, Map<String, Boolean>> {
    val values = LinkedHashMap<String, String>()
    val toggles = LinkedHashMap<String, Boolean>()
    for (i in 0 until elements.length()) {
        val el = elements.optJSONObject(i) ?: continue
        val key = el.optString("key").ifBlank { continue }
        when (el.optString("type")) {
            "toggle" -> {
                val def = el.optBoolean("defaultValue", false)
                toggles[key] = saved?.optBoolean(key, def) ?: def
            }
            "multi" -> {
                val arr = saved?.optJSONArray(key)
                values[key] = if (arr != null) {
                    (0 until arr.length()).joinToString(",") { arr.optString(it) }
                } else {
                    el.optString("defaultValue")
                }
            }
            else -> {
                val def = el.optString("defaultValue")
                values[key] = saved?.optString(key, def) ?: def
            }
        }
    }
    return values to toggles
}

/** A schema's key→value object (booleans for toggles, strings otherwise). */
fun schemaValuesJson(
    elements: JSONArray,
    values: Map<String, String>,
    toggles: Map<String, Boolean>,
): JSONObject {
    val out = JSONObject()
    for (i in 0 until elements.length()) {
        val el = elements.optJSONObject(i) ?: continue
        val key = el.optString("key").ifBlank { continue }
        if (el.optString("type") == "toggle") {
            out.put(key, toggles[key] ?: el.optBoolean("defaultValue", false))
        } else {
            out.put(key, values[key] ?: el.optString("defaultValue"))
        }
    }
    return out
}

/** A nuvio scraper's own settings layout (its `onSettings()` export). */
private suspend fun nuvioSchemaLoad(context: Context, provider: ContentProvider): SchemaLoad {
    val source = runCatching { File(provider.config.url).readText() }.getOrNull()
    if (source.isNullOrBlank()) {
        return SchemaLoad.Failed(I18n.t("Provider file missing — reinstall this extension"))
    }
    val payload = com.hikari.app.nuvio.NuvioRuntime.getSettingsLayout(
        context, source, provider.config.id,
    )
    val parsed = runCatching { JSONObject(payload) }.getOrNull()
    val data = parsed?.takeIf { it.optBoolean("ok", false) }?.opt("data")
    val elements = when (data) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("items") ?: data.optJSONArray("elements")
        else -> null
    }
    if (elements == null) {
        return SchemaLoad.Failed(
            parsed?.optString("error")?.takeIf { it.isNotBlank() }
                ?: I18n.t("This provider exposes no settings")
        )
    }
    val saved = runCatching {
        JSONObject(com.hikari.app.nuvio.NuvioRuntime.loadSettings(provider.config.id))
    }.getOrNull()
    val (values, toggles) = schemaDefaults(elements, saved)
    return SchemaLoad.Ok(elements, values, toggles)
}

/** A Vega provider's own `settings.js` schema, with its saved kv values. */
private suspend fun vegaSchemaLoad(provider: ContentProvider): SchemaLoad {
    val dir = com.hikari.app.providers.vega.VegaPluginManager.dirOf(provider.config)
    val value = provider.config.id.removePrefix("vega|")
    val payload = com.hikari.app.providers.vega.VegaRuntime.settingsSchema(
        dir, provider.config.id, value,
    )
    val parsed = runCatching { JSONObject(payload) }.getOrNull()
    if (parsed == null || !parsed.optBoolean("ok", false)) {
        return SchemaLoad.Failed(
            parsed?.optString("error")?.takeIf { it.isNotBlank() }
                ?: I18n.t("Could not read this provider's settings")
        )
    }
    val data = parsed.opt("data")
    val elements = when (data) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("items") ?: data.optJSONArray("elements")
        else -> null
    }
    if (elements == null || elements.length() == 0) return SchemaLoad.None
    val (values, toggles) = schemaDefaults(
        elements,
        com.hikari.app.providers.vega.VegaRuntime.savedSettings(dir),
    )
    return SchemaLoad.Ok(elements, values, toggles)
}

/** An Aniyomi/Manga extension's own `setupPreferenceScreen` tree. */
private suspend fun aniyomiSchemaLoad(context: Context, provider: ContentProvider): SchemaLoad {
    val screen = withContext(Dispatchers.IO) {
        com.hikari.app.data.ExtensionPreferences.load(context, provider.config)
    } ?: return SchemaLoad.None
    return SchemaLoad.Ok(screen.fields, screen.strings, screen.bools)
}

/** Settings editor for a NUVIO provider, driven by the provider's own
 *  `onSettings()` layout (header/info/toggle/text/select elements). */
@Composable
fun NuvioSettingsDialog(
    provider: ContentProvider,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SchemaSettingsDialog(
        title = I18n.t("%s settings").replace("%s", provider.config.name),
        load = { nuvioSchemaLoad(context, provider) },
        save = { elements, values, toggles ->
            com.hikari.app.nuvio.NuvioRuntime.saveSettings(
                provider.config.id,
                schemaValuesJson(elements, values, toggles).toString(),
            )
        },
        onDismiss = onDismiss,
    )
}

/** Settings editor for a VEGA provider, driven by its own `settings.js`
 *  (`getSettingsSchema`); the values land in the provider's own kv.json — the
 *  store its modules read — so what the screen shows is what the provider
 *  actually uses. */
@Composable
fun VegaSettingsDialog(
    provider: ContentProvider,
    onDismiss: () -> Unit,
) {
    SchemaSettingsDialog(
        title = I18n.t("%s settings").replace("%s", provider.config.name),
        load = { vegaSchemaLoad(provider) },
        save = { elements, values, toggles ->
            val dir = com.hikari.app.providers.vega.VegaPluginManager.dirOf(provider.config)
            com.hikari.app.providers.vega.VegaRuntime.saveSettings(
                dir,
                schemaValuesJson(elements, values, toggles),
            )
        },
        onDismiss = onDismiss,
    )
}

/** Settings editor for an ANIYOMI/MANGA extension, built from the preference
 *  tree the extension itself declares — and written back through those same
 *  `Preference` objects, so an extension's own change listeners (which is how a
 *  text field becomes an Int, or one switch rewrites another) still run. */
@Composable
fun AniyomiSettingsDialog(
    provider: ContentProvider,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SchemaSettingsDialog(
        title = I18n.t("%s settings").replace("%s", provider.config.name),
        load = { aniyomiSchemaLoad(context, provider) },
        save = { _, values, toggles ->
            val ok = com.hikari.app.data.ExtensionPreferences.save(
                context, provider.config, values, toggles,
            )
            if (!ok) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        I18n.t("Could not save these settings"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * What an extension IS, for the engines whose format has no settings screen to
 * open at all (a `.hiki` bundle, a universal scraper, a SkyStream extension), or
 * for one whose own screen could not be read.
 *
 * The gear has to answer something for every row; saying WHICH KIND of extension
 * this is, where it came from, and that its format carries no settings, is the
 * honest answer — the old generic line read as if Hikari had lost them.
 */
@Composable
fun ExtensionPrefDialog(
    title: String,
    load: suspend () -> String,
    save: (key: String, rawJson: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var defs by remember { mutableStateOf<JSONArray?>(null) }
    var listPick by remember { mutableStateOf<JSONObject?>(null) }
    var listSel by remember { mutableStateOf(0) }
    var multiPick by remember { mutableStateOf<JSONObject?>(null) }
    var multiSel by remember { mutableStateOf(setOf<String>()) }
    var textPick by remember { mutableStateOf<JSONObject?>(null) }
    var textVal by remember { mutableStateOf("") }
    LaunchedEffect(title) {
        val raw = runCatching { withContext(Dispatchers.IO) { load() } }.getOrDefault("[]")
        defs = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
    }
    fun live(key: String): JSONObject? {
        val a = defs ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("key") == key) return o
        }
        return null
    }
    fun update(key: String, fn: (JSONObject) -> Unit) {
        val a = defs ?: return
        val n = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("key") == key) {
                val c = JSONObject(o.toString())
                fn(c)
                n.put(c)
            } else {
                n.put(o)
            }
        }
        defs = n
    }
    fun entryIndex(d: JSONObject): Int {
        val values = d.optJSONArray("entryValues") ?: return 0
        val cur = d.optString("value")
        for (i in 0 until values.length()) if (values.optString(i) == cur) return i
        return 0
    }
    fun entryLabel(d: JSONObject): String {
        val entries = d.optJSONArray("entries") ?: return d.optString("value").ifBlank { d.optString("summary") }
        val idx = entryIndex(d)
        return entries.optString(idx).ifBlank { d.optString("summary") }
    }
    fun multiLabels(d: JSONObject): String {
        val entries = d.optJSONArray("entries") ?: JSONArray()
        val values = d.optJSONArray("entryValues") ?: JSONArray()
        val sel = d.optJSONArray("values") ?: JSONArray()
        val picked = LinkedHashSet<String>()
        for (i in 0 until sel.length()) picked.add(sel.optString(i))
        val names = ArrayList<String>()
        for (i in 0 until entries.length()) {
            if (i < values.length() && picked.contains(values.optString(i))) names.add(entries.optString(i))
        }
        return names.joinToString(", ").ifBlank { d.optString("summary") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val a = defs
            if (a == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else if (a.length() == 0) {
                Text(tr("This extension has no settings"))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp).fillMaxWidth()) {
                    items(a.length(), key = { idx -> a.optJSONObject(idx)?.optString("key") ?: idx.toString() }) { idx ->
                        val d = a.optJSONObject(idx) ?: return@items
                        val key = d.optString("key")
                        when (d.optString("type")) {
                            "switch", "checkBox" -> {
                                val on = d.optBoolean("value", false)
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.optString("title"), style = MaterialTheme.typography.titleSmall)
                                        if (d.optString("summary").isNotBlank()) Text(d.optString("summary"), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = on, onCheckedChange = { v ->
                                        update(key) { it.put("value", v) }
                                        scope.launch(Dispatchers.IO) { runCatching { save(key, v.toString()) } }
                                    })
                                }
                            }
                            "list" -> {
                                Column(Modifier.fillMaxWidth().clickable {
                                    listPick = JSONObject(d.toString())
                                    listSel = entryIndex(live(key) ?: d)
                                }.padding(vertical = 8.dp)) {
                                    Text(d.optString("title"), style = MaterialTheme.typography.titleSmall)
                                    Text(entryLabel(live(key) ?: d), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            "multi_select" -> {
                                Column(Modifier.fillMaxWidth().clickable {
                                    val cur = live(key) ?: d
                                    val sel = cur.optJSONArray("values") ?: JSONArray()
                                    val s = LinkedHashSet<String>()
                                    for (i in 0 until sel.length()) s.add(sel.optString(i))
                                    multiSel = s
                                    multiPick = JSONObject(cur.toString())
                                }.padding(vertical = 8.dp)) {
                                    Text(d.optString("title"), style = MaterialTheme.typography.titleSmall)
                                    Text(multiLabels(live(key) ?: d), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            else -> {
                                Column(Modifier.fillMaxWidth().clickable {
                                    textPick = JSONObject(d.toString())
                                    textVal = (live(key) ?: d).optString("value")
                                }.padding(vertical = 8.dp)) {
                                    Text(d.optString("title"), style = MaterialTheme.typography.titleSmall)
                                    val v = (live(key) ?: d).optString("value").ifBlank { d.optString("summary") }
                                    Text(v, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
    listPick?.let { lp ->
        val entries = lp.optJSONArray("entries") ?: JSONArray()
        val values = lp.optJSONArray("entryValues") ?: JSONArray()
        AlertDialog(
            onDismissRequest = { listPick = null },
            title = { Text(lp.optString("title")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp).fillMaxWidth()) {
                    items(entries.length()) { i ->
                        Row(Modifier.fillMaxWidth().clickable { listSel = i }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = listSel == i, onClick = { listSel = i })
                            Spacer(Modifier.width(8.dp))
                            Text(entries.optString(i))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = if (listSel < values.length()) values.optString(listSel) else ""
                    update(lp.optString("key")) { it.put("value", v) }
                    val k = lp.optString("key")
                    scope.launch(Dispatchers.IO) { runCatching { save(k, JSONObject.quote(v)) } }
                    listPick = null
                }) { Text(tr("Confirm")) }
            },
            dismissButton = { TextButton(onClick = { listPick = null }) { Text(tr("Cancel")) } },
        )
    }
    multiPick?.let { mp ->
        val entries = mp.optJSONArray("entries") ?: JSONArray()
        val values = mp.optJSONArray("entryValues") ?: JSONArray()
        AlertDialog(
            onDismissRequest = { multiPick = null },
            title = { Text(mp.optString("title")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp).fillMaxWidth()) {
                    items(entries.length()) { i ->
                        val ev = if (i < values.length()) values.optString(i) else entries.optString(i)
                        val on = multiSel.contains(ev)
                        Row(Modifier.fillMaxWidth().clickable {
                            multiSel = if (on) multiSel - ev else multiSel + ev
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = on, onCheckedChange = { c ->
                                multiSel = if (c) multiSel + ev else multiSel - ev
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(entries.optString(i))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val arr = JSONArray()
                    multiSel.forEach { arr.put(it) }
                    update(mp.optString("key")) { it.put("values", arr) }
                    val k = mp.optString("key")
                    scope.launch(Dispatchers.IO) { runCatching { save(k, arr.toString()) } }
                    multiPick = null
                }) { Text(tr("Confirm")) }
            },
            dismissButton = { TextButton(onClick = { multiPick = null }) { Text(tr("Cancel")) } },
        )
    }
    textPick?.let { tp ->
        AlertDialog(
            onDismissRequest = { textPick = null },
            title = { Text(tp.optString("title")) },
            text = {
                OutlinedTextField(value = textVal, onValueChange = { textVal = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    update(tp.optString("key")) { it.put("value", textVal) }
                    val k = tp.optString("key")
                    val v = textVal
                    scope.launch(Dispatchers.IO) { runCatching { save(k, JSONObject.quote(v)) } }
                    textPick = null
                }) { Text(tr("Confirm")) }
            },
            dismissButton = { TextButton(onClick = { textPick = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
fun AnymexSettingsDialog(
    provider: ContentProvider,
    onDismiss: () -> Unit,
) {
    ExtensionPrefDialog(
        title = I18n.t("%s settings").replace("%s", provider.config.name),
        load = {
            when (val pp = provider) {
                is com.hikari.app.anymex.AnymexProvider -> pp.preferenceDefs()
                is com.hikari.app.anymex.AnymexMangaProvider -> pp.preferenceDefs()
                else -> "[]"
            }
        },
        save = { key, raw ->
            when (val pp = provider) {
                is com.hikari.app.anymex.AnymexProvider -> pp.setPreference(key, raw)
                is com.hikari.app.anymex.AnymexMangaProvider -> pp.setPreference(key, raw)
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
fun SoraSettingsDialog(
    provider: ContentProvider,
    onDismiss: () -> Unit,
) {
    ExtensionPrefDialog(
        title = I18n.t("%s settings").replace("%s", provider.config.name),
        load = { (provider as? com.hikari.app.sora.SoraProvider)?.moduleSettings() ?: "[]" },
        save = { key, raw -> (provider as? com.hikari.app.sora.SoraProvider)?.setModuleSetting(key, raw) },
        onDismiss = onDismiss,
    )
}

/**
 * A SkyStream extension's mirror-domain picker — the reference app's domain
 * selector (see DEVELOPER.md "Declaring Mirror Domains"): the manifest's
 * `domains` become `manifest.baseUrl` candidates, and the pick is stored next
 * to plugin.json and injected at engine boot (see SkyStreamRuntime).
 */
@Composable
fun SkyStreamSettingsDialog(
    provider: ContentProvider,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mgr = com.hikari.app.skystream.SkyStreamPluginManager
    val pkg = remember(provider.config.id) { mgr.packageOf(provider.config.id) }
    var domains by remember {
        mutableStateOf(emptyList<com.hikari.app.skystream.SkyStreamPluginManager.SkyDomain>())
    }
    var picked by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(pkg) {
        val pair = withContext(Dispatchers.IO) {
            val file = mgr.scriptFile(context, pkg)
            mgr.domainsOf(file) to mgr.customBaseUrl(pkg)
        }
        domains = pair.first
        picked = pair.second
        loaded = true
    }
    fun pick(url: String?) {
        picked = url
        scope.launch(Dispatchers.IO) {
            runCatching {
                mgr.setCustomBaseUrl(context, pkg, url)
                // A new site means new pages: retire cached episode payloads
                // so the next open re-resolves against the picked mirror.
                com.hikari.app.data.MetaCache.bumpEpisodesEpoch()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("%s settings").replace("%s", provider.config.name)) },
        text = {
            if (!loaded) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else {
                Column {
                    Text(
                        tr("Mirror domain — the site address this extension reads. Switch mirrors here when the current one stops answering. Takes effect on the next search."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    domains.forEach { d ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pick(d.url) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = picked == d.url,
                                onClick = { pick(d.url) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    d.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    d.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { pick(null) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = picked == null,
                            onClick = { pick(null) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Default (manifest)"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}
@Composable
fun ProviderInfoDialog(provider: ContentProvider, onDismiss: () -> Unit) {
    val kind = when (provider.config.type) {
        ProviderType.CS3 -> tr("CloudStream plugin")
        ProviderType.HIKARI -> tr("Hikari extension")
        ProviderType.NUVIO -> tr("Nuvio scraper")
        ProviderType.VEGA -> tr("Vega provider")
        ProviderType.ZANGETSU -> tr("Zangetsu source")
        ProviderType.SORA -> tr("Sora source")
         ProviderType.ANYMEX -> tr("Anymex extension")
         ProviderType.ANYMEX_MANGA -> tr("Anymex manga")
        ProviderType.SKYSTREAM -> tr("SkyStream extension")
        ProviderType.UNIVERSAL -> tr("Universal scraper")
        ProviderType.ANIYOMI -> tr("Aniyomi extension")
        ProviderType.MANGA ->
            if (com.hikari.app.kotatsu.KotatsuRegistry.isKotatsuId(provider.config.id)) tr("Kotatsu source")
            else if (com.hikari.app.kotatsu.fork.ForkRegistry.isForkMangaId(provider.config.id)) tr("Fork source")
            else tr("Manga extension")
        ProviderType.FORK_VIDEO -> tr("Fork source")
        ProviderType.STREMIO -> tr("Stremio addon")
        ProviderType.IPTV -> tr("IPTV playlist")
        else -> tr("Extension")
    }
    val why = when (provider.config.type) {
        ProviderType.VEGA ->
            tr("This provider's folder has no settings.js, so it declares nothing to configure.")
        ProviderType.UNIVERSAL ->
            tr("A universal scraper is configured by the JSON it was added with — remove it and add it again to change that.")
        ProviderType.HIKARI ->
            tr("The .hiki extension format has no settings screen of its own.")
        ProviderType.SKYSTREAM ->
            tr("This extension declares no settings.")
        ProviderType.ANIYOMI, ProviderType.MANGA ->
            tr("Its own settings screen could not be read — the extension may be broken or need reinstalling.")
        ProviderType.NUVIO ->
            tr("Its onSettings() declares nothing to configure.")
        ProviderType.STREMIO ->
            tr("Its manifest declares no configuration page, so it has nothing to configure.")
        else ->
            tr("This extension declares no settings of its own.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.config.name.ifBlank { kind }) },
        text = {
            Column {
                Text(
                    kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (provider.config.url.isNotBlank()) {
                    Text(
                        provider.config.url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}

/**
 * What a Stremio addon IS, for an addon whose manifest declares NO
 * configuration page.
 *
 * The gear on an addon used to open [NuvioSettingsDialog], which reads the
 * provider's FILE (`File(provider.config.url)`) — an addon is a remote manifest
 * URL, so it read nothing and answered "Provider file missing — reinstall this
 * extension" over a perfectly healthy addon. An addon WITH a config page now
 * opens that page instead (see openProviderSettings in ExtensionsScreen); this dialog is what
 * remains for the ones that genuinely have nothing to configure, and it says so
 * in those terms rather than claiming no addon ever has settings.
 */
@Composable
fun StremioAddonInfoDialog(provider: ContentProvider, onDismiss: () -> Unit) {
    val id = provider.config.id
    val summary = com.hikari.app.providers.StremioAddon.resourceSummary[id]
    val streamOnly = com.hikari.app.providers.StremioAddon.streamOnlyAddons[id] == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.config.name.ifBlank { tr("Stremio addon") }) },
        text = {
            Column {
                Text(
                    tr("Stremio addon"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    provider.config.url,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                if (!LocalHideHelp.current) {
                Text(
                    tr(
                        when {
                            summary != null -> "What it provides: %s".replace("%s", summary)
                            streamOnly -> "It has no catalog of its own — it adds playback servers to titles you open from any extension."
                            else -> "It adds playback servers to titles you open anywhere in the app."
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
                Spacer(Modifier.height(6.dp))
                if (!LocalHideHelp.current) {
                Text(
                    tr(
                        "Its manifest declares no configuration page, so there is nothing " +
                            "to set up here. Use the switch to turn it off without " +
                            "uninstalling it."
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}

/**
 * What an IPTV playlist IS: where it was read from, how many channels it holds,
 * and a way to read it again (a panel that added channels, or a link that was
 * briefly down). A playlist has no settings of its own, so a Nuvio-style
 * settings screen would only ever report a missing file — this is the honest
 * version of that screen for a playlist.
 */
@Composable
fun IptvInfoDialog(
    provider: ContentProvider,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val id = provider.config.id
    val error = com.hikari.app.providers.IptvProvider.iptvErrors[id]
    val count = com.hikari.app.providers.IptvProvider.channelCounts[id]
    val local = !provider.config.url.startsWith("http")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.config.name.ifBlank { tr("IPTV playlist") }) },
        text = {
            Column {
                Text(
                    tr("IPTV playlist"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    provider.config.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (!LocalHideHelp.current) {
                Text(
                    when {
                        error != null -> error
                        count != null -> I18n.t(
                            "Channels found: %s — they appear on Home, in search and in the " +
                                "player's server list."
                        ).replace("%s", count.toString())
                        else -> tr("Reading this playlist…")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
                if (local) {
                    Spacer(Modifier.height(6.dp))
                    if (!LocalHideHelp.current) {
                    Text(
                        tr("Stored inside the app — removing this playlist deletes the file too."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRefresh() }) { Text(tr("Refresh")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}

@Composable
fun SettingsElementRow(
    el: JSONObject,
    values: Map<String, String>,
    toggles: Map<String, Boolean>,
    onValue: (String, String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val type = el.optString("type")
    val rawLabel = el.optString("label")
    val rawDescription = el.optString("description").ifBlank { null }
    // Extensions write this screen's rows themselves, and a "header"/"info"
    // row is a favourite place for a funding ask ("Support us on Patreon", a
    // ko-fi link). Nothing about MONEY belongs in an extension's settings, so
    // a promo-only row is dropped outright and every other string is cleaned
    // (markup and donation links stripped) before it is drawn.
    if ((type == "header" || type == "info") && PromoGuard.isPromoText(rawLabel)) return
    val label = PromoGuard.cleanText(rawLabel).ifBlank { rawLabel }
    val description = rawDescription?.let { PromoGuard.cleanText(it).ifBlank { null } }
    when (type) {
        "header" -> Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        "info" -> Column(Modifier.padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        "toggle" -> {
            val key = el.optString("key")
            val checked = toggles[key] ?: el.optBoolean("defaultValue", false)
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        description?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = { onToggle(key, it) },
                        // See [Modifier.tvToggle]: the switch answers the D-pad itself.
                        modifier = Modifier.tvToggle(checked, onValueChange = { onToggle(key, it) }),
                    )
                }
            }
        }
        "select" -> {
            val key = el.optString("key")
            val options = runCatching { el.getJSONArray("options") }.getOrNull()
                ?: return
            val selected = values[key] ?: el.optString("defaultValue")
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (i in 0 until options.length()) {
                    val opt = options.optJSONObject(i) ?: continue
                    val optValue = opt.optString("value")
                    val optLabel = opt.optString("label").ifBlank { optValue }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onValue(key, optValue) }
                            .tvPress(previewPass = false, onClick = { onValue(key, optValue) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == optValue,
                            onClick = { onValue(key, optValue) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(optLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        "multi" -> {
            val key = el.optString("key")
            val options = runCatching { el.getJSONArray("options") }.getOrNull()
                ?: return
            // Carried as a comma-joined string (see ExtensionPreferences): a
            // multi-select is a Set<String> in the extension's own preferences,
            // and this keeps the dialog's two plain value maps enough for every
            // engine's schema.
            val selected = (values[key] ?: el.optString("defaultValue"))
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (i in 0 until options.length()) {
                    val opt = options.optJSONObject(i) ?: continue
                    val optValue = opt.optString("value")
                    val optLabel = opt.optString("label").ifBlank { optValue }
                    val on = optValue in selected
                    val toggle = {
                        val next = if (on) selected - optValue else selected + optValue
                        onValue(key, next.joinToString(","))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { toggle() }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = on, onCheckedChange = { toggle() })
                        Spacer(Modifier.width(8.dp))
                        Text(optLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        else -> {
            val key = el.optString("key")
            val isPassword = el.optBoolean("isPassword", false)
            // A numeric field (an Aniyomi `SeekBarPreference`, a port setting):
            // non-digits are dropped as they are typed, so the value that gets
            // saved is always a number the extension can parse.
            val isNumber = el.optBoolean("isNumber", false)
            val value = values[key] ?: el.optString("defaultValue")
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { raw -> onValue(key, if (isNumber) raw.filter { c -> c.isDigit() } else raw) },
                    placeholder = { Text(el.optString("placeholder")) },
                    singleLine = true,
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = when {
                        isPassword -> KeyboardOptions(keyboardType = KeyboardType.Password)
                        isNumber -> KeyboardOptions(keyboardType = KeyboardType.Number)
                        else -> KeyboardOptions.Default
                    },
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(value)
                )
            }
        }
    }
}
/**
 * The extension-settings dialog for any provider, shared by the Extensions
 * screen and the catalogue 3-dot button (see CatalogScreen): a schema-driven
 * screen for the engines that describe their settings as data (nuvio's own
 * `onSettings()`, a Vega provider's settings.js, an Aniyomi/Manga
 * extension's own preference tree, Anymex/Sora prefs, SkyStream mirrors) and
 * the provider-info dialog for formats with no settings screen of their own.
 */
@Composable
fun ProviderSettingsDialog(provider: ContentProvider, onDismiss: () -> Unit) {
    when (provider.config.type) {
        ProviderType.VEGA -> VegaSettingsDialog(provider = provider, onDismiss = onDismiss)
        ProviderType.ANIYOMI, ProviderType.MANGA ->
            AniyomiSettingsDialog(provider = provider, onDismiss = onDismiss)
        ProviderType.ANYMEX, ProviderType.ANYMEX_MANGA ->
            AnymexSettingsDialog(provider = provider, onDismiss = onDismiss)
        ProviderType.SORA ->
            SoraSettingsDialog(provider = provider, onDismiss = onDismiss)
        ProviderType.SKYSTREAM ->
            SkyStreamSettingsDialog(provider = provider, onDismiss = onDismiss)
        ProviderType.CS3, ProviderType.HIKARI, ProviderType.UNIVERSAL,
        ProviderType.STREMIO, ProviderType.IPTV, ProviderType.ZANGETSU ->
            ProviderInfoDialog(provider = provider, onDismiss = onDismiss)
        else -> NuvioSettingsDialog(provider = provider, onDismiss = onDismiss)
    }
}
