async function getJSON(url, timeoutMs) {
  const opts = { headers: { Accept: 'application/json' } };
  let timer = null;
  if (timeoutMs) {
    const ctrl = new AbortController();
    opts.signal = ctrl.signal;
    timer = setTimeout(() => ctrl.abort(), timeoutMs);
  }
  try {
    const r = await fetch(url, opts);
    if (!r.ok) {
      let msg = 'HTTP ' + r.status;
      try {
        const j = await r.json();
        if (j && j.message) msg = j.message;
      } catch (_) {}
      throw new Error(msg);
    }
    return r.json();
  } catch (e) {
    if (e && e.name === 'AbortError') throw new Error('请求超时，请稍后刷新');
    throw e;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function sendJSON(method, url, body) {
  const r = await fetch(url, {
    method: method,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body)
  });
  if (!r.ok) {
    let msg = 'HTTP ' + r.status;
    try {
      const j = await r.json();
      if (j && j.message) msg = j.message;
    } catch (_) {}
    throw new Error(msg);
  }
  return r.json().catch(() => ({}));
}

async function postJSON(url, body) {
  return sendJSON('POST', url, body);
}

async function putJSON(url, body) {
  return sendJSON('PUT', url, body);
}

async function del(url) {
  const r = await fetch(url, { method: 'DELETE' });
  if (!r.ok) throw new Error('HTTP ' + r.status);
}

function toast(msg, type) {
  const box = document.getElementById('toastContainer');
  const el = document.createElement('div');
  el.className = 'toast ' + (type || 'info');
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => el.remove(), 3200);
  el.onclick = () => el.remove();
}

function fmt(n, d) {
  if (n == null || n === '' || Number.isNaN(n)) return '-';
  return Number(n).toLocaleString('zh-CN', { maximumFractionDigits: d == null ? 2 : d });
}

function pctCls(v) {
  if (v == null) return '';
  return v > 0 ? 'up' : (v < 0 ? 'down' : '');
}

function signPct(v) {
  if (v == null) return '-';
  const s = (v > 0 ? '+' : '') + Number(v).toFixed(2) + '%';
  return s;
}

function todayStr() {
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

function dirLabel(d) {
  return d === 'buy' ? '买' : (d === 'sell' ? '卖' : d);
}

function consensusLabel(c) {
  return ({ BUY: '偏多', SELL: '偏空', BUY_WEAK: '弱多', SELL_WEAK: '弱空', HOLD: '观望' })[c] || c || '观望';
}

function consensusCls(c) {
  if (c === 'BUY' || c === 'BUY_WEAK') return 'buy';
  if (c === 'SELL' || c === 'SELL_WEAK') return 'sell';
  return 'hold';
}

function conLabel(c) {
  return ({ watch: '观察', add: '加仓', hold: '持有', reduce: '减仓' })[c] || c;
}

function loadingHtml(text) {
  return '<div class="loading"><span class="spinner"></span>' + (text || '加载中...') + '</div>';
}
