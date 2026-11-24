package com.cubalance.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.ScrollView;

/**
 * 限高滚动容器：内容超过 maxHeightDp 时容器高度封顶并在内部滚动，
 * 不超过时按内容自适应。用于「已保存卡片」列表，避免多卡时把页面无限撑长。
 */
public class MaxHeightScrollView extends ScrollView {

    private final int maxPx;

    public MaxHeightScrollView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        maxPx = (int) (240 * d); // 最多铺约 3 条卡片，其余滚动
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (maxPx > 0) {
            heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(maxPx, android.view.View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
