/* HIKARI nuvio provider runtime harness.
 * Mirrors NuvioMobile's PluginRuntime/JsBindings calling conventions but runs in a Chromium WebView,
 * so providers get the real cheerio, crypto-js, fetch, URL, WebSocket, etc.
 * The host app injects: NuvioBridge (fetch + onGetStreamsDone + onSettingsDone + log) via addJavascriptInterface.
 * The WebView loads assets/nuvio/runtime.html which loads cheerio.js + crypto-js.js as plain scripts
 * (avoids evaluateJavascript's message-size ceiling), then this harness, then registers the two modules,
 * then per call: window.__nuvioRunProvider(source, id, cid, tmdbId, mediaType, season, episode).
 */
(function () {
  'use strict';
  var g = typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this);
  g.global = g;
  try { g.window = g; } catch (e) {}
  try { g.self = g; } catch (e) {}

  var SHIM_BUFFER = "function Buffer(arg, encodingOrOffset, length) {\n  if (arg instanceof Uint8Array) {\n    return Buffer.from(arg);\n  }\n  if (typeof arg === 'number') {\n    var size = arg;\n    var b = new Uint8Array(size);\n    Object.setPrototypeOf(b, Buffer.prototype);\n    return b;\n  }\n  return Buffer.from(arg, encodingOrOffset, length);\n}\n\nvar HEX = '0123456789abcdef';\nvar encodings = ['utf8', 'utf-8', 'hex', 'latin1', 'binary', 'base64', 'ascii', 'ucs2', 'ucs-2', 'utf16le', 'utf-16le'];\nvar normalized = { 'utf-8': 'utf8', latin1: 'latin1', binary: 'latin1', ucs2: 'ucs2', 'ucs-2': 'ucs2', 'utf16le': 'ucs2', 'utf-16le': 'ucs2', ascii: 'ascii', hex: 'hex', base64: 'base64', utf8: 'utf8' };\n\nfunction normalizeEnc(enc) {\n  if (enc === undefined) return 'utf8';\n  var e = String(enc).toLowerCase();\n  return normalized[e] || 'utf8';\n}\n\nfunction utf8Bytes(str) {\n  var out = [];\n  for (var i = 0; i < str.length; i++) {\n    var c = str.codePointAt(i);\n    if (c > 0xFFFF) i++;\n    if (c < 0x80) out.push(c);\n    else if (c < 0x800) out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));\n    else if (c < 0x10000) out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));\n    else out.push(0xF0 | (c >> 18), 0x80 | ((c >> 12) & 0x3F), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));\n  }\n  return out;\n}\n\nfunction utf8String(bytes, start, end) {\n  var s = '';\n  var i = start;\n  while (i < end) {\n    var b = bytes[i];\n    if (b < 0x80) { s += String.fromCharCode(b); i++; }\n    else if (b < 0xE0) { s += String.fromCharCode(((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F)); i += 2; }\n    else if (b < 0xF0) { s += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F)); i += 3; }\n    else {\n      var cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);\n      s += String.fromCodePoint(cp); i += 4;\n    }\n  }\n  return s;\n}\n\nfunction fromString(str, enc) {\n  enc = normalizeEnc(enc);\n  if (enc === 'hex') {\n    var len = Math.floor(str.length / 2);\n    var b = new Uint8Array(len);\n    for (var i = 0; i < len; i++) b[i] = parseInt(str.substr(i * 2, 2), 16);\n    Object.setPrototypeOf(b, Buffer.prototype);\n    return b;\n  }\n  if (enc === 'base64') {\n    var bin = typeof atob === 'function' ? atob(str) : null;\n    if (bin === null) { var b2 = new Uint8Array(0); Object.setPrototypeOf(b2, Buffer.prototype); return b2; }\n    var out = new Uint8Array(bin.length);\n    for (var j = 0; j < bin.length; j++) out[j] = bin.charCodeAt(j);\n    Object.setPrototypeOf(out, Buffer.prototype);\n    return out;\n  }\n  if (enc === 'latin1' || enc === 'ascii') {\n    var l = new Uint8Array(str.length);\n    for (var k = 0; k < str.length; k++) l[k] = enc === 'ascii' ? (str.charCodeAt(k) & 0x7F) : (str.charCodeAt(k) & 0xFF);\n    Object.setPrototypeOf(l, Buffer.prototype);\n    return l;\n  }\n  if (enc === 'ucs2') {\n    var u = new Uint8Array(str.length * 2);\n    for (var m = 0; m < str.length; m++) { var code = str.charCodeAt(m); u[m * 2] = code & 0xFF; u[m * 2 + 1] = code >> 8; }\n    Object.setPrototypeOf(u, Buffer.prototype);\n    return u;\n  }\n  var bytes = utf8Bytes(str);\n  var b3 = new Uint8Array(bytes.length);\n  for (var n = 0; n < bytes.length; n++) b3[n] = bytes[n];\n  Object.setPrototypeOf(b3, Buffer.prototype);\n  return b3;\n}\n\nBuffer.from = function (value, encodingOrOffset, length) {\n  if (value === undefined || value === null) throw new TypeError('Buffer.from requires a value');\n  if (typeof value === 'string') return fromString(value, encodingOrOffset);\n  if (value instanceof Uint8Array) {\n    var start = encodingOrOffset || 0;\n    var end = length !== undefined ? start + length : value.length;\n    var slice = value.subarray(start, end);\n    var copy = new Uint8Array(slice);\n    Object.setPrototypeOf(copy, Buffer.prototype);\n    return copy;\n  }\n  if (value instanceof ArrayBuffer || (typeof SharedArrayBuffer !== 'undefined' && value instanceof SharedArrayBuffer)) {\n    return Buffer.from(new Uint8Array(value), encodingOrOffset, length);\n  }\n  if (Array.isArray(value) || ArrayBuffer.isView(value)) {\n    var arr = new Uint8Array(value.length);\n    for (var i = 0; i < value.length; i++) arr[i] = value[i] & 0xFF;\n    Object.setPrototypeOf(arr, Buffer.prototype);\n    return arr;\n  }\n  if (typeof value === 'number') {\n    var b = new Uint8Array(value);\n    Object.setPrototypeOf(b, Buffer.prototype);\n    return b;\n  }\n  throw new TypeError('Buffer.from: unsupported type ' + typeof value);\n};\n\nBuffer.alloc = function (size, fill, encoding) {\n  if (typeof size !== 'number') size = 0;\n  var b = new Uint8Array(size);\n  Object.setPrototypeOf(b, Buffer.prototype);\n  if (fill !== undefined) {\n    if (typeof fill === 'number') b.fill(fill);\n    else if (typeof fill === 'string') {\n      var f = fromString(fill, encoding);\n      for (var i = 0; i < size; i++) b[i] = f[i % f.length];\n    } else if (fill instanceof Uint8Array) {\n      for (var j = 0; j < size; j++) b[j] = fill[j % fill.length];\n    }\n  }\n  return b;\n};\nBuffer.allocUnsafe = function (size) {\n  var b = new Uint8Array(typeof size === 'number' ? size : 0);\n  Object.setPrototypeOf(b, Buffer.prototype);\n  return b;\n};\nBuffer.allocUnsafeSlow = Buffer.allocUnsafe;\nBuffer.isBuffer = function (o) { return !!(o && o._isBuffer); };\nBuffer.isEncoding = function (enc) { return encodings.indexOf(String(enc).toLowerCase()) !== -1; };\n\nBuffer.byteLength = function (str, enc) {\n  if (typeof str !== 'string') return str ? str.length : 0;\n  enc = normalizeEnc(enc);\n  if (enc === 'hex') return Math.floor(str.length / 2);\n  if (enc === 'base64') {\n    var l = str.length;\n    if (str.endsWith('==')) l -= 2; else if (str.endsWith('=')) l -= 1;\n    return Math.floor(l * 3 / 4);\n  }\n  if (enc === 'latin1' || enc === 'ascii') return str.length;\n  if (enc === 'ucs2') return str.length * 2;\n  var bytes = utf8Bytes(str);\n  return bytes.length;\n};\n\nBuffer.concat = function (list, totalLength) {\n  if (!Array.isArray(list)) throw new TypeError('Buffer.concat expects an array');\n  if (list.length === 0) return Buffer.alloc(0);\n  if (totalLength === undefined) {\n    totalLength = 0;\n    for (var i = 0; i < list.length; i++) totalLength += list[i].length;\n  }\n  var out = new Uint8Array(totalLength);\n  Object.setPrototypeOf(out, Buffer.prototype);\n  var pos = 0;\n  for (var j = 0; j < list.length; j++) {\n    var item = list[j];\n    if (!item) continue;\n    out.set(item, pos);\n    pos += item.length;\n  }\n  return out;\n};\n\nBuffer.compare = function (a, b) {\n  var len = Math.min(a.length, b.length);\n  for (var i = 0; i < len; i++) { if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1; }\n  return a.length - b.length;\n};\n\nBuffer.prototype = Object.create(Uint8Array.prototype);\nObject.defineProperty(Buffer.prototype, '_isBuffer', { value: true, enumerable: false, configurable: false, writable: false });\nObject.defineProperty(Buffer.prototype, 'constructor', { value: Buffer, enumerable: false, writable: true, configurable: true });\n\nBuffer.prototype.toString = function (encoding, start, end) {\n  encoding = normalizeEnc(encoding);\n  start = start || 0;\n  end = end === undefined ? this.length : end;\n  if (encoding === 'hex') {\n    var s = '';\n    for (var i = start; i < end; i++) { var b = this[i]; s += HEX[b >> 4] + HEX[b & 15]; }\n    return s;\n  }\n  if (encoding === 'base64') {\n    var bin = '';\n    for (var j = start; j < end; j++) bin += String.fromCharCode(this[j]);\n    return typeof btoa === 'function' ? btoa(bin) : bin;\n  }\n  if (encoding === 'latin1') {\n    var s2 = '';\n    for (var k = start; k < end; k++) s2 += String.fromCharCode(this[k]);\n    return s2;\n  }\n  if (encoding === 'ucs2') {\n    var s3 = '';\n    for (var m = start; m + 1 < end; m += 2) s3 += String.fromCharCode(this[m] | (this[m + 1] << 8));\n    return s3;\n  }\n  if (encoding === 'ascii') {\n    var s4 = '';\n    for (var n = start; n < end; n++) s4 += String.fromCharCode(this[n] & 0x7F);\n    return s4;\n  }\n  return utf8String(this, start, end);\n};\n\nBuffer.prototype.slice = function (start, end) {\n  var s = start === undefined ? 0 : start;\n  var e = end === undefined ? this.length : end;\n  var sub = this.subarray(s, e);\n  var copy = new Uint8Array(sub);\n  Object.setPrototypeOf(copy, Buffer.prototype);\n  return copy;\n};\n\nBuffer.prototype.subarray = function (start, end) {\n  return this.slice(start, end);\n};\n\nBuffer.prototype.copy = function (target, targetStart, sourceStart, sourceEnd) {\n  targetStart = targetStart || 0;\n  sourceStart = sourceStart || 0;\n  sourceEnd = sourceEnd === undefined ? this.length : sourceEnd;\n  var len = Math.min(sourceEnd - sourceStart, target.length - targetStart);\n  for (var i = 0; i < len; i++) target[targetStart + i] = this[sourceStart + i];\n  return len;\n};\n\nBuffer.prototype.equals = function (other) {\n  if (!other || this.length !== other.length) return false;\n  for (var i = 0; i < this.length; i++) { if (this[i] !== other[i]) return false; }\n  return true;\n};\n\nBuffer.prototype.write = function (str, offset, length, encoding) {\n  if (typeof offset === 'string') { encoding = offset; offset = 0; length = undefined; }\n  else if (typeof length === 'string') { encoding = length; length = undefined; }\n  offset = offset || 0;\n  encoding = normalizeEnc(encoding);\n  var data = fromString(str, encoding);\n  var len = length === undefined ? Math.min(data.length, this.length - offset) : Math.min(length, data.length, this.length - offset);\n  for (var i = 0; i < len; i++) this[offset + i] = data[i];\n  return len;\n};\n\nBuffer.prototype.fill = function (value, offset, end, encoding) {\n  if (typeof value === 'string') value = fromString(value, encoding);\n  offset = offset || 0;\n  end = end === undefined ? this.length : end;\n  if (typeof value === 'number') value = new Uint8Array([value]);\n  for (var i = offset; i < end; i++) this[i] = value[i % value.length];\n  return this;\n};\n\nBuffer.prototype.toJSON = function () { return { type: 'Buffer', data: Array.prototype.slice.call(this) }; };\n\nBuffer.prototype.indexOf = function (value, byteOffset) {\n  byteOffset = byteOffset || 0;\n  if (typeof value === 'string') value = fromString(value);\n  outer:\n  for (var i = byteOffset; i + value.length <= this.length; i++) {\n    for (var j = 0; j < value.length; j++) { if (this[i + j] !== value[j]) continue outer; }\n    return i;\n  }\n  return -1;\n};\n\nBuffer.prototype.includes = function (value, byteOffset) { return this.indexOf(value, byteOffset) !== -1; };\n\nBuffer.prototype.writeUInt8 = function (v, o) { this[o] = v & 0xFF; return o + 1; };\nBuffer.prototype.writeUInt16BE = function (v, o) { this[o] = v >> 8 & 0xFF; this[o + 1] = v & 0xFF; return o + 2; };\nBuffer.prototype.writeUInt16LE = function (v, o) { this[o] = v & 0xFF; this[o + 1] = v >> 8 & 0xFF; return o + 2; };\nBuffer.prototype.writeUInt32BE = function (v, o) { this[o] = v >>> 24 & 0xFF; this[o + 1] = v >>> 16 & 0xFF; this[o + 2] = v >>> 8 & 0xFF; this[o + 3] = v & 0xFF; return o + 4; };\nBuffer.prototype.writeUInt32LE = function (v, o) { this[o] = v & 0xFF; this[o + 1] = v >>> 8 & 0xFF; this[o + 2] = v >>> 16 & 0xFF; this[o + 3] = v >>> 24 & 0xFF; return o + 4; };\nBuffer.prototype.writeInt32BE = function (v, o) { return this.writeUInt32BE(v >>> 0, o); };\nBuffer.prototype.writeInt32LE = function (v, o) { return this.writeUInt32LE(v >>> 0, o); };\nBuffer.prototype.writeFloatBE = Buffer.prototype.writeFloatLE = function () { throw new Error('writeFloat not supported'); };\n\nBuffer.prototype.readUInt8 = function (o) { return this[o]; };\nBuffer.prototype.readUInt16BE = function (o) { return (this[o] << 8) | this[o + 1]; };\nBuffer.prototype.readUInt16LE = function (o) { return this[o] | (this[o + 1] << 8); };\nBuffer.prototype.readUInt32BE = function (o) { return ((this[o] << 24) >>> 0) + (this[o + 1] << 16) + (this[o + 2] << 8) + this[o + 3]; };\nBuffer.prototype.readUInt32LE = function (o) { return ((this[o + 3] << 24) >>> 0) + (this[o + 2] << 16) + (this[o + 1] << 8) + this[o]; };\nBuffer.prototype.readInt32BE = function (o) { return this.readUInt32BE(o) | 0; };\nBuffer.prototype.readInt32LE = function (o) { return this.readUInt32LE(o) | 0; };\n\nBuffer.SlowBuffer = Buffer;\n\nmodule.exports = Buffer;\nmodule.exports.Buffer = Buffer;\n";
  var SHIM_PROCESS = "var process = {\n  browser: true,\n  env: {},\n  argv: [],\n  version: 'v16.0.0',\n  platform: 'browser',\n  title: 'browser',\n  pid: 1,\n  nextTick: function (fn) { setTimeout(function () { fn(); }, 0); },\n  stdout: { write: function (s) { try { console.log(String(s).replace(/\\n$/, '')); } catch (e) {} } },\n  stderr: { write: function (s) { try { console.warn(String(s).replace(/\\n$/, '')); } catch (e) {} } },\n  cwd: function () { return '/'; },\n  chdir: function () {},\n  exit: function () {},\n  on: function () { return process; },\n  once: function () { return process; },\n  off: function () { return process; },\n  removeListener: function () { return process; },\n  addListener: function () { return process; },\n  emit: function () { return false; },\n  listeners: function () { return []; },\n  listenerCount: function () { return 0; },\n  setMaxListeners: function () { return process; },\n  getMaxListeners: function () { return 0; },\n  hrtime: function () { return [0, 0]; },\n  uptime: function () { return 0; },\n  memoryUsage: function () { return { rss: 0, heapTotal: 0, heapUsed: 0, external: 0 }; },\n  kill: function () {},\n  binding: function () { throw new Error('process.binding is not supported'); },\n  umask: function () { return 0; },\n  getuid: function () { return 0; },\n  getgid: function () { return 0; },\n  execPath: '/',\n  cwd: function () { return '/'; },\n  features: {},\n  versions: {}\n};\nmodule.exports = process;\n";
  var SHIM_UTIL = "function inherits(ctor, superCtor) {\n  if (superCtor) {\n    ctor.super_ = superCtor;\n    ctor.prototype = Object.create(superCtor.prototype, {\n      constructor: { value: ctor, enumerable: false, writable: true, configurable: true }\n    });\n  }\n}\n\nfunction deprecate(fn, msg) { return fn; }\n\nfunction inspect(value, opts) {\n  if (value === null || value === undefined) return String(value);\n  if (typeof value === 'string') return \"'\" + value + \"'\";\n  if (typeof value === 'function') return '[Function]';\n  if (Array.isArray(value)) return '[' + value.map(function (v) { return inspect(v, opts); }).join(', ') + ']';\n  if (typeof value === 'object') {\n    var depth = (opts && opts.depth != null) ? opts.depth : 2;\n    if (depth <= 0) return '[' + (value.constructor && value.constructor.name || 'Object') + ']';\n    var keys = Object.keys(value);\n    var parts = keys.slice(0, 8).map(function (k) {\n      return k + ': ' + inspect(value[k], { depth: depth - 1 });\n    });\n    if (keys.length > 8) parts.push('... ' + (keys.length - 8) + ' more items');\n    return '{ ' + parts.join(', ') + ' }';\n  }\n  return String(value);\n}\n\nfunction format(f) {\n  var args = Array.prototype.slice.call(arguments, 1);\n  if (typeof f !== 'string') {\n    return args.map(function (a) { return inspect(a); }).join(' ');\n  }\n  return f.replace(/%[sdjif%]/g, function (tok) {\n    if (tok === '%%') return '%';\n    var a = args.shift();\n    if (a === undefined) return tok;\n    if (tok === '%d') return String(Math.round(Number(a)));\n    if (tok === '%j') return JSON.stringify(a);\n    if (tok === '%i') return String(parseInt(a, 10));\n    return String(a);\n  });\n}\n\nfunction isArray(v) { return Array.isArray(v); }\nfunction isBoolean(v) { return typeof v === 'boolean'; }\nfunction isNull(v) { return v === null; }\nfunction isNullOrUndefined(v) { return v == null; }\nfunction isNumber(v) { return typeof v === 'number'; }\nfunction isString(v) { return typeof v === 'string'; }\nfunction isSymbol(v) { return typeof v === 'symbol'; }\nfunction isUndefined(v) { return typeof v === 'undefined'; }\nfunction isObject(v) { return v !== null && typeof v === 'object'; }\nfunction isFunction(v) { return typeof v === 'function'; }\nfunction isRegExp(v) { return v instanceof RegExp; }\nfunction isDate(v) { return v instanceof Date; }\nfunction isError(v) { return v instanceof Error; }\nfunction isBuffer(b) { return !!(b && b._isBuffer); }\n\nmodule.exports = {\n  inherits: inherits,\n  deprecate: deprecate,\n  inspect: inspect,\n  format: format,\n  isArray: isArray,\n  isBoolean: isBoolean,\n  isNull: isNull,\n  isNullOrUndefined: isNullOrUndefined,\n  isNumber: isNumber,\n  isString: isString,\n  isSymbol: isSymbol,\n  isUndefined: isUndefined,\n  isObject: isObject,\n  isFunction: isFunction,\n  isRegExp: isRegExp,\n  isDate: isDate,\n  isError: isError,\n  isBuffer: isBuffer\n};\n";
  var SHIM_EVENTS = "function EventEmitter() {\n  this._events = Object.create(null);\n  this._eventsCount = 0;\n  this._maxListeners = undefined;\n}\nEventEmitter.prototype._maxListenersDefault = 10;\nEventEmitter.defaultMaxListeners = 10;\n\nEventEmitter.prototype.setMaxListeners = function (n) {\n  this._maxListeners = n;\n  return this;\n};\nEventEmitter.prototype.getMaxListeners = function () {\n  return this._maxListeners === undefined ? EventEmitter.defaultMaxListeners : this._maxListeners;\n};\n\nEventEmitter.prototype.eventNames = function () {\n  return Object.keys(this._events);\n};\n\nfunction _getListeners(emitter, type) {\n  return emitter._events[type] || (emitter._events[type] = []);\n}\n\nEventEmitter.prototype.listeners = function (type) {\n  var arr = this._events[type];\n  return arr ? arr.slice() : [];\n};\nEventEmitter.prototype.rawListeners = EventEmitter.prototype.listeners;\nEventEmitter.prototype.listenerCount = function (type) {\n  var arr = this._events[type];\n  return arr ? arr.length : 0;\n};\nEventEmitter.listenerCount = function (emitter, type) {\n  return emitter.listenerCount(type);\n};\n\nfunction _addListener(emitter, type, fn, prepend) {\n  if (typeof fn !== 'function') throw new TypeError('listener must be a function');\n  var arr = _getListeners(emitter, type);\n  if (prepend) arr.unshift(fn); else arr.push(fn);\n  emitter._eventsCount = Object.keys(emitter._events).length;\n  return emitter;\n}\n\nEventEmitter.prototype.addListener = function (type, fn) { return _addListener(this, type, fn, false); };\nEventEmitter.prototype.on = EventEmitter.prototype.addListener;\nEventEmitter.prototype.prependListener = function (type, fn) { return _addListener(this, type, fn, true); };\nEventEmitter.prototype.once = function (type, fn) {\n  var self = this;\n  function g() {\n    self.removeListener(type, g);\n    fn.apply(this, arguments);\n  }\n  g.listener = fn;\n  _addListener(this, type, g, false);\n  return this;\n};\nEventEmitter.prototype.prependOnceListener = function (type, fn) {\n  var self = this;\n  function g() {\n    self.removeListener(type, g);\n    fn.apply(this, arguments);\n  }\n  g.listener = fn;\n  _addListener(this, type, g, true);\n  return this;\n};\n\nEventEmitter.prototype.removeListener = function (type, fn) {\n  var arr = this._events[type];\n  if (!arr) return this;\n  for (var i = arr.length - 1; i >= 0; i--) {\n    if (arr[i] === fn || (arr[i].listener && arr[i].listener === fn)) {\n      arr.splice(i, 1);\n      break;\n    }\n  }\n  if (arr.length === 0) delete this._events[type];\n  this._eventsCount = Object.keys(this._events).length;\n  return this;\n};\nEventEmitter.prototype.off = EventEmitter.prototype.removeListener;\n\nEventEmitter.prototype.removeAllListeners = function (type) {\n  if (type === undefined) { this._events = Object.create(null); }\n  else { delete this._events[type]; }\n  this._eventsCount = Object.keys(this._events).length;\n  return this;\n};\n\nEventEmitter.prototype.emit = function (type) {\n  var arr = this._events[type];\n  if (!arr || arr.length === 0) return false;\n  var args = Array.prototype.slice.call(arguments, 1);\n  var copy = arr.slice();\n  for (var i = 0; i < copy.length; i++) {\n    try { copy[i].apply(this, args); }\n    catch (e) {\n      if (type === 'error') throw e;\n      if (typeof console !== 'undefined' && console.error) console.error('Unhandled error in event listener:', e);\n    }\n  }\n  return true;\n};\n\nEventEmitter.once = function (emitter, name) {\n  return new Promise(function (resolve, reject) {\n    function errorListener(err) { emitter.removeListener(name, good); reject(err); }\n    function good() {\n      emitter.removeListener('error', errorListener);\n      resolve(Array.prototype.slice.call(arguments));\n    }\n    emitter.once(name, good);\n    emitter.once('error', errorListener);\n  });\n};\n\nmodule.exports = EventEmitter;\nmodule.exports.EventEmitter = EventEmitter;\nmodule.exports.once = EventEmitter.once;\n";

  // ---- ESM -> CJS: some nuvio providers ship as ES modules (import/export)
  // even though evalModule evaluates CommonJS. __nuvioCjsify rewrites those
  // statements into their CJS equivalents first (this is what let AllWish and
  // other phisher/eclipsia providers that use `import cheerio from ...` load).
  function __nuvioSkipWs(src, k) {
    var n = src.length;
    while (k < n) {
      var c = src[k];
      if (c === ' ' || c === '\t' || c === '\n' || c === '\r') { k++; continue; }
      if (c === '/' && src[k + 1] === '/') { var j = src.indexOf('\n', k); k = j < 0 ? n : j; continue; }
      if (c === '/' && src[k + 1] === '*') { var j2 = src.indexOf('*/', k + 2); k = j2 < 0 ? n : j2 + 2; continue; }
      break;
    }
    return k;
  }
  function __nuvioWordAt(src, k) {
    var m = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(src.slice(k));
    return m ? { word: m[0], end: k + m[0].length } : null;
  }
  function __nuvioReadStringLit(src, k) {
    var c = src[k], n = src.length, j = k + 1;
    while (j < n) {
      if (src[j] === '\\') { j += 2; continue; }
      if (src[j] === c) break;
      j++;
    }
    return { end: Math.min(j + 1, n) };
  }
  function __nuvioReadTemplateLit(src, k) {
    var j = k + 1, n = src.length;
    while (j < n) {
      if (src[j] === '\\') { j += 2; continue; }
      if (src[j] === '`') break;
      j++;
    }
    return { end: Math.min(j + 1, n) };
  }
  function __nuvioReadNamed(src, k) {
    var specs = [];
    var j = __nuvioSkipWs(src, k + 1);
    while (j < src.length) {
      var c = src[j];
      if (c === '}') break;
      if (c === ',') { j = __nuvioSkipWs(src, j + 1); continue; }
      var w = __nuvioWordAt(src, j);
      if (!w) { j++; continue; }
      var left = w.word;
      j = __nuvioSkipWs(src, w.end);
      if (src.slice(j, j + 2) === 'as') {
        j = __nuvioSkipWs(src, j + 2);
        var w2 = __nuvioWordAt(src, j);
        if (w2) { specs.push({ left: left, right: w2.word }); j = w2.end; }
        else { specs.push({ left: left, right: left }); }
      } else {
        specs.push({ left: left, right: left });
      }
      j = __nuvioSkipWs(src, j);
    }
    return { specs: specs, end: __nuvioSkipWs(src, j + 1) };
  }
  function __nuvioSkipSemi(src, k) {
    k = __nuvioSkipWs(src, k);
    if (src[k] === ';') k++;
    return k;
  }
  function __nuvioScanToSemi(src, k) {
    var n = src.length, depth = 0, j = k;
    while (j < n) {
      var c = src[j];
      if (c === '"' || c === "'") { j = __nuvioReadStringLit(src, j).end; continue; }
      if (c === '`') { j = __nuvioReadTemplateLit(src, j).end; continue; }
      if (c === '/' && src[j + 1] === '/') { var nl = src.indexOf('\n', j); j = nl < 0 ? n : nl; continue; }
      if (c === '/' && src[j + 1] === '*') { var bl = src.indexOf('*/', j + 2); j = bl < 0 ? n : bl + 2; continue; }
      if (c === '(' || c === '{' || c === '[') depth++;
      else if (c === ')' || c === '}' || c === ']') depth--;
      else if (c === ';' && depth === 0) { j++; break; }
      j++;
    }
    return { text: src.slice(k, j), end: j };
  }
  function __nuvioScanBalanced(src, k) {
    var n = src.length, depth = 0, j = k, sawBody = false;
    while (j < n) {
      var c = src[j];
      if (c === '"' || c === "'") { j = __nuvioReadStringLit(src, j).end; continue; }
      if (c === '`') { j = __nuvioReadTemplateLit(src, j).end; continue; }
      if (c === '/' && src[j + 1] === '/') { var nl = src.indexOf('\n', j); j = nl < 0 ? n : nl; continue; }
      if (c === '/' && src[j + 1] === '*') { var bl = src.indexOf('*/', j + 2); j = bl < 0 ? n : bl + 2; continue; }
      if (c === '{') { sawBody = true; depth++; }
      else if (c === '}' ) { depth--; if (sawBody && depth === 0) { j++; break; } }
      else if (c === '(' || c === '[') depth++;
      else if (c === ')' || c === ']') depth--;
      j++;
    }
    return { text: src.slice(k, j), end: j };
  }
  function __nuvioRewriteEs(src, start, kw) {
    var k = __nuvioSkipWs(src, start + kw.length);
    var out = '';
    if (kw === 'import') {
      var c = src[k];
      if (c === '"' || c === "'") {
        var lit = __nuvioReadStringLit(src, k);
        return { out: 'require(' + src.slice(k, lit.end) + ');', i: __nuvioSkipSemi(src, lit.end) };
      }
      var defaultName = null, nsName = null, named = null;
      if (c === '{') {
        named = __nuvioReadNamed(src, k);
        k = named.end;
      } else if (c === '*') {
        k = __nuvioSkipWs(src, k + 1);
        var wa = __nuvioWordAt(src, k);
        if (wa && wa.word === 'as') {
          k = __nuvioSkipWs(src, wa.end);
          var wb = __nuvioWordAt(src, k);
          if (wb) { nsName = wb.word; k = wb.end; }
        }
      } else {
        var w = __nuvioWordAt(src, k);
        if (w) {
          defaultName = w.word;
          k = w.end;
          var k2 = __nuvioSkipWs(src, k);
          if (src[k2] === ',') {
            k = __nuvioSkipWs(src, k2 + 1);
            if (src[k] === '{') { named = __nuvioReadNamed(src, k); k = named.end; }
            else if (src[k] === '*') {
              k = __nuvioSkipWs(src, k + 1);
              var wc = __nuvioWordAt(src, k);
              if (wc && wc.word === 'as') {
                k = __nuvioSkipWs(src, wc.end);
                var wd = __nuvioWordAt(src, k);
                if (wd) { nsName = wd.word; k = wd.end; }
              }
            }
          }
        }
      }
      k = __nuvioSkipWs(src, k);
      var wf = __nuvioWordAt(src, k);
      if (wf && wf.word === 'from') k = __nuvioSkipWs(src, wf.end);
      var mod = __nuvioReadStringLit(src, k);
      var modExpr = src.slice(k, mod.end);
      var end = __nuvioSkipSemi(src, mod.end);
      var parts = [];
      if (nsName) parts.push('var ' + nsName + ' = require(' + modExpr + ');');
      if (defaultName) {
        parts.push('var ' + defaultName + ' = (function(){ var m = require(' + modExpr + '); return (m && m.__esModule && m.default !== undefined) ? m.default : (m && m.default !== undefined) ? m.default : m; })();');
      }
      if (named) {
        parts.push('var __nuvioImp = require(' + modExpr + ');');
        for (var p = 0; p < named.specs.length; p++) {
          parts.push('var ' + named.specs[p].right + ' = __nuvioImp[' + JSON.stringify(named.specs[p].left) + '];');
        }
      }
      return { out: parts.join('\n'), i: end };
    }
    var w = __nuvioWordAt(src, k);
    if (src[k] === '{') {
      var ns2 = __nuvioReadNamed(src, k);
      var e2 = __nuvioSkipSemi(src, ns2.end);
      var o3 = '';
      for (var r = 0; r < ns2.specs.length; r++) o3 += 'exports.' + ns2.specs[r].right + ' = ' + ns2.specs[r].left + ';\n';
      return { out: o3, i: e2 };
    }
    if (src[k] === '*') {
      var st3 = __nuvioScanToSemi(src, k);
      return { out: '/* nuvio: export * from dropped */', i: st3.end };
    }
    if (!w) return { out: '', i: start + kw.length };
    var word = w.word;
    if (word === 'default') {
      var k2 = __nuvioSkipWs(src, w.end);
      var w2 = __nuvioWordAt(src, k2);
      if (w2 && (w2.word === 'function' || w2.word === 'class')) {
        var k3 = __nuvioSkipWs(src, w2.end);
        var w3 = __nuvioWordAt(src, k3);
        var name = null, k4 = k3;
        if (w3 && w3.word !== '(') { name = w3.word; k4 = w3.end; }
        var rest = __nuvioScanBalanced(src, k4);
        if (name) return { out: (w2.word === 'function' ? 'function ' : 'class ') + name + ' ' + rest.text + '\nexports.default = ' + name + ';', i: rest.end };
        return { out: 'exports.default = ' + (w2.word === 'function' ? 'function ' : 'class ') + rest.text + ';', i: rest.end };
      }
      var rex = __nuvioScanToSemi(src, k2);
      return { out: 'exports.default = ' + rex.text + ';', i: rex.end };
    }
    if (word === 'function' || word === 'class') {
      var k5 = __nuvioSkipWs(src, w.end);
      var w4 = __nuvioWordAt(src, k5);
      if (!w4) return { out: '', i: start + kw.length };
      var fname = w4.word;
      var frest = __nuvioScanBalanced(src, w4.end);
      return { out: (word === 'function' ? 'function ' : 'class ') + fname + ' ' + frest.text + '\nexports.' + fname + ' = ' + fname + ';', i: frest.end };
    }
    if (word === 'async') {
      var k6 = __nuvioSkipWs(src, w.end);
      var w5 = __nuvioWordAt(src, k6);
      if (w5 && w5.word === 'function') {
        var k7 = __nuvioSkipWs(src, w5.end);
        var w6 = __nuvioWordAt(src, k7);
        var aname = w6 ? w6.word : null;
        var k8 = w6 ? w6.end : k7;
        var arest = __nuvioScanBalanced(src, k8);
        var o = 'async function ' + (aname ? aname + ' ' : '') + arest.text;
        if (aname) o += '\nexports.' + aname + ' = ' + aname + ';';
        return { out: o, i: arest.end };
      }
    }
    if (word === 'const' || word === 'let' || word === 'var') {
      var k9 = __nuvioSkipWs(src, w.end);
      var vrest = __nuvioScanToSemi(src, k9);
      var names = [];
      var re = /[A-Za-z_$][A-Za-z0-9_$]*/g, m;
      while ((m = re.exec(vrest.text)) !== null) names.push(m[0]);
      var o2 = word + ' ' + vrest.text + '\n';
      for (var q = 0; q < names.length; q++) o2 += 'exports.' + names[q] + ' = ' + names[q] + ';\n';
      return { out: o2, i: vrest.end };
    }
    var uns = __nuvioScanToSemi(src, k);
    return { out: '/* nuvio: unsupported export dropped */', i: uns.end };
  }
  function __nuvioCjsify(source) {
    if (source.indexOf('import') < 0 && source.indexOf('export') < 0) return source;
    var n = source.length, i = 0, out = '', depth = 0;
    while (i < n) {
      var c = source[i];
      if (c === '/' && source[i + 1] === '/') {
        var j = source.indexOf('\n', i);
        if (j < 0) j = n;
        out += source.slice(i, j); i = j; continue;
      }
      if (c === '/' && source[i + 1] === '*') {
        var jb = source.indexOf('*/', i + 2);
        if (jb < 0) jb = n - 2;
        out += source.slice(i, jb + 2); i = jb + 2; continue;
      }
      if (c === '"' || c === "'") {
        var sl = __nuvioReadStringLit(source, i);
        out += source.slice(i, sl.end); i = sl.end; continue;
      }
      if (c === '`') {
        var tl = __nuvioReadTemplateLit(source, i);
        out += source.slice(i, tl.end); i = tl.end; continue;
      }
      if (c === '{') { depth++; out += c; i++; continue; }
      if (c === '}') { depth--; out += c; i++; continue; }
      if (depth === 0 && (c === 'i' || c === 'e')) {
        var prev = i > 0 ? source[i - 1] : ' ';
        if (!/[A-Za-z0-9_$]/.test(prev)) {
          var w = __nuvioWordAt(source, i);
          if (w && (w.word === 'import' || w.word === 'export')) {
            var after = __nuvioSkipWs(source, w.end);
            var ac = after < n ? source[after] : '';
            if (w.word === 'import') {
              if (ac === '{' || ac === '*' || ac === '"' || ac === "'" || /[A-Za-z_$]/.test(ac)) {
                var r = __nuvioRewriteEs(source, i, w.word);
                out += r.out; i = r.i; continue;
              }
            } else {
              if (ac === '{' || ac === '*' || ac === 'd' || /[A-Za-z_$]/.test(ac)) {
                var r2 = __nuvioRewriteEs(source, i, w.word);
                out += r2.out; i = r2.i; continue;
              }
            }
          }
        }
      }
      out += c;
      i++;
    }
    return out;
  }

  function evalModule(source) {
    if (source.charCodeAt(0) === 0x23 && source.charCodeAt(1) === 0x21) {
      var nl = source.indexOf('\n');
      source = nl >= 0 ? source.slice(nl + 1) : '';
    }
    var mod = { exports: {} };
    var fn = new Function('module', 'exports', 'require', '__dirname', '__filename', source);
    fn(mod, mod.exports, __nuvioRequire, '/', '/provider.js');
    return mod;
  }

  var __moduleCache = Object.create(null);

  function __nuvioRequire(name) {
    name = String(name);
    if (name === 'cheerio-without-node-native' || name === 'react-native-cheerio') name = 'cheerio';
    if (name === 'ws') name = 'ws';
    if (__moduleCache[name]) return __moduleCache[name].exports;
    if (name in __builtins) return __builtins[name];
    var err = new Error('Cannot find module ' + JSON.stringify(name));
    err.code = 'MODULE_NOT_FOUND';
    throw err;
  }

  function __nuvioProvideModule(name, source) {
    var mod = evalModule(source);
    __moduleCache[name] = mod;
    if (name === 'cheerio') {
      __moduleCache['cheerio-without-node-native'] = mod;
      __moduleCache['react-native-cheerio'] = mod;
    }
    return mod.exports;
  }

  // Registers a module whose exports were produced by loading the module source
  // as a plain <script> (runtime.html) instead of evalModule — used for the
  // big vendor libs (cheerio, crypto-js) so their content never travels
  // through evaluateJavascript, which has a practical message-size ceiling.
  function __nuvioRegisterModule(name, exports) {
    var mod = { exports: exports };
    __moduleCache[name] = mod;
    if (name === 'cheerio') {
      __moduleCache['cheerio-without-node-native'] = mod;
      __moduleCache['react-native-cheerio'] = mod;
    }
    return exports;
  }

  function __nuvioLoadProvider(source, id) {
    g.SCRAPER_ID = id;
    var mod = evalModule(__nuvioCjsify(source));
    var ex = mod.exports;
    if (typeof ex === 'function' && String(ex).indexOf('class ') !== 0 && typeof ex.getStreams !== 'function') ex = { getStreams: ex };
    else if (ex && typeof ex === 'object' && ex.default && typeof ex.default.getStreams === 'function' && typeof ex.getStreams !== 'function') {
      ex = ex.default;
    }
    return ex;
  }

  function stub(name) {
    var f = function () { throw new Error('module ' + name + ' is not available in this runtime'); };
    f[name] = f;
    return f;
  }

  var __builtins = {
    buffer: __moduleCache['buffer'] ? __moduleCache['buffer'].exports : null,
    process: __moduleCache['process'] ? __moduleCache['process'].exports : null,
    util: __moduleCache['util'] ? __moduleCache['util'].exports : null,
    events: __moduleCache['events'] ? __moduleCache['events'].exports : null,
    stream: {
      Readable: stub('stream.Readable'),
      Writable: stub('stream.Writable'),
      Duplex: stub('stream.Duplex'),
      Transform: stub('stream.Transform'),
      PassThrough: stub('stream.PassThrough'),
      EventEmitter: __moduleCache['events'] ? __moduleCache['events'].exports : null
    },
    path: (function () {
      function join() {
        var parts = [];
        for (var i = 0; i < arguments.length; i++) {
          var a = String(arguments[i]);
          if (a === '/' || a === '') continue;
          parts = parts.concat(a.split('/').filter(function (s) { return s !== '' && s !== '.'; }));
        }
        return '/' + parts.join('/');
      }
      return {
        join: join,
        dirname: function (p) { p = String(p); var i = p.lastIndexOf('/'); return i <= 0 ? '/' : p.slice(0, i) || '/'; },
        basename: function (p, ext) { p = String(p); var b = p.slice(p.lastIndexOf('/') + 1); if (ext && b.endsWith(ext)) b = b.slice(0, -ext.length); return b; },
        extname: function (p) { p = String(p); var b = p.slice(p.lastIndexOf('/') + 1); var i = b.lastIndexOf('.'); return i > 0 ? b.slice(i) : ''; },
        resolve: join,
        normalize: function (p) { return join(p); },
        isAbsolute: function (p) { return String(p).startsWith('/'); },
        sep: '/', delimiter: ':',
        posix: null
      };
    })(),
    querystring: {
      parse: function (q) {
        var o = {};
        if (!q) return o;
        String(q).replace(/^\?/, '').split('&').forEach(function (kv) {
          if (!kv) return;
          var eq = kv.indexOf('=');
          var k = eq >= 0 ? kv.slice(0, eq) : kv;
          var v = eq >= 0 ? kv.slice(eq + 1) : '';
          try { k = decodeURIComponent(k); v = decodeURIComponent(v); } catch (e) {}
          if (o[k] === undefined) o[k] = v; else if (Array.isArray(o[k])) o[k].push(v); else o[k] = [o[k], v];
        });
        return o;
      },
      stringify: function (o) {
        var parts = [];
        for (var k in o) {
          var v = o[k];
          if (Array.isArray(v)) v.forEach(function (x) { parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(x)); });
          else parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
        }
        return parts.join('&');
      },
      decode: function (q) { return this.parse(q); },
      encode: function (o) { return this.stringify(o); }
    },
    qs: null,
    url: (function () {
      var UP = typeof URL !== 'undefined' ? URL : null;
      var US = typeof URLSearchParams !== 'undefined' ? URLSearchParams : null;
      return {
        URL: UP, URLSearchParams: US,
        parse: function (u) {
          u = String(u);
          var m = u.match(/^(?:([a-z][a-z0-9+.-]*):)?(?:\/\/([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/i);
          if (!m) return { href: u };
          var host = m[2] || '';
          var hp = host.lastIndexOf(':');
          var hostname = hp > 0 && /^\d/.test(host.slice(hp + 1)) === false && host.indexOf(']') === -1 && hp === host.lastIndexOf(':') ? host.slice(0, hp) : host;
          var port = hp > 0 && host.indexOf(']') === -1 ? host.slice(hp + 1) : '';
          if (/^\d+$/.test(port) === false) { hostname = host; port = ''; }
          return {
            protocol: m[1] ? m[1] + ':' : null,
            slashes: !!m[2],
            auth: null, host: host, port: port || null,
            hostname: hostname || null,
            hash: m[5] ? '#' + m[5] : null,
            search: m[4] ? '?' + m[4] : null,
            query: m[4] || null,
            pathname: m[3] || null,
            path: (m[3] || '') + (m[4] ? '?' + m[4] : ''),
            href: u
          };
        },
        format: function (o) {
          var out = (o.protocol ? o.protocol.replace(/:$/, '') + ':' : '') + (o.slashes ? '//' : '');
          if (o.auth) out += o.auth + '@';
          out += o.hostname || o.host || '';
          if (o.port) out += ':' + o.port;
          out += o.pathname || o.path || '';
          if (o.search) out += String(o.search).startsWith('?') ? o.search : '?' + o.search;
          else if (o.query && typeof o.query === 'string') out += '?' + o.query;
          else if (o.query && typeof o.query === 'object') out += '?' + new US(o.query).toString();
          if (o.hash) out += String(o.hash).startsWith('#') ? o.hash : '#' + o.hash;
          return out;
        },
        resolve: function (from, to) {
          try { return new UP(String(to), String(from)).href; } catch (e) { return String(to); }
        },
        resolveObject: function (from, to) { return this.parse(this.resolve(from, to)); },
        Url: function () {}
      };
    })(),
    os: {
      platform: 'android', EOL: '\n', arch: function () { return 'arm64'; }, type: function () { return 'Android'; },
      endianness: function () { return 'LE'; }, tmpdir: function () { return '/tmp'; }, hostname: function () { return ''; },
      homedir: function () { return '/'; }, cpus: function () { return [{ model: '', speed: 0 }]; },
      totalmem: function () { return 0; }, freemem: function () { return 0; }, release: function () { return ''; },
      networkInterfaces: function () { return {}; }, userInfo: function () { return { username: '', uid: 0, gid: 0 }; }
    },
    crypto: (function () {
      function randomBytes(n) {
        var b = new Uint8Array(n);
        for (var i = 0; i < n; i++) b[i] = Math.floor(Math.random() * 256);
        return g.Buffer.from(b);
      }
      return {
        randomBytes: randomBytes,
        randomFillSync: randomBytes,
        getRandomValues: function (arr) { for (var i = 0; i < arr.length; i++) arr[i] = Math.floor(Math.random() * 4294967296); return arr; },
        createHash: function (alg) { throw new Error('crypto.createHash not supported - use crypto-js'); },
        createHmac: function (alg, key) { throw new Error('crypto.createHmac not supported - use crypto-js'); },
        timingSafeEqual: function (a, b) { return a.equals(b); }
      };
    })(),
    axios: (function () {
      function perform(config) {
        var url = config.url || '';
        var method = (config.method || 'GET').toUpperCase();
        var headers = config.headers || {};
        var body;
        if (config.data != null) {
          body = typeof config.data === 'string' ? config.data : JSON.stringify(config.data);
          if (typeof config.data !== 'string' && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
        }
        return __nuvioFetch(url, { method: method, headers: headers, body: body }).then(function (res) {
          return res.text().then(function (text) {
            var data = text;
            var ct = (res.headers.get('content-type') || '').toLowerCase();
            if (ct.indexOf('json') !== -1 && text) { try { data = JSON.parse(text); } catch (e) {} }
            return { data: data, status: res.status, statusText: res.statusText, headers: res.headers, config: config, request: {} };
          });
        });
      }
      function request(config) { return perform(config); }
      function get(url, config) { return perform(Object.assign({}, config, { url: url, method: 'GET' })); }
      function del(url, config) { return perform(Object.assign({}, config, { url: url, method: 'DELETE' })); }
      function head(url, config) { return perform(Object.assign({}, config, { url: url, method: 'HEAD' })); }
      function options(url, config) { return perform(Object.assign({}, config, { url: url, method: 'OPTIONS' })); }
      function post(url, data, config) { return perform(Object.assign({}, config, { url: url, data: data, method: 'POST' })); }
      function put(url, data, config) { return perform(Object.assign({}, config, { url: url, data: data, method: 'PUT' })); }
      function patch(url, data, config) { return perform(Object.assign({}, config, { url: url, data: data, method: 'PATCH' })); }
      function makeInstance(defaults) {
        var instance = function (config) { return perform(Object.assign({}, defaults, config)); };
        instance.defaults = Object.assign({ headers: { common: {}, get: {}, post: {}, put: {}, delete: {} } }, defaults);
        instance.get = function (u, c) { return perform(Object.assign({}, defaults, c, { url: u, method: 'GET' })); };
        instance.post = function (u, d, c) { return perform(Object.assign({}, defaults, c, { url: u, data: d, method: 'POST' })); };
        instance.put = function (u, d, c) { return perform(Object.assign({}, defaults, c, { url: u, data: d, method: 'PUT' })); };
        instance.patch = function (u, d, c) { return perform(Object.assign({}, defaults, c, { url: u, data: d, method: 'PATCH' })); };
        instance.delete = function (u, c) { return perform(Object.assign({}, defaults, c, { url: u, method: 'DELETE' })); };
        instance.head = function (u, c) { return perform(Object.assign({}, defaults, c, { url: u, method: 'HEAD' })); };
        instance.request = request;
        instance.create = function (c) { return makeInstance(Object.assign({}, defaults, c)); };
        instance.interceptors = { request: { use: function () {} }, response: { use: function () {} } };
        return instance;
      }
      var axios = makeInstance({});
      axios.request = request;
      axios.get = get;
      axios.delete = del;
      axios.head = head;
      axios.options = options;
      axios.post = post;
      axios.put = put;
      axios.patch = patch;
      axios.create = makeInstance;
      axios.all = function (promises) { return Promise.all(promises); };
      axios.spread = function (cb) { return function (arr) { return cb.apply(null, arr); }; };
      axios.default = axios;
      axios.Axios = function () {};
      axios.CancelToken = function () {};
      axios.isCancel = function () { return false; };
      return axios;
    })(),
    http: stub('http'),
    https: stub('https'),
    fs: stub('fs'),
    zlib: stub('zlib'),
    net: stub('net'),
    tls: stub('tls'),
    assert: stub('assert'),
    tty: stub('tty'),
    dns: stub('dns'),
    child_process: stub('child_process'),
    timers: stub('timers'),
    constants: {},
    punycode: {},
    string_decoder: {}
  };
  __builtins.qs = __builtins.querystring;
  __builtins['ws'] = (function () {
    if (typeof WebSocket !== 'undefined') {
      function WrappedWebSocket() { return new (Function.prototype.bind.apply(WebSocket, [null].concat(Array.prototype.slice.call(arguments))))(); }
      try {
        var w = {};
        w.WebSocket = WebSocket;
        w.default = WebSocket;
        return w;
      } catch (e) {}
    }
    return stub('ws');
  })();
  if (typeof atob === 'undefined') {
    g.atob = function (s) { return decodeURIComponent(Array.prototype.map.call(g.Buffer.from(String(s), 'base64').toString('latin1'), function (c) { return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2); }).join('')); };
    g.btoa = function (s) { return g.Buffer.from(String(s), 'latin1').toString('base64'); };
  }

  function __normalizeHeaders(h) {
    var out = {};
    if (h == null) return out;
    if (typeof Headers !== 'undefined' && h instanceof Headers) {
      h.forEach(function (v, k) { out[k] = v; });
      return out;
    }
    if (Array.isArray(h)) {
      for (var i = 0; i < h.length; i++) { var e = h[i]; if (e && e.length >= 2) out[e[0]] = String(e[1]); }
      return out;
    }
    if (typeof h === 'object') {
      for (var k in h) if (h[k] != null) out[k] = String(h[k]);
      return out;
    }
    return out;
  }

  function __parseBridgePayload(raw, url) {
    if (raw == null) return { ok: false, status: 0, statusText: 'network error', url: url || '', body: '', headers: {} };
    if (typeof raw === 'object') return raw;
    try { return JSON.parse(raw); } catch (e) {
      return { ok: false, status: 0, statusText: 'bad bridge response', url: url || '', body: String(raw), headers: {} };
    }
  }

  function __base64ToBytes(b64) {
    var bin;
    if (typeof atob !== 'undefined') bin = atob(String(b64));
    else if (g.Buffer && typeof g.Buffer.from === 'function') bin = g.Buffer.from(String(b64), 'base64').toString('latin1');
    else throw new Error('no base64 decoder available');
    var len = bin.length;
    var bytes = new Uint8Array(len);
    for (var i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  function __makeResponse(payload, url) {
    var headers = {};
    if (payload.headers && typeof payload.headers === 'object') {
      for (var k in payload.headers) headers[String(k).toLowerCase()] = String(payload.headers[k]);
    }
    var status = typeof payload.status === 'number' ? payload.status : 0;
    var body = typeof payload.body === 'string' ? payload.body : '';
    var bodyBytes = null;
    if (payload.bodyBase64) {
      try { bodyBytes = __base64ToBytes(payload.bodyBase64); } catch (e) { bodyBytes = null; }
    }
    return {
      ok: status >= 200 && status < 300,
      status: status,
      statusText: payload.statusText || '',
      url: payload.url || url || '',
      headers: {
        get: function (n) { var v = headers[String(n).toLowerCase()]; return v === undefined ? null : v; },
        has: function (n) { return headers[String(n).toLowerCase()] !== undefined; },
        forEach: function (cb) { for (var h in headers) cb(headers[h], h, headers); }
      },
      text: function () { return Promise.resolve(body); },
      json: function () {
        try { return Promise.resolve(JSON.parse(body)); } catch (e) { return Promise.reject(new Error('invalid json: ' + e.message)); }
      },
      arrayBuffer: function () {
        if (bodyBytes) {
          var copy = new Uint8Array(bodyBytes.length);
          copy.set(bodyBytes);
          return Promise.resolve(copy.buffer);
        }
        var enc = new TextEncoder();
        return Promise.resolve(enc.encode(body).buffer);
      },
      blob: function () {
        if (bodyBytes) return Promise.resolve(new Blob([bodyBytes], { type: headers['content-type'] || '' }));
        return Promise.resolve(new Blob([body], { type: headers['content-type'] || '' }));
      },
      clone: function () { return __makeResponse(payload, url); }
    };
  }

  function __bridgeFetch(url, method, headersJson, body, followRedirects) {
    if (typeof NuvioBridge !== 'undefined' && typeof NuvioBridge.fetch === 'function') {
      return NuvioBridge.fetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);
    }
    if (typeof g.__nuvioFetchImpl === 'function') {
      return g.__nuvioFetchImpl(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);
    }
    throw new Error('no fetch bridge available');
  }

  function __nuvioFetch(input, init) {
    return new Promise(function (resolve, reject) {
      try {
        var url = (typeof input === 'object' && input !== null && input.url) ? input.url : String(input);
        var method = 'GET';
        var headers = {};
        var body = null;
        var followRedirects = true;
        if (init) {
          if (init.method) method = String(init.method).toUpperCase();
          if (init.headers) headers = __normalizeHeaders(init.headers);
          if (init.body != null) body = String(init.body);
          if (init.redirect === 'manual' || init.redirect === 'error') followRedirects = false;
        }
        var payload;
        try {
          var raw = __bridgeFetch(url, method, JSON.stringify(headers), body, followRedirects);
          payload = __parseBridgePayload(raw, url);
        } catch (e) {
          reject(e);
          return;
        }
        Promise.resolve(payload).then(function (p) {
          resolve(__makeResponse(__nuvioIntercept(url, method, headers, body, followRedirects, p), url));
        }, function (e) { reject(e); });
      } catch (e) { reject(e); }
    });
  }
  g.fetch = __nuvioFetch;

  // ---- response interceptors -------------------------------------------------
  // Several nuvio providers lean on APIs that are flaky or whose responses
  // omit fields the provider needs, and each provider bails to [] when its
  // helper returns null. Instead of patching every provider, the harness
  // rewrites those responses transparently:
  //   * TMDB — if a /tv|movie/<id> or /external_ids call fails (revoked /
  //     rate-limited api_key, network blip), retry it with Hikari's own
  //     working keys. And if a main-details response is missing `imdb_id`,
  //     fetch it from /external_ids and inject it (some anime providers read
  //     `.imdb_id` from the MAIN endpoint and bail with [] when it's absent).
  //   * Jikan (api.jikan.moe) — when it's down / 504ing (it frequently fails
  //     to reach MyAnimeList), synthesize the Jikan-shaped response from
  //     AniList's GraphQL API instead.
  g.__nuvioTmdbKeys = ["68e094699525b18a70bab2f86b1fa706", "439c478a771f35c05022f9feabcca01c"];

  function __nuvioIntercept(url, method, headers, body, followRedirects, p) {
    try {
      var tmdb = __nuvioTmdbMatch(url);
      if (tmdb) {
        var fixed = __nuvioFixTmdb(url, method, followRedirects, p, tmdb);
        if (fixed) p = fixed;
      }
      if (/^https?:\/\/api\.jikan\.moe\/v4\/anime/i.test(url)) {
        var j = __nuvioJikanFallback(url, method, followRedirects, p);
        if (j) p = j;
      }
    } catch (e) {
      // An interceptor bug must never break the provider's original response.
    }
    return p;
  }

  function __nuvioTryParse(p) {
    if (!p || typeof p.body !== 'string') return null;
    if (!(p.status >= 200 && p.status < 300)) return null;
    try {
      var o = JSON.parse(p.body);
      return o && typeof o === 'object' ? o : null;
    } catch (e) { return null; }
  }

  function __nuvioKeyFromUrl(url) {
    var m = url.match(/[?&]api_key=([^&]+)/);
    return m ? decodeURIComponent(m[1]) : null;
  }

  function __nuvioReplaceKey(url, key) {
    if (/[?&]api_key=/.test(url)) return url.replace(/([?&]api_key=)[^&]*/, '$1' + encodeURIComponent(key));
    return url + (url.indexOf('?') >= 0 ? '&' : '?') + 'api_key=' + encodeURIComponent(key);
  }

  function __nuvioTmdbMatch(url) {
    var m = url.match(/^https?:\/\/api\.themoviedb\.org\/3\/(tv|movie)\/(\d+)(\/external_ids)?(?:\?|$)/i);
    if (!m) return null;
    return { type: m[1], id: m[2], isExternal: !!m[3] };
  }

  function __nuvioFixTmdb(url, method, followRedirects, p, tmdb) {
    if (method !== 'GET') return null;
    var origKey = __nuvioKeyFromUrl(url);
    var key = origKey;
    var q = p;
    var obj = __nuvioTryParse(q);
    var failed = !obj || (typeof q.body === 'string' && /invalid api key/i.test(q.body));
    var changed = false;
    if (failed) {
      // Original call failed → retry with Hikari's own keys.
      var keys = g.__nuvioTmdbKeys || [];
      for (var i = 0; i < keys.length; i++) {
        if (keys[i] === origKey) continue;
        var altUrl = __nuvioReplaceKey(url, keys[i]);
        var alt = null;
        try { alt = __parseBridgePayload(__bridgeFetch(altUrl, 'GET', '{}', '', followRedirects), altUrl); } catch (e) { alt = null; }
        var altObj = __nuvioTryParse(alt);
        if (altObj) { q = alt; obj = altObj; key = keys[i]; failed = false; changed = true; break; }
      }
      if (failed) return null; // nothing fixed — leave the provider's response alone
    }
    // Main-details response missing imdb_id → pull it from /external_ids.
    if (!tmdb.isExternal && obj && typeof obj === 'object' && !obj.imdb_id) {
      var extUrl = 'https://api.themoviedb.org/3/' + tmdb.type + '/' + tmdb.id + '/external_ids?api_key=' + (key || '');
      var ext = null;
      try { ext = __parseBridgePayload(__bridgeFetch(extUrl, 'GET', '{}', '', followRedirects), extUrl); } catch (e) { ext = null; }
      var extObj = __nuvioTryParse(ext);
      if (extObj && extObj.imdb_id) { obj.imdb_id = extObj.imdb_id; changed = true; }
    }
    if (!changed) return null;
    var out = {};
    for (var kk in q) if (Object.prototype.hasOwnProperty.call(q, kk)) out[kk] = q[kk];
    out.body = JSON.stringify(obj);
    return out;
  }

  function __nuvioGraphQL(query, vars) {
    try {
      var bodyStr = JSON.stringify({ query: query, variables: vars || {} });
      var raw = __bridgeFetch(
        'https://graphql.anilist.co', 'POST',
        JSON.stringify({ 'Content-Type': 'application/json', 'Accept': 'application/json' }),
        bodyStr, true
      );
      return __nuvioTryParse(__parseBridgePayload(raw, 'https://graphql.anilist.co'));
    } catch (e) { return null; }
  }

  function __nuvioJikanFallback(url, method, followRedirects, p) {
    if (method !== 'GET') return null;
    if (__nuvioTryParse(p)) return null; // Jikan answered fine — pass through.
    var mId = url.match(/^https?:\/\/api\.jikan\.moe\/v4\/anime\/(\d+)/i);
    var mSearch = url.match(/^https?:\/\/api\.jikan\.moe\/v4\/anime\?/i);
    var synth = null;
    if (mId) {
      var gql = 'query ($id: Int) { Media(idMal: $id, type: ANIME) { idMal title { romaji english } } }';
      var r = __nuvioGraphQL(gql, { id: parseInt(mId[1], 10) });
      if (r && r.data && r.data.Media) {
        var t = r.data.Media.title || {};
        synth = { data: { mal_id: r.data.Media.idMal, title: t.english || t.romaji || '' } };
      }
    } else if (mSearch) {
      var qm = url.match(/[?&]q=([^&]*)/i);
      var title = qm ? decodeURIComponent(qm[1].replace(/\+/g, ' ')) : '';
      if (title) {
        var typeMovie = /[?&]type=movie/i.test(url);
        var q2 = 'query ($s: String) { Media(search: $s, type: ANIME' + (typeMovie ? ', format: MOVIE' : '') + ') { idMal } }';
        var r2 = __nuvioGraphQL(q2, { s: title });
        if (r2 && r2.data && r2.data.Media) synth = { data: [{ mal_id: r2.data.Media.idMal }] };
      }
    }
    if (!synth) return null;
    var out = {};
    for (var kk in p) if (Object.prototype.hasOwnProperty.call(p, kk)) out[kk] = p[kk];
    out.status = 200;
    out.statusText = 'OK';
    out.body = JSON.stringify(synth);
    return out;
  }

  var __currentProvider = null;
  var __currentProviderId = null;

  function __runProvider(source, id, cid, tmdbId, mediaType, season, episode) {
    g.SCRAPER_SETTINGS = g.__nuvioSettings || {};
    var provider;
    try {
      provider = __nuvioLoadProvider(source, id);
    } catch (e) {
      __bridge().onGetStreamsDone(cid, JSON.stringify({ ok: false, error: 'load error: ' + (e && e.message || e) }));
      return;
    }
    __currentProvider = provider;
    __currentProviderId = id;
    var getStreams = (provider && typeof provider.getStreams === 'function') ? provider.getStreams : g.getStreams;
    if (typeof getStreams !== 'function') {
      __bridge().onGetStreamsDone(cid, JSON.stringify({ ok: false, error: 'provider has no getStreams export' }));
      return;
    }
    var done = false;
    var timer = setTimeout(function () {
      if (!done) { done = true; __bridge().onGetStreamsDone(cid, JSON.stringify({ ok: false, error: 'timeout' })); }
    }, 60000);
    Promise.resolve()
      .then(function () { return getStreams(tmdbId, mediaType, season, episode); })
      .then(function (result) {
        if (done) return;
        done = true; clearTimeout(timer);
        if (result === undefined || result === null) result = [];
        __bridge().onGetStreamsDone(cid, JSON.stringify({ ok: true, data: result }));
      })
      .catch(function (e) {
        if (done) return;
        done = true; clearTimeout(timer);
        __bridge().onGetStreamsDone(cid, JSON.stringify({ ok: false, error: String(e && e.stack || e) }));
      });
  }

  function __runSettings(source, id, cid) {
    var provider;
    try {
      provider = __nuvioLoadProvider(source, id);
    } catch (e) {
      __bridge().onSettingsDone(cid, JSON.stringify({ ok: false, error: 'load error: ' + (e && e.message || e) }));
      return;
    }
    __currentProvider = provider;
    __currentProviderId = id;
    var onSettings = (provider && typeof provider.onSettings === 'function') ? provider.onSettings : null;
    if (!onSettings) {
      __bridge().onSettingsDone(cid, JSON.stringify({ ok: true, data: [] }));
      return;
    }
    Promise.resolve()
      .then(function () { return onSettings(); })
      .then(function (layout) {
        __bridge().onSettingsDone(cid, JSON.stringify({ ok: true, data: layout || [] }));
      })
      .catch(function (e) {
        __bridge().onSettingsDone(cid, JSON.stringify({ ok: false, error: String(e && e.stack || e) }));
      });
  }

  function __bridge() {
    if (typeof NuvioBridge !== 'undefined') return NuvioBridge;
    if (typeof g.__nuvioBridgeStub === 'object') return g.__nuvioBridgeStub;
    return { onGetStreamsDone: function () {}, onSettingsDone: function () {}, fetch: null, log: function () {} };
  }

  g.__nuvioFetch = __nuvioFetch;
  g.__nuvioRequire = __nuvioRequire;
  g.__nuvioProvideModule = __nuvioProvideModule;
  g.__nuvioRegisterModule = __nuvioRegisterModule;
  g.__nuvioLoadProvider = __nuvioLoadProvider;
  g.__nuvioCjsify = __nuvioCjsify;
  g.__nuvioRunProvider = __runProvider;
  g.__nuvioRunSettings = __runSettings;
  g.__nuvioGetProvider = function () { return __currentProvider; };
  g.__nuvioGetProviderId = function () { return __currentProviderId; };
  g.__nuvioSetSettings = function (settings) { g.__nuvioSettings = settings || {}; g.SCRAPER_SETTINGS = settings || {}; };

  __nuvioProvideModule('buffer', SHIM_BUFFER);
  __nuvioProvideModule('process', SHIM_PROCESS);
  __nuvioProvideModule('util', SHIM_UTIL);
  __nuvioProvideModule('events', SHIM_EVENTS);

  g.Buffer = __moduleCache['buffer'].exports;
  g.Buffer.Buffer = g.Buffer;
  g.process = __moduleCache['process'].exports;
  __builtins.buffer = __moduleCache['buffer'].exports;
  __builtins.process = __moduleCache['process'].exports;
  __builtins.util = __moduleCache['util'].exports;
  __builtins.events = __moduleCache['events'].exports;

  if (typeof console !== 'undefined' && console.log) {
    console.log('[nuvio] harness ready, version 3');
  }
})();
