package io.wifi.rhythmfall.beatmap;

public class Note {
    /** 轨道 0..keys-1，从左到右 */
    public int lane = 0;
    /** 音符时刻（毫秒，相对音乐开始） */
    public long time = 0;
    /** "tap" 单击 / "hold" 长按 */
    public String type = "tap";
    /** 长按结束时刻（毫秒），仅 hold 用于表现（尾部无判定） */
    public long endTime = 0;

    public boolean isHold() {
        return "hold".equalsIgnoreCase(type);
    }
}
