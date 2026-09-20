package com.calendar.ui;

import com.calendar.data.ScheduleStore;
import com.calendar.model.RemindOption;
import com.calendar.model.Repeat;
import com.calendar.model.Schedule;
import com.calendar.util.Texts;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerDateModel;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Date;

/**
 * 日程编辑对话框。
 *
 * <p>静态工厂 {@link #open} 是唯一入口，对话框本身不持有状态，保存/删除后
 * 通过 {@code onChanged} 回调让主窗口刷新。
 */
public final class ScheduleEditor {

    private ScheduleEditor() { }

    /**
     * 打开编辑对话框（模态，阻塞到关闭）。
     *
     * @param owner     父窗口
     * @param existing  已有日程；{@code null} 表示新建
     * @param date      新建时的落库日期；编辑时忽略，沿用原日程日期
     * @param store     数据库
     * @param onChanged 保存或删除成功后的回调，通常是主窗口的整体刷新
     */
    public static void open(Window owner, Schedule existing, LocalDate date,
                            ScheduleStore store, Runnable onChanged) {
        JDialog dialog = new JDialog(owner, existing == null ? "添加日程" : "编辑日程", JDialog.DEFAULT_MODALITY_TYPE);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(18, 20, 14, 20));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 5, 0);

        JTextField title = new JTextField(existing == null ? "" : existing.title());
        JTextField location = new JTextField(existing == null ? "" : existing.location());
        JCheckBox allDay = new JCheckBox("全天日程", existing != null && existing.allDay());

        LocalTime initialStart = existing != null && existing.startTime() != null
                ? existing.startTime() : LocalTime.of(9, 0);
        LocalTime initialEnd = existing != null && existing.endTime() != null
                ? existing.endTime() : initialStart.plusHours(1);
        JSpinner startTime = timeSpinner(initialStart);
        JSpinner endTime = timeSpinner(initialEnd);

        JComboBox<Repeat> repeat = new JComboBox<>(Repeat.values());
        JTextField repeatUntil = new JTextField(existing != null && existing.repeatUntil() != null
                ? existing.repeatUntil().format(Texts.DATE_FORMAT) : "");
        repeatUntil.setToolTipText("留空表示不限结束日期，格式 yyyy-MM-dd");

        JComboBox<RemindOption> reminder = new JComboBox<>(RemindOption.OPTIONS);

        JTextArea description = new JTextArea(3, 20);
        description.setLineWrap(true);
        if (existing != null) {
            repeat.setSelectedItem(existing.repeat());
            reminder.setSelectedItem(RemindOption.fromStored(existing.remindMinutes()));
            description.setText(existing.description());
        }

        // 全天日程没有时刻可言，禁掉两个时间选择器而不是隐藏，避免布局跳动。
        ChangeListener allDayListener = event -> {
            boolean on = allDay.isSelected();
            startTime.setEnabled(!on);
            endTime.setEnabled(!on);
        };
        allDay.addChangeListener(allDayListener);
        allDayListener.stateChanged(new ChangeEvent(allDay));

        addField(form, constraints, "标题", title);
        addField(form, constraints, "地点", location);
        form.add(allDay, constraints);
        constraints.gridy++;
        addField(form, constraints, "开始时间", startTime);
        addField(form, constraints, "结束时间", endTime);
        addField(form, constraints, "重复", repeat);
        addField(form, constraints, "重复截止", repeatUntil);
        addField(form, constraints, "提醒", reminder);
        addField(form, constraints, "备注", new JScrollPane(description));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton delete = new JButton("删除");
        JButton cancel = new JButton("取消");
        JButton save = new JButton("保存");
        Theme.stylePrimaryButton(save);
        delete.setForeground(Theme.DANGER);
        delete.setVisible(existing != null);
        delete.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(dialog,
                    "确定删除“" + existing.title() + "”吗？此操作无法撤销。",
                    "删除日程", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                store.delete(existing.id());
                dialog.dispose();
                onChanged.run();
            }
        });
        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            String titleText = title.getText().trim();
            if (titleText.isEmpty()) {
                warn(dialog, "请输入日程标题");
                return;
            }
            LocalDate until = null;
            String untilText = repeatUntil.getText().trim();
            if (!untilText.isEmpty()) {
                try {
                    until = LocalDate.parse(untilText, Texts.DATE_FORMAT);
                } catch (RuntimeException exception) {
                    warn(dialog, "重复截止日期格式不正确，应为 yyyy-MM-dd");
                    return;
                }
                if (until.isBefore(date)) {
                    warn(dialog, "重复截止日期不能早于日程本身");
                    return;
                }
            }
            LocalTime start = spinnerTime(startTime);
            LocalTime end = spinnerTime(endTime);
            if (!allDay.isSelected() && end.isBefore(start)) {
                warn(dialog, "结束时间不能早于开始时间");
                return;
            }
            // 编辑时保留原日程的日期，而不是当前选中日期。
            LocalDate targetDate = existing == null ? date : existing.date();
            Schedule schedule = new Schedule(
                    existing == null ? 0 : existing.id(),
                    targetDate,
                    titleText,
                    location.getText().trim(),
                    allDay.isSelected() ? null : start,
                    allDay.isSelected() ? null : end,
                    (Repeat) repeat.getSelectedItem(),
                    until,
                    ((RemindOption) reminder.getSelectedItem()).minutes(),
                    description.getText().trim(),
                    allDay.isSelected());
            if (existing == null) {
                store.insert(schedule);
            } else {
                store.update(schedule);
            }
            dialog.dispose();
            onChanged.run();
        });
        buttons.add(delete);
        buttons.add(cancel);
        buttons.add(save);
        constraints.gridy++;
        constraints.insets = new Insets(10, 0, 0, 0);
        form.add(buttons, constraints);

        dialog.setContentPane(form);
        dialog.pack();
        dialog.setSize(400, Math.min(dialog.getHeight(), 680));
        dialog.setLocationRelativeTo(owner);
        // Esc 关闭，Enter 保存。
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().setDefaultButton(save);
        dialog.setVisible(true);
    }

    private static void warn(JDialog dialog, String message) {
        JOptionPane.showMessageDialog(dialog, message, "无法保存", JOptionPane.WARNING_MESSAGE);
    }

    private static JSpinner timeSpinner(LocalTime initial) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, initial.getHour());
        calendar.set(Calendar.MINUTE, initial.getMinute());
        calendar.set(Calendar.SECOND, 0);
        JSpinner spinner = new JSpinner(new SpinnerDateModel(calendar.getTime(), null, null,
                Calendar.MINUTE));
        spinner.setEditor(new JSpinner.DateEditor(spinner, "HH:mm"));
        return spinner;
    }

    private static LocalTime spinnerTime(JSpinner spinner) {
        Date value = (Date) spinner.getValue();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(value);
        return LocalTime.of(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    /** 加一行"标签 + 控件"，并把游标推进两格。 */
    private static void addField(JPanel panel, GridBagConstraints constraints,
                                 String label, JComponent component) {
        JLabel caption = new JLabel(label);
        caption.setFont(Theme.FONT);
        caption.setForeground(Theme.MUTED);
        panel.add(caption, constraints);
        constraints.gridy++;
        panel.add(component, constraints);
        constraints.gridy++;
    }
}
