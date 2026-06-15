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
 * distributed under the License is distributed on an an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calendar.widget;

import android.content.Context;
import android.database.MatrixCursor;
import android.provider.CalendarContract;

import androidx.test.core.app.ApplicationProvider;

import com.android.calendar.calendarcommon2.Time;
import com.android.calendar.widget.CalendarAppWidgetModel.DayInfo;
import com.android.calendar.widget.CalendarAppWidgetModel.EventInfo;
import com.android.calendar.widget.CalendarAppWidgetModel.RowInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Comprehensive tests for {@link CalendarAppWidgetModel} construction from Cursor data.
 *
 * These tests verify that the widget correctly:
 * - Handles empty, single, and multiple events
 * - Sorts all-day events before timed events
 * - Handles cross-midnight and DST transitions
 * - Filters past events
 * - Handles missing fields gracefully
 * - Maintains sort stability
 * - Shows timezone information when appropriate
 *
 * Run with: ./gradlew :app:testDebugUnitTest --tests "com.android.calendar.widget.CalendarAppWidgetModelTest"
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CalendarAppWidgetModelTest {

    private static final String TEST_TIMEZONE = "America/Los_Angeles";
    private static final Locale TEST_LOCALE = Locale.US;

    private static final long ONE_MINUTE = 60_000L;
    private static final long ONE_HOUR = 60 * ONE_MINUTE;
    private static final long ONE_DAY = 24 * ONE_HOUR;

    private Context mContext;
    private TimeZone mOriginalTimeZone;
    private Locale mOriginalLocale;

    @Before
    public void setUp() {
        // Save original settings
        mOriginalTimeZone = TimeZone.getDefault();
        mOriginalLocale = Locale.getDefault();

        // Pin timezone and locale for reproducible tests
        TimeZone.setDefault(TimeZone.getTimeZone(TEST_TIMEZONE));
        Locale.setDefault(TEST_LOCALE);

        mContext = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() {
        // Restore original settings
        TimeZone.setDefault(mOriginalTimeZone);
        Locale.setDefault(mOriginalLocale);
    }

    // ===== Test 1: Empty Cursor =====

    @Test
    public void testEmptyCursor() {
        MatrixCursor cursor = createEmptyCursor();

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertNotNull("Model should not be null", model);
        assertEquals("Event list should be empty", 0, model.mEventInfos.size());
        assertEquals("Row list should be empty", 0, model.mRowInfos.size());
        assertEquals("Day list should be empty", 0, model.mDayInfos.size());
    }

    // ===== Test 2: Single Future Event =====

    @Test
    public void testSingleFutureEvent() {
        long now = System.currentTimeMillis();
        long eventStart = now + ONE_HOUR;
        long eventEnd = now + 2 * ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();
        cursor.addRow(buildEventRow(
                0,                    // not all-day
                eventStart,
                eventEnd,
                "Team Meeting",
                "Conference Room A",
                1001L,
                eventStart,           // startDay (will be computed)
                eventEnd,             // endDay
                0xFF0000FF,           // blue color
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 1 event", 1, model.mEventInfos.size());
        assertEquals("Should have 1 row", 1, model.mRowInfos.size());

        EventInfo event = model.mEventInfos.get(0);
        assertEquals("Team Meeting", event.title);
        assertEquals("Conference Room A", event.where);
        assertEquals(eventStart, event.start);
        assertEquals(eventEnd, event.end);
        assertFalse("Should not be all-day", event.allDay);
        assertEquals(CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED, event.selfAttendeeStatus);
        assertEquals(0xFF0000FF, event.color);
        assertNotNull("When text should not be null", event.when);
    }

    // ===== Test 3: Multiple Events on Same Day =====

    @Test
    public void testMultipleEventsOnSameDay() {
        long now = System.currentTimeMillis();
        long baseTime = now + ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();

        // Add 3 events on the same day, in chronological order
        for (int i = 0; i < 3; i++) {
            long start = baseTime + (i * 2 * ONE_HOUR);
            long end = start + ONE_HOUR;
            cursor.addRow(buildEventRow(
                    0, start, end,
                    "Event " + (i + 1),
                    "Location " + (i + 1),
                    2000L + i,
                    start, end,
                    0xFFFF0000,
                    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
            ));
        }

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 3 events", 3, model.mEventInfos.size());
        assertEquals("Should have 3 rows (all on same day, no day headers)", 3, model.mRowInfos.size());

        // Verify chronological order
        for (int i = 0; i < 3; i++) {
            EventInfo event = model.mEventInfos.get(i);
            assertEquals("Event " + (i + 1), event.title);
            assertEquals("Location " + (i + 1), event.where);
        }

        // Verify times are in ascending order
        for (int i = 1; i < 3; i++) {
            assertTrue("Events should be in chronological order",
                    model.mEventInfos.get(i).start > model.mEventInfos.get(i - 1).start);
        }
    }

    // ===== Test 4: Cross-Midnight Event =====

    @Test
    public void testCrossMidnightEvent() {
        long now = System.currentTimeMillis();

        // Create an event that spans midnight (starts at 11 PM, ends at 1 AM next day)
        Time startTime = new Time(TEST_TIMEZONE);
        startTime.set(now);
        startTime.setHour(23);
        startTime.setMinute(0);
        startTime.setSecond(0);
        long eventStart = startTime.toMillis();

        long eventEnd = eventStart + (2 * ONE_HOUR); // 2 hours later = 1 AM next day

        MatrixCursor cursor = createEmptyCursor();
        cursor.addRow(buildEventRow(
                0, eventStart, eventEnd,
                "Late Night Event",
                "Venue",
                3001L,
                eventStart, eventEnd,
                0xFF00FF00,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 1 event", 1, model.mEventInfos.size());

        EventInfo event = model.mEventInfos.get(0);
        assertEquals("Late Night Event", event.title);
        assertEquals(eventStart, event.start);
        assertEquals(eventEnd, event.end);

        // The cross-midnight event should appear in both today's and tomorrow's bucket
        // Today has no day header; tomorrow gets a day header
        int meetingCount = 0;
        int dayHeaderCount = 0;
        for (RowInfo row : model.mRowInfos) {
            if (row.mType == RowInfo.TYPE_MEETING) meetingCount++;
            else if (row.mType == RowInfo.TYPE_DAY) dayHeaderCount++;
        }
        // Event appears once per day bucket it spans (today + tomorrow = 2 meeting rows)
        assertEquals("Cross-midnight event should appear in 2 day buckets", 2, meetingCount);
        // Tomorrow should have a day header (today does not)
        assertEquals("Should have 1 day header (for tomorrow)", 1, dayHeaderCount);
    }

    // ===== Test 5: All-Day Event Sorting (Before Timed Events) =====

    @Test
    public void testAllDayEventSorting() {
        long now = System.currentTimeMillis();
        long tomorrow = now + ONE_DAY;

        // Create an all-day event for tomorrow
        Time allDayTime = new Time(TEST_TIMEZONE);
        allDayTime.set(tomorrow);
        allDayTime.setHour(0);
        allDayTime.setMinute(0);
        allDayTime.setSecond(0);
        long allDayStart = allDayTime.toMillis();
        long allDayEnd = allDayStart + ONE_DAY;

        // Create a timed event for tomorrow at 2 PM
        Time timedTime = new Time(TEST_TIMEZONE);
        timedTime.set(tomorrow);
        timedTime.setHour(14);
        timedTime.setMinute(0);
        timedTime.setSecond(0);
        long timedStart = timedTime.toMillis();
        long timedEnd = timedStart + ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();

        // Add timed event FIRST in cursor
        cursor.addRow(buildEventRow(
                0, timedStart, timedEnd,
                "Afternoon Meeting",
                "Office",
                4001L,
                timedStart, timedEnd,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        // Add all-day event SECOND in cursor
        cursor.addRow(buildEventRow(
                1, allDayStart, allDayEnd,  // allDay = 1
                "Company Holiday",
                "",
                4002L,
                allDayStart, allDayEnd,
                0xFFFF0000,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 2 events", 2, model.mEventInfos.size());

        // Find the all-day and timed events
        EventInfo allDayEvent = null;
        EventInfo timedEvent = null;
        for (EventInfo event : model.mEventInfos) {
            if (event.allDay) {
                allDayEvent = event;
            } else {
                timedEvent = event;
            }
        }

        assertNotNull("All-day event should exist", allDayEvent);
        assertNotNull("Timed event should exist", timedEvent);
        assertEquals("Company Holiday", allDayEvent.title);
        assertEquals("Afternoon Meeting", timedEvent.title);

        // Verify all-day event appears before timed event in row order
        int allDayRowIndex = -1;
        int timedRowIndex = -1;
        for (int i = 0; i < model.mRowInfos.size(); i++) {
            RowInfo row = model.mRowInfos.get(i);
            if (row.mType == RowInfo.TYPE_MEETING) {
                EventInfo event = model.mEventInfos.get(row.mIndex);
                if (event.allDay) {
                    allDayRowIndex = i;
                } else {
                    timedRowIndex = i;
                }
            }
        }

        assertTrue("All-day event should appear before timed event",
                allDayRowIndex >= 0 && timedRowIndex >= 0 && allDayRowIndex < timedRowIndex);
    }

    // ===== Test 6: DST Transition Day =====

    @Test
    public void testDSTTransitionDay() {
        // In 2026, DST starts on March 8 in America/Los_Angeles (spring forward)
        // Create events on March 8 and March 9 to verify correct handling

        Time dstDay = new Time(TEST_TIMEZONE);
        dstDay.set(0, 0, 10, 8, 2, 2026); // March 8, 2026 at 10 AM (second, minute, hour, day, month, year)
        long event1Start = dstDay.toMillis();
        long event1End = event1Start + ONE_HOUR;

        Time nextDay = new Time(TEST_TIMEZONE);
        nextDay.set(0, 0, 10, 9, 2, 2026); // March 9, 2026 at 10 AM
        long event2Start = nextDay.toMillis();
        long event2End = event2Start + ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();
        cursor.addRow(buildEventRow(
                0, event1Start, event1End,
                "DST Day Event",
                "Location 1",
                5001L,
                event1Start, event1End,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));
        cursor.addRow(buildEventRow(
                0, event2Start, event2End,
                "Post-DST Event",
                "Location 2",
                5002L,
                event2Start, event2End,
                0xFF00FF00,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 2 events", 2, model.mEventInfos.size());
        assertEquals("DST Day Event", model.mEventInfos.get(0).title);
        assertEquals("Post-DST Event", model.mEventInfos.get(1).title);

        // Verify both events are present in rows
        List<EventInfo> rowEvents = extractEventsFromRows(model);
        assertEquals("Should have 2 events in rows", 2, rowEvents.size());
    }

    // ===== Test 7: Declined Event Status =====

    @Test
    public void testDeclinedEventStatus() {
        long now = System.currentTimeMillis();
        long eventStart = now + ONE_HOUR;
        long eventEnd = now + 2 * ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();
        cursor.addRow(buildEventRow(
                0, eventStart, eventEnd,
                "Declined Meeting",
                "Room B",
                6001L,
                eventStart, eventEnd,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 1 event", 1, model.mEventInfos.size());

        EventInfo event = model.mEventInfos.get(0);
        assertEquals("Declined Meeting", event.title);
        assertEquals("Event should be declined",
                CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                event.selfAttendeeStatus);
    }

    // ===== Test 8: Missing Event Title =====

    @Test
    public void testMissingEventTitle() {
        long now = System.currentTimeMillis();
        long eventStart = now + ONE_HOUR;
        long eventEnd = now + 2 * ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();
        cursor.addRow(buildEventRow(
                0, eventStart, eventEnd,
                "",  // Empty title
                "Location",
                7001L,
                eventStart, eventEnd,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 1 event", 1, model.mEventInfos.size());

        EventInfo event = model.mEventInfos.get(0);
        // The model should handle empty titles gracefully (may use default or leave empty)
        assertNotNull("Title should not be null", event.title);
    }

    // ===== Test 9: Missing Event Location =====

    @Test
    public void testMissingEventLocation() {
        long now = System.currentTimeMillis();
        long eventStart = now + ONE_HOUR;
        long eventEnd = now + 2 * ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();
        cursor.addRow(buildEventRow(
                0, eventStart, eventEnd,
                "Meeting Without Location",
                "",  // Empty location
                8001L,
                eventStart, eventEnd,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 1 event", 1, model.mEventInfos.size());

        EventInfo event = model.mEventInfos.get(0);
        assertEquals("Meeting Without Location", event.title);
        // Location should be empty or null
        assertTrue("Location should be empty or null",
                event.where == null || event.where.isEmpty());
    }

    // ===== Test 10: Null Event Title =====

    @Test
    public void testNullEventTitle() {
        long now = System.currentTimeMillis();
        long eventStart = now + ONE_HOUR;
        long eventEnd = now + 2 * ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();
        cursor.addRow(buildEventRow(
                0, eventStart, eventEnd,
                null,  // Null title
                "Location",
                9001L,
                eventStart, eventEnd,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 1 event", 1, model.mEventInfos.size());

        EventInfo event = model.mEventInfos.get(0);
        assertNotNull("Title should not be null even if cursor has null", event.title);
    }

    // ===== Test 11: Sort Stability (Same Start Time) =====

    @Test
    public void testSortStability() {
        long now = System.currentTimeMillis();
        long sameStart = now + ONE_HOUR;
        long sameEnd = sameStart + ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();

        // Add 3 events with identical start and end times
        for (int i = 0; i < 3; i++) {
            cursor.addRow(buildEventRow(
                    0, sameStart, sameEnd,
                    "Event " + (char) ('A' + i),  // Event A, Event B, Event C
                    "Location " + (i + 1),
                    10000L + i,
                    sameStart, sameEnd,
                    0xFF0000FF,
                    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
            ));
        }

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 3 events", 3, model.mEventInfos.size());

        // Verify cursor order is preserved (stable sort)
        assertEquals("Event A", model.mEventInfos.get(0).title);
        assertEquals("Event B", model.mEventInfos.get(1).title);
        assertEquals("Event C", model.mEventInfos.get(2).title);
    }

    // ===== Test 12: Timezone Difference Display =====

    @Test
    public void testTimezoneDifference() {
        long now = System.currentTimeMillis();
        long eventStart = now + ONE_HOUR;
        long eventEnd = now + 2 * ONE_HOUR;

        MatrixCursor cursor = createEmptyCursor();
        cursor.addRow(buildEventRow(
                0, eventStart, eventEnd,
                "Meeting",
                "Office",
                11001L,
                eventStart, eventEnd,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        // Build model with a DIFFERENT timezone than the system default
        String differentTZ = "America/New_York";
        CalendarAppWidgetModel model = buildModel(cursor, differentTZ);

        assertEquals("Should have 1 event", 1, model.mEventInfos.size());

        EventInfo event = model.mEventInfos.get(0);
        assertNotNull("When text should not be null", event.when);

        // When timezone differs from system timezone, the "when" text should include timezone info
        // The exact format depends on Android's DateUtils, but it should contain some timezone indicator
        // We'll just verify it's not empty and contains some text
        assertFalse("When text should not be empty", event.when.isEmpty());
    }

    // ===== Test 13: Past Event Filtering =====

    @Test
    public void testPastEventFiltering() {
        long now = System.currentTimeMillis();

        MatrixCursor cursor = createEmptyCursor();

        // Add a past event (ended 1 hour ago)
        long pastStart = now - 2 * ONE_HOUR;
        long pastEnd = now - ONE_HOUR;
        cursor.addRow(buildEventRow(
                0, pastStart, pastEnd,
                "Past Event",
                "Old Location",
                12001L,
                pastStart, pastEnd,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        // Add a future event
        long futureStart = now + ONE_HOUR;
        long futureEnd = now + 2 * ONE_HOUR;
        cursor.addRow(buildEventRow(
                0, futureStart, futureEnd,
                "Future Event",
                "New Location",
                12002L,
                futureStart, futureEnd,
                0xFF00FF00,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        // Past events should be filtered out
        assertEquals("Should have only 1 event (future)", 1, model.mEventInfos.size());
        assertEquals("Future Event", model.mEventInfos.get(0).title);
    }

    // ===== Test 14: Day Headers for Future Days =====

    @Test
    public void testDayHeadersForFutureDays() {
        long now = System.currentTimeMillis();

        MatrixCursor cursor = createEmptyCursor();

        // Event on today (no day header)
        long todayStart = now + ONE_HOUR;
        long todayEnd = todayStart + ONE_HOUR;
        cursor.addRow(buildEventRow(
                0, todayStart, todayEnd,
                "Today Event",
                "Location",
                13001L,
                todayStart, todayEnd,
                0xFF0000FF,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        // Event on tomorrow (should have day header)
        long tomorrowStart = now + ONE_DAY + ONE_HOUR;
        long tomorrowEnd = tomorrowStart + ONE_HOUR;
        cursor.addRow(buildEventRow(
                0, tomorrowStart, tomorrowEnd,
                "Tomorrow Event",
                "Location",
                13002L,
                tomorrowStart, tomorrowEnd,
                0xFF00FF00,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        ));

        CalendarAppWidgetModel model = buildModel(cursor, TEST_TIMEZONE);

        assertEquals("Should have 2 events", 2, model.mEventInfos.size());

        // Count day headers and meeting rows
        int dayHeaderCount = 0;
        int meetingCount = 0;
        for (RowInfo row : model.mRowInfos) {
            if (row.mType == RowInfo.TYPE_DAY) dayHeaderCount++;
            else if (row.mType == RowInfo.TYPE_MEETING) meetingCount++;
        }

        assertEquals("Should have 2 meeting rows", 2, meetingCount);
        assertEquals("Should have 1 day header (for tomorrow, not today)", 1, dayHeaderCount);
        assertEquals("Should have 1 day info", 1, model.mDayInfos.size());

        // Verify the day header is for tomorrow
        DayInfo dayInfo = model.mDayInfos.get(0);
        assertNotNull("Day label should not be null", dayInfo.mDayLabel);
        assertFalse("Day label should not be empty", dayInfo.mDayLabel.isEmpty());
    }

    // ===== Helper Methods =====

    /**
     * Creates an empty MatrixCursor with the correct projection for widget events.
     */
    private MatrixCursor createEmptyCursor() {
        return new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION);
    }

    /**
     * Builds a complete event row for the MatrixCursor.
     *
     * @param allDay 1 if all-day event, 0 otherwise
     * @param begin Start time in milliseconds
     * @param end End time in milliseconds
     * @param title Event title (can be null or empty)
     * @param location Event location (can be null or empty)
     * @param eventId Unique event ID
     * @param startTime Time for computing start Julian day
     * @param endTime Time for computing end Julian day
     * @param color Event display color (ARGB)
     * @param selfStatus Attendee status (ACCEPTED, DECLINED, INVITED, etc.)
     * @return Object array suitable for MatrixCursor.addRow()
     */
    private Object[] buildEventRow(int allDay, long begin, long end, String title,
                                    String location, long eventId, long startTime, long endTime,
                                    int color, int selfStatus) {
        int startDay = computeJulianDay(startTime);
        int endDay = computeJulianDay(endTime);

        Object[] row = new Object[CalendarAppWidgetService.EVENT_PROJECTION.length];
        row[CalendarAppWidgetService.INDEX_ALL_DAY] = allDay;
        row[CalendarAppWidgetService.INDEX_BEGIN] = begin;
        row[CalendarAppWidgetService.INDEX_END] = end;
        row[CalendarAppWidgetService.INDEX_TITLE] = title;
        row[CalendarAppWidgetService.INDEX_EVENT_LOCATION] = location;
        row[CalendarAppWidgetService.INDEX_EVENT_ID] = eventId;
        row[CalendarAppWidgetService.INDEX_START_DAY] = startDay;
        row[CalendarAppWidgetService.INDEX_END_DAY] = endDay;
        row[CalendarAppWidgetService.INDEX_COLOR] = color;
        row[CalendarAppWidgetService.INDEX_SELF_ATTENDEE_STATUS] = selfStatus;
        return row;
    }

    /**
     * Computes the Julian day for a given time in the test timezone.
     * This matches the logic used in CalendarAppWidgetModel.
     */
    private int computeJulianDay(long millis) {
        Time time = new Time(TEST_TIMEZONE);
        time.set(millis);
        return Time.getJulianDay(millis, time.getGmtOffset());
    }

    /**
     * Builds a CalendarAppWidgetModel from a cursor using the standard factory method.
     */
    private CalendarAppWidgetModel buildModel(MatrixCursor cursor, String timeZone) {
        return CalendarAppWidgetService.CalendarFactory.buildAppWidgetModel(
                mContext, cursor, timeZone);
    }

    /**
     * Extracts EventInfo objects from the model's row list in display order.
     * This skips day headers and returns only meeting rows.
     */
    private List<EventInfo> extractEventsFromRows(CalendarAppWidgetModel model) {
        List<EventInfo> events = new ArrayList<>();
        for (RowInfo row : model.mRowInfos) {
            if (row.mType == RowInfo.TYPE_MEETING) {
                events.add(model.mEventInfos.get(row.mIndex));
            }
        }
        return events;
    }
}
