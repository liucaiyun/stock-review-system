package com.yan.stockreview.strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 策略说明书：给学习和勾选分析用 */
public final class StrategyCatalog {

    private StrategyCatalog() {}

    public record Def(
            String id,
            String name,
            String category,
            String summary,
            String idea,
            String how,
            String buyRule,
            String sellRule,
            String pitfall,
            String suitable
    ) {}

    public static final List<Def> ALL = List.of(
            new Def(StrategyEngine.MY_STEADY, "稳健版（自有）", "自有",
                    "你自己的打法：6 个零件里至少 2 个同向才算强信号，分歧日不建仓。",
                    "单一指标容易骗人，多个独立角度同时指向一个方向才值得动手。6 个零件：双均线（MA5 vs MA20）、MACD（DIF vs DEA）、RSI（50 上下）、布林（中轨上下）、突破（贴近 20 日新高/新低）、量价（放量红/绿 K）。",
                    "每天收盘后给 6 个零件各记一票多/空。买点：多头票从不足 2 票变成 ≥2 票（共振形成当天）；卖点：空头票 ≥2 票形成当天。分歧日（振幅>8% 且实体<2%）即使共振也只提醒、不记买点。",
                    "≥2 个零件同向转多，且当天不是分歧日。",
                    "≥2 个零件同向转空。",
                    "共振形成那天往往已经涨了一段，追高风险仍在；6 个零件都和价格相关，极端行情里会同时翻空，不等于底部。",
                    "中短线纪律交易，配合 -8% 硬止损和交易计划一起用。"),
            new Def(StrategyEngine.MY_ULTRA, "超稳健版（自有）", "自有",
                    "稳健版再加一道趋势门：MA20>MA60 且收盘在 MA20 上方才允许建仓。",
                    "共振说明短期资金同向，趋势门说明中期方向没坏。两个都满足，胜率通常比单看共振高，代价是信号更少、更晚。",
                    "在稳健版的共振买点上再检查趋势门：MA20>MA60 且收盘价>MA20，门内才记买点；门外只提醒。卖点：转空共振，或收盘跌破 MA20（趋势门破坏，纪律出场）。",
                    "≥2 零件共振 + 趋势门成立（MA20>MA60 且收盘>MA20）+ 非分歧日。",
                    "≥2 零件转空，或收盘跌破 MA20。",
                    "趋势门是滞后的，大V反转初期会被挡在门外；长期横盘时 MA20/MA60 缠绕，门会反复开关。",
                    "实盘主仓位，宁可错过不可做错的场景。"),
            new Def(StrategyEngine.MA_CROSS, "均线金叉死叉", "趋势",
                    "用短期均线穿过长期均线，判断趋势可能转向。",
                    "价格围绕均线波动。短均线从下向上穿过长均线，常被看成买的兴趣回来；从上向下穿过则看成卖压加重。",
                    "本系统用日线前复权收盘价，算 MA5 和 MA20。只在「交叉发生的那一天」记信号，不是 MA5 一直大于 MA20 就天天买。",
                    "MA5 上穿 MA20（金叉）。",
                    "MA5 下穿 MA20（死叉）。",
                    "震荡市会反复金叉死叉，容易来回打脸。均线是滞后指标，大涨大跌后才交叉。",
                    "更适合有方向的趋势市，不适合窄幅震荡。"),
            new Def(StrategyEngine.MA_TREND, "均线多空排列", "趋势",
                    "看 MA5、MA10、MA20 是否按顺序排好，判断多头还是空头。",
                    "多头排列（短>中>长）说明近、中、远期都在抬升；空头排列则相反。排列「刚刚形成」比「已经排了很久」更有学习价值。",
                    "比较三条均线的大小。只在从「不是多头」变成「多头」，或从「不是空头」变成「空头」那天记信号。",
                    "当天形成 MA5>MA10>MA20。",
                    "当天形成 MA5<MA10<MA20。",
                    "排列形成后趋势可能已经走了一截。横盘时三条线缠在一起，信号没有意义。",
                    "用来确认趋势，而不是抓最低点。"),
            new Def(StrategyEngine.MACD, "MACD金叉死叉", "趋势",
                    "用 DIF 与 DEA 的交叉，看上涨/下跌动能是否切换。",
                    "DIF 是 12 日与 26 日指数均线的差，DEA 是 DIF 的 9 日均线。DIF 上穿 DEA 称金叉，下穿称死叉。柱状图（直方图）变长表示动能加强。",
                    "标准参数 12、26、9，用日线收盘价。只在交叉当天记信号。",
                    "DIF 上穿 DEA。",
                    "DIF 下穿 DEA。",
                    "零轴下方的金叉和零轴上方的死叉，含义不同。单看交叉、不看价格位置，容易学偏。",
                    "趋势跟踪的辅助工具，可和均线对照着学。"),
            new Def(StrategyEngine.RSI, "RSI超买超卖", "超买超卖",
                    "用 0~100 的相对强弱，判断涨跌是否过热或超跌。",
                    "RSI 看过去一段时间里涨幅占总波动的比例。高于 70 常称超买，低于 30 常称超卖。本系统不在「进入」超买超卖时交易，而在「离开」时记信号，减少钝化干扰。",
                    "14 日 RSI。买：从 ≤30 上穿 30；卖：从 ≥70 下穿 70。",
                    "RSI 由超卖区上穿 30。",
                    "RSI 由超买区下穿 70。",
                    "单边大趋势里 RSI 会长期停在超买或超卖区，这时反向信号经常失败。这叫钝化，是学习时最该记住的一点。",
                    "更适合震荡市的高抛低吸练习，趋势市要谨慎。"),
            new Def(StrategyEngine.KDJ, "KDJ金叉死叉", "超买超卖",
                    "用 K、D、J 三条线的交叉，看超买超卖转折。",
                    "先算 RSV（收盘价在近 N 日高低点中的位置），再平滑成 K、D，J=3K-2D。K 上穿 D 为金叉。J 值更敏感，过高过低常被用来过滤。",
                    "N=9。买：K 上穿 D 且 J<40；卖：K 下穿 D 且 J>60。用 J 过滤掉一些高位金叉、低位死叉。",
                    "K 上穿 D，且 J 偏低。",
                    "K 下穿 D，且 J 偏高。",
                    "比 RSI 更敏感，震荡市信号很多。J 值可以大于 100 或小于 0，这是正常现象。",
                    "适合短线节奏练习，不要把它当成精确买卖点。"),
            new Def(StrategyEngine.BOLL, "布林带突破", "超买超卖",
                    "价格相对均线加减 2 倍标准差的轨道，看是否冲出「平常波动范围」。",
                    "中轨是 20 日均线，上下轨 = 中轨 ± 2 倍标准差。跌破下轨可能超跌，升破上轨可能过热。带宽变窄后突然放大，常伴随变盘。",
                    "20 日、2 倍标准差。买：收盘从轨道内跌到下轨之外；卖：收盘从轨道内涨到上轨之外。",
                    "收盘跌破下轨。",
                    "收盘升破上轨。",
                    "强趋势里价格可以贴着上轨或下轨走很久，这时「突破就反向」会错。先看它是开口还是收口。",
                    "用来理解波动率和均值回归，趋势市要配合均线方向。"),
            new Def(StrategyEngine.VOL, "放量突破", "趋势",
                    "价格创新高的同时成交量明显放大，学习量价配合。",
                    "没有量的新高容易是假突破；放量说明有更多人在这个价位成交。本系统用「大于 20 日均量 1.5 倍 + 收盘创近 20 日新高 + 站上 MA20」作为买点练习。",
                    "比较当日成交量与 20 日均量，以及当日收盘与过去 20 日最高收盘。",
                    "放量（>1.5 倍均量）突破近 20 日收盘高点，且收盘在 MA20 上方。",
                    "本策略目前只练买点（突破），没有对应卖点规则。",
                    "利好发布日、打板日也会放量，不等于趋势成立。量能突然放大也可能是出货。",
                    "用来观察「量价是否配合」，不要单独当作开仓依据。")
    );

    public static Map<String, Def> byId() {
        Map<String, Def> map = new LinkedHashMap<>();
        for (Def def : ALL) {
            map.put(def.id(), def);
        }
        return map;
    }

    public static List<Map<String, Object>> asJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Def def : ALL) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", def.id());
            row.put("name", def.name());
            row.put("category", def.category());
            row.put("summary", def.summary());
            row.put("idea", def.idea());
            row.put("how", def.how());
            row.put("buyRule", def.buyRule());
            row.put("sellRule", def.sellRule());
            row.put("pitfall", def.pitfall());
            row.put("suitable", def.suitable());
            list.add(row);
        }
        return list;
    }
}
