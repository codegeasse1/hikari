package com.hikari.app.web

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.Userscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal Tampermonkey-style userscript runner for the in-app WebView. Scripts
 * are stored in AppStore, matched against the page URL by their
 * `// ==UserScript==` header (@match / @include / @exclude / @run-at), and
 * injected with a small GM_* shim (getValue/setValue/deleteValue/listValues/
 * addStyle/addElement/setClipboard). GM values persist per script in a
 * SharedPreferences file. Scripts NEVER run outside the WebView.
 */
object UserscriptManager {

    data class Parsed(
        val name: String,
        val runAtStart: Boolean,
        val matches: List<String>,
        val includes: List<String>,
        val excludes: List<String>,
    )

    @Volatile
    private var cached: List<Userscript>? = null

    suspend fun reload(ctx: Context): List<Userscript> = withContext(Dispatchers.IO) {
        (ctx.applicationContext as HikariApp).store.userscripts().also { cached = it }
    }

    fun isLoaded(): Boolean = cached != null

    /** Wrapped JS payloads for every enabled script that targets [url] at the
     *  requested phase. */
    fun scriptsFor(url: String, atStart: Boolean): List<String> {
        val all = cached ?: return emptyList()
        val out = mutableListOf<String>()
        for (s in all) {
            if (!s.enabled || s.code.isBlank()) continue
            val p = parse(s.code)
            if (p.runAtStart != atStart) continue
            if (!urlMatches(p, url)) continue
            out.add(wrapper(s, p.name))
        }
        return out
    }

    fun parse(code: String): Parsed {
        val name = grab(code, """@name\s+(\S+)""").firstOrNull() ?: "Userscript"
        val runAtStart = grab(code, """@run-at\s+(\S+)""").any { it == "document-start" }
        return Parsed(
            name = name,
            runAtStart = runAtStart,
            matches = grab(code, """@match\s+(\S+)"""),
            includes = grab(code, """@include\s+(\S+)"""),
            excludes = grab(code, """@exclude\s+(\S+)"""),
        )
    }

    private fun grab(code: String, regex: String): List<String> =
        Regex(regex).findAll(code).map { it.groupValues[1].trim() }.toList()

    private val globCache = ConcurrentHashMap<String, Regex>()

    private fun globRegex(glob: String): Regex {
        globCache[glob]?.let { return it }
        var g = glob.trim()
        val r = when {
            // Tampermonkey regex form: /pattern/
            g.startsWith("/") && g.endsWith("/") && g.length > 2 ->
                runCatching { Regex(g.substring(1, g.length - 1), RegexOption.IGNORE_CASE) }
                    .getOrElse { Regex("^$") }
            else -> {
                // A trailing `/*` should also match the bare host (no path).
                val trailingSlashStar = g.endsWith("/*")
                if (trailingSlashStar) g = g.dropLast(2).trimEnd('/')
                val sb = StringBuilder("^")
                var i = 0
                while (i < g.length) {
                    val c = g[i]
                    when {
                        c == '*' && g.startsWith("*://", i) -> { sb.append("[a-z]+://"); i += 4 }
                        c == '*' && i + 1 < g.length && g[i + 1] == '.' -> {
                            sb.append("(?:.*\\.)?"); i += 2
                        }
                        c == '*' -> { sb.append(".*"); i += 1 }
                        c == '?' -> { sb.append('.'); i += 1 }
                        else -> { sb.append(Regex.escape(c.toString())); i += 1 }
                    }
                }
                if (trailingSlashStar) sb.append("(?:/.*)?")
                sb.append("$")
                runCatching { Regex(sb.toString(), RegexOption.IGNORE_CASE) }
                    .getOrElse { Regex("^$") }
            }
        }
        globCache[glob] = r
        return r
    }

    private fun urlMatches(p: Parsed, url: String): Boolean {
        if (p.excludes.any { globRegex(it).matches(url) }) return false
        if (p.matches.isNotEmpty()) return p.matches.any { globRegex(it).matches(url) }
        if (p.includes.isNotEmpty()) return p.includes.any { globRegex(it).matches(url) }
        // No @match/@include — run everywhere (page context is still limited
        // to the WebView).
        return true
    }

    /** Wraps the raw script in an IIFE with a GM_* shim, matching Tampermonkey
     *  semantics (script scope isolated, `unsafeWindow` = page window). */
    fun wrapper(s: Userscript, displayName: String): String {
        val id = s.id.replace("\\", "\\\\").replace("'", "\\'")
        val name = displayName.replace("\\", "\\\\").replace("'", "\\'")
        val pre = ("(function(){try{" +
            "var __usN='$name',__usI='$id';" +
            "function __g(k){try{return HikariBridge.userscriptGet(__usI,k)}catch(e){return null}}" +
            "function __s(k,v){try{HikariBridge.userscriptSet(__usI,k,JSON.stringify(v))}catch(e){}}" +
            "function __d(k){try{HikariBridge.userscriptDelete(__usI,k)}catch(e){}}" +
            "function __l(){try{return HikariBridge.userscriptList(__usI)}catch(e){return ''}}" +
            "function __v(k,d){var x=__g(k);if(x===null||x===undefined)return d;try{return JSON.parse(x)}catch(e){return x}}" +
            "var GM={getValue:__v,setValue:__s,deleteValue:__d," +
            "listValues:function(){return __l().split('\\n').filter(function(x){return x.length>0})}," +
            "addStyle:function(c){var st=document.createElement('style');st.textContent=c;" +
            "(document.head||document.documentElement).appendChild(st);return st}," +
            "addElement:function(t,a){var el=document.createElement(t);for(var k in a||{}){el.setAttribute(k,a[k])}" +
            "(document.head||document.documentElement).appendChild(el);return el}," +
            "setClipboard:function(t){try{navigator.clipboard.writeText(t)}catch(e){}}," +
            "getResourceText:function(){return undefined}," +
            "info:{script:{name:__usN,id:__usI},version:undefined,scriptHandler:'Hikari'}};" +
            "var GM_getValue=GM.getValue,GM_setValue=GM.setValue,GM_deleteValue=GM.deleteValue," +
            "GM_listValues=GM.listValues,GM_addStyle=GM.addStyle,GM_addElement=GM.addElement," +
            "GM_setClipboard=GM.setClipboard,GM_getResourceText=GM.getResourceText,GM_info=GM.info;" +
            "var unsafeWindow=window;")
        val post = ("}catch(e){console.error('[Hikari userscript] '+__usN,e)}})();")
        return pre + s.code + post
    }

    // ---- GM value storage (SharedPreferences, keyed per script) ----

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("userscript_values", Context.MODE_PRIVATE)

    fun getValue(ctx: Context, scriptId: String, key: String): String? =
        prefs(ctx).getString("$scriptId::$key", null)

    fun setValue(ctx: Context, scriptId: String, key: String, valueJson: String) {
        prefs(ctx).edit().putString("$scriptId::$key", valueJson).apply()
    }

    fun deleteValue(ctx: Context, scriptId: String, key: String) {
        prefs(ctx).edit().remove("$scriptId::$key").apply()
    }

    fun listValues(ctx: Context, scriptId: String): String {
        val prefix = "$scriptId::"
        return prefs(ctx).all.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .joinToString("\n")
    }
}
