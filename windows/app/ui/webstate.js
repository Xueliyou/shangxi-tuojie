/* 内置网页的「状态记忆」
   把你在页面里的选择（解析类型 / 音质 / 关键词 / 滚动位置）记下来，
   下次进来——哪怕 App 被杀掉重启——自动帮你还原。

   注意：只记录表单选择，不碰任何账号数据。 */
(function () {
  if (window.__dshStateHook) return;
  window.__dshStateHook = 1;

  var KEY = '__dsh_ui_state__';
  var restoring = false;
  var last = '';

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function waitFor(fn, timeout) {
    var t0 = Date.now();
    return new Promise(function (resolve) {
      (function loop() {
        var v = false;
        try { v = fn(); } catch (e) {}
        if (v) return resolve(true);
        if (Date.now() - t0 > (timeout || 8000)) return resolve(false);
        setTimeout(loop, 150);
      })();
    });
  }

  /* ---------------- 采集 ---------------- */

  function selectNodes() {
    var list = [].slice.call(document.querySelectorAll('.ant-select'));
    if (!list.length) list = [].slice.call(document.querySelectorAll('select'));
    return list;
  }

  function selectText(el) {
    if (!el) return '';
    if (el.tagName === 'SELECT') {
      var o = el.options[el.selectedIndex];
      return o ? (o.textContent || '').trim() : '';
    }
    var it = el.querySelector('.ant-select-selection-item');
    return it ? (it.textContent || '').trim() : '';
  }

  function inputNodes() {
    var all = document.querySelectorAll('input, textarea');
    var out = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var t = (el.type || 'text').toLowerCase();
      if (t === 'password' || t === 'hidden' || t === 'submit' ||
          t === 'button' || t === 'checkbox' || t === 'radio' || t === 'file') continue;
      if (el.disabled || el.readOnly) continue;
      out.push(el);
    }
    return out;
  }

  function collect() {
    var st = { t: Date.now(), href: location.href, selects: [], inputs: [], scroll: 0 };
    var sels = selectNodes();
    for (var i = 0; i < sels.length; i++) st.selects.push(selectText(sels[i]));
    var ins = inputNodes();
    for (var j = 0; j < ins.length; j++) {
      st.inputs.push({ v: ins[j].value, t: (ins[j].type || 'text') });
    }
    try {
      st.scroll = Math.round(window.scrollY || document.documentElement.scrollTop ||
                             (document.body && document.body.scrollTop) || 0);
    } catch (e) {}
    return st;
  }

  function save(force) {
    if (restoring) return;
    try {
      var s = JSON.stringify(collect());
      if (!force && s === last) return;
      last = s;
      try { localStorage.setItem(KEY, s); } catch (e) {}
      try { if (window.StateBridge && StateBridge.save) StateBridge.save(s); } catch (e) {}
    } catch (e) {}
  }

  /* ---------------- 恢复 ---------------- */

  function clickSeq(el) {
    ['mousedown', 'mouseup', 'click'].forEach(function (type) {
      try {
        el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
      } catch (e) {}
    });
  }

  async function pickAntd(el, label) {
    if (selectText(el) === label) return true;
    var trigger = el.querySelector('.ant-select-selector') || el;
    clickSeq(trigger);
    await sleep(200);
    var opts = document.querySelectorAll('.ant-select-item-option');
    for (var i = 0; i < opts.length; i++) {
      if ((opts[i].textContent || '').trim() === label) {
        try { opts[i].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window })); } catch (e) {}
        try { opts[i].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window })); } catch (e) {}
        await sleep(260);
        return selectText(el) === label;
      }
    }
    try { document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); } catch (e) {}
    await sleep(120);
    return false;
  }

  function setNativeSelect(el, label) {
    for (var i = 0; i < el.options.length; i++) {
      if ((el.options[i].textContent || '').trim() === label) {
        el.selectedIndex = i;
        try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
        return true;
      }
    }
    return false;
  }

  /* React 受控组件必须用原生 setter 再派发事件，直接赋值它收不到 */
  function setInputValue(el, val) {
    try {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, val);
    } catch (e) {
      try { el.value = val; } catch (e2) {}
    }
    try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}
    try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
  }

  async function restore(st) {
    restoring = true;
    try {
      await waitFor(function () { return selectNodes().length > 0; }, 8000);

      // 1) 先还原下拉框：切换"解析类型"会重建整个表单，所以必须排在输入框之前
      var sels = selectNodes();
      for (var i = 0; i < sels.length && i < st.selects.length; i++) {
        var want = st.selects[i];
        if (!want) continue;
        if (sels[i].tagName === 'SELECT') setNativeSelect(sels[i], want);
        else await pickAntd(sels[i], want);
        await sleep(140);
      }

      // 2) 再还原输入框
      await sleep(220);
      var ins = inputNodes();
      for (var j = 0; j < st.inputs.length && j < ins.length; j++) {
        var rec = st.inputs[j];
        if (!rec || !rec.v) continue;
        if (ins[j].value === rec.v) continue;
        setInputValue(ins[j], rec.v);
      }

      // 3) 滚动位置：SPA 内容是异步渲染的，多试几次
      if (st.scroll > 0) {
        [320, 1000, 2100].forEach(function (d) {
          setTimeout(function () {
            try {
              window.scrollTo(0, st.scroll);
              var de = document.documentElement, bd = document.body;
              if (de) de.scrollTop = st.scroll;
              if (bd) bd.scrollTop = st.scroll;
            } catch (e) {}
          }, d);
        });
      }
    } finally {
      restoring = false;
      try { last = JSON.stringify(collect()); } catch (e) {}
    }
  }

  /* ---------------- 启动 ---------------- */

  async function boot() {
    var raw = null;
    try { raw = localStorage.getItem(KEY); } catch (e) {}
    if (!raw) {
      try { if (window.StateBridge && StateBridge.load) raw = StateBridge.load(); } catch (e) {}
    }
    if (raw) {
      var st = null;
      try { st = JSON.parse(raw); } catch (e) {}
      if (st && st.href && st.href.split('#')[0] === location.href.split('#')[0]) {
        await restore(st);
      }
    }

    try { setInterval(function () { save(false); }, 2000); } catch (e) {}
    try {
      document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') save(true);
      });
    } catch (e) {}
    try {
      window.addEventListener('pagehide', function () { save(true); });
      window.addEventListener('beforeunload', function () { save(true); });
    } catch (e) {}
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(boot, 350);
  } else {
    document.addEventListener('DOMContentLoaded', function () { setTimeout(boot, 350); });
  }
})();
