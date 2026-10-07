package com.yan.stockreview.service;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.PositionOverview;
import com.yan.stockreview.dto.PositionRow;
import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.dto.WatchSaveRequest;
import com.yan.stockreview.entity.EquitySnapshot;
import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.market.MarketCodeUtil;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.repository.EquitySnapshotRepository;
import com.yan.stockreview.strategy.IndicatorEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 风险与仓位：把「止损」从手填的百分比换成 ATR，把「仓位」从占持仓市值换成占总资金，
 * 并对组合上限给出**明确的是/否**，而不是只在界面上给个提示。
 *
 * <p>为什么这一步比继续调策略重要：
 * <ul>
 *   <li>固定 −8% 对银行股是 4 个 ATR、对题材股只有 1 个 ATR——同一套「纪律」在不同票上的实际风险差 4 倍；</li>
 *   <li>单笔风险不定，就没有「期望值 R」可言，也就永远不知道一次亏损会不会伤到本金；</li>
 *   <li>样本外验证已经说明这 9 套策略没有可证明的选股 edge，那么能稳定改善结果的只剩下风险约束。</li>
 * </ul>
 *
 * <p>口径说明：{@code riskAmount} = 持仓股数 × 2×ATR(14)，即「如果跌到 ATR 止损会亏多少钱」；
 * {@code weightPct} 用**总资金**做分母（含现金），这才是「仓位占比」应有的含义。
 */
@Service
public class RiskService {

    /** 止损距离 = 该倍数 × ATR(14)，与逐笔回测里的 1R 定义保持一致 */
    public static final double STOP_ATR = 2.0;
    public static final int ATR_PERIOD = 14;

    /** 默认上限 */
    private static final double DEF_MAX_STOCK_PCT = 25;
    private static final double DEF_MAX_SECTOR_PCT = 40;
    private static final int DEF_MAX_POSITIONS = 8;
    private static final double DEF_MAX_RISK_PCT = 2;
    private static final double DEF_MAX_INVESTED_PCT = 95;
    private static final double DEF_RISK_PER_TRADE_PCT = 1;

    /** 现价到止损线不足这么多倍 ATR 就算太紧（会被日常波动打掉） */
    private static final double TOO_TIGHT_ATR = 1.0;
    /** 超过这么多倍 ATR 就算太松（单笔风险过大） */
    private static final double TOO_LOOSE_ATR = 4.0;
    /** 单次请求最多为多少只票现场拉 K 线（其余用缓存） */
    private static final int MAX_LIVE_FETCH = 12;

    private final WatchlistService watchlistService;
    private final QuoteClient quoteClient;
    private final EquitySnapshotRepository equityRepository;

    public RiskService(WatchlistService watchlistService, QuoteClient quoteClient,
                       EquitySnapshotRepository equityRepository) {
        this.watchlistService = watchlistService;
        this.quoteClient = quoteClient;
        this.equityRepository = equityRepository;
    }

    public record Limits(double maxStockPct, double maxSectorPct, int maxPositions,
                         double maxRiskPct, double maxInvestedPct, double riskPerTradePct) {}

    public static Limits defaultLimits() {
        return new Limits(DEF_MAX_STOCK_PCT, DEF_MAX_SECTOR_PCT, DEF_MAX_POSITIONS,
                DEF_MAX_RISK_PCT, DEF_MAX_INVESTED_PCT, DEF_RISK_PER_TRADE_PCT);
    }

    public Map<String, Object> check(Double capitalParam, Limits limits, boolean refresh) {
        Limits lim = limits == null ? defaultLimits() : limits;
        PositionOverview overview = watchlistService.positionOverview(refresh);
        List<PositionRow> rows = overview.getItems() == null ? List.of() : overview.getItems();

        // 总资金：优先用调用方传的（前端 localStorage），其次用最近一次净值快照，最后退化为「只有持仓」
        double capital = 0;
        String capitalSource;
        if (capitalParam != null && capitalParam > 0) {
            capital = capitalParam;
            capitalSource = "param";
        } else {
            Double snapAsset = latestSnapshotAsset();
            if (snapAsset != null && snapAsset > 0) {
                capital = snapAsset;
                capitalSource = "equity_snapshot";
            } else {
                capital = overview.getTotalMarket() == null ? 0 : overview.getTotalMarket();
                capitalSource = "positionsOnly";
            }
        }

        Map<String, WatchStock> byCode = new LinkedHashMap<>();
        for (WatchStock s : watchlistService.list()) {
            byCode.put(s.getCode(), s);
        }

        int[] liveFetches = {0};
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Double> sectorValue = new LinkedHashMap<>();
        double invested = 0;
        double totalRisk = 0;
        int atrCount = 0;
        List<Map<String, Object>> violations = new ArrayList<>();

        for (PositionRow r : rows) {
            double shares = r.getShares() == null ? 0 : r.getShares();
            if (shares <= 0) {
                continue;
            }
            double price = r.getPrice() == null ? 0 : r.getPrice();
            double marketValue = r.getMarketValue() == null ? price * shares : r.getMarketValue();
            invested += marketValue;

            WatchStock ws = byCode.get(r.getCode());
            Double atr = atrOf(ws, refresh, liveFetches);
            if (atr != null && atr > 0) {
                atrCount++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", r.getCode());
            row.put("name", r.getName());
            row.put("shares", shares);
            row.put("price", price);
            row.put("marketValue", round2(marketValue));
            row.put("floatPlPct", r.getFloatPlPct());
            row.put("disciplineStopLine", r.getStopLine());
            row.put("disciplineStopPct", r.getStopPct());
            row.put("sector", sectorName(r));
            row.put("weightPct", capital > 0 ? round2(marketValue / capital * 100) : null);

            String sector = sectorName(r);
            sectorValue.merge(sector == null ? "未分类" : sector, marketValue, Double::sum);

            List<String> flags = new ArrayList<>();
            if (atr != null && atr > 0 && price > 0) {
                double stopDistance = STOP_ATR * atr;
                double atrStop = price - stopDistance;
                double riskAmount = shares * stopDistance;
                totalRisk += riskAmount;
                row.put("atr", round2(atr));
                row.put("atrPct", round2(atr / price * 100));
                row.put("atrStop", round2(atrStop));
                row.put("atrStopDistancePct", round2(stopDistance / price * 100));
                row.put("riskAmount", round2(riskAmount));
                row.put("riskPct", capital > 0 ? round2(riskAmount / capital * 100) : null);
                // 用户当前纪律线离现价有几个 ATR：<1 太紧、>4 太松
                if (r.getStopLine() != null && r.getStopLine() > 0) {
                    double atrMultiple = (price - r.getStopLine()) / stopDistance;
                    row.put("disciplineAtrMultiple", round2(atrMultiple));
                    if (atrMultiple < TOO_TIGHT_ATR) {
                        flags.add("纪律线太紧（不足 1 个 ATR，容易被日常波动打掉）");
                    } else if (atrMultiple > TOO_LOOSE_ATR) {
                        flags.add("纪律线太松（超过 4 个 ATR，单笔风险过大）");
                    }
                }
                // 按「单笔风险 = 总资金 × riskPerTradePct」反推可买股数（向下取整到 100 股）
                if (capital > 0) {
                    double budget = capital * lim.riskPerTradePct() / 100;
                    double rawShares = budget / stopDistance;
                    double lots = Math.floor(rawShares / 100) * 100;
                    row.put("suggestShares", lots);
                    row.put("suggestValue", round2(lots * price));
                    row.put("suggestPct", round2(lots * price / capital * 100));
                }
            } else {
                row.put("atr", null);
                flags.add("拿不到日 K，算不出 ATR（点「刷新并拉行情」）");
            }

            Double weightPct = (Double) row.get("weightPct");
            Double riskPct = (Double) row.get("riskPct");
            if (weightPct != null && weightPct > lim.maxStockPct()) {
                flags.add("单票仓位 " + weightPct + "% 超过上限 " + lim.maxStockPct() + "%");
            }
            if (riskPct != null && riskPct > lim.maxRiskPct()) {
                flags.add("单票风险 " + riskPct + "% 超过上限 " + lim.maxRiskPct() + "%");
            }
            row.put("flags", flags);
            if (!flags.isEmpty()) {
                for (String f : flags) {
                    violations.add(violation(flags.size() > 1 ? "warn" : "bad", r.getCode(), r.getName(), f));
                }
            }
            out.add(row);
        }

        List<Map<String, Object>> sectors = new ArrayList<>();
        for (Map.Entry<String, Double> e : sectorValue.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", e.getKey());
            s.put("marketValue", round2(e.getValue()));
            s.put("pct", capital > 0 ? round2(e.getValue() / capital * 100) : null);
            sectors.add(s);
        }
        sectors.sort(Comparator.comparingDouble((Map<String, Object> m) ->
                -((Number) m.get("marketValue")).doubleValue()));
        for (Map<String, Object> s : sectors) {
            Double pct = (Double) s.get("pct");
            if (pct != null && pct > lim.maxSectorPct()) {
                violations.add(violation("bad", null, null,
                        "行业「" + s.get("name") + "」占总资金 " + pct + "%，超过上限 " + lim.maxSectorPct() + "%"));
            }
        }

        if (out.size() > lim.maxPositions()) {
            violations.add(violation("warn", null, null,
                    "持仓 " + out.size() + " 只，超过上限 " + lim.maxPositions() + " 只（票越多，越管不过来）"));
        }
        double investedPct = capital > 0 ? invested / capital * 100 : 0;
        if (capital > 0 && investedPct > lim.maxInvestedPct()) {
            violations.add(violation("bad", null, null,
                    "总仓位 " + round2(investedPct) + "% 超过上限 " + lim.maxInvestedPct() + "%"));
        }
        violations.sort(Comparator.comparing((Map<String, Object> m) -> "bad".equals(m.get("level")) ? 0 : 1));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capital", round2(capital));
        map.put("capitalSource", capitalSource);
        map.put("capitalSourceNote", switch (capitalSource) {
            case "param" -> "总资金来自你在页面上填的「账户总资金」";
            case "equity_snapshot" -> "总资金来自最近一次净值快照";
            default -> "没有总资金，只能按「持仓市值合计」当分母——此时的仓位占比是相对持仓的，不是相对账户的，别直接参考";
        });
        map.put("positionValue", round2(invested));
        map.put("positionPct", capital > 0 ? round2(investedPct) : null);
        map.put("cash", capital > 0 ? round2(capital - invested) : null);
        map.put("cashPct", capital > 0 ? round2(100 - investedPct) : null);
        map.put("totalRiskAmount", round2(totalRisk));
        map.put("totalRiskPct", capital > 0 ? round2(totalRisk / capital * 100) : null);
        map.put("atrAvailable", atrCount);
        map.put("positionCount", out.size());
        map.put("stopAtr", STOP_ATR);
        map.put("atrPeriod", ATR_PERIOD);
        map.put("riskPerTradePct", lim.riskPerTradePct());
        map.put("limits", Map.of(
                "maxStockPct", lim.maxStockPct(),
                "maxSectorPct", lim.maxSectorPct(),
                "maxPositions", lim.maxPositions(),
                "maxRiskPct", lim.maxRiskPct(),
                "maxInvestedPct", lim.maxInvestedPct()));
        map.put("sectors", sectors);
        map.put("rows", out);
        map.put("violations", violations);
        map.put("okCount", violations.stream().filter(v -> "bad".equals(v.get("level"))).count() == 0
                ? "没有硬性违规" : (violations.size() + " 项待处理"));
        map.put("note", "「单笔风险」= 持仓股数 × " + (int) STOP_ATR + "×ATR(" + ATR_PERIOD
                + ")，即跌到 ATR 止损会亏多少；「建议股数」按单笔风险 = 总资金 × " + lim.riskPerTradePct() + "% 反推。");
        return map;
    }

    /**
     * 录入拦截：超持仓只数 / 单票仓位 / 总仓位 / 单笔 ATR 风险上限时抛 IllegalStateException（文案含「超过上限」→ 409）。
     * 没有现价或总资金时跳过占比类检查，避免误拦。force=true 跳过。
     */
    public void assertCanHold(WatchSaveRequest req) {
        if (req == null || Boolean.TRUE.equals(req.getForce())) {
            return;
        }
        Double shares = req.getShares();
        if (shares == null || shares <= 0) {
            return;
        }
        Limits lim = defaultLimits();
        String code;
        String secid;
        try {
            MarketCodeUtil.ParsedCode parsed = MarketCodeUtil.parse(req.getCode());
            code = parsed.code();
            secid = parsed.secid();
        } catch (Exception ex) {
            return;
        }
        WatchStock local = watchlistService.findLocal(code);
        if (local != null && local.getSecid() != null && !local.getSecid().isBlank()) {
            secid = local.getSecid();
        }
        List<WatchStock> holdings = watchlistService.list().stream()
                .filter(s -> s.getShares() != null && s.getShares() > 0)
                .toList();
        boolean already = holdings.stream().anyMatch(s -> code.equals(s.getCode()));
        int count = already ? holdings.size() : holdings.size() + 1;
        if (count > lim.maxPositions()) {
            throw new IllegalStateException("超过上限：保存后持仓将有 " + count + " 只，上限 "
                    + lim.maxPositions() + " 只");
        }
        Double price = lastPrice(secid);
        if (price == null || price <= 0) {
            return;
        }
        double thisMv = shares * price;
        double invested = thisMv;
        for (WatchStock s : holdings) {
            if (code.equals(s.getCode())) {
                continue;
            }
            Double p = lastPrice(s.getSecid());
            if (p != null && p > 0 && s.getShares() != null) {
                invested += s.getShares() * p;
            } else if (s.getCostAmount() != null) {
                invested += s.getCostAmount();
            }
        }
        Double capital = req.getCapital();
        if (capital == null || capital <= 0) {
            capital = latestSnapshotAsset();
        }
        if (capital == null || capital <= 0) {
            return;
        }
        double weightPct = thisMv / capital * 100;
        if (weightPct > lim.maxStockPct()) {
            throw new IllegalStateException("超过上限：该票约占资金 " + round2(weightPct)
                    + "%，单票上限 " + lim.maxStockPct() + "%");
        }
        double investedPct = invested / capital * 100;
        if (investedPct > lim.maxInvestedPct()) {
            throw new IllegalStateException("超过上限：总仓位约 " + round2(investedPct)
                    + "%，上限 " + lim.maxInvestedPct() + "%");
        }
        Double atr = atrPeek(secid);
        if (atr != null && atr > 0) {
            double riskPct = shares * STOP_ATR * atr / capital * 100;
            if (riskPct > lim.maxRiskPct()) {
                throw new IllegalStateException("超过上限：该票单笔风险约 " + round2(riskPct)
                        + "%（股数×2×ATR），上限 " + lim.maxRiskPct() + "%");
            }
        }
    }

    private Double lastPrice(String secid) {
        if (secid == null || secid.isBlank()) {
            return null;
        }
        List<QuoteSnapshot> qs = quoteClient.cachedQuotes(List.of(secid));
        if (!qs.isEmpty() && qs.get(0).price() != null && qs.get(0).price() > 0) {
            return qs.get(0).price();
        }
        List<KlineBar> bars = quoteClient.peekKline(secid);
        if (bars.isEmpty()) {
            return null;
        }
        double close = bars.get(bars.size() - 1).close();
        return close > 0 ? close : null;
    }

    private Double atrPeek(String secid) {
        if (secid == null || secid.isBlank()) {
            return null;
        }
        List<KlineBar> bars = quoteClient.peekKline(secid);
        if (bars.size() < ATR_PERIOD + 1) {
            return null;
        }
        double[] atr = IndicatorEngine.atr(bars, ATR_PERIOD);
        double last = atr[atr.length - 1];
        return Double.isNaN(last) || last <= 0 ? null : last;
    }

    /** 取 ATR：先读缓存，缓存没有且允许刷新时现场拉（有次数上限，避免一次点出几十个请求） */
    private Double atrOf(WatchStock ws, boolean refresh, int[] liveFetches) {
        if (ws == null || ws.getSecid() == null) {
            return null;
        }
        List<KlineBar> bars = quoteClient.peekKline(ws.getSecid());
        if (bars.size() < ATR_PERIOD + 1 && refresh && liveFetches[0] < MAX_LIVE_FETCH) {
            try {
                bars = quoteClient.fetchKline(ws.getSecid(), 120);
                liveFetches[0]++;
            } catch (Exception ignored) {
                return null;
            }
        }
        if (bars.size() < ATR_PERIOD + 1) {
            return null;
        }
        double[] atr = IndicatorEngine.atr(bars, ATR_PERIOD);
        double last = atr[atr.length - 1];
        return Double.isNaN(last) || last <= 0 ? null : last;
    }

    private Double latestSnapshotAsset() {
        try {
            List<EquitySnapshot> all = equityRepository.findAllByOrderBySnapDateAsc();
            if (all.isEmpty()) {
                return null;
            }
            return all.get(all.size() - 1).getTotalAsset();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sectorName(PositionRow r) {
        String path = r.getBoardPath();
        if (path == null || path.isBlank()) {
            return "未分类";
        }
        String[] parts = path.split("[,，>·\\-]");
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                return p.trim();
            }
        }
        return "未分类";
    }

    private static Map<String, Object> violation(String level, String code, String name, String text) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("level", level);
        v.put("code", code);
        v.put("name", name);
        v.put("text", text);
        return v;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
