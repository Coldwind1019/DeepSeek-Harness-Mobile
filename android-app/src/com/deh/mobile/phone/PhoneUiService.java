package com.deh.mobile.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.deh.mobile.Logx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 无障碍服务：UI 树读取 / 点击 / 输入 / 滑动。
 * 通过 Shizuku 的 settings put secure 自动开启（无手动步骤）。
 */
public class PhoneUiService extends AccessibilityService {
    private static volatile PhoneUiService INSTANCE;

    public static PhoneUiService get() { return INSTANCE; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                        | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
                Logx.i("phone-ui service connected, flags=" + info.flags);
            }
        } catch (Throwable t) {
            Logx.e("phone-ui flags fail", t);
        }
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* no-op */ }

    @Override
    public void onInterrupt() { /* no-op */ }

    /** 当前窗口 UI 树 → JSON。 */
    public String dumpUi(int maxNodes) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "{\"ok\":false,\"error\":\"no root\"}";
            JSONObject out = new JSONObject();
            out.put("ok", true);
            JSONArray nodes = new JSONArray();
            Deque<Object[]> q = new ArrayDeque<>();
            q.add(new Object[]{root, 0});
            int count = 0;
            while (!q.isEmpty() && count < maxNodes) {
                Object[] it = q.poll();
                AccessibilityNodeInfo n = (AccessibilityNodeInfo) it[0];
                int depth = (Integer) it[1];
                if (n == null) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                JSONObject o = new JSONObject();
                o.put("depth", depth);
                o.put("text", n.getText() == null ? "" : n.getText().toString());
                o.put("desc", n.getContentDescription() == null ? "" : n.getContentDescription().toString());
                o.put("cls", n.getClassName() == null ? "" : n.getClassName().toString());
                o.put("pkg", n.getPackageName() == null ? "" : n.getPackageName().toString());
                o.put("id", n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName());
                o.put("bounds", r.left + "," + r.top + "," + r.right + "," + r.bottom);
                o.put("clickable", n.isClickable());
                o.put("editable", n.isEditable());
                o.put("enabled", n.isEnabled());
                o.put("scrollable", n.isScrollable());
                nodes.put(o);
                count++;
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(new Object[]{c, depth + 1});
                }
            }
            out.put("count", count);
            out.put("nodes", nodes);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
        }
    }

    /** 按文本点击（支持包含匹配）；返回是否成功。 */
    public boolean clickText(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            java.util.List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
            if (list == null) return false;
            for (AccessibilityNodeInfo n : list) {
                AccessibilityNodeInfo c = n;
                for (int i = 0; i < 6 && c != null; i++) {
                    if (c.isClickable()) {
                        return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    c = c.getParent();
                }
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    /** 坐标点击。 */
    public boolean clickAt(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(path, 0, 60));
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) { return false; }
    }

    /** 输入文本（先找焦点/可编辑节点）。 */
    public boolean typeText(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) focus = findByEditable(root);
            if (focus == null) return false;
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (Throwable t) { return false; }
    }

    private AccessibilityNodeInfo findByEditable(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isEditable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    /** 滑动（坐标）。 */
    public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(60, ms)));
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) { return false; }
    }
}
