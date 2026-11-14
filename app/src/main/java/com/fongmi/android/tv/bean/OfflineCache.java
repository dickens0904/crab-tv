package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.github.catvod.utils.Crypto;

/**
 * 手机版点播「离线缓存」的一条下载记录（2026-11-13）。
 *
 * <p>一集 = 一条记录，key = 播放地址 URL 的 md5，这样播放器、选集网格、批量多选
 * 在任何上下文里都能用同一个 key 查到状态，不需要额外传站点/影片信息。
 *
 * <p>文件落在 App 专属外部目录 offline/&lt;key&gt;/ 下：HLS 为 index.m3u8 + 分片，
 * 单文件为 video.&lt;ext&gt;。播放时把 {@link #localPath} 交给 9978 本地 /file/ 服务重放，
 * 因此不需要改动任何播放器代码。
 */
@Entity(tableName = "offline_cache")
public class OfflineCache {

    public static final int QUEUED = 0;
    public static final int DOWNLOADING = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;

    @NonNull
    @PrimaryKey
    private String key;
    private String siteKey;
    private String vodId;
    private String vodName;
    private String vodPic;
    private String episodeName;
    private String episodeUrl;
    private String localPath;
    private String error;
    private int status;
    private int progress;
    private long bytes;
    private long createdAt;

    public OfflineCache() {
    }

    @Ignore
    public OfflineCache(String episodeUrl, String episodeName, String vodName, String vodId, String vodPic, String siteKey) {
        this.key = keyOf(episodeUrl);
        this.episodeUrl = episodeUrl;
        this.episodeName = episodeName;
        this.vodName = vodName;
        this.vodId = vodId;
        this.vodPic = vodPic;
        this.siteKey = siteKey;
        this.status = QUEUED;
        this.progress = 0;
        this.bytes = 0;
        this.createdAt = System.currentTimeMillis();
    }

    public static String keyOf(String url) {
        return Crypto.md5(url == null ? "" : url);
    }

    @Ignore
    public boolean isDone() {
        return status == DONE && localPath != null && !localPath.isEmpty();
    }

    @Ignore
    public boolean isActive() {
        return status == QUEUED || status == DOWNLOADING;
    }

    public String displayName() {
        String name = vodName == null || vodName.isEmpty() ? "" : vodName;
        String episode = episodeName == null || episodeName.isEmpty() ? "" : episodeName;
        if (name.isEmpty()) return episode;
        if (episode.isEmpty()) return name;
        return name + " · " + episode;
    }

    @NonNull
    public String getKey() {
        return key;
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public void setSiteKey(String siteKey) {
        this.siteKey = siteKey;
    }

    public String getVodId() {
        return vodId;
    }

    public void setVodId(String vodId) {
        this.vodId = vodId;
    }

    public String getVodName() {
        return vodName;
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getVodPic() {
        return vodPic;
    }

    public void setVodPic(String vodPic) {
        this.vodPic = vodPic;
    }

    public String getEpisodeName() {
        return episodeName;
    }

    public void setEpisodeName(String episodeName) {
        this.episodeName = episodeName;
    }

    public String getEpisodeUrl() {
        return episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public long getBytes() {
        return bytes;
    }

    public void setBytes(long bytes) {
        this.bytes = bytes;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
