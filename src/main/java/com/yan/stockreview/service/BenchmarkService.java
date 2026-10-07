package com.yan.stockreview.service;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.market.QuoteClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 基准（沪深300）行情。
 *
 * <p>修掉两个老问题：
 * <ol>
 *   <li>以前净值曲线用 {@code peekKline}（只读缓存）取沪深300，但全项目没有地方为它预取过日 K，
 *       所以基准永远是空的、还静默不报错；这里改成真正拉取并缓存。</li>
 *   <li>以前基准的「起点」用之后最近交易日、「其余点」用之前最近交易日，两套口径混用；这里统一成
 *       「不晚于该日期的最近一个交易日」。</li>
 * </ol>
 * 失败时保留上一次成功的数据，并把错误信息暴露给前端，不再静默变成空白。
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    public static final String BENCH_SECID = "1.000300";
    public static final String BENCH_NAME = "沪深300";

    /** 指数日 K 变化很慢，30 分钟足够 */
    private static final long TTL_MS = 30 * 60_000L;

    private final QuoteClient quoteClient;

    private volatile List<KlineBar> cached = List.of();
    private volatile long cachedAt = 0;
    private volatile String lastError;

    public BenchmarkService(QuoteClient quoteClient) {
        this.quoteClient = quoteClient;
    }

    /** 沪深300 日 K（升序）。拉取失败时返回上一次成功的数据（可能为空）。 */
    public List<KlineBar> bars() {
        long now = System.currentTimeMillis();
        List<KlineBar> current = cached;
        if (!current.isEmpty() && now - cachedAt < TTL_MS) {
            return current;
        }
        try {
            List<KlineBar> fresh = quoteClient.fetchKline(BENCH_SECID, 500);
            if (fresh != null && !fresh.isEmpty()) {
                cached = List.copyOf(fresh);
                cachedAt = now;
                lastError = null;
                return cached;
            }
            lastError = "基准行情返回空";
        } catch (Exception ex) {
            lastError = ex.getMessage();
            log.warn("沪深300 行情拉取失败，仍用上一次成功的数据（{} 根）：{}", current.size(), ex.getMessage());
        }
        return current;
    }

    /** 最近一次拉取失败的原因；成功时为 null */
    public String lastError() {
        return cached.isEmpty() ? lastError : null;
    }

    public boolean available() {
        return cachedAvailable();
    }

    /** 不触发网络：给统计总览这种「不能卡住」的接口用。 */
    public boolean cachedAvailable() {
        return cached != null && !cached.isEmpty();
    }

    /** 日期 → 收盘价 */
    public Map<String, Double> closesByDate() {
        return closesOf(bars());
    }

    /** 只读已缓存的基准收盘，不现场拉行情。 */
    public Map<String, Double> cachedClosesByDate() {
        return closesOf(cached == null ? List.of() : cached);
    }

    private static Map<String, Double> closesOf(List<KlineBar> bars) {
        Map<String, Double> map = new LinkedHashMap<>();
        if (bars == null) {
            return map;
        }
        for (KlineBar b : bars) {
            if (b != null && b.date() != null) {
                map.put(b.date(), b.close());
            }
        }
        return map;
    }

    /** 不晚于该日期的最近一个交易日收盘；取不到返回 null（全项目统一用这一条口径） */
    public Double closeOnOrBefore(String date) {
        TreeMap<String, Double> map = new TreeMap<>(closesByDate());
        Map.Entry<String, Double> e = date == null ? null : map.floorEntry(date);
        return e == null ? null : e.getValue();
    }

    /** 从 fromDate 到 toDate 的涨跌幅（%）；取不到返回 null */
    public Double changePct(String fromDate, String toDate) {
        Double from = closeOnOrBefore(fromDate);
        Double to = closeOnOrBefore(toDate);
        if (from == null || to == null || from <= 0) {
            return null;
        }
        return (to / from - 1) * 100;
    }
}
