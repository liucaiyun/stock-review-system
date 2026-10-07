package com.yan.stockreview.controller;

import com.yan.stockreview.dto.PositionOverview;
import com.yan.stockreview.dto.WatchSaveRequest;
import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.service.InventoryService;
import com.yan.stockreview.service.RiskService;
import com.yan.stockreview.service.WatchlistService;
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
@RequestMapping("/api/positions")
public class PositionController {

    private final WatchlistService watchlistService;
    private final RiskService riskService;
    private final InventoryService inventoryService;

    public PositionController(WatchlistService watchlistService, RiskService riskService,
                              InventoryService inventoryService) {
        this.watchlistService = watchlistService;
        this.riskService = riskService;
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public PositionOverview list(@RequestParam(defaultValue = "false") boolean refresh) {
        return watchlistService.positionOverview(refresh);
    }

    /**
     * 风险与仓位体检：ATR 止损、单笔风险、按风险预算反推的建议股数，以及组合上限的硬性违规。
     *
     * @param capital 账户总资金（含现金）；不传则用最近一次净值快照的总资产
     */
    @GetMapping("/risk")
    public Map<String, Object> risk(@RequestParam(required = false) Double capital,
                                    @RequestParam(required = false) Double maxStockPct,
                                    @RequestParam(required = false) Double maxSectorPct,
                                    @RequestParam(required = false) Integer maxPositions,
                                    @RequestParam(required = false) Double maxRiskPct,
                                    @RequestParam(required = false) Double maxInvestedPct,
                                    @RequestParam(required = false) Double riskPerTradePct,
                                    @RequestParam(defaultValue = "false") boolean refresh) {
        RiskService.Limits def = RiskService.defaultLimits();
        RiskService.Limits limits = new RiskService.Limits(
                maxStockPct == null ? def.maxStockPct() : maxStockPct,
                maxSectorPct == null ? def.maxSectorPct() : maxSectorPct,
                maxPositions == null ? def.maxPositions() : maxPositions,
                maxRiskPct == null ? def.maxRiskPct() : maxRiskPct,
                maxInvestedPct == null ? def.maxInvestedPct() : maxInvestedPct,
                riskPerTradePct == null ? def.riskPerTradePct() : riskPerTradePct);
        return riskService.check(capital, limits, refresh);
    }

    /** 成交流水 FIFO 对照手动持仓 */
    @GetMapping("/reconcile")
    public Map<String, Object> reconcile() {
        return inventoryService.reconcile();
    }

    @PostMapping
    public WatchStock save(@RequestBody WatchSaveRequest body) {
        riskService.assertCanHold(body);
        return watchlistService.upsertPosition(body);
    }

    @PutMapping("/{id}")
    public WatchStock update(@PathVariable Long id, @RequestBody WatchSaveRequest body) {
        body.setId(id);
        riskService.assertCanHold(body);
        return watchlistService.upsertPosition(body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> clear(@PathVariable Long id,
                                      @RequestParam(defaultValue = "false") boolean removeWatch) {
        if (removeWatch) {
            watchlistService.delete(id);
        } else {
            watchlistService.clearPosition(id);
        }
        return Map.of("ok", true);
    }
}
