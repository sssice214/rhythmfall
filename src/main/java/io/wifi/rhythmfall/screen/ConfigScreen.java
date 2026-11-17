package io.wifi.rhythmfall.screen;

import io.wifi.rhythmfall.audio.HitSounds;
import io.wifi.rhythmfall.client.ClientConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * 配置界面：流速、延迟校准、特效档位（高/低）、音符音效/音乐开关、
 * 音符与音乐音量、自动演奏。首次打开 GUI 时强制出现一次。
 */
public class ConfigScreen extends Screen {
    private static final Component HINT = Component.translatable("screen.rhythmfall.settingsHint");

    private final Screen parent;
    private final ClientConfig cfg = ClientConfig.get();
    private final long openedAt = Util.getMillis();
    private LabelWidget labels;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("screen.rhythmfall.settingsTitle"));
        this.parent = parent;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 不走原版：原版会把高斯模糊层写入 GUI 缓冲，波及界面自绘内容（封面/音符）。
        g.fill(0, 0, width, height, 0xFF14141C);
    }

    @Override
    protected void init() {
        labels = new LabelWidget();
        addRenderableWidget(labels);
        int w = 160;
        int x0 = this.width / 2 - w - 8;
        int x1 = this.width / 2 + 8;
        int y = 48;
        int step = 24;

        String on = Component.translatable("screen.rhythmfall.on").getString();
        String off = Component.translatable("screen.rhythmfall.off").getString();

        addRenderableWidget(new FSlider(x0, y, w, 20, 0.5, 3.0, cfg.speedMult,
                v -> cfg.speedMult = (float) v,
                v -> Component.translatable("screen.rhythmfall.speedFmt", String.format("%.2f", v)).getString()));
        addRenderableWidget(new FSlider(x1, y, w, 20, -300, 300, cfg.offsetMs,
                v -> cfg.offsetMs = (int) Math.round(v),
                v -> Component.translatable("screen.rhythmfall.offsetFmt", Math.round(v)).getString()));
        y += step;
        addRenderableWidget(new FSlider(x0, y, w, 20, 0, 1, cfg.musicVolume,
                v -> cfg.musicVolume = (float) v,
                v -> Component.translatable("screen.rhythmfall.musicVolFmt", Math.round(v * 100)).getString()));
        addRenderableWidget(new FSlider(x1, y, w, 20, 0, 1, cfg.noteVolume,
                v -> {
                    cfg.noteVolume = (float) v;
                    HitSounds.setVolume(cfg.hitSounds ? cfg.noteVolume : 0f);
                },
                v -> Component.translatable("screen.rhythmfall.noteVolFmt", Math.round(v * 100)).getString()));
        y += step;

        addRenderableWidget(toggle(x0, y, w, () -> cfg.effectsHigh, v -> cfg.effectsHigh = v,
                Component.translatable("screen.rhythmfall.effects"),
                Component.translatable("screen.rhythmfall.effectsHigh").getString(),
                Component.translatable("screen.rhythmfall.effectsLow").getString()));
        addRenderableWidget(toggle(x1, y, w, () -> cfg.hitSounds, v -> cfg.hitSounds = v,
                Component.translatable("screen.rhythmfall.sounds"), on, off));
        y += step;
        addRenderableWidget(toggle(x0, y, w, () -> cfg.playMusic, v -> cfg.playMusic = v,
                Component.translatable("screen.rhythmfall.music"), on, off));
        addRenderableWidget(toggle(x1, y, w, () -> cfg.autoPlay, v -> cfg.autoPlay = v,
                Component.translatable("screen.rhythmfall.autoplay"), on, off));
        y += step + 12;

        addRenderableWidget(Button.builder(Component.translatable("screen.rhythmfall.done"), b -> done())
                .pos(this.width / 2 - 100, y).size(200, 20).build());
    }

    private void done() {
        cfg.configured = true;
        ClientConfig.saveAsync();
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            minecraft.setScreen(new SongSelectScreen(null));
        }
    }

    private Button toggle(int x, int y, int w, java.util.function.BooleanSupplier get,
                          java.util.function.Consumer<Boolean> set, Component label, String on, String off) {
        Button btn = Button.builder(label, b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(buildToggleText(label, get.getAsBoolean(), on, off));
        }).pos(x, y).size(w, 20).build();
        btn.setMessage(buildToggleText(label, get.getAsBoolean(), on, off));
        return btn;
    }

    private static Component buildToggleText(Component label, boolean value, String on, String off) {
        return Component.translatable("screen.rhythmfall.toggleFmt",
                label.getString(), value ? on : off);
    }

    /** 通用浮点滑条：拖动即写回配置并刷新显示文本。 */
    private static final class FSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final DoubleConsumer setter;
        private final DoubleFunction<String> fmt;

        FSlider(int x, int y, int w, int h, double min, double max, double current,
                DoubleConsumer setter, DoubleFunction<String> fmt) {
            super(x, y, w, h, Component.empty(), (current - min) / (max - min));
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.fmt = fmt;
            updateMessage();
        }

        private double current() {
            return min + this.value * (max - min);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(fmt.apply(current())));
        }

        @Override
        protected void applyValue() {
            setter.accept(current());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        labels.clear();
        labels.add(title, this.width / 2, 20, 0xFFFFAA00, true);
        labels.add(HINT, this.width / 2, 36, 0x8899AA, true);
        super.render(g, mouseX, mouseY, partialTick);

        float fi = UiFx.fadeIn(openedAt, 350);
        if (fi < 1f) {
            g.fill(0, 0, width, height, UiFx.overlay((int) ((1f - fi) * 255)));
        }
    }

    @Override
    public void onClose() {
        cfg.configured = true;
        ClientConfig.saveAsync();
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            minecraft.setScreen(new SongSelectScreen(null));
        }
    }
}
