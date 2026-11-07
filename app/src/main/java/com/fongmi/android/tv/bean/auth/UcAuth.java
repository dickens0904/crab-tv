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
 * UC网盘扫码登录（CAS 设备流，逆向实现）。
 * 流程同夸克：getTokenForQrcodeLogin -> 二维码 -> getServiceTicketByQrcodeToken 取 ST -> account/info 取 Cookie。
 * 注：CAS 参数为社区逆向，盒子端联网实测可能需要微调（见任务书 §3/§7）。
 */
public class UcAuth {
    private static final String CLIENT_ID = "20000009";
    private static final String APP_KEY = "20000009";
    private static final String CAS_TOKEN = "https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin?callback=cd&appKey=" + APP_KEY + "&client_id=" + CLIENT_ID + "&redirect=%2F&v=1.0";
    private static final String QR_TPL = "https://api.open.uc.cn/cas/login?qrcodeToken=%s";
    private static final String CAS_TICKET = "https://drive.uc.cn/cas/ajax/getServiceTicketByQrcodeToken?callback=cd&qrcodeToken=%s&client_id=" + CLIENT_ID + "&v=1.0&appKey=" + APP_KEY;
    private static final String ACCOUNT_INFO = "https://drive.uc.cn/account/info?st=%s";
    private static final String PREF = "uc_cookie";
    private static volatile UcAuth instance;

    public static UcAuth get() {
        if (instance == null) {
            synchronized (UcAuth.class) {
                if (instance == null) instance = new UcAuth();
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
