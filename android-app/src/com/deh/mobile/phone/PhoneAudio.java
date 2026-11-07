package com.deh.mobile.phone;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

import java.io.File;
import java.io.FileOutputStream;

/** 内录：AudioPlaybackCapture（非麦克风）。授权流程：onActivityResult → 启服务 → getMediaProjection。 */
public final class PhoneAudio {
    private static Context CTX;
    private static MediaProjection projection;
    private static AudioRecord rec;
    private static volatile boolean capturing = false;
    private static final int SR = 48000;

    public static void init(Context c) { CTX = c.getApplicationContext(); }

    public static boolean hasProjection() { return projection != null; }

    /** 授权回调里调用：先启动前台服务（mediaProjection 类型），再 getMediaProjection。 */
    public static synchronized boolean onAuthResult(int resultCode, Intent data) {
        if (CTX == null || data == null) return false;
        try {
            // 1) 启动 mediaProjection 前台服务（Android 14 要求）
            android.content.Intent svc = new android.content.Intent(CTX, CaptureService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                CTX.startForegroundService(svc);
            } else {
                CTX.startService(svc);
            }
            Thread.sleep(600);
            // 2) 拿 MediaProjection
            MediaProjectionManager mgr = (MediaProjectionManager) CTX.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection mp = mgr.getMediaProjection(resultCode, data);
            if (mp == null) return false;
            mp.registerCallback(new MediaProjection.Callback() {}, new android.os.Handler(android.os.Looper.getMainLooper()));
            projection = mp;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 开始/停止内录。 */
    public static synchronized boolean startCapture() {
        if (projection == null) return false;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioRecord r = new AudioRecord.Builder()
                    .setContext(CTX)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(8192)
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) return false;
            r.startRecording();
            rec = r;
            capturing = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized String record(int seconds) {
        if (!capturing || rec == null) return null;
        try {
            File dir = new File("/storage/emulated/0/DEH/.phone");
            if (!dir.isDirectory()) dir.mkdirs();
            File out = new File(dir, "audio_" + System.currentTimeMillis() + ".wav");
            int total = SR * Math.max(1, seconds);
            byte[] buf = new byte[8192];
            FileOutputStream fos = new FileOutputStream(out);
            writeWavHeader(fos, total * 2, SR);
            int written = 0;
            while (written < total) {
                int n = rec.read(buf, 0, Math.min(buf.length, (total - written) * 2));
                if (n <= 0) break;
                fos.write(buf, 0, n);
                written += n / 2;
            }
            fos.close();
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized void stop() {
        capturing = false;
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignored) {}
        rec = null;
    }

    private static void writeWavHeader(FileOutputStream f, int dataLen, int sr) throws Exception {
        int ch = 1, bits = 16;
        int byteRate = sr * ch * bits / 8;
        f.write("RIFF".getBytes());
        f.write(intLE(36 + dataLen));
        f.write("WAVE".getBytes());
        f.write("fmt ".getBytes());
        f.write(intLE(16));
        f.write(shortLE(1));
        f.write(shortLE(ch));
        f.write(intLE(sr));
        f.write(intLE(byteRate));
        f.write(shortLE(ch * bits / 8));
        f.write(shortLE(bits));
        f.write("data".getBytes());
        f.write(intLE(dataLen));
    }
    private static byte[] intLE(int v) { return new byte[]{(byte)v,(byte)(v>>8),(byte)(v>>16),(byte)(v>>24)}; }
    private static byte[] shortLE(int v) { return new byte[]{(byte)v,(byte)(v>>8)}; }

    // ===== 实时录音（供 /phone/asr/live 用）=====
    public interface ChunkSink { void onChunk(byte[] pcm); }

    /** 实时录音：每 0.1 秒回调一块 PCM16（48k mono），持续 sec 秒。 */
    public static String recordLive(int sec, ChunkSink sink) {
        if (projection == null) return null;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioRecord r = new AudioRecord.Builder()
                    .setContext(CTX)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(8192)
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) return null;
            r.startRecording();
            int chunkBytes = SR / 10 * 2; // 0.1s
            byte[] buf = new byte[chunkBytes];
            long endAt = System.currentTimeMillis() + sec * 1000L;
            while (System.currentTimeMillis() < endAt) {
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) break;
                sink.onChunk(java.util.Arrays.copyOf(buf, n));
            }
            r.stop();
            r.release();
            return "ok";
        } catch (Throwable t) {
            return null;
        }
    }
}
