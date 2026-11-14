package com.fongmi.android.tv.ui.dialog;

import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.OfflineCache;
import com.fongmi.android.tv.databinding.ItemOfflineCacheBinding;
import com.fongmi.android.tv.offline.OfflineCacheManager;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 「我的缓存」：列出所有离线下载，短按播放/重试/取消，长按删除。
 * 下载进度由 {@link OfflineCacheManager.Listener} 实时刷新。
 */
public class OfflineCacheDialog implements OfflineCacheManager.Listener {

    private final FragmentActivity activity;
    private final List<OfflineCache> items = new ArrayList<>();
    private android.widget.ArrayAdapter<OfflineCache> adapter;
    private AlertDialog dialog;

    private OfflineCacheDialog(FragmentActivity activity) {
        this.activity = activity;
    }

    public static void show(FragmentActivity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new OfflineCacheDialog(activity).open();
    }

    private void open() {
        refreshItems();
        if (items.isEmpty()) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.offline_cache_title)
                    .setMessage(R.string.offline_cache_empty)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .show();
            return;
        }
        adapter = new android.widget.ArrayAdapter<OfflineCache>(activity, 0, items) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = convertView;
                ItemOfflineCacheBinding binding;
                if (view == null) {
                    binding = ItemOfflineCacheBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
                    view = binding.getRoot();
                    view.setTag(binding);
                } else {
                    binding = (ItemOfflineCacheBinding) view.getTag();
                }
                OfflineCache item = getItem(position);
                if (item != null) {
                    binding.title.setText(item.displayName());
                    binding.status.setText(statusText(item));
                }
                return view;
            }
        };
        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.offline_cache_title)
                .setAdapter(adapter, (d, which) -> onItemClick(which))
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            onItemLongClick(position);
            return true;
        }));
        dialog.setOnDismissListener(d -> OfflineCacheManager.get().removeListener(this));
        OfflineCacheManager.get().addListener(this);
        dialog.show();
    }

    private void refreshItems() {
        items.clear();
        items.addAll(OfflineCacheManager.get().all());
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private String statusText(OfflineCache item) {
        switch (item.getStatus()) {
            case OfflineCache.QUEUED:
                return activity.getString(R.string.offline_cache_status_queued);
            case OfflineCache.DOWNLOADING:
                return activity.getString(R.string.offline_cache_status_downloading, item.getProgress());
            case OfflineCache.FAILED:
                return activity.getString(R.string.offline_cache_status_failed, item.getError() == null ? "" : item.getError());
            default:
                return activity.getString(R.string.offline_cache_status_done, Formatter.formatFileSize(activity, item.getBytes()));
        }
    }

    private OfflineCache itemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    private void onItemClick(int position) {
        OfflineCache item = itemAt(position);
        if (item == null) return;
        if (item.isDone()) {
            VideoActivity.start(activity, OfflineCacheManager.playUrl(item));
            if (dialog != null) dialog.dismiss();
            if (activity instanceof VideoActivity) activity.finish();
            return;
        }
        if (item.getStatus() == OfflineCache.FAILED) {
            OfflineCacheManager.get().enqueue(item.getEpisodeUrl(), item.getEpisodeName(), item.getVodName(), item.getVodId(), item.getVodPic(), item.getSiteKey());
            Notify.show(R.string.offline_cache_added);
        } else {
            Notify.show(statusText(item));
        }
    }

    private void onItemLongClick(int position) {
        OfflineCache item = itemAt(position);
        if (item == null) return;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.offline_cache_delete)
                .setMessage(activity.getString(R.string.offline_cache_delete_confirm, item.displayName()))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    OfflineCacheManager.get().delete(item.getKey());
                    refreshItems();
                    if (items.isEmpty() && dialog != null) dialog.dismiss();
                })
                .show();
    }

    @Override
    public void onChanged(String key) {
        refreshItems();
    }
}
