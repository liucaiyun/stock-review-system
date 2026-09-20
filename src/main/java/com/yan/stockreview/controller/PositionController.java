package com.yan.stockreview.controller;

import com.yan.stockreview.dto.PositionOverview;
import com.yan.stockreview.dto.WatchSaveRequest;
import com.yan.stockreview.entity.WatchStock;
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

    public PositionController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    public PositionOverview list(@RequestParam(defaultValue = "false") boolean refresh) {
        return watchlistService.positionOverview(refresh);
    }

    @PostMapping
    public WatchStock save(@RequestBody WatchSaveRequest body) {
        return watchlistService.upsertPosition(body);
    }

    @PutMapping("/{id}")
    public WatchStock update(@PathVariable Long id, @RequestBody WatchSaveRequest body) {
        body.setId(id);
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
