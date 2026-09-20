package com.yan.stockreview.controller;

import com.yan.stockreview.dto.PlanView;
import com.yan.stockreview.entity.TradePlan;
import com.yan.stockreview.service.TradePlanService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plans")
public class TradePlanController {

    private final TradePlanService tradePlanService;

    public TradePlanController(TradePlanService tradePlanService) {
        this.tradePlanService = tradePlanService;
    }

    @GetMapping
    public List<PlanView> list(@RequestParam(defaultValue = "OPEN") String status) {
        return tradePlanService.list(status);
    }

    @GetMapping("/{id}")
    public PlanView get(@PathVariable Long id) {
        return tradePlanService.get(id);
    }

    @PostMapping
    public TradePlan save(@RequestBody TradePlan plan) {
        return tradePlanService.save(plan);
    }

    @PutMapping("/{id}")
    public TradePlan update(@PathVariable Long id, @RequestBody TradePlan plan) {
        plan.setId(id);
        return tradePlanService.save(plan);
    }

    @PostMapping("/{id}/close")
    public TradePlan close(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return tradePlanService.close(id, note);
    }

    @PostMapping("/close-code")
    public TradePlan closeByCode(@RequestParam String code,
                                 @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return tradePlanService.closeByCode(code, note);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable Long id) {
        tradePlanService.delete(id);
        return Map.of("ok", true);
    }
}
