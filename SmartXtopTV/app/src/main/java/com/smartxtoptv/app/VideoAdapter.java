package com.smartxtoptv.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.ViewHolder> {

    public interface OnVideoClickListener {
        void onVideoClick(int position);
    }

    private final Context context;
    private final List<VideoItem> items;
    private final OnVideoClickListener clickListener;

    public VideoAdapter(Context context, List<VideoItem> items, OnVideoClickListener clickListener) {
        this.context = context;
        this.items = items;
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_video_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VideoItem item = items.get(position);
        holder.txtTitle.setText(item.getTitle());
        holder.txtUploader.setText(item.getUploader());

        String dur = item.getDuration();
        if (dur != null && !dur.isEmpty()) {
            holder.txtDuration.setVisibility(View.VISIBLE);
            holder.txtDuration.setText(dur);
        } else {
            holder.txtDuration.setVisibility(View.GONE);
        }

        Glide.with(context)
                .load(item.getThumbnailUrl())
                .placeholder(new ColorDrawable(Color.parseColor("#1F1F1F")))
                .centerCrop()
                .into(holder.imgThumbnail);

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? 1.05f : 1.0f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && clickListener != null) {
                clickListener.onVideoClick(pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgThumbnail;
        final TextView txtDuration;
        final TextView txtTitle;
        final TextView txtUploader;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumbnail = itemView.findViewById(R.id.img_thumbnail);
            txtDuration = itemView.findViewById(R.id.txt_duration);
            txtTitle = itemView.findViewById(R.id.txt_title);
            txtUploader = itemView.findViewById(R.id.txt_uploader);
        }
    }
}
