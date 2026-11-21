package com.gamtan.frame;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * 자동 업데이트: 서버(Supabase 저장소 frame-app)에 올린 새 버전을 새벽에 혼자 받아 설치.
 * 기기 관리자(device owner) 앱이라 안드로이드 6에서 확인 창 없이 설치됨.
 * 서명 열쇠가 같은 APK만 설치되므로, 다른 파일이 올라와도 설치되지 않음.
 */
public final class Updater {

    static final String BASE = MainActivity.SB_URL + "/storage/v1/object/public/frame-app/";
    static final String ACTION_RESULT = "com.gamtan.frame.INSTALL_RESULT";
    private static volatile boolean running = false;

    private Updater() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gamtan", Context.MODE_PRIVATE);
    }

    static void log(Context c, String msg) {
        try {
            String t = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            String old = prefs(c).getString("upd_log", "");
            String s = t + " " + msg + "\n" + old;
            if (s.length() > 1500) s = s.substring(0, 1500);
            prefs(c).edit().putString("upd_log", s).apply();
        } catch (Throwable ignore) {}
    }

    static int myCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 새벽 2~5시에만 설치 (force면 시간 무시). */
    static void checkAsync(final Context ctx, final boolean force) {
        if (running) return;
        running = true;
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(c, force);
                } catch (Throwable e) {
                    log(c, "error " + e.getClass().getSimpleName());
                } finally {
                    running = false;
                }
            }
        }).start();
    }

    private static void check(Context c, boolean force) throws Exception {
        if (!force) {
            // 하루 한 번, 새벽 2~5시 사이 액자마다 정해진 시각에만 서버 확인 (100대가 한꺼번에 몰리지 않게)
            Calendar now = Calendar.getInstance();
            int min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int slot = 120 + Math.abs(prefs(c).getString("token", "x").hashCode() % 170);
            if (min < slot || min >= 300) return;
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(now.getTime());
            if (today.equals(prefs(c).getString("upd_day", ""))) return;
            prefs(c).edit().putString("upd_day", today).apply();
        }
        String ch = prefs(c).getString("upd_channel", "latest");
        if (!"beta".equals(ch)) ch = "latest";
        byte[] js = get(c, BASE + ch + ".json?t=" + System.currentTimeMillis(), 64 * 1024);
        if (js == null) { log(c, "no json"); return; }
        JSONObject o = new JSONObject(new String(js, "UTF-8"));
        int code = o.optInt("code", 0);
        String apk = o.optString("apk", "");
        long size = o.optLong("size", 0);
        int mine = myCode(c);
        if (code <= mine || apk.length() == 0) {
            if (force) log(c, "up to date " + mine + " (" + ch + " " + code + ")");
            return;
        }
        // 같은 버전에서 3번 실패하면 더 시도 안 함 (새 번호가 올라오면 다시 시도)
        SharedPreferences p = prefs(c);
        if (p.getInt("upd_fail_code", 0) == code && p.getInt("upd_fail_n", 0) >= 3 && !force) return;

        log(c, "download " + mine + " -> " + code);
        File f = new File(c.getFilesDir(), "update.apk");
        if (!download(c, BASE + apk, f) || (size > 0 && f.length() != size)) {
            fail(c, code, "download bad " + f.length());
            return;
        }
        p.edit().putInt("upd_try_code", code).apply();
        install(c, f, code);
    }

    private static void fail(Context c, int code, String why) {
        SharedPreferences p = prefs(c);
        int n = p.getInt("upd_fail_code", 0) == code ? p.getInt("upd_fail_n", 0) + 1 : 1;
        p.edit().putInt("upd_fail_code", code).putInt("upd_fail_n", n).apply();
        log(c, "fail " + code + " " + why);
    }

    static void installResult(Context c, int status, String msg) {
        int code = prefs(c).getInt("upd_try_code", 0);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            log(c, "installed " + code);
        } else {
            fail(c, code, "install " + status + " " + (msg == null ? "" : msg));
        }
    }

    private static void install(Context c, File f, int code) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int sid = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(sid);
        InputStream in = new FileInputStream(f);
        OutputStream out = s.openWrite("gamtan", 0, f.length());
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        s.fsync(out);
        in.close();
        out.close();
        Intent i = new Intent(c, UpdateReceiver.class).setAction(ACTION_RESULT);
        PendingIntent pend = PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT);
        log(c, "install " + code);
        s.commit(pend.getIntentSender());
        s.close();
    }

    private static HttpsURLConnection open(Context c, String url) throws Exception {
        HttpsURLConnection k = (HttpsURLConnection) new URL(url).openConnection();
        k.setSSLSocketFactory(SslKit.factory(c));
        k.setConnectTimeout(15000);
        k.setReadTimeout(30000);
        k.setUseCaches(false);
        return k;
    }

    private static byte[] get(Context c, String url, int max) {
        HttpsURLConnection k = null;
        try {
            k = open(c, url);
            if (k.getResponseCode() != 200) return null;
            InputStream in = k.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) {
                bo.write(buf, 0, r);
                if (bo.size() > max) return null;
            }
            in.close();
            return bo.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            if (k != null) k.disconnect();
        }
    }

    private static boolean download(Context c, String url, File f) {
        HttpsURLConnection k = null;
        try {
            k = open(c, url);
            if (k.getResponseCode() != 200) return false;
            InputStream in = k.getInputStream();
            OutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[16384];
            int r;
            long total = 0;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                total += r;
                if (total > 50L * 1024 * 1024) { out.close(); in.close(); return false; }
            }
            out.close();
            in.close();
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (k != null) k.disconnect();
        }
    }
}
