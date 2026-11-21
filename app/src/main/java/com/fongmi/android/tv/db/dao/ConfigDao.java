package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.RoomWarnings;

import com.fongmi.android.tv.bean.Config;

import java.util.List;

@Dao
public abstract class ConfigDao extends BaseDao<Config> {

    @Query("SELECT * FROM Config")
    public abstract List<Config> findAll();

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC")
    public abstract List<Config> findByType(int type);

    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
    @Query("SELECT id, name, url, type, time FROM Config WHERE type = :type ORDER BY time DESC")
    public abstract List<Config> findUrlByType(int type);

    @Query("SELECT * FROM Config WHERE id = :id")
    public abstract Config findById(int id);

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC LIMIT 1")
    public abstract Config findOne(int type);

    @Query("SELECT * FROM Config WHERE url = :url AND type = :type")
    public abstract Config find(String url, int type);

    /**
     * 一次性存量自愈：修复历史版本 {@code Config.insert()} 不写 time 造成的 time=0 脏行。
     * <p>
     * {@code findOne}/{@code findByType}/{@code findUrlByType} 都按 {@code time DESC} 排序，
     * time=0 的行会被任何一条曾被激活过的行永久压住，恢复回来的多仓源因此永远排不到首页。
     * 这里给每行补一个「随 id 递增的当前时间」而不是同一个时间戳，避免排序出现并列导致
     * {@code findOne} 选中哪一条全看 SQLite 的扫描顺序。补完之后 {@code time=0} 不再存在，
     * 本语句自然变成 no-op，因此不需要额外的「已执行」标记位。
     */
    @Query("UPDATE Config SET time = :base + id WHERE time = 0")
    public abstract int healEmptyTime(long base);

    @Query("DELETE FROM Config WHERE url = :url AND type = :type")
    public abstract void delete(String url, int type);

    @Query("DELETE FROM Config WHERE url = :url")
    public abstract void delete(String url);
}
