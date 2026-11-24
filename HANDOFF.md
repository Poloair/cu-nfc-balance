# 交接纲要 · 交通卡余额查询（NFC）Android App

> 给接手的 AI／工程师：这份文档描述**当前真实代码状态**（v2.23 / versionCode 32），不是需求稿。读完「0. 30 秒速览」+「2. 构建」+「4. 代码地图」就能开工；改代码前务必看「9. 约定与坑」。

---

## 0. 30 秒速览

| 项 | 值 |
|---|---|
| 包名 | `com.cubalance.app` |
| 版本 | `versionCode 32` / `versionName "2.23"`（在 `AndroidManifest.xml` 里改） |
| minSdk / targetSdk | 21 / 34 |
| 语言/框架 | Java 8 语法、原生 Android SDK（`android.*`）、**没有 Gradle、没有 AndroidX、没有 Material Components、没有第三方库** |
| 权限 | 只有 `NFC` + `VIBRATE`，**没有 INTERNET**（产品红线：纯本地、不联网、免登录） |
| 构建方式 | 手写 `build.sh`（aapt2 + ECJ + d8 + zipalign + apksigner），产出 `交通卡余额查询.apk` |
| 代码规模 | 18 个 Java 文件 ≈ 3.4k 行；13 个 layout；19 个 drawable；50 条 string |
| 功能 | 贴卡读余额 + 卡号/卡种、余额走势图、单笔柱状图、消费记录、收支账本、多卡管理（备注/排序/删除/可用天数提醒）、读卡失败自助诊断 |

---

## 1. 产品定位与红线

- 只读应用：**不对卡片做任何写操作**，不修改卡内数据。
- 纯本地：所有数据只存 `SharedPreferences`，无网络请求（不要新增 INTERNET 权限）。
- 无账号、无广告、无埋点。
- 核心交互只有一个动作：**把卡贴到手机背面 NFC 区域**，等 1–2 秒出结果（震动反馈）。
- 读不了的卡（产品上要如实说明，不要"修复"成能读）：后付费卡、带交通功能的银行卡、手机内虚拟卡、余额在服务器上的礼品卡。

---

## 2. 构建（这步最容易踩坑，先读）

工具链**不在仓库里**（体积大，没提交）。默认取项目同级目录 `../tools/`，可用环境变量 `ANDROID_TOOLS` 指向别处；本机实际位置是 `D:\Work\2026-11-24-15-32-50\tools\`：

```
tools/
├── jdk-17.0.21.1+2-jre/        # 只用它的 java.exe / keytool.exe
├── ecj.jar                     # Eclipse 编译器（替代 javac）
├── platform/android.jar        # 编译期 classpath
├── build-tools/
│   ├── aapt2.exe  zipalign.exe
│   └── lib/d8.jar  lib/apksigner.jar
└── release.jks                 # 签名库 alias=cubalance，口令 123456
```

### 构建命令（Windows + Git Bash）

```bash
cd /d/Work/2026-11-24-15-32-50/cu-nfc-balance
rm -rf build && bash build.sh      # ← 每次都必须先删 build
```

`build.sh` 的 9 步：aapt2 compile → aapt2 link（出 R.java + base.apk）→ ECJ 编译 → d8 转 dex → Python 注入 `classes.dex`（`resources.arsc` 必须 STORED）→ 生成/复用 keystore → zipalign → apksigner（v1/v2/v3）→ verify。成功标志：`Verifies` + `class files: N` + 生成 `交通卡余额查询.apk`。

**硬性注意**
1. `build/classes` 里的旧 `.class` 不清会混进 dex（已踩过坑），所以必须 `rm -rf build`，不要只跑 `bash build.sh`。
2. `build.sh` 的路径已改成可覆盖：`TOOLS` 默认 `$(dirname PROJ)/tools`（用 `ANDROID_TOOLS` 覆盖），Python 自动探测 `python3/python/py`（用 `PYTHON` 覆盖）。换电脑一般不用改脚本。
3. Windows 下依赖 `cygpath`（Git Bash 自带，脚本自动检测并处理 `.exe` 后缀）；Linux/macOS 用同名可执行文件即可。纯 PowerShell/CMD 跑不了。

### 校验命令（改完必做）

```bash
A=/d/Work/2026-11-24-15-32-50/tools/build-tools/aapt2.exe
$A dump badging 交通卡余额查询.apk | head -3        # 版本号、sdk、权限
$A dump badging 交通卡余额查询.apk | grep uses-permission
$A dump resources 交通卡余额查询.apk | grep <新id>   # 确认新资源打进去了
$A dump xmltree --file res/layout/xxx.xml 交通卡余额查询.apk | head -40
```

---

## 3. 目录结构

```
cu-nfc-balance/
├── AndroidManifest.xml        # 版本号、权限、6 个 Activity、NFC TECH_DISCOVERED
├── build.sh                   # 唯一构建入口
├── gen_icon.py                # 生成 res/mipmap-xxxhdpi/ic_launcher.png（Pillow 手绘）
├── README.md                  # 面向用户的说明（不是技术文档）
├── src/com/cubalance/app/*.java
├── res/{layout,drawable,values,xml,mipmap-xxxhdpi}
└── 交通卡余额查询.apk          # 构建产物（唯一交付物）
```

---

## 4. 代码地图（每个文件的职责）

| 文件 | 行数 | 职责 / 关键点 |
|---|---|---|
| `MainActivity` | 417 | 首页。NFC 适配器/前台分发、`onNewIntent` 处理贴卡、读卡结果展示、余额卡、统计入口卡、可用天数提醒弹窗、失败弹窗（诊断摘要 + 复制诊断） |
| `CardReader` | 759 | **协议层，最复杂**。APDU 读余额、读交易记录、解析、FeliCa 分支、诊断日志。纯静态方法，无状态 |
| `Store` | 413 | 全部本地存储（SharedPreferences）。卡片/历史/交易/快照/备注/阈值/排序 |
| `DetailActivity` | 290 | 单卡详情：余额大头、卡片管理（备注/可用天数提醒）、余额折线图、余额记录列表（点击→当次明细，长按删除，3 条半折叠） |
| `TripsActivity` | 183 | 消费记录列表；支持 `snapTime` 模式回看"某一次读卡"的明细；诊断入口藏标题长按 |
| `StatsActivity` | 131 | 使用统计（二级页）：收支四宫格 + 单笔柱状图 + 逐笔账本 |
| `CardsActivity` | 136 | 全部卡片列表；长按菜单（重命名/上移/下移） |
| `SettingsActivity` | 115 | NFC 状态行、支持的卡种、隐私说明、清空数据、版本 |
| `BottomBar` | 68 | 三项浮动底栏绑定：0=主页 1=卡片 2=设置 |
| `BlurBar` / `RenderBlur` | 143 / 18 | 底栏真模糊：截屏下采样 + API31 RenderEffect |
| `Dialogs` | 192 | 全部弹窗工厂：`show/info/confirm/input/menu`，统一窗体宽度与按钮行 |
| `BalanceChart` | 132 | 余额折线图（横轴=笔序，不用时间） |
| `BarChart` | 232 | 单笔柱状图，点击柱子高亮 + 金额气泡，默认选中最新一笔 |
| `Money` | 38 | 币种常量与格式化（KRW/CNY/JPY/HKD，CNY 以分存、HKD 以 1/10 存） |
| `Forecast` | 105 | 预计可用天数：按卡内交易记录（不够时用本机余额降幅）算日均消费，`daysLeft`=余额/日均，-1=数据不足 |
| `Trip` | 45 | 一笔交易：type/cost/balance/timeMs/timeText + JSON |
| `TransitCard` | 23 | 卡片身份：key(sha256 uid)/uidTail/csn/typeName/typeCode/currency |
| `MaxHeightScrollView` | 29 | 限制最大高度的 ScrollView（弹窗长文本用） |

### 页面关系

```
MainActivity(底栏0) ─┬─ DetailActivity ─┬─ TripsActivity(普通/  snapTime 模式)
                     │                  └─ (长按余额记录→删除)
                     ├─ StatsActivity   (统计入口卡)
CardsActivity(底栏1) ─┴─ DetailActivity
SettingsActivity(底栏2)
BottomBar.setup(activity, index) 在每个一级页 onCreate 里调用
```

二级页传参：`DetailActivity` 收 `key`；`TripsActivity` 收 `key` + 可选 `long snapTime`。

---

## 5. 数据层（`Store`，SharedPreferences 文件 `cu_balance_local`）

| Key | 类型 | 内容 |
|---|---|---|
| `cards` | JSON array | 每张卡当前状态：`{key,uidTail,csn,typeName,typeCode,balance,time,currency}` |
| `history` | JSON array | 所有卡的余额历史（升序），同结构；**全局限 200 条** |
| `trips_<key>` | JSON array | 该卡交易记录（卡内原始顺序，最新在前，约 20 条） |
| `snap_<key>` | JSON array | 每次读卡的快照：`{time,balance,trips:[...]}`，**每卡限 60 份** |
| `alias_<key>` | String | 备注名（空=未设） |
| `alert_<key>` | int | 可用天数提醒线（天，0=关闭，1-365） |
| `alerts_use_days` | bool | 提醒线单位迁移标记：旧版本存的是金额阈值，首次启动清空全部 `alert_*` |
| `order` | JSON array | 卡片显示顺序（key 数组，手动排序用） |
| `diag` | String | 最近一次读卡的诊断日志 |

`Trip` JSON：`{type,cost,balance,timeMs,timeText}`，`type==2` 为充值，其余为消费。

主要方法：

```java
saveRead(TransitCard card, int balance, List<Trip> trips) // 写 cards + history + order 置顶 + trips + 每次必写 snap
loadCards() / loadHistory() / historyOf(key) / loadTrips(key) / nameOf(entry)
saveSnap(key,time,balance,trips) / loadSnaps(key) / snapFor(key,time) → SnapHit{snap,rel}
deleteHistory(key,time)   // 删该条历史 + 同次快照
deleteCard(key) / clearAll()
aliasOf/setAlias ; alertOf/setAlert ; moveCard(key,±1) ; loadOrder/saveOrder
```

**快照语义（重要，别改坏）**：`snapFor` 返回 `rel`：
- `HIT_EXACT` = 就是那一次读卡的快照（`|Δtime| ≤ 5s`）
- `HIT_BEFORE` = 当次没读到明细，沿用更早一次读到的
- `HIT_AFTER` = 升级前的老记录没有快照，用最早一份并标注
- `null` = 完全没有快照（只有极老数据），此时退回 `loadTrips` 并标注"没有当次快照"

---

## 6. 读卡流程（端到端）

```
用户贴卡
 → MainActivity.onNewIntent(ACTION_TECH_DISCOVERED) → handleIntent()
 → 震动 + "读取中"
 → CardReader.read(tag)                       // 可能抛 ReadException(diag)
 → store.saveRead(card, balance, trips)
 → showResult(result)                          // 余额、卡种、卡号、时间、"查看消费记录"
 → checkLowBalance(result)                     // 阈值命中 → 弹窗
 → updateStats()                               // 刷新首页统计入口卡
失败 → showFailDialog(msg, diag)                // 错误摘要 + 6 条常见原因 + 复制诊断
```

`CardReader` 关键事实：
- 已知 AID 列表 10 个：T-money(`D4100000030001`)、Cashbee(`D4100000140001`)、交通联合(`A000000632010105`)、MOIBA、K-Cash、Config DF(`A0000004520001`)、银联电子现金(`A000000333010101`) 等。
- 优先用 Config DF 动态读取"卡自己声明的主应用 + 余额命令"，否则遍历已知 AID 兜底。
- 余额命令按 AID 映射（`balanceCmdForAid`），失败会依次试多种 CLA/INS/P1/P2。
- 交易记录：`READ RECORD`，SFI 与 PATH 两条路都试；解析支持 **T-money 46 字节** 与 **Cashbee 26 字节** 两种布局。
- FeliCa 分支（v2.23 重写，此前命令码写错导致 Suica/八达通全读不出）：Read Without Encryption 用命令 `06h` / 响应 `07h`（0x10/0x12 是 Authentication1/2，旧版误用）；2 字节块列表元素 = 高字节 `0x80`（2 字节标记+服务序号 0）、低字节块号；Suica 余额在记录 10-11 **小端 2 字节**（旧版误当 4 字节大端）；历史日期 @4-5 为 7bit 年(自 2000)/4bit 月/5bit 日打包（旧版误当天数）；Suica 系 service `0007` 余额 + `090F` 历史（相邻余额差推金额），八达通 `0117` 余额（raw-350)/10，卡内无历史；`nfc_tech_filter.xml` 已补 NfcF tech-list（后台贴卡可拉起）；**此分支仍未经真卡验证**，改完必须拿实体 Suica/八达通卡测
- 币种按 AID 判定 → `Money.KRW/CNY/JPY/HKD`。
- 诊断写进 `Store.diag`，UI 入口：消费记录页长按标题。

---

## 7. UI / 设计系统

- 主题：`@android:style/Theme.Material.Light.NoActionBar`，窗口背景 `@drawable/bg_glass`。**不要换 Material Components**。
- 配色（`res/values/colors.xml`）：品牌薄荷 `#10A37F`（brand）/ `#0B7A63`（brand_dark）/ `#3EC9A5`；底 `#F4F5F7`；主文字 `#1B2420`；次文字 `#8A9296`；涨 `#D64545`（红）/ 跌 `#2E9E5B`（绿，**中国习惯：涨红跌绿**）；图表色 `chart_line/chart_pay/chart_grid/chart_text`。
- 字体：全部 `sans-serif`；标题 26sp bold，卡标题 16sp bold，正文 13–14sp，辅助 11sp。
- 卡片：`@drawable/card_bg`（白卡 18dp 内边距、圆角、浅阴影）；瓦片 `tile_bg`、可点瓦片 `tile_click`（ripple）、整卡可点 `card_click`。
- 图标：不用图片图标，全部用**文字符号**放在 34dp `icon_sq` 方底里（`▥ ≡ ∿ ✎ ▮ ▥ ›` 等）。
- 底栏：3 项（主页/卡片/设置），`bottom_nav.xml` 根是 `FrameLayout(navRoot)`，玻璃层 `nav_bar_bg.xml`（layer-list 模拟液态玻璃）+ `nav_pill_sel.xml` 选中胶囊。真模糊由 `BlurBar` 负责：
  - 截取底栏区域内容 → 1/5 下采样 → ImageView 显示 → 滚动停止 110ms 防抖重采样；采样时 navRoot 临时 INVISIBLE。
  - API 31+ 走 `RenderBlur`（`RenderEffect.createBlurEffect`，单独类隔离版本风险），低版本只靠玻璃层叠加。
- 底栏是**浮层**：一级页根布局 `LinearLayout`，`ScrollView` 撑满，内容 `paddingBottom=104dp` 防遮挡。
- 弹窗：统一走 `Dialogs.*`（布局 `dialog_glass.xml` / `dialog_menu.xml`）。**按钮必须是 `TextView` 不是 `Button`**：Material 主题的 Button 有 88dp minWidth 和内嵌留白，`weight=1` 也会撑破窄弹窗导致重叠（历史上反复出问题）。
- 图表：`BalanceChart`（余额折线，横轴笔序）与 `BarChart`（单笔柱，旧→新，点柱出光柱+金额气泡）。**都不使用时间轴**（用户明确要求）。

---

## 8. 版本演进（了解"为什么长这样"）

| 版本 | 内容 |
|---|---|
| ≤2.13 | 单页 + 图表 + 消费记录 |
| 2.14 | 回退重建：修图表时间轴、修弹窗重叠、新增浮动底栏 + 主页/卡片/统计/设置四页 |
| 2.15–2.16 | 修底栏缺失、删重复的统计卡、贴卡入口去掉卡种列表、统计并入首页 |
| 2.17 | 底栏真模糊、低余额提醒、多卡汇总、别名与排序、读卡失败自助引导 |
| 2.18 | 汇率换算做了又整体撤销（无 INTERNET 权限）；删多卡汇总；长按菜单改列表式 |
| 2.19 | 弹窗按钮改 TextView 根治重叠；余额记录按次快照；首页删隐私按钮；统计改二级页收支账本 |
| 2.20 | 统计页加回柱状图；快照改为每次读卡必写；余额记录折叠 + 内部滚动 |
| 2.21 | 柱状图点击光柱看金额；余额记录长按删除；折叠改 3 条半 |
| 2.22 | 低余额提醒改成「预计可用天数」（新增 `Forecast`），提醒线单位迁移；输入弹窗支持说明正文 |
| 2.23 | 修复 FeliCa 协议级错误：RWE 命令 06/07、块列表元素字节序、Suica 余额小端 2 字节、日期 7/4/5bit 打包；tech filter 补 NfcF；build.sh 两处无条件 cygpath 改条件执行（Linux/macOS 可跑） |

**已撤销/不要重加**：汇率换算（用户明确不要，且要联网）、首页隐私按钮（只在设置页）、首页多卡汇总卡、统计页独立页面里的重复图表。

---

## 9. 约定与坑（改代码前必读）

1. **不要引入 Gradle / AndroidX / Kotlin / 第三方依赖**。ECJ 只编到 Java 8：不能用 lambda、不能用 `Stream`、不能用 `var`、不能用 `try-with-resources` 之外的 8+ 语法（实际代码风格是匿名内部类）。
2. 新 Activity 要在 `AndroidManifest.xml` 注册，`exported=false`（除 Main）。
3. 资源 ID 全部走 `R.id.*`；`aapt2` 生成 R.java，新增 layout/drawable 后必须重新构建（不要手写 ID）。
4. `findViewById` 用泛型返回（无需强转），API 21 可用（编译期推断）。
5. **ListView 放在 ScrollView 里**：高度必须给具体值或 wrap_content；需要内部滚动时在 `ACTION_DOWN` 里 `requestDisallowInterceptTouchEvent(true)`（见 DetailActivity），否则手势被外层抢走。
6. 图表/自绘 View 的所有尺寸用 `density` 换算，**不要写死 px**（早期写死 24px，在高分屏上小到看不清）。
7. SharedPreferences 别塞无上限数据：history 200 条、snap 每卡 60 份已经是上限，新增字段要考虑体积。
8. 时间轴是禁区：所有图表横轴用"第 N 笔"，不要用时间（用户明确要求过两次）。
9. `Money` 的单位换算：CNY 内部以**分**存、HKD 以**1/10 港元**存，输入/展示要换算（见 `DetailActivity.showUnit/unitName`）。
10. 弹窗里需要输入框时用 `Dialogs.input`，不要自己 `setView`（宽度/按钮行会被破坏）。
11. 构建产物只有一个：`交通卡余额查询.apk`。改完版本号 → `rm -rf build` → `bash build.sh` → `aapt2 dump badging` 校验 → 交付。
12. 未做深色模式（`values-night` 曾存在但已删除），如果要加需要同步改 `BarChart`/`BalanceChart` 的取色（已走 `R.color.*`，基本能自动适配）。

---

## 10. 已知限制

- 卡内交易记录通常只有最近约 20 笔，账本/图表的"历史深度"受卡片本身限制，无法扩展。
- 部分卡（T-Union、银联电子现金）记录里不含"交易后余额"，UI 显示 `余 —`。
- 升级前的旧余额记录没有快照，只能借用最早一份快照并如实标注。
- 读卡成功率依赖机型 NFC 天线位置与卡片；失败时靠 `diag` 文本人工判断。

---

## 11. 待办候选（用户还没定）

1. ~~低余额提醒改造~~ 已在 v2.22 完成：改为 `Forecast` 估算可用天数。
2. 导出/分享账本（CSV 或文本，走系统分享）。
3. 通勤成本统计（平均单程票价、最高/最低、月度估算）。
4. 桌面小部件（2x1：余额 + 最近读取时间）——需要 AppWidget + RemoteViews，工作量中等。
5. 深色模式（跟随系统）。
6. 一键复制卡号/CSN。

优先级建议按上面顺序；做之前先确认，避免做成用户不想要的功能（历史上汇率换算就是做完被撤的）。

---

## 12. 改一次代码的标准动作

```
1. 读 HANDOFF.md + 目标文件
2. 改代码（Java 8 语法，不引第三方）
3. 改 versionCode/versionName（+1 / 小版本 +0.01）
4. rm -rf build && bash build.sh
5. aapt2 dump badging 校验版本 + 权限（必须仍只有 NFC、VIBRATE）
6. 如改了布局/资源：aapt2 dump resources | grep 新 id
7. 交付 apk，并说明改了什么、有什么已知取舍
```
