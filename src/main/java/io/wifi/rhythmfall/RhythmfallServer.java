package io.wifi.rhythmfall;

import io.wifi.rhythmfall.content.RhythmfallContent;
import io.wifi.rhythmfall.network.DevOpenS2CPacket;
import io.wifi.rhythmfall.network.OpenGuiS2CPacket;
import io.wifi.rhythmfall.network.RecordsS2CPacket;
import io.wifi.rhythmfall.network.RequestRecordsC2SPacket;
import io.wifi.rhythmfall.network.SubmitRecordC2SPacket;
import io.wifi.rhythmfall.server.RecordStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/**
 * 服务端入口。
 * 谱面/音频/封面全部由客户端本地提供（jar 内置或本地 charts 文件夹），
 * 服务端承担排行榜与 /rhythmfall 指令：读写极小的 JSON 记录，占用可忽略。
 */
public class RhythmfallServer implements ModInitializer {
    @Override
    public void onInitialize() {
        RhythmfallContent.register();
        RecordStore.loadAsync();

        PayloadTypeRegistry.playC2S().register(RequestRecordsC2SPacket.ID, RequestRecordsC2SPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(SubmitRecordC2SPacket.ID, SubmitRecordC2SPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(RecordsS2CPacket.ID, RecordsS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenGuiS2CPacket.ID, OpenGuiS2CPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(DevOpenS2CPacket.ID, DevOpenS2CPacket.CODEC);

        // /rhythmfall          给自己打开音游 GUI（人人可用，等效右键）
        // /rhythmfall <玩家>   给指定玩家打开（需 OP 权限等级 2，支持 @a 等目标选择器）
        // /rhythmfall dev      打开开发者面板（OP 专属，见 DevScreen）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("rhythmfall")
                        .executes(ctx -> {
                            sendOpenGui(ctx.getSource().getPlayerOrException());
                            return 1;
                        })
                        .then(Commands.literal("dev")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> {
                                    sendDevGui(ctx.getSource().getPlayerOrException());
                                    return 1;
                                })
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(ctx -> {
                                            var targets = EntityArgument.getPlayers(ctx, "players");
                                            for (ServerPlayer p : targets) {
                                                sendDevGui(p);
                                            }
                                            return targets.size();
                                        })))
                        .then(Commands.argument("players", EntityArgument.players())
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> {
                                    var targets = EntityArgument.getPlayers(ctx, "players");
                                    for (ServerPlayer p : targets) {
                                        sendOpenGui(p);
                                    }
                                    return targets.size();
                                }))));

        ServerPlayNetworking.registerGlobalReceiver(RequestRecordsC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            String mapId = payload.mapId();
            Rhythmfall.IO.execute(() -> sendRecords(player, mapId));
        });

        ServerPlayNetworking.registerGlobalReceiver(SubmitRecordC2SPacket.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            String mapId = payload.mapId();
            String uuid = player.getUUID().toString();
            String name = player.getGameProfile().getName();
            Rhythmfall.IO.execute(() -> {
                boolean changed = RecordStore.submit(mapId, uuid, name,
                        payload.acc(), payload.score(), payload.maxCombo());
                if (changed) {
                    RecordStore.saveAsync();
                }
                sendRecords(player, mapId);
            });
        });
    }

    private static void sendOpenGui(ServerPlayer player) {
        ServerPlayNetworking.send(player, new OpenGuiS2CPacket());
    }

    private static void sendDevGui(ServerPlayer player) {
        ServerPlayNetworking.send(player, new DevOpenS2CPacket());
    }

    private static void sendRecords(ServerPlayer player, String mapId) {
        var top = RecordStore.top(mapId, RecordStore.MAX_RECORDS);
        int n = top.size();
        String[] names = new String[n];
        float[] accs = new float[n];
        for (int i = 0; i < n; i++) {
            RecordStore.Rec r = top.get(i);
            names[i] = r.name;
            accs[i] = r.acc;
        }
        ServerPlayNetworking.send(player, new RecordsS2CPacket(mapId, Arrays.asList(names), accs));
    }
}
