package io.wifi.rhythmfall.item;

import io.wifi.rhythmfall.client.ClientHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 节奏控制器：手持右键打开音游 GUI（判定在客户端侧，仅客户端触碰 client 类）。 */
public class RhythmControllerItem extends Item {
    public RhythmControllerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            ClientHooks.openSongSelect();
        }
        ItemStack stack = player.getItemInHand(hand);
        return level.isClientSide
                ? InteractionResultHolder.success(stack)
                : InteractionResultHolder.consume(stack);
    }
}
