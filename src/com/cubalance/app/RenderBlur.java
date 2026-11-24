package com.cubalance.app;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;

/**
 * API 31+ 的真高斯模糊。单独成类做版本隔离：
 * 低版本系统不会走到这里，也就不会去解析 RenderEffect。
 */
final class RenderBlur {

    private RenderBlur() {}

    static void apply(View v, float radius) {
        v.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }
}
