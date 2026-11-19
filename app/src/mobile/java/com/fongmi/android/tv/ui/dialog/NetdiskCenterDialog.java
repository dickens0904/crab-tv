package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DriveManager;
import com.fongmi.android.tv.bean.auth.QuarkAuth;
import com.fongmi.android.tv.bean.auth.UcAuth;
import com.fongmi.android.tv.databinding.DialogNetdiskCenterBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class NetdiskCenterDialog extends BaseAlertDialog {

    private DialogNetdiskCenterBinding binding;

    public static NetdiskCenterDialog create() {
        return new NetdiskCenterDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNetdiskCenterBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        refresh();
    }

    @Override
    protected void initEvent() {
        binding.rowBaidu.setOnClickListener(v -> open("baidu"));
        binding.rowQuark.setOnClickListener(v -> open("quark"));
        binding.rowUc.setOnClickListener(v -> open("uc"));
        binding.rowAliyun.setOnClickListener(v -> open("aliyun"));
    }

    private void refresh() {
        binding.statusBaidu.setText(DriveManager.get().isBaiduLoggedIn() ? R.string.netdisk_status_logged : R.string.netdisk_status_none);
        binding.statusQuark.setText(QuarkAuth.get().isLogged() ? R.string.netdisk_status_logged : R.string.netdisk_status_none);
        binding.statusUc.setText(UcAuth.get().isLogged() ? R.string.netdisk_status_logged : R.string.netdisk_status_none);
        binding.statusAliyun.setText(DriveManager.get().isAliyunLoggedIn() ? R.string.netdisk_status_logged : R.string.netdisk_status_none);
    }

    private void open(String type) {
        NetdiskQrDialog.create(type).show(requireActivity(), this::refresh);
    }
}
