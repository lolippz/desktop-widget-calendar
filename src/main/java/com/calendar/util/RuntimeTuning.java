package com.calendar.util;

/**
 * 运行时资源调优：把 JVM / AWT 的默认"服务器级"配置收回到桌面挂件该有的量级。
 *
 * <h2>真实占用拆解（实测，2026-09-20）</h2>
 *
 * 挂件在任务管理器里显示约 163 MB，容易让人以为"应用吃内存"。用 {@code jcmd} 拆开看：
 *
 * <pre>
 *   堆实际使用         约 11 MB   ← 应用真正需要的量
 *   堆已提交           254 MB    ← 元凶，-Xms 默认按物理内存 1/64 = 266 MB 预提交
 *   堆上限允许值       4,242 MB  ← -Xmx 默认按物理内存 1/4
 *   Metaspace 使用     21 MB     ← Swing + FlatLaf + sqlite，5600 个类，正常
 *   线程               46        ← 其中 11 个是 G1GC 的 GC 线程
 * </pre>
 *
 * 也就是说：**不是应用吃得多，是 JVM 默认给得多。** 收紧 JVM 参数后实测
 * WorkingSet 从 163 MB 降到 102 MB（−37%），堆使用毫无变化、无 OOM、50 秒无增长。
 *
 * <h2>本类的职责边界</h2>
 *
 * 堆大小与 GC 算法**必须**在 JVM 启动前由 {@code jpackage --java-options} 指定（见 BUILD.md 3.2），
 * 运行时改不了。本类只做两件运行时能做、且有实测收益的事：关掉用不到的子系统、
 * 打印占用便于回归对比。
 *
 * <h2>一个被否掉的优化（勿重复尝试）</h2>
 *
 * 曾考虑收窄 {@code SwingWorker} 的线程池（默认 10 个常驻），但实测：
 * <ul>
 *   <li>撑满 10 个线程与不用时，进程 RSS 只差 <b>5 MB</b>，每个空转线程不足 0.5 MB；</li>
 *   <li>JDK 17 的 {@code SwingWorker} <b>没有</b> {@code executorService} 字段（旧版本才有），
 *       线程池实际由 {@code sun.awt.AppContext} 通过 {@code DO_SUBMIT_KEY} 持有，
 *       想改必须依赖 {@code sun.*} 内部 API 并加 {@code --add-exports}。</li>
 * </ul>
 * 为 5 MB 引入内部 API 依赖不划算，已放弃。
 */
public final class RuntimeTuning {

    private RuntimeTuning() { }

    /**
     * 关掉本项目用不到的子系统，减少常驻内存与首次调用开销。
     *
     * <p>必须在 Swing 组件创建之前调用（{@code Main} 里紧随 {@code Theme.install()} 之后）。
     * 一次调用即可，重复调用无害。
     */
    public static void installProcessHints() {
        // ImageIO 的磁盘缓存是为"反复读同一张大图"准备的；本挂件的图像只有图标与
        // 高 DPI 缩放图，缓存只带来内存占用，没有命中收益。
        System.setProperty("jdk.imageio.useCache", "false");

        // 明确保持双缓冲开启 —— 这是圆角卡片不闪屏的前提，防止被外部参数意外关掉。
        System.setProperty("swing.doubleBuffer", "true");

        // 走 Direct3D 的软件回退路径。挂件内容以文本与圆角矩形为主，
        // D3D 管线带来的显存与驱动层占用不划算（半透明窗口仍需软件合成）。
        System.setProperty("sun.java2d.noddraw", "true");
    }

    /**
     * 把实际资源占用打到 stderr，便于每次发版后回归对比。
     *
     * <p>用 {@code System.err} 是刻意的：jpackage 的启动器在无控制台时不会显示它，
     * 对用户零干扰；而带上 {@code -Ddesktopcalendar.debug=true} 从命令行启动即可看到。
     *
     * <p>输出刻意保持 ASCII：stderr 在重定向到文件时按系统默认编码（中文 Windows 为 GBK）
     * 写出，若打印中文，用 UTF-8 读日志的人会看到乱码。这里只需要数值，不必冒这个风险。
     */
    public static void logFootprint() {
        Runtime runtime = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        System.err.printf(
                "[RuntimeTuning] heap used=%dMB committed=%dMB max=%dMB | threads=%d | cores=%d%n",
                (runtime.totalMemory() - runtime.freeMemory()) / mb,
                runtime.totalMemory() / mb,
                runtime.maxMemory() / mb,
                Thread.activeCount(),
                runtime.availableProcessors());
    }
}
