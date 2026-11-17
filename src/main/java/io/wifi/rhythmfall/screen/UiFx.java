package io.wifi.rhythmfall.screen;

import net.minecraft.Util;

/** 界面淡入淡出与缓动的通用小工具。 */
public final class UiFx {
    private UiFx() {
    }

    /** easeOutCubic，输入自动收敛到 0..1 */
    public static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    /** 淡入进度 0..1 */
    public static float fadeIn(long openedAt, long durMs) {
        return ease((Util.getMillis() - openedAt) / (float) durMs);
    }

    /** 黑色遮罩 ARGB（fade=0 全黑 → fade=1 透明） */
    public static int overlay(int fade255) {
        int a = Math.max(0, Math.min(255, fade255));
        return a << 24;
    }
}
