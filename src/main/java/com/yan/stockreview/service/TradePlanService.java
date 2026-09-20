package com.yan.stockreview.service;

import com.yan.stockreview.dto.PlanView;
import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.dto.RelativeStrength;
import com.yan.stockreview.entity.TradePlan;
import com.yan.stockreview.market.MarketCodeUtil;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.repository.TradePlanRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradePlanService {

    private final TradePlanRepository tradePlanRepository;
    private final QuoteClient quoteClient;
    private final WatchlistService watchlistService;

    public TradePlanService(TradePlanRepository tradePlanRepository, QuoteClient quoteClient,
                            WatchlistService watchlistService) {
        this.tradePlanRepository = tradePlanRepository;
        this.quoteClient = quoteClient;
        this.watchlistService = watchlistService;
    }

    public List<PlanView> list(String status) {
        return list(status, true);
    }

    public List<PlanView> list(String status, boolean network) {
        List<TradePlan> plans;
        if (status == null || status.isBlank() || "OPEN".equalsIgnoreCase(status)) {
            plans = tradePlanRepository.findByStatusOrderByPlanDateDescIdDesc("OPEN");
        } else if ("ALL".equalsIgnoreCase(status)) {
            plans = tradePlanRepository.findAllByOrderByPlanDateDescIdDesc();
        } else {
            plans = tradePlanRepository.findByStatusOrderByPlanDateDescIdDesc(status.toUpperCase(Locale.ROOT));
        }
        List<PlanView> views = new ArrayList<>();
        for (TradePlan p : plans) {
            views.add(toView(p, network));
        }
        return views;
    }

    public PlanView get(Long id) {
        TradePlan plan = tradePlanRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        return toView(plan, true);
    }

    @Transactional
    public TradePlan save(TradePlan incoming) {
        if (incoming.getCode() == null || incoming.getCode().isBlank()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        var parsed = MarketCodeUtil.parse(incoming.getCode());
        incoming.setCode(parsed.code());
        if (incoming.getPlanDate() == null) {
            incoming.setPlanDate(LocalDate.now());
        }
        if (incoming.getStopPrice() != null && incoming.getTargetPrice() != null
                && incoming.getStopPrice() >= incoming.getTargetPrice()) {
            throw new IllegalArgumentException("止损价应低于目标价");
        }
        if (incoming.getHoldDays() != null && incoming.getHoldDays() <= 0) {
            throw new IllegalArgumentException("计划持有天数应大于 0");
        }
        if (incoming.getName() == null || incoming.getName().isBlank()) {
            try {
                var stock = watchlistService.resolve(parsed.code());
                incoming.setName(stock.getName());
            } catch (Exception ex) {
                incoming.setName(parsed.code());
            }
        }
        if (incoming.getId() != null) {
            TradePlan exist = tradePlanRepository.findById(incoming.getId())
                    .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
            incoming.setCreatedAt(exist.getCreatedAt());
            if (incoming.getStatus() == null) {
                incoming.setStatus(exist.getStatus());
            }
            return tradePlanRepository.save(incoming);
        }
        TradePlan open = tradePlanRepository.findFirstByCodeAndStatusOrderByIdDesc(parsed.code(), "OPEN").orElse(null);
        if (open != null) {
            incoming.setId(open.getId());
            incoming.setCreatedAt(open.getCreatedAt());
            incoming.setStatus("OPEN");
            incoming.setBuyTradeId(incoming.getBuyTradeId() != null ? incoming.getBuyTradeId() : open.getBuyTradeId());
            return tradePlanRepository.save(incoming);
        }
        incoming.setStatus("OPEN");
        return tradePlanRepository.save(incoming);
    }

    @Transactional
    public TradePlan close(Long id, String note) {
        TradePlan plan = tradePlanRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在"));
        plan.setStatus("CLOSED");
        plan.setClosedOn(LocalDate.now());
        plan.setCloseNote(note);
        return tradePlanRepository.save(plan);
    }

    @Transactional
    public TradePlan closeByCode(String code, String note) {
        var parsed = MarketCodeUtil.parse(code);
        TradePlan plan = tradePlanRepository.findFirstByCodeAndStatusOrderByIdDesc(parsed.code(), "OPEN")
                .orElseThrow(() -> new IllegalArgumentException(parsed.code() + " 没有进行中的计划"));
        return close(plan.getId(), note);
    }

    @Transactional
    public void delete(Long id) {
        tradePlanRepository.deleteById(id);
    }

    public TradePlan openOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return tradePlanRepository.findFirstByCodeAndStatusOrderByIdDesc(
                    MarketCodeUtil.parse(code).code(), "OPEN").orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    public PlanView toView(TradePlan plan) {
        return toView(plan, true);
    }

    public PlanView toView(TradePlan plan, boolean network) {
        PlanView view = new PlanView();
        view.setId(plan.getId());
        view.setCode(plan.getCode());
        view.setName(plan.getName());
        view.setPlanDate(plan.getPlanDate());
        view.setReason(plan.getReason());
        view.setPlanPrice(plan.getPlanPrice());
        view.setStopPrice(plan.getStopPrice());
        view.setTargetPrice(plan.getTargetPrice());
        view.setHoldDays(plan.getHoldDays());
        view.setStatus(plan.getStatus());
        view.setClosedOn(plan.getClosedOn());
        view.setCloseNote(plan.getCloseNote());
        LocalDate end = "OPEN".equals(plan.getStatus()) ? LocalDate.now()
                : (plan.getClosedOn() != null ? plan.getClosedOn() : LocalDate.now());
        if (plan.getPlanDate() != null) {
            view.setHeldDays((int) Math.max(0, ChronoUnit.DAYS.between(plan.getPlanDate(), end)));
        }
        try {
            var stock = watchlistService.findLocal(plan.getCode());
            if (stock == null && network) {
                stock = watchlistService.resolve(plan.getCode());
            }
            if (stock != null) {
                if (view.getName() == null || view.getName().isBlank()) {
                    view.setName(stock.getName());
                }
                List<QuoteSnapshot> quotes = network
                        ? quoteClient.fetchQuotes(List.of(stock.getSecid()))
                        : quoteClient.cachedQuotes(List.of(stock.getSecid()));
                if (!quotes.isEmpty()) {
                    QuoteSnapshot q = quotes.get(0);
                    view.setPrice(q.price());
                    view.setPctChange(q.pctChange());
                }
                if (network) {
                    view.setStrength(quoteClient.relativeStrength(stock.getCode(), stock.getMarket(), stock.getSecid(),
                            view.getPctChange()));
                    if (view.getStrength() != null) {
                        view.setBoardPath(view.getStrength().boardPath());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        fillFlags(view, plan);
        return view;
    }

    private void fillFlags(PlanView view, TradePlan plan) {
        Double price = view.getPrice();
        if (price != null && plan.getStopPrice() != null && plan.getStopPrice() > 0) {
            view.setStopDistancePct(round2((price - plan.getStopPrice()) / plan.getStopPrice() * 100));
            view.setHitStop(price <= plan.getStopPrice());
        }
        if (price != null && plan.getTargetPrice() != null && plan.getTargetPrice() > 0) {
            view.setTargetDistancePct(round2((plan.getTargetPrice() - price) / price * 100));
            view.setHitTarget(price >= plan.getTargetPrice());
        }
        view.setOverdue("OPEN".equals(plan.getStatus())
                && plan.getHoldDays() != null && view.getHeldDays() != null
                && view.getHeldDays() > plan.getHoldDays());
        if (!"OPEN".equals(plan.getStatus())) {
            view.setFlag("CLOSED");
            view.setFlagLabel("已结束");
        } else if (view.isHitStop()) {
            view.setFlag("STOP");
            view.setFlagLabel("触及止损");
        } else if (view.isHitTarget()) {
            view.setFlag("TARGET");
            view.setFlagLabel("触及目标");
        } else if (view.isOverdue()) {
            view.setFlag("OVERDUE");
            view.setFlagLabel("超过计划天数");
        } else {
            view.setFlag("ON");
            view.setFlagLabel("进行中");
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
