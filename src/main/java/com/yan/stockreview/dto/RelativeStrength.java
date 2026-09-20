package com.yan.stockreview.dto;

/** 个股相对所属行业板块的 1 日 / 5 日强弱 */
public record RelativeStrength(
        String code,
        String name,
        String boardName,
        String boardCode,
        String boardPath,
        Double stockPct1d,
        Double stockPct5d,
        Double boardPct1d,
        Double boardPct5d,
        Double rs1d,
        Double rs5d,
        String verdict,
        String verdictLabel,
        String explain
) {
    public static RelativeStrength none(String code, String name, String path, String reason) {
        return new RelativeStrength(code, name, null, null, path, null, null, null, null, null, null,
                "NONE", "暂无板块涨跌", reason);
    }
}
