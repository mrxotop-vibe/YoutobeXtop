package com.smartxtoptv.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LoginActivity extends AppCompatActivity {

    private static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String UA_COBALT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version";
    private static final String UA_TV =
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) SamsungBrowser/4.0 Chrome/94.0.4606.112 TV Safari/537.36";

    private TextView txtStep1Url;
    private TextView txtDeviceCode;
    private TextView txtLoginStatus;

    private AuthManager authManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean cancelled = false;
    private String activeClientId = "";
    private String activeClientSecret = "";
    private String activeDeviceCode = "";
    private int pollIntervalSec = 5;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (cancelled || activeDeviceCode.isEmpty()) return;
            executor.execute(() -> pollForToken());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        authManager = new AuthManager(this);
        txtStep1Url = findViewById(R.id.txt_step1_url);
        txtDeviceCode = findViewById(R.id.txt_device_code);
        txtLoginStatus = findViewById(R.id.txt_login_status);

        Button btnRefresh = findViewById(R.id.btn_refresh_code);
        Button btnCustomClient = findViewById(R.id.btn_custom_client);
        Button btnLogout = findViewById(R.id.btn_logout);
        Button btnBack = findViewById(R.id.btn_back);

        btnRefresh.setOnClickListener(v -> startDeviceFlow());
        btnCustomClient.setOnClickListener(v -> showCustomClientDialog());
        btnLogout.setOnClickListener(v -> {
            cancelled = true;
            handler.removeCallbacks(pollTask);
            authManager.clearSession();
            Toast.makeText(this, "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
        btnBack.setOnClickListener(v -> finish());

        if (authManager.isLoggedIn()) {
            txtDeviceCode.setText("ĐÃ ĐĂNG NHẬP ✓");
            txtLoginStatus.setText("Tài khoản đã kết nối. Bấm 'Lấy mã mới' để đổi tài khoản hoặc 'Đăng xuất'.");
        } else {
            startDeviceFlow();
        }
    }

    private void startDeviceFlow() {
        cancelled = false;
        handler.removeCallbacks(pollTask);
        activeDeviceCode = "";

        txtDeviceCode.setText("Đang tạo mã...");
        txtLoginStatus.setText("Đang lấy thông tin OAuth từ YouTube TV...");

        executor.execute(() -> {
            try {
                String customId = authManager.getCustomClientId();
                String customSecret = authManager.getCustomClientSecret();

                if (!customId.isEmpty() && !customSecret.isEmpty()) {
                    activeClientId = customId;
                    activeClientSecret = customSecret;
                } else {
                    String[] tvCreds = fetchYoutubeTvClientCredentials();
                    if (tvCreds == null) {
                        runOnUiThread(() -> {
                            txtDeviceCode.setText("CHƯA CÓ MÃ");
                            txtLoginStatus.setText("Không tự lấy được Client ID từ YouTube TV.\nBấm 'Nhập Client ID riêng' (tạo ở Google Cloud -> TVs and Limited Input devices).");
                        });
                        return;
                    }
                    activeClientId = tvCreds[0];
                    activeClientSecret = tvCreds[1];
                }

                runOnUiThread(() -> txtLoginStatus.setText("Đang xin mã kết nối từ Google..."));

                String[] scopes = new String[]{
                        "https://www.googleapis.com/auth/youtube https://www.googleapis.com/auth/youtube.readonly",
                        "https://www.googleapis.com/auth/youtube",
                        "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
                };

                JSONObject codeResp = null;
                for (String sc : scopes) {
                    codeResp = requestDeviceCode(activeClientId, sc);
                    if (codeResp != null && codeResp.has("user_code")) {
                        break;
                    }
                }

                if (codeResp == null) {
                    runOnUiThread(() -> {
                        txtDeviceCode.setText("LỖI MẠNG");
                        txtLoginStatus.setText("Không kết nối được máy chủ Google OAuth (oauth2.googleapis.com)");
                    });
                    return;
                }

                if (codeResp.has("error")) {
                    String err = codeResp.optString("error", "") + " " + codeResp.optString("error_description", "");
                    runOnUiThread(() -> {
                        txtDeviceCode.setText("LỖI OAUTH");
                        txtLoginStatus.setText("Google báo lỗi: " + err.trim() + "\nHãy thử 'Nhập Client ID riêng'.");
                    });
                    return;
                }

                activeDeviceCode = codeResp.optString("device_code", "");
                String userCode = codeResp.optString("user_code", "");
                String verifyUrl = codeResp.optString("verification_url", "https://www.google.com/device");
                pollIntervalSec = Math.max(5, codeResp.optInt("interval", 5));

                runOnUiThread(() -> {
                    txtStep1Url.setText("1. Trên điện thoại, mở trình duyệt vào:\n" + verifyUrl);
                    txtDeviceCode.setText(userCode);
                    txtLoginStatus.setText("2. Nhập mã trên rồi bấm Xác nhận trên điện thoại (tự kiểm tra mỗi " + pollIntervalSec + " giây)...");
                    handler.postDelayed(pollTask, pollIntervalSec * 1000L);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtDeviceCode.setText("LỖI");
                    txtLoginStatus.setText("Lỗi tạo mã: " + e.getMessage());
                });
            }
        });
    }

    private void pollForToken() {
        if (cancelled || activeDeviceCode.isEmpty()) return;
        try {
            String form = "client_id=" + URLEncoder.encode(activeClientId, "UTF-8")
                    + "&client_secret=" + URLEncoder.encode(activeClientSecret, "UTF-8")
                    + "&device_code=" + URLEncoder.encode(activeDeviceCode, "UTF-8")
                    + "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8");

            HttpResponse res = httpPostForm(TOKEN_URL, form);
            if (res.body == null) {
                scheduleNextPoll();
                return;
            }

            JSONObject obj = new JSONObject(res.body);
            if (res.code >= 200 && res.code < 300 && obj.has("access_token")) {
                authManager.saveOAuthTokens(
                        obj.optString("access_token", ""),
                        obj.optString("refresh_token", ""),
                        obj.optLong("expires_in", 3600L),
                        activeClientId,
                        activeClientSecret
                );
                cancelled = true;
                runOnUiThread(() -> {
                    txtDeviceCode.setText("THÀNH CÔNG ✓");
                    txtLoginStatus.setText("Đã đăng nhập! Đang quay lại SmartXtop...");
                    Toast.makeText(LoginActivity.this, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    handler.postDelayed(LoginActivity.this::finish, 800);
                });
                return;
            }

            String err = obj.optString("error", "");
            if ("slow_down".equals(err)) {
                pollIntervalSec += 5;
                scheduleNextPoll();
            } else if ("authorization_pending".equals(err)) {
                scheduleNextPoll();
            } else if ("expired_token".equals(err) || "access_denied".equals(err)) {
                runOnUiThread(() -> txtLoginStatus.setText("Mã hết hạn hoặc bị từ chối (" + err + "). Bấm 'Lấy mã mới'."));
            } else {
                scheduleNextPoll();
            }
        } catch (Exception e) {
            scheduleNextPoll();
        }
    }

    private void scheduleNextPoll() {
        if (!cancelled) handler.postDelayed(pollTask, pollIntervalSec * 1000L);
    }

    private JSONObject requestDeviceCode(String clientId, String scope) {
        try {
            String form = "client_id=" + URLEncoder.encode(clientId, "UTF-8")
                    + "&scope=" + URLEncoder.encode(scope, "UTF-8");
            HttpResponse res = httpPostForm(DEVICE_CODE_URL, form);
            if (res.body != null && !res.body.isEmpty()) return new JSONObject(res.body);
        } catch (Exception ignored) {
        }
        return null;
    }

    private String[] fetchYoutubeTvClientCredentials() {
        String[] userAgents = new String[]{UA_COBALT, UA_TV};
        for (String ua : userAgents) {
            String tvHtml = httpGet("https://www.youtube.com/tv", ua);
            if (tvHtml == null || tvHtml.isEmpty()) continue;

            // 1. Check inline HTML/scripts first
            String[] inlineCreds = extractClientPairFromText(tvHtml);
            if (inlineCreds != null) return inlineCreds;

            // 2. Collect all script URLs, prioritizing kabuki / tv / base / main / cobalt bundles
            List<String> priorityScripts = new ArrayList<>();
            List<String> otherScripts = new ArrayList<>();

            Pattern scriptPattern = Pattern.compile("<script[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = scriptPattern.matcher(tvHtml);
            while (m.find()) {
                String src = m.group(1);
                if (src == null || src.isEmpty()) continue;
                if (src.startsWith("//")) src = "https:" + src;
                else if (src.startsWith("/")) src = "https://www.youtube.com" + src;
                if (!src.startsWith("http")) continue;

                String lower = src.toLowerCase();
                if (lower.contains("kabuki") || lower.contains("cobalt") || lower.contains("tv")
                        || lower.contains("base") || lower.contains("main") || lower.contains("app")) {
                    priorityScripts.add(src);
                } else {
                    otherScripts.add(src);
                }
            }

            List<String> allScripts = new ArrayList<>(priorityScripts);
            allScripts.addAll(otherScripts);

            for (String src : allScripts) {
                String js = httpGet(src, ua);
                if (js == null || js.isEmpty()) continue;
                String[] creds = extractClientPairFromText(js);
                if (creds != null) return creds;
            }
        }
        return null;
    }

    private static String[] extractClientPairFromText(String text) {
        if (text == null || text.isEmpty()) return null;

        Pattern[] patterns = new Pattern[]{
                // clientId followed closely by clientSecret (handles kabuki/cobalt minified JS & single/double quotes)
                Pattern.compile(
                        "(?:clientId|client_id)\\s*[:=]\\s*[\"']([0-9]+-[0-9a-zA-Z_-]{16,}\\.apps\\.googleusercontent\\.com)[\"'].{0,400}?(?:clientSecret|client_secret|secret)\\s*[:=]\\s*[\"']([a-zA-Z0-9_-]{16,})[\"']",
                        Pattern.DOTALL),
                // reversed order: clientSecret then clientId
                Pattern.compile(
                        "(?:clientSecret|client_secret|secret)\\s*[:=]\\s*[\"']([a-zA-Z0-9_-]{16,})[\"'].{0,400}?(?:clientId|client_id)\\s*[:=]\\s*[\"']([0-9]+-[0-9a-zA-Z_-]{16,}\\.apps\\.googleusercontent\\.com)[\"']",
                        Pattern.DOTALL),
                // direct literal pair in object/array e.g. "xxx.apps.googleusercontent.com", secret:"yyy" or GOCSPX-...
                Pattern.compile(
                        "[\"']([0-9]+-[0-9a-zA-Z_-]{16,}\\.apps\\.googleusercontent\\.com)[\"'].{0,220}?(?:clientSecret|secret|client_secret)\\s*[:=]\\s*[\"']([a-zA-Z0-9_-]{16,})[\"']",
                        Pattern.DOTALL),
                Pattern.compile(
                        "[\"']([0-9]+-[0-9a-zA-Z_-]{16,}\\.apps\\.googleusercontent\\.com)[\"']\\s*,\\s*[\"'](GOCSPX-[a-zA-Z0-9_-]{12,}|[a-zA-Z0-9_-]{20,36})[\"']",
                        Pattern.DOTALL)
        };

        for (int i = 0; i < patterns.length; i++) {
            Matcher cm = patterns[i].matcher(text);
            if (cm.find()) {
                if (i == 1) {
                    return new String[]{cm.group(2), cm.group(1)};
                }
                return new String[]{cm.group(1), cm.group(2)};
            }
        }
        return null;
    }

    private void showCustomClientDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 20, 36, 8);

        TextView hint = new TextView(this);
        hint.setText("Tạo trong Google Cloud Console -> Credentials -> OAuth Client ID (TVs and Limited Input devices):");
        hint.setTextSize(17f);
        layout.addView(hint);

        EditText edtId = new EditText(this);
        edtId.setHint("Client ID (xxx.apps.googleusercontent.com)");
        edtId.setText(authManager.getCustomClientId());
        edtId.setTextSize(17f);
        edtId.setSingleLine(true);
        layout.addView(edtId);

        EditText edtSecret = new EditText(this);
        edtSecret.setHint("Client Secret");
        edtSecret.setText(authManager.getCustomClientSecret());
        edtSecret.setTextSize(17f);
        edtSecret.setSingleLine(true);
        layout.addView(edtSecret);

        new AlertDialog.Builder(this)
                .setTitle("Cấu hình OAuth2 TV riêng")
                .setView(layout)
                .setPositiveButton("Lưu & Tạo mã", (d, w) -> {
                    authManager.saveCustomClient(edtId.getText().toString(), edtSecret.getText().toString());
                    startDeviceFlow();
                })
                .setNeutralButton("Dùng mặc định", (d, w) -> {
                    authManager.saveCustomClient("", "");
                    startDeviceFlow();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private static class HttpResponse {
        final int code;
        final String body;
        HttpResponse(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static HttpResponse httpPostForm(String urlStr, String formBody) {
        try {
            OkHttpClient client = DownloaderImpl.getInstance().getClient();
            RequestBody body = RequestBody.create(formBody, MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"));
            Request req = new Request.Builder()
                    .url(urlStr)
                    .post(body)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                ResponseBody rb = resp.body();
                return new HttpResponse(resp.code(), rb != null ? rb.string() : "");
            }
        } catch (Exception e) {
            return new HttpResponse(-1, null);
        }
    }

    private static String httpGet(String urlStr, String userAgent) {
        try {
            OkHttpClient client = DownloaderImpl.getInstance().getClient();
            Request req = new Request.Builder()
                    .url(urlStr)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/javascript,*/*;q=0.8")
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8")
                    .get()
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    ResponseBody rb = resp.body();
                    return rb != null ? rb.string() : null;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
