package com.smartxtoptv.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;

import java.security.KeyStore;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class AppConfig {

    public static final int QUALITY_720P = 0;
    public static final int QUALITY_HLS_AUTO = 1;
    public static final int QUALITY_360P = 2;

    private static final String PREFS = "smartxtoptv_settings";
    private static final String KEY_QUALITY = "default_quality";
    private static final String KEY_SPONSORBLOCK = "sponsorblock_enabled";
    private static final String KEY_DEARROW = "dearrow_enabled";
    private static final String KEY_AUTONEXT = "autonext_enabled";

    private static SSLSocketFactory tls12Factory;

    private final SharedPreferences prefs;

    public AppConfig(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getDefaultQuality() {
        return prefs.getInt(KEY_QUALITY, QUALITY_720P);
    }

    public void setDefaultQuality(int q) {
        prefs.edit().putInt(KEY_QUALITY, q).apply();
    }

    public boolean isSponsorBlockEnabled() {
        return prefs.getBoolean(KEY_SPONSORBLOCK, true);
    }

    public void setSponsorBlockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SPONSORBLOCK, enabled).apply();
    }

    public boolean isDeArrowEnabled() {
        return prefs.getBoolean(KEY_DEARROW, true);
    }

    public void setDeArrowEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEARROW, enabled).apply();
    }

    public boolean isAutoNextEnabled() {
        return prefs.getBoolean(KEY_AUTONEXT, true);
    }

    public void setAutoNextEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTONEXT, enabled).apply();
    }

    public String getQualityLabel() {
        int q = getDefaultQuality();
        if (q == QUALITY_HLS_AUTO) return "Auto HLS (720p/1080p)";
        if (q == QUALITY_360P) return "360p MP4 (Tiết kiệm)";
        return "720p MP4 (Mượt 5.1)";
    }

    /**
     * Ensures TLSv1.2 is explicitly enabled on Android 5.0/5.1 (API 21/22) HttpsURLConnection.
     */
    public static HttpURLConnection openSecureConnection(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection && Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SSLSocketFactory sf = getTls12SocketFactory();
            if (sf != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(sf);
            }
        }
        return conn;
    }

    public static synchronized X509TrustManager getDefaultTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static synchronized SSLSocketFactory getTls12SocketFactory() {
        if (tls12Factory != null) return tls12Factory;
        try {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, null, null);
            final SSLSocketFactory base = sc.getSocketFactory();
            tls12Factory = new SSLSocketFactory() {
                @Override
                public String[] getDefaultCipherSuites() {
                    return base.getDefaultCipherSuites();
                }

                @Override
                public String[] getSupportedCipherSuites() {
                    return base.getSupportedCipherSuites();
                }

                @Override
                public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                    return patch(base.createSocket(s, host, port, autoClose));
                }

                @Override
                public Socket createSocket(String host, int port) throws IOException {
                    return patch(base.createSocket(host, port));
                }

                @Override
                public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
                    return patch(base.createSocket(host, port, localHost, localPort));
                }

                @Override
                public Socket createSocket(InetAddress host, int port) throws IOException {
                    return patch(base.createSocket(host, port));
                }

                @Override
                public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
                    return patch(base.createSocket(address, port, localAddress, localPort));
                }

                private Socket patch(Socket s) {
                    if (s instanceof SSLSocket) {
                        ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1"});
                    }
                    return s;
                }
            };
        } catch (Exception ignored) {
        }
        return tls12Factory;
    }
}
