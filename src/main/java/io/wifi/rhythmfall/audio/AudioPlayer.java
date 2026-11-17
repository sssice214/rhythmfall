package io.wifi.rhythmfall.audio;

public interface AudioPlayer {
    boolean preload();

    void play();

    void pause();

    void resume();

    void stop();

    void setVolume(float volume);

    /** 设置播放速率 0.5~2.0（必须在 play() 之前调用；音高随速率变化，osu DT 同款） */
    void setPlaybackRate(float rate);
}
