package com.cubalance.app;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 悬浮底栏（液态玻璃）：主页 / 卡片 / 设置 三个页签。
 * 每个页签页布局末尾 include bottom_nav，并在 onCreate 里调用 setup(activity, index)。
 */
public final class BottomBar {

    private BottomBar() {}

    /** 绑定底栏并高亮当前页签；点击其他页签时切换页面（无转场动画） */
    public static void setup(final Activity a, int activeIndex) {
        // 底栏毛玻璃：截取下方页面内容做模糊底（API 31+ 再加 RenderEffect）
        View navRoot = a.findViewById(R.id.navRoot);
        if (navRoot != null) BlurBar.attach(a, navRoot);

        int[][] items = {
                {R.id.navHome, 0},
                {R.id.navCards, 1},
                {R.id.navSettings, 2}
        };
        final Class<?>[] targets = {
                MainActivity.class, CardsActivity.class, SettingsActivity.class
        };
        for (int[] it : items) {
            View v = a.findViewById(it[0]);
            if (v == null) continue;
            boolean sel = it[1] == activeIndex;
            // 选中态：液态玻璃薄荷胶囊 + 品牌色文字；未选中：透明底 + 灰字
            v.setBackgroundResource(sel ? R.drawable.nav_pill_sel : 0);
            tintItem(v, sel);
            if (sel) continue;
            final Class<?> target = targets[it[1]];
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { go(a, target, null); }
            });
        }
    }

    /** 携带 key 切换页签 */
    public static void go(Activity a, Class<?> target, String key) {
        if (a.getClass() == target) return;
        Intent i = new Intent(a, target);
        if (key != null) i.putExtra("key", key);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(i);
        a.overridePendingTransition(0, 0);
        a.finish();
    }

    private static void tintItem(View item, boolean sel) {
        if (!(item instanceof LinearLayout)) return;
        LinearLayout group = (LinearLayout) item;
        int iconColor = group.getResources().getColor(sel ? R.color.brand_dark : R.color.text_sub);
        int textColor = group.getResources().getColor(sel ? R.color.brand_dark : R.color.text_sub);
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                ((android.widget.TextView) c).setTextColor(i == 0 ? iconColor : textColor);
            }
        }
    }
}
