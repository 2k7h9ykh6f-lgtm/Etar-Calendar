package com.android.calendar.event;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import android.test.mock.MockContentResolver;

import com.android.calendar.AbstractCalendarActivity;
import com.android.calendar.AsyncQueryService;
import com.android.calendar.CalendarEventModel;
import com.android.calendar.CalendarEventModel.ReminderEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.TimeZone;

/**
 * Unit tests for {@link EditEventHelper} covering:
 * - New event default reminder saving
 * - Editing, deleting, and force-saving reminders
 * - Back-reference reminder saving for new events
 * - Content values generation for all-day, timed, and recurring events
 * - Recurrence rule generation
 * - First-in-series detection
 * - Editing single instance of recurring event (MODIFY_SELECTED)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class EditEventHelperTest {

    private static final int METHOD_ALERT = Reminders.METHOD_ALERT;
    private static final int METHOD_DEFAULT = Reminders.METHOD_DEFAULT;

    private EditEventHelper mHelper;
    private AbstractCalendarActivity mMockActivity;
    private AsyncQueryService mMockService;

    @Before
    public void setUp() {
        mMockActivity = mock(AbstractCalendarActivity.class);
        mMockService = mock(AsyncQueryService.class);
        when(mMockActivity.getAsyncQueryService()).thenReturn(mMockService);
        when(mMockActivity.getContentResolver()).thenReturn(new MockContentResolver());

        mHelper = new EditEventHelper(mMockActivity);
    }

    // ===== saveReminders tests =====

    @Test
    public void testSaveReminders_newEventWithDefaultReminder() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ArrayList<ReminderEntry> reminders = new ArrayList<>();
        reminders.add(ReminderEntry.valueOf(10, METHOD_ALERT));
        ArrayList<ReminderEntry> originalReminders = new ArrayList<>();

        boolean result = EditEventHelper.saveReminders(ops, 100L, reminders,
                originalReminders, true);

        assertTrue("saveReminders should return true", result);
        // 1 delete + 1 insert = 2 ops
        assertEquals(2, ops.size());
    }

    @Test
    public void testSaveReminders_editExistingReminders() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ArrayList<ReminderEntry> reminders = new ArrayList<>();
        reminders.add(ReminderEntry.valueOf(5, METHOD_ALERT));
        reminders.add(ReminderEntry.valueOf(15, METHOD_ALERT));
        ArrayList<ReminderEntry> originalReminders = new ArrayList<>();
        originalReminders.add(ReminderEntry.valueOf(10, METHOD_ALERT));

        boolean result = EditEventHelper.saveReminders(ops, 100L, reminders,
                originalReminders, false);

        assertTrue("saveReminders should return true when reminders changed", result);
        // 1 delete + 2 inserts = 3 ops
        assertEquals(3, ops.size());
    }

    @Test
    public void testSaveReminders_deleteAllReminders() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ArrayList<ReminderEntry> reminders = new ArrayList<>();
        ArrayList<ReminderEntry> originalReminders = new ArrayList<>();
        originalReminders.add(ReminderEntry.valueOf(10, METHOD_ALERT));

        boolean result = EditEventHelper.saveReminders(ops, 100L, reminders,
                originalReminders, false);

        assertTrue("saveReminders should return true when deleting reminders", result);
        // 1 delete only = 1 op
        assertEquals(1, ops.size());
    }

    @Test
    public void testSaveReminders_noChangeReturnsFalse() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ArrayList<ReminderEntry> reminders = new ArrayList<>();
        reminders.add(ReminderEntry.valueOf(10, METHOD_ALERT));
        ArrayList<ReminderEntry> originalReminders = new ArrayList<>();
        originalReminders.add(ReminderEntry.valueOf(10, METHOD_ALERT));

        boolean result = EditEventHelper.saveReminders(ops, 100L, reminders,
                originalReminders, false);

        assertFalse("saveReminders should return false when unchanged", result);
        assertEquals(0, ops.size());
    }

    @Test
    public void testSaveReminders_forceSaveWhenUnchanged() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ArrayList<ReminderEntry> reminders = new ArrayList<>();
        reminders.add(ReminderEntry.valueOf(10, METHOD_ALERT));
        ArrayList<ReminderEntry> originalReminders = new ArrayList<>();
        originalReminders.add(ReminderEntry.valueOf(10, METHOD_ALERT));

        boolean result = EditEventHelper.saveReminders(ops, 100L, reminders,
                originalReminders, true);

        assertTrue("saveReminders should return true when force=true", result);
        // 1 delete + 1 insert = 2 ops
        assertEquals(2, ops.size());
    }

    // ===== saveRemindersWithBackRef tests =====

    @Test
    public void testSaveRemindersWithBackRef_newEvent() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ArrayList<ReminderEntry> reminders = new ArrayList<>();
        reminders.add(ReminderEntry.valueOf(30, METHOD_ALERT));
        ArrayList<ReminderEntry> originalReminders = new ArrayList<>();

        boolean result = EditEventHelper.saveRemindersWithBackRef(ops, 0, reminders,
                originalReminders, true);

        assertTrue("saveRemindersWithBackRef should return true", result);
        // 1 delete + 1 insert = 2 ops
        assertEquals(2, ops.size());
    }

    // ===== getContentValuesFromModel tests =====

    @Test
    public void testGetContentValuesFromModel_allDayEvent() {
        CalendarEventModel model = createBaseModel();
        model.mAllDay = true;
        model.mStart = 1718409600000L; // 2024-06-15 00:00:00 UTC
        model.mEnd = model.mStart + 24 * 60 * 60 * 1000L; // 1 day

        ContentValues values = mHelper.getContentValuesFromModel(model);

        assertEquals(1, (int) values.getAsInteger(Events.ALL_DAY));
        assertEquals(CalendarContract.Events.TIMEZONE_UTC, values.getAsString(Events.EVENT_TIMEZONE));
        assertNotNull(values.get(Events.DTSTART));
        assertNotNull(values.get(Events.DTEND));
    }

    @Test
    public void testGetContentValuesFromModel_timedEvent() {
        CalendarEventModel model = createBaseModel();
        model.mAllDay = false;
        model.mTimezone = "America/New_York";
        model.mStart = 1718467200000L;
        model.mEnd = model.mStart + 60 * 60 * 1000L; // 1 hour

        ContentValues values = mHelper.getContentValuesFromModel(model);

        assertEquals(0, (int) values.getAsInteger(Events.ALL_DAY));
        assertEquals("America/New_York", values.getAsString(Events.EVENT_TIMEZONE));
        assertNotNull(values.get(Events.DTSTART));
        assertNotNull(values.get(Events.DTEND));
    }

    @Test
    public void testGetContentValuesFromModel_recurringEvent() {
        CalendarEventModel model = createBaseModel();
        model.mAllDay = false;
        model.mRrule = "FREQ=DAILY;COUNT=5";
        model.mStart = 1718467200000L;
        model.mEnd = model.mStart + 60 * 60 * 1000L;
        model.mDuration = "P3600S";

        ContentValues values = mHelper.getContentValuesFromModel(model);

        assertEquals("FREQ=DAILY;COUNT=5", values.getAsString(Events.RRULE));
        assertNotNull("Recurring event should have DURATION", values.get(Events.DURATION));
        assertNull("Recurring event should not have DTEND", values.get(Events.DTEND));
    }

    // ===== updateRecurrenceRule tests =====

    @Test
    public void testUpdateRecurrenceRule_daily() {
        CalendarEventModel model = createBaseModel();
        model.mStart = 1718467200000L;
        model.mTimezone = "UTC";

        EditEventHelper.updateRecurrenceRule(EditEventHelper.REPEATS_DAILY, model,
                java.util.Calendar.SUNDAY);

        assertNotNull(model.mRrule);
        assertTrue("Daily rrule should contain FREQ=DAILY",
                model.mRrule.contains("FREQ=DAILY"));
    }

    @Test
    public void testUpdateRecurrenceRule_yearly() {
        CalendarEventModel model = createBaseModel();
        model.mStart = 1718467200000L;
        model.mTimezone = "UTC";

        EditEventHelper.updateRecurrenceRule(EditEventHelper.REPEATS_YEARLY, model,
                java.util.Calendar.SUNDAY);

        assertNotNull(model.mRrule);
        assertTrue("Yearly rrule should contain FREQ=YEARLY",
                model.mRrule.contains("FREQ=YEARLY"));
    }

    @Test
    public void testUpdateRecurrenceRule_doesNotRepeat() {
        CalendarEventModel model = createBaseModel();
        model.mRrule = "FREQ=DAILY";

        EditEventHelper.updateRecurrenceRule(EditEventHelper.DOES_NOT_REPEAT, model,
                java.util.Calendar.SUNDAY);

        assertNull("Non-repeating event should have null rrule", model.mRrule);
    }

    // ===== isFirstEventInSeries test =====

    @Test
    public void testIsFirstEventInSeries() {
        CalendarEventModel model = createBaseModel();
        model.mOriginalStart = 1000L;
        CalendarEventModel originalModel = createBaseModel();
        originalModel.mStart = 1000L;

        assertTrue("Should be first event when mOriginalStart == originalModel.mStart",
                EditEventHelper.isFirstEventInSeries(model, originalModel));

        model.mOriginalStart = 2000L;
        assertFalse("Should not be first event when times differ",
                EditEventHelper.isFirstEventInSeries(model, originalModel));
    }

    // ===== buildSaveOperations: edit single instance of recurring event =====

    @Test
    public void testBuildSaveOperations_editSingleInstanceOfRecurringEvent() {
        CalendarEventModel model = createBaseModel();
        model.mUri = "content://com.android.calendar/events/100";
        model.mId = 100;
        model.mOriginalStart = 1718467200000L;
        model.mStart = 1718467200000L;
        model.mEnd = model.mStart + 3600000L;
        model.mRrule = null; // The exception event doesn't have rrule
        model.mReminders.add(ReminderEntry.valueOf(10, METHOD_ALERT));

        CalendarEventModel originalModel = createBaseModel();
        originalModel.mUri = "content://com.android.calendar/events/100";
        originalModel.mId = 100;
        originalModel.mStart = 1718467200000L;
        originalModel.mOriginalStart = 1718467200000L;
        originalModel.mEnd = model.mEnd;
        originalModel.mRrule = "FREQ=DAILY;COUNT=10";
        originalModel.mSyncId = "sync123";
        originalModel.mAllDay = false;
        originalModel.mEventStatus = Events.STATUS_CONFIRMED;

        // Use MODIFY_SELECTED to edit single instance
        ArrayList<ContentProviderOperation> ops = mHelper.buildSaveOperations(model,
                originalModel, EditEventHelper.MODIFY_SELECTED);

        assertNotNull("buildSaveOperations should return non-null ops", ops);
        assertTrue("Should have at least 1 operation (event insert)", ops.size() >= 1);

        // The first operation should be an INSERT (creating a recurrence exception)
        ContentProviderOperation firstOp = ops.get(0);
        assertNotNull("First operation should not be null", firstOp);
    }

    // ===== Helper methods =====

    private CalendarEventModel createBaseModel() {
        CalendarEventModel model = new CalendarEventModel();
        model.mCalendarId = 1;
        model.mTitle = "Test Event";
        model.mTimezone = TimeZone.getDefault().getID();
        model.mStart = System.currentTimeMillis() + 3600000L;
        model.mEnd = model.mStart + 3600000L;
        model.mOriginalStart = model.mStart;
        model.mOriginalEnd = model.mEnd;
        model.mAllDay = false;
        model.mHasAlarm = false;
        model.mHasAttendeeData = false;
        model.mAvailability = Events.AVAILABILITY_BUSY;
        model.mAccessLevel = Events.ACCESS_DEFAULT;
        model.mEventStatus = Events.STATUS_CONFIRMED;
        model.mReminders = new ArrayList<>();
        model.mAttendeesList = new java.util.LinkedHashMap<>();
        return model;
    }
}
