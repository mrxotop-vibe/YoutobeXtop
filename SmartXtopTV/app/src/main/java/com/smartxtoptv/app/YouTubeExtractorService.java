package com.smartxtoptv.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YouTubeExtractorService {

    private static final String YOUTUBE_BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String UA_TV =
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/94.0.4606.112 TV Safari/537.36";

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static class StreamCandidate {
        public final String videoUrl;
        public final String audioUrl;
        public final boolean isHls;
        public final String label;

        public StreamCandidate(String videoUrl, String audioUrl, boolean isHls, String label) {
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.isHls = isHls;
            this.label = label;
        }
    }

    public interface VideoListCallback {
        void onSuccess(List<VideoItem> videos);
        void onError(String message);
    }

    public interface StreamListCallback {
        void onSuccess(List<StreamCandidate> candidates, String title, String channel);
        void onError(String message);
    }

    public static String extractVideoId(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.matches("^[a-zA-Z0-9_-]{11}$")) return trimmed;
        Pattern p = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([a-zA-Z0-9_-]{11})");
        Matcher m = p.matcher(trimmed);
        return m.find() ? m.group(1) : null;
    }

    public static void fetchHomeFeed(Context context, VideoListCallback callback) {
        executor.execute(() -> {
            AuthManager auth = new AuthManager(context);
            String token = auth.getValidAccessToken();
            if (!token.isEmpty()) {
                List<VideoItem> authed = fetchAuthenticatedTvBrowse("FEwhat_to_watch", token);
                if (!authed.isEmpty()) {
                    callback.onSuccess(authed);
                    return;
                }
            }
            fetchKioskOrSearch("thịnh hành việt nam", callback);
        });
    }

    public static void fetchTrending(Context context, VideoListCallback callback) {
        executor.execute(() -> fetchKioskOrSearch("thịnh hành", callback));
    }

    public static void fetchSubscriptions(Context context, VideoListCallback callback) {
        executor.execute(() -> {
            AuthManager auth = new AuthManager(context);
            String token = auth.getValidAccessToken();
            if (token.isEmpty()) {
                callback.onError("Cần đăng nhập tài khoản để xem Kênh đăng ký");
                return;
            }
            List<VideoItem> subs = fetchAuthenticatedTvBrowse("FEsubscriptions", token);
            if (!subs.isEmpty()) {
                callback.onSuccess(subs);
            } else {
                callback.onError("Không lấy được Kênh đăng ký (token có thể đã hết hạn)");
            }
        });
    }

    public static void fetchMusic(Context context, VideoListCallback callback) {
        search(context, "nhạc trẻ remix mới nhất", callback);
    }

    public static void fetchGaming(Context context, VideoListCallback callback) {
        search(context, "liên quân mobile gaming", callback);
    }

    public static void search(Context context, String query, VideoListCallback callback) {
        executor.execute(() -> {
            try {
                StreamingService yt = ServiceList.YouTube;
                SearchInfo searchInfo = SearchInfo.getInfo(yt, yt.getSearchQHFactory().fromQuery(query));
                List<VideoItem> out = convertInfoItems(searchInfo.getRelatedItems());
                if (out.isEmpty()) {
                    callback.onError("Không tìm thấy video nào cho: " + query);
                } else {
                    callback.onSuccess(out);
                }
            } catch (Exception e) {
                callback.onError("Lỗi tìm kiếm NewPipe: " + e.getMessage());
            }
        });
    }

    private static void fetchKioskOrSearch(String fallbackQuery, VideoListCallback callback) {
        try {
            StreamingService yt = ServiceList.YouTube;
            KioskList kiosks = yt.getKioskList();
            String kioskUrl = kiosks.getDefaultKioskExtractor().getUrl();
            KioskInfo kioskInfo = KioskInfo.getInfo(yt, kioskUrl);
            List<VideoItem> out = convertInfoItems(kioskInfo.getRelatedItems());
            if (!out.isEmpty()) {
                callback.onSuccess(out);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            StreamingService yt = ServiceList.YouTube;
            SearchInfo searchInfo = SearchInfo.getInfo(yt, yt.getSearchQHFactory().fromQuery(fallbackQuery));
            List<VideoItem> out = convertInfoItems(searchInfo.getRelatedItems());
            if (!out.isEmpty()) {
                callback.onSuccess(out);
            } else {
                callback.onError("Không tải được danh sách video");
            }
        } catch (Exception e) {
            callback.onError("Lỗi tải YouTube: " + e.getMessage());
        }
    }

    public static void fetchStreams(Context context, String videoId, StreamListCallback callback) {
        executor.execute(() -> {
            try {
                AppConfig cfg = new AppConfig(context);
                int prefQuality = cfg.getDefaultQuality();

                StreamingService yt = ServiceList.YouTube;
                String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
                StreamInfo info = StreamInfo.getInfo(yt, watchUrl);

                String title = info.getName() != null ? info.getName() : "YouTube Video";
                String channel = info.getUploaderName() != null ? info.getUploaderName() : "YouTube";

                // Pick best Android 5.1 friendly AudioStream (M4A / AAC first, else highest bitrate)
                String bestAudioUrl = pickBestAudioUrl(info.getAudioStreams());

                List<StreamCandidate> candidates = new ArrayList<>();

                // Progressive combined MP4 streams (video + audio together)
                List<StreamCandidate> muxed = new ArrayList<>();
                if (info.getVideoStreams() != null) {
                    for (VideoStream vs : info.getVideoStreams()) {
                        String u = vs.getContent();
                        if (u == null || u.isEmpty()) continue;
                        String res = vs.getResolution() != null ? vs.getResolution() : "MP4";
                        muxed.add(new StreamCandidate(u, null, false, res + " (MP4)"));
                    }
                }

                // Adaptive video-only streams merged with audio via ExoPlayer MergingMediaSource (720p/1080p)
                List<StreamCandidate> dashPairs = new ArrayList<>();
                if (bestAudioUrl != null && !bestAudioUrl.isEmpty() && info.getVideoOnlyStreams() != null) {
                    for (VideoStream vs : info.getVideoOnlyStreams()) {
                        String u = vs.getContent();
                        if (u == null || u.isEmpty()) continue;
                        String res = vs.getResolution() != null ? vs.getResolution() : "HD";
                        String fmt = vs.getFormat() != null ? vs.getFormat().getName() : "DASH";
                        dashPairs.add(new StreamCandidate(u, bestAudioUrl, false, res + " (" + fmt + " + Audio)"));
                    }
                    // Sort highest/preferred resolution nicely: put H.264/MPEG_4 720p/1080p near top for Android 5.1
                    dashPairs.sort((a, b) -> scoreCandidate(b, prefQuality) - scoreCandidate(a, prefQuality));
                }

                String hlsUrl = info.getHlsUrl();
                StreamCandidate hlsCand = (hlsUrl != null && !hlsUrl.isEmpty())
                        ? new StreamCandidate(hlsUrl, null, true, "HLS Adaptive")
                        : null;

                if (prefQuality == AppConfig.QUALITY_HLS_AUTO) {
                    if (hlsCand != null) candidates.add(hlsCand);
                    candidates.addAll(dashPairs);
                    candidates.addAll(muxed);
                } else if (prefQuality == AppConfig.QUALITY_360P) {
                    // Put lower MP4 first for slow networks
                    candidates.addAll(muxed);
                    candidates.addAll(dashPairs);
                    if (hlsCand != null) candidates.add(hlsCand);
                } else {
                    // Default Android 5.1 smooth 720p: prefer 720p DASH H264 or 720p muxed, then others
                    candidates.addAll(dashPairs);
                    candidates.addAll(muxed);
                    if (hlsCand != null) candidates.add(hlsCand);
                    candidates.sort((a, b) -> scoreCandidate(b, prefQuality) - scoreCandidate(a, prefQuality));
                }

                if (candidates.isEmpty()) {
                    callback.onError("NewPipeExtractor không tìm thấy luồng phát cho video này.");
                } else {
                    callback.onSuccess(candidates, title, channel);
                }
            } catch (Exception e) {
                callback.onError("NewPipeExtractor lỗi: " + e.getMessage());
            }
        });
    }

    private static int scoreCandidate(StreamCandidate c, int prefQuality) {
        String lbl = c.label.toLowerCase(Locale.US);
        int score = 0;
        if (lbl.contains("mpeg_4") || lbl.contains("mp4")) score += 500; // Hardware decoded smoothly on Android 5.1
        int res = parseResolution(lbl);
        if (prefQuality == AppConfig.QUALITY_360P) {
            if (res == 360) score += 400;
            else if (res == 480) score += 300;
            else if (res == 720) score += 150;
        } else {
            if (res == 720) score += 450;
            else if (res == 1080) score += 380;
            else if (res == 480) score += 250;
            else if (res == 360) score += 180;
        }
        if (c.isHls && prefQuality == AppConfig.QUALITY_HLS_AUTO) score += 800;
        return score;
    }

    private static int parseResolution(String s) {
        Matcher m = Pattern.compile("(\\d{3,4})p").matcher(s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static String pickBestAudioUrl(List<AudioStream> audioStreams) {
        if (audioStreams == null || audioStreams.isEmpty()) return null;
        AudioStream bestM4a = null;
        AudioStream bestAny = null;
        for (AudioStream as : audioStreams) {
            if (as.getContent() == null || as.getContent().isEmpty()) continue;
            if (bestAny == null || as.getAverageBitrate() > bestAny.getAverageBitrate()) {
                bestAny = as;
            }
            String fmt = as.getFormat() != null ? as.getFormat().getName().toUpperCase(Locale.US) : "";
            if (fmt.contains("M4A")) {
                if (bestM4a == null || as.getAverageBitrate() > bestM4a.getAverageBitrate()) {
                    bestM4a = as;
                }
            }
        }
        return bestM4a != null ? bestM4a.getContent() : (bestAny != null ? bestAny.getContent() : null);
    }

    private static List<VideoItem> convertInfoItems(List<? extends InfoItem> items) {
        List<VideoItem> out = new ArrayList<>();
        if (items == null) return out;
        for (InfoItem item : items) {
            if (!(item instanceof StreamInfoItem)) continue;
            StreamInfoItem si = (StreamInfoItem) item;
            String vid = extractVideoId(si.getUrl());
            if (vid == null || vid.isEmpty()) continue;

            String title = si.getName() != null ? si.getName() : "Video YouTube";
            String uploader = si.getUploaderName() != null ? si.getUploaderName() : "YouTube";

            String thumb = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
            List<Image> thumbs = si.getThumbnails();
            if (thumbs != null && !thumbs.isEmpty()) {
                String u = thumbs.get(thumbs.size() - 1).getUrl();
                if (u != null && !u.isEmpty()) thumb = u;
            }

            String dur = formatDuration(si.getDuration());
            out.add(new VideoItem(vid, title, uploader, thumb, dur));
        }
        return out;
    }

    private static String formatDuration(long sec) {
        if (sec <= 0) return "";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static List<VideoItem> fetchAuthenticatedTvBrowse(String browseId, String token) {
        List<VideoItem> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            JSONObject clientObj = new JSONObject();
            clientObj.put("clientName", "TVHTML5");
            clientObj.put("clientVersion", "7.20240724.13.00");
            clientObj.put("hl", "vi");
            clientObj.put("gl", "VN");

            JSONObject ctx = new JSONObject();
            ctx.put("client", clientObj);

            JSONObject payload = new JSONObject();
            payload.put("context", ctx);
            payload.put("browseId", browseId);

            conn = AppConfig.openSecureConnection(YOUTUBE_BROWSE_URL);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", UA_TV);
            conn.setRequestProperty("X-YouTube-Client-Name", "7");
            conn.setRequestProperty("Authorization", "Bearer " + token);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream is = conn.getInputStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                extractVideosRecursive(new JSONObject(sb.toString()), out, new HashSet<>());
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private static void extractVideosRecursive(Object node, List<VideoItem> out, Set<String> seen) {
        if (out.size() >= 60 || node == null) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (String key : new String[]{"videoRenderer", "gridVideoRenderer", "compactVideoRenderer"}) {
                JSONObject vr = obj.optJSONObject(key);
                if (vr != null) {
                    String vid = vr.optString("videoId", "");
                    if (!vid.isEmpty() && !seen.contains(vid)) {
                        seen.add(vid);
                        out.add(new VideoItem(
                                vid,
                                extractJsonText(vr.optJSONObject("title")),
                                extractJsonText(vr.optJSONObject("shortBylineText")),
                                "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg",
                                extractJsonText(vr.optJSONObject("lengthText"))
                        ));
                    }
                }
            }
            JSONObject tile = obj.optJSONObject("tileRenderer");
            if (tile != null) {
                String vid = tile.optString("contentId", "");
                if (vid.length() == 11 && !seen.contains(vid)) {
                    seen.add(vid);
                    JSONObject tm = tile.optJSONObject("metadata") != null
                            ? tile.optJSONObject("metadata").optJSONObject("tileMetadataRenderer") : null;
                    String title = tm != null ? extractJsonText(tm.optJSONObject("title")) : "Video";
                    out.add(new VideoItem(vid, title, "YouTube", "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg", ""));
                }
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                extractVideosRecursive(obj.opt(keys.next()), out, seen);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) extractVideosRecursive(arr.opt(i), out, seen);
        }
    }

    private static String extractJsonText(JSONObject obj) {
        if (obj == null) return "";
        String simple = obj.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = obj.optJSONArray("runs");
        if (runs != null && runs.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < runs.length(); i++) {
                JSONObject r = runs.optJSONObject(i);
                if (r != null) sb.append(r.optString("text", ""));
            }
            return sb.toString().trim();
        }
        return "";
    }
}
