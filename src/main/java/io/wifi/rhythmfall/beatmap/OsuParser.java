package io.wifi.rhythmfall.beatmap;

import java.util.List;

/**
 * 直接解析 osu!mania (.osu) 谱面文本为 Beatmap。
 * 键数取自 [Difficulty] CircleSize（4K/5K/6K/7K，超出范围收敛）；
 * 仅接受 Mode: 3（mania）的谱面，其余返回 null 跳过。
 */
public final class OsuParser {
    private OsuParser() {
    }

    /** 从 .osu 文本内容解析（ChartSource 扫描 jar 内置/本地文件夹谱面的统一入口）。 */
    public static Beatmap parseFromText(String content) {
        return parseLines(java.util.Arrays.asList(content.split("\n", -1)));
    }

    private static Beatmap parseLines(List<String> lines) {
        Beatmap b = new Beatmap();
        b.difficulty = "Normal";
        int mode = 3;
        int keys = 4;
        String section = "";
        boolean coverSet = false;
        boolean hitObjects = false;
        for (String raw : lines) {
            // osu 文件常带 UTF-8 BOM
            String line = raw.trim();
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1).trim();
            }
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                hitObjects = line.equals("[HitObjects]");
                section = line;
                continue;
            }
            switch (section) {
                case "[General]" -> {
                    if (line.startsWith("AudioFilename:")) {
                        b.audio = sanitize(line.substring("AudioFilename:".length()).trim());
                    } else if (line.startsWith("Mode:")) {
                        mode = parseInt(line.substring("Mode:".length()).trim(), 3);
                    }
                }
                case "[Metadata]" -> {
                    if (line.startsWith("Title:")) {
                        b.name = line.substring("Title:".length()).trim();
                    } else if (line.startsWith("Artist:")) {
                        b.artist = line.substring("Artist:".length()).trim();
                    } else if (line.startsWith("Version:")) {
                        b.difficulty = line.substring("Version:".length()).trim();
                    }
                }
                case "[Events]" -> {
                    // 背景图 0,0,"bg.jpg" 用作封面（PNG/JPG 均可）
                    if (!coverSet && line.startsWith("0,0")) {
                        int a = line.indexOf('"');
                        int e = a >= 0 ? line.indexOf('"', a + 1) : -1;
                        if (a >= 0 && e > a) {
                            b.cover = sanitize(line.substring(a + 1, e));
                            coverSet = true;
                        }
                    }
                }
                case "[Difficulty]" -> {
                    if (line.startsWith("CircleSize:")) {
                        keys = Math.round(parseFloat(line.substring("CircleSize:".length()).trim(), 4));
                    }
                }
                case "[HitObjects]" -> {
                    String[] p = line.split(",");
                    if (p.length < 5) {
                        continue;
                    }
                    int x = parseInt(p[0], 0);
                    long time = parseLong(p[2], 0);
                    int type = parseInt(p[3], 0);
                    boolean hold = (type & 128) != 0;
                    boolean tap = (type & 1) != 0;
                    if (!hold && !tap) {
                        continue; // 滑条/转盘不适用于 mania
                    }
                    // 键数收敛到 4~7 后再算轨道：CS>7 的谱面右侧列折到第 7 列，绝不能越界
                    int keysNow = Math.max(4, Math.min(7, keys));
                    Note n = new Note();
                    n.lane = Math.max(0, Math.min(keysNow - 1, x * keys / 512));
                    n.time = time;
                    if (hold) {
                        n.type = "hold";
                        if (p.length >= 6) {
                            int colon = p[5].indexOf(':');
                            n.endTime = parseLong(colon >= 0 ? p[5].substring(0, colon) : p[5], time);
                        } else {
                            n.endTime = time;
                        }
                        if (n.endTime < n.time) {
                            n.endTime = n.time;
                        }
                    }
                    b.notes.add(n);
                }
                default -> {
                }
            }
        }
        if (mode != 3) {
            return null; // 只支持 mania
        }
        if (hitObjects && b.notes.isEmpty()) {
            return null; // 没有任何可用音符
        }
        b.keys = Math.max(4, Math.min(7, keys));
        if (b.name == null || b.name.isBlank()) {
            b.name = "Untitled";
        }
        return b;
    }

    static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('\\', '/');
        while (s.startsWith("../")) {
            s = s.substring(3);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    static int parseInt(String s, int def) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    static long parseLong(String s, long def) {
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
