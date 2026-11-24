package com.cubalance.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 统一玻璃风格弹窗：标题 + 可滚动正文 + 最多三个按钮（中性 / 次要 / 主按钮） */
public final class Dialogs {

    public interface Click { void onClick(AlertDialog dialog); }

    private Dialogs() {}

    /** 全参数版：任一按钮传 null 即隐藏 */
    public static AlertDialog show(Activity a, String title, String msg,
                                   String posText, final Click pos,
                                   String negText, final Click neg,
                                   String neutralText, final Click neutral) {
        View v = a.getLayoutInflater().inflate(R.layout.dialog_glass, null);
        TextView tvTitle = (TextView) v.findViewById(R.id.dlgTitle);
        TextView tvMsg = (TextView) v.findViewById(R.id.dlgMsg);
        TextView bPos = (TextView) v.findViewById(R.id.dlgBtnPositive);
        TextView bNeg = (TextView) v.findViewById(R.id.dlgBtnNegative);
        TextView bNeu = (TextView) v.findViewById(R.id.dlgBtnNeutral);

        tvTitle.setText(title);
        if (msg == null || msg.isEmpty()) {
            v.findViewById(R.id.dlgMsgScroll).setVisibility(View.GONE);
        } else {
            tvMsg.setText(msg);
            tvMsg.setTextIsSelectable(true); // 长按可复制（诊断文本用）
        }

        AlertDialog dlg = new AlertDialog.Builder(a).setView(v).create();
        dlg.show();
        window(a, dlg);

        hook(bPos, posText, pos, dlg);
        hook(bNeg, negText, neg, dlg);
        hook(bNeu, neutralText, neutral, dlg);
        return dlg;
    }

    /** 快捷：只有主按钮（提示类） */
    public static AlertDialog info(Activity a, String title, String msg) {
        return show(a, title, msg, a.getString(R.string.ok), null, null, null, null, null);
    }

    /** 快捷：主按钮 + 取消（确认类） */
    public static AlertDialog confirm(Activity a, String title, String msg, final Click onOk) {
        return show(a, title, msg,
                a.getString(R.string.ok), onOk,
                a.getString(R.string.cancel), null, null, null);
    }

    /** 输入弹窗：单行文本框 + 取消/确定 */
    public interface Input { void onOk(String text); }

    public static AlertDialog input(Activity a, String title, String preset, String hint,
                                    final Input cb) {
        return input(a, title, null, preset, hint, cb);
    }

    /** 输入弹窗（带说明正文）：正文在可滚动区，输入框只放短提示 */
    public static AlertDialog input(Activity a, String title, String msg, String preset,
                                    String hint, final Input cb) {
        final LinearLayout root =
                (LinearLayout) a.getLayoutInflater().inflate(R.layout.dialog_glass, null);
        TextView tvTitle = (TextView) root.findViewById(R.id.dlgTitle);
        View msgScroll = root.findViewById(R.id.dlgMsgScroll);
        TextView tvMsg = (TextView) root.findViewById(R.id.dlgMsg);
        if (msg == null || msg.isEmpty()) {
            msgScroll.setVisibility(View.GONE);
        } else {
            tvMsg.setText(msg);
            msgScroll.setVisibility(View.VISIBLE);
        }
        tvTitle.setText(title);

        final EditText et = new EditText(a);
        et.setHint(hint);
        if (preset != null && !preset.isEmpty()) {
            et.setText(preset);
            et.setSelection(preset.length());
        }
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setTextColor(a.getResources().getColor(R.color.text_main));
        et.setHintTextColor(a.getResources().getColor(R.color.text_sub));
        et.setBackgroundResource(R.drawable.tile_bg);
        float d = a.getResources().getDisplayMetrics().density;
        et.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * d);
        root.addView(et, 2, lp); // 0=标题 1=正文 2=按钮区

        final AlertDialog dlg = new AlertDialog.Builder(a).setView(root).create();
        dlg.show();
        window(a, dlg);

        TextView bPos = (TextView) root.findViewById(R.id.dlgBtnPositive);
        bPos.setVisibility(View.VISIBLE);
        bPos.setText(a.getString(R.string.ok));
        bPos.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.onOk(et.getText().toString());
                dlg.dismiss();
            }
        });
        TextView bNeg = (TextView) root.findViewById(R.id.dlgBtnNegative);
        bNeg.setVisibility(View.VISIBLE);
        bNeg.setText(a.getString(R.string.cancel));
        bNeg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        return dlg;
    }

    /** 列表式操作菜单：标题 + 副标题 + 若干整行操作 + 取消（比三个按钮挤一行更清楚） */
    public static AlertDialog menu(Activity a, String title, String sub,
                                   String[] labels, final Click[] actions) {
        LinearLayout root = (LinearLayout)
                a.getLayoutInflater().inflate(R.layout.dialog_menu, null);
        TextView tvTitle = (TextView) root.findViewById(R.id.menuTitle);
        TextView tvSub = (TextView) root.findViewById(R.id.menuSub);
        LinearLayout items = (LinearLayout) root.findViewById(R.id.menuItems);

        tvTitle.setText(title);
        if (sub == null || sub.isEmpty()) {
            tvSub.setVisibility(View.GONE);
        } else {
            tvSub.setText(sub);
        }

        final AlertDialog dlg = new AlertDialog.Builder(a).setView(root).create();
        dlg.show();
        window(a, dlg);

        float d = a.getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            TextView row = new TextView(a);
            row.setText(labels[i]);
            row.setTextSize(15);
            row.setTypeface(android.graphics.Typeface.SANS_SERIF);
            row.setTextColor(a.getResources().getColor(R.color.text_main));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.tile_bg);
            row.setPadding((int) (18 * d), (int) (15 * d), (int) (18 * d), (int) (15 * d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : (int) (8 * d);
            final Click c = actions != null && i < actions.length ? actions[i] : null;
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    if (c != null) c.onClick(dlg);
                }
            });
            items.addView(row, lp);
        }

        TextView cancel = (TextView) root.findViewById(R.id.menuCancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        return dlg;
    }

    /** 统一弹窗窗体：透明背景 + 固定宽度（避免按钮被挤压缩排） */
    private static void window(Activity a, AlertDialog dlg) {
        if (dlg.getWindow() == null) return;
        dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int max = (int) (400 * dm.density);
        int w = (int) (dm.widthPixels * 0.9f);
        dlg.getWindow().setLayout(Math.min(w, max), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void hook(TextView b, String text, final Click c, final AlertDialog dlg) {
        if (text == null || c == null) {
            b.setVisibility(View.GONE);
            return;
        }
        b.setVisibility(View.VISIBLE);
        b.setText(text);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                c.onClick(dlg);
                dlg.dismiss();
            }
        });
    }
}
