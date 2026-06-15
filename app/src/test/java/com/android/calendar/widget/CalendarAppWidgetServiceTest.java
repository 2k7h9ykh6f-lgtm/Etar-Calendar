/*
**
** Copyright 2010, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.calendar.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.MatrixCursor;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Instances;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;

import com.android.calendar.Utils;
import com.android.calendar.calendarcommon2.Time;
import com.android.calendar.widget.CalendarAppWidgetModel.EventInfo;
import com.android.calendar.widget.CalendarAppWidgetModel.RowInfo;
import com.android.calendar.widget.CalendarAppWidgetService.CalendarFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Unit tests for {@link CalendarAppWidgetModel} via the
 * {@link CalendarFactory#buildAppWidgetModel(Context, android.database.Cursor, String)} seam.
 *
 * <p>These run as local JVM unit tests under Robolectric (see {@code testOptions.unitTests} and the
 * {@code robolectric} test dependency), because the model touches the Android framework
 * ({@code MatrixCursor}, {@code Utils.formatDateRange}, {@code TextUtils}, {@code View}, resources).
 * Run with:
 * <pre>./gradlew :app:testDebugUnitTest --tests "com.android.calendar.widget.CalendarAppWidgetServiceTest"</pre>
 *
 * <p><b>Determinism:</b> {@code Locale} and {@code TimeZone} are pinned in {@link #setUp()} so output
 * is identical on every machine. The model derives "now"/"today" from the real system clock with no
 * injection seam, so:
 * <ul>
 *   <li>Event placement into day buckets is driven entirely by the cursor's {@code START_DAY}/
 *       {@code END_DAY} columns (the model trusts those and never recomputes them from begin/end),
 *       so we set those explicitly relative to today's Julian day.</li>
 *   <li>{@code BEGIN}/{@code END} are only kept in the future so events survive the
 *       {@code end < now} filter; expected time strings are derived from the very same
 *       {@code Utils.formatDateRange(...)} call the model uses, never hard-coded.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CalendarAppWidgetServiceTest {

    private static final String DEFAULT_TIMEZONE = "America/Los_Angeles";
    private static final String ALT_TIMEZONE = "America/New_York";

    private final long ONE_MINUTE = 60000;
    private final long ONE_HOUR = 60 * ONE_MINUTE;
    private final long TWO_HOURS = ONE_HOUR * 2;

    private final String title = "Title";
    private final String location = "Location";

    private Context mContext;
    /** Time zone passed to the model in the common case (equals the device default here). */
    private String mTimeZone;
    /** A future instant (2am tomorrow) guaranteed to pass the model's {@code end < now} filter. */
    private long now;
    /** A past instant used to exercise the filter. */
    private long mSysNow;
    /** Today's Julian day in {@link #mTimeZone}, computed the same way the model does. */
    private int mToday;
    private int mTomorrow;

    private TimeZone mSavedTimeZone;
    private Locale mSavedLocale;

    @Before
    public void setUp() {
        mSavedTimeZone = TimeZone.getDefault();
        mSavedLocale = Locale.getDefault();
        // Predictable locale + timezone so formatted strings and Julian days are machine-independent.
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone(DEFAULT_TIMEZONE));

        mContext = RuntimeEnvironment.getApplication();
        mTimeZone = Utils.getCurrentTimezone(); // == DEFAULT_TIMEZONE

        mSysNow = System.currentTimeMillis();
        mToday = julianDay(mSysNow, mTimeZone);
        mTomorrow = mToday + 1;

        // "now" anchored to 2am tomorrow, mirroring the historical test. Always in the future.
        Time time = new Time();
        time.set(mSysNow);
        time.setDay(time.getDay() + 1);
        time.setHour(2);
        time.setMinute(0);
        time.setSecond(0);
        now = time.normalize();
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(mSavedTimeZone);
        Locale.setDefault(mSavedLocale);
    }

    // ---------------------------------------------------------------------------------------------
    // Historical cases (preserved). _1Event is unchanged except getContext() -> mContext.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void testGetAppWidgetModel_1Event() throws Exception {
        CalendarAppWidgetModel expected =
                new CalendarAppWidgetModel(mContext, Utils.getCurrentTimezone());
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);

        // allDay, begin, end, title, location, eventId
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, title, location, 0));

        EventInfo eventInfo = new EventInfo();
        eventInfo.visibWhen = View.VISIBLE;
        eventInfo.visibWhere = View.VISIBLE;
        eventInfo.visibTitle = View.VISIBLE;
        eventInfo.when = Utils.formatDateRange(mContext, now + ONE_HOUR, now + TWO_HOURS,
                DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_ALL);
        eventInfo.where = location;
        eventInfo.title = title;
        expected.mEventInfos.add(eventInfo);

        CalendarAppWidgetModel actual =
                CalendarFactory.buildAppWidgetModel(mContext, cursor, Utils.getCurrentTimezone());

        assertEquals(expected.toString(), actual.toString());
    }

    /**
     * Historical scenario (a timed event today + an all-day event five days out). The original
     * assertion compared against a hand-built model that omitted the second event and formatted it
     * with {@code formatDateTime} instead of the model's {@code formatDateRange}, so it could never
     * match. Here we assert what the model actually produces: both events are parsed, in cursor
     * order, with the later one flagged all-day.
     */
    @Test
    public void testGetAppWidgetModel_AllDayEventLater() throws Exception {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);

        // Timed event today.
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, title + 0, location + 0, 0));

        // All-day event five days out, stored at midnight UTC (the on-disk convention).
        Time time = new Time();
        time.set(now);
        time.setDay(time.getDay() + 5);
        time.setHour(0);
        time.setTimezone(Time.TIMEZONE_UTC);
        long start = time.normalize();
        time.setDay(time.getDay() + 1);
        long end = time.normalize();
        cursor.addRow(getRow(1, start, end, title + 1, location + 1, 0));

        CalendarAppWidgetModel actual =
                CalendarFactory.buildAppWidgetModel(mContext, cursor, Utils.getCurrentTimezone());

        assertEquals(2, actual.mEventInfos.size());

        EventInfo first = actual.mEventInfos.get(0);
        assertFalse(first.allDay);
        assertEquals(title + 0, first.title);
        assertEquals(Utils.formatDateRange(mContext, now + ONE_HOUR, now + TWO_HOURS,
                DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_ALL), first.when);

        EventInfo second = actual.mEventInfos.get(1);
        assertTrue(second.allDay);
        assertEquals(title + 1, second.title);
    }

    // ---------------------------------------------------------------------------------------------
    // Counts / empty data
    // ---------------------------------------------------------------------------------------------

    @Test
    public void emptyCursor_producesNoEventsAndNoRows() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        assertTrue(model.mEventInfos.isEmpty());
        assertTrue(model.mRowInfos.isEmpty());
        assertTrue(model.mDayInfos.isEmpty());
    }

    @Test
    public void singleTimedEventToday_hasNoDayHeader() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, title, location, 1,
                mToday, mToday, 0, 0));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        assertEquals(1, model.mEventInfos.size());
        // Today never gets a day header, so the single row is the event itself.
        assertEquals(0, model.mDayInfos.size());
        assertEquals(1, model.mRowInfos.size());
        assertEquals(RowInfo.TYPE_MEETING, model.mRowInfos.get(0).mType);

        EventInfo e = model.mEventInfos.get(0);
        assertEquals(title, e.title);
        assertEquals(View.VISIBLE, e.visibWhere);
        assertEquals(expectedTimedWhen(now + ONE_HOUR, now + TWO_HOURS, false, null), e.when);
    }

    // ---------------------------------------------------------------------------------------------
    // Ordering: all-day floats to the top of its day regardless of cursor order
    // ---------------------------------------------------------------------------------------------

    @Test
    public void singleDayMultipleEvents_allDaySortsFirst() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        // Use tomorrow so the bucket also emits a day header. Deliberately place the all-day event
        // in the MIDDLE of cursor order to prove the model (not the input order) hoists it first.
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, "T10", "L10", 10,
                mTomorrow, mTomorrow, 0, 0));
        cursor.addRow(getRow(1, utcMidnight(mTomorrow), utcMidnight(mTomorrow + 1), "AllDay", null,
                11, mTomorrow, mTomorrow, 0, 0));
        cursor.addRow(getRow(0, now + 3 * ONE_HOUR, now + 4 * ONE_HOUR, "T14", "L14", 12,
                mTomorrow, mTomorrow, 0, 0));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        assertEquals(3, model.mEventInfos.size());
        assertEquals(1, model.mDayInfos.size());
        // [DAY(tomorrow), allDay, T10, T14]
        assertEquals(4, model.mRowInfos.size());
        assertEquals(RowInfo.TYPE_DAY, model.mRowInfos.get(0).mType);

        RowInfo firstEventRow = model.mRowInfos.get(1);
        assertEquals(RowInfo.TYPE_MEETING, firstEventRow.mType);
        assertTrue("all-day event must be hoisted to the top of the day",
                model.mEventInfos.get(firstEventRow.mIndex).allDay);

        assertEquals("T10", model.mEventInfos.get(model.mRowInfos.get(2).mIndex).title);
        assertEquals("T14", model.mEventInfos.get(model.mRowInfos.get(3).mIndex).title);
    }

    // ---------------------------------------------------------------------------------------------
    // Multi-day / cross-midnight: one event appears in each day bucket it spans
    // ---------------------------------------------------------------------------------------------

    @Test
    public void crossMidnightEvent_appearsInBothDayBuckets() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        cursor.addRow(getRow(0, now + ONE_HOUR, now + 26 * ONE_HOUR, "Overnight", location, 7,
                mToday, mTomorrow, 0, 0));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        // A single event, but rendered once under today (no header) and once under tomorrow (header).
        assertEquals(1, model.mEventInfos.size());
        assertEquals(1, model.mDayInfos.size());
        assertEquals(3, model.mRowInfos.size());
        assertEquals(RowInfo.TYPE_MEETING, model.mRowInfos.get(0).mType);
        assertEquals(RowInfo.TYPE_DAY, model.mRowInfos.get(1).mType);
        assertEquals(RowInfo.TYPE_MEETING, model.mRowInfos.get(2).mType);
        // Both meeting rows point at the same (only) event.
        assertEquals(0, model.mRowInfos.get(0).mIndex);
        assertEquals(0, model.mRowInfos.get(2).mIndex);
    }

    // ---------------------------------------------------------------------------------------------
    // All-day events: UTC -> local conversion and date text
    // ---------------------------------------------------------------------------------------------

    @Test
    public void allDayEventToday_convertedToLocalAndShowsDate() {
        long utcStart = utcMidnight(mToday);
        long utcEnd = utcMidnight(mToday + 1);
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        cursor.addRow(getRow(1, utcStart, utcEnd, title, location, 1, mToday, mToday, 0, 0));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        assertEquals(1, model.mEventInfos.size());
        EventInfo e = model.mEventInfos.get(0);
        assertTrue(e.allDay);
        assertEquals(View.VISIBLE, e.visibWhen);

        // The model converts all-day UTC times into the device-local zone (mTimeZone here).
        Time recycle = new Time();
        long expStart = Utils.convertAlldayUtcToLocal(recycle, utcStart, mTimeZone);
        long expEnd = Utils.convertAlldayUtcToLocal(recycle, utcEnd, mTimeZone);
        assertEquals(expStart, e.start);
        assertEquals(expEnd, e.end);
        assertEquals(Utils.formatDateRange(mContext, expStart, expEnd,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL),
                e.when);
    }

    // ---------------------------------------------------------------------------------------------
    // Past events are dropped
    // ---------------------------------------------------------------------------------------------

    @Test
    public void pastEvent_isFilteredOut() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        // Ends before "now" (the real system clock the model reads), so it must be filtered.
        cursor.addRow(getRow(0, mSysNow - TWO_HOURS, mSysNow - ONE_HOUR, title, location, 1,
                mToday, mToday, 0, 0));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        assertTrue(model.mEventInfos.isEmpty());
        assertTrue(model.mRowInfos.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Missing / empty fields
    // ---------------------------------------------------------------------------------------------

    @Test
    public void missingTitleAndLocation_usesFallbackTitleAndHidesWhere() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, null, null, 1,
                mToday, mToday, 0, 0));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        assertEquals(1, model.mEventInfos.size());
        EventInfo e = model.mEventInfos.get(0);
        assertEquals(mContext.getString(ws.xsoh.etar.R.string.no_title_label), e.title);
        assertEquals(View.VISIBLE, e.visibTitle);
        assertEquals(View.GONE, e.visibWhere);
    }

    /**
     * When the {@code START_DAY}/{@code END_DAY} columns are absent (0), the event is still parsed
     * into {@code mEventInfos} but cannot be placed into any day bucket, so it produces no rows.
     * This documents the contract that day placement depends on those columns.
     */
    @Test
    public void missingDayColumns_eventParsedButNotBucketed() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        // 6-arg overload leaves startDay/endDay/color/status = 0.
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, title, location, 1));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        assertEquals(1, model.mEventInfos.size());
        assertTrue(model.mRowInfos.isEmpty());
        assertTrue(model.mDayInfos.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Home (target) timezone differs from device timezone -> suffix on timed events
    // ---------------------------------------------------------------------------------------------

    @Test
    public void homeTimezoneDiffersFromDevice_appendsTimezoneSuffix() {
        // Device default stays America/Los_Angeles; ask the model to render for America/New_York.
        int todayAlt = julianDay(mSysNow, ALT_TIMEZONE);
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, title, location, 1,
                todayAlt, todayAlt, 0, 0));

        CalendarAppWidgetModel model = build(cursor, ALT_TIMEZONE);

        assertEquals(1, model.mEventInfos.size());
        String homeName =
                TimeZone.getTimeZone(ALT_TIMEZONE).getDisplayName(false, TimeZone.SHORT);
        String expected = expectedTimedWhen(now + ONE_HOUR, now + TWO_HOURS, false, homeName);
        assertEquals(expected, model.mEventInfos.get(0).when);
        assertTrue(model.mEventInfos.get(0).when.endsWith(homeName));
    }

    @Test
    public void homeTimezoneMatchesDevice_noTimezoneSuffix() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, title, location, 1,
                mToday, mToday, 0, 0));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        String homeName = TimeZone.getTimeZone(mTimeZone).getDisplayName(false, TimeZone.SHORT);
        assertFalse(model.mEventInfos.get(0).when.endsWith(homeName));
    }

    // ---------------------------------------------------------------------------------------------
    // Sort stability
    // ---------------------------------------------------------------------------------------------

    @Test
    public void identicalCursors_produceIdenticalModelAndRowOrder() {
        CalendarAppWidgetModel a = build(multiEventCursor(), mTimeZone);
        CalendarAppWidgetModel b = build(multiEventCursor(), mTimeZone);

        assertEquals(a.toString(), b.toString());
        assertEquals(a.mRowInfos.size(), b.mRowInfos.size());
        for (int i = 0; i < a.mRowInfos.size(); i++) {
            assertEquals("row " + i + " type", a.mRowInfos.get(i).mType, b.mRowInfos.get(i).mType);
            assertEquals("row " + i + " index", a.mRowInfos.get(i).mIndex,
                    b.mRowInfos.get(i).mIndex);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DST switch days: all-day events must land on the correct calendar day across transitions.
    // The model's "now" can't be pinned to a DST date (no clock seam), so this exercises the exact
    // conversion + Julian-day helpers the widget uses to bucket all-day events.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void dstSpringForward_allDayEventLandsOnCorrectDay() {
        // 2024-03-10 02:00 PST -> 03:00 PDT in America/Los_Angeles.
        assertAllDayLandsOnDay(2024, 2 /* March, 0-based */, 10);
    }

    @Test
    public void dstFallBack_allDayEventLandsOnCorrectDay() {
        // 2024-11-03 02:00 PDT -> 01:00 PST in America/Los_Angeles.
        assertAllDayLandsOnDay(2024, 10 /* November, 0-based */, 3);
    }

    // ---------------------------------------------------------------------------------------------
    // Hidden / declined / unsynced calendars are filtered by the loader's SELECTION, not the model.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void widgetSelection_excludesInvisibleCalendars() throws Exception {
        assertEquals(Calendars.VISIBLE + "=1", readStaticString("EVENT_SELECTION"));
    }

    @Test
    public void widgetSelection_excludesInvisibleCalendarsAndDeclinedEvents() throws Exception {
        String expected = Calendars.VISIBLE + "=1 AND "
                + Instances.SELF_ATTENDEE_STATUS + "!=" + Attendees.ATTENDEE_STATUS_DECLINED;
        assertEquals(expected, readStaticString("EVENT_SELECTION_HIDE_DECLINED"));
    }

    /**
     * The model itself does not drop declined events (hiding declined/hidden/unsynced calendars is
     * the query's job via the SELECTION above). Confirm a declined row is still rendered and its
     * status is preserved for downstream styling.
     */
    @Test
    public void declinedEventInCursor_isStillRenderedByModel() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, title, location, 1,
                mToday, mToday, 0, Attendees.ATTENDEE_STATUS_DECLINED));

        CalendarAppWidgetModel model = build(cursor, mTimeZone);

        assertEquals(1, model.mEventInfos.size());
        assertEquals(Attendees.ATTENDEE_STATUS_DECLINED,
                model.mEventInfos.get(0).selfAttendeeStatus);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private CalendarAppWidgetModel build(MatrixCursor cursor, String timeZone) {
        return CalendarFactory.buildAppWidgetModel(mContext, cursor, timeZone);
    }

    /** Julian day for {@code millis} in {@code tz}, computed exactly like the model does. */
    private static int julianDay(long millis, String tz) {
        Time time = new Time(tz);
        time.set(millis);
        return Time.getJulianDay(millis, time.getGmtOffset());
    }

    /** UTC midnight (millis) at the start of the given Julian day. */
    private static long utcMidnight(int julianDay) {
        Time time = new Time(Time.TIMEZONE_UTC);
        return time.setJulianDay(julianDay);
    }

    /** Mirrors the model's "when" formatting for a single-day timed event. */
    private String expectedTimedWhen(long begin, long end, boolean showDate, String homeTzName) {
        int flags = DateUtils.FORMAT_ABBREV_ALL | DateUtils.FORMAT_SHOW_TIME;
        if (DateFormat.is24HourFormat(mContext)) {
            flags |= DateUtils.FORMAT_24HOUR;
        }
        if (showDate) {
            flags |= DateUtils.FORMAT_SHOW_DATE;
        }
        String when = Utils.formatDateRange(mContext, begin, end, flags);
        if (homeTzName != null) {
            when = when + " " + homeTzName;
        }
        return when;
    }

    private void assertAllDayLandsOnDay(int year, int month, int day) {
        Time utc = new Time(Time.TIMEZONE_UTC);
        utc.set(0, 0, 0, day, month, year);
        long utcMidnight = utc.normalize();
        int utcJulian = Time.getJulianDay(utcMidnight, 0);

        long local = Utils.convertAlldayUtcToLocal(null, utcMidnight, DEFAULT_TIMEZONE);
        Time lt = new Time(DEFAULT_TIMEZONE);
        lt.set(local);

        assertEquals("hour", 0, lt.getHour());
        assertEquals("day", day, lt.getDay());
        assertEquals("month", month, lt.getMonth());
        assertEquals("year", year, lt.getYear());
        assertEquals("julian day stable across DST", utcJulian,
                Time.getJulianDay(local, lt.getGmtOffset()));
        // Sanity: the converted local instant is not the same instant as the stored UTC midnight.
        assertNotEquals(utcMidnight, local);
    }

    private MatrixCursor multiEventCursor() {
        MatrixCursor cursor = new MatrixCursor(CalendarAppWidgetService.EVENT_PROJECTION, 0);
        cursor.addRow(getRow(0, now + ONE_HOUR, now + TWO_HOURS, "T10", "L10", 10,
                mTomorrow, mTomorrow, 0, 0));
        cursor.addRow(getRow(1, utcMidnight(mTomorrow), utcMidnight(mTomorrow + 1), "AllDay", null,
                11, mTomorrow, mTomorrow, 0, 0));
        cursor.addRow(getRow(0, now + 3 * ONE_HOUR, now + 4 * ONE_HOUR, "T14", "L14", 12,
                mTomorrow, mTomorrow, 0, 0));
        return cursor;
    }

    private String readStaticString(String fieldName) throws Exception {
        Field field = CalendarAppWidgetService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    /** Backwards-compatible 6-arg row: day/color/status default to 0 (no bucketing). */
    private Object[] getRow(int allDay, long begin, long end, String title, String location,
            long eventId) {
        return getRow(allDay, begin, end, title, location, eventId, 0, 0, 0, 0);
    }

    private Object[] getRow(int allDay, long begin, long end, String title, String location,
            long eventId, int startDay, int endDay, int color, int selfStatus) {
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
}
