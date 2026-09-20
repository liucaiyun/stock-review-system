package com.yan.stockreview.repository;

import com.yan.stockreview.entity.ReviewItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {
    List<ReviewItem> findByReviewIdOrderByIdAsc(Long reviewId);

    @Modifying
    void deleteByReviewId(Long reviewId);
}
