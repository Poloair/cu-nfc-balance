package com.cubalance.app;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易金额柱状图（本地绘制）：
 *  - 横轴为交易笔序（旧→新，不用时间），每笔一根柱：充值=绿 / 消费=灰
 *  - 刻度按币种显示
 *  - 点任意一根柱子：亮起光柱 + 柱子顶部气泡显示这一笔的具体金额（默认选中最新一笔）
 */
public class BarChart extends View {

    private static class Bar {
        String label;   // "#笔序"
        int spend;      // 本笔消费
        int topup;      // 本笔充值
    }

    private final List<Bar> bars = new ArrayList<>();
    private int currency = 0;
    private int selected = -1;      // 选中的柱子下标
    private float downX, downY;

    private final float d;          // 密度
    private final float padL, padR, padT, padB, chipH;

    private Paint payPaint, topupPaint, gridPaint, labelPaint, labelSelPaint;
    private Paint beamPaint, barStroke, chipBg, chipText;

    public BarChart(Context c, AttributeSet a) {
        super(c, a);
        Resources res = getResources();
        d = res.getDisplayMetrics().density;
        padL = 12f * d;
        padR = 12f * d;
        padT = 26f * d;
        padB = 22f * d;
        chipH = 22f * d;

        payPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        payPaint.setColor(res.getColor(R.color.chart_pay));
        topupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        topupPaint.setColor(res.getColor(R.color.chart_line));
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(res.getColor(R.color.chart_grid));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, d));

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(res.getColor(R.color.chart_text));
        labelPaint.setTextSize(10f * d);

        labelSelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelSelPaint.setColor(res.getColor(R.color.brand_dark));
        labelSelPaint.setTextSize(10f * d);
        labelSelPaint.setFakeBoldText(true);

        // 点击后的「光柱」：淡薄荷竖条
        beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        beamPaint.setColor(0x2410A37F);

        barStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        barStroke.setColor(res.getColor(R.color.brand_dark));
        barStroke.setStyle(Paint.Style.STROKE);
        barStroke.setStrokeWidth(1.5f * d);
        barStroke.setAlpha(0x80);

        chipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        chipBg.setColor(0xFFFFFFFF);
        chipBg.setStyle(Paint.Style.FILL);

        chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
        chipText.setColor(res.getColor(R.color.text_main));
        chipText.setTextSize(11f * d);
        chipText.setFakeBoldText(true);
        chipText.setTextAlign(Paint.Align.CENTER);

        setLayerType(LAYER_TYPE_SOFTWARE, null); // 让气泡的投影生效
        chipBg.setShadowLayer(3f * d, 0f, 1f * d, 0x1F000000);
    }

    public void setTrips(List<Trip> trips, int cur) {
        currency = cur;
        bars.clear();
        if (trips != null && !trips.isEmpty()) {
            // 单笔模式（旧→新）：trips 存储是新→旧，倒序遍历
            for (int i = trips.size() - 1; i >= 0; i--) {
                Trip t = trips.get(i);
                Bar b = new Bar();
                b.label = "#" + (trips.size() - i);
                if (t.isTopUp()) b.topup += t.cost; else b.spend += t.cost;
                bars.add(b);
            }
        }
        selected = bars.size() - 1; // 默认选中最新一笔，数值一开始就能看到
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                // 不拦截父级：从这个区域起手上下滑仍然能滚动页面
                return true;
            case MotionEvent.ACTION_UP:
                float slop = 20f * d;
                if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) {
                    pick(e.getX(), e.getY());
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    /** 把点击位置换算成柱子下标 */
    private void pick(float x, float y) {
        int w = getWidth(), h = getHeight();
        if (bars.isEmpty() || w == 0 || h == 0) return;
        float innerW = w - padL - padR;
        float innerH = h - padT - padB;
        if (x < padL - 8 * d || x > w - padR + 8 * d) return;
        if (y < padT - chipH - 12 * d || y > padT + innerH + 14 * d) return;

        float slot = innerW / (float) bars.size();
        int idx = (int) ((x - padL) / slot);
        if (idx < 0) idx = 0;
        if (idx >= bars.size()) idx = bars.size() - 1;
        selected = idx;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (bars.isEmpty() || w == 0 || h == 0) {
            labelPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText("暂无交易记录", w / 2f, h / 2f, labelPaint);
            return;
        }

        float innerW = w - padL - padR;
        float innerH = h - padT - padB;

        int max = 1;
        for (Bar b : bars) {
            if (b.spend > max) max = b.spend;
            if (b.topup > max) max = b.topup;
        }

        int n = bars.size();
        float slot = innerW / (float) n;
        float barW = Math.min(slot * 0.62f, 26f * d);
        float r = Math.min(barW / 2f, 5f * d);

        // 选中柱子的光柱（画在柱子下面）
        if (selected >= 0 && selected < n) {
            float left = padL + slot * selected;
            c.drawRoundRect(new RectF(left + slot * 0.06f, padT - 4 * d,
                    left + slot * 0.94f, padT + innerH), 6f * d, 6f * d, beamPaint);
        }

        // 顶部参考线 + 最大值刻度（带币种符号）
        c.drawLine(padL, padT, w - padR, padT, gridPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(Money.fmt(currency, max), padL, padT - 7 * d, labelPaint);

        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            float cx = padL + slot * i + slot / 2f;
            int v = Math.max(b.spend, b.topup);
            float bh = v <= 0 ? 3f * d : Math.max(6f * d, innerH * v / (float) max);
            float x = cx - barW / 2f;
            RectF rect = new RectF(x, padT + innerH - bh, x + barW, padT + innerH);
            c.drawRoundRect(rect, r, r, b.topup > 0 ? topupPaint : payPaint);
            if (i == selected) c.drawRoundRect(rect, r, r, barStroke);

            // 横轴笔序标签（拥挤时隔一个显示一个；选中的那个一定显示）
            if (n <= 7 || i % 2 == 0 || i == selected) {
                Paint p = i == selected ? labelSelPaint : labelPaint;
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(b.label, cx, padT + innerH + 15 * d, p);
            }
        }

        // 底部基线
        c.drawLine(padL, padT + innerH, w - padR, padT + innerH, gridPaint);

        // 选中柱子的金额气泡（画最上层，压在柱子上也不影响读）
        if (selected >= 0 && selected < n) drawChip(c, w, innerH, max, slot);
    }

    /** 选中柱子顶部的金额气泡 */
    private void drawChip(Canvas c, int w, float innerH, int max, float slot) {
        Bar b = bars.get(selected);
        int v = Math.max(b.spend, b.topup);
        boolean topup = b.topup > 0;
        String txt = (topup ? "充 " : "消 ") + Money.fmt(currency, v);

        float padX = 8f * d;
        float chipW = chipText.measureText(txt) + padX * 2;

        float cx = padL + slot * selected + slot / 2f;
        float bh = v <= 0 ? 3f * d : Math.max(6f * d, innerH * v / (float) max);
        float barTop = padT + innerH - bh;

        float top = barTop - chipH - 3f * d;
        if (top < 1f) top = Math.min(barTop + 4f * d, padT + innerH - chipH - 1f);

        float left = cx - chipW / 2f;
        if (left < 2f) left = 2f;
        if (left + chipW > w - 2f) left = w - 2f - chipW;

        RectF rc = new RectF(left, top, left + chipW, top + chipH);
        c.drawRoundRect(rc, chipH / 2f, chipH / 2f, chipBg);
        chipText.setColor(getResources().getColor(topup ? R.color.gain : R.color.text_main));
        c.drawText(txt, rc.centerX(), rc.centerY() + 4f * d, chipText);
    }
}
