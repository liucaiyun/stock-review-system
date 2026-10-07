package com.yan.stockreview;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 股票复盘系统入口。启动后访问 http://localhost:8088
 */
@SpringBootApplication
@EnableScheduling
public class StockReviewApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(StockReviewApplication.class, args);
    }
}
