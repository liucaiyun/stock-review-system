package com.yan.stockreview.service;

import com.yan.stockreview.entity.DailyReview;
import com.yan.stockreview.entity.TradeRecord;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class StatsChartService {

    private final TradeService tradeService;
    private final ReviewService reviewService;
    private final WatchlistService watchlistService;
    private final DisciplineService disciplineService;

    public StatsChartService(TradeService tradeService, ReviewService reviewService,
                             WatchlistService watchlistService, DisciplineService disciplineService) {
        this.tradeService = tradeService;
        this.reviewService = reviewService;
        this.watchlistService = watchlistService;
        this.disciplineService = disciplineService;
    }

    public Map<String, Object> charts() {
        List<TradeRecord> trades = tradeService.list();
        Map<String, Double> monthlyBuy = new TreeMap<>();
        Map<String, Double> monthlySell = new TreeMap<>();
        Map<String, Integer> dirCount = new LinkedHashMap<>();
        dirCount.put("buy", 0);
        dirCount.put("sell", 0);
        double buyAmt = 0;
        double sellAmt = 0;
        for (TradeRecord t : trades) {
            if (t.getTradeDate() == null) {
                continue;
            }
            String month = t.getTradeDate().withDayOfMonth(1).toString().substring(0, 7);
            double amt = t.getAmount() == null ? 0 : t.getAmount();
            if ("buy".equals(t.getDirection())) {
                monthlyBuy.merge(month, amt, Double::sum);
                buyAmt += amt;
                dirCount.merge("buy", 1, Integer::sum);
            } else {
                monthlySell.merge(month, amt, Double::sum);
                sellAmt += amt;
                dirCount.merge("sell", 1, Integer::sum);
            }
        }
        Map<String, List<TradeRecord>> byCode = new LinkedHashMap<>();
        List<TradeRecord> chrono = new ArrayList<>(trades);
        chrono.sort((a, b) -> {
            int c = a.getTradeDate().compareTo(b.getTradeDate());
            return c != 0 ? c : Long.compare(a.getId(), b.getId());
        });
        for (TradeRecord t : chrono) {
            byCode.computeIfAbsent(t.getCode(), k -> new ArrayList<>()).add(t);
        }
        List<Map<String, Object>> stockStats = new ArrayList<>();
        for (Map.Entry<String, List<TradeRecord>> e : byCode.entrySet()) {
            double cost = 0;
            double income = 0;
            String name = e.getValue().get(0).getName();
            for (TradeRecord t : e.getValue()) {
                double amt = t.getAmount() == null ? 0 : t.getAmount();
                if ("buy".equals(t.getDirection())) cost += amt;
                else income += amt;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", e.getKey());
            row.put("name", name);
            row.put("buyAmount", round(cost));
            row.put("sellAmount", round(income));
            row.put("realized", round(income - cost));
            row.put("trades", e.getValue().size());
            stockStats.add(row);
        }
        stockStats.sort((a, b) -> Double.compare((Double) b.get("realized"), (Double) a.get("realized")));

        List<DailyReview> reviews = reviewService.recent();
        Collections.reverse(reviews);
        List<String> reviewDates = new ArrayList<>();
        List<Integer> sentiments = new ArrayList<>();
        Map<String, Integer> mistakeCount = new LinkedHashMap<>();
        for (DailyReview r : reviews) {
            reviewDates.add(r.getReviewDate().toString());
            sentiments.add(r.getSentiment() == null ? 3 : r.getSentiment());
            if (r.getMistakeTags() != null && !r.getMistakeTags().isBlank()) {
                for (String tag : r.getMistakeTags().split(",")) {
                    String t = tag.trim();
                    if (!t.isEmpty()) {
                        mistakeCount.merge(t, 1, Integer::sum);
                    }
                }
            }
        }
        List<Map<String, Object>> mistakes = new ArrayList<>();
        mistakeCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tag", e.getKey());
                    m.put("count", e.getValue());
                    mistakes.add(m);
                });

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tradeCount", trades.size());
        map.put("buyAmount", round(buyAmt));
        map.put("sellAmount", round(sellAmt));
        map.put("dirCount", dirCount);
        map.put("months", mergeMonths(monthlyBuy, monthlySell));
        map.put("stockStats", stockStats.size() > 12 ? stockStats.subList(0, 12) : stockStats);
        map.put("reviewDates", reviewDates);
        map.put("sentiments", sentiments);
        map.put("mistakes", mistakes);
        try {
            map.put("discipline", disciplineService.summary());
        } catch (Exception ignored) {
        }
        var positions = watchlistService.positionOverviewLocal();
        map.put("positionCount", positions.getCount());
        map.put("positionCost", positions.getTotalCost());
        map.put("positionMarket", positions.getTotalMarket());
        map.put("positionPl", positions.getTotalPl());
        map.put("positionPlPct", positions.getTotalPlPct());
        map.put("positions", positions.getItems());
        map.put("from", trades.isEmpty() ? null : chrono.get(0).getTradeDate());
        map.put("to", LocalDate.now());
        return map;
    }

    private List<Map<String, Object>> mergeMonths(Map<String, Double> buy, Map<String, Double> sell) {
        TreeMap<String, Void> months = new TreeMap<>();
        buy.keySet().forEach(m -> months.put(m, null));
        sell.keySet().forEach(m -> months.put(m, null));
        List<Map<String, Object>> list = new ArrayList<>();
        for (String m : months.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m);
            row.put("buy", round(buy.getOrDefault(m, 0.0)));
            row.put("sell", round(sell.getOrDefault(m, 0.0)));
            list.add(row);
        }
        return list;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
