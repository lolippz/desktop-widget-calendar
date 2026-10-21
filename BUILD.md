# 构建与打包说明

本项目产出两种东西，用途不同：

| 产物 | 用途 | 使用者需要装 Java 吗 |
| --- | --- | --- |
| `desktop-calendar-1.0.2-jar-with-dependencies.jar` | 命令行 `java -jar` 运行 | 需要 |
| `DesktopCalendar-1.0.2-win-x64.zip` | 免安装版，解压双击 EXE 即用 | **不需要**（运行时已内置） |

下面按顺序说明。

---

## 一、环境要求

- **JDK 17**（`jpackage` 需要 JDK 14 及以上，本项目用 17）
  - 验证：`java -version`、`jpackage --version`
- **Maven 3.6+**
  - 没有的话可以先用 IDEA 自带的：`<IDEA安装目录>\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`

> 打包全程**不需要** WiX Toolset，因为用的是 `--type app-image`（免安装目录）。
> 只有做 `.msi` / `.exe` 安装程序才需要 WiX。

---

## 二、构建可执行 JAR

```bash
mvn clean package
```

产物：`target/desktop-calendar-1.0.2-jar-with-dependencies.jar`

这一步由 `pom.xml` 里的 `maven-assembly-plugin` 完成，会把
sqlite-jdbc、FlatLaf、lunar 三个依赖一起打进同一个 jar，
并在 `MANIFEST.MF` 里写好 `Main-Class: com.calendar.system.Main`。

---

## 三、打包成免安装 EXE

### 3.1 准备干净的输入目录

`jpackage --input` 会把该目录下**所有**文件原样复制进包里，
所以不能直接指向 `target/`（里面还有 classes 等中间产物），要单独准备：

```bash
mkdir -p target/jpackage-input
cp target/desktop-calendar-1.0.2-jar-with-dependencies.jar target/jpackage-input/
```

### 3.2 执行 jpackage

```bash
jpackage \
  --type app-image \
  --name DesktopCalendar \
  --input target/jpackage-input \
  --main-jar desktop-calendar-1.0.2-jar-with-dependencies.jar \
  --main-class com.calendar.system.Main \
  --dest target/release \
  --app-version 1.0.2 \
  --vendor 2U \
  --description "Desktop calendar widget with lunar calendar and stock quotes" \
  --icon src/main/resources/icon.ico \
  --java-options "-Xms16m" \
  --java-options "-Xmx128m" \
  --java-options "-XX:+UseSerialGC" \
  --java-options "-XX:MaxMetaspaceSize=64m" \
  --java-options "-XX:ReservedCodeCacheSize=32m" \
  --java-options "-XX:CICompilerCount=2" \
  --java-options "-Xshare:auto" \
  --add-modules java.base,java.desktop,java.net.http,java.prefs,java.sql,java.naming,java.logging,java.management,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs
```

产物结构：

```
target/release/DesktopCalendar/
├── DesktopCalendar.exe      启动器（约 440 KB）
├── app/                     应用 jar 与配置
│   ├── desktop-calendar-1.0.2-jar-with-dependencies.jar
│   └── DesktopCalendar.cfg
└── runtime/                 精简后的 Java 运行时
```

> **输出目录不要复用。** 每次发版换一个新目录名（本项目用 `target/release`）。
> 如果某个输出目录曾被"被文件锁打断的删除"删过一半，它看起来还在、实际已损坏，
> 症状是启动时报 `FileNotFoundException: ...\runtime\lib\tzdb.dat` —— 极容易被
> 误判成代码问题。构建前先确认没有实例在运行，否则 `mvn clean` 就会留下这种半删目录。

### 3.3 内存参数：为什么必须显式限制

**不加这些参数，挂件在任务管理器里会显示 145 MB；加上之后是 102 MB（降 30%）。**

原因不是应用吃得多，而是 **JVM 默认按物理内存定参数**，桌面挂件被当成了服务器。
用 `jcmd` 拆开一次真实运行（本机 15.8 GB 内存）：

| 指标 | 默认值下的实测 | 说明 |
| --- | --- | --- |
| 堆**实际使用** | **约 11 MB** | 应用真正需要的量，收不收紧都一样 |
| 堆**已提交** | **254 MB** | 元凶：`-Xms` 默认 = 物理内存/64 = 266 MB，一启动就整块提交 |
| 堆**上限** | **4,242 MB** | 另一个元凶：`-Xmx` 默认 = 物理内存/4，等于签了张 4 GB 空头支票 |
| Metaspace 使用 | 21 MB | Swing + FlatLaf + sqlite 共约 5,600 个类，正常，动不了 |
| 线程数 | 46 | 其中 **11 个是 G1GC 的 GC 线程** |

据此定下这组参数：

| 参数 | 作用 | 为什么是这个值 |
| --- | --- | --- |
| `-Xms16m` | 初始堆 | 实测只用 11 MB，给 16 MB 起步即可，不再预提交 266 MB |
| `-Xmx128m` | 堆上限 | 留 10 倍余量。实测堆用量与该值无关，设大只是浪费承诺 |
| `-XX:+UseSerialGC` | 换串行 GC | 省掉 11 个 GC 线程；单用户挂件没有吞吐量需求 |
| `-XX:MaxMetaspaceSize=64m` | 元空间上限 | 实测用 21 MB，且顺手把 Class space 的保留量从 **1 GB 压到 64 MB** |
| `-XX:ReservedCodeCacheSize=32m` | JIT 代码缓存上限 | 默认 251 MB 是给长时间运行的服务器准备的，挂件到不了这个量级 |
| `-XX:CICompilerCount=2` | JIT 编译器线程数 | **不能设 1**，64 位 JVM 最小为 2，设 1 会直接启动失败 |
| `-Xshare:auto` | 类数据共享 | 开启 CDS，用系统自带归档减少启动时类解析与内存 |

实测对比（同一份 jar、同一台机器、同一稳态采样点，测 WorkingSet 即任务管理器口径）：

| 用例 | WorkingSet | PrivateBytes | 线程数 |
| --- | --- | --- | --- |
| 无任何参数 | 144.6 MB | 199 MB | 47 |
| **完整方案（即上表）** | **101.8 MB** | **78 MB** | **29** |

**回归验证**：完整方案下连续运行 46 秒，两个采样点分别为 104,224 K 与 104,040 K
（**不升反降，无泄漏**），无 OOM。

> 剩下的约 102 MB 里，约 30 MB 是共享的 DLL 页面（`jvm.dll` / `awt.dll` /
> `fontmanager.dll` 等），属于 Java 桌面应用的固定成本，压不下去。
> 如果看到一个 9 MB 左右的 `DesktopCalendar.exe` 进程，那是 jpackage 的启动器壳，正常。

> **踩过的坑：`-XX:CICompilerCount=1` 会让 JVM 拒绝启动**，报
> `CICompilerCount (1) must be at least 2`。64 位 JVM 最少要 2 个 JIT 编译器线程。

> **另一个被否掉的优化**：曾想收窄 `SwingWorker` 的线程池（默认 10 个常驻）。
> 实测撑满 10 线程与不用时进程 RSS 只差 **5 MB**，且 JDK 17 的 `SwingWorker`
> 已没有 `executorService` 字段，要改必须碰 `sun.awt.AppContext` 内部 API。
> 不值得，已放弃。**别再试这条路。**

### 关于两个关键参数

**`--java-options "-Dfile.encoding=UTF-8"` 千万不要加！**

这一点与直觉相反，但本项目**必须**使用系统默认字符集（中文 Windows 上是 GBK）。

原因：AWT 的托盘菜单（`PopupMenu` / `MenuItem`）在 Windows 上是**原生 Win32 菜单**。
Java 把菜单项文本转成字节时走的是 `file.encoding`，而 Win32 的 ANSI 菜单 API
按系统 ANSI 代码页（GBK）解读这些字节。一旦把 `file.encoding` 改成 UTF-8，
字节与解读方式不匹配，菜单项就会全部渲染成方框（缺字形）。

实测对照（同一份菜单代码、同一字体 `Microsoft YaHei UI`，仅改这一个参数）：

| JVM 参数 | 托盘右键菜单渲染 |
| --- | --- |
| 不加（`file.encoding=GBK`） | ✅ 显示日程 / 隐藏日程 / 新建日程 / 总在最前 / 透明度 / 退出 |
| `-Dfile.encoding=UTF-8` | ❌ 全部变成方框 |

那不加会不会让中文文件读写乱码？**不会。** 本项目所有文件与网络 I/O 都显式指定了字符集：

- `HistoryService` 用 `Files.readString/writeString(..., StandardCharsets.UTF_8)`
- `Net` 的 `new String(response.body(), charset)` 显式传 charset
- SQLite 由 sqlite-jdbc 内部按 UTF-8 处理，与默认字符集无关
- `java.util.prefs.Preferences` 内部用 UTF-8 存储

源码里的中文字面量在**编译期**就已按 UTF-8 写进 class 文件（由 `pom.xml` 的
`maven.compiler.encoding` 保证），运行时与默认字符集无关。

> 同理，在 IDEA 里运行时也要检查运行配置的 **VM options**：
> IDEA 会给 Java 运行配置自动加上 `-Dfile.encoding=UTF-8`，
> 需要把它删掉，否则同样出现方框。

**`--add-modules` 是体积优化的关键。**
不加它，jpackage 会把**整个 JDK 运行时**塞进去（约 129 MB，总包 143 MB）；
加上之后运行时降到约 93 MB，zip 从 90 MB 降到 46 MB。

模块清单的确定方法：

```bash
jdeps --multi-release 17 --ignore-missing-deps --print-module-deps \
      --class-path target/desktop-calendar-1.0.2-jar-with-dependencies.jar \
      target/desktop-calendar-1.0.2-jar-with-dependencies.jar
```

`jdeps` 输出 `java.base,java.desktop,java.net.http,java.prefs,java.sql`。
但**它看不到反射和 ServiceLoader 的依赖**，所以在此基础上补了：

| 模块 | 为什么必须补 |
| --- | --- |
| `jdk.crypto.ec` | HTTPS 走椭圆曲线证书需要它，缺了会导致网络请求全部失败 |
| `jdk.localedata` | 中文区域数据。缺了 `Locale.CHINA` 的日期/月份名称会退回英文 |
| `jdk.charsets` | GBK 等扩展字符集 |
| `java.naming` | JDBC 驱动加载路径上可能用到 JNDI |
| `java.logging` | 第三方库普遍使用 `java.util.logging` |
| `java.management` | 部分库会读取 JVM 运行时信息 |
| `jdk.unsupported` | `sun.misc.Unsafe`，老库常用 |
| `jdk.zipfs` | Zip 文件系统 |

> 裁剪模块后**务必实机运行一次 EXE 验证**，确认窗口正常弹出、网络与数据库功能正常。
> 漏掉模块的症状通常是"启动即闪退"，且因为是 GUI 程序看不到任何报错。

### 3.4 验证托盘右键菜单（务必单独验证）

**主窗口正常 ≠ 托盘菜单正常。** 主窗口是 Swing 自己绘制的，托盘菜单是原生 Win32 菜单，
两者走完全不同的渲染路径。上面那条 `-Dfile.encoding` 的坑只会在托盘菜单上暴露出来，
所以每次改动启动参数或打包配置后，都要专门回归这一项：

1. 运行 `DesktopCalendar.exe`
2. 点击窗口关闭按钮（或最小化），程序会收进系统托盘
3. 在托盘图标上**点鼠标右键**
4. 菜单应显示「显示日程 / 隐藏日程 / 新建日程 / 总在最前 / 透明度 / 退出」；
   若显示为方框，说明 `file.encoding` 又被加回来了

**想不靠肉眼验证字符集**，可以用下面这个办法直接在发布包的运行时上跑探针。
jlink 精简镜像里没有 `java.exe`，但同版本 JDK 的 `java.exe` 拷进去就能用
（它会自动加载同目录 `../lib/modules`）：

```bash
JDK="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
RT="target/release/DesktopCalendar/runtime"

# 临时借一个 java.exe 来跑探针
cp "$JDK/bin/java.exe" "$RT/bin/java.exe"
"$RT/bin/java.exe" -cp <探针目录> EncProbe      # 应输出 file.encoding = GBK

# ★ 验证完必须删掉，否则会被打进发布包
rm -f "$RT/bin/java.exe"
```

顺带能确认 `jdk.charsets` 没被裁掉（GBK 由它提供，缺了默认字符集会退化成
`US-ASCII` 之类，症状同样是菜单乱码）。

### 3.5 验证截图的隐私规范（重要）

**凡是走 `Robot.createScreenCapture` 抓的截图，都会把桌面内容一起拍进去。**
本挂件默认 92% 不透明，窗口背后的桌面会**整个合成进图片** —— 你当时开着的聊天窗口、
浏览器、邮件全在里面。肉眼看小图不容易发现，一旦外发就是隐私事故。

本项目曾因此清理掉 53 张历史截图。

#### 首选：离屏渲染

`tools/OffscreenShot.java` 把真实组件树 `paint()` 进内存图，再按指定不透明度
**合成到程序生成的背景**上。窗口级不透明度在视觉上就是"整窗按 alpha 叠加到底层"，
所以合成结果与真实上屏效果一致，但物理上不含任何屏幕像素。

```bash
# 取依赖 classpath（Windows 用 ; 分隔，Linux/macOS 用 :）
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

javac -encoding UTF-8 -cp "target/classes;$(cat target/cp.txt)" \
      -d target/tools tools/OffscreenShot.java

java -cp "target/classes;$(cat target/cp.txt);target/tools" \
     OffscreenShot today 92 out/preview/今日模式.png
```

参数：`OffscreenShot <today|month> <不透明度> <输出.png> [宽x高] [menu]`

菜单也能离屏画 —— `JPopupMenu` 只要 `setInvoker` + `setSize(preferred)` + `doLayout()`
就能直接 `paint()`，不需要显示任何窗口。

#### 万不得已要抓真实屏幕时

Win32 原生菜单（托盘右键菜单）离屏画不出来，只能抓屏。此时截取区域**要紧贴
目标窗口的 `GetWindowRect`**，不要用整屏或大片区域。

#### 交付前做客观检测

圆角窗口的四角在干净图里应该彼此一致；透出桌面的图，四角各自映到不同的桌面内容。
取三处角块各 12×12 的平均色，算两两欧氏距离，`>= 30` 判为泄漏。

> ⚠️ **必须排除右下角**：窗口右下角自带"大小调整手柄"（几条斜线），会让该处块均值
> 偏暗，单这一项就能造出 ~31 的差异，足以把完全干净的离屏渲染图误判成泄漏。
> 所以只比较**左上 / 右上 / 左下**三个角。

---

## 四、压缩成发布包

把 `DesktopCalendar` 目录和一份使用说明一起打包：

```
DesktopCalendar-1.0.2-win-x64.zip
├── DesktopCalendar/          解压后直接双击里面的 EXE
│   ├── DesktopCalendar.exe
│   ├── app/
│   └── runtime/
└── README.txt                使用说明（UTF-8 带 BOM，保证记事本不乱码）
```

Windows PowerShell：

```powershell
Compress-Archive -Path target\release\DesktopCalendar -DestinationPath target\release\DesktopCalendar-1.0.2-win-x64.zip
```

> 注意：`Compress-Archive` 处理中文路径时偶有编码问题；
> 若遇到，改用 7-Zip 或 Python 的 `zipfile` 模块。

---

## 五、发布到 GitHub Release

1. 打开 `https://github.com/<用户名>/<仓库名>/releases/new`
2. **Choose a tag** 填 `v1.0.2`，点 `Create new tag: v1.0.2`
3. **Release title** 填 `v1.0.2`
4. 在描述框里写更新说明
5. 把 zip 拖进最下方的 **Attach binaries** 区域
6. 点 **Publish release**

发布后下载页地址是：

```
https://github.com/<用户名>/<仓库名>/releases/latest
```

可以在 README 顶部加一行引导用户直接去下载页，例如：

```markdown
[⬇ 下载免安装版](https://github.com/lolippz/desktop-widget-calendar/releases/latest)
```

---

## 附：完整命令速查

```bash
# 1. 构建
mvn clean package

# 2. 准备输入目录
mkdir -p target/jpackage-input
cp target/desktop-calendar-1.0.2-jar-with-dependencies.jar target/jpackage-input/

# 3. 打包（模块清单与内存参数见 3.2 / 3.3）
jpackage --type app-image --name DesktopCalendar \
  --input target/jpackage-input \
  --main-jar desktop-calendar-1.0.2-jar-with-dependencies.jar \
  --main-class com.calendar.system.Main \
  --dest target/release --app-version 1.0.2 --vendor 2U \
  --icon src/main/resources/icon.ico \
  --java-options "-Xms16m" \
  --java-options "-Xmx128m" \
  --java-options "-XX:+UseSerialGC" \
  --java-options "-XX:MaxMetaspaceSize=64m" \
  --java-options "-XX:ReservedCodeCacheSize=32m" \
  --java-options "-XX:CICompilerCount=2" \
  --java-options "-Xshare:auto" \
  --add-modules java.base,java.desktop,java.net.http,java.prefs,java.sql,java.naming,java.logging,java.management,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs

# 4. 压缩
powershell -Command "Compress-Archive -Path target\release\DesktopCalendar -DestinationPath target\release\DesktopCalendar-1.0.2-win-x64.zip -Force"
```
