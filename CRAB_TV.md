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
