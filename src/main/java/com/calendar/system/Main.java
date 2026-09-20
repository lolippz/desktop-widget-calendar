package com.calendar.system;

import com.calendar.data.AppPaths;
import com.calendar.ui.CalendarWindow;
import com.calendar.ui.Theme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * 进程入口：单实例锁 → 旧库迁移 → 外观安装 → 主窗口。
 *
 * <p>顺序不能调换：单实例锁必须在建库之前拿到，否则两个实例会同时执行迁移；
 * 外观必须在任何 Swing 组件创建之前安装，否则已创建的组件不会套用新默认值。
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (!AppPaths.acquireSingleInstanceLock()) {
                    JOptionPane.showMessageDialog(null, "桌面日程已在运行中，请在系统托盘中查看。",
                            AppPaths.APP_NAME, JOptionPane.INFORMATION_MESSAGE);
                    System.exit(0);
                }
                AppPaths.migrateLegacyDatabase();
                Theme.install();
                new CalendarWindow().setVisible(true);
            } catch (Throwable failure) {
                // 构造过程中的异常原本只会打到 stderr，用户看到的是"双击了没反应"。
                failure.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "启动失败：" + failure.getClass().getSimpleName()
                                + (failure.getMessage() == null ? "" : "\n" + failure.getMessage()),
                        AppPaths.APP_NAME, JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
