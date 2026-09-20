package com.yan.stockreview.repository;

import com.yan.stockreview.entity.DisciplineEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisciplineEventRepository extends JpaRepository<DisciplineEvent, Long> {
    Optional<DisciplineEvent> findFirstByCodeAndStatusOrderByIdDesc(String code, String status);
    List<DisciplineEvent> findByStatusOrderByTriggerDateDesc(String status);
    List<DisciplineEvent> findAllByOrderByTriggerDateDescIdDesc();
    long countByStatus(String status);
}
