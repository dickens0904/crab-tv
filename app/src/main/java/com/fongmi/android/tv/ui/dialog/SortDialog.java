package com.fongmi.android.tv.ui.dialog;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.VodSort;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 点播分栏内排序选择。
 *
 * <p>2026-10-25 用户口径：主维度是「上映时间」（新上传的可能是老片），
 * 「上传时间」是另一个维度，热度再一个。入口挂在分栏上（长按分栏 / 电视端菜单键），
 * 不占页面右上角的位置；选择后按确定进入分栏即按所选方式排序。
 *
 * <p>2026-11-15：Material3 的 AlertDialog 里 setMessage 与 setSingleChoiceItems 共用时，
 * 消息会顶掉单选列表（实测盒子端只剩标题+提示、5 个选项完全不可见）。
 * 因此提示改为放进自定义标题视图，列表保持单选列表。
 */
public final class SortDialog {

    public interface OnSortListener {
        void onSort(int mode);
    }

    private SortDialog() {
    }

    /**
     * 显示排序菜单。
     *
     * @param rated 当前栏已加载列表中「有评分」的条数（可空）
     * @param total 当前栏已加载列表总条数（可空）
     *             当覆盖率过低（实测各源最高仅约 20%）时，在对话框内提示用户切换到
     *             豆瓣较全的源，避免「评分排序」因数据稀疏而几乎无效。
     */
    public static void show(FragmentActivity activity, OnSortListener listener, Integer rated, Integer total) {
        int current = Setting.getVodSort();
        String[] items = {
                ResUtil.getString(R.string.sort_default),
                ResUtil.getString(R.string.sort_year),
                ResUtil.getString(R.string.sort_upload),
                ResUtil.getString(R.string.sort_hot),
                ResUtil.getString(R.string.sort_score),
        };
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setCustomTitle(buildTitle(activity, rated, total))
                .setSingleChoiceItems(items, current, (dialog, which) -> {
                    Setting.putVodSort(which);
                    if (listener != null) listener.onSort(which);
                    dialog.dismiss();
                });
        builder.show();
    }

    /** 仅保留旧调用点兼容：未传覆盖率时一律不提示。 */
    public static void show(FragmentActivity activity, OnSortListener listener) {
        show(activity, listener, null, null);
    }

    /** 标题 + 可选的低覆盖提示，合并成一个视图，避免 message 顶掉单选列表。 */
    private static View buildTitle(FragmentActivity activity, Integer rated, Integer total) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(24);
        box.setPadding(padding, ResUtil.dp2px(20), padding, 0);
        TextView title = new TextView(activity);
        title.setText(R.string.sort_vod);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        box.addView(title);
        if (shouldHintDouban(rated, total)) {
            TextView hint = new TextView(activity);
            hint.setText(hintDouban(rated, total));
            hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            hint.setTextColor(MaterialColors.getColor(box, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFCCCCCC));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ResUtil.dp2px(6);
            box.addView(hint, lp);
        }
        return box;
    }

    /** 覆盖率过低才提示：无数据/样本太少/评分占比 < 25%（实测最高约 20%）视为低覆盖。 */
    private static boolean shouldHintDouban(Integer rated, Integer total) {
        if (rated == null || total == null) return false;
        if (total == 0 || total < 5) return true;
        return (rated * 100.0 / total) < 25;
    }

    private static String hintDouban(Integer rated, Integer total) {
        if (total != null && total >= 5) {
            return ResUtil.getString(R.string.sort_score_hint, rated, total);
        }
        return ResUtil.getString(R.string.sort_score_hint_short);
    }

    public static int mode() {
        return Setting.getVodSort();
    }

    public static boolean isHot() {
        return Setting.getVodSort() == VodSort.HOT;
    }
}
