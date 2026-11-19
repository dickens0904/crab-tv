package com.fongmi.android.tv;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.startup.Initializer;

import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.CrashActivity;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.orhanobut.logger.AndroidLogAdapter;
import com.orhanobut.logger.Logger;
import com.orhanobut.logger.PrettyFormatStrategy;

import org.greenrobot.eventbus.EventBus;

import java.util.Collections;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class Startup implements Initializer<Void> {

    @NonNull
    @Override
    public Void create(@NonNull Context context) {
        CaocConfig.Builder.create().trackActivities(true).backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT).errorActivity(CrashActivity.class).apply();
        Logger.addLogAdapter(new AndroidLogAdapter(PrettyFormatStrategy.newBuilder().methodCount(0).showThreadInfo(false).tag("TV").build()));
        // 2026-10-09: was EventBus.builder().addIndex(new EventIndex()).installDefaultEventBus();
        // There is no com.fongmi.android.tv.event.EventIndex in this repository -- and none
        // upstream either at this commit (the event package holds only the six *Event.java
        // payload classes), so the call cannot compile as written. addIndex() is purely an
        // optimisation: it pre-seeds the subscriber method finder. Without it EventBus falls
        // back to its default reflective scan of @Subscribe methods, which finds exactly the
        // same 17 subscriber classes, just a few ms slower at first use. Swapping to the
        // processor-generated EventBusIndex would only re-create a build-order dependency.
        EventBus.builder().installDefaultEventBus();
        OkHttp.dns().setDoh(() -> Doh.objectFrom(Setting.getDoh()));
        return null;
    }

    @NonNull
    @Override
    public List<Class<? extends Initializer<?>>> dependencies() {
        return Collections.emptyList();
    }
}
