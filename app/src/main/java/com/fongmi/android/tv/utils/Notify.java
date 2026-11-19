package com.fongmi.android.tv.utils;

import android.Manifest;
import android.os.Looper;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class Notify {

    public static final String DEFAULT = "default";
    public static final int ID = 9527;
    private AlertDialog mDialog;
    private Toast mToast;

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void createChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build());
    }

    public static String getError(int resId, Throwable e) {
        if (TextUtils.isEmpty(e.getMessage())) return ResUtil.getString(resId);
        return ResUtil.getString(resId) + "\n" + e.getMessage();
    }

    public static void show(Notification notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(App.get()).notify(ID, notification);
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }

    /**
     * 2026-11-09 修复：Toast 只能在主线程 show。配置解析(initList/initSite)跑在后台线程，
     * 其中 NetworkPolicy.ensureTvProxy() 会调 Notify.show(...)，非主线程弹 Toast 在 Android 11+
     * 直接抛 "Can't toast on a thread that has not called Looper.prepare()"，
     * 异常沿 parseConfig 冒出去 → **整个 sites 数组被丢弃** → 首页/分类全空，
     * 且现象只出现在"订阅里带 hosts 字段"的配置上(不带 hosts 时 ensureTvProxy 提前 return)，极难定位。
     * 现统一在主线程执行，任何线程调用都安全。
     */
    public static void show(String text) {
        if (TextUtils.isEmpty(text)) return;
        if (Looper.myLooper() == Looper.getMainLooper()) get().makeText(text);
        else App.post(() -> get().makeText(text));
    }

    public static void progress(Context context) {
        dismiss();
        get().create(context);
    }

    public static void dismiss() {
        try {
            if (get().mDialog != null) get().mDialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    private void create(Context context) {
        ViewProgressBinding binding = ViewProgressBinding.inflate(LayoutInflater.from(context));
        mDialog = new MaterialAlertDialogBuilder(context).setView(binding.getRoot()).create();
        mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        mDialog.show();
    }

    private void makeText(String text) {
        if (mToast != null) mToast.cancel();
        mToast = Toast.makeText(App.get(), text, Toast.LENGTH_LONG);
        mToast.show();
    }
}
