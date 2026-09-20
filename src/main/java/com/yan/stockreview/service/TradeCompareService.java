package com.yan.stockreview.service;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.SignalPoint;
import com.yan.stockreview.dto.TradeSignalCompare;
import com.yan.stockreview.dto.TradeSignalRow;
import com.yan.stockreview.entity.TradeRecord;
import com.yan.stockreview.market.MarketCodeUtil;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.strategy.IndicatorEngine;
import com.yan.stockreview.strategy.StrategyEngine;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 把每笔真实成交对照策略买/卖点：差几个交易日、差价多少。
 * 用于复盘纪律，不是买卖建议。
 */
@Service
public class TradeCompareService {

    /** 同向/反向信号搜索窗口（交易日） */
    private static final int WINDOW = 20;
    /** 视为「追」的最晚交易日数 */
    private static final int CHASE_DAYS = 5;
    /** 视为「靠近反向」的交易日数 */
    private static final int AGAINST_DAYS = 5;
    private static final int KLINE_LIMIT = 500;

    private final TradeService tradeService;
    private final QuoteClient quoteClient;
    private final StrategyEngine strategyEngine = new StrategyEngine();

    public TradeCompareService(TradeService tradeService, QuoteClient quoteClient) {
        this.tradeService = tradeService;
        this.quoteClient = quoteClient;
    }

    public TradeSignalCompare compare() {
        List<TradeRecord> trades = tradeService.list();
        if (trades.isEmpty()) {
            return new TradeSignalCompare("还没有成交记录，录入或导入后再对照。",
                    0, 0, 0, 0, 0, 0, 0, null, List.of());
        }

        Map<String, List<TradeRecord>> byCode = new LinkedHashMap<>();
        for (TradeRecord t : trades) {
            byCode.computeIfAbsent(t.getCode(), k -> new ArrayList<>()).add(t);
        }

        Map<Long, TradeSignalRow> byId = new HashMap<>();
        for (Map.Entry<String, List<TradeRecord>> e : byCode.entrySet()) {
            for (TradeSignalRow row : compareCode(e.getKey(), e.getValue())) {
                if (row.tradeId() != null) {
                    byId.put(row.tradeId(), row);
                }
            }
        }

        List<TradeSignalRow> items = new ArrayList<>();
        for (TradeRecord t : trades) {
            TradeSignalRow row = byId.get(t.getId());
            items.add(row != null ? row : TradeSignalRow.none(t, "未能对照"));
        }
        return summarize(items);
    }

    private List<TradeSignalRow> compareCode(String code, List<TradeRecord> trades) {
        try {
            var parsed = MarketCodeUtil.parse(code);
            List<KlineBar> bars = quoteClient.fetchKline(parsed.secid(), KLINE_LIMIT);
            if (bars == null || bars.size() < 35) {
                return noneAll(trades, "K线不足，无法计算策略");
            }
            List<SignalPoint> signals = strategyEngine.analyze(bars).signals();
            Map<String, Integer> dateIndex = new HashMap<>();
            for (int i = 0; i < bars.size(); i++) {
                dateIndex.put(bars.get(i).date(), i);
            }
            List<TradeSignalRow> rows = new ArrayList<>();
            for (TradeRecord t : trades) {
                rows.add(matchOne(t, bars, dateIndex, signals));
            }
            return rows;
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "行情失败" : ex.getMessage();
            return noneAll(trades, "无法对照：" + msg);
        }
    }

    private TradeSignalRow matchOne(TradeRecord t, List<KlineBar> bars,
                                    Map<String, Integer> dateIndex, List<SignalPoint> signals) {
        if (t.getTradeDate() == null) {
            return TradeSignalRow.none(t, "成交日期为空");
        }
        int tradeIdx = indexOnOrBefore(bars, t.getTradeDate());
        if (tradeIdx < 0) {
            return TradeSignalRow.none(t, "K线覆盖不到成交日（日线不足或成交过早）");
        }

        boolean buy = "buy".equalsIgnoreCase(t.getDirection());
        String want = buy ? "BUY" : "SELL";
        String opp = buy ? "SELL" : "BUY";
        Candidate same = nearest(signals, dateIndex, tradeIdx, want, WINDOW);
        Candidate against = nearest(signals, dateIndex, tradeIdx, opp, WINDOW);

        if (against != null && Math.abs(against.days) <= AGAINST_DAYS
                && (same == null || Math.abs(against.days) < Math.abs(same.days))) {
            return toRow(t, bars, tradeIdx, against, "AGAINST");
        }
        if (same != null) {
            String verdict;
            if (same.days == 0) {
                verdict = "HIT";
            } else if (same.days > 0 && same.days <= CHASE_DAYS) {
                verdict = "CHASE";
            } else if (same.days > CHASE_DAYS) {
                verdict = "LATE";
            } else {
                verdict = "EARLY";
            }
            return toRow(t, bars, tradeIdx, same, verdict);
        }
        String act = buy ? "买" : "卖";
        return TradeSignalRow.none(t, "前后 " + WINDOW + " 个交易日没有同向" + act + "点");
    }

    private TradeSignalRow toRow(TradeRecord t, List<KlineBar> bars, int tradeIdx,
                                 Candidate cand, String verdict) {
        SignalPoint sig = cand.signal;
        Double tradePrice = t.getPrice();
        if (tradePrice == null && tradeIdx >= 0 && tradeIdx < bars.size()) {
            tradePrice = bars.get(tradeIdx).close();
        }
        Double pct = null;
        if (tradePrice != null && sig.price() != 0) {
            pct = IndicatorEngine.round((tradePrice - sig.price()) / sig.price() * 100.0, 2);
        }
        boolean buy = "buy".equalsIgnoreCase(t.getDirection());
        String verdictLabel = verdictLabel(verdict, buy);
        String label = shortLabel(verdict, cand.days, buy, sig.strategyName());
        String explain = explain(verdict, buy, cand.days, pct, sig);
        String date = t.getTradeDate() == null ? null : t.getTradeDate().toString();
        return new TradeSignalRow(
                t.getId(),
                date,
                t.getCode(),
                t.getName(),
                t.getDirection(),
                t.getPrice(),
                verdict,
                verdictLabel,
                cand.days,
                pct,
                sig.date(),
                sig.strategy(),
                sig.strategyName(),
                sig.reason(),
                IndicatorEngine.round(sig.price(), 3),
                label,
                explain
        );
    }

    private static String verdictLabel(String verdict, boolean buy) {
        return switch (verdict) {
            case "HIT" -> "当天打中";
            case "CHASE" -> buy ? "信号后追买" : "信号后才卖";
            case "LATE" -> "偏晚";
            case "EARLY" -> buy ? "提前买入" : "提前卖出";
            case "AGAINST" -> "逆信号";
            default -> "附近无信号";
        };
    }

    private static String shortLabel(String verdict, int days, boolean buy, String strategyName) {
        String name = strategyName == null ? "策略" : strategyName;
        return switch (verdict) {
            case "HIT" -> "当天打中 · " + name;
            case "CHASE" -> "信号后 " + days + " 日" + (buy ? "追买" : "才卖") + " · " + name;
            case "LATE" -> "偏晚 " + days + " 日 · " + name;
            case "EARLY" -> "提前 " + Math.abs(days) + " 日 · " + name;
            case "AGAINST" -> "逆信号 · 靠近" + name;
            default -> "附近无同向信号";
        };
    }

    private static String explain(String verdict, boolean buy, int days, Double pct, SignalPoint sig) {
        String act = buy ? "买" : "卖";
        String sigAct = "BUY".equals(sig.action()) ? "买点" : "卖点";
        String pctText = pct == null ? "" : ("，成交价相对信号价 " + (pct > 0 ? "贵" : (pct < 0 ? "便宜" : "持平"))
                + " " + Math.abs(pct) + "%");
        String name = sig.strategyName() == null ? "策略" : sig.strategyName();
        return switch (verdict) {
            case "HIT" -> "成交日当天有「" + name + "」" + sigAct + "（" + sig.reason() + "）" + pctText
                    + "。对照：是否真按交叉/突破当日做。";
            case "CHASE" -> act + "点在 " + days + " 个交易日前（" + name + "：" + sig.reason() + "）" + pctText
                    + "。常见于信号出现后才动手。";
            case "LATE" -> act + "点已过 " + days + " 个交易日（" + name + "），参考意义变弱" + pctText + "。";
            case "EARLY" -> "成交在" + sigAct + "前 " + Math.abs(days) + " 个交易日（之后才出现「" + name + "」）。属于预判，不是打中信号。";
            case "AGAINST" -> "最近的是" + sigAct + "（" + name + "，差 " + Math.abs(days) + " 个交易日），这笔却是"
                    + act + "。用来检查是否逆信号操作。";
            default -> "前后没有可对照的信号。";
        };
    }

    private static Candidate nearest(List<SignalPoint> signals, Map<String, Integer> dateIndex,
                                     int tradeIdx, String action, int window) {
        Candidate best = null;
        for (SignalPoint s : signals) {
            if (!action.equals(s.action())) {
                continue;
            }
            Integer si = dateIndex.get(s.date());
            if (si == null) {
                continue;
            }
            int days = tradeIdx - si;
            if (Math.abs(days) > window) {
                continue;
            }
            if (better(best, days)) {
                best = new Candidate(s, days);
            }
        }
        return best;
    }

    /** 优先更近；同样近时优先「信号已出现」（days >= 0） */
    private static boolean better(Candidate best, int days) {
        if (best == null) {
            return true;
        }
        int abs = Math.abs(days);
        int bestAbs = Math.abs(best.days);
        if (abs < bestAbs) {
            return true;
        }
        return abs == bestAbs && days >= 0 && best.days < 0;
    }

    private static int indexOnOrBefore(List<KlineBar> bars, LocalDate tradeDate) {
        int idx = -1;
        for (int i = 0; i < bars.size(); i++) {
            LocalDate d = LocalDate.parse(bars.get(i).date());
            if (!d.isAfter(tradeDate)) {
                idx = i;
            } else {
                break;
            }
        }
        if (idx < 0) {
            return -1;
        }
        LocalDate barDate = LocalDate.parse(bars.get(idx).date());
        if (tradeDate.isAfter(barDate) && ChronoUnit.DAYS.between(barDate, tradeDate) > 7
                && idx == bars.size() - 1) {
            return idx;
        }
        return idx;
    }

    private static List<TradeSignalRow> noneAll(List<TradeRecord> trades, String explain) {
        List<TradeSignalRow> rows = new ArrayList<>();
        for (TradeRecord t : trades) {
            rows.add(TradeSignalRow.none(t, explain));
        }
        return rows;
    }

    private static TradeSignalCompare summarize(List<TradeSignalRow> items) {
        int hit = 0, chase = 0, late = 0, early = 0, against = 0, none = 0;
        double daySum = 0;
        int dayN = 0;
        for (TradeSignalRow r : items) {
            String v = r.verdict() == null ? "NONE" : r.verdict();
            switch (v) {
                case "HIT" -> hit++;
                case "CHASE" -> chase++;
                case "LATE" -> late++;
                case "EARLY" -> early++;
                case "AGAINST" -> against++;
                default -> none++;
            }
            if (r.daysDiff() != null && ("HIT".equals(v) || "CHASE".equals(v) || "LATE".equals(v))) {
                daySum += r.daysDiff();
                dayN++;
            }
        }
        Double avg = dayN == 0 ? null : IndicatorEngine.round(daySum / dayN, 1);
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(items.size()).append(" 笔：当天打中 ").append(hit)
                .append("，信号后追 ").append(chase)
                .append("，提前 ").append(early)
                .append("，偏晚 ").append(late)
                .append("，逆信号 ").append(against)
                .append("，附近无信号 ").append(none).append("。");
        if (avg != null) {
            sb.append("同向且未提前的成交，平均在信号后 ").append(avg).append(" 个交易日。");
        }
        sb.append("策略用于对照和学习，不是买卖建议。");
        return new TradeSignalCompare(sb.toString(), items.size(), hit, chase, late, early, against, none, avg, items);
    }

    private record Candidate(SignalPoint signal, int days) {}
}
