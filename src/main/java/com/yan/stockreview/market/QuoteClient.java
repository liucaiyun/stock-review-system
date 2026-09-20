package com.yan.stockreview.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yan.stockreview.dto.BoardProfile;
import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.dto.RelativeStrength;
import com.yan.stockreview.dto.StockSuggest;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 公开行情客户端。
 * 主源：东方财富；失败时回退腾讯 / 新浪。
 */
@Component
public class QuoteClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final String KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
            + "?secid=%s&ut=fa5fd1943c7b386f172d6893dbfba10b"
            + "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
            + "&klt=101&fqt=1&end=20500101&lmt=%d";
    private static final String ULIST_URL = "https://push2.eastmoney.com/api/qt/ulist.np/get"
            + "?fltt=2&invt=2&fields=f12,f13,f14,f2,f3,f4,f5,f6&secids=%s";
    private static final String SUGGEST_URL = "https://searchapi.eastmoney.com/api/suggest/get"
            + "?input=%s&type=14&token=FAKESECRET_k3l4m5n6o7p8q9r0s1t2&count=8";
    private static final String TENCENT_KLINE = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=%s,day,,,%d,qfq";
    private static final String TENCENT_QUOTE = "https://qt.gtimg.cn/q=%s";
    private static final String SINA_QUOTE = "https://hq.sinajs.cn/list=%s";
    private static final String SINA_KLINE = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?symbol=%s&scale=240&ma=no&datalen=%d";
    private static final String F10_BASIC = "https://datacenter.eastmoney.com/securities/api/data/v1/get"
            + "?reportName=RPT_F10_ORG_BASICINFO"
            + "&columns=SECUCODE,SECURITY_CODE,SECURITY_NAME_ABBR,TRADE_MARKET,CSRC_INDUSTRY_NAME,EM2016,"
            + "BOARD_NAME_1LEVEL,BOARD_NAME_2LEVEL,BOARD_NAME_3LEVEL,AREA_BOARD_NAME,PROVINCE,REGIONBK,BLGAINIAN,MAIN_BUSINESS"
            + "&filter=%s&pageNumber=1&pageSize=1&source=HSF10&client=PC";
    private static final String STOCK_BOARD = "https://push2.eastmoney.com/api/qt/stock/get"
            + "?invt=2&fltt=2&ut=fa5fd1943c7b386f172d6893dbfba10b"
            + "&secid=%s&fields=f57,f58,f100,f127,f128,f129,f207,f208,f209";
    private static final String STOCK_SLIST = "https://push2.eastmoney.com/api/qt/slist/get"
            + "?spt=1&fltt=2&invt=2&np=1&pn=1&pz=20"
            + "&ut=fa5fd1943c7b386f172d6893dbfba10b"
            + "&fields=f12,f13,f14,f3&secid=%s";
    private static final String INDUSTRY_CLIST = "https://push2.eastmoney.com/api/qt/clist/get"
            + "?np=1&fltt=2&invt=2&pn=1&pz=500&po=1&fid=f12"
            + "&ut=fa5fd1943c7b386f172d6893dbfba10b"
            + "&fs=m:90%2Bt:2%2Bf:%2150&fields=f12,f14,f2,f3,f109";
    private static final String BK_KLINE = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
            + "?secid=%s&ut=fa5fd1943c7b386f172d6893dbfba10b"
            + "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
            + "&klt=101&fqt=1&end=20500101&lmt=%d";
    private static final Pattern BK_CODE = Pattern.compile("BK\\d+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> INDEX_LIKE = Set.of(
            "融资融券", "沪股通", "深股通", "MSCI中国", "富时罗素", "标准普尔", "百元股",
            "东方财富热股", "大盘股", "权重股", "茅指数", "HS300_", "上证50_", "上证180_",
            "央视50_", "中证500", "创业板指", "深证成指"
    );
    private static final Charset GBK = Charset.forName("GBK");

    private final ObjectMapper objectMapper;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final Cache<String, List<KlineBar>> klineCache;
    private final Cache<String, List<StockSuggest>> suggestCache;
    private final Cache<String, List<QuoteSnapshot>> quoteCache;
    private final Cache<String, BoardProfile> boardCache;
    private final Cache<String, RelativeStrength> rsCache;
    private final Cache<String, List<IndustryBoard>> industryCache;
    private final Semaphore httpPermits = new Semaphore(3);

    public QuoteClient(ObjectMapper objectMapper,
                       @Value("${stock.quote.connect-timeout-ms:8000}") int connectTimeoutMs,
                       @Value("${stock.quote.read-timeout-ms:12000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.klineCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(400)
                .build();
        this.suggestCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(200)
                .build();
        this.quoteCache = Caffeine.newBuilder()
                .expireAfterWrite(20, TimeUnit.SECONDS)
                .maximumSize(200)
                .build();
        this.boardCache = Caffeine.newBuilder()
                .expireAfterWrite(12, TimeUnit.HOURS)
                .maximumSize(800)
                .build();
        this.rsCache = Caffeine.newBuilder()
                .expireAfterWrite(45, TimeUnit.SECONDS)
                .maximumSize(400)
                .build();
        this.industryCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(4)
                .build();
    }

    public List<StockSuggest> suggest(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String key = keyword.trim();
        List<StockSuggest> cached = suggestCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        try {
            String url = String.format(SUGGEST_URL, URLEncoder.encode(key, StandardCharsets.UTF_8));
            JsonNode root = getJson(url);
            JsonNode data = root.path("QuotationCodeTable").path("Data");
            List<StockSuggest> list = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode n : data) {
                    String code = text(n, "Code");
                    String quoteId = text(n, "QuoteID");
                    if (code == null || quoteId == null || !quoteId.contains(".")) {
                        continue;
                    }
                    String marketName = text(n, "MktNum");
                    String typeName = text(n, "SecurityTypeName");
                    String market = MarketCodeUtil.inferMarket(code);
                    if (quoteId.startsWith("1.")) {
                        market = "SH";
                    } else if (code.startsWith("8") || code.startsWith("4")) {
                        market = "BJ";
                    } else if (quoteId.startsWith("0.")) {
                        market = "SZ";
                    }
                    list.add(new StockSuggest(code, text(n, "Name"), market, quoteId, typeName != null ? typeName : marketName));
                }
            }
            suggestCache.put(key, list);
            return list;
        } catch (Exception ex) {
            throw new IllegalStateException("搜索股票失败：" + ex.getMessage(), ex);
        }
    }

    public List<KlineBar> fetchKline(String secid, int limit) {
        int n = Math.min(Math.max(limit, 30), 500);
        String cacheKey = secid + "|" + n;
        List<KlineBar> cached = klineCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<KlineBar> bars = null;
        Exception last = null;
        try {
            bars = fetchEastMoneyKline(secid, n);
        } catch (Exception ex) {
            last = ex;
        }
        if (isEmpty(bars)) {
            try {
                bars = fetchTencentKline(secid, n);
            } catch (Exception ex) {
                last = ex;
            }
        }
        if (isEmpty(bars)) {
            try {
                bars = fetchSinaKline(secid, n);
            } catch (Exception ex) {
                last = ex;
            }
        }
        if (isEmpty(bars)) {
            throw new IllegalStateException("拉取K线失败：" + (last == null ? "无数据" : last.getMessage()), last);
        }
        klineCache.put(cacheKey, bars);
        return bars;
    }

    /** 只读缓存，不访问行情。统计总览用，避免 30+ 只自选把页面卡住。 */
    public List<KlineBar> peekKline(String secid) {
        if (secid == null || secid.isBlank()) {
            return List.of();
        }
        for (int n : new int[] {250, 180, 120, 400, 500}) {
            List<KlineBar> cached = klineCache.getIfPresent(secid + "|" + n);
            if (cached != null && cached.size() >= 35) {
                return cached;
            }
        }
        return List.of();
    }

    /** 只读报价缓存。统计页用，缓存没有就返回空，不打东财。 */
    public List<QuoteSnapshot> cachedQuotes(List<String> secids) {
        if (secids == null || secids.isEmpty()) {
            return List.of();
        }
        List<String> ids = secids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<QuoteSnapshot> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<QuoteSnapshot> joined = quoteCache.getIfPresent(String.join(",", ids));
        if (joined != null) {
            for (QuoteSnapshot q : joined) {
                if (q != null && q.secid() != null && seen.add(q.secid())) {
                    out.add(q);
                }
            }
        }
        for (String id : ids) {
            if (seen.contains(id)) {
                continue;
            }
            List<QuoteSnapshot> one = quoteCache.getIfPresent(id);
            if (one != null) {
                for (QuoteSnapshot q : one) {
                    if (q != null && seen.add(id)) {
                        out.add(q);
                    }
                }
            }
        }
        return out;
    }

    public String fetchName(String secid) {
        try {
            List<QuoteSnapshot> quotes = fetchQuotes(List.of(secid));
            if (!quotes.isEmpty() && quotes.get(0).name() != null) {
                return quotes.get(0).name();
            }
        } catch (Exception ignored) {
        }
        try {
            JsonNode root = getJson(String.format(KLINE_URL, secid, 2));
            return text(root.path("data"), "name");
        } catch (Exception ex) {
            return null;
        }
    }

    public List<QuoteSnapshot> fetchQuotes(List<String> secids) {
        return fetchQuotes(secids, false);
    }

    public List<QuoteSnapshot> fetchQuotes(List<String> secids, boolean refresh) {
        if (secids == null || secids.isEmpty()) {
            return List.of();
        }
        List<String> ids = secids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        String cacheKey = String.join(",", ids);
        if (!refresh) {
            List<QuoteSnapshot> cached = quoteCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
        } else {
            quoteCache.invalidate(cacheKey);
        }
        Map<String, QuoteSnapshot> merged = new LinkedHashMap<>();
        Exception last = null;
        try {
            mergeQuotes(merged, fetchEastMoneyQuotes(ids));
        } catch (Exception ex) {
            last = ex;
        }
        if (missingPrice(merged, ids)) {
            try {
                mergeQuotes(merged, fetchTencentQuotes(ids));
            } catch (Exception ex) {
                last = ex;
            }
        }
        if (missingPrice(merged, ids)) {
            try {
                mergeQuotes(merged, fetchSinaQuotes(ids));
            } catch (Exception ex) {
                last = ex;
            }
        }
        List<QuoteSnapshot> list = new ArrayList<>(merged.values());
        if (list.isEmpty()) {
            throw new IllegalStateException("拉取实时行情失败：" + (last == null ? "无数据" : last.getMessage()), last);
        }
        quoteCache.put(cacheKey, list);
        for (QuoteSnapshot q : list) {
            if (q != null && q.secid() != null && !q.secid().isBlank()) {
                quoteCache.put(q.secid(), List.of(q));
            }
        }
        return list;
    }

    public List<QuoteSnapshot> fetchIndices() {
        return fetchQuotes(List.of("1.000001", "0.399001", "0.399006", "1.000300"));
    }

    /** 只读板块缓存。 */
    public BoardProfile peekBoard(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return boardCache.getIfPresent(code.trim());
    }

    /** 只读相对强弱缓存。 */
    public RelativeStrength peekRs(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return rsCache.getIfPresent(code.trim());
    }

    public BoardProfile fetchBoardProfile(String code, String market, String secid) {
        if (code == null || code.isBlank()) {
            return new BoardProfile();
        }
        String key = code.trim();
        BoardProfile cached = boardCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        BoardProfile profile = new BoardProfile();
        profile.setCode(key);
        try {
            fillFromF10(profile, key);
        } catch (Exception ignored) {
        }
        if (isBlankList(profile.getEmIndustry()) && secid != null && !secid.isBlank()) {
            try {
                fillFromQuote(profile, secid);
            } catch (Exception ignored) {
            }
        } else if (isBlankList(profile.getEmIndustry())) {
            try {
                String guessed = MarketCodeUtil.parse(key).secid();
                fillFromQuote(profile, guessed);
            } catch (Exception ignored) {
            }
        }
        if (isBlankList(profile.getEmIndustry()) && !isBlankList(profile.getEm2016())) {
            profile.setEmIndustry(new ArrayList<>(profile.getEm2016()));
        }
        if (profile.getIndustrySecid() == null) {
            matchIndustryByName(profile);
        }
        if (profile.getIndustrySecid() == null && secid != null && !secid.isBlank()) {
            try {
                fillFromSlist(profile, secid);
            } catch (Exception ignored) {
            }
        }
        if (profile.getIndustrySecid() != null && profile.getIndustryCode() != null) {
            List<IndustryBoard> boards = loadIndustryBoards();
            if (!boards.isEmpty()) {
                boolean known = false;
                for (IndustryBoard b : boards) {
                    if (profile.getIndustryCode().equalsIgnoreCase(b.code())) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    profile.setIndustryCode(null);
                    profile.setIndustrySecid(null);
                    matchIndustryByName(profile);
                }
            }
        }
        profile.setPath(joinPath(profile.getEmIndustry()));
        if (profile.getIndustryName() == null && !isBlankList(profile.getEmIndustry())) {
            profile.setIndustryName(profile.getEmIndustry().get(profile.getEmIndustry().size() - 1));
        }
        boardCache.put(key, profile);
        return profile;
    }

    public Map<String, BoardProfile> fetchBoardProfiles(List<WatchKey> keys) {
        Map<String, BoardProfile> map = new LinkedHashMap<>();
        if (keys == null) {
            return map;
        }
        for (WatchKey key : keys) {
            if (key == null || key.code() == null) {
                continue;
            }
            map.put(key.code(), fetchBoardProfile(key.code(), key.market(), key.secid()));
        }
        return map;
    }

    public record WatchKey(String code, String market, String secid) {}

    public RelativeStrength relativeStrength(String code, String market, String secid) {
        return relativeStrength(code, market, secid, null);
    }

    public RelativeStrength relativeStrength(String code, String market, String secid, Double stockPct1d) {
        if (code == null || code.isBlank()) {
            return RelativeStrength.none(code, null, null, "代码为空");
        }
        String cacheKey = code.trim();
        RelativeStrength cached = rsCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        BoardProfile profile = fetchBoardProfile(code, market, secid);
        String boardSecid = profile.getIndustrySecid();
        String boardName = profile.getIndustryName();
        if (boardSecid == null) {
            matchIndustryByName(profile);
            boardSecid = profile.getIndustrySecid();
            boardName = profile.getIndustryName();
        }
        Double s1 = stockPct1d;
        Double s5 = null;
        try {
            if (secid != null && !secid.isBlank()) {
                List<KlineBar> bars = fetchKline(secid, 12);
                if (s1 == null) {
                    s1 = lastPct(bars);
                }
                s5 = pctFrom(bars, 5);
            }
        } catch (Exception ignored) {
        }
        if (boardSecid == null) {
            RelativeStrength none = RelativeStrength.none(code, profile.getName(), profile.getPath(),
                    "未匹配到行业板块行情");
            return none;
        }
        Double b1 = null;
        Double b5 = null;
        try {
            QuoteSnapshot bq = fetchBoardQuote(boardSecid);
            if (bq != null) {
                b1 = bq.pctChange();
                if (boardName == null) {
                    boardName = bq.name();
                }
            }
        } catch (Exception ignored) {
        }
        if (b1 == null) {
            for (IndustryBoard b : loadIndustryBoards()) {
                if (b.code() != null && boardSecid.toUpperCase(Locale.ROOT).contains(b.code().toUpperCase(Locale.ROOT))) {
                    b1 = b.pct1d();
                    if (boardName == null) {
                        boardName = b.name();
                    }
                    break;
                }
            }
        }
        try {
            List<KlineBar> boardBars = fetchBoardKline(boardSecid, 12);
            if (b5 == null) {
                b5 = pctFrom(boardBars, 5);
            }
            if (b1 == null) {
                b1 = lastPct(boardBars);
            }
        } catch (Exception ignored) {
        }
        Double rs1 = (s1 != null && b1 != null) ? round2(s1 - b1) : null;
        Double rs5 = (s5 != null && b5 != null) ? round2(s5 - b5) : null;
        String verdict;
        String label;
        if (rs1 == null && rs5 == null) {
            verdict = "NONE";
            label = "暂无板块涨跌";
        } else if ((rs1 != null && rs1 >= 0.3) && (rs5 == null || rs5 >= 0.5)) {
            verdict = "STRONG";
            label = "强于板块";
        } else if ((rs1 != null && rs1 <= -0.3) && (rs5 == null || rs5 <= -0.5)) {
            verdict = "WEAK";
            label = "弱于板块";
        } else {
            verdict = "MIXED";
            label = "贴近板块";
        }
        String explain = buildRsExplain(boardName, s1, b1, rs1, s5, b5, rs5);
        RelativeStrength rs = new RelativeStrength(
                code, profile.getName(), boardName, profile.getIndustryCode(), profile.getPath(),
                s1 == null ? null : round2(s1),
                s5, b1 == null ? null : round2(b1), b5, rs1, rs5, verdict, label, explain);
        rsCache.put(cacheKey, rs);
        return rs;
    }

    private static String buildRsExplain(String board, Double s1, Double b1, Double rs1,
                                         Double s5, Double b5, Double rs5) {
        String name = board == null ? "所属板块" : board;
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (s1 != null && b1 != null) {
            sb.append("：今日个股 ").append(signed(s1)).append("%，板块 ").append(signed(b1))
                    .append("%，相对 ").append(signed(rs1)).append("%。");
        }
        if (s5 != null && b5 != null) {
            sb.append("近5日个股 ").append(signed(s5)).append("%，板块 ").append(signed(b5))
                    .append("%，相对 ").append(signed(rs5)).append("%。");
        }
        if (rs1 != null && rs1 < -0.3 && b1 != null && b1 > 0) {
            sb.append("板块在涨、个股更弱，中短线需警惕掉队。");
        } else if (rs1 != null && rs1 > 0.3 && b1 != null && b1 < 0) {
            sb.append("板块在跌、个股更抗，属于相对强。");
        }
        return sb.toString();
    }

    private static String signed(Double v) {
        if (v == null) {
            return "-";
        }
        return (v > 0 ? "+" : "") + v;
    }

    private QuoteSnapshot fetchBoardQuote(String boardSecid) throws Exception {
        String url = String.format(ULIST_URL, boardSecid) + "&ut=fa5fd1943c7b386f172d6893dbfba10b";
        JsonNode root = getJson(url);
        JsonNode diff = root.path("data").path("diff");
        if (diff.isArray() && !diff.isEmpty()) {
            JsonNode n = diff.get(0);
            String code = text(n, "f12");
            return new QuoteSnapshot(code, text(n, "f14"), boardSecid, "BK",
                    decimal(n, "f2"), null, decimal(n, "f3"), null, null);
        }
        return null;
    }

    private List<KlineBar> fetchBoardKline(String boardSecid, int n) throws Exception {
        String cacheKey = boardSecid + "|" + n;
        List<KlineBar> cached = klineCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        JsonNode root = getJson(String.format(BK_KLINE, boardSecid, n));
        JsonNode klines = root.path("data").path("klines");
        List<KlineBar> bars = new ArrayList<>();
        if (klines.isArray()) {
            for (JsonNode node : klines) {
                String[] p = node.asText().split(",");
                if (p.length < 11) {
                    continue;
                }
                bars.add(new KlineBar(p[0], num(p[1]), num(p[2]), num(p[3]), num(p[4]),
                        num(p[5]), num(p[6]), num(p[8]), num(p[10])));
            }
        }
        if (!bars.isEmpty()) {
            klineCache.put(cacheKey, bars);
        }
        return bars;
    }

    private static Double lastPct(List<KlineBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return null;
        }
        return round2(bars.get(bars.size() - 1).pctChange());
    }

    private static Double pctFrom(List<KlineBar> bars, int days) {
        if (bars == null || bars.size() <= days) {
            return null;
        }
        double now = bars.get(bars.size() - 1).close();
        double then = bars.get(bars.size() - 1 - days).close();
        if (then == 0) {
            return null;
        }
        return round2((now - then) / then * 100);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void matchIndustryByName(BoardProfile profile) {
        List<String> names = new ArrayList<>();
        if (profile.getIndustryName() != null) {
            names.add(profile.getIndustryName());
        }
        if (profile.getEmIndustry() != null) {
            for (int i = profile.getEmIndustry().size() - 1; i >= 0; i--) {
                names.add(profile.getEmIndustry().get(i));
            }
        }
        List<IndustryBoard> boards = loadIndustryBoards();
        for (String name : names) {
            IndustryBoard hit = findIndustry(boards, name);
            if (hit != null) {
                profile.setIndustryCode(hit.code());
                profile.setIndustrySecid("90." + hit.code());
                if (profile.getIndustryName() == null) {
                    profile.setIndustryName(hit.name());
                }
                return;
            }
        }
    }

    private IndustryBoard findIndustry(List<IndustryBoard> boards, String name) {
        if (boards == null || name == null || name.isBlank()) {
            return null;
        }
        String want = normalizeBoardName(name);
        for (IndustryBoard b : boards) {
            if (name.equals(b.name()) || want.equals(normalizeBoardName(b.name()))) {
                return b;
            }
        }
        for (IndustryBoard b : boards) {
            String bn = normalizeBoardName(b.name());
            if (!bn.isEmpty() && (want.contains(bn) || bn.contains(want))) {
                return b;
            }
        }
        return null;
    }

    private List<IndustryBoard> loadIndustryBoards() {
        List<IndustryBoard> cached = industryCache.getIfPresent("all");
        if (cached != null) {
            return cached;
        }
        try {
            JsonNode diff = getJson(INDUSTRY_CLIST).path("data").path("diff");
            List<IndustryBoard> list = new ArrayList<>();
            for (JsonNode n : eachDiff(diff)) {
                String code = text(n, "f12");
                String name = text(n, "f14");
                if (code == null || name == null) {
                    continue;
                }
                list.add(new IndustryBoard(code, name, decimal(n, "f3"), decimal(n, "f109")));
            }
            if (!list.isEmpty()) {
                industryCache.put("all", list);
            }
            return list;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String normalizeBoardName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("板块", "")
                .replaceAll("[ⅠⅡⅢIVX一二三级]+$", "")
                .trim();
    }

    private record IndustryBoard(String code, String name, Double pct1d, Double pct5d) {}

    private void fillFromF10(BoardProfile profile, String code) throws Exception {
        String filter = URLEncoder.encode("(SECURITY_CODE=\"" + code + "\")", StandardCharsets.UTF_8);
        JsonNode root = getJson(String.format(F10_BASIC, filter), "https://emweb.securities.eastmoney.com/");
        JsonNode data = root.path("result").path("data");
        if (!data.isArray() || data.isEmpty()) {
            return;
        }
        JsonNode n = data.get(0);
        if (profile.getName() == null) {
            profile.setName(text(n, "SECURITY_NAME_ABBR"));
        }
        profile.setExchange(text(n, "TRADE_MARKET"));
        profile.setMainBusiness(text(n, "MAIN_BUSINESS"));
        profile.setProvince(text(n, "PROVINCE"));
        profile.setRegion(firstNonBlank(text(n, "AREA_BOARD_NAME"), text(n, "REGIONBK")));
        List<String> em = new ArrayList<>();
        addLevel(em, text(n, "BOARD_NAME_1LEVEL"));
        addLevel(em, text(n, "BOARD_NAME_2LEVEL"));
        addLevel(em, text(n, "BOARD_NAME_3LEVEL"));
        profile.setEmIndustry(em);
        profile.setEm2016(splitPath(text(n, "EM2016")));
        profile.setCsrc(splitPath(text(n, "CSRC_INDUSTRY_NAME")));
        splitConcepts(profile, text(n, "BLGAINIAN"));
    }

    private void fillFromQuote(BoardProfile profile, String secid) throws Exception {
        JsonNode data = getJson(String.format(STOCK_BOARD, secid)).path("data");
        if (data.isMissingNode() || data.isNull()) {
            return;
        }
        if (profile.getName() == null) {
            profile.setName(text(data, "f58"));
        }
        if (profile.getIndustryCode() == null) {
            String bk = extractBk(text(data, "f207"));
            if (bk != null) {
                profile.setIndustryCode(bk);
                profile.setIndustrySecid("90." + bk);
            }
        }
        if (profile.getIndustryName() == null) {
            profile.setIndustryName(firstNonBlank(text(data, "f100"), text(data, "f127")));
        }
        if (isBlankList(profile.getEmIndustry())) {
            List<String> em = new ArrayList<>();
            addLevel(em, text(data, "f127"));
            profile.setEmIndustry(em);
        }
        if (profile.getRegion() == null) {
            profile.setRegion(text(data, "f128"));
        }
        if (isBlankList(profile.getConcepts()) && isBlankList(profile.getExtraConcepts())) {
            splitConcepts(profile, text(data, "f129"));
        }
    }

    private void fillFromSlist(BoardProfile profile, String secid) throws Exception {
        JsonNode diff = getJson(String.format(STOCK_SLIST, secid)).path("data").path("diff");
        String lastBk = null;
        String lastName = null;
        String knownBk = null;
        String knownName = null;
        List<String> levels = new ArrayList<>();
        Set<String> industryCodes = new HashSet<>();
        for (IndustryBoard b : loadIndustryBoards()) {
            if (b.code() != null) {
                industryCodes.add(b.code().toUpperCase(Locale.ROOT));
            }
        }
        for (JsonNode n : eachDiff(diff)) {
            String code = text(n, "f12");
            String name = text(n, "f14");
            addLevel(levels, name);
            if (code != null && code.toUpperCase(Locale.ROOT).startsWith("BK")) {
                String bk = code.toUpperCase(Locale.ROOT);
                lastBk = bk;
                lastName = name;
                if (industryCodes.contains(bk)) {
                    knownBk = bk;
                    knownName = name;
                }
            }
        }
        String useBk = knownBk != null ? knownBk : lastBk;
        String useName = knownBk != null ? knownName : lastName;
        if (useBk != null) {
            profile.setIndustryCode(useBk);
            profile.setIndustrySecid("90." + useBk);
            if (profile.getIndustryName() == null) {
                profile.setIndustryName(useName);
            }
        }
        if (isBlankList(profile.getEmIndustry()) && !levels.isEmpty()) {
            profile.setEmIndustry(levels);
        }
    }

    private static List<JsonNode> eachDiff(JsonNode diff) {
        List<JsonNode> list = new ArrayList<>();
        if (diff == null || diff.isMissingNode() || diff.isNull()) {
            return list;
        }
        if (diff.isArray()) {
            diff.forEach(list::add);
        } else if (diff.isObject()) {
            diff.fields().forEachRemaining(e -> list.add(e.getValue()));
        }
        return list;
    }

    private static String extractBk(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return null;
        }
        Matcher m = BK_CODE.matcher(raw.toUpperCase(Locale.ROOT));
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    private static void splitConcepts(BoardProfile profile, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Set<String> core = new LinkedHashSet<>();
        Set<String> extra = new LinkedHashSet<>();
        for (String part : raw.split("[,，;；]+")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (isIndexLike(name)) {
                extra.add(name);
            } else {
                core.add(name);
            }
        }
        profile.setConcepts(new ArrayList<>(core));
        profile.setExtraConcepts(new ArrayList<>(extra));
    }

    private static boolean isIndexLike(String name) {
        if (INDEX_LIKE.contains(name)) {
            return true;
        }
        if (name.endsWith("_")) {
            return true;
        }
        return name.contains("通") && (name.contains("股") || name.contains("深") || name.contains("沪"));
    }

    private static void addLevel(List<String> levels, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (levels.isEmpty() || !name.equals(levels.get(levels.size() - 1))) {
            levels.add(name.trim());
        }
    }

    private static List<String> splitPath(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        for (String part : raw.split("[-/／>＞]+")) {
            addLevel(list, part);
        }
        return list;
    }

    private static String joinPath(List<String> levels) {
        if (isBlankList(levels)) {
            return null;
        }
        return String.join(" / ", levels);
    }

    private static boolean isBlankList(List<String> list) {
        return list == null || list.isEmpty();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private List<QuoteSnapshot> fetchEastMoneyQuotes(List<String> secids) throws Exception {
        String joined = String.join(",", secids);
        JsonNode root = getJson(String.format(ULIST_URL, joined));
        JsonNode diff = root.path("data").path("diff");
        List<QuoteSnapshot> list = new ArrayList<>();
        if (diff.isArray()) {
            for (JsonNode n : diff) {
                String code = text(n, "f12");
                Integer marketNo = n.path("f13").isNumber() ? n.path("f13").asInt() : null;
                String market = (marketNo != null && marketNo == 1) ? "SH" : MarketCodeUtil.inferMarket(code);
                if (code != null && (code.startsWith("8") || code.startsWith("4") || code.startsWith("92"))) {
                    market = "BJ";
                }
                String secid = (marketNo != null ? marketNo : ("SH".equals(market) ? 1 : 0)) + "." + code;
                list.add(new QuoteSnapshot(
                        code,
                        text(n, "f14"),
                        secid,
                        market,
                        decimal(n, "f2"),
                        decimal(n, "f4"),
                        decimal(n, "f3"),
                        decimal(n, "f5"),
                        decimal(n, "f6")
                ));
            }
        }
        return list;
    }

    private List<QuoteSnapshot> fetchTencentQuotes(List<String> secids) throws Exception {
        String q = secids.stream().map(QuoteClient::toTencentSymbol).collect(Collectors.joining(","));
        String body = httpGet(String.format(TENCENT_QUOTE, q), GBK, "https://gu.qq.com/");
        List<QuoteSnapshot> list = new ArrayList<>();
        for (String line : body.split("\\r?\\n")) {
            QuoteSnapshot snap = parseTencentQuote(line);
            if (snap != null) {
                list.add(snap);
            }
        }
        return list;
    }

    private List<QuoteSnapshot> fetchSinaQuotes(List<String> secids) throws Exception {
        String q = secids.stream().map(QuoteClient::toTencentSymbol).collect(Collectors.joining(","));
        String body = httpGet(String.format(SINA_QUOTE, q), GBK, "https://finance.sina.com.cn/");
        List<QuoteSnapshot> list = new ArrayList<>();
        for (String line : body.split(";")) {
            QuoteSnapshot snap = parseSinaQuote(line);
            if (snap != null) {
                list.add(snap);
            }
        }
        return list;
    }

    private static QuoteSnapshot parseTencentQuote(String line) {
        if (line == null || !line.contains("~")) {
            return null;
        }
        int eq = line.indexOf("=\"");
        if (eq < 0) {
            return null;
        }
        String symbol = line.substring(0, eq).replace("v_", "").trim();
        String payload = line.substring(eq + 2);
        if (payload.endsWith("\";")) {
            payload = payload.substring(0, payload.length() - 2);
        } else if (payload.endsWith("\"")) {
            payload = payload.substring(0, payload.length() - 1);
        }
        String[] p = payload.split("~");
        if (p.length < 5) {
            return null;
        }
        String code = p[2];
        Double price = parseQuoteNumber(p[3]);
        Double prev = parseQuoteNumber(p[4]);
        Double change = p.length > 31 ? parseQuoteNumber(p[31]) : null;
        Double pct = p.length > 32 ? parseQuoteNumber(p[32]) : null;
        if (change == null && price != null && prev != null) {
            change = price - prev;
        }
        if (pct == null && price != null && prev != null && prev != 0) {
            pct = (price - prev) / prev * 100;
        }
        String market = symbol.startsWith("sh") ? "SH" : (symbol.startsWith("bj") ? "BJ" : "SZ");
        String marketNo = "SH".equals(market) ? "1" : "0";
        return new QuoteSnapshot(code, emptyToNull(p[1]), marketNo + "." + code, market, price, change, pct, null, null);
    }

    private static QuoteSnapshot parseSinaQuote(String line) {
        if (line == null || !line.contains("hq_str_")) {
            return null;
        }
        int nameAt = line.indexOf("hq_str_");
        int eq = line.indexOf("=\"", nameAt);
        if (eq < 0) {
            return null;
        }
        String symbol = line.substring(nameAt + 7, eq);
        String payload = line.substring(eq + 2).replace("\"", "");
        String[] p = payload.split(",");
        if (p.length < 4) {
            return null;
        }
        String code = symbol.length() >= 8 ? symbol.substring(2) : symbol;
        Double price = parseQuoteNumber(p[3]);
        Double prev = parseQuoteNumber(p[2]);
        Double change = price != null && prev != null ? price - prev : null;
        Double pct = price != null && prev != null && prev != 0 ? (price - prev) / prev * 100 : null;
        String market = symbol.startsWith("sh") ? "SH" : (symbol.startsWith("bj") ? "BJ" : "SZ");
        String marketNo = "SH".equals(market) ? "1" : "0";
        return new QuoteSnapshot(code, emptyToNull(p[0]), marketNo + "." + code, market, price, change, pct, null, null);
    }

    private static void mergeQuotes(Map<String, QuoteSnapshot> merged, List<QuoteSnapshot> incoming) {
        if (incoming == null) {
            return;
        }
        for (QuoteSnapshot q : incoming) {
            if (q == null || q.code() == null) {
                continue;
            }
            QuoteSnapshot old = merged.get(q.code());
            if (old == null || (old.price() == null && q.price() != null)) {
                merged.put(q.code(), q);
            }
        }
    }

    private static boolean missingPrice(Map<String, QuoteSnapshot> merged, List<String> secids) {
        if (merged.isEmpty()) {
            return true;
        }
        for (String secid : secids) {
            String code = MarketCodeUtil.parse(secid).code();
            QuoteSnapshot q = merged.get(code);
            if (q == null || q.price() == null) {
                return true;
            }
        }
        return false;
    }

    private static Double parseQuoteNumber(String s) {
        if (s == null || s.isBlank() || "-".equals(s)) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return v == 0 ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private List<KlineBar> fetchEastMoneyKline(String secid, int n) throws Exception {
        JsonNode root = getJson(String.format(KLINE_URL, secid, n));
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalArgumentException("未找到行情：" + secid);
        }
        List<KlineBar> bars = new ArrayList<>();
        JsonNode klines = data.path("klines");
        if (klines.isArray()) {
            for (JsonNode node : klines) {
                String[] p = node.asText().split(",");
                if (p.length < 11) {
                    continue;
                }
                bars.add(new KlineBar(p[0], num(p[1]), num(p[2]), num(p[3]), num(p[4]),
                        num(p[5]), num(p[6]), num(p[8]), num(p[10])));
            }
        }
        return bars;
    }

    private List<KlineBar> fetchTencentKline(String secid, int n) throws Exception {
        String symbol = toTencentSymbol(secid);
        JsonNode root = getJson(String.format(TENCENT_KLINE, symbol, n));
        JsonNode node = root.path("data").path(symbol);
        JsonNode arr = node.path("qfqday");
        if (!arr.isArray() || arr.isEmpty()) {
            arr = node.path("day");
        }
        List<KlineBar> bars = new ArrayList<>();
        if (arr.isArray()) {
            double prev = 0;
            for (JsonNode row : arr) {
                if (!row.isArray() || row.size() < 6) {
                    continue;
                }
                double open = row.get(1).asDouble();
                double close = row.get(2).asDouble();
                double high = row.get(3).asDouble();
                double low = row.get(4).asDouble();
                double volume = row.get(5).asDouble();
                double pct = prev == 0 ? 0 : (close - prev) / prev * 100;
                bars.add(new KlineBar(row.get(0).asText(), open, close, high, low, volume, 0, pct, 0));
                prev = close;
            }
        }
        return bars;
    }

    private List<KlineBar> fetchSinaKline(String secid, int n) throws Exception {
        String symbol = toTencentSymbol(secid);
        JsonNode arr = getJson(String.format(SINA_KLINE, symbol, n));
        List<KlineBar> bars = new ArrayList<>();
        if (arr.isArray()) {
            double prev = 0;
            for (JsonNode row : arr) {
                double open = num(text(row, "open"));
                double close = num(text(row, "close"));
                double high = num(text(row, "high"));
                double low = num(text(row, "low"));
                double volume = num(text(row, "volume"));
                double pct = prev == 0 ? 0 : (close - prev) / prev * 100;
                bars.add(new KlineBar(text(row, "day"), open, close, high, low, volume, 0, pct, 0));
                prev = close;
            }
        }
        return bars;
    }

    private static String toTencentSymbol(String secid) {
        MarketCodeUtil.ParsedCode parsed = MarketCodeUtil.parse(secid);
        String prefix = "BJ".equals(parsed.market()) ? "bj" : parsed.market().toLowerCase(Locale.ROOT);
        return prefix + parsed.code();
    }

    private JsonNode getJson(String url) throws Exception {
        return getJson(url, "https://quote.eastmoney.com/");
    }

    private JsonNode getJson(String url, String referer) throws Exception {
        return objectMapper.readTree(httpGet(url, StandardCharsets.UTF_8, referer));
    }

    private String httpGet(String url, Charset charset, String referer) throws Exception {
        boolean acquired = false;
        try {
            acquired = httpPermits.tryAcquire(80, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IllegalStateException("行情请求繁忙，请稍后刷新");
            }
            Exception last = null;
            for (int i = 0; i < 2; i++) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(connectTimeoutMs);
                    conn.setReadTimeout(readTimeoutMs);
                    conn.setInstanceFollowRedirects(true);
                    conn.setUseCaches(false);
                    conn.setRequestProperty("User-Agent", UA);
                    conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
                    conn.setRequestProperty("Connection", "close");
                    if (referer != null) {
                        conn.setRequestProperty("Referer", referer);
                    }
                    int status = conn.getResponseCode();
                    InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
                    String body = stream == null ? "" : new String(stream.readAllBytes(), charset);
                    if (status >= 200 && status < 300) {
                        return body;
                    }
                    last = new IllegalStateException("行情接口 HTTP " + status);
                } catch (Exception ex) {
                    last = ex;
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
                Thread.sleep(300L * (i + 1));
            }
            throw last;
        } finally {
            if (acquired) {
                httpPermits.release();
            }
        }
    }

    private static boolean isEmpty(List<KlineBar> bars) {
        return bars == null || bars.isEmpty();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() || "-".equals(s) ? null : s;
    }

    private static Double decimal(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull() || v.isTextual() && ("-".equals(v.asText()) || v.asText().isBlank())) {
            return null;
        }
        return v.asDouble();
    }

    private static double num(String s) {
        if (s == null || s.isBlank() || "-".equals(s)) {
            return 0;
        }
        return Double.parseDouble(s);
    }
}
