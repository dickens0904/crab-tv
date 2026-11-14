package com.fongmi.android.tv.offline;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.OfflineCache;
import com.fongmi.android.tv.db.OfflineDatabase;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Init;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 手机版离线缓存的索引与串行下载队列（2026-11-13）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>内存索引 {@link #map} 与 Room 表双向同步，UI（选集网格每个 item）查询是 O(1)，
 *       不会在 bindViewHolder 里打 SQLite。</li>
 *   <li><b>单线程串行</b>：批量缓存几十集时不会并发把网络打满，也避免 wmdb 那类限流问题。</li>
 *   <li>下载产物通过 9978 本地 /file/ 服务重放，播放链路完全不用改。</li>
 * </ul>
 */
public class OfflineCacheManager {

    private static final class Holder {
        private static final OfflineCacheManager INSTANCE = new OfflineCacheManager();
    }

    public static OfflineCacheManager get() {
        return Holder.INSTANCE;
    }

    public interface Listener {
        void onChanged(String key);
    }

    private final ConcurrentHashMap<String, OfflineCache> map = new ConcurrentHashMap<>();
    private final Set<String> canceled = ConcurrentHashMap.newKeySet();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile boolean loaded;
    private volatile boolean running;
    private volatile OfflineDownloader current;
    private volatile String currentKey;

    private OfflineCacheManager() {
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged(String key) {
        App.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onChanged(key);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        boolean resume = false;
        for (OfflineCache item : OfflineDatabase.get().getOfflineCacheDao().all()) {
            if (item.getStatus() == OfflineCache.DOWNLOADING) {
                // 上次进程被杀时正在下载，重启后继续排队
                item.setStatus(OfflineCache.QUEUED);
                persist(item);
            }
            if (item.getStatus() == OfflineCache.DONE && !new File(item.getLocalPath() == null ? "" : item.getLocalPath()).isFile()) {
                OfflineDatabase.get().getOfflineCacheDao().delete(item.getKey());
                Task.execute(() -> Path.clear(dir(item.getKey())));
                continue;
            }
            if (item.getStatus() == OfflineCache.QUEUED) resume = true;
            map.put(item.getKey(), item);
        }
        // 关键：进程重启后未完成的批量任务必须自动续跑，否则永远停在「排队中」
        if (resume) startQueue();
    }

    private static void persist(OfflineCache item) {
        OfflineDatabase.get().getOfflineCacheDao().insert(item);
    }

    public static boolean isCacheable(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * App 启动时调用：载入索引，并自动续跑上次进程被杀时未完成的下载。
     * 必须主动触发——索引是惰性的，否则重启后排队的任务永远等不到启动。
     */
    public void resume() {
        ensureLoaded();
    }

    public OfflineCache find(String episodeUrl) {
        ensureLoaded();
        return map.get(OfflineCache.keyOf(episodeUrl));
    }

    public OfflineCache findByKey(String key) {
        ensureLoaded();
        return map.get(key);
    }

    public List<OfflineCache> all() {
        ensureLoaded();
        List<OfflineCache> result = new ArrayList<>(map.values());
        result.sort(Comparator.comparingLong(OfflineCache::getCreatedAt).reversed());
        return result;
    }

    public static File baseDir() {
        File external = Init.context().getExternalFilesDir(null);
        return new File(external == null ? Path.cache() : external, "offline");
    }

    public static File dir(String key) {
        return new File(baseDir(), key);
    }

    /** 交给 VideoActivity.start(url) 播放的本地地址。 */
    public static String playUrl(OfflineCache item) {
        Server.get().start();
        return Server.get().getAddress("/file/" + item.getLocalPath());
    }

    /**
     * 加入缓存队列。
     *
     * @return true=已入队或已缓存；false=地址不是可直接下载的 http(s)（需源站二次解析）
     */
    public boolean enqueue(String episodeUrl, String episodeName, String vodName, String vodId, String vodPic, String siteKey) {
        if (!isCacheable(episodeUrl)) return false;
        ensureLoaded();
        String key = OfflineCache.keyOf(episodeUrl);
        OfflineCache exist = map.get(key);
        if (exist != null && (exist.isDone() || exist.isActive())) return true;
        OfflineCache item = exist == null
                ? new OfflineCache(episodeUrl, episodeName, vodName, vodId, vodPic, siteKey)
                : exist;
        item.setStatus(OfflineCache.QUEUED);
        item.setProgress(0);
        item.setBytes(0);
        item.setError(null);
        item.setVodName(vodName);
        item.setEpisodeName(episodeName);
        item.setCreatedAt(System.currentTimeMillis());
        map.put(key, item);
        persist(item);
        notifyChanged(key);
        startQueue();
        return true;
    }

    /** 取消（下载中则中断）并从索引/磁盘删除。 */
    public void delete(String key) {
        ensureLoaded();
        canceled.add(key);
        if (key.equals(currentKey) && current != null) current.cancel();
        map.remove(key);
        OfflineDatabase.get().getOfflineCacheDao().delete(key);
        // 删几百个分片放后台，避免主线程掉帧
        Task.execute(() -> {
            Path.clear(dir(key));
            removeEmptyBase();
        });
        notifyChanged(key);
    }

    private void removeEmptyBase() {
        File base = baseDir();
        String[] children = base.list();
        if (base.isDirectory() && children != null && children.length == 0) base.delete();
    }

    private void startQueue() {
        synchronized (lock) {
            if (running) return;
            running = true;
        }
        Task.execute(this::runQueue);
    }

    private void runQueue() {
        while (true) {
            OfflineCache next = nextQueued();
            if (next == null) {
                synchronized (lock) {
                    if (nextQueued() == null) {
                        running = false;
                        return;
                    }
                }
                continue;
            }
            download(next);
        }
    }

    private OfflineCache nextQueued() {
        OfflineCache best = null;
        for (OfflineCache item : map.values()) {
            if (item.getStatus() != OfflineCache.QUEUED) continue;
            if (best == null || item.getCreatedAt() < best.getCreatedAt()) best = item;
        }
        return best;
    }

    private void download(OfflineCache item) {
        String key = item.getKey();
        // 任务可能在排队期间被用户删掉，别把它重新写回库
        if (!map.containsKey(key) || canceled.contains(key)) return;
        canceled.remove(key);
        currentKey = key;
        item.setStatus(OfflineCache.DOWNLOADING);
        item.setProgress(0);
        item.setError(null);
        persist(item);
        notifyChanged(key);
        OfflineDownloader downloader = new OfflineDownloader();
        current = downloader;
        int[] last = {-1};
        try {
            File file = downloader.run(item.getEpisodeUrl(), dir(key), (percent, bytes) -> {
                if (canceled.contains(key)) return;
                boolean changed = percent == 0 || percent == 100 || percent - last[0] >= 2;
                if (!changed) return;
                last[0] = percent;
                item.setProgress(percent);
                item.setBytes(bytes);
                persist(item);
                notifyChanged(key);
            }, () -> canceled.contains(key));
            if (!canceled.contains(key)) {
                item.setLocalPath(file.getAbsolutePath());
                item.setStatus(OfflineCache.DONE);
                item.setProgress(100);
                persist(item);
            }
        } catch (Exception e) {
            if (!canceled.contains(key)) {
                item.setStatus(OfflineCache.FAILED);
                item.setError(e.getMessage() == null ? "download failed" : e.getMessage());
                persist(item);
            }
        } finally {
            current = null;
            currentKey = null;
            notifyChanged(key);
        }
    }
}
