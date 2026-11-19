package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.hook.Hook;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 2026-10-11: one-shot self-heal for rows written before Config.insert() started
        // stamping time. Those rows sit at time=0 and ConfigDao.findOne sorts by time DESC,
        // so restored sources could never win the home-source selection. The UPDATE is a
        // no-op once no time=0 rows remain, so running it on every start costs nothing and
        // needs no "already done" marker. Never let it throw: a heal failure must not block
        // app startup.
        try {
            Config.healEmptyTime();
            Config.seedBuiltin();
        } catch (Throwable ignored) {
        }
        Notify.createChannel();
        registerActivityLifecycleCallbacks(this);
        // 2026-11-08: 预热配置订阅的 HTTP 缓存。client() 是懒加载单例，必须等
        // attachBaseContext 的 Init.set(base) 完成后再建，否则缓存会永久失效。
        // 之后 OkHttp 对订阅自动做条件请求：无更新 304 零下载，有更新才拉新内容。
        try {
            com.github.catvod.net.OkHttp.client();
            android.util.Log.d("TV-ConfigCache", "warmup " + com.github.catvod.net.OkHttp.cacheStats());
        } catch (Throwable ignored) {
        }
        // 2026-11-12: 盒子版评分补全——导入种子 + 每日一次后台扫描（flavor 门控在方法内）。
        try {
            com.fongmi.android.tv.utils.RatingEnricher.scheduleSweep();
        } catch (Throwable ignored) {
        }
        // 2026-11-13: 手机版离线缓存——载入索引并自动续跑未完成的下载。
        // 仅 mobile 触发：盒子版（leanback）不需要缓存下载，不能在 TV 端启动任何下载行为。
        // 必须主动触发：缓存索引是惰性的，否则重启后排队任务永远等不到启动。
        try {
            if (com.fongmi.android.tv.utils.Util.isMobile()) {
                com.fongmi.android.tv.offline.OfflineCacheManager.get().resume();
            }
        } catch (Throwable ignored) {
        }
        // 2026-10-17: 离网直连策略——监听网络变化实时重算代理/DNS hosts
        com.fongmi.android.tv.net.NetworkPolicy.get().init();
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
        // 2026-10-17: 进前台顺带确认盒上 tvproxy(127.0.0.1:7890)在跑，死了就拉起
        com.fongmi.android.tv.net.NetworkPolicy.get().ensureTvProxy();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}