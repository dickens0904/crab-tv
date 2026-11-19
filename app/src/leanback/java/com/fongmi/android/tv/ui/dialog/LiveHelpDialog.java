package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 直播操作说明（leanback）。
 *
 * <p>2026-10-25: 电视端只有方向键+确定，用户找不到直播面板里的引擎切换
 * （"EXO" 就是解码引擎按钮，无声时切 "IJK"）→ 这里给一份完整操作手册。
 */
public final class LiveHelpDialog {

    private LiveHelpDialog() {
    }

    public static void show(FragmentActivity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.live_help_title)
                .setMessage(ResUtil.getString(R.string.live_help_tv))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
