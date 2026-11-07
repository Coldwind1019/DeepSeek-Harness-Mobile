package com.deh.mobile.phone;

import android.content.Context;

import com.deh.mobile.Logx;
import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineLMConfig;
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;

/** 离线流式 ASR（sherpa-onnx，中文 zipformer CTC int8）。 */
public final class PhoneAsr {
    private static final String EXTERNAL_MODEL = "/storage/emulated/0/DEH/.phone/sherpa/model.onnx";
    private static final String EXTERNAL_TOKENS = "/storage/emulated/0/DEH/.phone/sherpa/tokens.txt";
    private static Context CTX;
    private static OnlineRecognizer recognizer;
    private static volatile boolean inited = false;

    public static void init(Context c) { CTX = c.getApplicationContext(); }

    public static synchronized boolean ensure() {
        if (inited && recognizer != null) return true;
        try {
            OnlineZipformer2CtcModelConfig ctc = new OnlineZipformer2CtcModelConfig("sherpa/model.int8.onnx");
            OnlineModelConfig mc = new OnlineModelConfig(
                    new OnlineTransducerModelConfig("", "", "", new com.k2fsa.sherpa.onnx.QnnConfig("", "", "")),
                    new OnlineParaformerModelConfig("", ""),
                    ctc,
                    new OnlineNeMoCtcModelConfig(""),
                    new OnlineToneCtcModelConfig(""),
                    "sherpa/tokens.txt", 2, false, "", "", "", "");
            OnlineRecognizerConfig cfg = new OnlineRecognizerConfig(
                    new FeatureConfig(16000, 80, 0.0f),
                    mc,
                    new OnlineLMConfig("", 0.0f),
                    new OnlineCtcFstDecoderConfig("", 0),
                    new HomophoneReplacerConfig("", "", ""),
                    new EndpointConfig(new EndpointRule(false, 2.4f, 0.0f), new EndpointRule(false, 1.2f, 0.0f), new EndpointRule(false, 2.4f, 0.0f)),
                    true, "greedy_search", 0, "", 0.0f, "", "", 0.0f);
            recognizer = new OnlineRecognizer(CTX.getAssets(), cfg);
            inited = true;
            return true;
        } catch (Throwable t) {
            Logx.e("asr init fail: " + t);
            return false;
        }
    }

    /** PCM 16-bit mono → 文本（一次性喂完后取结果）。 */
    public static synchronized String transcribe(byte[] pcm, int sr) {
        if (!ensure() || recognizer == null) return null;
        try {
            int n = pcm.length / 2;
            // 先转 float（原始采样率）
            float[] raw = new float[n];
            for (int i = 0; i < n; i++) {
                int lo = pcm[2 * i] & 0xFF;
                int hi = pcm[2 * i + 1];
                raw[i] = (short) ((hi << 8) | lo) / 32768.0f;
            }
            // 重采样到 16k（简单线性插值）
            int target = 16000;
            float[] f;
            if (sr == target) {
                f = raw;
            } else {
                int outLen = (int) ((long) n * target / sr);
                f = new float[outLen];
                double ratio = (double) sr / target;
                for (int i = 0; i < outLen; i++) {
                    double pos = i * ratio;
                    int i0 = (int) pos;
                    int i1 = Math.min(i0 + 1, n - 1);
                    float frac = (float) (pos - i0);
                    f[i] = raw[i0] * (1 - frac) + raw[i1] * frac;
                }
            }
            OnlineStream stream = recognizer.createStream("");
            // 流式分块喂（0.5 秒一块），边喂边解码
            int chunk = target / 2; // 0.5s @16k = 8000
            StringBuilder sb = new StringBuilder();
            for (int off = 0; off < f.length; off += chunk) {
                int end = Math.min(off + chunk, f.length);
                float[] part = java.util.Arrays.copyOfRange(f, off, end);
                stream.acceptWaveform(part, target);
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                String partial = recognizer.getResult(stream).getText();
                if (partial != null && !partial.isEmpty()) {
                    String cur = partial.trim();
                    if (cur.length() > sb.length()) sb.setLength(0);
                    sb.append(cur);
                }
            }
            // 尾部静音，触发最终 endpoint
            stream.acceptWaveform(new float[16000], target);
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            String finalText = recognizer.getResult(stream).getText();
            stream.release();
            String out = (finalText != null && !finalText.trim().isEmpty()) ? finalText.trim() : sb.toString().trim();
            return out;
        } catch (Throwable t) {
            Logx.e("asr transcribe fail: " + t);
            return null;
        }
    }

    // ===== 实时流式（供 /phone/asr/live 用）=====
    private static OnlineStream liveStream;
    private static String liveLast = "";

    public static synchronized boolean liveStart() {
        if (!ensure() || recognizer == null) return false;
        try {
            liveStream = recognizer.createStream("");
            liveLast = "";
            return true;
        } catch (Throwable t) { return false; }
    }

    /** 喂一块 PCM16（48k）→ 返回增量文本（无新增返回空）。 */
    public static synchronized String liveFeed(byte[] pcm, int sr) {
        if (liveStream == null) return "";
        try {
            int n = pcm.length / 2;
            float[] raw = new float[n];
            for (int i = 0; i < n; i++) {
                int lo = pcm[2 * i] & 0xFF;
                int hi = pcm[2 * i + 1];
                raw[i] = (short) ((hi << 8) | lo) / 32768.0f;
            }
            float[] f;
            int target = 16000;
            if (sr == target) f = raw;
            else {
                int outLen = (int) ((long) n * target / sr);
                f = new float[outLen];
                double ratio = (double) sr / target;
                for (int i = 0; i < outLen; i++) {
                    double pos = i * ratio;
                    int i0 = (int) pos, i1 = Math.min(i0 + 1, n - 1);
                    float frac = (float) (pos - i0);
                    f[i] = raw[i0] * (1 - frac) + raw[i1] * frac;
                }
            }
            liveStream.acceptWaveform(f, target);
            while (recognizer.isReady(liveStream)) recognizer.decode(liveStream);
            String cur = recognizer.getResult(liveStream).getText();
            if (cur == null) cur = "";
            cur = cur.trim();
            if (cur.length() <= liveLast.length()) return "";
            String delta = cur.substring(liveLast.length()).trim();
            liveLast = cur;
            return delta;
        } catch (Throwable t) { return ""; }
    }

    public static synchronized void liveStop() {
        try { if (liveStream != null) liveStream.release(); } catch (Throwable ignored) {}
        liveStream = null;
        liveLast = "";
    }
}
