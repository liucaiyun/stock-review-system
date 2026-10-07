package com.yan.stockreview.service;

import com.yan.stockreview.entity.DisciplineEvent;
import com.yan.stockreview.entity.TradePlan;
import com.yan.stockreview.entity.TradeRecord;
import com.yan.stockreview.repository.DisciplineEventRepository;
import com.yan.stockreview.repository.TradePlanRepository;
import com.yan.stockreview.repository.TradeRecordRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 执行偏差量化：把「计划」和「实际成交」接起来，并且给违规标上钱。
 *
 * <p>为什么这一步最关键：前面几轮已经证明，选股那一侧（9 套策略）在样本外没有可证明的正期望，
 * 唯一还能稳定改善结果的变量就是**你自己的执行**。但原来的系统里：
 * <ul>
 *   <li>「已执行止损」是用户自己点的，从不和成交记录核对；</li>
 *   <li>「硬抗多亏」是在点按钮那一刻冻结的市价，不是真实卖出价；</li>
 *   <li>计划只有 {@code buyTradeId} 一条单向引用，没有离场价、没有实现盈亏；</li>
 *   <li>没有任何地方告诉你「这一年的违规大概花了多少钱」。</li>
 * </ul>
 *
 * <p>这里做三件事：
 * <ol>
 *   <li><b>用成交流水还原真实的一笔笔交易</b>（买入建仓 → 全部卖出平仓，含加仓），算出每笔的
 *       R 倍数（分母是计划止损给出的初始风险）；</li>
 *   <li><b>把每笔和计划配对</b>，逐项检查：追高入场、没按计划止损、亏损加仓、未达目标、超期持有、
 *       以及「根本没计划就买」；能算出钱的（追高多付、超出计划止损的额外亏损、亏损加仓部分的盈亏）
 *       都折算成元；</li>
 *   <li><b>把纪律事件和真实卖出成交对账</b>：标记「已执行止损」但找不到对应卖出的，直接列出来——
 *       这一条把「纪律执行率」从可刷的自我报告变成可核对的数字。</li>
 * </ol>
 *
 * <p>口径与限制（写在这里免得被数字骗）：全部基于 {@code trade_record} 流水，不含佣金/印花税
 * （所以成本被低估，约千分之一量级）；持有天数用自然日；「未达目标」只标出差距，不折算成钱——
 * 那需要卖出后的价格路径，属于机会成本，不硬算。
 */
@Service
public class ExecutionService {

    /** 计划匹配的时间窗口：入场日之前的这么多天内有计划就算「有计划交易」 */
    private static final int PLAN_MATCH_DAYS = 60;
    /** 入场价高于计划价这么多 % 就算追高 */
    private static final double CHASE_PCT = 2.0;
    /** 纪律事件与卖出成交的对账窗口（天） */
    private static final int VERIFY_WINDOW_DAYS = 7;

    private final TradeRecordRepository tradeRepository;
    private final TradePlanRepository planRepository;
    private final DisciplineEventRepository disciplineRepository;

    public ExecutionService(TradeRecordRepository tradeRepository, TradePlanRepository planRepository,
                            DisciplineEventRepository disciplineRepository) {
        this.tradeRepository = tradeRepository;
        this.planRepository = planRepository;
        this.disciplineRepository = disciplineRepository;
    }

    /** 一笔真实交易：从买入建仓到全部卖出平仓（含中途加仓） */
    private static final class Trip {
        String code;
        String name;
        LocalDate entryDate;
        double entryPrice;
        double boughtShares;
        double boughtAmount;
        double soldShares;
        double soldAmount;
        LocalDate exitDate;
        LocalDate lastAddDate;
        double lastAddPrice;
        double addedBelowCostShares;
        double addedBelowCostAmount;
        double addedBelowCostThenExit;
        int adds;
        List<Long> fillIds = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        LinkedHashMap<String, Double> costs = new LinkedHashMap<>();
        double openShares;
        double openCost;
        TradePlan plan;
    }

    public Map<String, Object> report() {
        List<TradeRecord> trades = new ArrayList<>(tradeRepository.findAllByOrderByTradeDateDescIdDesc());
        trades.sort(Comparator.comparing(TradeRecord::getTradeDate).thenComparing(TradeRecord::getId));
        List<TradePlan> plans = planRepository.findAllByOrderByPlanDateDescIdDesc();
        List<DisciplineEvent> events = disciplineRepository.findAllByOrderByTriggerDateDescIdDesc();

        Map<String, List<TradePlan>> plansByCode = new LinkedHashMap<>();
        for (TradePlan p : plans) {
            plansByCode.computeIfAbsent(p.getCode(), k -> new ArrayList<>()).add(p);
        }

        List<Trip> trips = buildTrips(trades);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, double[]> byType = new LinkedHashMap<>(); // 类型 -> [次数, 代价合计]
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, double[]> agg = new LinkedHashMap<>(); // 分组 -> [笔数, R合计, 盈笔数, 有R笔数]

        int closed = 0;
        int planned = 0;
        int compliant = 0;
        double violationCost = 0;

        for (Trip t : trips) {
            if (t.openShares > 1e-6) {
                continue; // 还没平仓，不参与执行偏差统计
            }
            closed++;
            t.plan = matchPlan(t, plansByCode.get(t.code));
            double avgEntry = t.boughtAmount / t.boughtShares;
            double avgExit = t.soldShares <= 0 ? 0 : t.soldAmount / t.soldShares;
            double pnl = t.soldAmount - t.boughtAmount;
            int holdDays = (int) ChronoUnit.DAYS.between(t.entryDate, t.exitDate);

            Double r = null;
            if (t.plan != null && t.plan.getStopPrice() != null && t.plan.getStopPrice() > 0
                    && avgEntry > t.plan.getStopPrice()) {
                double risk = avgEntry - t.plan.getStopPrice();
                r = (avgExit - avgEntry) / risk;
            }

            if (t.plan != null) {
                planned++;
                checkPlan(t, avgEntry, avgExit, holdDays);
            } else {
                t.flags.add("无计划交易：买入前没有对应的交易计划（所以这笔的风险是临时决定的）");
                byType.computeIfAbsent("无计划交易", k -> new double[2])[0]++;
            }
            for (Map.Entry<String, Double> e : t.costs.entrySet()) {
                double[] slot = byType.computeIfAbsent(e.getKey(), k -> new double[2]);
                slot[0]++;
                slot[1] += e.getValue();
                violationCost += e.getValue();
            }
            boolean hasCost = !t.costs.isEmpty();
            if (t.plan != null && !hasCost) {
                compliant++;
            }
            String group = t.plan == null ? "无计划" : (hasCost ? "有计划但违规" : "按计划执行");
            double[] slot = agg.computeIfAbsent(group, k -> new double[4]);
            slot[0]++;
            if (r != null) {
                slot[1] += r;
                slot[3]++;
                if (r > 0) {
                    slot[2]++;
                }
            }
            rows.add(row(t, avgEntry, avgExit, pnl, holdDays, r, group));
        }

        for (Map.Entry<String, double[]> e : agg.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("group", e.getKey());
            g.put("trades", (int) e.getValue()[0]);
            g.put("withR", (int) e.getValue()[3]);
            g.put("expectancyR", e.getValue()[3] == 0 ? null : round(e.getValue()[1] / e.getValue()[3], 3));
            g.put("winRate", e.getValue()[3] == 0 ? null : round(e.getValue()[2] / e.getValue()[3] * 100, 1));
            groups.add(g);
        }
        groups.sort(Comparator.comparing((Map<String, Object> m) -> {
            String g = (String) m.get("group");
            return "按计划执行".equals(g) ? 0 : ("有计划但违规".equals(g) ? 1 : 2);
        }));

        List<Map<String, Object>> types = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byType.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.getKey());
            m.put("count", (int) e.getValue()[0]);
            m.put("cost", round(e.getValue()[1], 2));
            types.add(m);
        }
        types.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("cost")).doubleValue()));
        rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("exitDate"))).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("closedTrips", closed);
        out.put("plannedTrips", planned);
        out.put("unplannedTrips", closed - planned);
        out.put("planRate", closed == 0 ? null : round(planned * 100.0 / closed, 1));
        out.put("compliantTrips", compliant);
        out.put("complianceRate", planned == 0 ? null : round(compliant * 100.0 / planned, 1));
        out.put("violationCostTotal", round(violationCost, 2));
        out.put("violationCount", (int) byType.values().stream()
                .filter(v -> v[0] > 0).count());
        out.put("groups", groups);
        out.put("byType", types);
        out.put("trips", rows);
        out.put("discipline", disciplineCheck(events, trades));
        out.put("note", "全部基于成交流水，未扣佣金与印花税（成本被低估约千分之一量级）；持有天数用自然日；"
                + "「未达目标」只标差距不折钱（那是机会成本，需要卖出后的价格路径）。"
                + "「有计划但违规」只要沾上任何一条能算出钱的偏差就算违规。");
        return out;
    }

    // ================= 用流水还原真实交易 =================

    private List<Trip> buildTrips(List<TradeRecord> trades) {
        Map<String, Trip> open = new LinkedHashMap<>();
        List<Trip> done = new ArrayList<>();
        for (TradeRecord t : trades) {
            double shares = t.getShares() == null ? 0 : t.getShares();
            double price = t.getPrice() == null ? 0 : t.getPrice();
            if (shares <= 0 || price <= 0) {
                continue;
            }
            boolean buy = "buy".equalsIgnoreCase(t.getDirection());
            Trip trip = open.get(t.getCode());
            if (buy) {
                if (trip == null) {
                    trip = new Trip();
                    trip.code = t.getCode();
                    trip.name = t.getName();
                    trip.entryDate = t.getTradeDate();
                    trip.entryPrice = price;
                    open.put(t.getCode(), trip);
                } else {
                    double avgCost = trip.openShares <= 0 ? 0 : trip.openCost / trip.openShares;
                    trip.adds++;
                    if (avgCost > 0 && price < avgCost) {
                        // 亏损加仓：把「加进去这部分」的最终盈亏单独记下来
                        trip.addedBelowCostShares += shares;
                        trip.addedBelowCostAmount += price * shares;
                        trip.lastAddDate = t.getTradeDate();
                        trip.lastAddPrice = price;
                    }
                }
                trip.boughtShares += shares;
                trip.boughtAmount += price * shares;
                trip.openShares += shares;
                trip.openCost += price * shares;
                trip.fillIds.add(t.getId());
            } else {
                if (trip == null) {
                    // 先卖后买（做T 或数据不全）：单独记一笔，不参与计划评估
                    Trip orphan = new Trip();
                    orphan.code = t.getCode();
                    orphan.name = t.getName();
                    orphan.entryDate = t.getTradeDate();
                    orphan.exitDate = t.getTradeDate();
                    orphan.boughtShares = shares;
                    orphan.boughtAmount = price * shares;
                    orphan.soldShares = shares;
                    orphan.soldAmount = price * shares;
                    orphan.flags.add("只有卖出没有对应买入（做T 或流水不全），不计入执行偏差");
                    done.add(orphan);
                    continue;
                }
                double sold = Math.min(shares, trip.openShares);
                double avgCost = trip.openShares <= 0 ? 0 : trip.openCost / trip.openShares;
                trip.openShares -= sold;
                trip.openCost -= avgCost * sold;
                trip.soldShares += sold;
                trip.soldAmount += price * sold;
                trip.fillIds.add(t.getId());
                if (trip.openShares <= 1e-6) {
                    trip.exitDate = t.getTradeDate();
                    // 亏损加仓那部分最终的盈亏
                    if (trip.addedBelowCostShares > 0) {
                        double avgAdd = trip.addedBelowCostAmount / trip.addedBelowCostShares;
                        trip.addedBelowCostThenExit = (price - avgAdd) * trip.addedBelowCostShares;
                    }
                    done.add(trip);
                    open.remove(t.getCode());
                }
            }
        }
        done.addAll(open.values()); // 还没平仓的也返回（调用方会跳过）
        done.sort(Comparator.comparing((Trip t) -> t.entryDate == null ? LocalDate.MIN : t.entryDate));
        return done;
    }

    // ================= 计划配对与偏差检查 =================

    private TradePlan matchPlan(Trip t, List<TradePlan> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // 1) 计划里明确记了 buyTradeId 的优先
        for (TradePlan p : candidates) {
            if (p.getBuyTradeId() != null && t.fillIds.contains(p.getBuyTradeId())) {
                return p;
            }
        }
        // 2) 否则取「入场日之前、离得最近」的计划（太远的不算，避免把半年前的计划硬套上来）
        TradePlan best = null;
        for (TradePlan p : candidates) {
            if (p.getPlanDate() == null || p.getPlanDate().isAfter(t.entryDate)) {
                continue;
            }
            if (ChronoUnit.DAYS.between(p.getPlanDate(), t.entryDate) > PLAN_MATCH_DAYS) {
                continue;
            }
            if ("CANCELLED".equals(p.getStatus())) {
                continue;
            }
            if (best == null || p.getPlanDate().isAfter(best.getPlanDate())) {
                best = p;
            }
        }
        return best;
    }

    private void checkPlan(Trip t, double avgEntry, double avgExit, int holdDays) {
        TradePlan p = t.plan;
        double shares = t.boughtShares;
        // 1) 追高入场：多付的钱是实数
        if (p.getPlanPrice() != null && p.getPlanPrice() > 0) {
            double chasePct = (avgEntry - p.getPlanPrice()) / p.getPlanPrice() * 100;
            if (chasePct > CHASE_PCT) {
                double over = (avgEntry - p.getPlanPrice()) * shares;
                t.flags.add("追高入场：入场均价 " + round(avgEntry, 3) + " 比计划价 " + round(p.getPlanPrice(), 3)
                        + " 高 " + round(chasePct, 2) + "%");
                t.costs.merge("追高入场（高于计划价多付）", over, Double::sum);
            }
        }
        // 2) 没按计划止损：实际卖出价低于计划止损价的部分，就是硬抗的代价
        if (p.getStopPrice() != null && p.getStopPrice() > 0 && avgExit < p.getStopPrice()) {
            double extra = (avgExit - p.getStopPrice()) * shares;
            t.flags.add("超过计划止损：计划止损 " + round(p.getStopPrice(), 3) + "，实际卖出均价 "
                    + round(avgExit, 3) + "，多亏 " + round(Math.abs(extra), 2) + " 元"
                    + "（若当天跳空跌停开在止损下方，这部分不算你的错，但要能从流水里看出来）");
            t.costs.merge("超过计划止损（硬抗/滑出）", extra, Double::sum);
        }
        // 3) 亏损加仓
        if (t.addedBelowCostShares > 0) {
            t.flags.add("亏损加仓：" + t.adds + " 次加仓中有低于成本价的买入，共 "
                    + round(t.addedBelowCostShares, 0) + " 股，这部分最终盈亏 "
                    + round(t.addedBelowCostThenExit, 2) + " 元");
            t.costs.merge("亏损加仓（加仓部分的实际盈亏）", t.addedBelowCostThenExit, Double::sum);
        }
        // 4) 未达目标（只标差距，不折钱）
        if (p.getTargetPrice() != null && p.getTargetPrice() > 0 && avgExit < p.getTargetPrice()) {
            double gap = (p.getTargetPrice() - avgExit) / p.getTargetPrice() * 100;
            t.flags.add("未达目标：计划目标 " + round(p.getTargetPrice(), 3) + "，实际卖出 "
                    + round(avgExit, 3) + "（差 " + round(gap, 2) + "%，机会成本未计入）");
        }
        // 5) 超期持有
        if (p.getHoldDays() != null && p.getHoldDays() > 0 && holdDays > p.getHoldDays()) {
            t.flags.add("超期持有：计划 " + p.getHoldDays() + " 天，实际 " + holdDays + " 天（多 "
                    + (holdDays - p.getHoldDays()) + " 天）");
        }
        // 6) 有计划但没记止损价 → 算不出 R，也没法判断有没有按计划止损
        if (p.getStopPrice() == null || p.getStopPrice() <= 0) {
            t.flags.add("计划里没有止损价：这笔算不出 R，也无法判断「有没有按计划止损」——"
                    + "没有止损价的计划等于没有计划");
        } else if (avgEntry <= p.getStopPrice()) {
            // 典型情况：越跌越买把均价摊到了计划止损之下，风险结构已经被破坏
            t.flags.add("加仓后均价 " + round(avgEntry, 3) + " 已低于计划止损 " + round(p.getStopPrice(), 3)
                    + "：这笔的 R 无法定义（计划里的 1R 风险已经不存在了），"
                    + "而且只要再跌一点就同时触发止损");
        }
    }

    // ================= 纪律事件 vs 真实成交 =================

    /** 「已执行止损」必须能在成交流水里找到对应卖出；「硬抗」的代价用真实卖出价重算 */
    private Map<String, Object> disciplineCheck(List<DisciplineEvent> events, List<TradeRecord> trades) {
        int executed = 0;
        int verified = 0;
        int ignored = 0;
        int ignoredWithFill = 0;
        double extraLossFromFills = 0;
        List<Map<String, Object>> problems = new ArrayList<>();

        for (DisciplineEvent ev : events) {
            if ("EXECUTED".equals(ev.getStatus())) {
                executed++;
                TradeRecord sell = findSell(trades, ev.getCode(), ev.getTriggerDate(), VERIFY_WINDOW_DAYS);
                if (sell == null) {
                    problems.add(problem("bad", ev.getCode(), ev.getName(),
                            "标记为「已执行止损」，但" + VERIFY_WINDOW_DAYS + "天内找不到这只股票的卖出成交"
                                    + "（破线日 " + ev.getTriggerDate() + "）——执行率不该由自报决定"));
                } else {
                    verified++;
                }
            } else if ("IGNORED".equals(ev.getStatus())) {
                ignored++;
                TradeRecord sell = findSell(trades, ev.getCode(), ev.getTriggerDate(), 3650);
                if (sell != null && ev.getStopLine() != null && ev.getStopLine() > 0
                        && sell.getPrice() != null && sell.getShares() != null) {
                    ignoredWithFill++;
                    double extra = (sell.getPrice() - ev.getStopLine()) * sell.getShares();
                    extraLossFromFills += extra;
                    if (extra < 0) {
                        problems.add(problem("warn", ev.getCode(), ev.getName(),
                                "硬抗后实际卖出 " + round(sell.getPrice(), 3) + "（纪律线 "
                                        + round(ev.getStopLine(), 3) + "），比按纪律线卖出多亏 "
                                        + round(Math.abs(extra), 2) + " 元"));
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executedEvents", executed);
        out.put("verifiedSells", verified);
        out.put("unverifiedExecuted", executed - verified);
        out.put("ignoredEvents", ignored);
        out.put("ignoredWithFill", ignoredWithFill);
        out.put("extraLossFromFills", round(extraLossFromFills, 2));
        out.put("problems", problems);
        out.put("note", "「已执行止损」只有能在流水里找到对应卖出才算核实通过；"
                + "硬抗的额外亏损用**真实卖出价**重算（原来是在你点按钮那一刻用市价冻结的，不是成交价）。");
        return out;
    }

    private TradeRecord findSell(List<TradeRecord> trades, String code, LocalDate from, int days) {
        if (code == null || from == null) {
            return null;
        }
        for (TradeRecord t : trades) {
            if (!code.equals(t.getCode()) || !"sell".equalsIgnoreCase(t.getDirection())) {
                continue;
            }
            if (t.getTradeDate() == null || t.getTradeDate().isBefore(from)) {
                continue;
            }
            if (ChronoUnit.DAYS.between(from, t.getTradeDate()) <= days) {
                return t;
            }
        }
        return null;
    }

    private static Map<String, Object> problem(String level, String code, String name, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("code", code);
        m.put("name", name);
        m.put("text", text);
        return m;
    }

    private Map<String, Object> row(Trip t, double avgEntry, double avgExit, double pnl,
                                    int holdDays, Double r, String group) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", t.code);
        m.put("name", t.name);
        m.put("entryDate", t.entryDate == null ? null : t.entryDate.toString());
        m.put("entryPrice", round(avgEntry, 3));
        m.put("exitDate", t.exitDate == null ? null : t.exitDate.toString());
        m.put("exitPrice", round(avgExit, 3));
        m.put("shares", t.boughtShares);
        m.put("holdDays", holdDays);
        m.put("pnl", round(pnl, 2));
        m.put("pnlPct", t.boughtAmount == 0 ? null : round(pnl / t.boughtAmount * 100, 2));
        m.put("r", r == null ? null : round(r, 3));
        m.put("adds", t.adds);
        m.put("group", group);
        m.put("planned", t.plan != null);
        m.put("planId", t.plan == null ? null : t.plan.getId());
        m.put("planPrice", t.plan == null ? null : t.plan.getPlanPrice());
        m.put("planStop", t.plan == null ? null : t.plan.getStopPrice());
        m.put("planTarget", t.plan == null ? null : t.plan.getTargetPrice());
        m.put("planHoldDays", t.plan == null ? null : t.plan.getHoldDays());
        double cost = t.costs.values().stream().mapToDouble(Double::doubleValue).sum();
        m.put("violationCost", round(cost, 2));
        m.put("flags", t.flags);
        m.put("costs", t.costs);
        return m;
    }

    private static double round(double v, int scale) {
        double p = Math.pow(10, scale);
        return Math.round(v * p) / p;
    }
}
