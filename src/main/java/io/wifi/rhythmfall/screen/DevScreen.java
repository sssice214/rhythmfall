package io.wifi.rhythmfall.screen;

import io.wifi.rhythmfall.client.ClientConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 开发者面板（管理员 /rhythmfall dev 打开）：
 * 「自动打歌」开关——开启后进入打歌：系统自动演奏，但判定正常显示
 * （Perfect/Best 而非 AUTO），且成绩计入服务端排行榜。持久化在 client.json。
 */
public class DevScreen extends Screen {
    private static final Component HINT = Component.translatable("rhythmfall.dev.hint");

    private final Screen parent;
    private final long openedAt = Util.getMillis();
    private LabelWidget labels;

    public DevScreen(Screen parent) {
        super(Component.translatable("rhythmfall.dev.title"));
        this.parent = parent;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 不走原版：原版会把高斯模糊层写入 GUI 缓冲，波及界面自绘内容。
        g.fill(0, 0, width, height, 0xFF14141C);
    }

    @Override
    protected void init() {
        labels = new LabelWidget();
        addRenderableWidget(labels);

        ClientConfig cfg = ClientConfig.get();
        addRenderableWidget(Button.builder(autoText(), b -> {
                    cfg.devAutoPlay = !cfg.devAutoPlay;
                    ClientConfig.saveAsync();
                    b.setMessage(autoText());
                }).pos(this.width / 2 - 130, this.height / 2 - 10).size(260, 22).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.rhythmfall.back"),
                        b -> onClose()).pos(10, this.height - 30).size(90, 20).build());
    }

    private Component autoText() {
        boolean on = ClientConfig.get().devAutoPlay;
        return Component.translatable("rhythmfall.dev.auto",
                Component.translatable(on ? "screen.rhythmfall.on" : "screen.rhythmfall.off").getString());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fillGradient(0, 0, width, height, 0xFF161824, 0xFF0E1018);
        labels.clear();
        labels.add(title, width / 2, 20, 0xFFFFAA00, true);
        labels.add(HINT, width / 2, 42, 0xFF9AABB8, true);
        labels.add(Component.translatable("rhythmfall.dev.hint2"), width / 2, height - 52,
                0xFF8899AA, true);
        super.render(g, mouseX, mouseY, partialTick);

        float fi = UiFx.fadeIn(openedAt, 350);
        if (fi < 1f) {
            g.fill(0, 0, width, height, UiFx.overlay((int) ((1f - fi) * 255)));
        }
    }

    @Override
    public void onClose() {
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }
}
