package io.wifi.rhythmfall;

import io.wifi.rhythmfall.audio.HitSounds;
import io.wifi.rhythmfall.client.ClientConfig;
import io.wifi.rhythmfall.client.ClientHooks;
import io.wifi.rhythmfall.client.RecordsClient;
import io.wifi.rhythmfall.network.DevOpenS2CPacket;
import io.wifi.rhythmfall.network.OpenGuiS2CPacket;
import io.wifi.rhythmfall.network.RecordsS2CPacket;
import io.wifi.rhythmfall.screen.GameScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口。
 * 打开方式：/rhythmfall 指令（可指定玩家）、手持「节奏控制器」或右键「音游机」方块，三者等效。
 * 轨道键位存于 client.json（不注册全局按键——彻底避开与原版移动键 S/D/空格 的冲突）；
 * 仅暂停键保留全局注册（默认 TAB，无冲突，可在控制设置修改）。
 * 音游界面打开时玩家无法主动移动（MC 原版 Screen 行为），外物推动照常生效。
 */
public class RhythmfallClient implements ClientModInitializer {
    /** 打歌暂停/继续键（默认 TAB：无原版冲突，可在控制设置修改） */
    private static final KeyMapping PAUSE_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.rhythmfall.pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_TAB, "category.rhythmfall"));

    /** @return 打歌暂停/继续键绑定 */
    public static KeyMapping pauseKey() { return PAUSE_KEY; }

    @Override
    public void onInitializeClient() {
        ClientConfig cfg = ClientConfig.get();
        HitSounds.setVolume(cfg.hitSounds ? cfg.noteVolume : 0f);

        // 打歌中切出窗口（去玩别的游戏等）自动暂停音乐与谱面
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.screen instanceof GameScreen gs && !client.isWindowActive()) {
                gs.handleFocusLoss();
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(RecordsS2CPacket.ID,
                (pkt, ctx) -> RecordsClient.onRecords(pkt));

        // /rhythmfall 指令：服务端要求本客户端打开音游 GUI（强制覆盖当前界面）
        ClientPlayNetworking.registerGlobalReceiver(OpenGuiS2CPacket.ID,
                (pkt, ctx) -> ClientHooks.openSongSelect(true));

        // /rhythmfall dev 指令：打开开发者面板（管理员）
        ClientPlayNetworking.registerGlobalReceiver(DevOpenS2CPacket.ID,
                (pkt, ctx) -> net.minecraft.client.Minecraft.getInstance()
                        .execute(ClientHooks::openDev));
    }

    private static final int[] DEFAULT_4K = {68, 70, 74, 75};

    /** @return 指定键数的轨道键 GLFW code 方案（client.json 可自定义；非法配置回落 4K 默认） */
    public static int[] keysFor(int keyCount) {
        ClientConfig cfg = ClientConfig.get();
        int[] set = switch (keyCount) {
            case 5 -> cfg.laneKeys5;
            case 6 -> cfg.laneKeys6;
            case 7 -> cfg.laneKeys7;
            default -> cfg.laneKeys4;
        };
        if (set == null || set.length == 0) {
            set = DEFAULT_4K;
        }
        return set.clone();
    }
}
