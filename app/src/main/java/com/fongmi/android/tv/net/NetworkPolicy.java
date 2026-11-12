package com.fongmi.android.tv.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import android.content.ComponentName;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;

import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-17: 离网直连策略。订阅里的 proxy(指向家庭局域网代理)与 hosts(主机名→局域网 IP)
 * 注入到 OkHttp 单例；一旦设备离开家庭 WiFi(切 5G/连别人家 WiFi)这些地址不可达，App 即"不能连"。
 * 本类按"代理策略"设置门控 setProxy/setHosts：
 *   0 全局代理 —— 沿用订阅(默认，盒子无变化)
 *   1 仅家庭WiFi用代理 —— 连上家庭 SSID 才套用订阅代理/DNS hosts，否则清空直连
 *   2 始终直连 —— 永远不管订阅代理/DNS hosts
 * 同时监听网络变化，实时重算，无需重载订阅。
 */
public class NetworkPolicy {

    private static final NetworkPolicy INSTANCE = new NetworkPolicy();

    private static final String TAG = "NetworkPolicy";

    public static NetworkPolicy get() {
        return INSTANCE;
    }

    private volatile List<Proxy> lastProxy = new ArrayList<>();
    private volatile List<String> lastHosts = new ArrayList<>();
    private volatile boolean registered;
    private volatile long lastTvProxyLaunch;

    private static final String TVPROXY_PKG = "com.tvproxy.box";
    private static final String TVPROXY_ACT = "com.tvproxy.box.ui.MainActivity";
    private static final int TVPROXY_PORT = 7890;

    private NetworkPolicy() {
    }

    public void init() {
        if (registered) return;
        registered = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        try {
            App.get().registerReceiver(receiver, filter);
        } catch (Throwable ignored) {
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reapply();
        }
    };

    /** 订阅解析时调用：缓存并据当前策略应用代理/DNS hosts。 */
    public void setProxy(List<Proxy> proxy) {
        lastProxy = proxy == null ? new ArrayList<>() : new ArrayList<>(proxy);
        reapply();
    }

    public void setHosts(List<String> hosts) {
        lastHosts = hosts == null ? new ArrayList<>() : new ArrayList<>(hosts);
        reapply();
    }

    /** 网络或策略变化时调用：先清空再按需套用，避免重复累积。 */
    public synchronized void reapply() {
        boolean useProxy = shouldUseProxy();
        Log.d(TAG, "reapply useProxy=" + useProxy + " mode=" + Setting.getProxyMode()
                + " homeWifi=" + isHomeWifi() + " tvproxyInstalled=" + isTvProxyInstalled());
        OkHttp.selector().clear();
        OkHttp.dns().clear();
        if (useProxy) {
            if (!lastProxy.isEmpty()) OkHttp.selector().addAll(lastProxy);
            if (!lastHosts.isEmpty()) OkHttp.dns().addAll(lastHosts);
        }
        // 2026-10-17: 盒上 tvproxy(com.tvproxy.box) 监听 127.0.0.1:7890 出公网；
        // 它崩溃/被停时 7890 死端口 → 走它的公网站源全挂。网络一变就顺带确认代理在跑。
        ensureTvProxy();
    }

    private boolean shouldUseProxy() {
        switch (Setting.getProxyMode()) {
            case 2:
                return false;
            case 1:
                return isHomeWifi();
            default:
                return true;
        }
    }

    public boolean isHomeWifi() {
        String ssids = Setting.getHomeSsids();
        if (ssids == null || ssids.trim().isEmpty()) return false;
        String current = currentSsid();
        if (current == null) return false;
        String cur = current.replace("\"", "").trim().toLowerCase();
        for (String raw : ssids.split("[,;\\n]")) {
            String s = raw.replace("\"", "").trim().toLowerCase();
            if (!s.isEmpty() && s.equals(cur)) return true;
        }
        return false;
    }

    /** 获取当前 WiFi SSID(已去引号)；不在 WiFi 或未连返回 null。 */
    public String currentSsid() {
        try {
            WifiManager wm = (WifiManager) App.get().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null || ssid.equals("<unknown ssid>") || ssid.equals("0x")) return null;
            return ssid;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 是否当前以蜂窝(5G/4G)为主网络。 */
    public boolean isCellular() {
        try {
            ConnectivityManager cm = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_MOBILE;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 订阅 URL 是否指向家庭/局域网(私网)地址：127.0.0.1 / 192.168.x / 10.x / 172.16-31.x */
    public boolean isLanUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        if (u.contains("127.0.0.1") || u.contains("127.0.0.")) return true;
        if (u.matches(".*192\\.168\\.\\d{1,3}\\.\\d{1,3}.*")) return true;
        if (u.matches(".*10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*")) return true;
        if (u.matches(".*172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}.*")) return true;
        return false;
    }

    /**
     * 家庭局域网订阅的加载门控：能连通(在家内网)才加载，连不通(离网/外网)直接跳过，
     * 避免 App 卡在拉取不可达的 192.168.x 地址上。无需用户预先配置家庭 SSID。
     */
    public boolean shouldSkipLanOffHome(String url) {
        if (!isLanUrl(url)) return false;
        boolean reachable = lanReachable(url);
        Log.d(TAG, "homeLanGating url=" + url + " reachable=" + reachable + " skip=" + (!reachable));
        return !reachable;
    }

    private boolean lanReachable(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            int port = u.getPort();
            if (port == -1) port = "https".equalsIgnoreCase(u.getProtocol()) ? 443 : 80;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 800);
                return true;
            }
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 盒上 tvproxy(com.tvproxy.box) 看门狗：127.0.0.1:7890 死端口时由本 App 打开它，
     * 请用户在其界面点一次「启动」(首次需授权 VPN)。TVPROXY 为 VpnService，Android 不允许静默自启，
     * 故只能拉起其界面由用户确认；授权一次后开机可自启，此后 7890 常驻本方法不再触发。
     */
    /**
     * 盒上 tvproxy(com.tvproxy.box) 提示：127.0.0.1:7890 死端口时提醒用户。
     *
     * 2026-10-24 重写：原实现会 startActivity 把 tvproxy 界面拉到前台，每 60s 一次，
     * 电视看直播时反复被"电视代理"界面盖住（观感极差），而且**与是否真的需要代理无关**。
     * 现状：tvproxy 只在订阅里带 proxy 节点、且该节点指向 7890 时才需要；
     * 局域网源与国内直连源（CCTV 等）根本不需要，代理断开也照样播。
     * 因此改为：**永不抢前台**，只在"当前订阅确实带代理 且 代理没起"时弹一次 Toast，
     * 由用户自己决定要不要开。
     */
    public void ensureTvProxy() {
        if (!isTvProxyInstalled()) {
            Log.d(TAG, "ensureTvProxy skip: tvproxy not installed");
            return;
        }
        if (proxyUp()) {
            Log.d(TAG, "ensureTvProxy skip: already up");
            return;
        }
        if (!shouldUseProxy()) {
            Log.d(TAG, "ensureTvProxy skip: current policy does not use proxy");
            return;
        }
        if (lastProxy.isEmpty() && lastHosts.isEmpty()) {
            Log.d(TAG, "ensureTvProxy skip: current config has no proxy/hosts -> not needed");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTvProxyLaunch < 600000) return;   // 10 分钟一次，勿频繁打扰
        lastTvProxyLaunch = now;
        Log.d(TAG, "ensureTvProxy notify only (no auto-launch)");
        Notify.show("tvproxy 未运行：仅访问被墙源时才需要，可手动打开「电视代理」启动");
    }

    private boolean proxyUp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", TVPROXY_PORT), 500);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** tvproxy(com.tvproxy.box) 是否已安装：未装则看门狗不触发(手机纯直连场景)。 */
    private boolean isTvProxyInstalled() {
        try {
            App.get().getPackageManager().getPackageInfo(TVPROXY_PKG, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
