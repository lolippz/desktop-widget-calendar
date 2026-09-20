# 构建与打包说明

本项目产出两种东西，用途不同：

| 产物 | 用途 | 使用者需要装 Java 吗 |
| --- | --- | --- |
| `desktop-calendar-1.0.0-jar-with-dependencies.jar` | 命令行 `java -jar` 运行 | 需要 |
| `DesktopCalendar-1.0.0-win-x64.zip` | 免安装版，解压双击 EXE 即用 | **不需要**（运行时已内置） |

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

产物：`target/desktop-calendar-1.0.0-jar-with-dependencies.jar`

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
cp target/desktop-calendar-1.0.0-jar-with-dependencies.jar target/jpackage-input/
```

### 3.2 执行 jpackage

```bash
jpackage \
  --type app-image \
  --name DesktopCalendar \
  --input target/jpackage-input \
  --main-jar desktop-calendar-1.0.0-jar-with-dependencies.jar \
  --main-class com.calendar.system.Main \
  --dest target/dist \
  --app-version 1.0.0 \
  --vendor lolippz \
  --description "Desktop calendar widget with lunar calendar and stock quotes" \
  --icon src/main/resources/icon.ico \
  --java-options "-Dfile.encoding=UTF-8" \
  --add-modules java.base,java.desktop,java.net.http,java.prefs,java.sql,java.naming,java.logging,java.management,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs
```

产物结构：

```
target/dist/DesktopCalendar/
├── DesktopCalendar.exe      启动器（约 440 KB）
├── app/                     应用 jar 与配置
│   ├── desktop-calendar-1.0.0-jar-with-dependencies.jar
│   └── DesktopCalendar.cfg
└── runtime/                 精简后的 Java 运行时
```

### 关于两个关键参数

**`--java-options "-Dfile.encoding=UTF-8"` 不能省。**
JDK 17 在中文 Windows 上默认字符集是 GBK，本项目源码里有大量中文字面量与
中文文件读写，不加这个参数会出现乱码。

**`--add-modules` 是体积优化的关键。**
不加它，jpackage 会把**整个 JDK 运行时**塞进去（约 129 MB，总包 143 MB）；
加上之后运行时降到约 93 MB，zip 从 90 MB 降到 46 MB。

模块清单的确定方法：

```bash
jdeps --multi-release 17 --ignore-missing-deps --print-module-deps \
      --class-path target/desktop-calendar-1.0.0-jar-with-dependencies.jar \
      target/desktop-calendar-1.0.0-jar-with-dependencies.jar
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

---

## 四、压缩成发布包

把 `DesktopCalendar` 目录和一份使用说明一起打包：

```
DesktopCalendar-1.0.0-win-x64.zip
├── DesktopCalendar/          解压后直接双击里面的 EXE
│   ├── DesktopCalendar.exe
│   ├── app/
│   └── runtime/
└── README.txt                使用说明（UTF-8 带 BOM，保证记事本不乱码）
```

Windows PowerShell：

```powershell
Compress-Archive -Path target\dist\DesktopCalendar -DestinationPath target\dist\DesktopCalendar-1.0.0-win-x64.zip
```

> 注意：`Compress-Archive` 处理中文路径时偶有编码问题；
> 若遇到，改用 7-Zip 或 Python 的 `zipfile` 模块。

---

## 五、发布到 GitHub Release

1. 打开 `https://github.com/<用户名>/<仓库名>/releases/new`
2. **Choose a tag** 填 `v1.0.0`，点 `Create new tag: v1.0.0`
3. **Release title** 填 `v1.0.0`
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
cp target/desktop-calendar-1.0.0-jar-with-dependencies.jar target/jpackage-input/

# 3. 打包（模块清单见上文）
jpackage --type app-image --name DesktopCalendar \
  --input target/jpackage-input \
  --main-jar desktop-calendar-1.0.0-jar-with-dependencies.jar \
  --main-class com.calendar.system.Main \
  --dest target/dist --app-version 1.0.0 --vendor lolippz \
  --icon src/main/resources/icon.ico \
  --java-options "-Dfile.encoding=UTF-8" \
  --add-modules java.base,java.desktop,java.net.http,java.prefs,java.sql,java.naming,java.logging,java.management,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs

# 4. 压缩
powershell -Command "Compress-Archive -Path target\dist\DesktopCalendar -DestinationPath target\dist\DesktopCalendar-1.0.0-win-x64.zip -Force"
```
