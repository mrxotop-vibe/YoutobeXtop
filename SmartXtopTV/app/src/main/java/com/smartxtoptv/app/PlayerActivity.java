package com.smartxtoptv.app;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity {

    private StyledPlayerView playerView;
    private ExoPlayer exoPlayer;
    private DefaultHttpDataSource.Factory dataSourceFactory;

    private LinearLayout playerHud;
    private LinearLayout loadingContainer;
    private LinearLayout errorContainer;
    private TextView txtLoadingStatus;
    private TextView txtErrorDetail;
    private TextView txtVideoTitle;
    private TextView txtVideoChannel;
    private TextView txtPlayerMode;
    private TextView txtTimeInfo;
    private TextView txtSponsorHud;
    private ProgressBar progressPlayback;
    private Button btnPlayPause;
    private Button btnSpeed;

    private AppConfig appConfig;
    private HistoryManager historyManager;

    private ArrayList<String> playlistIds;
    private ArrayList<String> playlistTitles;
    private ArrayList<String> playlistChannels;
    private int currentIndex = 0;

    private String videoId;
    private String videoTitle;
    private String videoChannel;
    private float currentSpeed = 1.0f;

    private final List<YouTubeExtractorService.StreamCandidate> candidates = new ArrayList<>();
    private int candidateIndex = 0;
    private final List<SponsorBlockService.Segment> sponsorSegments = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable hideHudRunnable = () -> {
        if (playerHud != null) playerHud.setVisibility(View.GONE);
    };

    private final Runnable playbackTicker = new Runnable() {
        @Override
        public void run() {
            if (exoPlayer != null) {
                long curMs = exoPlayer.getCurrentPosition();
                long durMs = Math.max(1L, exoPlayer.getDuration());
                if (exoPlayer.getDuration() > 0) {
                    progressPlayback.setProgress((int) ((curMs * 1000L) / durMs));
                    txtTimeInfo.setText(formatMs(curMs) + " / " + formatMs(durMs)
                            + "  |  OK: Dừng/Phát  |  ◀ ▶: Tua 10s");
                }
                if (exoPlayer.isPlaying()) {
                    checkSponsorSkip(curMs);
                }
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        appConfig = new AppConfig(this);
        historyManager = new HistoryManager(this);

        playerView = findViewById(R.id.player_view);
        playerHud = findViewById(R.id.player_hud);
        loadingContainer = findViewById(R.id.loading_container);
        errorContainer = findViewById(R.id.error_container);
        txtLoadingStatus = findViewById(R.id.txt_loading_status);
        txtErrorDetail = findViewById(R.id.txt_error_detail);
        txtVideoTitle = findViewById(R.id.txt_video_title);
        txtVideoChannel = findViewById(R.id.txt_video_channel);
        txtPlayerMode = findViewById(R.id.txt_player_mode);
        txtTimeInfo = findViewById(R.id.txt_time_info);
        txtSponsorHud = findViewById(R.id.txt_sponsor_hud);
        progressPlayback = findViewById(R.id.progress_playback);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSpeed = findViewById(R.id.btn_speed);

        Button btnPrev = findViewById(R.id.btn_prev_video);
        Button btnNext = findViewById(R.id.btn_next_video);
        Button btnQuality = findViewById(R.id.btn_quality);
        Button btnRetry = findViewById(R.id.btn_retry);
        Button btnClose = findViewById(R.id.btn_close_player);

        playlistIds = getIntent().getStringArrayListExtra("PLAYLIST_IDS");
        playlistTitles = getIntent().getStringArrayListExtra("PLAYLIST_TITLES");
        playlistChannels = getIntent().getStringArrayListExtra("PLAYLIST_CHANNELS");
        currentIndex = getIntent().getIntExtra("PLAYLIST_INDEX", 0);

        if (playlistIds == null || playlistIds.isEmpty()) {
            String singleId = getIntent().getStringExtra("VIDEO_ID");
            String singleTitle = getIntent().getStringExtra("VIDEO_TITLE");
            String singleChannel = getIntent().getStringExtra("VIDEO_CHANNEL");
            if (singleId == null || singleId.isEmpty()) {
                showNativeError("Không có Video ID");
                return;
            }
            playlistIds = new ArrayList<>();
            playlistTitles = new ArrayList<>();
            playlistChannels = new ArrayList<>();
            playlistIds.add(singleId);
            playlistTitles.add(singleTitle != null ? singleTitle : "YouTube Video");
            playlistChannels.add(singleChannel != null ? singleChannel : "YouTube");
            currentIndex = 0;
        }

        btnPrev.setOnClickListener(v -> playPlaylistIndex(currentIndex - 1));
        btnNext.setOnClickListener(v -> playPlaylistIndex(currentIndex + 1));
        btnPlayPause.setOnClickListener(v -> {
            togglePlayPause();
            showHudBriefly();
        });
        btnQuality.setOnClickListener(v -> showQualityDialog());
        btnSpeed.setOnClickListener(v -> showSpeedDialog());
        btnRetry.setOnClickListener(v -> loadCurrentVideo());
        btnClose.setOnClickListener(v -> finish());

        initExoPlayer();
        playPlaylistIndex(currentIndex);
        handler.post(playbackTicker);
    }

    private void initExoPlayer() {
        dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(DownloaderImpl.USER_AGENT)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    txtLoadingStatus.setText("Đang đệm video...");
                    loadingContainer.setVisibility(View.VISIBLE);
                } else if (playbackState == Player.STATE_READY) {
                    loadingContainer.setVisibility(View.GONE);
                    errorContainer.setVisibility(View.GONE);
                    exoPlayer.setPlaybackParameters(new PlaybackParameters(currentSpeed, 1.0f));
                    showHudBriefly();
                } else if (playbackState == Player.STATE_ENDED) {
                    if (appConfig.isAutoNextEnabled() && playlistIds != null
                            && currentIndex + 1 < playlistIds.size()) {
                        Toast.makeText(PlayerActivity.this,
                                "Tự động chuyển sang video tiếp theo...", Toast.LENGTH_SHORT).show();
                        playPlaylistIndex(currentIndex + 1);
                    } else {
                        showHudBriefly();
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                String failedLabel = (candidateIndex >= 0 && candidateIndex < candidates.size())
                        ? candidates.get(candidateIndex).label : "";
                candidateIndex++;
                if (candidateIndex < candidates.size()) {
                    Toast.makeText(PlayerActivity.this,
                            "Không phát được " + failedLabel + " -> chuyển " + candidates.get(candidateIndex).label,
                            Toast.LENGTH_SHORT).show();
                    playNextCandidate();
                } else {
                    showNativeError("ExoPlayer không phát được nguồn video ("
                            + error.getErrorCodeName() + ").\nHãy bấm 'Thử lại' hoặc đổi chất lượng trong Cài đặt.");
                }
            }
        });
    }

    private void togglePlayPause() {
        if (exoPlayer == null) return;
        if (exoPlayer.isPlaying()) {
            exoPlayer.pause();
            btnPlayPause.setText("▶ Phát");
        } else {
            exoPlayer.play();
            btnPlayPause.setText("⏸ Dừng");
        }
    }

    private void playPlaylistIndex(int index) {
        if (playlistIds == null || playlistIds.isEmpty()) return;
        if (index < 0) index = 0;
        if (index >= playlistIds.size()) {
            Toast.makeText(this, "Đã hết danh sách phát", Toast.LENGTH_SHORT).show();
            return;
        }
        currentIndex = index;
        videoId = playlistIds.get(index);
        videoTitle = (playlistTitles != null && index < playlistTitles.size()) ? playlistTitles.get(index) : "Video";
        videoChannel = (playlistChannels != null && index < playlistChannels.size()) ? playlistChannels.get(index) : "YouTube";

        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        sponsorSegments.clear();
        candidates.clear();
        candidateIndex = 0;

        txtVideoTitle.setText(videoTitle);
        txtVideoChannel.setText(videoChannel);
        historyManager.addWatched(new VideoItem(
                videoId,
                videoTitle,
                videoChannel,
                "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg",
                ""
        ));

        loadCurrentVideo();
    }

    private void loadCurrentVideo() {
        errorContainer.setVisibility(View.GONE);
        loadingContainer.setVisibility(View.VISIBLE);
        txtLoadingStatus.setText("NewPipe Engine đang giải mã luồng phát (" + appConfig.getQualityLabel() + ")...");

        if (appConfig.isSponsorBlockEnabled()) {
            SponsorBlockService.fetchSegments(videoId, segments -> runOnUiThread(() -> {
                sponsorSegments.clear();
                sponsorSegments.addAll(segments);
            }));
        }

        if (appConfig.isDeArrowEnabled()) {
            DeArrowService.fetchCleanTitle(videoId, cleanTitle -> runOnUiThread(() -> {
                if (cleanTitle != null && !cleanTitle.isEmpty()) {
                    videoTitle = cleanTitle;
                    txtVideoTitle.setText(cleanTitle + " ✦");
                }
            }));
        }

        YouTubeExtractorService.fetchStreams(this, videoId, new YouTubeExtractorService.StreamListCallback() {
            @Override
            public void onSuccess(List<YouTubeExtractorService.StreamCandidate> list, String title, String channel) {
                runOnUiThread(() -> {
                    if ((videoTitle == null || videoTitle.equals("YouTube Video"))
                            && title != null && !title.isEmpty()) {
                        videoTitle = title;
                        txtVideoTitle.setText(title);
                    }
                    if (channel != null && !channel.isEmpty()) {
                        videoChannel = channel;
                        txtVideoChannel.setText(channel);
                    }
                    candidates.clear();
                    candidates.addAll(list);
                    candidateIndex = 0;
                    playNextCandidate();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showNativeError("Không lấy được luồng phát:\n" + message));
            }
        });
    }

    private void playNextCandidate() {
        if (exoPlayer == null) return;
        if (candidateIndex >= candidates.size()) {
            showNativeError("Tất cả nguồn phát đều không phản hồi.");
            return;
        }

        YouTubeExtractorService.StreamCandidate cand = candidates.get(candidateIndex);
        errorContainer.setVisibility(View.GONE);
        loadingContainer.setVisibility(View.VISIBLE);
        txtPlayerMode.setText(cand.label);
        txtLoadingStatus.setText("Đang kết nối " + cand.label + "...");

        try {
            MediaSource mediaSource;
            if (cand.isHls) {
                mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(cand.videoUrl)));
            } else if (cand.audioUrl != null && !cand.audioUrl.isEmpty()) {
                MediaSource videoSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(cand.videoUrl)));
                MediaSource audioSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(cand.audioUrl)));
                mediaSource = new MergingMediaSource(videoSource, audioSource);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(cand.videoUrl)));
            }

            exoPlayer.setMediaSource(mediaSource);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
            btnPlayPause.setText("⏸ Dừng");
            playerView.requestFocus();
        } catch (Exception e) {
            candidateIndex++;
            playNextCandidate();
        }
    }

    private void showQualityDialog() {
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Chưa có danh sách nguồn phát", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            labels[i] = (i == candidateIndex ? "● " : "   ") + candidates.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle("Chọn chất lượng / Nguồn phát")
                .setItems(labels, (d, which) -> {
                    candidateIndex = which;
                    playNextCandidate();
                })
                .show();
    }

    private void showSpeedDialog() {
        final float[] speeds = new float[]{0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        final String[] names = new String[]{"0.75x", "1.0x (Bình thường)", "1.25x", "1.5x", "2.0x"};
        new AlertDialog.Builder(this)
                .setTitle("Tốc độ phát (ExoPlayer - Hỗ trợ Android 5.1)")
                .setItems(names, (d, which) -> {
                    currentSpeed = speeds[which];
                    btnSpeed.setText(currentSpeed + "x");
                    if (exoPlayer != null) {
                        exoPlayer.setPlaybackParameters(new PlaybackParameters(currentSpeed, 1.0f));
                    }
                })
                .show();
    }

    private void showNativeError(String msg) {
        loadingContainer.setVisibility(View.GONE);
        playerHud.setVisibility(View.GONE);
        txtErrorDetail.setText(msg);
        errorContainer.setVisibility(View.VISIBLE);
        findViewById(R.id.btn_retry).requestFocus();
    }

    private void checkSponsorSkip(long currentPosMs) {
        if (!appConfig.isSponsorBlockEnabled() || exoPlayer == null) return;
        for (SponsorBlockService.Segment seg : sponsorSegments) {
            if (currentPosMs >= seg.startMs && currentPosMs < seg.endMs) {
                exoPlayer.seekTo(seg.endMs);
                showSponsorHud("⏭️ Đã bỏ qua đoạn " + seg.category);
                break;
            }
        }
    }

    private void showSponsorHud(String text) {
        txtSponsorHud.setText(text);
        txtSponsorHud.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> txtSponsorHud.setVisibility(View.GONE), 3000);
    }

    private void showHudBriefly() {
        playerHud.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHudRunnable);
        handler.postDelayed(hideHudRunnable, 5000);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (playerHud.getVisibility() == View.VISIBLE) {
                playerHud.setVisibility(View.GONE);
                return true;
            }
            finish();
            return true;
        }
        if (errorContainer.getVisibility() == View.VISIBLE) {
            return super.onKeyDown(keyCode, event);
        }

        if (playerHud.getVisibility() == View.VISIBLE && playerHud.hasFocus()
                && getCurrentFocus() != playerView) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                playerHud.setVisibility(View.GONE);
                return true;
            }
            showHudBriefly();
            return super.onKeyDown(keyCode, event);
        }

        if (exoPlayer == null) {
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                showHudBriefly();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                exoPlayer.seekTo(Math.max(0L, exoPlayer.getCurrentPosition() - 10000L));
                showHudBriefly();
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                long dur = exoPlayer.getDuration();
                long fwd = exoPlayer.getCurrentPosition() + 10000L;
                if (dur > 0) fwd = Math.min(dur, fwd);
                exoPlayer.seekTo(fwd);
                showHudBriefly();
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MENU:
                showHudBriefly();
                btnPlayPause.requestFocus();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null && exoPlayer.isPlaying()) {
            exoPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        super.onDestroy();
    }
}
