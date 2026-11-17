package io.wifi.rhythmfall.network;

import io.wifi.rhythmfall.Rhythmfall;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 提交一次游玩成绩（服务端只保留每位玩家的最高 acc，总榜上限 50 条）。 */
public record SubmitRecordC2SPacket(String mapId, float acc, int score, int maxCombo)
        implements CustomPacketPayload {
    public static final Type<SubmitRecordC2SPacket> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Rhythmfall.MOD_ID, "submit_record"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SubmitRecordC2SPacket> CODEC =
            StreamCodec.ofMember(SubmitRecordC2SPacket::write, SubmitRecordC2SPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(mapId);
        buf.writeFloat(acc);
        buf.writeInt(score);
        buf.writeInt(maxCombo);
    }

    public static SubmitRecordC2SPacket read(FriendlyByteBuf buf) {
        return new SubmitRecordC2SPacket(buf.readUtf(), buf.readFloat(), buf.readInt(), buf.readInt());
    }
}
