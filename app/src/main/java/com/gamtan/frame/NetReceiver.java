package com.gamtan.frame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;

/**
 * 와이파이 설정 화면에서 연결되면 감탄 화면으로 되돌림.
 * 앱이 뒤에서 메모리 부족으로 꺼져 있어도 안드로이드 6은 이 알림을 보내 주므로 동작함.
 * 감탄 앱이 와이파이 설정을 열었을 때만(wifiPending) 작동하고, 감탄 화면이 다시 뜨면 꺼짐.
 */
public class NetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        try {
            final SharedPreferences prefs =
                    context.getSharedPreferences("gamtan", Context.MODE_PRIVATE);
            if (!prefs.getBoolean("wifiPending", false)) return;
            if (!isOnline(context)) return;
            final Context app = context.getApplicationContext();
            final PendingResult pr = goAsync();
            // 연결 직후 "연결됨" 표시를 잠깐 보여 주고 돌아감
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (!prefs.getBoolean("wifiPending", false)) return;
                        if (!isOnline(app)) return;
                        prefs.edit().putBoolean("wifiPending", false).apply();
                        Intent i = new Intent(app, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        app.startActivity(i);
                    } catch (Throwable ignore) {
                    } finally {
                        try { pr.finish(); } catch (Throwable ignore) {}
                    }
                }
            }, 2500);
        } catch (Throwable ignore) {}
    }

    static boolean isOnline(Context c) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable e) {
            return false;
        }
    }
}
