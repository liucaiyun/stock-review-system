package com.yan.stockreview.repository;

import com.yan.stockreview.entity.TradePlan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradePlanRepository extends JpaRepository<TradePlan, Long> {
    Optional<TradePlan> findFirstByCodeAndStatusOrderByIdDesc(String code, String status);
    List<TradePlan> findByStatusOrderByPlanDateDescIdDesc(String status);
    List<TradePlan> findAllByOrderByPlanDateDescIdDesc();
    List<TradePlan> findByCodeOrderByPlanDateDescIdDesc(String code);
}
