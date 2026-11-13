package com.smartxtoptv.app;

public class VideoItem {
    private final String id;
    private final String title;
    private final String uploader;
    private final String thumbnailUrl;
    private final String duration;

    public VideoItem(String id, String title, String uploader, String thumbnailUrl, String duration) {
        this.id = id;
        this.title = title;
        this.uploader = uploader;
        this.thumbnailUrl = thumbnailUrl;
        this.duration = duration != null ? duration : "";
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getUploader() {
        return uploader;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getDuration() {
        return duration;
    }
}
