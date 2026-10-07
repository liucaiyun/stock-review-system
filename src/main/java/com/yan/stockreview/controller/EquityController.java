package com.yan.stockreview.controller;

import com.yan.stockreview.entity.EquitySnapshot;
import com.yan.stockreview.service.EquityService;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equity")
public class EquityController {

    private final EquityService equityService;

    public EquityController(EquityService equityService) {
        this.equityService = equityService;
    }

    /** 记一笔今日净值：totalAsset=账户总资产（含现金），cashFlow=当日出入金（正=入金，负=出金） */
    @PostMapping("/snapshot")
    public EquitySnapshot snapshot(@RequestBody SnapshotRequest req) {
        return equityService.snapshot(req == null ? null : req.totalAsset,
                req == null ? null : req.cashFlow, req == null ? null : req.note);
    }

    /** 净值曲线（剔除出入金）+ 最大回撤 + 对比沪深300 + 超额/年化/夏普 */
    @GetMapping("/curve")
    public Map<String, Object> curve() {
        return equityService.curve();
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable Long id) {
        equityService.delete(id);
        return Map.of("ok", true);
    }

    public static class SnapshotRequest {
        public Double totalAsset;
        public Double cashFlow;
        public String note;
    }
}
