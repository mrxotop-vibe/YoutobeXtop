package com.smartxtoptv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HistoryManager {

    private static final String PREFS = "smartxtoptv_history";
    private static final String KEY_ITEMS = "items_json";
    private static final int MAX_ITEMS = 45;

    private final SharedPreferences prefs;

    public HistoryManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void addWatched(VideoItem item) {
        if (item == null || item.getId().isEmpty()) return;
        List<VideoItem> list = getHistory();
        JSONArray arr = new JSONArray();
        try {
            arr.put(toJson(item));
            for (VideoItem old : list) {
                if (old.getId().equals(item.getId())) continue;
                arr.put(toJson(old));
                if (arr.length() >= MAX_ITEMS) break;
            }
            prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public synchronized List<VideoItem> getHistory() {
        List<VideoItem> out = new ArrayList<>();
        try {
            String raw = prefs.getString(KEY_ITEMS, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new VideoItem(
                        o.optString("id", ""),
                        o.optString("title", "Video"),
                        o.optString("uploader", "YouTube"),
                        o.optString("thumb", ""),
                        o.optString("duration", "")
                ));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_ITEMS).apply();
    }

    private static JSONObject toJson(VideoItem item) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", item.getId());
        o.put("title", item.getTitle());
        o.put("uploader", item.getUploader());
        o.put("thumb", item.getThumbnailUrl());
        o.put("duration", item.getDuration());
        return o;
    }
}
