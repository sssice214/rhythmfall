package io.wifi.rhythmfall.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.wifi.rhythmfall.Rhythmfall;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端成绩榜：每张谱面记录每位玩家的最高 acc（完成度），
 * 按 acc 降序排名，总榜上限 50 条。数据落盘 config/rhythmfall/records.json。
 * 所有读写都应通过 {@link io.wifi.rhythmfall.Rhythmfall#IO} 或 synchronized 方法进行。
 */
public final class RecordStore {
    public static final int MAX_RECORDS = 50;

    public static class Rec {
        public String uuid = "";
        public String name = "";
        public float acc = 0;
        public int score = 0;
        public int combo = 0;
        public long t = 0;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, List<Rec>>>() {
    }.getType();

    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("rhythmfall").resolve("records.json");
    private static final Map<String, List<Rec>> DATA = new HashMap<>();
    private static volatile boolean loaded = false;

    private RecordStore() {
    }

    /** 启动时在 IO 线程异步加载。 */
    public static void loadAsync() {
        Rhythmfall.IO.execute(() -> {
            synchronized (DATA) {
                if (loaded) {
                    return;
                }
                loaded = true;
                if (!Files.isRegularFile(FILE)) {
                    return;
                }
                try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    Map<String, List<Rec>> m = GSON.fromJson(r, TYPE);
                    if (m != null) {
                        // putIfAbsent：不覆盖加载期间新提交的谱面成绩
                        for (Map.Entry<String, List<Rec>> e : m.entrySet()) {
                            DATA.putIfAbsent(e.getKey(), e.getValue());
                        }
                    }
                    Rhythmfall.LOGGER.info("Loaded {} record groups from {}", DATA.size(), FILE.getFileName());
                } catch (Exception e) {
                    Rhythmfall.LOGGER.error("Failed to read records", e);
                }
            }
        });
    }

    /** @return 指定谱面的榜单副本（acc 降序） */
    public static List<Rec> top(String mapId, int limit) {
        synchronized (DATA) {
            List<Rec> list = DATA.get(mapId);
            if (list == null) {
                return List.of();
            }
            List<Rec> copy = new ArrayList<>(list);
            if (copy.size() > limit) {
                copy = copy.subList(0, limit);
            }
            return copy;
        }
    }

    /**
     * 提交成绩：同一玩家只保留最高 acc，然后按 acc 排序并裁剪到 MAX_RECORDS。
     * @return 是否产生了变化（需要落盘/回发）
     */
    public static boolean submit(String mapId, String uuid, String name, float acc, int score, int combo) {
        synchronized (DATA) {
            List<Rec> list = DATA.computeIfAbsent(mapId, k -> new ArrayList<>());
            Rec best = null;
            for (Rec r : list) {
                if (r.uuid != null && r.uuid.equals(uuid)) {
                    best = r;
                    break;
                }
            }
            if (best == null) {
                Rec r = new Rec();
                r.uuid = uuid;
                r.name = name;
                r.acc = acc;
                r.score = score;
                r.combo = combo;
                r.t = System.currentTimeMillis();
                list.add(r);
            } else if (acc > best.acc) {
                best.name = name;
                best.acc = acc;
                best.score = score;
                best.combo = combo;
                best.t = System.currentTimeMillis();
            } else {
                return false;
            }
            list.sort(Comparator.comparingDouble((Rec r) -> -r.acc));
            if (list.size() > MAX_RECORDS) {
                list.subList(MAX_RECORDS, list.size()).clear();
            }
            return true;
        }
    }

    /** 在 IO 线程异步落盘。 */
    public static void saveAsync() {
        Rhythmfall.IO.execute(() -> {
            Map<String, List<Rec>> snapshot;
            synchronized (DATA) {
                snapshot = new HashMap<>(DATA);
            }
            try {
                Files.createDirectories(FILE.getParent());
                try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, TYPE, w);
                }
            } catch (IOException e) {
                Rhythmfall.LOGGER.error("Failed to write records", e);
            }
        });
    }
}
