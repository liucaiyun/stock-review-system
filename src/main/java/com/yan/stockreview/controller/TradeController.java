package com.yan.stockreview.controller;

import com.yan.stockreview.dto.TradeSignalCompare;
import com.yan.stockreview.entity.TradeRecord;
import com.yan.stockreview.service.StatsChartService;
import com.yan.stockreview.service.TradeCompareService;
import com.yan.stockreview.service.TradeService;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class TradeController {

    private final TradeService tradeService;
    private final StatsChartService statsChartService;
    private final TradeCompareService tradeCompareService;

    public TradeController(TradeService tradeService, StatsChartService statsChartService,
                           TradeCompareService tradeCompareService) {
        this.tradeService = tradeService;
        this.statsChartService = statsChartService;
        this.tradeCompareService = tradeCompareService;
    }

    @GetMapping("/trades")
    public List<TradeRecord> list() {
        return tradeService.list();
    }

    @GetMapping("/trades/signal-compare")
    public TradeSignalCompare signalCompare() {
        return tradeCompareService.compare();
    }

    @PostMapping("/trades")
    public TradeRecord save(@RequestBody TradeRecord record) {
        return tradeService.save(record);
    }

    @PutMapping("/trades/{id}")
    public TradeRecord update(@PathVariable Long id, @RequestBody TradeRecord record) {
        return tradeService.update(id, record);
    }

    @DeleteMapping("/trades/{id}")
    public Map<String, Boolean> delete(@PathVariable Long id) {
        tradeService.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/trades/import-ths")
    public Map<String, Object> importThs(@RequestParam("file") MultipartFile file) {
        return tradeService.importThsCsv(file);
    }

    @GetMapping("/stats/charts")
    public Map<String, Object> charts() {
        return statsChartService.charts();
    }
}
