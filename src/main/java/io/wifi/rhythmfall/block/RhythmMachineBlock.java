package io.wifi.rhythmfall.block;

import io.wifi.rhythmfall.client.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 音游机方块：右键打开音游 GUI（判定在客户端侧，仅客户端触碰 client 类）。 */
public class RhythmMachineBlock extends Block {
    public RhythmMachineBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            ClientHooks.openSongSelect();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }
}
