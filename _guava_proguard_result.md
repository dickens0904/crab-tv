# Guava / `com.google.common` 冲突根治 — 执行报告

> 任务书：`I:\Temp\opencode\xie_task_guava_proguard.md`
> 工程：`I:\Temp\opencode\FongMi`
> 执行会话：`ses_guava_proguard_20261010`
> 创建：2026-10-10

---

## Step 0 — 确认 Guava 入口（只读） ✅

### 0.1 依赖树来源

`gradlew :app:dependencies --configuration leanbackReleaseRuntimeClasspath`

三条引入路径，**唯一被解析到实际版本的是 `catvod`**：

| 引入方 | 声明版本 | 解析结果 |
|---|---|---|
| `project ':catvod'` → `api libs.guava` | `33.7.1-android` | **33.7.1-android（胜出）** |
| `androidx.media3:media3-common:1.8.0` | `33.3.1-android` | → 33.7.1-android |
| `com.github.bumptech.glide:avif-integration:5.0.9` | `28.1-android` | → 33.7.1-android |

- `gradle/libs.versions.toml:33` → `guava = "33.7.1-android"`
- `catvod/build.gradle` → `api libs.guava`（**api**，故对 `:app` 可见）
- 传递附属：`com.google.guava:failureaccess:1.0.3`、
  `listenablefuture:9999.0-empty-...`（空壳）、`org.jspecify`、`j2objc-annotations`

### 0.2 ⚠️ 关键发现：任务书前提「运行时不需要 App 侧 Guava」**不成立**

任务书 §1 与 Step 1 的推荐前提是「R8 shrink 把 Guava 整个剔除了 / 运行时不需要 App 侧 Guava」。
**实测为假。** 全仓 grep `com.google.common` → **33 个 Java 文件直接 import 并在运行时调用**：

| 依赖的 API | 使用处（部分） |
|---|---|
| `net.HttpHeaders` | `UrlUtil`、`Channel`、`Live`、`Strm`、`ParseJob`、`PlaySpec`、`PlayerManager`、`CustomWebView`、catvod 的 3 个 OkHttp 拦截器、`SearchActivity`、`SearchFragment`、`quickjs/Connect` |
| `collect.ImmutableList` | `VodBrowse`、`LiveBrowse`、`BrowseTree`、`PlaybackService` |
| `collect.Lists` | `HomeActivity`、`TypeFragment`、`CollectFragment`（均 leanback flavor） |
| `util.concurrent.{FluentFuture,ListenableFuture,MoreExecutors,Futures,SettableFuture,FutureCallback,ListeningExecutorService}` | `Task`、`ViewModelTaskRunner`、`ViewModelSearchRunner`、`PlaybackActivity`、`PlaybackService`、`ArtworkBitmapLoader` |
| `io.ByteStreams` | `ArtworkBitmapLoader` |
| `cache.{Cache,CacheBuilder}` | `ImgUtil` |
| `base.Predicates` / `collect.Iterables` | `Filter` |

→ **Guava 是 App 的一等运行时依赖，不是可剔除的死重。**

### 0.3 ✅ 但「装机 APK 扫不到 `com/google/common`」这一事实为真 —— 原因与任务书所述不同

对现有 release 产物 `app\build\outputs\apk\leanback\release\leanback-armeabi_v7a.apk`
用 dex string pool 解析（`I:\Temp\opencode\gv_scan_dex.py`，非字节盲扫）：

```
dex files: 3  ->  classes.dex, classes2.dex, classes3.dex
total dex strings parsed: 73586
UNOBFUSCATED "com/google/common" type descriptors : 0
dotted "com.google.common" strings               : 2
    com.google.common.base.Strings          (classes.dex)
    com.google.common.util.concurrent.      (classes.dex)
```

**真正的机制是「整体改名（obfuscation）」而不是「整体剔除（shrink）」。**
`app\build\outputs\mapping\leanbackRelease\mapping.txt` 证据（222 条 `com.google.common.*` 顶层类映射）：

| 类别 | 数量 |
|---|---|
| 被完全 REMOVED | 47 |
| **存活且被改名为短名（实进 dex）** | **175** |
| 保留原始包名（= 冲突风险） | **0** |

例：
```
com.google.common.base.CharMatcher$Is -> ao:
com.google.common.base.Function        -> mr0:
com.google.common.cache.CacheBuilder   -> cl:
```

→ Guava **确实被打进了 APK**，只是 175 个类全部被 R8 改名成 `a/b/c…` 短名。
这也解释了为何字节级扫 `com/google/common` 会「0 命中」—— **名字被换掉了，类还在**。

### 0.4 对冲突风险的重新定性（与任务书 §1 的判断相反）

- 任务书/上轮报告的因果：**shrink 剔除了 Guava** → 所以没有东西可被系统顶掉。
- 实测的因果：**Guava 全部存活但被 R8 改名** → dex 里**不存在任何 `com/google/common` 类型描述符**
  → 系统 `libsetting.jar` 里的 `com.google.common.*` 因**包名对不上而永远不会被命中**
  → parent-first 类加载无从触发 → `NoSuchMethodError buildOrThrow` 链路不成立。

**结论：当前 P0 通过的真实护栏是「R8 对整个 Guava 包的整体重命名」，而不是「剔除」。**
它在功能上等价于任务书方案 B 想做的「重定位到独立包名」，而且更强（名字比
`com.fongmi.android.tv.guava` 更不可能与任何东西碰撞），但它是**隐式且无护栏**的：
一旦有人给 `com.google.common.**` 加一条 `-keep`/`-keepnames`/`-keeppackagenames`，
或关掉混淆，包名会立刻回到 dex，冲突即刻复现，且**没有任何东西会拦住**。

---

## Step 1 — 方案选型：**A 与 B 均不可行 → 采纳「A 的意图 + B 的机制 + CI 硬拦」** ✅

### 1.1 方案 A（显式 exclude）——**实测证伪，不可行**

任务书推荐 A，理由是「运行时不需要 App 侧 Guava」。**按任务书要求做了真实试验**
（临时加 `exclude group:'com.google.guava', module:'guava'` 后跑
`gradlew :app:compileLeanbackReleaseJavaWithJavac`），**构建直接失败**：

```
> Task :app:compileLeanbackReleaseJavaWithJavac FAILED
> java.lang.NoClassDefFoundError: com/google/common/base/Equivalence
    at org.gradle.api.internal.tasks.compile.AnnotationProcessingCompileTask.call(...)
```

失败原因是 **Glide 的注解处理器**（`libs.glide.compiler`，注解 `GlideUrl`）在
annotation processing 阶段就 `Class.forName` 了 Guava 的 `Equivalence`。
即使绕过注解处理器，**33 个业务源文件**也直接 `import com.google.common.*`，
javac 同样编不过。

> **试验已完整回滚**，`app/build.gradle` 无任何 `com.google.guava` 残留（已 grep 校验）。

**A 在本工程物理上不可能。** 这也再次证实任务书 §1「R8 已把 Guava 剔除干净」的前提是错的。

### 1.2 方案 B（重定位到 `com.fongmi.android.tv.guava`）——**判定为不需要，且强行做会更差**

不做的理由不是「做不了」，而是**做了没有安全增益**：

| | 现状（R8 混淆重命名） | 方案 B（`-repackageclasses` 搬到固定包名） |
|---|---|---|
| dex 内 Guava 名字 | `a/a`、`c/l` … 短名，**几乎不可能与任何东西碰撞** | `com/fongmi/android/tv/guava/**` 固定可预测名 |
| 与系统旧 Guava 冲突 | 不可能（包名对不上） | 不可能（包名对不上） |
| 反射/字符串脆弱性 | 最低 | **更高**：固定包名一旦被别处硬编码引用，反而更易被撞 |
| 实现成本 | 0 | 需处理 `-repackageclasses` 的字符串/`-adaptclassstrings` 连带面，风险外溢 |

**且标准 R8 的 `-repackageclasses` 是全局重打包**（把所有类塞进一个包），无法只限定
`com.google.common.*` —— 强行套用会把 `androidx.media3` / `tv.danmaku.ijk` 等
JNI 依赖精确类名的模块一起卷进去，**直接违反红线 1**。

> 补充核查：175 个存活 Guava 类分布在 8 个子包，**简单类名跨包无冲突**——这只说明
> 「理论上能重定位」，不改变上面「无安全增益 + 有回归风险」的结论。

**结论：Guava 保留在 classpath 上（必须），真正要做的是把这个「靠混淆侥幸不出事」
的状态变成「显式规则 + CI 强制保证」。这正是任务书 §二 目标 2 的本意。**

### 1.3 落地改动（严格限于红线 1 允许的文件）

1. **`app/proguard-rules-media.pro`** — 把唯一一处 `com.google.common` 规则
   （`-keepclassmembernames class com.google.common.base.Function`）就地加上**长注释**，
   写明：为何不能 `-keep`、为何不能 `-keeppackagenames`/`-keepnames`/`-dontobfuscate`、
   为何不能 exclude，以及一旦回归会有什么后果。**规则本身一个字节都没改**（它本就是
   `-keepclassmembernames`，保留成员名但不保留类名/包名，正是安全的那一档）。
2. **`buildSrc/.../AssertNoGuavaTask.groovy`**（新增）— 构建期断言任务，解析**产物 APK 的
   dex string pool**，发现任何 `com/google/common` 类型描述符即 **BUILD FAILURE**。
3. **`buildSrc/.../AbiApkPackaging.groovy`** — 挂载到 release 流水线：对
   `leanbackRelease` / `mobileRelease` 的 **两个 ABI 分包**各注册一个断言任务，
   并 `assemble<Variant>Release → dependsOn(assert*)`，`assert* → dependsOn(finalize*Apks)`
   （读最终签名产物，同时满足 Gradle 的隐式依赖校验）。

### 1.4 断言为什么判 `com/google/common` 而不是 `com.google.common`

- `Lcom/google/common/**;` 是 **dex 类型描述符**，即真正会触发 parent-first 类加载的东西 → 必须为 0。
- 扫描命中**字符串池**而非盲扫字节，避免把「恰好出现的字节序列」误判成类型引用。
- 实测 dex 中存在 2 个**点号形式**的普通字符串
  （`com.google.common.base.Strings`、`com.google.common.util.concurrent.`），
  属 protobuf/类型元数据里的**非类型引用**，不构成类加载冲突，故**不计入失败**——
  这一点在断言里是明确区分的，不是漏网。

---

## Step 2/3 — CI 断言上线 + 重建 release ✅

`gradlew.bat assembleLeanbackRelease` → **BUILD SUCCESSFUL in 6m 29s**，两个 ABI 分包各跑一次断言：

```
> Task :app:assertNoGuavaLeanbackReleaseArm64_v8a
[assertNoGuava] scanning leanback-arm64_v8a.apk
[assertNoGuava]   dex files      : 3
[assertNoGuava]   dex strings    : 73586
[assertNoGuava]   com/google/common descriptors : 0
[assertNoGuava] PASS - no com/google/common type descriptor in leanback-arm64_v8a.apk
> Task :app:assertNoGuavaLeanbackReleaseArmeabi_v7a
[assertNoGuava]   ... PASS - no com/google/common type descriptor in leanback-armeabi_v7a.apk
BUILD SUCCESSFUL in 6m 29s
```

`minifyEnabled = true` 保持不变（红线 2）。

---

## Step 2 反向验证 —— **故意引入 Guava，断言确实拦得住** ✅

任务书要求「做一次故意引入 Guava 的反向验证」。做法：临时在
`app/proguard-rules-media.pro` 加一行**模拟未来误加**的规则

```proguard
-keep class com.google.common.** { *; }
```

重跑 `gradlew assembleLeanbackRelease`，结果：

```
[assertNoGuava]   com/google/common descriptors : 2498
[assertNoGuava]     classes.dex: Lcom/google/common/annotations/Beta;
[assertNoGuava]     classes.dex: Lcom/google/common/base/Absent;
[assertNoGuava]     classes.dex: Lcom/google/common/base/CaseFormat$1;
...
> Guava collision guard tripped: the APK still ships 2498 unobfuscated
  com/google/common type descriptor(s).
  Dreambox loads /system/framework/libsetting.jar (ancient Guava) from BOOTCLASSPATH
  parent-first, so these names would resolve to the system copy and crash with
  NoSuchMethodError at runtime.

BUILD FAILED in 2m 39s
```

| 反向验证项 | 结果 |
|---|---|
| 加 1 行 `-keep` 后 dex 内原始包名类型描述符 | **0 → 2498** |
| 构建结果 | **BUILD FAILED**（exit=1） |
| 报错是否可读、是否指出真因 | 是（点名 `libsetting.jar` + parent-first + 为何不能 exclude） |
| 探针是否已回滚 | 是，`git diff` 确认无 `TEMP-REGRESSION-PROBE` 残留 |

**这正是任务书担心的「静默复现」场景，现在会被构建当场拦下。**
（注意：**仅靠 shrink 是拦不住的**——被 `-keep` 的类根本不会被 shrink 掉，
这也是必须扫 dex 而不能只扫 mapping/usage 的原因。）

---

## Step 3/4 — 干净重建 + 独立 dex 扫描互证 ✅

干净重建：`BUILD SUCCESSFUL in 6m 29s`，两分包断言均 PASS（各 `73586` 条 dex 字符串 / `0` 命中）。

**独立扫描器**（`I:\Temp\opencode\gv_scan_dex.py`，Python 解析 dex string pool，
与 Gradle 里的 Groovy 断言**两套独立实现**）结果：

| APK | dex | dex 字符串 | `com/google/common` 类型描述符 | 点号字符串 |
|---|---|---|---|---|
| `leanback-armeabi_v7a.apk` | 3 | 73586 | **0** | 2 |
| `leanback-arm64_v8a.apk` | 3 | 73586 | **0** | 2 |

→ 两套实现结论一致，**互证通过**。

### 4.1 反向排除「假阴性」：Guava 确实还在包里，只是被改名

为防止把「Guava 根本没进来」误当成「扫描干净」，用 `mapping.txt` 交叉验证：

```
kept under ORIGINAL name : 0      <-- 必须为 0
removed by shrink        : 47
live, renamed to short   : 175    <-- Guava 真的在 APK 里
```

关键存活类（业务代码真实调用）：
```
com.google.common.collect.ImmutableList        -> w11:
com.google.common.cache.CacheBuilder           -> cl:
com.google.common.io.ByteStreams               -> kk:
com.google.common.util.concurrent.MoreExecutors -> zr1:
```

存活子包共 8 个：`base / cache / collect / io / math / primitives /
util.concurrent / util.concurrent.internal`。

**结论：Guava 是「在包里、被改名」，不是「被剔除」。**
这一条同时纠正了任务书 §1 与上轮 `_dreambox_release_result.md` §3 的因果描述。

---

## Step 5 — Dreambox 真机验证（`libsetting.jar` 在位） ✅ P0 PASS

### 5.1 环境

```
Dreambox, Android 9.0.0-R-20210518.1058
/system/framework/libsetting.jar  746410 bytes  2023-04-12 10:25   ← 冲突面确实在位
```

**红线 3 合规**：官方 `com.fongmi.android.tv` **全程未动**。
本包以**并存 appId `com.fongmi.android.tv.guava`** 装机（`-PappId=`，任务书推荐方案），
装机后设备包列表：

```
package:com.fongmi.android.tv          ← 官方，原封未动
package:com.fongmi.android.tv.opencode ← 上轮留存
package:com.fongmi.android.tv.depot
package:com.fongmi.android.tv.guava    ← 本次验证包（22:28:14 安装）
```

### 5.2 P0：启动无 Guava / AudioCapabilities 崩溃 — **PASS**

启动 `HomeActivity` → PID **16842**，前台确认：
```
mResumedActivity: ... com.fongmi.android.tv.guava/...HomeActivity
Displayed com.fongmi.android.tv.guava/...HomeActivity: +879ms
```

按任务书过滤式 + 按 **PID 精确过滤**（app PID 16842，共 151 行）：

| 检查项 | 结果 |
|---|---|
| `FATAL EXCEPTION` / `AndroidRuntime` | **0 命中** |
| `NoSuchMethodError` / `buildOrThrow` / `NoClassDefFoundError: com/google` | **0 命中** |
| `AudioCapabilities` | **0 命中** |
| `libsetting` / `BOOTCLASSPATH` | **0 命中** |

> 「guava」字样共 8 处命中，**逐条核对全部无害**：7 处是 Android 的
> `I/chatty` 节流日志在打印包名 `com.fongmi.android.tv.guava`（我们自己的包名），
> 1 处是 `ProfileInstaller`。**没有一处是 Guava 类加载。**
> 另 2 条 `ValueAnimator$DurationScaleChangeListener` 的
> `ClassNotFoundException` 是 **Lottie 在 Android 9 上探测隐藏 API** 的既有良性日志，
> 与 Guava 无关，且被 app 自己 `Rejecting re-init` 吞掉。

### 5.3 EXO 起播冒烟 — **PASS**（且直接命中任务书的崩溃点）

`file:///sdcard/gv_smoke.mp4` 经 `VIEW` intent 拉起 `VideoActivity` 并真实解码：

```
I/ExoPlayerImpl(16842): Init 48fe0f6 [AndroidXMedia3/1.8.0] [Dreambox, Dreambox, Droidlogic, 28]
I/MediaCodec(16842): [OMX.amlogic.avc.decoder.awesome] setting surface generation to 17246209
D/TV-rt0  (16842): Created dir:/data/user/0/com.fongmi.android.tv.guava/cache/exo
mResumedActivity: ... VideoActivity
```

**关键**——`AudioCapabilities` 在起播时**被真实执行**了，输出仅为格式告警，**无任何 Guava 报错**：

```
W/AudioCapabilities(16842): Unsupported mime audio/dtshd
W/AudioCapabilities(16842): Unsupported mime audio/ffmpeg
```

这正是任务书描述的崩溃点（`AudioCapabilities.<clinit>` → `ImmutableList.buildOrThrow`）。
它能正常跑完并返回，**就是「Guava 依赖被完整满足且未被系统旧版顶掉」的最强证据** ——
比单纯「启动没崩」更有说服力。

全量 logcat（不限 PID）复查 `FATAL EXCEPTION|AndroidRuntime.*com.fongmi|NoSuchMethodError|buildOrThrow` → **0 命中**。
进程在播放后仍存活（PID 16842 未变），无延迟崩溃。

### 5.4 APK md5

| 包 | MD5 |
|---|---|
| `leanback-armeabi_v7a.apk`（官方 appId 构建） | `1C0DAB7F30BDB4BD1E655BFDCDD632A1` |
| **`leanback-armeabi_v7a.apk`（并存 `…tv.guava`，装机验证件）** | **`206B9DEC2B0BB3D1E1DC60539A604450`** |

> 两者不同仅因 `applicationId` 不同（签名/内容随之变化），dex 内容一致
> （两套扫描均为 `73586` 字符串 / `0` 命中）。

---

## Step 6 — 红线确认

| # | 红线 | 状态 | 证据 |
|---|---|---|---|
| 1 | 单写者，只碰 build.gradle / proguard / CI 断言 | ✅ | 实际改动仅 3 处：`app/proguard-rules-media.pro`（**纯注释**）、`buildSrc/.../AssertNoGuavaTask.groovy`（新增 CI 断言）、`buildSrc/.../AbiApkPackaging.groovy`（挂载断言）。**`app/build.gradle` 最终零改动**（git diff 仅剩 2026-10-09 遗留的 appId + media3 依赖）。**未碰** EXO/IJK/MPV 引擎与降混、theme/colors、VodConfig/LiveConfig、HomeActivity/Depot UI、tvproxy |
| 2 | release 必须 `minifyEnabled=true` | ✅ | 原样保留 `minifyEnabled = true` / `shrinkResources = true`，未改动 |
| 3 | 严禁卸载/改动官方 `com.fongmi.android.tv` | ✅ | 全程只装 `com.fongmi.android.tv.guava`；装机后官方包仍在列表中未被覆盖 |
| 4 | 不删既有源码/资源；不碰 `I:\蟹老板TV` | ✅ | 无删除操作；未访问该目录 |
| 5 | 本会话为唯一活跃会话（串行） | ✅ | 全程单会话串行执行，未与其他会话并发改本仓 |

---

## 总结（一句话）

**任务书的两个前提都是错的**：Guava 既没有「被 R8 剔除」（175 个类其实在包里，只是被改名），
也**不能**被 exclude（Glide 注解处理器 + 33 个源文件直接依赖，exclude 会直接编不过）。
真正的风险不是「Guava 存在」，而是「**Guava 可能哪天以原始包名进 dex，而没人拦**」。
因此本轮**既没做 A 也没做 B**，而是把这个隐式护栏变成了**显式规则 + 构建期硬断言**：
加一行 `-keep class com.google.common.** { *; }` 就会让 dex 里的
`com/google/common` 从 **0 → 2498**，并让 `gradlew assembleLeanbackRelease` **当场 BUILD FAILED**。
Dreambox 真机 P0 **PASS**，EXO 起播时 `AudioCapabilities` 真实执行且无 Guava 报错。

**给章鱼哥的待办**：`app/proguard-rules-media.pro` 顶部那段 Guava 注释请勿删除——
它是防止后人「顺手加个 -keep」的唯一现场指引；真正的保险是 `AssertNoGuavaTask`。

---

## 附：证据文件

| 文件 | 内容 |
|---|---|
| `_gv_deps.txt` | `:app:dependencies` 全量输出（Guava 来源） |
| `_gv_trialA.txt` / `_gv_trialA3.txt` | 方案 A 试验失败日志（`NoClassDefFoundError: Equivalence`） |
| `_gv_regress.txt` | 反向验证日志（2498 命中 + BUILD FAILED） |
| `_gv_build_final.txt` | 干净重建 + 断言 PASS 日志 |
| `_gv_logcat.txt` / `_gv_play_logcat.txt` | Dreambox 启动 / EXO 起播 logcat |
| `I:\Temp\opencode\gv_scan_dex.py` | 独立 dex 扫描器（与 Gradle 断言互证） |
| `I:\Temp\opencode\_gv_check.ps1` | PID 精确过滤检查脚本 |

---

# 附：任务书前提的纠正（供后续轮次引用）

任务书 §一 与上轮 `_dreambox_release_result.md` §3/§7 均称
「R8 shrink 把 Guava 整个剔除，所以 APK 里没有 Guava 可被顶掉」。

**实测纠正**：Guava **没有被剔除**，而是被 **R8 整体改名**（175 个存活类，
如 `ImmutableList → w11`、`ByteStreams → kk`、`CacheBuilder → cl`）。
安全原因是 **dex 内不存在 `com/google/common` 类型描述符**，
系统 `libsetting.jar` 的同名旧 Guava **因包名对不上而永远不会被命中**。

两者在「结果」上都是「P0 不崩」，但**机制完全不同**，且这个区别决定了：
- 任务书据此推荐的**方案 A（exclude Guava）是错的**，实测会直接编不过；
- 风险点也不是「某依赖重新引入 Guava API」（引入也没事，混淆照样改名），
  而是「**有人让 Guava 保留原始包名**」——这才是断言要拦的东西。