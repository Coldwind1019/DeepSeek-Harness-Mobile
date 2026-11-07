package com.deh.mobile.phone;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/** /phone/* 路由：uri 形如 "ui/dump?text=..."。 */
public final class PhoneRouter {
    public static String handle(String path, String query) {
        String sub = path.substring("/phone/".length());
        String action = sub;
        String qs = query == null ? "" : query;
        int q = action.indexOf('?');
        if (q >= 0) { qs = action.substring(q + 1); action = action.substring(0, q); }
        Uri u = Uri.parse("?" + qs);
        try {
            if ("ui/dump".equals(action)) return PhoneApi.dumpUi();
            if ("ui/enable".equals(action)) return PhoneApi.ensureAccessibility();
            if ("ui/click".equals(action)) {
                String text = u.getQueryParameter("text");
                String x = u.getQueryParameter("x");
                String y = u.getQueryParameter("y");
                if (text != null && !text.isEmpty()) return PhoneApi.clickText(text);
                if (x != null && y != null) return PhoneApi.clickAt(Integer.parseInt(x), Integer.parseInt(y));
                return "{\"ok\":false,\"error\":\"text or x,y required\"}";
            }
            if ("ui/type".equals(action)) {
                String text = u.getQueryParameter("text");
                if (text == null) return "{\"ok\":false,\"error\":\"text required\"}";
                return PhoneApi.typeText(text);
            }
            if ("ui/swipe".equals(action)) {
                int x1 = Integer.parseInt(u.getQueryParameter("x1"));
                int y1 = Integer.parseInt(u.getQueryParameter("y1"));
                int x2 = Integer.parseInt(u.getQueryParameter("x2"));
                int y2 = Integer.parseInt(u.getQueryParameter("y2"));
                int ms = Integer.parseInt(u.getQueryParameter("ms") == null ? "300" : u.getQueryParameter("ms"));
                return PhoneApi.swipe(x1, y1, x2, y2, ms);
            }
            if ("shot".equals(action)) return PhoneApi.screenshot();
            if ("audio/listen".equals(action)) {
                if (!PhoneAudio.hasProjection()) return "{\"ok\":false,\"error\":\"need projection (start from app)\"}";
                if (!PhoneAudio.startCapture()) return "{\"ok\":false,\"error\":\"capture start failed\"}";
                int sec = Integer.parseInt(u.getQueryParameter("sec") == null ? "3" : u.getQueryParameter("sec"));
                String audioPath = PhoneAudio.record(sec);
                PhoneAudio.stop();
                if (audioPath == null) return "{\"ok\":false,\"error\":\"record failed\"}";
                return "{\"ok\":true,\"path\":\"" + audioPath + "\",\"sec\":" + sec + "}";
            }
            if ("asr/live".equals(action)) {
                if (!PhoneAudio.hasProjection()) return "{\"ok\":false,\"error\":\"need projection\"}";
                int sec = Integer.parseInt(u.getQueryParameter("sec") == null ? "5" : u.getQueryParameter("sec"));
                if (!PhoneAsr.liveStart()) return "{\"ok\":false,\"error\":\"asr init failed\"}";
                final JSONArray segs = new JSONArray();
                final long startMs = System.currentTimeMillis();
                final int[] lastMs = {0};
                String r = PhoneAudio.recordLive(sec, new PhoneAudio.ChunkSink() {
                    @Override public void onChunk(byte[] pcm) {
                        String delta = PhoneAsr.liveFeed(pcm, 48000);
                        if (delta != null && !delta.isEmpty()) {
                            try {
                                JSONObject seg = new JSONObject();
                                seg.put("t", System.currentTimeMillis() - startMs);
                                seg.put("text", delta);
                                segs.put(seg);
                                lastMs[0] = (int) (System.currentTimeMillis() - startMs);
                            } catch (Throwable ignored) {}
                        }
                    }
                });
                PhoneAsr.liveStop();
                if (r == null) return "{\"ok\":false,\"error\":\"record failed\"}";
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("sec", sec);
                o.put("segs", segs);
                return o.toString();
            }
            if ("usage".equals(action)) {
                int hours = Integer.parseInt(u.getQueryParameter("hours") == null ? "24" : u.getQueryParameter("hours"));
                return PhoneUsage.topApps(hours);
            }
            if ("observe".equals(action)) {
                int sec = Integer.parseInt(u.getQueryParameter("sec") == null ? "4" : u.getQueryParameter("sec"));
                int fps = Integer.parseInt(u.getQueryParameter("fps") == null ? "2" : u.getQueryParameter("fps"));
                return PhoneObserve.observe(sec, fps);
            }
            if ("see".equals(action)) {
                int sec = Integer.parseInt(u.getQueryParameter("sec") == null ? "3" : u.getQueryParameter("sec"));
                int fps = Integer.parseInt(u.getQueryParameter("fps") == null ? "2" : u.getQueryParameter("fps"));
                return PhoneSee.see(sec, fps);
            }
            if ("asr".equals(action)) {
                if (!PhoneAudio.hasProjection()) return "{\"ok\":false,\"error\":\"need projection\"}";
                if (!PhoneAudio.startCapture()) return "{\"ok\":false,\"error\":\"capture start failed\"}";
                int sec = Integer.parseInt(u.getQueryParameter("sec") == null ? "3" : u.getQueryParameter("sec"));
                String wav = PhoneAudio.record(sec);
                PhoneAudio.stop();
                if (wav == null) return "{\"ok\":false,\"error\":\"record failed\"}";
                byte[] pcm = readPcmFromWav(wav);
                String text = PhoneAsr.transcribe(pcm, 48000);
                return "{\"ok\":true,\"text\":\"" + (text == null ? "" : text.replace("\"","'").replace("\n"," ")) + "\",\"wav\":\"" + wav + "\"}";
            }
            if ("audio/start".equals(action)) return PhoneAudio.startCapture() ? "{\"ok\":true}" : "{\"ok\":false}";
            if ("audio/stop".equals(action)) { PhoneAudio.stop(); return "{\"ok\":true}"; }

            if ("shell".equals(action)) {
                String cmd = u.getQueryParameter("cmd");
                if (cmd == null || cmd.isEmpty()) return "{\"ok\":false,\"error\":\"cmd required\"}";
                return PhoneApi.shell(cmd);
            }
            return "{\"ok\":false,\"error\":\"unknown /phone/" + action + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
        }
    }

    private static byte[] readPcmFromWav(String path) {
        try {
            byte[] all = java.nio.file.Files.readAllBytes(new java.io.File(path).toPath());
            if (all.length <= 44) return new byte[0];
            return java.util.Arrays.copyOfRange(all, 44, all.length);
        } catch (Throwable t) {
            return new byte[0];
        }
    }
}
