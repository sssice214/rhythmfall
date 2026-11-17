package io.wifi.rhythmfall.client;

import io.wifi.rhythmfall.network.RecordsS2CPacket;
import io.wifi.rhythmfall.network.RequestRecordsC2SPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayList;
import java.util.List;

/** 客户端当前谱面的服务端排行榜快照。 */
public final class RecordsClient {
    public record Entry(String name, float acc) {
    }

    private static volatile String mapId = "";
    private static volatile List<Entry> records = List.of();

    private RecordsClient() {
    }

    public static void request(String id) {
        mapId = id == null ? "" : id;
        records = List.of();
        if (id != null) {
            ClientPlayNetworking.send(new RequestRecordsC2SPacket(id));
        }
    }

    public static void onRecords(RecordsS2CPacket pkt) {
        if (pkt.mapId() == null || !pkt.mapId().equals(mapId)) {
            return;
        }
        List<Entry> list = new ArrayList<>();
        int n = pkt.names().size();
        for (int i = 0; i < n; i++) {
            list.add(new Entry(pkt.names().get(i), pkt.accs()[i]));
        }
        records = list;
    }

    public static List<Entry> records() {
        return records;
    }
}
