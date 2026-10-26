package com.deh.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 前台服务：提升进程优先级，保证切到后台后 DSH 继续运行。 */
public class DshService extends Service {
    private static final String CHANNEL = "dsh_running";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "DeepSeek Harness", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持 DSH 在后台运行");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentTitle("DeepSeek Harness 运行中")
                .setContentText("点击回到 DSH")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true);
        Intent settingsIntent = new Intent(this, MainActivity.class);
        settingsIntent.setAction("com.deh.mobile.OPEN_SETTINGS");
        PendingIntent settingsPi = PendingIntent.getActivity(this, 1, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(android.R.drawable.ic_menu_preferences, "设置", settingsPi);
        startForeground(1001, builder.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
