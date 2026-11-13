package com.smartxtoptv.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

public class SettingsActivity extends AppCompatActivity {

    private AppConfig config;
    private Button btnQuality;
    private Button btnSponsorBlock;
    private Button btnDeArrow;
    private Button btnAutoNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        config = new AppConfig(this);
        btnQuality = findViewById(R.id.btn_setting_quality);
        btnSponsorBlock = findViewById(R.id.btn_setting_sponsorblock);
        btnDeArrow = findViewById(R.id.btn_setting_dearrow);
        btnAutoNext = findViewById(R.id.btn_setting_autonext);
        Button btnClearHistory = findViewById(R.id.btn_clear_history);
        Button btnBack = findViewById(R.id.btn_settings_back);

        btnQuality.setOnClickListener(v -> {
            int cur = config.getDefaultQuality();
            int next;
            if (cur == AppConfig.QUALITY_1080P) next = AppConfig.QUALITY_720P;
            else if (cur == AppConfig.QUALITY_720P) next = AppConfig.QUALITY_HLS_AUTO;
            else if (cur == AppConfig.QUALITY_HLS_AUTO) next = AppConfig.QUALITY_360P;
            else next = AppConfig.QUALITY_1080P;
            config.setDefaultQuality(next);
            refreshLabels();
        });

        btnSponsorBlock.setOnClickListener(v -> {
            config.setSponsorBlockEnabled(!config.isSponsorBlockEnabled());
            refreshLabels();
        });

        btnDeArrow.setOnClickListener(v -> {
            config.setDeArrowEnabled(!config.isDeArrowEnabled());
            refreshLabels();
        });

        btnAutoNext.setOnClickListener(v -> {
            config.setAutoNextEnabled(!config.isAutoNextEnabled());
            refreshLabels();
        });

        btnClearHistory.setOnClickListener(v -> {
            new HistoryManager(this).clear();
            Glide.get(this).clearMemory();
            new Thread(() -> Glide.get(getApplicationContext()).clearDiskCache()).start();
            Toast.makeText(this, "Đã xóa lịch sử xem và bộ nhớ đệm ảnh!", Toast.LENGTH_SHORT).show();
        });

        btnBack.setOnClickListener(v -> finish());
        refreshLabels();
    }

    private void refreshLabels() {
        btnQuality.setText("Chất lượng ưu tiên: " + config.getQualityLabel());
        btnSponsorBlock.setText("SponsorBlock (Tự bỏ qua tài trợ/intro): "
                + (config.isSponsorBlockEnabled() ? "BẬT ✓" : "TẮT"));
        btnDeArrow.setText("DeArrow (Làm sạch tiêu đề giật tít - TizenCobalt): "
                + (config.isDeArrowEnabled() ? "BẬT ✓" : "TẮT"));
        btnAutoNext.setText("Tự động phát video tiếp theo: "
                + (config.isAutoNextEnabled() ? "BẬT ✓" : "TẮT"));
    }
}
