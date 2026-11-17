package io.wifi.rhythmfall.screen;

import io.wifi.rhythmfall.beatmap.Beatmap;
import io.wifi.rhythmfall.client.CoverTextures;
import io.wifi.rhythmfall.client.ChartSource;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选曲界面：先选歌、再选难度。
 * 横向轮播布局，滚轮/方向键切换，卡片带淡入淡出与平滑位移动画。
 */
public class SongSelectScreen extends Screen {
    private static final int CARD = 96;
    private static final int GAP = 112;
    private static final int VISIBLE = 6;
    private static final List<String> DIFF_ORDER = List.of(
            "easy", "normal", "hard", "insane", "expert", "beyond");

    private final Screen parent;
    private List<Song> songs = List.of();
    private int sel = 0;
    private float anim = 0f;
    private int loadedGen = -1;
    private final long openedAt = Util.getMillis();
    private long fadeStart = -1;
    private Runnable afterFade;
    private LabelWidget labels;
    private ImageLayerWidget covers;

    public SongSelectScreen(Screen parent) {
        super(Component.translatable("screen.rhythmfall.songs"));
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
        covers = new ImageLayerWidget();
        addRenderableWidget(covers);
        addRenderableWidget(Button.builder(Component.translatable("screen.rhythmfall.settings"),
                        b -> fadeTo(() -> this.minecraft.setScreen(new ConfigScreen(this))))
                .pos(10, this.height - 30).size(90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.rhythmfall.random"),
                        b -> openRandom())
                .pos(this.width / 2 - 60, this.height - 30).size(120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.rhythmfall.refresh"),
                        b -> ChartSource.scan(minecraft))
                .pos(this.width - 100, this.height - 30).size(90, 20).build());
        refreshSongs();
        ChartSource.scan(minecraft);
    }

    /** 随机选一首歌进入难度界面（开始前仍可调难度与歌曲速度）。 */
    private void openRandom() {
        if (songs.isEmpty()) {
            return;
        }
        Song chosen = songs.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(songs.size()));
        fadeTo(() -> this.minecraft.setScreen(new DifficultyScreen(this, chosen)));
    }

    private void refreshSongs() {
        List<Beatmap> maps = ChartSource.charts();
        Map<String, Song> grouped = new LinkedHashMap<>();
        List<Beatmap> sorted = new ArrayList<>(maps);
        sorted.sort((a, b) -> {
            int c = a.name.compareToIgnoreCase(b.name);
            return c != 0 ? c : a.difficulty.compareToIgnoreCase(b.difficulty);
        });
        for (Beatmap b : sorted) {
            Song song = grouped.computeIfAbsent(b.songKey(),
                    k -> new Song(b.name, b.artist, new ArrayList<>()));
            song.diffs().add(b);
        }
        List<Song> list = new ArrayList<>(grouped.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        // 组内难度按 Easy→Beyond 排序
        for (Song song : list) {
            song.diffs().sort((a, b) -> {
                int ra = difficultyRank(a.difficulty);
                int rb = difficultyRank(b.difficulty);
                return ra != rb ? Integer.compare(ra, rb)
                        : String.CASE_INSENSITIVE_ORDER.compare(a.difficulty, b.difficulty);
            });
        }
        songs = list;
        if (sel >= songs.size()) {
            sel = Math.max(0, songs.size() - 1);
        }
    }

    private static int difficultyRank(String d) {
        String k = d == null ? "" : d.trim().toLowerCase();
        for (int i = 0; i < DIFF_ORDER.size(); i++) {
            if (k.startsWith(DIFF_ORDER.get(i))) {
                return i;
            }
        }
        return 100;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        // 全屏不透明底：彻底屏蔽原版世界模糊后效与第三方 GUI mod 干扰，保证清晰度
        g.fillGradient(0, 0, width, height, 0xFF161824, 0xFF0E1018);
        labels.clear();
        covers.clear();
        labels.add(title, width / 2, 14, 0xFFFFFFFF, true);

        if (loadedGen != ChartSource.generation()) {
            loadedGen = ChartSource.generation();
            refreshSongs();
        }

        if (songs.isEmpty()) {
            labels.add(Component.translatable("screen.rhythmfall.loading"),
                    width / 2, height / 2, 0xFFAAAAAA, true);
        } else {
            drawCarousel(g);
        }

        super.render(g, mouseX, mouseY, partialTick);
        drawFades(g);
    }

    private void drawCarousel(GuiGraphics g) {
        anim += (sel - anim) * 0.22f;
        int centerY = height / 2 - 20;

        // 不使用 scissor 裁剪（部分环境/驱动下 scissor 内绘制动态纹理会变纯黑），改用越界剔除
        int first = Math.max(0, sel - VISIBLE);
        int last = Math.min(songs.size() - 1, sel + VISIBLE);
        for (int i = first; i <= last; i++) {
            float dist = Math.abs(i - anim);
            if (dist > VISIBLE + 1) {
                continue;
            }
            int cx = (int) (width / 2f + (i - anim) * GAP) - CARD / 2;
            if (cx + CARD < 0 || cx > width) {
                continue;
            }
            int cy = centerY - CARD / 2;
            float alpha = Math.max(0.1f, 1f - dist * 0.18f);
            int alphaI = (int) (alpha * 255);

            // 阴影 + 卡片底
            g.fill(cx + 5, cy + 5, cx + CARD + 5, cy + CARD + 5, UiFx.overlay((int) (alphaI * 0.5f)));
            g.fill(cx, cy, cx + CARD, cy + CARD, (alphaI << 24) | 0x2A2C38);

            // 封面（经专属图片层/widgets 通道绘制）
            CoverTextures.Tex tex = CoverTextures.get(songs.get(i).rep());
            if (tex != null) {
                covers.addFit(tex.loc(), cx, cy, CARD, CARD, tex.width(), tex.height());
            } else {
                g.fillGradient(cx, cy, cx + CARD, cy + CARD, (alphaI << 24) | 0x3A3F52, (alphaI << 24) | 0x1C1E28);
                String initial = songs.get(i).name();
                if (!initial.isBlank()) {
                    labels.add(initial.substring(0, 1), cx + CARD / 2,
                            cy + CARD / 2 - 6, (alphaI << 24) | 0x99AABB, true);
                }
            }

            // 选中高亮框
            if (dist < 0.45f) {
                g.renderOutline(cx - 2, cy - 2, CARD + 4, CARD + 4, 0xFFFFD700);
                g.renderOutline(cx - 3, cy - 3, CARD + 6, CARD + 6, 0x60FFD700);
            }
        }

        // 选中歌曲信息
        Song song = songs.get(sel);
        int infoY = centerY + CARD / 2 + 12;
        labels.add(fit(song.name(), width - 160), width / 2, infoY, 0xFFFFFFFF, true);
        String sub = (song.artist().isBlank() ? "" : song.artist() + " · ")
                + Component.translatable("screen.rhythmfall.diffCount", song.diffs().size()).getString();
        labels.add(sub, width / 2, infoY + 12, 0xFF99AABB, true);

        Component hint = Component.translatable("screen.rhythmfall.selectHint");
        labels.add(hint, width / 2, height - 46, 0xFF8899AA, true);

        // 左右页指示
        if (songs.size() > 1) {
            labels.add(Component.literal((sel + 1) + " / " + songs.size()),
                    width / 2, 30, 0xFF8899AA, true);
        }
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

    private void openSelected() {
        if (songs.isEmpty()) {
            return;
        }
        Song target = songs.get(sel); // 提前捕获，避免淡出期间滚动改变选择
        fadeTo(() -> this.minecraft.setScreen(new DifficultyScreen(this, target)));
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
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (!songs.isEmpty()) {
            sel = Math.max(0, Math.min(songs.size() - 1, sel - (int) Math.signum(vAmount)));
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int centerY = height / 2 - 20;
        if (button == 0 && !songs.isEmpty()
                && mouseY >= centerY - CARD / 2 - 6 && mouseY <= centerY + CARD / 2 + 6) {
            int i = (int) Math.round((mouseX - width / 2f) / GAP + anim);
            if (i >= 0 && i < songs.size()) {
                if (i == sel && Math.abs(i - anim) < 0.35f) {
                    openSelected();
                } else {
                    sel = i;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            sel = Math.max(0, sel - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            sel = Math.min(songs.size() - 1, sel + 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            openSelected();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
