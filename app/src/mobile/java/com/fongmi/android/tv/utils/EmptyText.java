package com.fongmi.android.tv.utils;

import android.content.Context;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;

/**
 * 2026-10-13：把 {@link Result#getEmptyReason()} 翻成给用户看的话。
 *
 * <p>放在 mobile 源集而不是 {@code Result} 里，因为文案是手机端的空状态 UI 才需要的，
 * leanback 没有这个界面；原因码本身在 main 里定义，两边共用。
 */
public class EmptyText {

    public static String of(Context context, Result result) {
        int reason = result == null ? Result.EMPTY_NONE : result.getEmptyReason();
        return switch (reason) {
            case Result.EMPTY_NO_CONTENT -> context.getString(R.string.empty_source_no_content);
            case Result.EMPTY_BAD_RESPONSE -> context.getString(R.string.empty_source_bad_response);
            default -> context.getString(R.string.empty_source_hint);
        };
    }
}