package com.gamtan.frame;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        prefs = getSharedPreferences("gamtan", Context.MODE_PRIVATE);

        String token = prefs.getString("token", "");
        if (TextUtils.isEmpty(token)) {
            // First run / no token yet -> ask for it.
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

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
    }

    /**
     * If the frame still has no internet a short while after starting, open the
     * Wi-Fi settings automatically so the person setting it up (at the parents'
     * home) can join the network. Fires at most ONCE per launch, so a later
     * transient drop does not keep popping settings in the parents' face.
     */
    private void scheduleAutoWifi() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!autoWifiOpened && !isOnline()) {
                    autoWifiOpened = true;
                    openWifiSettingsInternal();
                }
            }
        }, 15000);
    }

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
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
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
