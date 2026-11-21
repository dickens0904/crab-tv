package com.fongmi.android.tv.ui.dialog;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.OfflineCache;
import com.fongmi.android.tv.offline.OfflineCacheManager;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 选集界面右上角「三点」菜单：批量多选缓存 + 我的缓存。
 * grid / list 两种选集 dialog 共用，避免两套逻辑。
 */
public final class OfflineCacheMenu {

    private OfflineCacheMenu() {
    }

    public static void show(FragmentActivity activity, List<Episode> episodes,
                            String vodName, String vodId, String vodPic, String siteKey) {
        if (activity == null || episodes == null || episodes.isEmpty()) return;
        String[] menu = {
                activity.getString(R.string.offline_cache_multi),
                activity.getString(R.string.offline_cache_title)
        };
        new MaterialAlertDialogBuilder(activity)
                .setItems(menu, (d, which) -> {
                    if (which == 0) multi(activity, episodes, vodName, vodId, vodPic, siteKey);
                    else OfflineCacheDialog.show(activity);
                })
                .show();
    }

    private static void multi(FragmentActivity activity, List<Episode> episodes,
                              String vodName, String vodId, String vodPic, String siteKey) {
        int size = episodes.size();
        String[] names = new String[size];
        boolean[] checked = new boolean[size];
        boolean[] locked = new boolean[size];
        for (int i = 0; i < size; i++) {
            Episode episode = episodes.get(i);
            OfflineCache cache = OfflineCacheManager.get().find(episode.getUrl());
            String name = episode.getName();
            if (cache != null && (cache.isDone() || cache.isActive())) {
                names[i] = activity.getString(cache.isDone() ? R.string.offline_cache_badge_done : R.string.offline_cache_badge_active) + name;
                locked[i] = true;
            } else if (!OfflineCacheManager.isCacheable(episode.getUrl())) {
                names[i] = name;
                locked[i] = true;
            } else {
                names[i] = name;
            }
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.offline_cache_multi_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    if (locked[which] && isChecked) {
                        if (dialog instanceof AlertDialog) ((AlertDialog) dialog).getListView().setItemChecked(which, false);
                        checked[which] = false;
                        Notify.show(R.string.offline_cache_exists);
                        return;
                    }
                    checked[which] = isChecked;
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> submit(activity, episodes, checked, vodName, vodId, vodPic, siteKey))
                .show();
    }

    private static void submit(FragmentActivity activity, List<Episode> episodes, boolean[] checked,
                               String vodName, String vodId, String vodPic, String siteKey) {
        int added = 0;
        int skipped = 0;
        for (int i = 0; i < checked.length; i++) {
            if (!checked[i]) continue;
            Episode episode = episodes.get(i);
            if (OfflineCacheManager.get().enqueue(episode.getUrl(), episode.getName(), vodName, vodId, vodPic, siteKey)) added++;
            else skipped++;
        }
        if (added == 0 && skipped == 0) return;
        String text = activity.getString(R.string.offline_cache_multi_added, added);
        if (skipped > 0) text = text + " · " + activity.getString(R.string.offline_cache_multi_skipped, skipped);
        Notify.show(text);
    }
}
