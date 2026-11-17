package io.wifi.rhythmfall.client;

import com.google.gson.Gson;
import io.wifi.rhythmfall.Rhythmfall;
import io.wifi.rhythmfall.beatmap.Beatmap;
import io.wifi.rhythmfall.beatmap.OsuParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 谱面来源（全部客户端本地，零服务器占用）：
 * <pre>
 *   1. jar 内置  assets/rhythmfall/charts/   （随模组分发，改谱需重打包；资源路径仅限
 *      小写字母/数字/._-/，音频按需读取）
 *   2. 本地文件夹 config/rhythmfall/charts/   （丢文件夹/.osz/.osu 均可，无需重新构建；
 *      新 .osz 刷新时自动解包；音频在扫描时读入 audioData）
 * </pre>
 * 每个谱面 = 一个文件夹（.osu/.json + 音频 + 封面），显示名取自谱面 Title。
 * 扫描、.osz 解包、封面解码在 IO 线程异步执行；音频在进入谱面时按需读取（LRU 缓存）。
 */
public final class ChartSource {
    private static final Gson GSON = new Gson();
    private static final String CHARTS_DIR = "charts";
    private static final Path LOCAL_DIR = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("rhythmfall").resolve("charts");
    private static volatile List<Beatmap> charts = List.of();
    private static volatile int generation;
    /** jar 谱面音频的 LRU 缓存（上限 4 首，避免内存随游玩曲数无限增长） */
    private static final Map<String, byte[]> AUDIO_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 4;
        }
    };

    private ChartSource() {
    }

    public static List<Beatmap> charts() {
        return charts;
    }

    public static int generation() {
        return generation;
    }

    /** 重新扫描全部来源（IO 线程异步执行）。选曲界面初始化/刷新按钮时调用。 */
    public static void scan(Minecraft mc) {
        // 主线程先取好资源管理器引用：IO 线程里触碰 mc 有与资源重载竞争的风险
        ResourceManager rm = mc.getResourceManager();
        Rhythmfall.IO.execute(() -> scanNow(rm));
    }

    private static void scanNow(ResourceManager rm) {
        List<Beatmap> out = new ArrayList<>();
        out.addAll(scanJar(rm));
        out.addAll(scanLocal());
        out.sort(Comparator.comparing(b -> b.name.toLowerCase()));
        // 封面优先：字节已就位，触发异步解码与纹理注册（选曲/游玩/结算共用）
        for (Beatmap b : out) {
            if (b.coverData != null && b.coverData.length > 0) {
                CoverTextures.get(b);
            }
        }
        charts = List.copyOf(out);
        generation++;
        Rhythmfall.LOGGER.info("[Charts] scanned {} charts", out.size());
    }

    // ==================== jar 内置谱面 ====================

    private static List<Beatmap> scanJar(ResourceManager rm) {
        List<Beatmap> out = new ArrayList<>();
        Map<ResourceLocation, Resource> all;
        try {
            all = rm.listResources(CHARTS_DIR, rl -> true);
        } catch (Exception e) {
            Rhythmfall.LOGGER.warn("[Charts] failed to list jar charts", e);
            return out;
        }
        List<String> chartPaths = new ArrayList<>();
        for (ResourceLocation id : all.keySet()) {
            String p = id.getPath();
            if (p.endsWith(".osu") || p.endsWith(".json")) {
                chartPaths.add(p);
            }
        }
        chartPaths.sort(Comparator.naturalOrder());
        for (String path : chartPaths) {
            try {
                String text = new String(readResource(rm, path), StandardCharsets.UTF_8);
                Beatmap b = text.contains("[HitObjects]")
                        ? OsuParser.parseFromText(text)
                        : GSON.fromJson(text, Beatmap.class);
                if (b == null) {
                    continue;
                }
                sortNotes(b);
                b.id = "jar:" + path;
                String folder = folderOf(path);
                b.bundledAudioPath = joinRes(folder, sanitizeRes(b.audio));
                String coverPath = b.cover == null || b.cover.isBlank()
                        ? null : joinRes(folder, sanitizeRes(b.cover));
                // 封面字节优先就位（jar 内读取很快）
                b.coverData = readResource(rm, coverPath);
                out.add(b);
            } catch (Exception e) {
                Rhythmfall.LOGGER.warn("[Charts] failed to load {}", path, e);
            }
        }
        return out;
    }

    private static String folderOf(String chartPath) {
        int slash = chartPath.lastIndexOf('/');
        return slash >= 0 ? chartPath.substring(0, slash) : CHARTS_DIR;
    }

    /** 音符按时间升序：漏判扫描/二分查找/结束判定都依赖有序（osu 与 JSON 都不保证）。 */
    private static void sortNotes(Beatmap b) {
        b.notes.sort(java.util.Comparator.comparingLong(n -> n.time));
    }

    private static String joinRes(String folder, String file) {
        return folder + "/" + sanitizeRes(file);
    }

    private static String sanitizeRes(String s) {
        String t = s == null ? "" : s.toLowerCase().replace('\\', '/');
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        return t.replaceAll("[^a-z0-9._\\-/]", "_");
    }

    private static byte[] readResource(ResourceManager rm, String path) {
        if (path == null) {
            return null;
        }
        try (InputStream is = rm.getResourceOrThrow(
                ResourceLocation.fromNamespaceAndPath(Rhythmfall.MOD_ID, path)).open()) {
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 本地文件夹谱面 ====================

    private static List<Beatmap> scanLocal() {
        List<Beatmap> out = new ArrayList<>();
        if (!Files.isDirectory(LOCAL_DIR)) {
            try {
                Files.createDirectories(LOCAL_DIR);
            } catch (IOException ignored) {
                return out;
            }
        }
        unpackNewOsz();
        List<Path> files;
        try (Stream<Path> s = Files.walk(LOCAL_DIR)) {
            files = s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".osu") || n.endsWith(".json");
            }).sorted().toList();
        } catch (IOException e) {
            return out;
        }
        for (Path p : files) {
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                Beatmap b = text.contains("[HitObjects]")
                        ? OsuParser.parseFromText(text)
                        : GSON.fromJson(text, Beatmap.class);
                if (b == null) {
                    continue;
                }
                sortNotes(b);
                Path base = p.getParent();
                b.id = "local:" + LOCAL_DIR.relativize(p).toString().replace('\\', '/');
                b.audioData = b.audio == null || b.audio.isBlank()
                        ? null : Files.readAllBytes(base.resolve(b.audio));
                b.coverData = b.cover == null || b.cover.isBlank()
                        ? null : Files.readAllBytes(base.resolve(b.cover));
                out.add(b);
            } catch (Exception e) {
                Rhythmfall.LOGGER.warn("[Charts] failed to load {}", p, e);
            }
        }
        return out;
    }

    /**
     * 把 charts 目录下新出现的 .osz（zip 包）解包为同名文件夹。
     * 目标文件夹已存在则视为解包过，跳过——改谱请直接改文件夹内容或删包重放。
     */
    private static void unpackNewOsz() {
        List<Path> osz;
        try (Stream<Path> s = Files.list(LOCAL_DIR)) {
            osz = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".osz"))
                    .toList();
        } catch (IOException e) {
            return;
        }
        for (Path pack : osz) {
            String name = pack.getFileName().toString();
            Path dest = LOCAL_DIR.resolve(name.substring(0, name.length() - 4));
            if (Files.isDirectory(dest)) {
                continue; // 已解包过
            }
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pack.toFile())) {
                Files.createDirectories(dest);
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    Path target = dest.resolve(entry.getName()).normalize();
                    if (!target.startsWith(dest)) {
                        continue; // 防 zip slip（恶意绝对路径/..）
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Rhythmfall.LOGGER.info("[Charts] unpacked {} -> {}", name, dest.getFileName());
            } catch (Exception e) {
                Rhythmfall.LOGGER.warn("[Charts] failed to unpack {}", name, e);
            }
        }
    }

    // ==================== 音频按需读取 ====================

    /**
     * 读取谱面音频字节：内存缓存 → jar 资源 → 本地文件。
     * 进谱面时调用（一次性读取，之后走 AudioPlayers 播放）。
     */
    public static byte[] audioBytes(Beatmap b) {
        if (b.audioData != null) {
            return b.audioData;
        }
        byte[] cached = AUDIO_CACHE.get(b.id);
        if (cached != null) {
            return cached;
        }
        if (b.bundledAudioPath != null) {
            try (InputStream is = Minecraft.getInstance().getResourceManager()
                    .getResourceOrThrow(ResourceLocation
                            .fromNamespaceAndPath(Rhythmfall.MOD_ID, b.bundledAudioPath)).open()) {
                byte[] data = is.readAllBytes();
                AUDIO_CACHE.put(b.id, data);
                return data;
            } catch (Exception e) {
                Rhythmfall.LOGGER.warn("[Charts] audio read failed for {}", b.id, e);
                return null;
            }
        }
        return null;
    }
}
