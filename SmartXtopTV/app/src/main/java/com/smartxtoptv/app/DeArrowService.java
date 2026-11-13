package com.smartxtoptv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeArrowService {

    public interface TitleCallback {
        void onCleanTitle(String cleanTitle);
    }

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void fetchCleanTitle(String videoId, TitleCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String urlStr = "https://sponsor.ajay.app/api/branding?videoID=" + URLEncoder.encode(videoId, "UTF-8");
                conn = AppConfig.openSecureConnection(urlStr);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray titles = root.optJSONArray("titles");
                    if (titles != null && titles.length() > 0) {
                        JSONObject first = titles.optJSONObject(0);
                        if (first != null && (first.optInt("votes", 0) >= 0 || first.optBoolean("locked", false))) {
                            String t = first.optString("title", "").replace(">", "").trim();
                            if (!t.isEmpty()) {
                                callback.onCleanTitle(t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
