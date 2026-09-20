package com.yan.stockreview.controller;

import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.dto.WatchSaveRequest;
import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.service.WatchlistService;
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
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    public List<WatchStock> list() {
        return watchlistService.list();
    }

    @GetMapping("/quotes")
    public List<QuoteSnapshot> quotes() {
        return watchlistService.quotesForWatchlist();
    }

    @PostMapping
    public WatchStock add(@RequestBody WatchSaveRequest body) {
        return watchlistService.add(body);
    }

    @PutMapping("/{id}")
    public WatchStock update(@PathVariable Long id, @RequestBody WatchSaveRequest body) {
        return watchlistService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable Long id) {
        watchlistService.delete(id);
        return Map.of("ok", true);
    }

    @GetMapping("/search")
    public Object search(@RequestParam String q) {
        return watchlistService.resolve(q);
    }

    @GetMapping("/boards")
    public Map<String, Object> boards() {
        return watchlistService.boardView();
    }
}
