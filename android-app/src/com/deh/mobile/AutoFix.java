package com.deh.mobile;

/**
 * 通用自修复框架：对任意版本 WebView 的运行时错误分类并给出修复动作。
 * - API 缺失（xxx is not a function / is not defined）→ 注入 polyfill
 * - 语法错误（SyntaxError）→ 记录（前端已转译，正常不会出现）→ 重载
 * - 白屏 / 渲染进程崩溃 → 重建 WebView 或重载
 * - 网络/端口 → 交给 Runtime 重试
 * 所有修复动作写入 diag，用户可查。
 */
public final class AutoFix {
    private AutoFix() {
    }

    /** 注入页面的守护脚本：探测缺失 API 并通过 console 上报给原生层。 */
    public static final String AGENT = "(function(){"
            + "if(window.__dsAgentInstalled)return;window.__dsAgentInstalled=true;"
            + "function report(name){try{console.log('DSH_REPAIR:missing:'+name);}catch(e){}}"
            + "function probe(){"
            + "var checks=[['Promise.withResolvers',typeof Promise!=='undefined'&&!!Promise.withResolvers],"
            + "['Object.hasOwn',typeof Object.hasOwn==='function'],"
            + "['Array.prototype.at',typeof Array.prototype.at==='function'],"
            + "['Array.prototype.flat',typeof Array.prototype.flat==='function'],"
            + "['String.prototype.replaceAll',typeof String.prototype.replaceAll==='function'],"
            + "['Object.fromEntries',typeof Object.fromEntries==='function'],"
            + "['structuredClone',typeof structuredClone==='function'],"
            + "['queueMicrotask',typeof queueMicrotask==='function']];"
            + "for(var i=0;i<checks.length;i++){if(!checks[i][1])report(checks[i][0]);}"
            + "}"
            + "window.addEventListener('error',function(e){try{console.log('DSH_REPAIR:error:'+(e&&e.message?e.message:''));}catch(x){}});"
            + "window.addEventListener('unhandledrejection',function(e){try{console.log('DSH_REPAIR:rejection:'+(e&&e.reason?e.reason:''));}catch(x){}});"
            + "probe();setInterval(probe,4000);"
            + "})();";

    /** 从 console 文本判断错误类别。 */
    public static String classify(String text) {
        if (text == null) return "other";
        if (text.startsWith("DSH_REPAIR:missing:") || text.contains(" is not a function") || text.contains(" is not defined")) {
            return "api";
        }
        if (text.contains("SyntaxError") || text.contains("Unexpected token")) {
            return "syntax";
        }
        if (text.contains("Failed to fetch") || text.contains("NetworkError") || text.contains("ERR_CONNECTION")) {
            return "network";
        }
        if (text.contains("DSH_REPAIR:error:") || text.contains("DSH_REPAIR:rejection:")) {
            return "runtime";
        }
        return "other";
    }

    /** 从上报/错误文本提取 API 名。 */
    public static String missingApi(String text) {
        if (text == null) return "";
        String prefix = "DSH_REPAIR:missing:";
        if (text.startsWith(prefix)) {
            return text.substring(prefix.length()).trim();
        }
        int idx = text.indexOf(" is not a function");
        if (idx < 0) idx = text.indexOf(" is not defined");
        if (idx < 0) return "";
        int start = idx;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '$') {
                start--;
            } else {
                break;
            }
        }
        return text.substring(start, idx).trim();
    }
}
