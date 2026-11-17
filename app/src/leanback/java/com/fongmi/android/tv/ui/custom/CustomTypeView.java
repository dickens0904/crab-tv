package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.KeyUtil;
import com.google.android.material.textview.MaterialTextView;

public class CustomTypeView extends MaterialTextView {

    /** 两次确定键间隔小于该值视为「连按两次」，弹出排序菜单。单击也延迟这么久派发。 */
    private static final long DOUBLE_TAP_MS = 600;
    /** 长按判定：遥控器按住确定键时系统持续下发 repeat，达到该次数即视为长按。 */
    private static final int LONG_PRESS_REPEAT = 2;

    private final Runnable pendingClick = this::performClick;

    private Listener listener;
    private boolean coolDown;
    private long lastEnterDownMs;
    private boolean sortFired;

    public CustomTypeView(@NonNull Context context) {
        super(context);
    }

    public CustomTypeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private boolean hasEvent(KeyEvent event) {
        return !coolDown && KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (listener != null && hasEvent(event)) return onKeyDown();
        if (listener != null && KeyUtil.isEnterKey(event) && handleEnter(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * 分栏上的排序手势：长按确定键（repeat 累积 / 系统长按标记）或 400ms 内连按两次确定键。
     * 完全接管确定键：单击延迟派发（否则双击的第一次会误切换筛选），
     * 手势命中时取消待派发的单击并消费本次按键序列。
     */
    private boolean handleEnter(KeyEvent event) {
        if (KeyUtil.isActionDown(event)) {
            if (event.getRepeatCount() >= LONG_PRESS_REPEAT || event.isLongPress()) {
                App.removeCallbacks(pendingClick);
                lastEnterDownMs = 0;
                return fireSort();
            }
            if (event.getRepeatCount() > 0) return true;
            long now = SystemClock.uptimeMillis();
            boolean doubleTap = now - lastEnterDownMs < DOUBLE_TAP_MS;
            lastEnterDownMs = now;
            if (doubleTap) {
                App.removeCallbacks(pendingClick);
                lastEnterDownMs = 0;
                return fireSort();
            }
            sortFired = false;
            return true;
        }
        if (KeyUtil.isActionUp(event)) {
            if (sortFired) {
                sortFired = false;
                return true;
            }
            App.removeCallbacks(pendingClick);
            App.post(pendingClick, DOUBLE_TAP_MS);
            return true;
        }
        return false;
    }

    /** 一次长按会产生多次 repeat，用 sortFired 保证只弹一次菜单。 */
    private boolean fireSort() {
        if (sortFired) return true;
        sortFired = true;
        listener.onSort();
        return true;
    }

    private boolean onKeyDown() {
        App.post(() -> coolDown = false, 3000);
        listener.onRefresh();
        coolDown = true;
        return true;
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        App.post(() -> coolDown = false, 500);
        if (focused) coolDown = true;
        // 焦点切换时重置双击/长按状态，避免跨栏目误判
        lastEnterDownMs = 0;
        sortFired = false;
        App.removeCallbacks(pendingClick);
    }

    @Override
    protected void onDetachedFromWindow() {
        App.removeCallbacks(pendingClick);
        super.onDetachedFromWindow();
    }

    public interface Listener {

        void onRefresh();

        void onSort();
    }
}
