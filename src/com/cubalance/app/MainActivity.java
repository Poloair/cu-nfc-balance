package com.cubalance.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] filters;
    private String[][] techLists;

    private Store store;
    private TextView tvBalance, tvCur, tvCardType, tvUid, tvCsn, tvTime, tvStatus, tvDiff;
    private TextView tvCardTitle;
    private LinearLayout resultPanel, emptyPanel, statPanel;
    private TextView tvStatSpend, tvStatTopup, tvStatCount;
    private boolean reading = false;
    private boolean nfcTipShown = false; // 本次停留只提示一次 NFC 问题
    private CardReader.Result lastResult;
    private String currentKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);

        tvBalance = findViewById(R.id.tvBalance);
        tvCur = findViewById(R.id.tvCur);
        tvCardType = findViewById(R.id.tvCardType);
        tvUid = findViewById(R.id.tvUid);
        tvCsn = findViewById(R.id.tvCsn);
        tvTime = findViewById(R.id.tvTime);
        tvStatus = findViewById(R.id.tvStatus);
        tvDiff = findViewById(R.id.tvDiff);
        resultPanel = findViewById(R.id.resultPanel);
        emptyPanel = findViewById(R.id.emptyPanel);
        statPanel = findViewById(R.id.statPanel);
        tvStatSpend = findViewById(R.id.tvStatSpend);
        tvStatTopup = findViewById(R.id.tvStatTopup);
        tvStatCount = findViewById(R.id.tvStatCount);
        tvCardTitle = findViewById(R.id.tvCardTitle);

        findViewById(R.id.btnScan).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onScanEntry(); }
        });
        findViewById(R.id.btnTrips).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openTrips(); }
        });
        // 使用统计改成二级页面：点开看收支账本
        statPanel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openStats(); }
        });
        BottomBar.setup(this, 0);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        // FLAG_MUTABLE = 0x02000000（API 31+ 常量），用数值兼容低版本编译与运行
        int piFlag = (android.os.Build.VERSION.SDK_INT >= 31) ? 0x02000000 : 0;
        pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                piFlag);
        IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        filters = new IntentFilter[]{tech};
        techLists = new String[][]{
                new String[]{"android.nfc.tech.IsoDep"},
                new String[]{"android.nfc.tech.NfcA", "android.nfc.tech.IsoDep"},
                new String[]{"android.nfc.tech.NfcF"} // 日本/香港 FeliCa 卡
        };

        checkNfc();
        // 若上次读过卡，启动时展示最近一次结果
        List<Store.Entry> cards = store.loadCards();
        if (!cards.isEmpty()) showCard(cards.get(0), true);
        handleIntent(getIntent());
    }

    /** NFC 状态检测：无硬件 / 未开启时状态栏提示 + 弹窗，可直接跳到系统 NFC 设置 */
    private void checkNfc() {
        if (nfcAdapter == null) {
            tvStatus.setText(R.string.nfc_unsupported);
            if (!nfcTipShown) {
                nfcTipShown = true;
                Dialogs.info(this, getString(R.string.nfc_unsupported),
                        getString(R.string.nfc_unsupported_tip));
            }
        } else if (!nfcAdapter.isEnabled()) {
            tvStatus.setText(R.string.nfc_off);
            if (!nfcTipShown) {
                nfcTipShown = true;
                showNfcOffDialog();
            }
        } else {
            tvStatus.setText(R.string.hint_title);
            nfcTipShown = false; // 已开启，下次关掉时还能再提醒
        }
    }

    /** 未开启 NFC：去开启 / 稍后 */
    private void showNfcOffDialog() {
        Dialogs.show(this, getString(R.string.nfc_off), getString(R.string.nfc_off_msg),
                getString(R.string.enable_nfc), new Dialogs.Click() {
                    @Override public void onClick(android.app.AlertDialog d) { openNfcSettings(); }
                },
                getString(R.string.later), null,
                null, null);
    }

    /** 跳系统 NFC 设置页；个别机型没有独立页面时退到无线设置 */
    private void openNfcSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_NFC_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "请手动打开系统设置 → NFC", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 点击贴卡入口：先确认 NFC 可用，再说明支持卡种 */
    private void onScanEntry() {
        if (nfcAdapter == null) {
            Dialogs.info(this, getString(R.string.nfc_unsupported),
                    getString(R.string.nfc_unsupported_tip));
        } else if (!nfcAdapter.isEnabled()) {
            showNfcOffDialog();
        } else {
            Toast.makeText(this, "把卡片贴到手机背面 NFC 区域，保持 1-2 秒", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, filters, techLists);
        }
        checkNfc();
        // 从详情页改完备注/排序回来，卡片标题可能有变化
        List<Store.Entry> cards = store.loadCards();
        if (currentKey != null) {
            for (Store.Entry e : cards) {
                if (e.key.equals(currentKey)) {
                    String alias = store.aliasOf(e.key);
                    tvCardTitle.setText(alias.isEmpty() ? "卡片余额" : alias);
                    break;
                }
            }
        }
        BlurBar.refresh(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if ((NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TAG_DISCOVERED.equals(action))
                && tag != null) {
            if (!reading) new ReadTask().execute(tag);
        }
    }

    private class ReadTask extends AsyncTask<Tag, Void, Object> {
        @Override protected void onPreExecute() {
            reading = true;
            tvStatus.setText(R.string.reading);
        }
        @Override protected Object doInBackground(Tag... tags) {
            // 贴卡不稳导致通信中断时自动重试一次；AID 不匹配等确定性错误不重试
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return CardReader.read(tags[0]);
                } catch (CardReader.ReadException e) {
                    boolean transientFail = e.getMessage() != null && e.getMessage().startsWith("通信失败");
                    if (attempt == 0 && transientFail) {
                        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    return e;
                } catch (Exception e) {
                    return e;
                }
            }
            return null;
        }
        @Override protected void onPostExecute(Object o) {
            reading = false;
            if (o instanceof CardReader.Result) {
                CardReader.Result r = (CardReader.Result) o;
                lastResult = r;
                store.saveRead(r.card, r.balance, r.trips);
                store.saveDiag(r.diag);
                showResult(r);
                vibrate();
                Toast.makeText(MainActivity.this, R.string.read_ok, Toast.LENGTH_SHORT).show();
                checkLowBalance(r);
            } else {
                String msg = "未知错误";
                final String diag;
                if (o instanceof CardReader.ReadException) {
                    msg = ((CardReader.ReadException) o).getMessage();
                    diag = ((CardReader.ReadException) o).diag;
                } else if (o instanceof Throwable) {
                    msg = ((Throwable) o).getMessage();
                    diag = "(无诊断数据)";
                } else {
                    diag = "(无诊断数据)";
                }
                tvStatus.setText(R.string.read_fail);
                showFailDialog(msg, diag);
            }
        }
    }

    private void showResult(CardReader.Result r) {
        emptyPanel.setVisibility(View.GONE);
        resultPanel.setVisibility(View.VISIBLE);
        tvCur.setText(Money.symbol(r.card.currency));
        tvBalance.setText(Money.num(r.card.currency, r.balance));
        tvCardType.setText(r.card.typeName);
        tvUid.setText(r.card.uidTail);
        tvCsn.setText(r.card.csn == null || r.card.csn.isEmpty() ? "未读到" : r.card.csn);
        currentKey = r.card.key;
        tvTime.setText(fmt(System.currentTimeMillis()));

        List<Store.Entry> hist = store.historyOf(r.card.key);
        if (hist.size() >= 2) {
            int prev = hist.get(hist.size() - 2).balance;
            int diff = r.balance - prev;
            if (diff > 0) {
                tvDiff.setText("▲ +" + Money.num(r.card.currency, diff) + " " + Money.symbol(r.card.currency));
                tvDiff.setTextColor(getResources().getColor(R.color.up));
            } else if (diff < 0) {
                tvDiff.setText("▼ -" + Money.num(r.card.currency, -diff) + " " + Money.symbol(r.card.currency));
                tvDiff.setTextColor(getResources().getColor(R.color.down));
            } else {
                tvDiff.setText("— 0");
                tvDiff.setTextColor(getResources().getColor(R.color.text_sub));
            }
            tvDiff.setVisibility(View.VISIBLE);
        } else {
            tvDiff.setVisibility(View.GONE);
        }
        tvStatus.setText(R.string.read_ok);
        String alias = store.aliasOf(r.card.key);
        tvCardTitle.setText(alias.isEmpty() ? "卡片余额" : alias);
        updateStats();
        BlurBar.refresh(this);
    }

    private void showCard(Store.Entry e, boolean silent) {
        emptyPanel.setVisibility(View.GONE);
        resultPanel.setVisibility(View.VISIBLE);
        tvCur.setText(Money.symbol(e.currency));
        tvBalance.setText(Money.num(e.currency, e.balance));
        tvCardType.setText(e.typeName);
        String alias = store.aliasOf(e.key);
        tvCardTitle.setText(alias.isEmpty() ? "卡片余额" : alias);
        tvUid.setText(e.uidTail);
        tvCsn.setText(e.csn == null || e.csn.isEmpty() ? "未读到" : e.csn);
        currentKey = e.key;
        tvTime.setText(fmt(e.time));
        tvDiff.setVisibility(View.GONE);
        if (!silent) tvStatus.setText(R.string.read_ok);
        updateStats();
    }

    /**
     * 读卡后按「预计可用天数」判断是否提醒：
     * 用这次读到的交易记录估算日均消费，算出余额还能用几天，低于该卡设置的天数就弹窗。
     * 记录不足（估不出来）时不打扰，详情页会写「数据不足」。
     */
    private void checkLowBalance(CardReader.Result r) {
        int limit = store.alertOf(r.card.key);
        if (limit <= 0) return;
        List<Trip> trips = (r.trips != null && !r.trips.isEmpty())
                ? r.trips : store.loadTrips(r.card.key);
        Forecast f = Forecast.of(r.balance, trips, store.historyOf(r.card.key));
        if (!f.ok() || f.daysLeft > limit) return;

        String alias = store.aliasOf(r.card.key);
        final String name = alias.isEmpty() ? r.card.typeName : alias;
        final String key = r.card.key;
        Dialogs.show(this, "该充值了",
                "「" + name + "」当前余额 " + Money.fmt(r.card.currency, r.balance)
                        + "，" + f.basis(r.card.currency)
                        + "，预计还能用 " + f.daysLeft + " 天，已经到了你设的 "
                        + limit + " 天提醒线。\n\n"
                        + "提醒线可以在「卡片 → 这张卡 → 可用天数提醒」里改。",
                getString(R.string.ok), null,
                "查看卡片", new Dialogs.Click() {
                    @Override public void onClick(android.app.AlertDialog d) {
                        Intent i = new Intent(MainActivity.this, DetailActivity.class);
                        i.putExtra("key", key);
                        startActivity(i);
                    }
                }, null, null);
    }

    /** 首页只留汇总入口，图表与收支明细在「使用统计」二级页 */
    private void updateStats() {
        if (currentKey == null) { statPanel.setVisibility(View.GONE); return; }
        List<Trip> trips = store.loadTrips(currentKey);
        List<Store.Entry> hist = store.historyOf(currentKey);
        if (trips.isEmpty() && hist.isEmpty()) { statPanel.setVisibility(View.GONE); return; }
        int cur = currencyOf(currentKey, hist);

        if (trips.isEmpty()) {
            tvStatSpend.setText("未读到");
            tvStatTopup.setText("未读到");
            tvStatCount.setText("已记录 " + hist.size() + " 次余额 · 点开查看收支账本");
        } else {
            int spend = 0, topup = 0;
            for (Trip t : trips) {
                if (t.isTopUp()) topup += t.cost; else spend += t.cost;
            }
            tvStatSpend.setText(Money.fmt(cur, spend));
            tvStatTopup.setText(Money.fmt(cur, topup));
            tvStatCount.setText("共 " + trips.size() + " 笔交易 · " + hist.size()
                    + " 次余额记录 · 点开查看收支账本");
        }
        statPanel.setVisibility(View.VISIBLE);
    }

    private int currencyOf(String key, List<Store.Entry> hist) {
        for (Store.Entry e : store.loadCards()) if (e.key.equals(key)) return e.currency;
        return hist.isEmpty() ? Money.KRW : hist.get(hist.size() - 1).currency;
    }

    private void openStats() {
        if (currentKey == null) {
            Toast.makeText(this, "请先贴卡读取一次", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, StatsActivity.class);
        i.putExtra("key", currentKey);
        startActivity(i);
    }

    private void openTrips() {
        if (currentKey == null) {
            Toast.makeText(this, "请先贴卡读取一次", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, TripsActivity.class);
        i.putExtra("key", currentKey);
        startActivity(i);
    }

    /** 读卡失败：错误摘要 + 常见原因自助排查 + 诊断复制/查看 */
    private void showFailDialog(String msg, final String diag) {
        StringBuilder sb = new StringBuilder();
        sb.append("错误：").append(msg == null ? "未知错误" : msg).append("\n\n");
        sb.append("常见原因（按概率排）\n");
        sb.append("1. 没对准天线：NFC 多在背面摄像头附近，贴住后慢慢平移找位置\n");
        sb.append("2. 停留太短：贴住保持 1-2 秒，读取过程中别晃动、别移开\n");
        sb.append("3. 手机壳太厚或带金属/磁吸：取掉壳再试\n");
        sb.append("4. 多张卡叠在一起：一次只贴一张\n");
        sb.append("5. 卡种不支持：加密卡（Oyster、悠游卡、Clipper 等）读不到，"
                + "可在「设置 → 支持的卡种」查看范围\n");
        sb.append("6. 卡内没有电子现金：部分银行卡只有账户余额，没有可读的卡内钱包\n\n");
        sb.append("多次失败可以复制诊断数据发给开发者。");
        Dialogs.show(this, getString(R.string.read_fail), sb.toString(),
                getString(R.string.ok), null,
                "复制诊断", new Dialogs.Click() {
                    @Override public void onClick(android.app.AlertDialog d) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "diag", diag + "\n错误：" + msg));
                            Toast.makeText(MainActivity.this, "诊断已复制", Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {}
                    }
                },
                "诊断详情", new Dialogs.Click() {
                    @Override public void onClick(android.app.AlertDialog d) {
                        Dialogs.info(MainActivity.this, "诊断数据", diag);
                    }
                });
    }

    /** 卡种说明统一在设置页，这里不再重复 */
    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(80);
        } catch (Exception ignored) {}
    }

    private String fmt(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }
}
