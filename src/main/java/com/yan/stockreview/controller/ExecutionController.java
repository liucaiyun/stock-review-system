package com.yan.stockreview.controller;

import com.yan.stockreview.service.ExecutionService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/execution")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /** 执行偏差报表：计划→成交闭环、违规代价（元）、按计划 vs 违规的期望 R、纪律事件与真实成交对账 */
    @GetMapping("/report")
    public Map<String, Object> report() {
        return executionService.report();
    }
}
