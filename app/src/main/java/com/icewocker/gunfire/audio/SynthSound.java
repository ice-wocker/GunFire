package com.icewocker.gunfire.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * 程序合成的音效。
 *
 * 这个项目有一条硬线：不往仓库里放素材文件。所以音效也不能是 wav——
 * 直接用 PCM 在内存里算出一条波形，交给 AudioTrack 播。
 * 每条音效就是一个短促的包络乘以噪声/正弦，和开源音效库的做法一致，
 * 区别只是别人离线烘好，我们每次启动时算一遍（几毫秒的事）。
 *
 * 播放是非阻塞的：每次开一条短命线程写完就走。
 * 同时最多 {@link #MAX_STREAMS} 条，多了直接丢弃——枪战里音效叠太多只会变糊。
 */
public final class SynthSound {

    private static final int SAMPLE_RATE = 44100;
    private static final int MAX_STREAMS = 8;

    /** 音效种类。 */
    public enum Id {
        RIFLE, SHOTGUN, SNIPER, HIT, KILL, HEADSHOT, RELOAD, HURT, PICKUP
    }

    private final short[][] cache = new short[Id.values().length][];
    private final Thread[] streams = new Thread[MAX_STREAMS];
    private volatile boolean enabled = true;

    public SynthSound() {
        build(Id.RIFLE, rifle());
        build(Id.SHOTGUN, shotgun());
        build(Id.SNIPER, sniper());
        build(Id.HIT, hit());
        build(Id.KILL, kill());
        build(Id.HEADSHOT, headshot());
        build(Id.RELOAD, reload());
        build(Id.HURT, hurt());
        build(Id.PICKUP, pickup());
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 设备是否具备播放条件。缺音频设备时上层可以静默降级。 */
    public boolean available() {
        return AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT) > 0;
    }

    /** 播放一个音效。非阻塞。 */
    public void play(Id id) {
        if (!enabled) {
            return;
        }
        short[] data = cache[id.ordinal()];
        if (data == null) {
            return;
        }
        int slot = -1;
        for (int i = 0; i < MAX_STREAMS; i++) {
            if (streams[i] == null || !streams[i].isAlive()) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        Thread t = new Thread(() -> write(data), "gunfire-sfx");
        t.setDaemon(true);
        streams[slot] = t;
        t.start();
    }

    private void write(short[] data) {
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(data.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(data, 0, data.length);
            track.play();
            // MODE_STATIC 播完不会自己收尾，等一个片段长度再释放
            Thread.sleep(data.length * 1000L / SAMPLE_RATE + 40L);
        } catch (IllegalStateException | IllegalArgumentException | InterruptedException ignored) {
            // 音频设备不可用就静音继续——不能因为没声音把游戏卡住
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                    // 已经停了就算了
                }
                track.release();
            }
        }
    }

    private void build(Id id, short[] samples) {
        cache[id.ordinal()] = samples;
    }

    // ---- 波形 ----

    /** 步枪：低频冲击 + 白噪声爆音，衰减很快。 */
    private static short[] rifle() {
        int n = SAMPLE_RATE / 10;
        short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = expEnv(t, 0.004f, 26f);
            float body = (float) Math.sin(2 * Math.PI * 130 * t) * 0.55f;
            float crack = (rnd.nextFloat() * 2f - 1f) * 0.7f;
            out[i] = clip((body + crack) * env * 0.85f);
        }
        return out;
    }

    /** 霰弹枪：更低更闷，尾巴长一点。 */
    private static short[] shotgun() {
        int n = SAMPLE_RATE / 5;
        short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = (float) Math.exp(-t * 14f);
            float noise = rnd.nextFloat() * 2f - 1f;
            float low = (float) Math.sin(2 * Math.PI * 70 * t) * 0.8f;
            out[i] = clip((noise * 0.55f + low) * env);
        }
        return out;
    }

    /** 狙击枪：一声闷响，衰减慢，留出回声感。 */
    private static short[] sniper() {
        int n = SAMPLE_RATE / 4;
        short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(23);
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = (float) Math.exp(-t * 7f);
            float tone = (float) Math.sin(2 * Math.PI * 90 * t) * 0.7f;
            float noise = (rnd.nextFloat() * 2f - 1f) * 0.5f;
            out[i] = clip((tone + noise) * env);
        }
        return out;
    }

    /** 命中：一声短促的高频"嗒"。 */
    private static short[] hit() {
        int n = SAMPLE_RATE / 25;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = expEnv(t, 0.001f, 90f);
            out[i] = clip((float) Math.sin(2 * Math.PI * 1400 * t) * env * 0.55f);
        }
        return out;
    }

    /** 击杀：上扫音，表示「成了」。 */
    private static short[] kill() {
        int n = SAMPLE_RATE / 8;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 700f + t * 4200f;
            float env = expEnv(t, 0.003f, 22f);
            out[i] = clip((float) Math.sin(2 * Math.PI * freq * t) * env * 0.6f);
        }
        return out;
    }

    /** 爆头：两个叠在一起的正弦，比击杀更亮。 */
    private static short[] headshot() {
        int n = SAMPLE_RATE / 6;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = expEnv(t, 0.002f, 16f);
            float a = (float) Math.sin(2 * Math.PI * 1800 * t);
            float b = (float) Math.sin(2 * Math.PI * 2700 * t) * 0.6f;
            out[i] = clip((a + b) * env * 0.5f);
        }
        return out;
    }

    /** 换弹：两下机械噪声，中间留一个空档。 */
    private static short[] reload() {
        int n = SAMPLE_RATE / 4;
        short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(31);
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float a = (float) Math.exp(-t * 60f);
            float b = t > 0.12f ? (float) Math.exp(-(t - 0.12f) * 55f) : 0f;
            out[i] = clip((rnd.nextFloat() * 2f - 1f) * (a * 0.4f + b * 0.5f));
        }
        return out;
    }

    /** 受伤：低通噪声 + 一点低频，闷。 */
    private static short[] hurt() {
        int n = SAMPLE_RATE / 9;
        short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(41);
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = (float) Math.exp(-t * 18f);
            float noise = (rnd.nextFloat() * 2f - 1f) * 0.5f;
            float low = (float) Math.sin(2 * Math.PI * 180 * t) * 0.6f;
            out[i] = clip((noise + low) * env);
        }
        return out;
    }

    /** 拾取：上扫正弦，和击杀同族但更干净。 */
    private static short[] pickup() {
        int n = SAMPLE_RATE / 5;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 600f + t * 2600f;
            float env = (float) Math.exp(-t * 10f);
            out[i] = clip((float) Math.sin(2 * Math.PI * freq * t) * env * 0.5f);
        }
        return out;
    }

    /** 攻击-衰减包络：起音 attack 秒冲上去，之后按 decay 速率指数衰减。 */
    private static float expEnv(float t, float attack, float decay) {
        float a = attack <= 0f ? 1f : Math.min(1f, t / attack);
        return a * (float) Math.exp(-t * decay);
    }

    private static short clip(float v) {
        if (v > 1f) {
            v = 1f;
        } else if (v < -1f) {
            v = -1f;
        }
        return (short) (v * Short.MAX_VALUE);
    }
}
