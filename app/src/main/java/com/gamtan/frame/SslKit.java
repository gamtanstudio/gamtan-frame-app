package com.gamtan.frame;

import android.content.Context;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds an SSLSocketFactory that trusts BOTH the device's system CAs and the
 * bundled ISRG Root X1 / X2 certificates. This lets old Android (e.g. Android 6)
 * validate Supabase's Let's Encrypt certificate chain even when the device's own
 * trust store is too old — no manual certificate install needed.
 */
public final class SslKit {

    private static volatile SSLSocketFactory FACTORY;

    private SslKit() {}

    public static SSLSocketFactory factory(Context ctx) {
        if (FACTORY != null) return FACTORY;
        synchronized (SslKit.class) {
            if (FACTORY != null) return FACTORY;
            FACTORY = build(ctx.getApplicationContext());
            return FACTORY;
        }
    }

    private static SSLSocketFactory build(Context ctx) {
        try {
            final List<X509TrustManager> managers = new ArrayList<>();

            // 1) System default trust managers
            TrustManagerFactory sys = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            sys.init((KeyStore) null);
            for (TrustManager tm : sys.getTrustManagers()) {
                if (tm instanceof X509TrustManager) managers.add((X509TrustManager) tm);
            }

            // 2) Custom keystore holding the bundled ISRG roots
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String[] pems = {"isrg_x1.pem", "isrg_x2.pem"};
            int idx = 0;
            for (String p : pems) {
                InputStream in = null;
                try {
                    in = ctx.getAssets().open(p);
                    Certificate cert = cf.generateCertificate(in);
                    ks.setCertificateEntry("isrg" + (idx++), cert);
                } catch (Exception ignore) {
                    // a missing/unreadable pem just means we fall back to system trust
                } finally {
                    if (in != null) try { in.close(); } catch (Exception ignore) {}
                }
            }
            TrustManagerFactory custom = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            custom.init(ks);
            for (TrustManager tm : custom.getTrustManagers()) {
                if (tm instanceof X509TrustManager) managers.add((X509TrustManager) tm);
            }

            X509TrustManager combined = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    throw new CertificateException("client authentication not supported");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    CertificateException last = null;
                    for (X509TrustManager tm : managers) {
                        try {
                            tm.checkServerTrusted(chain, authType);
                            return; // trusted by at least one manager
                        } catch (CertificateException e) {
                            last = e;
                        }
                    }
                    if (last != null) throw last;
                    throw new CertificateException("no trust manager available");
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    List<X509Certificate> all = new ArrayList<>();
                    for (X509TrustManager tm : managers) {
                        X509Certificate[] a = tm.getAcceptedIssuers();
                        if (a != null) for (X509Certificate c : a) all.add(c);
                    }
                    return all.toArray(new X509Certificate[0]);
                }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{combined}, null);
            return sc.getSocketFactory();
        } catch (Exception e) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }
}
