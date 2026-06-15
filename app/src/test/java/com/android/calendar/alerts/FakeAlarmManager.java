package com.android.calendar.alerts;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake implementation of {@link AlarmManagerInterface} that records all scheduled alarms
 * for verification in tests.
 */
public class FakeAlarmManager implements AlarmManagerInterface {

    public static class ScheduledAlarm {
        public final int type;
        public final long triggerAtMillis;
        public final Object operation; // PendingIntent in production; may be null in tests

        public ScheduledAlarm(int type, long triggerAtMillis, Object operation) {
            this.type = type;
            this.triggerAtMillis = triggerAtMillis;
            this.operation = operation;
        }
    }

    public final List<ScheduledAlarm> scheduledAlarms = new ArrayList<>();

    @Override
    public void set(int type, long triggerAtMillis, android.app.PendingIntent operation) {
        scheduledAlarms.add(new ScheduledAlarm(type, triggerAtMillis, operation));
    }

    public int getAlarmCount() {
        return scheduledAlarms.size();
    }

    public ScheduledAlarm getLastAlarm() {
        if (scheduledAlarms.isEmpty()) {
            return null;
        }
        return scheduledAlarms.get(scheduledAlarms.size() - 1);
    }

    public void clear() {
        scheduledAlarms.clear();
    }
}
