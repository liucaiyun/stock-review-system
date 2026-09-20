package com.yan.stockreview.service;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.PositionOverview;
import com.yan.stockreview.entity.EquitySnapshot;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.repository.EquitySnapshotRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账户净值：每日快照总资金 → 净值曲线、最大回撤、对比沪深300 */
@Service
public class EquityService {

    private static final String BENCH_SECID = "1.000300"; // 沪深300

    private final EquitySnapshotRepository repository;
    private final WatchlistService watchlistService;
    private final QuoteClient quoteClient;

    public EquityService(EquitySnapshotRepository repository, WatchlistService watchlistService,
                         QuoteClient quoteClient) {
        this.repository = repository;
        this.watchlistService = watchlistService;
        this.quoteClient = quoteClient;
    }

    /** 记一笔今日净值：总资金手填，持仓市值自动按现价算，同日重复记录会覆盖 */
    @Transactional
    public EquitySnapshot snapshot(Double totalAsset, String note) {
        if (totalAsset == null || totalAsset <= 0) {
            throw new IllegalArgumentException("请填写账户总资金（含现金）");
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
        if (note != null && !note.isBlank()) {
            snap.setNote(note);
        }
        return repository.save(snap);
    }

    /** 净值曲线：我的净值归一化为 1，叠加同期沪深300，附最大回撤 */
    public Map<String, Object> curve() {
        List<EquitySnapshot> snaps = repository.findAllByOrderBySnapDateAsc();
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> dates = new ArrayList<>();
        List<Double> mine = new ArrayList<>();
        List<Double> assets = new ArrayList<>();
        if (snaps.isEmpty()) {
            out.put("dates", dates);
            out.put("mine", mine);
            out.put("bench", List.of());
            out.put("assets", assets);
            out.put("empty", true);
            return out;
        }
        double base = snaps.get(0).getTotalAsset();
        double peak = -Double.MAX_VALUE;
        double maxDd = 0;
        String maxDdDate = null;
        for (EquitySnapshot s : snaps) {
            double nav = s.getTotalAsset() / base;
            dates.add(s.getSnapDate().toString());
            mine.add(round4(nav));
            assets.add(round2(s.getTotalAsset()));
            if (nav > peak) {
                peak = nav;
            }
            double dd = (nav - peak) / peak * 100;
            if (dd < maxDd) {
                maxDd = dd;
                maxDdDate = s.getSnapDate().toString();
            }
        }
        // 基准：沪深300 同期归一
        List<Double> bench = new ArrayList<>();
        Double benchReturn = null;
        try {
            List<KlineBar> bars = quoteClient.peekKline(BENCH_SECID);
            Map<String, Double> closes = new HashMap<>();
            for (KlineBar b : bars) {
                closes.put(b.date(), b.close());
            }
            Double benchBase = closes.get(dates.get(0));
            if (benchBase == null) {
                // 快照日是非交易日：先找之后最近的交易日，没有再找之前最近的
                for (KlineBar b : bars) {
                    if (b.date().compareTo(dates.get(0)) >= 0) {
                        benchBase = b.close();
                        break;
                    }
                }
                if (benchBase == null && !bars.isEmpty()) {
                    benchBase = bars.get(bars.size() - 1).close();
                }
            }
            if (benchBase != null) {
                Double lastClose = null;
                for (String d : dates) {
                    Double c = closes.get(d);
                    if (c == null && !bars.isEmpty()) {
                        // 非交易日快照：用该日之前最近交易日收盘
                        for (int k = bars.size() - 1; k >= 0; k--) {
                            if (bars.get(k).date().compareTo(d) <= 0) {
                                c = bars.get(k).close();
                                break;
                            }
                        }
                    }
                    bench.add(c == null ? null : round4(c / benchBase));
                    if (c != null) {
                        lastClose = c;
                    }
                }
                if (lastClose != null) {
                    benchReturn = round2((lastClose / benchBase - 1) * 100);
                }
            }
        } catch (Exception ignored) {
        }
        double myReturn = (mine.get(mine.size() - 1) - 1) * 100;
        out.put("dates", dates);
        out.put("mine", mine);
        out.put("bench", bench);
        out.put("assets", assets);
        out.put("days", snaps.size());
        out.put("firstDate", dates.get(0));
        out.put("latestAsset", snaps.get(snaps.size() - 1).getTotalAsset());
        out.put("totalReturn", round2(myReturn));
        out.put("benchReturn", benchReturn);
        out.put("maxDrawdown", round2(maxDd));
        out.put("maxDrawdownDate", maxDdDate);
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
