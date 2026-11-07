package com.fongmi.android.tv.ui.dialog;

import android.content.Intent;
import android.net.Uri;
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
    private String url;
    private String sid;
    private String deviceCode;
    private String loginToken;

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
        return builder().setView(getBinding().getRoot()).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        binding.info.setText("正在生成二维码…");
        Task.execute(() -> {
            try {
                generate();
            } catch (Throwable e) {
                App.post(() -> binding.info.setText("生成失败：" + e.getMessage()));
            }
        });
    }

    @Override
    protected void initEvent() {
        binding.openBtn.setOnClickListener(v -> openUrl());
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.9f);
    }

    private DriveManager.LoginCallback callback() {
        return new DriveManager.LoginCallback() {
            @Override
            public void onLoginFailed() {
                App.post(() -> binding.info.setText("登录失败或超时，请重试"));
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
    }

    private void generate() throws Throwable {
        DriveManager.LoginCallback cb = callback();
        if ("aliyun".equals(type)) {
            Map<String, String> m = DriveManager.get().generateQrCode(Drive.TYPE_ALIYUN);
            url = m.get("qrCodeUrl");
            sid = m.get("sid");
            App.post(() -> {
                binding.title.setText("阿里云盘授权");
                binding.openBtn.setText("打开授权页（备用）");
                binding.code.setImageBitmap(QRCode.getBitmap(url, 240, 1));
                binding.info.setText("请用阿里云盘 App「扫一扫」扫描上方二维码（可先截图，再从相册选择），确认后自动完成登录（二维码约5分钟内有效，超时请重试）");
                binding.openBtn.setEnabled(true);
            });
            DriveManager.get().startAliyunLogin(sid, cb);
        } else if ("baidu".equals(type)) {
            String dc = DriveManager.get().baiduGetDeviceCode();
            JsonObject j = JsonParser.parseString(dc).getAsJsonObject();
            url = j.has("qrcode_url") ? j.get("qrcode_url").getAsString() : (j.has("verification_url") ? j.get("verification_url").getAsString() : "");
            String userCode = j.has("user_code") ? j.get("user_code").getAsString() : "";
            deviceCode = j.has("device_code") ? j.get("device_code").getAsString() : "";
            String finalUrl = url;
            App.post(() -> {
                binding.title.setText("百度网盘授权");
                binding.openBtn.setText("打开百度授权页（备用）");
                if (!finalUrl.isEmpty()) binding.code.setImageBitmap(QRCode.getBitmap(finalUrl, 240, 1));
                if (!userCode.isEmpty()) {
                    binding.userCode.setVisibility(View.VISIBLE);
                    binding.userCode.setText("授权码：" + userCode);
                }
                binding.info.setText("请用百度网盘 App「扫一扫」扫描上方二维码，确认后自动完成登录");
                binding.openBtn.setEnabled(!finalUrl.isEmpty());
            });
            DriveManager.get().startBaiduLogin(deviceCode, cb);
        } else if ("quark".equals(type)) {
            Map<String, String> m = QuarkAuth.get().getQrToken();
            url = m.get("qrUrl");
            loginToken = m.get("token");
            App.post(() -> {
                binding.title.setText("夸克网盘授权");
                binding.openBtn.setText("打开夸克授权页（备用）");
                binding.code.setImageBitmap(QRCode.getBitmap(url, 240, 1));
                binding.info.setText("请用夸克 App「扫一扫」扫描上方二维码，确认后自动完成登录");
                binding.openBtn.setEnabled(true);
            });
            QuarkAuth.get().startLogin(loginToken, cb);
        } else if ("uc".equals(type)) {
            Map<String, String> m = UcAuth.get().getQrToken();
            url = m.get("qrUrl");
            loginToken = m.get("token");
            App.post(() -> {
                binding.title.setText("UC网盘授权");
                binding.openBtn.setText("打开UC授权页（备用）");
                binding.code.setImageBitmap(QRCode.getBitmap(url, 240, 1));
                binding.info.setText("请用UC App「扫一扫」扫描上方二维码，确认后自动完成登录");
                binding.openBtn.setEnabled(true);
            });
            UcAuth.get().startLogin(loginToken, cb);
        }
    }

    private void openUrl() {
        if (url == null || url.isEmpty()) {
            Notify.show("授权页未就绪");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable e) {
            Notify.show("无法打开授权页：" + e.getMessage());
        }
    }
}
