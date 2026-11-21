package com.fongmi.android.tv.bean;

import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DriveManager {
    /* ===================== 阿里云盘（extscreen 代理 / AList 社区 app） =====================
     * 阿里自 2025-07-01 暂停个人开发者自助申请，裸社区 client_id 全灭。
     * 改用 AList/小雅生态存活的代理：api.extscreen.com（内部持 AList app 凭证）。
     * 流程：timestamp -> 出码(extscreen qrcode) -> 轮询(openapi status) -> 换token(extscreen v3/token, AES-256-CBC 解密)。
     * 设备参数 d/wifiMac/timestamp 出码与换token须一致，故出码时持久化。 */
    private static final String ALIYUN_EXT_TS = "http://api.extscreen.com/timestamp";
    private static final String ALIYUN_EXT_QR = "http://api.extscreen.com/aliyundrive/qrcode";
    private static final String ALIYUN_EXT_TOKEN = "http://api.extscreen.com/aliyundrive/v3/token";
    private static final String ALIYUN_QR_STATUS = "https://openapi.alipan.com/oauth/qrcode/%s/status";
    private static final String ALIYUN_QR_PAGE = "https://www.aliyundrive.com/o/oauth/authorize?sid=%s";
    private static final String ALIYUN_TV_REFRESH_URL = "https://i-tools.ilay.top/api/oauth/alipan/token";
    private static final String ALIYUN_EXT_TOKEN_KEY = "6733b42e28cdba32";
    private static final String ALIYUN_EXT_UA = "Mozilla/5.0 (Linux; U; Android 9; zh-cn; SM-S908E Build/TP1A.220624.014) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1";
    private static final String ALIYUN_AKV = "2.8.1496";
    private static final String ALIYUN_APV = "1.3.8";
    private static final String ALIYUN_B = "samsung";
    private static final String ALIYUN_M = "SM-S908E";
    private static final String ALIYUN_MAC = "";
    private static final String ALIYUN_N = "SM-S908E";
    private static volatile DriveManager instance;

    public interface LoginCallback {
        void onLoginFailed();

        void onLoginSuccess(String token);
    }

    public static DriveManager get() {
        if (instance == null) {
            synchronized (DriveManager.class) {
                if (instance == null) {
                    instance = new DriveManager();
                }
            }
        }
        return instance;
    }

    /* ===================== 阿里云盘（官方二维码授权） ===================== */

    private Request.Builder addExtHeaders(Request.Builder b, String d, String wifiMac, String t) {
        b.header("token", ALIYUN_EXT_TOKEN_KEY);
        b.header("User-Agent", ALIYUN_EXT_UA);
        b.header("Host", "api.extscreen.com");
        b.header("akv", ALIYUN_AKV);
        b.header("apv", ALIYUN_APV);
        b.header("b", ALIYUN_B);
        b.header("d", d);
        b.header("m", ALIYUN_M);
        b.header("mac", ALIYUN_MAC);
        b.header("n", ALIYUN_N);
        b.header("t", t);
        b.header("wifiMac", wifiMac);
        return b;
    }

    // 复刻小雅 h()：去重(保序)字符后按 timestamp[7:]%127 做位移变换
    private static String h(String input, String modifier) {
        LinkedHashSet<Character> seen = new LinkedHashSet<>();
        for (char c : input.toCharArray()) seen.add(c);
        int numericModifier;
        try {
            numericModifier = Integer.parseInt(modifier.length() > 7 ? modifier.substring(7) : modifier);
        } catch (NumberFormatException e) {
            numericModifier = 0;
        }
        int mod = numericModifier % 127;
        StringBuilder sb = new StringBuilder();
        for (char c : seen) {
            int x = Math.abs((int) c - mod - 1);
            if (x < 33) x = x + 33;
            sb.append((char) x);
        }
        return sb.toString();
    }

    // AES-256 key = md5(h(concat)).hexdigest 的 32 字符 ASCII 字节
    private static byte[] deriveKey(String d, String wifiMac, String t) throws Exception {
        String concat = ALIYUN_AKV + ALIYUN_APV + ALIYUN_B + d + ALIYUN_M + ALIYUN_MAC + ALIYUN_N + wifiMac;
        String hashed = h(concat, t);
        byte[] digest = MessageDigest.getInstance("MD5").digest(hashed.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte bb : digest) hex.append(String.format("%02x", bb & 0xFF));
        return hex.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) | Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }

    private static String aesDecrypt(String ciphertextB64, String ivHex, byte[] key) throws Exception {
        byte[] iv = hexToBytes(ivHex);
        byte[] ct = Base64.decode(ciphertextB64, Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    public Map<String, String> generateQrCode(String type) throws Throwable {
        Map<String, String> result = new HashMap<>();
        if (!Drive.TYPE_ALIYUN.equals(type)) {
            return result;
        }
        // 1) timestamp
        String t;
        Request tsReq = new Request.Builder().url(ALIYUN_EXT_TS).get()
                .header("token", ALIYUN_EXT_TOKEN_KEY).header("Host", "api.extscreen.com").build();
        Response tsResp = OkHttp.client().newCall(tsReq).execute();
        try {
            String tsBody = tsResp.body().string();
            JsonObject tsJson = JsonParser.parseString(tsBody).getAsJsonObject();
            t = tsJson.getAsJsonObject("data").get("timestamp").getAsString();
        } finally {
            tsResp.close();
        }
        // 2) 设备参数（uuid hex 无横线 + 12 位随机 wifiMac），持久化供换 token 复用
        String d = UUID.randomUUID().toString().replace("-", "");
        String wifiMac = String.valueOf(100000000000L + (long) (Math.random() * 900000000000L));
        Prefers.put("aliyun_ext_d", d);
        Prefers.put("aliyun_ext_wifimac", wifiMac);
        Prefers.put("aliyun_ext_t", t);
        // 3) 出码
        RequestBody rb = new FormBody.Builder()
                .add("scopes", "user:base,file:all:read,file:all:write")
                .add("width", "500")
                .add("height", "500")
                .build();
        Request request = addExtHeaders(new Request.Builder().url(ALIYUN_EXT_QR).post(rb), d, wifiMac, t).build();
        Response response = OkHttp.client().newCall(request).execute();
        try {
            String respBody = response.body().string();
            JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
            if (!json.has("data") || json.get("data").isJsonNull()) {
                throw new RuntimeException("阿里出码失败：" + respBody);
            }
            String sid = json.getAsJsonObject("data").get("sid").getAsString();
            result.put("sid", sid);
            result.put("qrCodeUrl", String.format(ALIYUN_QR_PAGE, sid));
            return result;
        } finally {
            response.close();
        }
    }

    // 返回 {status, authCode}；authCode 仅 LoginSuccess 时非空
    public String[] pollQrCode(String type, String sid, String ck) throws Throwable {
        if (!Drive.TYPE_ALIYUN.equals(type)) {
            return new String[]{"", null};
        }
        String url = String.format(ALIYUN_QR_STATUS, sid);
        Request request = new Request.Builder().url(url).get().build();
        Response response = OkHttp.client().newCall(request).execute();
        try {
            String body = response.body().string();
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String status = json.has("status") ? json.get("status").getAsString() : "";
            if ("LoginSuccess".equals(status)) {
                if (json.has("authCode") && !json.get("authCode").isJsonNull()) {
                    return new String[]{status, json.get("authCode").getAsString()};
                }
            }
            return new String[]{status, null};
        } finally {
            response.close();
        }
    }

    public boolean aliyunExchangeToken(String authCode) throws Throwable {
        String d = Prefers.getString("aliyun_ext_d", "");
        String wifiMac = Prefers.getString("aliyun_ext_wifimac", "");
        String t = Prefers.getString("aliyun_ext_t", "");
        if (d.isEmpty() || t.isEmpty()) {
            return false;
        }
        RequestBody rb = new FormBody.Builder().add("code", authCode).build();
        Request request = addExtHeaders(new Request.Builder().url(ALIYUN_EXT_TOKEN).post(rb), d, wifiMac, t).build();
        Response response = OkHttp.client().newCall(request).execute();
        try {
            String respBody = response.body().string();
            JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
            if (!json.has("data") || json.get("data").isJsonNull()) {
                return false;
            }
            JsonObject data = json.getAsJsonObject("data");
            String ciphertext = data.get("ciphertext").getAsString();
            String iv = data.get("iv").getAsString();
            byte[] key = deriveKey(d, wifiMac, t);
            String decrypted = aesDecrypt(ciphertext, iv, key);
            JsonObject tk = JsonParser.parseString(decrypted).getAsJsonObject();
            String refreshToken = tk.has("refresh_token") ? tk.get("refresh_token").getAsString() : "";
            if (refreshToken.isEmpty()) {
                return false;
            }
            String accessToken = tk.has("access_token") ? tk.get("access_token").getAsString() : "";
            long expires = tk.has("expires_in") ? System.currentTimeMillis() + tk.get("expires_in").getAsLong() * 1000L : System.currentTimeMillis() + 7200000L;
            Prefers.put("aliyun_refresh_token", refreshToken);
            Prefers.put("aliyun_access_token", accessToken);
            Prefers.put("aliyun_token_expires", expires);
            Prefers.remove("aliyun_ext_d");
            Prefers.remove("aliyun_ext_wifimac");
            Prefers.remove("aliyun_ext_t");
            return true;
        } finally {
            response.close();
        }
    }

    public boolean aliyunRefreshToken() throws Throwable {
        String refreshToken = Prefers.getString("aliyun_refresh_token", "");
        if (refreshToken.isEmpty()) {
            return false;
        }
        // TV 令牌经小雅 i-tools 代理刷新（内部持 AList app 凭证）
        MediaType jsonType = MediaType.get("application/json; charset=utf-8");
        String body = "{\"refresh_token\":\"" + refreshToken + "\"}";
        RequestBody rb = RequestBody.create(body, jsonType);
        Request request = new Request.Builder().url(ALIYUN_TV_REFRESH_URL).post(rb).build();
        Response response = OkHttp.client().newCall(request).execute();
        try {
            String respBody = response.body().string();
            JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
            JsonObject tk = json.has("data") && !json.get("data").isJsonNull() ? json.getAsJsonObject("data") : json;
            String accessToken = tk.has("access_token") ? tk.get("access_token").getAsString() : "";
            String newRefresh = tk.has("refresh_token") ? tk.get("refresh_token").getAsString() : "";
            if (accessToken.isEmpty()) {
                return false;
            }
            Prefers.put("aliyun_access_token", accessToken);
            if (!newRefresh.isEmpty()) Prefers.put("aliyun_refresh_token", newRefresh);
            Prefers.put("aliyun_token_expires", System.currentTimeMillis() + 7200000L);
            return true;
        } finally {
            response.close();
        }
    }

    public String getAliyunAccessToken() {
        String accessToken = Prefers.getString("aliyun_access_token", "");
        long expires = Prefers.getLong("aliyun_token_expires", 0L);
        if (accessToken.isEmpty() || System.currentTimeMillis() >= expires) {
            try {
                if (aliyunRefreshToken()) return Prefers.getString("aliyun_access_token", "");
            } catch (Throwable ignored) {
            }
            return "";
        }
        return accessToken;
    }

    public String getAliyunRefreshToken() {
        return Prefers.getString("aliyun_refresh_token", "");
    }

    public boolean isAliyunLoggedIn() {
        return !getAliyunRefreshToken().isEmpty();
    }

    public void clearAliyun() {
        Prefers.remove("aliyun_refresh_token");
        Prefers.remove("aliyun_access_token");
        Prefers.remove("aliyun_token_expires");
        Prefers.remove("aliyun_ext_d");
        Prefers.remove("aliyun_ext_wifimac");
        Prefers.remove("aliyun_ext_t");
    }

    public void startAliyunLogin(final String t, final LoginCallback callback) {
        Task.execute(() -> {
            boolean expired = false;
            for (int i = 0; i < 150 && !expired; i++) {
                try {
                    Thread.sleep(2000);
                    final String[] r = pollQrCode(Drive.TYPE_ALIYUN, t, null);
                    String status = r[0] != null ? r[0] : "";
                    final String authCode = r[1];
                    if ("QRCodeExpired".equals(status)) {
                        expired = true;
                        break;
                    }
                    if (authCode != null) {
                        boolean ok = aliyunExchangeToken(authCode);
                        if (ok) {
                            if (callback != null) App.post(() -> callback.onLoginSuccess(authCode));
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (callback != null) App.post(() -> callback.onLoginFailed());
        });
    }

    /* ===================== 百度网盘（OAuth 设备码流） ===================== */

    public String baiduGetDeviceCode() {
        try {
            return BaiduDriveApi.getDeviceCode();
        } catch (Throwable e) {
            e.printStackTrace();
            return "{}";
        }
    }

    public String baiduPollToken(String deviceCode) {
        try {
            return BaiduDriveApi.pollToken(deviceCode);
        } catch (Throwable e) {
            e.printStackTrace();
            return "{}";
        }
    }

    public void saveBaiduTokens(String accessToken, String refreshToken) {
        Drive drive = Drive.getBaidu();
        drive.setToken(accessToken);
        drive.setRefreshToken(refreshToken);
        drive.save();
    }

    public boolean baiduRefreshToken() {
        Drive drive = Drive.getBaidu();
        String rt = drive.getRefreshToken();
        if (rt == null || rt.isEmpty()) {
            return false;
        }
        try {
            String resp = BaiduDriveApi.refreshToken(rt);
            JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
            if (json.has("access_token")) {
                String at = json.get("access_token").getAsString();
                String newRt = json.has("refresh_token") ? json.get("refresh_token").getAsString() : rt;
                saveBaiduTokens(at, newRt);
                return true;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean isBaiduLoggedIn() {
        Drive drive = Drive.getBaidu();
        return (drive.getRefreshToken() == null || drive.getRefreshToken().isEmpty()) ? false : true;
    }

    public void clearBaidu() {
        Drive drive = Drive.getBaidu();
        drive.setToken(null);
        drive.setRefreshToken(null);
        drive.save();
    }

    public void startBaiduLogin(final String deviceCode, final LoginCallback callback) {
        Task.execute(() -> {
            for (int i = 0; i < 90; i++) {
                try {
                    Thread.sleep(2000);
                    String resp = baiduPollToken(deviceCode);
                    JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
                    if (json.has("access_token")) {
                        final String at = json.get("access_token").getAsString();
                        String rt = json.has("refresh_token") ? json.get("refresh_token").getAsString() : "";
                        saveBaiduTokens(at, rt);
                        if (callback != null) App.post(() -> callback.onLoginSuccess(at));
                        return;
                    }
                    String error = json.has("error") ? json.get("error").getAsString() : "";
                    if (!"authorization_pending".equals(error) && !"slow_down".equals(error) && !error.isEmpty()) {
                        if (callback != null) App.post(() -> callback.onLoginFailed());
                        return;
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (callback != null) App.post(() -> callback.onLoginFailed());
        });
    }
}
