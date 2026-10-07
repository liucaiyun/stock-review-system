package com.yan.stockreview.strategy;

/**
 * 一次买卖往返的交易成本模型（A 股现货）。
 *
 * <p>这里是全项目唯一的手续费口径，前端「做T计算器」用的费率必须与之一致：
 * 佣金万 2.5、单笔最低 5 元（买卖各收一次）、过户费 0.001%（双边）、
 * 印花税 0.05%（仅卖出）、滑点 0.05%（单边，按成交价折让）。
 *
 * <p>费用按 {@link #NOTIONAL} 名义本金折算成比例，所以「最低 5 元佣金」对小资金的影响会体现在结果里：
 * 名义本金 10 万元时往返成本约 0.21%（10 万元以下更贵）。
 */
public final class TradeCost {

    /** 佣金率（万 2.5） */
    public static final double COMMISSION_RATE = 0.00025;
    /** 单笔最低佣金（元） */
    public static final double COMMISSION_MIN = 5.0;
    /** 过户费（双边） */
    public static final double TRANSFER_FEE = 0.00001;
    /** 印花税（仅卖出） */
    public static final double STAMP_TAX = 0.0005;
    /** 单边滑点 */
    public static final double SLIPPAGE = 0.0005;
    /** 用于把最低佣金折算成比例的名义本金 */
    public static final double NOTIONAL = 100_000;

    private TradeCost() {}

    /** 买入实际成交价（含滑点：买贵一点） */
    public static double buyPrice(double price) {
        return price * (1 + SLIPPAGE);
    }

    /** 卖出实际成交价（含滑点：卖便宜一点） */
    public static double sellPrice(double price) {
        return price * (1 - SLIPPAGE);
    }

    /**
     * 一次往返（买入 @entry → 卖出 @exit）的净收益率（小数，非百分数）。
     * entry/exit 都应当是已经含滑点的价格（即用 {@link #buyPrice}/{@link #sellPrice} 处理过）。
     */
    public static double roundTripNetReturn(double entry, double exit) {
        if (!(entry > 0) || !(exit > 0)) {
            return 0;
        }
        double buyAmount = NOTIONAL;
        double buyFee = commission(buyAmount) + buyAmount * TRANSFER_FEE;
        double cost = buyAmount + buyFee;
        double sellAmount = NOTIONAL * exit / entry;
        double sellFee = commission(sellAmount) + sellAmount * TRANSFER_FEE + sellAmount * STAMP_TAX;
        return (sellAmount - sellFee) / cost - 1;
    }

    /** 一次往返的成本占名义本金的比例（用于前端展示「这一趟要多少成本才能保本」） */
    public static double roundTripCostRatio() {
        return -roundTripNetReturn(buyPrice(1.0), sellPrice(1.0));
    }

    /**
     * 每股净盈亏（元）：按 {@link #NOTIONAL} 名义本金折算股数，扣掉买卖两次的佣金/过户费/印花税。
     * entry/exit 应当是已含滑点的价格。R 倍数与期望值都用这个口径算，所以 R 是「净 R」。
     */
    public static double netPnlPerShare(double entry, double exit) {
        if (!(entry > 0) || !(exit > 0)) {
            return 0;
        }
        double shares = NOTIONAL / entry;
        double buyAmount = shares * entry;
        double buyFee = commission(buyAmount) + buyAmount * TRANSFER_FEE;
        double sellAmount = shares * exit;
        double sellFee = commission(sellAmount) + sellAmount * TRANSFER_FEE + sellAmount * STAMP_TAX;
        return (sellAmount - sellFee - buyAmount - buyFee) / shares;
    }

    private static double commission(double amount) {
        return Math.max(amount * COMMISSION_RATE, COMMISSION_MIN);
    }
}
