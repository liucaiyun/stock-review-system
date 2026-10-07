import com.yan.stockreview.dto.HorizonStats;
import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.ParamSensitivity;
import com.yan.stockreview.dto.SignalPoint;
import com.yan.stockreview.dto.SimTrade;
import com.yan.stockreview.dto.StrategyStats;
import com.yan.stockreview.dto.TradeStats;
import com.yan.stockreview.dto.WalkForwardFold;
import com.yan.stockreview.dto.WalkForwardStats;
import com.yan.stockreview.strategy.IndicatorEngine;
import com.yan.stockreview.strategy.StrategyEngine;
import com.yan.stockreview.strategy.TradeParams;
import com.yan.stockreview.strategy.TradeSimulator;
import com.yan.stockreview.strategy.WalkForwardAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 回测体检工具（只读）：调用项目自身的 StrategyEngine 生成信号，再用「真实成本 + 基准」复算统计。
 * 不改动任何项目源码，也不写数据库。
 *
 * 用法：
 *   javac -encoding UTF-8 -cp target\classes -d tools\out tools\BacktestAudit.java
 *   java -cp "target\classes;tools\out" BacktestAudit [数据目录] [报告路径]
 * 数据目录里每个股票一个 <tencentSymbol>.json（如 sh600519.json），
 * 由 tools\backtest-audit.ps1 预先下载（腾讯前复权日线）。
 */
public class BacktestAudit {

    /** 日线 JSON 缓存目录，可由命令参数覆盖 */
    static String DATA_DIR = "tools/backtest-audit-data";

    // A 股真实成本：佣金万2.5/最低5元、过户费0.001%、印花税0.05%(卖出)、单边滑点0.05%
    static final double COMM_RATE = 0.00025;
    static final double COMM_MIN = 5.0;
    static final double TRANSFER = 0.00001;
    static final double STAMP = 0.0005;
    static final double SLIP = 0.0005;
    static final double NOTIONAL = 100_000;

    static final int[] HORIZONS = {5, 10, 20};
    static final String[][] UNIVERSE = {
            {"sh600519", "贵州茅台"}, {"sz000858", "五粮液"}, {"sh601318", "中国平安"},
            {"sz000001", "平安银行"}, {"sh600036", "招商银行"}, {"sz300750", "宁德时代"},
            {"sz002594", "比亚迪"}, {"sh600276", "恒瑞医药"}, {"sz000651", "格力电器"},
            {"sh601899", "紫金矿业"}, {"sz002415", "海康威视"}, {"sh600030", "中信证券"},
            {"sh601012", "隆基绿能"}, {"sz300059", "东方财富"}, {"sh600887", "伊利股份"},
            {"sh601668", "中国建筑"}, {"sz000333", "美的集团"}, {"sh688111", "金山办公"},
            {"sh603259", "药明康德"}, {"sz002714", "牧原股份"}, {"sh600309", "万华化学"},
            {"sz000725", "京东方A"}, {"sh601088", "中国神华"}, {"sz002304", "洋河股份"},
    };
    static final String INDEX = "sh000300";

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            DATA_DIR = args[0];
        }
        String report = args.length > 1 ? args[1] : DATA_DIR + "/report.txt";
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(report), true, "UTF-8"));
        runTradeModelSelfCheck();
        List<KlineBar> index = fetch(INDEX, 600);
        Map<String, Integer> indexAt = new LinkedHashMap<>();
        for (int i = 0; i < index.size(); i++) {
            indexAt.put(index.get(i).date(), i);
        }
        double[] indexClose = index.stream().mapToDouble(KlineBar::close).toArray();
        Map<String, Double> benchCloses = new LinkedHashMap<>();
        for (KlineBar b : index) {
            benchCloses.put(b.date(), b.close());
        }
        System.out.println("基准 沪深300: " + index.size() + " 根 "
                + index.get(0).date() + " ~ " + index.get(index.size() - 1).date());
        System.out.println("样本股数: " + UNIVERSE.length + "，每只取 500 根日线（前复权）");
        System.out.println();

        StrategyEngine engine = new StrategyEngine();

        // 汇总容器
        Map<String, Agg> perStrategy = new LinkedHashMap<>();
        Agg baseline = new Agg();
        // 项目自身新口径（扣费 + 次日开盘 + 基准）的汇总，用来跟本工具的独立复算互相校验
        Map<String, EngineAgg> engineAgg = new LinkedHashMap<>();
        // 逐笔交易（跨股票汇总后重算期望值）
        Map<String, List<SimTrade>> allTrades = new LinkedHashMap<>();
        // 对照：不许卖点出场（只靠 ATR 止损/跟踪），看策略自己的卖点到底帮忙还是帮倒忙
        Map<String, List<SimTrade>> allTradesNoSell = new LinkedHashMap<>();
        // 样本外验证需要的原始材料（K 线 + 信号）
        List<WalkForwardAnalyzer.StockSeries> seriesAll = new ArrayList<>();
        Map<String, double[]> voteBull = new LinkedHashMap<>();   // strategyName -> sums
        // 6 零件票：pairwise 共现
        String[] parts = {"双均线", "MACD", "RSI", "布林", "突破", "量价"};
        long[][] both = new long[parts.length][parts.length];
        long[] singles = new long[parts.length];
        long voteBars = 0;
        // 按“多头零件个数”分组的前向收益
        Map<Integer, Agg> byVote = new TreeMap<>();
        // 次日开盘缺口（衡量 “按信号当日收盘价成交” 的乐观程度）
        double gapSum = 0;
        int gapN = 0;
        int barTotal = 0;

        for (String[] s : UNIVERSE) {
            String secid = s[0];
            String name = s[1];
            List<KlineBar> bars;
            try {
                bars = fetch(secid, 500);
            } catch (Exception ex) {
                System.out.println("!! " + name + " 抓取失败: " + ex.getMessage());
                continue;
            }
            if (bars.size() < 120) {
                System.out.println("!! " + name + " 数据不足 " + bars.size());
                continue;
            }
            barTotal += bars.size();
            double[] close = IndicatorEngine.closes(bars);
            double[] vol = IndicatorEngine.volumes(bars);
            int n = bars.size();

            // ---------- 1) 基线：无条件同期限收益（同样跳过前 30 根、同样丢弃末尾不足 h 的样本）----------
            for (int h : HORIZONS) {
                for (int i = 30; i + h < n; i++) {
                    baseline.add(h, (close[i + h] - close[i]) / close[i], bench(indexAt, indexClose, bars, i, h));
                }
            }

            // ---------- 2) 用项目自身代码产生信号（并把基准传进去，走新口径）----------
            StrategyEngine.AnalysisResult res = engine.analyze(bars, StrategyEngine.NAMES.keySet(), benchCloses);
            seriesAll.add(new WalkForwardAnalyzer.StockSeries(name, bars, res.signals()));
            for (StrategyStats st : res.stats()) {
                engineAgg.computeIfAbsent(st.strategy(), k -> new EngineAgg()).merge(st.d5());
            }
            // 逐笔交易跨股票汇总：期望值/盈亏比由合并后的样本重算
            for (Map.Entry<String, List<SimTrade>> te : res.simTrades().entrySet()) {
                allTrades.computeIfAbsent(te.getKey(), k -> new ArrayList<>()).addAll(te.getValue());
            }
            // 对照实验：只用 BUY 信号（不许策略自己的卖点出场，纯靠 ATR 止损/跟踪）
            List<SignalPoint> buysOnly = res.signals().stream()
                    .filter(p -> "BUY".equals(p.action())).toList();
            for (String id : StrategyEngine.NAMES.keySet()) {
                List<SimTrade> t = TradeSimulator.simulate(bars, buysOnly, id, benchCloses);
                if (!t.isEmpty()) {
                    allTradesNoSell.computeIfAbsent(id, k -> new ArrayList<>()).addAll(t);
                }
            }
            Map<String, Integer> at = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                at.put(bars.get(i).date(), i);
            }
            for (SignalPoint p : res.signals()) {
                Integer i = at.get(p.date());
                if (i == null) {
                    continue;
                }
                if ("BUY".equals(p.action())) {
                    Agg a = perStrategy.computeIfAbsent(p.strategy(), k -> new Agg());
                    for (int h : HORIZONS) {
                        if (i + h < n) {
                            a.add(h, (close[i + h] - p.price()) / p.price(),
                                    bench(indexAt, indexClose, bars, i, h));
                        }
                    }
                    // 次日开盘 vs 信号日收盘
                    if (i + 1 < n) {
                        KlineBar nx = bars.get(i + 1);
                        if (nx.open() > 0) {
                            gapSum += (nx.open() - p.price()) / p.price();
                            gapN++;
                        }
                    }
                } else if ("SELL".equals(p.action())) {
                    Agg a = perStrategy.computeIfAbsent(p.strategy() + "(SELL记为空头)", k -> new Agg());
                    for (int h : HORIZONS) {
                        if (i + h < n) {
                            a.add(h, -(close[i + h] - p.price()) / p.price(),
                                    -bench(indexAt, indexClose, bars, i, h));
                        }
                    }
                }
            }

            // ---------- 3) 6 零件投票（复刻 StrategyEngine.resonanceAt）----------
            double[] ma5 = IndicatorEngine.sma(close, 5);
            double[] ma20 = IndicatorEngine.sma(close, 20);
            double[] volMa20 = IndicatorEngine.sma(vol, 20);
            double[] rsi = IndicatorEngine.rsi(close, 14);
            IndicatorEngine.MacdSeries macd = IndicatorEngine.macd(close);
            IndicatorEngine.BollSeries boll = IndicatorEngine.boll(close, 20, 2);
            for (int i = 30; i + 5 < n; i++) {
                boolean[] v = votes(bars, i, close, vol, ma5, ma20, rsi, macd, boll, volMa20);
                int bullCount = 0;
                for (boolean b : v) {
                    if (b) {
                        bullCount++;
                    }
                }
                byVote.computeIfAbsent(bullCount, k -> new Agg())
                        .add(5, (close[i + 5] - close[i]) / close[i], bench(indexAt, indexClose, bars, i, 5));
                if (bullCount >= 1) {
                    voteBars++;
                }
                for (int x = 0; x < parts.length; x++) {
                    if (v[x]) {
                        singles[x]++;
                    }
                    for (int y = 0; y < parts.length; y++) {
                        if (v[x] && v[y]) {
                            both[x][y]++;
                        }
                    }
                }
            }
            System.out.println("  ok " + name + " (" + n + " 根, 信号 " + res.signals().size() + " 条)");
        }

        // ================= 输出 =================
        System.out.println();
        System.out.println("############ 1. 各策略 BUY 信号：毛收益 vs 扣费后 vs 基准 ############");
        System.out.printf("%-30s %5s | %-28s | %-28s%n", "策略", "样本", "毛收益(胜率/均值)", "扣费+滑点(胜率/均值)");
        for (Map.Entry<String, Agg> e : perStrategy.entrySet()) {
            Agg a = e.getValue();
            System.out.printf("%-30s %5d | %s | %s%n", e.getKey(), a.n5,
                    fmt(a.pct(a.win5, a.n5), a.net(a.avg(a.sum5, a.n5))),
                    fmt(a.pct(a.netWin5, a.n5), a.net(netOf(a.avg(a.sum5, a.n5)))));
        }
        System.out.println();
        System.out.printf("%-30s %5d | %s | %s%n", "★ 基线(不挑信号,全样本)", baseline.n5,
                fmt(baseline.pct(baseline.win5, baseline.n5), baseline.net(baseline.avg(baseline.sum5, baseline.n5))),
                fmt(baseline.pct(baseline.netWin5, baseline.n5), baseline.net(netOf(baseline.avg(baseline.sum5, baseline.n5)))));

        System.out.println();
        System.out.println("############ 2. 各期限：扣费后是否还赢基准 ############");
        for (Map.Entry<String, Agg> e : perStrategy.entrySet()) {
            if (e.getKey().contains("SELL")) {
                continue;
            }
            Agg a = e.getValue();
            System.out.printf("%-14s", e.getKey());
            for (int h : HORIZONS) {
                double gross = a.avg(a.sum(h), a.n(h));
                double net = netOf(gross);
                double bench = a.avg(a.bench(h), a.n(h));
                System.out.printf(" | %2dd 毛%+.2f%% 净%+.2f%% 基准%+.2f%% 超额(净-基准)%+.2f%%",
                        h, gross * 100, net * 100, bench * 100, (net - bench) * 100);
            }
            System.out.println();
        }
        System.out.printf("%-14s", "★基线");
        for (int h : HORIZONS) {
            double gross = baseline.avg(baseline.sum(h), baseline.n(h));
            double bench = baseline.avg(baseline.bench(h), baseline.n(h));
            System.out.printf(" | %2dd 毛%+.2f%% 净%+.2f%% 基准%+.2f%% 超额(净-基准)%+.2f%%",
                    h, gross * 100, netOf(gross) * 100, bench * 100, (netOf(gross) - bench) * 100);
        }
        System.out.println();

        System.out.println();
        System.out.println("############ 3. “6 零件共振”到底有几个独立零件？（皮尔逊 φ，全样本共现率）############");
        System.out.print("            ");
        for (String p : parts) {
            System.out.printf("%8s", p);
        }
        System.out.println();
        for (int x = 0; x < parts.length; x++) {
            System.out.printf("%-12s", parts[x]);
            for (int y = 0; y < parts.length; y++) {
                double px = (double) singles[x] / voteBars;
                double py = (double) singles[y] / voteBars;
                double pxy = (double) both[x][y] / voteBars;
                double phi = (pxy - px * py) / Math.sqrt(px * (1 - px) * py * (1 - py));
                System.out.printf("%8.2f", phi);
            }
            System.out.println();
        }
        System.out.println("(1.00=完全重复的同一个零件，0=互不相关)");
        System.out.print("各零件出现频率: ");
        for (int x = 0; x < parts.length; x++) {
            System.out.printf("%s=%.0f%% ", parts[x], 100.0 * singles[x] / voteBars);
        }
        System.out.println();

        System.out.println();
        System.out.println("############ 4. 多头零件个数 -> 未来 5 日收益（共振门槛有没有信息量）############");
        System.out.printf("%-18s %8s %10s %10s %10s%n", "多头零件数", "样本", "胜率", "毛均值", "扣费后");
        for (Map.Entry<Integer, Agg> e : byVote.entrySet()) {
            Agg a = e.getValue();
            System.out.printf("%-18s %8d %9.1f%% %9.2f%% %9.2f%%%n",
                    e.getKey() + " 个", a.n5, 100.0 * a.win5 / a.n5, 100 * a.avg(a.sum5, a.n5),
                    100 * netOf(a.avg(a.sum5, a.n5)));
        }

        System.out.println();
        System.out.println("############ 5. 可成交性：BUY 信号次日开盘相对信号日收盘的缺口 ############");
        System.out.printf("平均缺口 %+.3f%%（样本 %d）。%s%n",
                100 * gapSum / gapN, gapN,
                gapSum / gapN > 0 ? "次日开盘更高，所以「按信号当日收盘价成交」是乐观偏差（新口径已改为次日开盘）" : "缺口为负");
        System.out.println("总K线数 " + barTotal);

        System.out.println();
        System.out.println("############ 6. 校验：项目自身新口径（扣费+次日开盘+基准）汇总 ############");
        System.out.println("与上面第 1/2 节本工具的独立复算应当接近，用来确认代码里的成本/入场口径真的生效了。");
        System.out.printf("%-16s %8s %10s %11s %11s %11s %11s%n",
                "策略", "样本", "净胜率", "净均值", "毛均值(旧)", "同期基准", "净超额");
        for (Map.Entry<String, EngineAgg> e : engineAgg.entrySet()) {
            EngineAgg a = e.getValue();
            if (a.n == 0) {
                continue;
            }
            System.out.printf("%-16s %8d %9.1f%% %10.2f%% %10.2f%% %10.2f%% %10.2f%%%n",
                    e.getKey(), a.n, a.winRate(), a.net(), a.gross(), a.bench(), a.excess());
        }

        System.out.println();
        System.out.println("############ 7. 逐笔交易模拟：R 倍数与期望值（这才是能不能赚钱的答案）############");
        System.out.println("规则：次日开盘入场；一次只持一笔（持仓期间新买点忽略）；初始止损 = 入场 − 2×ATR(14)；");
        System.out.println("      赚到 1R 抬止损到成本；赚到 2R 改 ATR 跟踪；最长 20 个交易日；卖点次日开盘出；含费与滑点。");
        System.out.println();
        System.out.printf("%-14s %6s %8s %10s %9s %9s %8s %8s %8s %8s %9s%n",
                "策略", "交易数", "胜率", "期望值R", "平均盈利R", "平均亏损R", "盈亏比", "最大连亏", "持仓天数", "累计R", "R回撤");
        for (Map.Entry<String, List<SimTrade>> e : allTrades.entrySet()) {
            TradeStats t = TradeStats.from(e.getValue(), TradeSimulator.MIN_TRADES);
            if (t.trades() == 0) {
                continue;
            }
            System.out.printf("%-14s %6d %7.1f%% %+9.3f %9s %9s %8s %8d %8s %+8.1f %8s%n",
                    e.getKey(), t.trades(), t.winRate(), t.expectancyR(),
                    t.avgWinR() == null ? "-" : String.format("%.2f", t.avgWinR()),
                    t.avgLossR() == null ? "-" : String.format("%.2f", t.avgLossR()),
                    t.profitFactor() == null ? "-" : String.format("%.2f", t.profitFactor()),
                    t.maxConsecLoss() == null ? 0 : t.maxConsecLoss(),
                    t.avgHoldDays() == null ? "-" : String.format("%.1f", t.avgHoldDays()),
                    t.totalR() == null ? 0 : t.totalR(),
                    t.maxDrawdownR() == null ? "-" : String.format("%.1f", t.maxDrawdownR()));
        }
        System.out.println();
        System.out.println("注：期望值R = 每笔平均赚多少个 R。单笔风险设成账户 1% 时，期望值 0.20R ≈ 每次下注平均赚账户 0.2%。");
        System.out.println("    交易数 < " + TradeSimulator.MIN_TRADES + " 时这些数字只能当参考。");
        System.out.println();
        System.out.println("出场原因分布与「赚的到底是自己的还是大盘的」：");
        for (Map.Entry<String, List<SimTrade>> e : allTrades.entrySet()) {
            TradeStats t = TradeStats.from(e.getValue(), TradeSimulator.MIN_TRADES);
            if (t.trades() == 0) {
                continue;
            }
            System.out.printf("  %-14s %s　| 平均每笔净收益 %s%% vs 同期沪深300 %s%%（超额 %s%%）%n",
                    e.getKey(), t.exitMix(),
                    t.avgNetPct(), t.avgBenchPct(), t.avgExcessPct());
        }

        System.out.println();
        System.out.println("############ 8. 策略自己的卖点到底帮忙还是帮倒忙？############");
        System.out.println("同一批买点、同一套 ATR 止损，只改「要不要用策略的卖点出场」：");
        System.out.println();
        System.out.printf("%-14s %10s %10s %10s %10s %10s%n",
                "策略", "用卖点R", "不用卖点R", "用卖点胜率", "不用卖点胜率", "结论");
        for (Map.Entry<String, List<SimTrade>> e : allTrades.entrySet()) {
            TradeStats withSell = TradeStats.from(e.getValue(), TradeSimulator.MIN_TRADES);
            TradeStats noSell = TradeStats.from(allTradesNoSell.getOrDefault(e.getKey(), List.of()),
                    TradeSimulator.MIN_TRADES);
            if (withSell.trades() == 0 || noSell.trades() == 0) {
                continue;
            }
            double d = noSell.expectancyR() - withSell.expectancyR();
            String verdict = Math.abs(d) < 0.05 ? "差不多"
                    : (d > 0 ? "卖点帮倒忙（去掉更好 " + String.format("%+.3f", d) + "R）"
                    : "卖点有用（留着更好 " + String.format("%+.3f", d) + "R）");
            System.out.printf("%-14s %+10.3f %+10.3f %9.1f%% %11.1f%% %s%n",
                    e.getKey(), withSell.expectancyR(), noSell.expectancyR(),
                    withSell.winRate(), noSell.winRate(), verdict);
        }
        System.out.println();
        System.out.println("提示：如果「不用卖点」明显更好，说明该策略的卖点信号在过早砍掉盈利单；");
        System.out.println("      反过来则说明卖点确实在躲避下跌。这一步只是把差异量化，不代表换参数就能赚钱。");

        runWalkForwardAnalysis(seriesAll, benchCloses);
    }

    // ================= 样本外验证（walk-forward）+ 参数敏感性 =================

    static void runWalkForwardAnalysis(List<WalkForwardAnalyzer.StockSeries> series,
                                       Map<String, Double> benchCloses) {
        System.out.println();
        System.out.println("############ 9. 样本外验证（walk-forward）：那些正期望是真的吗？############");
        System.out.println("做法：时间轴按日历切成 4 段；每一折只用「过去」挑参数（止损倍数 × 最长持有），");
        System.out.println("      挑完原封不动拿去跑「未来」那一段；最后把各折的测试段合并，就是诚实的样本外结果。");
        System.out.println("      对照组是「在全部历史上挑最好的参数」——那是会骗人的乐观数字。");
        System.out.println();
        long t0 = System.currentTimeMillis();
        List<WalkForwardStats> wf = WalkForwardAnalyzer.analyze(series, StrategyEngine.NAMES.keySet(),
                TradeParams.grid(), WalkForwardAnalyzer.DEFAULT_FOLDS,
                WalkForwardAnalyzer.MIN_TRAIN_TRADES, benchCloses);
        long ms = System.currentTimeMillis() - t0;
        System.out.printf("（%d 只股票 × %d 个参数组合 × %d 套策略，用时 %.1f 秒）%n",
                series.size(), TradeParams.grid().size(), StrategyEngine.NAMES.size(), ms / 1000.0);
        System.out.println();
        System.out.printf("%-14s %8s %10s %9s %10s %9s %10s %10s %10s%n",
                "策略", "样本外笔数", "样本外期望", "标准误", "t值", "样本外胜率", "全样本最优", "网格中位", "网格最差");
        for (WalkForwardStats s : wf) {
            System.out.printf("%-14s %8d %+10.3f %9s %10s %8s%% %+10.3f %+10.3f %+10.3f%n",
                    s.strategyName(), s.oosTrades(), nz(s.oosExpectancyR()),
                    s.oosStdErr() == null ? "-" : String.format("%.3f", s.oosStdErr()),
                    s.oosTStat() == null ? "-" : String.format("%.2f", s.oosTStat()),
                    s.oosWinRate() == null ? "-" : String.format("%.1f", s.oosWinRate()),
                    nz(s.isBestExpectancyR()), nz(s.gridMedian()), nz(s.gridMin()));
        }
        System.out.println();
        System.out.println("怎么读这三列：");
        System.out.println("  「样本外期望」是唯一诚实的数字；「全样本最优」是同一批数据里挑出来的最好看的值，");
        System.out.println("  两者的差距就是过拟合的代价。|t| < 2 表示这个期望值和 0 分不出来。");
        System.out.println("  「网格最差」如果是负的、中位只勉强为正，说明 edge 只在某个特定参数上成立。");
        System.out.println();

        for (WalkForwardStats s : wf) {
            if (s.oosTrades() == 0) {
                continue;
            }
            System.out.printf("— %s（%s ~ %s，%d 只票，%d 笔交易，用了 %d/%d 折，%d 折为正）%n",
                    s.strategyName(), s.from(), s.to(), s.seriesCount(), s.totalTrades(),
                    s.usedFolds(), WalkForwardAnalyzer.DEFAULT_FOLDS - 1, s.positiveFolds());
            System.out.printf("  %-4s %-12s %-12s %-8s %8s %10s %8s %10s%n",
                    "折", "训练段", "测试段", "选用参数", "训练笔数", "训练期望", "测试笔数", "测试期望");
            for (WalkForwardFold f : s.foldList()) {
                System.out.printf("  %-4d %-12s %-12s %-8s %8d %+10.3f %8d %+10.3f%n",
                        f.index(), f.trainStart(), f.testStart() + "~" + f.testEnd(), f.params(),
                        f.trainTrades(), nz(f.trainExpectancyR()), f.testTrades(), nz(f.testExpectancyR()));
            }
            System.out.println("  参数敏感性（一次只动一个，其余保持默认）：");
            StringBuilder sb = new StringBuilder();
            String lastParam = null;
            for (ParamSensitivity row : s.sensitivity()) {
                if (lastParam != null && !lastParam.equals(row.param())) {
                    System.out.println("    " + sb);
                    sb.setLength(0);
                }
                lastParam = row.param();
                sb.append(row.param()).append('=').append(trim(row.value()))
                        .append(" → ").append(row.expectancyR() == null ? "n/a"
                                : String.format("%+.3fR", row.expectancyR())).append("   ");
            }
            if (sb.length() > 0) {
                System.out.println("    " + sb);
            }
            System.out.println();
        }
        System.out.println("结论怎么看写在 docs/代码评审与盈利路线.md 里；这里只给数字。");
    }

    static double nz(Double v) {
        return v == null ? 0 : v;
    }

    static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    /** 项目自身 StrategyStats 的 5 日汇总（按样本数加权） */
    static class EngineAgg {
        int n;
        int gn;
        int bn;
        double winW;
        double netW;
        double grossW;
        double benchW;

        void merge(HorizonStats d5) {
            if (d5 == null || d5.samples() == 0) {
                return;
            }
            n += d5.samples();
            if (d5.winRate() != null) {
                winW += d5.winRate() * d5.samples();
            }
            if (d5.netAvg() != null) {
                netW += d5.netAvg() * d5.samples();
            }
            if (d5.grossAvg() != null) {
                grossW += d5.grossAvg() * d5.samples();
                gn += d5.samples();
            }
            if (d5.benchAvg() != null) {
                benchW += d5.benchAvg() * d5.samples();
                bn += d5.samples();
            }
        }

        double winRate() {
            return winW / n;
        }

        double net() {
            return netW / n;
        }

        double gross() {
            return gn == 0 ? 0 : grossW / gn;
        }

        double bench() {
            return bn == 0 ? 0 : benchW / bn;
        }

        double excess() {
            return net() - bench();
        }
    }

    // ================= 合成 K 线自检：逐笔交易模型 =================
    //
    // 用人工构造的 K 线把交易模型的每条出口规则单独逼出来，断言已知答案。
    // 构造方式：前 22 根做成「平盘 ±range」→ TR 恒等于 2×range → ATR(14) 恒等于 2×range，
    // 于是 1R = 2×ATR 是已知数，就能手算出每一笔的期望 R 来核对。

    static int passCount = 0;
    static int failCount = 0;

    static void runTradeModelSelfCheck() {
        String id = "T";
        System.out.println("############ 0. 交易模型自检（合成 K 线，不依赖行情）############");
        System.out.println("构造：22 根平盘 100（振幅 ±1）→ ATR14=2.0 → 1R = 2×ATR = 4.00 元，入场价含滑点=100.05，初始止损=96.05");

        // 1) 时间止损：一直平盘，20 个交易日后按收盘出场，只亏手续费与滑点
        List<KlineBar> b1 = flat(60, 100, 1.0);
        List<SimTrade> t1 = TradeSimulator.simulate(b1, List.of(sig(20, id, "BUY")), id, null);
        check("1 时间止损", t1.size() == 1 && "时间".equals(t1.get(0).exitReason()) && t1.get(0).holdDays() == 20,
                "trades=" + t1.size() + " reason=" + reason(t1) + " hold=" + hold(t1));
        check("1 时间止损 R≈0（只有成本）", !t1.isEmpty() && Math.abs(t1.get(0).r()) < 0.12,
                "r=" + r(t1) + "（期望约 -0.05）");

        // 2) 初始止损：跌破 96.05，按止损价成交（当日开盘在止损之上）
        List<KlineBar> b2 = with(flat(60, 100, 1.0), 22, bar(22, 99, 99, 95, 96));
        List<SimTrade> t2 = TradeSimulator.simulate(b2, List.of(sig(20, id, "BUY")), id, null);
        check("2 初始止损", t2.size() == 1 && "止损".equals(t2.get(0).exitReason()) && t2.get(0).holdDays() == 2,
                "reason=" + reason(t2) + " hold=" + hold(t2));
        check("2 止损 R≈-1.04（1R 风险+成本）", !t2.isEmpty() && Math.abs(t2.get(0).r() + 1.04) < 0.08,
                "r=" + r(t2));

        // 2b) 跳空低开：开盘 95 已在止损之下 → 按开盘价成交，比止损价更亏（保守处理）
        List<KlineBar> b2b = with(flat(60, 100, 1.0), 22, bar(22, 95, 95, 94, 94.5));
        List<SimTrade> t2b = TradeSimulator.simulate(b2b, List.of(sig(20, id, "BUY")), id, null);
        check("2b 跳空低开按开盘价成交（比止损价更亏）", !t2b.isEmpty() && t2b.get(0).r() < -1.15,
                "r=" + r(t2b) + "（期望约 -1.29，明显差于 -1.04）");

        // 3) 保本：收盘涨到 1R 以上把止损抬到成本，随后回落 → 打掉在成本价附近
        List<KlineBar> b3 = with(flat(40, 100, 1.0), 22, bar(22, 100, 105.5, 100.5, 105));
        b3 = with(b3, 23, bar(23, 101, 101, 99.5, 100));
        List<SimTrade> t3 = TradeSimulator.simulate(b3, List.of(sig(20, id, "BUY")), id, null);
        check("3 保本止损（1R 后抬到成本）", t3.size() == 1 && "移动止损".equals(t3.get(0).exitReason())
                        && t3.get(0).r() > -0.15 && t3.get(0).r() < 0.05,
                "reason=" + reason(t3) + " r=" + r(t3) + "（期望约 -0.06，说明没让它变成 -1R）");

        // 4) 跟踪止损：涨到 2R 以上改用「收盘 − 2ATR」跟踪，随后回落打掉
        List<KlineBar> b4 = with(flat(40, 100, 1.0), 22, bar(22, 100, 110.5, 100, 110));
        b4 = with(b4, 23, bar(23, 104, 104.5, 103, 103.5));
        List<SimTrade> t4 = TradeSimulator.simulate(b4, List.of(sig(20, id, "BUY")), id, null);
        check("4 ATR 跟踪止损（2R 后启动）", t4.size() == 1 && "移动止损".equals(t4.get(0).exitReason())
                        && t4.get(0).r() > 0.8 && t4.get(0).r() < 1.1,
                "reason=" + reason(t4) + " r=" + r(t4) + "（期望约 +0.95，锁住了大部分利润）");

        // 5) 卖点出场：卖点次日开盘走
        List<KlineBar> b5 = with(flat(40, 100, 1.0), 24, bar(24, 103, 103.5, 102, 103));
        List<SimTrade> t5 = TradeSimulator.simulate(b5,
                List.of(sig(20, id, "BUY"), sig(23, id, "SELL")), id, null);
        check("5 卖点次日开盘出场", t5.size() == 1 && "卖点".equals(t5.get(0).exitReason()) && t5.get(0).holdDays() == 4,
                "reason=" + reason(t5) + " hold=" + hold(t5) + " r=" + r(t5));

        // 6) 一次只持一笔：持仓期间的新买点被忽略
        List<SimTrade> t6 = TradeSimulator.simulate(flat(60, 100, 1.0),
                List.of(sig(20, id, "BUY"), sig(22, id, "BUY")), id, null);
        check("6 一次只持一笔（重叠买点被忽略）", t6.size() == 1, "trades=" + t6.size() + "（旧的固定持有期口径会算成 2 个样本）");

        // 7) 开盘涨停买不到
        List<KlineBar> b7 = with(flat(40, 100, 1.0), 21, bar(21, 110, 110.5, 109, 110));
        List<SimTrade> t7 = TradeSimulator.simulate(b7, List.of(sig(20, id, "BUY")), id, null);
        check("7 开盘涨停（+10%）买不到", t7.isEmpty(), "trades=" + t7.size());

        // 8) 指标口径自检：期望值/盈亏比/最大连亏/R 曲线回撤
        List<SimTrade> fake = List.of(
                fakeTrade("2025-01-01", 2.0), fakeTrade("2025-01-02", -1.0),
                fakeTrade("2025-01-03", -1.0), fakeTrade("2025-01-04", 3.0));
        TradeStats ts = TradeStats.from(fake, 30);
        check("8 期望值 = 平均 R", eq(ts.expectancyR(), 0.75), "expectancyR=" + ts.expectancyR() + "（期望 0.75）");
        check("8 胜率 50%", eq(ts.winRate(), 50.0), "winRate=" + ts.winRate());
        check("8 平均盈利 R=2.5 / 平均亏损 R=1.0", eq(ts.avgWinR(), 2.5) && eq(ts.avgLossR(), 1.0),
                "avgWin=" + ts.avgWinR() + " avgLoss=" + ts.avgLossR());
        check("8 盈亏比 = 5/2 = 2.5", eq(ts.profitFactor(), 2.5), "profitFactor=" + ts.profitFactor());
        check("8 最大连亏 = 2", ts.maxConsecLoss() != null && ts.maxConsecLoss() == 2, "maxConsecLoss=" + ts.maxConsecLoss());
        check("8 R 曲线最大回撤 = -2R", eq(ts.maxDrawdownR(), -2.0), "maxDrawdownR=" + ts.maxDrawdownR());
        check("8 样本不足会标记", ts.insufficient(), "trades=" + ts.trades() + " insufficient=" + ts.insufficient());

        // 9) walk-forward 的时间轴等分
        List<String> bounds = WalkForwardAnalyzer.dateBounds("2024-01-01", "2024-01-31", 4);
        check("9 时间轴等分（4 段 5 个边界）",
                bounds.size() == 5 && "2024-01-01".equals(bounds.get(0)) && "2024-01-31".equals(bounds.get(4)),
                bounds.toString());

        // 10) 样本外验证的结构 + 「平盘行情任何参数都不该赚钱」这条铁律
        List<WalkForwardAnalyzer.StockSeries> flatSeries = new ArrayList<>();
        for (int s = 0; s < 2; s++) {
            List<KlineBar> fb = isoFlat(240, 50, 1.0);
            List<SignalPoint> fs = new ArrayList<>();
            for (int d = 20; d < 230; d += 8) {
                fs.add(new SignalPoint(fb.get(d).date(), "T", "test", "BUY", "", 0));
            }
            flatSeries.add(new WalkForwardAnalyzer.StockSeries("S" + s, fb, fs));
        }
        List<WalkForwardStats> wfs = WalkForwardAnalyzer.analyze(flatSeries, List.of("T"),
                List.of(TradeParams.DEFAULT), 4, 1, null);
        boolean flatOk = wfs.size() == 1 && wfs.get(0).isBestExpectancyR() != null && wfs.get(0).isBestExpectancyR() < 0;
        check("10 平盘行情下任何参数都赚不到钱（只剩成本）", flatOk,
                wfs.isEmpty() ? "无结果" : "全样本最优期望=" + wfs.get(0).isBestExpectancyR() + "R（必须为负）");
        boolean foldOk = !wfs.isEmpty() && wfs.get(0).foldList().size() >= 2;
        int foldSum = wfs.isEmpty() ? -1
                : wfs.get(0).foldList().stream().mapToInt(WalkForwardFold::testTrades).sum();
        check("10 折数与「样本外笔数=各折测试笔数之和」",
                foldOk && wfs.get(0).oosTrades() == foldSum,
                wfs.isEmpty() ? "无结果" : "折数=" + wfs.get(0).foldList().size()
                        + " oos笔数=" + wfs.get(0).oosTrades() + " 各折合计=" + foldSum);
        boolean sensOk = !wfs.isEmpty() && !wfs.get(0).sensitivity().isEmpty();
        check("10 参数敏感性有输出", sensOk,
                wfs.isEmpty() ? "无结果" : "敏感性行数=" + wfs.get(0).sensitivity().size());

        // 11) ATR 必须算对：平盘 ±1 的 TR 恒为 2.0，所以 ATR(14) 必须正好是 2.0
        double[] atrFlat = IndicatorEngine.atr(flat(40, 100, 1.0), 14);
        check("11 ATR(14) 平盘 ±1 → 正好 2.0", Math.abs(atrFlat[39] - 2.0) < 1e-9,
                "atr=" + atrFlat[39] + "（期望 2.0）");
        check("11 ATR 预热期应为 NaN", Double.isNaN(atrFlat[12]) && !Double.isNaN(atrFlat[13]),
                "atr[12]=" + atrFlat[12] + " atr[13]=" + atrFlat[13]);
        // 非平盘：TR 由 |高−昨收| 决定时也要对
        List<KlineBar> gapBars = flat(20, 100, 1.0);
        gapBars = with(gapBars, 19, bar(19, 100, 100, 100, 100)); // 当天 close=100，high−low=0
        double[] atrGap = IndicatorEngine.atr(gapBars, 14);
        double expectedLast = (2.0 * 13 + 0 + 0) / 14; // prevClose=100，high=low=close=100 → TR=0
        check("11 ATR 非平盘递推正确（Wilder）", Math.abs(atrGap[19] - expectedLast) < 1e-9,
                "atr=" + atrGap[19] + "（期望 " + expectedLast + "）");

        System.out.println();
        System.out.println("自检结果：PASS " + passCount + " / FAIL " + failCount
                + (failCount == 0 ? "　✅ 交易模型按预期工作" : "　❌ 有断言没通过，下面的统计不可信"));
        System.out.println();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passCount++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "　" + detail));
        } else {
            failCount++;
            System.out.println("  FAIL  " + name + "　" + detail);
        }
    }

    static boolean eq(Double a, double b) {
        return a != null && Math.abs(a - b) < 0.006;
    }

    static String r(List<SimTrade> t) {
        return t.isEmpty() ? "n/a" : String.valueOf(t.get(t.size() - 1).r());
    }

    static String reason(List<SimTrade> t) {
        return t.isEmpty() ? "n/a" : t.get(t.size() - 1).exitReason();
    }

    static String hold(List<SimTrade> t) {
        return t.isEmpty() ? "n/a" : String.valueOf(t.get(t.size() - 1).holdDays());
    }

    /** 平盘 n 根：open=close=price，振幅 ±range → TR 恒为 2×range */
    static List<KlineBar> flat(int n, double price, double range) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            bars.add(new KlineBar(date(i), price, price, price + range, price - range, 1000, 0, 0, 0));
        }
        return bars;
    }

    static KlineBar bar(int i, double o, double h, double l, double c) {
        return new KlineBar(date(i), o, c, h, l, 1000, 0, 0, 0);
    }

    static List<KlineBar> with(List<KlineBar> bars, int idx, KlineBar b) {
        List<KlineBar> out = new ArrayList<>(bars);
        out.set(idx, b);
        return out;
    }

    static String date(int i) {
        return String.format("D%03d", i);
    }

    /** 带 ISO 日期的平盘序列（walk-forward 需要真实日历日期才能切片） */
    static List<KlineBar> isoFlat(int n, double price, double range) {
        List<KlineBar> bars = new ArrayList<>();
        java.time.LocalDate start = java.time.LocalDate.of(2025, 1, 1);
        for (int i = 0; i < n; i++) {
            bars.add(new KlineBar(start.plusDays(i).toString(), price, price,
                    price + range, price - range, 1000, 0, 0, 0));
        }
        return bars;
    }

    static SignalPoint sig(int day, String strategy, String action) {
        return new SignalPoint(date(day), strategy, "test", action, "", 0);
    }

    static SimTrade fakeTrade(String exitDate, double r) {
        return new SimTrade("T", "2024-12-01", 100, exitDate, 100, "时间", r, 5, -0.5, 1.0, r, 0.0);
    }

    /** 复刻 StrategyEngine.resonanceAt 的 6 个零件（多票=true，空票/无票=false） */
    static boolean[] votes(List<KlineBar> bars, int i, double[] close, double[] vol, double[] ma5,
                           double[] ma20, double[] rsi, IndicatorEngine.MacdSeries macd,
                           IndicatorEngine.BollSeries boll, double[] volMa20) {
        boolean[] v = new boolean[6];
        if (i < 20) {
            return v;
        }
        if (ok(ma5[i]) && ok(ma20[i]) && ma5[i] > ma20[i]) {
            v[0] = true;
        }
        if (ok(macd.dif()[i]) && ok(macd.dea()[i]) && macd.dif()[i] > macd.dea()[i]) {
            v[1] = true;
        }
        if (ok(rsi[i]) && rsi[i] > 50) {
            v[2] = true;
        }
        if (ok(boll.mid()[i]) && close[i] > boll.mid()[i]) {
            v[3] = true;
        }
        double prevHigh = -Double.MAX_VALUE;
        for (int p = i - 20; p <= i - 1; p++) {
            prevHigh = Math.max(prevHigh, close[p]);
        }
        if (close[i] >= prevHigh * 0.98) {
            v[4] = true;
        }
        if (ok(volMa20[i]) && vol[i] >= volMa20[i]) {
            KlineBar b = bars.get(i);
            if (b.close() > b.open()) {
                v[5] = true;
            }
        }
        return v;
    }

    static boolean ok(double d) {
        return !Double.isNaN(d);
    }

    static double bench(Map<String, Integer> indexAt, double[] indexClose, List<KlineBar> bars, int i, int h) {
        Integer a = indexAt.get(bars.get(i).date());
        if (a == null || a + h >= indexClose.length) {
            return 0;
        }
        return indexClose[a + h] / indexClose[a] - 1;
    }

    /** 扣掉佣金/过户费/印花税/滑点后的净收益（按 10 万元名义本金） */
    static double netOf(double gross) {
        double buyAmt = NOTIONAL;
        double buyFee = Math.max(buyAmt * COMM_RATE, COMM_MIN) + buyAmt * TRANSFER;
        double cost = buyAmt + buyFee;
        double sellAmt = NOTIONAL * (1 + gross) * (1 - SLIP) / (1 + SLIP);
        double sellFee = Math.max(sellAmt * COMM_RATE, COMM_MIN) + sellAmt * TRANSFER + sellAmt * STAMP;
        double proceeds = sellAmt - sellFee;
        return proceeds / cost - 1;
    }

    static String fmt(String a, String b) {
        return String.format("%6s /%7s", a, b);
    }

    static List<KlineBar> fetch(String symbol, int n) throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of(DATA_DIR + "/" + symbol + ".json");
        if (!java.nio.file.Files.exists(p)) {
            throw new IllegalStateException("缺少本地缓存 " + p);
        }
        String body = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        int k = body.indexOf("\"qfqday\":[");
        if (k < 0) {
            k = body.indexOf("\"day\":[");
        }
        if (k < 0) {
            throw new IllegalStateException("无 klines 字段");
        }
        int s = body.indexOf('[', k);
        // 找到与 s 配对的右括号
        int depth = 1;
        int e = s + 1;
        while (e < body.length() && depth > 0) {
            char c = body.charAt(e);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            e++;
        }
        e--;
        List<KlineBar> out = new ArrayList<>();
        String arr = body.substring(s + 1, e);
        for (String raw : arr.split("\\],\\[")) {
            String item = raw.replace("[", "").replace("]", "").replace("\"", "");
            String[] q = item.split(",");
            if (q.length < 6) {
                continue;
            }
            out.add(new KlineBar(q[0], num(q[1]), num(q[2]), num(q[3]), num(q[4]), num(q[5]), 0, 0, 0));
        }
        return out;
    }

    static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    /** 一个策略在各期限上的累加器 */
    static class Agg {
        int n5;
        int n10;
        int n20;
        double sum5;
        double sum10;
        double sum20;
        double win5;
        double win10;
        double win20;
        double netWin5;
        double netWin10;
        double netWin20;
        double ben5;
        double ben10;
        double ben20;

        void add(int h, double ret, double bench) {
            double net = netOf(ret);
            switch (h) {
                case 5 -> {
                    n5++;
                    sum5 += ret;
                    ben5 += bench;
                    if (ret > 0) win5++;
                    if (net > 0) netWin5++;
                }
                case 10 -> {
                    n10++;
                    sum10 += ret;
                    ben10 += bench;
                    if (ret > 0) win10++;
                    if (net > 0) netWin10++;
                }
                default -> {
                    n20++;
                    sum20 += ret;
                    ben20 += bench;
                    if (ret > 0) win20++;
                    if (net > 0) netWin20++;
                }
            }
        }

        int n(int h) {
            return h == 5 ? n5 : h == 10 ? n10 : n20;
        }

        double sum(int h) {
            return h == 5 ? sum5 : h == 10 ? sum10 : sum20;
        }

        double bench(int h) {
            return h == 5 ? ben5 : h == 10 ? ben10 : ben20;
        }

        double win(int h) {
            return h == 5 ? win5 : h == 10 ? win10 : win20;
        }

        double avg(double sum, int n) {
            return n == 0 ? 0 : sum / n;
        }

        String pct(double w, int n) {
            return n == 0 ? "  n/a" : String.format("%5.1f%%", 100 * w / n);
        }

        String net(double net) {
            return String.format("%+6.2f%%", net * 100);
        }
    }
}
