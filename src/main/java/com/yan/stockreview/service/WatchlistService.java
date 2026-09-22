package com.yan.stockreview.service;

import com.yan.stockreview.dto.BoardProfile;
import com.yan.stockreview.dto.BoardTreeNode;
import com.yan.stockreview.dto.PositionOverview;
import com.yan.stockreview.dto.PositionRow;
import com.yan.stockreview.dto.QuoteSnapshot;
import com.yan.stockreview.dto.RelativeStrength;
import com.yan.stockreview.dto.StockSuggest;
import com.yan.stockreview.dto.WatchSaveRequest;
import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.market.MarketCodeUtil;
import com.yan.stockreview.market.QuoteClient;
import com.yan.stockreview.repository.TradePlanRepository;
import com.yan.stockreview.repository.WatchStockRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WatchlistService {

    /** 默认纪律线幅度：-8% */
    public static final double DEFAULT_STOP_PCT = 8.0;

    private final WatchStockRepository watchStockRepository;
    private final QuoteClient quoteClient;
    private final TradePlanRepository tradePlanRepository;

    public WatchlistService(WatchStockRepository watchStockRepository, QuoteClient quoteClient,
                            TradePlanRepository tradePlanRepository) {
        this.watchStockRepository = watchStockRepository;
        this.quoteClient = quoteClient;
        this.tradePlanRepository = tradePlanRepository;
    }

    public List<WatchStock> list() {
        return watchStockRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    /** 只查本地自选，不访问行情搜索。 */
    public WatchStock findLocal(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String digits = code.replaceAll("\\D", "");
        if (digits.length() != 6) {
            try {
                digits = MarketCodeUtil.parse(code).code();
            } catch (Exception ex) {
                return null;
            }
        }
        return watchStockRepository.findByCode(digits).orElse(null);
    }

    @Transactional
    public WatchStock add(WatchSaveRequest req) {
        WatchStock stock = buildManual(req);
        watchStockRepository.findByCode(stock.getCode()).ifPresent(exist -> {
            throw new IllegalStateException(stock.getCode() + " " + stock.getName() + " 已在自选中，可点「编辑」修改");
        });
        return watchStockRepository.save(stock);
    }

    @Transactional
    public WatchStock update(Long id, WatchSaveRequest req) {
        WatchStock exist = watchStockRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("自选不存在"));
        WatchStock incoming = buildManual(req);
        if (!exist.getCode().equals(incoming.getCode())) {
            watchStockRepository.findByCode(incoming.getCode()).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw new IllegalStateException("代码 " + incoming.getCode() + " 已在自选中");
                }
            });
        }
        exist.setCode(incoming.getCode());
        exist.setName(incoming.getName());
        exist.setMarket(incoming.getMarket());
        exist.setSecid(incoming.getSecid());
        exist.setNotes(incoming.getNotes());
        // 自选页编辑：填了才更新纪律线，留空保留原值（持仓页保存则以表单为准）
        if (req.getStopPct() != null) {
            exist.setStopPct(normalizeStopPct(req.getStopPct()));
        }
        // 自选页不传份额时保留已录入持仓，避免被清空
        if (req.getShares() != null || req.getCostPrice() != null || req.getCostAmount() != null) {
            applyPosition(exist, req);
        }
        return watchStockRepository.save(exist);
    }

    /**
     * 手动录入 / 更新持仓。同一代码已在自选中则合并，不重复建记录。
     */
    @Transactional
    public WatchStock upsertPosition(WatchSaveRequest req) {
        WatchStock incoming = buildManual(req);
        applyPosition(incoming, req);
        incoming.setStopPct(normalizeStopPct(req.getStopPct()));
        if (incoming.getShares() == null || incoming.getShares() <= 0) {
            throw new IllegalArgumentException("请填写持仓数量");
        }
        WatchStock exist = null;
        if (req.getId() != null) {
            exist = watchStockRepository.findById(req.getId())
                    .orElseThrow(() -> new IllegalArgumentException("持仓不存在"));
        } else {
            exist = watchStockRepository.findByCode(incoming.getCode()).orElse(null);
        }
        if (exist == null) {
            return watchStockRepository.save(incoming);
        }
        Long existId = exist.getId();
        if (!exist.getCode().equals(incoming.getCode())) {
            watchStockRepository.findByCode(incoming.getCode()).ifPresent(other -> {
                if (!other.getId().equals(existId)) {
                    throw new IllegalStateException("代码 " + incoming.getCode() + " 已有持仓");
                }
            });
        }
        exist.setCode(incoming.getCode());
        exist.setName(incoming.getName());
        exist.setMarket(incoming.getMarket());
        exist.setSecid(incoming.getSecid());
        if (req.getNotes() != null) {
            exist.setNotes(incoming.getNotes());
        }
        exist.setShares(incoming.getShares());
        exist.setCostPrice(incoming.getCostPrice());
        exist.setCostAmount(incoming.getCostAmount());
        // 持仓页保存时始终以表单值为准（留空 = 恢复默认 -8%）
        exist.setStopPct(normalizeStopPct(req.getStopPct()));
        return watchStockRepository.save(exist);
    }

    @Transactional
    public void clearPosition(Long id) {
        WatchStock exist = watchStockRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("持仓不存在"));
        exist.setShares(null);
        exist.setCostPrice(null);
        exist.setCostAmount(null);
        watchStockRepository.save(exist);
    }

    public PositionOverview positionOverview() {
        return positionOverview(false);
    }

    public PositionOverview positionOverview(boolean refresh) {
        return positionOverview(refresh, true);
    }

    /** 统计图用：不访问行情，有缓存才带现价。 */
    public PositionOverview positionOverviewLocal() {
        return positionOverview(false, false);
    }

    public PositionOverview positionOverview(boolean refresh, boolean network) {
        List<WatchStock> holdings = list().stream()
                .filter(s -> s.getShares() != null && s.getShares() > 0)
                .toList();
        Map<String, QuoteSnapshot> qmap = new HashMap<>();
        String quoteError = null;
        if (!holdings.isEmpty()) {
            try {
                List<String> secids = holdings.stream().map(WatchStock::getSecid).toList();
                List<QuoteSnapshot> quotes = network
                        ? quoteClient.fetchQuotes(secids, refresh)
                        : quoteClient.cachedQuotes(secids);
                for (QuoteSnapshot q : quotes) {
                    if (q != null && q.code() != null) {
                        qmap.put(q.code(), q);
                    }
                }
            } catch (Exception ex) {
                quoteError = ex.getMessage();
            }
        }
        List<PositionRow> rows = new ArrayList<>();
        double totalCost = 0;
        double totalMarket = 0;
        double totalPl = 0;
        int quotedCount = 0;
        for (WatchStock s : holdings) {
            QuoteSnapshot q = qmap.get(s.getCode());
            PositionRow row = toRow(s, q, network);
            rows.add(row);
            if (row.getPrice() != null) {
                quotedCount++;
            }
            if (row.getCostAmount() != null) {
                totalCost += row.getCostAmount();
            }
            if (row.getMarketValue() != null) {
                totalMarket += row.getMarketValue();
            }
            if (row.getFloatPl() != null) {
                totalPl += row.getFloatPl();
            }
        }
        if (totalMarket > 0) {
            for (PositionRow row : rows) {
                if (row.getMarketValue() != null) {
                    row.setWeight(round2(row.getMarketValue() / totalMarket * 100));
                }
            }
        }
        rows.sort(Comparator.comparing(PositionRow::getMarketValue, Comparator.nullsLast(Comparator.reverseOrder())));
        PositionOverview overview = new PositionOverview();
        overview.setItems(rows);
        overview.setCount(rows.size());
        overview.setTotalCost(round2(totalCost));
        overview.setTotalMarket(round2(totalMarket));
        overview.setTotalPl(round2(totalPl));
        overview.setTotalPlPct(totalCost > 0 ? round2(totalPl / totalCost * 100) : null);
        fillDisciplineAndConcentration(overview, rows, totalMarket);
        overview.setQuotedCount(quotedCount);
        overview.setQuotedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        if (quoteError != null) {
            overview.setQuoteError(quoteError);
        } else if (network && !holdings.isEmpty() && quotedCount == 0) {
            overview.setQuoteError("未取到现价，请点「更新现价」重试");
        } else if (network && quotedCount < holdings.size()) {
            overview.setQuoteError("有 " + (holdings.size() - quotedCount) + " 只未取到现价");
        }
        return overview;
    }

    @Transactional
    public void delete(Long id) {
        watchStockRepository.deleteById(id);
    }

    /**
     * 解析代码：优先用已录入自选；行情接口失败时仍可用 6 位代码手工分析。
     */
    public WatchStock resolve(String codeOrKeyword) {
        String raw = codeOrKeyword == null ? "" : codeOrKeyword.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("请输入股票代码或名称");
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 6) {
            var local = watchStockRepository.findByCode(digits);
            if (local.isPresent()) {
                return local.get();
            }
        }
        try {
            List<StockSuggest> suggests = quoteClient.suggest(raw);
            StockSuggest hit = pickSuggest(raw, suggests);
            if (hit != null) {
                WatchStock stock = new WatchStock();
                stock.setCode(hit.code());
                stock.setName(hit.name());
                stock.setMarket(hit.market());
                stock.setSecid(hit.secid());
                return stock;
            }
        } catch (Exception ignored) {
        }
        MarketCodeUtil.ParsedCode parsed = MarketCodeUtil.parse(raw);
        WatchStock stock = new WatchStock();
        stock.setCode(parsed.code());
        stock.setMarket(parsed.market());
        stock.setSecid(parsed.secid());
        String name = null;
        try {
            name = quoteClient.fetchName(parsed.secid());
        } catch (Exception ignored) {
        }
        stock.setName(name == null || name.isBlank() ? parsed.code() : name);
        return stock;
    }

    public List<QuoteSnapshot> quotesForWatchlist() {
        List<WatchStock> list = list();
        if (list.isEmpty()) {
            return List.of();
        }
        return quoteClient.fetchQuotes(list.stream().map(WatchStock::getSecid).toList());
    }

    public Map<String, Object> boardView() {
        List<WatchStock> stocks = list();
        Map<String, QuoteSnapshot> quotes = new HashMap<>();
        try {
            if (!stocks.isEmpty()) {
                for (QuoteSnapshot q : quoteClient.fetchQuotes(stocks.stream().map(WatchStock::getSecid).toList())) {
                    if (q != null && q.code() != null) {
                        quotes.put(q.code(), q);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        List<Map<String, Object>> items = new ArrayList<>();
        List<QuoteClient.WatchKey> keys = stocks.stream()
                .map(s -> new QuoteClient.WatchKey(s.getCode(), s.getMarket(), s.getSecid()))
                .toList();
        Map<String, BoardProfile> profiles = quoteClient.fetchBoardProfiles(keys);
        for (WatchStock stock : stocks) {
            BoardProfile board = profiles.getOrDefault(stock.getCode(), new BoardProfile());
            QuoteSnapshot q = quotes.get(stock.getCode());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", stock.getId());
            row.put("code", stock.getCode());
            row.put("name", stock.getName());
            row.put("market", stock.getMarket());
            row.put("shares", stock.getShares());
            row.put("notes", stock.getNotes());
            row.put("price", q == null ? null : q.price());
            row.put("pctChange", q == null ? null : q.pctChange());
            row.put("boards", board);
            try {
                RelativeStrength rs = quoteClient.relativeStrength(stock.getCode(), stock.getMarket(),
                        stock.getSecid(), q == null ? null : q.pctChange());
                row.put("strength", rs);
            } catch (Exception ignored) {
            }
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("tree", buildIndustryTree(items));
        return out;
    }

    public BoardProfile boardOf(String codeOrKeyword) {
        WatchStock stock = resolve(codeOrKeyword);
        return quoteClient.fetchBoardProfile(stock.getCode(), stock.getMarket(), stock.getSecid());
    }

    private List<BoardTreeNode> buildIndustryTree(List<Map<String, Object>> items) {
        Map<String, BoardTreeNode> l1Map = new LinkedHashMap<>();
        List<Map<String, Object>> unknown = new ArrayList<>();
        for (Map<String, Object> item : items) {
            BoardProfile board = (BoardProfile) item.get("boards");
            List<String> levels = board == null ? List.of() : board.getEmIndustry();
            if (levels == null || levels.isEmpty()) {
                unknown.add(item);
                continue;
            }
            BoardTreeNode cursor = l1Map.computeIfAbsent(levels.get(0), k -> groupNode(k, 1));
            for (int i = 1; i < levels.size(); i++) {
                String name = levels.get(i);
                BoardTreeNode next = cursor.getChildren().stream()
                        .filter(c -> "group".equals(c.getType()) && name.equals(c.getName()))
                        .findFirst()
                        .orElse(null);
                if (next == null) {
                    next = groupNode(name, i + 1);
                    cursor.getChildren().add(next);
                }
                cursor = next;
            }
            cursor.getChildren().add(stockNode(item));
        }
        List<BoardTreeNode> roots = new ArrayList<>(l1Map.values());
        for (BoardTreeNode root : roots) {
            recount(root);
            fillGroupStrength(root);
        }
        roots.sort(Comparator.comparing(BoardTreeNode::getName));
        if (!unknown.isEmpty()) {
            BoardTreeNode other = groupNode("未分类 / ETF", 1);
            for (Map<String, Object> item : unknown) {
                other.getChildren().add(stockNode(item));
            }
            recount(other);
            roots.add(other);
        }
        return roots;
    }

    private static int recount(BoardTreeNode node) {
        if ("stock".equals(node.getType())) {
            node.setStockCount(1);
            return 1;
        }
        int sum = 0;
        for (BoardTreeNode child : node.getChildren()) {
            sum += recount(child);
        }
        node.setStockCount(sum);
        return sum;
    }

    private static BoardTreeNode groupNode(String name, int level) {
        BoardTreeNode node = new BoardTreeNode();
        node.setName(name);
        node.setType("group");
        node.setLevel(level);
        return node;
    }

    private static BoardTreeNode stockNode(Map<String, Object> item) {
        BoardTreeNode node = new BoardTreeNode();
        node.setType("stock");
        node.setLevel(4);
        node.setCode((String) item.get("code"));
        node.setName((String) item.get("name"));
        node.setPrice((Double) item.get("price"));
        node.setPctChange((Double) item.get("pctChange"));
        Object shares = item.get("shares");
        if (shares instanceof Number n) {
            node.setShares(n.doubleValue());
        }
        BoardProfile board = (BoardProfile) item.get("boards");
        if (board != null) {
            node.setPath(board.getPath());
        }
        Object strength = item.get("strength");
        if (strength instanceof RelativeStrength rs) {
            node.setBoardPct1d(rs.boardPct1d());
            node.setBoardPct5d(rs.boardPct5d());
            node.setRs1d(rs.rs1d());
            node.setRsLabel(rs.verdictLabel());
        }
        node.setStockCount(1);
        return node;
    }

    private WatchStock buildManual(WatchSaveRequest req) {
        if (req == null || req.getCode() == null || req.getCode().isBlank()) {
            throw new IllegalArgumentException("请填写股票代码");
        }
        MarketCodeUtil.ParsedCode parsed = MarketCodeUtil.parse(req.getCode().trim());
        WatchStock stock = new WatchStock();
        stock.setCode(parsed.code());
        stock.setMarket(parsed.market());
        stock.setSecid(parsed.secid());
        String name = req.getName() == null ? "" : req.getName().trim();
        if (name.isEmpty()) {
            try {
                List<StockSuggest> suggests = quoteClient.suggest(parsed.code());
                StockSuggest hit = pickSuggest(parsed.code(), suggests);
                if (hit != null && hit.name() != null) {
                    name = hit.name();
                }
            } catch (Exception ignored) {
            }
            if (name.isEmpty()) {
                try {
                    String fetched = quoteClient.fetchName(parsed.secid());
                    if (fetched != null) {
                        name = fetched;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        stock.setName(name.isEmpty() ? parsed.code() : name);
        stock.setNotes(req.getNotes());
        stock.setStopPct(normalizeStopPct(req.getStopPct()));
        applyPosition(stock, req);
        return stock;
    }

    private void applyPosition(WatchStock stock, WatchSaveRequest req) {
        Double shares = req.getShares();
        Double costPrice = req.getCostPrice();
        Double costAmount = req.getCostAmount();
        if (shares != null && shares < 0) {
            throw new IllegalArgumentException("持仓数量不能为负数");
        }
        if ((costPrice == null || costPrice <= 0) && costAmount != null && shares != null && shares > 0) {
            costPrice = costAmount / shares;
        }
        if (costAmount == null && shares != null && costPrice != null) {
            costAmount = shares * costPrice;
        }
        stock.setShares(shares);
        stock.setCostPrice(costPrice == null ? null : round4(costPrice));
        stock.setCostAmount(costAmount == null ? null : round2(costAmount));
    }

    private PositionRow toRow(WatchStock s, QuoteSnapshot q, boolean network) {
        PositionRow row = new PositionRow();
        row.setId(s.getId());
        row.setCode(s.getCode());
        row.setName(s.getName());
        row.setMarket(s.getMarket());
        row.setNotes(s.getNotes());
        row.setShares(s.getShares());
        row.setCostPrice(s.getCostPrice());
        Double costAmount = s.getCostAmount();
        if (costAmount == null && s.getShares() != null && s.getCostPrice() != null) {
            costAmount = s.getShares() * s.getCostPrice();
        }
        row.setCostAmount(costAmount == null ? null : round2(costAmount));
        if (q != null) {
            row.setPrice(q.price());
            row.setPctChange(q.pctChange());
            if (q.name() != null && (row.getName() == null || row.getName().isBlank() || row.getName().equals(row.getCode()))) {
                row.setName(q.name());
            }
        }
        try {
            BoardProfile board = network
                    ? quoteClient.fetchBoardProfile(s.getCode(), s.getMarket(), s.getSecid())
                    : quoteClient.peekBoard(s.getCode());
            if (board != null) {
                row.setBoardPath(board.getPath());
                row.setRegion(board.getRegion());
            }
        } catch (Exception ignored) {
        }
        if (s.getShares() != null && row.getPrice() != null) {
            row.setMarketValue(round2(s.getShares() * row.getPrice()));
        }
        if (row.getMarketValue() != null && row.getCostAmount() != null) {
            row.setFloatPl(round2(row.getMarketValue() - row.getCostAmount()));
        } else if (s.getShares() != null && s.getCostPrice() != null && row.getPrice() != null) {
            row.setFloatPl(round2((row.getPrice() - s.getCostPrice()) * s.getShares()));
        }
        if (row.getFloatPl() != null && row.getCostAmount() != null && row.getCostAmount() != 0) {
            row.setFloatPlPct(round2(row.getFloatPl() / row.getCostAmount() * 100));
        }
        // 纪律止损线：成本价 × (1 - 幅度/100)，幅度默认 8（-8%），可按持仓单独设置
        if (s.getCostPrice() != null && s.getCostPrice() > 0) {
            double pct = s.getStopPct() == null ? DEFAULT_STOP_PCT : s.getStopPct();
            double stopLine = Math.round(s.getCostPrice() * (1 - pct / 100) * 1000) / 1000.0;
            row.setStopPct(pct);
            row.setStopLine(stopLine);
            if (row.getPrice() != null) {
                row.setStopBroken(row.getPrice() < stopLine);
                row.setStopDistancePct(round2((row.getPrice() - stopLine) / stopLine * 100));
            }
        }
        try {
            RelativeStrength rs = network
                    ? quoteClient.relativeStrength(s.getCode(), s.getMarket(), s.getSecid(), row.getPctChange())
                    : quoteClient.peekRs(s.getCode());
            if (rs != null) {
                row.setStrength(rs);
            }
        } catch (Exception ignored) {
        }
        tradePlanRepository.findFirstByCodeAndStatusOrderByIdDesc(s.getCode(), "OPEN").ifPresent(plan -> {
            row.setPlanStop(plan.getStopPrice());
            row.setPlanTarget(plan.getTargetPrice());
            row.setPlanHoldDays(plan.getHoldDays());
            if (plan.getPlanDate() != null) {
                row.setPlanHeldDays((int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(plan.getPlanDate(), java.time.LocalDate.now())));
            }
            Double price = row.getPrice();
            if (price != null && plan.getStopPrice() != null && price <= plan.getStopPrice()) {
                row.setPlanFlag("STOP");
                row.setPlanFlagLabel("触及止损");
            } else if (price != null && plan.getTargetPrice() != null && price >= plan.getTargetPrice()) {
                row.setPlanFlag("TARGET");
                row.setPlanFlagLabel("触及目标");
            } else if (plan.getHoldDays() != null && row.getPlanHeldDays() != null && row.getPlanHeldDays() > plan.getHoldDays()) {
                row.setPlanFlag("OVERDUE");
                row.setPlanFlagLabel("超过计划天数");
            } else {
                row.setPlanFlag("ON");
                row.setPlanFlagLabel("有计划");
            }
        });
        return row;
    }

    /**
     * 汇总纪律与集中度指标：破 -8% 止损线数量、最大单一持仓、行业分布与最大行业占比。
     * 行业口径：个股取东财行业一级；ETF 无行业行情，按名称关键词归入对应行业暴露。
     */
    private void fillDisciplineAndConcentration(PositionOverview overview, List<PositionRow> rows, double totalMarket) {
        int broken = 0;
        PositionRow maxSingle = null;
        Map<String, Double> sectorMv = new LinkedHashMap<>();
        for (PositionRow row : rows) {
            if (Boolean.TRUE.equals(row.getStopBroken())) {
                broken++;
            }
            if (row.getMarketValue() == null) {
                continue;
            }
            if (maxSingle == null || (row.getWeight() != null && row.getWeight() > maxSingle.getWeight())) {
                maxSingle = row;
            }
            sectorMv.merge(sectorOf(row), row.getMarketValue(), Double::sum);
        }
        overview.setStopBrokenCount(broken);
        if (maxSingle != null) {
            overview.setMaxSingleName(maxSingle.getName() == null ? maxSingle.getCode() : maxSingle.getName());
            overview.setMaxSingleWeight(maxSingle.getWeight());
        }
        if (totalMarket > 0 && !sectorMv.isEmpty()) {
            List<Map<String, Object>> sectors = new ArrayList<>();
            for (Map.Entry<String, Double> e : sectorMv.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getKey());
                m.put("weight", round2(e.getValue() / totalMarket * 100));
                sectors.add(m);
            }
            sectors.sort((a, b) -> Double.compare((Double) b.get("weight"), (Double) a.get("weight")));
            overview.setSectorWeights(sectors);
            Map<String, Object> top = sectors.get(0);
            overview.setTopSector((String) top.get("name"));
            overview.setTopSectorWeight((Double) top.get("weight"));
        }
    }

    /** 个股取行业一级；ETF 按名称关键词归入行业暴露，用于集中度统计 */
    private static String sectorOf(PositionRow row) {
        String path = row.getBoardPath();
        if (path != null && !path.isBlank()) {
            int idx = path.indexOf(" / ");
            return idx > 0 ? path.substring(0, idx) : path.trim();
        }
        String name = row.getName() == null ? "" : row.getName();
        if (name.matches(".*(半导体|芯片|集成电路|材料设备).*")) return "电子";
        if (name.matches(".*(软件|计算机|信创|互联网|数据).*")) return "计算机";
        if (name.matches(".*(通信|光模块|5G).*")) return "通信";
        if (name.matches(".*(医药|医疗|生物|创新药).*")) return "医药生物";
        if (name.matches(".*(军工|国防).*")) return "国防军工";
        if (name.matches(".*(银行|证券|保险|金融).*")) return "金融";
        if (name.matches(".*(新能源|光伏|储能|电池|锂).*")) return "电力设备";
        if (name.matches(".*(科创|科技|成长).*")) return "科技主题ETF";
        if (name.matches(".*(红利|股息|央企|国企).*")) return "红利/价值ETF";
        if (name.matches(".*(300|500|1000|A50|上证|深证|宽基).*")) return "宽基ETF";
        return "其他ETF";
    }

    private static void fillGroupStrength(BoardTreeNode node) {
        if (node == null || !"group".equals(node.getType())) {
            return;
        }
        for (BoardTreeNode child : node.getChildren()) {
            fillGroupStrength(child);
        }
        Double boardPct = null;
        Double board5 = null;
        double rsSum = 0;
        int rsN = 0;
        for (BoardTreeNode child : node.getChildren()) {
            if (child.getBoardPct1d() != null && boardPct == null) {
                boardPct = child.getBoardPct1d();
                board5 = child.getBoardPct5d();
            }
            if (child.getRs1d() != null) {
                rsSum += child.getRs1d();
                rsN++;
            }
        }
        node.setBoardPct1d(boardPct);
        node.setBoardPct5d(board5);
        if (rsN > 0) {
            double avg = Math.round(rsSum / rsN * 100.0) / 100.0;
            node.setRs1d(avg);
            node.setRsLabel(avg >= 0.3 ? "强于板块" : (avg <= -0.3 ? "弱于板块" : "贴近板块"));
        }
    }

    private StockSuggest pickSuggest(String raw, List<StockSuggest> suggests) {
        if (suggests == null || suggests.isEmpty()) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 6) {
            return suggests.stream().filter(s -> digits.equals(s.code())).findFirst().orElse(suggests.get(0));
        }
        return suggests.stream()
                .filter(s -> raw.equals(s.name()) || raw.equalsIgnoreCase(s.code()))
                .findFirst()
                .orElse(suggests.get(0));
    }

    /** 纪律线幅度校验：空 = 用默认；否则限制在 0.5 ~ 90 之间 */
    private static Double normalizeStopPct(Double pct) {
        if (pct == null) {
            return null;
        }
        if (pct < 0.5 || pct > 90) {
            throw new IllegalArgumentException("纪律线幅度需在 0.5 ~ 90 之间（填 8 表示 -8%）");
        }
        return pct;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
