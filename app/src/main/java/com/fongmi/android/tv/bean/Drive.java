package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import com.fongmi.android.tv.db.AppDatabase;
import com.google.gson.annotations.SerializedName;
import java.util.List;

@Entity(tableName = "drive")
public class Drive {
    public static final String TYPE_ALIYUN = "aliyun";
    public static final String TYPE_BAIDU = "baidu";
    public static final String TYPE_WEBDAV = "webdav";

    @PrimaryKey
    @NonNull
    @SerializedName("type")
    private String type = "";

    @SerializedName("name")
    private String name;

    @SerializedName("refresh_token")
    private String refreshToken;

    @SerializedName("token")
    private String token;

    @SerializedName("updated")
    private long updated;

    @SerializedName("webdav_pass")
    private String webdavPass;

    @SerializedName("webdav_url")
    private String webdavUrl;

    @SerializedName("webdav_user")
    private String webdavUser;

    public Drive() {
        this.type = "";
    }

    @Ignore
    public Drive(String type, String name) {
        this.type = type;
        this.name = name;
    }

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToken() {
        return this.token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getWebdavUrl() {
        return this.webdavUrl;
    }

    public void setWebdavUrl(String webdavUrl) {
        this.webdavUrl = webdavUrl;
    }

    public String getWebdavUser() {
        return this.webdavUser;
    }

    public void setWebdavUser(String webdavUser) {
        this.webdavUser = webdavUser;
    }

    public String getWebdavPass() {
        return this.webdavPass;
    }

    public void setWebdavPass(String webdavPass) {
        this.webdavPass = webdavPass;
    }

    public long getUpdated() {
        return this.updated;
    }

    public void setUpdated(long updated) {
        this.updated = updated;
    }

    public String getRefreshToken() {
        return this.refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public boolean isTokenValid() {
        return (this.token == null || this.token.isEmpty() || System.currentTimeMillis() - this.updated > 604800000) ? false : true;
    }

    public boolean canRefresh() {
        return (this.refreshToken == null || this.refreshToken.isEmpty()) ? false : true;
    }

    public static Drive createAliyun(String name) {
        Drive drive = new Drive(TYPE_ALIYUN, name);
        drive.save();
        return drive;
    }

    public static Drive createWebdav(String name) {
        Drive drive = new Drive(TYPE_WEBDAV, name);
        drive.save();
        return drive;
    }

    public static Drive createBaidu(String name) {
        Drive drive = new Drive(TYPE_BAIDU, name);
        drive.save();
        return drive;
    }

    public Drive save() {
        setUpdated(System.currentTimeMillis());
        AppDatabase.get().getDriveDao().insertOrUpdate(this);
        return this;
    }

    public void delete() {
        AppDatabase.get().getDriveDao().delete(this.type);
    }

    public static Drive findByType(String type) {
        return AppDatabase.get().getDriveDao().findByType(type);
    }

    public static List<Drive> findAll() {
        return AppDatabase.get().getDriveDao().findAll();
    }

    public static Drive getAliyun() {
        Drive drive = findByType(TYPE_ALIYUN);
        return drive != null ? drive : createAliyun("阿里云盘");
    }

    public static Drive getWebdav() {
        Drive drive = findByType(TYPE_WEBDAV);
        return drive != null ? drive : createWebdav("WebDAV");
    }

    public static Drive getBaidu() {
        Drive drive = findByType(TYPE_BAIDU);
        return drive != null ? drive : createBaidu("百度网盘");
    }
}
