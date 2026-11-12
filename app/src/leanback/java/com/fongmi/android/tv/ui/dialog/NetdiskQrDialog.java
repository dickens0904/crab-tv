package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Drive;
import com.fongmi.android.tv.bean.DriveManager;
import com.fongmi.android.tv.bean.auth.QuarkAuth;
import com.fongmi.android.tv.bean.auth.UcAuth;
import com.fongmi.android.tv.databinding.DialogNetdiskQrBinding;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

public class NetdiskQrDialog extends BaseAlertDialog {

    private DialogNetdiskQrBinding binding;
    private String type;
    private Runnable onDone;

    public static NetdiskQrDialog create(String type) {
        NetdiskQrDialog d = new NetdiskQrDialog();
        d.type = type;
        return d;
    }

    public void show(FragmentActivity activity, Runnable onDone) {
        this.onDone = onDone;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNetdiskQrBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.info.setText("正在生成二维码…");
        binding.negative.setOnClickListener(v -> dismiss());
        Task.execute(() -> {
            try {
                generate();
            } catch (Throwable e) {
                App.post(() -> binding.info.setText("生成失败：" + e.getMessage()));
            }
        });
    }

    private void generate() throws Throwable {
        DriveManager.LoginCallback cb = new DriveManager.LoginCallback() {
            @Override
            public void onLoginFailed() {
                App.post(() -> {
                    binding.info.setText("登录失败或超时，请重试");
                    Notify.show("云盘授权失败");
                });
            }

            @Override
            public void onLoginSuccess(String token) {
                App.post(() -> {
                    binding.info.setText("授权成功");
                    Notify.show("云盘授权成功");
                    if (onDone != null) onDone.run();
                    dismiss();
                });
            }
        };

        if ("aliyun".equals(type)) {
            Map<String, String> m = DriveManager.get().generateQrCode(Drive.TYPE_ALIYUN);
            String qr = m.get("qrCodeUrl");
            String sid = m.get("sid");
            App.post(() -> {
                binding.code.setImageBitmap(QRCode.getBitmap(qr, 240, 1));
                binding.info.setText("请使用手机扫阿里云盘二维码登录");
            });
            DriveManager.get().startAliyunLogin(sid, cb);
        } else if ("baidu".equals(type)) {
            String dc = DriveManager.get().baiduGetDeviceCode();
            JsonObject j = JsonParser.parseString(dc).getAsJsonObject();
            String qr = j.has("qrcode_url") ? j.get("qrcode_url").getAsString() : (j.has("verification_url") ? j.get("verification_url").getAsString() : "");
            String deviceCode = j.has("device_code") ? j.get("device_code").getAsString() : "";
            App.post(() -> {
                if (!qr.isEmpty()) binding.code.setImageBitmap(QRCode.getBitmap(qr, 240, 1));
                binding.info.setText("请使用手机扫百度网盘二维码登录");
            });
            DriveManager.get().startBaiduLogin(deviceCode, cb);
        } else if ("quark".equals(type)) {
            Map<String, String> m = QuarkAuth.get().getQrToken();
            String qr = m.get("qrUrl");
            String token = m.get("token");
            App.post(() -> {
                binding.code.setImageBitmap(QRCode.getBitmap(qr, 240, 1));
                binding.info.setText("请使用手机扫夸克网盘二维码登录");
            });
            QuarkAuth.get().startLogin(token, cb);
        } else if ("uc".equals(type)) {
            Map<String, String> m = UcAuth.get().getQrToken();
            String qr = m.get("qrUrl");
            String token = m.get("token");
            App.post(() -> {
                binding.code.setImageBitmap(QRCode.getBitmap(qr, 240, 1));
                binding.info.setText("请使用手机扫UC网盘二维码登录");
            });
            UcAuth.get().startLogin(token, cb);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
    }
}
