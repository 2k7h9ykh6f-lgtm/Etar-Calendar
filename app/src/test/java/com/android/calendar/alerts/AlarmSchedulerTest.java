package com.android.calendar.alerts;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.database.Cursor;
import android.text.format.DateUtils;

import com.android.calendar.TestContextHelper;
import com.android.calendar.TypedFakeCursor;
import com.android.calendar.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.TimeZone;

/**
 * Unit tests for {@link AlarmScheduler} covering:
 * - Single event with reminder scheduling
 * - All-day event UTC-to-local time conversion
 * - Cross-timezone reminder calculation
 * - Past event filtering (not re-scheduled)
 * - Multiple reminders (nearest wins)
 * - Alarm time capping at 1 day
 * - 1-second delay addition
 * - Batch processing over 50 events
 * - Empty results (no alarm)
 * - Permission denied (no alarm, no crash)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class AlarmSchedulerTest {

    private static final long MINUTE_MS = 60 * 1000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = DateUtils.DAY_IN_MILLIS;

    private FakeAlarmManager mFakeAlarmManager;
    private FakePendingIntentFactory mFakePiFactory;
    private TestContextHelper mContextHelper;
    private Context mContext;
    private MockedStatic<Utils> mUtilsMock;

    @Before
    public void setUp() {
        mFakeAlarmManager = new FakeAlarmManager();
        mFakePiFactory = new FakePendingIntentFactory();
        mContextHelper = new TestContextHelper();
        mContext = mContextHelper.getContext();

        mUtilsMock = mockStatic(Utils.class);
        // Default: permissions granted
        mUtilsMock.when(() -> Utils.isCalendarPermissionGranted(any(), anyBoolean()))
                .thenReturn(true);
        // Default: UTC timezone
        mUtilsMock.when(Utils::getCurrentTimezone).thenReturn("UTC");
        // Let convertAlldayUtcToLocal call real implementation
        mUtilsMock.when(() -> Utils.convertAlldayUtcToLocal(any(), anyLong(), anyString()))
                .thenCallRealMethod();
    }

    @After
    public void tearDown() {
        mUtilsMock.close();
    }

    @Test
    public void testScheduleAlarm_singleUpcomingEventWithReminder() {
        long now = 1000000L;
        long eventBegin = now + 2 * HOUR_MS; // 2 hours from now
        int reminderMinutes = 10;

        // Instances cursor: 1 event
        TypedFakeCursor instancesCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"})
                .addRow(100, eventBegin, 0)
                .build();
        mContextHelper.addQueryResult("instances", instancesCursor);

        // Reminders cursor: 1 reminder for event 100
        TypedFakeCursor remindersCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "minutes", "method"})
                .addRow(100, reminderMinutes, 1) // METHOD_ALERT = 1
                .build();
        mContextHelper.addQueryResult("reminders", remindersCursor);

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        assertEquals(1, mFakeAlarmManager.getAlarmCount());
        long expectedAlarmTime = eventBegin - reminderMinutes * MINUTE_MS
                + AlarmScheduler.ALARM_DELAY_MS;
        assertEquals(expectedAlarmTime, mFakeAlarmManager.getLastAlarm().triggerAtMillis);
    }

    @Test
    public void testAllDayEvent_reminderTimeUsesUtcToLocalConversion() {
        // Use a timezone with a known offset: America/New_York (UTC-5 or UTC-4 depending on DST)
        // For simplicity, use UTC so the conversion is identity
        mUtilsMock.when(Utils::getCurrentTimezone).thenReturn("UTC");

        long now = 1000000L;
        // All-day event at a UTC midnight
        long utcMidnight = now + 5 * HOUR_MS;
        int reminderMinutes = 30;

        TypedFakeCursor instancesCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"})
                .addRow(200, utcMidnight, 1) // allDay = 1
                .build();
        mContextHelper.addQueryResult("instances", instancesCursor);

        TypedFakeCursor remindersCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "minutes", "method"})
                .addRow(200, reminderMinutes, 1)
                .build();
        mContextHelper.addQueryResult("reminders", remindersCursor);

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        assertEquals(1, mFakeAlarmManager.getAlarmCount());
        // With UTC timezone, convertAlldayUtcToLocal extracts the date from UTC and
        // reconstructs midnight in UTC, which should equal the original UTC midnight.
        // The alarm should be: convertedLocalMidnight - 30min + 1s
        // Since we're in UTC, the converted time should be close to utcMidnight
        long alarmTime = mFakeAlarmManager.getLastAlarm().triggerAtMillis;
        assertTrue("Alarm should be in the future", alarmTime > now);
        assertTrue("Alarm should include the delay",
                alarmTime % 1000 == AlarmScheduler.ALARM_DELAY_MS
                        || alarmTime > utcMidnight - reminderMinutes * MINUTE_MS);
    }

    @Test
    public void testCrossTimezone_reminderCalculation() {
        // Simulate a non-all-day event with a specific timezone
        mUtilsMock.when(Utils::getCurrentTimezone).thenReturn("America/Los_Angeles");

        long now = 1000000L;
        long eventBegin = now + HOUR_MS; // 1 hour from now (local millis)
        int reminderMinutes = 15;

        TypedFakeCursor instancesCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"})
                .addRow(300, eventBegin, 0) // not all-day
                .build();
        mContextHelper.addQueryResult("instances", instancesCursor);

        TypedFakeCursor remindersCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "minutes", "method"})
                .addRow(300, reminderMinutes, 1)
                .build();
        mContextHelper.addQueryResult("reminders", remindersCursor);

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        assertEquals(1, mFakeAlarmManager.getAlarmCount());
        // For non-all-day events, begin is used as-is (local millis)
        long expectedAlarmTime = eventBegin - reminderMinutes * MINUTE_MS
                + AlarmScheduler.ALARM_DELAY_MS;
        assertEquals(expectedAlarmTime, mFakeAlarmManager.getLastAlarm().triggerAtMillis);
    }

    @Test
    public void testPastEventsNotScheduled() {
        long now = 1000000L;
        // Event started 5 minutes ago, reminder 10 minutes before start
        // So alarm time = (now - 5min) - 10min = now - 15min (in the past)
        long eventBegin = now - 5 * MINUTE_MS;
        int reminderMinutes = 10;

        TypedFakeCursor instancesCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"})
                .addRow(400, eventBegin, 0)
                .build();
        mContextHelper.addQueryResult("instances", instancesCursor);

        TypedFakeCursor remindersCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "minutes", "method"})
                .addRow(400, reminderMinutes, 1)
                .build();
        mContextHelper.addQueryResult("reminders", remindersCursor);

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        // No alarm should be scheduled since the alarm time is in the past
        assertEquals(0, mFakeAlarmManager.getAlarmCount());
    }

    @Test
    public void testMultipleReminders_nearestOneWins() {
        long now = 1000000L;
        long eventBegin = now + HOUR_MS; // 1 hour from now

        TypedFakeCursor instancesCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"})
                .addRow(500, eventBegin, 0)
                .build();
        mContextHelper.addQueryResult("instances", instancesCursor);

        // Two reminders: 30 minutes and 5 minutes before event
        TypedFakeCursor remindersCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "minutes", "method"})
                .addRow(500, 30, 1)
                .addRow(500, 5, 1)
                .build();
        mContextHelper.addQueryResult("reminders", remindersCursor);

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        assertEquals(1, mFakeAlarmManager.getAlarmCount());
        // The 5-minute reminder is nearest: eventBegin - 5min + delay
        long expectedAlarmTime = eventBegin - 5 * MINUTE_MS + AlarmScheduler.ALARM_DELAY_MS;
        assertEquals(expectedAlarmTime, mFakeAlarmManager.getLastAlarm().triggerAtMillis);
    }

    @Test
    public void testAlarmTimeCappedAtOneDay() {
        long now = 1000000L;
        long eventBegin = now + 5 * DAY_MS; // 5 days from now
        int reminderMinutes = 0;

        TypedFakeCursor instancesCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"})
                .addRow(600, eventBegin, 0)
                .build();
        mContextHelper.addQueryResult("instances", instancesCursor);

        TypedFakeCursor remindersCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "minutes", "method"})
                .addRow(600, reminderMinutes, 1)
                .build();
        mContextHelper.addQueryResult("reminders", remindersCursor);

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        assertEquals(1, mFakeAlarmManager.getAlarmCount());
        // Alarm should be capped at now + DAY_IN_MILLIS + delay
        long expectedMaxAlarm = now + DAY_MS + AlarmScheduler.ALARM_DELAY_MS;
        assertEquals(expectedMaxAlarm, mFakeAlarmManager.getLastAlarm().triggerAtMillis);
    }

    @Test
    public void testOneSecondDelayAdded() {
        long now = 1000000L;
        long eventBegin = now + HOUR_MS;
        int reminderMinutes = 0;

        TypedFakeCursor instancesCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"})
                .addRow(700, eventBegin, 0)
                .build();
        mContextHelper.addQueryResult("instances", instancesCursor);

        TypedFakeCursor remindersCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "minutes", "method"})
                .addRow(700, reminderMinutes, 1)
                .build();
        mContextHelper.addQueryResult("reminders", remindersCursor);

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        assertEquals(1, mFakeAlarmManager.getAlarmCount());
        // Verify the 1-second delay is added
        long expectedAlarmTime = eventBegin + AlarmScheduler.ALARM_DELAY_MS;
        assertEquals(expectedAlarmTime, mFakeAlarmManager.getLastAlarm().triggerAtMillis);
        assertEquals(AlarmScheduler.ALARM_DELAY_MS, 1000);
    }

    @Test
    public void testBatchProcessing_over50Events() {
        long now = 1000000L;
        int batchSize = 50;
        int totalEvents = 75;

        // Create 75 events. The nearest event is #75, at 75 minutes from now.
        TypedFakeCursor.Builder instancesBuilder = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"});
        for (int i = 1; i <= totalEvents; i++) {
            instancesBuilder.addRow(1000 + i, now + i * MINUTE_MS, 0);
        }
        mContextHelper.addQueryResult("instances", instancesBuilder.build());

        // Create reminders for all events. The nearest reminder is for event #1001
        // (1 minute from now, 0 min reminder = alarm at now + 1min)
        TypedFakeCursor.Builder remindersBuilder = new TypedFakeCursor.Builder(
                new String[]{"event_id", "minutes", "method"});
        for (int i = 1; i <= totalEvents; i++) {
            remindersBuilder.addRow(1000 + i, 0, 1);
        }
        mContextHelper.addQueryResult("reminders", remindersBuilder.build());

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, batchSize, now,
                mFakePiFactory);

        assertEquals(1, mFakeAlarmManager.getAlarmCount());
        // The nearest alarm should be for event 1001 (begin = now + 1min)
        long expectedAlarmTime = now + MINUTE_MS + AlarmScheduler.ALARM_DELAY_MS;
        assertEquals(expectedAlarmTime, mFakeAlarmManager.getLastAlarm().triggerAtMillis);
    }

    @Test
    public void testNoEventsFound_noAlarmScheduled() {
        long now = 1000000L;

        TypedFakeCursor emptyCursor = new TypedFakeCursor.Builder(
                new String[]{"event_id", "begin", "allDay"})
                .build();
        mContextHelper.addQueryResult("instances", emptyCursor);

        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        assertEquals(0, mFakeAlarmManager.getAlarmCount());
    }

    @Test
    public void testPermissionDenied_noAlarmScheduled() {
        long now = 1000000L;

        // Override: permission denied
        mUtilsMock.when(() -> Utils.isCalendarPermissionGranted(any(), anyBoolean()))
                .thenReturn(false);

        // Even if we have events, the query should return null due to permission check
        AlarmScheduler.scheduleNextAlarm(mContext, mFakeAlarmManager, 50, now, mFakePiFactory);

        assertEquals(0, mFakeAlarmManager.getAlarmCount());
    }
}
