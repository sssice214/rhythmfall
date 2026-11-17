package io.wifi.rhythmfall.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.wifi.rhythmfall.Rhythmfall;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端本地配置（config/rhythmfall/client.json）：
 * 流速、延迟校准、特效档位、音符音效/音乐开关与音量、自动演奏。
 * 首次打开 GUI 会先进入配置界面，完成后 configured=true。
 */
public final class ClientConfig {
    /** 是否已完成首次配置（决定打开 GUI 时先显示哪个界面） */
    public boolean configured = false;
    /** 流速倍率 0.5~3.0 */
    public float speedMult = 1.0f;
    /** 延迟校准（毫秒） -300~300 */
    public int offsetMs = 0;
    /** 特效档位：true=高特效 false=低特效 */
    public boolean effectsHigh = true;
    /** 音符击打音效开关 */
    public boolean hitSounds = true;
    /** 音乐开关 */
    public boolean playMusic = true;
    /** 音乐音量 0~1 */
    public float musicVolume = 0.8f;
    /** 音符音效音量 0~1 */
    public float noteVolume = 0.8f;
    /** 自动演奏（判定显示 AUTO，不计入排行榜） */
    public boolean autoPlay = false;

    /** 开发者自动打歌（管理员面板开启：AUTO 演奏但判定正常显示，不计榜） */
    public boolean devAutoPlay = false;

    /**
     * 轨道键位（GLFW keycode，client.json 可直接改；不注册全局按键，避免与移动键冲突）。
     * 默认：4K = D F J K；5K = D F 空格 J K；6K = S D F J K L；7K = S D F 空格 J K L
     */
    public int[] laneKeys4 = {68, 70, 74, 75};
    public int[] laneKeys5 = {68, 70, 32, 74, 75};
    public int[] laneKeys6 = {83, 68, 70, 74, 75, 76};
    public int[] laneKeys7 = {83, 68, 70, 32, 74, 75, 76};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("rhythmfall").resolve("client.json");

    private static ClientConfig instance;

    private ClientConfig() {
    }

    public static synchronized ClientConfig get() {
        if (instance == null) {
            instance = new ClientConfig();
            if (Files.isRegularFile(FILE)) {
                try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    ClientConfig loaded = GSON.fromJson(r, ClientConfig.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                } catch (Exception e) {
                    Rhythmfall.LOGGER.warn("Failed to read client config, using defaults", e);
                }
            }
        }
        return instance;
    }

    /** 在 IO 线程异步落盘。 */
    public static void saveAsync() {
        ClientConfig snapshot = get();
        Rhythmfall.IO.execute(() -> {
            try {
                Files.createDirectories(FILE.getParent());
                try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, w);
                }
            } catch (IOException e) {
                Rhythmfall.LOGGER.warn("Failed to write client config", e);
            }
        });
    }
}
