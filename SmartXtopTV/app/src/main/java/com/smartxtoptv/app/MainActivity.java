package com.smartxtoptv.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_LOGIN = 1001;

    private RecyclerView recyclerVideos;
    private ProgressBar progressLoading;
    private EditText editSearch;
    private TextView txtSectionTitle;
    private TextView txtStatusPills;

    private Button navLogin;
    private Button navHome;
    private Button navTrending;
    private Button navSubs;
    private Button navMusic;
    private Button navGaming;
    private Button navHistory;
    private Button navSettings;

    private AuthManager authManager;
    private AppConfig appConfig;
    private HistoryManager historyManager;

    private final List<VideoItem> videoList = new ArrayList<>();
    private VideoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        authManager = new AuthManager(this);
        appConfig = new AppConfig(this);
        historyManager = new HistoryManager(this);

        recyclerVideos = findViewById(R.id.recycler_videos);
        progressLoading = findViewById(R.id.progress_loading);
        editSearch = findViewById(R.id.edit_search);
        txtSectionTitle = findViewById(R.id.txt_section_title);
        txtStatusPills = findViewById(R.id.txt_status_pills);

        navLogin = findViewById(R.id.nav_login);
        navHome = findViewById(R.id.nav_home);
        navTrending = findViewById(R.id.nav_trending);
        navSubs = findViewById(R.id.nav_subs);
        navMusic = findViewById(R.id.nav_music);
        navGaming = findViewById(R.id.nav_gaming);
        navHistory = findViewById(R.id.nav_history);
        navSettings = findViewById(R.id.nav_settings);
        Button btnSearch = findViewById(R.id.btn_search);

        recyclerVideos.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new VideoAdapter(this, videoList, this::openPlayerAt);
        recyclerVideos.setAdapter(adapter);

        navLogin.setOnClickListener(v -> startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN));
        navHome.setOnClickListener(v -> selectTab(navHome, "🏠 Trang chủ YouTube", "home"));
        navTrending.setOnClickListener(v -> selectTab(navTrending, "🔥 Thịnh hành", "trending"));
        navSubs.setOnClickListener(v -> {
            if (!authManager.isLoggedIn()) {
                Toast.makeText(this, "Vui lòng đăng nhập để xem Kênh đăng ký", Toast.LENGTH_SHORT).show();
                startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN);
                return;
            }
            selectTab(navSubs, "📺 Kênh đã đăng ký", "subs");
        });
        navMusic.setOnClickListener(v -> selectTab(navMusic, "🎵 Âm nhạc & Remix", "music"));
        navGaming.setOnClickListener(v -> selectTab(navGaming, "🎮 Giải trí & Game", "gaming"));
        navHistory.setOnClickListener(v -> {
            highlightSidebar(navHistory);
            txtSectionTitle.setText("🕘 Lịch sử đã xem (Trên máy)");
            List<VideoItem> hist = historyManager.getHistory();
            videoList.clear();
            videoList.addAll(hist);
            adapter.notifyDataSetChanged();
            if (hist.isEmpty()) {
                Toast.makeText(this, "Chưa có video nào trong lịch sử", Toast.LENGTH_SHORT).show();
            } else {
                recyclerVideos.requestFocus();
            }
        });
        navSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        btnSearch.setOnClickListener(v -> performSearch());
        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performSearch();
                return true;
            }
            return false;
        });

        updateUiBadges();
        selectTab(navHome, "🏠 Trang chủ YouTube", "home");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUiBadges();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            updateUiBadges();
            selectTab(navHome, "🏠 Trang chủ YouTube", "home");
        }
    }

    private void updateUiBadges() {
        if (authManager.isLoggedIn()) {
            navLogin.setText("👤 Đã ĐN ✓");
            navLogin.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            navLogin.setText("👤 Đăng nhập");
            navLogin.setTextColor(Color.WHITE);
        }

        String sb = appConfig.isSponsorBlockEnabled() ? "SponsorBlock ✓" : "SponsorBlock ✕";
        String da = appConfig.isDeArrowEnabled() ? "DeArrow ✓" : "DeArrow ✕";
        txtStatusPills.setText(sb + "  |  " + da + "  |  " + appConfig.getQualityLabel());
    }

    private void highlightSidebar(Button active) {
        Button[] all = new Button[]{navLogin, navHome, navTrending, navSubs, navMusic, navGaming, navHistory, navSettings};
        for (Button b : all) {
            b.setSelected(b == active);
        }
    }

    private void selectTab(Button btn, String title, String mode) {
        highlightSidebar(btn);
        txtSectionTitle.setText(title);
        progressLoading.setVisibility(View.VISIBLE);

        YouTubeExtractorService.VideoListCallback cb = new YouTubeExtractorService.VideoListCallback() {
            @Override
            public void onSuccess(List<VideoItem> videos) {
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    videoList.clear();
                    videoList.addAll(videos);
                    adapter.notifyDataSetChanged();
                    if (!videos.isEmpty()) recyclerVideos.requestFocus();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }
        };

        if ("trending".equals(mode)) {
            YouTubeExtractorService.fetchTrending(this, cb);
        } else if ("subs".equals(mode)) {
            YouTubeExtractorService.fetchSubscriptions(this, cb);
        } else if ("music".equals(mode)) {
            YouTubeExtractorService.fetchMusic(this, cb);
        } else if ("gaming".equals(mode)) {
            YouTubeExtractorService.fetchGaming(this, cb);
        } else {
            YouTubeExtractorService.fetchHomeFeed(this, cb);
        }
    }

    private void performSearch() {
        String query = editSearch.getText().toString().trim();
        if (query.isEmpty()) return;

        String directId = YouTubeExtractorService.extractVideoId(query);
        if (directId != null) {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("VIDEO_ID", directId);
            intent.putExtra("VIDEO_TITLE", "Video " + directId);
            startActivity(intent);
            return;
        }

        highlightSidebar(null);
        progressLoading.setVisibility(View.VISIBLE);
        txtSectionTitle.setText("🔍 Tìm kiếm: " + query);
        YouTubeExtractorService.search(this, query, new YouTubeExtractorService.VideoListCallback() {
            @Override
            public void onSuccess(List<VideoItem> videos) {
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    videoList.clear();
                    videoList.addAll(videos);
                    adapter.notifyDataSetChanged();
                    if (!videos.isEmpty()) recyclerVideos.requestFocus();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Lỗi tìm kiếm: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void openPlayerAt(int index) {
        if (index < 0 || index >= videoList.size()) return;
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> channels = new ArrayList<>();
        for (VideoItem item : videoList) {
            ids.add(item.getId());
            titles.add(item.getTitle());
            channels.add(item.getUploader());
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putStringArrayListExtra("PLAYLIST_IDS", ids);
        intent.putStringArrayListExtra("PLAYLIST_TITLES", titles);
        intent.putStringArrayListExtra("PLAYLIST_CHANNELS", channels);
        intent.putExtra("PLAYLIST_INDEX", index);
        startActivity(intent);
    }
}
