package io.wifi.rhythmfall.audio;

/** 根据音频字节自动识别格式（OGG 以 "OggS" 开头，其余按 MP3 处理）。 */
public final class AudioPlayers {
    private AudioPlayers() {
    }

    public static AudioPlayer create(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        if (data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
            OggPlayer p = new OggPlayer(data);
            return p.preload() ? p : null;
        }
        Mp3Player p = new Mp3Player(data);
        return p.preload() ? p : null;
    }
}
