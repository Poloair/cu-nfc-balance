package com.cubalance.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 卡片页：全部已保存卡片（可滚动），点击进详情，长按改名 / 调顺序 */
public class CardsActivity extends Activity {

    private Store store;
    private LinearLayout cardList;
    private View emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cards);
        store = new Store(this);
        cardList = findViewById(R.id.cardList);
        emptyView = findViewById(R.id.emptyView);
        refreshList();
        BottomBar.setup(this, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        BlurBar.refresh(this);
    }

    private void refreshList() {
        cardList.removeAllViews();
        final List<Store.Entry> cards = store.loadCards();
        for (int i = 0; i < cards.size(); i++) {
            final Store.Entry e = cards.get(i);
            final int index = i;
            final int total = cards.size();
            View row = getLayoutInflater().inflate(R.layout.item_card, cardList, false);
            TextView tvType = row.findViewById(R.id.itemType);
            TextView tvTail = row.findViewById(R.id.itemTail);
            TextView tvBal = row.findViewById(R.id.itemBalance);
            TextView tvT = row.findViewById(R.id.itemTime);
            String alias = store.aliasOf(e.key);
            if (alias.isEmpty()) {
                tvType.setText(e.typeName);
                tvTail.setText("••" + e.uidTail);
            } else {
                tvType.setText(alias);
                tvTail.setText(e.typeName + " ••" + e.uidTail);
            }
            tvBal.setText(Money.fmt(e.currency, e.balance));
            tvT.setText(fmt(e.time));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(CardsActivity.this, DetailActivity.class);
                    i.putExtra("key", e.key);
                    startActivity(i);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showManage(e, index, total);
                    return true;
                }
            });
            cardList.addView(row);
        }
        cardList.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 长按菜单：重命名 / 上移 / 下移（列表式逐行显示，避免按钮挤在一行重叠） */
    private void showManage(final Store.Entry e, int index, int total) {
        final String name = store.nameOf(e);
        java.util.List<String> labels = new java.util.ArrayList<String>();
        java.util.List<Dialogs.Click> cbs = new java.util.ArrayList<Dialogs.Click>();

        labels.add("重命名");
        cbs.add(new Dialogs.Click() {
            @Override public void onClick(AlertDialog d) { rename(e); }
        });
        if (index > 0) {
            labels.add("上移一位");
            cbs.add(new Dialogs.Click() {
                @Override public void onClick(AlertDialog d) {
                    store.moveCard(e.key, -1);
                    refreshList();
                    BlurBar.refresh(CardsActivity.this);
                }
            });
        }
        if (index < total - 1) {
            labels.add("下移一位");
            cbs.add(new Dialogs.Click() {
                @Override public void onClick(AlertDialog d) {
                    store.moveCard(e.key, 1);
                    refreshList();
                    BlurBar.refresh(CardsActivity.this);
                }
            });
        }

        Dialogs.menu(this, name,
                e.typeName + "  ••" + e.uidTail + "  ·  " + Money.fmt(e.currency, e.balance)
                        + "\n排序会同步到首页和本列表；备注只存在本机。",
                labels.toArray(new String[0]),
                cbs.toArray(new Dialogs.Click[0]));
    }

    private void rename(final Store.Entry e) {
        Dialogs.input(this, "卡片备注", store.aliasOf(e.key),
                "给这张卡起个名字，留空恢复为 " + e.typeName, new Dialogs.Input() {
                    @Override public void onOk(String text) {
                        store.setAlias(e.key, text);
                        refreshList();
                        BlurBar.refresh(CardsActivity.this);
                        Toast.makeText(CardsActivity.this, "已保存备注", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private String fmt(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }
}
