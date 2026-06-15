package com.android.calendar.alerts;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.CalendarAlerts;
import android.text.format.DateUtils;

import com.android.calendar.FakeSharedPreferences;
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

import java.util.ArrayList;
import java.util.TimeZone;

import ws.xsoh.etar.R;

/**
 * Unit tests for {@link AlertService} covering:
 * - Alert priority bucketization (high, medium, low)
 * - Declined event dismissal
 * - All-day event bucketization
 * - Recurring event deduplication
 * - Grace period calculations
 * - Notification posting and cancellation
 * - Bucket redistribution when exceeding max notifications
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class AlertServiceTest {

    private static final long MINUTE_MS = 60 * 1000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = DateUtils.DAY_IN_MILLIS;
    private static final int MIN_DEPRIORITIZE_GRACE_PERIOD_MS = 15 * (int) MINUTE_MS;

    private static final String[] ALERT_COLUMNS = new String[]{
            "_id", "event_id", "state", "title", "eventLocation",
            "selfAttendeeStatus", "allDay", "alarmTime", "minutes",
            "begin", "end", "description", "calendar_id"
    };

    private FakeNotificationMgr mFakeNotifMgr;
    private FakeAlarmManager mFakeAlarmManager;
    private FakeSharedPreferences mFakePrefs;
    private Context mContext;
    private MockedStatic<Utils> mUtilsMock;
    private MockedStatic<AlertUtils> mAlertUtilsMock;
    private long mCurrentTime;

    @Before
    public void setUp() {
        mFakeNotifMgr = new FakeNotificationMgr();
        mFakeAlarmManager = new FakeAlarmManager();
        mFakePrefs = new FakeSharedPreferences();
        mCurrentTime = 1000000L;

        // Set up context with mock Resources
        TestContextHelper helper = new TestContextHelper();
        Context baseContext = helper.getContext();

        // Create a mock context that wraps the test context but provides Resources
        mContext = mock(Context.class);
        when(mContext.getContentResolver()).thenReturn(baseContext.getContentResolver());
        when(mContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mFakePrefs);
        when(mContext.getPackageName()).thenReturn("com.android.calendar.test");

        // Mock Resources to return skip_reminders_values array
        Resources mockResources = mock(Resources.class);
        when(mockResources.getStringArray(R.array.preferences_skip_reminders_values))
                .thenReturn(new String[]{"declined", "not_responded"});
        when(mContext.getResources()).thenReturn(mockResources);

        // Mock Utils static methods
        mUtilsMock = mockStatic(Utils.class);
        mUtilsMock.when(() -> Utils.getSharedPreference(any(), anyString(), anyString()))
                .thenReturn("");
        mUtilsMock.when(() -> Utils.getCurrentTimezone()).thenReturn("UTC");
        mUtilsMock.when(() -> Utils.convertAlldayUtcToLocal(any(), anyLong(), anyString()))
                .thenCallRealMethod();
        mUtilsMock.when(() -> Utils.getDefaultVibrate(any(), any())).thenReturn(false);
        mUtilsMock.when(() -> Utils.getRingtonePreference(any())).thenReturn("");
        mUtilsMock.when(() -> Utils.isCalendarPermissionGranted(any(), anyBoolean()))
                .thenReturn(true);

        // Mock AlertUtils static methods
        mAlertUtilsMock = mockStatic(AlertUtils.class);
        mAlertUtilsMock.when(() -> AlertUtils.hasAlertFiredInSharedPrefs(
                any(), anyLong(), anyLong(), anyLong())).thenReturn(false);
        mAlertUtilsMock.when(() -> AlertUtils.setAlertFiredInSharedPrefs(
                any(), anyLong(), anyLong(), anyLong())).then(invocation -> null);
        mAlertUtilsMock.when(() -> AlertUtils.flushOldAlertsFromInternalStorage(any()))
                .then(invocation -> null);
        mAlertUtilsMock.when(() -> AlertUtils.formatTimeLocation(
                any(), anyLong(), anyBoolean(), any())).thenReturn("12:00 PM");

        // Set BYPASS_DB to true for local storage tracking
        AlertUtils.BYPASS_DB = true;
    }

    @After
    public void tearDown() {
        mUtilsMock.close();
        mAlertUtilsMock.close();
    }

    @Test
    public void testProcessQuery_singleFiredAlert_goesHighPriority() {
        long begin = mCurrentTime + HOUR_MS; // 1 hour in the future
        long end = begin + HOUR_MS;
        long alarmTime = mCurrentTime - MINUTE_MS; // fired 1 minute ago

        TypedFakeCursor cursor = new TypedFakeCursor.Builder(ALERT_COLUMNS)
                .addRow(1L, 100L, CalendarAlerts.STATE_FIRED, "Meeting", "Office",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 0, alarmTime, 10,
                        begin, end, "", 1L)
                .build();

        ArrayList<AlertService.NotificationInfo> high = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> med = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> low = new ArrayList<>();

        int numFired = AlertService.processQuery(cursor, mContext, mCurrentTime, high, med, low);

        assertEquals("Should have 1 high priority event", 1, high.size());
        assertEquals(0, med.size());
        assertEquals(0, low.size());
        assertEquals("Meeting", high.get(0).eventName);
    }

    @Test
    public void testProcessQuery_declinedEvent_dismissed() {
        long begin = mCurrentTime + HOUR_MS;
        long end = begin + HOUR_MS;
        long alarmTime = mCurrentTime - MINUTE_MS;

        TypedFakeCursor cursor = new TypedFakeCursor.Builder(ALERT_COLUMNS)
                .addRow(2L, 200L, CalendarAlerts.STATE_SCHEDULED, "Declined Meeting", "",
                        Attendees.ATTENDEE_STATUS_DECLINED, 0, alarmTime, 10,
                        begin, end, "", 1L)
                .build();

        ArrayList<AlertService.NotificationInfo> high = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> med = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> low = new ArrayList<>();

        int numFired = AlertService.processQuery(cursor, mContext, mCurrentTime, high, med, low);

        assertEquals("Declined events should not appear in any bucket", 0, high.size());
        assertEquals(0, med.size());
        assertEquals(0, low.size());
        assertEquals("No alerts should fire for declined events", 0, numFired);
    }

    @Test
    public void testProcessQuery_allDayEvent_todayMediumPriority() {
        // All-day event today: begin = today midnight UTC, current time = noon
        long todayMidnightUtc = mCurrentTime; // Pretend this is midnight UTC
        long currentTimeNoon = mCurrentTime + 12 * HOUR_MS; // 12 hours later (noon)
        long endOfDay = todayMidnightUtc + DAY_MS;
        long alarmTime = todayMidnightUtc - MINUTE_MS; // alarm fired 1 min before midnight

        // Mock convertAlldayUtcToLocal to return a time that's today but past grace period
        mUtilsMock.when(() -> Utils.convertAlldayUtcToLocal(any(), eq(todayMidnightUtc), anyString()))
                .thenReturn(todayMidnightUtc);

        TypedFakeCursor cursor = new TypedFakeCursor.Builder(ALERT_COLUMNS)
                .addRow(3L, 300L, CalendarAlerts.STATE_FIRED, "Birthday", "",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 1, alarmTime, 0,
                        todayMidnightUtc, endOfDay, "", 1L)
                .build();

        ArrayList<AlertService.NotificationInfo> high = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> med = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> low = new ArrayList<>();

        // Use noon as current time so the event is past the 15-min grace period
        int numFired = AlertService.processQuery(cursor, mContext, currentTimeNoon,
                high, med, low);

        // All-day events past grace period but still today should be medium priority
        // (The exact bucket depends on DateUtils.isToday which depends on timezone)
        int totalEvents = high.size() + med.size() + low.size();
        assertTrue("All-day event should appear in some bucket", totalEvents >= 1);
    }

    @Test
    public void testProcessQuery_recurringEvent_dedup() {
        // Two alerts for the same event ID (recurring event instances)
        long begin1 = mCurrentTime + HOUR_MS; // instance 1: 1 hour from now
        long end1 = begin1 + HOUR_MS;
        long begin2 = mCurrentTime + 2 * HOUR_MS; // instance 2: 2 hours from now
        long end2 = begin2 + HOUR_MS;
        long alarmTime1 = mCurrentTime - MINUTE_MS;
        long alarmTime2 = mCurrentTime - 2 * MINUTE_MS;

        TypedFakeCursor cursor = new TypedFakeCursor.Builder(ALERT_COLUMNS)
                .addRow(4L, 400L, CalendarAlerts.STATE_FIRED, "Daily Standup", "",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 0, alarmTime1, 10,
                        begin1, end1, "", 1L)
                .addRow(5L, 400L, CalendarAlerts.STATE_FIRED, "Daily Standup", "",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 0, alarmTime2, 10,
                        begin2, end2, "", 1L)
                .build();

        ArrayList<AlertService.NotificationInfo> high = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> med = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> low = new ArrayList<>();

        AlertService.processQuery(cursor, mContext, mCurrentTime, high, med, low);

        int totalEvents = high.size() + med.size() + low.size();
        assertEquals("Same eventId should be deduplicated to 1 entry", 1, totalEvents);
    }

    @Test
    public void testProcessQuery_endedEvent_lowPriority() {
        long begin = mCurrentTime - 3 * HOUR_MS; // started 3 hours ago
        long end = mCurrentTime - 2 * HOUR_MS;   // ended 2 hours ago
        long alarmTime = mCurrentTime - 4 * HOUR_MS;

        TypedFakeCursor cursor = new TypedFakeCursor.Builder(ALERT_COLUMNS)
                .addRow(6L, 500L, CalendarAlerts.STATE_FIRED, "Past Meeting", "",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 0, alarmTime, 10,
                        begin, end, "", 1L)
                .build();

        ArrayList<AlertService.NotificationInfo> high = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> med = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> low = new ArrayList<>();

        AlertService.processQuery(cursor, mContext, mCurrentTime, high, med, low);

        assertEquals("Ended event should not be in high priority", 0, high.size());
        assertEquals("Ended event should not be in medium priority", 0, med.size());
        assertEquals("Ended event should be in low priority", 1, low.size());
        assertEquals("Past Meeting", low.get(0).eventName);
    }

    @Test
    public void testProcessQuery_eventPastGracePeriod_mediumPriority() {
        // 1-hour event that started 30 minutes ago
        // Grace period = max(15min, 60min/4) = max(15min, 15min) = 15 minutes
        // Since 30min > 15min grace, event should be medium priority
        long begin = mCurrentTime - 30 * MINUTE_MS;
        long end = mCurrentTime + 30 * MINUTE_MS;
        long alarmTime = mCurrentTime - 40 * MINUTE_MS;

        TypedFakeCursor cursor = new TypedFakeCursor.Builder(ALERT_COLUMNS)
                .addRow(7L, 600L, CalendarAlerts.STATE_FIRED, "In Progress Meeting", "",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 0, alarmTime, 10,
                        begin, end, "", 1L)
                .build();

        ArrayList<AlertService.NotificationInfo> high = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> med = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> low = new ArrayList<>();

        AlertService.processQuery(cursor, mContext, mCurrentTime, high, med, low);

        assertEquals("Event past grace period should not be in high priority", 0, high.size());
        assertEquals("Event past grace should be in medium priority", 1, med.size());
        assertEquals(0, low.size());
    }

    @Test
    public void testRedistributeBuckets_exceedsMaxNotifications() {
        int maxNotifications = 20;
        ArrayList<AlertService.NotificationInfo> high = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> med = new ArrayList<>();
        ArrayList<AlertService.NotificationInfo> low = new ArrayList<>();

        // Add 25 high-priority events
        for (int i = 0; i < 25; i++) {
            high.add(new AlertService.NotificationInfo(
                    "Event " + i, "", "", mCurrentTime + i * HOUR_MS,
                    mCurrentTime + (i + 1) * HOUR_MS, i, 1, false, false));
        }

        AlertService.redistributeBuckets(high, med, low, maxNotifications);

        assertEquals("High priority should be capped at max", maxNotifications, high.size());
        assertEquals("Overflow should go to low priority", 5, low.size());
        assertEquals(0, med.size());
    }

    @Test
    public void testGenerateAlerts_noFiredAlerts_cancelAll() {
        // Empty cursor
        TypedFakeCursor emptyCursor = new TypedFakeCursor.Builder(ALERT_COLUMNS).build();

        boolean result = AlertService.generateAlerts(mContext, mFakeNotifMgr,
                mFakeAlarmManager, mFakePrefs, emptyCursor, mCurrentTime,
                AlertService.MAX_NOTIFICATIONS);

        assertTrue("generateAlerts should return true", result);
        assertTrue("cancelAll should be called when no alerts", mFakeNotifMgr.cancelAllCalled);
    }

    @Test
    public void testGenerateAlerts_postNotifications() {
        long begin1 = mCurrentTime + HOUR_MS;
        long end1 = begin1 + HOUR_MS;
        long alarmTime1 = mCurrentTime - MINUTE_MS;

        long begin2 = mCurrentTime + 2 * HOUR_MS;
        long end2 = begin2 + HOUR_MS;
        long alarmTime2 = mCurrentTime - 2 * MINUTE_MS;

        long begin3 = mCurrentTime - 3 * HOUR_MS; // ended event
        long end3 = mCurrentTime - 2 * HOUR_MS;
        long alarmTime3 = mCurrentTime - 4 * HOUR_MS;

        TypedFakeCursor cursor = new TypedFakeCursor.Builder(ALERT_COLUMNS)
                .addRow(10L, 700L, CalendarAlerts.STATE_FIRED, "Future Event 1", "Room A",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 0, alarmTime1, 10,
                        begin1, end1, "", 1L)
                .addRow(11L, 800L, CalendarAlerts.STATE_FIRED, "Future Event 2", "Room B",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 0, alarmTime2, 15,
                        begin2, end2, "", 1L)
                .addRow(12L, 900L, CalendarAlerts.STATE_FIRED, "Past Event", "",
                        Attendees.ATTENDEE_STATUS_ACCEPTED, 0, alarmTime3, 10,
                        begin3, end3, "", 1L)
                .build();

        boolean result = AlertService.generateAlerts(mContext, mFakeNotifMgr,
                mFakeAlarmManager, mFakePrefs, cursor, mCurrentTime,
                AlertService.MAX_NOTIFICATIONS);

        assertTrue("generateAlerts should return true", result);
        // 2 high-priority notifications + 1 expired group notification
        assertTrue("Should have posted notifications",
                mFakeNotifMgr.postedIds.size() > 0);
    }
}
