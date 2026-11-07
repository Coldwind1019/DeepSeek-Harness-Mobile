package com.deh.mobile.phone;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 组合观察：音频+画面并行采集（时间对齐）。 */
public final class PhoneObserve {
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    /** 召唤式观察：并行启动 音频录制 与 截图，同时结束，减少时差。 */
    public static String observe(int sec, int fps) {
        JSONObject out = new JSONObject();
        try {
            final int n = Math.max(1, sec);
            // 并行：音频（含转写） + 画面（拼图）
            Future<String> audioTask = POOL.submit(() -> {
                String text = "";
                String wav = null;
                try {
                    if (PhoneAudio.hasProjection() && PhoneAudio.startCapture()) {
                        wav = PhoneAudio.record(n);
                        PhoneAudio.stop();
                    }
                    if (wav != null) {
                        byte[] pcm = PhoneObserve.readPcm(wav);
                        String t = PhoneAsr.transcribe(pcm, 48000);
                        text = t == null ? "" : t;
                    }
                } catch (Throwable ignored) {}
                JSONObject r = new JSONObject();
                try { r.put("text", text); r.put("wav", wav == null ? "" : wav); } catch (Throwable ignored) {}
                return r.toString();
            });
            Future<String> seeTask = POOL.submit(() -> {
                try {
                    JSONObject sj = new JSONObject(PhoneSee.see(n, fps));
                    return sj.optBoolean("ok") ? sj.optString("mosaic") : "";
                } catch (Throwable t) { return ""; }
            });

            // 等两个都完成（顺序无关，时间已对齐）
            String audioJson = audioTask.get(n * 1000L + 30000, TimeUnit.MILLISECONDS);
            String mosaic = seeTask.get(n * 1000L + 30000, TimeUnit.MILLISECONDS);

            JSONObject aj = new JSONObject(audioJson);
            out.put("ok", true);
            out.put("text", aj.optString("text"));
            out.put("wav", aj.optString("wav"));
            out.put("mosaic", mosaic == null ? "" : mosaic);
            // UI 树（读一次）
            String ui = "{}";
            try {
                PhoneUiService s = PhoneUiService.get();
                if (s != null) ui = s.dumpUi(60);
            } catch (Throwable ignored) {}
            out.put("ui", ui);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
        }
    }

    static byte[] readPcm(String path) {
        try {
            byte[] all = java.nio.file.Files.readAllBytes(new java.io.File(path).toPath());
            if (all.length <= 44) return new byte[0];
            return java.util.Arrays.copyOfRange(all, 44, all.length);
        } catch (Throwable t) { return new byte[0]; }
    }
}
