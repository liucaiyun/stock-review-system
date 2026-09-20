package com.yan.stockreview.repository;

import com.yan.stockreview.entity.WatchStock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchStockRepository extends JpaRepository<WatchStock, Long> {
    Optional<WatchStock> findByCode(String code);
    List<WatchStock> findAllByOrderBySortOrderAscIdAsc();
}
