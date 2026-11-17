package io.wifi.rhythmfall.screen;

import io.wifi.rhythmfall.beatmap.Beatmap;
import io.wifi.rhythmfall.client.CoverTextures;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 结算界面：不透明底色（修复被铺面遮挡的问题），淡入显示，
 * 展示封面、Rank、分数、准确率、最大连击与判定统计。
 * AUTO 模式评级显示为 AUTO 且不提交排行榜。
 */
public class ResultScreen extends Screen {
    private static final int COVER_SIZE = 96;

    private final Beatmap map;
    private final Screen parent;
    private final int score;
    private final int maxCombo;
    private final int perfect;
    private final int best;
    private final int good;
    private final int bad;
    private final int miss;
    private final boolean auto;
    /** 本局的歌曲速度（选难度界面设定） */
    private final float songRate;
    private final long openedAt = Util.getMillis();
    private LabelWidget labels;
    private ImageLayerWidget covers;

    public ResultScreen(Beatmap map, Screen parent, int score, int maxCombo,
                        int perfect, int best, int good, int bad, int miss, boolean auto, float songRate) {
        super(Component.translatable("rhythmfall.result.title"));
        this.map = map;
        this.parent = parent;
        this.score = score;
        this.maxCombo = maxCombo;
        this.perfect = perfect;
        this.best = best;
        this.good = good;
        this.bad = bad;
        this.miss = miss;
        this.auto = auto;
        this.songRate = songRate;
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
        covers = new ImageLayerWidget();
        addRenderableWidget(covers);
        addRenderableWidget(Button.builder(Component.translatable("screen.rhythmfall.retry"),
                        b -> minecraft.setScreen(new GameScreen(map, parent, songRate)))
                .pos(this.width / 2 - 104, this.height - 56)
                .size(100, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("screen.rhythmfall.back"), b -> close())
                .pos(this.width / 2 + 4, this.height - 56)
                .size(100, 20)
                .build());
    }

    private void close() {
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            minecraft.setScreen(new SongSelectScreen(null));
        }
    }

    @Override
    public void onClose() {
        close();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private double accuracy() {
        int total = perfect + best + good + bad + miss;
        if (total == 0) {
            return 0;
        }
        return (perfect * 300.0 + best * 250.0 + good * 200.0 + bad * 50.0) / (total * 300.0) * 100.0;
    }

    private String rank() {
        if (auto) {
            return "AUTO";
        }
        double acc = accuracy();
        if (acc >= 100) return "SSS";
        if (acc >= 98) return "SS";
        if (acc >= 95) return "S";
        if (acc >= 90) return "A";
        if (acc >= 80) return "B";
        if (acc >= 70) return "C";
        return "D";
    }

    private int rankColor() {
        if (auto) {
            return 0xFFFFD700;
        }
        return switch (rank()) {
            case "SSS", "SS", "S" -> 0xFFFFD700;
            case "A" -> 0xFF00E676;
            case "B" -> 0xFF00B0FF;
            case "C" -> 0xFFFF9500;
            default -> 0xFFFF5252;
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 全屏不透明底色：彻底避免铺面/世界残留遮挡结算内容
        g.fill(0, 0, width, height, 0xFF14141C);
        labels.clear();
        covers.clear();
        int cx = width / 2;

        labels.add(title, cx, 14, 0xFFFFAA00, true);

        // 封面 + 信息行整体垂直居中于「标题下、按钮上」之间：行距固定，窗口小时自适应压缩
        int top = 30;
        int bottom = height - 64;
        int rows = 7;
        CoverTextures.Tex tex = CoverTextures.get(map);
        int step = Math.max(13, Math.min(22, (bottom - top - COVER_SIZE - 10) / rows));
        int contentH = (tex != null ? COVER_SIZE + 10 : 0) + rows * step;
        int y = top + Math.max(0, (bottom - top - contentH) / 2);

        if (tex != null) {
            covers.addFit(tex.loc(), cx - COVER_SIZE / 2, y, COVER_SIZE, COVER_SIZE,
                    tex.width(), tex.height());
            g.renderOutline(cx - COVER_SIZE / 2 - 1, y - 1, COVER_SIZE + 2, COVER_SIZE + 2, 0x40FFFFFF);
            y += COVER_SIZE + 10;
        }

        Component song = Component.literal(map.artist.isBlank() ? map.name : map.name + " - " + map.artist);
        labels.add(song, cx, y, 0xFFFFFFFF, true);
        y += step;
        labels.add(Component.translatable("rhythmfall.result.rank", rank()),
                cx, y, rankColor(), true);
        y += step;
        labels.add(Component.translatable("rhythmfall.result.score", score), cx, y, 0xFFFFFFFF, true);
        y += step;
        labels.add(Component.translatable("rhythmfall.result.accuracy", String.format("%.2f%%", accuracy())),
                cx, y, 0xFFFFFFFF, true);
        y += step;
        labels.add(Component.translatable("rhythmfall.result.combo", maxCombo), cx, y, 0xFFFFFFFF, true);
        y += step;
        labels.add(Component.translatable("rhythmfall.result.counts5",
                        perfect, best, good, bad, miss), cx, y, 0xFFFFFFFF, true);
        y += step;
        labels.add(Component.translatable("rhythmfall.result.rate", String.format("%.2f", songRate)),
                cx, y, 0xFF99AABB, true);

        super.render(g, mouseX, mouseY, partialTick);

        float fi = UiFx.fadeIn(openedAt, 400);
        if (fi < 1f) {
            g.fill(0, 0, width, height, UiFx.overlay((int) ((1f - fi) * 255)));
        }
    }
}
