package io.wifi.rhythmfall.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 专属 GUI 文本层：界面全部文字统一收进本控件，在 widgets 渲染通道绘制
 * （与原版按钮同通道）。部分整合包环境会对 Screen 直绘内容做模糊/重采样，
 * 而 widgets 通道不受影响——实测按钮文字清晰、直绘文字发糊，故以此绕开。
 *
 * 用法：界面 init 时 addRenderableWidget(new LabelWidget())；
 * 每帧 render 里 labels.clear() 后逐条 add(...)，super.render 时自动绘出。
 */
public class LabelWidget extends AbstractWidget {
    private record Line(Object text, int x, int y, int color, boolean centered, float scale) {
    }

    private final List<Line> lines = new ArrayList<>();

    public LabelWidget() {
        super(0, 0, 0, 0, Component.empty());
        this.active = false; // 纯展示控件：不拦截鼠标、不播放点击音
    }

    /** 清空本帧文字（每帧 render 开头调用）。 */
    public LabelWidget clear() {
        lines.clear();
        return this;
    }

    /** 添加一行文字。text 可为 Component 或 String。centered=true 时 x 为中心。 */
    public LabelWidget add(Object text, int x, int y, int color, boolean centered) {
        return addScaled(text, x, y, color, centered, 1f);
    }

    /** 添加一行带缩放的文字（如大号连击数）。 */
    public LabelWidget addScaled(Object text, int x, int y, int color, boolean centered, float scale) {
        if (text == null) {
            return this;
        }
        lines.add(new Line(text, x, y, color, centered, scale));
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        for (Line l : lines) {
            Component c = l.text instanceof Component comp ? comp : Component.literal(String.valueOf(l.text));
            if (l.scale != 1f) {
                g.pose().pushPose();
                g.pose().translate(l.x, l.y, 0);
                g.pose().scale(l.scale, l.scale, 1f);
                if (l.centered) {
                    g.drawCenteredString(font, c, 0, 0, l.color);
                } else {
                    g.drawString(font, c, 0, 0, l.color);
                }
                g.pose().popPose();
            } else if (l.centered) {
                g.drawCenteredString(font, c, l.x, l.y, l.color);
            } else {
                g.drawString(font, c, l.x, l.y, l.color);
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
