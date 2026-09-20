package com.yan.stockreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 股票复盘系统入口。启动后访问 http://localhost:8088
 */
@SpringBootApplication
public class StockReviewApplication {
    public static void main(String[] args) {
        SpringApplication.run(StockReviewApplication.class, args);
    }
}
