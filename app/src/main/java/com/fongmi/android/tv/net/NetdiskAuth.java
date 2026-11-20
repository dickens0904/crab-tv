package com.fongmi.android.tv.net;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网盘授权会话：管理「打开网盘 App → 自动确认授权」这一小段流程。
 *
 * <p>为什么需要它：夸克（CAS 扫码）、百度（device code）、阿里（OAuth）的授权都要求
 * 用户在网盘 App 里点一次「确认/允许」，这是 OAuth 的安全设计，third-party 无法用 API 跳过。
 * 手机单设备的痛点更明显——二维码显示在同一块屏幕上，用户没法自己扫自己。
 *
 * <p>本类的做法：
 * <ol>
 *   <li>把二维码存进相册，用户可在网盘 App 的「扫一扫 → 相册」里直接选；</li>
 *   <li>会话期内通过无障碍服务，在目标网盘 App 里自动点「扫一扫 / 相册 / 确认 / 允许」。</li>
 * </ol>
 *
 * <p>护栏：只在会话有效期内、只在目标包名内、只点白名单文案，且黑名单里的词
 * （支付/开通/购买/续费/会员/充值）一律不碰；同一按钮 10 秒内只点一次，避免死循环。
 */
public class NetdiskAuth {

    private static final String TAG = "CrabTV-AUTH";
    private static final long SESSION_MS = 150_000L;
    private static final long TICK_MS = 500L;
    private static final long SAME_LABEL_MS = 10_000L;
    private static final int MAX_DEPTH = 40;

    private static final String[] CONFIRM = {"确认登录", "确认授权", "确认", "允许", "同意", "确定", "立即登录", "继续"};
    private static final String[] SCAN = {"扫一扫", "扫二维码", "扫描二维码", "扫码"};
    private static final String[] ALBUM = {"从相册选择", "相册选取", "从相册", "相册"};
    private static final String[] BLACK = {"支付", "开通", "购买", "续费", "充值", "会员", "领取", "广告"};

    private static final NetdiskAuth INSTANCE = new NetdiskAuth();

    public static NetdiskAuth get() {
        return INSTANCE;
    }

    private final Set<String> targets = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> clicked = new ConcurrentHashMap<>();
    private volatile long until;
    private volatile long lastTick;

    private NetdiskAuth() {
    }

    /** 开始一次授权会话（打开网盘 App 前调用）。 */
    public void start(Collection<String> packages, long durationMs) {
        targets.clear();
        if (packages != null) targets.addAll(packages);
        clicked.clear();
        lastTick = 0;
        until = System.currentTimeMillis() + (durationMs > 0 ? durationMs : SESSION_MS);
        Log.i(TAG, "session start " + targets + " for " + durationMs + "ms");
    }

    public void stop() {
        until = 0;
        targets.clear();
        Log.i(TAG, "session stop");
    }

    public boolean active() {
        return System.currentTimeMillis() < until && !targets.isEmpty();
    }

    public void onEvent(AccessibilityService service, AccessibilityEvent event) {
        if (!active()) return;
        CharSequence name = event.getPackageName();
        if (name == null || !targets.contains(name.toString())) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastTick < TICK_MS) return;
        lastTick = now;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;
        String pkg = name.toString();
        // 优先级：授权确认 > 进扫一扫 > 进相册。前一步没命中才做下一步，避免乱点。
        if (tryClick(root, pkg, CONFIRM)) return;
        if (tryClick(root, pkg, SCAN)) return;
        tryClick(root, pkg, ALBUM);
    }

    private boolean tryClick(AccessibilityNodeInfo root, String pkg, String[] words) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            String label = labelOf(node);
            if (TextUtils.isEmpty(label)) continue;
            if (isBlocked(label)) continue;
            for (String word : words) {
                if (!label.contains(word)) continue;
                if (recentlyClicked(pkg, label)) continue;
                AccessibilityNodeInfo clickable = findClickable(node);
                if (clickable == null) continue;
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked.put(pkg + "|" + label, System.currentTimeMillis());
                    Log.i(TAG, "click [" + pkg + "] " + label);
                    return true;
                }
            }
        }
        return false;
    }

    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > MAX_DEPTH || out.size() > 800) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            try {
                collect(node.getChild(i), out, depth + 1);
            } catch (Throwable ignored) {
            }
        }
    }

    private String labelOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (!TextUtils.isEmpty(text)) return text.toString().trim();
        CharSequence desc = node.getContentDescription();
        if (!TextUtils.isEmpty(desc)) return desc.toString().trim();
        return null;
    }

    private boolean isBlocked(String label) {
        for (String word : BLACK) if (label.contains(word)) return true;
        return false;
    }

    private boolean recentlyClicked(String pkg, String label) {
        Long at = clicked.get(pkg + "|" + label);
        return at != null && System.currentTimeMillis() - at < SAME_LABEL_MS;
    }

    /** 文本节点本身常常不可点，往上找最近的可点击祖先。 */
    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable() && current.isEnabled()) return current;
            current = current.getParent();
        }
        return null;
    }

    // ---------------------------------------------------------------- 工具方法

    /** 无障碍服务是否已开启（服务已连接，或系统设置里勾选过）。 */
    public static boolean isServiceEnabled(Context context) {
        if (NetdiskAuthService.isConnected()) return true;
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            String name = context.getPackageName() + "/" + NetdiskAuthService.class.getName();
            return enabled.contains(name);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 把二维码存进相册，供网盘 App 的「扫一扫 → 相册」直接选取。
     * 单设备场景下这是唯一不依赖第二台手机的扫码方式。
     */
    public static boolean saveQrToGallery(Context context, Bitmap bitmap, String name) {
        if (bitmap == null) return false;
        OutputStream out = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CrabTV");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            out = context.getContentResolver().openOutputStream(uri);
            if (out == null) return false;
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
            Log.i(TAG, "qr saved " + uri);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "qr save failed " + e);
            return false;
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
