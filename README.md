# 交通卡余额查询（NFC）

一个只读、免登录、纯本地的安卓 App：把韩国的 NFC 交通卡贴到手机背面，直接读出卡内余额、最近交易，并把每次读卡记成本地账本。参考 TapCheck 这类应用的思路实现。

- 无 Gradle、无 AndroidX、无第三方库，纯 Java 8 + 原生 View
- 权限只有 `NFC` 和 `VIBRATE`，**没有 INTERNET 权限**，数据不出手机
- minSdk 21（Android 5.0）/ targetSdk 34，当前版本 **2.22**

## 安装 / 获取

- **直接下载 APK**：[Releases](https://github.com/Poloair/cu-nfc-balance/releases/latest) → `cu-nfc-balance-2.22.apk`（已签名，v1/v2/v3 校验通过）
- **源码**：`git clone https://github.com/Poloair/cu-nfc-balance.git`，构建见下方「自己编译」
- 首次安装需在系统设置里允许「安装未知来源应用」

## 能读什么卡

遵循韩国 **KS X 6924 / One Card All Pass** 标准、把余额写在卡内芯片的预付交通卡。采用动态探测 + 已知 AID 遍历，覆盖主流卡种：

- **T-money**（含 Snapper Plus、T-money Go 实体卡）
- **Cashbee / eB / EZL**
- **MOIBA / Hanpay（하나로PLUS）**
- **K-Cash**
- **Rail+（레일플러스）/ Korail 相关**
- **One Card All Pass 全国通用卡**（전국호환）
- **WOWPASS / Korea Tour Card** 等基于上述标准的游客联名卡

读卡器会先尝试 Config DF（`A0000004520001`）动态读取卡片声明的主应用 AID 和余额命令（符合 One Card All Pass 的卡自动适配），再遍历全部已知 AID 兜底（Config DF 不在 Cashbee/Snapper 上）。诊断日志会显示每张卡实际匹配到的 AID 和品牌名。日系 FeliCa（Suica / 八达通）走另一条分支。

**读不了的卡**（卡里根本没有余额数据）：

- 后付费交通卡、带交通功能的信用卡/借记卡
- 手机里的电子交通卡（Apple Wallet / 手机 T-money）
- CU 的**实体礼品卡 / 储值卡**——那类卡余额存在服务器上，卡里没有，NFC 读不到

## 功能

**① 读卡（首页）**
贴卡读余额、卡种、卡号、读取时间，并显示相对上次的涨跌。

**② 卡片页**
本机所有读过的卡，长按可以改备注、上移/下移排序，点进去是单卡详情：
- 余额走势折线图（横轴是读卡次数，不用时间）+ 余额记录列表
- 余额记录默认摆 3 条半，多的折叠进固定高度的滚轮，不再无限平铺
- **每次读卡各存一份交易快照**，点某条余额记录看到的就是「那一次读卡」的明细，不会串到最新一次；长按可删除单条记录
- 备注名、**可用天数提醒**（见下）

**③ 使用统计（二级页）**
收入（充值）/ 支出（消费）/ 净变化 / 笔数四个汇总 + 单笔金额柱状图 + 逐笔收支明细。点柱子会亮起光柱并在柱顶显示这笔的具体金额。

**④ 设置**
NFC 状态（未开启可直接跳系统开关）、支持的卡种、隐私说明、清空数据、版本号。

**可用天数提醒**：不再用固定金额阈值，而是按最近消费估算日均支出，算出余额还能用几天，低于你设的天数（如 3 天）就在读卡后提醒充值。数据来源是卡内交易记录，不够时退回本机余额历史，估算依据会显示在弹窗里（`日均 ₩2,300（卡内 10 天 12 笔消费）`）。

## 怎么用

1. 手机需要支持 NFC（Android 5.0 以上），并在系统设置里打开 NFC，App 会检测并引导。
2. 安装 `交通卡余额查询.apk`。
3. 打开 App，把卡片贴到手机背面 NFC 感应区（通常在背部中上部），保持 1-2 秒，别和其他卡叠在一起。
4. 读到余额后会有震动提示；点「查看消费记录」可看卡内保存的最近交易（卡内通常只存最近约 20 条）。

读不出来时：厚手机壳/磁吸壳先取下，卡片在背面慢慢挪动找感应点；确认不是上面列出的「读不了的卡」。

## 隐私设计

- **只读不写**：不充值、不扣款、不修改卡片任何数据。
- **不联网**：App 没有 INTERNET 权限，不会上传任何数据。
- **卡号**：只存本机；主键是 UID 的 SHA-256 哈希。
- **纯本地**：余额记录、交易快照存在本机 SharedPreferences，设置页可一键清空。
- **无账号**：不注册、不登录、不要手机号。
- 独立应用，与 T-money、Cashbee、BGF Retail、CU 等发卡/运营机构无关联。

## 仓库结构

```
cu-nfc-balance/
├── AndroidManifest.xml      # 版本号、权限、Activity、NFC TECH_DISCOVERED
├── build.sh                 # 免 Android Studio 构建脚本
├── src/com/cubalance/app/   # 19 个 Java 文件（约 4.5k 行）
│   ├── MainActivity         # 首页：读卡 + 汇总入口
│   ├── CardsActivity        # 卡片列表（备注 / 排序）
│   ├── DetailActivity       # 单卡详情（图表 / 余额记录 / 卡片管理）
│   ├── StatsActivity        # 使用统计（汇总 / 柱状图 / 收支明细）
│   ├── TripsActivity        # 消费记录（可指定「某一次读卡」的快照）
│   ├── SettingsActivity     # 设置
│   ├── CardReader           # NFC 读卡与 APDU 解析
│   ├── Store                # SharedPreferences 本地存储
│   ├── Forecast             # 预计可用天数估算
│   └── ...                  # 图表、底栏模糊、弹窗、金额格式化等
├── res/                     # 布局 / drawable / 值资源
├── assets/                  # 卡种说明等
└── 交通卡余额查询.apk        # 已签名的可安装包
```

## 构建

不用 Android Studio，`build.sh` 走 aapt2 → ECJ → d8 → aapt2 link → 注入 dex → zipalign → apksigner 的流程。需要准备一个工具目录，包含：

```
tools/
├── jdk-17*/bin/java(.exe), keytool(.exe)   # 任意 JRE 17
├── ecj.jar                                 # Eclipse 编译器
├── platform/android.jar                    # Android 34 platform
└── build-tools/
    ├── aapt2(.exe)  zipalign(.exe)
    └── lib/d8.jar  lib/apksigner.jar
```

放好后：

```bash
# 默认找项目同级的 ../tools
bash build.sh

# 工具在别处时
ANDROID_TOOLS=/path/to/tools bash build.sh

# Python 只用来自动清 build 目录和注入 dex，可指定
PYTHON=python3 bash build.sh
```

产物是根目录的 `交通卡余额查询.apk`，脚本最后会用 `apksigner verify` 打印 v1/v2/v3 校验结果。

两个坑：
- **改代码前先 `rm -rf build`**，否则旧的 `.class` 会混进 dex，出现「改了没生效」。
- 只能在 Windows/Git Bash 或 Linux 上跑；`w()` 会自动处理 `cygpath` 路径转换与 `.exe` 后缀。

## 技术说明

读卡协议来自开源项目 [Metrodroid](https://github.com/metrodroid/metrodroid)（GPLv3）对 KS X 6924 的实现：依次 SELECT 已知 AID，再发送专有命令 `90 4C 00 00 04`（CLA=0x90, INS=0x4C, Le=4）读余额，返回 4 字节大端序韩元金额，末两字节 `90 00` 表示成功。

更细的接手说明（数据 key、页面跳转、UI 约定、历史版本演进与已撤销功能）见 [HANDOFF.md](HANDOFF.md)。

## 许可

本应用代码遵循 **GPLv3**（读卡协议实现派生自 Metrodroid，GPLv3）。见 [LICENSE](LICENSE)。

自行构建的包与仓库里的 `交通卡余额查询.apk` 签名不同，覆盖安装会失败，需要先卸载旧版。
