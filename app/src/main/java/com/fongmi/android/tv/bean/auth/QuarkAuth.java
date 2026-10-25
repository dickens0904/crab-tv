package com.fongmi.android.tv.bean.auth;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.DriveManager;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 夸克网盘扫码登录（CAS 设备流，逆向实现）。
 * 流程：getTokenForQrcodeLogin 取 token -> 二维码 su.quark.cn/...?token= -> 轮询 getServiceTicketByQrcodeToken 取 ST
 *       -> account/info?st= 取 Set-Cookie（登录态）落 Prefers。
 * 注：CAS 参数为社区逆向，盒子端联网实测可能需要微调（见任务书 §3/§7）。
 */
public class QuarkAuth {
    private static final String CLIENT_ID = "532";
    private static final String APP_KEY = "30001007";
    private static final String CAS_TOKEN = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?callback=cd&appKey=" + APP_KEY + "&client_id=" + CLIENT_ID + "&redirect=%2F&v=1.0&ssb=weblogin";
    private static final String QR_TPL = "https://su.quark.cn/4_eMHBJ?token=%s&ssb=weblogin";
    private static final String CAS_TICKET = "https://pan.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?callback=cd&qrcodeToken=%s&client_id=" + CLIENT_ID + "&v=1.0&appKey=" + APP_KEY;
    private static final String ACCOUNT_INFO = "https://pan.quark.cn/account/info?st=%s";
    private static final String PREF = "quark_cookie";
    private static volatile QuarkAuth instance;

    public static QuarkAuth get() {
        if (instance == null) {
            synchronized (QuarkAuth.class) {
                if (instance == null) instance = new QuarkAuth();
            }
        }
        return instance;
    }

    private String readToken(String body) {
        try {
            JsonObject j = JsonParser.parseString(body).getAsJsonObject();
            if (j.has("data") && j.getAsJsonObject("data").has("token")) return j.getAsJsonObject("data").get("token").getAsString();
            if (j.has("token")) return j.get("token").getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    private String readSt(String body) {
        try {
            JsonObject j = JsonParser.parseString(body).getAsJsonObject();
            if (j.has("data") && j.getAsJsonObject("data").has("st")) return j.getAsJsonObject("data").get("st").getAsString();
            if (j.has("st")) return j.get("st").getAsString();
        } catch (Exception ignored) {
        }
        return null;
    }

    public Map<String, String> getQrToken() throws Exception {
        Map<String, String> r = new HashMap<>();
        Request request = new Request.Builder().url(CAS_TOKEN).get().build();
        Response response = OkHttp.client().newCall(request).execute();
        try {
            String body = response.body().string();
            String token = readToken(body);
            r.put("token", token);
            r.put("qrUrl", String.format(QR_TPL, token));
            return r;
        } finally {
            response.close();
        }
    }

    public String poll(String token) throws Exception {
        String url = String.format(CAS_TICKET, token);
        Request request = new Request.Builder().url(url).get().build();
        Response response = OkHttp.client().newCall(request).execute();
        try {
            return readSt(response.body().string());
        } finally {
            response.close();
        }
    }

    public boolean exchange(String st) throws Exception {
        String url = String.format(ACCOUNT_INFO, st);
        Request request = new Request.Builder().url(url).get().build();
        Response response = OkHttp.client().newCall(request).execute();
        try {
            StringBuilder sb = new StringBuilder();
            for (String c : response.headers("Set-Cookie")) sb.append(c).append("; ");
            String ck = sb.toString().trim();
            if (ck.isEmpty()) return false;
            Prefers.put(PREF, ck);
            return true;
        } finally {
            response.close();
        }
    }

    public void startLogin(final String token, final DriveManager.LoginCallback cb) {
        new Thread(() -> {
            for (int i = 0; i < 90; i++) {
                try {
                    Thread.sleep(2000);
                    String st = poll(token);
                    if (st != null && !st.isEmpty()) {
                        if (exchange(st)) {
                            if (cb != null) App.post(() -> cb.onLoginSuccess(st));
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (cb != null) App.post(() -> cb.onLoginFailed());
        }).start();
    }

    public boolean isLogged() {
        return !Prefers.getString(PREF, "").isEmpty();
    }

    public String getCookie() {
        return Prefers.getString(PREF, "");
    }

    public void logout() {
        Prefers.remove(PREF);
    }
}
