package com.yan.stockreview.service;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.strategy.MarketRegime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 周期和环境汇总：主看沪深300 日 K（已有基准缓存），辅看上证/创业板近20日风格差。
 */
@Service
public class MarketRegimeService {

    private static final String SH_SECID = "1.000001";
    private static final String CYB_SECID = "0.399006";

    private final BenchmarkService benchmarkService;
    private final QuoteClient quoteClient;

    public MarketRegimeService(BenchmarkService benchmarkService, QuoteClient quoteClient) {
        this.benchmarkService = benchmarkService;
        this.quoteClient = quoteClient;
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<KlineBar> hs300 = benchmarkService.bars();
        MarketRegime.Snapshot snap = MarketRegime.of(hs300);
        out.put("index", BenchmarkService.BENCH_NAME);
        out.put("secid", BenchmarkService.BENCH_SECID);
        if (snap == null) {
            out.put("ok", false);
            out.put("error", hs300 == null || hs300.isEmpty()
                    ? (benchmarkService.lastError() == null ? "沪深300 日K还没拉到" : benchmarkService.lastError())
                    : "沪深300 日K不足 21 根，算不出均线环境");
            out.put("disclaimer", "对照用，不是买卖建议。");
            return out;
        }
        out.put("ok", true);
        out.put("date", snap.date());
        out.put("close", snap.close());
        out.put("ma20", snap.ma20());
        out.put("ma60", snap.ma60());
        out.put("distMa20Pct", snap.distMa20Pct());
        out.put("pct5", snap.pct5());
        out.put("pct20", snap.pct20());
        out.put("pct60", snap.pct60());
        out.put("trend", snap.trend());
        out.put("trendLabel", snap.trendLabel());
        out.put("atrPct", snap.atrPct());
        out.put("atrPctMedian", snap.atrPctMedian());
        out.put("vol", snap.vol());
        out.put("volLabel", snap.volLabel());
        out.put("volumeRatio", snap.volumeRatio());
        out.put("volume", snap.volume());
        out.put("volumeLabel", snap.volumeLabel());
        out.put("summary", snap.summary());

        MarketRegime.Snapshot sh = MarketRegime.of(indexBars(SH_SECID));
        MarketRegime.Snapshot cyb = MarketRegime.of(indexBars(CYB_SECID));
        Double sh20 = sh == null ? null : sh.pct20();
        Double cyb20 = cyb == null ? null : cyb.pct20();
        out.put("shPct20", sh20);
        out.put("cybPct20", cyb20);
        out.put("hs300Pct20", snap.pct20());
        String style = MarketRegime.styleLabel(snap.pct20(), cyb20);
        out.put("styleLabel", style);
        if (style != null && cyb20 != null) {
            out.put("styleNote", "近20日创业板 " + signed(cyb20) + "，沪深300 " + signed(snap.pct20())
                    + " → " + style);
        }
        out.put("disclaimer", "周期看 5/20/60 个交易日涨跌；环境看收盘相对 MA20/MA60、ATR 相对近 60 日中位、量相对 20 日均量。对照用，不是买卖建议。");
        return out;
    }

    private List<KlineBar> indexBars(String secid) {
        List<KlineBar> bars = quoteClient.peekKline(secid);
        return bars == null ? List.of() : bars;
    }

    private static String signed(Double v) {
        if (v == null) {
            return "—";
        }
        return (v > 0 ? "+" : "") + v + "%";
    }
}
