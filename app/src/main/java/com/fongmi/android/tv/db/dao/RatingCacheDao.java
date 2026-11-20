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

    /** 清掉"查不到"的缓存，让片名清洗后的新策略重新查一遍（版本升级一次性迁移）。 */
    @Query("DELETE FROM rating_cache WHERE doubanScore = '' AND imdbRating = ''")
    public abstract void deleteMiss();
}
