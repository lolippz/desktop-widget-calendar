package com.calendar.data;

import com.calendar.model.Repeat;
import com.calendar.model.Schedule;
import com.calendar.util.Texts;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code schedule} 表的建表、旧库迁移与增删改查。
 *
 * <p>全部 SQL 收敛在这里，界面层不出现任何 JDBC 语句。数据库异常不在这里弹窗，
 * 而是交给构造时注入的 {@code errorHandler}，这样本类可以被无界面地测试。
 */
public final class ScheduleStore {

    private static final String SELECT_COLUMNS =
            "id,schedule_date,title,location,start_time,end_time,repeat_type,"
                    + "repeat_until,remind_minutes,description,all_day";

    private final Consumer<SQLException> errorHandler;

    public ScheduleStore(Consumer<SQLException> errorHandler) {
        this.errorHandler = errorHandler;
    }

    // ------------------------------------------------------------ 建表与迁移

    public void createSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS schedule ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, schedule_date TEXT NOT NULL, title TEXT NOT NULL,"
                + "location TEXT DEFAULT '', start_time TEXT DEFAULT '', end_time TEXT DEFAULT '',"
                + "repeat_type TEXT DEFAULT 'NONE', repeat_until TEXT DEFAULT '',"
                + "remind_minutes INTEGER NOT NULL DEFAULT -1, description TEXT DEFAULT '',"
                + "all_day INTEGER NOT NULL DEFAULT 0)";
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            migrateSchema(connection);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_schedule_date ON schedule(schedule_date)");
        } catch (SQLException exception) {
            throw new IllegalStateException("无法初始化 SQLite 数据库：" + exception.getMessage(), exception);
        }
    }

    private static Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(AppPaths.DATABASE_URL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=3000");
        }
        return connection;
    }

    /** 为旧版 calendar.db 补齐字段并转换旧值，不删除任何已有日程。 */
    private static void migrateSchema(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(schedule)")) {
            while (result.next()) {
                columns.add(result.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        addColumnIfMissing(connection, columns, "location", "TEXT DEFAULT ''");
        addColumnIfMissing(connection, columns, "start_time", "TEXT DEFAULT ''");
        addColumnIfMissing(connection, columns, "end_time", "TEXT DEFAULT ''");
        addColumnIfMissing(connection, columns, "repeat_type", "TEXT DEFAULT 'NONE'");
        addColumnIfMissing(connection, columns, "repeat_until", "TEXT DEFAULT ''");
        addColumnIfMissing(connection, columns, "remind_minutes", "INTEGER NOT NULL DEFAULT -1");
        addColumnIfMissing(connection, columns, "description", "TEXT DEFAULT ''");
        addColumnIfMissing(connection, columns, "all_day", "INTEGER NOT NULL DEFAULT 0");

        // 只在目标列为空时才搬运，保证迁移幂等：重复启动不会覆盖用户后来改过的值。
        if (columns.contains("time") && !columnHasValue(connection, "start_time")) {
            splitLegacyTimeColumn(connection);
        }
        if (columns.contains("remind") && !columnHasValue(connection, "remind_minutes", "> 0")) {
            convertLegacyReminders(connection);
        }
        if (hasLegacyRepeatValues(connection)) {
            convertLegacyRepeatTypes(connection);
        }
    }

    private static boolean columnHasValue(Connection connection, String column) throws SQLException {
        return columnHasValue(connection, column, "!= ''");
    }

    private static boolean columnHasValue(Connection connection, String column, String condition)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 FROM schedule WHERE " + column + " IS NOT NULL AND " + column + " "
                             + condition + " LIMIT 1")) {
            return result.next();
        }
    }

    /** 旧的自由文本 time 形如 "09:00 - 10:00"，拆成结构化的开始/结束时刻。 */
    private static void splitLegacyTimeColumn(Connection connection) throws SQLException {
        List<Long> ids = new ArrayList<>();
        List<String> values = new ArrayList<>();
        try (Statement select = connection.createStatement();
             ResultSet result = select.executeQuery("SELECT id, time FROM schedule WHERE time != ''")) {
            while (result.next()) {
                ids.add(result.getLong("id"));
                values.add(result.getString("time"));
            }
        }
        if (ids.isEmpty()) return;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE schedule SET start_time=?, end_time=? WHERE id=?")) {
            for (int i = 0; i < ids.size(); i++) {
                String[] parts = values.get(i).split("-", 2);
                update.setString(1, normalizeTime(parts[0]));
                update.setString(2, parts.length > 1 ? normalizeTime(parts[1]) : "");
                update.setLong(3, ids.get(i));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void convertLegacyReminders(Connection connection) throws SQLException {
        String[][] mappings = {
                {"提前 15 分钟", "15"}, {"提前 1 小时", "60"}, {"提前 1 天", "1440"}
        };
        for (String[] mapping : mappings) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE schedule SET remind_minutes=? WHERE remind=?")) {
                update.setInt(1, Integer.parseInt(mapping[1]));
                update.setString(2, mapping[0]);
                update.executeUpdate();
            }
        }
    }

    private static boolean hasLegacyRepeatValues(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 FROM schedule WHERE repeat_type IN ('每天','每周','每月','每年') LIMIT 1")) {
            return result.next();
        }
    }

    private static void convertLegacyRepeatTypes(Connection connection) throws SQLException {
        String[][] mappings = {
                {"每天", "DAILY"}, {"每周", "WEEKLY"}, {"每月", "MONTHLY"},
                {"每年", "YEARLY"}, {"不重复", "NONE"}
        };
        for (String[] mapping : mappings) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE schedule SET repeat_type=? WHERE repeat_type=?")) {
                update.setString(1, mapping[1]);
                update.setString(2, mapping[0]);
                update.executeUpdate();
            }
        }
    }

    /** 把 "9:5" 之类的自由文本规范成 "09:05"，无法解析则返回空串。 */
    public static String normalizeTime(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        String[] parts = trimmed.split(":");
        if (parts.length != 2) return "";
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return "";
            return String.format("%02d:%02d", hour, minute);
        } catch (NumberFormatException exception) {
            return "";
        }
    }

    private static void addColumnIfMissing(Connection connection, Set<String> columns,
                                           String name, String definition) throws SQLException {
        if (columns.contains(name)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE schedule ADD COLUMN " + name + " " + definition);
        }
    }

    // ------------------------------------------------------------ 查询

    /**
     * 取 [from, to] 区间内所有可能出现的日程（含重复规则的候选行）。
     *
     * <p>重复日程只在未超过截止日时才参与，最终判定交给 {@link #occursOn}。
     */
    public List<Schedule> loadSchedulesInRange(LocalDate from, LocalDate to) {
        List<Schedule> schedules = new ArrayList<>();
        String sql = "SELECT " + SELECT_COLUMNS + " FROM schedule "
                + "WHERE schedule_date <= ? "
                + "  AND (repeat_until IS NULL OR repeat_until = '' OR repeat_until >= ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, to.format(Texts.DATE_FORMAT));
            statement.setString(2, from.format(Texts.DATE_FORMAT));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    schedules.add(toSchedule(result));
                }
            }
        } catch (SQLException exception) {
            errorHandler.accept(exception);
        }
        return schedules;
    }

    /** 从候选集合里筛出真正落在目标日期上的日程。 */
    public static List<Schedule> schedulesOn(List<Schedule> candidates, LocalDate date) {
        List<Schedule> matches = new ArrayList<>();
        for (Schedule schedule : candidates) {
            if (occursOn(schedule, date)) matches.add(schedule);
        }
        return matches;
    }

    /** 判断一条日程是否落在目标日期上（含重复规则展开）。 */
    public static boolean occursOn(Schedule schedule, LocalDate target) {
        LocalDate start = schedule.date();
        if (target.isBefore(start)) return false;
        if (schedule.repeatUntil() != null && target.isAfter(schedule.repeatUntil())) return false;
        return switch (schedule.repeat()) {
            case DAILY -> true;
            case WEEKLY -> start.getDayOfWeek() == target.getDayOfWeek();
            // 31 日创建的"每月"日程，在 30 天的月份落到月末，而不是被跳过。
            case MONTHLY -> start.getDayOfMonth() == target.getDayOfMonth()
                    || (start.getDayOfMonth() > target.lengthOfMonth()
                        && target.getDayOfMonth() == target.lengthOfMonth());
            case YEARLY -> start.getMonthValue() == target.getMonthValue()
                    && start.getDayOfMonth() == target.getDayOfMonth();
            case NONE -> start.equals(target);
        };
    }

    private static Schedule toSchedule(ResultSet result) throws SQLException {
        String startTime = Texts.valueOr(result.getString("start_time"), "");
        String endTime = Texts.valueOr(result.getString("end_time"), "");
        String repeatUntil = Texts.valueOr(result.getString("repeat_until"), "");
        return new Schedule(
                result.getLong("id"),
                LocalDate.parse(result.getString("schedule_date")),
                Texts.valueOr(result.getString("title"), "未命名日程"),
                Texts.valueOr(result.getString("location"), ""),
                startTime.isBlank() ? null : LocalTime.parse(startTime, Texts.TIME_FORMAT),
                endTime.isBlank() ? null : LocalTime.parse(endTime, Texts.TIME_FORMAT),
                Repeat.fromStored(result.getString("repeat_type")),
                repeatUntil.isBlank() ? null : LocalDate.parse(repeatUntil, Texts.DATE_FORMAT),
                result.getInt("remind_minutes"),
                Texts.valueOr(result.getString("description"), ""),
                result.getInt("all_day") != 0);
    }

    // ------------------------------------------------------------ 写入

    public void insert(Schedule schedule) {
        write("INSERT INTO schedule(schedule_date,title,location,start_time,end_time,"
                + "repeat_type,repeat_until,remind_minutes,description,all_day) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?)", schedule, false);
    }

    public void update(Schedule schedule) {
        write("UPDATE schedule SET schedule_date=?,title=?,location=?,start_time=?,end_time=?,"
                + "repeat_type=?,repeat_until=?,remind_minutes=?,description=?,all_day=? WHERE id=?",
                schedule, true);
    }

    public void delete(long id) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM schedule WHERE id=?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            errorHandler.accept(exception);
        }
    }

    private void write(String sql, Schedule schedule, boolean includeId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schedule.date().format(Texts.DATE_FORMAT));
            statement.setString(2, schedule.title());
            statement.setString(3, schedule.location());
            statement.setString(4, schedule.startTime() == null ? ""
                    : schedule.startTime().format(Texts.TIME_FORMAT));
            statement.setString(5, schedule.endTime() == null ? ""
                    : schedule.endTime().format(Texts.TIME_FORMAT));
            statement.setString(6, schedule.repeat().name());
            statement.setString(7, schedule.repeatUntil() == null ? ""
                    : schedule.repeatUntil().format(Texts.DATE_FORMAT));
            statement.setInt(8, schedule.remindMinutes());
            statement.setString(9, schedule.description());
            statement.setInt(10, schedule.allDay() ? 1 : 0);
            if (includeId) statement.setLong(11, schedule.id());
            statement.executeUpdate();
        } catch (SQLException exception) {
            errorHandler.accept(exception);
        }
    }
}
