package com.cubalance.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** 一条消费/充值记录（解析自卡内交易记录） */
public class Trip {
    public int type;        // 记录类型：2=充值(金额为正)，其它=消费
    public int cost;        // 金额（韩元，正数；消费时界面显示 -）
    public int balance;     // 交易后余额
    public long timeMs;     // 交易时间戳（毫秒），0 表示无效
    public String timeText; // 卡内原始 BCD 时间串 YYYYMMDDHHMMSS（便于核对）

    public boolean isTopUp() { return type == 2; }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            o.put("cost", cost);
            o.put("balance", balance);
            o.put("timeMs", timeMs);
            o.put("timeText", timeText);
        } catch (JSONException ignored) {}
        return o;
    }

    public static Trip fromJson(JSONObject o) {
        Trip t = new Trip();
        try {
            t.type = o.optInt("type", 0);
            t.cost = o.optInt("cost", 0);
            t.balance = o.optInt("balance", 0);
            t.timeMs = o.optLong("timeMs", 0L);
            t.timeText = o.optString("timeText", "");
        } catch (Exception ignored) {}
        return t;
    }

    public String amountText() {
        return String.format(Locale.US, "%s%,d", isTopUp() ? "+" : "-", cost);
    }
}
