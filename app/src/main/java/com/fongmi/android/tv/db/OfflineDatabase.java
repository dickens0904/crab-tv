package com.fongmi.android.tv.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.OfflineCache;
import com.fongmi.android.tv.db.dao.OfflineCacheDao;

/**
 * 手机版离线缓存的独立数据库（2026-11-13）。
 *
 * <p>刻意不放进共享的 {@link AppDatabase}（tv 库）：缓存下载是<b>手机版专属</b>功能，
 * 盒子版（leanback）不应出现这个表，也不应触发任何下载行为。独立成库后，
 * tv 库版本保持 37 原样，盒子端零影响；缓存表只在 mobile 需要时才存在。
 */
@Database(entities = OfflineCache.class, version = OfflineDatabase.VERSION)
public abstract class OfflineDatabase extends RoomDatabase {

    public static final int VERSION = 1;
    public static final String NAME = "offline";

    private static volatile OfflineDatabase instance;

    public static synchronized OfflineDatabase get() {
        if (instance == null) instance = create(App.get());
        return instance;
    }

    private static OfflineDatabase create(Context context) {
        return Room.databaseBuilder(context, OfflineDatabase.class, NAME)
                .allowMainThreadQueries().build();
    }

    public abstract OfflineCacheDao getOfflineCacheDao();
}
