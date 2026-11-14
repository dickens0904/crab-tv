package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.RatingCache;

@Dao
public abstract class RatingCacheDao extends BaseDao<RatingCache> {

    @Query("SELECT * FROM rating_cache WHERE `key` = :key")
    public abstract RatingCache getByKey(String key);

    @Query("DELETE FROM rating_cache WHERE updatedAt < :ts")
    public abstract void deleteOlderThan(long ts);
}
