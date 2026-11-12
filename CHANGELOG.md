# CHANGELOG

蟹老板TV（crab-tv）版本变更与升级记录。版本号唯一真源见 `version.properties`，由 `scripts/release.py` 维护。

版本展示：App 内 **设置 → 版本** 一行显示 `版本号 · 构建时间·git 短 hash`，装到哪台设备上一眼可辨。

---

## [5.6.4] - 2026-11-07

### 配置外置化 · APK 零本地化地址

- **配置全量上 GitHub**：点播（`mobile_vod.json` / `mobile_vod_pan.json`）、直播（`merged.m3u`）等订阅配置从家庭局域网服务器（`192.168.31.29:8099`）迁移到 GitHub 仓库 [`dickens0904/crab-tv-live`](https://github.com/dickens0904/crab-tv-live)，APK 内以 `https://gh-proxy.com/https://raw.githubusercontent.com/...` 地址内置，公网可达、不再依赖家庭宽带。
- **APK 零本地化地址（外部 LAN 零依赖）**：移除全部指向家庭局域网的 `192.168.x` / `file://` 外部地址（设备自身回环 `127.0.0.1` 为 App 内部代理，保留）。
  - `assets/seed_configs.json`：35 条（含 4 条 LAN）→ **34 条（0 本地化 / 19 条 gh-proxy）**。
  - 源码 `RecommendedSource`（`VOD_URL` / `LIVE_URL` / `PAN_URL`）、`LiveSetting`（`SELF_ENDPOINT_URL`）、`LiveConfig` 注释中的家庭 IP 一并清除。
  - ✅ 删除遗留局域网网盘配对对话框 `NetdiskAuthDialog`（`192.168.31.29:8098/pair`）：依赖盒上 `pair_server.py`、无法上 GitHub；网盘授权统一走设备码 `NetdiskCenterDialog`（直连百度 / 夸克 / UC / 阿里，无服务器依赖），电视版「玩偶令牌配置」入口移除。
- **固化 5.6.3 修复**：播放错误态静默重试、配置内置化 seed 机制随本版打包。

### 升级说明（Upgrade）

- **全新安装**：首次启动当配置表为空时，自动从 `seed_configs.json` 灌入 **34 条** 默认订阅（全部 gh-proxy 公网地址），无需手动配置。
- **覆盖安装（同签名 `-r`）**：保留既有配置与历史记录。
- ⚠️ **签名注意**：本机 `debug.keystore` 与出厂包证书不一致。若覆盖安装报签名冲突：
  - **盒子（root）**：`adb uninstall` 前先 `tar` 备份 `/data/data/com.fongmi.android.tv.opencode` → 重装 → 还原并 `chown`。
  - **手机（非 root）**：需原 keystore 才能无缝升级；否则重装后由 seed 重建默认配置，原历史会丢失。

---

## [5.6.3]

### 播放错误态修复 + 配置内置化

- **修复播放错误态**：自动换源时「播放地址解析失败」文字不再常驻屏幕；三角播放键在终态能真正重新解析（`PlaybackReset` 补 `clearMediaItems()` + `clear()` 兜底）；有回退路径静默重试，无回退才提示。
- **引入配置内置化（seed）**：首次启动 Config 表空时由 `assets/seed_configs.json` 自动灌入默认订阅，解耦配置与签名 / 设备，升级不再丢配置。
- 版本 `5.6.3` / `VERSION_CODE 563`。

---

## 版本历史速查

| 版本 | 日期 | 要点 |
|---|---|---|
| 5.6.4 | 2026-11-07 | 配置全量上 GitHub + gh-proxy 内置；APK 零本地化地址；固化 5.6.3 修复 |
| 5.6.3 | 2026-11（上旬） | 播放错误态静默重试；配置内置化 seed 机制 |
| 5.6.2 | 早于本日志 | 基线版本（具体变更未在本仓库记录） |

> 更早的变更未在本仓库留存记录；如需补全请参照 GitHub Release 页与各版本 APK 附带的发布说明。
