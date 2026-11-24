package com.cubalance.app;

import java.util.Locale;

/** 币种与金额格式化：KRW ₩ / CNY ¥(分) / JPY ¥(整数) / HKD HK$(角) */
public final class Money {
    public static final int KRW = 0;
    public static final int CNY = 1;
    public static final int JPY = 2;
    public static final int HKD = 3;

    private Money() {}

    public static String symbol(int c) {
        if (c == CNY) return "¥";
        if (c == JPY) return "¥";
        if (c == HKD) return "HK$";
        return "₩";
    }

    /** 数字部分（不含符号）。CNY 以"分"存储、HKD 以"1/10 港元"存储 */
    public static String num(int c, int v) {
        switch (c) {
            case CNY: return String.format(Locale.US, "%,.2f", v / 100.0);
            case HKD: return String.format(Locale.US, "%,.1f", v / 10.0);
            default:  return String.format(Locale.US, "%,d", v);
        }
    }

    public static String fmt(int c, int v) {
        return num(c, v) + " " + symbol(c);
    }

    /** 带正负号的金额（充值+/消费-） */
    public static String signed(int c, boolean topup, int v) {
        return (topup ? "+" : "-") + num(c, v) + " " + symbol(c);
    }
}
