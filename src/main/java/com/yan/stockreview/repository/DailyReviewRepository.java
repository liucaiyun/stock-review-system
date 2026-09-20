package com.yan.stockreview.repository;

import com.yan.stockreview.entity.DailyReview;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyReviewRepository extends JpaRepository<DailyReview, Long> {
    Optional<DailyReview> findByReviewDate(LocalDate reviewDate);
    List<DailyReview> findTop60ByOrderByReviewDateDesc();
}
