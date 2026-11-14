package com.fongmi.android.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
        dismiss();
        installWhenScreenOn(file);
    }

    /**
     * 下载完成后启动安装。屏幕待机时，后台 startActivity 启动的安装界面不会显示，
     * 且该一次性 Intent 不会在亮屏后重试，导致进度卡在 100%、开屏后仍不推进。
     * 修复：先唤醒屏幕再启动安装，并用 SCREEN_ON 广播兜底，确保开屏后也能推进（只安装一次）。
     */
    private void installWhenScreenOn(File file) {
        final AtomicBoolean installed = new AtomicBoolean(false);
        final PowerManager.WakeLock[] wakeLock = new PowerManager.WakeLock[1];
        Runnable install = () -> {
            if (installed.compareAndSet(false, true)) {
                releaseWakeLock(wakeLock[0]);
                FileUtil.openFile(file);
            }
        };
        if (isScreenOn()) {
            install.run();
            return;
        }
        wakeLock[0] = acquireWakeLock();
        // 延迟启动，等屏幕点亮动画完成后再拉起安装界面
        App.post(install, 1500);
        // 兜底：若延迟后屏幕仍未点亮，监听 SCREEN_ON 广播，亮屏即启动安装
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    App.removeCallbacks(install);
                    install.run();
                    try {
                        context.unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                }
            }
        };
        try {
            App.get().registerReceiver(receiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Exception ignored) {
        }
        // 终极兜底：30s 内既未亮屏也未安装，强制尝试一次，避免永久卡死
        App.post(() -> {
            if (!installed.get()) {
                install.run();
                try {
                    App.get().unregisterReceiver(receiver);
                } catch (Exception ignored) {
                }
            }
        }, 30000);
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) App.get().getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    /** 唤醒屏幕，最多亮 10s（足够拉起安装界面），非引用计数。 */
    private PowerManager.WakeLock acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) App.get().getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "CrabTV:Updater");
            lock.setReferenceCounted(false);
            lock.acquire(10000);
            return lock;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void releaseWakeLock(PowerManager.WakeLock lock) {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
    }
}
