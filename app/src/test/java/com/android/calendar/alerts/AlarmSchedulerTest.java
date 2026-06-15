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

package com.android.calendar.alerts;

import android.text.format.DateUtils;

import androidx.test.filters.SmallTest;

import com.android.calendar.calendarcommon2.Time;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link AlarmScheduler}'s pure scheduling logic
 * ({@link AlarmScheduler#computeNextAlarm} and {@link AlarmScheduler#capAlarmTime}).
 *
 * <p>These cover the reminder-to-alarm computation that decides <em>when</em> the next reminder
 * fires.  Getting this wrong is what causes users to miss reminders, so the scenarios here exercise
 * the cases from the spec: a new event's reminder, multiple reminders on one event, all-day event
 * reminder time, cross-timezone all-day reminders, deleted reminders, recurring-event instances, and
 * past events not being (re)scheduled.
 *
 * <p>The tests run on a plain JVM: they do not touch the content provider, a real calendar account,
 * AlarmManager, or notification permissions.  They only call the package-private static helpers
 * extracted from {@link AlarmScheduler} plus the project's pure {@link Time} class.
 *
 * <pre>
 * ./gradlew :app:testFossDebugUnitTest --tests \
 *     "com.android.calendar.alerts.AlarmSchedulerTest"
 * </pre>
 */
public class AlarmSchedulerTest extends TestCase {

    private static final long MIN = DateUtils.MINUTE_IN_MILLIS;
    private static final long DAY = DateUtils.DAY_IN_MILLIS;

    // An arbitrary fixed "current time" used as the scheduling reference point.  Absolute value is
    // unimportant; only the relative offsets of the events/reminders matter for non-all-day cases.
    private static final long NOW = 1_700_000_000_000L;

    // Mirrors the private AlarmScheduler.ALARM_DELAY_MS (added to every scheduled alarm time).
    private static final long ALARM_DELAY_MS = 1000L;

    private Map<Integer, List<Long>> startTimes;
    private Map<Integer, List<Integer>> reminderMinutes;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        startTimes = new HashMap<Integer, List<Long>>();
        reminderMinutes = new HashMap<Integer, List<Integer>>();
    }

    private void addStart(int eventId, long startTime) {
        List<Long> list = startTimes.get(eventId);
        if (list == null) {
            list = new ArrayList<Long>();
            startTimes.put(eventId, list);
        }
        list.add(startTime);
    }

    private void addReminder(int eventId, int minutes) {
        List<Integer> list = reminderMinutes.get(eventId);
        if (list == null) {
            list = new ArrayList<Integer>();
            reminderMinutes.put(eventId, list);
        }
        list.add(minutes);
    }

    private AlarmScheduler.NextAlarm compute() {
        return AlarmScheduler.computeNextAlarm(startTimes, reminderMinutes, NOW);
    }

    /** Builds the millis for UTC midnight of the given date (month is 0-based, like Time). */
    private static long utcMidnight(int monthDay, int month0, int year) {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.set(0, 0, 0, monthDay, month0, year);
        return t.normalize();
    }

    /**
     * Mirrors {@code Utils.convertAlldayUtcToLocal}: an all-day event is stored at UTC midnight; the
     * actual reminder is anchored to local midnight of that same calendar date.
     */
    private static long allDayLocalStart(long utcMidnightBegin, String tzId) {
        Time utc = new Time(Time.TIMEZONE_UTC);
        utc.set(utcMidnightBegin);
        Time local = new Time(tzId);
        local.set(0, 0, 0, utc.getDay(), utc.getMonth(), utc.getYear());
        return local.normalize();
    }

    // ---------------------------------------------------------------------------------------------
    // computeNextAlarm: basic reminder math
    // ---------------------------------------------------------------------------------------------

    /** New event with a single default reminder: alarm = start - reminderMinutes. */
    @SmallTest
    public void testComputeNextAlarm_singleReminder() {
        long start = NOW + 60 * MIN;
        addStart(1, start);
        addReminder(1, 10);

        AlarmScheduler.NextAlarm na = compute();

        assertEquals(start - 10 * MIN, na.time);
        assertEquals(1, na.eventId);
    }

    /**
     * Multiple reminders on one event: the next alarm is the soonest upcoming one.  For an event
     * 60 min away, a 30-min reminder fires before a 10-min reminder, so it wins.
     */
    @SmallTest
    public void testComputeNextAlarm_multipleRemindersNearestWins() {
        long start = NOW + 60 * MIN;
        addStart(1, start);
        addReminder(1, 10);
        addReminder(1, 30);

        AlarmScheduler.NextAlarm na = compute();

        assertEquals(start - 30 * MIN, na.time);
        assertEquals(1, na.eventId);
    }

    /**
     * A reminder whose fire time is already in the past is ignored, but a later reminder on the same
     * event that is still upcoming is used.
     */
    @SmallTest
    public void testComputeNextAlarm_pastReminderSkippedFutureChosen() {
        long start = NOW + 5 * MIN;
        addStart(1, start);
        addReminder(1, 10); // alarm = NOW - 5min  -> in the past, skipped
        addReminder(1, 2);  // alarm = NOW + 3min  -> upcoming, chosen

        AlarmScheduler.NextAlarm na = compute();

        assertEquals(start - 2 * MIN, na.time);
        assertEquals(1, na.eventId);
    }

    /** Past event (already started): its reminder is in the past, so nothing is scheduled. */
    @SmallTest
    public void testComputeNextAlarm_pastEventNotScheduled() {
        addStart(1, NOW - 60 * MIN);
        addReminder(1, 10); // alarm = NOW - 70min -> in the past

        AlarmScheduler.NextAlarm na = compute();

        assertEquals(Long.MAX_VALUE, na.time);
        assertEquals(0, na.eventId);
    }

    /** Event is in range but has no reminders (e.g. the user deleted them): no alarm. */
    @SmallTest
    public void testComputeNextAlarm_deletedRemindersNoAlarm() {
        addStart(1, NOW + 60 * MIN);
        // reminderMinutes intentionally has no entry for event 1.

        AlarmScheduler.NextAlarm na = compute();

        assertEquals(Long.MAX_VALUE, na.time);
    }

    /** Defensive: a reminder for an event with no known start time contributes nothing. */
    @SmallTest
    public void testComputeNextAlarm_reminderWithoutStartSkipped() {
        addReminder(99, 10); // no start time for event 99

        AlarmScheduler.NextAlarm na = compute();

        assertEquals(Long.MAX_VALUE, na.time);
    }

    /** Across multiple events, the globally-soonest upcoming reminder wins, with its event id. */
    @SmallTest
    public void testComputeNextAlarm_picksGlobalMinAcrossEvents() {
        addStart(1, NOW + 120 * MIN);
        addReminder(1, 10); // alarm = NOW + 110min
        addStart(2, NOW + 60 * MIN);
        addReminder(2, 5);  // alarm = NOW + 55min  (sooner)

        AlarmScheduler.NextAlarm na = compute();

        assertEquals((NOW + 60 * MIN) - 5 * MIN, na.time);
        assertEquals(2, na.eventId);
    }

    // ---------------------------------------------------------------------------------------------
    // computeNextAlarm: recurring events (same event id, multiple instance start times)
    // ---------------------------------------------------------------------------------------------

    /** A recurring event surfaces multiple instance start times; the soonest instance wins. */
    @SmallTest
    public void testComputeNextAlarm_multipleInstancesOfRecurringEvent() {
        long firstInstance = NOW + 60 * MIN;
        long secondInstance = NOW + 24 * 60 * MIN;
        addStart(7, firstInstance);
        addStart(7, secondInstance);
        addReminder(7, 10);

        AlarmScheduler.NextAlarm na = compute();

        assertEquals(firstInstance - 10 * MIN, na.time);
        assertEquals(7, na.eventId);
    }

    /**
     * Recurring event whose first instance is in the past and second is upcoming: the past instance
     * must not suppress scheduling of the next upcoming one.
     */
    @SmallTest
    public void testComputeNextAlarm_recurringPastInstanceDoesNotBlockFuture() {
        long pastInstance = NOW - 10 * MIN;     // its reminder alarm is already in the past
        long futureInstance = NOW + 120 * MIN;
        addStart(7, pastInstance);
        addStart(7, futureInstance);
        addReminder(7, 10);

        AlarmScheduler.NextAlarm na = compute();

        assertEquals(futureInstance - 10 * MIN, na.time);
        assertEquals(7, na.eventId);
    }

    // ---------------------------------------------------------------------------------------------
    // computeNextAlarm: all-day and cross-timezone
    // ---------------------------------------------------------------------------------------------

    /**
     * All-day event: the reminder is anchored to <em>local</em> midnight of the event date (not the
     * stored UTC midnight), so a 30-min reminder fires 30 min before local midnight.
     */
    @SmallTest
    public void testComputeNextAlarm_allDayEventReminderTime() {
        long beginUtc = utcMidnight(15, 5 /* June */, 2024);
        String tz = "America/Los_Angeles";
        long localStart = allDayLocalStart(beginUtc, tz);
        long now = localStart - 10 * DAY; // well before the event

        Map<Integer, List<Long>> starts = new HashMap<Integer, List<Long>>();
        Map<Integer, List<Integer>> minutes = new HashMap<Integer, List<Integer>>();
        starts.put(1, new ArrayList<Long>());
        starts.get(1).add(localStart);
        minutes.put(1, new ArrayList<Integer>());
        minutes.get(1).add(30);

        AlarmScheduler.NextAlarm na = AlarmScheduler.computeNextAlarm(starts, minutes, now);

        assertEquals(localStart - 30 * MIN, na.time);
        assertEquals(1, na.eventId);
        // For a timezone with a non-zero UTC offset, local midnight != stored UTC midnight.
        assertTrue("all-day local start should differ from stored UTC midnight",
                localStart != beginUtc);
    }

    /**
     * The same all-day event (same stored UTC midnight) produces different absolute reminder times
     * in different timezones.  Local midnight in Tokyo (UTC+9) occurs earlier in absolute time than
     * local midnight in Los Angeles (UTC-7/-8), so the Tokyo alarm is earlier.
     */
    @SmallTest
    public void testComputeNextAlarm_allDayReminderDiffersAcrossTimezones() {
        long beginUtc = utcMidnight(15, 5 /* June */, 2024);
        long laStart = allDayLocalStart(beginUtc, "America/Los_Angeles");
        long tokyoStart = allDayLocalStart(beginUtc, "Asia/Tokyo");

        assertTrue("Tokyo local midnight should precede LA local midnight in absolute time",
                tokyoStart < laStart);

        long now = Math.min(laStart, tokyoStart) - 10 * DAY;

        addStart(1, laStart);
        addReminder(1, 30);
        long laAlarm = AlarmScheduler.computeNextAlarm(startTimes, reminderMinutes, now).time;

        startTimes.clear();
        reminderMinutes.clear();
        addStart(1, tokyoStart);
        addReminder(1, 30);
        long tokyoAlarm = AlarmScheduler.computeNextAlarm(startTimes, reminderMinutes, now).time;

        assertEquals(laStart - 30 * MIN, laAlarm);
        assertEquals(tokyoStart - 30 * MIN, tokyoAlarm);
        assertTrue("cross-timezone alarms must differ", laAlarm != tokyoAlarm);
        assertTrue(tokyoAlarm < laAlarm);
    }

    // ---------------------------------------------------------------------------------------------
    // capAlarmTime
    // ---------------------------------------------------------------------------------------------

    /** An alarm within the 1-day window is left in place, with only the fixed delay added. */
    @SmallTest
    public void testCapAlarmTime_withinWindowAddsDelay() {
        long alarm = NOW + 60 * MIN;
        assertEquals(alarm + ALARM_DELAY_MS, AlarmScheduler.capAlarmTime(alarm, NOW));
    }

    /** An alarm far in the future is pulled in to at most 1 day out (so it can be at most 1 day late). */
    @SmallTest
    public void testCapAlarmTime_beyondMaxIsCappedToOneDay() {
        long alarm = NOW + 3 * DAY;
        assertEquals(NOW + DAY + ALARM_DELAY_MS, AlarmScheduler.capAlarmTime(alarm, NOW));
    }

    /** Exactly at the 1-day boundary is not capped (the cap is strictly-greater-than). */
    @SmallTest
    public void testCapAlarmTime_exactlyAtMaxNotCapped() {
        long alarm = NOW + DAY;
        assertEquals(NOW + DAY + ALARM_DELAY_MS, AlarmScheduler.capAlarmTime(alarm, NOW));
    }
}
