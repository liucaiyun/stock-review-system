package com.yan.stockreview.market;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 A 股代码并生成东方财富 secid（市场.代码） */
public final class MarketCodeUtil {

    private static final Pattern FULL = Pattern.compile("^(?:(SH|SZ|BJ))?(\\d{6})(?:\\.(SH|SZ|BJ))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECID = Pattern.compile("^(\\d)\\.(\\d{6})$");

    private MarketCodeUtil() {}

    public static ParsedCode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String s = raw.trim().toUpperCase(Locale.ROOT)
                .replace("．", ".")
                .replace(" ", "");

        Matcher secidMatcher = SECID.matcher(s);
        if (secidMatcher.matches()) {
            String marketNo = secidMatcher.group(1);
            String code = secidMatcher.group(2);
            String market = "1".equals(marketNo) ? "SH" : "SZ";
            if (isBj(code)) {
                market = "BJ";
            }
            return new ParsedCode(code, market, marketNo + "." + code);
        }

        Matcher m = FULL.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("无法识别的股票代码：" + raw);
        }
        String prefix = m.group(1);
        String code = m.group(2);
        String suffix = m.group(3);
        String market = prefix != null ? prefix : (suffix != null ? suffix : inferMarket(code));
        String marketNo = "SH".equals(market) ? "1" : "0";
        return new ParsedCode(code, market, marketNo + "." + code);
    }

    public static String inferMarket(String code) {
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) {
            return "SH";
        }
        if (isBj(code)) {
            return "BJ";
        }
        return "SZ";
    }

    private static boolean isBj(String code) {
        return code.startsWith("8") || code.startsWith("4") || code.startsWith("92");
    }

    public record ParsedCode(String code, String market, String secid) {}
}
