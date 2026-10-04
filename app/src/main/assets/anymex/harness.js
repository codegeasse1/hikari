/* HIKARI anymex (mangayomi-format) runtime harness.
 *
 * Mangayomi JavaScript extensions are single scripts shaped like:
 *
 *   const mangayomiSources = [{name, lang, baseUrl, apiUrl, iconUrl,
 *     typeSource, isManga, version, ...}];
 *   class DefaultExtension extends MProvider {
 *     async getPopular(page) { ... }
 *     async getLatestUpdates(page) { ... }
 *     async search(query, page, filters) { ... }
 *     async getDetail(url) { ... }            // -> {name, description, ..., chapters:[{name,url}]}
 *     async getVideoList(url) { ... }         // anime: [{url, quality, subtitles?}]
 *     async getPageList(url) { ... }          // manga: [imageUrl, ...]
 *   }
 *
 * The host contract the scripts are written against:
 *   MProvider            — base class; the instance reads `this.source`
 *   Client               — new Client().get/post(url, headers, body)
 *                          -> {body, code, headers, url}
 *   Document             — new Document(html); select()/selectFirst() with
 *                          .text / .getSrc / .getHref / .className / .attr()
 *   SharedPreferences    — new SharedPreferences().get(key) (persisted by host)
 *   String.substringAfter / .substringBefore (Kotlin-style helpers mangayomi adds)
 *
 * Loaded AFTER nuvio/boot.js, nuvio/cheerio.js and nuvio/harness.js, so the
 * polyfills, cheerio and the global fetch (over Hikari's OkHttp bridge) are
 * already in place.
 */
(function () {
  'use strict';
  var g = globalThis;

  function isDef(v) { return v !== undefined && v !== null; }

  // ---- timers (QuickJS ships none) ----
  if (typeof g.setTimeout !== 'function') {
    g.__anymexTimers = {};
    g.__anymexTimerSeq = 0;
    g.setTimeout = function (callback, delay) {
      var id = 't_' + (++g.__anymexTimerSeq);
      g.__anymexTimers[id] = {
        due: Date.now() + (Number(delay) || 0),
        fn: function () {
          if (!g.__anymexTimers[id]) return;
          delete g.__anymexTimers[id];
          try { callback(); } catch (e) { try { console.error('Timeout error:', e); } catch (e2) {} }
        }
      };
      return id;
    };
    g.clearTimeout = function (id) { if (id) delete g.__anymexTimers[id]; };
    g.setInterval = function (callback, delay) {
      var id = 'i_' + (++g.__anymexTimerSeq);
      var period = Number(delay) || 0;
      g.__anymexTimers[id] = {
        due: Date.now() + period,
        fn: function () {
          if (!g.__anymexTimers[id]) return;
          try { callback(); } catch (e) { try { console.error('Interval error:', e); } catch (e2) {} }
          var t = g.__anymexTimers[id];
          if (t) t.due = Date.now() + period;
        }
      };
      return id;
    };
    g.clearInterval = g.clearTimeout;
  }

  g.__anymexFireTimer = function () {
    var ids = Object.keys(g.__anymexTimers || {});
    if (!ids.length) return -1;
    var now = Date.now();
    var best = null;
    var bestDue = Infinity;
    for (var i = 0; i < ids.length; i++) {
      var t = g.__anymexTimers[ids[i]];
      if (!t) continue;
      if (t.due < bestDue) { bestDue = t.due; best = ids[i]; }
    }
    if (best === null) return -1;
    if (bestDue > now) return Math.max(1, Math.round(bestDue - now));
    var fn = g.__anymexTimers[best] && g.__anymexTimers[best].fn;
    try { if (fn) fn(); } catch (e) {}
    return 0;
  };

  if (typeof g.console === 'undefined') {
    g.console = {
      log: function (m) { try { g.__anymexLog(String(m)); } catch (e) {} },
      error: function (m) { try { g.__anymexLog('ERR: ' + String(m)); } catch (e) {} },
      warn: function (m) { try { g.__anymexLog('WARN: ' + String(m)); } catch (e) {} }
    };
  }

  // ---- Kotlin-style string helpers (mangayomi defines these) ----
  if (typeof String.prototype.substringAfter !== 'function') {
    String.prototype.substringAfter = function (delimiter, missing) {
      var s = String(this);
      var i = s.indexOf(String(delimiter));
      if (i === -1) return missing === undefined ? s : missing;
      return s.substring(i + String(delimiter).length);
    };
  }
  if (typeof String.prototype.substringBefore !== 'function') {
    String.prototype.substringBefore = function (delimiter, missing) {
      var s = String(this);
      var i = s.indexOf(String(delimiter));
      if (i === -1) return missing === undefined ? s : missing;
      return s.substring(0, i);
    };
  }
  if (typeof String.prototype.substringAfterLast !== 'function') {
    String.prototype.substringAfterLast = function (delimiter, missing) {
      var s = String(this);
      var i = s.lastIndexOf(String(delimiter));
      if (i === -1) return missing === undefined ? s : missing;
      return s.substring(i + String(delimiter).length);
    };
  }
  if (typeof String.prototype.substringBeforeLast !== 'function') {
    String.prototype.substringBeforeLast = function (delimiter, missing) {
      var s = String(this);
      var i = s.lastIndexOf(String(delimiter));
      if (i === -1) return missing === undefined ? s : missing;
      return s.substring(0, i);
    };
  }
  if (typeof String.prototype.substringBetween !== 'function') {
    String.prototype.substringBetween = function (left, right) {
      var s = String(this);
      var a = s.indexOf(String(left));
      if (a === -1) return '';
      a += String(left).length;
      var b = s.indexOf(String(right), a);
      if (b === -1) return '';
      return s.substring(a, b);
    };
  }

  // ---- cheerio ----
  var cheerio = null;
  try {
    if (typeof g.__nuvioRequire === 'function') cheerio = g.__nuvioRequire('cheerio');
  } catch (e) {}
  if (!cheerio) cheerio = g.__nuvioCheerio || null;
  if (cheerio && !cheerio.load && cheerio.default) cheerio = cheerio.default;
  try {
    if (typeof g.__hikariMakeCheerioIterable === 'function') g.__hikariMakeCheerioIterable(cheerio);
  } catch (e) {}

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

  // ---- Client ----
  // Per-call fetch accounting: when a catalogue/search call ends with an
  // EMPTY list, the caller can tell "the site answered fine but listed
  // nothing" (end of pages, no results) apart from "every request failed"
  // (dead site, moved paths, blocking) and say so honestly instead of the
  // generic "no titles" line. Reset at the start of every __anymexCall.
  g.__anymexFetchStats = { ok: 0, fail: 0, lastStatus: 0, deadSite: false };
  var DEAD_SITE_RE = /account suspended|account.*(suspended|terminated)|domain.*(parked|for sale|expired)|this site can.t be reached|404 not found/i;
  function noteFetch(status, bodyText) {
    try {
      if (status >= 200 && status < 300) { g.__anymexFetchStats.ok++; }
      else {
        g.__anymexFetchStats.fail++;
        if (status) g.__anymexFetchStats.lastStatus = status;
      }
      if (bodyText && DEAD_SITE_RE.test(String(bodyText).slice(0, 4000))) {
        g.__anymexFetchStats.deadSite = true;
      }
    } catch (e) {}
  }
  function noteFetchError() {
    try { g.__anymexFetchStats.fail++; } catch (e) {}
  }
  function describeFetchFailure() {
    try {
      var st = g.__anymexFetchStats || { ok: 0, fail: 0, lastStatus: 0, deadSite: false };
      if (st.ok > 0 || st.fail === 0) return null;
      if (st.deadSite) {
        return 'the site itself looks dead — its pages say the account is suspended or the domain is parked/expired';
      }
      if (st.lastStatus) {
        return 'the site answered HTTP ' + st.lastStatus + ' to every request — its pages may have moved, or it blocks the app';
      }
      return 'every request to the site failed — it may be down, moved, or blocking the app';
    } catch (e) { return null; }
  }
  function doRequest(method, url, headers, body) {
    var init = { method: method, headers: normHeaders(headers) };
    if (isDef(body) && method !== 'GET' && method !== 'HEAD') {
      if (typeof body === 'object') {
        var ct = init.headers['content-type'] || init.headers['Content-Type'] || '';
        if (/json/i.test(ct)) {
          try { body = JSON.stringify(body); } catch (e) { body = String(body); }
        } else {
          var parts = [];
          for (var k in body) {
            if (!Object.prototype.hasOwnProperty.call(body, k)) continue;
            var v = body[k];
            if (v === undefined || v === null) continue;
            if (typeof v === 'object') { try { v = JSON.stringify(v); } catch (e2) { v = String(v); } }
            parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)));
          }
          body = parts.join('&');
          if (!ct) init.headers['content-type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
        }
      }
      init.body = body;
    }
    return fetch(String(url), init).then(function (res) {
      return res.text().then(function (text) {
        var plain = {};
        try {
          if (res.headers && typeof res.headers.forEach === 'function') {
            res.headers.forEach(function (v, k) { plain[String(k).toLowerCase()] = v; });
          }
        } catch (e) {}
        try { noteFetch(res.status, text); } catch (e2) {}
        return { body: text, code: res.status, status: res.status, headers: plain, url: res.url || String(url) };
      }, function (e) {
        try { noteFetchError(); } catch (e2) {}
        throw e;
      });
    }, function (e) {
      try { noteFetchError(); } catch (e2) {}
      throw e;
    });
  }

  g.Client = function (headers) {
    this.defaultHeaders = normHeaders(headers);
  };
  g.Client.prototype.withHeaders = function (h) {
    var c = new g.Client(this.defaultHeaders);
    var extra = normHeaders(h);
    for (var k in extra) c.defaultHeaders[k] = extra[k];
    return c;
  };
  ['get', 'post', 'put', 'delete', 'patch', 'head'].forEach(function (m) {
    g.Client.prototype[m] = function (url, headers, body) {
      var merged = {};
      var d = this.defaultHeaders || {};
      for (var a in d) merged[a] = d[a];
      try {
        var xh = g.__anymexExtHeaders || {};
        for (var x in xh) {
          if (!Object.prototype.hasOwnProperty.call(xh, x)) continue;
          var clash = false;
          for (var mk in merged) {
            if (String(mk).toLowerCase() === String(x).toLowerCase()) { clash = true; break; }
          }
          if (!clash) merged[x] = xh[x];
        }
      } catch (e) {}
      var e = normHeaders(headers);
      for (var b in e) merged[b] = e[b];
      return doRequest(m.toUpperCase(), url, merged, body);
    };
  });

  // ---- Document ----
  function wrap(selection) {
    return new DomElement(selection);
  }

  function DomElement(sel) {
    this._sel = sel;
  }

  DomElement.prototype.first = function () {
    try { return wrap(this._sel.first()); } catch (e) { return wrap(null); }
  };

  Object.defineProperty(DomElement.prototype, 'text', {
    get: function () {
      try {
        var t = this._sel ? this._sel.text() : '';
        return typeof t === 'string' ? t.trim() : '';
      } catch (e) { return ''; }
    }
  });

  // Lazy-load aware, data-first: sites like animeonline.ninja serve a blank
  // SVG data: placeholder in src and keep the real URL in data-src. Mangayomi
  // extensions read data-src first for the same reason — src-first returned
  // the placeholder and every poster came up blank. data: values are never
  // real posters, so they are skipped wherever they sit.
  function pickImg(sel) {
    try {
      if (!sel || !sel.length) return null;
      var attrs = ['data-src', 'data-lazy-src', 'data-original', 'data-srcset', 'srcset', 'src'];
      for (var i = 0; i < attrs.length; i++) {
        var v = sel.attr(String(attrs[i]));
        if (v === undefined || v === null) continue;
        v = String(v).trim();
        if (!v || v.indexOf('data:') === 0) continue;
        if (attrs[i] === 'srcset' || attrs[i] === 'data-srcset') {
          v = v.split(',')[0].trim().split(' ')[0].trim();
          if (!v || v.indexOf('data:') === 0) continue;
        }
        return v;
      }
    } catch (e) {}
    return null;
  }

  Object.defineProperty(DomElement.prototype, 'getSrc', {
    get: function () { return pickImg(this._sel); }
  });

  Object.defineProperty(DomElement.prototype, 'getHref', {
    get: function () { return this.attr('href'); }
  });

  Object.defineProperty(DomElement.prototype, 'className', {
    get: function () { return this.attr('class') || ''; }
  });

  DomElement.prototype.attr = function (name) {
    try {
      if (!this._sel || !this._sel.length) return null;
      var key = String(name);
      var v = this._sel.attr(key);
      // Lazy-load aware `src`: themes that defer images leave src empty or a
      // data: placeholder and keep the real URL in data-src — returning that
      // raw would blank every poster, so fall through the same chain getSrc
      // uses. A data:-only result is returned as-is; the Kotlin side already
      // refuses data: art, so it degrades to "no poster", never a blank tile.
      if (key.toLowerCase() === 'src' &&
          (v === undefined || v === null || v === '' ||
           (typeof v === 'string' && v.indexOf('data:') === 0))) {
        var lazy = pickImg(this._sel);
        if (lazy !== undefined && lazy !== null && lazy !== '') v = lazy;
      }
      return v === undefined ? null : v;
    } catch (e) { return null; }
  };

  DomElement.prototype.html = function () {
    try { return this._sel ? (this._sel.html() || '') : ''; } catch (e) { return ''; }
  };

  DomElement.prototype.outerHtml = function () {
    try {
      if (!this._sel || !cheerio) return '';
      if (typeof cheerio.html === 'function') return cheerio.html(this._sel) || '';
      return this._sel.toString();
    } catch (e) { return ''; }
  };

  DomElement.prototype.select = function (css) {
    var out = [];
    try {
      if (!this._sel || !cheerio) return out;
      var found = this._sel.find ? this._sel.find(String(css)) : cheerio(String(css), this._sel);
      found.each(function (i, el) { out.push(wrap(cheerio(el))); });
    } catch (e) {}
    return out;
  };

  DomElement.prototype.selectFirst = function (css) {
    try {
      if (!this._sel || !cheerio) return null;
      var found = this._sel.find ? this._sel.find(String(css)) : cheerio(String(css), this._sel);
      if (!found || !found.length) return null;
      return wrap(found.first());
    } catch (e) { return null; }
  };

  function wrapAll(found) {
    var out = [];
    try {
      if (!found) return out;
      found.each(function (i, el) { out.push(wrap(cheerio(el))); });
    } catch (e) {}
    return out;
  }

  DomElement.prototype.getElementsByClassName = function (name) {
    try {
      if (!this._sel || !cheerio || !this._sel.find) return [];
      return wrapAll(this._sel.find('.' + String(name)));
    } catch (e) { return []; }
  };

  DomElement.prototype.getElementsByTagName = function (name) {
    try {
      if (!this._sel || !cheerio || !this._sel.find) return [];
      return wrapAll(this._sel.find(String(name)));
    } catch (e) { return []; }
  };

  Object.defineProperty(DomElement.prototype, 'children', {
    get: function () {
      try {
        if (!this._sel || !cheerio || !this._sel.children) return [];
        return wrapAll(this._sel.children());
      } catch (e) { return []; }
    }
  });

  Object.defineProperty(DomElement.prototype, 'previousElementSibling', {
    get: function () {
      try {
        if (!this._sel || !cheerio) return null;
        var p = this._sel.prev();
        if (!p || !p.length) return null;
        return wrap(p.first());
      } catch (e) { return null; }
    }
  });

  Object.defineProperty(DomElement.prototype, 'nextElementSibling', {
    get: function () {
      try {
        if (!this._sel || !cheerio) return null;
        var n = this._sel.next();
        if (!n || !n.length) return null;
        return wrap(n.first());
      } catch (e) { return null; }
    }
  });

  DomElement.prototype.hasAttr = function (name) {
    try {
      return this.attr(String(name)) !== null;
    } catch (e) { return false; }
  };

  Object.defineProperty(DomElement.prototype, 'getImg', {
    get: function () { return pickImg(this._sel); }
  });

  Object.defineProperty(DomElement.prototype, 'getDataSrc', {
    get: function () { return this.attr('data-src') || null; }
  });

  Object.defineProperty(DomElement.prototype, 'innerHtml', {
    get: function () {
      try { return this._sel ? (this._sel.html() || '') : ''; } catch (e) { return ''; }
    }
  });

  Object.defineProperty(DomElement.prototype, 'outerHtml', {
    get: function () {
      try {
        if (!this._sel || !cheerio) return '';
        if (typeof cheerio.html === 'function') return cheerio.html(this._sel) || '';
        return this._sel.toString();
      } catch (e) { return ''; }
    }
  });

  Object.defineProperty(DomElement.prototype, 'localName', {
    get: function () {
      try {
        if (!this._sel) return '';
        if (typeof this._sel.prop === 'function') {
          var t = this._sel.prop('tagName');
          if (typeof t === 'string' && t) return t.toLowerCase();
        }
        return '';
      } catch (e) { return ''; }
    }
  });

  g.Document = function (html) {
    var root;
    try { root = cheerio ? cheerio.load(String(html || '')) : null; }
    catch (e) { root = null; }
    var doc = wrap(null);
    doc._root = root;
    doc.select = function (css) {
      var out = [];
      try {
        if (!root) return out;
        root(String(css)).each(function (i, el) { out.push(wrap(root(el))); });
      } catch (e) {}
      return out;
    };
    doc.selectFirst = function (css) {
      try {
        if (!root) return null;
        var found = root(String(css));
        if (!found || !found.length) return null;
        return wrap(found.first());
      } catch (e) { return null; }
    };
    doc.getElementsByClassName = function (name) {
      try {
        if (!root) return [];
        return wrapAll(root('.' + String(name)));
      } catch (e) { return []; }
    };
    doc.getElementsByTagName = function (name) {
      try {
        if (!root) return [];
        return wrapAll(root(String(name)));
      } catch (e) { return []; }
    };
    doc.getElementById = function (id) {
      try {
        if (!root) return null;
        var found = root('[id="' + String(id).replace(/"/g, '') + '"]');
        if (!found || !found.length) return null;
        return wrap(found.first());
      } catch (e) { return null; }
    };
    Object.defineProperty(doc, 'body', {
      get: function () {
        try {
          if (!root) return null;
          var b = root('body');
          if (!b || !b.length) return null;
          return wrap(b.first());
        } catch (e) { return null; }
      }
    });
    Object.defineProperty(doc, 'head', {
      get: function () {
        try {
          if (!root) return null;
          var h = root('head');
          if (!h || !h.length) return null;
          return wrap(h.first());
        } catch (e) { return null; }
      }
    });
    return doc;
  };

  // ---- SharedPreferences (persisted by the host to kv.json) ----
  var prefData = {};
  try {
    var rawPrefs = g.__anymexKvJson;
    var parsedPrefs = JSON.parse(typeof rawPrefs === 'string' && rawPrefs ? rawPrefs : '{}');
    if (parsedPrefs && typeof parsedPrefs === 'object') prefData = parsedPrefs;
  } catch (e) { prefData = {}; }

  function prefWrite(key) {
    if (typeof g.__anymexKvSet !== 'function') return;
    try {
      var has = Object.prototype.hasOwnProperty.call(prefData, key);
      g.__anymexKvSet(String(key), has ? JSON.stringify(prefData[key] === undefined ? null : prefData[key]) : '');
    } catch (e) {}
  }

  g.SharedPreferences = function () {};
  g.SharedPreferences.prototype.get = function (key, def) {
    key = String(key);
    if (Object.prototype.hasOwnProperty.call(prefData, key)) return prefData[key];
    // MangaDex: getPreference("original_languages", []) — second arg is default.
    return arguments.length > 1 ? def : '';
  };
  g.SharedPreferences.prototype.getString = g.SharedPreferences.prototype.get;
  g.SharedPreferences.prototype.set = function (key, value) {
    prefData[String(key)] = value;
    prefWrite(String(key));
    return Promise.resolve();
  };
  g.SharedPreferences.prototype.setString = g.SharedPreferences.prototype.set;

  // ---- MProvider base ----
  g.MProvider = function (source) {
    var host = {};
    try { host = JSON.parse(g.__anymexHostMeta || '{}'); } catch (e) {}
    if (!host || typeof host !== 'object') host = {};
    this.source = Object.assign({}, host, source || {});
    // Many scripts read SharedPreferences("url") / ("lang") instead of
    // this.source.baseUrl — seed them so getPopular/search actually hit the
    // site (otherwise requests go to "undefined/undefined/...").
    try {
      var s = this.source || {};
      var base = String(s.baseUrl || s.url || host.baseUrl || '').replace(/\/+$/, '');
      var lang = String(s.lang || host.lang || '').trim();
      if (base) {
        // If base already ends with /en, /all, etc. split for scripts that
        // join url + "/" + lang + path.
        var m = base.match(/^(https?:\/\/[^\/]+)(?:\/([a-zA-Z0-9_-]{2,8}))?$/);
        if (m) {
          if (!Object.prototype.hasOwnProperty.call(prefData, 'url') || !prefData['url']) {
            prefData['url'] = m[1];
          }
          if ((!lang || lang === 'all') && m[2]) lang = m[2];
        } else if (!Object.prototype.hasOwnProperty.call(prefData, 'url') || !prefData['url']) {
          prefData['url'] = base;
        }
      }
      if (!lang) lang = 'en';
      this.source.lang = lang;
      if (lang && (!Object.prototype.hasOwnProperty.call(prefData, 'lang') || !prefData['lang'])) {
        prefData['lang'] = lang;
      }
      // MangaDex & friends need apiUrl on this.source
      if (!this.source.apiUrl && host.apiUrl) this.source.apiUrl = host.apiUrl;
      if (!this.source.baseUrl && host.baseUrl) this.source.baseUrl = host.baseUrl;
      // Also expose common keys scripts read.
      if (s.name && !prefData['name']) prefData['name'] = s.name;
      if (base && !prefData['baseUrl']) prefData['baseUrl'] = base;
    } catch (e) {}
  };

  g.MProvider.prototype.getPreference = function (key) {
    try {
      var sp = new g.SharedPreferences();
      var v = sp.get(String(key));
      if (v !== undefined && v !== null && v !== '') return v;
      // Sensible defaults used by MangaDex etc.
      if (String(key) === 'custom_user_agent') {
        return 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
      }
      return '';
    } catch (e) { return ''; }
  };

  // Any top-level const foo = [{name, baseUrl, ...}] declares the source.
  // Repos name it freely (mangayomiSources, kegaretaSauces, ...), so the first
  // array whose item looks like a source entry wins, then the host listing.
  // NOTE: `const`/`let` at top level do NOT become properties of globalThis —
  // Object.getOwnPropertyNames misses them entirely — but they ARE visible by
  // name through the scope chain, so the known spellings are probed with
  // eval/typeof as well (a missing name never throws this way).
  function declaredSource() {
    // Standard name first (see the lexical-binding note on extension()).
    try {
      var std = bareGlobal('mangayomiSources');
      if (Array.isArray(std) && std.length) {
        var src = Object.assign({}, std[0]);
        // Multi-lang packages omit singular lang — default en so API queries work.
        if (!src.lang && Array.isArray(src.langs) && src.langs.length) {
          src.lang = src.langs.indexOf('en') >= 0 ? 'en' : src.langs[0];
        }
        if (!src.lang) src.lang = 'en';
        return src;
      }
    } catch (e) {}
    try {
      if (Array.isArray(g.mangayomiSources) && g.mangayomiSources.length) {
        var srcG = Object.assign({}, g.mangayomiSources[0]);
        // Multi-lang packages omit singular lang — default en so API queries work.
        if (!srcG.lang && Array.isArray(srcG.langs) && srcG.langs.length) {
          srcG.lang = srcG.langs.indexOf('en') >= 0 ? 'en' : srcG.langs[0];
        }
        if (!srcG.lang) srcG.lang = 'en';
        return srcG;
      }
    } catch (e) {}
    try {
      var lexNames = ['kegaretaSauces', 'animeSources', 'mangaSources', 'animeSauces', 'mangaSauces', 'sources'];
      for (var li = 0; li < lexNames.length; li++) {
        var found = null;
        try {
          if (eval('typeof ' + lexNames[li]) !== 'undefined') found = eval(lexNames[li]);
        } catch (eLex) { found = null; }
        if (Array.isArray(found) && found.length && found[0] && typeof found[0] === 'object' &&
            (found[0].baseUrl || found[0].name)) {
          var lex = Object.assign({}, found[0]);
          if (!lex.lang) lex.lang = 'en';
          return lex;
        }
      }
    } catch (e) {}
    try {
      var keys = Object.getOwnPropertyNames(g);
      for (var i = 0; i < keys.length; i++) {
        var v = g[keys[i]];
        if (Array.isArray(v) && v.length && v[0] && typeof v[0] === 'object' &&
            (v[0].baseUrl || v[0].name)) return v[0];
      }
    } catch (e) {}
    try { return JSON.parse(g.__anymexHostMeta || '{}'); } catch (e2) { return {}; }
  }

  // ---- extension instance ----
  // NOTE: a top-level `class DefaultExtension` / `const mangayomiSources` does
  // NOT become a property of globalThis (lexical bindings live in the global
  // declarative record, not on the global object), so `globalThis.X` lookups
  // and Object.getOwnPropertyNames scans miss EVERY normally-written module.
  // The bare NAME still resolves through the scope chain, so it is read with
  // eval (a missing name throws ReferenceError, caught below) before falling
  // back to the globalThis property (for `var`-declared or assigned shapes).
  function bareGlobal(name) {
    try {
      if (eval('typeof ' + name) === 'undefined') return undefined;
      return eval(name);
    } catch (e) { return undefined; }
  }
  function extension() {
    if (g.__anymexExt) return g.__anymexExt;
    try {
      var Ctor = bareGlobal('DefaultExtension');
      if (typeof Ctor !== 'function') Ctor = g.DefaultExtension;
      if (typeof Ctor !== 'function') return null;
      g.__anymexExt = new Ctor(declaredSource() || {});
      return g.__anymexExt;
    } catch (e) { return null; }
  }

  g.__anymexExtGet = extension;

  g.__anymexSourceMeta = function () {
    try {
      var s = declaredSource() || {};
      var Ctor = bareGlobal('DefaultExtension');
      if (typeof Ctor !== 'function') Ctor = g.DefaultExtension;
      if (typeof Ctor === 'function') {
        var cap = null;
        try {
          var probe = new Ctor(s);
          var hasVideo = typeof probe.getVideoList === 'function';
          var hasPages = typeof probe.getPageList === 'function';
          if (hasVideo || hasPages) cap = !hasVideo && hasPages;
        } catch (e) {}
        return JSON.stringify({ ok: true, isManga: cap != null ? cap : !!s.isManga, name: s.name || '' });
      }
    } catch (e) {}
    return JSON.stringify({ ok: false });
  };

  g.parseDates = function (value, format, locale) {
    try {
      var tt = Date.parse(String(value));
      if (!isNaN(tt)) return tt;
    } catch (e) {}
    return 0;
  };

  function extractViaHost(url, quality) {
    if (typeof g.__anymexExtract !== 'function') return Promise.resolve([]);
    return g.__anymexExtract(String(url == null ? '' : url), String(quality == null ? '' : quality))
      .then(function (raw) {
        try { var pp = JSON.parse(raw); return Array.isArray(pp) ? pp : []; }
        catch (e) { return []; }
      }, function () { return []; });
  }
  var __anymexExtractorNames = ['sibnetExtractor', 'myTvExtractor', 'okruExtractor',
    'voeExtractor', 'vidBomExtractor', 'streamlareExtractor', 'sendVidExtractor',
    'yourUploadExtractor', 'gogoCdnExtractor', 'doodExtractor', 'streamTapeExtractor',
    'mp4UploadExtractor', 'streamWishExtractor', 'filemoonExtractor',
    'quarkVideosExtractor', 'ucVideosExtractor', 'quarkFilesExtractor', 'ucFilesExtractor',
    'Mp4UploadExtractor', 'MP4UploadExtractor', 'StreamWishExtractor', 'FilemoonExtractor',
    'DoodExtractor', 'StreamTapeExtractor', 'VoeExtractor', 'OkruExtractor'];
  __anymexExtractorNames.forEach(function (name) {
    if (typeof g[name] !== 'function') {
      g[name] = function (url, quality) { return extractViaHost(url, quality); };
    }
  });
  // Some scripts call constructors by short name (e.g. new MP4Upload()).
  if (typeof g.MP4Upload !== 'function') {
    g.MP4Upload = function () {};
    g.MP4Upload.prototype.extract = function (url, quality) { return extractViaHost(url, quality); };
  }
  if (typeof g.Mp4Upload !== 'function') g.Mp4Upload = g.MP4Upload;

  function describeError(e) {
    if (e === undefined || e === null) return 'unknown error';
    if (typeof e === 'string') return e;
    return e.message ? String(e.message) : String(e);
  }

  function done(payload) {
    try { g.__anymexDone(typeof payload === 'string' ? payload : JSON.stringify(payload)); } catch (e) {}
  }

  // Calls one DefaultExtension method with the given JSON arg array. List and
  // detail answers are plain objects; both land as data either way.
  g.__anymexCall = function (fnName, argsJson) {
    try {
      try { g.__anymexFetchStats = { ok: 0, fail: 0, lastStatus: 0, deadSite: false }; } catch (e0) {}
      var ext = extension();
      if (!ext) { done({ ok: false, error: 'module does not define DefaultExtension' }); return; }
      var fn = ext[String(fnName)];
      if (typeof fn !== 'function') { done({ ok: false, error: 'module does not implement ' + fnName }); return; }
      var args = JSON.parse(argsJson || '[]');
      if (!Array.isArray(args)) args = [args];
      // Upstream parity (AnymeXExtensionRuntimeBridge DartExtensionService):
      // an extension may declare its own request headers (Referer, custom UA,
      // Cloudflare-cookie helpers) via headers() or getHeader(baseUrl). Those
      // used to be silently dropped, so every request a header-dependent site
      // got carried only the defaults and its catalog came back empty.
      function extBaseUrl() {
        try {
          var s = (ext && ext.source) || {};
          return String(s.baseUrl || s.url || '');
        } catch (e) { return ''; }
      }
      function collectExtHeaders() {
        try {
          var f = null;
          if (ext && typeof ext.headers === 'function') f = ext.headers;
          else if (ext && typeof ext.getHeader === 'function') {
            f = function () { return ext.getHeader(extBaseUrl()); };
          }
          if (!f) return null;
          return Promise.resolve(f.call(ext)).then(function (h) {
            try { g.__anymexExtHeaders = normHeaders(h); } catch (e) {}
            return null;
          }, function () { return null; });
        } catch (e) { return null; }
      }
      Promise.resolve()
        .then(function () { return collectExtHeaders(); })
        .then(function () { return fn.apply(ext, args); })
        .then(function (r) {
          if (r === undefined || r === null) { done({ ok: true, data: null }); return; }
          // An empty catalogue/search answer after nothing but failed fetches
          // is not "no titles" — it is a dead/moved/blocking site. Say so
          // while the evidence is still here; the Kotlin side only sees the
          // final JSON. Lists that fetched fine (or never fetched) pass
          // through untouched, so genuine end-of-pages stays quiet.
          try {
            var catalogFn = (fnName === 'getPopular' || fnName === 'getLatestUpdates' || fnName === 'search');
            var emptyList = (r && !Array.isArray(r) && Array.isArray(r.list) && r.list.length === 0) ||
              (Array.isArray(r) && r.length === 0);
            if (catalogFn && emptyList) {
              var why = describeFetchFailure();
              if (why) { done({ ok: false, error: why }); return; }
            }
          } catch (e5) {}
          if (typeof r === 'string') {
            var t = r.trim();
            if (t.charAt(0) === '{' || t.charAt(0) === '[') {
              try { done({ ok: true, data: JSON.parse(t) }); return; } catch (e) {}
            }
            done({ ok: true, data: r });
            return;
          }
          try { done({ ok: true, data: JSON.parse(JSON.stringify(r)) }); }
          catch (e) { done({ ok: false, error: 'module result could not be serialized' }); }
        }, function (e) { done({ ok: false, error: describeError(e).slice(0, 400) }); });
    } catch (e) {
      done({ ok: false, error: describeError(e).slice(0, 400) });
    }
  };

  if (typeof console !== 'undefined' && console.log) console.log('[anymex] harness ready');
})();
