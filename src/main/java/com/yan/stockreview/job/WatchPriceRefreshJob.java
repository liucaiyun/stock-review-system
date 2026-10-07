package com.yan.stockreview.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.service.WatchlistService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时刷新自选/持仓现价。
 *
 * <p>本机默认走 Spring 定时（工作日 9:00），不依赖 xxl-job-admin。
 * 若 {@code xxl.job.enabled=true}，改由调度中心触发，本机 cron 不再跑，避免刷两遍。
 */
@Component
public class WatchPriceRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(WatchPriceRefreshJob.class);

    private final WatchlistService watchlistService;
    private final QuoteClient quoteClient;

    @Value("${xxl.job.enabled:false}")
    private boolean xxlEnabled;

    public WatchPriceRefreshJob(WatchlistService watchlistService, QuoteClient quoteClient) {
        this.watchlistService = watchlistService;
        this.quoteClient = quoteClient;
    }

    /** 调度中心 JobHandler 填类名 WatchPriceRefreshJob（大小写必须一致）。 */
    @XxlJob("WatchPriceRefreshJob")
    public void watchPriceRefreshJob() {
        runForXxl();
    }

    /** 兼容文档里的小写名，两种填法都能触发。 */
    @XxlJob("watchPriceRefreshJob")
    public void watchPriceRefreshJobAlias() {
        runForXxl();
    }

    private void runForXxl() {
        Result result = refreshQuotes();
        if (result.noWatchlist()) {
            XxlJobHelper.log("自选为空，无需刷新");
            XxlJobHelper.handleSuccess();
            return;
        }
        XxlJobHelper.log(result.summary());
        if (result.ok() == 0) {
            XxlJobHelper.handleFail("一只都没刷到，行情源可能不可用");
        } else {
            XxlJobHelper.handleSuccess();
        }
    }

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Shanghai")
    public void scheduledMorningRefresh() {
        if (xxlEnabled) {
            return;
        }
        refreshQuotes();
    }

    private Result refreshQuotes() {
        List<WatchStock> stocks = watchlistService.list();
        List<String> secids = stocks.stream()
                .map(WatchStock::getSecid)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        if (secids.isEmpty()) {
            log.info("自选为空，无需刷新现价");
            return Result.noWatchlistResult();
        }
        try {
            List<QuoteSnapshot> quotes = quoteClient.fetchQuotes(secids, true);
            int ok = 0;
            for (QuoteSnapshot q : quotes) {
                if (q != null && q.code() != null && q.price() != null) {
                    ok++;
                }
            }
            String summary = "自选现价刷新完成：成功 " + ok + " / 总 " + secids.size();
            log.info("{}", summary);
            return new Result(false, ok, summary);
        } catch (Exception ex) {
            log.warn("自选现价刷新失败：{}", ex.getMessage());
            return new Result(false, 0, "刷新失败：" + ex.getMessage());
        }
    }

    private record Result(boolean noWatchlist, int ok, String summary) {
        static Result noWatchlistResult() {
            return new Result(true, 0, "");
        }
    }
}
