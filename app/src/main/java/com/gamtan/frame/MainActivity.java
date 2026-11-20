package com.gamtan.frame;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.Toast;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.Activity;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.security.MessageDigest;

import javax.net.ssl.HttpsURLConnection;

/**
 * Full-screen kiosk that displays the Gamtan photo frame.
 *
 * The frame UI (assets/frame.html) is unchanged in look/behaviour, but its two
 * kinds of network access to Supabase are handled by this app natively, using a
 * certificate bundle baked into the APK:
 *   - the photo-list request (POST rpc/frame_manifest) -> bridge Android.fetchManifest()
 *   - image + background GETs (storage/object/public) -> shouldInterceptRequest()
 * This is why the app works on old Android 6 devices with no manual WebView
 * update and no manual certificate install.
 */
public class MainActivity extends Activity {

    // Same values as the frame web page (public "publishable" key — safe to ship).
    static final String SB_URL = "https://sdjhvtljrzkvniwdtwir.supabase.co";
    static final String SB_KEY = "sb_publishable_qoIwl7-UfHB2L3LsqnCu-g_t-_6B53R";

    private WebView web;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoWifiOpened = false;
    private int offlineTicks = 0;
    private volatile boolean isResumed = false;
    private ConnectivityManager.NetworkCallback netCb;
    private File imgCacheDir;
    private PowerManager.WakeLock cpuLock;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        prefs = getSharedPreferences("gamtan", Context.MODE_PRIVATE);

        String token = prefs.getString("token", "");
        if (TextUtils.isEmpty(token)) {
            // First run / no token yet -> ask for it.
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        applyOrientation();

        imgCacheDir = new File(getCacheDir(), "img");
        if (!imgCacheDir.exists()) imgCacheDir.mkdirs();
        pruneCache(300L * 1024 * 1024);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new Client());
        web.addJavascriptInterface(new Bridge(), "Android");

        web.loadUrl("file:///android_asset/frame.html?t=" + Uri.encode(token));

        goImmersive();
        scheduleAutoWifi();
        registerNet();

        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        // If the nav/status bar slid back into view (e.g. a swipe
                        // from the edge), re-hide it shortly so the kiosk stays full.
                        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                            handler.postDelayed(new Runnable() {
                                @Override public void run() { goImmersive(); }
                            }, 2000);
                        }
                    }
                });
    }

    /**
     * If the frame still has no internet a short while after starting, open the
     * Wi-Fi settings automatically so the person setting it up (at the parents'
     * home) can join the network. Fires at most ONCE per launch, so a later
     * transient drop does not keep popping settings in the parents' face.
     */
    private void scheduleAutoWifi() {
        handler.postDelayed(autoWifiChecker, 8000);
    }

    // Checks connectivity repeatedly. As soon as the device is online it stops
    // (never opens settings). Only if it stays offline for ~40s straight does it
    // open Wi-Fi settings, and only once per launch.
    private final Runnable autoWifiChecker = new Runnable() {
        @Override
        public void run() {
            if (autoWifiOpened) return;
            if (isOnline()) { offlineTicks = 0; return; }
            offlineTicks++;
            if (offlineTicks >= 5) {
                autoWifiOpened = true;
                openWifiSettingsInternal();
                return;
            }
            handler.postDelayed(this, 8000);
        }
    };

    private boolean isOnline() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void openWifiSettingsInternal() {
        // Prefer the lightweight slide-up Wi-Fi panel (Android 10+).
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                return;
            } catch (Exception ignore) {}
        }
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignore) {}
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (netCb != null) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCb);
            } catch (Exception ignore) {}
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isResumed = true;
        goImmersive();
        // Returning to the frame (e.g. after joining Wi-Fi) -> refetch right away.
        if (web != null) {
            try {
                web.evaluateJavascript("window.__poke && window.__poke()", null);
            } catch (Exception ignore) {}
        }
    }

    @Override
    protected void onPause() {
        isResumed = false;
        super.onPause();
    }

    // Wi-Fi / Internet just came up. Refresh, and if the frame is in the
    // background (e.g. the person is in the Wi-Fi settings panel), bring it back
    // to the front by itself so photos resume without anyone touching it.
    private void onNetworkUp() {
        if (web != null) {
            try {
                web.evaluateJavascript("window.__poke && window.__poke()", null);
            } catch (Exception ignore) {}
        }
        if (!isResumed) {
            try {
                Intent i = new Intent(MainActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable ignore) {}
        }
    }

    private void registerNet() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handler.post(new Runnable() {
                        @Override public void run() { onNetworkUp(); }
                    });
                }
            };
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, netCb);
        } catch (Throwable ignore) {}
    }

    private void applyOrientation() {
        String o = prefs.getString("orient", "sensor");
        int mode;
        if ("portrait".equals(o)) mode = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        else if ("portrait_rev".equals(o)) mode = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        else if ("landscape".equals(o)) mode = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        else if ("landscape_rev".equals(o)) mode = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        else mode = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        try {
            setRequestedOrientation(mode);
        } catch (Exception ignore) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    private void goImmersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // Block the back key so a parent cannot leave the frame.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------------
    // WebView client: serve Supabase GETs (images / css backgrounds) natively
    // ---------------------------------------------------------------------
    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                String url = request.getUrl().toString();
                String method = request.getMethod();
                if (url.contains("supabase.co") && ("GET".equalsIgnoreCase(method))) {
                    return fetchResource(url);
                }
            } catch (Throwable ignore) {}
            return null;
        }
    }

    private WebResourceResponse fetchResource(String url) {
        // 1) Already downloaded before? Serve it from the device, no network.
        File cf = cacheFileFor(url);
        WebResourceResponse cached = cachedResponse(cf, url);
        if (cached != null) return cached;

        HttpsURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpsURLConnection) u.openConnection();
            c.setSSLSocketFactory(SslKit.factory(getApplicationContext()));
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            String ct = c.getContentType();
            String mime = (ct != null && ct.length() > 0) ? ct.split(";")[0].trim() : guessMime(url);
            InputStream in = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
            byte[] data = readAll(in);

            // 2) Fresh download succeeded -> keep a copy on the device for next time.
            if (code >= 200 && code < 400 && data.length > 0) {
                writeCache(cf, data);
            }

            WebResourceResponse r = new WebResourceResponse(mime, "", new ByteArrayInputStream(data));
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(h);
            try {
                r.setStatusCodeAndReasonPhrase(code >= 200 && code < 400 ? 200 : 404,
                        code >= 200 && code < 400 ? "OK" : "ERR");
            } catch (Throwable ignore) {}
            return r;
        } catch (Throwable e) {
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------------
    // JS bridge exposed to the frame page as "Android"
    // ---------------------------------------------------------------------
    private final class Bridge {

        // Fetch the photo list (POST with body — cannot be done via interception).
        @JavascriptInterface
        public void fetchManifest(final String token) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String text = doManifest(token);
                    if (web == null) return;
                    web.post(new Runnable() {
                        @Override
                        public void run() {
                            if (web != null) {
                                web.evaluateJavascript(
                                        "window.__manifest && window.__manifest(" + JSONObject.quote(text) + ")",
                                        null);
                            }
                        }
                    });
                }
            }).start();
        }

        // Night / "screen off" from the phone: turn the backlight fully off while
        // the app keeps running (so it can turn itself back on by schedule).
        @JavascriptInterface
        public void setScreenOff(final boolean off) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.screenBrightness = off
                                ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                        getWindow().setAttributes(lp);
                    } catch (Exception ignore) {}
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm == null) return;
                    if (off) {
                        // Keep the CPU (and the page's 30s check) running, but let the
                        // display really go to sleep: backlight fully off.
                        try {
                            if (cpuLock == null) {
                                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gamtan:cpu");
                                cpuLock.setReferenceCounted(false);
                            }
                            if (!cpuLock.isHeld()) cpuLock.acquire();
                        } catch (Exception ignore) {}
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        // Wake the display back up (works even if it already slept).
                        try {
                            @SuppressWarnings("deprecation")
                            PowerManager.WakeLock w = pm.newWakeLock(
                                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                            | PowerManager.ON_AFTER_RELEASE, "gamtan:wake");
                            w.acquire(5000);
                        } catch (Exception ignore) {}
                        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (Exception ignore) {}
                    }
                }
            });
        }

        @JavascriptInterface
        public void openWifiSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { openWifiSettingsInternal(); }
            });
        }

        @JavascriptInterface
        public void openSetup() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startActivity(new Intent(MainActivity.this, SetupActivity.class));
                }
            });
        }

        // Escape hatch (PIN-protected): leave the kiosk straight to the real Android
        // home screen, so it works even without a PC. The PIN never travels to JS.
        @JavascriptInterface
        public void exitToHome(final String pin) {
            final String saved = prefs.getString("admin_pin", "1234");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (saved == null || !saved.equals(pin)) {
                        Toast.makeText(MainActivity.this, "PIN이 올바르지 않습니다", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 1) Open the "Home app" chooser in Settings. Changing the
                    //    default launcher there (then rebooting) is the reliable
                    //    way out of the kiosk for updates — a plain Home jump just
                    //    gets pulled back, because this app is the default home.
                    try {
                        startActivity(new Intent("android.settings.HOME_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    } catch (Exception ignore) {}
                    // 2) Fallback: launch a *different* home launcher directly.
                    try {
                        PackageManager pm = getPackageManager();
                        Intent home = new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
                        String me = getPackageName();
                        if (list != null) {
                            for (ResolveInfo ri : list) {
                                if (ri == null || ri.activityInfo == null) continue;
                                String pkg = ri.activityInfo.packageName;
                                if (pkg != null && !pkg.equals(me) && !pkg.equals("android")) {
                                    Intent i = new Intent(Intent.ACTION_MAIN);
                                    i.addCategory(Intent.CATEGORY_HOME);
                                    i.setClassName(pkg, ri.activityInfo.name);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(i);
                                    return;
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                    // 3) Last resort: full Android settings.
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignore) {}
                }
            });
        }

        @JavascriptInterface
        public String getInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("label", prefs.getString("label", ""));
                o.put("phone", prefs.getString("phone", ""));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    private String doManifest(String token) {
        HttpsURLConnection c = null;
        try {
            URL u = new URL(SB_URL + "/rest/v1/rpc/frame_manifest");
            c = (HttpsURLConnection) u.openConnection();
            c.setSSLSocketFactory(SslKit.factory(getApplicationContext()));
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("apikey", SB_KEY);
            c.setRequestProperty("Authorization", "Bearer " + SB_KEY);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");

            byte[] body = ("{\"p_token\":" + JSONObject.quote(token) + "}").getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                return new String(readAll(c.getInputStream()), "UTF-8");
            }
            return "__NETFAIL__";
        } catch (Throwable e) {
            return "__NETFAIL__";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------------
    // Image disk cache: a photo is downloaded once, then served from the
    // device on every later rotation. Without this the frame re-downloads
    // every photo on every loop, burning Supabase bandwidth 24/7.
    // ---------------------------------------------------------------------
    private File cacheFileFor(String url) {
        String key;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dg = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : dg) sb.append(String.format("%02x", b));
            key = sb.toString();
        } catch (Exception e) {
            key = Integer.toHexString(url.hashCode());
        }
        return new File(imgCacheDir, key);
    }

    private WebResourceResponse cachedResponse(File f, String url) {
        try {
            if (imgCacheDir == null) return null;
            byte[] data = readFile(f);
            if (data == null || data.length == 0) return null;
            WebResourceResponse r = new WebResourceResponse(
                    guessMime(url), "", new ByteArrayInputStream(data));
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(h);
            try { r.setStatusCodeAndReasonPhrase(200, "OK"); } catch (Throwable ignore) {}
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    private void writeCache(File f, byte[] data) {
        if (imgCacheDir == null || data == null || data.length == 0) return;
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(data);
            os.flush();
            os.close();
            os = null;
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable e) {
            try { if (tmp.exists()) tmp.delete(); } catch (Exception ignore) {}
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignore) {}
        }
    }

    private static byte[] readFile(File f) {
        if (f == null || !f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) {}
        }
    }

    // Keep the cache from growing without bound if photos change often over the
    // frame's life. 150 photos is ~60MB, so 300MB is generous headroom.
    private void pruneCache(final long capBytes) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (imgCacheDir == null) return;
                    File[] fs = imgCacheDir.listFiles();
                    if (fs == null) return;
                    long total = 0;
                    for (File f : fs) total += f.length();
                    if (total <= capBytes) return;
                    java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            long d = a.lastModified() - b.lastModified();
                            return d < 0 ? -1 : (d > 0 ? 1 : 0);
                        }
                    });
                    for (File f : fs) {
                        if (total <= capBytes) break;
                        long len = f.length();
                        if (f.delete()) total -= len;
                    }
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static String guessMime(String url) {
        String l = url.toLowerCase();
        if (l.contains(".png")) return "image/png";
        if (l.contains(".webp")) return "image/webp";
        if (l.contains(".gif")) return "image/gif";
        if (l.contains(".jpg") || l.contains(".jpeg")) return "image/jpeg";
        if (l.contains(".json")) return "application/json";
        return "application/octet-stream";
    }
}
