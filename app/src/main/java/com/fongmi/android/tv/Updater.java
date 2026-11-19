package com.fongmi.android.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.util.Log;
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
    /** 是否由用户手动点设置页触发（只有手动检查才提示「已是最新版本」）。 */
    private volatile boolean manual;

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
        this.manual = true;
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
            // 双重判定：版本号 + 版本名。只比 code 时，自编译的高版本（名字更高、code 更低）
            // 会弹出「发现新版本 5.6.18」这种倒退提示；名字不高于本地一律视为已最新。
            if (code <= BuildConfig.VERSION_CODE || code == Setting.getSkipCode() || compareVersion(name, BuildConfig.VERSION_NAME) <= 0) {
                notifyLatest();
                return;
            }
            currentCode = code;
            App.post(() -> show(activity, name, desc));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 手动检查且无新版本时给个明确反馈，避免用户以为点了没反应。 */
    private void notifyLatest() {
        if (!manual) return;
        manual = false;
        App.post(() -> Notify.show(R.string.update_latest));
    }

    /** 逐段比较语义化版本号，a>b 返回 1，相等 0，a<b 返回 -1；非数字段按 0 处理。 */
    private int compareVersion(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return 0;
        String[] x = a.trim().split("\\.");
        String[] y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int left = i < x.length ? parseInt(x[i]) : 0;
            int right = i < y.length ? parseInt(y[i]) : 0;
            if (left != right) return left > right ? 1 : -1;
        }
        return 0;
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
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
        String error = verifyApk(file);
        dismiss();
        if (error != null) {
            Notify.show(error);
            try {
                if (file != null && file.exists()) file.delete();
            } catch (Exception ignored) {
            }
            return;
        }
        installWhenScreenOn(file);
    }

    /**
     * 交给系统安装器前做最后一道校验。
     * 文件损坏 / 串了版本时，系统安装器只会停在「正在扫描安装包」不动、没有任何反馈；
     * 这里提前解析 AndroidManifest，坏包当场报错重下，不把脏文件丢给安装器。
     */
    private String verifyApk(File file) {
        try {
            if (file == null || !file.exists() || file.length() < 1024 * 1024) return "安装包下载不完整，请重新下载";
            PackageManager pm = App.get().getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (info == null) return "安装包校验失败（文件损坏），请重新下载";
            if (!App.get().getPackageName().equals(info.packageName)) return "下载到的不是蟹老板TV 安装包，请重新下载";
            if (info.versionCode <= BuildConfig.VERSION_CODE) return "下载到的版本不高于当前版本，请重新下载";
            Log.i(TAG, "apk ok pkg=" + info.packageName + " code=" + info.versionCode + " name=" + info.versionName + " size=" + file.length());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "verify fail " + e);
            return "安装包校验失败，请重新下载";
        }
    }

    /** 安装器扫描大包（本包 ~77MB）耗时可能远超 10 秒，亮屏时间要覆盖到用户点完「安装」。 */
    private static final long WAKELOCK_MS = 300_000;
    private static final long WAKE_DELAY_MS = 1500;
    private static final long FALLBACK_MS = 30_000;
    private static final String TAG = "CrabTV-UPD";

    /**
     * 下载完成后启动安装。屏幕待机时，后台 startActivity 启动的安装界面不会显示，
     * 且该一次性 Intent 不会在亮屏后重试，导致进度卡在 100%、开屏后仍不推进。
     * 修复：先唤醒屏幕再启动安装，并用 SCREEN_ON 广播兜底，确保开屏后也能推进（只安装一次）。
     * 注意：唤醒后不要立刻释放，否则安装器在扫描大包时灭屏会被系统冻结（表现为卡在扫描中）。
     */
    private void installWhenScreenOn(File file) {
        final AtomicBoolean installed = new AtomicBoolean(false);
        Runnable install = () -> {
            if (installed.compareAndSet(false, true)) FileUtil.openFile(file);
        };
        if (isScreenOn()) {
            install.run();
            return;
        }
        acquireWakeLock();
        // 延迟启动，等屏幕点亮动画完成后再拉起安装界面
        App.post(install, WAKE_DELAY_MS);
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
        }, FALLBACK_MS);
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) App.get().getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    /** 唤醒屏幕，保持 WAKELOCK_MS（覆盖安装器扫描＋用户确认），到期自动释放，非引用计数。 */
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) App.get().getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "CrabTV:Updater");
            lock.setReferenceCounted(false);
            lock.acquire(WAKELOCK_MS);
        } catch (Exception ignored) {
        }
    }
}
