package com.yan.stockreview.service;

import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.entity.DisciplineEvent;
import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.repository.DisciplineEventRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 止损纪律闭环：破 -8% 线记事件 → 执行/硬抗/涨回/清仓 → 统计纪律执行率 */
@Service
public class DisciplineService {

    public static final double STOP_PCT = 0.92; // 成本 × 0.92 = -8% 纪律线

    private final DisciplineEventRepository repository;
    private final WatchlistService watchlistService;
    private final QuoteClient quoteClient;

    public DisciplineService(DisciplineEventRepository repository, WatchlistService watchlistService,
                             QuoteClient quoteClient) {
        this.repository = repository;
        this.watchlistService = watchlistService;
        this.quoteClient = quoteClient;
    }

    public List<DisciplineEvent> list() {
        return repository.findAllByOrderByTriggerDateDescIdDesc();
    }

    public List<DisciplineEvent> openEvents() {
        return repository.findByStatusOrderByTriggerDateDesc("OPEN");
    }

    /**
     * 扫描全部持仓：
     * 破线且无 OPEN 事件 → 新建事件；
     * 破线已有 OPEN 事件 → 保持；
     * 涨回线上 → 标 RECOVERED；
     * 持仓已清零但事件未处理 → 标 CLOSED_POS。
     */
    @Transactional
    public Map<String, Object> scan() {
        List<WatchStock> holdings = watchlistService.list();
        Map<String, QuoteSnapshot> quotes = new HashMap<>();
        List<String> secids = holdings.stream().map(WatchStock::getSecid).filter(s -> s != null && !s.isBlank()).toList();
        if (!secids.isEmpty()) {
            try {
                for (QuoteSnapshot q : quoteClient.fetchQuotes(secids)) {
                    if (q != null && q.code() != null) {
                        quotes.put(q.code(), q);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        int created = 0;
        int recovered = 0;
        int closed = 0;
        Set<String> holdingCodes = holdings.stream()
                .filter(s -> s.getShares() != null && s.getShares() > 0)
                .map(WatchStock::getCode).collect(Collectors.toSet());
        for (WatchStock s : holdings) {
            boolean holding = s.getShares() != null && s.getShares() > 0 && s.getCostPrice() != null && s.getCostPrice() > 0;
            var open = repository.findFirstByCodeAndStatusOrderByIdDesc(s.getCode(), "OPEN").orElse(null);
            QuoteSnapshot q = quotes.get(s.getCode());
            Double price = q == null ? null : q.price();
            if (holding && price != null) {
                double stopLine = s.getCostPrice() * STOP_PCT;
                if (price < stopLine && open == null) {
                    DisciplineEvent ev = new DisciplineEvent();
                    ev.setCode(s.getCode());
                    ev.setName(s.getName());
                    ev.setTriggerDate(LocalDate.now());
                    ev.setTriggerPrice(price);
                    ev.setStopLine(round(stopLine, 3));
                    ev.setCostPrice(s.getCostPrice());
                    repository.save(ev);
                    created++;
                } else if (price >= stopLine && open != null) {
                    finish(open, "RECOVERED", price, "涨回纪律线上方，未执行止损");
                    recovered++;
                }
            }
        }
        // 已清仓但仍 OPEN 的事件 → 清仓了结
        for (DisciplineEvent ev : repository.findByStatusOrderByTriggerDateDesc("OPEN")) {
            if (!holdingCodes.contains(ev.getCode())) {
                QuoteSnapshot q = quotes.get(ev.getCode());
                finish(ev, "CLOSED_POS", q == null ? null : q.price(), "持仓已清零，按清仓了结");
                closed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("recovered", recovered);
        out.put("closed", closed);
        out.put("open", repository.countByStatus("OPEN"));
        return out;
    }

    /** 手动处理：EXECUTED=执行了止损 / IGNORED=选择硬抗 */
    @Transactional
    public DisciplineEvent resolve(Long id, String action, String note) {
        DisciplineEvent ev = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("纪律事件不存在"));
        if (!"OPEN".equals(ev.getStatus())) {
            throw new IllegalStateException("该事件已处理（" + statusLabel(ev.getStatus()) + "）");
        }
        String act = action == null ? "" : action.trim().toUpperCase();
        if (!act.equals("EXECUTED") && !act.equals("IGNORED")) {
            throw new IllegalArgumentException("action 只能是 EXECUTED 或 IGNORED");
        }
        Double price = latestPrice(ev.getCode());
        finish(ev, act, price, note);
        return ev;
    }

    private void finish(DisciplineEvent ev, String status, Double resolvedPrice, String note) {
        ev.setStatus(status);
        ev.setResolvedDate(LocalDate.now());
        if (resolvedPrice != null) {
            ev.setResolvedPrice(resolvedPrice);
            if (ev.getTriggerPrice() != null && ev.getTriggerPrice() > 0) {
                // 正数 = 破线后又跌了多少（硬抗代价）
                ev.setExtraLossPct(round((ev.getTriggerPrice() - resolvedPrice) / ev.getTriggerPrice() * 100, 2));
            }
        }
        if (ev.getTriggerDate() != null) {
            ev.setDaysOpen((int) Math.max(0, ChronoUnit.DAYS.between(ev.getTriggerDate(), LocalDate.now())));
        }
        if (note != null && !note.isBlank()) {
            ev.setNote(note);
        }
        repository.save(ev);
    }

    private Double latestPrice(String code) {
        try {
            var stock = watchlistService.resolve(code);
            List<QuoteSnapshot> qs = quoteClient.fetchQuotes(List.of(stock.getSecid()));
            if (!qs.isEmpty()) {
                return qs.get(0).price();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 纪律统计：执行率 = 执行 / (执行 + 硬抗)，另统计硬抗平均多拖几天、多亏几个点 */
    public Map<String, Object> summary() {
        List<DisciplineEvent> all = list();
        long open = 0, executed = 0, ignored = 0, recovered = 0, closedPos = 0;
        int ignoredDaysSum = 0;
        double ignoredLossSum = 0;
        int ignoredLossN = 0;
        int executedDaysSum = 0;
        for (DisciplineEvent ev : all) {
            switch (ev.getStatus()) {
                case "OPEN" -> open++;
                case "EXECUTED" -> {
                    executed++;
                    if (ev.getDaysOpen() != null) {
                        executedDaysSum += ev.getDaysOpen();
                    }
                }
                case "IGNORED" -> {
                    ignored++;
                    if (ev.getDaysOpen() != null) {
                        ignoredDaysSum += ev.getDaysOpen();
                    }
                    if (ev.getExtraLossPct() != null) {
                        ignoredLossSum += ev.getExtraLossPct();
                        ignoredLossN++;
                    }
                }
                case "RECOVERED" -> recovered++;
                case "CLOSED_POS" -> closedPos++;
                default -> {
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("open", open);
        out.put("executed", executed);
        out.put("ignored", ignored);
        out.put("recovered", recovered);
        out.put("closedPos", closedPos);
        long decided = executed + ignored;
        out.put("executeRate", decided == 0 ? null : round(executed * 100.0 / decided, 1));
        out.put("avgExecutedDays", executed == 0 ? null : round(executedDaysSum * 1.0 / executed, 1));
        out.put("avgIgnoredDays", ignored == 0 ? null : round(ignoredDaysSum * 1.0 / ignored, 1));
        out.put("avgIgnoredExtraLoss", ignoredLossN == 0 ? null : round(ignoredLossSum / ignoredLossN, 2));
        return out;
    }

    public static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "OPEN" -> "待处理";
            case "EXECUTED" -> "已执行止损";
            case "IGNORED" -> "选择硬抗";
            case "RECOVERED" -> "涨回线上";
            case "CLOSED_POS" -> "清仓了结";
            default -> status;
        };
    }

    private static double round(double v, int scale) {
        double p = Math.pow(10, scale);
        return Math.round(v * p) / p;
    }
}
