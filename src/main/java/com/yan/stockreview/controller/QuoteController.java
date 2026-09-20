package com.yan.stockreview.controller;

import com.yan.stockreview.service.StrategyService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QuoteController {

    private final StrategyService strategyService;

    public QuoteController(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    @GetMapping("/quotes/search")
    public Object search(@RequestParam String q) {
        return strategyService.search(q);
    }

    @GetMapping("/quotes/indices")
    public Object indices() {
        return strategyService.indices();
    }

    @GetMapping("/strategy/catalog")
    public List<Map<String, Object>> catalog() {
        return strategyService.catalog();
    }

    @GetMapping("/strategy/analyze")
    public Map<String, Object> analyze(@RequestParam String code,
                                       @RequestParam(defaultValue = "250") int limit,
                                       @RequestParam(required = false) String strategies,
                                       @RequestParam(required = false) String asOf) {
        return strategyService.analyze(code, limit, strategies, asOf);
    }

    @GetMapping("/today/digest")
    public Map<String, Object> todayDigest() {
        return strategyService.todayDigest();
    }

    @GetMapping("/strategy/watchlist-signals")
    public List<Map<String, Object>> watchlistSignals() {
        return strategyService.watchlistSignals();
    }

    @GetMapping("/strategy/overview")
    public Map<String, Object> overview() {
        return strategyService.overviewStats();
    }

    @GetMapping("/boards")
    public Object boards(@RequestParam String code) {
        return strategyService.boardOf(code);
    }

    @GetMapping("/relative-strength")
    public Object relativeStrength(@RequestParam String code) {
        return strategyService.relativeStrength(code);
    }

    @GetMapping("/quotes/tt-levels")
    public Object ttLevels(@RequestParam String code) {
        return strategyService.ttLevels(code);
    }
}
