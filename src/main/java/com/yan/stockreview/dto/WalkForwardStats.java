package com.yan.stockreview.dto;

import java.util.List;

/**
 * 一个策略的样本外验证 + 参数敏感性结果。
 *
 * <p>三组数字放在一起看：
 * <ol>
 *   <li>{@code isBestExpectancyR}：在<b>全部历史</b>上挑最好的参数能得到多少期望值——这是最乐观、也最容易骗人的数字；</li>
 *   <li>{@code oosExpectancyR}：严格按「只用过去挑参数、再往未来跑」得到的合并期望值——这才是诚实的数字；</li>
 *   <li>{@code gridMin / gridMedian / gridMax}：参数网格里最差/中位/最好的期望值。
 *       如果 min 是负的、median 勉强为正，说明这edge 只在某个特定参数上成立，等于没有。</li>
 * </ol>
 */
public record WalkForwardStats(
        String strategy,
        String strategyName,
        String from,
        String to,
        int seriesCount,
        int totalTrades,
        List<WalkForwardFold> foldList,
        int usedFolds,
        int positiveFolds,
        int oosTrades,
        Double oosExpectancyR,
        Double oosStdErr,
        Double oosTStat,
        Double oosWinRate,
        Double oosProfitFactor,
        Double oosTotalR,
        Double oosMaxDrawdownR,
        Double isBestExpectancyR,
        String isBestParams,
        Double gridMin,
        Double gridMedian,
        Double gridMax,
        int gridCombos,
        List<ParamSensitivity> sensitivity
) {}
