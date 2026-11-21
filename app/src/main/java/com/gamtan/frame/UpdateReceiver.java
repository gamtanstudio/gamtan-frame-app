package com.gamtan.frame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * 1) 설치 결과 받기  2) 업데이트 끝나면 액자 화면 다시 켜기
 * 3) PC(adb)에서 바로 업데이트 시험:
 *    am broadcast -a com.gamtan.frame.UPDATE_NOW -n com.gamtan.frame/.UpdateReceiver [--es ch beta]
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || i.getAction() == null) return;
        String a = i.getAction();
        if (Updater.ACTION_RESULT.equals(a)) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            Updater.installResult(c, st, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Updater.log(c, "replaced -> start");
            startFrame(c);
        } else if ("com.gamtan.frame.UPDATE_NOW".equals(a)) {
            String ch = i.getStringExtra("ch");
            if (ch != null) Updater.prefs(c).edit().putString("upd_channel", ch).apply();
            Updater.checkAsync(c, true);
        }
    }

    static void startFrame(Context c) {
        try {
            Intent m = new Intent(c, MainActivity.class);
            m.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(m);
        } catch (Throwable ignore) {}
    }
}
