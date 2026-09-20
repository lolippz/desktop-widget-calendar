package com.calendar.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 应用级路径与进程级资源。
 *
 * <p>数据目录固定到 {@code %APPDATA%\DesktopCalendar}，不再跟随工作目录——
 * 初版用相对路径建库，导致"换目录启动就看不到日程"。
 *
 * <p>可用 {@code -Ddesktopcalendar.dataDir=<路径>} 覆盖数据目录，便于测试。
 */
public final class AppPaths {

    public static final String APP_NAME = "桌面日程";
    public static final String APP_DIR_NAME = "DesktopCalendar";
    public static final String DATA_DIR_PROPERTY = "desktopcalendar.dataDir";

    /**
     * 首选项节点路径。
     *
     * <p>必须写死：初版用的是默认包下的 {@code DesktopCalendar}，
     * {@code userNodeForPackage} 会解析成 {@code /DesktopCalendar}。重构进包之后
     * 节点会变成 {@code /com/calendar/ui/CalendarWindow}，用户已保存的窗口位置与
     * "总在最前"设置会凭空丢失。
     *
     * <p>可用 {@code -Ddesktopcalendar.prefsPath=<路径>} 覆盖，便于测试隔离——
     * 首选项是跨进程持久化的，测试写进去的值会污染真实配置。
     */
    public static final String PREFERENCES_PATH =
            System.getProperty("desktopcalendar.prefsPath", "/DesktopCalendar");

    public static final Path DATA_DIR;
    public static final String DATABASE_URL;

    private static FileChannel lockChannel;
    private static FileLock instanceLock;

    static {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        Path dir;
        if (override != null && !override.isBlank()) {
            dir = Path.of(override);
        } else {
            String base = System.getenv("APPDATA");
            if (base == null || base.isBlank()) {
                base = System.getProperty("user.home");
            }
            dir = Path.of(base, APP_DIR_NAME);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法创建数据目录：" + dir, exception);
        }
        DATA_DIR = dir.toAbsolutePath();
        // SQLite JDBC 接受正斜杠，避免 Windows 反斜杠在 URL 中被转义。
        DATABASE_URL = "jdbc:sqlite:" + DATA_DIR.resolve("calendar.db").toString().replace('\\', '/');
    }

    private AppPaths() { }

    /** 单实例保护：拿不到锁说明已有实例在运行。 */
    public static boolean acquireSingleInstanceLock() {
        try {
            Path lockFile = DATA_DIR.resolve("app.lock");
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return false;
            }
            lockChannel = channel;
            instanceLock = lock;
            return true;
        } catch (IOException exception) {
            // 锁文件异常不应致命，放行总比打不开好。
            return true;
        }
    }

    /** 初版把库建在工作目录下，升级后把旧库搬到新位置，避免用户以为日程丢了。 */
    public static void migrateLegacyDatabase() {
        Path legacy = Path.of(System.getProperty("user.dir"), "calendar.db");
        Path target = DATA_DIR.resolve("calendar.db");
        if (!Files.exists(legacy) || Files.exists(target)) return;
        try {
            Files.move(legacy, target);
        } catch (IOException exception) {
            // 迁移失败不阻断启动，旧库仍在原处。
        }
    }

    /** 释放锁与文件句柄，供测试与优雅退出使用。 */
    public static void releaseSingleInstanceLock() {
        try {
            if (instanceLock != null) instanceLock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException exception) {
            // 退出路径上失败无可挽回，忽略。
        } finally {
            instanceLock = null;
            lockChannel = null;
        }
    }
}
