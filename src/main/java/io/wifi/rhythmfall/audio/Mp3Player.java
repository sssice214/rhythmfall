package io.wifi.rhythmfall.audio;

/**
 * MP3 解码播放（javazoom JLayer，纯 Java），接口与 OggPlayer 一致。
 */
public class Mp3Player implements AudioPlayer {
    private final byte[] data;

    private final java.util.concurrent.atomic.AtomicBoolean playing = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean stopped = false;

    private volatile javax.sound.sampled.SourceDataLine line;
    private volatile float volume = 1f;
    private volatile float playbackRate = 1f;

    private Thread thread;

    public Mp3Player(byte[] data) {
        this.data = data;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0f, volume);
    }

    @Override
    public void setPlaybackRate(float rate) {
        // 输出线采样率 = 解码采样率 × 速率：变速同时变调（osu DT 同款）
        this.playbackRate = Math.max(0.5f, Math.min(2f, rate));
    }

    @Override
    public boolean preload() {
        return data != null;
    }

    @Override
    public void play() {
        if (playing.get() || stopped) {
            return;
        }
        playing.set(true);
        paused.set(false);
        stopped = false;
        stopping.set(false);
        thread = new Thread(this::playbackLoop, "rhythmfall-mp3");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
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

    @Override
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

    @Override
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
        javazoom.jl.decoder.Bitstream bitstream = null;
        try {
            bitstream = new javazoom.jl.decoder.Bitstream(new java.io.ByteArrayInputStream(data));
            javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();
            javax.sound.sampled.AudioFormat fmt = null;

            javazoom.jl.decoder.Header header;
            while (!stopped && (header = bitstream.readFrame()) != null) {
                javazoom.jl.decoder.SampleBuffer out =
                        (javazoom.jl.decoder.SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] samples = out.getBuffer();
                int len = out.getBufferLength();
                int channels = out.getChannelCount();
                int rate = out.getSampleFrequency();

                if (fmt == null) {
                    fmt = new javax.sound.sampled.AudioFormat(
                            javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                            rate * playbackRate, 16, channels, channels * 2, rate * playbackRate, false);
                    javax.sound.sampled.DataLine.Info info =
                            new javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine.class, fmt);
                    line = (javax.sound.sampled.SourceDataLine) javax.sound.sampled.AudioSystem.getLine(info);
                    line.open(fmt, 16384);
                    line.start();
                }

                byte[] pcm = toLittleEndian(samples, len, volume);
                while (paused.get() && !stopped) {
                    Thread.sleep(10);
                }
                if (stopped) {
                    break;
                }
                line.write(pcm, 0, pcm.length);
                bitstream.closeFrame();
            }

            if (fmt != null && !stopping.get()) {
                line.drain();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (bitstream != null) {
                try {
                    bitstream.close();
                } catch (Exception ignored) {
                }
            }
            if (!stopping.get()) {
                if (line != null) {
                    line.close();
                }
                playing.set(false);
                stopped = true;
            }
        }
    }

    private static byte[] toLittleEndian(short[] samples, int len, float vol) {
        byte[] out = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            int s = (int) (samples[i] * vol);
            s = Math.max(-32768, Math.min(32767, s));
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }
}
