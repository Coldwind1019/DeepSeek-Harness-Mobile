package com.deh.mobile.adb;

import android.util.Base64;

import com.deh.mobile.App;
import com.deh.mobile.Logx;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * ADB 认证密钥：RSA-2048 生成/持久化、Android 公钥编码、token 签名。
 *
 * <p>公钥编码与 AOSP crypto_utils 的 android_pubkey_encode 完全一致
 * （JADB 的 AndroidPubkey.encode 也是同一格式），结构体共 524 字节、小端：</p>
 * <pre>
 *   uint32  modulus_size_words  // 模数的 32 位字数：2048/32 = 64（"QAAAA" 前缀即 64）
 *   uint32  n0inv               // -1 / n[0] mod 2^32（Montgomery 参数）
 *   byte[256] modulus           // 小端
 *   byte[256] rr                // R^2 mod n，R = 2^2048，即 2^4096 mod n，小端
 *   uint32  exponent            // 65537
 * </pre>
 * 该 524 字节整体 Base64（NO_WRAP）后即 adbkey.pub 里的字符串；
 * 发送给设备的 AUTH(RSAPUBLICKEY) 负载 = Base64 + " " + 用户名 + "\0"
 * （adbd 解码时在非 Base64 字符处停止，尾部用户名无副作用，与真实 adbkey.pub 一致）。
 *
 * <p>来源说明：格式依据 AOSP system/core/libcrypto_utils/android_pubkey.cpp 与
 * JADB（Apache-2.0）的 AndroidPubkey.java，本文件为独立实现，无外部依赖。</p>
 */
public final class AdbCrypto {

    private static final String FILE_PRIVATE = "adbkey";       // PKCS#8（DER）
    private static final String FILE_PUBLIC = "adbkey.pub";    // X.509（DER）
    private static final String USER = "adbtool@android";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int MODULUS_BYTES = 256;              // RSA-2048
    private static final int MODULUS_WORDS = MODULUS_BYTES / 4; // 64
    /** 3 个 uint32 + 2 个 256 字节数组 = 524。 */
    private static final int ENCODED_SIZE = 3 * 4 + 2 * MODULUS_BYTES;

    private static AdbCrypto instance;

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String adbPublicKey;
    private final Object signLock = new Object();

    private AdbCrypto(PrivateKey priv, RSAPublicKey pub) {
        this.privateKey = priv;
        this.publicKey = pub;
        this.adbPublicKey = buildAdbPublicKey(pub);
    }

    /** 获取（首次生成并落盘）本机 ADB 密钥。 */
    public static synchronized AdbCrypto get() throws Exception {
        if (instance == null) {
            instance = loadOrCreate();
        }
        return instance;
    }

    /** Android 格式公钥的 Base64 字符串（不含用户名后缀）。 */
    public String adbPublicKeyBase64() {
        return adbPublicKey;
    }

    /** AUTH(RSAPUBLICKEY) 的负载：Base64 + " " + 用户名 + '\0'。 */
    public byte[] adbPublicKeyPayload() {
        return (adbPublicKey + " " + USER + "\0").getBytes(UTF8);
    }

    /** 用 SHA1withRSA 对设备 token 签名，返回 256 字节（PKCS#1 v1.5）。 */
    public byte[] signToken(byte[] token) throws Exception {
        synchronized (signLock) {
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(privateKey);
            sig.update(token);
            return sig.sign();
        }
    }

    /* ------------------------------ 密钥加载/生成 ------------------------------ */

    private static AdbCrypto loadOrCreate() throws Exception {
        if (App.ctx() == null) {
            throw new IllegalStateException("应用上下文未初始化，无法读取 ADB 密钥");
        }
        File dir = App.ctx().getFilesDir();
        if (dir == null) {
            throw new IllegalStateException("应用私有目录不可用，无法保存 ADB 密钥");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建应用私有目录: " + dir);
        }
        File fPriv = new File(dir, FILE_PRIVATE);
        File fPub = new File(dir, FILE_PUBLIC);
        if (fPriv.exists() && fPub.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(fPriv)));
                RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(readAll(fPub)));
                if (pub.getModulus().bitLength() == 2048) {
                    return new AdbCrypto(priv, pub);
                }
                Logx.i("ADB 密钥位数非 2048，重新生成");
            } catch (Throwable t) {
                Logx.e("ADB 密钥损坏，重新生成: " + t);
            }
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        writeAll(fPriv, kp.getPrivate().getEncoded());
        writeAll(fPub, kp.getPublic().getEncoded());
        try {
            fPriv.setReadable(true, true);
            fPriv.setWritable(true, true);
        } catch (Throwable ignored) {
        }
        Logx.i("已生成 ADB RSA-2048 密钥: " + fPriv.getAbsolutePath());
        return new AdbCrypto(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] data = new byte[(int) Math.max(0, f.length())];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            if (off != data.length) {
                byte[] cut = new byte[off];
                System.arraycopy(data, 0, cut, 0, off);
                return cut;
            }
            return data;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        OutputStream out = new FileOutputStream(f, false);
        try {
            out.write(data);
            out.flush();
        } finally {
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ------------------------------ 公钥编码 ------------------------------ */

    /** 按 android_pubkey_encode 结构体编码并 Base64。 */
    private static String buildAdbPublicKey(RSAPublicKey key) {
        BigInteger modulus = key.getModulus();
        BigInteger exponent = key.getPublicExponent();
        if (modulus.bitLength() != 2048) {
            throw new IllegalStateException("ADB 公钥必须是 RSA-2048，当前 " + modulus.bitLength() + " 位");
        }
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = modulus.mod(r32);
        BigInteger inverse = n0.modInverse(r32);
        BigInteger n0inv = r32.subtract(inverse);
        // rr = (2^2048)^2 mod n = 2^4096 mod n
        BigInteger rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8)
                .modPow(BigInteger.valueOf(2), modulus);

        byte[] buf = new byte[ENCODED_SIZE];
        putIntLE(buf, 0, MODULUS_WORDS);
        putIntLE(buf, 4, n0inv.intValue());
        byte[] modulusLe = toLittleEndianFixed(modulus, MODULUS_BYTES);
        System.arraycopy(modulusLe, 0, buf, 8, MODULUS_BYTES);
        byte[] rrLe = toLittleEndianFixed(rr, MODULUS_BYTES);
        System.arraycopy(rrLe, 0, buf, 8 + MODULUS_BYTES, MODULUS_BYTES);
        putIntLE(buf, 8 + 2 * MODULUS_BYTES, exponent.intValue());
        return Base64.encodeToString(buf, Base64.NO_WRAP);
    }

    /** 大端 BigInteger -> 固定长度小端数组（不足补 0，超出抛异常）。 */
    private static byte[] toLittleEndianFixed(BigInteger value, int size) {
        byte[] be = value.toByteArray();
        int start = 0;
        while (start < be.length - 1 && be[start] == 0) {
            start++;
        }
        int len = be.length - start;
        if (len > size) {
            throw new IllegalStateException("数值超出 " + size + " 字节: " + len);
        }
        byte[] out = new byte[size];
        for (int i = 0; i < len; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    private static void putIntLE(byte[] b, int off, int value) {
        b[off] = (byte) (value & 0xFF);
        b[off + 1] = (byte) ((value >>> 8) & 0xFF);
        b[off + 2] = (byte) ((value >>> 16) & 0xFF);
        b[off + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    /** 调试用：公钥指纹（SHA-256 前 8 字节 hex）。 */
    public String fingerprint() {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(publicKey.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < d.length; i++) {
                String h = Integer.toHexString(d[i] & 0xFF);
                if (h.length() < 2) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 是否存在已持久化的密钥（不触发生成）。 */
    public static boolean hasStoredKey() {
        try {
            File dir = App.ctx().getFilesDir();
            return dir != null && new File(dir, FILE_PRIVATE).exists()
                    && new File(dir, FILE_PUBLIC).exists();
        } catch (Throwable t) {
            return false;
        }
    }
}
