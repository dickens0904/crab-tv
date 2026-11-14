# 蟹老板TV（crab-tv）

基于 [FongMi/TV](https://github.com/FongMi/TV)（GPL-3.0）二次开发的安卓电视 + 手机直播/点播 App。
**上游代码与许可证保持原样（GPL-3.0），本仓库为衍生作品。**

| 项 | 值 |
|---|---|
| 包名 | `com.fongmi.android.tv.opencode` |
| 桌面名 | 蟹老板TV |
| 版本 | `version.properties`（`VERSION_NAME` / `VERSION_CODE`，由 `scripts/release.py` 维护） |
| 上游 remote | `upstream` → https://github.com/FongMi/TV.git |

## 目录与配套工程
| 目录 | 内容 |
|---|---|
| `app/src/main` | 双 flavor 共享代码（播放器引擎、直播/点播配置、直播源库相关） |
| `app/src/leanback` | 电视端 UI（遥控器操作） |
| `app/src/mobile` | 手机端 UI（触屏/分区手势） |
| `scripts/release.py` | 发版脚本：自增版本号 → 提交 → 打 tag → 构建 → GitHub Release |
| `version.properties` | 版本号唯一真源 |

配套的**直播源库工程**在另一仓库：https://github.com/dickens0904/crab-tv-live （爬虫 + 公网订阅）。

## 发版
```bash
python scripts/release.py --level patch     # 5.6.2 → 5.6.3，同时自增 VERSION_CODE
python scripts/release.py --level minor     # 5.6.2 → 5.7.0
python scripts/release.py --dry-run         # 只看计划
```
脚本会：写 `version.properties` → `git commit` + `git tag v<版本>` → 构建 mobile/leanback release 包
→ 建 GitHub Release（附 APK）→ push 分支与 tag。

版本号展示：App 内 **设置 → 版本** 一行显示 `版本号 · 构建时间·git 短 hash`，
装到哪台设备上一眼可辨（不必再靠 MD5 认包）。

## 相对上游的主要改动（持续更新）
- **直播多源**：同频道多线路聚合（`Channel.urls`），直播页显示「线路 N」，←/→ 或「线路」按钮切线
- **直播源库接入**：公网/局域网 m3u 订阅，线路按 **直播窗口长度 → 清晰度 → 在线率 → 耗时** 排序
  （小窗口源会在每 30 秒的窗口末尾等下一个分片而卡顿，详见 `crab-tv-live`）
- **预取器**：跟随 302 解析分片 base、连续失败主动收手、按缓冲缺口决定并发
- **冻结看门狗**：20 秒无推进自动换线/换台
- **画面比例档位**：原始 / 16:9 / 4:3 / 填充 / 裁剪（4:3、16:9 为强制显示框，不拉伸）
- **四云盘扫码授权**、**离网门控**、**代理三态**、**多仓解析**、**红蟹图标与主题**

## v5.6.4 变更说明（2026-11-07）

- **配置全量上 GitHub + gh-proxy 内置**：点播（`mobile_vod.json` / `mobile_vod_pan.json`）、直播（`merged.m3u`）等订阅配置从家庭局域网服务器（`192.168.31.29:8099`）迁移到 GitHub 仓库 [`dickens0904/crab-tv-live`](https://github.com/dickens0904/crab-tv-live)，APK 内以 `https://gh-proxy.com/https://raw.githubusercontent.com/...` 地址内置，公网可达、不再依赖家庭宽带。
- **APK 零本地化地址（外部 LAN 零依赖）**：移除全部指向家庭局域网的 `192.168.x` / `file://` 外部地址；`assets/seed_configs.json` 由 35 条（含 4 条 LAN）精简为 **34 条（0 本地化 / 19 条 gh-proxy）**；源码 `RecommendedSource` / `LiveSetting` / `LiveConfig` 中的家庭 IP 一并清除。残留的 `127.0.0.1` 均为 App 自身回环代理（播放器内代理 / 投屏等运行期 localhost），非外部依赖，保留。
  - ✅ 已删除遗留的局域网网盘配对对话框 `NetdiskAuthDialog`（`192.168.31.29:8098/pair`）：该路径依赖盒上 `pair_server.py` 守护进程、无法上 GitHub；网盘授权现统一走设备码 `NetdiskCenterDialog`（直连百度 / 夸克 / UC / 阿里，无需任何服务器），电视版设置页「玩偶令牌配置」入口一并移除。
- **固化 5.6.3 修复**：播放错误态静默重试（自动换源时「播放地址解析失败」文字不再常驻、三角播放键在终态真正重新解析）、配置内置化 seed 机制随本版打包（详见下方「配置内置化」）。
- 版本 `5.6.4` / `VERSION_CODE 564`。

### 配置内置化（5.6.3 引入，5.6.4 固化）

首次启动当配置表（`Config`）为空（全新安装 / 签名变更重装）时，自动从 `app/src/main/assets/seed_configs.json` 灌入默认订阅，彻底解耦配置与签名 / 设备，升级不再丢配置。任何异常全吞，绝不拖垮启动。

### 升级 / 迁移说明

- **全新安装**：自动 seed 34 条默认订阅（全部 gh-proxy 公网地址），无需手动配置。
- **覆盖安装（同签名 `-r`）**：保留既有配置与历史。注意本机 `debug.keystore` 与出厂包证书不同；若签名不一致，盒上需走 root 备份还原、手机需重新授权后安装（装后同样自动 seed，原配置由 `-r` 保留）。
- 配套直播源库工程：[`dickens0904/crab-tv-live`](https://github.com/dickens0904/crab-tv-live)（爬虫 + 公网订阅，经 gh-proxy 加速）。
