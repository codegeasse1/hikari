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
  function doRequest(method, url, headers, body) {
    var init = { method: method, headers: normHeaders(headers) };
    if (isDef(body) && method !== 'GET' && method !== 'HEAD') init.body = body;
    return fetch(String(url), init).then(function (res) {
      return res.text().then(function (text) {
        var plain = {};
        try {
          if (res.headers && typeof res.headers.forEach === 'function') {
            res.headers.forEach(function (v, k) { plain[String(k).toLowerCase()] = v; });
          }
        } catch (e) {}
        return { body: text, code: res.status, status: res.status, headers: plain, url: res.url || String(url) };
      });
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

  Object.defineProperty(DomElement.prototype, 'getSrc', {
    get: function () { return this.attr('src') || this.attr('data-src') || this.attr('data-lazy-src') || this.attr('data-original') || null; }
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
      var v = this._sel.attr(String(name));
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
    get: function () { return this.attr('src') || this.attr('data-src') || null; }
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
  g.SharedPreferences.prototype.get = function (key) {
    key = String(key);
    return Object.prototype.hasOwnProperty.call(prefData, key) ? prefData[key] : '';
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
  };

  // Any top-level const foo = [{name, baseUrl, ...}] declares the source.
  // Repos name it freely (mangayomiSources, kegaretaSauces, ...), so the first
  // array whose item looks like a source entry wins, then the host listing.
  function declaredSource() {
    try {
      if (Array.isArray(g.mangayomiSources) && g.mangayomiSources.length) return g.mangayomiSources[0];
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
  function extension() {
    if (g.__anymexExt) return g.__anymexExt;
    try {
      if (typeof g.DefaultExtension !== 'function') return null;
      g.__anymexExt = new g.DefaultExtension(declaredSource() || {});
      return g.__anymexExt;
    } catch (e) { return null; }
  }

  g.__anymexExtGet = extension;

  g.__anymexSourceMeta = function () {
    try {
      var s = declaredSource() || {};
      if (typeof g.DefaultExtension === 'function') {
        var cap = null;
        try {
          var probe = new g.DefaultExtension(s);
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
    'quarkVideosExtractor', 'ucVideosExtractor', 'quarkFilesExtractor', 'ucFilesExtractor'];
  __anymexExtractorNames.forEach(function (name) {
    if (typeof g[name] !== 'function') {
      g[name] = function (url, quality) { return extractViaHost(url, quality); };
    }
  });

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
      var ext = extension();
      if (!ext) { done({ ok: false, error: 'module does not define DefaultExtension' }); return; }
      var fn = ext[String(fnName)];
      if (typeof fn !== 'function') { done({ ok: false, error: 'module does not implement ' + fnName }); return; }
      var args = JSON.parse(argsJson || '[]');
      if (!Array.isArray(args)) args = [args];
      Promise.resolve()
        .then(function () { return fn.apply(ext, args); })
        .then(function (r) {
          if (r === undefined || r === null) { done({ ok: true, data: null }); return; }
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
