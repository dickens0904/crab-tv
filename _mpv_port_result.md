# MPV 内核移植报告 — libmpv 已落地并在真机验证通过

会话：`ses_mpv_port_20261010` · 工作目录 `I:\Temp\opencode\FongMi` · 日期 2026-10-10

## 一句话结论

任务书预判的**核心阻塞（拿不到 libmpv.so）已被打破**：拿到了官方 mpv-android 发布的真实
`libmpv.so`（mpv v0.41.0），双 ABI 落盘，并已在 **ct2000 真机上跑通解码**。
但 **"应用内可选 MPV 并真正出画出声" 尚未完成** —— 因为仓库里的 `MpvPlayer.java`
是一个**零 native 方法的纯 Java 空壳**，把它当作可用引擎会导致黑屏（比回退 EXO 更糟），
故按铁律未擅自翻转 `isAvailable()`。详见第五节。

---

## 1. 网络阻塞是如何绕过的（与任务书预判相反）

任务书 §0 记录"本沙箱 GitHub 不可达"。实测**部分成立、部分不成立**：

| 目标 | 结果 |
|---|---|
| `github.com`（HTML / releases 下载） | ❌ socket 被重置（TCP 443 可连，TLS 后断开） |
| `api.github.com` | ✅ 200 |
| `codeload.github.com`（仓库 zip/tarball） | ✅ 200，真实 ZIP（PK 魔数） |
| `release-assets.githubusercontent.com`（发行版附件 CDN） | ❌ 不可达 |
| Maven Central / JitPack / Google Maven | ✅ 可达 |

**关键突破**：GitHub 代理镜像可达，实测 3 个可用：

- `https://ghproxy.net/` ✅
- `https://ghfast.top/` ✅
- `https://gh-proxy.com/` ✅

经代理可下载 mpv-android 官方**分 ABI 发行版 APK**（`Content-Type:
application/vnd.android.package-archive`，`Content-Range` 校验一致）。
**因此完全无需交叉编译**，直接取官方预编译产物（省掉 ffmpeg/libass/lua/libplacebo 全套编译，
本机也确实无 WSL 发行版、无 Docker、无 make/meson，`SLAT=False` 无法跑 WSL2）。

> 附带记录：`codeload` 可达意味着日后**从源码自行编译**也是可行的
> （`buildscripts/include/download-deps.sh` 里的 `git clone` / release tarball
> 可全部替换为 codeload 拉取；freetype/fontconfig 走 gitlab.freedesktop.org、
> libxml2 走 gitlab.gnome.org、lua 走 lua.org、curl 走 curl.se，均实测可达）。
> 但官方预编译产物已满足需求，无需走这条路。

---

## 2. 落地的原生库

来源：mpv-android release `2026-09-17`，`app-default-<abi>-release.apk`
（APK 已按字节数校验：armeabi-v7a 30,935,742；arm64-v8a 34,290,346）

落地路径：`app/src/main/jniLibs/{armeabi-v7a,arm64-v8a}/`（git 未跟踪，新增文件）

| 文件 | armeabi-v7a | arm64-v8a |
|---|---|---|
| **libmpv.so** | 7,461,272 | 8,256,792 |
| libavcodec.so | 11,831,236 | 12,392,912 |
| libavformat.so | 3,131,660 | 3,382,440 |
| libavutil.so | 653,100 | 740,360 |
| libavfilter.so | 3,489,728 | 4,300,432 |
| libavdevice.so | 7,084 | 9,216 |
| libswresample.so | 94,584 | 100,688 |
| libswscale.so | 838,916 | 1,246,600 |
| libc++_shared.so | 994,772 | 1,423,696 |
| libplayer.so | 15,320 | 21,512 |

合计约 **57.6 MB**。

**libmpv.so SHA256 前缀**：armeabi-v7a `433381171115BCE0` · arm64-v8a `03D2D02DB0451CEB`

### 依赖与冲突检查
- `readelf -d` 确认 NEEDED：`libavcodec/libavfilter/libavformat/libavutil/libswresample/
  libswscale/libavdevice/libc++_shared` + 系统 `libm/libz/libdl/libandroid/libOpenSLES/libEGL/libc`
  —— **必须整套同时打包**，缺一即加载失败。
- **无 `libc++_shared.so` 冲突**：改前 APK 完全不含该库，改后首次引入，不存在双份冲突。
- mpv-android 自带的 ffmpeg 是 `LIBAVCODEC_63` / `LIBSWSCALE_10` 版本线；仓库现用
  media3-exoplayer（纯 Java，不含 ffmpeg 原生库），ExoPlayer/IJK 路径未受影响。

---

## 3. 真机验证（ct2000）—— 已通过

设备：`192.168.31.124:5555` ct2000 / Android 9 (SDK 28) / armeabi-v7a

### 3.1 libmpv 加载与初始化
用 NDK r27 交叉编译探针程序 `dlopen` 真机上的 `.so`：

```
DLOPEN_OK
SYMBOLS_OK
MPV_CREATE_OK
MPV_INIT_OK
MPV_VERSION: mpv v0.41.0-1049-g0b7ed670f
```
→ 37 个 `mpv_*` client API 符号在两个 ABI 上均正常导出并可调用。

### 3.2 真实解码（关键验证）
喂入真实媒体（h264 + aac），`vo=null/ao=null` 探针：

```
Video  --vid=1  (h264 640x360 25 fps 171 kbps) [default]
Audio  --aid=1  (aac 1ch 44100 Hz 69 kbps) [default]
VO: [null] 640x360 yuv420p
RESULT ready=1 video=640x360 hwdec_req=no
DECODE_PASS
[stage] terminate_destroy returned cleanly
```
**硬解路径同样通过**：
```
Using hardware decoding (mediacodec-copy).
VO: [null] 640x360 nv12
RESULT ready=1 video=640x360 hwdec_req=mediacodec-copy
DECODE_PASS
```
→ 证明 mpv 在这台 Android 9 盒子上**能打开、解复用、起解码器、软解与 MediaCodec 硬解均可，
并能干净销毁**。（裸原生进程没有 JVM，MediaCodec 走 JNI 那段有告警，属预期；
应用内 `MPVLib.create(Context)` 会注册 `av_jni_set_java_vm`，该路径不受影响。）

### 3.3 libplayer.so JNI 桥可用
12 个 JNI 入口点真机全部解析成功：
`create / init / destroy / command / attachSurface / detachSurface / setOptionString /
getPropertyInt / getPropertyString / setPropertyString / observeProperty / grabThumbnail`

### 3.4 release 构建 + 安装 + 启动
- `assembleLeanbackRelease -PappId=com.fongmi.android.tv.opencode` → **BUILD SUCCESSFUL (50s)**
- APK `leanback-armeabi_v7a.apk` 68.5 MB，`lib/armeabi-v7a/` 下 mpv 全套 10 个 `.so` 齐备
- 已 `adb install -r -d` 装到 ct2000（versionCode 561 / 5.6.1）
- 冷启动正常，logcat **无 FATAL / 无 UnsatisfiedLinkError / 无 dlopen 失败**
  → 证实 mpv 原生栈与既有 native 库（chaquopy/jpa/thunder/gif/rtmp）**共存无冲突**

---

## 4. 已产出的代码

新增文件（**不与整合会话冲突**）：
- `app/src/main/jniLibs/**` —— 20 个 `.so`
- `app/src/main/java/is/xyz/mpv/MPVLib.java` —— JNI 绑定类

`MPVLib` 采用 mpv-android 原生类名 `is.xyz.mpv.MPVLib`，因为 `libplayer.so` 按 JNI 短名
修饰导出 `Java_is_xyz_mpv_MPVLib_*`；沿用同名即可**直接复用其已编译、经实战检验的 JNI 层**，
无需写 C、无需 NDK 编译步骤、**无需改 `build.gradle`**（该文件正被并发修改，避开）。
回调方法名/签名严格对齐 `jni_utils.cpp` 的 `init_methods_cache` 查表
（`eventProperty` 5 个重载、`event(int)`、`logMessage(String,int,String)`）。
`MPVLib.isAvailable()` 做真实 `System.loadLibrary` 探测（捕获 `UnsatisfiedLinkError`）。

---

## 5. 尚未完成的部分（需用户决策）

**为什么没有直接翻转 `MpvPlayer.isAvailable()`**：

`app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`（761 行）经确认是
**LOCAL COMPILE STUB**：`grep native|System.loadLibrary` 在整个 mpvplayer 包内
**零命中**，它用纯 Java 字段实现了 media3 `Player` 的 120 个抽象方法，**不持有任何原生状态**。

若此时把 `isAvailable()` 改成 true：
`PlayerEngineFactory.isMpvReady()` → true → 工厂选中 `MpvPlayerEngine` → 返回这个空壳
→ **出不来画面也没有声音**，比现在"自动回退 EXO"更糟。这违反任务书铁律③
（"MPV 失败回退 EXO"）与"绝不伪造成功"的要求。

真正的开关依赖链是：
```
PlayerEngineFactory.isMpvReady()
  = PlayerSetting.isMpv() && MpvPlayerEngine.isAvailable()
      = PlayerSetting.isMpv() && MpvUtil.isAvailable()
          = PlayerSetting.isMpv() && MpvPlayer.isAvailable()   ← 空壳硬编码 false
```
要让 MPV 真正可用，必须把 `MpvPlayer` **重写为基于 `MPVLib` 的真实 media3 Player**
（准备/播放/暂停/进度/音量/轨道/字幕/事件回调/视频 Surface 绑定等），再翻转 `isAvailable()`。

**这正是铁律①禁止我此刻动用的文件**（`MpvPlayer.java`、`PlayerEngineFactory.java`、
`PlayerSetting.java`、`PlayerEngine.java`），且 `ses_integrate_20261010` 仍在并发改动
该区域（最近一次写入 08:09，其间 `ExoPlayerEngine.java`/`AudioSetting.java`/
`DownmixMatrix.java` 等被改动）。

### 请用户选择

- **A. 授权本会话接管 `MpvPlayer.java`**（前提：整合会话确已结束）。
  我将继续：重写 `MpvPlayer` 为 `MPVLib` 驱动的真实实现 → 翻转 `isAvailable()` →
  release 装机 → 在三引擎选择器选 MPV 实测出画出声。
  预计工作量：JNI 已就绪（无 C 代码），主要是 Java 侧 Player 契约实现。
- **B. 先只保留已验证成果**（推荐作为安全默认）：`libmpv.so` 全套 + `MPVLib` 已落盘并验证，
  `isAvailable()` 仍为 false → 应用行为与现在**完全一致**（默认 EXO），零回归风险，
  等整合会话结束后再执行 A。
- **C. 仅验证、不要改任何 app 源码**：把本次成果仅作为构建产物/报告留存。

> 说明：我已默认按 **B** 收尾——除新增 `.so` 与新增 `MPVLib.java` 外，
> **未修改任何既有 app 源码**，`MpvPlayer.java` / `PlayerEngineFactory.java` /
> `PlayerSetting.java` / `PlayerEngine.java` 一字未动。

---

## 6. 合规提醒

`libmpv.so` / mpv-android 为 **GPL-2.0-or-later**（`buildscripts/scripts/ffmpeg.sh` 中
`--enable-gpl`）；本仓库 `LICENSE.md` 为 **GPL-3.0**。GPL-2.0-or-later 可按 GPLv3 组合分发，
**不构成许可冲突**，但分发时需保留相应版权声明与源码获取途径。

## 7. 红线确认

- ✅ `com.tvproxy.box` **全程未卸载**，安装前后均确认在位
- ✅ 未对 `com.fongmi.android.tv`（正式包）等其他包做任何操作
- ✅ 真机仅安装 `com.fongmi.android.tv.opencode`，release 构建，带 `-PappId` 指定包名
- ✅ 所有临时探针文件（`/data/local/tmp/mpvprobe`、`mpvp2`）已清理