package com.fongmi.android.tv.net;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 网盘授权辅助（无障碍服务）。
 *
 * <p>网盘的授权（夸克 CAS 扫码、百度 device code、阿里 OAuth）都要求用户在自己的 App 里
 * 点一次「确认」，这一步按 OAuth 设计无法用 API 绕过。这个服务的作用是：授权会话期间
 * 在目标网盘 App 的界面上自动点击「确认 / 允许 / 同意」，把用户从 3-4 次点击降到 0 次。
 *
 * <p>严格护栏（见 {@link NetdiskAuth}）：只在 {@code NetdiskAuth} 会话期内、只在目标网盘包名内、
 * 只点白名单文案、绝不碰支付/会员/购买类按钮，会话过期自动失效。
 */
public class NetdiskAuthService extends AccessibilityService {

    private static final String TAG = "CrabTV-AUTH";
    private static volatile boolean connected;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        Log.i(TAG, "service connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        Log.i(TAG, "service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            NetdiskAuth.get().onEvent(this, event);
        } catch (Throwable e) {
            Log.w(TAG, "event failed " + e);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
