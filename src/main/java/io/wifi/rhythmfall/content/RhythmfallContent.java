package io.wifi.rhythmfall.content;

import io.wifi.rhythmfall.Rhythmfall;
import io.wifi.rhythmfall.block.RhythmMachineBlock;
import io.wifi.rhythmfall.item.RhythmControllerItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * 注册「节奏控制器」物品与「音游机」方块：
 * 二者右键即可打开音游 GUI（首次为配置界面，其后为选曲界面）。
 * 在公共入口（客户端/服务端各一次生命周期内）调用 register()。
 */
public final class RhythmfallContent {
    public static final Block RHYTHM_MACHINE = new RhythmMachineBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_CYAN)
            .strength(1.2f, 8.0f)
            .sound(SoundType.WOOD));
    public static final Item RHYTHM_MACHINE_ITEM = new BlockItem(RHYTHM_MACHINE, new Item.Properties());
    public static final Item RHYTHM_CONTROLLER = new RhythmControllerItem(
            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

    private static boolean registered = false;

    private RhythmfallContent() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        Registry.register(BuiltInRegistries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(Rhythmfall.MOD_ID, "rhythm_machine"), RHYTHM_MACHINE);
        Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Rhythmfall.MOD_ID, "rhythm_machine"), RHYTHM_MACHINE_ITEM);
        Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Rhythmfall.MOD_ID, "rhythm_controller"), RHYTHM_CONTROLLER);

        // 加入创造模式物品栏（工具与实用物品）
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(RHYTHM_CONTROLLER);
            entries.accept(RHYTHM_MACHINE_ITEM);
        });
    }
}
