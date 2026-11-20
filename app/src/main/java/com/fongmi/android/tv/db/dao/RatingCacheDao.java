package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fongmi.android.tv.bean.RatingCache;

import java.util.List;

@Dao
public abstract class RatingCacheDao extends BaseDao<RatingCache> {

    /** 批量写入（单事务）。逐条 insert 导入 6k 条要 90 秒，批量后 1 秒级。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insertAll(List<RatingCache> list);

    @Query("SELECT * FROM rating_cache WHERE `key` = :key")
    public abstract RatingCache getByKey(String key);

    @Query("DELETE FROM rating_cache WHERE updatedAt < :ts")
    public abstract void deleteOlderThan(long ts);

    /** 清掉"查不到"的缓存，让片名清洗后的新策略重新查一遍（版本升级一次性迁移）。 */
    @Query("DELETE FROM rating_cache WHERE doubanScore = '' AND imdbRating = ''")
    public abstract void deleteMiss();
}
