package com.fongmi.android.tv.bean;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BaiduDriveApi {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();
    private static final String CLIENT_ID = "oSW2iSwRTiNTAWhKCIKxZ2rXxWvQav5t";
    private static final String CLIENT_SECRET = "7tg2rr79f5aMDmzYdGBLhpSSnEMIzbQO";
    private static final String OAUTH_DEVICE_CODE = "https://openapi.baidu.com/oauth/2.0/device/code";
    private static final String OAUTH_TOKEN = "https://openapi.baidu.com/oauth/2.0/token";
    private static final String PAN_API = "https://pan.baidu.com/rest/2.0/xpan";

    public static String getDeviceCode() throws IOException {
        HttpUrl url = HttpUrl.parse(OAUTH_DEVICE_CODE).newBuilder().addQueryParameter("response_type", "device_code").addQueryParameter("client_id", CLIENT_ID).addQueryParameter("scope", "basic,netdisk").build();
        Request request = new Request.Builder().url(url).get().build();
        Response response = CLIENT.newCall(request).execute();
        try {
            String strString = response.body() != null ? response.body().string() : "";
            if (response != null) {
                response.close();
            }
            return strString;
        } catch (Throwable th) {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public static String pollToken(String deviceCode) throws IOException {
        HttpUrl url = HttpUrl.parse(OAUTH_TOKEN).newBuilder().addQueryParameter("grant_type", "device_token").addQueryParameter("code", deviceCode).addQueryParameter("client_id", CLIENT_ID).addQueryParameter("client_secret", CLIENT_SECRET).build();
        Request request = new Request.Builder().url(url).get().build();
        Response response = CLIENT.newCall(request).execute();
        try {
            String strString = response.body() != null ? response.body().string() : "";
            if (response != null) {
                response.close();
            }
            return strString;
        } catch (Throwable th) {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public static String refreshToken(String refreshToken) throws IOException {
        HttpUrl url = HttpUrl.parse(OAUTH_TOKEN).newBuilder().addQueryParameter("grant_type", "refresh_token").addQueryParameter("refresh_token", refreshToken).addQueryParameter("client_id", CLIENT_ID).addQueryParameter("client_secret", CLIENT_SECRET).build();
        Request request = new Request.Builder().url(url).get().build();
        Response response = CLIENT.newCall(request).execute();
        try {
            String strString = response.body() != null ? response.body().string() : "";
            if (response != null) {
                response.close();
            }
            return strString;
        } catch (Throwable th) {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public static String listFiles(String accessToken, String dir, int page) throws IOException {
        HttpUrl url = HttpUrl.parse("https://pan.baidu.com/rest/2.0/xpan/file").newBuilder().addQueryParameter("method", "list").addQueryParameter("access_token", accessToken).addQueryParameter("dir", dir).addQueryParameter("order", "time").addQueryParameter("desc", "1").addQueryParameter("page", String.valueOf(page)).addQueryParameter("num", "100").addQueryParameter("web", "1").build();
        Request request = new Request.Builder().url(url).get().build();
        Response response = CLIENT.newCall(request).execute();
        try {
            String strString = response.body() != null ? response.body().string() : "";
            if (response != null) {
                response.close();
            }
            return strString;
        } catch (Throwable th) {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public static String getFileMeta(String accessToken, long fsId) throws IOException {
        HttpUrl url = HttpUrl.parse("https://pan.baidu.com/rest/2.0/xpan/multimedia").newBuilder().addQueryParameter("method", "filemetas").addQueryParameter("access_token", accessToken).addQueryParameter("fsids", "[" + fsId + "]").addQueryParameter("dlink", "1").addQueryParameter("web", "1").build();
        Request request = new Request.Builder().url(url).get().build();
        Response response = CLIENT.newCall(request).execute();
        try {
            String strString = response.body() != null ? response.body().string() : "";
            if (response != null) {
                response.close();
            }
            return strString;
        } catch (Throwable th) {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public static String getPlayUrl(String accessToken, long fsId) throws IOException {
        String meta = getFileMeta(accessToken, fsId);
        return meta;
    }
}
