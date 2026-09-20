# 桌面日程 · Desktop Widget Calendar

> 一个常驻桌面的日程挂件。农历、节假日、当天日程、股市行情、历史上的今天，一屏扫完。

一个用 Java Swing 写的 Windows 桌面挂件。它不做"功能齐全的日历软件"——它做的是**挂在桌面角落、扫一眼就够**的那块信息板：今天该做什么、今天是什么日子、今天市场怎么样。

[English](#english) · 简体中文

---

## 下载安装（普通用户）

**不需要安装 Java。** 运行时已经打包进去，下载解压双击即可用。

| 平台 | 下载 |
|---|---|
| Windows 10 / 11（64 位） | **[⬇ 下载最新版](https://github.com/lolippz/desktop-widget-calendar/releases/latest)** |

1. 下载 `DesktopCalendar-<版本>-win-x64.zip`
2. 解压到任意目录（例如 `D:\软件\`）
3. 双击解压出来的 `DesktopCalendar.exe`

> ⚠️ **不要只把 `DesktopCalendar.exe` 单独拷出来。** 它必须和同目录下的
> `app`、`runtime` 两个文件夹放在一起，否则无法启动。

数据保存在 `%APPDATA%\DesktopCalendar\`，想彻底卸载直接删掉这个文件夹即可，
不会在注册表留残留。

想从源码构建、或参与开发，见下面的[快速开始](#快速开始)。

---

## 功能特性

### 日程

- 新建 / 编辑 / 删除日程：标题、地点、开始与结束时间、备注
- 重复规则：不重复、每天、每周、每月、每年，可设重复截止日期
- 提醒：可设不提醒或提前若干分钟，到点弹系统托盘气泡
- 勾选完成，已完成项在列表里弱化显示
- 冲突提示：结束时间早于开始时间、重复截止早于日程本身等会在保存前拦下

### 日历

- 农历日期、节气
- 中国法定节假日与调休，用底色区分（放假红、调休蓝），不需要额外配置
- 今日模式 / 月历模式一键切换

### 桌面挂件

- **拖动**移动，**八方向**拖边缘缩放
- **贴边吸附**：靠近屏幕边缘 24px 内自动贴齐
- **透明度**六档（100 / 90 / 80 / 70 / 60 / 50），默认 92%
- **总在最前**可开关
- 关闭窗口后缩到**系统托盘**，提醒照常工作
- **单实例保护**：重复启动会激活已有窗口，不会开出第二个

### 行情与资讯（可选开关）

- **行情条**：纳斯达克 100、上证指数、深证成指，涨红跌绿，涨跌幅始终带 `+/-` 号
- **自选股**：最多 20 个，支持沪市 / 深市 / 北交所 / 美股 / 港股
- **详情弹窗**：指数、自选股、财经要闻三段式
- **历史上的今天**：按年份倒序，可点击跳转来源
- **刷新策略**：交易时段 60 秒一次，休市 30 分钟一次；窗口隐藏到托盘或上一轮请求未返回时不发请求

---

## 界面

从上到下的信息层级（示意，非截图）：

```
┌────────────────────────────────────┐
│  2026 年 9 月 20 日  周日            │
│  农历八月初十 · 白露                 │  ← 今日卡片
├────────────────────────────────────┤
│  ● 纳指100 29644 +0.67%  上证 3912 … │  ← 行情条，可关
├────────────────────────────────────┤
│  10:00  产品评审会   3 楼会议室      │
│  14:00  与客户电话会议               │  ← 当天日程，主体
│  17:30  整理本周周报                 │
├────────────────────────────────────┤
│  历史上的今天 · 11 条事件         ›  │  ← 可关
└────────────────────────────────────┘
```

关掉行情条和历史入口后，界面与最初版本**逐像素一致**——附加内容不占用日程列表的空间预算。

---

## 环境要求（从源码构建）

> 只是使用的话**不需要**看这一节 —— 直接下[发布包](#下载安装普通用户)即可。

| 项 | 要求 |
|---|---|
| 操作系统 | **Windows**（主目标平台，见[已知限制](#已知限制)） |
| JDK | **17 或更高**（代码使用 `record` 与 `switch` 箭头语法） |
| 构建工具 | Maven 3.8+，或任意能编译 Java 17 的方式 |

---

## 快速开始

### 方式一：Maven 打包成可执行 jar（推荐）

```bash
git clone https://github.com/lolippz/desktop-widget-calendar.git
cd desktop-widget-calendar
mvn clean package
```

产物是自带全部依赖的 fat jar，双击或命令行运行：

```bash
java -jar target/desktop-calendar-1.0.0-jar-with-dependencies.jar
```

### 方式二：在 IDE 里直接运行

用 IntelliJ IDEA 打开项目目录（它会被识别为 Maven 工程，自动拉取依赖），然后运行主类：

```
com.calendar.system.Main
```

### 方式三：没有 Maven 时用 javac

依赖只有三个 jar，手工下载后即可编译：

```bash
javac -encoding UTF-8 \
      -cp "flatlaf-3.5.1.jar;lunar-1.7.7.jar;sqlite-jdbc-3.41.2.1.jar" \
      -d out/classes $(find src/main/java -name "*.java")

java -cp "out/classes;flatlaf-3.5.1.jar;lunar-1.7.7.jar;sqlite-jdbc-3.41.2.1.jar" \
     com.calendar.system.Main
```

> **必须显式指定 `-encoding UTF-8`**。源码里有大量中文字面量，在中文 Windows 的默认 GBK 环境下不加这个参数会编译出乱码。

---

## 使用说明

### 右键菜单

在挂件任意空白处右键：

| 菜单项 | 作用 |
|---|---|
| 今天 / 回到今天 | 从其他日期跳回今天 |
| 显示行情条 | 开关顶部行情条 |
| 显示历史上的今天 | 开关底部历史入口 |
| 管理自选股… | 增删自选股 |
| 贴边吸附 | 开关自动贴边 |
| 透明度 | 六档调节 |
| 总在最前 | 开关窗口置顶 |
| 退出 | 真正退出（关闭按钮只是缩到托盘） |

### 添加自选股

右键 → `管理自选股…`，输入代码后回车。支持四种写法：

| 输入 | 识别为 | 规则 |
|---|---|---|
| `600519` | 沪市 | 6 或 9 开头 |
| `300750` | 深市 | 0 或 3 开头 |
| `830799` | 北交所 | 4 或 8 开头 |
| `AAPL` | 美股 | 纯字母 |
| `sh000001` | 指数 | 带市场前缀 |

> `000001` 既是上证指数也是平安银行。这里按**个股**解释——要加指数请写全 `sh000001`。

---

## 数据存储

全部数据都在本机，不上传任何服务器：

| 内容 | 位置 |
|---|---|
| 日程数据库 | `%APPDATA%\DesktopCalendar\calendar.db` |
| 单实例锁 | `%APPDATA%\DesktopCalendar\app.lock` |
| 窗口位置、透明度、开关状态、自选股 | 注册表 `HKCU\Software\JavaSoft\Prefs\DesktopCalendar` |
| 历史事件缓存 | `%APPDATA%\DesktopCalendar\history-yyyy-MM-dd.json` |

两个可选的启动参数（主要给测试用）：

```bash
# 换数据目录
-Ddesktopcalendar.dataDir=D:\tmp\cal-data

# 换首选项节点，避免污染真实配置
-Ddesktopcalendar.prefsPath=/DesktopCalendarTest
```

---

## 项目结构

```
src/main/java/com/calendar/
├── system/     Main —— 启动入口：单实例锁 → 数据迁移 → 主题 → 主窗口
├── data/       AppPaths（路径与单实例锁）、ScheduleStore（SQLite 读写）
├── model/      Schedule、Repeat、RemindOption、DayMeta、Quote、NewsItem、HistoryEvent
├── service/    LunarService（农历节气）
│               ReminderService（提醒调度）
│               QuoteService（行情，双数据源）
│               NewsService（财经要闻，双数据源）
│               HistoryService（历史上的今天，按日缓存）
│               MarketClock（交易时段判定）
│               WatchlistStore（自选股持久化）
├── ui/         CalendarWindow（主窗口，组装一切）
│               CalendarGrid（月历）、AgendaPanel（日程列表）、TodayCard（今日卡片）
│               ScheduleEditor（日程编辑）、QuoteTicker（行情条）、HistoryStrip（历史入口）
│               MarketDialog、HistoryDialog、WatchlistDialog（三个弹窗）
│               Theme（配色与尺寸）、WindowGeometry（拖拽与缩放）、ScrollableColumn、RoundedPanel
└── util/       Json（零依赖 JSON 解析）、Net（HTTP 统一出口）、Texts（文案）
```

共 34 个源文件。分层原则是 **`ui` 不直接碰网络与数据库**，一律经过 `service`。

---

## 技术栈

| 依赖 | 版本 | 用途 | 协议 |
|---|---|---|---|
| [FlatLaf](https://github.com/JFormDesigner/FlatLaf) | 3.5.1 | Swing 现代外观 | Apache-2.0 |
| [lunar](https://github.com/6tail/lunar-java) | 1.7.7 | 农历、节气、法定节假日与调休 | MIT |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | 3.41.2.1 | 本地数据库驱动 | Apache-2.0 |

JSON 解析没有引入第三方库——`util/Json.java` 是一个约 200 行的递归下降解析器，只为了不让使用者为了改一行代码去动 `pom.xml`。

---

## 数据来源与免责声明

行情与资讯来自第三方**免费公开接口**，非官方授权，随时可能变更或失效：

| 内容 | 主源 | 备源 |
|---|---|---|
| 行情 | 新浪财经 `hq.sinajs.cn` | 腾讯财经 `proxy.finance.qq.com` |
| 财经要闻 | 东方财富 `np-listapi` | 新浪 `feed.mix.sina.com.cn` |
| 历史上的今天 | `tmini.net` | 本地按日缓存 |

> **免责声明**：本项目仅用于个人学习与技术交流。行情数据存在延迟，准确性不做任何保证，**不构成任何投资建议**。因使用本软件产生的任何决策与后果，由使用者自行承担。数据源接口的可用性不受本项目控制。

---

## 已知限制

- **主要面向 Windows**。用到了系统托盘、`%APPDATA%` 与微软雅黑字体，macOS / Linux 未做验证，直接运行可能出现字体回退或托盘不可用。
- **"历史上的今天"只支持当天**。上游接口没有日期参数，选中其他日期时该区块会自动隐藏（不拿今天的数据冒充）。
- **节假日数据有硬截止**。`lunar` 1.7.7 的法定节假日与调休数据覆盖到 2026 年底，2027-01-01 之后需要升级该依赖。
- **`sqlite-jdbc` 版本偏旧**。3.41.2.1 命中 CVE-2023-32697（JDBC URL 注入）。本项目的连接串由本地路径拼成、不含任何外部输入，实际风险极低；介意的话可以把依赖升到更新版本。
- **行情依赖第三方免费接口**，不保证长期可用。接口改版后需要更新 `QuoteService` / `NewsService` 的解析逻辑。
- 编译时会出现若干 `[serial]` 警告（Swing 子类未声明 `serialVersionUID`），无害。

---

## 参与贡献

欢迎提 Issue 与 Pull Request。改动前建议先了解两条约定：

1. **界面文案与代码注释用中文**，这是项目的一致风格。
2. **改动解析逻辑后请实测**。第三方接口字段错位一位，接口照样返回 200、界面照样显示，只是数字全错——这类问题只能靠"数值是否落在常识区间"的断言发现（例如纳指 100 的价格必须落在 `[5000, 60000]`）。

---

## 开源协议

[MIT](LICENSE) © 2026 lolippz

---

<a name="english"></a>

## English

**Desktop Widget Calendar** is a lightweight always-on-desktop calendar widget for Windows, built with Java Swing.

It shows what you need at a glance — today's schedule, lunar date and Chinese public holidays, stock market indices, and "this day in history" — without getting in your way.

**Highlights**

- Today / Month view toggle, with lunar dates, solar terms and Chinese statutory holidays
- Schedules with repeat rules (daily / weekly / monthly / yearly) and reminders via system tray
- Draggable, resizable with 8-direction edges, snaps to screen edges, 6 opacity levels, always-on-top
- Minimizes to the system tray; single-instance protection
- Optional market ticker: NASDAQ-100, SSE Composite, SZSE Component, plus a personal watchlist (up to 20 symbols) and financial news
- Market data refreshes every 60s during trading hours, every 30min otherwise

**Download (no Java required)**

Grab the latest prebuilt package — the runtime is bundled, just unzip and run:

**https://github.com/lolippz/desktop-widget-calendar/releases/latest**

> ⚠️ Don't copy `DesktopCalendar.exe` out on its own — it needs the `app` and
> `runtime` folders sitting next to it.

**Build from source** — Windows, JDK 17+, Maven 3.8+ (or plain `javac`).

```bash
git clone https://github.com/lolippz/desktop-widget-calendar.git
cd desktop-widget-calendar
mvn clean package
java -jar target/desktop-calendar-1.0.0-jar-with-dependencies.jar
```

All data stays local (`%APPDATA%\DesktopCalendar`). Market data comes from third-party public endpoints and is **for personal reference only — not investment advice**.

Licensed under the [MIT License](LICENSE).
