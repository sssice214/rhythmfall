package io.wifi.rhythmfall.audio;

import net.minecraft.client.sounds.JOrbisAudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从内存字节播放 OGG。
 * 解码复用 Minecraft 客户端自带的 JOrbis（Vorbis 解码），输出走 javax.sound。
 */
public class OggPlayer implements AudioPlayer {
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile boolean stopped = false;

    private volatile SourceDataLine line;
    private volatile float volume = 1f;
    private volatile float playbackRate = 1f;

    private final byte[] rawData;
    private Thread thread;

    public OggPlayer(byte[] data) {
        this.rawData = data;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0f, volume);
    }

    @Override
    public void setPlaybackRate(float rate) {
        // 输出线采样率 = 原始采样率 × 速率，系统混音器自动重采样：变速同时变调（osu DT 同款）
        this.playbackRate = Math.max(0.5f, Math.min(2f, rate));
    }

    @Override
    public boolean preload() {
        return rawData != null;
    }

    public void play() {
        if (playing.get() || stopped) {
            return;
        }
        playing.set(true);
        paused.set(false);
        stopped = false;
        stopping.set(false);
        thread = new Thread(this::playbackLoop, "rhythmfall-ogg");
        thread.setDaemon(true);
        thread.start();
    }

    public void pause() {
        if (!playing.get() || paused.get()) {
            return;
        }
        paused.set(true);
        if (line != null && line.isOpen()) {
            try {
                line.stop();
            } catch (Exception ignored) {
            }
        }
    }

    public void resume() {
        if (!playing.get() || !paused.get()) {
            return;
        }
        paused.set(false);
        if (line != null && line.isOpen()) {
            try {
                line.start();
            } catch (Exception ignored) {
            }
        }
    }

    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        stopped = true;
        playing.set(false);
        paused.set(false);
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Exception ignored) {
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void playbackLoop() {
        try {
            try (InputStream mem = new ByteArrayInputStream(rawData)) {
                playStream(mem);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (!stopping.get()) {
                if (line != null) {
                    line.drain();
                    line.close();
                }
                playing.set(false);
                stopped = true;
            }
        }
    }

    private void playStream(InputStream in) throws Exception {
        try (JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            AudioFormat src = ogg.getFormat();
            float rate = src.getSampleRate() * playbackRate;

            AudioFormat pcm = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    rate,
                    16,
                    src.getChannels(),
                    src.getChannels() * 2,
                    rate,
                    false);

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcm);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(pcm, 16384);
            line.start();

            byte[] buf = new byte[8192];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

            while (!stopped) {
                while (paused.get() && !stopped) {
                    Thread.sleep(10);
                }
                if (stopped) {
                    break;
                }

                boolean more = ogg.readChunk(sample -> {
                    float vol = volume;
                    int scaled = (int) (sample * 32767f * vol);
                    short s = (short) Math.max(-32768, Math.min(32767, scaled));
                    if (bb.remaining() < 2) {
                        bb.flip();
                        line.write(buf, 0, bb.limit());
                        bb.clear();
                    }
                    bb.putShort(s);
                });

                if (bb.position() > 0) {
                    bb.flip();
                    line.write(buf, 0, bb.limit());
                    bb.clear();
                }
                if (!more) {
                    break;
                }
            }
        }
    }
}
