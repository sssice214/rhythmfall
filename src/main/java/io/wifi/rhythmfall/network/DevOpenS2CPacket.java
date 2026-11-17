package io.wifi.rhythmfall.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：打开开发者面板（/rhythmfall dev，OP 专属）。 */
public record DevOpenS2CPacket() implements CustomPacketPayload {
    public static final Type<DevOpenS2CPacket> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath("rhythmfall", "dev_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DevOpenS2CPacket> CODEC =
            StreamCodec.unit(new DevOpenS2CPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
