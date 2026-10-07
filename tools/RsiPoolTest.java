import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.SignalPoint;
import com.yan.stockreview.dto.SimTrade;
import com.yan.stockreview.dto.TradeStats;
import com.yan.stockreview.dto.WalkForwardStats;
import com.yan.stockreview.strategy.IndicatorEngine;
import com.yan.stockreview.strategy.TradeParams;
import com.yan.stockreview.strategy.TradeSimulator;
import com.yan.stockreview.strategy.WalkForwardAnalyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * RSI 超跌反转的独立检验（只读研究工具，不改项目源码、不写数据库）。
 *
 * 为什么单独做这件事：在前面几轮里（固定持有期 → 逐笔交易 → 样本外验证），RSI 是唯一一个
 * 每轮都排在最前的策略：固定 20 日净超额 +0.90%，逐笔期望 +0.374R，样本外 +0.094R（t=0.66）。
 * 但样本只有 24 只大盘股、2.4 年、111 笔样本外交易。所以这里把它单独拿出来：
 *   ① 股票池扩到 210 只（在 7 个代码段里机械抽样，不挑票）；
 *   ② 拿新浪 1000 根（4.1 年）做「更长历史」的稳健性检查（注意：新浪那个接口是不复权数据）；
 *   ③ 把规则本身也拆开扫：RSI 周期、超卖阈值、要不要加市场趋势过滤（沪深300 > MA20）、
 *      要不要用策略自己的卖点出场；
 *   ④ 关键对照：**「随便哪天都在场」**——同样的 ATR 止损、同样的持有规则，只是不用 RSI 择时。
 *      如果 RSI 打不过这个对照，那它的「超跌反转」就没有提供任何信息。
 *
 * 用法：java -cp "target\classes;tools\out" RsiPoolTest [数据目录] [报告文件]
 */
public class RsiPoolTest {

    static String DATA_DIR = "tools/pool-data";
    static final String INDEX_SYMBOL = "sh000300";
    static final int FOLDS = 4;
    static final int MIN_TRAIN_TRADES = 25;
    /**
     * 只保留这段窗口内的 K 线。
     *
     * <p>为什么必须做这一步（这是一个真实踩过的坑）：抽样池里有少数代码返回的是**上市以来的全历史**
     * （最早到 1996/1997），于是 walk-forward 的日期边界会被拉到 30 年跨度，而所有交易都集中在 2024-2026，
     * 结果每一折的「测试窗口」都落在交易之前——样本外笔数变成 0，或者只剩那几只长历史股票在贡献样本，
     * 得出 t=7.8 这种又大又假的数字。窗口对齐之后，所有股票共用同一段日期，折才是有意义的。
     */
    static final Map<String, String> WINDOW_FROM = Map.of("tx_", "2024-02-02", "sina_", "2022-08-12");
    /** 每个源要求的最少根数（少于这个数说明数据稀疏或来源不对，直接剔除） */
    static final Map<String, Integer> MIN_BARS = Map.of("tx_", 200, "sina_", 400);
    /** 新浪那个源是不复权数据，单独标注 */
    static final String SINA_NOTE = "（新浪源：不复权，除权日的假缺口会污染信号，只能当稳健性参考）";

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            DATA_DIR = args[0];
        }
        String report = args.length > 1 ? args[1] : DATA_DIR + "/rsi-report.txt";
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(report), true, "UTF-8"));

        // ---------- 数据 ----------
        List<String> syms = readSymbols("symbols_tx.txt");
        List<String> sinaSyms = readSymbols("symbols_sina.txt");
        Map<String, List<KlineBar>> txBars = loadAll(syms, "tx_");
        Map<String, List<KlineBar>> sinaBars = loadAll(sinaSyms, "sina_");
        List<KlineBar> index = loadOne(INDEX_SYMBOL, "tx_");
        Map<String, Double> bench = closes(index);
        System.out.println("############ RSI 超跌反转：大样本独立检验 ############");
        System.out.printf("腾讯前复权：%d 只，区间 %s ~ %s（约 2.6 年）%n",
                txBars.size(), firstDate(txBars), lastDate(txBars));
        System.out.printf("新浪（不复权）：%d 只，区间 %s ~ %s（约 4.1 年）%s%n",
                sinaBars.size(), firstDate(sinaBars), lastDate(sinaBars), SINA_NOTE);
        System.out.printf("沪深300 %d 根，用于「只在市场上升趋势里买」的过滤%n", index.size());
        System.out.println("窗口对齐后每个源的实际区间（必须都落在同一年代，否则 walk-forward 的折是错的）：");
        System.out.printf("  腾讯 %s ~ %s；新浪 %s ~ %s%n",
                firstDate(txBars), lastDate(txBars), firstDate(sinaBars), lastDate(sinaBars));
        System.out.println();

        // ---------- 变体网格 ----------
        List<Variant> variants = new ArrayList<>();
        // 对照 1：随便哪天都在场（每 3 天一个买点 + 一次只持一笔 ≈ 基本一直在场）
        variants.add(new Variant("对照:一直在场", -1, 0, false, true));
        // 对照 2：完全随机（用固定种子的伪随机日期）
        variants.add(new Variant("对照:随机日入场", -2, 0, false, true));
        for (int period : new int[]{6, 14, 21}) {
            for (double os : new double[]{20, 25, 30, 35}) {
                variants.add(new Variant("RSI" + period + "上穿" + (int) os, period, os, false, true));
                variants.add(new Variant("RSI" + period + "上穿" + (int) os + "+趋势过滤", period, os, true, true));
            }
        }
        // 关掉策略自己的卖点（只靠 ATR 跟踪）
        for (double os : new double[]{25, 30}) {
            variants.add(new Variant("RSI14上穿" + (int) os + "+趋势过滤+不用卖点", 14, os, true, false));
            variants.add(new Variant("RSI14上穿" + (int) os + "+不用卖点", 14, os, false, false));
        }

        System.out.printf("############ 1. 全样本 + 样本外（腾讯前复权，%d 只）############%n", txBars.size());
        System.out.println("「全样本期望」= 同一段历史里算出来的最好看的值；「样本外期望」= 只用过去、往前跑得到的值。");
        System.out.println();
        System.out.printf("%-34s %7s %8s %10s %9s %9s %10s %8s%n",
                "变体", "交易数", "胜率", "全样本期望", "t值", "样本外笔数", "样本外期望", "样本外t");

        List<Result> results = new ArrayList<>();
        for (Variant v : variants) {
            Result r = evaluate(v, txBars, index, bench);
            results.add(r);
            printResult(r);
        }
        System.out.println();
        System.out.println("（本次 walk-forward 实际用的日期边界：" + lastBounds + "）");
        System.out.println();

        // ---------- 排名与结论 ----------
        List<Result> ranked = new ArrayList<>(results);
        ranked.sort(Comparator.comparingDouble((Result r) -> -(r.oosExp == null ? -9 : r.oosExp)));
        System.out.println("############ 2. 按样本外期望值排序 ############");
        System.out.printf("%-34s %10s %8s %12s%n", "变体", "样本外期望", "样本外t", "判定");
        for (Result r : ranked) {
            String verdict = r.oosT == null ? "样本不足"
                    : (Math.abs(r.oosT) < 2 ? "与 0 分不出来" : (r.oosT > 0 ? "可能为正" : "可能为负"));
            System.out.printf("%-34s %+10.3f %8s %12s%n", r.name,
                    r.oosExp == null ? 0 : r.oosExp,
                    r.oosT == null ? "-" : String.format("%.2f", r.oosT), verdict);
        }
        System.out.println();

        Result control = results.get(0);
        Result best = ranked.get(0);
        System.out.println("############ 3. 结论 ############");
        System.out.printf("对照组「一直在场」：期望 %sR，样本外 %sR%n",
                fmt(control.exp), fmt(control.oosExp));
        System.out.printf("最好的变体「%s」：期望 %sR，样本外 %sR（t=%s）%n",
                best.name, fmt(best.exp), fmt(best.oosExp), best.oosT == null ? "-" : String.format("%.2f", best.oosT));
        long significant = results.stream().filter(r -> r.oosT != null && r.oosT > 2).count();
        long beatingControl = results.stream()
                .filter(r -> r.oosExp != null && control.oosExp != null && r.oosExp > control.oosExp).count();
        System.out.println("样本外 t > 2（即「和 0 分得出来」）的变体数：" + significant + " / " + results.size());
        System.out.println("样本外期望值超过「一直在场」对照的变体数：" + beatingControl + " / " + results.size());
        System.out.println();
        System.out.println("要看的两件事：① 有没有任何一个变体的样本外 t > 2；");
        System.out.println("              ② RSI 择时是否比「一直在场」更好——如果不是，那它没有提供信息。");
        System.out.println();

        // ---------- 更长历史（新浪 4.1 年，不复权）----------
        System.out.println("############ 4. 更长历史稳健性检查（新浪 1000 根 ≈ 4.1 年）" + SINA_NOTE + " ############");
        System.out.println();
        System.out.printf("%-34s %7s %8s %10s %9s %9s %10s %8s%n",
                "变体", "交易数", "胜率", "全样本期望", "t值", "样本外笔数", "样本外期望", "样本外t");
        List<Variant> shortlist = new ArrayList<>();
        shortlist.add(variants.get(0)); // 一直在场
        for (Result r : ranked) {
            if (shortlist.size() >= 7) {
                break;
            }
            // 只挑不带趋势过滤的：指数只有 2024-02 之后的数据，2022-2024 段没有过滤依据，
            // 带上它会把那段信号全砍掉，结论会被数据缺口而不是策略本身决定。
            if (r.variant.period > 0 && !r.variant.regimeFilter && !shortlist.contains(r.variant)) {
                shortlist.add(r.variant);
            }
        }
        for (Variant v : shortlist) {
            printResult(evaluate(v, sinaBars, index, bench));
        }
        System.out.println();
        System.out.println("注：新浪源不复权。除权日会产生假的向下跳空，对「超跌反转」是**有利**的偏差（假的超卖信号），");
        System.out.println("    所以如果这一节的结果比腾讯那节更好，不能当成支持证据。");
    }

    // ================= 变体与评估 =================

    /** period<0 表示对照组：-1=一直场、-2=随机日 */
    record Variant(String name, int period, double oversold, boolean regimeFilter, boolean useSell) {}

    static class Result {
        String name;
        Variant variant;
        int trades;
        Double winRate;
        Double exp;
        Double t;
        Double profitFactor;
        int oosTrades;
        Double oosExp;
        Double oosT;
        int positiveFolds;
        int usedFolds;
    }

    static Result evaluate(Variant v, Map<String, List<KlineBar>> data, List<KlineBar> index,
                           Map<String, Double> bench) {
        Set<String> regimeDates = v.regimeFilter ? regimeOkDates(index) : null;
        String pseudoId = "V";
        List<WalkForwardAnalyzer.StockSeries> series = new ArrayList<>();
        for (Map.Entry<String, List<KlineBar>> e : data.entrySet()) {
            List<KlineBar> bars = e.getValue();
            if (bars.size() < 120) {
                continue;
            }
            List<SignalPoint> sigs = switch (v.period) {
                case -1 -> everyDaySignals(bars, 3, regimeDates, pseudoId);
                case -2 -> randomSignals(bars, regimeDates, pseudoId, e.getKey());
                default -> rsiSignals(bars, v.period, v.oversold, pseudoId, regimeDates, v.useSell);
            };
            series.add(new WalkForwardAnalyzer.StockSeries(e.getKey(), bars, sigs));
        }
        // 全样本
        List<SimTrade> all = new ArrayList<>();
        for (WalkForwardAnalyzer.StockSeries s : series) {
            all.addAll(TradeSimulator.simulate(s.bars(), s.signals(), pseudoId, bench, TradeParams.DEFAULT));
        }
        TradeStats t = TradeStats.from(all, 0);
        Result r = new Result();
        r.name = v.name;
        r.variant = v;
        r.trades = t.trades();
        r.winRate = t.winRate();
        r.exp = t.expectancyR();
        r.t = t.tStat();
        r.profitFactor = t.profitFactor();
        // 样本外（固定参数，只做锚定切分，不做参数搜索——这里比的是「信号规则」不是「参数调优」）
        List<WalkForwardStats> wf = WalkForwardAnalyzer.analyze(series, List.of(pseudoId),
                List.of(TradeParams.DEFAULT), FOLDS, MIN_TRAIN_TRADES, bench);
        if (!wf.isEmpty()) {
            WalkForwardStats w = wf.get(0);
            r.oosTrades = w.oosTrades();
            r.oosExp = w.oosExpectancyR();
            r.oosT = w.oosTStat();
            r.positiveFolds = w.positiveFolds();
            r.usedFolds = w.usedFolds();
            lastBounds = w.from() + " → " + w.to() + "，折：" + w.foldList().stream()
                    .map(f -> f.testStart() + "~" + f.testEnd()).reduce((a, b) -> a + " | " + b).orElse("无");
        }
        return r;
    }

    static String lastBounds = "未计算";

    static void printResult(Result r) {
        System.out.printf("%-34s %7d %7s%% %+10.3f %9s %9d %+10.3f %8s%n",
                r.name, r.trades, r.winRate == null ? "-" : String.format("%.1f", r.winRate),
                r.exp == null ? 0 : r.exp, r.t == null ? "-" : String.format("%.2f", r.t),
                r.oosTrades, r.oosExp == null ? 0 : r.oosExp,
                r.oosT == null ? "-" : String.format("%.2f", r.oosT));
    }

    // ================= 信号生成 =================

    /** 标准规则：RSI 由超卖区上穿阈值 → 买；下穿超买阈值 → 卖（useSell=false 时不产生卖点） */
    static List<SignalPoint> rsiSignals(List<KlineBar> bars, int period, double oversold, String id,
                                        Set<String> regime, boolean useSell) {
        double[] close = IndicatorEngine.closes(bars);
        double[] rsi = IndicatorEngine.rsi(close, period);
        double overbought = 100 - oversold;
        List<SignalPoint> out = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            if (!ok(rsi[i]) || !ok(rsi[i - 1])) {
                continue;
            }
            if (rsi[i - 1] <= oversold && rsi[i] > oversold) {
                if (regime == null || regime.contains(bars.get(i).date())) {
                    out.add(new SignalPoint(bars.get(i).date(), id, id, "BUY", "RSI 上穿 " + oversold, close[i]));
                }
            } else if (useSell && rsi[i - 1] >= overbought && rsi[i] < overbought) {
                out.add(new SignalPoint(bars.get(i).date(), id, id, "SELL", "RSI 下穿 " + overbought, close[i]));
            }
        }
        return out;
    }

    /** 对照：每隔 step 天一个买点（配合「一次只持一笔」≈ 基本一直在场） */
    static List<SignalPoint> everyDaySignals(List<KlineBar> bars, int step, Set<String> regime, String id) {
        List<SignalPoint> out = new ArrayList<>();
        for (int i = 20; i < bars.size(); i += step) {
            if (regime == null || regime.contains(bars.get(i).date())) {
                out.add(new SignalPoint(bars.get(i).date(), id, id, "BUY", "对照", bars.get(i).close()));
            }
        }
        return out;
    }

    /** 对照：固定种子的伪随机入场（同一只票每次运行结果一致） */
    static List<SignalPoint> randomSignals(List<KlineBar> bars, Set<String> regime, String id, String code) {
        long seed = code.hashCode();
        List<SignalPoint> out = new ArrayList<>();
        for (int i = 20; i < bars.size(); i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            if (((seed >>> 33) & 0x3) == 0 && (regime == null || regime.contains(bars.get(i).date()))) {
                out.add(new SignalPoint(bars.get(i).date(), id, id, "BUY", "随机", bars.get(i).close()));
            }
        }
        return out;
    }

    /** 沪深300 收盘 > MA20 的日期集合 */
    static Set<String> regimeOkDates(List<KlineBar> index) {
        Set<String> out = new LinkedHashSet<>();
        if (index == null || index.isEmpty()) {
            return out;
        }
        double[] close = IndicatorEngine.closes(index);
        double[] ma20 = IndicatorEngine.sma(close, 20);
        for (int i = 0; i < index.size(); i++) {
            if (ok(ma20[i]) && close[i] > ma20[i]) {
                out.add(index.get(i).date());
            }
        }
        return out;
    }

    // ================= 数据读取 =================

    static List<String> readSymbols(String file) throws Exception {
        Path p = Path.of(DATA_DIR, file);
        if (!Files.exists(p)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(p)) {
            String s = line.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    static Map<String, List<KlineBar>> loadAll(List<String> syms, String prefix) {
        Map<String, List<KlineBar>> out = new LinkedHashMap<>();
        String from = WINDOW_FROM.getOrDefault(prefix, "1900-01-01");
        int minBars = MIN_BARS.getOrDefault(prefix, 120);
        int droppedShort = 0;
        int droppedNone = 0;
        for (String s : syms) {
            try {
                List<KlineBar> bars = loadOne(s, prefix);
                List<KlineBar> win = new ArrayList<>();
                for (KlineBar b : bars) {
                    if (b.date() != null && b.date().compareTo(from) >= 0) {
                        win.add(b);
                    }
                }
                if (win.size() >= minBars) {
                    out.put(s, win);
                } else if (win.isEmpty()) {
                    droppedNone++;
                } else {
                    droppedShort++;
                }
            } catch (Exception ignored) {
                droppedNone++;
            }
        }
        System.out.printf("  [%s] 保留 %d 只（窗口 %s 起，少于 %d 根的剔除 %d 只，读取失败 %d 只）%n",
                prefix, out.size(), from, minBars, droppedShort, droppedNone);
        return out;
    }

    /** 腾讯：data.<sym>.qfqday / day，元素是 [日期, 开, 收, 高, 低, 量]；新浪：数组对象 */
    static List<KlineBar> loadOne(String sym, String prefix) throws Exception {
        Path p = Path.of(DATA_DIR, prefix + sym + ".json");
        String body = Files.readString(p);
        List<KlineBar> out = new ArrayList<>();
        if ("sina_".equals(prefix)) {
            int i = 0;
            while (true) {
                int o = body.indexOf('{', i);
                if (o < 0) {
                    break;
                }
                int c = body.indexOf('}', o);
                if (c < 0) {
                    break;
                }
                String obj = body.substring(o, c);
                i = c + 1;
                String day = str(obj, "day");
                if (day == null) {
                    continue;
                }
                out.add(new KlineBar(day, dbl(obj, "open"), dbl(obj, "close"),
                        dbl(obj, "high"), dbl(obj, "low"), dbl(obj, "volume"), 0, 0, 0));
            }
            return out;
        }
        int k = body.indexOf("\"qfqday\":[");
        if (k < 0) {
            k = body.indexOf("\"day\":[");
        }
        if (k < 0) {
            return out;
        }
        int s = body.indexOf('[', k);
        int depth = 1;
        int e = s + 1;
        while (e < body.length() && depth > 0) {
            char ch = body.charAt(e);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
            }
            e++;
        }
        e--;
        for (String raw : body.substring(s + 1, e).split("\\],\\[")) {
            String item = raw.replace("[", "").replace("]", "").replace("\"", "");
            String[] q = item.split(",");
            if (q.length < 6) {
                continue;
            }
            out.add(new KlineBar(q[0], num(q[1]), num(q[2]), num(q[3]), num(q[4]), num(q[5]), 0, 0, 0));
        }
        return out;
    }

    static String str(String obj, String key) {
        int i = obj.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int c = obj.indexOf(':', i);
        int q1 = obj.indexOf('"', c);
        int q2 = obj.indexOf('"', q1 + 1);
        return q1 < 0 || q2 < 0 ? null : obj.substring(q1 + 1, q2);
    }

    static double dbl(String obj, String key) {
        int i = obj.indexOf("\"" + key + "\"");
        if (i < 0) {
            return 0;
        }
        int c = obj.indexOf(':', i);
        int p = c + 1;
        // 跳过空格和引号：新浪那个接口的价格是带引号的字符串（"open":"1906.900"），
        // 不跳过引号会解析出 0——之前整节结果全是 0 就是这个原因。
        while (p < obj.length() && (obj.charAt(p) == ' ' || obj.charAt(p) == '"')) {
            p++;
        }
        int end = p;
        while (end < obj.length() && "0123456789.-+eE".indexOf(obj.charAt(end)) >= 0) {
            end++;
        }
        return num(obj.substring(p, end));
    }

    static Map<String, Double> closes(List<KlineBar> bars) {
        Map<String, Double> m = new TreeMap<>();
        if (bars != null) {
            for (KlineBar b : bars) {
                m.put(b.date(), b.close());
            }
        }
        return m;
    }

    static String firstDate(Map<String, List<KlineBar>> data) {
        String min = null;
        for (List<KlineBar> b : data.values()) {
            if (!b.isEmpty() && (min == null || b.get(0).date().compareTo(min) < 0)) {
                min = b.get(0).date();
            }
        }
        return min;
    }

    static String lastDate(Map<String, List<KlineBar>> data) {
        String max = null;
        for (List<KlineBar> b : data.values()) {
            if (!b.isEmpty() && (max == null || b.get(b.size() - 1).date().compareTo(max) > 0)) {
                max = b.get(b.size() - 1).date();
            }
        }
        return max;
    }

    static boolean ok(double v) {
        return !Double.isNaN(v);
    }

    static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static String fmt(Double v) {
        return v == null ? "n/a" : String.format("%+.3f", v);
    }
}
