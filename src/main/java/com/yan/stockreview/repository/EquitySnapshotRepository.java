package com.yan.stockreview.repository;

import com.yan.stockreview.entity.EquitySnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquitySnapshotRepository extends JpaRepository<EquitySnapshot, Long> {
    Optional<EquitySnapshot> findBySnapDate(java.time.LocalDate date);
    List<EquitySnapshot> findAllByOrderBySnapDateAsc();
}
