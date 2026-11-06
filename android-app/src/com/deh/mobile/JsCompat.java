package com.deh.mobile;

/**
 * 自适应 JS 兼容层：把现代 JS API 补丁分成可复用的片段。
 * 页面加载时全量注入（幂等），运行时捕获 console 错误后再注入一次，
 * 从而对旧 WebView（Chromium 77 等）自适应，不需要用户手动升级 WebView。
 */
public final class JsCompat {
    private JsCompat() {
    }

    /** Promise.withResolvers（ES2024）。 */
    public static final String P_WITH_RESOLVERS =
            "if(typeof Promise!=='undefined'&&!Promise.withResolvers){Promise.withResolvers=function(){var r,j;var p=new Promise(function(a,b){r=a;j=b;});return{promise:p,resolve:r,reject:j};};}";

    /** Object.hasOwn（ES2022）。 */
    public static final String P_HAS_OWN =
            "if(!Object.hasOwn){Object.hasOwn=function(o,p){return Object.prototype.hasOwnProperty.call(o,p);};}";

    /** Array.prototype.at（ES2022）。 */
    public static final String P_ARRAY_AT =
            "if(!Array.prototype.at){Array.prototype.at=function(i){var n=this.length>>>0;var k=Math.trunc(i)||0;if(k<0)k+=n;return k<0||k>=n?undefined:this[k];};}";

    /** Array.prototype.flat / flatMap（ES2019）。 */
    public static final String P_ARRAY_FLAT =
            "if(!Array.prototype.flat){Array.prototype.flat=function(d){d=d===undefined?1:d;var out=[];for(var i=0;i<this.length;i++){if(Array.isArray(this[i])&&d>0){out=out.concat(this[i].flat(d-1));}else{out.push(this[i]);}}return out;};}"
            + "if(!Array.prototype.flatMap){Array.prototype.flatMap=function(f){var out=[];for(var i=0;i<this.length;i++){var r=f(this[i],i,this);if(Array.isArray(r)){for(var j=0;j<r.length;j++)out.push(r[j]);}else{out.push(r);}}return out;};}";

    /** String.prototype.replaceAll（ES2021）。 */
    public static final String P_REPLACE_ALL =
            "if(!String.prototype.replaceAll){String.prototype.replaceAll=function(a,b){return this.split(a).join(b);};}";

    /** Object.fromEntries（ES2019）。 */
    public static final String P_FROM_ENTRIES =
            "if(!Object.fromEntries){Object.fromEntries=function(it){var o={};if(it&&it.forEach){it.forEach(function(kv){o[kv[0]]=kv[1];});}return o;};}";

    /** Object.entries / values（ES2017）。 */
    public static final String P_OBJECT_ENTRIES =
            "if(!Object.entries){Object.entries=function(o){var r=[];for(var k in o){if(Object.prototype.hasOwnProperty.call(o,k))r.push([k,o[k]]);}return r;};}"
            + "if(!Object.values){Object.values=function(o){var r=[];for(var k in o){if(Object.prototype.hasOwnProperty.call(o,k))r.push(o[k]);}return r;};}";

    /** structuredClone（ES2021）。 */
    public static final String P_STRUCTURED_CLONE =
            "if(typeof structuredClone!=='function'){structuredClone=function(v){return JSON.parse(JSON.stringify(v));};}";

    /** queueMicrotask（ES2020 浏览器 API）。 */
    public static final String P_QUEUE_MICROTASK =
            "if(typeof queueMicrotask!=='function'){queueMicrotask=function(f){Promise.resolve().then(f);};}";

    /** globalThis（ES2020）。 */
    public static final String P_GLOBAL_THIS =
            "if(typeof globalThis==='undefined'){var globalThis=window;}";

    /** String.padStart / padEnd（ES2017）。 */
    public static final String P_PAD =
            "if(!String.prototype.padStart){String.prototype.padStart=function(l,c){l=l||0;c=c===undefined?' ':String(c);var s=String(this);while(s.length<l)s=c+s;return s;};}"
            + "if(!String.prototype.padEnd){String.prototype.padEnd=function(l,c){l=l||0;c=c===undefined?' ':String(c);var s=String(this);while(s.length<l)s=s+c;return s;};}";

    /** Promise.finally（ES2018）。 */
    public static final String P_PROMISE_FINALLY =
            "if(typeof Promise!=='undefined'&&!Promise.prototype.finally){Promise.prototype.finally=function(f){var p=this;return p.then(function(v){return Promise.resolve(f()).then(function(){return v;});},function(e){return Promise.resolve(f()).then(function(){throw e;});});};}";

    /** Array ES2023：toSorted / toReversed / toSpliced / with（Chrome 110+）。 */
    public static final String P_ARRAY_ES2023 =
            "if(!Array.prototype.toSorted){Array.prototype.toSorted=function(c){return this.slice().sort(c);};}"
            + "if(!Array.prototype.toReversed){Array.prototype.toReversed=function(){return this.slice().reverse();};}"
            + "if(!Array.prototype.toSpliced){Array.prototype.toSpliced=function(s,d){var a=this.slice();Array.prototype.splice.apply(a,arguments);return a;};}"
            + "if(!Array.prototype.with){Array.prototype.with=function(i,v){var a=this.slice();a[i<0?a.length+i:i]=v;return a;};}";

    /** AbortSignal.any / throwIfAborted（Chrome 116+/100+）。 */
    public static final String P_ABORT_SIGNAL =
            "if(typeof AbortSignal!=='undefined'&&!AbortSignal.any){AbortSignal.any=function(signals){var c=new AbortController();for(var i=0;i<signals.length;i++){(function(s){if(!s)return;if(s.aborted){try{c.abort(s.reason);}catch(e){c.abort();}}else{s.addEventListener('abort',function(){try{c.abort(s.reason);}catch(e){c.abort();}});}})(signals[i]);}return c.signal;};}"
            + "if(typeof AbortSignal!=='undefined'&&AbortSignal.prototype&&!AbortSignal.prototype.throwIfAborted){AbortSignal.prototype.throwIfAborted=function(){if(this.aborted){var r=this.reason;throw (r!==undefined?r:new Error('The operation was aborted'));}};}";

    /** findLast / findLastIndex（ES2023）。 */
    public static final String P_FIND_LAST =
            "if(!Array.prototype.findLast){Array.prototype.findLast=function(f){for(var i=this.length-1;i>=0;i--){if(f(this[i],i,this))return this[i];}return undefined;};}"
            + "if(!Array.prototype.findLastIndex){Array.prototype.findLastIndex=function(f){for(var i=this.length-1;i>=0;i--){if(f(this[i],i,this))return i;}return -1;};}";


    /** 探测当前 WebView 的语法与 API 能力（返回 JSON）。 */
    public static final String PROBE_JS =
            "(function(){var r={};"
            + "r.staticBlock=(function(){try{new Function('class A{static{}}');return true;}catch(e){return false;}})();"
            + "r.classFields=(function(){try{new Function('class A{x=1;}');return true;}catch(e){return false;}})();"
            + "r.nullishAssign=(function(){try{new Function('let a=1;a??=2;');return true;}catch(e){return false;}})();"
            + "r.withResolvers=(typeof Promise!=='undefined'&&!!Promise.withResolvers);"
            + "r.hasOwn=(typeof Object.hasOwn==='function');"
            + "r.arrayAt=(typeof Array.prototype.at==='function');"
            + "r.replaceAll=(typeof String.prototype.replaceAll==='function');"
            + "r.structuredClone=(typeof structuredClone==='function');"
            + "r.queueMicrotask=(typeof queueMicrotask==='function');"
            + "r.letConst=(function(){try{new Function('let a=1;const b=2;');return true;}catch(e){return false;}})();"
            + "r.classSupport=(function(){try{new Function('class A{}');return true;}catch(e){return false;}})();"
            + "return JSON.stringify(r);})();";

    /** 全量（幂等，可重复注入）。 */
    public static final String ALL = "(function(){"
            + P_WITH_RESOLVERS
            + P_HAS_OWN
            + P_ARRAY_AT
            + P_ARRAY_FLAT
            + P_REPLACE_ALL
            + P_FROM_ENTRIES
            + P_OBJECT_ENTRIES
            + P_STRUCTURED_CLONE
            + P_QUEUE_MICROTASK
            + P_GLOBAL_THIS
            + P_PAD
            + P_PROMISE_FINALLY
            + P_FIND_LAST
            + P_ABORT_SIGNAL
            + P_ARRAY_ES2023
            + "})();";

    /** 探测结果画像。 */
    public static final class Profile {
        public boolean staticBlock;
        public boolean classFields;
        public boolean nullishAssign;
        public boolean withResolvers;
        public boolean hasOwn;
        public boolean arrayAt;
        public boolean replaceAll;
        public boolean structuredClone;
        public boolean queueMicrotask;
        public boolean letConst;
        public boolean classSupport;

        /** 语法层面是否需要转译前端（缺任一现代语法 → 需要）。 */
        public boolean needsTranspile() {
            return !(staticBlock && classFields && nullishAssign);
        }

        /** 连 class 字段都不支持但支持 class/let（Chromium <72）→ 需要 ES2015 降级版。 */
        public boolean needsEs2015() {
            return !classFields && classSupport && letConst;
        }

        /** 太旧（连 class/let 都不支持，Chromium <49）→ 无法自动修复。 */
        public boolean tooOld() {
            return !(classSupport && letConst);
        }

        @Override
        public String toString() {
            return "staticBlock=" + staticBlock + " classFields=" + classFields
                    + " nullish=" + nullishAssign + " withResolvers=" + withResolvers
                    + " hasOwn=" + hasOwn + " arrayAt=" + arrayAt
                    + " replaceAll=" + replaceAll + " structuredClone=" + structuredClone
                    + " queueMicrotask=" + queueMicrotask
                    + " letConst=" + letConst + " classSupport=" + classSupport
                    + " => " + (tooOld() ? "过旧(需外部方案)" : needsEs2015() ? "兼容模式(ES2015+polyfill)" : needsTranspile() ? "兼容模式(ES2017+polyfill)" : "原生模式");
        }
    }

    /** 全局探测结果（每次启动重新探测）。 */
    public static volatile Profile PROFILE;

    /** 解析探测 JSON。 */
    public static Profile parseProfile(String json) {
        Profile p = new Profile();
        if (json == null) return p;
        p.staticBlock = json.contains("\"staticBlock\":true");
        p.classFields = json.contains("\"classFields\":true");
        p.nullishAssign = json.contains("\"nullishAssign\":true");
        p.withResolvers = json.contains("\"withResolvers\":true");
        p.hasOwn = json.contains("\"hasOwn\":true");
        p.arrayAt = json.contains("\"arrayAt\":true");
        p.replaceAll = json.contains("\"replaceAll\":true");
        p.structuredClone = json.contains("\"structuredClone\":true");
        p.queueMicrotask = json.contains("\"queueMicrotask\":true");
        p.letConst = json.contains("\"letConst\":true");
        p.classSupport = json.contains("\"classSupport\":true");
        return p;
    }

    /** 内置兼容层是否已覆盖该 API（覆盖则不需要 AI 兜底）。 */
    public static boolean isBuiltInApi(String api) {
        if (api == null) return false;
        String a = api.trim();
        String[] known = {
                "Promise.withResolvers", "Object.hasOwn", "Array.prototype.at", "Array.at",
                "Array.prototype.flat", "Array.prototype.flatMap", "String.prototype.replaceAll",
                "Object.fromEntries", "Object.entries", "Object.values", "structuredClone",
                "queueMicrotask", "globalThis", "String.prototype.padStart", "String.prototype.padEnd",
                "Promise.prototype.finally", "Array.prototype.findLast", "Array.prototype.findLastIndex",
                "Array.prototype.toSorted", "Array.prototype.toReversed", "Array.prototype.toSpliced",
                "Array.prototype.with", "AbortSignal.any", "AbortSignal.prototype.throwIfAborted",
                "rows.toSorted", "groups.toSorted"
        };
        for (String k : known) {
            if (k.equals(a) || a.endsWith("." + k) || k.endsWith("." + a)) return true;
        }
        return false;
    }

    /** 从 console 错误文本推断缺失的 API 名（用于日志）。 */
    public static String apiNameOf(String error) {
        if (error == null) return "";
        int idx = error.indexOf(" is not a function");
        if (idx < 0) idx = error.indexOf(" is not defined");
        if (idx < 0) return "";
        int start = idx;
        while (start > 0) {
            char c = error.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '$') {
                start--;
            } else {
                break;
            }
        }
        return error.substring(start, idx).trim();
    }
}
