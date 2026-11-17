package io.wifi.rhythmfall.beatmap;

import java.util.ArrayList;
import java.util.List;

public class Beatmap {
    public String name = "Untitled";
    public String artist = "";
    /** 难度（如 Easy/Normal/Hard/Insane/Expert），用于曲目选择分类 */
    public String difficulty = "";
    /** 键数：4/5/6/7。osu 直读时取自 CircleSize */
    public int keys = 4;
    /** 音频文件名，相对谱面所在目录 */
    public String audio = "";
    /** 封面图片文件名（PNG/JPG），相对谱面所在目录，可为空 */
    public String cover = "";
    /** 全局偏移（毫秒），正数延后所有音符 */
    public long offset = 0;
    /** 下落速度（像素/秒），游戏内可用倍率继续调节 */
    public float scrollSpeed = 300f;
    public List<Note> notes = new ArrayList<>();

    /** 谱面 ID（jar:资源路径 或 local:相对路径），排行榜与封面纹理缓存的 key */
    public transient String id;
    /** 扫描时读入的封面字节（选曲/游玩/结算共用，不参与序列化） */
    public transient byte[] coverData;
    /** 扫描时读入的音频字节（本地文件夹来源；jar 来源音频按需读取，不参与序列化） */
    public transient byte[] audioData;
    /** jar 内置谱面的音频资源路径（audioData 为空时由 ChartSource 按需读取） */
    public transient String bundledAudioPath;

    /** 曲目分组键：同名同艺术家的多个谱面归为同一首歌的不同难度 */
    public String songKey() {
        return (name == null ? "" : name.toLowerCase()) + "\n" + (artist == null ? "" : artist.toLowerCase());
    }

    public long lastNoteTime() {
        long max = 0;
        for (Note n : notes) {
            max = Math.max(max, n.time);
        }
        return max;
    }
}
