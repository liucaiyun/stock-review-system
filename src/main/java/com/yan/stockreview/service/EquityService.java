package com.yan.stockreview.service;

import com.yan.stockreview.dto.PositionOverview;
import com.yan.stockreview.entity.EquitySnapshot;
import com.yan.stockreview.repository.EquitySnapshotRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户净值：每日快照总资金 → 净值曲线、最大回撤、对比沪深300。
 *
 * <p>两个关键口径：
 * <ul>
 *   <li><b>剔除出入金</b>：入金/出金不是收益。当天记了 {@code cashFlow} 时，当日收益率按
 *       {@code (今日总资产 - 出入金) / 昨日总资产 - 1} 计算，再累乘成净值。否则追加资金当天净值假跳、
 *       抽资当天变成假回撤，最大回撤就没法看了。</li>
 *   <li><b>基准</b>：走 {@link BenchmarkService} 真正拉取沪深300（老代码读只读缓存，永远取不到数据），
 *       起点与其余点统一为「不晚于该日期的最近交易日」；取不到时返回 {@code benchError} 而不是静默空白。</li>
 * </ul>
 */
@Service
public class EquityService {

    /** 无风险利率（用于夏普），按年 2% */
    private static final double RISK_FREE = 0.02;
    /** 少于这么多笔快照或跨度不足这么多天，就不给年化/夏普（样本太少没有意义） */
    private static final int MIN_SNAPSHOTS = 20;
    private static final long MIN_SPAN_DAYS = 60;

    private final EquitySnapshotRepository repository;
    private final WatchlistService watchlistService;
    private final BenchmarkService benchmarkService;

    public EquityService(EquitySnapshotRepository repository, WatchlistService watchlistService,
                         BenchmarkService benchmarkService) {
        this.repository = repository;
        this.watchlistService = watchlistService;
        this.benchmarkService = benchmarkService;
    }

    /**
     * 记一笔今日净值：总资金手填（含现金），持仓市值按现价自动算，同日重复记录会覆盖。
     *
     * <p>故意不加 {@code @Transactional}：里面要拉行情（算持仓市值），把网络 IO 关在事务里会占着数据库连接。
     */
    public EquitySnapshot snapshot(Double totalAsset, Double cashFlow, String note) {
        if (totalAsset == null || totalAsset <= 0) {
            throw new IllegalArgumentException("请填写账户总资产（含现金）");
        }
        Double market = null;
        try {
            PositionOverview overview = watchlistService.positionOverview();
            market = overview.getTotalMarket();
        } catch (Exception ignored) {
        }
        LocalDate today = LocalDate.now();
        EquitySnapshot snap = repository.findBySnapDate(today).orElseGet(EquitySnapshot::new);
        snap.setSnapDate(today);
        snap.setTotalAsset(totalAsset);
        snap.setPositionValue(market);
        snap.setCash(market == null ? null : round2(totalAsset - market));
        snap.setCashFlow(cashFlow == null ? 0.0 : cashFlow);
        if (note != null && !note.isBlank()) {
            snap.setNote(note);
        }
        return repository.save(snap);
    }

    /** 净值曲线（剔除出入金）+ 最大回撤 + 同期沪深300 + 超额 + 年化/夏普 */
    public Map<String, Object> curve() {
        List<EquitySnapshot> snaps = repository.findAllByOrderBySnapDateAsc();
        Map<String, Object> out = new LinkedHashMap<>();
        if (snaps.isEmpty()) {
            out.put("dates", List.of());
            out.put("mine", List.of());
            out.put("bench", List.of());
            out.put("assets", List.of());
            out.put("flow", List.of());
            out.put("empty", true);
            return out;
        }

        List<String> dates = new ArrayList<>();
        List<Double> mine = new ArrayList<>();
        List<Double> assets = new ArrayList<>();
        List<Double> flow = new ArrayList<>();
        List<Double> returns = new ArrayList<>(); // 与 dates 平行的当日收益率（首日为 null）
        boolean adjusted = false;

        double peak = Double.NEGATIVE_INFINITY;
        double maxDd = 0;
        String maxDdDate = null;
        double nav = 1.0;
        for (int i = 0; i < snaps.size(); i++) {
            EquitySnapshot s = snaps.get(i);
            double asset = s.getTotalAsset() == null ? 0 : s.getTotalAsset();
            double cf = s.getCashFlow() == null ? 0 : s.getCashFlow();
            if (Math.abs(cf) > 0.005) {
                adjusted = true;
            }
            Double dailyRet = null;
            if (i > 0) {
                double prev = snaps.get(i - 1).getTotalAsset() == null ? 0 : snaps.get(i - 1).getTotalAsset();
                if (prev > 0) {
                    dailyRet = (asset - cf) / prev - 1;
                    nav *= (1 + dailyRet);
                }
            }
            dates.add(s.getSnapDate().toString());
            mine.add(round4(nav));
            assets.add(round2(asset));
            flow.add(round2(cf));
            returns.add(dailyRet);
            if (nav > peak) {
                peak = nav;
            }
            double dd = peak > 0 ? (nav - peak) / peak * 100 : 0;
            if (dd < maxDd) {
                maxDd = dd;
                maxDdDate = s.getSnapDate().toString();
            }
        }

        // 基准：沪深300 与快照同起点归一
        List<Double> bench = new ArrayList<>();
        Double benchReturn = null;
        String benchError = null;
        Map<String, Double> benchCloses = benchmarkService.closesByDate();
        if (benchCloses.isEmpty()) {
            benchError = benchmarkService.lastError() == null ? "基准行情不可用" : benchmarkService.lastError();
        } else {
            Double base = benchmarkService.closeOnOrBefore(dates.get(0));
            if (base == null || base <= 0) {
                benchError = "基准行情没有覆盖 " + dates.get(0) + " 这一天";
            } else {
                Double last = null;
                for (String d : dates) {
                    Double c = benchmarkService.closeOnOrBefore(d);
                    bench.add(c == null ? null : round4(c / base));
                    if (c != null) {
                        last = c;
                    }
                }
                if (last != null) {
                    benchReturn = round2((last / base - 1) * 100);
                }
            }
        }

        double myReturn = (nav - 1) * 100;
        long spanDays = Math.max(1, ChronoUnit.DAYS.between(snaps.get(0).getSnapDate(),
                snaps.get(snaps.size() - 1).getSnapDate()));
        Double annualReturn = null;
        Double annualVol = null;
        Double sharpe = null;
        if (snaps.size() >= MIN_SNAPSHOTS && spanDays >= MIN_SPAN_DAYS && nav > 0) {
            annualReturn = round2((Math.pow(nav, 365.0 / spanDays) - 1) * 100);
            // 用快照间隔的收益率估计波动；快照不是每日，所以这是近似值
            double mean = 0;
            int n = 0;
            for (Double r : returns) {
                if (r != null) {
                    mean += r;
                    n++;
                }
            }
            if (n >= 2) {
                mean /= n;
                double var = 0;
                for (Double r : returns) {
                    if (r != null) {
                        var += (r - mean) * (r - mean);
                    }
                }
                double sd = Math.sqrt(var / (n - 1));
                double periodsPerYear = 365.0 / spanDays * n;
                annualVol = round2(sd * Math.sqrt(Math.max(1, periodsPerYear)) * 100);
                if (annualVol > 0.01) {
                    sharpe = round2((annualReturn / 100 - RISK_FREE) / (annualVol / 100));
                }
            }
        }

        EquitySnapshot lastSnap = snaps.get(snaps.size() - 1);
        out.put("empty", false);
        out.put("dates", dates);
        out.put("mine", mine);
        out.put("bench", bench);
        out.put("assets", assets);
        out.put("flow", flow);
        out.put("days", snaps.size());
        out.put("firstDate", dates.get(0));
        out.put("spanDays", spanDays);
        out.put("latestAsset", lastSnap.getTotalAsset());
        out.put("latestPosition", lastSnap.getPositionValue());
        out.put("latestCash", lastSnap.getCash());
        out.put("totalReturn", round2(myReturn));
        out.put("benchReturn", benchReturn);
        out.put("excessReturn", benchReturn == null ? null : round2(myReturn - benchReturn));
        out.put("benchName", BenchmarkService.BENCH_NAME);
        out.put("benchError", benchError);
        out.put("cashFlowAdjusted", adjusted);
        out.put("maxDrawdown", round2(maxDd));
        out.put("maxDrawdownDate", maxDdDate);
        out.put("annualReturn", annualReturn);
        out.put("annualVol", annualVol);
        out.put("sharpe", sharpe);
        return out;
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
