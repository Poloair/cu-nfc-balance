package com.cubalance.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

/**
 * 底栏毛玻璃：把底栏所在区域的页面内容截图 → 严重下采样 → 放大铺在底栏底层。
 * 下采样本身就带来柔和模糊，API 31+ 再叠一层 RenderEffect 真模糊。
 * 页面滚动/布局变化时（防抖 110ms）重新采样，避免滚动中出现大面积空白。
 */
public final class BlurBar {

    private static final int SAMPLE = 5;       // 下采样倍数
    private static final long DEBOUNCE_MS = 110;

    private final Activity act;
    private final View navRoot;
    private final ImageView blurView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bitmap bmp;
    private boolean bound;
    private boolean effectApplied;

    private final Runnable captureTask = new Runnable() {
        @Override public void run() { capture(); }
    };

    private BlurBar(Activity a, View navRoot, ImageView blurView) {
        this.act = a;
        this.navRoot = navRoot;
        this.blurView = blurView;
    }

    /** 绑定到某个页面的底栏（bottom_nav 根节点） */
    public static void attach(Activity a, View navRoot) {
        if (navRoot == null) return;
        ImageView iv = (ImageView) navRoot.findViewById(R.id.navBlur);
        if (iv == null) return;
        final BlurBar bar = new BlurBar(a, navRoot, iv);

        // 圆角裁剪：让模糊层与玻璃层一起被裁成胶囊
        navRoot.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getResources()
                        .getDisplayMetrics().density * 30f);
            }
        });
        navRoot.setClipToOutline(true);

        navRoot.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                if ((r - l) != (or - ol) || (b - t) != (ob - ot)) bar.schedule();
            }
        });
        navRoot.setTag(bar);

        View content = a.findViewById(android.R.id.content);
        if (content != null) {
            content.getViewTreeObserver().addOnScrollChangedListener(
                    new ViewTreeObserver.OnScrollChangedListener() {
                        @Override public void onScrollChanged() { bar.schedule(); }
                    });
        }
        bar.schedule();
    }

    /** 内容可能变了（比如读卡后刷新），主动排一次重采样 */
    public static void refresh(Activity a) {
        View root = a.findViewById(R.id.navRoot);
        if (root == null) return;
        Object o = root.getTag();
        if (o instanceof BlurBar) {
            ((BlurBar) o).schedule();
            return;
        }
        attach(a, root);
    }

    private void schedule() {
        handler.removeCallbacks(captureTask);
        handler.postDelayed(captureTask, DEBOUNCE_MS);
    }

    private void capture() {
        if (act.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return;
        int w = navRoot.getWidth();
        int h = navRoot.getHeight();
        if (w <= 0 || h <= 0) return;
        View content = act.findViewById(android.R.id.content);
        if (content == null) return;

        int[] pr = new int[2];
        int[] pc = new int[2];
        navRoot.getLocationInWindow(pr);
        content.getLocationInWindow(pc);
        int x = pr[0] - pc[0];
        int y = pr[1] - pc[1];

        int sw = Math.max(1, w / SAMPLE);
        int sh = Math.max(1, h / SAMPLE);
        try {
            if (bmp == null || bmp.isRecycled()
                    || bmp.getWidth() != sw || bmp.getHeight() != sh) {
                if (bmp != null) bmp.recycle();
                bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                bound = false;
            }
            Canvas c = new Canvas(bmp);
            c.drawColor(act.getResources().getColor(R.color.bg));
            float k = 1f / SAMPLE;
            c.scale(k, k);
            c.translate(-x, -y);
            // 采样时先藏起底栏自身，否则会把玻璃与文字也糊进背景
            int vis = navRoot.getVisibility();
            navRoot.setVisibility(View.INVISIBLE);
            try {
                content.draw(c);
            } finally {
                navRoot.setVisibility(vis);
            }
            if (!bound) {
                blurView.setImageBitmap(bmp);
                bound = true;
            }
            if (Build.VERSION.SDK_INT >= 31 && !effectApplied) {
                RenderBlur.apply(blurView, act.getResources().getDisplayMetrics().density * 9f);
                effectApplied = true;
            }
            blurView.invalidate();
        } catch (Throwable ignored) {}
    }
}
