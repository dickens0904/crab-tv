package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class Action implements Process {

    private static final int PROBE_MAX = 200;
    private static final int PROBE_PARALLEL = 12;
    private static final long PROBE_TIMEOUT = 4000;
    private static final long PROBE_MIN_TIMEOUT = 500;
    private static final long PROBE_MAX_TIMEOUT = 30000;

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/action");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String param = params.get("do");
        if (!TextUtils.isEmpty(param)) {
            Response response = doJob(param, params, files);
            if (response != null) return response;
        }
        return Nano.ok();
    }

    private Response doJob(String param, Map<String, String> params, Map<String, String> files) {
        return switch (param) {
            case "probe" -> onProbe(params, files);
            case "file" -> { onFile(params); yield null; }
            case "push" -> { onPush(params); yield null; }
            case "cast" -> { onCast(params); yield null; }
            case "sync" -> { onSync(params); yield null; }
            case "search" -> { onSearch(params); yield null; }
            case "setting" -> { onSetting(params); yield null; }
            case "refresh" -> { onRefresh(params); yield null; }
            case "control" -> { onControl(params); yield null; }
            case "danmaku" -> { onDanmaku(params); yield null; }
            default -> null;
        };
    }

    private void onFile(Map<String, String> params) {
        String path = params.get("path");
        if (TextUtils.isEmpty(path)) return;
        if (path.endsWith(".apk")) FileUtil.openFile(Path.local(path));
        else if (path.endsWith(".srt") || path.endsWith(".ssa") || path.endsWith(".ass")) RefreshEvent.subtitle(path);
        else ServerEvent.setting(path);
    }

    private void onPush(Map<String, String> params) {
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return;
        ServerEvent.push(url);
    }

    private void onSearch(Map<String, String> params) {
        String word = params.get("word");
        if (TextUtils.isEmpty(word)) return;
        ServerEvent.search(word);
    }

    private void onSetting(Map<String, String> params) {
        String text = params.get("text");
        String name = params.get("name");
        if (TextUtils.isEmpty(text)) return;
        ServerEvent.setting(text, name);
    }

    private void onRefresh(Map<String, String> params) {
        String type = params.get("type");
        String path = params.get("path");
        String json = params.get("json");
        if (TextUtils.isEmpty(type)) return;
        switch (type) {
            case "live" -> RefreshEvent.live();
            case "detail" -> RefreshEvent.detail();
            case "player" -> RefreshEvent.player();
            case "category" -> RefreshEvent.category();
            case "danmaku" -> RefreshEvent.danmaku(path);
            case "subtitle" -> RefreshEvent.subtitle(path);
            case "vod" -> RefreshEvent.vod(Vod.objectFrom(json));
        }
    }

    private void onControl(Map<String, String> params) {
        String type = params.get("type");
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(type)) return;
        switch (type) {
            case "play" -> App.post(() -> service.player().play());
            case "pause" -> App.post(() -> service.player().pause());
            case "stop" -> App.post(service::dispatchStop);
            case "prev" -> App.post(service::dispatchPrev);
            case "next" -> App.post(service::dispatchNext);
            case "repeat" -> App.post(service::dispatchRepeat);
            case "replay" -> App.post(service::dispatchReplay);
        }
    }

    private void onDanmaku(Map<String, String> params) {
        String text = params.get("text");
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(text)) return;
        App.post(() -> service.player().sendDanmaku(text));
    }

    /**
     * 盒上探测端点：主机把候选 URL 清单 POST 过来，App 用自身网络栈（含 App 配置的 DNS/代理）测活后回传 JSON。
     * 纯只读、纯内存，不落任何文件、不改任何配置。
     */
    private Response onProbe(Map<String, String> params, Map<String, String> files) {
        JsonObject json = toObject(files == null ? null : files.get("postData"));
        if (json == null) json = toObject(params.get("body"));
        List<String> urls = json == null ? getQueryUrls(params) : getBodyUrls(json);
        long timeout = json == null ? getQueryTimeout(params) : getBodyTimeout(json);
        if (urls.isEmpty()) return probeError("urls is required");
        boolean truncated = urls.size() > PROBE_MAX;
        if (truncated) urls = new ArrayList<>(urls.subList(0, PROBE_MAX));
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("results", doProbe(urls, timeout));
        if (truncated) result.addProperty("truncated", true);
        return Nano.json(result.toString());
    }

    private JsonArray doProbe(List<String> urls, long timeout) {
        OkHttpClient client = OkHttp.client();
        List<Probe> probes = new ArrayList<>();
        for (String url : urls) probes.add(new Probe(url));
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Probe probe : probes) tasks.add(() -> {
            probe(client, probe, timeout);
            return null;
        });
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PROBE_PARALLEL, probes.size()));
        JsonArray results = new JsonArray();
        try {
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (Exception ignored) {
                }
                results.add(probes.get(i).toJson());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    private void probe(OkHttpClient client, Probe probe, long timeout) {
        long start = System.currentTimeMillis();
        if (TextUtils.isEmpty(probe.url) || !(probe.url.startsWith("http://") || probe.url.startsWith("https://"))) {
            probe.finish(0, start);
            return;
        }
        int code = execute(client, probe.url, true, timeout);
        if (code >= 200 && code < 400) {
            probe.finish(code, start);
            return;
        }
        long remain = timeout - (System.currentTimeMillis() - start);
        if (remain >= PROBE_MIN_TIMEOUT) code = execute(client, probe.url, false, Math.min(remain, timeout));
        probe.finish(code, start);
    }

    private int execute(OkHttpClient base, String url, boolean head, long timeout) {
        try {
            Request.Builder builder = new Request.Builder().url(url);
            if (head) builder.head();
            else builder.header("Range", "bytes=0-4095");
            OkHttpClient client = base.newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS).build();
            try (var res = client.newCall(builder.build()).execute()) {
                return res.code();
            }
        } catch (Throwable e) {
            return -1;
        }
    }

    private Response probeError(String error) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", error);
        return Nano.json(Response.Status.BAD_REQUEST, result.toString());
    }

    private JsonObject toObject(String text) {
        try {
            if (TextUtils.isEmpty(text)) return null;
            JsonElement element = Json.parse(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private List<String> getBodyUrls(JsonObject json) {
        try {
            return trim(Json.safeListString(json, "urls"));
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private List<String> getQueryUrls(Map<String, String> params) {
        List<String> urls = new ArrayList<>();
        String text = params.get("urls");
        if (!TextUtils.isEmpty(text)) urls.addAll(List.of(text.split(",")));
        return trim(urls);
    }

    private long getBodyTimeout(JsonObject json) {
        try {
            return clampTimeout(json.get("timeout_ms").getAsLong());
        } catch (Throwable e) {
            return PROBE_TIMEOUT;
        }
    }

    private long getQueryTimeout(Map<String, String> params) {
        try {
            return clampTimeout(Long.parseLong(params.get("timeout_ms")));
        } catch (Throwable e) {
            return PROBE_TIMEOUT;
        }
    }

    private long clampTimeout(long timeout) {
        if (timeout <= 0) return PROBE_TIMEOUT;
        return Math.max(PROBE_MIN_TIMEOUT, Math.min(PROBE_MAX_TIMEOUT, timeout));
    }

    private List<String> trim(List<String> urls) {
        List<String> result = new ArrayList<>();
        for (String url : urls) if (!TextUtils.isEmpty(url = url.trim())) result.add(url);
        return result;
    }

    private static class Probe {
        final String url;
        String state = "unknown";
        int code;
        long ms;

        Probe(String url) {
            this.url = url;
        }

        void finish(int code, long start) {
            this.code = code;
            this.ms = System.currentTimeMillis() - start;
            if (code >= 200 && code < 400) this.state = "alive";
            else if (code == -1) this.state = "dead";
            else this.state = "unknown";
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("url", url);
            result.addProperty("state", state);
            result.addProperty("code", code);
            result.addProperty("ms", ms);
            return result;
        }
    }

    private void onCast(Map<String, String> params) {
        Config config = Config.objectFrom(params.get("config"));
        Device device = Device.objectFrom(params.get("device"));
        History history = History.objectFrom(params.get("history"));
        CastEvent.post(Config.find(config), device, history);
    }

    private void onSync(Map<String, String> params) {
        String type = params.get("type");
        boolean force = Objects.equals(params.get("force"), "true");
        String mode = Objects.requireNonNullElse(params.get("mode"), "0");
        if (params.get("device") != null && (mode.equals("0") || mode.equals("2"))) {
            Device device = Device.objectFrom(params.get("device"));
            if ("history".equals(type)) sendHistory(device, params);
            else if ("keep".equals(type)) sendKeep(device);
        }
        if (mode.equals("0") || mode.equals("1")) {
            if ("history".equals(type)) syncHistory(params, force);
            else if ("keep".equals(type)) syncKeep(params, force);
        }
    }

    private void post(Device device, String type, FormBody.Builder body) {
        try {
            OkHttp.newCall(OkHttp.client(Constant.TIMEOUT_SYNC), device.getIp().concat("/action?do=sync&mode=0&type=" + type), body.build()).execute();
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    private void sendHistory(Device device, Map<String, String> params) {
        try {
            Config config = Config.find(Config.objectFrom(params.get("config")));
            if (config.getUrl() == null) config = Config.vod();
            FormBody.Builder body = new FormBody.Builder();
            body.add("config", config.toString());
            body.add("targets", App.gson().toJson(History.get(config.getId())));
            post(device, "history", body);
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    private void sendKeep(Device device) {
        try {
            FormBody.Builder body = new FormBody.Builder();
            body.add("targets", App.gson().toJson(Keep.getVod()));
            body.add("configs", App.gson().toJson(Config.findUrls()));
            post(device, "keep", body);
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    public void syncHistory(Map<String, String> params, boolean force) {
        Config config = Config.find(Config.objectFrom(params.get("config")));
        List<History> targets = History.arrayFrom(params.get("targets"));
        if (config.getUrl() == null) return;
        if (config.getUrl().equals(VodConfig.getUrl())) {
            if (force) History.delete(config.getId());
            History.sync(targets);
            RefreshEvent.history();
        } else {
            VodConfig.load(config, getCallback(targets, force, config.getId()));
        }
    }

    private Callback getCallback(List<History> targets, boolean force, int cid) {
        return new Callback() {
            @Override
            public void success() {
                if (force) History.delete(cid);
                History.sync(targets);
                RefreshEvent.history();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private void syncKeep(Map<String, String> params, boolean force) {
        List<Keep> targets = Keep.arrayFrom(params.get("targets"));
        List<Config> configs = Config.arrayFrom(params.get("configs"));
        if (TextUtils.isEmpty(VodConfig.getUrl()) && !configs.isEmpty()) {
            VodConfig.load(Config.find(configs.get(0)), getCallback(configs, targets, force));
        } else {
            if (force) Keep.deleteAll();
            Keep.sync(configs, targets);
            RefreshEvent.keep();
        }
    }

    private Callback getCallback(List<Config> configs, List<Keep> targets, boolean force) {
        return new Callback() {
            @Override
            public void success() {
                if (force) Keep.deleteAll();
                Keep.sync(configs, targets);
                RefreshEvent.keep();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }
}
