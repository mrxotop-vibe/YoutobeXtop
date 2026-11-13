package com.smartxtoptv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class AuthManager {

    private static final String PREFS_NAME = "smartxtoptv_oauth2";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_CLIENT_ID = "oauth_client_id";
    private static final String KEY_CLIENT_SECRET = "oauth_client_secret";
    private static final String KEY_CUSTOM_CLIENT_ID = "custom_client_id";
    private static final String KEY_CUSTOM_CLIENT_SECRET = "custom_client_secret";
    private static final String KEY_VISITOR_DATA = "visitor_data";

    private final SharedPreferences prefs;

    public AuthManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveOAuthTokens(String accessToken, String refreshToken, long expiresInSec,
                                String clientId, String clientSecret) {
        long expiresAt = System.currentTimeMillis() + Math.max(60L, expiresInSec - 60L) * 1000L;
        SharedPreferences.Editor ed = prefs.edit();
        if (accessToken != null && !accessToken.isEmpty()) ed.putString(KEY_ACCESS_TOKEN, accessToken);
        if (refreshToken != null && !refreshToken.isEmpty()) ed.putString(KEY_REFRESH_TOKEN, refreshToken);
        ed.putLong(KEY_EXPIRES_AT, expiresAt);
        if (clientId != null && !clientId.isEmpty()) ed.putString(KEY_CLIENT_ID, clientId);
        if (clientSecret != null && !clientSecret.isEmpty()) ed.putString(KEY_CLIENT_SECRET, clientSecret);
        ed.apply();
    }

    public void saveCustomClient(String clientId, String clientSecret) {
        prefs.edit()
                .putString(KEY_CUSTOM_CLIENT_ID, clientId != null ? clientId.trim() : "")
                .putString(KEY_CUSTOM_CLIENT_SECRET, clientSecret != null ? clientSecret.trim() : "")
                .apply();
    }

    public String getCustomClientId() {
        return prefs.getString(KEY_CUSTOM_CLIENT_ID, "");
    }

    public String getCustomClientSecret() {
        return prefs.getString(KEY_CUSTOM_CLIENT_SECRET, "");
    }

    public void clearSession() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_EXPIRES_AT)
                .remove(KEY_CLIENT_ID)
                .remove(KEY_CLIENT_SECRET)
                .apply();
    }

    public boolean isLoggedIn() {
        return !prefs.getString(KEY_ACCESS_TOKEN, "").isEmpty()
                || !prefs.getString(KEY_REFRESH_TOKEN, "").isEmpty();
    }

    public synchronized String getValidAccessToken() {
        String access = prefs.getString(KEY_ACCESS_TOKEN, "");
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L);
        if (!access.isEmpty() && System.currentTimeMillis() < expiresAt) {
            return access;
        }
        String refresh = prefs.getString(KEY_REFRESH_TOKEN, "");
        String clientId = prefs.getString(KEY_CLIENT_ID, "");
        String clientSecret = prefs.getString(KEY_CLIENT_SECRET, "");
        if (refresh.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) {
            return access;
        }
        HttpURLConnection conn = null;
        try {
            String form = "client_id=" + URLEncoder.encode(clientId, "UTF-8")
                    + "&client_secret=" + URLEncoder.encode(clientSecret, "UTF-8")
                    + "&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
                    + "&grant_type=refresh_token";
            conn = AppConfig.openSecureConnection("https://oauth2.googleapis.com/token");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                if (code >= 200 && code < 300) {
                    JSONObject obj = new JSONObject(sb.toString());
                    String newAccess = obj.optString("access_token", "");
                    long exp = obj.optLong("expires_in", 3600L);
                    if (!newAccess.isEmpty()) {
                        saveOAuthTokens(newAccess, refresh, exp, clientId, clientSecret);
                        return newAccess;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return access;
    }

    public String getVisitorData() {
        return prefs.getString(KEY_VISITOR_DATA, "");
    }

    public void saveVisitorData(String visitorData) {
        if (visitorData != null && !visitorData.isEmpty()) {
            prefs.edit().putString(KEY_VISITOR_DATA, visitorData).apply();
        }
    }
}
