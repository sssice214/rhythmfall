package io.wifi.rhythmfall.screen;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 专属 GUI 图片层：封面等图片统一收进本控件，在 widgets 渲染通道绘制
 * （与 LabelWidget 同理——绕开整合包环境对 Screen 直绘内容的模糊/黑块处理）。
 */
public class ImageLayerWidget extends AbstractWidget {
    private record Img(ResourceLocation loc, int x, int y, int w, int h,
                       float u, float v, int sw, int sh, int tw, int th) {
    }

    private final List<Img> imgs = new ArrayList<>();

    public ImageLayerWidget() {
        super(0, 0, 0, 0, Component.empty());
        this.active = false;
    }

    /** 清空本帧图片（每帧 render 开头调用）。 */
    public ImageLayerWidget clear() {
        imgs.clear();
        return this;
    }

    /**
     * 添加一张要绘制的图片：<b>等比缩放</b>到完全放入 w×h 区域（宽高比不变、不裁剪、居中）。
     * 大图等比缩小到刚好放入；小图保持原尺寸居中（不放大）。
     */
    public ImageLayerWidget addFit(ResourceLocation loc, int x, int y, int w, int h, int texW, int texH) {
        int dw;
        int dh;
        if (texW <= w && texH <= h) {
            dw = texW;
            dh = texH;
        } else {
            float scale = Math.min((float) w / texW, (float) h / texH);
            dw = Math.round(texW * scale);
            dh = Math.round(texH * scale);
        }
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        imgs.add(new Img(loc, dx, dy, dw, dh, 0f, 0f, texW, texH, texW, texH));
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        for (Img m : imgs) {
            g.blit(m.loc(), m.x(), m.y(), m.w(), m.h(),
                    m.u(), m.v(), m.sw(), m.sh(), m.tw(), m.th());
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
