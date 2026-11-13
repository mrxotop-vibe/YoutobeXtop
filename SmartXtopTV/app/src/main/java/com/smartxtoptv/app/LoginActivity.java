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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoginActivity extends AppCompatActivity {

    private static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
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
        txtLoginStatus.setText("Đang kết nối Google TV OAuth...");

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
                            txtLoginStatus.setText("Không tự lấy được YouTube TV Client ID.\nBấm 'Nhập Client ID riêng' (Google Cloud -> TVs & Limited Input devices).");
                        });
                        return;
                    }
                    activeClientId = tvCreds[0];
                    activeClientSecret = tvCreds[1];
                }

                JSONObject codeResp = requestDeviceCode(activeClientId,
                        "https://www.googleapis.com/auth/youtube https://www.googleapis.com/auth/youtube.readonly");
                if (codeResp == null || codeResp.has("error")) {
                    codeResp = requestDeviceCode(activeClientId,
                            "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content");
                }

                if (codeResp == null) {
                    runOnUiThread(() -> {
                        txtDeviceCode.setText("LỖI MẠNG");
                        txtLoginStatus.setText("Không kết nối được máy chủ Google OAuth");
                    });
                    return;
                }

                if (codeResp.has("error")) {
                    String err = codeResp.optString("error", "") + " " + codeResp.optString("error_description", "");
                    runOnUiThread(() -> {
                        txtDeviceCode.setText("LỖI OAUTH");
                        txtLoginStatus.setText("Google báo lỗi (" + err.trim() + "). Hãy thử 'Nhập Client ID riêng'.");
                    });
                    return;
                }

                activeDeviceCode = codeResp.optString("device_code", "");
                String userCode = codeResp.optString("user_code", "");
                String verifyUrl = codeResp.optString("verification_url", "https://www.google.com/device");
                pollIntervalSec = Math.max(5, codeResp.optInt("interval", 5));

                runOnUiThread(() -> {
                    txtStep1Url.setText("1. Mở trình duyệt trên điện thoại và truy cập:\n" + verifyUrl);
                    txtDeviceCode.setText(userCode);
                    txtLoginStatus.setText("Đang chờ bạn xác nhận trên điện thoại (tự kiểm tra mỗi " + pollIntervalSec + "s)...");
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
        try {
            String tvHtml = httpGet("https://www.youtube.com/tv", UA_TV);
            if (tvHtml == null) return null;

            Pattern scriptPattern = Pattern.compile("<script[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = scriptPattern.matcher(tvHtml);
            while (m.find()) {
                String src = m.group(1);
                if (src == null) continue;
                if (src.startsWith("//")) src = "https:" + src;
                else if (src.startsWith("/")) src = "https://www.youtube.com" + src;

                if (!src.contains("tv") && !src.contains("base.js") && !src.contains("cobalt")) continue;
                String js = httpGet(src, UA_TV);
                if (js == null) continue;

                Pattern p1 = Pattern.compile(
                        "([0-9]+-[0-9a-zA-Z_-]{20,}\\.apps\\.googleusercontent\\.com)[\"']\\s*,\\s*(?:clientSecret|secret|client_secret)\\s*:\\s*[\"']([a-zA-Z0-9_-]{18,})[\"']");
                Matcher cm = p1.matcher(js);
                if (cm.find()) return new String[]{cm.group(1), cm.group(2)};

                Pattern p2 = Pattern.compile(
                        "clientId\\s*[:=]\\s*[\"']([0-9]+-[0-9a-zA-Z_-]{20,}\\.apps\\.googleusercontent\\.com)[\"'].{0,180}?clientSecret\\s*[:=]\\s*[\"']([a-zA-Z0-9_-]{18,})[\"']",
                        Pattern.DOTALL);
                Matcher cm2 = p2.matcher(js);
                if (cm2.find()) return new String[]{cm2.group(1), cm2.group(2)};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void showCustomClientDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 20, 36, 8);

        TextView hint = new TextView(this);
        hint.setText("Tạo trong Google Cloud Console -> Credentials -> OAuth Client ID (TVs and Limited Input devices):");
        hint.setTextSize(13f);
        layout.addView(hint);

        EditText edtId = new EditText(this);
        edtId.setHint("Client ID (xxx.apps.googleusercontent.com)");
        edtId.setText(authManager.getCustomClientId());
        edtId.setSingleLine(true);
        layout.addView(edtId);

        EditText edtSecret = new EditText(this);
        edtSecret.setHint("Client Secret");
        edtSecret.setText(authManager.getCustomClientSecret());
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
        HttpURLConnection conn = null;
        try {
            conn = AppConfig.openSecureConnection(urlStr);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(formBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return new HttpResponse(code, null);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new HttpResponse(code, sb.toString());
        } catch (Exception e) {
            return new HttpResponse(-1, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String httpGet(String urlStr, String userAgent) {
        HttpURLConnection conn = null;
        try {
            conn = AppConfig.openSecureConnection(urlStr);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", userAgent);
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                return sb.toString();
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
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
