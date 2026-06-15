/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calendar.event;

import android.content.ContentProviderOperation;
import android.provider.CalendarContract.Reminders;

import androidx.test.filters.SmallTest;

import com.android.calendar.CalendarEventModel;
import com.android.calendar.CalendarEventModel.ReminderEntry;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Unit tests for the pure, content-provider-free logic in {@link EditEventHelper} that governs
 * event saving and reminder updates.
 *
 * <p>The focus is the decisions that determine whether a reminder change is persisted and how a
 * recurrence rule is built when editing an event -- both of which, if wrong, lead to missed or
 * duplicated reminders.  Covered:
 * <ul>
 *   <li>{@link EditEventHelper#shouldSaveReminders} (added/deleted/changed/unchanged reminders)</li>
 *   <li>The early-return ("nothing changed") path of {@link EditEventHelper#saveReminders} and
 *       {@link EditEventHelper#saveRemindersWithBackRef}</li>
 *   <li>{@link EditEventHelper#isFirstEventInSeries} (single-instance vs whole-series edits)</li>
 *   <li>{@link EditEventHelper#updateRecurrenceRule} (recurrence selection -> RRULE)</li>
 *   <li>{@link EditEventHelper#extractDomain}</li>
 *   <li>{@link ReminderEntry} equality/ordering and {@link CalendarEventModel#normalizeReminders}</li>
 * </ul>
 *
 * <p>These tests run on a plain JVM and do not require a real calendar account, the content
 * provider, or notification permissions.
 *
 * <pre>
 * ./gradlew :app:testFossDebugUnitTest --tests \
 *     "com.android.calendar.event.EditEventHelperTest"
 * </pre>
 */
public class EditEventHelperTest extends TestCase {

    /** Builds a reminder list using the default alert method for each given minutes value. */
    private static ArrayList<ReminderEntry> reminders(int... minutes) {
        ArrayList<ReminderEntry> list = new ArrayList<ReminderEntry>();
        for (int m : minutes) {
            list.add(ReminderEntry.valueOf(m));
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------
    // shouldSaveReminders
    // ---------------------------------------------------------------------------------------------

    @SmallTest
    public void testShouldSaveReminders_unchangedNoForce_false() {
        assertFalse(EditEventHelper.shouldSaveReminders(
                reminders(10, 5), reminders(10, 5), false));
    }

    @SmallTest
    public void testShouldSaveReminders_unchangedButForced_true() {
        assertTrue(EditEventHelper.shouldSaveReminders(
                reminders(10, 5), reminders(10, 5), true));
    }

    @SmallTest
    public void testShouldSaveReminders_reminderAdded_true() {
        assertTrue(EditEventHelper.shouldSaveReminders(
                reminders(10, 5), reminders(10), false));
    }

    @SmallTest
    public void testShouldSaveReminders_reminderDeleted_true() {
        // User removed all reminders: empty list differs from the original -> must save.
        assertTrue(EditEventHelper.shouldSaveReminders(
                reminders(), reminders(10), false));
    }

    @SmallTest
    public void testShouldSaveReminders_minutesChanged_true() {
        assertTrue(EditEventHelper.shouldSaveReminders(
                reminders(15), reminders(10), false));
    }

    @SmallTest
    public void testShouldSaveReminders_defaultVsAlertSameMinutes_notAChange() {
        // ReminderEntry treats METHOD_DEFAULT and METHOD_ALERT as equal, so converting one to the
        // other (with identical minutes) must NOT be considered a change worth persisting.
        ArrayList<ReminderEntry> asDefault = new ArrayList<ReminderEntry>();
        asDefault.add(ReminderEntry.valueOf(10, Reminders.METHOD_DEFAULT));
        ArrayList<ReminderEntry> asAlert = new ArrayList<ReminderEntry>();
        asAlert.add(ReminderEntry.valueOf(10, Reminders.METHOD_ALERT));

        assertFalse(EditEventHelper.shouldSaveReminders(asDefault, asAlert, false));
    }

    // ---------------------------------------------------------------------------------------------
    // saveReminders / saveRemindersWithBackRef: "nothing changed" early-return path
    // ---------------------------------------------------------------------------------------------

    @SmallTest
    public void testSaveReminders_unchanged_addsNoOps() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        boolean changed = EditEventHelper.saveReminders(
                ops, 42L, reminders(10, 5), reminders(10, 5), false);

        assertFalse(changed);
        assertEquals(0, ops.size());
    }

    @SmallTest
    public void testSaveRemindersWithBackRef_unchanged_addsNoOps() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        boolean changed = EditEventHelper.saveRemindersWithBackRef(
                ops, 0, reminders(10, 5), reminders(10, 5), false);

        assertFalse(changed);
        assertEquals(0, ops.size());
    }

    // ---------------------------------------------------------------------------------------------
    // isFirstEventInSeries
    // ---------------------------------------------------------------------------------------------

    @SmallTest
    public void testIsFirstEventInSeries_matchingStart_true() {
        CalendarEventModel model = new CalendarEventModel();
        CalendarEventModel original = new CalendarEventModel();
        model.mOriginalStart = 1_000L;
        original.mStart = 1_000L;

        assertTrue(EditEventHelper.isFirstEventInSeries(model, original));
    }

    @SmallTest
    public void testIsFirstEventInSeries_editingLaterInstance_false() {
        // Editing a later instance: the instance's original start differs from the series start.
        CalendarEventModel model = new CalendarEventModel();
        CalendarEventModel original = new CalendarEventModel();
        model.mOriginalStart = 2_000L;
        original.mStart = 1_000L;

        assertFalse(EditEventHelper.isFirstEventInSeries(model, original));
    }

    // ---------------------------------------------------------------------------------------------
    // updateRecurrenceRule
    // ---------------------------------------------------------------------------------------------

    private static CalendarEventModel modelForRecurrence() {
        CalendarEventModel model = new CalendarEventModel();
        model.mTimezone = "UTC";
        model.mStart = 1_700_000_000_000L; // arbitrary fixed instant
        return model;
    }

    @SmallTest
    public void testUpdateRecurrenceRule_doesNotRepeat_clearsRule() {
        CalendarEventModel model = modelForRecurrence();
        model.mRrule = "FREQ=DAILY"; // sentinel that must be cleared

        EditEventHelper.updateRecurrenceRule(
                EditEventHelper.DOES_NOT_REPEAT, model, Calendar.MONDAY);

        assertNull(model.mRrule);
    }

    @SmallTest
    public void testUpdateRecurrenceRule_daily() {
        CalendarEventModel model = modelForRecurrence();

        EditEventHelper.updateRecurrenceRule(
                EditEventHelper.REPEATS_DAILY, model, Calendar.MONDAY);

        assertNotNull(model.mRrule);
        assertTrue(model.mRrule, model.mRrule.contains("FREQ=DAILY"));
    }

    @SmallTest
    public void testUpdateRecurrenceRule_everyWeekday() {
        CalendarEventModel model = modelForRecurrence();

        EditEventHelper.updateRecurrenceRule(
                EditEventHelper.REPEATS_EVERY_WEEKDAY, model, Calendar.MONDAY);

        assertNotNull(model.mRrule);
        assertTrue(model.mRrule, model.mRrule.contains("FREQ=WEEKLY"));
        assertTrue(model.mRrule, model.mRrule.contains("BYDAY=MO,TU,WE,TH,FR"));
    }

    @SmallTest
    public void testUpdateRecurrenceRule_weeklyOnDay() {
        CalendarEventModel model = modelForRecurrence();

        EditEventHelper.updateRecurrenceRule(
                EditEventHelper.REPEATS_WEEKLY_ON_DAY, model, Calendar.MONDAY);

        assertNotNull(model.mRrule);
        assertTrue(model.mRrule, model.mRrule.contains("FREQ=WEEKLY"));
        assertTrue(model.mRrule, model.mRrule.contains("BYDAY="));
    }

    @SmallTest
    public void testUpdateRecurrenceRule_custom_leavesRuleUnchanged() {
        CalendarEventModel model = modelForRecurrence();
        model.mRrule = "FREQ=YEARLY;INTERVAL=2"; // a pre-existing custom rule

        EditEventHelper.updateRecurrenceRule(
                EditEventHelper.REPEATS_CUSTOM, model, Calendar.MONDAY);

        assertEquals("FREQ=YEARLY;INTERVAL=2", model.mRrule);
    }

    // ---------------------------------------------------------------------------------------------
    // extractDomain
    // ---------------------------------------------------------------------------------------------

    @SmallTest
    public void testExtractDomain_normal() {
        assertEquals("example.com", EditEventHelper.extractDomain("user@example.com"));
    }

    @SmallTest
    public void testExtractDomain_lastAtWins() {
        assertEquals("c.com", EditEventHelper.extractDomain("a@b@c.com"));
    }

    @SmallTest
    public void testExtractDomain_noAtSign_null() {
        assertNull(EditEventHelper.extractDomain("not-an-email"));
    }

    @SmallTest
    public void testExtractDomain_trailingAt_null() {
        assertNull(EditEventHelper.extractDomain("user@"));
    }

    // ---------------------------------------------------------------------------------------------
    // ReminderEntry semantics
    // ---------------------------------------------------------------------------------------------

    @SmallTest
    public void testReminderEntry_valueOfUsesDefaultMethod() {
        assertEquals(Reminders.METHOD_DEFAULT, ReminderEntry.valueOf(5).getMethod());
        assertEquals(5, ReminderEntry.valueOf(5).getMinutes());
    }

    @SmallTest
    public void testReminderEntry_defaultEqualsAlert() {
        assertTrue(ReminderEntry.valueOf(5, Reminders.METHOD_DEFAULT)
                .equals(ReminderEntry.valueOf(5, Reminders.METHOD_ALERT)));
    }

    @SmallTest
    public void testReminderEntry_defaultNotEqualEmail() {
        assertFalse(ReminderEntry.valueOf(5, Reminders.METHOD_DEFAULT)
                .equals(ReminderEntry.valueOf(5, Reminders.METHOD_EMAIL)));
    }

    @SmallTest
    public void testReminderEntry_differentMinutesNotEqual() {
        assertFalse(ReminderEntry.valueOf(5).equals(ReminderEntry.valueOf(10)));
    }

    @SmallTest
    public void testReminderEntry_compareToIsDescendingByMinutes() {
        // Larger minutes sort first (descending), so 10.compareTo(5) is negative.
        assertTrue(ReminderEntry.valueOf(10).compareTo(ReminderEntry.valueOf(5)) < 0);
        assertTrue(ReminderEntry.valueOf(5).compareTo(ReminderEntry.valueOf(10)) > 0);
    }

    // ---------------------------------------------------------------------------------------------
    // normalizeReminders
    // ---------------------------------------------------------------------------------------------

    @SmallTest
    public void testNormalizeReminders_sortsDescendingAndDedups() {
        CalendarEventModel model = new CalendarEventModel();
        model.mReminders.add(ReminderEntry.valueOf(5));
        model.mReminders.add(ReminderEntry.valueOf(10));
        model.mReminders.add(ReminderEntry.valueOf(10)); // duplicate

        model.normalizeReminders();

        assertEquals(2, model.mReminders.size());
        assertEquals(10, model.mReminders.get(0).getMinutes());
        assertEquals(5, model.mReminders.get(1).getMinutes());
    }

    @SmallTest
    public void testNormalizeReminders_collapsesDefaultAndAlertDuplicate() {
        // Same minutes with DEFAULT vs ALERT are equal, so they collapse to a single reminder.
        CalendarEventModel model = new CalendarEventModel();
        model.mReminders.add(ReminderEntry.valueOf(10, Reminders.METHOD_DEFAULT));
        model.mReminders.add(ReminderEntry.valueOf(10, Reminders.METHOD_ALERT));

        model.normalizeReminders();

        assertEquals(1, model.mReminders.size());
        assertEquals(10, model.mReminders.get(0).getMinutes());
    }
}
