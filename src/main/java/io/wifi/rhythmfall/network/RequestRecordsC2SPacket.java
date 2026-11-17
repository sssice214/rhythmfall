package io.wifi.rhythmfall.network;

import io.wifi.rhythmfall.Rhythmfall;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 请求某张谱面的服务端排行榜。 */
public record RequestRecordsC2SPacket(String mapId) implements CustomPacketPayload {
    public static final Type<RequestRecordsC2SPacket> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Rhythmfall.MOD_ID, "request_records"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRecordsC2SPacket> CODEC =
            StreamCodec.ofMember(RequestRecordsC2SPacket::write, RequestRecordsC2SPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(mapId);
    }

    public static RequestRecordsC2SPacket read(FriendlyByteBuf buf) {
        return new RequestRecordsC2SPacket(buf.readUtf());
    }
}
