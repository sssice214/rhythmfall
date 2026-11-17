package io.wifi.rhythmfall.screen;

import io.wifi.rhythmfall.beatmap.Beatmap;

import java.util.List;

/** 一首歌：同名同艺术家的多个谱面（难度）集合。 */
public record Song(String name, String artist, List<Beatmap> diffs) {
    /** 封面代表谱面：优先有封面字节的 */
    public Beatmap rep() {
        for (Beatmap b : diffs) {
            if (b.coverData != null && b.coverData.length > 0) {
                return b;
            }
        }
        return diffs.get(0);
    }
}
