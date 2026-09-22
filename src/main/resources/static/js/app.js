let currentAnalyze = null;
let replayDates = [];
let replayIndex = -1;
let replayTimer = null;
let lastDigest = null;
let reviewItems = [];
let lastIndices = [];
let watchCache = [];
let tradeCache = [];
let positionCache = [];
let strategyCatalog = [];
let watchViewMode = (function () {
  try { return localStorage.getItem('sr.watchView') || 'tree'; } catch (e) { return 'tree'; }
})();
let watchBoardTree = [];
let watchBoardMap = {};
let planCache = [];
let planFilter = 'OPEN';

async function show(id) {
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('nav button').forEach(b => b.classList.toggle('active', b.dataset.page === id));
  document.getElementById(id).classList.add('active');
  if (id === 'today') loadToday();
  if (id === 'watch') loadWatch();
  if (id === 'position') { loadPositions(); loadDiscipline(); }
  if (id === 'plans') loadPlans();
  if (id === 'strategy') await initStrategyPicks();
  if (id === 'charts') { loadCharts(); loadEquity(); }
  if (id === 'trades') loadTrades();
  pollAlerts();
  setTimeout(() => Object.values(chartPool || {}).forEach(c => c && c.resize()), 80);
}

/* ---------- 到价提醒横幅（P1-7） ---------- */
let lastAlerts = null;
async function pollAlerts() {
  try {
    const d = await getJSON('/api/alerts', 8000);
    lastAlerts = d;
    renderAlertBar(d);
  } catch (e) { /* 静默，下一轮再试 */ }
}

function renderAlertBar(d) {
  const bar = document.getElementById('alertBar');
  if (!bar) return;
  const items = (d && d.items) || [];
  if (!items.length) {
    bar.style.display = 'none';
    return;
  }
  const first = items[0];
  bar.style.display = 'block';
  bar.innerHTML = '<b>🔔 ' + items.length + ' 条到价提醒</b>' + escHtml(first.message)
    + (items.length > 1 ? '　<span style="text-decoration:underline">查看全部</span>' : '');
}

function onAlertBarClick() {
  const items = (lastAlerts && lastAlerts.items) || [];
  if (!items.length) return;
  if (items.length === 1) {
    show(items[0].page || 'position');
    return;
  }
  const msg = items.map((a, i) => (i + 1) + '. ' + a.message).join('\n');
  alert('到价提醒：\n\n' + msg);
  show(items[0].page || 'position');
}

async function loadToday() {
  const dateInput = document.getElementById('reviewDate');
  if (!dateInput.value) dateInput.value = todayStr();
  const date = dateInput.value;
  const box = document.getElementById('indexBox');
  const digestBox = document.getElementById('todayDigest');
  box.innerHTML = loadingHtml('拉取指数...');
  document.getElementById('todaySignals').innerHTML = loadingHtml('计算信号...');
  if (digestBox) digestBox.innerHTML = loadingHtml('生成今日摘要...');
  const idxTask = getJSON('/api/quotes/indices').then(indices => {
    lastIndices = indices || [];
    box.innerHTML = (lastIndices.map(idx => indexCard(idx)).join('')) || '<div class="empty">指数行情暂不可用</div>';
  }).catch(e => {
    box.innerHTML = '<div class="empty">指数加载失败：' + e.message + '</div>';
  });
  const digTask = getJSON('/api/today/digest').then(digest => {
    lastDigest = digest;
    if (digestBox) digestBox.innerHTML = renderTodayDigest(digest);
  }).catch(e => {
    if (digestBox) digestBox.innerHTML = '<div class="empty">' + e.message + '</div>';
  });
  const sigTask = getJSON('/api/strategy/watchlist-signals').then(sigs => {
    document.getElementById('todaySignals').innerHTML = renderTodaySignals(sigs);
    renderRsBox(sigs);
  }).catch(e => {
    document.getElementById('todaySignals').innerHTML = '<div class="empty">' + e.message + '</div>';
    renderRsBox([]);
  });
  const rvTask = getJSON('/api/reviews?date=' + date).then(fillReview).catch(e => toast(e.message, 'error'));
  await Promise.all([idxTask, digTask, sigTask, rvTask]);
}

function sceneNames(list) {
  if (!list || !list.length) return '';
  return list.map(x => (x.name || x.code) + (x.statusLabel ? '（' + x.statusLabel + '）' : '')).join('、');
}

function renderTodayDigest(d) {
  const scenes = d.scenes || {};
  const pbHit = scenes.pullbackHit || [];
  const pbNear = scenes.pullbackNear || [];
  const pbBroken = scenes.pullbackBroken || [];
  const volHit = scenes.volHit || [];
  const volNear = scenes.volNear || [];
  const planHits = d.planHits || [];
  const plans = d.plans || [];
  const indexLine = d.indexLine || '指数行情暂不可用';
  let planLine = '没有进行中的计划';
  if (plans.length && !planHits.length) {
    planLine = plans.length + ' 条进行中，尚未碰到止损/目标/超期';
  } else if (planHits.length) {
    planLine = planHits.map(p => (p.name || p.code) + ' ' + (p.flagLabel || p.flag)).join('；');
  }
  const sceneBits = [];
  if (pbHit.length) sceneBits.push(pbHit.length + ' 只像回踩：' + sceneNames(pbHit));
  if (pbNear.length) sceneBits.push(pbNear.length + ' 只接近回踩：' + sceneNames(pbNear));
  if (pbBroken.length) sceneBits.push(pbBroken.length + ' 只已跌破 MA20：' + sceneNames(pbBroken));
  if (volHit.length) sceneBits.push(volHit.length + ' 只像放量突破：' + sceneNames(volHit));
  if (volNear.length) sceneBits.push(volNear.length + ' 只接近放量：' + sceneNames(volNear));
  const sceneLine = sceneBits.length ? sceneBits.join('。') : '自选里今天没有回踩/放量这类样子（或不在自选里）';
  const draft = '【自动摘要】\n指数：' + indexLine + '\n计划：' + planLine + '\n情景：' + sceneLine
    + '\n（情景不记买卖点，摘要只供复盘对照。）';
  return ''
    + '<p class="hint" style="margin-top:0">' + escHtml(d.disclaimer || '摘要用于复盘对照，不是买卖建议。') + '</p>'
    + '<div class="digest-line"><b>指数</b>　' + escHtml(indexLine) + '</div>'
    + '<div class="digest-line"><b>计划</b>　' + escHtml(planLine) + '</div>'
    + '<div class="digest-line"><b>情景</b>　' + escHtml(sceneLine) + '</div>'
    + '<div style="margin-top:12px;"><button type="button" class="btn secondary sm" onclick="appendDigestToReview()">写入笔记草稿</button>'
    + '<span class="hint" style="margin-left:8px">不会覆盖你已写的内容，只追加在末尾。</span></div>'
    + '<textarea id="digestDraft" style="display:none">' + escHtml(draft) + '</textarea>';
}

function appendDigestToReview() {
  const draftEl = document.getElementById('digestDraft');
  const ta = document.getElementById('rvContent');
  if (!draftEl || !ta) return;
  const draft = draftEl.value;
  if (!ta.value.trim()) ta.value = draft;
  else ta.value = ta.value.replace(/\s+$/, '') + '\n\n' + draft;
  toast('已追加到复盘笔记，记得点保存', 'success');
}

function indexCard(idx) {
  const cls = pctCls(idx.pctChange);
  return '<div class="kpi"><div class="n">' + (idx.name || idx.code) + '</div>'
    + '<div class="v ' + cls + '">' + fmt(idx.price, 2) + '</div>'
    + '<div class="l ' + cls + '">' + signPct(idx.pctChange) + '</div></div>';
}

function renderTodaySignals(list) {
  if (!list || !list.length) {
    return '<div class="empty">先在「自选股」添加股票，再回来看当日买卖点</div>';
  }
  return '<div class="table-wrap"><table><thead><tr><th>代码</th><th>名称</th><th>行业层级</th><th>收盘</th><th>涨跌</th><th>相对板块</th><th>综合</th><th>当日信号</th></tr></thead><tbody>'
    + list.map(r => {
      const cls = pctCls(r.pctChange);
      const sig = (r.signals || []).map(s =>
        '<span class="sig-pill tag ' + signalCls(s.action) + '">' + s.strategyName + signalLabel(s.action) + '</span>'
      ).join('') || '-';
      const divTag = r.divergence ? ' <span class="tag watch" title="振幅>8%且实体<2%，纪律不建仓">分歧日</span>' : '';
      return '<tr><td><a onclick="openStrategy(\'' + r.code + '\')">' + r.code + '</a></td><td>' + (r.name || '') + divTag + '</td>'
        + '<td>' + renderBoardPath(r.boardPath, r.region) + '</td>'
        + '<td>' + fmt(r.close, 3) + '</td><td class="' + cls + '">' + signPct(r.pctChange) + '</td>'
        + '<td class="cmp-cell">' + renderRsCell(r.strength) + '</td>'
        + '<td><span class="tag ' + consensusCls(r.consensus) + '">' + consensusLabel(r.consensus) + '</span></td>'
        + '<td>' + sig + '</td></tr>';
    }).join('')
    + '</tbody></table></div>';
}

function signalCls(action) {
  if (action === 'BUY') return 'buy';
  if (action === 'SELL') return 'sell';
  return 'watch';
}

function signalLabel(action) {
  if (action === 'BUY') return '买';
  if (action === 'SELL') return '卖';
  return '警示';
}

function renderRsCell(s) {
  if (!s || s.verdict === 'NONE') {
    const board = s && s.boardName ? escHtml(s.boardName) + '　' : '';
    return '<span class="hint" style="margin:0">' + board + escHtml((s && (s.verdictLabel || s.explain)) || '-') + '</span>';
  }
  const tag = s.verdict === 'STRONG' ? 'hit' : (s.verdict === 'WEAK' ? 'against' : 'hold');
  const cls = s.rs1d == null ? '' : pctCls(s.rs1d);
  const title = (s.explain || '').replace(/"/g, '&quot;');
  return '<span class="tag ' + tag + '" title="' + title + '">' + escHtml(s.verdictLabel || '') + '</span>'
    + '<div class="sub ' + cls + '">今日相对 ' + signPct(s.rs1d) + '　5日 ' + signPct(s.rs5d) + '</div>'
    + (s.boardName ? '<div class="sub">' + escHtml(s.boardName) + ' ' + signPct(s.boardPct1d) + '</div>' : '');
}

function renderRsBox(list) {
  const box = document.getElementById('rsBox');
  if (!box) return;
  const rows = (list || []).filter(r => r.strength && r.strength.verdict && r.strength.verdict !== 'NONE');
  if (!rows.length) {
    box.innerHTML = '<div class="empty">先在「自选股」添加股票。有行业板块后会显示个股相对板块的强弱。</div>';
    return;
  }
  rows.sort((a, b) => (b.strength.rs1d || 0) - (a.strength.rs1d || 0));
  box.innerHTML = '<div class="table-wrap"><table><thead><tr><th>代码</th><th>名称</th><th>板块</th><th>个股今日</th><th>板块今日</th><th>相对1日</th><th>相对5日</th><th>对照</th></tr></thead><tbody>'
    + rows.map(r => {
      const s = r.strength;
      return '<tr><td><a onclick="openStrategy(\'' + r.code + '\')">' + r.code + '</a></td>'
        + '<td>' + (r.name || '') + '</td>'
        + '<td>' + escHtml(s.boardName || s.boardPath || '-') + '</td>'
        + '<td class="' + pctCls(s.stockPct1d) + '">' + signPct(s.stockPct1d) + '</td>'
        + '<td class="' + pctCls(s.boardPct1d) + '">' + signPct(s.boardPct1d) + '</td>'
        + '<td class="' + pctCls(s.rs1d) + '">' + signPct(s.rs1d) + '</td>'
        + '<td class="' + pctCls(s.rs5d) + '">' + signPct(s.rs5d) + '</td>'
        + '<td>' + renderRsCell(s) + '</td></tr>';
    }).join('')
    + '</tbody></table></div>';
}

function fillReview(rv) {
  const review = rv.review || {};
  document.getElementById('rvBias').value = review.marketBias || 'neutral';
  document.getElementById('rvSent').value = review.sentiment != null ? String(review.sentiment) : '3';
  document.getElementById('rvContent').value = review.content || '';
  const tags = (review.mistakeTags || '').split(',').map(s => s.trim()).filter(Boolean);
  document.querySelectorAll('#rvMistakes input[type=checkbox]').forEach(box => {
    box.checked = tags.indexOf(box.value) >= 0;
    box.closest('label').classList.toggle('on', box.checked);
  });
  reviewItems = (rv.items || []).map(it => ({
    code: it.code, name: it.name, conclusion: it.conclusion, note: it.note
  }));
  renderReviewItems();
}

function selectedMistakeTags() {
  return Array.from(document.querySelectorAll('#rvMistakes input[type=checkbox]:checked')).map(el => el.value);
}

function renderReviewItems() {
  const body = document.getElementById('riBody');
  if (!reviewItems.length) {
    body.innerHTML = '<tr><td colspan="5" class="empty">暂无个股点评</td></tr>';
    return;
  }
  body.innerHTML = reviewItems.map((it, i) =>
    '<tr><td>' + it.code + '</td><td>' + (it.name || '') + '</td><td>' + conLabel(it.conclusion) + '</td>'
    + '<td>' + (it.note || '') + '</td><td><a class="danger" onclick="removeReviewItem(' + i + ')">删</a></td></tr>'
  ).join('');
}

function addReviewItem() {
  const code = document.getElementById('riCode').value.trim();
  if (!code) { toast('请填代码', 'warn'); return; }
  reviewItems.push({
    code: code,
    name: document.getElementById('riName').value.trim(),
    conclusion: document.getElementById('riCon').value,
    note: document.getElementById('riNote').value.trim()
  });
  document.getElementById('riCode').value = '';
  document.getElementById('riName').value = '';
  document.getElementById('riNote').value = '';
  renderReviewItems();
}

function removeReviewItem(i) {
  reviewItems.splice(i, 1);
  renderReviewItems();
}

async function saveReview() {
  try {
    await postJSON('/api/reviews', {
      date: document.getElementById('reviewDate').value,
      marketBias: document.getElementById('rvBias').value,
      sentiment: Number(document.getElementById('rvSent').value),
      content: document.getElementById('rvContent').value,
      indices: lastIndices,
      items: reviewItems,
      mistakeTags: selectedMistakeTags()
    });
    toast('复盘已保存', 'success');
  } catch (e) {
    toast(e.message, 'error');
  }
}

let watchLoadSeq = 0;
let watchQuotes = {};
let watchBoardView = { items: [], tree: [] };

function asWatchList(raw) {
  if (Array.isArray(raw)) return raw;
  if (raw && Array.isArray(raw.data)) return raw.data;
  if (raw && Array.isArray(raw.Data)) return raw.Data;
  return [];
}

function quoteMap(quotes) {
  const qmap = {};
  (quotes || []).forEach(q => {
    if (q && q.code) qmap[q.code] = q;
  });
  return qmap;
}

function fallbackWatchTree(list, qmap) {
  return [{
    type: 'group',
    name: '自选',
    level: 1,
    stockCount: list.length,
    children: list.map(s => {
      const q = qmap[s.code] || {};
      return {
        type: 'stock',
        level: 4,
        code: s.code,
        name: s.name || q.name || s.code,
        price: q.price,
        pctChange: q.pctChange
      };
    })
  }];
}

function paintWatch() {
  const body = document.getElementById('watchBody');
  const treeWrap = document.getElementById('watchTreeWrap');
  if (!body) return;
  const list = watchCache || [];
  const qmap = Object.assign({}, watchQuotes);
  watchBoardMap = {};
  ((watchBoardView && watchBoardView.items) || []).forEach(it => {
    if (!it || !it.code) return;
    watchBoardMap[it.code] = it;
    if (it.price != null) {
      qmap[it.code] = Object.assign(qmap[it.code] || {}, {
        price: it.price, pctChange: it.pctChange, name: it.name
      });
    }
  });
  const industryTree = (watchBoardView && watchBoardView.tree) || [];
  watchBoardTree = industryTree.length ? industryTree : (list.length ? fallbackWatchTree(list, qmap) : []);
  const countEl = document.getElementById('watchCount');
  if (countEl) countEl.textContent = list.length ? '（' + list.length + '）' : '';
  if (!list.length) {
    body.innerHTML = '<tr><td colspan="10" class="empty">还没有自选，上方手填代码即可保存</td></tr>';
    if (treeWrap) treeWrap.innerHTML = '<div class="empty">还没有自选</div>';
    applyWatchView();
    return;
  }
  body.innerHTML = list.map(s => {
    const q = qmap[s.code] || {};
    const board = (watchBoardMap[s.code] && watchBoardMap[s.code].boards) || {};
    const cls = pctCls(q.pctChange);
    return '<tr><td><a onclick="openStrategy(\'' + s.code + '\')">' + s.code + '</a></td>'
      + '<td>' + (s.name || q.name || '') + '</td>'
      + '<td>' + renderBoardPath(board.path, board.region) + '</td>'
      + '<td>' + (s.market || '') + '</td>'
      + '<td>' + (s.shares ? fmt(s.shares, 0) : '<span class="hint" style="margin:0">未录入</span>') + '</td>'
      + '<td>' + fmt(q.price, 3) + '</td>'
      + '<td class="' + cls + '">' + signPct(q.pctChange) + '</td>'
      + '<td class="cmp-cell">' + renderRsCell(watchBoardMap[s.code] && watchBoardMap[s.code].strength) + '</td>'
      + '<td>' + (s.notes || '') + '</td>'
      + '<td><a onclick="openPosition(' + s.id + ',\'' + s.code + '\',\'' + esc(s.name) + '\','
      + (s.shares != null ? s.shares : 'null') + ',' + (s.costPrice != null ? s.costPrice : 'null') + ','
      + '\'' + esc(s.notes) + '\')">持仓</a> · <a onclick="openPlan(\'' + s.code + '\',\'' + esc(s.name || '') + '\')">计划</a> · <a onclick="editWatch(' + s.id + ')">编辑</a> · '
      + '<a onclick="openStrategy(\'' + s.code + '\')">策略</a> · <a class="danger" onclick="delWatch(' + s.id + ')">删除</a></td></tr>';
  }).join('');
  if (treeWrap) {
    treeWrap.innerHTML = renderIndustryTree(watchBoardTree)
      + (industryTree.length ? '' : '<p class="hint">行业层级还在拉取，先按自选列出。点「列表」可看表格。</p>');
  }
  applyWatchView();
}

async function loadWatch() {
  const seq = ++watchLoadSeq;
  const body = document.getElementById('watchBody');
  const treeWrap = document.getElementById('watchTreeWrap');
  body.innerHTML = '<tr><td colspan="10" class="empty">加载中...</td></tr>';
  if (treeWrap) treeWrap.innerHTML = loadingHtml('加载自选...');
  try {
    const list = asWatchList(await getJSON('/api/watchlist'));
    if (seq !== watchLoadSeq) return;
    watchCache = list;
    watchQuotes = {};
    watchBoardView = { items: [], tree: [] };
    paintWatch();
    getJSON('/api/watchlist/quotes').then(quotes => {
      if (seq !== watchLoadSeq) return;
      watchQuotes = quoteMap(quotes);
      paintWatch();
    }).catch(() => {});
    getJSON('/api/watchlist/boards').then(boardView => {
      if (seq !== watchLoadSeq) return;
      watchBoardView = boardView || { items: [], tree: [] };
      paintWatch();
    }).catch(() => {});
  } catch (e) {
    if (seq !== watchLoadSeq) return;
    body.innerHTML = '<tr><td colspan="10" class="empty">' + e.message + '</td></tr>';
    if (treeWrap) treeWrap.innerHTML = '<div class="empty">' + e.message + '</div>';
  }
}

function setWatchView(mode) {
  watchViewMode = mode;
  try { localStorage.setItem('sr.watchView', mode); } catch (e) {}
  applyWatchView();
}

function applyWatchView() {
  const tree = document.getElementById('watchTreeWrap');
  const table = document.getElementById('watchTableWrap');
  if (tree) tree.style.display = watchViewMode === 'tree' ? '' : 'none';
  if (table) table.style.display = watchViewMode === 'list' ? '' : 'none';
  const tb = document.getElementById('watchViewTree');
  const lb = document.getElementById('watchViewList');
  if (tb) tb.classList.toggle('active', watchViewMode === 'tree');
  if (lb) lb.classList.toggle('active', watchViewMode === 'list');
}

function escHtml(s) {
  return String(s || '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function renderBoardPath(path, region) {
  if (!path && !region) return '<span class="hint" style="margin:0">-</span>';
  return '<span class="board-path">' + escHtml(path || '-')
    + (region ? '<span class="board-region">' + escHtml(region) + '</span>' : '')
    + '</span>';
}

function nestLevels(levels) {
  if (!levels || !levels.length) return '<div class="empty" style="padding:8px">暂无</div>';
  let html = '';
  levels.forEach(lv => { html += '<ul class="bt-ul"><li>' + escHtml(lv); });
  for (let i = 0; i < levels.length; i++) html += '</li></ul>';
  return html;
}

function renderBoardCard(b) {
  if (!b) return '';
  const crumb = (b.emIndustry || []).length
    ? b.emIndustry.map(escHtml).join('<span class="sep">›</span>')
    : escHtml(b.path || '');
  const blocks = []
    .concat(b.emIndustry && b.emIndustry.length ? [['东财行业', nestLevels(b.emIndustry)]] : [])
    .concat(b.em2016 && b.em2016.length ? [['申万/东财2016', nestLevels(b.em2016)]] : [])
    .concat(b.csrc && b.csrc.length ? [['证监会行业', nestLevels(b.csrc)]] : []);
  const regionLine = [b.province, b.region, b.exchange].filter(Boolean).join(' · ');
  const tags = (b.concepts || []).map(c => '<span class="tag board">' + escHtml(c) + '</span>').join('');
  const extra = (b.extraConcepts || []).map(c => '<span class="tag hold">' + escHtml(c) + '</span>').join('');
  if (!crumb && !blocks.length && !regionLine && !tags) return '';
  return '<div class="board-card">'
    + (crumb ? '<div class="board-crumb">' + crumb + '</div>' : '')
    + (regionLine ? '<div class="hint" style="margin:0 0 8px;">' + escHtml(regionLine) + (b.mainBusiness ? '　主营：' + escHtml(b.mainBusiness) : '') + '</div>' : '')
    + (blocks.length ? '<div class="board-blocks">' + blocks.map(x => '<div class="board-block"><div class="lab">' + x[0] + '</div>' + x[1] + '</div>').join('') + '</div>' : '')
    + (tags ? '<div class="board-tags">' + tags + '</div>' : '')
    + (extra ? '<details style="margin-top:8px;"><summary class="hint" style="cursor:pointer;margin:0;">指数 / 风格标签</summary><div class="board-tags">' + extra + '</div></details>' : '')
    + '</div>';
}

function renderStrengthCard(s) {
  if (!s) return '';
  return '<div class="board-card"><div class="board-crumb">板块相对强弱</div>'
    + '<div class="cmp-cell">' + renderRsCell(s) + '</div>'
    + (s.explain ? '<p class="hint" style="margin:8px 0 0;">' + escHtml(s.explain) + '</p>' : '')
    + '</div>';
}

function renderIndustryTree(nodes) {
  if (!nodes || !nodes.length) return '<div class="empty">暂无板块数据</div>';
  return nodes.map(renderTreeNode).join('');
}

function renderTreeNode(n) {
  if (!n) return '';
  if (n.type === 'stock') {
    const cls = pctCls(n.pctChange);
    const rs = n.rsLabel ? '<span class="tag ' + (n.rs1d >= 0.3 ? 'hit' : (n.rs1d <= -0.3 ? 'against' : 'hold')) + '">' + escHtml(n.rsLabel) + '</span>' : '';
    return '<div class="bt-stock">'
      + '<a onclick="openStrategy(\'' + n.code + '\')">' + n.code + '</a>'
      + '<b>' + escHtml(n.name || '') + '</b>'
      + rs
      + (n.path ? '<span class="board-path">' + escHtml(n.path) + '</span>' : '')
      + '<span class="bt-px ' + cls + '">' + fmt(n.price, 3) + '　' + signPct(n.pctChange) + '</span></div>';
  }
  const kids = (n.children || []).map(renderTreeNode).join('');
  const opened = n.level <= 2 ? ' open' : '';
  return '<details class="bt-group lv' + n.level + '"' + opened + '>'
    + '<summary><span class="bt-name">' + escHtml(n.name) + '</span>'
    + '<span class="sub-count">' + (n.stockCount || 0) + ' 只</span>'
    + (n.boardPct1d != null ? '<span class="sub-count ' + pctCls(n.boardPct1d) + '">板块 ' + signPct(n.boardPct1d) + '</span>' : '')
    + (n.rsLabel ? '<span class="sub-count">' + escHtml(n.rsLabel) + '</span>' : '')
    + '</summary>'
    + '<div class="bt-children">' + kids + '</div></details>';
}

function esc(s) {
  return String(s || '').replace(/\\/g, '\\\\').replace(/'/g, '\\\'');
}

function watchPayload() {
  return {
    code: document.getElementById('wCode').value.trim(),
    name: document.getElementById('wName').value.trim(),
    notes: document.getElementById('wNotes').value.trim()
  };
}

async function saveWatch() {
  const body = watchPayload();
  if (!body.code) { toast('请填写股票代码', 'warn'); return; }
  try {
    const id = document.getElementById('wId').value;
    if (id) await putJSON('/api/watchlist/' + id, body);
    else await postJSON('/api/watchlist', body);
    toast('自选已保存', 'success');
    resetWatch();
    loadWatch();
  } catch (e) {
    toast(e.message, 'error');
  }
}

function resetWatch() {
  document.getElementById('wId').value = '';
  document.getElementById('wCode').value = '';
  document.getElementById('wName').value = '';
  document.getElementById('wNotes').value = '';
  document.getElementById('wSaveBtn').textContent = '💾 保存自选';
}

function editWatch(id) {
  const s = (watchCache || []).find(x => x.id === id);
  if (!s) return;
  document.getElementById('wId').value = s.id;
  document.getElementById('wCode').value = s.code || '';
  document.getElementById('wName').value = s.name || '';
  document.getElementById('wNotes').value = s.notes || '';
  document.getElementById('wSaveBtn').textContent = '💾 保存修改';
  document.getElementById('wCode').focus();
}

function quickAdd(code) {
  resetWatch();
  document.getElementById('wCode').value = code;
  saveWatch();
}

async function addWatch() {
  return saveWatch();
}

async function delWatch(id) {
  if (!confirm('从自选中删除？')) return;
  try {
    await del('/api/watchlist/' + id);
    loadWatch();
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function openStrategy(code) {
  document.getElementById('stCode').value = code;
  await show('strategy');
  analyzeStock();
}

async function analyzeStock(asOf, replayOnly) {
  const code = document.getElementById('stCode').value.trim();
  if (!code) { toast('请输入股票', 'warn'); return; }
  const ids = selectedStrategyIds();
  if (!ids.length) { toast('请至少勾选一个策略', 'warn'); return; }
  const box = document.getElementById('stResult');
  const status = document.getElementById('replayStatus');
  if (!replayOnly) {
    box.innerHTML = loadingHtml('拉取K线并用所选策略分析...');
  } else if (status) {
    status.textContent = '回放计算中…';
  }
  try {
    const limit = document.getElementById('stLimit').value;
    let url = '/api/strategy/analyze?code=' + encodeURIComponent(code)
      + '&limit=' + limit + '&strategies=' + encodeURIComponent(ids.join(','));
    if (asOf) url += '&asOf=' + encodeURIComponent(asOf);
    const data = await getJSON(url);
    currentAnalyze = data;
    if (!replayOnly || !replayDates.length) {
      replayDates = data.allDates && data.allDates.length ? data.allDates.slice() : (data.klines || []).map(k => k.date);
      replayIndex = replayDates.length - 1;
    }
    if (asOf && replayDates.length) {
      const i = replayDates.indexOf(data.asOf || asOf);
      if (i >= 0) replayIndex = i;
    }
    box.innerHTML = renderAnalyze(data);
    renderKlineChart('klineChart', data);
    syncReplaySlider();
  } catch (e) {
    if (replayOnly) {
      toast(e.message, 'error');
      if (status) status.textContent = e.message;
    } else {
      box.innerHTML = '<div class="card"><div class="empty" style="color:var(--red)">' + e.message + '</div></div>';
    }
  }
}

function replayMinIndex() {
  return Math.min(34, Math.max(0, replayDates.length - 1));
}

function syncReplaySlider() {
  const sl = document.getElementById('replaySlider');
  if (!sl || !replayDates.length) return;
  sl.min = String(replayMinIndex());
  sl.max = String(replayDates.length - 1);
  sl.value = String(replayIndex);
}

function onReplaySlide(val) {
  replayIndex = Number(val);
  const date = replayDates[replayIndex];
  const status = document.getElementById('replayStatus');
  if (status && date) status.textContent = '回放到 ' + date;
  if (replayTimer) clearTimeout(replayTimer);
  replayTimer = setTimeout(() => analyzeStock(date, true), 220);
}

function stepReplay(delta) {
  if (!replayDates.length) return;
  const next = Math.max(replayMinIndex(), Math.min(replayDates.length - 1, replayIndex + delta));
  replayIndex = next;
  syncReplaySlider();
  analyzeStock(replayDates[replayIndex], true);
}

function resetReplay() {
  if (!replayDates.length) return;
  replayIndex = replayDates.length - 1;
  analyzeStock(null, true);
}

function selectedStrategyIds() {
  const fromDom = Array.from(document.querySelectorAll('#stPicks input[type=checkbox]:checked')).map(el => el.value);
  if (fromDom.length) return fromDom;
  try {
    const saved = JSON.parse(localStorage.getItem('sr.strategies') || 'null');
    if (Array.isArray(saved) && saved.length) return saved;
  } catch (e) {}
  return strategyCatalog.map(s => s.id);
}

function saveStrategyPicks() {
  try { localStorage.setItem('sr.strategies', JSON.stringify(selectedStrategyIds())); } catch (e) {}
  document.querySelectorAll('.strategy-pick').forEach(el => {
    const box = el.querySelector('input');
    el.classList.toggle('active', box && box.checked);
  });
}

function pickStrategies(mode) {
  const boxes = document.querySelectorAll('#stPicks input[type=checkbox]');
  boxes.forEach(box => {
    const cat = box.dataset.category || '';
    if (mode === 'all') box.checked = true;
    else if (mode === 'mine') box.checked = cat === '自有';
    else if (mode === 'trend') box.checked = cat === '趋势';
    else if (mode === 'osc') box.checked = cat === '超买超卖';
    else if (mode === 'ma') box.checked = box.value === 'MA_CROSS' || box.value === 'MA_TREND';
    else if (mode === 'quiet') box.checked = box.value === 'VOL' || box.value === 'MA_TREND' || box.value === 'BOLL';
  });
  saveStrategyPicks();
}

async function initStrategyPicks() {
  const box = document.getElementById('stPicks');
  if (!box) return;
  if (!strategyCatalog.length) {
    try { strategyCatalog = await getJSON('/api/strategy/catalog'); } catch (e) { strategyCatalog = []; }
  }
  if (!strategyCatalog.length) {
    box.innerHTML = '<div class="empty">策略列表加载失败</div>';
    return;
  }
  let saved = null;
  try { saved = JSON.parse(localStorage.getItem('sr.strategies') || 'null'); } catch (e) {}
  const checked = Array.isArray(saved) && saved.length ? saved : strategyCatalog.map(s => s.id);
  box.innerHTML = strategyCatalog.map(s =>
    '<label class="strategy-pick' + (checked.indexOf(s.id) >= 0 ? ' active' : '') + '">'
    + '<input type="checkbox" value="' + s.id + '" data-category="' + s.category + '"'
    + (checked.indexOf(s.id) >= 0 ? ' checked' : '') + ' onchange="saveStrategyPicks()">'
    + '<span class="nm">' + s.name + '</span>'
    + '<div class="cat">' + s.category + ' · ' + s.summary + '</div></label>'
  ).join('');
}

function renderAnalyze(d) {
  const today = (d.todaySignals || []).map(s =>
    '<div class="alert ' + (s.action === 'BUY' ? 'bad' : (s.action === 'SELL' ? 'ok' : 'warn')) + '"><b>' + s.strategyName + ' · '
    + (s.action === 'BUY' ? '买点' : (s.action === 'SELL' ? '卖点' : '警示')) + '</b>　' + s.reason + '　价格 ' + fmt(s.price, 3) + '</div>'
  ).join('') || '<div class="alert warn">所选策略在最近一根K线上没有新信号。打开下方学习卡片，看今天为什么没触发。</div>';

  const divInfo = d.divergence || {};
  const divAlert = divInfo.today
    ? '<div class="alert bad"><b>⚠️ 今天是分歧日</b>　振幅 ' + (divInfo.amplitude == null ? '-' : divInfo.amplitude)
      + '%、实体 ' + (divInfo.body == null ? '-' : divInfo.body) + '%（振幅>8% 且实体<2%）。按纪律：不追、不建仓。</div>'
    : '';

  const latest = (d.latestSignals || []).map(s =>
    '<tr><td>' + s.strategyName + '</td><td>' + s.date + '</td>'
    + '<td><span class="tag ' + signalCls(s.action) + '">' + signalLabel(s.action) + '</span></td>'
    + '<td>' + s.reason + '</td><td>' + fmt(s.price, 3) + '</td></tr>'
  ).join('');

  const stats = (d.stats || []).map(s =>
    '<tr><td>' + s.strategyName + '</td><td>' + s.buyCount + '</td><td>' + s.sellCount + '</td>'
    + '<td>' + (s.winRate5d == null ? '-' : s.winRate5d + '%') + '</td>'
    + '<td>' + (s.avgReturn5d == null ? '-' : s.avgReturn5d + '%') + '</td>'
    + '<td>' + (s.winRate10d == null ? '-' : s.winRate10d + '%') + '</td>'
    + '<td>' + (s.avgReturn10d == null ? '-' : s.avgReturn10d + '%') + '</td>'
    + '<td>' + (s.winRate20d == null ? '-' : s.winRate20d + '%') + '</td>'
    + '<td>' + (s.avgReturn20d == null ? '-' : s.avgReturn20d + '%') + '</td></tr>'
  ).join('');

  const lessons = (d.lessons || []).map(renderLesson).join('');
  const scenes = (d.scenarios || []).map(renderScenario).join('');
  const cls = pctCls(d.latestPct);
  const sel = (d.selected || []).length;
  const ov = d.overlay || {};
  const dayWord = d.replaying ? ('回放到 ' + (d.asOf || '')) : '最近一根K线';
  const trades = d.trades || [];
  const tradeHint = trades.length
    ? '菱形是你自己的买卖（蓝=买，紫=卖），三角是策略信号。已标 ' + trades.length + ' 笔成交。'
    : '还没有这只股票的成交。录入后会用蓝色/紫色菱形画在图上，和策略三角分开。';
  const dates = replayDates.length ? replayDates : (d.allDates || []);
  const maxIdx = Math.max(0, dates.length - 1);
  const minIdx = Math.min(34, maxIdx);
  const curIdx = replayIndex >= 0 ? replayIndex : maxIdx;
  const ovHint = '图上蓝色粗线是 MA20 生命线。横线：'
    + (ov.costPrice != null ? '成本 ' + fmt(ov.costPrice, 3) + '　' : '')
    + (ov.planPrice != null ? '计划买 ' + fmt(ov.planPrice, 3) + '　' : '')
    + (ov.stopPrice != null ? '止损 ' + fmt(ov.stopPrice, 3) + '　' : '')
    + (ov.targetPrice != null ? '目标 ' + fmt(ov.targetPrice, 3) + '　' : '')
    + (ov.prevHigh != null ? '前高 ' + fmt(ov.prevHigh, 3) + (ov.prevHighDate ? '（' + ov.prevHighDate + '）' : '') + '　' : '')
    + (ov.prevLow != null ? '前低 ' + fmt(ov.prevLow, 3) + (ov.prevLowDate ? '（' + ov.prevLowDate + '）' : '') : '')
    + '。前高前低取近 20 日（不含当天）。灰色 × 是分歧日（振幅>8% 且实体<2%），纪律上分歧日不建仓。副图柱子是成交量，黄线 5 日均量、蓝线 20 日均量；放量突破用的是「大于 20 日均量 1.5 倍」。' + tradeHint;
  const replayBar = dates.length > minIdx
    ? '<div class="replay-bar">'
      + '<button type="button" class="btn secondary sm" onclick="stepReplay(-1)">前一天</button>'
      + '<input id="replaySlider" type="range" min="' + minIdx + '" max="' + maxIdx + '" value="' + curIdx + '" oninput="onReplaySlide(this.value)">'
      + '<button type="button" class="btn secondary sm" onclick="stepReplay(1)">后一天</button>'
      + '<button type="button" class="btn secondary sm" onclick="resetReplay()">回到最新</button>'
      + '<span id="replayStatus" class="replay-status">' + (d.replaying ? ('回放到 ' + d.asOf) : '当前最新一根') + '</span>'
      + '</div>'
      + '<p class="hint" style="margin-top:6px">拖动后，MA20、前高前低、回踩/放量、金叉都只算到这一天为止，用来体会「当时」而不是事后全对。</p>'
    : '';
  return ''
    + '<div class="card"><h2>' + d.name + ' <span class="sub-count">' + d.code + ' · ' + (d.market || '') + ' · 已选 ' + sel + ' 个策略'
    + (d.replaying ? ' · 回放 ' + d.asOf : '') + '</span>'
    + '<div class="right"><span class="' + cls + '">' + fmt(d.latestClose, 3) + '　' + signPct(d.latestPct) + '</span>'
    + '　<span class="tag ' + consensusCls(d.consensus) + '">综合 ' + consensusLabel(d.consensus) + '</span></div></h2>'
    + renderBoardCard(d.boards)
    + renderStrengthCard(d.strength)
    + '<div class="alert warn">' + (d.disclaimer || '用于学习对照，不构成投资建议。') + '</div>'
    + '<div id="stTodayAlerts">' + today + '</div>'
    + '<div id="klineChart" class="chart" style="height:500px;margin-top:12px;"></div>'
    + replayBar
    + '<p class="hint" id="stOverlayHint">' + ovHint + '</p></div>'
    + '<div class="card"><h2>中短线情景 <span class="sub-count">' + dayWord + '</span></h2>'
    + '<p class="hint" style="margin-top:0">回踩 MA20、放量突破只描述「这一天像不像」，<b>不记买点/卖点</b>，也不参与上面的综合偏多偏空。金叉死叉在震荡市会天天交叉，情景和它们分开看。</p>'
    + '<div id="sceneBox">' + (scenes || '<div class="empty">没有情景卡片</div>') + '</div></div>'
    + '<div class="card"><h2>策略学习（金叉 / 超买超卖等） <span class="sub-count">' + dayWord + '</span></h2><p class="hint" style="margin-top:0">先看「这一根K线」，再展开规则。建议每次只勾 1～2 个交叉类策略，对着图把交叉看懂。</p>'
    + (lessons || '<div class="empty">没有学习卡片</div>') + '</div>'
    + '<div class="grid2"><div class="card"><h2>所选策略最近一次信号</h2><div class="table-wrap"><table>'
    + '<thead><tr><th>策略</th><th>日期</th><th>方向</th><th>说明</th><th>价格</th></tr></thead><tbody>'
    + (latest || '<tr><td colspan="5" class="empty">暂无</td></tr>') + '</tbody></table></div></div>'
    + '<div class="card"><h2>本股回测（仅所选策略）</h2><p class="hint" style="margin-top:0">买点后 N 日上涨、卖点后 N 日下跌记为胜。用来体会策略脾气，不是预测。</p>'
    + '<div class="table-wrap"><table><thead><tr><th>策略</th><th>买点</th><th>卖点</th><th>5日胜率</th><th>5日均收益</th><th>10日胜率</th><th>10日均收益</th><th>20日胜率</th><th>20日均收益</th></tr></thead><tbody>'
    + stats + '</tbody></table></div></div></div>';
}

function renderScenario(sc) {
  const tag = sc.status === 'HIT' ? 'hit' : (sc.status === 'NEAR' ? 'chase' : (sc.status === 'BROKEN' ? 'against' : 'hold'));
  const snap = sc.snapshot || {};
  const snapText = Object.keys(snap).map(k => k + ' ' + (snap[k] == null ? '-' : snap[k])).join('　');
  return '<div class="lesson scene scene--' + (sc.status || 'NONE').toLowerCase() + '">'
    + '<h3>' + escHtml(sc.name) + ' <span class="tag hold">情景</span> <span class="tag ' + tag + '">' + escHtml(sc.statusLabel || '') + '</span></h3>'
    + '<div class="summary">' + escHtml(sc.summary || '') + '</div>'
    + '<div class="lesson-now"><div class="lesson-snap">现在：' + snapText + '</div>'
    + escHtml(sc.explain || '') + '</div></div>';
}

function renderLesson(ls) {
  const day = (currentAnalyze && currentAnalyze.replaying) ? '当天' : '今日';
  const st = ls.status === 'BUY' ? '<span class="tag buy">' + day + '买点</span>'
    : (ls.status === 'SELL' ? '<span class="tag sell">' + day + '卖点</span>' : '<span class="tag hold">' + day + '观察</span>');
  const snap = ls.snapshot || {};
  const snapText = Object.keys(snap).map(k => k + ' ' + (snap[k] == null ? '-' : snap[k])).join('　');
  const last = ls.lastSignal
    ? (ls.lastSignal.date + ' ' + (ls.lastSignal.action === 'BUY' ? '买' : '卖') + ' · ' + ls.lastSignal.reason)
    : '这段K线上还没有出现过信号';
  const stat = ls.stats && ls.stats.winRate5d != null
    ? ('本股 5 日胜率 ' + ls.stats.winRate5d + '%（样本 ' + (ls.stats.evaluated || 0) + '）')
    : '本股样本还不够，先看规则';
  return '<div class="lesson">'
    + '<h3>' + ls.name + ' <span class="tag hold">' + ls.category + '</span> ' + st + '</h3>'
    + '<div class="summary">' + (ls.summary || '') + '</div>'
    + '<div class="lesson-now"><div class="lesson-snap">现在：' + snapText + '</div>'
    + (ls.explain || '') + '<div class="lesson-snap" style="margin:8px 0 0;">最近信号：' + last + '　·　' + stat + '</div></div>'
    + '<details><summary>规则、适用场景、常见坑</summary>'
    + '<p><b>核心想法</b>　' + (ls.idea || '') + '</p>'
    + '<p><b>本系统怎么算</b>　' + (ls.how || '') + '</p>'
    + '<p><b>记买点</b>　' + (ls.buyRule || '') + '</p>'
    + '<p><b>记卖点</b>　' + (ls.sellRule || '') + '</p>'
    + '<p><b>适合</b>　' + (ls.suitable || '') + '</p>'
    + '<p><b>常见坑</b>　' + (ls.pitfall || '') + '</p>'
    + '</details></div>';
}

async function addCurrentToWatch() {
  const code = (currentAnalyze && currentAnalyze.code) || document.getElementById('stCode').value.trim();
  if (!code) { toast('请先分析一只股票', 'warn'); return; }
  try {
    await postJSON('/api/watchlist', { code: code });
    toast('已加入自选', 'success');
  } catch (e) {
    toast(e.message, 'error');
  }
}

function divergenceGuard() {
  if (currentAnalyze && currentAnalyze.divergence && currentAnalyze.divergence.today
      && !currentAnalyze.replaying) {
    toast('⚠️ 今天是分歧日（振幅>8% 且实体<2%），按纪律不建仓', 'warn');
    return true;
  }
  return false;
}

function addCurrentToPlan() {
  const code = (currentAnalyze && currentAnalyze.code) || document.getElementById('stCode').value.trim();
  if (!code) { toast('请先分析一只股票', 'warn'); return; }
  divergenceGuard();
  openPlan(code, currentAnalyze && currentAnalyze.name);
}

function renderPlanCell(p) {
  if (!p || !p.planFlag) {
    return '<span class="hint" style="margin:0">无计划</span>';
  }
  const tag = p.planFlag === 'STOP' ? 'against' : (p.planFlag === 'TARGET' ? 'hit' : (p.planFlag === 'OVERDUE' ? 'chase' : 'early'));
  const days = p.planHeldDays != null
    ? (p.planHeldDays + (p.planHoldDays != null ? '/' + p.planHoldDays : '') + ' 天')
    : '';
  return '<span class="tag ' + tag + '">' + escHtml(p.planFlagLabel || '有计划') + '</span>'
    + '<div class="sub">止损 ' + fmt(p.planStop, 3) + '　目标 ' + fmt(p.planTarget, 3) + '</div>'
    + (days ? '<div class="sub">' + days + '</div>' : '');
}

function planFlagTag(p) {
  const flag = p.flag || p.status;
  const tag = flag === 'STOP' ? 'against' : (flag === 'TARGET' ? 'hit' : (flag === 'OVERDUE' ? 'chase' : (flag === 'CLOSED' || flag === 'CANCELLED' ? 'hold' : 'early')));
  return '<span class="tag ' + tag + '">' + escHtml(p.flagLabel || p.status || '') + '</span>';
}

async function loadPlans(status) {
  if (status) planFilter = status;
  const body = document.getElementById('planBody');
  const kpi = document.getElementById('planKpi');
  const summary = document.getElementById('planSummary');
  if (!body) return;
  body.innerHTML = '<tr><td colspan="11" class="empty">加载中...</td></tr>';
  if (kpi) kpi.innerHTML = loadingHtml('对照现价...');
  try {
    const list = await getJSON('/api/plans?status=' + encodeURIComponent(planFilter || 'OPEN'));
    planCache = list || [];
    const open = planCache.filter(p => p.status === 'OPEN');
    const stop = open.filter(p => p.flag === 'STOP').length;
    const target = open.filter(p => p.flag === 'TARGET').length;
    const overdue = open.filter(p => p.flag === 'OVERDUE').length;
    const strong = planCache.filter(p => p.strength && p.strength.verdict === 'STRONG').length;
    if (kpi) {
      kpi.innerHTML = kpiHtml(open.length, '进行中')
        + kpiHtml(stop, '触及止损')
        + kpiHtml(target, '触及目标')
        + kpiHtml(overdue, '超计划天数')
        + kpiHtml(strong, '强于板块');
    }
    if (summary) {
      summary.textContent = planFilter === 'ALL'
        ? '显示全部计划。现价对照止损/目标，相对板块用于判断是否掉队，不是买卖建议。'
        : '进行中的计划会对照现价。触及止损/目标或超天数时请复盘，决定结束或改计划。';
    }
    if (!planCache.length) {
      body.innerHTML = '<tr><td colspan="11" class="empty">还没有计划。买入前先写下止损、目标和拿几天。</td></tr>';
      return;
    }
    body.innerHTML = planCache.map(p => {
      const cls = pctCls(p.pctChange);
      const days = (p.heldDays != null ? p.heldDays : '-') + (p.holdDays != null ? ' / ' + p.holdDays : '');
      const acts = p.status === 'OPEN'
        ? '<a onclick="editPlan(' + p.id + ')">编辑</a> · <a onclick="closePlan(' + p.id + ')">结束</a> · <a class="danger" onclick="delPlan(' + p.id + ')">删除</a>'
        : '<a onclick="editPlan(' + p.id + ')">查看</a> · <a class="danger" onclick="delPlan(' + p.id + ')">删除</a>';
      return '<tr><td>' + (p.planDate || '') + '</td>'
        + '<td><a onclick="openStrategy(\'' + p.code + '\')">' + p.code + '</a></td>'
        + '<td>' + (p.name || '') + '</td>'
        + '<td>' + planFlagTag(p) + '</td>'
        + '<td>' + escHtml(p.reason || '-') + '</td>'
        + '<td>' + fmt(p.stopPrice, 3) + (p.stopDistancePct != null ? '<div class="sub">距止损 ' + signPct(p.stopDistancePct) + '</div>' : '') + '</td>'
        + '<td>' + fmt(p.targetPrice, 3) + (p.targetDistancePct != null ? '<div class="sub">距目标 ' + signPct(p.targetDistancePct) + '</div>' : '') + '</td>'
        + '<td>' + days + '</td>'
        + '<td class="' + cls + '">' + fmt(p.price, 3) + '<div class="sub">' + signPct(p.pctChange) + '</div></td>'
        + '<td class="cmp-cell">' + renderRsCell(p.strength) + '</td>'
        + '<td>' + acts + '</td></tr>';
    }).join('');
  } catch (e) {
    if (kpi) kpi.innerHTML = '';
    body.innerHTML = '<tr><td colspan="11" class="empty">' + e.message + '</td></tr>';
  }
}

function resetPlan() {
  document.getElementById('plId').value = '';
  document.getElementById('plCode').value = '';
  document.getElementById('plName').value = '';
  document.getElementById('plPrice').value = '';
  document.getElementById('plStop').value = '';
  document.getElementById('plTarget').value = '';
  document.getElementById('plDays').value = '';
  document.getElementById('plReason').value = '';
  document.getElementById('plSaveBtn').textContent = '💾 保存计划';
  document.getElementById('planFormTitle').textContent = '写一笔中短线计划';
  if (!document.getElementById('plDate').value) document.getElementById('plDate').value = todayStr();
}

async function openPlan(code, name) {
  show('plans');
  resetPlan();
  document.getElementById('plCode').value = code || '';
  document.getElementById('plName').value = name || '';
  document.getElementById('plDate').value = todayStr();
  try {
    const list = await getJSON('/api/plans?status=OPEN');
    planCache = list || [];
    const exist = planCache.find(p => p.code === code);
    if (exist) fillPlanForm(exist);
  } catch (_) {}
  document.getElementById('plReason').focus();
}

function fillPlanForm(p) {
  document.getElementById('plId').value = p.id || '';
  document.getElementById('plDate').value = p.planDate || todayStr();
  document.getElementById('plCode').value = p.code || '';
  document.getElementById('plName').value = p.name || '';
  document.getElementById('plPrice').value = p.planPrice != null ? p.planPrice : '';
  document.getElementById('plStop').value = p.stopPrice != null ? p.stopPrice : '';
  document.getElementById('plTarget').value = p.targetPrice != null ? p.targetPrice : '';
  document.getElementById('plDays').value = p.holdDays != null ? p.holdDays : '';
  document.getElementById('plReason').value = p.reason || '';
  document.getElementById('plSaveBtn').textContent = '💾 保存修改';
  document.getElementById('planFormTitle').textContent = '修改交易计划';
}

function editPlan(id) {
  const p = (planCache || []).find(x => x.id === id);
  if (!p) return;
  fillPlanForm(p);
  document.getElementById('plCode').focus();
}

async function savePlan() {
  const body = {
    code: document.getElementById('plCode').value.trim(),
    name: document.getElementById('plName').value.trim(),
    planDate: document.getElementById('plDate').value || todayStr(),
    planPrice: numOrNull('plPrice'),
    stopPrice: numOrNull('plStop'),
    targetPrice: numOrNull('plTarget'),
    holdDays: numOrNull('plDays'),
    reason: document.getElementById('plReason').value.trim()
  };
  if (!body.code) { toast('请填写股票代码', 'warn'); return; }
  try {
    const id = document.getElementById('plId').value;
    if (id) await putJSON('/api/plans/' + id, body);
    else await postJSON('/api/plans', body);
    toast('计划已保存', 'success');
    resetPlan();
    loadPlans(planFilter);
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function closePlan(id) {
  const note = prompt('结束这条计划的备注（可空）', '按计划结束');
  if (note === null) return;
  try {
    await postJSON('/api/plans/' + id + '/close', { note: note });
    toast('计划已结束', 'success');
    loadPlans(planFilter);
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function delPlan(id) {
  if (!confirm('删除这条计划？')) return;
  try {
    await del('/api/plans/' + id);
    toast('已删除', 'success');
    loadPlans(planFilter);
  } catch (e) {
    toast(e.message, 'error');
  }
}

function addCurrentToPosition() {
  const code = (currentAnalyze && currentAnalyze.code) || document.getElementById('stCode').value.trim();
  if (!code) { toast('请先分析一只股票', 'warn'); return; }
  divergenceGuard();
  const name = currentAnalyze && currentAnalyze.name;
  openPosition(null, code, name);
}

function refreshPositions() {
  return loadPositions(true);
}

/** 账户总资金存入本机浏览器，重新渲染仓位指标 */
function savePosCapital() {
  const el = document.getElementById('posCapital');
  const v = parseFloat(el && el.value);
  if (isNaN(v) || v <= 0) localStorage.removeItem('posCapital');
  else localStorage.setItem('posCapital', String(v));
  loadPositions();
}

function setPosRefreshBusy(busy) {
  ['posRefreshBtn', 'posRefreshBtn2'].forEach(id => {
    const btn = document.getElementById(id);
    if (!btn) return;
    btn.disabled = busy;
    btn.textContent = busy ? '更新中...' : '更新现价';
  });
}

async function loadPositions(refresh) {
  const body = document.getElementById('posBody');
  const kpi = document.getElementById('posKpi');
  if (refresh) setPosRefreshBusy(true);
  body.innerHTML = '<tr><td colspan="16" class="empty">加载中...</td></tr>';
  kpi.innerHTML = loadingHtml(refresh ? '正在更新现价...' : '汇总持仓...');
  try {
    const d = await getJSON('/api/positions' + (refresh ? '?refresh=true' : ''));
    const list = d.items || [];
    positionCache = list;
    document.getElementById('posCount').textContent = d.count ? '（' + d.count + '）' : '';
    const at = document.getElementById('posQuoteAt');
    if (at) at.textContent = d.quotedAt ? ('现价 ' + d.quotedAt) : '';
    const tip = document.getElementById('posQuoteTip');
    if (tip) {
      tip.innerHTML = d.quoteError ? '<div class="alert warn" style="margin-top:12px;">' + d.quoteError + '</div>' : '';
    }
    // 账户总资金（存本机浏览器），用于算真实仓位
    const capInput = document.getElementById('posCapital');
    const cap = parseFloat(localStorage.getItem('posCapital') || '');
    if (capInput && document.activeElement !== capInput) {
      capInput.value = (isNaN(cap) || cap <= 0) ? '' : cap;
    }
    const pl = d.totalPl;
    const plLabel = pl > 0 ? '赚了' : (pl < 0 ? '亏了' : '浮动盈亏');
    const plShow = pl == null ? '-' : (pl < 0 ? fmt(Math.abs(pl)) : fmt(pl));
    kpi.innerHTML =
      kpiHtml(fmt(d.totalCost), '持仓成本')
      + kpiHtml(fmt(d.totalMarket), '持仓市值')
      + '<div class="kpi"><div class="v ' + pctCls(pl) + '">' + plShow + '</div><div class="l">' + plLabel + '</div></div>'
      + '<div class="kpi"><div class="v ' + pctCls(d.totalPlPct) + '">' + signPct(d.totalPlPct) + '</div><div class="l">盈亏比例</div></div>'
      + (!isNaN(cap) && cap > 0 && d.totalMarket != null
        ? '<div class="kpi"><div class="v">' + (d.totalMarket / cap * 100).toFixed(1) + '%</div><div class="l">总仓位</div></div>'
          + '<div class="kpi"><div class="v">' + fmt(Math.max(0, cap - d.totalMarket)) + '</div><div class="l">可用资金(约)</div></div>'
        : '')
      + (d.stopBrokenCount > 0
        ? '<div class="kpi"><div class="v down">' + d.stopBrokenCount + ' 只</div><div class="l">已破纪律线</div></div>'
        : '');
    // 纪律与集中度提醒
    const dTip = document.getElementById('posDisciplineTip');
    if (dTip) {
      let html = '';
      const broken = list.filter(p => p.stopBroken);
      if (broken.length) {
        html += '<div class="alert bad" style="margin-top:12px;">🚨 '
          + broken.map(p => (p.name || p.code) + '（' + signPct(p.floatPlPct) + '，纪律线 -' + (p.stopPct || 8) + '%）').join('、')
          + ' 已跌破纪律止损线。按纪律应离场复盘，不要硬抗。</div>';
      }
      if (d.topSectorWeight != null && d.topSectorWeight >= 50) {
        html += '<div class="alert warn">⚠️ 「' + d.topSector + '」占持仓市值 ' + d.topSectorWeight.toFixed(1)
          + '%，行业集中度过高，单一板块回调会拖累整体，建议控制该方向总暴露。</div>';
      } else if (d.maxSingleWeight != null && d.maxSingleWeight >= 40) {
        html += '<div class="alert warn">⚠️ 单一持仓「' + (d.maxSingleName || '') + '」占比 ' + d.maxSingleWeight.toFixed(1)
          + '%，个股风险偏高，注意仓位控制。</div>';
      }
      dTip.innerHTML = html;
    }
    // 行业分布条
    const sBox = document.getElementById('posSectorBox');
    if (sBox) {
      const secs = d.sectorWeights || [];
      sBox.innerHTML = secs.length
        ? '<p class="hint" style="margin:8px 0 0;">行业分布：' + secs.map(s => s.name + ' ' + Number(s.weight).toFixed(1) + '%').join(' ｜ ') + '</p>'
        : '';
    }
    pieChart('chartPosPie', list.filter(p => p.marketValue).map(p => ({ name: p.name || p.code, value: p.marketValue })));
    signedBarChart('chartPosPl', list.map(p => p.name || p.code), list.map(p => p.floatPl || 0));
    if (!list.length) {
      body.innerHTML = '<tr><td colspan="16" class="empty">还没有持仓。上方填写代码、数量、成本价后保存。</td></tr>';
      return;
    }
    body.innerHTML = list.map(p => {
      const cls = pctCls(p.pctChange);
      const plCls = pctCls(p.floatPl);
      const plCell = p.floatPl == null ? '-' : ((p.floatPl < 0 ? '亏 ' : (p.floatPl > 0 ? '赚 ' : '')) + fmt(Math.abs(p.floatPl)));
      const stopCell = p.stopLine == null ? '-' : (p.stopBroken
        ? '<span class="down"><strong>已破线 ' + fmt(p.stopLine, 3) + '</strong></span>'
        : fmt(p.stopLine, 3) + ' <span class="sub">-' + (p.stopPct || 8) + '%'
          + (p.stopDistancePct == null ? '' : ' · 距线 ' + p.stopDistancePct.toFixed(1) + '%') + '</span>');
      return '<tr' + (p.stopBroken ? ' class="stop-broken"' : '') + '><td><a onclick="openStrategy(\'' + p.code + '\')">' + p.code + '</a></td>'
        + '<td>' + (p.name || '') + '</td>'
        + '<td>' + renderBoardPath(p.boardPath, p.region) + '</td>'
        + '<td class="cmp-cell">' + renderRsCell(p.strength) + '</td>'
        + '<td>' + fmt(p.shares, 0) + '</td><td>' + fmt(p.costPrice, 3) + '</td><td>' + fmt(p.costAmount) + '</td>'
        + '<td>' + fmt(p.price, 3) + '</td>'
        + '<td class="' + cls + '">' + signPct(p.pctChange) + '</td>'
        + '<td>' + fmt(p.marketValue) + '</td>'
        + '<td class="' + plCls + '">' + plCell + '</td>'
        + '<td class="' + plCls + '">' + signPct(p.floatPlPct) + '</td>'
        + '<td>' + stopCell + '</td>'
        + '<td>' + (p.weight == null ? '-' : p.weight.toFixed(1) + '%') + '</td>'
        + '<td class="cmp-cell">' + renderPlanCell(p) + '</td>'
        + '<td><a onclick="editPosition(' + p.id + ')">编辑</a> · <a onclick="openPlan(\'' + p.code + '\',\'' + esc(p.name || '') + '\')">计划</a> · <a class="danger" onclick="clearPosition(' + p.id + ')">清仓</a></td></tr>';
    }).join('');
    if (refresh) {
      if (d.quotedCount) {
        if (pl < 0) toast('现价已更新，合计亏了 ' + fmt(Math.abs(pl)), 'warn');
        else if (pl > 0) toast('现价已更新，合计赚了 ' + fmt(pl), 'success');
        else toast('现价已更新', 'success');
      } else {
        toast(d.quoteError || '未取到现价', 'error');
      }
    }
  } catch (e) {
    kpi.innerHTML = '';
    body.innerHTML = '<tr><td colspan="15" class="empty">' + e.message + '</td></tr>';
    if (refresh) toast(e.message, 'error');
  } finally {
    setPosRefreshBusy(false);
  }
}

function recalcPositionCost() {
  const shares = numOrNull('pShares');
  const cost = numOrNull('pCost');
  if (shares != null && cost != null) {
    document.getElementById('pCostAmt').value = (shares * cost).toFixed(2);
  }
}

/* ---------- 止损纪律闭环（P0-1） ---------- */
const DISC_STATUS = {
  OPEN: ['待处理', 'chase'], EXECUTED: ['已执行止损', 'hit'], IGNORED: ['选择硬抗', 'against'],
  RECOVERED: ['涨回线上', 'early'], CLOSED_POS: ['清仓了结', 'hold']
};

async function loadDiscipline(scan) {
  const kpi = document.getElementById('discKpi');
  const body = document.getElementById('discBody');
  if (!kpi || !body) return;
  try {
    await postJSON('/api/discipline/scan', {});
    const [summary, events, openLive] = await Promise.all([
      getJSON('/api/discipline/summary'),
      getJSON('/api/discipline/events'),
      getJSON('/api/discipline/open-live')
    ]);
    const liveMap = {};
    (openLive || []).forEach(lv => { liveMap[lv.id] = lv; });
    kpi.innerHTML =
      kpiHtml(summary.open || 0, '待处理')
      + '<div class="kpi"><div class="v ' + (summary.executeRate == null ? '' : (summary.executeRate >= 60 ? 'up' : 'down')) + '">'
      + (summary.executeRate == null ? '-' : summary.executeRate + '%') + '</div><div class="l">纪律执行率（' + (summary.executed || 0) + ' 执行 / ' + (summary.ignored || 0) + ' 硬抗）</div></div>'
      + kpiHtml(summary.avgIgnoredDays == null ? '-' : summary.avgIgnoredDays + ' 天', '硬抗平均多拖')
      + '<div class="kpi"><div class="v ' + (summary.avgIgnoredExtraLoss > 0 ? 'down' : '') + '">'
      + (summary.avgIgnoredExtraLoss == null ? '-' : summary.avgIgnoredExtraLoss + '%') + '</div><div class="l">硬抗平均多亏</div></div>'
      + kpiHtml(summary.recovered || 0, '涨回线上（运气）');
    // 硬抗结局分布：越扛越亏 vs 熬回涨回
    const ho = summary.hardOutcome || {};
    const hoBox = document.getElementById('discHardOutcome');
    if (hoBox) {
      hoBox.innerHTML = ho.resolved > 0
        ? '硬抗结局分布：已了结的 ' + ho.resolved + ' 次硬抗里，<span class="down">' + ho.worseCount + ' 次越扛越亏（平均多亏 '
          + ho.avgWorseLoss + '%）</span>，<span class="up">' + ho.recoverCount + ' 次熬了回来（平均收复 '
          + Math.abs(ho.avgRecoverGain || 0) + '%）</span>。熬回来的比例越高越要警惕——那是运气，不是能力，下一次可能就是深套。'
        : '';
    }
    if (!events || !events.length) {
      body.innerHTML = '<tr><td colspan="11" class="empty">还没有破线事件，继续保持</td></tr>';
      return;
    }
    body.innerHTML = events.map(ev => {
      const st = DISC_STATUS[ev.status] || [ev.status, 'hold'];
      const acts = ev.status === 'OPEN'
        ? '<a onclick="resolveEvent(' + ev.id + ',\'EXECUTED\')">执行止损</a> · <a class="danger" onclick="resolveEvent(' + ev.id + ',\'IGNORED\')">硬抗</a>'
        : (ev.note ? '<span class="sub">' + escHtml(ev.note) + '</span>' : '-');
      const lv = liveMap[ev.id];
      let extra;
      if (ev.status === 'OPEN') {
        if (lv && lv.liveExtraLossPct != null) {
          extra = '<span class="' + (lv.liveExtraLossPct > 0 ? 'down' : 'up') + '">' + signPct(lv.liveExtraLossPct)
            + '</span> <span class="tag ' + (lv.liveExtraLossPct > 0 ? 'watch' : 'hit') + '">硬抗中' + (lv.daysHeld != null ? ' ' + lv.daysHeld + ' 天' : '') + '</span>';
        } else {
          extra = '<span class="tag watch">硬抗中</span>';
        }
      } else {
        extra = ev.extraLossPct == null ? '-' : '<span class="' + (ev.extraLossPct > 0 ? 'down' : 'up') + '">' + signPct(ev.extraLossPct) + '</span>';
      }
      return '<tr><td>' + (ev.triggerDate || '') + '</td>'
        + '<td><a onclick="openStrategy(\'' + ev.code + '\')">' + ev.code + '</a></td>'
        + '<td>' + (ev.name || '') + '</td>'
        + '<td>' + fmt(ev.costPrice, 3) + '</td>'
        + '<td>' + fmt(ev.stopLine, 3) + '</td>'
        + '<td>' + fmt(ev.triggerPrice, 3) + '</td>'
        + '<td><span class="tag ' + st[1] + '">' + st[0] + '</span></td>'
        + '<td>' + (ev.resolvedDate || '-') + '</td>'
        + '<td>' + (ev.status === 'OPEN' && lv && lv.daysHeld != null ? lv.daysHeld + ' 天' : (ev.daysOpen == null ? '-' : ev.daysOpen + ' 天')) + '</td>'
        + '<td>' + extra + '</td>'
        + '<td>' + acts + '</td></tr>';
    }).join('');
  } catch (e) {
    body.innerHTML = '<tr><td colspan="11" class="empty">' + e.message + '</td></tr>';
  }
}

async function resolveEvent(id, action) {
  const label = action === 'EXECUTED' ? '确认已按纪律止损卖出？' : '确认选择硬抗（不执行止损）？这会被记入纪律统计';
  if (!confirm(label)) return;
  const note = action === 'IGNORED' ? prompt('硬抗理由（可空）', '') : '';
  if (note === null) return;
  try {
    await postJSON('/api/discipline/' + id + '/resolve', { action: action, note: note || null });
    toast(action === 'EXECUTED' ? '已记为执行止损' : '已记为硬抗', action === 'EXECUTED' ? 'success' : 'warn');
    loadDiscipline();
    pollAlerts();
  } catch (e) {
    toast(e.message, 'error');
  }
}

/* ---------- 净值快照（P0-2） ---------- */
async function snapshotEquity() {
  const cap = parseFloat(localStorage.getItem('posCapital') || '');
  if (isNaN(cap) || cap <= 0) {
    toast('先在右上方填入「账户总资金」再记净值', 'warn');
    return;
  }
  try {
    const s = await postJSON('/api/equity/snapshot', { totalAsset: cap });
    toast('已记今日净值：总资金 ' + fmt(s.totalAsset) + '（持仓 ' + fmt(s.positionValue) + '，现金约 ' + fmt(s.cash) + '）', 'success');
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function loadEquity() {
  const kpi = document.getElementById('equityKpi');
  if (!kpi) return;
  try {
    const d = await getJSON('/api/equity/curve', 8000);
    if (d.empty) {
      kpi.innerHTML = '<div class="empty">还没有净值记录。每天在「持仓」页填总资金并点「记今日净值」。</div>';
      const c = getChart('chartEquity');
      if (c) c.clear();
      return;
    }
    kpi.innerHTML =
      kpiHtml(d.days, '已记天数')
      + kpiHtml(fmt(d.latestAsset), '最新总资金')
      + '<div class="kpi"><div class="v ' + pctCls(d.totalReturn) + '">' + signPct(d.totalReturn) + '</div><div class="l">累计收益（自 ' + (d.firstDate || '') + '）</div></div>'
      + '<div class="kpi"><div class="v down">' + (d.maxDrawdown == null ? '-' : d.maxDrawdown + '%') + '</div><div class="l">最大回撤' + (d.maxDrawdownDate ? '（' + d.maxDrawdownDate + '）' : '') + '</div></div>'
      + '<div class="kpi"><div class="v ' + pctCls(d.benchReturn) + '">' + (d.benchReturn == null ? '-' : signPct(d.benchReturn)) + '</div><div class="l">同期沪深300</div></div>';
    equityLineChart('chartEquity', d.dates || [], d.mine || [], d.bench || []);
  } catch (e) {
    kpi.innerHTML = '<div class="empty">' + e.message + '</div>';
  }
}

/* ---------- 做T计算器（P1-5） ---------- */
async function loadTtLevels() {
  const code = document.getElementById('ttCode').value.trim();
  if (!code) { toast('请填代码', 'warn'); return; }
  const box = document.getElementById('ttLevels');
  box.innerHTML = '<span class="hint" style="margin:0">拉取中...</span>';
  try {
    const d = await getJSON('/api/quotes/tt-levels?code=' + encodeURIComponent(code));
    box.innerHTML =
      '<span class="tag">昨收 ' + fmt(d.prevClose, 3) + '</span>'
      + '<span class="tag">昨高 ' + fmt(d.prevHigh, 3) + '</span>'
      + '<span class="tag">昨低 ' + fmt(d.prevLow, 3) + '</span>'
      + '<span class="tag">今开 ' + fmt(d.open, 3) + '</span>'
      + '<span class="tag">均价 ' + fmt(d.vwap, 3) + '</span>'
      + '<span class="tag">MA5 ' + fmt(d.ma5, 3) + '</span>'
      + '<span class="tag">MA20 ' + fmt(d.ma20, 3) + '</span>'
      + (d.divergence ? '<span class="tag watch">今日分歧日</span>' : '');
    if (!document.getElementById('ttBuyPrice').value && d.prevLow) document.getElementById('ttBuyPrice').value = d.prevLow;
    if (!document.getElementById('ttSellPrice').value && d.prevHigh) document.getElementById('ttSellPrice').value = d.prevHigh;
  } catch (e) {
    box.innerHTML = '<span class="hint" style="margin:0">' + e.message + '</span>';
  }
}

function fillTtFromPosition() {
  const code = document.getElementById('ttCode').value.trim();
  const p = (positionCache || []).find(x => x.code === code);
  if (!p) {
    if (!code && (positionCache || []).length) {
      const first = positionCache[0];
      document.getElementById('ttCode').value = first.code;
      document.getElementById('ttShares').value = first.shares || '';
      document.getElementById('ttCost').value = first.costPrice || '';
      toast('已带入 ' + (first.name || first.code), 'success');
      return;
    }
    toast('持仓里没找到这只，先更新现价或手填', 'warn');
    return;
  }
  document.getElementById('ttShares').value = p.shares || '';
  document.getElementById('ttCost').value = p.costPrice || '';
  toast('已带入持仓数量和成本', 'success');
}

function calcT() {
  const shares = numOrNull('ttShares') || 0;
  const cost = numOrNull('ttCost') || 0;
  const bp = numOrNull('ttBuyPrice');
  const bs = numOrNull('ttBuyShares') || 0;
  const sp = numOrNull('ttSellPrice');
  const ss = numOrNull('ttSellShares') || 0;
  const code = document.getElementById('ttCode').value.trim();
  const rateIn = numOrNull('ttFeeRate');
  const feeRate = (rateIn == null ? 0.025 : rateIn) / 100;
  const minIn = numOrNull('ttFeeMin');
  const feeMin = minIn == null ? 5 : minIn;
  const box = document.getElementById('ttResult');
  if (shares <= 0 || cost <= 0) { box.innerHTML = '先填持仓数量和成本价（或点「从持仓带入」）。'; return; }
  if ((bs > 0 && bp == null) || (ss > 0 && sp == null)) { box.innerHTML = '买卖价格要填完整。'; return; }
  // ETF（沪 5xxxxx / 深 15、16、18 开头）免印花税和过户费，只收佣金
  const isEtf = /^5|^1[568]/.test(code);
  const buyAmt = bs * (bp || 0);
  const sellAmt = ss * (sp || 0);
  const buyComm = buyAmt > 0 ? Math.max(buyAmt * feeRate, feeMin) : 0;
  const sellComm = sellAmt > 0 ? Math.max(sellAmt * feeRate, feeMin) : 0;
  const stampTax = isEtf ? 0 : sellAmt * 0.0005;      // 印花税 0.05%，仅卖出、仅股票
  const transferFee = isEtf ? 0 : (buyAmt + sellAmt) * 0.00001; // 过户费 0.001%，双边、仅股票
  const totalFee = Math.round((buyComm + sellComm + stampTax + transferFee) * 100) / 100;
  const costAmt = shares * cost;
  const newShares = shares + bs - ss;
  if (newShares < 0) { box.innerHTML = '卖出数量超过总持仓了。'; return; }
  if (newShares === 0) {
    const pl = sellAmt - costAmt - buyAmt - totalFee;
    box.innerHTML = '全部卖完了。本次总盈亏（已扣手续费 ' + fmt(totalFee) + ' 元）<b class="' + (pl >= 0 ? 'pos' : 'neg') + '">' + fmt(pl) + '</b> 元。';
    return;
  }
  // 手续费计入摊成本：真金白银付出的费用会抬高/摊低实际成本
  const newCost = (costAmt + buyAmt - sellAmt + totalFee) / newShares;
  const drop = cost - newCost;
  const pairShares = Math.min(bs, ss);
  const grossT = pairShares > 0 && bp != null && sp != null ? (sp - bp) * pairShares : null;
  const netT = grossT == null ? null : grossT - totalFee;
  const feeDetail = '手续费合计 <b>' + fmt(totalFee) + '</b> 元（佣金 ' + fmt(buyComm + sellComm)
    + (isEtf ? '，ETF 免印花税/过户费' : ' + 印花税 ' + fmt(stampTax) + ' + 过户费 ' + fmt(transferFee)) + '）';
  let tPart = '';
  if (grossT != null) {
    tPart = '<br>配对做T ' + fmt(pairShares, 0) + ' 股：差价毛收益 <b>' + fmt(grossT) + '</b> 元，扣费后净收益 <b class="' + (netT >= 0 ? 'pos' : 'neg') + '">' + fmt(netT) + '</b> 元'
      + '<br>' + feeDetail;
    if (netT < 0) {
      tPart += '<br><span class="neg">⚠️ 价差不够付手续费，这趟T是白做，不如不动。</span>';
    } else if (grossT > 0 && totalFee / grossT > 0.3) {
      tPart += '<br><span class="neg">⚠️ 手续费吃掉了差价的 ' + (totalFee / grossT * 100).toFixed(0) + '%，价差偏薄，慎做。</span>';
    }
  } else {
    tPart = '<br>' + feeDetail;
  }
  box.innerHTML =
    '新持仓 <b>' + fmt(newShares, 0) + '</b> 股，新成本 <b>' + newCost.toFixed(3) + '</b> 元（含手续费，原 ' + cost.toFixed(3)
    + '，' + (drop >= 0 ? '降 <span class="pos">' + drop.toFixed(3) : '升 <span class="neg">' + Math.abs(drop).toFixed(3)) + '</span> 元/股）'
    + tPart
    + '<br><span class="hint" style="margin:0">提示：卖出超过买入的部分按减仓算，会直接兑现盈亏。佣金率按你券商实际值改（默认万2.5、单笔最低5元）；ETF 按代码自动识别，免印花税和过户费。</span>';
}

/* ---------- 仓位计算器（P1-6） ---------- */
function calcSizing() {
  let cap = numOrNull('szCapital');
  if (cap == null) {
    const saved = parseFloat(localStorage.getItem('posCapital') || '');
    if (!isNaN(saved) && saved > 0) cap = saved;
  }
  const riskPct = numOrNull('szRiskPct') || 2;
  const stopPct = numOrNull('szStopPct') || 8;
  const price = numOrNull('szPrice');
  const box = document.getElementById('szResult');
  if (cap == null || cap <= 0) { box.innerHTML = '填账户总资金（或在持仓页填过一次，这里会自动带）。'; return; }
  if (price == null || price <= 0) { box.innerHTML = '填计划买入价。'; return; }
  const riskAmt = cap * riskPct / 100;
  const buyAmt = riskAmt / (stopPct / 100);
  const shares = Math.floor(buyAmt / price / 100) * 100;
  const realAmt = shares * price;
  box.innerHTML =
    '单笔最多亏 <b>' + fmt(riskAmt) + '</b> 元（' + riskPct + '% 风险预算），按 ' + stopPct + '% 止损幅度：'
    + '<br>可买金额 <b>' + fmt(buyAmt) + '</b> 元 ≈ <b>' + fmt(shares, 0) + '</b> 股（' + fmt(realAmt) + ' 元）'
    + '<br>实际占总资金 <b>' + (realAmt / cap * 100).toFixed(1) + '%</b>，止损触发时约亏 <span class="neg">' + fmt(realAmt * stopPct / 100) + '</span> 元'
    + (buyAmt > cap ? '<br><span class="neg">注意：按这个参数算出的金额超过总资金了，说明止损幅度设得太小或风险预算太松。</span>' : '');
}

function recalcPositionPrice() {
  const shares = numOrNull('pShares');
  const amt = numOrNull('pCostAmt');
  if (shares != null && shares !== 0 && amt != null) {
    document.getElementById('pCost').value = (amt / shares).toFixed(3);
  }
}

function resetPosition() {
  document.getElementById('pId').value = '';
  document.getElementById('pCode').value = '';
  document.getElementById('pName').value = '';
  document.getElementById('pNotes').value = '';
  document.getElementById('pShares').value = '';
  document.getElementById('pCost').value = '';
  document.getElementById('pCostAmt').value = '';
  document.getElementById('pStopPct').value = '';
  document.getElementById('pSaveBtn').textContent = '💾 保存持仓';
}

function openPosition(id, code, name, shares, costPrice, notes, stopPct) {
  show('position');
  resetPosition();
  if (id) document.getElementById('pId').value = id;
  document.getElementById('pCode').value = code || '';
  document.getElementById('pName').value = name || '';
  document.getElementById('pNotes').value = notes || '';
  if (shares != null) document.getElementById('pShares').value = shares;
  if (costPrice != null) document.getElementById('pCost').value = costPrice;
  if (stopPct != null) document.getElementById('pStopPct').value = stopPct;
  recalcPositionCost();
  if (id) document.getElementById('pSaveBtn').textContent = '💾 保存修改';
  document.getElementById('pShares').focus();
}

function editPosition(id) {
  const p = (positionCache || []).find(x => x.id === id);
  if (!p) return;
  openPosition(p.id, p.code, p.name, p.shares, p.costPrice, p.notes, p.stopPct);
}

async function savePosition() {
  const body = {
    code: document.getElementById('pCode').value.trim(),
    name: document.getElementById('pName').value.trim(),
    notes: document.getElementById('pNotes').value.trim(),
    shares: numOrNull('pShares'),
    costPrice: numOrNull('pCost'),
    costAmount: numOrNull('pCostAmt'),
    stopPct: numOrNull('pStopPct')
  };
  if (!body.code) { toast('请填写股票代码', 'warn'); return; }
  if (body.shares == null || body.shares <= 0) { toast('请填写持仓数量', 'warn'); return; }
  try {
    const id = document.getElementById('pId').value;
    if (id) await putJSON('/api/positions/' + id, body);
    else await postJSON('/api/positions', body);
    toast('持仓已保存', 'success');
    resetPosition();
    loadPositions();
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function clearPosition(id) {
  if (!confirm('清仓这只股票？自选会保留，仅去掉数量和成本。')) return;
  try {
    await del('/api/positions/' + id);
    toast('已清仓', 'success');
    loadPositions();
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function loadCharts() {
  const kpi = document.getElementById('chartKpi');
  kpi.innerHTML = loadingHtml();
  try {
    const d = await getJSON('/api/stats/charts', 8000);
    const disc = d.discipline || {};
    kpi.innerHTML =
      kpiHtml(d.positionCount || 0, '持仓只数')
      + kpiHtml(fmt(d.positionMarket), '持仓市值')
      + '<div class="kpi"><div class="v ' + pctCls(d.positionPl) + '">' + (d.positionPl == null ? '-' : fmt(d.positionPl)) + '</div><div class="l">浮动盈亏</div></div>'
      + kpiHtml(d.tradeCount || 0, '成交笔数')
      + (disc.total != null
        ? '<div class="kpi"><div class="v ' + (disc.executeRate == null ? '' : (disc.executeRate >= 60 ? 'up' : 'down')) + '">'
          + (disc.executeRate == null ? '-' : disc.executeRate + '%') + '</div><div class="l">纪律执行率</div></div>'
        + '<div class="kpi"><div class="v down">' + (disc.avgIgnoredExtraLoss == null ? '-' : disc.avgIgnoredExtraLoss + '%') + '</div><div class="l">硬抗平均多亏</div></div>'
        : '');
    const months = d.months || [];
    dualBarChart('chartMonth', months.map(m => m.month), months.map(m => m.buy), months.map(m => m.sell), '买入', '卖出');
    lineChart('chartMood', d.reviewDates || [], d.sentiments || [], '情绪');
    const holds = d.positions || [];
    signedBarChart('chartHold', holds.map(s => s.name || s.code), holds.map(s => s.floatPl || 0));
    const stocks = d.stockStats || [];
    barChart('chartStock', stocks.map(s => s.name || s.code), stocks.map(s => s.realized), '#f53f3f');
    const mistakes = d.mistakes || [];
    mistakeBarChart('chartMistake', mistakes.map(m => m.tag), mistakes.map(m => m.count));
    fillStrategyOverview(null);
    getJSON('/api/strategy/overview', 8000).then(fillStrategyOverview).catch(() => fillStrategyOverview({ skipped: -1 }));
  } catch (e) {
    kpi.innerHTML = '<div class="empty">' + e.message + '</div>';
  }
}

function fillStrategyOverview(ov) {
  const winHint = document.getElementById('chartWinHint');
  if (!ov) {
    if (winHint) winHint.textContent = '策略胜率单独加载，不挡住上方成交统计。';
    return;
  }
  if (ov.skipped === -1) {
    if (winHint) winHint.textContent = '策略胜率暂时没拉到。先去「策略学习」看几只票，再回来刷新。';
    return;
  }
  const strats = ov.strategies || [];
  barChart('chartWin',
    strats.map(s => s.strategyName),
    strats.map(s => s.winRate5d == null ? 0 : s.winRate5d),
    '#165dff');
  barChart('chartSig',
    strats.map(s => s.strategyName),
    strats.map(s => (s.buyCount || 0) + (s.sellCount || 0)),
    '#d4820a');
  if (winHint) {
    winHint.textContent = (ov.stockCount ? ('已用缓存日 K 统计 ' + ov.stockCount + ' 只。') : '')
      + (ov.skipped ? '有 ' + ov.skipped + ' 只还没拉过 K 线，打开「策略学习」看过的票才会进入胜率。' : (ov.stockCount ? '' : '策略胜率不现场全算自选，避免卡住。先去「策略学习」看几只，再回来刷新。'));
  }
}

function kpiHtml(v, l) {
  return '<div class="kpi"><div class="v">' + v + '</div><div class="l">' + l + '</div></div>';
}

async function loadTrades() {
  if (!document.getElementById('tDate').value) document.getElementById('tDate').value = todayStr();
  try {
    const list = await getJSON('/api/trades');
    const body = document.getElementById('tradeBody');
    if (!list.length) {
      body.innerHTML = '<tr><td colspan="12" class="empty">暂无成交。上方手填保存，或从同花顺导入。</td></tr>';
      renderTradeCompare(null);
      return;
    }
    tradeCache = list;
    body.innerHTML = list.map(t => tradeRowHtml(t, null)).join('');
    renderTradeCompare(null, true);
    try {
      const cmp = await getJSON('/api/trades/signal-compare');
      const map = {};
      (cmp.items || []).forEach(r => { map[r.tradeId] = r; });
      body.innerHTML = list.map(t => tradeRowHtml(t, map[t.id])).join('');
      renderTradeCompare(cmp, false);
    } catch (ce) {
      renderTradeCompare({ summary: '对照失败：' + ce.message }, false);
    }
  } catch (e) {
    toast(e.message, 'error');
  }
}

function tradeRowHtml(t, c) {
  const codeCell = '<a onclick="openStrategy(\'' + t.code + '\')">' + t.code + '</a>';
  return '<tr><td>' + t.tradeDate + '</td><td>' + codeCell + '</td><td>' + (t.name || '') + '</td>'
    + '<td><span class="tag ' + t.direction + '">' + dirLabel(t.direction) + '</span></td>'
    + '<td>' + fmt(t.shares, 2) + '</td><td>' + fmt(t.price, 3) + '</td><td>' + fmt(t.amount) + '</td>'
    + compareCells(c)
    + '<td>' + (t.source === 'THS' ? '<span class="tag ths">同花顺</span>' : '<span class="tag hold">手工</span>') + '</td>'
    + '<td><a onclick="editTrade(' + t.id + ')">编辑</a> · <a class="danger" onclick="delTrade(' + t.id + ')">删</a></td></tr>';
}

function compareCells(c) {
  if (!c) {
    return '<td class="cmp-cell"><span class="tag hold">对照中</span></td><td>-</td><td>-</td>';
  }
  const cls = ({ HIT: 'hit', CHASE: 'chase', LATE: 'late', EARLY: 'early', AGAINST: 'against', NONE: 'hold' })[c.verdict] || 'hold';
  const days = formatDaysDiff(c.daysDiff);
  const pct = c.priceDiffPct == null ? '-' : '<span class="' + pctCls(c.priceDiffPct) + '">' + signPct(c.priceDiffPct) + '</span>';
  const title = (c.explain || '').replace(/"/g, '&quot;');
  const sub = c.signalDate ? (c.signalDate + ' ' + (c.signalReason || '')) : '';
  return '<td class="cmp-cell" title="' + title + '"><span class="tag ' + cls + '">' + (c.verdictLabel || c.label || c.verdict) + '</span>'
    + (sub ? '<div class="sub">' + sub + '</div>' : '') + '</td>'
    + '<td>' + days + '</td><td>' + pct + '</td>';
}

function formatDaysDiff(d) {
  if (d == null || d === '') return '-';
  if (d === 0) return '当天';
  return d > 0 ? '后 ' + d + ' 日' : '前 ' + Math.abs(d) + ' 日';
}

function renderTradeCompare(cmp, loading) {
  const kpi = document.getElementById('tradeCompareKpi');
  const sum = document.getElementById('tradeCompareSummary');
  if (!kpi) return;
  if (loading) {
    kpi.innerHTML = loadingHtml('按股票拉取 K 线并对照买/卖点...');
    if (sum) sum.textContent = '';
    return;
  }
  if (!cmp || !cmp.total) {
    kpi.innerHTML = '';
    if (sum) sum.textContent = (cmp && cmp.summary) || '录入成交后，会把每笔买卖对上最近的策略信号。';
    return;
  }
  kpi.innerHTML = kpiHtml(cmp.hit || 0, '当天打中')
    + kpiHtml(cmp.chase || 0, '信号后追')
    + kpiHtml(cmp.early || 0, '提前')
    + kpiHtml(cmp.late || 0, '偏晚')
    + kpiHtml(cmp.against || 0, '逆信号')
    + kpiHtml(cmp.none || 0, '附近无信号')
    + kpiHtml(cmp.avgDaysAfter == null ? '-' : cmp.avgDaysAfter, '平均晚几天');
  if (sum) sum.textContent = cmp.summary || '';
}

function recalcTradeAmount() {
  const shares = numOrNull('tShares');
  const price = numOrNull('tPrice');
  if (shares != null && price != null) {
    document.getElementById('tAmount').value = (shares * price).toFixed(2);
  }
}

function resetTrade() {
  document.getElementById('tId').value = '';
  document.getElementById('tCode').value = '';
  document.getElementById('tName').value = '';
  document.getElementById('tShares').value = '';
  document.getElementById('tPrice').value = '';
  document.getElementById('tAmount').value = '';
  document.getElementById('tNote').value = '';
  document.getElementById('tDir').value = 'buy';
  const stop = document.getElementById('tStop');
  const target = document.getElementById('tTarget');
  const days = document.getElementById('tHoldDays');
  if (stop) stop.value = '';
  if (target) target.value = '';
  if (days) days.value = '';
  const writePlan = document.getElementById('tWritePlan');
  if (writePlan) writePlan.checked = true;
  document.getElementById('tSaveBtn').textContent = '💾 保存成交';
  document.getElementById('tradeFormTitle').textContent = '手动录入成交';
  if (!document.getElementById('tDate').value) document.getElementById('tDate').value = todayStr();
}

function editTrade(id) {
  const t = (tradeCache || []).find(x => x.id === id);
  if (!t) return;
  document.getElementById('tId').value = t.id;
  document.getElementById('tDate').value = t.tradeDate || todayStr();
  document.getElementById('tCode').value = t.code || '';
  document.getElementById('tName').value = t.name || '';
  document.getElementById('tDir').value = t.direction || 'buy';
  document.getElementById('tShares').value = t.shares != null ? t.shares : '';
  document.getElementById('tPrice').value = t.price != null ? t.price : '';
  document.getElementById('tAmount').value = t.amount != null ? t.amount : '';
  document.getElementById('tNote').value = t.note || '';
  document.getElementById('tSaveBtn').textContent = '💾 保存修改';
  document.getElementById('tradeFormTitle').textContent = '编辑成交';
  document.getElementById('tCode').focus();
}

async function saveTrade() {
  try {
    const body = {
      tradeDate: document.getElementById('tDate').value,
      code: document.getElementById('tCode').value.trim(),
      name: document.getElementById('tName').value.trim(),
      direction: document.getElementById('tDir').value,
      shares: numOrNull('tShares'),
      price: numOrNull('tPrice'),
      amount: numOrNull('tAmount'),
      note: document.getElementById('tNote').value.trim(),
      source: 'MANUAL'
    };
    const id = document.getElementById('tId').value;
    let saved = null;
    if (id) await putJSON('/api/trades/' + id, body);
    else saved = await postJSON('/api/trades', body);
    if (!id && body.direction === 'buy' && document.getElementById('tWritePlan') && document.getElementById('tWritePlan').checked) {
      try {
        await postJSON('/api/plans', {
          code: body.code,
          name: body.name,
          planDate: body.tradeDate || todayStr(),
          planPrice: body.price,
          stopPrice: numOrNull('tStop'),
          targetPrice: numOrNull('tTarget'),
          holdDays: numOrNull('tHoldDays'),
          reason: body.note || '买入时写入计划',
          buyTradeId: saved && saved.id
        });
      } catch (pe) {
        toast('成交已保存，计划未写入：' + pe.message, 'warn');
      }
    }
    if (!id && body.direction === 'sell') {
      try {
        await postJSON('/api/plans/close-code?code=' + encodeURIComponent(body.code), { note: body.note || '卖出结束计划' });
      } catch (_) {}
    }
    toast('成交已保存', 'success');
    resetTrade();
    loadTrades();
  } catch (e) {
    toast(e.message, 'error');
  }
}

function numOrNull(id) {
  const v = document.getElementById(id).value;
  return v === '' ? null : Number(v);
}

async function delTrade(id) {
  if (!confirm('删除这笔成交？')) return;
  try {
    await del('/api/trades/' + id);
    loadTrades();
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function importThs() {
  const file = document.getElementById('thsFile').files[0];
  if (!file) { toast('请选择文件', 'warn'); return; }
  const fd = new FormData();
  fd.append('file', file);
  const box = document.getElementById('importResult');
  box.innerHTML = loadingHtml('导入中...');
  try {
    const r = await fetch('/api/trades/import-ths', { method: 'POST', body: fd });
    const j = await r.json();
    if (!r.ok) throw new Error(j.message || '导入失败');
    box.innerHTML = '<div class="alert ok">导入成功 ' + j.imported + ' 条，跳过（重复或空行） ' + j.skipped + ' 条。</div>'
      + ((j.errors || []).length ? '<div class="alert warn">' + j.errors.slice(0, 8).join('<br>') + '</div>' : '');
    toast('导入完成', 'success');
    loadTrades();
  } catch (e) {
    box.innerHTML = '<div class="alert bad">' + e.message + '</div>';
  }
}

document.getElementById('reviewDate').addEventListener('change', loadToday);
loadToday();
initStrategyPicks();
