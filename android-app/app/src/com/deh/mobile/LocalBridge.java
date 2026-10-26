package com.deh.mobile;

import android.content.Intent;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/** 本地命令桥：127.0.0.1:38620，供容器内 agent/脚本调用 Shizuku 或请求打开文件。 */
public final class LocalBridge {
    private static ServerSocket server;
    private static volatile String token = "";
    private static volatile android.content.Context appContext = null;

    private LocalBridge() {
    }

    public static String token() {
        return token;
    }

    public static void start(final android.content.Context context) {
        if (server != null) return;
        appContext = context.getApplicationContext();
        try {
            token = Prefs.shizukuToken(context);
            if (token == null || token.isEmpty()) {
                token = randomToken();
                Prefs.setShizukuToken(context, token);
            }
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 38620));
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (server != null && !server.isClosed()) {
                        try {
                            Socket socket = server.accept();
                            handle(socket);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "dsh-bridge");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            server = null;
        }
    }

    private static void handle(Socket socket) {
        int code = 200;
        String body = "{\"error\":\"unknown\"}";
        try {
            socket.setSoTimeout(60000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String requestLine = in.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String tokenParam = null;
            String cmd = null;
            String target = null;
            int q = path.indexOf('?');
            if (q >= 0) {
                String query = path.substring(q + 1);
                path = path.substring(0, q);
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) continue;
                    String key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    if ("token".equals(key)) tokenParam = value;
                    else if ("cmd".equals(key)) cmd = value;
                    else if ("path".equals(key)) target = value;
                }
            }
            if (tokenParam == null || !tokenParam.equals(token)) {
                code = 403;
                body = "{\"error\":\"forbidden\"}";
            } else if ("/status".equals(path)) {
                body = "{\"shizuku\":\"" + ShizukuBridge.status() + "\"}";
            } else if ("/run".equals(path)) {
                if (cmd == null || cmd.isEmpty()) {
                    code = 400;
                    body = "{\"error\":\"cmd required\"}";
                } else {
                    try {
                        String output = ShizukuBridge.run(cmd, 60000);
                        body = "{\"output\":" + jsonString(output) + "}";
                    } catch (Throwable t) {
                        code = 500;
                        body = "{\"error\":" + jsonString(String.valueOf(t)) + "}";
                    }
                }
            } else if ("/dhizuku".equals(path)) {
                body = "{\"dhizuku\":\"" + DhizukuBridge.status() + "\",\"info\":" + jsonString(DhizukuBridge.info()) + "}";
            } else if ("/dhizuku-run".equals(path)) {
                if (cmd == null || cmd.isEmpty()) {
                    code = 400;
                    body = "{\"error\":\"cmd required\"}";
                } else {
                    try {
                        body = "{\"output\":" + jsonString(DhizukuBridge.run(cmd)) + "}";
                    } catch (Throwable t) {
                        code = 500;
                        body = "{\"error\":" + jsonString(String.valueOf(t)) + "}";
                    }
                }
            } else if ("/open".equals(path)) {
                if (target == null || target.isEmpty()) {
                    code = 400;
                    body = "{\"error\":\"path required\"}";
                } else {
                    body = "{\"result\":" + jsonString(openPath(target)) + "}";
                }
            } else {
                code = 404;
                body = "{\"error\":\"not found\"}";
            }
        } catch (Throwable t) {
            code = 500;
            body = "{\"error\":" + jsonString(String.valueOf(t)) + "}";
        }
        try {
            byte[] payload = body.getBytes("UTF-8");
            String header = "HTTP/1.1 " + code + " OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream os = socket.getOutputStream();
            os.write(header.getBytes("UTF-8"));
            os.write(payload);
            os.flush();
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    private static void diag(String message) {
        try {
            java.io.File dir = new java.io.File("/storage/emulated/0/DEH");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "diag.log"), true);
            String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date())
                    + " [open] " + message + "\n";
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private static String openPath(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return "not found: " + path;
            MainActivity.pickOpenForBridge(path);
            diag("pick open requested path=" + path);
            return "ok";
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    private static String guessMime(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json") || lower.endsWith(".md")) return "text/plain";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; value != null && i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private static String randomToken() {
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 24; i++) {
            sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }
}
