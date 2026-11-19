package com.github.catvod.crawler;

import android.text.TextUtils;

import android.util.Log;
import com.orhanobut.logger.Logger;

import java.util.Arrays;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();
    private static final int CHUNK = 3000;

    public static void log(Throwable th) {
        if (th != null) th.printStackTrace();
    }

    public static void log(String msg) {
        if (TextUtils.isEmpty(msg)) return;
        print(TAG, msg);
    }

    public static void log(String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        String text;
        if (args == null || args.length == 0) {
            text = msg;
        } else {
            try {
                text = String.format(msg, args);
            } catch (Throwable e) {
                text = msg + " " + Arrays.toString(args);
            }
        }
        print(TextUtils.isEmpty(tag) ? TAG : tag, text);
    }

    /**
     * 2026-11-09 修复：原来直接 String.format(msg, args) 再交给 Logger，
     * 只要 spider 的日志文本里带一个裸 '%'（URL 编码、格式串、CSS 都常见），
     * 就会抛 MissingFormatArgumentException/UnknownFormatConversionException，
     * 异常沿调用栈冒到 SiteViewModel -> homeContent 整体失败 -> 首页/分类静默空白。
     * 现改为：format 先兜住异常，Logger 固定用 "%s" 模板，日志按 3000 字分段落 Log.d。
     */
    private static void print(String tag, String text) {
        try {
            Logger.t(tag).d("%s", text);
        } catch (Throwable ignored) {
        }
        String full = tag + " | " + text;
        int len = full.length();
        for (int i = 0; i < len; i += CHUNK) {
            Log.d("TVSpiderDebug", full.substring(i, Math.min(len, i + CHUNK)));
        }
    }
}
