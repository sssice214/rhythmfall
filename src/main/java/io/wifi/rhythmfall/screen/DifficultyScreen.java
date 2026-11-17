package io.wifi.rhythmfall.screen;

import io.wifi.rhythmfall.beatmap.Beatmap;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 难度选择界面：选歌之后选择难度开始。
 * 谱面/音频/封面全部客户端本地就绪，音频由 GameScreen 进入时按需读取。
 */
public class DifficultyScreen extends Screen {
    private final SongSelectScreen parent;
    private final Song song;
    private final long openedAt = Util.getMillis();

    /** 歌曲速度 0.5~2.0：开始前在此调节，局内不可改 */
    private float songRate = 1.0f;
    /** 难度列表滚动起始下标（滚轮翻页） */
    private int scroll = 0;
    private long fadeStart = -1;
    private Runnable afterFade;
    private LabelWidget labels;

    public DifficultyScreen(SongSelectScreen parent, Song song) {
        super(Component.literal(song.name()));
        this.parent = parent;
        this.song = song;
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
        addRenderableWidget(Button.builder(Component.translatable("screen.rhythmfall.back"),
                        b -> onClose()).pos(10, this.height - 30).size(90, 20).build());

        // 歌曲速度滑条（50%~200%），开始前可随时调整
        addRenderableWidget(new RateSlider(this.width / 2 - 110, 58, 220, 18));

        int y = 86;
        int per = perPage();
        int n = song.diffs().size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, n - per)));
        for (int i = scroll; i < n; i++) {
            if (y + 22 > height - 46) {
                break; // 超出一页，滚轮翻页查看其余难度
            }
            Beatmap diff = song.diffs().get(i);
            addRenderableWidget(Button.builder(diffLabel(diff), btn -> tryStart(diff))
                    .pos(this.width / 2 - 110, y).size(220, 22).build());
            y += 26;
        }
    }

    /** 一页能容纳的难度按钮数（避开顶部标题与底部返回按钮） */
    private int perPage() {
        return Math.max(1, (height - 132) / 26);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        int n = song.diffs().size();
        int per = perPage();
        if (n > per) {
            int ns = scroll - (int) Math.signum(vAmount);
            ns = Math.max(0, Math.min(n - per, ns));
            if (ns != scroll) {
                scroll = ns;
                init(minecraft, width, height); // 重建按钮，翻到新一页
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, hAmount, vAmount);
    }

    /** 歌曲速度滑条：50%~200%，默认 100%。 */
    private final class RateSlider extends AbstractSliderButton {
        private RateSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), (DifficultyScreen.this.songRate - 0.5) / 1.5);
            updateMessage();
        }

        private float current() {
            return (float) (0.5 + this.value * 1.5);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("screen.rhythmfall.rateFmt",
                    String.format("%.2f", current())));
        }

        @Override
        protected void applyValue() {
            songRate = current();
        }
    }

    private Component diffLabel(Beatmap b) {
        String notes = Component.translatable("screen.rhythmfall.noteCount", b.notes.size()).getString();
        String keys = Component.translatable("screen.rhythmfall.keysFmt", b.keys).getString();
        String name = b.difficulty == null || b.difficulty.isBlank() ? "Normal" : b.difficulty;
        return Component.literal(name + "  ·  " + notes + "  ·  " + keys);
    }

    private void tryStart(Beatmap b) {
        fadeTo(() -> this.minecraft.setScreen(new GameScreen(b, this, songRate)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        // 全屏不透明底：与选曲/结算界面一致，屏蔽世界模糊与第三方 GUI mod 干扰
        g.fillGradient(0, 0, width, height, 0xFF161824, 0xFF0E1018);
        labels.clear();

        labels.add(fit(song.name(), width - 40), width / 2, 12, 0xFFFFFFFF, true);
        if (!song.artist().isBlank()) {
            labels.add(song.artist(), width / 2, 26, 0xFF99AABB, true);
        }
        labels.add(Component.translatable("screen.rhythmfall.chooseDifficulty"),
                width / 2, 44, 0xFFCCAA00, true);

        // 难度超出一页时显示翻页状态
        int n = song.diffs().size();
        if (n > perPage()) {
            labels.add(Component.literal(
                    "滚轮翻页 · " + (scroll + 1) + "-" + Math.min(n, scroll + perPage()) + " / " + n),
                    width / 2, height - 44, 0xFF8899AA, true);
        }

        super.render(g, mouseX, mouseY, partialTick);
        drawFades(g);
    }

    private String fit(String s, int maxPx) {
        if (font.width(s) <= maxPx) {
            return s;
        }
        while (s.length() > 1 && font.width(s + "…") > maxPx) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    private void fadeTo(Runnable action) {
        if (fadeStart < 0) {
            fadeStart = Util.getMillis();
            afterFade = action;
        }
    }

    private void drawFades(GuiGraphics g) {
        float fi = UiFx.fadeIn(openedAt, 350);
        if (fi < 1f) {
            g.fill(0, 0, width, height, UiFx.overlay((int) ((1f - fi) * 255)));
        }
        if (fadeStart >= 0) {
            float fo = UiFx.ease((Util.getMillis() - fadeStart) / 250f);
            if (fo >= 1f) {
                Runnable r = afterFade;
                afterFade = null;
                fadeStart = -1; // 关键：跳转后清掉计时，防止复用本实例时残留全黑遮罩
                if (r != null) {
                    r.run();
                }
            } else if (afterFade != null) {
                g.fill(0, 0, width, height, UiFx.overlay((int) (fo * 255)));
            } else {
                fadeStart = -1; // 没有待执行动作的残留计时直接作废
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
