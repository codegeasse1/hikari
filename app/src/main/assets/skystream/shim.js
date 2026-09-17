/*
 * SkyStream plugin shim.
 *
 * SkyStream (github.com/akashdh11/skystream) plugins are single-file JavaScript
 * scrapers that SkyStream itself runs in QuickJS. Each plugin hides behind an
 * IIFE and publishes four callbacks on globalThis:
 *
 *   getHome(cb)              -> cb({success, data: { "<category>": [MultimediaItem, …] }})
 *   search(query, cb)        -> cb({success, data: [MultimediaItem, …]})
 *   load(url, cb)            -> cb({success, data: MultimediaItem   // + episodes[]
 *   loadStreams(url, cb)     -> cb({success, data: [StreamResult, …]})
 *
 * Every `data` payload is plain JSON-able objects, and the plugin reaches the
 * network / HTML parsing / crypto through the globals this shim installs:
 *
 *   http_get(url, headers[, cb])          -> Promise<{status, headers, body}>
 *   http_post(url, headers, body[, cb])   -> Promise<{status, headers, body}>
 *   http_parallel(requests)               -> Promise<[{status, headers, body}, …]>
 *   parseHtml(html)                       -> document facade (querySelector…)
 *   parse_html(html, selector, attr)      -> Promise<[string]>
 *   getAndUnpack(js)                      -> the de-obfuscated P.A.C.K.E.R. payload
 *   crypto.decryptAES(data, key, iv)      -> Promise<string> (CryptoJS)
 *   solveCaptcha(siteKey, url)            -> Promise<null> (no solver here)
 *   getPreference(key) / setPreference(key, value)
 *   btoa / atob, URL, setTimeout/setInterval (from the engine + boot.js)
 *
 * The DOM facade is built on the cheerio bundle Hikari already ships for its
 * Nuvio runtime (globalThis.__skyCheerio), so no native HTML parser is needed:
 * cheerio's domhandler tree gives us attributes, children, siblings and the
 * parent chain, and this file wraps those nodes in objects that expose the
 * browser-ish surface the plugins actually use — querySelector(All),
 * getAttribute, textContent, innerHTML, outerHTML, children, parentElement,
 * nextElementSibling, tagName, matches, id, href, src.
 *
 * Nothing here creates a WebView or touches the UI: every request goes out
 * through __hikariFetch (OkHttp) on the engine thread.
 */
(function () {
  var g = globalThis;

  // ── console glue (boot.js already installed one that forwards to __hikariLog) ──

  // ── SkyStream's helper classes ────────────────────────────────────────────
  function Actor(p) { if (p) Object.assign(this, p); }
  function Trailer(p) { if (p) Object.assign(this, p); }
  function NextAiring(p) { if (p) Object.assign(this, p); }

  function MultimediaItem(params) {
    Object.assign(this, {
      type: 'movie',
      status: 'ongoing',
      playbackPolicy: 'none',
      isAdult: false,
      streams: [],
      syncData: {}
    }, params || {});
  }

  function Episode(params) {
    Object.assign(this, {
      season: 0,
      episode: 0,
      dubStatus: 'none',
      playbackPolicy: 'none',
      streams: []
    }, params || {});
  }

  function StreamResult(o) {
    o = o || {};
    this.url = o.url;
    this.source = o.source || 'Auto';
    this.quality = o.quality;
    this.headers = o.headers;
    this.subtitles = o.subtitles;
    this.drmKid = o.drmKid;
    this.drmKey = o.drmKey;
    this.licenseUrl = o.licenseUrl;
  }

  g.MultimediaItem = MultimediaItem;
  g.Episode = Episode;
  g.StreamResult = StreamResult;
  g.Actor = Actor;
  g.Trailer = Trailer;
  g.NextAiring = NextAiring;

  g.CloudStream = {
    getLanguage: function () { return 'en'; },
    getRegion: function () { return 'US'; }
  };

  // ── HTTP ─────────────────────────────────────────────────────────────────
  // Every call is SYNCHRONOUS inside the engine (the native bridge blocks on
  // OkHttp), so the promise resolves on the microtask queue the engine drains
  // while it awaits the plugin's own async function.
  function decodeResponse(json) {
    var res;
    try { res = JSON.parse(json); } catch (e) { res = { status: 0, body: '', headers: {} }; }
    if (res == null || typeof res !== 'object') res = { status: 0, body: String(res || ''), headers: {} };
    return res;
  }

  function request(method, url, headers, body) {
    // Two shapes are in the wild: http_get(url, {"User-Agent": …}) and the
    // fetch-flavoured http_get(url, {headers: {"User-Agent": …}}) a few plugins
    // use. Unwrap the latter instead of sending a nested object as a header.
    if (headers && typeof headers === 'object' && headers.headers &&
        typeof headers.headers === 'object') {
      headers = headers.headers;
    }
    var headersJson = '{}';
    try { headersJson = JSON.stringify(headers || {}); } catch (e) { headersJson = '{}'; }
    var raw = g.__hikariFetch(String(url), String(method || 'GET'), headersJson,
      body == null ? '' : String(body), true);
    return Promise.resolve(decodeResponse(raw));
  }

  function withCb(p, cb) {
    if (typeof cb === 'function') {
      p.then(function (r) { try { cb(r); } catch (e) {} });
    }
    return p;
  }

  g.http_get = function (url, headers, cb) {
    return withCb(request('GET', url, headers, null), cb);
  };

  g.http_post = function (url, headers, body, cb) {
    // SkyStream's own signature is (url, headers, body) and its polyfill swaps
    // a (url, body, headers) call for you, so mirror that: a string in the
    // headers slot with an object in the body slot means the caller swapped them.
    if (typeof headers === 'string' && body && typeof body === 'object') {
      var t = headers; headers = body; body = t;
    }
    return withCb(request('POST', url, headers, body), cb);
  };

  g.http_parallel = function (requests) {
    var list = (requests && requests.length !== undefined) ? Array.prototype.slice.call(requests) : [];
    return Promise.all(list.map(function (r) {
      r = r || {};
      var method = r.method || 'GET';
      var headers = r.headers || {};
      var body = r.data !== undefined ? r.data : r.body;
      if (r.params && method === 'GET') {
        var q = Object.keys(r.params).map(function (k) {
          return encodeURIComponent(k) + '=' + encodeURIComponent(r.params[k]);
        }).join('&');
        if (q) r.url = r.url + (r.url.indexOf('?') === -1 ? '?' : '&') + q;
      }
      return request(method, r.url, headers, body);
    }));
  };

  // A fetch()-flavoured wrapper: a few community plugins use it even though the
  // official API is http_get. Gives them text()/json()/status/ok/headers.get().
  g.fetch = function (url, opts) {
    opts = opts || {};
    var headers = opts.headers || {};
    return request(opts.method || 'GET', url, headers, opts.body).then(function (r) {
      var lower = {};
      try { Object.keys(r.headers || {}).forEach(function (k) { lower[String(k).toLowerCase()] = r.headers[k]; }); } catch (e) {}
      return {
        ok: r.status >= 200 && r.status < 300,
        status: r.status,
        url: r.url || url,
        headers: { get: function (k) { return lower[String(k).toLowerCase()] || null; } },
        text: function () { return Promise.resolve(r.body || ''); },
        json: function () { return Promise.resolve(JSON.parse(r.body || 'null')); }
      };
    });
  };
  g._fetch = g.fetch;

  // ── HTML parsing ─────────────────────────────────────────────────────────
  // The cheerio bundle is evaluated before this file, but its export shape
  // depends on the build (a UMD factory carrying `.load`, or an ESM namespace
  // whose `default` is that factory), so normalise it on every access.
  function cheerioFactory() {
    var c = g.__skyCheerio;
    if (!c) return null;
    if (typeof c.load === 'function') return c;
    if (c.default && typeof c.default.load === 'function') return c.default;
    if (typeof c.default === 'function') return c.default;
    return typeof c === 'function' ? c : null;
  }

  // Tag the loaded document's root node with its own `$` so any node can walk
  // back up to the document it came from.
  var CHEERIO_DOC = '__skyDollar';

  function rawNode(x) {
    if (x == null) return null;
    if (x.__skyNode !== undefined) return x.__skyNode;
    if (x[0] !== undefined) return x[0];
    if (typeof x.get === 'function') { try { return x.get(0); } catch (e) { return null; } }
    return null;
  }

  function wrap(node) {
    if (node == null) return null;
    if (node.__skyWrap !== undefined) return node.__skyWrap;
    var out = new SkyNode(node);
    try { node.__skyWrap = out; } catch (e) {}
    return out;
  }

  function wrapAll(list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(wrap(list[i]));
    return out;
  }

  /**
   * Resolve the document-bound `$` for a node: parseDocument tags each root
   * node with it, so walking the parent chain recomposes the document a node
   * belongs to. Nodes from nowhere fall back to the bare factory.
   */
  function dollarFor(node) {
    var n = node;
    while (n) {
      if (n[CHEERIO_DOC]) return n[CHEERIO_DOC];
      n = n.parent;
    }
    var f = cheerioFactory();
    if (!f) return null;
    return f.load ? f : (f.default && f.default.load ? f.default : f);
  }

  function selectAll(rootNode, sel) {
    if (!sel) return [];
    var $ = dollarFor(rootNode);
    if (typeof $ !== 'function') return [];
    try {
      if (rootNode) {
        // cheerio's find() treats a root node as "the whole document", which is
        // exactly the semantics a Document's querySelectorAll needs.
        if (rootNode.type !== 'root') {
          return wrapAll($(rootNode).find(String(sel)).toArray());
        }
        var viaRoot = $(rootNode).find(String(sel));
        if (viaRoot && viaRoot.length) return wrapAll(viaRoot.toArray());
        var html = $('html');
        return html.length ? wrapAll(html.find(String(sel)).toArray()) : [];
      }
      return wrapAll($(String(sel)).toArray());
    } catch (e) {
      return [];
    }
  }

  function selectOne(rootNode, sel) {
    var all = selectAll(rootNode, sel);
    return all.length ? all[0] : null;
  }

  function attrOf(node, name) {
    if (!node || !node.attribs) return null;
    var v = node.attribs[String(name)];
    return v === undefined ? null : v;
  }

  function textOf(node) {
    if (!node) return '';
    var $ = dollarFor(node);
    if (typeof $ !== 'function') return '';
    try {
      return $(node).text() || '';
    } catch (e) {
      return '';
    }
  }

  function htmlOf(node, outer) {
    if (!node) return '';
    var $ = dollarFor(node);
    if (typeof $ !== 'function') return '';
    try {
      return outer ? $.html(node) : ($(node).html() || '');
    } catch (e) {
      return '';
    }
  }

  /**
   * Browser-ish element wrapper over one cheerio/domhandler node. Everything a
   * plugin is likely to touch is here; anything else falls through to the raw
   * node so a deep call doesn't explode.
   */
  function SkyNode(node) {
    this.__skyNode = node;
  }

  SkyNode.prototype.getAttribute = function (name) { return attrOf(this.__skyNode, name); };
  SkyNode.prototype.hasAttribute = function (name) { return attrOf(this.__skyNode, name) !== null; };
  SkyNode.prototype.setAttribute = function (name, value) {
    if (this.__skyNode && this.__skyNode.attribs) this.__skyNode.attribs[String(name)] = String(value);
  };
  SkyNode.prototype.removeAttribute = function (name) {
    if (this.__skyNode && this.__skyNode.attribs) delete this.__skyNode.attribs[String(name)];
  };
  SkyNode.prototype.querySelector = function (sel) { return selectOne(this.__skyNode, sel); };
  SkyNode.prototype.querySelectorAll = function (sel) { return selectAll(this.__skyNode, sel); };
  SkyNode.prototype.getElementsByTagName = function (name) { return selectAll(this.__skyNode, String(name)); };
  SkyNode.prototype.getElementsByClassName = function (name) {
    return selectAll(this.__skyNode, '.' + String(name).trim().split(/\s+/).join('.'));
  };
  SkyNode.prototype.matches = function (sel) {
    var up = this.__skyNode && this.__skyNode.parent;
    if (!up) return false;
    var list = selectAll(up, sel);
    for (var i = 0; i < list.length; i++) {
      if (list[i].__skyNode === this.__skyNode) return true;
    }
    return false;
  };
  SkyNode.prototype.closest = function (sel) {
    var cur = this.__skyNode;
    while (cur) {
      var w = wrap(cur);
      if (w.matches(sel)) return w;
      cur = cur.parent;
    }
    return null;
  };
  SkyNode.prototype.text = function () { return textOf(this.__skyNode); };
  SkyNode.prototype.html = function () { return htmlOf(this.__skyNode, false); };
  SkyNode.prototype.attr = function (name, value) {
    if (value === undefined) return attrOf(this.__skyNode, name);
    this.setAttribute(name, value);
    return this;
  };
  SkyNode.prototype.find = function (sel) { return selectAll(this.__skyNode, sel); };
  SkyNode.prototype.first = function () { return this; };
  SkyNode.prototype.toString = function () { return htmlOf(this.__skyNode, true); };

  Object.defineProperty(SkyNode.prototype, 'textContent', {
    get: function () { return textOf(this.__skyNode); },
    set: function (v) { if (this.__skyNode) this.__skyNode.data = String(v); }
  });
  Object.defineProperty(SkyNode.prototype, 'innerHTML', {
    get: function () { return htmlOf(this.__skyNode, false); }
  });
  Object.defineProperty(SkyNode.prototype, 'outerHTML', {
    get: function () { return htmlOf(this.__skyNode, true); }
  });
  Object.defineProperty(SkyNode.prototype, 'innerText', {
    get: function () { return textOf(this.__skyNode); }
  });
  Object.defineProperty(SkyNode.prototype, 'tagName', {
    get: function () {
      var n = this.__skyNode;
      return n && n.name ? String(n.name).toUpperCase() : '';
    }
  });
  Object.defineProperty(SkyNode.prototype, 'nodeName', {
    get: function () { return this.tagName; }
  });
  Object.defineProperty(SkyNode.prototype, 'parentElement', {
    get: function () {
      var p = this.__skyNode && this.__skyNode.parent;
      return p && p.type !== 'root' ? wrap(p) : null;
    }
  });
  Object.defineProperty(SkyNode.prototype, 'parentNode', {
    get: function () { return this.parentElement; }
  });
  Object.defineProperty(SkyNode.prototype, 'children', {
    get: function () {
      var n = this.__skyNode;
      var kids = (n && n.children) || [];
      var out = [];
      for (var i = 0; i < kids.length; i++) {
        if (kids[i] && kids[i].type === 'tag') out.push(wrap(kids[i]));
      }
      return out;
    }
  });
  Object.defineProperty(SkyNode.prototype, 'childNodes', {
    get: function () { return this.children; }
  });
  Object.defineProperty(SkyNode.prototype, 'firstElementChild', {
    get: function () { return this.children[0] || null; }
  });
  // Browsers skip text/comment nodes here, so walk past them.
  function siblingOf(node, key) {
    var n = node && node[key];
    while (n && n.type !== 'tag') n = n[key];
    return n ? wrap(n) : null;
  }
  Object.defineProperty(SkyNode.prototype, 'nextElementSibling', {
    get: function () { return siblingOf(this.__skyNode, 'next'); }
  });
  Object.defineProperty(SkyNode.prototype, 'previousElementSibling', {
    get: function () { return siblingOf(this.__skyNode, 'prev'); }
  });
  Object.defineProperty(SkyNode.prototype, 'id', {
    get: function () { return attrOf(this.__skyNode, 'id') || ''; }
  });
  Object.defineProperty(SkyNode.prototype, 'className', {
    get: function () { return attrOf(this.__skyNode, 'class') || ''; }
  });
  Object.defineProperty(SkyNode.prototype, 'classList', {
    get: function () {
      var cls = (attrOf(this.__skyNode, 'class') || '').split(/\s+/).filter(Boolean);
      return {
        length: cls.length,
        contains: function (c) { return cls.indexOf(String(c)) !== -1; },
        item: function (i) { return cls[i] || null; },
        toArray: function () { return cls.slice(); }
      };
    }
  });
  Object.defineProperty(SkyNode.prototype, 'href', {
    get: function () { return attrOf(this.__skyNode, 'href') || ''; },
    set: function (v) { this.setAttribute('href', v); }
  });
  Object.defineProperty(SkyNode.prototype, 'src', {
    get: function () { return attrOf(this.__skyNode, 'src') || ''; },
    set: function (v) { this.setAttribute('src', v); }
  });
  Object.defineProperty(SkyNode.prototype, 'dataset', {
    get: function () {
      var out = {};
      var a = (this.__skyNode && this.__skyNode.attribs) || {};
      Object.keys(a).forEach(function (k) {
        if (k.indexOf('data-') === 0) {
          var camel = k.slice(5).replace(/-([a-z])/g, function (_, c) { return c.toUpperCase(); });
          out[camel] = a[k];
        }
      });
      return out;
    }
  });

  function SkyDocument(rootNode, dollar) {
    this.__skyNode = rootNode;
    var htmlEl = null;
    var bodyEl = null;
    if (typeof dollar === 'function') {
      try { htmlEl = dollar('html')[0] || null; } catch (e) {}
      try { bodyEl = dollar('body')[0] || null; } catch (e) {}
    }
    this.documentElement = wrap(htmlEl || rootNode);
    this.body = wrap(bodyEl || htmlEl || rootNode);
    this.title = '';
    try {
      if (htmlEl) {
        var t = selectOne(htmlEl, 'title');
        if (t) this.title = t.textContent;
      }
    } catch (e) {}
  }
  SkyDocument.prototype.querySelector = function (sel) { return selectOne(this.__skyNode, sel); };
  SkyDocument.prototype.querySelectorAll = function (sel) { return selectAll(this.__skyNode, sel); };
  SkyDocument.prototype.getElementsByTagName = function (n) { return selectAll(this.__skyNode, String(n)); };
  SkyDocument.prototype.getElementsByClassName = function (n) {
    return selectAll(this.__skyNode, '.' + String(n).trim().split(/\s+/).join('.'));
  };
  SkyDocument.prototype.getElementById = function (id) { return selectOne(this.__skyNode, '#' + id); };
  SkyDocument.prototype.toString = function () { return htmlOf(this.__skyNode, true); };

  function parseDocument(html) {
    var factory = cheerioFactory();
    if (!factory || typeof factory.load !== 'function') {
      // No cheerio: answer with an empty stand-in so a plugin's scrape fails
      // as "no results" instead of throwing on a missing global.
      return new SkyDocument({ type: 'root', children: [] });
    }
    var $;
    try {
      $ = factory.load(String(html == null ? '' : html));
    } catch (e) {
      return new SkyDocument({ type: 'root', children: [] });
    }
    var root;
    try { root = $.root()[0]; } catch (e) { root = null; }
    if (!root) {
      try { root = $('html')[0] || { type: 'root', children: [] }; } catch (e) {
        root = { type: 'root', children: [] };
      }
    }
    try { root[CHEERIO_DOC] = $; } catch (e) {}
    return new SkyDocument(root, $);
  }

  g.parseHtml = function (html) { return Promise.resolve(parseDocument(html)); };
  // Some plugins expect the document synchronously.
  g.parseHtmlSync = parseDocument;

  g.parse_html = function (html, selector, attr) {
    var doc = parseDocument(html);
    var out = [];
    try {
      var list = doc.querySelectorAll(selector);
      for (var i = 0; i < list.length; i++) {
        out.push(attr ? list[i].getAttribute(attr) : list[i].textContent);
      }
    } catch (e) {}
    return Promise.resolve(out);
  };

  g.JSDOM = function JSDOM(html) {
    var doc = parseDocument(html);
    this.window = { document: doc };
  };

  // ── De-obfuscation (Dean Edwards' P.A.C.K.E.R.) ──────────────────────────
  function unpackPacker(source) {
    try {
      var m = /}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\('\|'\)/.exec(source);
      if (!m) return null;
      var payload = m[1];
      var radix = parseInt(m[2], 10);
      var count = parseInt(m[3], 10);
      var words = m[4].split('|');
      var unescape_ = function (s) {
        return s.replace(/\\'/g, "'").replace(/\\\\/g, '\\');
      };
      payload = unescape_(payload);
      function baseN(n) {
        return (n < radix ? '' : baseN(Math.floor(n / radix))) +
          ((n = n % radix) > 35 ? String.fromCharCode(n + 29) : n.toString(36));
      }
      var dict = {};
      for (var i = 0; i < count; i++) dict[baseN(i)] = words[i] || baseN(i);
      return payload.replace(/\b\w+\b/g, function (w) {
        return dict[w] !== undefined ? dict[w] : w;
      });
    } catch (e) {
      return null;
    }
  }

  g.getAndUnpack = function (js) {
    var unpacked = unpackPacker(String(js || ''));
    return unpacked === null ? '' : unpacked;
  };

  // ── Crypto / captcha ─────────────────────────────────────────────────────
  var cjs = g.CryptoJS;
  function toUtf8WordArray(str) {
    if (cjs.enc && cjs.enc.Utf8) return cjs.enc.Utf8.parse(String(str));
    return String(str);
  }
  function decryptAES(data, key, iv, options) {
    if (!cjs) return Promise.reject(new Error('AES decryption is unavailable in this build'));
    try {
      var mode = (options && options.mode) ? String(options.mode).toLowerCase() : 'cbc';
      var args = {
        iv: toUtf8WordArray(iv || ''),
        mode: mode === 'cbc' ? cjs.mode.CBC : (mode === 'ecb' ? cjs.mode.ECB : cjs.mode.CBC),
        padding: cjs.pad.Pkcs7
      };
      var keyStr = String(key || '');
      // A 32/48/64-char key is the hex form of a 16/24/32-byte key — the
      // convention every ported CloudStream extractor uses.
      var keyWA = /^[0-9a-fA-F]{32,64}$/.test(keyStr) && keyStr.length % 2 === 0
        ? cjs.enc.Hex.parse(keyStr)
        : toUtf8WordArray(keyStr);
      var dataWA = toUtf8WordArray(String(data || ''));
      var dec = cjs.AES.decrypt({ ciphertext: dataWA }, keyWA, args);
      var out = dec.toString(cjs.enc.Utf8);
      return Promise.resolve(out);
    } catch (e) {
      return Promise.reject(e);
    }
  }
  var existingCrypto = g.crypto || {};
  existingCrypto.decryptAES = decryptAES;
  existingCrypto.pbkdf2 = function (password, salt, iterations, keyLength) {
    if (!cjs) return Promise.reject(new Error('PBKDF2 is unavailable in this build'));
    try {
      return Promise.resolve(
        cjs.PBKDF2(
          String(password), cjs.enc.Hex.parse(String(salt)),
          { keySize: (keyLength || 32) / 4, iterations: iterations || 10000 }
        ).toString()
      );
    } catch (e) {
      return Promise.reject(e);
    }
  };
  g.crypto = existingCrypto;

  // No captcha solver in Hikari: answer null so a plugin's `if (token)` path
  // simply falls through instead of hanging on a dialog that never appears.
  g.solveCaptcha = function () { return Promise.resolve(null); };

  // SkyStream's CLI tree-shakes its extractor library into the plugin, so
  // loadExtractor is the legacy call — but plugins written against it still
  // reach for it. Hikari has its own full extraction stack (parsing, packing,
  // dood/rumble dances, the CloudStream jar registry), so answer through that
  // instead of the empty list a stub would give: those plugins would otherwise
  // report "no sources" for hosts Hikari can actually resolve.
  g.loadExtractor = function (url, cb) {
    var p = new Promise(function (resolve) {
      var out = [];
      try {
        var raw = g.__hikariExtract(String(url), '');
        var list = JSON.parse(raw || '[]');
        if (list && list.length) {
          out = list.map(function (s) {
            return new StreamResult({
              url: s.url,
              source: s.name || 'Extractor',
              headers: s.headers || undefined
            });
          });
        }
      } catch (e) { out = []; }
      resolve(out);
    });
    if (typeof cb === 'function') {
      p.then(function (r) { try { cb(r); } catch (e) {} });
    }
    return p;
  };

  // ── Plugin preferences (shared across calls; backed by a JSON file) ──────
  g.getPreference = function (key) {
    try {
      return Promise.resolve(g.__hikariPrefGet(String(key)));
    } catch (e) {
      return Promise.resolve(null);
    }
  };
  g.setPreference = function (key, value) {
    try { g.__hikariPrefSet(String(key), String(value == null ? '' : value)); } catch (e) {}
    return Promise.resolve(true);
  };
})();
