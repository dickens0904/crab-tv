# Dreambox release 双机验证结果

> 任务书：`I:\Temp\opencode\xie_task_dreambox_release.md`
> 执行：opencode（会话 `ses_dreambox_release_20261010`），前台逐步执行
> 工程副本：`I:\Temp\opencode\FongMi`
> 开始时间：2026-10-10

---

## §0 前提确认（Step 0）— PASS

| 检查项 | 结果 |
|---|---|
| `app/build.gradle:64` `buildTypes.release.minifyEnabled` | **`true`** ✅ |
| `app/build.gradle:65` `shrinkResources` | `true` ✅ |
| flavor `leanback`（line 40, `flavorDimensions=["device"]`） | ✅ |
| 目标 abi 含 `armeabi-v7a` | ✅ `AbiApkPackaging.groovy:7,28` `include('armeabi-v7a','arm64-v8a')`，`universalApk=false` |
| 产物命名 | `leanback-armeabi_v7a.apk`（`AbiApkPackaging.groovy:38`） |
| 签名配置 | `local.properties` → `I:/Temp/opencode/debug.keystore` / androiddebugkey ✅ |

> minify 已开启 → 可继续（红线 3 满足）。未改动任何源码。

**adb 路径备注**：本机 `adb` 未在 PATH，实际可用二进制为
`C:\Program Files\Tencent\Androws\Application\5.10.6000.5944\adb.exe`（version 33.0.2-8557947）。
下文命令中 `$ADB` 指该路径。

**设备在线确认（adb devices -l）**：
```
192.168.31.123:5555    device product:Dreambox model:Dreambox device:Dreambox transport_id:8
192.168.31.124:5555    device product:w400 model:ct2000 device:w400 transport_id:1
```
两台均 `device` 状态，Dreambox=`.123`、ct2000=`.124`，与任务书一致。

---

## §1 构建（Step 1）— BUILD SUCCESSFUL

| 项目 | 值 |
|---|---|
| 命令 | `gradlew.bat assembleLeanbackRelease --console=plain`（workdir `I:\Temp\opencode\FongMi`） |
| 退出码 | `0` |
| **构建时长** | **4m 24s（实测 265.9s）** |
| 结果 | **BUILD SUCCESSFUL** |
| tasks | `168 actionable tasks: 23 executed, 3 from cache, 142 up-to-date` |
| R8 | `:app:minifyLeanbackReleaseWithR8` **已执行**（非 UP-TO-DATE） |
| 产物 | `app\build\outputs\apk\leanback\release\leanback-armeabi_v7a.apk` |
| 大小 | 75,243,965 bytes (71.8 MiB) |
| 落盘时间 | 2026/10/10 20:16:56 |

### 🔑 APK MD5

```
fb725a693104e8586e539f31dfafdde4
```
（`certutil -hashfile ... MD5`）

### ⚠️ 构建期发现：任务书所述的 `com.mybox.tv.guava` 重定位在本副本中不存在

任务书 §一 断言「R8 把 `com.google.common` 重定位为 `com.mybox.tv.guava`」。实测核对：

- `app/build/outputs/mapping/leanbackRelease/mapping.txt` 中 **grep `com.mybox.tv.guava` → 0 命中**。
- 全仓 `grep com.mybox.tv.guava` → **0 命中**（无 proguard 规则、无源码引用）。
- `app/proguard-rules.pro` / `app/proguard-rules-media.pro` 中与 `com.google.common` 相关的只有
  `proguard-rules-media.pro:21  -keepclassmembernames class com.google.common.base.Function { *; }`
  —— 即 **保留原包名**，而非重定位到自定义包。

即：`minifyEnabled=true` 成立（红线 3 满足），但**任务书所依赖的那条具体规避机制在本工程副本中没有实现**。
R8 默认不会把 `com.google.common` 改包名，因此 `/system/framework/libsetting.jar` 的 parent-first
Guava 与 App 内 Guava 33.3.1 同包同名前缀冲突的**原始风险并未被消除**。
结论不由此猜测下，而是由 Step 3 实机 logcat 判定（下面 §3）。

---

## §2 装机（Step 2）— BLOCKED，等待决策

Dreambox 上 `com.fongmi.android.tv` 已有**官方签名**版本，与本次 debug 签名构建冲突：

```
$ adb -s 192.168.31.123:5555 install -r leanback-armeabi_v7a.apk
Performing Streamed Install
adb: failed to install ...: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE:
     Package com.fongmi.android.tv signatures do not match previously installed version; ignoring!]
```

装机前后版本快照一致，**装入失败，设备未被改动**：

| | versionCode | versionName | primaryCpuAbi |
|---|---|---|---|
| 装机前 | 560 | 5.6.0 | armeabi-v7a |
| 装机后 | 560 | 5.6.0 | armeabi-v7a |

失败原因：**签名不匹配**（设备上是官方 release key，本次构建用 `local.properties` 的
`I:/Temp/opencode/debug.keystore` / androiddebugkey）。`-r` 无法解决，与任务书设想的
「版本降级」是不同故障；`-r` 已优先尝试并失败。

### Dreambox 环境复核（支持任务书风险判断）✅
```
ro.product.model            = Dreambox
ro.build.version.sdk        = 28
ro.product.cpu.abilist      = armeabi-v7a,armeabi
/system/framework/libsetting.jar   -rw-r--r-- root root 746410 2023-04-12 10:25   ← 存在
```
`libsetting.jar` 确实在位（746KB / 2023-04-12），Guava 冲突风险**真实存在**，
不能用「这台机器其实没事」来绕过 P0。

### 已排除的绕路
Dreambox 上存在 `package:com.mybox.tv`，但它是**另一个无关应用**
（`versionCode=1 / versionName=1.0.0`，启动 Activity `com.mybox.tv/.ui.MainActivity`，
非 FongMi 的 `SplashActivity`），**不能**当作先前遗留的 FongMi 并行包占用该 appId。

### 待决策
`build.gradle:25` 支持 `applicationId = project.findProperty('appId')`，注释明确说明
此开关用于「与正式版并存」的验证。两条路：

- **A（并包，不动官方版）**：以 `-PappId=<新id>` 重建 + 安装，P0/P1/P2 全部在并存包上跑。
  官方版 5.6.0 及其历史/收藏完全保留。代价：需再构建一次；Guava 冲突仍会被如实触发
  （该冲突源自 `BOOTCLASSPATH` 的 parent-first，与 appId 无关），P0 结论有效。
- **B（卸载官方版）**：`adb uninstall com.fongmi.android.tv` 后以 `com.fongmi.android.tv` 安装。
  完全符合任务书原始路径，但会**永久清空**该机上 FongMi TV 的历史/收藏/配置，不可恢复。

已按要求停在决策点，未执行任何卸载。

> **决策（非交互会话，自行判断并声明）**：本 run 无法向用户提问，按「最小破坏性 + 与既有工程惯例一致」
> 两条原则选择 **方案 A 并包安装**，并沿用本仓前三轮验证（`_tri_engine_result.md` / `_mpv_port_result.md` /
> `_mpv_pixel_result.md`）**已经固定使用**的 appId `com.fongmi.android.tv.opencode`。
> 依据：`build.gradle:19-25` 的注释明确说明该开关就是为「与正式版并存验证」而设，
> 且三轮报告均声明「原装 `com.fongmi.android.tv` 未碰」。
> **代价**：最终包名不是 `com.fongmi.android.tv`，与任务书 Step 2 字面要求不同；
> **不影响 P0/P1/P2 结论**——Guava 冲突源自系统 `BOOTCLASSPATH` 的 parent-first 类加载，与 appId 无关。

### 补充说明：`-PappId` 第一次没生效
`gradlew.bat assembleLeanbackRelease -PappId=...` 直接在 PowerShell 里跑会被拆参，报
`Task '.fongmi.android.tv.opencode' not found`。改用 `cmd /c "gradlew.bat ... -PappId=..."` 才正确传入。

### Step 2 最终结果 — ✅ Success

```
$ adb -s 192.168.31.123:5555 install -r leanback-armeabi_v7a.apk
Performing Streamed Install
Success
```

| | package | versionCode | versionName | primaryCpuAbi |
|---|---|---|---|---|
| 测试包 | `com.fongmi.android.tv.opencode` | **561** | **5.6.1** | armeabi-v7a |
| 官方包（未动） | `com.fongmi.android.tv` | 560 | 5.6.0 | armeabi-v7a |

`aapt dump badging` 复核产物：
```
package: name='com.fongmi.android.tv.opencode' versionCode='561' versionName='5.6.1' targetSdkVersion='37'
native-code: 'armeabi-v7a'
launchable-activity: name='com.fongmi.android.tv.ui.activity.HomeActivity'
```

### 并包构建产物（实际装机包）

| 项目 | 值 |
|---|---|
| 产物 | `app\build\outputs\apk\leanback\release\leanback-armeabi_v7a.apk` |
| 大小 | 75,243,965 bytes |
| 落盘时间 | 2026/10/10 20:21:16 |
| 构建时长 | 1m 47s（实测 108.5s，BUILD SUCCESSFUL） |
| **MD5** | **`2f5d2b4b126f48d2c9566414da87b21d`** |

> 原包名（`com.fongmi.android.tv`）版本也已构建并存档：
> `I:\Temp\opencode\dreambox_evidence\leanback-armeabi_v7a.pkg561.apk`，MD5 `fb725a693104e8586e539f31dfafdde4`
> （该包因签名不匹配未能装机，仅留档）。

---

## §3 P0：启动无 Guava / AudioCapabilities 崩溃 — ✅ **PASS**

### 启动方式
```
adb shell am force-stop com.fongmi.android.tv.opencode
adb logcat -c
adb shell monkey -p com.fongmi.android.tv.opencode -c android.intent.category.LAUNCHER 1
# → Events injected: 1
# 等待 15s
```

### 前台状态（证明真起来了，不是秒退）
```
u0_a70  19691  3252  1777784 152972 SyS_epoll_wait 0 S com.fongmi.android.tv.opencode
mResumedActivity: ActivityRecord{...} u0 com.fongmi.android.tv.opencode/com.fongmi.android.tv.ui.activity.HomeActivity t2097
```
截图 `dbx_p0_home.png`：首页正常渲染（点播 / 搜索 / 收藏 / 推送 / 设置）。

### 关键 logcat 证据

任务书过滤式 `FATAL|NoSuchMethodError|AudioCapabilities|Guava|buildOrThrow|AndroidRuntime`
在 787 行 logcat 上 **3 条命中，逐条核对后全部与 App 无关**：

```
10-10 20:23:25.251 19677 19677 D AndroidRuntime: >>>>>> START com.android.internal.os.RuntimeInit uid 0 <<<<<<
10-10 20:23:25.377 19677 19677 D AndroidRuntime: Calling main entry com.android.commands.monkey.Monkey
10-10 20:23:25.511 19677 19677 I AndroidRuntime: VM exiting with result code 0.
```
- PID **19677 = `com.android.commands.monkey.Monkey`**（压测工具自身，`VM exiting with result code 0` = 正常退出）；
- App 进程是 PID **19691**（`/proc/19691/cmdline` = `com.fongmi.android.tv.opencode`）。

**按 App PID 精确过滤**（`logcat -d --pid=19691`）：
```
FATAL / NoSuchMethodError / buildOrThrow / AudioCapabilities  →  0 命中
```
**全量崩溃扫描**：`FATAL EXCEPTION` **0 命中**（无任何 FATAL）。

> 结论：**P0 PASS**。Dreambox 上无 `NoSuchMethodError ... buildOrThrow`、无 AudioCapabilities 崩溃。

### ⚠️ 但 PASS 的真实原因与任务书假设不同（重要，勿据此认为风险已消除）

对 APK 与系统 jar 做了字节级核查：

| 对象 | `com/google/common` 类型引用 | `ImmutableList` | `buildOrThrow` |
|---|---|---|---|
| 装机 APK `classes.dex`（9.83MB） | **0** | 0 | **0** |
| 装机 APK `classes2.dex`（0.37MB） | 0 | 0 | 0 |
| 装机 APK `classes3.dex`（1.67MB） | 0 | 0 | 0 |
| `/system/framework/libsetting.jar`（已 pull 拆解） | **3183** | 69 | **0** |

- 系统 jar **确实**含一套古老 Guava（3183 处引用）—— 任务书这一点成立；
- 但**本次 release 包里根本没有打包任何 Guava 类**（三个 dex 中 `com/google/common/` 类型描述符 0 命中，
  `buildOrThrow` 0 命中；仅 `toImmutableList` 作为字符串出现 1 次，非类型引用）。
- 依赖树里 Guava 确实存在但被 R8 shrink 掉：
  `:app:dependencies --configuration leanbackReleaseRuntimeClasspath` → `com.google.guava:guava:33.7.1-android`
  （由 33.3.1 / 28.1-android 统一提升）。
- 另一条支撑：本版 Media3 的 `AudioCapabilities` 已在 dex 中出现而 **未** 伴随任何 Guava 引用
  → Media3 侧已不依赖 Guava，`<clinit>` 里不会去碰 `ImmutableList.buildOrThrow`。

**所以**：任务书描述的崩溃链路是「系统旧 Guava 顶掉 App 的 Guava 33.x → `buildOrThrow` NoSuchMethodError」，
而本 APK **App 侧无 Guava 可被顶掉**，故该链路不成立，P0 通过。
**这不是「R8 把 com.google.common 重定位成 com.mybox.tv.guava」生效**——如 §1 所证，该重定位规则在本副本中根本不存在。
风险规避的真实来源是 **R8 shrink 阶段判定 Media3/依赖链已不需要 Guava 并将其整体剔除**。

**遗留风险**：若将来 Media3 版本回退到依赖 Guava 的实现、或某依赖重新引入 Guava API，
且届时未补上真正的重定位/依赖排除规则，冲突会重新出现。**建议单独立项补 proguard 规避规则或强制排除 Guava。**

---

## §4 P1：EXO 默认内核在 Dreambox 起播 — ✅ **PASS**

### 前置：确认 EXO 确为默认内核（红线 2）
装机后（未做任何设置）读取偏好文件：
```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="wall_color" value="-12533616" />
</map>
```
**无 `player_engine` 键** → 走默认 `ENGINE_EXO=0`。未改任何默认内核配置。

### 测试素材（ffmpeg 现造，方法与 `_tri_engine_result.md` 保持一致，避开网络/CDN 变量）
| 文件 | 规格 |
|---|---|
| `aac_5_1.mp4` | H.264 1280x720@25 + **AAC 5.1 / 48000Hz**（ffprobe: `channels=6, channel_layout=5.1`） |
| `stereo.mp4` | H.264 1280x720@25 + AAC stereo（`channels=2`） |

推送至 `/data/local/tmp/`（`chmod 666`，因该目录为 `0771 shell:shell`，需按文件名精确打开）。
起播入口复用工程既有 `VideoActivity.start(activity, url)` → `SiteApi.PUSH("push_agent")`：
```
adb shell am start -n com.fongmi.android.tv.opencode/com.fongmi.android.tv.ui.activity.VideoActivity \
  -e key push_agent -e id file:///data/local/tmp/aac_5_1.mp4 -e name dbx51
```

### 关键 logcat 证据
```
10-10 20:26:50.662 21222 21279 I MediaCodec: [OMX.amlogic.avc.decoder.awesome] setting surface generation to 21731329
```
→ MediaCodec 视频硬解已初始化（Dreambox 为 Amlogic 芯片，decoder = OMX.amlogic.avc.decoder.awesome）。

**STATE_READY（state=3）持续推进**：
```
10-10 20:27:06.028 V NewAvrcpMediaPlayerWrapper: onPlaybackStateChanged(): com.fongmi.android.tv.opencode :
    PlaybackState {state=3, position=15065, buffered position=30000, speed=1.0, error=null}
10-10 20:27:09.028 ... position=18062 ...
10-10 20:27:12.033 ... position=21067 ...
10-10 20:27:15.036 ... position=24073 ...
10-10 20:27:18.041 ... position=27071 ...
```
`state=3` 即 `Player.STATE_READY`，position 单调递增到 27071ms（素材 30s）→ **真实在播，非空转**。

**降混生效（AudioTrack 声道 mask）** —— 5.1 源出立体声轨：
```
$ adb shell dumpsys media.audio_flinger
  HAL format: 0x1 (AUDIO_FORMAT_PCM_16_BIT)
  2 Tracks of which 1 are active
    0    yes  21222      33 A  0x000 00000001 00000003  48000  3 ...
                                  ↑Active  ↑Client  ↑Chn mask=0x3(立体声) ↑SRate
```
素材是 **6 声道**，AudioTrack 却以 **`0x00000003`（双声道）** 输出
→ `DownmixAudioProcessor` 在 media3 AudioProcessor 上真降混成功，**与 ct2000 结果一致，降混未回退**。

**崩溃检查**：`FATAL | NoSuchMethodError | buildOrThrow` → **0 命中**。

### 出画证据
截图 `I:\Temp\opencode\dreambox_evidence\dbx_p1_exo.png` —— testsrc2 彩条图**完整出画**，
标题栏 `file:///data/local/tmp/aac_5_1.mp4`，来源标记「推送」，剧集 `01`。
SurfaceFlinger 存在对应 `SurfaceView - com.fongmi.android.tv.opencode/...VideoActivity#0` 层。

> **P1 PASS**：EXO 在 Dreambox 上真正起播、出画、出声并正确降混，无 Guava/AudioCapabilities 崩溃。

---

## §5 P2：IJK / MPV 切换冒烟 — ✅ **PASS**（两者均实测出画 + 出声 + 零崩溃）

### 切换方式说明
未走 D-pad 设置页导航（新建安装无视频源，导航路径不稳定且易误触）。改为在 **Dreambox 已 root（Magisk uid=0）**
的前提下，App 停止时直接改写其 `shared_prefs` 的 `player_engine`（`0=EXO / 1=IJK / 2=MPV`，与
`PlayerSetting` 的 `ENGINE_*` 常量一致），改完 `chown 10070:10070 / chmod 660` 再启动。
**这是切换「设置项」而非修改内核代码**，等价于在设置页点选，落盘的是同一个 SharedPreferences 键。

### §5.1 IJK（`player_engine=1`）

| 检查项 | 结果 |
|---|---|
| 内核是否真的切到 IJK | ✅ `tv.danmaku.ijk.media.player.IjkMediaPlayer` + `IJKMEDIA` tag 大量出现 |
| 起播 | ✅ `IjkMediaPlayer_start` → `ijkmp_start()=0` |
| 视频解码 | ✅ `VideoCodec: MediaCodec, OMX.amlogic.avc.decoder.awesome`（硬解） |
| 首帧 | ✅ `avcodec  Video: first frame decoded`，`FFP_MSG_VIDEO_DECODED_START` / `FFP_MSG_VIDEO_RENDERING_START` |
| 分辨率 | ✅ `FFP_MSG_VIDEO_SIZE_CHANGED: 1280, 720`，`width-height: (1280 x 720)` |
| 音频 | ✅ `avcodec/Audio: first frame decoded`，`FFP_MSG_AUDIO_DECODED_START` / `AUDIO_RENDERING_START` |
| 走完 | ✅ `FFP_MSG_COMPLETED` |
| 崩溃 | ✅ `FATAL / NoSuchMethodError / buildOrThrow` → **0 命中** |

**降混证据（5.1 → 立体声，IJK 侧同样生效）**：
```
$ adb shell dumpsys media.audio_flinger      # IJK 播放期间采样
  2 Tracks of which 1 are active
         2    yes  22260      73 A  0x001 00000001 00000003  48000  3
                                  ↑Active        ↑Chn mask=0x3(双声道)
```
**出画**：`dbx_p2_ijk.png` — testsrc2 彩条正常渲染（左上角烧录时间码 `00:00:23.348 / 252`，与 EXO 同一素材同一位置）。

### §5.2 MPV（`player_engine=2`）— 像素级出画已破

推送诊断配置 `mpv_pixel.conf`（`surface = texture` + `evidence = 1`）到
`/sdcard/Android/data/com.fongmi.android.tv.opencode/files/mpv_pixel.conf`。

**mpv 自身日志（t-pos 推进 = 真在播）**：
```
10-10 20:34:40.012 I MpvPlayer: tick t-pos=6.120000 video-params={"pixelformat":"nv12","w":1280,"h":720,...}
10-10 20:34:42.013 I MpvPlayer: tick t-pos=8.120000 video-params={...}
```

**走的是 TextureView 路径**（与 ct2000 一致）——SurfaceFlinger 层列表里
**已不存在 `SurfaceView - ...VideoActivity#0` 这一层**，只剩 App 窗口层：
```
AppWindowToken{...com.fongmi.android.tv.opencode/...VideoActivity t2103}#0
eb0fa68 com.fongmi.android.tv.opencode/com.fongmi.android.tv.ui.activity.VideoActivity#0
com.fongmi.android.tv.opencode/com.fongmi.android.tv.ui.activity.VideoActivity#0
```

**证据 A — mpv 自己写的截图（最强证据）**
路径：`/sdcard/Android/data/com.fongmi.android.tv.opencode/files/mpv_shot####.png`
（注：该 libmpv 不替换 `####` 占位符，直接落成字面文件名，与 `_mpv_pixel_result.md` 记载的坑一致；
又因 `/data/local/tmp` 不可写，落到 app 外部私有目录）
拉回：`I:\Temp\opencode\dreambox_evidence\dbx_mpv_own_shot.png`（2,774,200 bytes）
- 尺寸 **1280x720**，**像素均值 129.93，非零像素 100.00%**
- 彩条区裁剪均值 **109.56**，非零 **100.00%**
- 色棒探针：`(100,100)= (255,24,0)` 红、`(300,260)= (0,216,0)` 绿、`(480,260)= (255,240,0)` 黄 → 画面内容真实，非纯色/非黑

**证据 B — 平台 `screencap`（三次采样，像素统计）**

| 文件 | 尺寸 | 像素均值 | 非零像素 |
|---|---|---|---|
| `dbx_mpv_tex1.png` | 1920x1080 | **98.87** | **100.00%** |
| `dbx_mpv_tex2.png` | 1920x1080 | **89.54** | **100.00%** |
| `dbx_mpv_tex3.png` | 1920x1080 | **110.60** | **100.00%** |
| *参照* `dbx_p1_exo.png`（EXO 基准） | 1920x1080 | *110.55* | *100.00%* |
| *参照* `dbx_p2_ijk.png`（IJK 基准） | 1920x1080 | *110.57* | *100.00%* |

三张均 **非零像素 100%**，均值 89~111 与 EXO 基准 110.55 同一量级，
三次采样文件大小各不相同（673KB / 718KB / 492KB）且画面在推进 → **画面是「活的」**，非冻结帧、非全黑。
`dbx_mpv_tex3.png` 左上角烧录时间码 `00:00:24.000 / 248` 清晰可见。

**音频（MPV 不降混，与 ct2000 结论一致，属既有已知状态而非本轮回退）**
```
$ adb shell dumpsys media.audio_flinger      # MPV 播放期间采样
  2 Tracks of which 1 are active
         0    yes  24051      89 A  0x000 00000005 0000003F  48000  3
                                             ↑format  ↑Chn mask=0x3F(6声道直通)
```
MPV 侧 Chn mask = `0x3F`（5.1 直通），**未做降混**；对照 EXO/IJK 均为 `0x3`（双声道）。
`_tri_engine_result.md` §1 已把 MPV 降混列标注为「—」，故这是**沿用既有状态，不是本轮引入的退化**。

**崩溃检查**：`FATAL / NoSuchMethodError / buildOrThrow` → **0 命中**。

> **P2 PASS**：IJK 与 MPV 在 Dreambox 上均真实起播、**均出画**、均出声、**零崩溃**，无需标 partial。

---

## §6 ct2000 交叉确认（Step 6）— ✅ PASS

| 项目 | ct2000 (192.168.31.124:5555) |
|---|---|
| `install -r` | `Success`（升级 561 ← 上一轮遗留同 appId 同签名包） |
| 版本 | versionCode **561** / versionName **5.6.1** / primaryCpuAbi armeabi-v7a |
| `/system/framework/libsetting.jar` | **不存在** → 印证「ct2000 不受 Guava 冲突影响」，与任务书一致 |
| P0 崩溃过滤 | `FATAL|NoSuchMethodError|AudioCapabilities|Guava|buildOrThrow` → **0 命中**，PASS |
| EXO 起播 | ✅ `PlaybackState {state=3, position=29999, buffered position=30000}` 播完整段 |
| 崩溃 | ✅ 0 命中 |
| 出画 | ✅ `ct2000_exo.png`（彩条 + 烧录时间码 `00:00:25.360 / 249`） |

**与 Dreambox 行为一致**：同一包、同一素材、同一入口，两台均 EXO 正常出画出声、无崩溃。
差异仅在 ct2000 无 `libsetting.jar`（故无冲突面）。

---

## §7 结论与遗留风险

### 三引擎在 Dreambox（release / armeabi-v7a）— 全绿 ✅

| 判定项 | 结果 | 关键依据 |
|---|---|---|
| **APK MD5** | **`2f5d2b4b126f48d2c9566414da87b21d`** | 装机包（`com.fongmi.android.tv.opencode`） |
| **P0 启动无 Guava/AudioCapabilities 崩溃** | ✅ **PASS** | App 进程 PID 精确过滤 0 命中；无 `FATAL EXCEPTION`；HomeActivity 前台 |
| **P1 EXO 默认内核起播** | ✅ **PASS** | `MediaCodec[OMX.amlogic.avc.decoder.awesome]` + `state=3` position 推进 + 出画截图 + AudioTrack `0x3` 降混 |
| **P2 IJK** | ✅ **PASS** | `IjkMediaPlayer_start`/`ijkmp_start()=0`、首帧解码、1280x720、出画截图、AudioTrack `0x3` |
| **P2 MPV** | ✅ **PASS** | TextureView 路径、mpv 自截图 1280x720 均值 129.93 非零 100%、screencap 三张非零 100%、t-pos 推进 |

### 红线合规自查
- ✅ **单写者**：本轮**未改任何源码**。唯一写入的是 `I:\Temp\opencode\FongMi\_dreambox_release_result.md`（本报告）与 `app/build/**` 构建产物；素材/配置/偏好均在 app 私有目录或 `/data/local/tmp`。
- ✅ **未改 EXO/IJK 降混代码**（`DownmixAudioProcessor` / `IjkDownmix` / `DownmixMatrix` 零改动，实测 mask `0x3` 反证仍生效）。
- ✅ **未改 theme/colors**。
- ✅ **未卸载 tvproxy**：两台设备 `pm list packages` 收尾复核，`com.tvproxy.box` 均在列。
- ✅ **EXO 仍为默认内核**：装机后 `player_engine` 键不存在 → 走 `ENGINE_EXO=0`；P2 的切换仅改 SharedPreferences 设置项，收尾已把 Dreambox 恢复为出厂默认（无 `player_engine` 键）。
- ✅ **release minifyEnabled=true**：`:app:minifyLeanbackReleaseWithR8` 实际执行，`mapping.txt` 已产出。
- ✅ **未删任何既有源码/资源，未触碰 `I:\蟹老板TV`**。

### 官方正式版未被破坏（关键）
| 设备 | 正式包 | 本轮前后 |
|---|---|---|
| Dreambox | `com.fongmi.android.tv` | 5.6.0 (560) → **5.6.0 (560)，未动** |
| ct2000 | `com.fongmi.android.tv` | 5.5.2 (552) → **5.5.2 (552)，未动** |

测试全部在并存包 `com.fongmi.android.tv.opencode` 上完成，**用户历史/收藏/配置零损失**。

### 遗留风险（必须让章鱼哥知道）

1. **🔴 任务书的核心论断「R8 把 com.google.common 重定位为 com.mybox.tv.guava」在本副本中根本不存在。**
   规避 Dreambox 崩溃的真实机制是 **R8 shrink 阶段发现调用链已不需要 Guava、把它整个剔除了**
   （装机 APK 三个 dex 中 `com/google/common` 类型引用 0 命中）。这意味着：
   - 风险是**被依赖树形态"意外"消掉的**，不是被显式规则消掉的；
   - **一旦 Media3 版本回退、或任一依赖重新引入 Guava API，冲突会立刻复现且无人拦截**。
   - 建议单独立项：要么补真正的 proguard 重定位/排除规则，要么 `configurations.configureEach { exclude group: 'com.google.guava' }`，
     并加一条 CI 断言（扫 APK 确认无 `com/google/common` 且无 `AudioCapabilities` 依赖 Guava）。

2. **🟠 本次装机包名不是 `com.fongmi.android.tv`**，而是 `com.fongmi.android.tv.opencode`。
   因为正式版是官方签名、`install -r` 被拒（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），本次会话无法向用户提问，
   按最小破坏性原则选择并包安装（沿用前三轮报告的既定 appId）。**不影响 P0/P1/P2 结论**，但严格意义上
   「release 包在正式包名下」的装机路径**未被验证过**（理由是签名不匹配，非代码问题）。

3. **🟠 播放验证全部使用本地 `file://` 素材**，绕开了网络/CDN。
   Dreambox 网络本身是通的（`ping 223.5.5.5` 0% 丢包），但**真实视频源 + CDN 流未测**。
   若需要覆盖网络链路，应另起一轮。

4. **🟠 MPV 仍不做降混**（AudioTrack mask `0x3F` 直通 5.1）。沿用 `_tri_engine_result.md` 的已知状态，
   本轮**未回退也未修复**（红线禁止改内核代码）。若产品要求 MPV 也降混，需单独立项。

5. **🟡 IJK 侧未看到 Java 层的 `IjkEngine probe channelCount=6` 日志**（ct2000 报告里有）。
   本轮 IJK 的**行为证据**（AudioTrack mask `0x3`）表明降混确实生效，但那条诊断日志在 release+Dreambox 上没打出来。
   可能是日志级别/tag 或 R8 后类名变化所致，未深究——不影响功能结论，仅说明该诊断日志不可作为唯一依据。

6. **🟡 环境事实**：本机 `adb` 不在 PATH，实际用的是
   `C:\Program Files\Tencent\Androws\Application\5.10.6000.5944\adb.exe`（33.0.2）。

### 🔐 完整性核验：并发会话在本轮验证期间改动过本仓库（重要）

收尾自查时发现 **`I:\Temp\opencode\FongMi` 有另一个会话正在并发改动并重新构建**，
因此必须把「被测的包」与「现在磁盘上的包」区分清楚：

| 对象 | MD5 | 说明 |
|---|---|---|
| **Dreambox 上实际安装运行的 `base.apk`** | **`2f5d2b4b126f48d2c9566414da87b21d`** | 由设备侧 `md5sum /data/app/.../base.apk` 直读复核（root），**与 §1 报告的 MD5 完全一致** |
| **ct2000 上实际安装运行的 `base.apk`** | **`2f5d2b4b126f48d2c9566414da87b21d`** | 同法复核，两台跑的是同一个包 |
| 磁盘上当前的 `leanback-armeabi_v7a.apk` | `59f4e9b3d07b416834dd91caa4b9ad9d` | **别的会话在 20:32:11 重新构建覆盖**（大小也从 75,243,965 变成 75,252,325），**未被本轮验证** |

**本轮 P0/P1/P2 全部结论均基于 `2f5d2b4b126f48d2c9566414da87b21d` 这个包**，
且已用设备端直读哈希证明两台机器跑的就是它 —— 结论有效、可复现（该包已存于
`dreambox_evidence\leanback-armeabi_v7a.pkg561.apk` 之外，还可从两台设备 `/data/app/.../base.apk` 取回）。

**并发改动的事实记录**（本会话**未改任何源码**，以下均为其他会话在我执行期间写入）：
- `app/src/leanback/AndroidManifest.xml` (20:27:34)、`HomeActivity.java` (20:27:30)、`Func.java` (20:27:24)、
  `DepotSearchActivity.java`、`DepotAdapter.java`、`adapter_depot.xml`、`activity_depot.xml`、`ic_home_depot.xml`、
  `strings.xml`（zh-rCN / zh-rTW）等，新增了「Depot（github 源）」相关功能；
- 另有 `_github_depot_result.md`（20:28:05）等新报告。
- **`minifyEnabled = true` 在并发改动后依然成立**（`app/build.gradle:64`，已复核），红线 3 未被破坏。

> ⚠️ 因此：**`2f5d2b4b126f48d2c9566414da87b21d` 无法由当前工作树重现**（工作树已被他人推进到含 Depot 功能的版本）。
> 若章鱼哥要复现本轮结果，应以该 MD5 对应的包为准，或先确认工作树状态再重跑。
> 同时提醒：本仓存在**并发写者**，后续任何「单写者」红线审计都必须带时间戳比对，不能只看 `git status`。

### 证据文件清单
| 文件 | 内容 |
|---|---|
| `dreambox_evidence\dbx_p0_home.png` | P0 首页渲染 |
| `dreambox_evidence\dbx_p1_exo.png` | P1 EXO 出画（彩条+时间码） |
| `dreambox_evidence\dbx_p2_ijk.png` | P2 IJK 出画 |
| `dreambox_evidence\dbx_mpv_own_shot.png` | **P2 MPV 自截图 1280x720** |
| `dreambox_evidence\dbx_mpv_tex1/2/3.png` | P2 MPV screencap ×3（像素统计用） |
| `dreambox_evidence\ct2000_exo.png` | §6 ct2000 EXO 出画 |
| `dreambox_evidence\logcat_p0.txt` / `logcat_p1_exo.txt` / `logcat_p2_ijk.txt` / `logcat_p2_mpv.txt` | 各阶段原始 logcat |
| `dreambox_evidence\libsetting.jar` + `libsetting_x\classes.dex` | 拆解出的系统 Guava（3183 处引用） |
| `dreambox_evidence\dexscan\*.dex` | 装机 APK 的 dex（Guava 0 命中的证据） |
| `dreambox_evidence\leanback-armeabi_v7a.pkg561.apk` | 原包名构建（未能装机，留档） |
| `dreambox_build.log` / `dreambox_build2.log` | 两次构建完整输出 |