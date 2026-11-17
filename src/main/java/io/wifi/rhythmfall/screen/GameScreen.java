package io.wifi.rhythmfall.screen;

import io.wifi.rhythmfall.Rhythmfall;
import io.wifi.rhythmfall.RhythmfallClient;
import io.wifi.rhythmfall.audio.AudioPlayer;
import io.wifi.rhythmfall.audio.AudioPlayers;
import io.wifi.rhythmfall.audio.HitSounds;
import io.wifi.rhythmfall.beatmap.Beatmap;
import io.wifi.rhythmfall.beatmap.Note;
import io.wifi.rhythmfall.client.ClientConfig;
import io.wifi.rhythmfall.client.ChartSource;
import io.wifi.rhythmfall.client.CoverTextures;
import io.wifi.rhythmfall.client.RecordsClient;
import io.wifi.rhythmfall.network.SubmitRecordC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 游玩界面。
 * 判定：Perfect ±50 / Best ±100 / Good ±150 / Bad ±200 ms，超出判 Miss；
 * 长按只有头部判定（无尾判），按住期间播放持续咻音；
 * 击打有波纹特效与「哒」音效；AUTO 模式自动演奏且不计入排行榜。
 * 左侧为连击与排行榜，右侧为封面/曲目信息面板。
 */
public class GameScreen extends Screen {
    private static final long LEAD_IN_MS = 2500;
    private static final long PERFECT_MS = 50;
    private static final long BEST_MS = 100;
    private static final long GOOD_MS = 150;
    private static final long BAD_MS = 200;
    private static final long FINISH_FADE_MS = 700;
    /** 长按判定点间隔（歌曲毫秒）：按住每满 0.3 秒一个 Perfect 判定点 */
    private static final long HOLD_TICK_MS = 300;
    private static final float MIN_MULT = 0.5f;
    private static final float MAX_MULT = 3.0f;

    private enum J { NONE, PERFECT, BEST, GOOD, BAD, MISS }

    private static final int[] J_SCORE = { 0, 300, 250, 200, 50, 0 };
    private static final int[] J_COLOR = { 0, 0xFFFFD700, 0xFF00E676, 0xFF00B0FF, 0xFFFF9500, 0xFFFF5252 };
    private static final Component[] J_TEXT = {
            null,
            Component.literal("Perfect"),
            Component.literal("Best"),
            Component.literal("Good"),
            Component.literal("Bad"),
            Component.literal("Miss")
    };
    private static final Component AUTO_TEXT = Component.literal("AUTO");
    private static final int AUTO_COLOR = 0xFFFFF0A0;
    private static final Component COMBO_LABEL = Component.literal("COMBO");
    private static final Component BOARD_TITLE = Component.translatable("screen.rhythmfall.board");

    private static final int[] LANE_COLORS = {
            0xFF00E5FF, 0xFFFF9500, 0xFFFF2D55, 0xFF7BFF00,
            0xFFB36BFF, 0xFFFF6EC7, 0xFFFFD600
    };

    private final Beatmap map;
    private final Screen parent;
    private final int lanes;
    /** 轨道键 GLFW code（client.json 自定义，自管不注册——避免与移动键冲突） */
    private final int[] laneKeys;
    private final ClientConfig cfg;
    /** 玩家配置的 AUTO（显示 AUTO、不计榜） */
    private final boolean auto;
    /** 开发者自动打歌（管理员面板开启：AUTO 演奏但判定正常显示、成绩计榜） */
    private final boolean devAuto;
    /** 歌曲速度 0.5~2.0（选难度界面设定）：音乐与铺面同比变速，局内不可改 */
    private final float songRate;
    private final List<Note> notes;

    private final J[] head;
    private int remaining;
    private final int[] holdNote;
    private final long[] holdLastTick;
    private final boolean[] laneDown;
    private final long[] autoDownUntil;
    private final HitSounds.Voice[] whoosh;

    private AudioPlayer player;

    private boolean started = false;
    private boolean paused = false;
    private boolean finished = false;
    private boolean resultShown = false;

    private long startSys = -1;
    private long pauseStartSys = 0;
    private long pauseAccum = 0;

    private boolean musicPlaying = false;

    private float speedMult = 1f;

    private int score = 0;
    private int combo = 0;
    private int maxCombo = 0;
    private int perfect = 0;
    private int best = 0;
    private int good = 0;
    private int bad = 0;
    private int miss = 0;

    private int missIdx = 0;
    private int autoIdx = 0;
    private long finishAt = -1;
    private boolean recordSubmitted = false;
    private final long openedAt = Util.getMillis();

    private final List<Floater> floaters = new ArrayList<>();
    private final List<Ripple> ripples = new ArrayList<>();

    // 排行榜显示缓存
    private List<BoardRow> board = List.of();
    private int boardJudged = -1;
    private List<RecordsClient.Entry> boardSource = List.of();

    // HUD 字符串缓存（每 tick 刷新，避免每帧 format）
    private String scoreText = "";
    private String accText = "";
    private String speedText = "";
    private String comboText = "0";
    private String diffText = "";
    private String keysText = "";
    private String rateText = "";

    private static final Component SPEED_HINT = Component.translatable("rhythmfall.hud.speedHint");

    /** 专属 GUI 文本层：界面全部文字经 widgets 通道绘制 */
    private LabelWidget labels;
    /** 专属 GUI 图片层：封面等图片经 widgets 通道绘制 */
    private ImageLayerWidget covers;

    public GameScreen(Beatmap map, Screen parent, float songRate) {
        super(Component.literal(map.name));
        this.map = map;
        this.parent = parent;
        this.lanes = Math.max(1, Math.min(7, map.keys));
        this.notes = map.notes;
        this.cfg = ClientConfig.get();
        this.auto = cfg.autoPlay;
        this.devAuto = cfg.devAutoPlay;
        this.speedMult = cfg.speedMult;
        this.songRate = Math.max(0.5f, Math.min(2f, songRate));
        int[] keys = RhythmfallClient.keysFor(map.keys).clone();
        // 兜底：键位数组长度不足 lanes 时用最后一个键补齐，防止按键判定越界崩溃
        if (keys.length < lanes) {
            int[] fixed = new int[lanes];
            for (int i = 0; i < lanes; i++) {
                fixed[i] = keys[Math.min(i, keys.length - 1)];
            }
            keys = fixed;
        }
        this.laneKeys = keys;
        this.head = new J[notes.size()];
        for (int i = 0; i < head.length; i++) {
            head[i] = J.NONE;
        }
        // 兜底：任何来源的谱面轨道都夹到 0..lanes-1，防 holdNote/laneColor 越界崩溃
        for (Note n : notes) {
            if (n.lane < 0) {
                n.lane = 0;
            } else if (n.lane >= lanes) {
                n.lane = lanes - 1;
            }
        }
        this.remaining = notes.size();
        this.holdNote = new int[lanes];
        this.holdLastTick = new long[lanes];
        this.laneDown = new boolean[lanes];
        this.autoDownUntil = new long[lanes];
        this.whoosh = new HitSounds.Voice[lanes];
        for (int i = 0; i < lanes; i++) {
            holdNote[i] = -1;
            autoDownUntil[i] = -1;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 回归原版行为：对世界做常规压暗（模糊强度由玩家视频设置控制）
        g.fill(0, 0, width, height, 0x66000000);
    }

    @Override
    protected void init() {
        if (!started) {
            started = true;
            startSys = now();
            HitSounds.setVolume(cfg.hitSounds ? cfg.noteVolume : 0f);
            byte[] audioBytes = ChartSource.audioBytes(map);
            if (cfg.playMusic && audioBytes != null) {
                player = AudioPlayers.create(audioBytes);
                if (player != null) {
                    player.setVolume(cfg.musicVolume);
                    player.setPlaybackRate(songRate);
                }
            }
            RecordsClient.request(map.id);
        }
        labels = new LabelWidget();
        addRenderableWidget(labels);
        covers = new ImageLayerWidget();
        addRenderableWidget(covers);
    }

    @Override
    public void onClose() {
        stopPlayback();
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    /**
     * MC 以任何方式替换本界面（掉线、回主菜单、被其他界面覆盖等）都会调用
     * removed() 而不是 onClose()——在这里兜底停止音乐与音效，防止离开音游后
     * 歌还在后台放。stopPlayback 幂等，重复调用安全。
     */
    @Override
    public void removed() {
        stopPlayback();
        restoreHeldMovementKeys();
    }

    /**
     * 关闭音游界面时，把仍被物理按住的移动键（WASD/跳跃/潜行）恢复为按下状态。
     * 打开任何 Screen 时 MC 会释放全部按键绑定，否则退出音游后即使手还按着 W
     * 也无法移动，必须松开重按——这里消除该体验问题。
     */
    private void restoreHeldMovementKeys() {
        Minecraft mc = minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) {
            return;
        }
        long win = mc.getWindow().getWindow();
        KeyMapping[] moves = { mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
                mc.options.keyRight, mc.options.keyJump, mc.options.keyShift };
        for (KeyMapping m : moves) {
            InputConstants.Key k = KeyBindingHelper.getBoundKeyOf(m);
            if (k != null && k.getType() == InputConstants.Type.KEYSYM
                    && k.getValue() != InputConstants.UNKNOWN.getValue()
                    && InputConstants.isKeyDown(win, k.getValue())) {
                m.setDown(true);
            }
        }
    }

    /** 失去窗口焦点时自动暂停（含音乐），回到游戏按空格继续。 */
    public void handleFocusLoss() {
        if (started && !finished && !paused) {
            togglePause();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            player.stop();
        }
        // 立即整体关闭音效引擎：退出音游后保证完全无声
        HitSounds.shutdownNow();
        for (int lane = 0; lane < lanes; lane++) {
            whoosh[lane] = null;
        }
    }

    // ==================== 时间 ====================
    private long now() {
        return Util.getMillis();
    }

    private long gameTime() {
        if (startSys < 0) {
            return 0;
        }
        long t;
        if (paused) {
            // 暂停期间冻结在暂停时刻，铺面与音乐时间一起停住
            t = pauseStartSys - startSys - pauseAccum;
        } else {
            t = now() - startSys - pauseAccum;
        }
        return Math.max(0, t);
    }

    /** 歌曲毫秒时间轴：随歌曲速度同比缩放（1.5×速时铺面/判定也 1.5 倍速）。
     *  不做音频时钟校准——声卡缓冲延迟/消耗速率差异会让时间轴反复跳变（低速尤甚）。 */
    private long songTime() {
        double w = gameTime() - LEAD_IN_MS;
        return Math.round(w * songRate + cfg.offsetMs);
    }

    private void togglePause() {
        if (!started || finished) {
            return;
        }
        // 音频操作全部兜底：任何声卡异常都不允许带崩游戏
        if (paused) {
            long d = now() - pauseStartSys;
            pauseAccum += d;
            if (musicPlaying) {
                try {
                    player.resume();
                } catch (Exception ex) {
                    Rhythmfall.LOGGER.warn("Resume failed", ex);
                }
            }
            paused = false;
        } else {
            pauseStartSys = now();
            if (musicPlaying) {
                try {
                    player.pause();
                } catch (Exception ex) {
                    Rhythmfall.LOGGER.warn("Pause failed", ex);
                }
            }
            HitSounds.stopAll();
            for (int lane = 0; lane < lanes; lane++) {
                whoosh[lane] = null;
            }
            paused = true;
        }
    }

    // ==================== 主循环 ====================
    @Override
    public void tick() {
        super.tick();
        if (!started) {
            return;
        }
        if (finished) {
            if (!resultShown && finishAt >= 0 && now() - finishAt >= FINISH_FADE_MS) {
                resultShown = true;
                stopPlayback();
                minecraft.setScreen(new ResultScreen(map, parent, score, maxCombo,
                        perfect, best, good, bad, miss, auto && !devAuto, songRate));
            }
            return;
        }

        long gt = gameTime();
        if (player != null && !musicPlaying && gt >= LEAD_IN_MS) {
            player.play();
            musicPlaying = true;
        }
        if (paused) {
            refreshHudStrings();
            return;
        }

        long st = songTime();

        if (auto || devAuto) {
            processAuto(st);
        }

        // 漏接判定（长按只有头部判定）
        while (missIdx < notes.size()) {
            Note n = notes.get(missIdx);
            if (st - (n.time + map.offset) > BAD_MS) {
                if (head[missIdx] == J.NONE) {
                    judge(missIdx, J.MISS);
                }
                missIdx++;
            } else {
                break;
            }
        }

        updateHolds(st);

        if (remaining <= 0 && st > map.lastNoteTime() + 800) {
            beginFinish();
        }

        updateFloaters();
        updateRipples();
        refreshBoard();
        refreshHudStrings();
    }

    private void processAuto(long st) {
        for (int i = autoIdx; i < notes.size(); i++) {
            Note n = notes.get(i);
            long t = n.time + map.offset;
            if (t > st) {
                break;
            }
            if (head[i] == J.NONE) {
                judge(i, J.PERFECT);
                autoDownUntil[n.lane] = n.isHold() ? n.endTime + map.offset : st + 80;
            }
        }
        while (autoIdx < notes.size() && head[autoIdx] != J.NONE) {
            autoIdx++;
        }
    }

    /**
     * 长按状态推进：咻音只在「按住进行中的长条」时发声；
     * 按住期间每满 0.3 秒一个 Perfect 判定点（+连击/+300 分/波纹特效，显示与一般判定一致）；
     * 中途松手断连（连击清零、咻声停），重新按住可恢复；到达终点自动收尾。
     */
    private void updateHolds(long st) {
        for (int lane = 0; lane < lanes; lane++) {
            int idx = holdNote[lane];
            if (idx < 0 || idx >= notes.size()) {
                continue;
            }
            Note n = notes.get(idx);
            long end = n.endTime + map.offset;
            if (st >= end) {
                // 到达终点：收尾（松手与否不影响已获得的判定点）
                HitSounds.stopWhoosh(whoosh[lane]);
                whoosh[lane] = null;
                holdNote[lane] = -1;
                holdLastTick[lane] = 0;
                continue;
            }
            boolean held = auto || devAuto || laneDown[lane];
            if (held) {
                if (whoosh[lane] == null) {
                    whoosh[lane] = HitSounds.startWhoosh();
                }
                if (holdLastTick[lane] <= 0) {
                    holdLastTick[lane] = st;
                }
                while (st - holdLastTick[lane] >= HOLD_TICK_MS) {
                    holdLastTick[lane] += HOLD_TICK_MS;
                    // 长条判定点：与一般 Perfect 判定同样显示与计分
                    perfect++;
                    score += J_SCORE[1];
                    combo++;
                    maxCombo = Math.max(maxCombo, combo);
                    boolean showAuto = auto && !devAuto;
                    Component text = showAuto ? AUTO_TEXT : J_TEXT[1];
                    int color = showAuto ? AUTO_COLOR : J_COLOR[1];
                    floaters.add(new Floater(laneCenterX(lane), judgmentY() - 30, text, color));
                    ripples.add(new Ripple(lane, color));
                }
            } else {
                if (whoosh[lane] != null) {
                    // 按住 -> 松开：断连（连击清零；重新按住可继续长按，判定点重新计秒）
                    HitSounds.stopWhoosh(whoosh[lane]);
                    whoosh[lane] = null;
                    combo = 0;
                }
                holdLastTick[lane] = st;
            }
        }
    }

    private void beginFinish() {
        finished = true;
        finishAt = now();
        submitRecord();
    }

    /** 提交成绩给服务端排行榜（每谱面只保留个人最高 acc）。
     *  所有自动演奏（AUTO / 开发者自动打歌）一律不计榜。 */
    private void submitRecord() {
        if (recordSubmitted || auto || devAuto) {
            return; // 自动演奏一律不计入总排名
        }
        recordSubmitted = true;
        if (minecraft != null && minecraft.getConnection() != null && map.id != null) {
            ClientPlayNetworking.send(new SubmitRecordC2SPacket(map.id, (float) accuracy(), score, maxCombo));
        }
    }

    // ==================== 输入 ====================
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 暂停中：轨道键 / 暂停键 / 空格 任意一个都恢复
        // （修复 5K/7K 空格既是中轨道又是暂停键时，暂停后无法继续的问题）
        if (paused) {
            int lane = laneIndexOf(keyCode);
            boolean isPauseKey = RhythmfallClient.pauseKey().matches(keyCode, scanCode)
                    || keyCode == GLFW.GLFW_KEY_SPACE;
            if (lane >= 0 || isPauseKey) {
                togglePause();
                if (lane >= 0) {
                    laneDown[lane] = true;
                }
                return true;
            }
            // 其它按键（含 ESC 退出音游）不吞，走 super 正常处理
        }
        // 打歌中：轨道键（client.json 自管键位，直接比较 GLFW code）
        for (int i = 0; i < lanes; i++) {
            if (keyCode == laneKeys[i]) {
                if (!laneDown[i]) {
                    laneDown[i] = true;
                    if (!finished) {
                        onLanePress(i);
                    }
                }
                return true;
            }
        }
        // 暂停键（默认 TAB）；空格仅在它不是轨道键时兼任暂停（4K 保持空格暂停习惯）
        if (RhythmfallClient.pauseKey().matches(keyCode, scanCode)
                || (keyCode == GLFW.GLFW_KEY_SPACE && !spaceIsLaneKey())) {
            togglePause();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean spaceIsLaneKey() {
        for (int i = 0; i < lanes; i++) {
            if (laneKeys[i] == GLFW.GLFW_KEY_SPACE) {
                return true;
            }
        }
        return false;
    }

    private int laneIndexOf(int keyCode) {
        for (int i = 0; i < lanes; i++) {
            if (laneKeys[i] == keyCode) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        for (int i = 0; i < lanes; i++) {
            if (keyCode == laneKeys[i]) {
                laneDown[i] = false;
                return true;
            }
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        speedMult = Math.max(MIN_MULT, Math.min(MAX_MULT, speedMult + (float) (verticalAmount * 0.1f)));
        return true;
    }

    private void onLanePress(int lane) {
        if (auto) {
            return; // AUTO 模式由系统演奏
        }
        long st = songTime();
        int bestIdx = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = missIdx; i < notes.size(); i++) {
            Note n = notes.get(i);
            long t = n.time + map.offset;
            if (t - st > BAD_MS) {
                break;
            }
            if (n.lane != lane || head[i] != J.NONE) {
                continue;
            }
            long d = Math.abs(st - t);
            if (d <= BAD_MS && d < bestDiff) {
                bestDiff = d;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            J res = bestDiff <= PERFECT_MS ? J.PERFECT
                    : bestDiff <= BEST_MS ? J.BEST
                    : bestDiff <= GOOD_MS ? J.GOOD
                    : J.BAD;
            judge(bestIdx, res);
        }
    }

    private void judge(int idx, J res) {
        if (head[idx] != J.NONE) {
            return;
        }
        head[idx] = res;
        remaining--;
        Note n = notes.get(idx);
        int x = laneCenterX(n.lane);
        if (res == J.MISS) {
            miss++;
            combo = 0;
            floaters.add(new Floater(x, judgmentY() - 30, J_TEXT[5], J_COLOR[5]));
            return;
        }
        switch (res) {
            case PERFECT -> perfect++;
            case BEST -> best++;
            case GOOD -> good++;
            default -> bad++;
        }
        score += J_SCORE[res.ordinal()];
        combo++;
        maxCombo = Math.max(maxCombo, combo);
        boolean showAuto = auto && !devAuto;
        Component text = showAuto ? AUTO_TEXT : J_TEXT[res.ordinal()];
        int color = showAuto ? AUTO_COLOR : J_COLOR[res.ordinal()];
        floaters.add(new Floater(x, judgmentY() - 30, text, color));
        ripples.add(new Ripple(n.lane, color));
        HitSounds.click();
        if (n.isHold() && holdNote[n.lane] < 0) {
            holdNote[n.lane] = idx;
            holdLastTick[n.lane] = songTime();
        }
    }

    private void updateFloaters() {
        long n = now();
        floaters.removeIf(f -> n - f.start > 600);
    }

    private void updateRipples() {
        long n = now();
        ripples.removeIf(r -> n - r.start > 500);
    }

    // ==================== 布局 ====================
    private int laneW() {
        return Math.max(14, Math.min(44, (width - 340) / lanes));
    }

    private int fieldLeft() {
        return (width - lanes * laneW()) / 2;
    }

    private int judgmentY() {
        return height - 55;
    }

    private int laneX(int lane) {
        return fieldLeft() + lane * laneW();
    }

    private int laneCenterX(int lane) {
        return laneX(lane) + laneW() / 2;
    }

    private int radius() {
        return Math.max(6, laneW() / 2 - 2);
    }

    private static void fillCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        if (r <= 0) {
            return;
        }
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.round(Math.sqrt(r * (double) r - dy * (double) dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static void circleOutline(GuiGraphics g, int cx, int cy, int r, int color) {
        int x = r;
        int y = 0;
        int err = 1 - r;
        while (x >= y) {
            put4(g, cx, cy, x, y, color);
            put4(g, cx, cy, y, x, color);
            y++;
            if (err < 0) {
                err += 2 * y + 1;
            } else {
                x--;
                err += 2 * (y - x) + 1;
            }
        }
    }

    private static void put4(GuiGraphics g, int cx, int cy, int x, int y, int color) {
        g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
        g.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color);
        g.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color);
        g.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color);
    }

    private float pxPerMs() {
        return map.scrollSpeed * speedMult / 1000f;
    }

    private float noteY(long noteTime, long st) {
        return judgmentY() - ((noteTime + map.offset) - st) * pxPerMs();
    }

    /** 第一个 (time+offset) >= t 的音符下标（notes 已按 time 排序）。 */
    private int lowerBound(long t) {
        int lo = 0;
        int hi = notes.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (notes.get(mid).time + map.offset < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ==================== 渲染 ====================
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        labels.clear();
        covers.clear();
        drawLanes(g);
        if (!finished) {
            drawNotes(g);
        }
        drawRipples(g);
        drawFloaters(g);
        drawLeftPanel(g);
        drawRightPanel(g);
        if (paused) {
            labels.add(Component.literal("Paused"), width / 2, height / 2 - 20, 0xFFFFFFFF, true);
            labels.add(Component.translatable("rhythmfall.hud.pauseHint",
                    RhythmfallClient.pauseKey().getTranslatedKeyMessage()),
                    width / 2, height / 2, 0xFF9AABB8, true);
        }
        // 全部文字经专属文本层（widgets 通道）绘出
        super.render(g, mouseX, mouseY, partialTick);
        drawFade(g);
    }

    private void drawLanes(GuiGraphics g) {
        long st = songTime();
        for (int i = 0; i < lanes; i++) {
            int x = laneX(i);
            int w = laneW();
            int c = laneColor(i);
            // 轨道底色极淡（去分割感），按下时高亮当前轨道
            g.fill(x, 0, x + w, judgmentY() + 4, 0x0DFFFFFF);
            boolean down = laneDown[i] || ((auto || devAuto) && autoDownUntil[i] > st);
            if (down) {
                g.fill(x, 0, x + w, judgmentY() + 4, (c & 0x00FFFFFF) | 0x28000000);
            }
        }
        // 轨道分隔线延伸至屏幕最底下（键位区也贯通）
        for (int i = 0; i <= lanes; i++) {
            int x = fieldLeft() + i * laneW();
            g.fill(x - 1, 0, x + 1, height, 0x2EFFFFFF);
        }
        int r = radius();
        for (int i = 0; i < lanes; i++) {
            int cx = laneCenterX(i);
            int c = laneColor(i);
            circleOutline(g, cx, judgmentY(), r, c);
            circleOutline(g, cx, judgmentY(), r - 1, c);
            labels.add(InputConstants.Type.KEYSYM.getOrCreate(laneKeys[i])
                            .getDisplayName(), cx, height - 22,
                    (0xFF << 24) | (c & 0xFFFFFF), true);
        }
    }

    private void drawNotes(GuiGraphics g) {
        long st = songTime();
        float px = Math.max(pxPerMs(), 0.001f);
        int window = (int) (judgmentY() / px) + 500;
        int i = lowerBound(st - window - 2000);
        for (; i < notes.size(); i++) {
            Note n = notes.get(i);
            long t = n.time + map.offset;
            if (t - st > window) {
                break;
            }
            drawNote(g, i, st);
        }
    }

    private void drawNote(GuiGraphics g, int idx, long st) {
        Note n = notes.get(idx);
        int cx = laneCenterX(n.lane);
        int r = radius();
        if (!n.isHold()) {
            if (head[idx] != J.NONE) {
                return;
            }
            int y = Math.round(noteY(n.time, st));
            if (y < -r - 20 || y > judgmentY() + r + 60) {
                return;
            }
            fillCircle(g, cx, y, r, laneColor(n.lane));
            circleOutline(g, cx, y, r, 0xFFFFFFFF);
        } else {
            if (head[idx] == J.MISS) {
                return;
            }
            int top = Math.round(noteY(n.endTime, st));
            int bottom;
            if (head[idx] == J.NONE) {
                bottom = Math.round(noteY(n.time, st));
                if (bottom > judgmentY()) {
                    bottom = judgmentY(); // 头部已到线仍未击，贴线显示
                }
            } else {
                // 已击头部：柱体钉在判定线上，随尾部接近逐渐收短
                if (top > judgmentY() + 2) {
                    return;
                }
                bottom = judgmentY();
            }
            if (top < -40 && bottom < -40) {
                return;
            }
            if (bottom < top) {
                bottom = top;
            }
            int color = head[idx] != J.NONE ? 0xFF00C853 : laneColor(n.lane);
            fillCircle(g, cx, top, r, color);
            fillCircle(g, cx, bottom, r, color);
            g.fill(cx - r, top, cx + r + 1, bottom, color);
            circleOutline(g, cx, top, r, 0xFFFFFFFF);
            circleOutline(g, cx, bottom, r, 0xFFFFFFFF);
        }
    }

    private void drawRipples(GuiGraphics g) {
        int r = radius();
        long n = now();
        long life = cfg.effectsHigh ? 420 : 300;
        for (Ripple rp : ripples) {
            float p = (n - rp.start) / (float) life;
            if (p < 0f || p >= 1f) {
                continue;
            }
            // 扩散半径收小、环加厚（双描边）
            float scale = cfg.effectsHigh ? 1.4f : 0.9f;
            int rr = Math.max(3, Math.round(r * (0.7f + p * scale)));
            int alpha = (int) ((1f - p) * 190);
            int color = (alpha << 24) | (rp.color & 0x00FFFFFF);
            circleOutline(g, laneCenterX(rp.lane), judgmentY(), rr, color);
            circleOutline(g, laneCenterX(rp.lane), judgmentY(), Math.max(2, rr - 1), color);
            if (cfg.effectsHigh) {
                float p2 = p - 0.22f;
                if (p2 > 0f) {
                    int rr2 = Math.max(3, Math.round(r * (0.7f + p2 * scale)));
                    int alpha2 = (int) ((1f - p2) * 140);
                    int color2 = (alpha2 << 24) | (rp.color & 0x00FFFFFF);
                    circleOutline(g, laneCenterX(rp.lane), judgmentY(), rr2, color2);
                    circleOutline(g, laneCenterX(rp.lane), judgmentY(), Math.max(2, rr2 - 1), color2);
                }
            }
        }
    }

    private void drawFloaters(GuiGraphics g) {
        long n = now();
        for (Floater f : floaters) {
            float life = 1f - (n - f.start) / 600f;
            if (life <= 0) {
                continue;
            }
            // 原地扩散式：判定文字不上升，跟随波纹原地轻微放大并淡出
            int alpha = (int) (life * 255);
            int color = (alpha << 24) | (f.color & 0x00FFFFFF);
            labels.addScaled(f.text, f.x, f.y, color, true, 1f + (1 - life) * 0.18f);
        }
    }

    // ==================== 左侧面板：连击 + 排行榜 ====================
    private void drawLeftPanel(GuiGraphics g) {
        labels.add(scoreText, 10, 10, 0xFFFFFFFF, false);
        labels.add(accText, 10, 26, 0xFF9AD8FF, false);

        // 连击（实时）
        g.fill(6, 44, 168, 104, 0x55000000);
        labels.add(COMBO_LABEL, 87, 50, 0xFFCCCCCC, true);
        labels.addScaled(comboText, 87, 66, combo > 0 ? 0xFFFFAA00 : 0xFF888888, true, 1.6f);

        // 排行榜（服务端成绩 + 本地实时条目）
        if (board.isEmpty()) {
            return;
        }
        int y = 112;
        int rows = board.size();
        g.fill(6, y, 168, y + 20 + rows * 13, 0x55000000);
        labels.add(BOARD_TITLE, 12, y + 5, 0xFFFFAA00, false);
        int ry = y + 18;
        for (BoardRow r : board) {
            String name = r.name();
            if (font.width(name) > 92) {
                while (name.length() > 1 && font.width(name + "…") > 92) {
                    name = name.substring(0, name.length() - 1);
                }
                name = name + "…";
            }
            labels.add(name, 12, ry, r.self() ? 0xFFFFD700 : 0xFFDDDDDD, false);
            String acc = r.accText();
            labels.add(acc, 162 - font.width(acc), ry, r.self() ? 0xFFFFD700 : 0xFF99BBCC, false);
            ry += 13;
        }
    }

    /** 判定数或服务端数据变化时重建显示列表（本地条目默认 100% 置顶并动态浮动）。 */
    private void refreshBoard() {
        int judged = perfect + best + good + bad + miss;
        List<RecordsClient.Entry> src = RecordsClient.records();
        if (judged == boardJudged && src == boardSource) {
            return;
        }
        boardJudged = judged;
        boardSource = src;
        String selfName = minecraft != null && minecraft.getUser() != null ? minecraft.getUser().getName() : "?";
        List<BoardRow> rows = new ArrayList<>();
        for (RecordsClient.Entry e : src) {
            rows.add(new BoardRow(e.name(), e.acc(), false));
        }
        rows.add(new BoardRow(selfName, (float) accuracy(), true));
        rows.sort((a, b) -> Float.compare(b.acc, a.acc));
        if (rows.size() > 10) {
            List<BoardRow> shown = new ArrayList<>(rows.subList(0, 9));
            BoardRow self = rows.stream().filter(BoardRow::self).findFirst().orElse(null);
            if (self != null && !shown.contains(self)) {
                shown.add(self);
            }
            rows = shown;
        }
        board = rows;
    }

    private void refreshHudStrings() {
        scoreText = Component.translatable("rhythmfall.hud.score", score).getString();
        accText = Component.translatable("rhythmfall.hud.acc", String.format("%.2f%%", accuracy())).getString();
        speedText = Component.translatable("rhythmfall.hud.speed", String.format("%.1f", speedMult)).getString();
        comboText = String.valueOf(combo);
        diffText = Component.translatable("screen.rhythmfall.difficulty",
                map.difficulty == null || map.difficulty.isBlank() ? "Normal" : map.difficulty).getString();
        keysText = Component.translatable("screen.rhythmfall.keysFmt", map.keys).getString();
        rateText = Component.translatable("rhythmfall.result.rate", String.format("%.2f", songRate)).getString();
    }

    private double accuracy() {
        int judged = perfect + best + good + bad + miss;
        if (judged == 0) {
            return 100.0;
        }
        return (perfect * 300.0 + best * 250.0 + good * 200.0 + bad * 50.0) / (judged * 300.0) * 100.0;
    }

    // ==================== 右侧面板：封面/曲目信息 ====================
    private void drawRightPanel(GuiGraphics g) {
        int pw = 118;
        int x = width - pw - 12;
        CoverTextures.Tex tex = CoverTextures.get(map);
        int coverSize = 96;
        int coverX = x + (pw - coverSize) / 2;
        g.fill(coverX - 2, 6, coverX + coverSize + 2, 6 + coverSize + 2, 0x55000000);
        if (tex != null) {
            covers.addFit(tex.loc(), coverX, 8, coverSize, coverSize, tex.width(), tex.height());
        } else {
            g.fillGradient(coverX, 8, coverX + coverSize, 8 + coverSize, 0xFF3A3F52, 0xFF1C1E28);
        }

        int ty = 8 + coverSize + 10;
        labels.add(fitStr(map.name, pw), x, ty, 0xFFFFFFFF, false);
        ty += 12;
        if (!map.artist.isBlank()) {
            labels.add(fitStr(map.artist, pw), x, ty, 0xFF99AABB, false);
            ty += 12;
        }
        labels.add(diffText, x, ty, 0xFFCCAA00, false);
        ty += 12;
        labels.add(keysText, x, ty, 0xFF99AABB, false);
        ty += 12;
        labels.add(rateText, x, ty, 0xFF99AABB, false);
        ty += 12;
        labels.add(speedText, x, ty, 0xFFFFFFFF, false);
        labels.add(SPEED_HINT, width - 12 - font.width(SPEED_HINT), ty + 12, 0xFF999999, false);

        if (auto && !devAuto) {
            Component badge = Component.literal("AUTO");
            int bw = font.width(badge) + 8;
            g.fill(x, height - 24, x + bw, height - 10, 0x66FFD700);
            labels.add(badge, x + 4, height - 21, 0xFF222222, false);
        }
    }

    private String fitStr(String s, int maxPx) {
        if (s == null || font.width(s) <= maxPx) {
            return s == null ? "" : s;
        }
        while (s.length() > 1 && font.width(s + "…") > maxPx) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    /** 淡入（开场）与淡出（结束进结算）遮罩。 */
    private void drawFade(GuiGraphics g) {
        float fi = UiFx.fadeIn(openedAt, 400);
        if (fi < 1f) {
            g.fill(0, 0, width, height, UiFx.overlay((int) ((1f - fi) * 255)));
        }
        if (finished && finishAt >= 0) {
            float fo = UiFx.ease((now() - finishAt) / (float) FINISH_FADE_MS);
            g.fill(0, 0, width, height, UiFx.overlay((int) (fo * 255)));
        }
    }

    private static int laneColor(int lane) {
        return LANE_COLORS[lane % LANE_COLORS.length];
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private static class Floater {
        final int x;
        final int y;
        final Component text;
        final int color;
        final long start = Util.getMillis();

        Floater(int x, int y, Component text, int color) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
        }
    }

    private static class Ripple {
        final int lane;
        final int color;
        final long start = Util.getMillis();

        Ripple(int lane, int color) {
            this.lane = lane;
            this.color = color;
        }
    }

    private record BoardRow(String name, float acc, boolean self) {
        String accText() {
            return String.format("%.2f%%", acc);
        }
    }
}
