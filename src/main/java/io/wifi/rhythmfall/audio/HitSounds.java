package io.wifi.rhythmfall.audio;

import io.wifi.rhythmfall.Rhythmfall;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 合成击打音效：短促「哒」点击与长按持续「咻」风声。
 * 内部单线程混音器写 SourceDataLine；主线程只入队/置开关，零分配零卡顿。
 */
public final class HitSounds {
    private static final int RATE = 44100;
    private static final int CHUNK = 256;

    private static final Object LOCK = new Object();

    private static SourceDataLine line;
    private static volatile boolean running = false;
    private static volatile boolean failed = false;
    private static volatile float volume = 0.8f;

    /** 预渲染的点击声部（支持多个点击并行叠加） */
    private static final List<ClickVoice> CLICKS = new ArrayList<>();
    /** 长按风声声部 */
    private static final List<Voice> VOICES = new ArrayList<>();

    private static final class ClickVoice {
        final short[] data;
        int pos = 0;

        ClickVoice(short[] data) {
            this.data = data;
        }
    }

    /** 长按声部句柄：startWhoosh 返回，stopWhoosh 传入。 */
    public static final class Voice {
        volatile boolean active = true;
        float gain = 0;
        float lp = 0;
        float shimmer = 0;
        final Random rnd = new Random();
    }

    private HitSounds() {
    }

    /** 与配置音量联动（音符音量）。 */
    public static void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
    }

    /** 播放一次「哒」击打音。 */
    public static void click() {
        float v = volume;
        if (v <= 0.01f || failed) {
            return;
        }
        ensureRunning();
        if (!running) {
            return;
        }
        short[] s = renderClick(v);
        synchronized (LOCK) {
            if (CLICKS.size() < 16) {
                CLICKS.add(new ClickVoice(s));
            }
        }
    }

    /** 开始一段持续「咻」声，返回声部句柄（音效关闭时返回 null）。 */
    public static Voice startWhoosh() {
        float v = volume;
        if (v <= 0.01f || failed) {
            return null;
        }
        ensureRunning();
        if (!running) {
            return null;
        }
        Voice voice = new Voice();
        synchronized (LOCK) {
            VOICES.add(voice);
        }
        return voice;
    }

    /** 结束一段长按声（句柄可为 null）。 */
    public static void stopWhoosh(Voice voice) {
        if (voice == null) {
            return;
        }
        synchronized (LOCK) {
            voice.active = false;
        }
    }

    /** 关闭全部声部（暂停时调用，音频线保留等下次使用）。 */
    public static void stopAll() {
        synchronized (LOCK) {
            for (Voice v : VOICES) {
                v.active = false;
            }
        }
    }

    /** 立即整体关闭音频引擎（退出音游时调用），确保完全无声；下次击打会自动重新打开。 */
    public static void shutdownNow() {
        stopAll();
        synchronized (HitSounds.class) {
            running = false;
            SourceDataLine l = line;
            line = null;
            if (l != null) {
                l.stop();
                l.close();
            }
        }
    }

    private static void ensureRunning() {
        if (running || failed) {
            return;
        }
        synchronized (HitSounds.class) {
            if (running || failed) {
                return;
            }
            try {
                AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, RATE, 16, 1, 2, RATE, false);
                line = AudioSystem.getSourceDataLine(fmt);
                line.open(fmt, 8192);
                line.start();
                running = true;
                Thread t = new Thread(HitSounds::mixLoop, "rhythmfall-sfx");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                Rhythmfall.LOGGER.warn("Hit sound engine unavailable: {}", e.toString());
                failed = true;
            }
        }
    }

    private static void mixLoop() {
        short[] buf = new short[CHUNK];
        byte[] bytes = new byte[CHUNK * 2];
        // 线程内持有本地引用，避免与关闭/重启流程竞争
        SourceDataLine out = line;
        int silenceChunks = 0;
        while (running) {
            boolean anySound = false;
            Arrays.fill(buf, (short) 0);
            synchronized (LOCK) {
                // 长按声部
                for (Voice v : VOICES) {
                    float target = v.active ? 1f : 0f;
                    v.gain += (target - v.gain) * (v.active ? 0.004f : 0.0025f);
                    if (v.gain > 0.01f) {
                        anySound = true;
                        v.shimmer += 0.013f;
                        float amp = 0.85f + 0.15f * (float) Math.sin(v.shimmer);
                        for (int i = 0; i < CHUNK; i++) {
                            float x = v.rnd.nextFloat() * 2f - 1f;
                            v.lp += (x - v.lp) * 0.14f;
                            buf[i] += (short) (v.lp * 0.55f * v.gain * amp * volume * 32767f);
                        }
                    } else if (!v.active) {
                        v.gain = 0;
                    }
                }
                for (int i = VOICES.size() - 1; i >= 0; i--) {
                    Voice v = VOICES.get(i);
                    if (!v.active && v.gain <= 0.01f) {
                        VOICES.remove(i);
                    }
                }
                // 点击声部（可多个并行叠加，混完后移除）
                for (int i = CLICKS.size() - 1; i >= 0; i--) {
                    ClickVoice c = CLICKS.get(i);
                    anySound = true;
                    int n = Math.min(CHUNK, c.data.length - c.pos);
                    for (int j = 0; j < n; j++) {
                        buf[j] += c.data[c.pos + j];
                    }
                    c.pos += n;
                    if (c.pos >= c.data.length) {
                        CLICKS.remove(i);
                    }
                }
            }
            // 持续静音约 2.5 秒后关闭音频线并退出混音线程，杜绝后台底噪；
            // 下次击打会经 ensureRunning 重新打开
            if (!anySound) {
                if (++silenceChunks > 430) {
                    running = false;
                    synchronized (HitSounds.class) {
                        if (out != null) {
                            out.stop();
                            out.close();
                        }
                        if (line == out) {
                            line = null;
                        }
                    }
                    return;
                }
            } else {
                silenceChunks = 0;
            }
            // 转 little-endian 字节并写出（write 阻塞即自然节拍）
            for (int i = 0; i < CHUNK; i++) {
                int s = buf[i];
                s = Math.max(-32768, Math.min(32767, s));
                bytes[i * 2] = (byte) (s & 0xFF);
                bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            try {
                out.write(bytes, 0, bytes.length);
            } catch (Exception ignored) {
                // 音频线被关闭（如退出音游）时写失败属正常，混音线程随后退出
            }
        }
    }

    /** 50ms 短点击：高频正弦快衰减 + 起振噪声 + 低频闷响。 */
    private static short[] renderClick(float vol) {
        int n = 2205;
        short[] out = new short[n];
        Random rnd = new Random();
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float env = (float) Math.exp(-t * 70);
            float tone = (float) Math.sin(2 * Math.PI * 1900 * t) * 0.5f
                    + (float) Math.sin(2 * Math.PI * 320 * t) * 0.35f;
            float burst = 0;
            if (i < 120) {
                burst = (rnd.nextFloat() * 2f - 1f) * 0.6f * (1f - i / 120f);
            }
            float s = (tone + burst) * env * vol;
            s = Math.max(-1f, Math.min(1f, s));
            out[i] = (short) (s * 32767f);
        }
        return out;
    }
}
