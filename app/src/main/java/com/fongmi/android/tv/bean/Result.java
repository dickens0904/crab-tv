package com.fongmi.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.gson.DanmakuAdapter;
import com.fongmi.android.tv.gson.FilterAdapter;
import com.fongmi.android.tv.gson.HeaderAdapter;
import com.fongmi.android.tv.gson.MsgAdapter;
import com.fongmi.android.tv.gson.UrlAdapter;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.utils.Trans;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import org.json.JSONObject;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Path;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.core.Persister;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fongmi.android.tv.utils.ContentFilter;

@Root(name = "rss", strict = false)
public class Result implements Parcelable {

    @Path("class")
    @ElementList(entry = "ty", required = false, inline = true)
    @SerializedName("class")
    private List<Class> types;

    @Path("list")
    @ElementList(entry = "video", required = false, inline = true)
    @SerializedName("list")
    private List<Vod> list;

    @SerializedName("filters")
    @JsonAdapter(FilterAdapter.class)
    private LinkedHashMap<String, List<Filter>> filters;

    @SerializedName("url")
    @JsonAdapter(UrlAdapter.class)
    private Url url;

    @SerializedName("header")
    @JsonAdapter(HeaderAdapter.class)
    private Map<String, String> header;

    @SerializedName("msg")
    @JsonAdapter(MsgAdapter.class)
    private String msg;

    @SerializedName("danmaku")
    @JsonAdapter(DanmakuAdapter.class)
    private List<Danmaku> danmaku;

    @SerializedName("subs")
    private List<Sub> subs;
    @SerializedName("playUrl")
    private String playUrl;
    @SerializedName("artwork")
    private String artwork;
    @SerializedName("jxFrom")
    private String jxFrom;
    @SerializedName("flag")
    private String flag;
    @SerializedName("desc")
    private String desc;
    @SerializedName("format")
    private String format;
    @SerializedName("click")
    private String click;
    @SerializedName("key")
    private String key;
    @SerializedName("position")
    private Long position;
    @SerializedName("pagecount")
    private Integer pagecount;
    @SerializedName("parse")
    private Integer parse;
    @SerializedName("code")
    private Integer code;
    @SerializedName("jx")
    private Integer jx;
    @SerializedName("drm")
    private Drm drm;

    /**
     * 2026-10-13：空结果的原因码。
     *
     * <p>以前所有“空”一律提示“多半依赖 X5 / JS 引擎，本程序未内置”。真机实测下来这句话基本是错的：
     * 绝大多数空结果要么是站点返回了 HTML 风控页 / 错误页，要么是这个分类本来就没有内容。
     * 由 {@code SiteApi} 在解析原始响应时判定，UI 据此给不同的文案。
     */
    public static final int EMPTY_NONE = 0;
    /** 源返回了合法 JSON，但这个分类 / 这次查询确实没有内容。 */
    public static final int EMPTY_NO_CONTENT = 1;
    /** 源返回的不是预期数据：HTML 风控页、空响应体、或根本解析不了。 */
    public static final int EMPTY_BAD_RESPONSE = 2;

    /** 不参与序列化，只是本次解析的诊断结论。 */
    private int emptyReason = EMPTY_NONE;

    public int getEmptyReason() {
        return emptyReason;
    }

    public Result setEmptyReason(int reason) {
        this.emptyReason = reason;
        return this;
    }

    /** 空结果该给用户看哪条提示 —— 文案映射放在 mobile 侧，main 不碰 flavor 资源。 */
    public Result() {
    }

    protected Result(Parcel in) {
        this.types = new ArrayList<>();
        in.readList(this.types, Class.class.getClassLoader());
        this.emptyReason = in.readInt();
    }

    public static Result objectFrom(String str) {
        try {
            return App.gson().fromJson(str, Result.class);
        } catch (Exception e) {
            return empty();
        }
    }

    public static Result fromJson(String str) {
        Result result = objectFrom(str);
        return result == null ? empty() : result.trans();
    }

    public static Result fromXml(String str) {
        try {
            return new Persister().read(Result.class, str, false).trans();
        } catch (Exception e) {
            return empty();
        }
    }

    public static Result fromType(int type, String str) {
        return type == 0 ? fromXml(str) : fromJson(str);
    }

    public static Result fromObject(JSONObject object) {
        return object == null ? empty() : objectFrom(object.toString());
    }

    public static Result empty() {
        return new Result();
    }

    public static Result error(String msg) {
        Result result = new Result();
        result.setParse(0);
        result.setMsg(msg);
        return result;
    }

    public static Result folder(Vod item) {
        Result result = new Result();
        Class type = new Class();
        type.setTypeFlag("1");
        type.setTypeId(item.getId());
        type.setTypeName(item.getName());
        result.setTypes(Arrays.asList(type));
        return result;
    }

    public static Result type(String json) {
        Result result = new Result();
        result.setTypes(Arrays.asList(Class.objectFrom(json)));
        return result.trans();
    }

    public static Result list(List<Vod> items) {
        Result result = new Result();
        result.setList(items);
        return result;
    }

    public static Result vod(Vod item) {
        return list(Arrays.asList(item));
    }

    /** 未过滤的全部分类（含被隐藏的），供「过滤」对话框列出。 */
    public List<Class> getAllTypes() {
        return types == null ? Collections.emptyList() : types;
    }

    public List<Class> getTypes() {
        // 2026-10-25: 过滤成人向分栏（伦理片/写真热舞/理论片/福利/三级/里番…）。
        // 只删命中的分类，同一源内其它正常分栏原样保留；电视端与手机端都走这里。
        if (types == null) return Collections.emptyList();
        // 按站点分别记录规则（家长控制）：切换源再切回来，隐藏的分栏仍然隐藏
        return ContentFilter.filter(types, getKey());
    }

    public void setTypes(List<Class> types) {
        this.types = types;
    }

    public List<Vod> getList() {
        return list == null ? Collections.emptyList() : list;
    }

    public void setList(List<Vod> list) {
        this.list = list;
    }

    public LinkedHashMap<String, List<Filter>> getFilters() {
        return filters == null ? new LinkedHashMap<>() : filters;
    }

    public Url getUrl() {
        return url == null ? Url.create() : url;
    }

    public void setUrl(String url) {
        this.url = getUrl().replace(url);
    }

    public String getMsg() {
        return TextUtils.isEmpty(msg) || getCode() != 0 ? "" : msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public List<Sub> getSubs() {
        return subs == null ? new ArrayList<>() : new ArrayList<>(subs);
    }

    public void setSubs(List<Sub> subs) {
        if (getSubs().isEmpty()) this.subs = subs;
    }

    public Map<String, String> getHeader() {
        return header == null ? new HashMap<>() : header;
    }

    public void setHeader(Map<String, String> header) {
        if (getHeader().isEmpty()) this.header = header;
    }

    public String getPlayUrl() {
        return TextUtils.isEmpty(playUrl) ? "" : playUrl;
    }

    public void setPlayUrl(String playUrl) {
        this.playUrl = playUrl;
    }

    public String getArtwork() {
        return TextUtils.isEmpty(artwork) ? "" : artwork;
    }

    public String getJxFrom() {
        return TextUtils.isEmpty(jxFrom) ? "" : jxFrom;
    }

    public String getFlag() {
        return TextUtils.isEmpty(flag) ? "" : flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public String getDesc() {
        return TextUtils.isEmpty(desc) ? "" : Util.clean(desc);
    }

    public List<Danmaku> getDanmaku() {
        if (!DanmakuSetting.isLoad()) return new ArrayList<>();
        return danmaku = danmaku == null ? new ArrayList<>() : danmaku;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getClick() {
        return TextUtils.isEmpty(click) ? "" : click;
    }

    public void setClick(String click) {
        this.click = click;
    }

    public String getKey() {
        return TextUtils.isEmpty(key) ? "" : key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Long getPosition() {
        return position;
    }

    public void setPosition(Long position) {
        this.position = position;
    }

    public Integer getPageCount() {
        return pagecount == null ? 0 : pagecount;
    }

    public Integer getParse() {
        return parse == null ? 0 : parse;
    }

    public void setParse(Integer parse) {
        this.parse = parse;
    }

    public Integer getCode() {
        return code == null ? 0 : code;
    }

    public Integer getJx() {
        return jx == null ? 0 : jx;
    }

    public Drm getDrm() {
        return drm;
    }

    public void setDrm(Drm drm) {
        this.drm = drm;
    }

    public boolean hasMsg() {
        return !getMsg().isEmpty();
    }

    public boolean hasArtwork() {
        return !getArtwork().isEmpty();
    }

    public boolean hasPosition() {
        return getPosition() != null;
    }

    public boolean hasDesc() {
        return !getDesc().isEmpty();
    }

    public boolean isUseParse() {
        if (!VodConfig.hasParse()) return false;
        return (getPlayUrl().isEmpty() && VodConfig.get().getFlags().contains(getFlag())) || getJx() == 1;
    }

    public boolean needParse() {
        return getParse() == 1 || getJx() == 1;
    }

    public String getRealUrl() {
        return getPlayUrl() + getUrl().v();
    }

    public Style getStyle(Style style) {
        return getList().isEmpty() ? Style.rect() : getList().get(0).getStyle(style);
    }

    public Vod getVod() {
        return getList().isEmpty() ? new Vod() : getList().get(0);
    }

    public Result clear() {
        getList().clear();
        return this;
    }

    public Result trans() {
        if (Trans.pass()) return this;
        getTypes().forEach(Class::trans);
        getList().forEach(Vod::trans);
        getSubs().forEach(Sub::trans);
        return this;
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeList(this.types);
        dest.writeInt(this.emptyReason);
    }

    public static final Creator<Result> CREATOR = new Creator<>() {
        @Override
        public Result createFromParcel(Parcel source) {
            return new Result(source);
        }

        @Override
        public Result[] newArray(int size) {
            return new Result[size];
        }
    };
}
