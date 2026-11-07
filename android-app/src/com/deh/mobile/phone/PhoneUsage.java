package com.deh.mobile.phone;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 使用数据：App 使用时长统计（需 PACKAGE_USAGE_STATS / appops GET_USAGE_STATS）。 */
public final class PhoneUsage {
    private static Context CTX;

    public static void init(Context c) { CTX = c.getApplicationContext(); }

    /** 最近 hours 小时的 App 使用统计。 */
    public static String topApps(int hours) {
        try {
            UsageStatsManager usm = (UsageStatsManager) CTX.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return "{\"ok\":false,\"error\":\"no usage service\"}";
            long now = System.currentTimeMillis();
            long start = now - (long) Math.max(1, Math.min(168, hours)) * 3600_000L;
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now);
            if (list == null || list.isEmpty()) {
                return "{\"ok\":false,\"error\":\"no data (need PACKAGE_USAGE_STATS permission)\"}";
            }
            Map<String, long[]> agg = new HashMap<>();
            for (UsageStats u : list) {
                if (u == null) continue;
                String pkg = u.getPackageName();
                if (pkg == null || pkg.isEmpty()) continue;
                long[] a = agg.get(pkg);
                if (a == null) { a = new long[]{0, 0}; agg.put(pkg, a); }
                a[0] += u.getTotalTimeInForeground();
                a[1] = Math.max(a[1], u.getLastTimeUsed());
            }
            java.util.List<Map.Entry<String, long[]>> entries = new java.util.ArrayList<>(agg.entrySet());
            entries.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));
            JSONArray arr = new JSONArray();
            int n = 0;
            for (Map.Entry<String, long[]> e : entries) {
                if (e.getValue()[0] <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("pkg", e.getKey());
                o.put("ms", e.getValue()[0]);
                o.put("min", e.getValue()[0] / 60000);
                o.put("last", e.getValue()[1]);
                arr.put(o);
                if (++n >= 20) break;
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("hours", hours);
            out.put("apps", arr);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
        }
    }
}
