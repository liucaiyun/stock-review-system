const chartPool = {};

function hasEcharts() {
  return typeof echarts !== 'undefined';
}

function getChart(id) {
  if (!hasEcharts()) return null;
  const el = document.getElementById(id);
  if (!el) return null;
  if (chartPool[id]) {
    chartPool[id].resize();
    return chartPool[id];
  }
  chartPool[id] = echarts.init(el);
  return chartPool[id];
}

function renderKlineChart(elId, data) {
  const el = document.getElementById(elId);
  if (!el || !hasEcharts()) {
    if (el) el.innerHTML = '<div class="empty">图表库未加载，请检查网络后刷新</div>';
    return;
  }
  if (chartPool[elId]) {
    chartPool[elId].dispose();
    delete chartPool[elId];
  }
  const chart = echarts.init(el);
  chartPool[elId] = chart;
  const dates = data.klines.map(k => k.date);
  const ohlc = data.klines.map(k => [k.open, k.close, k.low, k.high]);
  const vols = data.klines.map(k => k.volume);
  const ma5 = (data.indicators.ma5 || []);
  const ma20 = (data.indicators.ma20 || []);
  const ma60 = (data.indicators.ma60 || []);
  const overlay = data.overlay || {};
  const buy = [];
  const sell = [];
  const watchPts = [];
  (data.signals || []).forEach(s => {
    if (s.action === 'BUY') buy.push([s.date, s.price]);
    else if (s.action === 'SELL') sell.push([s.date, s.price]);
    else watchPts.push([s.date, s.price]);
  });
  // 分歧日标记：振幅>8%且实体<2%，画灰色 × 在K线高点上方
  const divDays = (data.divergence && data.divergence.days) || [];
  const kByDate = {};
  data.klines.forEach(k => { kByDate[k.date] = k; });
  const divPts = divDays.map(d => {
    const k = kByDate[d];
    return k ? [d, (k.high * 1.015).toFixed(3) * 1] : null;
  }).filter(Boolean);
  const myBuy = [];
  const mySell = [];
  (data.trades || []).forEach(t => {
    if (t.price == null || !t.date) return;
    if (t.direction === 'buy') myBuy.push([t.date, t.price]);
    else if (t.direction === 'sell') mySell.push([t.date, t.price]);
  });
  const selected = data.selected || [];
  const volMa5 = (data.indicators.volMa5 || []);
  const volMa20 = (data.indicators.volMa20 || []);
  const legend = ['K线', 'MA5', 'MA20生命线', 'MA60', '买点', '卖点'];
  if (watchPts.length) legend.push('警示');
  if (divPts.length) legend.push('分歧日');
  if (watchPts.length) legend.push('警示');
  if (divPts.length) legend.push('分歧日');
  if (myBuy.length) legend.push('我的买');
  if (mySell.length) legend.push('我的卖');
  const markLines = [];
  const pushLevel = (name, val, color, type, width, pos) => {
    if (val == null || val === '') return;
    markLines.push({
      name: name + ' ' + Number(val).toFixed(2),
      yAxis: Number(val),
      label: { formatter: name + ' {c}', position: pos || 'insideEndTop' },
      lineStyle: { color: color, type: type || 'dashed', width: width || 1 }
    });
  };
  pushLevel('成本', overlay.costPrice, '#165dff', 'solid', 1.6);
  if (overlay.planPrice != null && (overlay.costPrice == null || Math.abs(overlay.planPrice - overlay.costPrice) > 0.001)) {
    pushLevel('计划买', overlay.planPrice, '#7b61ff', 'dashed', 1, 'insideEndBottom');
  }
  pushLevel('止损', overlay.stopPrice, '#00a854', 'dashed', 1.4, 'insideStartTop');
  pushLevel('目标', overlay.targetPrice, '#f53f3f', 'dashed', 1.4);
  pushLevel('前高', overlay.prevHigh, '#d4820a', 'dotted', 1.2);
  pushLevel('前低', overlay.prevLow, '#86909c', 'dotted', 1.2, 'insideEndBottom');
  const markPoints = [];
  if (overlay.prevHigh != null && overlay.prevHighDate) {
    markPoints.push({ name: '前高', coord: [overlay.prevHighDate, overlay.prevHigh], value: overlay.prevHigh, itemStyle: { color: '#d4820a' } });
  }
  if (overlay.prevLow != null && overlay.prevLowDate) {
    markPoints.push({ name: '前低', coord: [overlay.prevLowDate, overlay.prevLow], value: overlay.prevLow, itemStyle: { color: '#86909c' } });
  }
  if (data.replaying && dates.length) {
    markLines.push({
      name: '回放到这天',
      xAxis: dates[dates.length - 1],
      label: { formatter: '回放', position: 'insideStartTop' },
      lineStyle: { color: '#165dff', type: 'solid', width: 1.2 }
    });
  }
  const series = [
      {
        name: 'K线', type: 'candlestick', data: ohlc, xAxisIndex: 0, yAxisIndex: 0,
        itemStyle: { color: '#f53f3f', color0: '#00a854', borderColor: '#f53f3f', borderColor0: '#00a854' },
        markLine: markLines.length ? { silent: true, symbol: 'none', data: markLines } : undefined,
        markPoint: markPoints.length ? { symbol: 'pin', symbolSize: 28, data: markPoints, label: { formatter: '{b}' } } : undefined
      },
      { name: 'MA5', type: 'line', data: ma5, xAxisIndex: 0, yAxisIndex: 0, showSymbol: false, itemStyle: { color: '#d4820a' }, lineStyle: { width: 1, color: '#d4820a' } },
      { name: 'MA20生命线', type: 'line', data: ma20, xAxisIndex: 0, yAxisIndex: 0, showSymbol: false, itemStyle: { color: '#165dff' }, lineStyle: { width: 2.4, color: '#165dff' } },
      { name: 'MA60', type: 'line', data: ma60, xAxisIndex: 0, yAxisIndex: 0, showSymbol: false, itemStyle: { color: '#86909c' }, lineStyle: { width: 1, color: '#86909c' } }
  ];
  if (selected.indexOf('BOLL') >= 0) {
    legend.push('布林上轨', '布林中轨', '布林下轨');
    series.push(
      { name: '布林上轨', type: 'line', data: data.indicators.bollUp || [], xAxisIndex: 0, yAxisIndex: 0, showSymbol: false, lineStyle: { width: 1, type: 'dashed', color: '#86909c' } },
      { name: '布林中轨', type: 'line', data: data.indicators.bollMid || [], xAxisIndex: 0, yAxisIndex: 0, showSymbol: false, lineStyle: { width: 1, color: '#4e5969' } },
      { name: '布林下轨', type: 'line', data: data.indicators.bollDn || [], xAxisIndex: 0, yAxisIndex: 0, showSymbol: false, lineStyle: { width: 1, type: 'dashed', color: '#86909c' } }
    );
  }
  series.push(
      { name: '买点', type: 'scatter', data: buy, xAxisIndex: 0, yAxisIndex: 0, symbol: 'triangle', symbolSize: 10, itemStyle: { color: '#f53f3f' } },
      { name: '卖点', type: 'scatter', data: sell, xAxisIndex: 0, yAxisIndex: 0, symbol: 'triangle', symbolRotate: 180, symbolSize: 10, itemStyle: { color: '#00a854' } }
  );
  if (myBuy.length) {
    series.push({ name: '我的买', type: 'scatter', data: myBuy, xAxisIndex: 0, yAxisIndex: 0, symbol: 'diamond', symbolSize: 14, itemStyle: { color: '#165dff', borderColor: '#fff', borderWidth: 1 } });
  }
  if (mySell.length) {
    series.push({ name: '我的卖', type: 'scatter', data: mySell, xAxisIndex: 0, yAxisIndex: 0, symbol: 'diamond', symbolSize: 14, itemStyle: { color: '#722ed1', borderColor: '#fff', borderWidth: 1 } });
  }
  legend.push('5日均量', '20日均量');
  series.push(
      {
        name: '成交量', type: 'bar', data: vols, xAxisIndex: 1, yAxisIndex: 1,
        itemStyle: {
          color: (p) => {
            const k = data.klines[p.dataIndex];
            return k && k.close >= k.open ? '#f53f3f' : '#00a854';
          }
        }
      },
      {
        name: '5日均量', type: 'line', data: volMa5, xAxisIndex: 1, yAxisIndex: 1,
        showSymbol: false, itemStyle: { color: '#d4820a' },
        lineStyle: { width: 1.2, color: '#d4820a' }
      },
      {
        name: '20日均量', type: 'line', data: volMa20, xAxisIndex: 1, yAxisIndex: 1,
        showSymbol: false, itemStyle: { color: '#165dff' },
        lineStyle: { width: 1.8, color: '#165dff' }
      }
  );
  chart.setOption({
    animation: false,
    legend: { data: legend, top: 0 },
    tooltip: { trigger: 'axis' },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 50, right: 56, top: 40, height: '52%' },
      { left: 50, right: 56, top: '72%', height: '18%' }
    ],
    xAxis: [
      { type: 'category', data: dates, gridIndex: 0, axisLabel: { show: false } },
      { type: 'category', data: dates, gridIndex: 1 }
    ],
    yAxis: [
      { scale: true, gridIndex: 0, splitLine: { lineStyle: { type: 'dashed' } } },
      { scale: true, gridIndex: 1, splitNumber: 2, axisLabel: { show: false }, splitLine: { show: false } }
    ],
    dataZoom: [{ type: 'inside', xAxisIndex: [0, 1], start: dates.length > 80 ? 55 : 0, end: 100 },
               { xAxisIndex: [0, 1], start: dates.length > 80 ? 55 : 0, end: 100, height: 18, bottom: 8 }],
    series: series
  });
}

function barChart(id, names, values, color) {
  const c = getChart(id);
  if (!c) return;
  c.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 24, bottom: 48 },
    xAxis: { type: 'category', data: names, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', data: values, itemStyle: { color: color || '#165dff' }, barMaxWidth: 36 }]
  }, true);
}

function dualBarChart(id, names, a, b, la, lb) {
  const c = getChart(id);
  if (!c) return;
  c.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: [la, lb] },
    grid: { left: 48, right: 16, top: 32, bottom: 36 },
    xAxis: { type: 'category', data: names },
    yAxis: { type: 'value' },
    series: [
      { name: la, type: 'bar', data: a, itemStyle: { color: '#f53f3f' }, barMaxWidth: 22 },
      { name: lb, type: 'bar', data: b, itemStyle: { color: '#00a854' }, barMaxWidth: 22 }
    ]
  }, true);
}

function lineChart(id, names, values, name) {
  const c = getChart(id);
  if (!c) return;
  c.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 24, bottom: 36 },
    xAxis: { type: 'category', data: names },
    yAxis: { type: 'value', min: 1, max: 5 },
    series: [{ name: name, type: 'line', data: values, smooth: true, itemStyle: { color: '#165dff' } }]
  }, true);
}

function signedBarChart(id, names, values) {
  const c = getChart(id);
  if (!c) return;
  c.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 16, top: 24, bottom: 48 },
    xAxis: { type: 'category', data: names, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value' },
    series: [{
      type: 'bar',
      data: (values || []).map(v => ({
        value: v,
        itemStyle: { color: v >= 0 ? '#f53f3f' : '#00a854' }
      })),
      barMaxWidth: 36
    }]
  }, true);
}

function pieChart(id, items) {
  const c = getChart(id);
  if (!c) return;
  if (!items || !items.length) {
    c.clear();
    return;
  }
  c.setOption({
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c}（{d}%）' },
    series: [{
      type: 'pie',
      radius: ['38%', '66%'],
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { formatter: '{b}\n{d}%' },
      data: items
    }]
  }, true);
}

window.addEventListener('resize', () => {
  Object.values(chartPool).forEach(c => c && c.resize());
});

/** 净值曲线：我的净值 vs 沪深300（都归一到 1 起步） */
function equityLineChart(id, dates, mine, bench) {
  const c = getChart(id);
  if (!c) return;
  const series = [{
    name: '我的净值', type: 'line', data: mine, smooth: true, showSymbol: false,
    itemStyle: { color: '#165dff' }, lineStyle: { width: 2.4, color: '#165dff' },
    areaStyle: { color: 'rgba(22,93,255,0.08)' }
  }];
  const legend = ['我的净值'];
  if (bench && bench.length) {
    legend.push('沪深300');
    series.push({
      name: '沪深300', type: 'line', data: bench, smooth: true, showSymbol: false,
      itemStyle: { color: '#86909c' }, lineStyle: { width: 1.4, color: '#86909c', type: 'dashed' },
      connectNulls: true
    });
  }
  c.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { data: legend, top: 0 },
    grid: { left: 48, right: 16, top: 32, bottom: 36 },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', scale: true, axisLabel: { formatter: (v) => v.toFixed(3) }, splitLine: { lineStyle: { type: 'dashed' } } },
    series: series
  }, true);
}

/** 错题本横向条形图 */
function mistakeBarChart(id, tags, counts) {
  const c = getChart(id);
  if (!c) return;
  if (!tags || !tags.length) {
    c.clear();
    return;
  }
  c.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 24, top: 16, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: tags.slice().reverse() },
    series: [{
      type: 'bar', data: counts.slice().reverse(), barMaxWidth: 22,
      itemStyle: { color: '#f53f3f', borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right' }
    }]
  }, true);
}
