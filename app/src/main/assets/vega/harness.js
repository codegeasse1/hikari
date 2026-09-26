/* HIKARI vega-provider runtime harness.
 *
 * Vega providers (github.com/Zenda-Cross/vega-providers) are plain CommonJS
 * modules — one file per function — that are handed a `providerContext` object
 * and reach the network through it:
 *
 *   catalog.js   exports catalog[], genres[]           (pure data)
 *   posts.js     getPosts({filter,page,signal,providerContext})
 *                getSearchPosts({searchQuery,page,signal,providerContext})
 *   meta.js      getMeta({link,providerContext})   -> Info
 *   episodes.js  getEpisodes({url,providerContext})-> EpisodeLink[]
 *   stream.js    getStream({link,type,signal,providerContext,isDownload}) -> Stream[]
 *   settings.js  getSettingsSchema({providerContext}) -> SettingsField[]
 *
 * The real Vega app builds that context around axios + cheerio + a native HTTP
 * bridge + a per-provider key/value store + an interactive WebView for bot
 * walls (`openWebView`). This harness recreates the same surface inside Hikari's
 * embedded QuickJS engine:
 *
 *   axios        — a faithful-enough axios built on the engine's global `fetch`
 *                  (which nuvio's harness.js provides over Hikari's OkHttp
 *                  bridge): get/post/put/patch/delete/head/options, the callable
 *                  form, `params`, `maxRedirects: 0`, `responseType:
 *                  'arraybuffer'` and axios' own `transformResponse`
 *                  (JSON.parse the body, fall back to the raw string). It
 *                  REJECTS on a non-2xx status unless `validateStatus` says
 *                  otherwise, and the rejection carries `.response` — several
 *                  providers branch on `error.response.status === 403`.
 *   cheerio      — the same bundle nuvio's harness already loads.
 *   commonHeaders— dist/headers.js's desktop-Chrome header object.
 *   kvStore      — per provider, persisted by the host to kv.json.
 *   openWebView  — answered honestly (Hikari's engine has no interactive
 *                  WebView to hand a captcha to) so a provider falls through to
 *                  its own error path instead of hanging.
 *   providerGlobal — the per-provider cache object several providers create.
 *
 * Loaded AFTER nuvio/boot.js, nuvio/cheerio.js and nuvio/harness.js, so the
 * polyfills (URL/TextEncoder/abort/atob/Buffer/console), the CommonJS `require`
 * registry and the global `fetch` are already in place.
 */
(function () {
  'use strict';
  var g = globalThis;

  // ---- providerGlobal: the per-provider cache object providers expect ----
  if (!g.providerGlobal || typeof g.providerGlobal !== 'object') g.providerGlobal = {};

  // ---- timers (QuickJS ships none; a handful of providers use setTimeout) ----
  if (typeof g.setTimeout !== 'function') {
    g.__vegaTimers = {};
    g.__vegaTimerSeq = 0;
    g.setTimeout = function (callback, delay) {
      var id = 't_' + (++g.__vegaTimerSeq);
      g.__vegaTimers[id] = {
        due: Date.now() + (Number(delay) || 0),
        fn: function () {
          if (!g.__vegaTimers[id]) return;
          delete g.__vegaTimers[id];
          try { callback(); } catch (e) { try { console.error('Timeout error:', e); } catch (e2) {} }
        }
      };
      return id;
    };
    g.clearTimeout = function (id) { if (id) delete g.__vegaTimers[id]; };
    g.setInterval = function (callback, delay) {
      var id = 'i_' + (++g.__vegaTimerSeq);
      var period = Number(delay) || 0;
      g.__vegaTimers[id] = {
        due: Date.now() + period,
        fn: function () {
          if (!g.__vegaTimers[id]) return;
          try { callback(); } catch (e) { try { console.error('Interval error:', e); } catch (e2) {} }
          var t = g.__vegaTimers[id];
          if (t) t.due = Date.now() + period;
        }
      };
      return id;
    };
    g.clearInterval = g.clearTimeout;
  }

  /* Fires at most one due timer per call, returning -1 when nothing is parked,
   * 0 when a timer was due and has just run, or the milliseconds until the next
   * one is due. Never fires a timer early — providers use setTimeout for
   * Promise.race guards and retry backoff, and firing early would abort a
   * healthy request. */
  g.__vegaFireTimer = function () {
    var ids = Object.keys(g.__vegaTimers || {});
    if (!ids.length) return -1;
    var now = Date.now();
    var best = null;
    var bestDue = Infinity;
    for (var i = 0; i < ids.length; i++) {
      var t = g.__vegaTimers[ids[i]];
      if (!t) continue;
      if (t.due < bestDue) { bestDue = t.due; best = ids[i]; }
    }
    if (best === null) return -1;
    if (bestDue > now) return Math.max(1, Math.round(bestDue - now));
    var fn = g.__vegaTimers[best] && g.__vegaTimers[best].fn;
    try { if (fn) fn(); } catch (e) {}
    return 0;
  };

  function isDef(v) { return v !== undefined && v !== null; }

  // ---- commonHeaders (dist/headers.js) ----
  var commonHeaders = {};
  try {
    var rawHeaders = g.__vegaCommonHeadersJson;
    var parsed = JSON.parse(typeof rawHeaders === 'string' && rawHeaders ? rawHeaders : '{}');
    if (parsed && typeof parsed === 'object') commonHeaders = parsed;
  } catch (e) { commonHeaders = {}; }

  // ---- kvStore (persisted by the host to kv.json) ----
  var kvData = {};
  try {
    var rawKv = g.__vegaKvJson;
    var parsedKv = JSON.parse(typeof rawKv === 'string' && rawKv ? rawKv : '{}');
    if (parsedKv && typeof parsedKv === 'object') kvData = parsedKv;
  } catch (e) { kvData = {}; }

  function kvWrite(key) {
    if (typeof g.__vegaKvSet !== 'function') return;
    try {
      var has = Object.prototype.hasOwnProperty.call(kvData, key);
      g.__vegaKvSet(String(key), has ? JSON.stringify(kvData[key] === undefined ? null : kvData[key]) : '');
    } catch (e) {}
  }

  var kvStore = {
    get: function (key) {
      key = String(key);
      return Promise.resolve(Object.prototype.hasOwnProperty.call(kvData, key) ? kvData[key] : undefined);
    },
    set: function (key, value) {
      key = String(key);
      kvData[key] = value;
      kvWrite(key);
      return Promise.resolve();
    },
    'delete': function (key) {
      key = String(key);
      var had = Object.prototype.hasOwnProperty.call(kvData, key);
      if (had) {
        delete kvData[key];
        if (typeof g.__vegaKvDel === 'function') { try { g.__vegaKvDel(key); } catch (e) {} }
      }
      return Promise.resolve(had);
    },
    keys: function () { return Promise.resolve(Object.keys(kvData)); },
    clear: function () {
      kvData = {};
      if (typeof g.__vegaKvClear === 'function') { try { g.__vegaKvClear(); } catch (e) {} }
      return Promise.resolve();
    }
  };

  // ---- axios ----
  function hasHeader(headers, name) {
    var want = String(name).toLowerCase();
    for (var k in headers) if (Object.prototype.hasOwnProperty.call(headers, k)) {
      if (String(k).toLowerCase() === want) return true;
    }
    return false;
  }

  function normalizeHeaders(h) {
    var out = {};
    if (!h) return out;
    if (typeof h.forEach === 'function' && typeof h.get === 'function') {
      h.forEach(function (v, k) { if (isDef(v)) out[String(k)] = String(v); });
      return out;
    }
    if (Array.isArray(h)) {
      for (var i = 0; i < h.length; i++) {
        var e = h[i];
        if (e && e.length >= 2 && isDef(e[1])) out[String(e[0])] = String(e[1]);
      }
      return out;
    }
    for (var k in h) {
      if (!Object.prototype.hasOwnProperty.call(h, k)) continue;
      if (isDef(h[k])) out[String(k)] = String(h[k]);
    }
    return out;
  }

  function serializeParams(params) {
    var parts = [];
    function add(k, v) {
      if (!isDef(v)) return;
      if (Array.isArray(v)) { for (var i = 0; i < v.length; i++) add(k, v[i]); return; }
      if (typeof v === 'object') { try { v = JSON.stringify(v); } catch (e) { v = String(v); } }
      parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)));
    }
    for (var k in params) if (Object.prototype.hasOwnProperty.call(params, k)) add(k, params[k]);
    return parts.join('&');
  }

  function perform(config) {
    config = config || {};
    var url = String(config.url || '');
    var method = String(config.method || 'GET').toUpperCase();
    var headers = normalizeHeaders(config.headers);
    if (config.params) {
      var qs = serializeParams(config.params);
      if (qs) url += (url.indexOf('?') >= 0 ? '&' : '?') + qs;
    }
    var body;
    if (isDef(config.data) && method !== 'GET' && method !== 'HEAD') {
      body = typeof config.data === 'string' ? config.data : JSON.stringify(config.data);
      if (typeof config.data !== 'string' && !hasHeader(headers, 'Content-Type')) {
        headers['Content-Type'] = 'application/json';
      }
    }
    var init = { method: method, headers: headers };
    if (body !== undefined) init.body = body;
    if (config.signal) init.signal = config.signal;
    if (config.maxRedirects === 0) init.redirect = 'manual';
    var wantBuf = config.responseType === 'arraybuffer';
    return fetch(url, init).then(function (res) {
      var payload = (wantBuf && typeof res.arrayBuffer === 'function') ? res.arrayBuffer() : res.text();
      return Promise.resolve(payload).then(function (raw) {
        var data = raw;
        if (!wantBuf) {
          // axios' own transformResponse: JSON.parse the body and fall back to
          // the raw string when it isn't JSON (which is how an HTML page comes
          // back as a string and a JSON API as an object).
          try { data = JSON.parse(raw); } catch (e) { data = raw; }
        }
        var plain = {};
        if (res.headers && typeof res.headers.forEach === 'function') {
          res.headers.forEach(function (v, k) { plain[String(k).toLowerCase()] = v; });
        }
        var response = {
          data: data,
          status: res.status,
          statusText: res.statusText || '',
          headers: plain,
          config: config,
          request: {},
          url: res.url || url
        };
        var ok = typeof config.validateStatus === 'function'
          ? !!config.validateStatus(res.status)
          : (res.status >= 200 && res.status < 300);
        if (!ok) {
          var err = new Error('Request failed with status code ' + res.status);
          err.isAxiosError = true;
          err.response = response;
          err.config = config;
          err.code = 'ERR_BAD_STATUS';
          return Promise.reject(err);
        }
        return response;
      });
    });
  }

  function mergeConfig(defaults, config) {
    var out = {};
    var k;
    for (k in defaults) if (Object.prototype.hasOwnProperty.call(defaults, k)) out[k] = defaults[k];
    for (k in (config || {})) {
      if (!Object.prototype.hasOwnProperty.call(config, k)) continue;
      if (k === 'headers') {
        var h = {};
        var dh = (defaults && defaults.headers) || {};
        for (var a in dh) if (Object.prototype.hasOwnProperty.call(dh, a)) h[a] = dh[a];
        var ch = config.headers || {};
        for (var b in ch) if (Object.prototype.hasOwnProperty.call(ch, b)) h[b] = ch[b];
        out.headers = h;
      } else {
        out[k] = config[k];
      }
    }
    return out;
  }

  function makeInstance(defaults) {
    defaults = defaults || {};
    function run(cfg) { return perform(mergeConfig(defaults, cfg)); }
    var inst = function (cfg) { return run(cfg); };
    inst.get = function (u, c) { return run(mergeConfig({ url: u, method: 'GET' }, c)); };
    inst['delete'] = function (u, c) { return run(mergeConfig({ url: u, method: 'DELETE' }, c)); };
    inst.head = function (u, c) { return run(mergeConfig({ url: u, method: 'HEAD' }, c)); };
    inst.options = function (u, c) { return run(mergeConfig({ url: u, method: 'OPTIONS' }, c)); };
    inst.post = function (u, d, c) { return run(mergeConfig({ url: u, data: d, method: 'POST' }, c)); };
    inst.put = function (u, d, c) { return run(mergeConfig({ url: u, data: d, method: 'PUT' }, c)); };
    inst.patch = function (u, d, c) { return run(mergeConfig({ url: u, data: d, method: 'PATCH' }, c)); };
    inst.request = function (c) { return run(c); };
    inst.create = function (c) { return makeInstance(mergeConfig(defaults, c)); };
    inst.defaults = defaults;
    inst.interceptors = { request: { use: function () {} }, response: { use: function () {} } };
    inst.CancelToken = function () {};
    inst.isCancel = function () { return false; };
    inst.all = function (arr) { return Promise.all(arr); };
    inst.spread = function (cb) { return function (arr) { return cb.apply(null, arr); }; };
    inst.Axios = function () {};
    return inst;
  }

  var vegaAxios = function (configOrUrl, maybeConfig) {
    if (typeof configOrUrl === 'string') {
      return perform(mergeConfig({ url: configOrUrl, method: (maybeConfig && maybeConfig.method) || 'GET' }, maybeConfig));
    }
    return perform(configOrUrl || {});
  };
  var baseInstance = makeInstance({ headers: { common: {}, get: {}, post: {}, put: {}, delete: {} } });
  for (var mk in baseInstance) if (Object.prototype.hasOwnProperty.call(baseInstance, mk)) vegaAxios[mk] = baseInstance[mk];
  vegaAxios.default = vegaAxios;
  vegaAxios.defaults = baseInstance.defaults;

  // ---- openWebView ----
  function openWebView(url, opts) {
    return Promise.resolve({
      success: false,
      url: String(url || ''),
      error: 'interactive web view is not available in this engine'
    });
  }

  // ---- cheerio ----
  var cheerio = null;
  try {
    if (typeof g.__nuvioRequire === 'function') cheerio = g.__nuvioRequire('cheerio');
  } catch (e) {}
  if (!cheerio) cheerio = g.__nuvioCheerio || null;
  if (cheerio && !cheerio.load && cheerio.default) cheerio = cheerio.default;

  // ---- providerContext ----
  g.__vegaProviderContext = {
    axios: vegaAxios,
    cheerio: cheerio,
    commonHeaders: commonHeaders,
    kvStore: kvStore,
    openWebView: openWebView,
    providerGlobal: g.providerGlobal
  };

  // ---- module calling ----
  // The provider module itself is loaded by the host (isolated inside a
  // function wrapper and bytecode-cached), which leaves its exports on
  // `globalThis.__vegaExports`.
  function vegaExports() {
    if (g.__vegaExports) return g.__vegaExports;
    if (g.__vegaModule && g.__vegaModule.exports) return g.__vegaModule.exports;
    return {};
  }

  function pickFunction(name) {
    var ex = vegaExports();
    if (ex && typeof ex[name] === 'function') return ex[name];
    if (ex && ex['default'] && typeof ex['default'][name] === 'function') return ex['default'][name];
    return null;
  }

  /** Pure-data read of catalog.js (its exports are arrays, never functions). */
  g.__vegaCatalogJson = function () {
    var ex = vegaExports();
    var catalog = ex && Array.isArray(ex.catalog) ? ex.catalog : [];
    var genres = ex && Array.isArray(ex.genres) ? ex.genres : [];
    return JSON.stringify({ catalog: catalog, genres: genres });
  };

  function describeError(e) {
    if (e === undefined || e === null) return 'unknown error';
    if (typeof e === 'string') return e;
    var msg = e.message ? String(e.message) : String(e);
    if (e.response && e.response.status) {
      msg = 'HTTP ' + e.response.status +
        (e.response.statusText ? ' ' + e.response.statusText : '') +
        (msg ? ' — ' + msg : '');
    }
    return msg;
  }

  function done(out, res, err) {
    if (out.sent) return;
    out.sent = true;
    var payload;
    if (err !== null && err !== undefined) {
      payload = JSON.stringify({ ok: false, error: describeError(err).slice(0, 400) });
    } else if (res === undefined || res === null) {
      payload = JSON.stringify({ ok: true, data: null });
    } else {
      var text = null;
      try { text = JSON.stringify(res); } catch (e) { text = null; }
      if (text === undefined || text === null) {
        payload = JSON.stringify({ ok: false, error: 'provider result could not be serialized' });
      } else {
        payload = '{"ok":true,"data":' + text + '}';
      }
    }
    try { g.__vegDone(payload); } catch (e) {}
  }

  g.__vegaCall = function (fnName, argsJson) {
    var out = { sent: false };
    try {
      var fn = pickFunction(String(fnName));
      if (!fn) { done(out, null, new Error('provider does not export ' + fnName)); return; }
      var args = JSON.parse(argsJson || '{}');
      args.providerContext = g.__vegaProviderContext;
      if (g.__vegaProviderValue !== undefined) args.providerValue = g.__vegaProviderValue;
      Promise.resolve()
        .then(function () { return fn(args); })
        .then(function (r) { done(out, r, null); }, function (e) { done(out, null, e); });
    } catch (e) {
      done(out, null, e);
    }
  };

  /* Runs ONE exported function once per entry of an array of argument objects,
   * sequentially, and answers the array of results. Used for episodes.js: a
   * season list is several `getEpisodes({url})` calls, and paying for one fresh
   * engine per season would be wasteful. A season whose call throws contributes
   * null rather than failing the whole list. */
  g.__vegaCallMany = function (fnName, argsArrayJson) {
    var out = { sent: false };
    try {
      var fn = pickFunction(String(fnName));
      if (!fn) { done(out, null, new Error('provider does not export ' + fnName)); return; }
      var list = JSON.parse(argsArrayJson || '[]');
      if (!Array.isArray(list)) list = [];
      var results = new Array(list.length);
      var chain = Promise.resolve();
      list.forEach(function (a, i) {
        chain = chain.then(function () {
          var args = a || {};
          args.providerContext = g.__vegaProviderContext;
          if (g.__vegaProviderValue !== undefined) args.providerValue = g.__vegaProviderValue;
          return Promise.resolve()
            .then(function () { return fn(args); })
            .then(function (r) { results[i] = (r === undefined) ? null : r; },
              function (e) { results[i] = null; });
        });
      });
      chain.then(function () { done(out, results, null); }, function (e) { done(out, null, e); });
    } catch (e) {
      done(out, null, e);
    }
  };

  if (typeof console !== 'undefined' && console.log) console.log('[vega] harness ready');
})();
