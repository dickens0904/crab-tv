package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogNetdiskAuthBinding;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class NetdiskAuthDialog extends BaseAlertDialog {

    // 主机网盘授权配对服务地址：与 I:/Temp/opencode/live_source_db/pair_server.py 同源
    // （该服务监听 0.0.0.0:8098，与 8099 静态服务器同 docroot，直写 tvfan/Cloud-drive.txt）。
    // 增强项（可选）：改由 VodConfig.getUrl() 推导——取 scheme://host:port，将 :8099 替换为
    // :8098 并追 /pair，使主机 IP/端口变动时无需改码。
    private static final String PAIR_URL = "http://192.168.31.29:8098/pair";

    private DialogNetdiskAuthBinding binding;

    public static NetdiskAuthDialog create() {
        return new NetdiskAuthDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNetdiskAuthBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.code.setImageBitmap(QRCode.getBitmap(PAIR_URL, 220, 1));
        binding.info.setText(ResUtil.getString(R.string.netdisk_auth_info, PAIR_URL));
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(this::onNegative);
    }

    private void onNegative(View view) {
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
    }
}
