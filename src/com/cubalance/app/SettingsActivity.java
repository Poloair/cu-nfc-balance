package com.cubalance.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：支持的卡种声明 / 隐私说明 / 清空全部 / 版本信息 */
public class SettingsActivity extends Activity {

    private Store store;
    private TextView tvCardCount, tvNfcState;
    private android.nfc.NfcAdapter nfcAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new Store(this);
        nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this);

        findViewById(R.id.rowNfc).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNfcSettings(); }
        });
        findViewById(R.id.rowSupported).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSupported(); }
        });
        findViewById(R.id.rowPrivacy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Dialogs.info(SettingsActivity.this,
                        getString(R.string.privacy_title), getString(R.string.privacy_text));
            }
        });
        findViewById(R.id.rowClear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClear(); }
        });
        tvCardCount = findViewById(R.id.tvCardCount);
        tvNfcState = findViewById(R.id.tvNfcState);
        BottomBar.setup(this, 2);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int n = store.loadCards().size();
        tvCardCount.setText(n == 0 ? "还没有保存的卡片" : "已保存 " + n + " 张卡片（仅存本机）");
        refreshNfcState();
    }

    /** NFC 状态：已开启 / 未开启（可点行跳设置）/ 不支持 */
    private void refreshNfcState() {
        if (nfcAdapter == null) {
            tvNfcState.setText(getString(R.string.nfc_unsupported));
            tvNfcState.setTextColor(getResources().getColor(R.color.text_sub));
        } else if (!nfcAdapter.isEnabled()) {
            tvNfcState.setText(getString(R.string.nfc_off) + " · 点此开启");
            tvNfcState.setTextColor(getResources().getColor(R.color.up));
        } else {
            tvNfcState.setText("已开启");
            tvNfcState.setTextColor(getResources().getColor(R.color.brand_dark));
        }
    }

    private void openNfcSettings() {
        if (nfcAdapter == null) {
            Dialogs.info(this, getString(R.string.nfc_unsupported),
                    getString(R.string.nfc_unsupported_tip));
            return;
        }
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "请手动打开系统设置 → NFC", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void confirmClear() {
        Dialogs.confirm(this,
                getString(R.string.clear_all), getString(R.string.confirm_clear),
                new Dialogs.Click() {
                    @Override public void onClick(android.app.AlertDialog d) {
                        store.clearAll();
                        Toast.makeText(SettingsActivity.this, "已清空", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 支持的卡种 + 使用声明（与主页贴卡入口一致） */
    private void showSupported() {
        StringBuilder sb = new StringBuilder();
        sb.append("【支持的卡种】\n");
        sb.append("🇨🇳 中国内地\n");
        sb.append("  · 交通联合卡（卡面有「交通联合」标志，全国 300+ 城市）\n");
        sb.append("  · 银联卡（带 QuickPass 闪付，读取电子现金钱包）\n");
        sb.append("🇰🇷 韩国\n");
        sb.append("  · T-money / Cashbee / MOIBA / K-Cash / Rail+ 等\n");
        sb.append("🇯🇵 日本\n");
        sb.append("  · Suica / PASMO / ICOCA / TOICA 等 FeliCa 交通卡\n");
        sb.append("🇭🇰 中国香港\n");
        sb.append("  · 八达通\n\n");
        sb.append("【重要说明】\n");
        sb.append("· 只读卡内公开数据，不写卡、不充值、不联网、不上传\n");
        sb.append("· 银行卡读到的是「电子现金」钱包，不是银行账户余额，多数卡为 0 属正常\n");
        sb.append("· FeliCa 卡（日本/香港）需手机 NFC 支持 NFC-F，部分机型不支持\n");
        sb.append("· 余额与记录为贴卡时的快照，仅存本机\n");
        sb.append("· 悠游卡 / Oyster / Clipper 等加密封闭卡种暂不支持");
        Dialogs.info(this, "支持的卡片与声明", sb.toString());
    }
}
