package io.wifi.rhythmfall.client;

import io.wifi.rhythmfall.screen.ConfigScreen;
import io.wifi.rhythmfall.screen.DevScreen;
import io.wifi.rhythmfall.screen.SongSelectScreen;
import net.minecraft.client.Minecraft;

/** 物品/方块右键与 /rhythmfall 指令打开 GUI 的客户端入口（仅客户端侧调用）。 */
public final class ClientHooks {
    private ClientHooks() {
    }

    /** 首次打开进入配置界面，之后直接进入选曲界面。 */
    public static void openSongSelect() {
        openSongSelect(false);
    }

    /** 打开开发者面板（管理员 /rhythmfall dev）。 */
    public static void openDev() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.setScreen(new DevScreen(null));
    }

    /**
     * force=true（/rhythmfall 指令指定玩家时）覆盖玩家当前打开的界面；
     * 右键入口保持 force=false：已有界面打开时不抢屏。
     */
    public static void openSongSelect(boolean force) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!force && mc.screen != null) {
            return;
        }
        if (!ClientConfig.get().configured) {
            mc.setScreen(new ConfigScreen(null));
        } else {
            mc.setScreen(new SongSelectScreen(null));
        }
    }
}
