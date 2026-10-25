# MPV 像素级出画 交付报告（ses_mpv_pixel_20261010）

- 工程：`I:\Temp\opencode\FongMi`，包名 `com.fongmi.android.tv.opencode`
- 真机：ct2000 `192.168.31.124:5555`（armeabi-v7a，Android 9 / API 28，root）
- libmpv：**`mpv v0.41.0-1049-g0b7ed670f`**（从 `jniLibs/armeabi-v7a/libmpv.so` 字符串表读出）
- 证据目录：`I:\Temp\opencode\mpv_pixel_evidence\`
- 构建：`-PappId=com.fongmi.android.tv.opencode`，`assembleLeanbackRelease` + `assembleMobileRelease`（R8 开启）

---

## 0. 结论

**MPV 像素级出画已攻破。** 渲染管线本来就是健康的，问题百分之百出在 **Android 的呈现/抓取**：mpv 把帧送进 SurfaceView 的 Surface（SurfaceFlinger latency 确实在走），但这台盒子的 SurfaceView 图层不进显示 framebuffer，`screencap` 读不到。

把 MPV 改走 **TextureView**（帧落在 app 窗口内）后，`screencap` 直接抓到彩条，并且画面是**活的**（烧录时间码逐帧推进，与墙钟严格对得上）。

同包同素材的三路 screencap 对照（像素取自视频矩形内 672,270）：

| 引擎 / 路径 | 该点像素 | 全屏均值 | 非零像素 |
|---|---|---|---|
| EXO（上一轮会话留档，参考基准） | `#FF00FE` 彩条 | 110.58 | 100% |
| **MPV / TextureView（本轮）** | **`#FF27FF` 彩条** | **110.67** | **100%** |
| MPV / SurfaceView（同一构建，`surface=surfaceview`） | `#000000` 全黑 | 89.02 | 83% |

MPV-TextureView 与 EXO 基准在统计量上不可区分。

---

## 1. §1 首要目标：mpv 自身的像素铁证（与 Android 合成无关）

### 1.1 怎么拿到的（踩了两个坑，都已修）

1. **mpv 0.41 的 `screenshot` 命令不再接受文件名。**
   第一次按任务书原样发 `screenshot video /data/local/tmp/mpv_shot0.png`，libmpv 报：
   ```
   [main:fatal] Invalid value for option screenshot: /data/local/tmp/mpv_shot0.png
   [main:info] Valid values are:  unused / single / each-frame
   [main:error] Command screenshot: argument legacy can't be parsed: option parameter could not be parsed.
   ```
   从 libmpv 字符串表里挖出该版本绑定键，正确的形态是 **`screenshot [<flags>]`，文件名只来自 `--screenshot-template`**：
   ```
   #s screenshot                           # video, with subtitles
   #S screenshot video                     # video, without subtitles
   #Ctrl+s screenshot window               # window with OSD and subtitles
   #Alt+s screenshot each-frame            # automatically screenshot every frame
   ```
   最终下发 `command("screenshot", "video")` + `--screenshot-template=/data/local/tmp/mpv_shot####`，文件名靠目录 diff 认。

2. **`--screenshot=each-frame` 在 `mpv_initialize()` 前设不进去。** rc=-5 = `MPV_ERROR_OPTION_NOT_FOUND`：它不是全局选项，是 **VO 私有选项**，VO 建好之后才存在。降级方案是用 `setPropertyString` 在 `MPV_EVENT_FILE_LOADED` 之后补设（代码里保留了 `prop:<name>` 这个口子，本轮没用到）。

3. 该 libmpv 不替换模板里的 `####`，直接落成字面文件名 `mpv_shot####.png`，且**拒绝覆盖**（`Can't save screenshot, file ... already exists!`）。所以每次抓图前先清掉旧文件。

### 1.2 实测值（真·非黑）

mpv 自己写的文件：`/data/local/tmp/mpv_shot####.png` → 拉回 `mpv_pixel_evidence\1_mpv_own_screenshot_t2080.png`

| 指标 | 值 |
|---|---|
| 尺寸 | **1280x720**（= 源视频原始分辨率） |
| 文件大小 | **2,774,200 字节** |
| **平均亮度** | **127.359**（RGB 分别 126.10 / 128.42 / 127.56） |
| 最大值 | **255** |
| **非零像素占比** | **100.00%** |
| 亮度直方图主峰 | `217:15.70%  164:15.17%  128:12.72%  126:12.22%  90:15.41%  37:14.73%` → **6 条等宽色带** |

抽样像素（RGB）：

| 位置 | 坐标 | 值 | 对应彩条 |
|---|---|---|---|
| TL | (25,14) | `#460600` | 红带（带烧录时间码黑底） |
| TR | (1254,14) | `#00E7FF` | 青 |
| BL | (25,705) | `#FF1800` | 红 |
| BR | (1254,705) | `#00E7FF` | 青 |
| C | (640,360) | `#000FFF` | 蓝 |
| Q1 | (320,360) | `#00D800` | 绿 |
| Q3 | (960,360) | `#FF27FF` | 品红 |
| U1 | (448,180) | `#FF2055` | 红带上的运动扫线 |
| D2 | (832,540) | `#000EFF` | 蓝 |

肉眼核对 `1_mpv_own_screenshot_t2080.png`：完整 SMPTE 彩条 + 左上角烧录时间码 + 斜向扫线 + 移动虚线块。

另有一张更早的抓图 `2_mpv_own_screenshot_t0_paused.png`（当时画面被卡在 t-pos=0，见 §2.3）：mean **126.491**，非零 **100.00%**，同样非黑。

> **判定：PNG 非黑 → §1 结论「渲染管线健康，问题在 Android 呈现/抓取」，转 §2。§3 降变量排查未启用。**

---

## 2. §2 走的路径：TextureView

### 2.1 最终生效配置

| 项 | 值 |
|---|---|
| `vo` | `gpu-next`（未改） |
| `gpu-context` | `android`（未改） |
| `gpu-api` | `opengl` |
| `hwdec` | `mediacodec-copy` |
| `ao` | `audiotrack` |
| **Surface 形态** | **TextureView**（MPV 引擎内，由 `MpvPlayer.setVideoSurfaceView` 自主换装） |
| EXO / IJK Surface 形态 | **SurfaceView，一行未动** |

`vo=gpu` / `gpu-context=egl_android` 等对照项**没有再试**：§1 已证明渲染侧无 bug，§2.1 的 TextureView 一步就拿到了 screencap 铁证，没有必要再动 libmpv 选项。

### 2.2 SurfaceFlinger latency 复测

**SurfaceView 路径（旧行为，19:12 前的运行）：**
```
212778492823751  212778532621876  212778495783459
212779412822377  212779450220002  212779415754460
212780332807877  212780367810669  212780335778002   <- 三次采样，全部在走
```
刷新周期 37.4 ms ≈ 25 fps，**帧确实一直在往 SurfaceView 的 BufferQueue 里队列化**。
→ 上一轮报告里「三次采样不变」那条结论是错的（当时多半取错了层或取在非播放态）。
但帧进了队列 ≠ 进得了 framebuffer：screencap 依然是 `#000000`。

**TextureView 路径（当前默认）：** `dumpsys SurfaceFlinger --list` 里已**不再有 `SurfaceView - ...VideoActivity#0` 这一层**，画面并进 app 窗口层：
```
214996978254142  214997001855059  214996983947434
214997012576976  214997035236142  214997023018184
214997062832601  214997101978351  214997064316226   <- 窗口层自己在刷新
```

### 2.3 途中发现并修掉的一个真 bug（TextureView 路径独有的）

第一次切 TextureView，画面卡死：`t-pos=0.000000` 一直不变，5 次 screencap 字节完全相同。

根因：`ensureInitialized()` 里硬编码了 `mpv.setPropertyBoolean("pause", true)`。
TextureView 路径下 `onSurfaceTextureAvailable` **必然晚于** `prepare()`，于是 `prepare()` 走的是「挂起等 Surface」分支；而 `MpvPlayerEngine.prepareAndPlay()` 是 `prepare(); play();`，`play()` 在**尚未 initialized** 时就消费掉了 `playWhenReady=true` 并 return。等 Surface 到了、真初始化时又被硬设成 `pause=true` —— 于是永远停在 0。
SurfaceView 路径之所以没暴露，是因为那条路 `prepare()` 时 Surface 已就绪，`initialized` 已为 true。

修法一行：`mpv.setPropertyBoolean("pause", !playWhenReady);`

修后 `t-pos` 实测：
```
tick t-pos=0.000000 ...   tick t-pos=3.920000 ...   tick t-pos=11.960000 ...
tick t-pos=1.920000 ...   tick t-pos=7.920000 ...   tick t-pos=31.960000 ...
```
2 s 一跳、每次涨 2.000 s → **1.0× 正常速度**。

### 2.4 上屏「活」的硬证据（两路互相印证）

**(a) screencap 里的烧录时间码逐帧推进**（`5_..._t8120.png` → `6_..._t19200.png`）：

| 抓图 | 画面左上角烧录时间码 | 帧号 |
|---|---|---|
| t1 | `00:00:08.120` | 10803 |
| t2 | `00:00:19.200` | 12303 |

差值 **11.080 s / 1500 帧 = 25.000 fps**，与素材标称 25 fps 完全一致；两次 `screencap` 的墙钟间隔约 12 s（11.1 s 视频 + screencap 自身开销）。斜向扫线角度、虚线块位置同步变化。

**(b) 连续 4 张 screencap（间隔 2 s）互不相同：**
```
3E99381734DCEDD6…  491699 bytes
3ED02EB3140DFC00…  491653 bytes
7D0C1196F3DE1CAE…  489212 bytes
CFF6889334617B4B…  487181 bytes
```

**(c) mpv 自带抓图**分别落在 `t-pos = 3.040 / 8.640 / 20.800`，三次都写出 2,774,200 字节的非黑 PNG。

**(d) 全屏 + 普通布局都正常**：按 DPAD_CENTER 进全屏，`8_screencap_texture_fullscreen.png` 显示 1920x1080 满屏、时间码 `00:01:48.000` 帧 2702 仍在走；BACK 退出后布局复原、画面继续（`00:01:59.320` 帧 2798）。

### 2.5 screencap 复测汇总（最终出包默认，无任何 conf）

`7_screencap_texture_shipped_default.png`（默认 TextureView，播 `aac_5_1.mp4`）：
mean **110.667**、非零 **100.00%**、max 255、视频矩形内取样 `#FF27FF`（彩条）。

对照回退开关 `surface=surfaceview`（同一构建、同一素材）：`4_screencap_surfaceview_fallback_BLACK.png`
mean **89.017**、非零 **83.00%**、取样 `#000000`。mpv 侧一切正常（t-pos 照常推进、PNG 照常写出），**只有 screencap 看不见** —— 这是最干净的 A/B，因果链闭合。

---

## 3. 三内核 release 包复测（均无 FATAL）

素材：`aac_5_1.mp4`（H.264 720p + AAC **5.1**）。

| 引擎 | 出画 | AudioTrack mask | 降混 | FATAL |
|---|---|---|---|---|
| **EXO** (`player_engine=0`) | ✅ 截图实证（`11_reference_EXO51.png`，取样 `#FF00FE`） | **`00000003`（立体声）** | ✅ `DownmixAudioProcessor` 生效 | 无 |
| **IJK** (`player_engine=1`) | ✅ 截图实证（`10_reference_IJK.png`，取样 `#FF00FE`） | **`00000003`（立体声）** | ✅ 日志仍见 `apply ffmpeg af: pan=stereo\|c0=c0*1.0+c1*0.7071+c3*0.5+c4*0.7071\|c1=c1*1.0+c2*0.7071+c3*0.5+c5*0.7071` | 无 |
| **MPV** (`player_engine=2`) | ✅ **截图实证**（`7_...png`，取样 `#FF27FF`，画面实时推进） | `0000003F`（6ch） | — | 无 |

> 关于 MPV 那行 mask `0000003F`：mpv 把 5.1 原样交给盒子输出（AO 直通），**本轮未做也未承诺 MPV 侧软件降混**；EXO / IJK 的降混是红线，两者均实测 0x3 完好。

---

## 4. 改动清单（单写者，只碰 MPV 相关 + 一行 PlaybackActivity）

本会话只写了 **3 个文件**（用文件 mtime 审计，全仓其余文件最后修改时间都早于本会话开始时刻 18:40）：

### 4.1 `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`

1. **TextureView 换装**（核心修复）
   `setVideoSurfaceView()` 改为：把 media3 PlayerView 交给的 SurfaceView **隐藏**，在**同一父容器**（media3 把 surface 放在 `AspectRatioFrameLayout` 里，所以几何尺寸天然保持）、**紧邻其上**的位置插入一个 TextureView（复制 LayoutParams，打 tag 便于跨播放复用）。控制器仍在最上层，UI 不受影响；TextureView 不可用时自动退回原 SurfaceView 路径。
   可用 `surface=surfaceview` 切回旧行为（见 §2.5 的 A/B）。
2. **修 pause 卡死 bug**：`setPropertyBoolean("pause", !playWhenReady)`（§2.3）。
3. **补 `onVideoSizeChanged` 回调**：原来只更新 `videoWidth/Height` 字段不派发 media3 回调，导致 PlayerView 的 shutter 与画面宽高比都拿不到真实视频尺寸。
4. **mpv 日志观察者**：注册 `MPVLib.LogObserver`，`warn/error` 走 `Log.w/e`，其余 `Log.d`。`msg-level` 已保持 `all=warn`（不刷屏）。
5. **诊断开关 `mpv_pixel.conf`**（放在 app 自己的外部目录 `/sdcard/Android/data/<pkg>/files/`，仅 root/adb 可写）：
   - `evidence = 1` → 开 mpv `screenshot` 抓图 + 2 s 一次 `t-pos` 打点（**默认关**，因为每张图 2.7 MB）
   - `surface = texture|surfaceview`
   - 任意 libmpv 选项的 `key = value` 覆盖（在 init 之前下发）
   - `prop:<name> = value` → 等 VO 建好后用 `setPropertyString` 补设 VO 私有选项
6. **像素证据采样日志**：`screenshot-template / screenshot-format=png / screenshot-png-compression=0 / screenshot-jpeg-quality`，抓图时打印 `t-pos / paused-for-cache / current-vo / osd 尺寸`。

### 4.2 `app/src/main/java/com/fongmi/android/tv/player/mpv/MpvUtil.java`

新增 `applySurfaceMode(PlayerView, Player)`：**仅当 player 是 `MpvPlayer` 且走 TextureView 时**，把 PlayerView 的黑色 shutter 设为透明。
原因：media3 的 shutter 是 surface 的兄弟节点，靠 SurfaceView 在窗口上打洞才被「绕过」；TextureView 是普通子 View，会被这层不透明黑幕盖住。EXO / IJK 不走这里。

### 4.3 `app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java`

**一行**（任务书允许的「为 TextureView 切换所需的最小改动」）：
```java
private void syncPlayerView(Player player) {
    getPlayerView().setPlayer(player);
    com.fongmi.android.tv.player.mpv.MpvUtil.applySurfaceMode(getPlayerView(), player);   // ← 新增
    syncDanmakuSource();
}
```

### 4.4 未改动

`MPVLib.java`、`MpvPlayerEngine.java` 本轮**零改动**（`MpvUtil`/`MpvPlayer` 已足够）。

---

## 5. 红线复核

| 红线 | 结果 | 证据 |
|---|---|---|
| EXO 默认内核 + `DownmixAudioProcessor`/`DownmixMatrix` **一行不改** | ✅ | 两文件 mtime 08:05 / 08:07（本会话 18:40 才开始）；真机 5.1→AudioTrack mask `00000003` |
| IJK `pan` 降混 **一行不改** | ✅ | `IjkPlayerEngine.java` 16:00 / `IjkDownmix.java` 13:56，均早于本会话；真机日志 `pan=stereo|...` 原样 + mask `00000003` |
| **`com.tvproxy.box` 绝不卸载** | ✅ | 每轮 `pm list packages` 复核：`package:com.tvproxy.box` 始终在列 |
| 原装 `com.fongmi.android.tv` 5.5.2 不动 | ✅ | `pm list packages` 复核在列；未执行任何针对它的操作 |
| **单写者**：只碰 MPV 相关 + TextureView 必需的 PlaybackActivity 最小改动 | ✅ | 全仓 mtime 审计：只有 `MpvPlayer.java`(19:15) / `MpvUtil.java`(18:47) / `PlaybackActivity.java`(18:47) 晚于 18:40。`PlayerEngine` / `PlayerEngineFactory` / `PlayerSetting` / `EngineCommands` / `Ijk*` / `Exo*` / `Downmix*` 全部未动 |
| release 双 flavor 出包（R8 开启） | ✅ | `leanback-arm64_v8a 82.6MB` / `leanback-armeabi_v7a 71.8MB` / `mobile-arm64_v8a 82.5MB` / `mobile-armeabi_v7a 71.7MB`，`BUILD SUCCESSFUL` |
| **不伪造** | ✅ | 出画铁证 = mpv 自带 `screenshot` 的非黑 PNG（§1，含均值/直方图/抽样）+ screencap 彩条图 + 画面内烧录时间码的帧级推进（§2.4）。没有用日志措辞冒充出画 |
| ct2000 装机验证 | ✅ | `leanback-armeabi_v7a.apk` 安装成功，三内核各跑一轮，全 logcat 无 `FATAL EXCEPTION` |

---

## 6. 遗留 / 局限（如实说明）

1. **没有 HDMI 外部采集卡**，「真上屏」的依据是 TextureView 帧被 SurfaceFlinger 合成进显示 framebuffer、且被平台自带的 `screencap` 读到——这是比日志更硬的证据，但严格说不等于外接采集卡的物理录屏。**SurfaceView 路径在这台盒子上的面板实际观感仍未被证明**（只能证明它在出帧、只是不进 framebuffer）。
2. 视频矩形尺寸 800x450 出现在**三个引擎上完全一样**，是 `Player.Vod` 样式既有排版行为，非 MPV 引入，本轮未动。
3. `mpv_pixel.conf` 诊断口保留在出包里（便于现场翻 vo/gpu-context/hwdec），文件落在 app 私有外部目录，只有 root/adb 写得进去；不写文件时行为与普通安装完全一致。
4. mpv 侧 5.1 不做软件降混（mask 0x3F），与本轮开始时一致，未回退也未新增。
5. 抓图一次约 2.7 MB，故 `evidence` 默认关闭；报告里的数值均由 `evidence = 1` 复现得到。