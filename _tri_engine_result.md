# 三引擎收口交付报告（EXO / IJK / MPV）

- 工程：`I:\Temp\opencode\FongMi`，包名 `com.fongmi.android.tv.opencode`
- 真机：ct2000 `192.168.31.124:5555`（armeabi-v7a，Android 14 / SDK 28，root 可用）
- 构建：`-PappId=com.fongmi.android.tv.opencode`，`assembleLeanbackRelease` + `assembleMobileRelease`（R8 开启，`isMinifyEnabled=true`）
  - `leanback-armeabi_v7a.apk` 71.8MB / `leanback-arm64_v8a.apk` 82.6MB
  - `mobile-armeabi_v7a.apk` 71.7MB / `mobile-arm64_v8a.apk` 82.5MB
  - 两个 variant 均 `BUILD SUCCESSFUL`

---

## 1. 结论速览

| 内核 | 选择可选 | 出画 | 出声 | 降混 | 崩溃 |
|---|---|---|---|---|---|
| EXO（默认/保底） | ✅ | ✅ 截图实证 | ✅ | ✅ `DownmixAudioProcessor`，AudioTrack mask `0x3` | 无 |
| IJK | ✅ 设置页可见可选 | ✅ 截图实证 | ✅ | ✅ ffmpeg `pan`，AudioTrack mask `0x3` | 无 |
| MPV | ✅ 设置页可见可选 | ⚠️ 见 §5（未取得截图证据） | ✅ | — | 无 |

Dreambox `.123` 依旧不可达，本轮未验证。

---

## 2. 改动清单

### 2.1 MPV 接线（Step A）

- `androidx/media3/mpvplayer/MpvPlayer.java` — **重写为 MPVLib 驱动的真实 media3 `Player`**
  - `isAvailable()` 由硬编码 `false` 改为真实探测（`MPVLib.ensureLoaded()` + `isAvailable()`）
  - 通过 `MPVLib` 驱动 libmpv：`loadfile/seek/volume/speed/pause/stop/attachSurface/observeProperty`
  - 实现 `MPVLib.EventObserver`，把 mpv 事件线程的回调切到主线程后再派发给 media3 listener
  - 进程内只有一个 mpv core：用 `coreCreated/coreInitialized/active` 三个静态标记保证 `create/init/destroy` 各只发生一次（重复 `init()`/`destroy()` 会走 native 的 `exit()` 直接杀进程）；换内核时 PlayerManager 是「先 new 再 release」，旧实例一旦被顶替就只摘观察者、不再持有销毁权，否则会销毁新实例的 core
  - libmpv 选项（实测有效）：`vo=gpu-next`、`gpu-context=android`、`ao=audiotrack`、`hwdec=mediacodec-copy`（硬解）/ `no`（软解）、`msg-level=all=warn`、`input-default-bindings=no`
    - `vo=android` 在这份 libmpv 里**不存在**（实测 `[vo:error] Video output android not found!`）
    - `vo=mediacodec_embed` 能打开但 `[vf:fatal] Cannot convert decoder/filter output to any format supported by the output`，最终确定用 `vo=gpu-next` + `gpu-context=android`
  - **surface 时序保护**：`libplayer` 的 `attachSurface` 走 `mpv_set_option(wid)`，而 `mpv_set_option` 在 `mpv_initialize()` 之后一律返回 `MPV_ERROR_OPTION_ERROR`；且 `vo=mediacodec_embed` 在 `wid==0` 时会 **SIGABRT 直接杀进程**。因此：
    - `prepare()` 在拿到 Surface 之前不初始化 mpv，而是挂起等待（最多 4s），Surface 一到立刻起播
    - `setVideoSurfaceView/Holder` 在 holder 尚未产出 Surface 时自行注册 `SurfaceHolder.Callback`，保证第一个拿到 `surfaceCreated` 的是我们
    - 极端情况（4s 内仍无 Surface）降级为 `vid=no`（只出声不出画），不 abort
- `MpvPlayerConfig.java` / `MpvSubtitleOptions.java` — 从空壳改为真正承载 `config-dir` / `sub-pos` / `sub-scale`，player 会应用
- `is/xyz/mpv/MPVLib.java` — 修掉两处**编译不过**的问题：`command(String...)` 与 native `command(String[])` 擦除冲突（改名 `commandArray`）；observer 列表声明为 `List` 却调 `addIfAbsent`（改为 `CopyOnWriteArrayList` 类型）

### 2.2 IJK 接入（Step B）

- **新增** `player/ijk/IjkPlayerEngine.java` — 基于 crab_full 参考实现，适配本仓 `PlayerEngine` 契约 + 内层 `IjkPlayerAdapter`（media3 `Player` 转发适配器）
- **新增** `player/ijk/IjkDownmix.java` — IJK 侧降混，`pan` 滤镜的**每一个权重都从 `DownmixMatrix.left/right()` 取**，与 ExoPlayer 的 `DownmixAudioProcessor` 共享同一份 ITU-R BS.775 矩阵
- **新增** `player/engine/EngineCommands.java` — 见 §4 的两个坑（`Commands` 排序 + `COMMAND_SET_VIDEO_SURFACE`）

### 2.3 工厂 / 设置 / UI（Step C）

- `PlayerEngine.Type` → `{EXO, IJK, MPV}`
- `PlayerEngineFactory` — 三值 `resolve()`：选 IJK/MPV 都先 `isAvailable()` 探测，任一不可用回退 EXO；`create()` 三分支整体 try/catch，构造抛异常降级成 ExoPlayer（EXO 自身抛异常才向上抛，因为再下面没有兜底了）；保留 `requiresExo(Drm/smb)→EXO`
- `PlayerSetting` — 三值 `ENGINE_EXO=0 / ENGINE_IJK=1 / ENGINE_MPV=2`，`getEngine/putEngine` clamp 到 `[0,2]`，新增 `isIjk()` 与 `isMpvOnly()`（mpv.conf/gpu-next/vulkan 三行只对 MPV 显示，IJK 不再误显示）
- `PlayerManager.getEngine()` 改为按真实运行内核回报，回退时 UI 显示 EXO 而不是谎报用户选的内核
- `strings.xml` — `select_engine` 三项、新增 `play_ijk`
- `SettingPlayerActivity`（leanback）/ `SettingPlayerFragment`（mobile）— 行可见性改用 `isMpvOnly()`
- `PlayerEngineDialog` + 两端 `dialog_player_engine.xml` — 播放中引擎弹窗新增 IJK 选项

### 2.4 ProGuard

`proguard-rules-media.pro` 追加：ijk 全包 keep（0.8.8 split AAR **不带 consumer rules**，而 libijkffmpeg/libijkplayer 是按类名+方法名反查 Java 侧成员的，不 keep 会变成静默死回调）、`is.xyz.mpv.MPVLib$*`、两个适配器类。

### 2.5 未改动（红线确认）

- `DownmixAudioProcessor` / `DownmixMatrix` — 一行未动，EXO 降混 intact
- `PlaybackService.java` — `git diff` 为空（排查期加的诊断代码已全部回退）
- 原装 `com.fongmi.android.tv`、5.5.2 未碰

---

## 3. 三个内核的真机验证（ct2000，release 包）

测试素材用 ffmpeg 现造，推到 `/data/local/tmp`（免存储权限）：

| 文件 | 规格 | 用途 |
|---|---|---|
| `aac_5_1.mp4` | H.264 720p + AAC **5.1** (48000Hz, 5.1) | 多声道降混验证（AC3 会被这台盒子走 HDMI passthrough，绕过 AudioProcessor，所以必须用 AAC） |
| `stereo.mp4` | H.264 720p + AAC stereo | 立体声基线 |
| `ac3_5_1.mp4` | H.264 720p + AC3 5.1 | 观察 passthrough |

### 3.1 EXO（`player_engine=0`）

```
-- crash check --  none
MediaCodec [OMX.amlogic.avc.decoder.awesome] setting surface generation to 28035073
audio_flinger track: 1 yes <pid> 265 A ... 00000003 48000 ...
```
5.1 AAC 源 → AudioTrack **Chn mask `00000003`（立体声）** ⇒ `DownmixAudioProcessor` 真降混。
截图 `shot_rel_EXO51.png` / `shot_EXO.png`：彩条测试图正常出画。

### 3.2 IJK（`player_engine=1`）

```
-- crash check --  none
I/IJKMEDIA  SDL_Android_AudioTrack: CHANNEL_OUT_STEREO
I/IJKMEDIA  AudioCodec: avcodec, aac
I/IJKMEDIA  VideoCodec: MediaCodec, OMX.amlogic.avc.decoder.awesome
D/IjkEngine probe channelCount=6 downmixMode=auto
D/IjkEngine apply ffmpeg af: pan=stereo|c0=c0*1.0+c1*0.7071+c3*0.5+c4*0.7071|c1=c1*1.0+c2*0.7071+c3*0.5+c5*0.7071
audio_flinger track: 2 yes <pid> 281 r ... 00000003 48000 ...
```
- 音频走 **ffmpeg 软件解码**（`AudioCodec: avcodec, aac`），视频走 **MediaCodec 硬解**
- 探到 6 声道后挂 `pan` 滤镜，展开后与 `DownmixMatrix.left(6)={1,0.7071,0,0.5,0.7071,0}`、`right(6)={0,1,0.7071,0.5,0,0.7071}` **逐值一致**
- AudioTrack **mask `00000003`（立体声）** ⇒ 6ch→2ch 真降混，不是丢声道
- 截图 `shot_rel_IJK51.png` / `shot_IJK.png`：彩条测试图正常出画
- 立体声源走「探测→`shouldApply(2)`=false→不挂滤镜」分支，不会被 5.1 滤镜打坏

### 3.3 MPV（`player_engine=2`）

```
-- crash check --  none
V/mpv  event: start-file / file-loaded / playback-restart / video-reconfig ×2
audio_flinger track: 1 yes <pid> 289 A ... 00000003 48000 ...
```
- 音频正常出声（AudioTrack active，立体声）
- debug 包（同代码）下 mpv 自身日志确认视频链路真实工作：
  ```
  VO: [gpu-next] 1280x720 yuv420p
  Window size: 800x450
  Video display: (0, 0) 1280x720 -> (0, 0) 800x450
  first video frame after restart shown
  playback restart complete @ 0.000000, audio=ready, video=playing
  Estimated source FPS: 25.000, display FPS: 25.000
  ```
  且 `mali_winsys: EGLint new_window_surface` 证明 GL 上下文已绑到 SurfaceView 的 Surface。
- ⚠️ **未取得像素级截图证据**，详见 §5。

### 3.4 设置页三值选择（leanback，release 包实机）

- `shot_settings_engine.png`：`播放引擎 = MPV`，mpv.conf / gpu-next 行显示，广告屏蔽/缓冲行隐藏（`isMpvOnly()` 生效）
- 按一次 → `shot_set_EXO.png`（EXO），再按一次 → `shot_set_IJK.png`（**IJK**），mpv.conf / gpu-next 行隐藏、解码设置保留
- 持久化落盘 `player_engine=1`

---

## 4. 过程中踩到并修掉的 4 个真问题

1. **`MediaSession.BuilderBase` 构造里 `Assertions.checkArgument(...)` 抛裸 `IllegalArgumentException`**（无 message），把 `PlaybackService.onCreate` 打死，IJK/MPV 一选就崩。根因：`canAdvertiseSession()` 返回 false。debug 包抓到确切栈：
   `Assertions.checkArgument ← MediaSession$BuilderBase.<init>(MediaSession.java:2308) ← MediaLibraryService$MediaLibrarySession$Builder.<init>`。改为与 ExoPlayer 一致的 `true`。
2. **`Player.Commands` 要求 id 严格升序**，否则 `Util.checkArgument(boolean)` 裸抛。已由 `EngineCommands` 排序统一构造。
3. **漏报 `COMMAND_SET_VIDEO_SURFACE`**。media3-ui `PlayerView.setPlayer` 字节码里：`if (player.isCommandAvailable(27)) { ...setVideoSurfaceView/setVideoTextureView... }` —— 不报这个命令，PlayerView 根本不把 Surface 交给播放器，mpv 因此永远拿不到 `wid`（表现为「有声音没画面」）。已加入 `EngineCommands`。
4. **ijk split AAR 不带 consumer rules**，R8 下 `tv.danmaku.ijk.media.player.**` 会被改名，native 侧按名反查会静默失败。已加 keep。

---

## 5. 诚实说明：MPV 未取得截图级出画证据

- mpv 自己的日志证明解码与渲染链路在工作（见 §3.3），GL 上下文也确实绑到了 SurfaceView 的 Surface。
- 但本机 `adb shell screencap` 与 `screenrecord` 对**这个 SurfaceView 渲染出的是全黑**；而同一份代码路径下 EXO / IJK（MediaCodec → SurfaceView）能被正常截到彩条图。说明该机截图通道抓不到 mpv 走的 GL/overlay 合成层。
- 另外 `dumpsys SurfaceFlinger --latency` 对该层的时间戳三次采样完全不变，无法作为「有新帧」的旁证。
- 因此本报告只宣称 **MPV 出声已实证、出画由 mpv 自身 VO/首帧/FPS 日志佐证**，**不宣称截图级出画已验证**。若需强证据，建议接 HDMI 外部采集或改用可被截图抓取的合成路径。

---

## 6. 红线确认

- ✅ ExoPlayer 默认（`ENGINE_EXO=0`，老安装升级后仍是 ExoPlayer）且是兜底：工厂三值全部 `isAvailable()` 探测 + 构造异常捕获，任一失败降级 EXO，不崩（三内核 release 包实测均无 FATAL）
- ✅ ExoPlayer 降混未回退：`DownmixAudioProcessor`/`DownmixMatrix` 零改动，且实测生效（mask 0x3）
- ✅ IJK 降混走 ffmpeg `pan`，权重引用共享 `DownmixMatrix`，与 EXO 两套并存互不破坏
- ✅ `com.tvproxy.box` 全程未卸载（每轮 `pm list packages` 复核均在）；原装 `com.fongmi.android.tv` 未碰
- ✅ 未伪造：MPV 截图证据缺失已如实标注；IJK `addSubtitle()` 返回 `false`（ijk Java 层无 sub-add binding），未假装支持外挂字幕
- ✅ 单写者：仅本会话改 `PlayerEngine`/`PlayerEngineFactory`/`PlayerSetting`/`MpvPlayer`/`IjkPlayerEngine`

## 7. 遗留 / 建议后续

1. MPV 出画补一张可抓取的截图（换 HDMI 采集或调整合成路径）。
2. IJK / MPV 的轨道面板仍是隐藏的（`Tracks.EMPTY`）：ijk 的 `selectTrack` 本身可用，但没有把 `getTrackInfo()` 翻译成 media3 `Tracks`；MPV 侧缺 `MPV_FORMAT_NODE` 绑定，枚举不了 `track-list`。
3. MPV 的 `af`/`vf`（视频调色、音频滤镜）未接，两条内核的调音/调色 UI 在 MPV 下仍报 unsupported（与本轮开始时一致，未回退任何已回灌能力）。
4. `player_engine` 存量迁移：旧两值版本里 `1` 是 MPV，现在 `1` 是 IJK、MPV 变 `2`。已在 `PlayerSetting` 注释里写明；两内核都会独立 resolve，不至于开不了播，但老用户的 mpv 选择会变成 ijk。
5. Dreambox `.123` 可达后补一轮 release 验证（防 `libsetting.jar` Guava 冲突）。