package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.DialogReceiveBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;

public class ReceiveDialog extends BaseBottomSheetDialog {

    private DialogReceiveBinding binding;
    private CastEvent event;

    public static ReceiveDialog create() {
        return new ReceiveDialog();
    }

    public ReceiveDialog event(CastEvent event) {
        this.event = event;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof ReceiveDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    public void show(Fragment fragment) {
        for (Fragment f : fragment.getChildFragmentManager().getFragments()) if (f instanceof ReceiveDialog) return;
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogReceiveBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        // 2026-11-08 修复：纯配置投屏（不带 history）时 event.history() 为 null，
        // 直接 item.getVodName() 会 NPE。此时展示配置名而非影片名，并仅用于切源。
        History item = event.history();
        if (item != null) {
            binding.name.setText(item.getVodName());
            ImgUtil.load(item.getVodName(), item.getVodPic(), binding.image);
        } else {
            binding.name.setText(event.config() != null && event.config().getName() != null ? event.config().getName() : "配置");
        }
        binding.from.setText(event.device() != null ? event.device().getName() : "");
    }

    @Override
    protected void initEvent() {
        binding.frame.setOnClickListener(v -> onReceiveCast());
    }

    private void showProgress() {
        binding.frame.setEnabled(false);
        binding.play.setVisibility(View.GONE);
        binding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        binding.frame.setEnabled(true);
        binding.play.setVisibility(View.VISIBLE);
        binding.progress.getRoot().setVisibility(View.GONE);
    }

    private void onReceiveCast() {
        // 2026-11-08 修复：无 history 时只切源、不播放，避免 VideoActivity.cast(null) NPE。
        if (event.history() != null && VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(requireActivity(), event.history());
            dismiss();
        } else {
            showProgress();
            VodConfig.load(event.config(), getCallback());
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                onReceiveCast();
                hideProgress();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                hideProgress();
            }
        };
    }
}
