package com.smartxtoptv.app;

import android.os.Build;

import androidx.multidex.MultiDexApplication;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

public class SmartXtopApp extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SSLSocketFactory tls12Factory = AppConfig.getTls12SocketFactory();
            if (tls12Factory != null) {
                HttpsURLConnection.setDefaultSSLSocketFactory(tls12Factory);
            }
        }
        NewPipe.init(
                DownloaderImpl.getInstance(),
                new Localization("vi", "VN"),
                new ContentCountry("VN")
        );
    }
}
