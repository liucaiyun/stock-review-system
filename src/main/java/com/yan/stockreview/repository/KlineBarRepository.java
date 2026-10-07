package com.yan.stockreview.repository;

import com.yan.stockreview.entity.KlineBarEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KlineBarRepository extends JpaRepository<KlineBarEntity, Long> {
    List<KlineBarEntity> findBySecidOrderByBarDateAsc(String secid);
    Optional<KlineBarEntity> findBySecidAndBarDate(String secid, String barDate);
}
