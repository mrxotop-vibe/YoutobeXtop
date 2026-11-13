package com.smartxtoptv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SponsorBlockService {

    public static class Segment {
        public final long startMs;
        public final long endMs;
        public final String category;

        public Segment(long startMs, long endMs, String category) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.category = category;
        }
    }

    public interface SponsorCallback {
        void onSegmentsLoaded(List<Segment> segments);
    }

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void fetchSegments(String videoId, SponsorCallback callback) {
        executor.execute(() -> {
            List<Segment> segments = new ArrayList<>();
            HttpURLConnection conn = null;
            try {
                String cats = URLEncoder.encode(
                        "[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"music_offtopic\"]",
                        "UTF-8");
                String urlStr = "https://sponsor.ajay.app/api/skipSegments?videoID="
                        + URLEncoder.encode(videoId, "UTF-8") + "&categories=" + cats;
                conn = AppConfig.openSecureConnection(urlStr);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    JSONArray array = new JSONArray(sb.toString());
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        JSONArray segmentArr = obj.getJSONArray("segment");
                        double startSec = segmentArr.getDouble(0);
                        double endSec = segmentArr.getDouble(1);
                        String cat = obj.optString("category", "sponsor");
                        segments.add(new Segment((long) (startSec * 1000), (long) (endSec * 1000), cat));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            callback.onSegmentsLoaded(segments);
        });
    }
}
