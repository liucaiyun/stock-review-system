package com.yan.stockreview.controller;

import com.yan.stockreview.entity.DisciplineEvent;
import com.yan.stockreview.service.DisciplineService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/discipline")
public class DisciplineController {

    private final DisciplineService disciplineService;

    public DisciplineController(DisciplineService disciplineService) {
        this.disciplineService = disciplineService;
    }

    @GetMapping("/events")
    public List<DisciplineEvent> events() {
        return disciplineService.list();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return disciplineService.summary();
    }

    /** 待处理（硬抗中）事件的实时代价：现价相对破线时又亏/收复多少 */
    @GetMapping("/open-live")
    public List<Map<String, Object>> openLive() {
        return disciplineService.openLive();
    }

    /** 扫描持仓，新建/更新破线事件 */
    @PostMapping("/scan")
    public Map<String, Object> scan() {
        return disciplineService.scan();
    }

    /** 处理事件：action = EXECUTED（执行了止损）/ IGNORED（选择硬抗） */
    @PostMapping("/{id}/resolve")
    public DisciplineEvent resolve(@PathVariable Long id, @RequestBody ResolveRequest req) {
        return disciplineService.resolve(id, req == null ? null : req.action, req == null ? null : req.note);
    }

    public static class ResolveRequest {
        public String action;
        public String note;
    }
}
