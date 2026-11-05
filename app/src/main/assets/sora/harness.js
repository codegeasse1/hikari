/* HIKARI sora-module runtime harness.
 *
 * Sora modules (Sora / Luna / Anymex / Dartotsu / ...) are single self-contained
 * scripts with global async entry points:
 *
 *   searchResults(keyword)  -> JSON string [{title, image, href}]
 *   extractDetails(url)     -> JSON string [{description, aliases, airdate}]
 *   extractEpisodes(url)    -> JSON string [{href, number}]
 *   extractStreamUrl(url)   -> JSON string {streams: [{title, streamUrl, headers?}], subtitles?}
 *
 * (Manga modules return raw objects instead of JSON strings; novels return
 * JSON strings except extractText which returns raw HTML.)
 *
 * The host contract the scripts are written against:
 *   fetchv2(url, headers, method, body, opts?) -> {text(), json(), headers}
 *   networkFetch(url, timeoutSec, headers, mode) -> {url, html, requests[]}
 *   console.log / console.error
 *
 * Loaded AFTER nuvio/boot.js and nuvio/harness.js, so the polyfills and the
 * global fetch (over Hikari's OkHttp bridge) are already in place.
 */
(function () {
  'use strict';
  var g = globalThis;

  function isDef(v) { return v !== undefined && v !== null; }

  // ---- timers (QuickJS ships none) ----
  if (typeof g.setTimeout !== 'function') {
    g.__soraTimers = {};
    g.__soraTimerSeq = 0;
    g.setTimeout = function (callback, delay) {
      var id = 't_' + (++g.__soraTimerSeq);
      g.__soraTimers[id] = {
        due: Date.now() + (Number(delay) || 0),
        fn: function () {
          if (!g.__soraTimers[id]) return;
          delete g.__soraTimers[id];
          try { callback(); } catch (e) { try { console.error('Timeout error:', e); } catch (e2) {} }
        }
      };
      return id;
    };
    g.clearTimeout = function (id) { if (id) delete g.__soraTimers[id]; };
    g.setInterval = function (callback, delay) {
      var id = 'i_' + (++g.__soraTimerSeq);
      var period = Number(delay) || 0;
      g.__soraTimers[id] = {
        due: Date.now() + period,
        fn: function () {
          if (!g.__soraTimers[id]) return;
          try { callback(); } catch (e) { try { console.error('Interval error:', e); } catch (e2) {} }
          var t = g.__soraTimers[id];
          if (t) t.due = Date.now() + period;
        }
      };
      return id;
    };
    g.clearInterval = g.clearTimeout;
  }

  g.__soraFireTimer = function () {
    var ids = Object.keys(g.__soraTimers || {});
    if (!ids.length) return -1;
    var now = Date.now();
    var best = null;
    var bestDue = Infinity;
    for (var i = 0; i < ids.length; i++) {
      var t = g.__soraTimers[ids[i]];
      if (!t) continue;
      if (t.due < bestDue) { bestDue = t.due; best = ids[i]; }
    }
    if (best === null) return -1;
    if (bestDue > now) return Math.max(1, Math.round(bestDue - now));
    var fn = g.__soraTimers[best] && g.__soraTimers[best].fn;
    try { if (fn) fn(); } catch (e) {}
    return 0;
  };

  // ---- console fallback (boot.js normally provides it) ----
  if (typeof g.console === 'undefined') {
    g.console = {
      log: function (m) { try { g.__soraLog(String(m)); } catch (e) {} },
      error: function (m) { try { g.__soraLog('ERR: ' + String(m)); } catch (e) {} },
      warn: function (m) { try { g.__soraLog('WARN: ' + String(m)); } catch (e) {} }
    };
  }

  function normHeaders(h) {
    var out = {};
    if (!h) return out;
    if (Array.isArray(h)) {
      for (var i = 0; i < h.length; i++) {
        if (h[i] && h[i].length >= 2 && isDef(h[i][1])) out[String(h[i][0])] = String(h[i][1]);
      }
      return out;
    }
    if (typeof h === 'object') {
      for (var k in h) {
        if (Object.prototype.hasOwnProperty.call(h, k) && isDef(h[k])) out[String(k)] = String(h[k]);
      }
    }
    return out;
  }

  // ---- fetchv2: the native bridge the spec mandates ----
  g.fetchv2 = function (url, headers, method, body, opts) {
    var h = normHeaders(headers);
    var m = String(method || 'GET').toUpperCase();
    if (opts && opts.impersonate === 'chrome' && !h['User-Agent'] && !h['user-agent']) {
      h['User-Agent'] = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
    } else if (opts && opts.impersonate === 'safari' && !h['User-Agent'] && !h['user-agent']) {
      h['User-Agent'] = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15';
    } else if (opts && opts.impersonate === 'firefox' && !h['User-Agent'] && !h['user-agent']) {
      h['User-Agent'] = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0';
    }
    var init = { method: m, headers: h };
    if (body !== undefined && body !== null && m !== 'GET' && m !== 'HEAD') init.body = body;
    return fetch(String(url), init).then(function (res) {
      if (!res.ok && (res.status < 200 || res.status >= 300)) {
        var err = new Error('Request failed with status code ' + res.status);
        err.response = res;
        return Promise.reject(err);
      }
      return res;
    });
  };

  // ---- networkFetch: best-effort page interception ----
  // The real Sora host loads the page in a WebView and captures the requests
  // the page makes. This engine has no WebView, so the page is fetched
  // directly and every URL it references (scripts, iframes, API-looking
  // strings) is reported as a captured request. Modules that only need the
  // page's own links work unchanged; ones that need true XHR interception
  // see an empty list and take their own fallback path.
  g.networkFetch = function (url, timeoutSec, headers, mode) {
    var u = String(url || '');
    return fetch(u, { method: 'GET', headers: normHeaders(headers) })
      .then(function (res) { return res.text().then(function (html) { return { res: res, html: html }; }); })
      .then(function (pair) {
        var html = pair.html || '';
        var seen = {};
        var requests = [];
        function add(x) {
          if (!x || seen[x]) return;
          if (x.indexOf('http') !== 0 && x.indexOf('//') !== 0 && x.indexOf('/') !== 0) return;
          seen[x] = true;
          requests.push(x);
        }
        var re = /(?:src|href|data-src|content)\s*=\s*["']([^"']+)["']/gi;
        var m;
        while ((m = re.exec(html)) !== null) add(m[1]);
        var re2 = /"(https?:\/\/[^"\\\s<>]+)"/g;
        while ((m = re2.exec(html)) !== null) {
          var s = m[1];
          if (/api|embed|player|source|stream|server|video|m3u8|mp4/i.test(s)) add(s);
        }
        return { url: pair.res.url || u, html: html, requests: requests };
      })
      .catch(function (e) { return { url: u, html: '', requests: [], error: String((e && e.message) || e) }; });
  };

  // ---- module calling ----
  function describeError(e) {
    if (e === undefined || e === null) return 'unknown error';
    if (typeof e === 'string') return e;
    var msg = e.message ? String(e.message) : String(e);
    if (e.response && e.response.status) msg = 'HTTP ' + e.response.status + (msg ? ' — ' + msg : '');
    return msg;
  }

  function done(payload) {
    try { g.__soraDone(typeof payload === 'string' ? payload : JSON.stringify(payload)); } catch (e) {}
  }

  // Calls one global entry point with the given JSON arg array and answers
  // {"ok":true,"data":<parsed-or-raw>} — anime modules answer JSON strings,
  // manga modules raw objects; both land as data either way.
  g.__soraCall = function (fnName, argsJson) {
    try {
      var fn = g[String(fnName)];
      if (typeof fn !== 'function') { done({ ok: false, error: 'module does not export ' + fnName }); return; }
      var args = JSON.parse(argsJson || '[]');
      if (!Array.isArray(args)) args = [args];
      Promise.resolve()
        .then(function () { return fn.apply(null, args); })
        .then(function (r) {
          if (typeof r === 'string') {
            var t = r.trim();
            if ((t.charAt(0) === '{' || t.charAt(0) === '[')) {
              try { done({ ok: true, data: JSON.parse(t) }); return; } catch (e) {}
            }
            done({ ok: true, data: r });
          } else if (r === undefined || r === null) {
            done({ ok: true, data: null });
          } else {
            try { done({ ok: true, data: JSON.parse(JSON.stringify(r)) }); }
            catch (e) { done({ ok: false, error: 'module result could not be serialized' }); }
          }
        }, function (e) { done({ ok: false, error: describeError(e).slice(0, 400) }); });
    } catch (e) {
      done({ ok: false, error: describeError(e).slice(0, 400) });
    }
  };

  if (typeof console !== 'undefined' && console.log) console.log('[sora] harness ready');
})();
