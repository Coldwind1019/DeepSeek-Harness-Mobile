package com.deh.mobile;

import android.content.Context;

/** Compatibility shim for the ADB protocol classes (App.ctx()). */
public final class App {
    private static Context sCtx;

    private App() {
    }

    public static void init(Context context) {
        sCtx = context.getApplicationContext();
    }

    public static Context ctx() {
        return sCtx;
    }
}
