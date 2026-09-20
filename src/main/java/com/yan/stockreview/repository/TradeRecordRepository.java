package com.yan.stockreview.repository;

import com.yan.stockreview.entity.TradeRecord;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRecordRepository extends JpaRepository<TradeRecord, Long> {
    List<TradeRecord> findAllByOrderByTradeDateDescIdDesc();
    List<TradeRecord> findByCodeOrderByTradeDateAscIdAsc(String code);
    List<TradeRecord> findByTradeDateBetweenOrderByTradeDateAscIdAsc(LocalDate start, LocalDate end);
    boolean existsByTradeDateAndCodeAndDirectionAndSharesAndPrice(LocalDate tradeDate, String code,
                                                                  String direction, Double shares, Double price);
}
