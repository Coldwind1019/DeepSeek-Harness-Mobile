package com.deh.mobile.phone;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.deh.mobile.ShizukuBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 连续截图 + 变化帧筛选 + 拼图（see-now 安卓版）。
 * 召唤式：/phone/see?sec=N&fps=M
 */
public final class PhoneSee {
    private static final int GW = 64, GH = 36; // 变化分网格

    /** 截一张图（shell）→ Bitmap。 */
    private static Bitmap grab(File out) {
        try {
            ShizukuBridge.run("screencap -p '" + out.getAbsolutePath() + "'", 15000);
            if (!out.isFile() || out.length() < 100) return null;
            return BitmapFactory.decodeFile(out.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 亮度/色差 变化分 0-255（简化版 see-now：缩到 64x36 网格，HSV 加权）。 */
    private static double score(Bitmap a, Bitmap b) {
        if (a == null || b == null) return 255;
        Bitmap sa = Bitmap.createScaledBitmap(a, GW, GH, false);
        Bitmap sb = Bitmap.createScaledBitmap(b, GW, GH, false);
        long sum = 0;
        for (int y = 0; y < GH; y++) {
            for (int x = 0; x < GW; x++) {
                int ca = sa.getPixel(x, y), cb = sb.getPixel(x, y);
                int ra = Color.red(ca), ga = Color.green(ca), ba = Color.blue(ca);
                int rb = Color.red(cb), gb = Color.green(cb), bb = Color.blue(cb);
                int dh = Math.abs(hue(ra, ga, ba) - hue(rb, gb, bb));
                int ds = Math.abs(sat(ra, ga, ba) - sat(rb, gb, bb));
                int dv = Math.abs(val(ra, ga, ba) - val(rb, gb, bb));
                sum += dh + ds + dv;
            }
        }
        sa.recycle(); sb.recycle();
        return Math.min(255, sum / (double) (GW * GH));
    }
    private static int hue(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        return (int) hsv[0];
    }
    private static int sat(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        return (int) (hsv[1] * 100);
    }
    private static int val(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        return (int) (hsv[2] * 100);
    }

    /** 拼图（≤16 帧，4x4）。 */
    private static String mosaic(java.util.List<Bitmap> frames) {
        try {
            if (frames.isEmpty()) return null;
            int cellW = frames.get(0).getWidth() / 2;
            int cellH = frames.get(0).getHeight() / 2;
            int cols = Math.min(4, frames.size());
            int rows = (int) Math.ceil(frames.size() / (double) cols);
            Bitmap out = Bitmap.createBitmap(cols * cellW, rows * cellH, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(out);
            Paint p = new Paint();
            for (int i = 0; i < frames.size(); i++) {
                int cx = (i % cols) * cellW;
                int cy = (i / cols) * cellH;
                c.drawBitmap(frames.get(i), null, new Rect(cx, cy, cx + cellW, cy + cellH), p);
            }
            File f = new File("/storage/emulated/0/DEH/.phone", "see_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(f);
            out.compress(Bitmap.CompressFormat.PNG, 80, fos);
            fos.close();
            out.recycle();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 连续截图 sec 秒（fps 次/秒）→ 变化帧拼图。 */
    public static String see(int sec, int fps) {
        try {
            int total = Math.max(1, sec) * Math.max(1, Math.min(5, fps));
            java.util.List<Bitmap> kept = new java.util.ArrayList<>();
            Bitmap prev = null;
            int interval = 1000 / Math.max(1, Math.min(5, fps));
            for (int i = 0; i < total; i++) {
                File tmp = new File("/storage/emulated/0/DEH/.phone", "f" + i + ".png");
                Bitmap cur = grab(tmp);
                tmp.delete();
                if (cur == null) continue;
                double sc = score(prev, cur);
                if (prev == null || sc > 6.0) { // 变化阈值（突变/持续运动）
                    kept.add(cur);
                    prev = cur.copy(Bitmap.Config.RGB_565, false);
                } else {
                    cur.recycle();
                }
                Thread.sleep(interval);
            }
            String mosaicPath = mosaic(kept);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("frames", kept.size());
            o.put("total", total);
            o.put("mosaic", mosaicPath == null ? "" : mosaicPath);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
        }
    }
}
