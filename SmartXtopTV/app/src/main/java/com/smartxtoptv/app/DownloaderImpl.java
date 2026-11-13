package com.smartxtoptv.app;

import android.os.Build;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class DownloaderImpl extends Downloader {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static DownloaderImpl instance;
    private final OkHttpClient client;

    private DownloaderImpl() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SSLSocketFactory sf = AppConfig.getTls12SocketFactory();
            X509TrustManager tm = AppConfig.getDefaultTrustManager();
            if (sf != null && tm != null) {
                builder.sslSocketFactory(sf, tm);
            }
        }
        this.client = builder.build();
    }

    public static synchronized DownloaderImpl getInstance() {
        if (instance == null) {
            instance = new DownloaderImpl();
        }
        return instance;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        String httpMethod = request.httpMethod();
        String url = request.url();
        Map<String, List<String>> headers = request.headers();
        byte[] dataToSend = request.dataToSend();

        RequestBody requestBody = null;
        if (dataToSend != null) {
            requestBody = RequestBody.create(dataToSend, null);
        } else if ("POST".equalsIgnoreCase(httpMethod) || "PUT".equalsIgnoreCase(httpMethod)) {
            requestBody = RequestBody.create(new byte[0], MediaType.parse("application/json; charset=utf-8"));
        }

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .method(httpMethod, requestBody)
                .url(url)
                .addHeader("User-Agent", USER_AGENT);

        for (Map.Entry<String, List<String>> pair : headers.entrySet()) {
            String headerName = pair.getKey();
            List<String> values = pair.getValue();
            if (values == null || values.isEmpty()) continue;
            if (values.size() == 1) {
                builder.header(headerName, values.get(0));
            } else {
                builder.removeHeader(headerName);
                for (String val : values) {
                    builder.addHeader(headerName, val);
                }
            }
        }

        try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
            if (response.code() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }
            ResponseBody body = response.body();
            String responseBodyToReturn = body != null ? body.string() : "";
            String latestUrl = response.request().url().toString();
            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBodyToReturn,
                    latestUrl
            );
        }
    }
}
