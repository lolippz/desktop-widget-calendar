package com.calendar.service;

import com.calendar.data.ScheduleStore;
import com.calendar.model.Schedule;

import javax.swing.Timer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 常驻提醒调度：每 30 秒扫一次当天的日程，命中则通过 {@link Notifier} 外发。
 *
 * <p>通知出口是注入的，所以本类不依赖系统托盘，可以在无界面环境下直接测试。
 */
public final class ReminderService {

    /** 通知出口。界面层注入托盘气泡，测试时可注入收集器。 */
    public interface Notifier {
        void notify(String title, String body);
    }

    private static final int POLL_INTERVAL_MILLIS = 30_000;

    /** 触发窗口延伸到日程开始后 5 分钟，容忍休眠唤醒导致的 tick 缺失。 */
    private static final int GRACE_MINUTES = 5;

    private final ScheduleStore store;
    private final Notifier notifier;
    private final Timer ticker;

    /** 已弹过的提醒，键为 {@code 日期#日程 id}；跨天自动清空。 */
    private final Set<String> firedReminders = new HashSet<>();
    private LocalDate cursorDate = LocalDate.now();

    public ReminderService(ScheduleStore store, Notifier notifier) {
        this.store = store;
        this.notifier = notifier;
        this.ticker = new Timer(POLL_INTERVAL_MILLIS, event -> tick());
    }

    public void start() {
        ticker.start();
    }

    public void stop() {
        ticker.stop();
    }

    /** 扫描一次。公开以便测试时直接驱动，不必等待定时器。 */
    public void tick() {
        LocalDate nowDate = LocalDate.now();
        if (!nowDate.equals(cursorDate)) {
            cursorDate = nowDate;
            firedReminders.clear();
        }
        LocalTime nowTime = LocalTime.now();
        for (Schedule schedule : ScheduleStore.schedulesOn(
                store.loadSchedulesInRange(nowDate, nowDate), nowDate)) {
            if (!shouldFire(schedule, nowTime)) continue;
            if (!firedReminders.add(nowDate + "#" + schedule.id())) continue;
            String body = schedule.timeRangeText()
                    + (schedule.location().isBlank() ? "" : "　" + schedule.location());
            notifier.notify(schedule.title(), body);
        }
    }

    /**
     * 判断此刻是否应当为这条日程弹提醒。
     *
     * <p>纯函数，不依赖当前时间与已弹记录，因此可以脱离定时器直接测试。
     */
    public static boolean shouldFire(Schedule schedule, LocalTime nowTime) {
        if (schedule.allDay() || schedule.remindMinutes() < 0 || schedule.startTime() == null) {
            return false;
        }
        LocalTime trigger = schedule.startTime().minusMinutes(schedule.remindMinutes());
        return !nowTime.isBefore(trigger)
                && nowTime.isBefore(schedule.startTime().plusMinutes(GRACE_MINUTES));
    }
}
