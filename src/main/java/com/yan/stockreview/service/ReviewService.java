package com.yan.stockreview.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.entity.DailyReview;
import com.yan.stockreview.entity.ReviewItem;
import com.yan.stockreview.repository.DailyReviewRepository;
import com.yan.stockreview.repository.ReviewItemRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    /** 错题本预设标签 */
    public static final List<String> MISTAKE_TAGS = List.of(
            "追高", "不止损", "分歧日买入", "无计划交易", "重仓单票", "频繁交易");

    private final DailyReviewRepository reviewRepository;
    private final ReviewItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    public ReviewService(DailyReviewRepository reviewRepository,
                         ReviewItemRepository itemRepository,
                         ObjectMapper objectMapper) {
        this.reviewRepository = reviewRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getByDate(LocalDate date) {
        DailyReview review = reviewRepository.findByReviewDate(date).orElse(null);
        Map<String, Object> map = new HashMap<>();
        map.put("review", review);
        map.put("items", review == null ? List.of() : itemRepository.findByReviewIdOrderByIdAsc(review.getId()));
        return map;
    }

    public List<DailyReview> recent() {
        return reviewRepository.findTop60ByOrderByReviewDateDesc();
    }

    @Transactional
    public DailyReview save(LocalDate date, String marketBias, Integer sentiment, String content,
                            List<QuoteSnapshot> indices, List<ReviewItem> items) {
        return save(date, marketBias, sentiment, content, indices, items, null);
    }

    @Transactional
    public DailyReview save(LocalDate date, String marketBias, Integer sentiment, String content,
                            List<QuoteSnapshot> indices, List<ReviewItem> items, List<String> mistakeTags) {
        DailyReview review = reviewRepository.findByReviewDate(date).orElseGet(DailyReview::new);
        review.setReviewDate(date);
        review.setMarketBias(marketBias);
        review.setSentiment(sentiment);
        review.setContent(content);
        if (mistakeTags != null) {
            String joined = mistakeTags.stream()
                    .filter(t -> t != null && MISTAKE_TAGS.contains(t.trim()))
                    .map(String::trim)
                    .distinct()
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
            review.setMistakeTags(joined);
        }
        if (indices != null) {
            try {
                review.setIndexSnapshot(objectMapper.writeValueAsString(indices));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("保存指数快照失败", e);
            }
        }
        DailyReview saved = reviewRepository.save(review);
        itemRepository.deleteByReviewId(saved.getId());
        if (items != null) {
            for (ReviewItem item : items) {
                if (item.getCode() == null || item.getCode().isBlank()) {
                    continue;
                }
                item.setId(null);
                item.setReviewId(saved.getId());
                item.setItemDate(date);
                itemRepository.save(item);
            }
        }
        return saved;
    }
}
