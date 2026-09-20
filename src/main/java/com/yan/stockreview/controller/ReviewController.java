package com.yan.stockreview.controller;

import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.entity.DailyReview;
import com.yan.stockreview.entity.ReviewItem;
import com.yan.stockreview.service.ReviewService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public Map<String, Object> get(@RequestParam(required = false) String date) {
        LocalDate d = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return reviewService.getByDate(d);
    }

    @GetMapping("/recent")
    public List<DailyReview> recent() {
        return reviewService.recent();
    }

    @PostMapping
    public DailyReview save(@RequestBody SaveRequest req) {
        LocalDate date = req.date == null || req.date.isBlank() ? LocalDate.now() : LocalDate.parse(req.date);
        return reviewService.save(date, req.marketBias, req.sentiment, req.content, req.indices, req.items,
                req.mistakeTags);
    }

    public static class SaveRequest {
        public String date;
        public String marketBias;
        public Integer sentiment;
        public String content;
        public List<QuoteSnapshot> indices;
        public List<ReviewItem> items;
        public List<String> mistakeTags;
    }
}
