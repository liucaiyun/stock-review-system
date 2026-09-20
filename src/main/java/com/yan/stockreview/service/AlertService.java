package com.yan.stockreview.service;

import com.yan.stockreview.dto.PlanView;
import com.yan.stockreview.dto.PositionOverview;
import com.yan.stockreview.dto.PositionRow;
import com.yan.stockreview.entity.DisciplineEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 到价提醒汇总：持仓破纪律线、计划触及止损/目标/超期、待处理纪律事件 */
@Service
public class AlertService {

    private final WatchlistService watchlistService;
    private final TradePlanService tradePlanService;
    private final DisciplineService disciplineService;

    public AlertService(WatchlistService watchlistService, TradePlanService tradePlanService,
                        DisciplineService disciplineService) {
        this.watchlistService = watchlistService;
        this.tradePlanService = tradePlanService;
        this.disciplineService = disciplineService;
    }

    public Map<String, Object> alerts() {
        List<Map<String, Object>> items = new ArrayList<>();
        // 1. 持仓破 -8% 纪律线
        try {
            PositionOverview overview = watchlistService.positionOverviewLocal();
            for (PositionRow row : overview.getItems()) {
                if (Boolean.TRUE.equals(row.getStopBroken())) {
                    items.add(alert("DISCIPLINE", row.getCode(), row.getName(),
                            (row.getName() == null ? row.getCode() : row.getName())
                                    + " 已跌破 -8% 纪律线（现价 " + row.getPrice() + "，纪律线 " + row.getStopLine() + "）",
                            "position", "bad"));
                }
            }
        } catch (Exception ignored) {
        }
        // 2. 计划触及止损/目标/超期
        try {
            for (PlanView p : tradePlanService.list("OPEN", false)) {
                String name = p.getName() == null ? p.getCode() : p.getName();
                if (p.isHitStop()) {
                    items.add(alert("PLAN_STOP", p.getCode(), p.getName(),
                            name + " 触及计划止损价 " + p.getStopPrice(), "plans", "bad"));
                } else if (p.isHitTarget()) {
                    items.add(alert("PLAN_TARGET", p.getCode(), p.getName(),
                            name + " 达到计划目标价 " + p.getTargetPrice() + "，按计划复盘止盈", "plans", "ok"));
                } else if (p.isOverdue()) {
                    items.add(alert("PLAN_OVERDUE", p.getCode(), p.getName(),
                            name + " 已超过计划持有天数（" + p.getHeldDays() + "/" + p.getHoldDays() + " 天）", "plans", "warn"));
                }
            }
        } catch (Exception ignored) {
        }
        // 3. 待处理的纪律事件（破线后还没决定执行还是硬抗）
        try {
            for (DisciplineEvent ev : disciplineService.openEvents()) {
                String name = ev.getName() == null ? ev.getCode() : ev.getName();
                items.add(alert("EVENT_OPEN", ev.getCode(), ev.getName(),
                        name + " 破线事件待处理（" + ev.getTriggerDate() + " 破线），请决定执行止损或标记硬抗",
                        "position", "warn"));
            }
        } catch (Exception ignored) {
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("items", items);
        return out;
    }

    private static Map<String, Object> alert(String type, String code, String name,
                                             String message, String page, String level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("code", code);
        m.put("name", name);
        m.put("message", message);
        m.put("page", page);
        m.put("level", level); // bad 红 / warn 黄 / ok 绿
        return m;
    }
}
