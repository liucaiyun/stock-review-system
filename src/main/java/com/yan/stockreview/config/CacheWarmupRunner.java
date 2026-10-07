package com.yan.stockreview.config;

import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.service.BenchmarkService;
import com.yan.stockreview.service.WatchlistService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动后后台预热行情缓存。
 *
 * <p>解决冷启动问题：重启后 K 线缓存为空，首个访问
 * {@code /api/strategy/watchlist-signals}（今日复盘/自选信号）的请求要现场拉
 * 最多 4 只股票的日 K，每只失败还要依次试东财→腾讯→新浪三个源，
 * 网络差时前端 15 秒超时反复报错，看起来像"接口一直不返回"。
 *
 * <p>预热完成后，自选信号、今日复盘等接口首次调用就是热数据，秒回。
 */
@Component
public class CacheWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupRunner.class);

    private final QuoteClient quoteClient;
    private final WatchlistService watchlistService;
    private final BenchmarkService benchmarkService;

    public CacheWarmupRunner(QuoteClient quoteClient, WatchlistService watchlistService,
                             BenchmarkService benchmarkService) {
        this.quoteClient = quoteClient;
        this.watchlistService = watchlistService;
        this.benchmarkService = benchmarkService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(this::warmup, "kline-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    private void warmup() {
        // 等应用完全起起来再开始，避免和启动期请求抢 HTTP 信号量
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // 指数先预热，今日复盘市场快照才不会和 K 线抢通道
        try {
            quoteClient.fetchIndices();
            log.info("预热：指数快照就绪");
        } catch (Exception ex) {
            log.warn("预热：指数拉取失败（{}），首次打开今日复盘会再试", ex.getMessage());
        }
        // 现价批量预热：统计图用缓存现价算市值，现价缓存只有 20 秒，启动时拉一次
        try {
            List<String> secids = new ArrayList<>();
            for (WatchStock s : watchlistService.list()) {
                if (s.getSecid() != null && !s.getSecid().isBlank()) {
                    secids.add(s.getSecid());
                }
            }
            if (!secids.isEmpty()) {
                quoteClient.fetchQuotes(secids);
                log.info("预热：现价 {} 只就绪", secids.size());
            }
        } catch (Exception ex) {
            log.warn("预热：现价拉取失败（{}），统计图会改用日 K 收盘价", ex.getMessage());
        }
        // 沪深300 基准（净值曲线、相对强弱都要用）
        try {
            benchmarkService.bars();
            log.info("预热：沪深300 基准就绪");
        } catch (Exception ex) {
            log.warn("预热：沪深300 拉取失败（{}），首次访问净值相关页面会再试", ex.getMessage());
        }
        for (String[] idx : new String[][] {{"1.000001", "上证"}, {"0.399006", "创业板"}}) {
            try {
                quoteClient.fetchKline(idx[0], 180);
                log.info("预热：{} 日K就绪", idx[1]);
            } catch (Exception ex) {
                log.warn("预热：{} 日K失败（{}），风格对照可能先空着", idx[1], ex.getMessage());
            }
        }
        // 全部自选/持仓的日 K
        int ok = 0;
        int fail = 0;
        long start = System.currentTimeMillis();
        for (WatchStock s : watchlistService.list()) {
            if (s.getSecid() == null || s.getSecid().isBlank()) {
                continue;
            }
            try {
                quoteClient.fetchKline(s.getSecid(), 250);
                ok++;
                Thread.sleep(120);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("预热被中断：K线成功 {} 只", ok);
                return;
            } catch (Exception ex) {
                fail++;
                log.warn("预热：{} {} K线拉取失败（{}），首次访问会再试", s.getCode(), s.getName(), ex.getMessage());
            }
        }
        log.info("预热完成：K线成功 {} 只、失败 {} 只，耗时 {} ms",
                ok, fail, System.currentTimeMillis() - start);
    }
}
