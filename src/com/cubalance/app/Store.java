package com.cubalance.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 本地存储：所有数据只存在本机 SharedPreferences，不联网、不上传。
 * 每张卡片一条记录：key(sha256 uid) / uidTail / type / balance / time。
 * 另存全局余额历史，用于画折线图。
 */
public class Store {

    private static final String PREF = "cu_balance_local";
    private static final String K_CARDS = "cards";
    private static final String K_HISTORY = "history";
    private static final String K_TRIPS = "trips_"; // 前缀 + sha256(key) 存每张卡的交易记录
    private static final String K_SNAP = "snap_";   // 前缀 + key：每次读卡的交易记录快照（供详情页按次查看）
    private static final String K_DIAG = "diag";   // 最近一次读卡的诊断日志
    private static final String K_ALIAS = "alias_"; // 前缀 + key：卡片备注名
    private static final String K_ALERT = "alert_"; // 前缀 + key：可用天数提醒线（天，0=关闭）
    private static final String K_ALERT_DAYS = "alerts_use_days"; // 提醒线单位标记：旧版本存的是金额阈值
    private static final String K_ORDER = "order";  // 卡片的显示顺序（key 数组），用户可手动调整

    public static class Entry {
        public String key;
        public String uidTail;
        public String csn;
        public String typeName;
        public int typeCode;
        public int balance;
        public long time;
        public int currency;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("key", key);
                o.put("uidTail", uidTail);
                o.put("csn", csn == null ? "" : csn);
                o.put("typeName", typeName);
                o.put("typeCode", typeCode);
                o.put("balance", balance);
                o.put("time", time);
                o.put("currency", currency);
            } catch (JSONException ignored) {}
            return o;
        }

        public static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            try {
                e.key = o.optString("key", "");
                e.uidTail = o.optString("uidTail", "----");
                e.csn = o.optString("csn", "");
                e.typeName = o.optString("typeName", "交通卡");
                e.typeCode = o.optInt("typeCode", 5);
                e.balance = o.optInt("balance", 0);
                e.time = o.optLong("time", 0L);
                e.currency = o.optInt("currency", 0);
            } catch (Exception ignored) {}
            return e;
        }
    }

    private final SharedPreferences prefs;

    public Store(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        migrateAlertUnit();
    }

    /**
     * 提醒线从「余额金额阈值」改成「预计可用天数」：旧值含义完全不同（₩5,000 会被当成 5,000 天），
     * 升级时统一清掉，让用户按天数重新设一次。
     */
    private void migrateAlertUnit() {
        if (prefs.getBoolean(K_ALERT_DAYS, false)) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : prefs.getAll().keySet()) {
            if (k.startsWith(K_ALERT)) ed.remove(k);
        }
        ed.putBoolean(K_ALERT_DAYS, true).apply();
    }

    /** 读取成功后保存/更新卡片当前状态，并追加一条历史 */
    public void saveRead(TransitCard card, int balance, java.util.List<Trip> trips) {
        List<Entry> cards = loadCards();
        Entry found = null;
        for (Entry e : cards) {
            if (e.key.equals(card.key)) { found = e; break; }
        }
        if (found == null) {
            found = new Entry();
            found.key = card.key;
            cards.add(found);
        }
        found.uidTail = card.uidTail;
        found.csn = card.csn;
        found.typeName = card.typeName;
        found.typeCode = card.typeCode;
        found.balance = balance;
        found.currency = card.currency;
        found.time = System.currentTimeMillis();
        putJsonArray(K_CARDS, cards);

        // 刚读的卡放到列表最前（用户手动排过的其余卡片相对顺序不变）
        List<String> order = loadOrder();
        order.remove(card.key);
        order.add(0, card.key);
        saveOrder(order);

        List<Entry> history = loadHistory();
        Entry h = new Entry();
        h.key = card.key;
        h.uidTail = card.uidTail;
        h.csn = card.csn;
        h.typeName = card.typeName;
        h.typeCode = card.typeCode;
        h.balance = balance;
        h.currency = card.currency;
        h.time = found.time;
        history.add(h);
        // 只保留最近 200 条，避免本地数据无限膨胀
        if (history.size() > 200) {
            history = new ArrayList<>(history.subList(history.size() - 200, history.size()));
        }
        putJsonArray(K_HISTORY, history);

        // 交易记录：读到了才覆盖（读不到时保留上次成功的记录）
        if (trips != null && !trips.isEmpty()) saveTrips(card.key, trips);
        // 每次读卡都留一份快照：这次没读到明细也留，详情页才能逐次区分「没读到」与「没记录」
        saveSnap(card.key, found.time, balance, trips);
    }

    /** 一次读卡时的交易记录快照（trips 为空表示这次读卡没读到明细） */
    public static class Snap {
        public long time;
        public int balance = -1;
        public List<Trip> trips = new ArrayList<>();
    }

    public static final int HIT_EXACT = 0;  // 就是那一次读卡的快照
    public static final int HIT_BEFORE = 1; // 当次没读到明细，沿用更早一次读到的
    public static final int HIT_AFTER = 2;  // 升级前的旧记录，只能用最早一次快照

    public static class SnapHit {
        public Snap snap;
        public int rel;
    }

    /** 追加一次读卡的交易快照：每次 saveRead 都会调用，一条余额记录对应一份 */
    public void saveSnap(String key, long time, int balance, List<Trip> trips) {
        if (key == null || time <= 0L) return;
        List<Snap> list = loadSnaps(key);
        Snap s = new Snap();
        s.time = time;
        s.balance = balance;
        if (trips != null) s.trips = new ArrayList<>(trips);
        list.add(s);
        // 每张卡最多留 60 份快照，够回看且不会无限增长
        while (list.size() > 60) list.remove(0);

        JSONArray arr = new JSONArray();
        for (Snap x : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("time", x.time);
                o.put("balance", x.balance);
                JSONArray ta = new JSONArray();
                for (Trip t : x.trips) ta.put(t.toJson());
                o.put("trips", ta);
            } catch (JSONException ignored) {}
            arr.put(o);
        }
        prefs.edit().putString(K_SNAP + key, arr.toString()).apply();
    }

    public List<Snap> loadSnaps(String key) {
        List<Snap> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_SNAP + key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Snap s = new Snap();
                s.time = o.optLong("time", 0L);
                s.balance = o.optInt("balance", -1);
                JSONArray ta = o.optJSONArray("trips");
                if (ta != null) {
                    for (int j = 0; j < ta.length(); j++) s.trips.add(Trip.fromJson(ta.getJSONObject(j)));
                }
                list.add(s);
            }
        } catch (Exception ignored) {}
        return list;
    }

    /**
     * 取「某次读卡（time 毫秒）」能用的交易明细：
     * 先找自己的快照；自己没有明细（或升级前没有快照）时，退回更早一次读到的 / 最早一次的快照，
     * 并给出 rel 让调用方如实说明数据来自哪一次读卡。
     */
    public SnapHit snapFor(String key, long time) {
        List<Snap> all = loadSnaps(key);
        Snap own = null, prev = null, next = null;
        for (Snap s : all) {
            if (Math.abs(s.time - time) <= 5000L) {
                own = s; // 同一次读卡（允许毫秒误差）
            } else if (s.time < time) {
                if (!s.trips.isEmpty() || prev == null) prev = s; // 只保留最近一次有明细的
            } else if (next == null && !s.trips.isEmpty()) {
                next = s; // 最早一次有明细的
            }
        }
        SnapHit hit = new SnapHit();
        if (own != null && !own.trips.isEmpty()) {
            hit.snap = own; hit.rel = HIT_EXACT;
        } else if (prev != null && !prev.trips.isEmpty()) {
            hit.snap = prev; hit.rel = HIT_BEFORE;
        } else if (own != null) {
            hit.snap = own; hit.rel = HIT_EXACT; // 这次确实没读到明细
        } else if (next != null) {
            hit.snap = next; hit.rel = HIT_AFTER;
        }
        return hit.snap == null ? null : hit;
    }

    public void saveDiag(String diag) {
        prefs.edit().putString(K_DIAG, diag == null ? "" : diag).apply();
    }

    public String loadDiag() {
        return prefs.getString(K_DIAG, "");
    }

    public void saveTrips(String key, List<Trip> trips) {
        JSONArray arr = new JSONArray();
        for (Trip t : trips) arr.put(t.toJson());
        prefs.edit().putString(K_TRIPS + key, arr.toString()).apply();
    }

    /** 某张卡的交易记录，卡内最多约 20 条 */
    public List<Trip> loadTrips(String key) {
        List<Trip> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_TRIPS + key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                list.add(Trip.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException ignored) {}
        return list;
    }

    public List<Entry> loadCards() {
        List<Entry> list = parseArray(prefs.getString(K_CARDS, "[]"));
        final List<String> order = loadOrder();
        Collections.sort(list, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int ia = order.indexOf(a.key);
                int ib = order.indexOf(b.key);
                if (ia < 0 && ib < 0) return Long.compare(b.time, a.time); // 未排序的按最近读卡
                if (ia < 0) return 1;
                if (ib < 0) return -1;
                return Integer.compare(ia, ib);
            }
        });
        return list;
    }

    /** 卡片备注名，空串 = 未设置 */
    public String aliasOf(String key) {
        return key == null ? "" : prefs.getString(K_ALIAS + key, "");
    }

    public void setAlias(String key, String alias) {
        if (key == null) return;
        if (alias == null || alias.trim().isEmpty()) prefs.edit().remove(K_ALIAS + key).apply();
        else prefs.edit().putString(K_ALIAS + key, alias.trim()).apply();
    }

    /** 卡片的显示名：优先别名，其次卡类型 */
    public String nameOf(Entry e) {
        String alias = aliasOf(e.key);
        return alias.isEmpty() ? e.typeName : alias;
    }

    /** 可用天数提醒线（天），0 = 关闭 */
    public int alertOf(String key) {
        if (key == null) return 0;
        int v = prefs.getInt(K_ALERT + key, 0);
        return (v < 1 || v > 365) ? 0 : v;
    }

    public void setAlert(String key, int days) {
        if (key == null) return;
        if (days <= 0) prefs.edit().remove(K_ALERT + key).apply();
        else prefs.edit().putInt(K_ALERT + key, Math.min(days, 365)).apply();
    }

    /** 上移/下移一张卡：delta = -1 上移，+1 下移 */
    public void moveCard(String key, int delta) {
        List<String> order = new ArrayList<>();
        for (Entry e : loadCards()) order.add(e.key);
        int i = order.indexOf(key);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= order.size()) return;
        Collections.swap(order, i, j);
        saveOrder(order);
    }

    private List<String> loadOrder() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_ORDER, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    private void saveOrder(List<String> keys) {
        JSONArray arr = new JSONArray();
        for (String k : keys) arr.put(k);
        prefs.edit().putString(K_ORDER, arr.toString()).apply();
    }

    public List<Entry> loadHistory() {
        List<Entry> list = parseArray(prefs.getString(K_HISTORY, "[]"));
        Collections.sort(list, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return Long.compare(a.time, b.time);
            }
        });
        return list;
    }

    /** 某张卡的历史（按时间升序） */
    public List<Entry> historyOf(String key) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : loadHistory()) {
            if (e.key.equals(key)) out.add(e);
        }
        return out;
    }

    /** 删掉某一条余额记录（连带那一次读卡的快照），卡片本身不动 */
    public boolean deleteHistory(String key, long time) {
        boolean removed = false;
        List<Entry> hist = new ArrayList<>();
        for (Entry e : loadHistory()) {
            if (!removed && e.key.equals(key) && e.time == time) { removed = true; continue; }
            hist.add(e);
        }
        if (!removed) return false;
        putJsonArray(K_HISTORY, hist);

        // 同一次读卡的快照一起删，避免列表里看不到、数据还留着
        List<Snap> snaps = loadSnaps(key);
        boolean snapRemoved = false;
        List<Snap> keep = new ArrayList<>();
        for (Snap s : snaps) {
            if (!snapRemoved && Math.abs(s.time - time) <= 5000L) { snapRemoved = true; continue; }
            keep.add(s);
        }
        if (snapRemoved) {
            JSONArray arr = new JSONArray();
            for (Snap x : keep) {
                JSONObject o = new JSONObject();
                try {
                    o.put("time", x.time);
                    o.put("balance", x.balance);
                    JSONArray ta = new JSONArray();
                    for (Trip t : x.trips) ta.put(t.toJson());
                    o.put("trips", ta);
                } catch (JSONException ignored) {}
                arr.put(o);
            }
            prefs.edit().putString(K_SNAP + key, arr.toString()).apply();
        }
        return true;
    }

    public void deleteCard(String key) {
        List<Entry> cards = new ArrayList<>();
        for (Entry e : loadCards()) if (!e.key.equals(key)) cards.add(e);
        putJsonArray(K_CARDS, cards);
        List<Entry> hist = new ArrayList<>();
        for (Entry e : loadHistory()) if (!e.key.equals(key)) hist.add(e);
        putJsonArray(K_HISTORY, hist);
        List<String> order = loadOrder();
        order.remove(key);
        saveOrder(order);
        prefs.edit().remove(K_TRIPS + key).remove(K_SNAP + key)
                .remove(K_ALIAS + key).remove(K_ALERT + key).apply();
    }

    public void clearAll() {
        android.content.SharedPreferences.Editor ed = prefs.edit()
                .remove(K_CARDS).remove(K_HISTORY).remove(K_ORDER);
        for (Entry e : loadCards()) {
            ed.remove(K_TRIPS + e.key).remove(K_SNAP + e.key)
                    .remove(K_ALIAS + e.key).remove(K_ALERT + e.key);
        }
        ed.apply();
    }

    private void putJsonArray(String key, List<Entry> list) {
        JSONArray arr = new JSONArray();
        for (Entry e : list) arr.put(e.toJson());
        prefs.edit().putString(key, arr.toString()).apply();
    }

    private List<Entry> parseArray(String json) {
        List<Entry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(Entry.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException ignored) {}
        return list;
    }
}
