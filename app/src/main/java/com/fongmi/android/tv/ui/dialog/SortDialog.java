package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.VodSort;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 点播分栏内排序选择。
 *
 * <p>2026-10-25 用户口径：主维度是「上映时间」（新上传的可能是老片），
 * 「上传时间」是另一个维度，热度再一个。入口挂在分栏上（长按分栏 / 电视端菜单键），
 * 不占页面右上角的位置；选择后按确定进入分栏即按所选方式排序。
 */
public final class SortDialog {

    public interface OnSortListener {
        void onSort(int mode);
    }

    private SortDialog() {
    }

    public static void show(FragmentActivity activity, OnSortListener listener) {
        int current = Setting.getVodSort();
        String[] items = {
                ResUtil.getString(R.string.sort_default),
                ResUtil.getString(R.string.sort_year),
                ResUtil.getString(R.string.sort_upload),
                ResUtil.getString(R.string.sort_hot),
        };
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sort_vod)
                .setSingleChoiceItems(items, current, (dialog, which) -> {
                    Setting.putVodSort(which);
                    if (listener != null) listener.onSort(which);
                    dialog.dismiss();
                })
                .show();
    }

    public static int mode() {
        return Setting.getVodSort();
    }

    public static boolean isHot() {
        return Setting.getVodSort() == VodSort.HOT;
    }
}
