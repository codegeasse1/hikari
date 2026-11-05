package org.hany18h.parsers.site.ar

import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.hany18h.parsers.exception.ContentUnavailableException

/** Uses the site's public, same-origin client, not a guessed future cipher. */
internal object DilarWebClient {

    fun buildScript(baseUrl: String, url: String, clientToken: String, freePass: String?, requestId: String): String = """
        (function() {
          const key = ${JSONObject.quote("__dilarRequest_$requestId")};
          let state = window[key];
          // The host may poll just as navigation commits, before module tags
          // are parsed. Wait for the real page rather than caching that race.
          if (!state && document.querySelectorAll('script[type="module"][src]').length === 0) return null;
          if (!state) {
            state = window[key] = {done: false};
            (async function() {
              const base = new URL(${JSONObject.quote(baseUrl)});
              const target = new URL(${JSONObject.quote(url)});
              if (location.origin !== base.origin || target.origin !== base.origin ||
                  !target.pathname.startsWith('/api/chapters/')) {
                throw new Error('Unexpected Dilar request origin');
              }
              // Discover fresh entry URLs from the loaded website, never pin a
              // minified export name or an asset hash. Importing an already
              // loaded ES module reuses its authenticated session/interceptors.
              const entries = Array.from(document.querySelectorAll('script[type="module"][src]'))
                .map(function(node) { return new URL(node.getAttribute('src'), base); })
                .filter(function(entry) { return entry.origin === base.origin; })
                .reverse().slice(0, 8);
              let get = null;
              for (const entry of entries) {
                let module;
                try { module = await import(entry.href); } catch (_) { continue; }
                get = Object.values(module).find(function(value) {
                  return typeof value === 'function' &&
                    /\bmethod\s*:\s*["']GET["']/.test(Function.prototype.toString.call(value));
                });
                if (get) break;
              }
              if (!get) throw new Error('Current Dilar client API was not found');
              const headers = {'X-Client-Token': ${JSONObject.quote(clientToken)}};
              const pass = ${freePass?.let(JSONObject::quote) ?: "null"};
              if (pass) headers['X-Unlock-Free-Chapter'] = pass;
              const controller = new AbortController();
              const timer = setTimeout(function() { controller.abort(); }, 10000);
              try {
                // The current client supplies crypto capabilities, ECDH and
                // authentication, and verifies the response tag itself.
                const data = await get(target.pathname.substring(4) + target.search,
                  {headers: headers, signal: controller.signal, timeout: 10000});
                if (!data || typeof data !== 'object' || Array.isArray(data) ||
                    ('epk' in data && 'ct' in data) || !Array.isArray(data.pages)) {
                  throw new Error('Current Dilar client could not open the chapter');
                }
                state.result = {data: data};
              } finally { clearTimeout(timer); }
            })().catch(function(error) {
              const status = Number(error.status || (error.response && error.response.status) || 0);
              // Do not send credentials or arbitrary site error text into logs.
              state.result = {error: 'Dilar website client could not load this chapter', status: status};
            }).finally(function() { state.done = true; });
          }
          // evaluateJavascript does not await promises. The host polls this
          // synchronous result; it also imposes an overall timeout.
          return state.done ? JSON.stringify(state.result) : null;
        })()
    """.trimIndent()

    fun parseResult(raw: String?, url: String): JSONObject {
        if (raw.isNullOrBlank() || raw == "null") {
            throw ContentUnavailableException("Dilar website client timed out; update Android System WebView or retry")
        }
        // Android's evaluateJavascript JSON-encodes the returned string.
        val result = JSONObject(JSONObject("{\"value\":$raw}").getString("value"))
        if (result.has("error")) {
            val status = result.optInt("status")
            if (status in 400..599) throw HttpStatusException("Dilar browser request rejected", status, url)
            throw ContentUnavailableException("Dilar website client could not load this chapter; browser verification or a source update may be required")
        }
        val data = result.optJSONObject("data")
        if (data == null || (data.has("epk") && data.has("ct")) || data.optJSONArray("pages") == null) {
            throw ContentUnavailableException("Dilar website client did not return a decrypted chapter")
        }
        return data
    }
}
