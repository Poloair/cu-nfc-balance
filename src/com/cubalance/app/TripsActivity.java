package com.cubalance.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 消费记录列表（来自卡内交易记录，卡最多存约 20 条）
 *  传 snapTime（毫秒）时展示「那一次读卡」看到的明细，便于从余额记录回看当次。 */
public class TripsActivity extends Activity {

    private Store store;
    private String key;
    private long snapTime = 0L;
    private int snapBalance = -1; // 当次快照里的余额（-1 表示用卡片当前余额）
    private int cur = 0; // 当前卡币种
    private ListView lv;
    private TextView tvEmpty, tvSumBalance, tvSumPay, tvSumTopup, tvSnap;
    private LinearLayout sumPanel;
    private List<Trip> trips = new ArrayList<>();
    private TripAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trips);
        store = new Store(this);
        key = getIntent().getStringExtra("key");
        snapTime = getIntent().getLongExtra("snapTime", 0L);

        lv = findViewById(R.id.lvTrips);
        tvEmpty = findViewById(R.id.tvTripsEmpty);
        tvSnap = findViewById(R.id.tvTripsSnap);
        sumPanel = findViewById(R.id.sumPanel);
        tvSumBalance = findViewById(R.id.tvSumBalance);
        tvSumPay = findViewById(R.id.tvSumPay);
        tvSumTopup = findViewById(R.id.tvSumTopup);
        adapter = new TripAdapter();
        lv.setAdapter(adapter);

        findViewById(R.id.btnTripsBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        // 诊断入口隐藏：长按标题打开（需要排查时仍可触达）
        findViewById(R.id.tvTripsTitle).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showDiag(); return true; }
        });
        load();
    }

    private void showDiag() {
        final String d = store.loadDiag();
        Dialogs.show(TripsActivity.this, "读卡诊断",
                d == null || d.isEmpty() ? "无诊断数据" : d,
                getString(R.string.ok), null,
                null, null,
                "复制诊断", new Dialogs.Click() {
                    @Override public void onClick(android.app.AlertDialog dd) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", d));
                            Toast.makeText(TripsActivity.this, "诊断已复制，可粘贴发我", Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {}
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        long latestRead = 0L;
        for (Store.Entry e : store.loadCards()) {
            if (e.key.equals(key)) { cur = e.currency; latestRead = e.time; break; }
        }

        if (snapTime > 0) {
            // 按「这一次读卡」回看：优先当次快照，没有明细则沿用更早一次读到的
            Store.SnapHit hit = store.snapFor(key, snapTime);
            if (hit != null && !hit.snap.trips.isEmpty()) {
                trips = hit.snap.trips;
                snapBalance = hit.snap.balance;
                String when = fmt(hit.snap.time);
                if (hit.rel == Store.HIT_EXACT) {
                    tvSnap.setText("读取于 " + when + " · 卡内交易明细");
                } else if (hit.rel == Store.HIT_BEFORE) {
                    tvSnap.setText("当次没读到明细 · 沿用 " + when + " 读到的明细");
                } else {
                    tvSnap.setText("升级前的记录 · 显示最早一次读到的明细（" + when + "）");
                }
            } else {
                if (hit != null) snapBalance = hit.snap.balance;
                trips = store.loadTrips(key);
                tvSnap.setText(trips.isEmpty()
                        ? "这次读卡没有读到交易明细"
                        : "升级前的记录 · 没有当次快照 · 显示最近一次读到的明细");
            }
            tvSnap.setVisibility(View.VISIBLE);
        } else {
            // 保持卡内原始顺序（rec1=最近 → recN=最早），不做排序
            trips = store.loadTrips(key);
            if (latestRead > 0) {
                tvSnap.setText("最近一次读取于 " + fmt(latestRead)
                        + (trips.isEmpty() ? "" : " · 卡内最近 " + trips.size() + " 笔"));
                tvSnap.setVisibility(View.VISIBLE);
            } else {
                tvSnap.setVisibility(View.GONE);
            }
        }

        tvEmpty.setVisibility(trips.isEmpty() ? View.VISIBLE : View.GONE);
        lv.setVisibility(trips.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        updateSummary();
    }

    /** 本卡概览：当前余额 + 消费/充值笔数 */
    private void updateSummary() {
        int payCount = 0, topupCount = 0;
        for (Trip t : trips) {
            if (t.isTopUp()) topupCount++; else payCount++;
        }
        // 当次回看：用快照里那次读卡的余额；否则用保存的卡片余额
        int balance = -1;
        if (snapTime > 0 && snapBalance >= 0) {
            balance = snapBalance;
        } else {
            for (Store.Entry e : store.loadCards()) {
                if (e.key.equals(key)) { balance = e.balance; break; }
            }
        }
        if (balance < 0 && !trips.isEmpty() && trips.get(0).balance >= 0) {
            balance = trips.get(0).balance;
        }
        if (balance < 0) balance = 0;
        tvSumBalance.setText(Money.fmt(cur, balance));
        tvSumPay.setText(payCount + " 笔");
        tvSumTopup.setText(topupCount + " 笔");
        sumPanel.setVisibility(trips.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String fmt(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    private class TripAdapter extends BaseAdapter {
        @Override public int getCount() { return trips.size(); }
        @Override public Object getItem(int p) { return trips.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_trip, parent, false);
            Trip t = trips.get(pos);
            TextView kind = cv.findViewById(R.id.tripKind);
            TextView amount = cv.findViewById(R.id.tripAmount);
            TextView after = cv.findViewById(R.id.tripAfter);

            boolean topup = t.isTopUp();
            kind.setText(topup ? R.string.txn_topup : R.string.txn_pay);
            // 配色收敛：充值用品牌绿，消费用深灰，避免整页绿色刺眼
            kind.setTextColor(getResources().getColor(topup ? R.color.gain : R.color.text_main));
            // 消费后余额不变（cost=0）显示"消费 0"；T-Union 等记录不含交易后余额时显示"余 —"
            amount.setText(Money.signed(cur, topup, t.cost));
            amount.setTextColor(getResources().getColor(topup ? R.color.gain : R.color.text_main));
            after.setText(t.balance < 0 ? "余 —" : "余 " + Money.fmt(cur, t.balance));
            return cv;
        }
    }
}
