package com.cubalance.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/** 余额走势折线图（本地绘制，无第三方依赖） */
public class BalanceChart extends View {

    private List<Store.Entry> entries;
    private int currency = 0;
    private Paint linePaint, fillPaint, dotPaint, gridPaint, labelPaint;

    public BalanceChart(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        // 用语义色资源取色，深色模式（values-night）自动适配
        android.content.res.Resources res = getResources();
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(res.getColor(R.color.chart_line));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(5f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(res.getColor(R.color.chart_line));
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAlpha(36);

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(res.getColor(R.color.chart_dot));
        dotPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(res.getColor(R.color.chart_grid));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(res.getColor(R.color.chart_label));
        labelPaint.setTextSize(24f);
    }

    public void setEntries(List<Store.Entry> e) {
        entries = e;
        invalidate();
    }

    /** 刻度与数值按币种显示（CNY 以分存储，需换算成元） */
    public void setCurrency(int c) {
        currency = c;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (entries == null || entries.isEmpty() || w == 0 || h == 0) {
            labelPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText("暂无记录", w / 2f, h / 2f, labelPaint);
            return;
        }

        int padL = 90, padR = 30, padT = 40, padB = 60;
        int innerW = w - padL - padR;
        int innerH = h - padT - padB;

        int n = entries.size();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Store.Entry e : entries) {
            if (e.balance < min) min = e.balance;
            if (e.balance > max) max = e.balance;
        }
        if (min == max) { min -= 100; max += 100; }
        int span = max - min;
        int step = span / 3;
        if (step <= 0) step = 1;

        // 横向网格 + 刻度（最高一条刻度带币种符号，避免只有裸数字看不出单位）
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 3; i++) {
            int val = min + step * i;
            float y = padT + innerH - (innerH * (val - min) / (float) span);
            c.drawLine(padL, y, w - padR, y, gridPaint);
            c.drawText(i == 3 ? Money.fmt(currency, val) : Money.num(currency, val),
                    padL - 12, y + 8, labelPaint);
        }

        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? padL + innerW / 2f : padL + innerW * i / (float) (n - 1);
            ys[i] = padT + innerH - innerH * (entries.get(i).balance - min) / (float) span;
        }

        // 填充区
        Path fill = new Path();
        fill.moveTo(xs[0], padT + innerH);
        for (int i = 0; i < n; i++) fill.lineTo(xs[i], ys[i]);
        fill.lineTo(xs[n - 1], padT + innerH);
        fill.close();
        c.drawPath(fill, fillPaint);

        // 折线
        Path line = new Path();
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) line.lineTo(xs[i], ys[i]);
        c.drawPath(line, linePaint);

        // 数据点
        for (int i = 0; i < n; i++) {
            c.drawCircle(xs[i], ys[i], i == n - 1 ? 9f : 6f, dotPaint);
        }

        // 底部横轴：按笔序显示（不用时间），左「第1笔」右「第N笔」
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("第1笔", padL, h - 16, labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(n >= 2 ? "第" + n + "笔" : "", w - padR, h - 16, labelPaint);
    }
}
