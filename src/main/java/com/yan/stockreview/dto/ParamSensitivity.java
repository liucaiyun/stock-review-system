package com.yan.stockreview.dto;

/**
 * 参数敏感性的一行：把某一个参数挪到某个值、其余保持默认，看期望值还剩多少。
 * 如果期望值对参数非常敏感（挪一点就变负），那它就不是一个可靠的 edge。
 */
public record ParamSensitivity(
        String param,
        double value,
        int trades,
        Double expectancyR,
        Double winRate
) {}
