package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * 评分补全缓存（2026-11-12 / 盒子版）。
 *
 * <p>把「片名 + 年份 + 类型」映射到的豆瓣/IMDb 评分，离线落库在 Room「tv」库的
 * rating_cache 表。App 端在渲染点播列表前按 key 命中，回填 {@link Vod#getDoubanScore()}，
 * 使「按评分排序」的覆盖率从源站原生 ~20% 提升到 ~74%（首装即带种子数据），并随盒子
 * 后台自动补全持续增长。
 *
 * <p>key 规范化（必须与离线种子 / 运行期完全一致）：{@code 片名.trim().toLowerCase()}|四位年份|movie/tv}
 * —— 注意 key 规范化<b>不去标点</b>，仅 trim+小写；只有做片名相似度匹配时才去标点。
 */
@Entity(tableName = "rating_cache")
public class RatingCache {

    @NonNull
    @PrimaryKey
    private String key;
    private String doubanScore;
    private String doubanId;
    private String imdbRating;
    private String imdbId;
    private String source;
    private long updatedAt;

    public RatingCache() {
    }

    @Ignore
    public RatingCache(String key, String doubanScore, String doubanId, String imdbRating, String imdbId, String source) {
        this.key = key;
        this.doubanScore = doubanScore;
        this.doubanId = doubanId;
        this.imdbRating = imdbRating;
        this.imdbId = imdbId;
        this.source = source;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getDoubanScore() {
        return doubanScore;
    }

    public void setDoubanScore(String doubanScore) {
        this.doubanScore = doubanScore;
    }

    public String getDoubanId() {
        return doubanId;
    }

    public void setDoubanId(String doubanId) {
        this.doubanId = doubanId;
    }

    public String getImdbRating() {
        return imdbRating;
    }

    public void setImdbRating(String imdbRating) {
        this.imdbRating = imdbRating;
    }

    public String getImdbId() {
        return imdbId;
    }

    public void setImdbId(String imdbId) {
        this.imdbId = imdbId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
