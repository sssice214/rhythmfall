package io.wifi.rhythmfall.network;

import io.wifi.rhythmfall.Rhythmfall;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** 谱面排行榜（按 acc 降序）。 */
public record RecordsS2CPacket(String mapId, List<String> names, float[] accs)
        implements CustomPacketPayload {
    public static final Type<RecordsS2CPacket> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Rhythmfall.MOD_ID, "records"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecordsS2CPacket> CODEC =
            StreamCodec.ofMember(RecordsS2CPacket::write, RecordsS2CPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(mapId);
        buf.writeInt(names.size());
        for (String s : names) {
            buf.writeUtf(s);
        }
        for (float v : accs) {
            buf.writeFloat(v);
        }
    }

    public static RecordsS2CPacket read(FriendlyByteBuf buf) {
        String mapId = buf.readUtf();
        int n = buf.readInt();
        List<String> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            names.add(buf.readUtf());
        }
        float[] accs = new float[n];
        for (int i = 0; i < n; i++) {
            accs[i] = buf.readFloat();
        }
        return new RecordsS2CPacket(mapId, names, accs);
    }
}
