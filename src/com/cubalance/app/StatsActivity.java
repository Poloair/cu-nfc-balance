package com.cubalance.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 使用统计（二级页面）：收支账本。
 * 「收入 / 支出 / 净变化 / 笔数」汇总 + 单笔金额柱状图 + 逐笔收支明细（带交易时间）。
 */
public class StatsActivity extends Activity {

    private Store store;
    private String key;
    private int cur = Money.KRW;
    private TextView tvCard, tvRange, tvIn, tvOut, tvNet, tvCount, tvMix, tvEmpty;
    private LinearLayout txnList, barStatsCard;
    private BarChart barStats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        store = new Store(this);
        key = getIntent().getStringExtra("key");

        tvCard = findViewById(R.id.tvStatsCard);
        tvRange = findViewById(R.id.tvStatsRange);
        tvIn = findViewById(R.id.tvStatsIn);
        tvOut = findViewById(R.id.tvStatsOut);
        tvNet = findViewById(R.id.tvStatsNet);
        tvCount = findViewById(R.id.tvStatsCount);
        tvMix = findViewById(R.id.tvStatsMix);
        tvEmpty = findViewById(R.id.tvStatsEmpty);
        txnList = findViewById(R.id.txnList);
        barStatsCard = findViewById(R.id.barStatsCard);
        barStats = findViewById(R.id.barStats);

        findViewById(R.id.btnStatsBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        List<Store.Entry> cards = store.loadCards();
        Store.Entry card = null;
        for (Store.Entry e : cards) {
            if (key != null && e.key.equals(key)) { card = e; break; }
        }
        if (card == null && !cards.isEmpty()) card = cards.get(0); // 兜底：没有传 key 时用第一张
        if (card != null) {
            key = card.key;
            cur = card.currency;
        }

        List<Trip> trips = key == null ? new java.util.ArrayList<Trip>() : store.loadTrips(key);
        String name = card == null ? "交通卡" : store.nameOf(card);
        tvCard.setText(name);

        StringBuilder range = new StringBuilder();
        if (card != null) {
            range.append("当前余额 ").append(Money.fmt(cur, card.balance)).append("  ·  ");
            range.append("最近读取 ").append(fmt(card.time));
        } else {
            range.append("还没有读取过卡片");
        }
        tvRange.setText(range.toString());

        int income = 0, expense = 0, topupCount = 0, payCount = 0;
        for (Trip t : trips) {
            if (t.isTopUp()) { income += t.cost; topupCount++; }
            else { expense += t.cost; payCount++; }
        }
        int net = income - expense;

        tvIn.setText(Money.fmt(cur, income));
        tvOut.setText(Money.fmt(cur, expense));
        if (net == 0) {
            tvNet.setText("0 " + Money.symbol(cur));
            tvNet.setTextColor(getResources().getColor(R.color.text_sub));
        } else {
            tvNet.setText((net > 0 ? "+" : "-") + Money.num(cur, Math.abs(net)) + " " + Money.symbol(cur));
            tvNet.setTextColor(getResources().getColor(net > 0 ? R.color.gain : R.color.up));
        }
        tvCount.setText(trips.size() + " 笔");
        tvMix.setText("消费 " + payCount + " · 充值 " + topupCount);

        // 单笔金额柱状图：卡内记录本身是「最近 → 最早」，BarChart 内部会翻成 旧 → 新
        barStatsCard.setVisibility(trips.isEmpty() ? View.GONE : View.VISIBLE);
        if (!trips.isEmpty()) barStats.setTrips(trips, cur);

        // 逐笔收支明细
        txnList.removeAllViews();
        txnList.setVisibility(trips.isEmpty() ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(trips.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < trips.size(); i++) {
            Trip t = trips.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_txn, txnList, false);
            TextView kind = row.findViewById(R.id.txnKind);
            TextView time = row.findViewById(R.id.txnTime);
            TextView amount = row.findViewById(R.id.txnAmount);
            TextView after = row.findViewById(R.id.txnAfter);

            boolean topup = t.isTopUp();
            kind.setText(topup ? "收入 · 充值" : "支出 · 消费");
            kind.setTextColor(getResources().getColor(topup ? R.color.gain : R.color.text_main));
            amount.setText(Money.signed(cur, topup, t.cost));
            amount.setTextColor(getResources().getColor(topup ? R.color.gain : R.color.text_main));
            time.setText(t.timeMs > 0 ? fmt(t.timeMs) : "第 " + (i + 1) + " 笔（卡内未记时间）");
            after.setText(t.balance < 0 ? "余 —" : "余 " + Money.fmt(cur, t.balance));
            txnList.addView(row);
        }
    }

    private String fmt(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }
}
