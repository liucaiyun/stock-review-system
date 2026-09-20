package com.yan.stockreview.service;

import com.yan.stockreview.entity.TradeRecord;
import com.yan.stockreview.market.MarketCodeUtil;
import com.yan.stockreview.repository.TradeRecordRepository;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TradeService {

    private static final DateTimeFormatter[] DATES = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    };

    private final TradeRecordRepository tradeRecordRepository;

    public TradeService(TradeRecordRepository tradeRecordRepository) {
        this.tradeRecordRepository = tradeRecordRepository;
    }

    public List<TradeRecord> list() {
        return tradeRecordRepository.findAllByOrderByTradeDateDescIdDesc();
    }

    public List<TradeRecord> listByCode(String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return tradeRecordRepository.findByCodeOrderByTradeDateAscIdAsc(MarketCodeUtil.parse(code).code());
    }

    @Transactional
    public TradeRecord save(TradeRecord record) {
        if (record.getTradeDate() == null) {
            throw new IllegalArgumentException("交易日期不能为空");
        }
        if (record.getCode() == null || record.getCode().isBlank()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        record.setCode(MarketCodeUtil.parse(record.getCode()).code());
        record.setDirection(normalizeDirection(record.getDirection()));
        if (record.getAmount() == null && record.getShares() != null && record.getPrice() != null) {
            record.setAmount(record.getShares() * record.getPrice());
        }
        if (record.getSource() == null) {
            record.setSource("MANUAL");
        }
        return tradeRecordRepository.save(record);
    }

    @Transactional
    public TradeRecord update(Long id, TradeRecord incoming) {
        TradeRecord exist = tradeRecordRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("成交记录不存在"));
        incoming.setId(id);
        incoming.setCreatedAt(exist.getCreatedAt());
        if (incoming.getSource() == null) {
            incoming.setSource(exist.getSource() == null ? "MANUAL" : exist.getSource());
        }
        return save(incoming);
    }

    @Transactional
    public void delete(Long id) {
        tradeRecordRepository.deleteById(id);
    }

    /**
     * 导入同花顺导出的成交明细 CSV/TXT（常见为 GBK）。
     * 同花顺个人账号没有官方 API，这是对接持仓/成交的可行方式。
     */
    @Transactional
    public Map<String, Object> importThsCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择同花顺导出的 CSV 文件");
        }
        try {
            byte[] bytes = file.getBytes();
            String text = decode(bytes);
            List<String[]> rows = parseCsv(text);
            if (rows.size() < 2) {
                throw new IllegalArgumentException("文件没有数据行，请确认是成交明细导出文件");
            }
            Map<String, Integer> header = mapHeader(rows.get(0));
            int imported = 0;
            int skipped = 0;
            List<String> errors = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                try {
                    TradeRecord rec = toRecord(row, header);
                    if (rec == null) {
                        skipped++;
                        continue;
                    }
                    boolean exists = tradeRecordRepository.existsByTradeDateAndCodeAndDirectionAndSharesAndPrice(
                            rec.getTradeDate(), rec.getCode(), rec.getDirection(), rec.getShares(), rec.getPrice());
                    if (exists) {
                        skipped++;
                        continue;
                    }
                    tradeRecordRepository.save(rec);
                    imported++;
                } catch (Exception ex) {
                    errors.add("第" + (i + 1) + "行：" + ex.getMessage());
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("errors", errors);
            return result;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("导入失败：" + ex.getMessage(), ex);
        }
    }

    public List<TradeRecord> range(LocalDate start, LocalDate end) {
        return tradeRecordRepository.findByTradeDateBetweenOrderByTradeDateAscIdAsc(start, end);
    }

    private TradeRecord toRecord(String[] row, Map<String, Integer> header) {
        String dateText = col(row, header, "date");
        String codeText = col(row, header, "code");
        String dirText = col(row, header, "dir");
        if (isBlank(dateText) || isBlank(codeText) || isBlank(dirText)) {
            return null;
        }
        String direction = normalizeDirection(dirText);
        if (direction == null) {
            return null;
        }
        String code = codeText.replaceAll("\\D", "");
        if (code.length() > 6) {
            code = code.substring(code.length() - 6);
        }
        if (code.length() != 6) {
            throw new IllegalArgumentException("代码无效：" + codeText);
        }
        TradeRecord rec = new TradeRecord();
        rec.setTradeDate(parseDate(dateText));
        rec.setCode(code);
        rec.setName(col(row, header, "name"));
        rec.setDirection(direction);
        rec.setShares(parseDouble(col(row, header, "shares")));
        rec.setPrice(parseDouble(col(row, header, "price")));
        rec.setAmount(parseDouble(col(row, header, "amount")));
        rec.setSource("THS");
        rec.setNote("同花顺导入");
        return rec;
    }

    private static Map<String, Integer> mapHeader(String[] headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headerRow.length; i++) {
            String h = headerRow[i] == null ? "" : headerRow[i].replace("\uFEFF", "").trim();
            String key = classifyHeader(h);
            if (key != null && !map.containsKey(key)) {
                map.put(key, i);
            }
        }
        if (!map.containsKey("date") || !map.containsKey("code") || !map.containsKey("dir")) {
            throw new IllegalArgumentException("无法识别表头。需要包含：成交日期、证券代码、操作/买卖标志。当前：" + String.join(",", headerRow));
        }
        return map;
    }

    private static String classifyHeader(String h) {
        if (h.contains("日期") || h.contains("发生日期")) return "date";
        if (h.contains("代码") || h.contains("证券代码") || h.contains("股票代码")) return "code";
        if (h.contains("名称") || h.contains("简称")) return "name";
        if (h.contains("操作") || h.contains("买卖") || h.contains("方向") || h.contains("业务名称") || h.contains("标志")) return "dir";
        if (h.contains("数量") || h.contains("成交量") || h.contains("股数")) return "shares";
        if (h.contains("均价") || h.contains("价格") || h.contains("成交价")) return "price";
        if (h.contains("金额") || h.contains("发生金额")) return "amount";
        return null;
    }

    private static String normalizeDirection(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.contains("买") || s.equals("buy") || s.equals("b") || s.contains("申购") || s.contains("转入")) {
            return "buy";
        }
        if (s.contains("卖") || s.equals("sell") || s.equals("s") || s.contains("赎回") || s.contains("转出")) {
            return "sell";
        }
        return null;
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        String gbk = new String(bytes, Charset.forName("GBK"));
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        return countReplace(utf8) > countReplace(gbk) ? gbk : utf8;
    }

    private static int countReplace(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') n++;
        }
        return n;
    }

    private static List<String[]> parseCsv(String text) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rows.add(split(line));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("解析 CSV 失败", ex);
        }
        return rows;
    }

    private static String[] split(String line) {
        if (line.contains("\t") && !line.contains(",")) {
            return line.split("\t", -1);
        }
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quote = !quote;
            } else if ((c == ',' || c == ';') && !quote) {
                cols.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString().trim());
        return cols.toArray(new String[0]);
    }

    private static String col(String[] row, Map<String, Integer> header, String key) {
        Integer idx = header.get(key);
        if (idx == null || idx >= row.length) {
            return null;
        }
        String v = row[idx];
        return v == null ? null : v.trim();
    }

    private static LocalDate parseDate(String text) {
        String t = text.replace("年", "-").replace("月", "-").replace("日", "").trim();
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new IllegalArgumentException("无法解析日期：" + text);
    }

    private static Double parseDouble(String text) {
        if (isBlank(text)) {
            return null;
        }
        String t = text.replace(",", "").replace("，", "").replace("%", "").trim();
        if (t.isEmpty() || "-".equals(t)) {
            return null;
        }
        return Double.parseDouble(t);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
