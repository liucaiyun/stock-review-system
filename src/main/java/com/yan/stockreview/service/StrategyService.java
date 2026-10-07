package com.yan.stockreview.service;

import com.yan.stockreview.dto.BoardProfile;
import com.yan.stockreview.dto.ChartOverlay;
import com.yan.stockreview.dto.HorizonStats;
import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.PlanView;
import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.dto.RelativeStrength;
import com.yan.stockreview.dto.ScenarioCard;
import com.yan.stockreview.dto.SignalPoint;
import com.yan.stockreview.dto.SimTrade;
import com.yan.stockreview.dto.StockSuggest;
import com.yan.stockreview.dto.StrategyStats;
import com.yan.stockreview.dto.TradeStats;
import com.yan.stockreview.entity.TradePlan;
import com.yan.stockreview.entity.TradeRecord;
import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.strategy.StrategyCatalog;
import com.yan.stockreview.strategy.StrategyEngine;
import com.yan.stockreview.strategy.TradeCost;
import com.yan.stockreview.strategy.TradeParams;
import com.yan.stockreview.strategy.TradeSimulator;
import com.yan.stockreview.strategy.WalkForwardAnalyzer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class StrategyService {

    private final QuoteClient quoteClient;
    private final WatchlistService watchlistService;
    private final TradePlanService tradePlanService;
    private final TradeService tradeService;
    private final BenchmarkService benchmarkService;
    private final MarketRegimeService marketRegimeService;
    private final StrategyEngine strategyEngine = new StrategyEngine();

    /** 每只股票的逐笔明细最多返回多少条（防止响应过大） */
    private static final int MAX_SIM_TRADES = 60;

    public StrategyService(QuoteClient quoteClient, WatchlistService watchlistService,
                           TradePlanService tradePlanService, TradeService tradeService,
                           BenchmarkService benchmarkService, MarketRegimeService marketRegimeService) {
        this.quoteClient = quoteClient;
        this.watchlistService = watchlistService;
        this.tradePlanService = tradePlanService;
        this.tradeService = tradeService;
        this.benchmarkService = benchmarkService;
        this.marketRegimeService = marketRegimeService;
    }

    public Map<String, Object> environment() {
        return marketRegimeService.summary();
    }

    public List<StockSuggest> search(String q) {
        return quoteClient.suggest(q);
    }

    public List<Map<String, Object>> catalog() {
        return StrategyCatalog.asJson();
    }

    public Map<String, Object> analyze(String codeOrKeyword, int limit) {
        return analyze(codeOrKeyword, limit, null, null);
    }

    public Map<String, Object> analyze(String codeOrKeyword, int limit, String strategies) {
        return analyze(codeOrKeyword, limit, strategies, null);
    }

    public Map<String, Object> analyze(String codeOrKeyword, int limit, String strategies, String asOf) {
        WatchStock stock = watchlistService.resolve(codeOrKeyword);
        List<KlineBar> full = quoteClient.fetchKline(stock.getSecid(), limit);
        List<String> allDates = full.stream().map(KlineBar::date).toList();
        List<KlineBar> bars = truncateTo(full, asOf);
        if (bars.size() < 35) {
            throw new IllegalArgumentException("回放到这一天时K线不足 35 根，请再往后拖几天");
        }
        StrategyEngine.AnalysisResult result = strategyEngine.analyze(bars, parseStrategies(strategies),
                benchmarkService.closesByDate());
        String lastDate = bars.get(bars.size() - 1).date();
        boolean replaying = !allDates.isEmpty() && !lastDate.equals(allDates.get(allDates.size() - 1));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", stock.getCode());
        map.put("name", stock.getName());
        map.put("market", stock.getMarket());
        map.put("secid", stock.getSecid());
        map.put("klines", bars);
        map.put("allDates", allDates);
        map.put("asOf", lastDate);
        map.put("replaying", replaying);
        map.put("indicators", result.indicators());
        map.put("signals", result.signals());
        map.put("latestSignals", latestPerStrategy(result.signals()));
        map.put("todaySignals", todaySignals(result.signals(), bars));
        map.put("consensus", consensus(todaySignals(result.signals(), bars), result.selected().size()));
        map.put("stats", result.stats());
        // 回测口径透明化：把「往返成本」和基准是否取到告诉前端，别再让用户猜
        Map<String, Object> benchInfo = new LinkedHashMap<>();
        benchInfo.put("name", BenchmarkService.BENCH_NAME);
        benchInfo.put("available", benchmarkService.available());
        benchInfo.put("error", benchmarkService.lastError());
        map.put("benchmark", benchInfo);
        map.put("tradeCostPct", round(TradeCost.roundTripCostRatio() * 100, 2));
        // 逐笔交易明细（每个策略只给最近 MAX_SIM_TRADES 笔，避免响应过大）
        Map<String, List<SimTrade>> simTrades = new LinkedHashMap<>();
        for (Map.Entry<String, List<SimTrade>> e : result.simTrades().entrySet()) {
            List<SimTrade> list = e.getValue();
            simTrades.put(e.getKey(), list.size() <= MAX_SIM_TRADES
                    ? list : list.subList(list.size() - MAX_SIM_TRADES, list.size()));
        }
        map.put("simTrades", simTrades);
        map.put("tradeModel", tradeModel());
        map.put("lessons", result.lessons());
        map.put("selected", result.selected());
        map.put("scenarios", result.scenarios());
        // 分歧日（振幅>8% 且实体<2%）：列表 + 当天判定，前端标图+建仓警告
        Map<String, Object> divergence = new LinkedHashMap<>();
        divergence.put("days", result.divergenceDays());
        if (!bars.isEmpty()) {
            int last = bars.size() - 1;
            divergence.put("today", StrategyEngine.isDivergenceDay(bars, last));
            double[] ab = StrategyEngine.amplitudeAndBody(bars, last);
            divergence.put("amplitude", ab[0]);
            divergence.put("body", ab[1]);
        } else {
            divergence.put("today", false);
        }
        map.put("divergence", divergence);
        map.put("overlay", fillOverlay(result.overlay(), stock));
        map.put("trades", tradeMarks(stock.getCode(), lastDate));
        map.put("disclaimer", "策略、情景和回放用于对照学习，不是买卖建议。过去回测不代表未来。");
        try {
            map.put("boards", quoteClient.fetchBoardProfile(stock.getCode(), stock.getMarket(), stock.getSecid()));
        } catch (Exception ignored) {
        }
        try {
            Double pct = bars.isEmpty() ? null : bars.get(bars.size() - 1).pctChange();
            map.put("strength", quoteClient.relativeStrength(stock.getCode(), stock.getMarket(), stock.getSecid(), pct));
        } catch (Exception ignored) {
        }
        if (!bars.isEmpty()) {
            KlineBar last = bars.get(bars.size() - 1);
            map.put("latestClose", last.close());
            map.put("latestDate", last.date());
            map.put("latestPct", last.pctChange());
        }
        return map;
    }

    private Set<String> parseStrategies(String strategies) {
        if (strategies == null || strategies.isBlank()) {
            return new LinkedHashSet<>(StrategyEngine.NAMES.keySet());
        }
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(strategies.split("[,，\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(set::add);
        return set;
    }

    private ChartOverlay fillOverlay(ChartOverlay overlay, WatchStock stock) {
        ChartOverlay out = overlay == null ? new ChartOverlay() : overlay;
        if (stock.getId() != null && stock.getShares() != null && stock.getShares() > 0 && stock.getCostPrice() != null) {
            out.setCostPrice(stock.getCostPrice());
        }
        try {
            TradePlan plan = tradePlanService.openOf(stock.getCode());
            if (plan != null) {
                out.setPlanPrice(plan.getPlanPrice());
                out.setStopPrice(plan.getStopPrice());
                out.setTargetPrice(plan.getTargetPrice());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public List<Map<String, Object>> watchlistSignals() {
        return watchlistRows(true);
    }

    public Map<String, Object> todayDigest() {
        List<QuoteSnapshot> indices;
        try {
            indices = indices();
        } catch (Exception ex) {
            indices = quoteClient.cachedQuotes(List.of("1.000001", "0.399001", "0.399006", "1.000300"));
        }
        List<Map<String, Object>> watch = watchlistRows(false);
        List<PlanView> plans = tradePlanService.list("OPEN", false);
        List<PlanView> planHits = new ArrayList<>();
        for (PlanView p : plans) {
            if (p.isHitStop() || p.isHitTarget() || p.isOverdue()) {
                planHits.add(p);
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("indices", indices);
        map.put("indexLine", indexLine(indices));
        map.put("watchlist", watch);
        map.put("plans", plans);
        map.put("planHits", planHits);
        map.put("scenes", groupWatchScenes(watch));
        try {
            map.put("environment", marketRegimeService.summary());
        } catch (Exception ignored) {
        }
        map.put("disclaimer", "摘要用于复盘对照，不是买卖建议。情景不记买点卖点。");
        return map;
    }

    private List<Map<String, Object>> watchlistRows(boolean extras) {
        List<WatchStock> stocks = watchlistService.list();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Double> bench = benchmarkService.closesByDate();
        int liveFetches = 0;
        for (WatchStock stock : stocks) {
            try {
                List<KlineBar> bars = quoteClient.peekKline(stock.getSecid());
                if (bars.size() < 35 && extras && liveFetches < 4) {
                    bars = quoteClient.fetchKline(stock.getSecid(), 180);
                    liveFetches++;
                }
                if (bars.size() < 35) {
                    continue;
                }
                StrategyEngine.AnalysisResult result = strategyEngine.analyze(bars, StrategyEngine.NAMES.keySet(), bench);
                List<SignalPoint> today = todaySignals(result.signals(), bars);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", stock.getCode());
                row.put("name", stock.getName());
                row.put("date", bars.get(bars.size() - 1).date());
                row.put("close", bars.get(bars.size() - 1).close());
                row.put("pctChange", bars.get(bars.size() - 1).pctChange());
                row.put("signals", today);
                row.put("consensus", consensus(today));
                row.put("scenarios", result.scenarios());
                row.put("divergence", StrategyEngine.isDivergenceDay(bars, bars.size() - 1));
                if (extras) {
                    try {
                        var board = quoteClient.peekBoard(stock.getCode());
                        if (board != null) {
                            row.put("boardPath", board.getPath());
                            row.put("region", board.getRegion());
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        var strength = quoteClient.peekRs(stock.getCode());
                        if (strength != null) {
                            row.put("strength", strength);
                        }
                    } catch (Exception ignored) {
                    }
                }
                out.add(row);
            } catch (Exception ignored) {
            }
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> {
            Object c = m.get("consensus");
            if ("BUY".equals(c)) return 0;
            if ("SELL".equals(c)) return 1;
            return 2;
        }));
        return out;
    }

    private String indexLine(List<QuoteSnapshot> indices) {
        if (indices == null || indices.isEmpty()) {
            return "指数行情暂不可用";
        }
        List<String> parts = new ArrayList<>();
        for (QuoteSnapshot idx : indices) {
            String name = idx.name() != null ? idx.name() : idx.code();
            Double pct = idx.pctChange();
            if (pct == null) {
                parts.add(name + " —");
            } else {
                parts.add(name + " " + (pct > 0 ? "+" : "") + round(pct, 2) + "%");
            }
        }
        return String.join("，", parts);
    }

    private Map<String, List<Map<String, Object>>> groupWatchScenes(List<Map<String, Object>> watch) {
        Map<String, List<Map<String, Object>>> g = new LinkedHashMap<>();
        g.put("pullbackHit", new ArrayList<>());
        g.put("pullbackNear", new ArrayList<>());
        g.put("pullbackBroken", new ArrayList<>());
        g.put("volHit", new ArrayList<>());
        g.put("volNear", new ArrayList<>());
        for (Map<String, Object> row : watch) {
            Object raw = row.get("scenarios");
            if (!(raw instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof ScenarioCard sc)) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("code", row.get("code"));
                one.put("name", row.get("name"));
                one.put("status", sc.getStatus());
                one.put("statusLabel", sc.getStatusLabel());
                if ("MA20_PULLBACK".equals(sc.getId())) {
                    if ("HIT".equals(sc.getStatus())) {
                        g.get("pullbackHit").add(one);
                    } else if ("NEAR".equals(sc.getStatus())) {
                        g.get("pullbackNear").add(one);
                    } else if ("BROKEN".equals(sc.getStatus())) {
                        g.get("pullbackBroken").add(one);
                    }
                } else if ("VOL_BREAK".equals(sc.getId())) {
                    if ("HIT".equals(sc.getStatus())) {
                        g.get("volHit").add(one);
                    } else if ("NEAR".equals(sc.getStatus())) {
                        g.get("volNear").add(one);
                    }
                }
            }
        }
        return g;
    }

    private List<KlineBar> truncateTo(List<KlineBar> bars, String asOf) {
        if (asOf == null || asOf.isBlank() || bars == null || bars.isEmpty()) {
            return bars;
        }
        List<KlineBar> cut = new ArrayList<>();
        for (KlineBar bar : bars) {
            if (bar.date() != null && bar.date().compareTo(asOf) <= 0) {
                cut.add(bar);
            }
        }
        if (cut.isEmpty()) {
            throw new IllegalArgumentException("回放日期早于这段K线的起点");
        }
        return cut;
    }

    private List<Map<String, Object>> tradeMarks(String code, String asOf) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (TradeRecord t : tradeService.listByCode(code)) {
                String date = t.getTradeDate() == null ? null : t.getTradeDate().toString();
                if (date == null) {
                    continue;
                }
                if (asOf != null && date.compareTo(asOf) > 0) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", t.getId());
                row.put("date", date);
                row.put("direction", t.getDirection());
                row.put("price", t.getPrice());
                row.put("shares", t.getShares());
                row.put("note", t.getNote());
                out.add(row);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public List<QuoteSnapshot> indices() {
        return quoteClient.fetchIndices();
    }

    public BoardProfile boardOf(String code) {
        return watchlistService.boardOf(code);
    }

    public RelativeStrength relativeStrength(String code) {
        WatchStock stock = watchlistService.resolve(code);
        return quoteClient.relativeStrength(stock.getCode(), stock.getMarket(), stock.getSecid());
    }

    /** 做T参考位：昨收/昨高/昨低、今开/现价/今高今低、均价(VWAP)、MA5/MA10/MA20 */
    public Map<String, Object> ttLevels(String codeOrKeyword) {
        WatchStock stock = watchlistService.resolve(codeOrKeyword);
        List<KlineBar> bars = quoteClient.fetchKline(stock.getSecid(), 60);
        if (bars.size() < 21) {
            throw new IllegalArgumentException("K线数据不足");
        }
        int last = bars.size() - 1;
        KlineBar today = bars.get(last);
        KlineBar prev = bars.get(last - 1);
        double[] close = bars.stream().mapToDouble(KlineBar::close).toArray();
        double ma5 = avg(close, last, 5);
        double ma10 = avg(close, last, 10);
        double ma20 = avg(close, last, 20);
        // VWAP：额(元) / 量(手×100)
        Double vwap = today.volume() > 0 && today.amount() > 0
                ? round(today.amount() / (today.volume() * 100), 3) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", stock.getCode());
        out.put("name", stock.getName());
        out.put("date", today.date());
        out.put("open", today.open());
        out.put("price", today.close());
        out.put("high", today.high());
        out.put("low", today.low());
        out.put("pctChange", today.pctChange());
        out.put("prevClose", prev.close());
        out.put("prevHigh", prev.high());
        out.put("prevLow", prev.low());
        out.put("vwap", vwap);
        out.put("ma5", round(ma5, 3));
        out.put("ma10", round(ma10, 3));
        out.put("ma20", round(ma20, 3));
        out.put("divergence", StrategyEngine.isDivergenceDay(bars, last));
        return out;
    }

    private static double avg(double[] src, int last, int n) {
        double sum = 0;
        int from = Math.max(0, last - n + 1);
        for (int i = from; i <= last; i++) {
            sum += src[i];
        }
        return sum / (last - from + 1);
    }

    private final Object overviewLock = new Object();
    private Map<String, Object> overviewCache;
    private long overviewCacheAt;

    public Map<String, Object> overviewStats() {
        synchronized (overviewLock) {
            if (overviewCache != null && System.currentTimeMillis() - overviewCacheAt < 10 * 60_000L) {
                return overviewCache;
            }
        }
        Map<String, Object> computed = computeOverviewLocked();
        synchronized (overviewLock) {
            overviewCache = computed;
            overviewCacheAt = System.currentTimeMillis();
            return computed;
        }
    }

    private Map<String, Object> computeOverviewLocked() {
            List<WatchStock> stocks = watchlistService.list();
            Map<String, Agg> agg = new LinkedHashMap<>();
            for (String id : StrategyEngine.NAMES.keySet()) {
                agg.put(id, new Agg());
            }
            Map<String, Double> bench = benchmarkService.cachedClosesByDate();
            int stockCount = 0;
            int skipped = 0;
            for (WatchStock stock : stocks) {
                try {
                    List<KlineBar> bars = quoteClient.peekKline(stock.getSecid());
                    if (bars.size() < 35) {
                        skipped++;
                        continue;
                    }
                    StrategyEngine.AnalysisResult result = strategyEngine.analyze(bars, StrategyEngine.NAMES.keySet(), bench);
                    stockCount++;
                    for (StrategyStats s : result.stats()) {
                        Agg a = agg.computeIfAbsent(s.strategy(), k -> new Agg());
                        a.buy += s.buyCount();
                        a.sell += s.sellCount();
                        a.merge(s.d5());
                        // 逐笔交易直接汇总原始记录，期望值/盈亏比由合并后的样本重算（比加权平均正确）
                        a.trades.addAll(result.simTrades().getOrDefault(s.strategy(), List.of()));
                    }
                } catch (Exception ignored) {
                    skipped++;
                }
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<String, Agg> e : agg.entrySet()) {
                Agg a = e.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("strategy", e.getKey());
                row.put("strategyName", StrategyEngine.NAMES.getOrDefault(e.getKey(), e.getKey()));
                row.put("buyCount", a.buy);
                row.put("sellCount", a.sell);
                row.put("samples", a.samples);
                row.put("insufficient", a.samples < StrategyEngine.MIN_SAMPLES);
                row.put("winRate5d", a.winRate());
                row.put("netAvg5d", a.net());
                row.put("grossAvg5d", a.gross());
                row.put("benchAvg5d", a.bench());
                row.put("excessAvg5d", a.excess());
                // 逐笔交易口径：期望值(R) 才是「能不能赚钱」的答案
                TradeStats t = TradeStats.from(a.trades, TradeSimulator.MIN_TRADES);
                row.put("trades", t.trades());
                row.put("tradeInsufficient", t.insufficient());
                row.put("tradeWinRate", t.winRate());
                row.put("expectancyR", t.expectancyR());
                row.put("avgWinR", t.avgWinR());
                row.put("avgLossR", t.avgLossR());
                row.put("profitFactor", t.profitFactor());
                row.put("maxConsecLoss", t.maxConsecLoss());
                row.put("avgHoldDays", t.avgHoldDays());
                row.put("totalR", t.totalR());
                row.put("maxDrawdownR", t.maxDrawdownR());
                row.put("avgNetPct", t.avgNetPct());
                row.put("avgExcessPct", t.avgExcessPct());
                row.put("exitMix", t.exitMix());
                list.add(row);
            }
            Map<String, Object> map = new HashMap<>();
            map.put("stockCount", stockCount);
            map.put("skipped", skipped);
            map.put("cachedOnly", true);
            map.put("benchmark", BenchmarkService.BENCH_NAME);
            map.put("benchmarkAvailable", benchmarkService.cachedAvailable());
            map.put("tradeCostPct", round(TradeCost.roundTripCostRatio() * 100, 2));
            map.put("tradeModel", tradeModel());
            map.put("strategies", list);
            return map;
    }

    private final Object wfLock = new Object();
    private Map<String, Object> wfCache;
    private long wfCacheAt;

    /** 样本外验证最多用多少只股票（算一次要几秒，所以限制规模并缓存） */
    private static final int MAX_WF_STOCKS = 30;
    /** 少于这个股票数就不给结论，免得用 3 只票的样本骗自己 */
    private static final int MIN_WF_STOCKS = 5;
    /** 点「跑一次」时现场补日 K 的上限，避免一次把接口拖死 */
    private static final int MAX_WF_LIVE_FETCH = 12;

    /**
     * 样本外验证 + 参数敏感性。
     *
     * <p>样本就是「自选股」：只用这些票的日 K。{@code fill=true} 时，缓存不够会现场补拉
     * （有次数上限）。结果缓存 1 小时。
     */
    public Map<String, Object> walkForward() {
        return walkForward(false);
    }

    public Map<String, Object> walkForward(boolean fill) {
        if (!fill) {
            synchronized (wfLock) {
                if (wfCache != null && System.currentTimeMillis() - wfCacheAt < 60 * 60_000L) {
                    return wfCache;
                }
            }
        }
        Map<String, Object> map = buildWalkForward(fill);
        if (Boolean.TRUE.equals(map.get("needMore"))) {
            return map;
        }
        synchronized (wfLock) {
            wfCache = map;
            wfCacheAt = System.currentTimeMillis();
            return map;
        }
    }

    private Map<String, Object> buildWalkForward(boolean fill) {
        Map<String, Double> bench = benchmarkService.cachedClosesByDate();
        List<WatchStock> watch = watchlistService.list();
        List<WalkForwardAnalyzer.StockSeries> series = new ArrayList<>();
        int skipped = 0;
        int fetched = 0;
        for (WatchStock stock : watch) {
            if (series.size() >= MAX_WF_STOCKS) {
                break;
            }
            if (stock.getSecid() == null || stock.getSecid().isBlank()) {
                skipped++;
                continue;
            }
            List<KlineBar> bars = quoteClient.peekKline(stock.getSecid());
            if (bars.size() < 120 && fill && fetched < MAX_WF_LIVE_FETCH) {
                try {
                    bars = quoteClient.fetchKline(stock.getSecid(), 250);
                    fetched++;
                } catch (Exception ignored) {
                    skipped++;
                    continue;
                }
            }
            if (bars.size() < 120) {
                skipped++;
                continue;
            }
            StrategyEngine.AnalysisResult res =
                    strategyEngine.analyze(bars, StrategyEngine.NAMES.keySet(), bench);
            series.add(new WalkForwardAnalyzer.StockSeries(stock.getCode(), bars, res.signals()));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("watchlistCount", watch.size());
        map.put("stockCount", series.size());
        map.put("skipped", skipped);
        map.put("fetched", fetched);
        map.put("minStocks", MIN_WF_STOCKS);
        map.put("maxStocks", MAX_WF_STOCKS);
        map.put("benchmark", BenchmarkService.BENCH_NAME);
        map.put("folds", WalkForwardAnalyzer.DEFAULT_FOLDS);
        map.put("minTrainTrades", WalkForwardAnalyzer.MIN_TRAIN_TRADES);
        map.put("gridCombos", TradeParams.grid().size());
        map.put("tradeModel", tradeModel());
        if (series.size() < MIN_WF_STOCKS) {
            map.put("needMore", true);
            map.put("strategies", List.of());
            map.put("message", "样本就是「自选股」，没有单独的样本库。"
                    + "请到「自选股」加入至少 " + MIN_WF_STOCKS + " 只股票，再点「跑一次」（会给自选拉取日 K）。"
                    + "现在自选 " + watch.size() + " 只，其中日 K 够用的 " + series.size() + " 只。");
            return map;
        }
        map.put("needMore", false);
        map.put("strategies", WalkForwardAnalyzer.analyze(series, StrategyEngine.NAMES.keySet(),
                TradeParams.grid(), WalkForwardAnalyzer.DEFAULT_FOLDS,
                WalkForwardAnalyzer.MIN_TRAIN_TRADES, bench));
        return map;
    }

    private List<SignalPoint> latestPerStrategy(List<SignalPoint> signals) {
        Map<String, SignalPoint> last = new LinkedHashMap<>();
        for (SignalPoint s : signals) {
            last.put(s.strategy(), s);
        }
        return new ArrayList<>(last.values());
    }

    private List<SignalPoint> todaySignals(List<SignalPoint> signals, List<KlineBar> bars) {
        if (bars.isEmpty()) {
            return List.of();
        }
        String lastDate = bars.get(bars.size() - 1).date();
        return signals.stream().filter(s -> lastDate.equals(s.date())).toList();
    }

    private String consensus(List<SignalPoint> today) {
        return consensus(today, 7);
    }

    private String consensus(List<SignalPoint> today, int selectedCount) {
        int buy = 0;
        int sell = 0;
        for (SignalPoint s : today) {
            if ("BUY".equals(s.action())) buy++;
            else if ("SELL".equals(s.action())) sell++;
        }
        int need = selectedCount <= 2 ? 1 : 2;
        if (buy >= need && buy > sell) return "BUY";
        if (sell >= need && sell > buy) return "SELL";
        if (buy > 0 && sell == 0) return "BUY_WEAK";
        if (sell > 0 && buy == 0) return "SELL_WEAK";
        return "HOLD";
    }

    private static double round(double v, int scale) {
        double p = Math.pow(10, scale);
        return Math.round(v * p) / p;
    }

    /** 逐笔交易模型的可疑参数都摊开给前端，别藏在代码里 */
    private static Map<String, Object> tradeModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stopAtr", TradeSimulator.STOP_ATR);
        m.put("atrPeriod", TradeSimulator.ATR_PERIOD);
        m.put("breakevenAtR", TradeSimulator.BREAKEVEN_AT_R);
        m.put("trailAtR", TradeSimulator.TRAIL_AT_R);
        m.put("maxHoldDays", TradeSimulator.MAX_HOLD_DAYS);
        m.put("minTrades", TradeSimulator.MIN_TRADES);
        m.put("notional", TradeCost.NOTIONAL);
        return m;
    }

    /** 跨股票按样本数加权汇总一个策略的 5 日口径 */
    private static class Agg {
        int buy;
        int sell;
        int samples;
        double winW;
        double netW;
        double grossW;
        double benchW;
        double excessW;
        int grossN;
        int benchN;
        int excessN;
        /** 逐笔成交原始记录（汇总后再算期望值） */
        final List<SimTrade> trades = new ArrayList<>();

        void merge(HorizonStats d5) {
            if (d5 == null || d5.samples() == 0) {
                return;
            }
            samples += d5.samples();
            if (d5.winRate() != null) {
                winW += d5.winRate() * d5.samples();
            }
            if (d5.netAvg() != null) {
                netW += d5.netAvg() * d5.samples();
            }
            if (d5.grossAvg() != null) {
                grossW += d5.grossAvg() * d5.samples();
                grossN += d5.samples();
            }
            if (d5.benchAvg() != null) {
                benchW += d5.benchAvg() * d5.samples();
                benchN += d5.samples();
            }
            if (d5.excessAvg() != null) {
                excessW += d5.excessAvg() * d5.samples();
                excessN += d5.samples();
            }
        }

        Double winRate() {
            return samples == 0 ? null : round(winW / samples, 1);
        }

        Double net() {
            return samples == 0 ? null : round(netW / samples, 2);
        }

        Double gross() {
            return grossN == 0 ? null : round(grossW / grossN, 2);
        }

        Double bench() {
            return benchN == 0 ? null : round(benchW / benchN, 2);
        }

        Double excess() {
            return excessN == 0 ? null : round(excessW / excessN, 2);
        }
    }
}
