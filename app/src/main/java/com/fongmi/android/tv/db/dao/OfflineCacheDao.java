package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fongmi.android.tv.bean.OfflineCache;

import java.util.List;

@Dao
public interface OfflineCacheDao {

    @Query("SELECT * FROM offline_cache")
    List<OfflineCache> all();

    @Query("SELECT * FROM offline_cache WHERE `key` = :key")
    OfflineCache find(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(OfflineCache item);

    @Query("DELETE FROM offline_cache WHERE `key` = :key")
    void delete(String key);
}
