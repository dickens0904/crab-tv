package com.fongmi.android.tv.api.config;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;
import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LiveConfig extends BaseConfig {

    private static final String TAG = LiveConfig.class.getSimpleName();

    private Live home;
    private List<Live> lives;
    private List<Rule> rules;
    private List<String> ads;

    public static LiveConfig get() {
        return Loader.INSTANCE;
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static String getResp() {
        return get().getHome().getCore().getResp();
    }

    public static int getHomeIndex() {
        return get().getLives().indexOf(get().getHome());
    }

    public static boolean isOnly() {
        return get().getLives().size() == 1;
    }

    public static boolean isEmpty() {
        return get().getHome().isEmpty();
    }

    public static boolean hasUrl() {
        return !TextUtils.isEmpty(getUrl());
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public LiveConfig init() {
        return config(Config.live());
    }

    public LiveConfig config(Config config) {
        this.config = config;
        if (config.isEmpty()) return this;
        this.sync = config.getUrl().equals(VodConfig.getUrl());
        return this;
    }

    public LiveConfig clear() {
        ads = null;
        home = null;
        lives = null;
        rules = null;
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.live();
    }

    @Override
    protected void postEvent() {
        appendSelfEndpoint();
        super.postEvent();
        ConfigEvent.live();
    }

    @Override
    protected void load(Config config) throws Throwable {
        // 2026-10-24: 家庭局域网直播订阅离网(不可达)时跳过 —— 实测手机切 5G 后仍在连
        // 192.168.31.29:8099，每次 SocketTimeout 30s，首页转圈半分钟。离网就该立刻跳过。
        if (com.fongmi.android.tv.net.NetworkPolicy.get().shouldSkipLanOffHome(config.getUrl()))
            throw new RuntimeException("__HOME_LAN_SKIP__:" + config.getUrl());
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
        if (Json.isObj(json)) checkJson(config, Json.parse(json).getAsJsonObject());
        else parseText(config, json);
    }

    @Override
    protected boolean isLoaded() {
        return !getLives().isEmpty() && !getHome().getGroups().isEmpty();
    }

    @Override
    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) {
                appendSelfEndpoint();
                return;
            }
            super.ensureLoaded();
            LiveApi.parse(getHome());
            LiveApi.parseXml(getHome());
            appendSelfEndpoint();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void appendSelfEndpoint() {
        try {
            if (!LiveSetting.isSelfEndpoint()) return;
            String url = LiveSetting.getSelfUrl();
            if (TextUtils.isEmpty(url)) return;
            boolean exists = getLives().stream().anyMatch(item -> url.equals(item.getUrl()));
            if (exists) return;
            String text = OkHttp.string(url);
            if (TextUtils.isEmpty(text)) return;
            Live live = new Live("自建源", url).sync();
            LiveParser.text(live, text);
            if (live.getGroups().isEmpty()) return;
            live.setSelected(false);
            getLives().add(live);
            Logger.t(TAG).d("append self endpoint: " + url + ", groups=" + live.getGroups().size() + ", lives=" + getLives().size() + ", isOnly=" + isOnly());
        } catch (Throwable e) {
            Logger.t(TAG).e("append self endpoint failed: " + e);
        }
    }

    public void load() {
        if (sync) return;
        load(new Callback());
    }

    private void parseText(Config config, String text) {
        Live live = new Live(UrlUtil.getName(config.getUrl()), config.getUrl()).sync();
        lives = new ArrayList<>(List.of(live));
        LiveParser.text(live, text);
        setHome(config, live, false);
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            parseConfig(config, object);
        }
    }

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        if (items.isEmpty()) throw new Exception("Depot urls is empty");
        List<Config> configs = new ArrayList<>();
        // 2026-10-18: 多仓必须把每个子仓都持久化为独立可切换源(原实现只 load 了第一个，其余丢失)。
        for (Depot item : items) configs.add(Config.find(item, LIVE).save());
        Log.d(TAG, "parseDepot(live) save subRepos=" + configs.size() + " for " + config.getUrl());
        Config.delete(config.getUrl());
        load(this.config = configs.get(0));
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initLive(config, object);
    }

    public void parse(JsonObject object) {
        initLive(getConfig(), object);
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, false);
        List<Live> lives = Json.safeListElement(object, "lives").stream().map(e -> Live.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new));
        // 2026-10-13：同 initSite，把 "./lib/xxx.js" 这类相对配置文件的 api 解析成绝对地址。
        String base = config.getUrl();
        lives.forEach(live -> live.setApi(UrlUtil.resolveApi(live.getApi(), base)));
        setLives(lives);
        Map<String, Live> items = Live.findAll().stream().collect(Collectors.toMap(Live::getName, Function.identity()));
        getLives().forEach(live -> live.sync(items.get(live.getName())));
        setHome(config, getLives().isEmpty() ? new Live() : getLives().stream().filter(item -> item.getName().equals(config.getHome())).findFirst().orElse(getLives().get(0)), false);
    }

    public void setKeep(Channel channel) {
        if (home != null && !channel.getGroup().isHidden()) home.keep(channel).save();
    }

    public void applyKeepsToGroups(List<Group> items) {
        Set<String> key = Keep.getLive().stream().map(Keep::getKey).collect(Collectors.toSet());
        items.stream().filter(group -> !group.isKeep())
                .flatMap(group -> group.getChannel().stream())
                .filter(channel -> key.contains(channel.getName()))
                .forEach(channel -> items.get(0).add(channel));
    }

    public int[] findKeepPosition(List<Group> items) {
        String[] splits = getHome().getKeep().split(AppDatabase.SYMBOL);
        if (splits.length < 3) return new int[]{1, 0};
        for (int i = 0; i < items.size(); i++) {
            Group group = items.get(i);
            if (group.getName().equals(splits[0])) {
                int j = group.find(splits[1]);
                if (j != -1) {
                    group.getChannel().get(j).setIndex(splits[2]);
                    return new int[]{i, j};
                }
            }
        }
        return new int[]{1, 0};
    }

    public int[] findByChannelNumber(String number, List<Group> items) {
        int num = Integer.parseInt(number);
        for (int i = 0; i < items.size(); i++) {
            int j = items.get(i).find(num);
            if (j != -1) return new int[]{i, j};
        }
        return new int[]{-1, -1};
    }

    public List<Live> getLives() {
        return lives == null ? lives = new ArrayList<>() : lives;
    }

    private void setLives(List<Live> lives) {
        this.lives = lives;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    public Live getHome() {
        return home == null ? new Live() : home;
    }

    public void setHome(Live home) {
        setHome(getConfig(), home, true);
    }

    public Live getLive(String key) {
        return getLives().stream().filter(item -> item.getName().equals(key)).findFirst().orElse(new Live());
    }

    private void setHome(Config config, Live live, boolean save) {
        home = live;
        home.setSelected(true);
        config.setHome(home.getName());
        if (save) config.save();
        getLives().forEach(item -> item.setSelected(home));
        if (!save && (home.isBoot() || LiveSetting.isBoot())) ConfigEvent.boot();
    }

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }
}
