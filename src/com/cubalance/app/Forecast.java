package com.cubalance.app;

import java.util.List;

/**
 * 预计可用天数：用卡内已有的消费记录估算日均支出，再折算「余额还能用几天」。
 *
 * 两条数据来源，优先用交易记录（有每笔金额），不够时退回余额历史（看余额降幅）：
 * 1. trips：卡内最近约 20 笔交易，按「最早一笔 → 最新一笔」的时间跨度算日均消费；
 * 2. hist：本机读卡历史，取相邻两次余额的下降量当消费，按时间跨度算日均。
 *
 * 全部本地计算，不联网、不写入任何新数据。
 */
public final class Forecast {

    private static final double DAY = 86400000.0;

    /** 预计可用天数；-1 = 数据不足，无法估算 */
    public int daysLeft = -1;
    /** 日均消费（与余额同原始单位） */
    public int avgPerDay = 0;
    /** 采样跨度（天，向上取整，至少 1） */
    public int sampleDays = 0;
    /** 参与估算的笔数 */
    public int samples = 0;
    /** true = 按卡内交易记录算，false = 按本机余额历史算 */
    public boolean fromTrips = false;

    private Forecast() {}

    /**
     * @param balance 当前余额（原始单位）
     * @param trips   卡内交易记录（可为空）
     * @param hist    本机这张卡的余额历史，按时间升序（可为空）
     */
    public static Forecast of(int balance, List<Trip> trips, List<Store.Entry> hist) {
        Forecast f = new Forecast();
        if (!f.useTrips(trips)) f.useHist(hist);
        if (f.avgPerDay > 0) {
            f.daysLeft = Math.max(0, balance / f.avgPerDay);
        }
        return f;
    }

    /** 有没有可用的估算结果 */
    public boolean ok() { return daysLeft >= 0; }

    private boolean useTrips(List<Trip> trips) {
        if (trips == null || trips.isEmpty()) return false;
        long min = Long.MAX_VALUE, max = 0;
        int spend = 0, n = 0;
        for (Trip t : trips) {
            if (t == null || t.isTopUp() || t.cost <= 0) continue;
            spend += t.cost;
            n++;
            if (t.timeMs > 0) {
                if (t.timeMs < min) min = t.timeMs;
                if (t.timeMs > max) max = t.timeMs;
            }
        }
        if (n == 0 || spend <= 0) return false;
        if (max <= 0 || min == Long.MAX_VALUE) return false; // 卡里没给时间，只能用历史降幅
        int days = spanDays(min, max);
        avgPerDay = Math.max(1, (int) Math.round((double) spend / days));
        sampleDays = days;
        samples = n;
        fromTrips = true;
        return true;
    }

    private boolean useHist(List<Store.Entry> hist) {
        if (hist == null || hist.size() < 2) return false;
        long min = Long.MAX_VALUE, max = 0;
        int spend = 0, n = 0;
        int prev = -1;
        for (Store.Entry e : hist) {
            if (e == null) continue;
            if (prev >= 0 && e.balance < prev) {
                spend += prev - e.balance;
                n++;
            }
            prev = e.balance;
            if (e.time > 0) {
                if (e.time < min) min = e.time;
                if (e.time > max) max = e.time;
            }
        }
        if (n == 0 || spend <= 0) return false;
        if (max <= 0 || min == Long.MAX_VALUE) return false;
        int days = spanDays(min, max);
        avgPerDay = Math.max(1, (int) Math.round((double) spend / days));
        sampleDays = days;
        samples = n;
        fromTrips = false;
        return true;
    }

    /** 采样跨度：不足一天按一天算（同一天的多笔 → 当天的消费就是日均） */
    private static int spanDays(long min, long max) {
        double span = (max - min) / DAY;
        return (int) Math.max(1, Math.ceil(span));
    }

    /** 日均消费的说明文字，如「日均 ₩2,300（卡内 10 天 12 笔消费）」 */
    public String basis(int cur) {
        if (avgPerDay <= 0) return "还读不到足够的消费记录";
        return "日均 " + Money.fmt(cur, avgPerDay)
                + "（" + (fromTrips ? "卡内 " : "本机 ")
                + sampleDays + " 天 " + samples + " 笔" + (fromTrips ? "消费" : "余额下降") + "）";
    }

    /** 可用天数的简短描述，如「约 6 天」；数据不足时给 null */
    public String daysText() {
        return daysLeft < 0 ? null : "约 " + daysLeft + " 天";
    }
}
