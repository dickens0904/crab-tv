package com.fongmi.android.tv.ui.dialog;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogPasswordBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 6 位数字密码输入（自绘数字键盘）。
 *
 * <p>2026-10-25: 电视端用系统软键盘会全屏弹出、还挡住对话框按钮，遥控器也不直观 →
 * 改成对话框内自绘 0-9 键盘：电视用方向键移动 + 数字键直输，手机直接点。
 */
public final class PasswordDialog {

    public interface OnPasswordListener {
        void onPassword(String password);
    }

    private final StringBuilder value = new StringBuilder();
    private DialogPasswordBinding binding;

    private PasswordDialog() {
    }

    public static void show(FragmentActivity activity, int titleRes, int hintRes, OnPasswordListener listener) {
        new PasswordDialog().build(activity, titleRes, hintRes, listener);
    }

    private void build(FragmentActivity activity, int titleRes, int hintRes, OnPasswordListener listener) {
        binding = DialogPasswordBinding.inflate(LayoutInflater.from(activity));
        binding.hint.setText(hintRes);
        binding.password.setText(mask());
        buildKeypad();

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(titleRes)
                .setView(binding.getRoot())
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (listener != null) listener.onPassword(value.toString());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // 2026-10-25：遥控器数字键直接输入（电视端最自然的输密码方式），退格删除
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                if (value.length() < 6) value.append((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
                binding.password.setText(mask());
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (value.length() > 0) value.deleteCharAt(value.length() - 1);
                binding.password.setText(mask());
                return true;
            }
            return false;
        });
        dialog.show();
    }

    /** 自绘 0-9 键盘：1-9 / [清空] 0 [删除]（4 行 × 3 列，按钮固定高度）。 */
    private void buildKeypad() {
        LinearLayout grid = binding.keypad;
        grid.removeAllViews();
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(grid.getContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int col = 0; col < 3; col++) {
                String text;
                int textRes = 0;
                if (row < 3) {
                    text = String.valueOf(row * 3 + col + 1);
                } else if (col == 0) {
                    text = "";
                    textRes = R.string.parent_clear;
                } else if (col == 1) {
                    text = "0";
                } else {
                    text = "";
                    textRes = R.string.parent_delete;
                }
                line.addView(key(text, textRes));
            }
            grid.addView(line);
        }
    }

    private MaterialButton key(String text) {
        return key(text, 0, false);
    }

    private MaterialButton key(String text, int textRes) {
        return key(text, textRes, false);
    }

    private MaterialButton key(String text, int textRes, boolean primary) {
        MaterialButton btn = new MaterialButton(binding.getRoot().getContext());
        btn.setText(textRes == 0 ? text : binding.getRoot().getContext().getString(textRes));
        btn.setTextSize(16);
        btn.setGravity(Gravity.CENTER);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        // 固定高度：GridLayout 默认会把行拉伸到填满，导致 4-9 那行被挤出屏幕
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ResUtil.dp2px(40), 1f);
        params.setMargins(ResUtil.dp2px(4), ResUtil.dp2px(3), ResUtil.dp2px(4), ResUtil.dp2px(3));
        btn.setLayoutParams(params);
        btn.setOnClickListener(v -> {
            if (primary) {
                // 确定：关闭对话框由 MaterialAlertDialog 的 positive 按钮处理，这里不重复
                return;
            }
            if (textRes == R.string.parent_clear) {
                value.setLength(0);
            } else if (textRes == R.string.parent_delete) {
                if (value.length() > 0) value.deleteCharAt(value.length() - 1);
            } else if (text.length() == 1 && Character.isDigit(text.charAt(0))) {
                if (value.length() < 6) value.append(text);
            }
            binding.password.setText(mask());
        });
        return btn;
    }

    private String mask() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) sb.append('●');
        return sb.toString();
    }
}
