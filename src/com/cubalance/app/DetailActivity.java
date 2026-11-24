package com.cubalance.app;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 单卡详情：余额折线图 + 历史记录 + 删除 */
public class DetailActivity extends Activity {

    private Store store;
    private String key;
    private TextView tvTitle, tvSub;
    private BalanceChart chart;
    private ListView lvHistory;
    private TextView btnHistMore;
    private HistAdapter adapter;
    private List<Store.Entry> hist = new ArrayList<>();
    /** 余额记录默认只摆 3 条多（第 4 条露半截提示还有），更多的折叠，不无限平铺 */
    private static final int HIST_COLLAPSED = 3;
    private boolean histExpanded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        store = new Store(this);
        key = getIntent().getStringExtra("key");

        tvTitle = findViewById(R.id.detailTitle);
        tvSub = findViewById(R.id.detailSub);
        chart = findViewById(R.id.chart);
        lvHistory = findViewById(R.id.lvHistory);
        btnHistMore = findViewById(R.id.btnHistMore);
        adapter = new HistAdapter();
        lvHistory.setAdapter(adapter);
        // 点某条余额记录 → 看「那一次读卡」读到的交易明细（不是这张卡的最新明细）
        lvHistory.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Store.Entry e = hist.get(hist.size() - 1 - position); // 列表是倒序（最新在上）
                Intent i = new Intent(DetailActivity.this, TripsActivity.class);
                i.putExtra("key", key);
                i.putExtra("snapTime", e.time);
                startActivity(i);
            }
        });
        // 长按某条余额记录 → 删掉这一条（连带当次快照），卡片本身保留
        lvHistory.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view,
                                                     int position, long id) {
                final Store.Entry e = hist.get(hist.size() - 1 - position);
                Dialogs.confirm(DetailActivity.this, "删除这条记录",
                        "删除 " + fmt(e.time) + " 这次读取的余额记录？\n对应的读卡明细快照也会一起删掉，卡片本身不受影响。",
                        new Dialogs.Click() {
                            @Override public void onClick(android.app.AlertDialog d) {
                                if (store.deleteHistory(key, e.time)) {
                                    Toast.makeText(DetailActivity.this, "已删除这条记录",
                                            Toast.LENGTH_SHORT).show();
                                    load();
                                }
                            }
                        });
                return true;
            }
        });
        // 展开后列表在卡片内自己滚动，别被外层 ScrollView 抢走手势
        lvHistory.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                if (histExpanded && ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            }
        });
        btnHistMore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                histExpanded = !histExpanded;
                renderHistHeight();
                if (!histExpanded) lvHistory.setSelection(0);
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnDelete).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(); }
        });
        findViewById(R.id.rowAlias).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editAlias(); }
        });
        findViewById(R.id.rowAlert).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editAlert(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(); // 从别处改完备注 / 删卡回来时同步
    }

    /** 这张卡的币种（取不到就用历史里最新一条） */
    private int cardCurrency() {
        for (Store.Entry e : store.loadCards()) {
            if (e.key.equals(key)) return e.currency;
        }
        return hist.isEmpty() ? Money.KRW : hist.get(hist.size() - 1).currency;
    }

    private void editAlias() {
        Dialogs.input(this, "卡片备注", store.aliasOf(key),
                "给这张卡起个名字，留空清除", new Dialogs.Input() {
                    @Override public void onOk(String text) {
                        store.setAlias(key, text);
                        load();
                    }
                });
    }

    private void editAlert() {
        final int cur = cardCurrency();
        final int days = store.alertOf(key);
        Forecast f = forecastOf(currentBalance());
        String preset = days <= 0 ? "" : String.valueOf(days);
        String msg = (f.ok()
                ? "按最近消费估算，这张卡现在" + f.daysText() + "。\n" + f.basis(cur) + "。"
                : "还读不到足够的消费记录，多贴几次卡就能估算。")
                + "\n\n余额能用的天数低于设定值时就提醒你充值（填 1-365），留空关闭。";
        Dialogs.input(this, "可用天数提醒", msg, preset, "如 3", new Dialogs.Input() {
            @Override public void onOk(String text) {
                try {
                    String t = text.trim();
                    int v = t.isEmpty() ? 0 : Integer.parseInt(t);
                    store.setAlert(key, v);
                } catch (NumberFormatException ignored) {
                }
                load();
            }
        });
    }

    /** 当前余额：优先卡片记录，没有再退回最新一条历史 */
    private int currentBalance() {
        for (Store.Entry e : store.loadCards()) {
            if (e.key.equals(key)) return e.balance;
        }
        return hist.isEmpty() ? 0 : hist.get(hist.size() - 1).balance;
    }

    /** 按最近消费估算可用天数 */
    private Forecast forecastOf(int balance) {
        return Forecast.of(balance, store.loadTrips(key), hist);
    }

    private void load() {
        hist = store.historyOf(key);
        // 标题用「卡片当前余额」：删掉某条历史记录时余额不会跟着跳
        Store.Entry card = null;
        for (Store.Entry e : store.loadCards()) if (e.key.equals(key)) card = e;
        Store.Entry latest = card;
        if (latest == null) for (Store.Entry e : hist) latest = e;
        int cur = latest != null ? latest.currency : Money.KRW;
        String alias = store.aliasOf(key);
        if (latest != null) {
            tvTitle.setText(Money.fmt(cur, latest.balance));
            tvSub.setText((alias.isEmpty() ? "" : alias + " · ") + latest.typeName
                    + "  ••" + latest.uidTail
                    + "  ·  " + fmt(latest.time) + "  ·  共 " + hist.size() + " 次记录");
        }
        chart.setEntries(hist);
        chart.setCurrency(cur);
        if (hist.size() <= HIST_COLLAPSED) histExpanded = false;
        renderHistHeight();
        // 首次测量后按真实行高再算一次，避免估算值导致半条露得太多或太少
        lvHistory.post(new Runnable() {
            @Override public void run() { renderHistHeight(); }
        });

        TextView hint = findViewById(R.id.tvHistHint);
        hint.setText(hist.isEmpty()
                ? "还没有余额记录，贴卡读一次就会出现"
                : "共 " + hist.size() + " 次记录 · 点一条看当次明细 · 长按删除");

        // 卡片管理行
        TextView tvAlias = findViewById(R.id.tvAlias);
        tvAlias.setText(alias.isEmpty() ? "未设置" : alias);
        TextView tvAlert = findViewById(R.id.tvAlert);
        int limit = store.alertOf(key);
        Forecast f = forecastOf(latest != null ? latest.balance : 0);
        String now = f.ok() ? "现" + f.daysText() : "数据不足";
        tvAlert.setText(limit <= 0 ? "关闭 · " + now : "低于 " + limit + " 天提醒 · " + now);
    }

    /** 余额记录区高度：未展开=3 条半（第 4 条露出一点，提示还有）；展开=固定高度滚轮 */
    private void renderHistHeight() {
        int total = hist.size();
        int rowH = rowHeight();
        ViewGroup.LayoutParams lp = lvHistory.getLayoutParams();
        if (total <= HIST_COLLAPSED) {
            histExpanded = false;
            btnHistMore.setVisibility(View.GONE);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else if (histExpanded) {
            btnHistMore.setVisibility(View.VISIBLE);
            btnHistMore.setText("收起 · 只看前 " + HIST_COLLAPSED + " 条");
            lp.height = rowH * 11 / 2; // 5 条半的视口，里面滚动
        } else {
            btnHistMore.setVisibility(View.VISIBLE);
            btnHistMore.setText("展开全部 " + total + " 次");
            lp.height = rowH * 7 / 2;  // 3 条半
        }
        lvHistory.setLayoutParams(lp);
        lvHistory.requestLayout();
        adapter.notifyDataSetChanged();
    }

    /** 单行高度：列表已经有子项就用实测值，否则按布局估算 */
    private int rowHeight() {
        if (lvHistory.getChildCount() > 0) {
            int h = lvHistory.getChildAt(0).getHeight();
            if (h > 0) return h;
        }
        return dp(48);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private void confirmDelete() {
        Dialogs.confirm(this,
                getString(R.string.delete), getString(R.string.confirm_delete),
                new Dialogs.Click() {
                    @Override public void onClick(android.app.AlertDialog d) {
                        store.deleteCard(key);
                        Toast.makeText(DetailActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
    }

    private String fmt(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    private class HistAdapter extends BaseAdapter {
        /** 倒序显示：最新一次在最上面；收起来时给 3 条 + 半条（露一点提示还有更多） */
        @Override public int getCount() {
            return histExpanded ? hist.size() : Math.min(hist.size(), HIST_COLLAPSED + 1);
        }
        @Override public Object getItem(int p) { return hist.get(hist.size() - 1 - p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_history, parent, false);
            TextView bal = cv.findViewById(R.id.hBal);
            TextView time = cv.findViewById(R.id.hTime);
            TextView diff = cv.findViewById(R.id.hDiff);
            int idx = hist.size() - 1 - pos; // 在升序 history 里的下标
            Store.Entry e = hist.get(idx);
            int cur = e.currency;
            bal.setText(Money.fmt(cur, e.balance));
            time.setText(fmt(e.time));
            if (idx > 0) {
                // 跟「上一次读卡」（更早那条）比，涨红跌绿
                int d = e.balance - hist.get(idx - 1).balance;
                if (d > 0) { diff.setText("+" + Money.num(cur, d)); diff.setTextColor(getResources().getColor(R.color.up)); }
                else if (d < 0) { diff.setText(Money.num(cur, d)); diff.setTextColor(getResources().getColor(R.color.down)); }
                else { diff.setText("0"); diff.setTextColor(getResources().getColor(R.color.text_sub)); }
                diff.setVisibility(View.VISIBLE);
            } else {
                diff.setVisibility(View.GONE);
            }
            return cv;
        }
    }
}
