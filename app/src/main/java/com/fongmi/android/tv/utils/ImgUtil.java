package com.fongmi.android.tv.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Json;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.HttpHeaders;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    private static final String TAG = "ImgUtil";
    private static final int MAX_CACHE_SIZE = 16 * 1024 * 1024;
    /** failed 黑名单上限，避免长期运行时把整站图片都钉死。 */
    private static final int MAX_FAILED = 2000;
    /**
     * failed 黑名单条目有效期。旧实现是无期限的：App 刚起来时网络还没热（配置/DNS/代理
     * 都在初始化），那一瞬间失败的图会在整个进程生命周期里永远只显示占位图 —— 这正是
     * 「首页预览图不显示」的一个来源。给个过期时间让它们能自愈。
     */
    private static final long FAILED_TTL = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_DATA_URI_LENGTH = 8 * 1024 * 1024;
    private static final Pattern IMAGE_MIME = Pattern.compile("image/[a-z0-9.+-]+");
    private static final Map<String, Long> failed = new ConcurrentHashMap<>();
    private static final Cache<String, Image> CACHE = CacheBuilder.newBuilder().maximumWeight(MAX_CACHE_SIZE).weigher((String key, Image value) -> value.data().length).build();

    /** 黑名单里还有效吗（顺带顺手清掉过期的）。 */
    private static boolean isFailed(String url) {
        Long at = failed.get(url);
        if (at == null) return false;
        if (System.currentTimeMillis() - at < FAILED_TTL) return true;
        failed.remove(url, at);
        return false;
    }

    public static void logo(ImageView view) {
        try {
            Glide.with(view).load(UrlUtil.convert(VodConfig.get().getConfig().getLogo())).circleCrop().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL).error(R.drawable.ic_logo).into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String url, CustomTarget<Bitmap> target) {
        try {
            Glide.with(App.get()).asBitmap().load(getUrl(url)).override(ResUtil.dp2px(96), ResUtil.dp2px(96)).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String text, String url, ImageView view) {
        load(text, url, view, true);
    }

    public static void load(String text, String url, ImageView view, boolean vod) {
        load(text, url, view, vod, 0);
    }

    /**
     * 2026-10-13 手机端「首页预览图不显示」修复。
     *
     * 旧实现只挂了 onLoadFailed 兜底：请求在飞的那几秒（以及被墙住的域名一直挂着），
     * 格子是完全空的，用户看到的就是「没有预览图」。这里补一个加载中占位图，
     * 并让 failed 黑名单带 TTL（见上），避免一次抖动就把某张海报钉死到进程结束。
     *
     * @param placeholderRes 加载中/失败占位图；传 0 表示沿用旧行为（leanback 调用点不传该参数）。
     */
    public static void load(String text, String url, ImageView view, boolean vod, int placeholderRes) {
        view.setScaleType(vod ? CENTER_CROP : FIT_CENTER);
        if (!vod) view.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(url) || isFailed(url)) {
            view.setImageDrawable(getTextDrawable(text, vod));
            return;
        }
        try {
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url)).listener(getListener(text, url, view, vod));
            if (placeholderRes != 0) builder = builder.placeholder(placeholderRes).error(placeholderRes);
            if (vod) builder.centerCrop().into(view);
            else builder.fitCenter().into(view);
        } catch (Throwable e) {
            view.setImageDrawable(getTextDrawable(text, vod));
            e.printStackTrace();
        }
    }

    public static Object getUrl(String url) {
        String param = null;
        url = UrlUtil.convert(url);
        if (url.startsWith("data:")) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        if (url.contains("@Headers=")) addHeader(builder, param = url.split("@Headers=")[1].split("@")[0]);
        if (url.contains("@Cookie=")) builder.addHeader(HttpHeaders.COOKIE, param = url.split("@Cookie=")[1].split("@")[0]);
        if (url.contains("@Referer=")) builder.addHeader(HttpHeaders.REFERER, param = url.split("@Referer=")[1].split("@")[0]);
        if (url.contains("@User-Agent=")) builder.addHeader(HttpHeaders.USER_AGENT, param = url.split("@User-Agent=")[1].split("@")[0]);
        url = param == null ? url : url.split("@")[0];
        return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
    }

    public static String cache(String url) {
        if (!isData(url)) return url;
        if (url.length() > MAX_DATA_URI_LENGTH) return "";
        String key = Crypto.md5(url);
        if (TextUtils.isEmpty(key)) return "";
        if (CACHE.asMap().computeIfAbsent(key, ignored -> decode(url)) == null) return "";
        String address = Server.get().getAddress("/image/" + key);
        failed.remove(address);
        return address;
    }

    private static boolean isData(String url) {
        return !TextUtils.isEmpty(url) && url.regionMatches(true, 0, "data:", 0, 5);
    }

    private static Image decode(String url) {
        try {
            int comma = url.indexOf(',');
            if (comma == -1) return null;
            String metadata = url.substring(5, comma).toLowerCase(Locale.ROOT);
            String mime = metadata.split(";", 2)[0];
            if (!IMAGE_MIME.matcher(mime).matches() || !metadata.endsWith(";base64")) return null;
            byte[] data = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
            return data.length == 0 ? null : new Image(mime, data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Image getImage(String key) {
        return CACHE.getIfPresent(key);
    }

    public record Image(String mime, byte[] data) {
    }

    private static void addHeader(LazyHeaders.Builder builder, String header) {
        Map<String, String> map = Json.toMap(Json.parse(header));
        for (Map.Entry<String, String> entry : map.entrySet()) builder.addHeader(UrlUtil.fixHeader(entry.getKey()), entry.getValue());
    }

    private static Drawable getTextDrawable(String text, boolean vod) {
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "！" : text.substring(0, 1);
        if (vod) builder.buildRect(text, ColorGenerator.get400(text));
        return builder.buildRoundRect(text, ColorGenerator.get400(text), ResUtil.dp2px(4));
    }

    private static RequestListener<Drawable> getListener(String text, String url, ImageView view, boolean vod) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                view.setImageDrawable(getTextDrawable(text, vod));
                Log.w(TAG, "image failed: " + url + " (" + (e == null ? "unknown" : e.getMessage()) + ")");
                failed.put(url, System.currentTimeMillis());
                while (failed.size() > MAX_FAILED) failed.remove(failed.keySet().iterator().next());
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                failed.remove(url);
                return false;
            }
        };
    }
}
