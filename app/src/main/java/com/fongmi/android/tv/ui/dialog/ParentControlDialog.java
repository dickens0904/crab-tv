package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.ContentFilter;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 家长控制：按源隐藏不想看的分栏（伦理片/写真热舞/…）。
 *
 * <p>2026-10-25 用户口径：
 * <ul>
 *   <li>进入需要 6 位数字密码（初始 122192），可修改</li>
 *   <li>规则<strong>按源分别记录在设备本地</strong>：切到别的源再切回来，被隐藏的分栏
 *       仍然是隐藏的；直到在这里把该源的分栏重新打开（取消勾选）</li>
 * </ul>
 */
public final class ParentControlDialog {

    private ParentControlDialog() {
    }

    public static void show(FragmentActivity activity) {
        promptPassword(activity, () -> showSites(activity));
    }

    /** 密码输入（自绘数字键盘，电视端不被系统软键盘挡）。 */
    private static void promptPassword(FragmentActivity activity, Runnable onOk) {
        PasswordDialog.show(activity, R.string.parent_control, R.string.parent_pwd_hint, pwd -> {
            if (!ContentFilter.checkPassword(pwd)) {
                Notify.show(activity.getString(R.string.parent_pwd_wrong));
                return;
            }
            onOk.run();
        });
    }

    /** 列出已保存的源，选一个后配置它的隐藏分栏。 */
    private static void showSites(FragmentActivity activity) {
        List<Site> sites = VodConfig.get().getSites();
        if (sites.isEmpty()) {
            Notify.show(activity.getString(R.string.setting_vod));
            return;
        }
        String[] names = new String[sites.size()];
        for (int i = 0; i < sites.size(); i++) names[i] = sites.get(i).getName();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.parent_control)
                .setItems(names, (dialog, which) -> loadCategories(activity, sites.get(which)))
                .setNeutralButton(R.string.parent_change_pwd, (d, w) -> changePassword(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 拉该源的全部分类（含被隐藏的），勾选=隐藏。 */
    private static void loadCategories(FragmentActivity activity, Site site) {
        new Thread(() -> {
            List<String> names = new ArrayList<>();
            try {
                Result result = SiteApi.homeContent(site);
                names = ContentFilter.names(result == null ? null : result.getAllTypes());
            } catch (Exception e) {
                // 拉不到就空列表
            }
            final List<String> finalNames = names;
            activity.runOnUiThread(() -> showCategories(activity, site, finalNames));
        }).start();
    }

    private static void showCategories(FragmentActivity activity, Site site, List<String> names) {
        if (names.isEmpty()) {
            Notify.show(activity.getString(R.string.setting_vod));
            return;
        }
        boolean[] checked = new boolean[names.size()];
        boolean[] defaultHidden = new boolean[names.size()];
        java.util.Set<String> hidden = ContentFilter.hiddenNames(site.getKey());
        for (int i = 0; i < names.size(); i++) {
            boolean hit = false;
            for (String key : hidden) {
                if (names.get(i).contains(key)) {
                    hit = true;
                    break;
                }
            }
            checked[i] = hit;
            defaultHidden[i] = hit;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(site.getName())
                .setMultiChoiceItems(names.toArray(new String[0]), checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> hide = new ArrayList<>();
                    List<String> show = new ArrayList<>();
                    for (int i = 0; i < names.size(); i++) {
                        if (checked[i]) hide.add(names.get(i));
                        else if (defaultHidden[i]) show.add(names.get(i));
                    }
                    ContentFilter.save(site.getKey(), hide, show);
                })
                .setNeutralButton(R.string.parent_reset, (d, w) -> ContentFilter.clear(site.getKey()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 修改密码。 */
    private static void changePassword(FragmentActivity activity) {
        PasswordDialog.show(activity, R.string.parent_change_pwd, R.string.parent_pwd_new, pwd -> {
            if (pwd == null || pwd.length() != 6) {
                Notify.show(activity.getString(R.string.parent_pwd_input));
                return;
            }
            ContentFilter.setPassword(pwd);
            Notify.show(activity.getString(R.string.parent_pwd_changed));
        });
    }
}
