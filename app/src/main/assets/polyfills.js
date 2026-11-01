(function () {
  function def(o, k, v) { if (o && !o[k]) Object.defineProperty(o, k, { value: v, writable: true, configurable: true }); }
  var TA = Object.getPrototypeOf(Int8Array.prototype);
  [Array.prototype, TA].forEach(function (P) {
    def(P, 'at', function (i) { i = Math.trunc(i) || 0; if (i < 0) i += this.length; return this[i]; });
    def(P, 'findLast', function (f, t) { for (var i = this.length - 1; i >= 0; i--) if (f.call(t, this[i], i, this)) return this[i]; });
    def(P, 'findLastIndex', function (f, t) { for (var i = this.length - 1; i >= 0; i--) if (f.call(t, this[i], i, this)) return i; return -1; });
    def(P, 'toSorted', function (c) { return this.slice().sort(c); });
    def(P, 'toReversed', function () { return this.slice().reverse(); });
    def(P, 'with', function (i, v) { var a = this.slice(); if (i < 0) i += a.length; a[i] = v; return a; });
  });
  def(Array.prototype, 'toSpliced', function () { var a = this.slice(); a.splice.apply(a, arguments); return a; });
  def(Array.prototype, 'flat', function (d) { d = d === undefined ? 1 : d; return d < 1 ? this.slice() : this.reduce(function (acc, v) { return acc.concat(Array.isArray(v) ? v.flat(d - 1) : v); }, []); });
  def(Array.prototype, 'flatMap', function (f, t) { return this.map(f, t).flat(); });
  def(String.prototype, 'at', function (i) { i = Math.trunc(i) || 0; if (i < 0) i += this.length; return this.charAt(i) || undefined; });
  def(String.prototype, 'replaceAll', function (s, r) { return s instanceof RegExp ? this.replace(s, r) : this.split(s).join(typeof r === 'function' ? r(s) : r); });
  def(Object, 'hasOwn', function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); });
  def(Object, 'fromEntries', function (it) { var o = {}; for (var e of it) o[e[0]] = e[1]; return o; });

  if (typeof Promise.any !== 'function') Promise.any = function (ps) { return new Promise(function (res, rej) { var n = 0, errs = []; ps = Array.from(ps); if (!ps.length) rej(new Error('All promises were rejected')); ps.forEach(function (p, i) { Promise.resolve(p).then(res, function (e) { errs[i] = e; if (++n === ps.length) rej(new Error('All promises were rejected')); }); }); }); };
})();
