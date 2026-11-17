package io.wifi.rhythmfall.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：打开音游 GUI（/rhythmfall 指令触发，可指定玩家）。 */
public record OpenGuiS2CPacket() implements CustomPacketPayload {
    public static final Type<OpenGuiS2CPacket> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath("rhythmfall", "open_gui"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenGuiS2CPacket> CODEC =
            StreamCodec.unit(new OpenGuiS2CPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
