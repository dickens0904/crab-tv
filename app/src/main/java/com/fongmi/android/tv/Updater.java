package com.fongmi.android.tv;

import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;

public class Updater implements Download.Callback, UpdateListener {

    private final Download download;
    private UpdateDialog dialog;
    /** 本次检查到的版本号；onCancel 在工作线程写、UI 线程读，故 volatile。 */
    private volatile int currentCode;

    private Updater() {
        this.download = Download.create(getApk(), getFile());
    }

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private String getJson() {
        return Github.getJson(BuildConfig.FLAVOR);
    }

    private String getApk() {
        return Github.getApk(BuildConfig.FLAVOR + "-" + (android.os.Process.is64Bit() ? "arm64_v8a" : "armeabi_v7a"));
    }

    /** 手动点设置页版本号：清掉「跳过此版本」，强制重新检查一次。 */
    public Updater force() {
        Notify.show(R.string.update_check);
        Setting.putSkipCode(0);
        return this;
    }

    /**
     * 每次启动都检查。2026-11-12 起不再有全局开关：旧版点过「取消」会留下
     * update=false 导致此后所有版本都不再自动提示，这里不读它即可洗掉历史残留。
     */
    public void start(FragmentActivity activity) {
        Task.execute(() -> doInBackground(activity));
    }

    private void doInBackground(FragmentActivity activity) {
        try {
            JSONObject object = new JSONObject(OkHttp.string(getJson()));
            String name = object.optString("name");
            String desc = object.optString("desc");
            int code = object.optInt("code");
            if (code <= BuildConfig.VERSION_CODE || code == Setting.getSkipCode()) return;
            currentCode = code;
            App.post(() -> show(activity, name, desc));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void show(FragmentActivity activity, String version, String desc) {
        dismiss();
        dialog = UpdateDialog.create().title(ResUtil.getString(R.string.update_version, version)).desc(desc).listener(this).show(activity);
    }

    @Override
    public void onConfirm(View view) {
        view.setEnabled(false);
        download.start(this);
    }

    /**
     * 「取消」= 本次不执行更新，只记住跳过这一个版本；
     * 下一个新版本照常自动弹框。绝不写全局开关。
     */
    @Override
    public void onCancel(View view) {
        if (currentCode > 0) Setting.putSkipCode(currentCode);
        download.cancel();
        dismiss();
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void error(String msg) {
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        FileUtil.openFile(file);
        dismiss();
    }
}
