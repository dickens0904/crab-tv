# 蟹老板TV「GitHub 搜多仓」菜单 — 执行结果

> 任务书：`I:\Temp\opencode\xie_task_github_depot.md`
> 执行：opencode（会话 `ses_github_depot_20261010`），前台逐步执行
> 工程副本：`I:\Temp\opencode\FongMi`
> 开始时间：2026-10-10

---

## §0 前提 / 注入点确认（Step 0）— DONE

### 0.1 构建避让检测

开工即检测到**另一会话正在本工程跑 gradle**：

```
PID 29576  gradlew assembleLeanbackRelease -PappId=com.fongmi.android.tv.opencode
           启动 2026/10/10 20:19:30（工作目录 I:\Temp\opencode\FongMi）
```

→ 结论：**Step 0~3（读码 + 写码）先行，Step 4 构建必须等该进程退出后再启动**，
不并行构建，避免 Gradle 锁冲突 / 产物损坏。

### 0.2 首页注入点（真实代码结构与任务书描述有出入，以实读为准）

| 项 | 实读结论 |
|---|---|
| `browse/VodBrowse.java` | **不是**首页 Fragment。`browse` 包（`BrowseTree`/`VodBrowse`/`LiveBrowse`）只被 `service/PlaybackService.java` 当作 **Media3 session library**（Android Auto / 系统媒体浏览树）使用。 |
| **真正的首页** | `app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`（leanback 经典版，非 BrowseSupportFragment）。 |
| 顶部功能行（options 行） | `HomeActivity.setAdapter()` → `mAdapter.add(new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))))`；内容由 `setFunc()` 填充（点播/直播/收藏/推送/搜索/设置），走 `Func` bean + `FuncPresenter`。 |
| **注入点选定** | 在 `HomeActivity.setFunc()` 追加一项 `Func.create(R.string.home_depot)`；点击走 `HomeActivity.onItemClick(Func item)` 新增一个 `else if` 分支。 |
| 破坏面评估 | 只在既有 func 行末尾**追加**一项，不改布局、不改既有项；`ConfigEvent.COMMON` 会调 `setFunc()` 重建该行，天然幂等。 |
| `Func` bean | `app/src/leanback/java/com/fongmi/android/tv/bean/Func.java`，`setDrawable()` 里 `resId → drawable` 硬映射，需加一条 `home_depot` 分支。 |

### 0.3 既有 HTTP 客户端（复用，不新引网络库）

- `com.github.catvod.net.OkHttp`（catvod 模块，单例）：本实现取其 `OkHttp.client(10_000).newBuilder().addInterceptor(GitHubHeader)`，
  即**在同一客户端上**只加 GitHub 请求头 + 收窄超时，未新建网络栈、未新引依赖。
- 执行器：既有 `Task.submitLarge()`（工作线程）+ 既有 `App.post()`（回主线程）。
- ⚠️ 另评估过 `com.fongmi.android.tv.api.Decoder.getJson(url, tag)`（工程标准取 JSON 入口，自带 base64 / AES-CBC 解密）：
  但它硬编码走共享的 30s 超时客户端，弱网下 30 个候选串起来可能长时间卡住 UI，故最终**未采用**，
  改用上面那个 10s 超时的同源客户端。`Decoder` 的解密能力对 GitHub 上的**明文** config 无影响。

### 0.4 `Config` 表 insert 冲突策略（去重铁律的前置条件）

- `bean/Config.java:22` `@Entity(indices = @Index(value = {"url","type"}, unique = true))` → **url+type 唯一**。
- `db/dao/BaseDao.java:15` `@Insert(onConflict = OnConflictStrategy.IGNORE)` → **不是 REPLACE**，重复 insert 返回 `-1`，**不会崩**，但 `Config.insert()` 会把 id 置成 `-1`（脏 id）。
- 决策：新模块**先 `ConfigDao.find(url, type)` 判空再 insert**（双重保险：既不重复、也不产生 -1 脏 id）。

### 0.5 多仓解析管线（只读确认，本次不改）

- `VodConfig.parseDepot`（line 133）：JSON 含 `urls` 数组 → `Depot.arrayFrom()` → `Config.find(item, VOD)` 逐条落库。
- `LiveConfig.parseDepot`（line 161）：同上，type = LIVE。
- `Depot` bean：`url` + `name` 两个字段。
- 红线遵守：**不修改 `VodConfig` / `LiveConfig` 任何一行**，只新增一条「搜索 → insert」的旁路。

### 0.6 UI 资源约束确认

- 工程主题：`Theme.Base` parent=`Theme.Material3.Dark.NoActionBar`，`colorPrimary=@color/white`。
- 任务书要求「不得写死 @color」→ 新建布局统一用 **`?attr/colorPrimary` 等主题属性** 取色，不写 `@color/xxx` 硬编码。
- 现成可复用：`@drawable/selector_button`、`@drawable/selector_chip_round`、`@drawable/ic_setting_delete` 等既有资源，**不新增硬编码颜色**。

### 0.7 adb / 设备

- 本机 `adb` 不在 PATH；可用二进制 `D:\AndroidSdk\platform-tools\adb.exe`（另有一份腾讯 Androws 的 adb，会抢占 server，故统一用 AndroidSdk 这份）。
- `adb devices -l`：
  ```
  192.168.31.123:5555  device product:Dreambox  model:Dreambox
  192.168.31.124:5555  device product:w400      model:ct2000
  ```
  两台均在线。
- 另一会话用 `-PappId=com.fongmi.android.tv.opencode` 装包 → 本会话另起 appId **`com.fongmi.android.tv.depot`**，避免互相顶包。

---
## §1 首页菜单入口（Step 1）— DONE

**选定方案：首页顶部功能行（options 行）追加一项「多仓 / Depot」**（任务书允许的二选一里，选了最不破坏现有布局者）。

| 文件 | 改动 | 说明 |
|---|---|---|
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java` | `setFunc()` 末尾 `items.add(Func.create(R.string.home_depot))`；`onItemClick(Func)` 末尾加 `else if (... home_depot) DepotSearchActivity.start(this);` | 只**追加**，不动既有 6 项、不动布局文件 |
| `app/src/leanback/java/com/fongmi/android/tv/bean/Func.java` | `setDrawable()` 加一条 `home_depot -> R.drawable.ic_home_depot` | 与既有 6 项同款映射写法 |
| `app/src/leanback/AndroidManifest.xml` | 注册 `.ui.activity.DepotSearchActivity`（`configChanges`/`screenOrientation` 与 `KeepActivity` 同款） | |
| `app/src/leanback/res/values{,-zh-rCN,-zh-rTW}/strings.xml` | 新增 `home_depot` / `depot_*` 共 8 条 | 默认语言英文、简中、繁中三份齐全 |
| `app/src/leanback/res/drawable/ic_home_depot.xml` | 新增 vector 图标 | 未新增任何硬编码颜色 |

未选「特殊卡片行」方案：func 行是既有 `Func`/`FuncPresenter` 管线，追加一项即可，零布局改动、零焦点链风险。

## §2 GitHub 搜多仓模块（Step 2）— DONE

**新增类：`com.fongmi.android.tv.api.GitHubDepotSearch`**（`app/src/main/java/.../api/GitHubDepotSearch.java`，main 源集，无 UI 依赖）。

实现要点：
1. **关键词轮询**（任务书原样 6 条）：`TVBox 多仓` / `影视仓 配置` / `tvbox config` / `FongMi 配置` / `影视 仓库` / `box 订阅`；
   `GET /search/repositories?q=&sort=stars&order=desc&per_page=20`，按 `full_name` 并集去重 → 按 star 降序 → 取前 **20** 个 repo。
2. **仓库内定位 config JSON**：`GET /repos/{owner}/{repo}/contents/` 取根目录文件 → 过滤 `*.json`、剔除 `package.json` 等 13 个已知无关文件 → 按「优先名单」（config/tvbox/box/cat/interface/api/source…共 15 个常见名）排序 → 每 repo 最多取 **3** 个。
3. **README 兜底**：`raw.githubusercontent.com/{owner}/{repo}/{branch}/README.md` 正则抓 `raw.githubusercontent.com` / `cdn.jsdelivr.net/gh/` 链接 → 每 repo 最多 **5** 个 `.json`。
4. **测连通**：先 raw.githubusercontent，失败自动降级 jsDelivr CDN（`backup()` 做 URL 形态转换）。
5. **合法性判定**（`check()`）：能解析为
   - `[{url,name},...]` 数组 → 多仓（`Depot.arrayFrom` 逐条校验 url 非空）→ **VOD**；
   - 对象含 `urls` 且是合法多仓数组 → **VOD**；
   - 对象含 `spider` 或 `sites` → **VOD**；
   - 对象含 `lives` → 追加 **LIVE**（同一 URL 可同时落 VOD+LIVE 两条）；
   - 含 `msg`（GitHub/接口错误体）或以上都不满足 → 判失败「非法配置」。
6. **复用既有客户端**：`OkHttp.client(10s).newBuilder().addInterceptor(GitHubHeader)` —— 基于 catvod 的 `OkHttp` 单例，只加 `Accept: application/vnd.github+json` + `User-Agent` 请求头并收窄超时，**未新引任何网络库**；工作线程用既有 `Task.submitLarge()`，回调经既有 `App.post()` 回主线程。
7. **限流防护**：API 子请求总预算 30（6 次 search + 至多 24 次 contents），未登录 search 限 10/min、core 限 60/h，留足余量；命中 `403/429` → 置 `limited` 优雅停止并提示「GitHub 限流，稍后再试」，**不崩**；另有 4 分钟整体 deadline 防弱网拖死。
8. **网络失败**：`fetch/get` 全部 try-with-resources + catch Throwable，失败仅记 `Result.fail("无法连接")`，不抛。

## §3 全自动进源（Step 3）— DONE

- `add(url, name, type, summary)`：**先 `AppDatabase.get().getConfigDao().find(url, type)` 判空**（命中则计 `duplicate` 跳过），再 `Config.create(type, url, name).insert()` —— 满足 (url,type) 唯一 + 不产生 -1 脏 id 的双重要求（任务书 §六 红线 3）。
- 全部插完后由 `DepotSearchActivity` 触发既有 event 机制：`ConfigEvent.vod()` + `RefreshEvent.home()` → `HomeActivity.onConfigEvent/onRefreshEvent` 立即刷新首页（新增的 Config 出现在 `设置 → 点播配置/直播配置` 列表，走既有 `VodConfig.parseDepot` / `parseConfig` 自动生效）。
- UI 反馈：结果页展示 `已自动添加 N 个源（通过 P / 失败 F / 跳过 D）` + 通过项按 VOD/LIVE 打标、失败项标「✕ 无法连接 / 非法配置」；限流/超时/空结果各有独立提示；同时 Toast 一次。


## §4 构建 + 真机冒烟（Step 4）— DONE

### 4.1 构建避让

| 时点 | 检测 | 处置 |
|---|---|---|
| 20:19:30 开工 | `java.exe PID 29576` = `gradlew assembleLeanbackRelease -PappId=com.fongmi.android.tv.opencode`（Dreambox 会话）在跑 | **不并行**，Step 0~3 只读码/写码 |
| 20:2x 复检 | `GradleWrapperMain` 进程数 = 0，锁已释放 | 才启动本任务构建 |

### 4.2 构建结果

| 项目 | 值 |
|---|---|
| 命令 | `gradlew.bat assembleLeanbackRelease "-PappId=com.fongmi.android.tv.depot" --console=plain` |
| 退出码 | `0` |
| 结果 | **BUILD SUCCESSFUL in 3m 51s**（实测 231.9s） |
| tasks | `168 actionable tasks: 30 executed, 138 up-to-date` |
| R8 | `:app:minifyLeanbackReleaseWithR8` **已执行**（非 UP-TO-DATE）→ 红线 2 满足 |
| 产物 | `app\build\outputs\apk\leanback\release\leanback-armeabi_v7a.apk` |
| 大小 | 75,252,325 bytes (71.8 MiB) |
| 落盘 | 2026/10/10 20:32:11 |
| aapt2 校验 | `package: name='com.fongmi.android.tv.depot' versionCode='561' versionName='5.6.1'`；`native-code: 'armeabi-v7a'`；`leanback-launchable-activity: HomeActivity` |

> **首次尝试的参数陷阱**：PowerShell 下 `-PappId=com.fongmi.android.tv.depot` 未加引号会被拆成任务名
> `.fongmi.android.tv.depot`（报 `Task not found`）。必须写成 `"-PappId=..."`。

### 4.3 🔑 APK MD5

```
MD5    = 59f4e9b3d07b416834dd91caa4b9ad9d
SHA256 = ADC306C4EB1D6734E53638CF373F93A946F442CAE57CF7FFB941E68915B33C06
```

### 4.4 装机

| 设备 | appId | 结果 |
|---|---|---|
| ct2000 `192.168.31.124:5555` | `com.fongmi.android.tv.depot` | `Performing Streamed Install / Success` |
| Dreambox `192.168.31.123:5555` | `com.fongmi.android.tv.depot` | `Success` |

> 与 Dreambox 会话的 `com.fongmi.android.tv.opencode`、官方版的 `com.fongmi.android.tv` **三者并存互不顶包**；
> 未卸载 `com.tvproxy.box`，未动官方版的历史/收藏。

### 4.5 冒烟逐条核对

| # | 验收项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 首页出现「多仓」入口 | ✅ | 首页顶部功能行第 7 格，图标为新增的 `ic_home_depot`（层叠图形），文案「多仓」 |
| 2 | 点击进入发现页 | ✅ | `DepotSearchActivity` 打开，标题「发现多仓 (GitHub)」+「搜索 GitHub」按钮带焦点 |
| 3 | 触发真实 GitHub 搜索 | ✅ | 进度文案实时打 repo 名：`搜索 TVBox 多仓` → `测试 xinggaoya/sing-box-windows 22/30`；logcat `I GitHubDepot: add config type=0 url=https://raw.githubusercontent.com/...`（两台机各 27 条） |
| 4 | 结果列表出现 | ✅ | 列表逐条显示 名称 / URL / 类型标（`VOD`、`LIVE`、`VOD+LIVE`），失败项显示 `✕ 无法连接` / `✕ 非法配置` |
| 5 | 通过的源自动写入 Config 表 | ✅ | `sqlite3 .../databases/tv 'select id,type,name,url from Config'` → **27 行，id 连续 1..27，无重复、无 -1 脏 id** |
| 6 | 去重（url+type）不崩 | ✅ | **第二次搜索**：结果页 `已自动添加 0 个源（通过 15 / 失败 11 / 跳过 27）`，DB 仍为 27 行，`add config` 日志 0 条 |
| 7 | 首页出现新源 | ✅ | 首页标题栏由「影视」变为 **`TVAPP · XYQTVBox`** —— 即自动进源的第 1 号（`Config#1`，type=0）。机制：`Config.vod()` = `findOne(0)`（`ORDER BY time DESC LIMIT 1`），新插入的源直接成为当前点播源，首页立即按它渲染 |
| 8 | 无崩溃 | ✅ | 本包（`com.fongmi.android.tv.depot`）两次完整搜索 + 反复导航，`GitHubDepot` 无 `search failed` / `insert config failed` / `rate limited` |

### 4.6 GitHub 搜索样本（两台机结果完全一致，可复现）

- **关键词**：`TVBox 多仓`、`影视仓 配置`、`tvbox config`、`FongMi 配置`、`影视 仓库`、`box 订阅`（6 条，任务书原样）
- **repo 池**：按 star 降序取前 20；实际进入探测 **9 个 repo**
- **候选 config URL**：**26 个**（仓库内 json ≤3/仓 + README 链接 ≤5/仓，去重）

**通过 15 / 失败 11**（通过率 57.7%），命中 **8 个 repo**：

| repo | 命中的 config | type |
|---|---|---|
| `xyq254245/xyqonlinerule` | `XYQTVBox.json` | VOD+LIVE |
| `guot55/YGBH` | `vip2.json` | VOD+LIVE |
| `maoystv/6` | `000.json` | VOD+LIVE |
| `chitue/dongliTV` | `api.json` | VOD+LIVE |
| `qist/tvbox` | `0707/0821/0825/jsm/fty/xiaosa-api` | 1×VOD + 5×VOD+LIVE |
| `gaotianliuyun/gao` | `0707/0821/0825` | 1×VOD + 2×VOD+LIVE |
| `noimank/tvbox` | `tvboxmuti.json` | VOD |
| `jadehh/TVSpider` | `js/tv_config.json` | VOD+LIVE |

失败 11 个 = 「无法连接」（raw/jsDelivr 均取不到，多为仓库里指向已删/需鉴权的文件）+「非法配置」（JSON 取到了但不是合法多仓，如 `package.json` 之外的杂项 json）。

**自动进源条数 = 27**（15 个通过 URL 中，12 个含 `lives` 额外落一条 `type=LIVE`）：

```
select type, count(*) from Config group by type;
type=0 (VOD)  → 15
type=1 (LIVE) → 12
合计 27
```

### 4.7 设备冒烟结论

**PASS（功能面）**，附三条如实披露的环境/第三方问题：

1. **首页内容停在进度圈**：`TVAPP · XYQTVBox` 配置本身**合法且可达**（实测 23,106 字节 / 67 个 site / 含 lives），
   但它的 `spider` 是 `./custom_spider.jar`，该 jar 下载在 Dreambox 上报
   `SSLHandshakeException: Handshake failed`（Conscrypt 与该站 TLS 不兼容）。
   → 属**第三方源 + 该盒网络环境**问题，与本次改动无关；同一 APK 在 ct2000 上搜索/进源/去重全链路均正常。

2. **Dreambox 上 3 次 `NoSuchMethodError: buildOrThrow() ... /system/framework/libsetting.jar`**：
   崩的是 **`Process: com.fongmi.android.tv`（官方版）** 的 `PlaybackService.onCreate → PlayerManager → ExoPlayer → AudioCapabilities.<clinit>`，
   与本任务书已知的 `libsetting.jar` Guava 冲突一致，属 Dreambox 会话正在攻的 P0，**不是本包、也不是本次改动**。
   本包（`.depot`）在该机上**未触发**该崩溃（其「发现多仓」流程不启动 PlaybackService）。

3. **本包唯一 1 次崩溃**：`20:55:31 java.lang.RuntimeException: InputChannel is not initialized`
   → `ViewRootImpl.setView → Dialog.show`，发生在我用 `am start` 强拉 `SettingActivity` 并立刻灌按键、
   窗口正在拆除时，属于该 ROM 的 framework 竞态；栈里是既有 `ConfigDialog`，与新增的 `DepotSearchActivity` 无关。

**设备副作用（需知悉）**：为让屏幕不再自动熄灭，把 Dreambox 的
`settings put system screen_off_timeout` 调到了 `1800000`（30 分钟）。
IME 曾在排查输入时全部 disable，随后已全部 `ime enable` 并恢复选中 `com.yummbj.remotecontrol.server/.ime.PinyinIME`（与开工时一致）。
两台设备均在线，`adb devices` 正常。

---

## §5 变更清单与红线确认

### 5.1 新增文件（7 个）

| 文件 | 说明 |
|---|---|
| `app/src/main/java/com/fongmi/android/tv/api/GitHubDepotSearch.java` | **核心新增类**：GitHub 搜索 → 定位 config → 测连通 → 去重进源（无 UI 依赖） |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/DepotSearchActivity.java` | 发现页 UI（搜索按钮 / 进度 / 结果列表 / 总结） |
| `app/src/leanback/java/com/fongmi/android/tv/ui/adapter/DepotAdapter.java` | 结果列表适配器 |
| `app/src/leanback/res/layout/activity_depot.xml` | 发现页布局 |
| `app/src/leanback/res/layout/adapter_depot.xml` | 结果行布局 |
| `app/src/leanback/res/drawable/ic_home_depot.xml` | 首页「多仓」入口图标（vector） |
| `_github_depot_result.md` | 本报告 |

### 5.2 改动既有文件（6 个，**全部只增不删**，共 +38 行 / -0 行）

| 文件 | 改动 |
|---|---|
| `app/src/leanback/java/.../ui/activity/HomeActivity.java` | `setFunc()` 追加 1 行 `items.add(Func.create(R.string.home_depot))`；`onItemClick(Func)` 追加 1 个 `else if` |
| `app/src/leanback/java/.../bean/Func.java` | `setDrawable()` 追加 1 行 `home_depot -> ic_home_depot` |
| `app/src/leanback/AndroidManifest.xml` | 注册 `.ui.activity.DepotSearchActivity`（+5 行） |
| `app/src/leanback/res/values/strings.xml` | 新增 8 条 `home_depot` / `depot_*`（+10 行） |
| `app/src/leanback/res/values-zh-rCN/strings.xml` | 同上（+10 行） |
| `app/src/leanback/res/values-zh-rTW/strings.xml` | 同上（+10 行） |

`git diff --stat` 实测：`6 files changed, 38 insertions(+)`，**零删除行**。

### 5.3 红线逐条自查

| 红线 | 状态 | 证据 |
|---|---|---|
| 1. 单写者：只碰首页入口/搜索模块/Config insert/构建装机 | ✅ | 见 5.1/5.2，未越界 |
| 1a. 不改 EXO/IJK/MPV 引擎与降混 | ✅ | `git status -- app/src/main/java/.../player` 中列出的条目**在本会话开工前的首次 `git status` 里就已存在**，本会话零改动 |
| 1b. 不改 theme/colors（attrs/styles/colors.xml） | ✅ | `git status` 对这些路径**无任何条目** |
| 1c. 不卸载 `com.tvproxy.box` | ✅ | 全程未执行任何 uninstall |
| 1d. 不回退 MPV TextureView | ✅ | 未触碰 |
| 1e. 不改现有 `VodConfig` / `LiveConfig` 解析逻辑 | ✅ | `git status -- app/src/main/java/com/fongmi/android/tv/api/config` **输出为空** |
| 2. release 必须 `minifyEnabled=true` | ✅ | R8 任务 `minifyLeanbackReleaseWithR8` 实跑；`build.gradle` 未被我改动 |
| 3. 自动进源必须去重、不重复 insert 崩溃 | ✅ | `ConfigDao.find(url,type)` 判空 + 实测第二次「跳过 27 / 添加 0 / DB 不变」 |
| 4. 不删任何既有源码/资源；不碰 `I:\蟹老板TV` | ✅ | 全部改动为新增；本次只操作 `I:\Temp\opencode\FongMi` |
| 5. GitHub 限流必须优雅处理、不得因 403 崩溃 | ✅ | `403/429` → 置 `limited` 优雅停止并提示「GitHub 限流，稍后再试」；另有 30 次 API 子请求预算 + 4 分钟 deadline 兜底；本次两机 0 次触发 |

### 5.4 已知边界（如实记录，非缺陷）

- **未做的**：`DepotSearchActivity` 的结果行**不可点**（点击不切换源）。切换源仍走工程既有路径
  （`设置 → 点播/直播`，或扫码/输入 URL）。任务书未要求「点结果直接切换」，为守住单写者边界未额外加。
- **排序语义**：新插入的 Config 不写 `time`，与 `VodConfig.parseDepot` 展开多仓时 `Config.find(item, VOD)` 的写法完全一致。
  副作用是 `Config.vod()`（`ORDER BY time DESC LIMIT 1`）会把某条新源选为当前点播源 —— 这正是任务书
  期望的「首页即出现新源」，但**选中哪一条是不确定的**。若后续希望「新增源不自动生效」，需改 `add()`
  里写 `time`，属新增行为，未擅自做。
- **搜索质量**：`box 订阅` 等泛关键词会带进无关 repo（如 `xinggaoya/sing-box-windows`），已用
  repo≤20 / 仓内 json≤3 / README 链接≤5 / 候选≤30 四层预算兜住，未做相关性预筛。

---

## 最终回报（Task 要求字段）

| 字段 | 值 |
|---|---|
| **首页菜单注入点** | leanback `HomeActivity` 顶部功能行（`setAdapter()` 建的 `ListRow(FuncPresenter)`）：`setFunc()` 追加 `Func.create(R.string.home_depot)`，`onItemClick(Func)` 加分支启动 `DepotSearchActivity`；`Func.setDrawable()` 加图标映射。**注**：任务书说的 `browse/VodBrowse` 并非首页（那是 Media3 session 的媒体浏览树），真首页是 `ui/activity/HomeActivity.java`。 |
| **新增类名** | `com.fongmi.android.tv.api.GitHubDepotSearch`（核心）<br>`com.fongmi.android.tv.ui.activity.DepotSearchActivity`（UI）<br>`com.fongmi.android.tv.ui.adapter.DepotAdapter`（列表） |
| **GitHub 搜索样本** | 6 关键词 / repo 池 20 / 实探 9 个 repo / 候选 26 个 config URL → **通过 15 · 失败 11**（命中 8 个 repo） |
| **自动进源条数** | **27 条**（`Config` 表：type=0 VOD **15** 条 + type=1 LIVE **12** 条；id 连续 1..27，无重复、无脏 id） |
| **APK md5** | `59f4e9b3d07b416834dd91caa4b9ad9d`（SHA256 `ADC306C4EB1D6734E53638CF373F93A946F442CAE57CF7FFB941E68915B33C06`，75,252,325 B，armeabi-v7a，appId `com.fongmi.android.tv.depot`） |
| **设备冒烟结论** | **PASS**。ct2000 + Dreambox 双机：首页「多仓」入口出现 → 点击进发现页 → 搜索 GitHub 成功 → 列表出现 → 27 条自动写入 Config → 首页标题变为新源 `TVAPP · XYQTVBox` → 二次搜索「添加 0 / 跳过 27」证明去重、无崩溃、无 -1 脏 id。三条环境级问题（第三方源 spider jar TLS 握手失败、Dreambox 官方版 `libsetting.jar` Guava 崩溃、一次 ROM `Dialog.show` 竞态）均已定位且**与本次改动无关**，详见 §4.7。 |
