(function () {
  'use strict';
  // Launcher catalogue. Editable: add or reorder entries here. No third-party logos are bundled; tiles use
  // brand-coloured monograms. `multi` services can run several isolated accounts; `safe` marks wallets/banks.
  var GROUPS = {
    money: [
      { id: 'gcash', n: 'GCash', m: 'G', c: '#0a6cff', u: 'https://www.gcash.com/', safe: true },
      { id: 'maya', n: 'Maya', m: 'M', c: '#00a651', u: 'https://www.maya.ph/', safe: true }
    ],
    shop: [
      { id: 'shopee', n: 'Shopee', m: 'S', c: '#ee4d2d', u: 'https://shopee.ph/', multi: true },
      { id: 'lazada', n: 'Lazada', m: 'L', c: '#1a237e', u: 'https://www.lazada.com.ph/', multi: true },
      { id: 'tiktokshop', n: 'TikTok Shop', m: 'TS', c: '#111111', u: 'https://www.tiktok.com/shop', multi: true }
    ],
    social: [
      { id: 'facebook', n: 'Facebook Lite', m: 'f', c: '#1877f2', u: 'https://m.facebook.com/', multi: true },
      { id: 'messenger', n: 'Messenger', m: 'Me', c: '#a334fa', u: 'https://www.messenger.com/', multi: true },
      { id: 'tiktok', n: 'TikTok Lite', m: 'T', c: '#161616', u: 'https://www.tiktok.com/', multi: true }
    ],
    news: [
      { id: 'pagasa', n: 'PAGASA', m: 'P', c: '#0d9488', u: 'https://www.pagasa.dost.gov.ph/' },
      { id: 'abscbn', n: 'ABS-CBN News', m: 'AB', c: '#2563eb', u: 'https://news.abs-cbn.com/' },
      { id: 'gma', n: 'GMA News', m: 'GM', c: '#dc2626', u: 'https://www.gmanetwork.com/news/' },
      { id: 'inquirer', n: 'Inquirer', m: 'I', c: '#334155', u: 'https://www.inquirer.net/' }
    ]
  };

  var state = { containers: [{ id: 'default', label: 'Main' }], supported: false };
  var LS = 'yh.last.';
  var $ = function (id) { return document.getElementById(id); };

  function lastFor(svc) {
    try { return localStorage.getItem(LS + svc) || 'default'; } catch (e) { return 'default'; }
  }
  function setLast(svc, c) { try { localStorage.setItem(LS + svc, c); } catch (e) { /* storage may be unavailable */ } }
  function labelOf(id) {
    for (var i = 0; i < state.containers.length; i++) if (state.containers[i].id === id) return state.containers[i].label;
    return 'Main';
  }
  function go(url, c) {
    location.href = 'yeehaw://open?url=' + encodeURIComponent(url) + '&c=' + encodeURIComponent(c || 'default');
  }

  function tile(s) {
    var a = document.createElement('a');
    a.className = 'tile';
    a.href = '#';
    a.setAttribute('role', 'button');
    var c = s.multi ? lastFor(s.id) : 'default';
    if (!state.containers.some(function (x) { return x.id === c; })) c = 'default';
    var html = '<div class="ico" style="background:' + s.c + '">' + s.m + '</div><div class="name"></div>';
    if (s.safe) html += '<span class="lock" title="Protected">\uD83D\uDD12</span>';
    if (s.multi && c !== 'default') html += '<span class="acct"></span>';
    a.innerHTML = html;
    a.querySelector('.name').textContent = s.n;
    var badge = a.querySelector('.acct');
    if (badge) badge.textContent = labelOf(c).slice(0, 6);

    var timer = null, long = false;
    function start() {
      long = false;
      if (!s.multi || !state.supported) return;
      timer = setTimeout(function () { long = true; openSheet(s); }, 450);
    }
    function end() { clearTimeout(timer); }
    a.addEventListener('touchstart', start, { passive: true });
    a.addEventListener('touchend', end);
    a.addEventListener('touchmove', end, { passive: true });
    a.addEventListener('touchcancel', end);
    a.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    a.addEventListener('click', function (e) {
      e.preventDefault();
      if (long) { long = false; return; }
      go(s.u, c);
    });
    return a;
  }

  function render() {
    Object.keys(GROUPS).forEach(function (g) {
      var el = $('grid-' + g);
      el.textContent = '';
      GROUPS[g].forEach(function (s) { el.appendChild(tile(s)); });
    });
  }

  var sheetSvc = null;
  function openSheet(s) {
    sheetSvc = s;
    $('sheetTitle').textContent = s.n + ' \u2014 choose account';
    var list = $('sheetList');
    list.textContent = '';
    var cur = lastFor(s.id);
    state.containers.forEach(function (c) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'opt' + (c.id === cur ? ' on' : '');
      b.textContent = c.label;
      b.addEventListener('click', function () { setLast(s.id, c.id); closeSheet(); go(s.u, c.id); });
      list.appendChild(b);
    });
    $('sheet').hidden = false;
  }
  function closeSheet() { $('sheet').hidden = true; sheetSvc = null; render(); }

  $('sheetClose').addEventListener('click', closeSheet);
  $('newBtn').addEventListener('click', function () {
    var label = $('newLabel').value.trim();
    if (!label || !sheetSvc) return;
    var url = sheetSvc.u;
    $('newLabel').value = '';
    $('sheet').hidden = true;
    location.href = 'yeehaw://container-new?label=' + encodeURIComponent(label) + '&url=' + encodeURIComponent(url);
  });

  $('searchForm').addEventListener('submit', function (e) {
    e.preventDefault();
    var q = $('q').value.trim();
    if (q) location.href = 'yeehaw://go?q=' + encodeURIComponent(q);
  });

  function greeting() {
    var h = new Date().getHours();
    $('greet').textContent = h < 12 ? 'Magandang umaga!' : h < 18 ? 'Magandang hapon!' : 'Magandang gabi!';
  }

  // Called by the native shell (Activity) with live state after each start-page load.
  window.YH = {
    init: function (s) {
      if (s && s.containers && s.containers.length) state.containers = s.containers;
      state.supported = !!(s && s.containersSupported);
      $('defaultCta').hidden = !!(s && s.defaultBrowser);
      var n = (s && s.blocked) | 0;
      $('stats').textContent = n > 0 ? n.toLocaleString() + ' ads & trackers blocked' : '';
      render();
    }
  };

  greeting();
  render();
})();
