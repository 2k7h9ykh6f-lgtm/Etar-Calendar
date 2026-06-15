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

package com.android.calendar.calendarcommon2;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Parameterized tests for {@link RecurrenceProcessor} covering complex RRULE
 * combinations that are prone to errors:
 *
 * <ul>
 *   <li>DST crossing (spring-forward and fall-back)</li>
 *   <li>UNTIL in UTC vs local time</li>
 *   <li>COUNT and UNTIL coexistence</li>
 *   <li>EXDATE / RDATE mixing</li>
 *   <li>BYDAY + BYMONTHDAY + BYSETPOS combinations</li>
 *   <li>Leap-year Feb 29</li>
 *   <li>End-of-month (day 31) monthly recurrence</li>
 *   <li>Week-start day (WKST) differences</li>
 * </ul>
 *
 * Each test case explicitly specifies DTSTART, RRULE, optional RDATE/EXDATE,
 * the expansion range, the expected instance list, and the expected last
 * occurrence. A fixed timezone is used for every case so that results are
 * independent of the host environment.
 */
@RunWith(Parameterized.class)
public class RecurrenceProcessorParamTest {

    // -----------------------------------------------------------------------
    // Parameters
    // -----------------------------------------------------------------------

    private final String mName;
    private final String mTz;
    private final String mDtstartStr;
    private final String mRrule;
    private final String mRdate;
    private final String mExrule;
    private final String mExdate;
    private final String mRangeStartStr;
    private final String mRangeEndStr;
    private final String[] mExpected;
    private final String mExpectedLast;

    public RecurrenceProcessorParamTest(String name, String tz,
            String dtstartStr, String rrule, String rdate, String exrule,
            String exdate, String rangeStartStr, String rangeEndStr,
            String[] expected, String expectedLast) {
        mName = name;
        mTz = tz;
        mDtstartStr = dtstartStr;
        mRrule = rrule;
        mRdate = rdate;
        mExrule = exrule;
        mExdate = exdate;
        mRangeStartStr = rangeStartStr;
        mRangeEndStr = rangeEndStr;
        mExpected = expected;
        mExpectedLast = expectedLast;
    }

    // -----------------------------------------------------------------------
    // Test data
    // -----------------------------------------------------------------------

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{

            // ===========================================================
            // 1. DST spring-forward (America/Los_Angeles 2024-03-10 02:00)
            //    Weekly Friday, 10:00 AM local, crossing the spring DST.
            // ===========================================================
            {
                "DST_spring_forward_weekly",
                "America/Los_Angeles",
                "20240301T100000",                                   // DTSTART Fri Mar 1 PST
                "FREQ=WEEKLY;COUNT=4;BYDAY=FR",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240301T100000",   // Mar  1 – PST
                    "20240308T100000",   // Mar  8 – PST
                    "20240315T100000",   // Mar 15 – PDT (DST Mar 10)
                    "20240322T100000",   // Mar 22 – PDT
                },
                null
            },

            // ===========================================================
            // 2. DST fall-back (America/Los_Angeles 2024-11-03 02:00)
            //    Weekly Friday, 10:00 AM local, crossing fall DST.
            // ===========================================================
            {
                "DST_fall_back_weekly",
                "America/Los_Angeles",
                "20241018T100000",                                   // DTSTART Fri Oct 18 PDT
                "FREQ=WEEKLY;COUNT=4;BYDAY=FR",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20241018T100000",   // Oct 18 – PDT
                    "20241025T100000",   // Oct 25 – PDT
                    "20241101T100000",   // Nov  1 – PDT
                    "20241108T100000",   // Nov  8 – PST (DST ended Nov 3)
                },
                null
            },

            // ===========================================================
            // 3. DST spring-forward daily across boundary
            //    Daily at 10 AM for 12 days crossing Mar 10.
            // ===========================================================
            {
                "DST_spring_forward_daily",
                "America/Los_Angeles",
                "20240305T100000",
                "FREQ=DAILY;COUNT=12",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240305T100000",
                    "20240306T100000",
                    "20240307T100000",
                    "20240308T100000",
                    "20240309T100000",
                    "20240310T100000",   // DST day
                    "20240311T100000",
                    "20240312T100000",
                    "20240313T100000",
                    "20240314T100000",
                    "20240315T100000",
                    "20240316T100000",
                },
                null
            },

            // ===========================================================
            // 4. UNTIL in UTC – event included at exact UNTIL boundary
            //    DTSTART 10:00 AM PST = 18:00 UTC.
            //    UNTIL=20240105T180000Z == Jan 5 10:00 AM PST → included.
            // ===========================================================
            {
                "UNTIL_utc_exact_boundary",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=DAILY;UNTIL=20240105T180000Z",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",
                    "20240102T100000",
                    "20240103T100000",
                    "20240104T100000",
                    "20240105T100000",
                },
                null
            },

            // ===========================================================
            // 5. UNTIL in UTC – one second before event time
            //    UNTIL=20240105T175959Z == Jan 5 09:59:59 AM PST
            //    → Jan 5 10:00 AM > UNTIL → excluded.
            //    getLastOccurence returns the UNTIL instant conservatively.
            // ===========================================================
            {
                "UNTIL_utc_one_second_before",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=DAILY;UNTIL=20240105T175959Z",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",
                    "20240102T100000",
                    "20240103T100000",
                    "20240104T100000",
                },
                "20240105T095959"    // getLastOccurence returns UNTIL time
            },

            // ===========================================================
            // 6. UNTIL in local time (no trailing Z)
            //    The code appends 'Z' when length==15, so UNTIL is parsed
            //    as UTC then converted to local.
            //    UNTIL=20240105T100000 → 20240105T100000Z
            //    → Jan 5 02:00 PST.  Jan 5 10:00 AM local > UNTIL → excluded.
            // ===========================================================
            {
                "UNTIL_local_time_no_Z",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=DAILY;UNTIL=20240105T100000",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",
                    "20240102T100000",
                    "20240103T100000",
                    "20240104T100000",
                },
                "20240105T100000"    // getLastOccurence returns UNTIL string as parsed
            },

            // ===========================================================
            // 7. UNTIL as date-only (8 chars, no time)
            //    UNTIL=20240105 is treated as 20240105 (no Z appended).
            //    Parsed as all-day: midnight UTC on Jan 5 → 4 PM PST Jan 4.
            //    Jan 5 10 AM PST > Jan 4 4 PM PST → excluded.
            // ===========================================================
            {
                "UNTIL_date_only",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=DAILY;UNTIL=20240105",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",
                    "20240102T100000",
                    "20240103T100000",
                    "20240104T100000",
                },
                "20240105T000000"    // getLastOccurence returns UNTIL date at midnight
            },

            // ===========================================================
            // 8. COUNT and UNTIL together – UNTIL fires first
            //    COUNT=10 would produce Jan 1-10, but UNTIL cuts at Jan 5.
            // ===========================================================
            {
                "COUNT_and_UNTIL_until_wins",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=DAILY;COUNT=10;UNTIL=20240105T180000Z",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",
                    "20240102T100000",
                    "20240103T100000",
                    "20240104T100000",
                    "20240105T100000",
                },
                null
            },

            // ===========================================================
            // 9. COUNT and UNTIL together – COUNT fires first
            //    COUNT=3, UNTIL far in the future → only 3 occurrences.
            // ===========================================================
            {
                "COUNT_and_UNTIL_count_wins",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=DAILY;COUNT=3;UNTIL=20241231T235959Z",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",
                    "20240102T100000",
                    "20240103T100000",
                },
                null
            },

            // ===========================================================
            // 10. EXDATE removes one occurrence from monthly series
            // ===========================================================
            {
                "EXDATE_removes_monthly_instance",
                "America/Los_Angeles",
                "20240115T100000",
                "FREQ=MONTHLY;COUNT=4;BYMONTHDAY=15",
                null, null,
                "America/Los_Angeles;20240215T100000",               // EXDATE
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240115T100000",
                    "20240315T100000",
                    "20240415T100000",
                    // COUNT=4 generated Jan,Feb,Mar,Apr; EXDATE removed Feb → 3 left
                },
                null
            },

            // ===========================================================
            // 11. RDATE adds extra instance, EXDATE removes another
            //     RRULE: Monthly 15th, COUNT=3 → Jan 15, Feb 15, Mar 15
            //     RDATE: Jan 20
            //     EXDATE: Feb 15
            //     Result: Jan 15, Jan 20, Mar 15
            // ===========================================================
            {
                "RDATE_adds_EXDATE_removes",
                "America/Los_Angeles",
                "20240115T100000",
                "FREQ=MONTHLY;COUNT=3;BYMONTHDAY=15",
                "America/Los_Angeles;20240120T100000",               // RDATE
                null,
                "America/Los_Angeles;20240215T100000",               // EXDATE
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240115T100000",
                    "20240120T100000",
                    "20240315T100000",
                },
                null
            },

            // ===========================================================
            // 12. EXDATE removes multiple daily occurrences
            //     DAILY;COUNT=7 → Jan 1-7, remove Jan 3 and Jan 5
            // ===========================================================
            {
                "EXDATE_multiple_daily",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=DAILY;COUNT=7",
                null, null,
                "America/Los_Angeles;20240103T100000,20240105T100000",
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",
                    "20240102T100000",
                    "20240104T100000",
                    "20240106T100000",
                    "20240107T100000",
                },
                null
            },

            // ===========================================================
            // 13. BYDAY + BYMONTHDAY intersection: Friday the 13th
            //     FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13;COUNT=4
            //     2024: Sep 13 (Fri), Dec 13 (Fri)
            //     2025: Jun 13 (Fri)
            //     2026: Feb 13 (Fri)
            //     DTSTART Jan 1 2024 is added unconditionally as first instance.
            // ===========================================================
            {
                "BYDAY_plus_BYMONTHDAY_friday_13th",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13;COUNT=4",
                null, null, null,
                "20240101T000000", "20270101T000000",
                new String[]{
                    "20240101T100000",   // DTSTART (always included)
                    "20240913T100000",   // Sep 13 2024 = Friday
                    "20241213T100000",   // Dec 13 2024 = Friday
                    "20250613T100000",   // Jun 13 2025 = Friday
                },
                null
            },

            // ===========================================================
            // 14. BYSETPOS=-1 with BYDAY=MO..FR (last weekday of month)
            //     2024 Jan: 31(Wed), Feb: 29(Thu, leap!), Mar: 29(Fri),
            //     Apr: 30(Tue), May: 31(Fri), Jun: 28(Fri)
            // ===========================================================
            {
                "BYSETPOS_last_weekday_of_month",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=6",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",   // DTSTART
                    "20240131T100000",   // Jan last weekday
                    "20240229T100000",   // Feb last weekday (leap year)
                    "20240329T100000",   // Mar last weekday
                    "20240430T100000",   // Apr last weekday
                    "20240531T100000",   // May last weekday
                },
                null
            },

            // ===========================================================
            // 15. BYSETPOS=1 with BYDAY=SA,SU (first weekend day of month)
            //     2024 Jan: 6(Sat), Feb: 3(Sat), Mar: 2(Sat),
            //     Apr: 6(Sat), May: 4(Sat), Jun: 1(Sat)
            // ===========================================================
            {
                "BYSETPOS_first_weekend_day",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=MONTHLY;BYDAY=SA,SU;BYSETPOS=1;COUNT=5",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240101T100000",   // DTSTART
                    "20240106T100000",   // Jan first SA/SU
                    "20240203T100000",   // Feb first SA/SU
                    "20240302T100000",   // Mar first SA/SU
                    "20240406T100000",   // Apr first SA/SU
                },
                null
            },

            // ===========================================================
            // 16. Leap year: yearly on Feb 29
            //     Only leap years produce instances: 2024, 2028, 2032, 2036.
            // ===========================================================
            {
                "LEAP_YEAR_yearly_feb29",
                "America/Los_Angeles",
                "20240229T100000",
                "FREQ=YEARLY;COUNT=4",
                null, null, null,
                "20240101T000000", "20400101T000000",
                new String[]{
                    "20240229T100000",
                    "20280229T100000",
                    "20320229T100000",
                    "20360229T100000",
                },
                null
            },

            // ===========================================================
            // 17. Monthly on day 31 – short months are skipped
            //     Jan 31, (Feb skip), Mar 31, (Apr skip),
            //     May 31, (Jun skip), Jul 31, Aug 31, (Sep skip), Oct 31
            // ===========================================================
            {
                "MONTHLY_day31_skip_short_months",
                "America/Los_Angeles",
                "20240131T100000",
                "FREQ=MONTHLY;COUNT=6",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240131T100000",   // Jan
                    "20240331T100000",   // Mar (Feb skipped)
                    "20240531T100000",   // May (Apr skipped)
                    "20240731T100000",   // Jul (Jun skipped)
                    "20240831T100000",   // Aug
                    "20241031T100000",   // Oct (Sep skipped)
                },
                null
            },

            // ===========================================================
            // 18. Monthly on day 30 – Feb skipped, all others OK
            // ===========================================================
            {
                "MONTHLY_day30_feb_skipped",
                "America/Los_Angeles",
                "20240130T100000",
                "FREQ=MONTHLY;COUNT=6",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240130T100000",   // Jan
                    "20240330T100000",   // Mar (Feb skipped)
                    "20240430T100000",   // Apr
                    "20240530T100000",   // May
                    "20240630T100000",   // Jun
                    "20240730T100000",   // Jul
                },
                null
            },

            // ===========================================================
            // 19. WKST=MO – biweekly TU,SU starting on a Sunday
            //     With WKST=MO the week runs Mon-Sun.
            //     Week 1 (Jan 1-7):  SU Jan 7 (DTSTART), TU Jan 2 < DTSTART → filtered
            //     Week 3 (Jan 15-21): TU Jan 16, SU Jan 21
            //     Week 5 (Jan 29-Feb 4): TU Jan 30 → COUNT=4 reached
            // ===========================================================
            {
                "WKST_MO_biweekly",
                "America/Los_Angeles",
                "20240107T100000",                                   // Sunday
                "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=MO",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240107T100000",   // SU Jan 7
                    "20240116T100000",   // TU Jan 16
                    "20240121T100000",   // SU Jan 21
                    "20240130T100000",   // TU Jan 30
                },
                null
            },

            // ===========================================================
            // 20. WKST=SU – same rule, different week boundaries
            //     With WKST=SU the week runs Sun-Sat.
            //     Week 1 (Jan 7-13): SU Jan 7, TU Jan 9
            //     Week 3 (Jan 21-27): SU Jan 21, TU Jan 23
            // ===========================================================
            {
                "WKST_SU_biweekly",
                "America/Los_Angeles",
                "20240107T100000",                                   // Sunday
                "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=SU",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240107T100000",   // SU Jan 7
                    "20240109T100000",   // TU Jan 9
                    "20240121T100000",   // SU Jan 21
                    "20240123T100000",   // TU Jan 23
                },
                null
            },

            // ===========================================================
            // 21. Monthly BYMONTHDAY=-1 (last day of every month)
            // ===========================================================
            {
                "MONTHLY_negative_byday_last_day",
                "America/Los_Angeles",
                "20240131T100000",
                "FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=6",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240131T100000",   // Jan 31
                    "20240229T100000",   // Feb 29 (leap year)
                    "20240331T100000",   // Mar 31
                    "20240430T100000",   // Apr 30
                    "20240531T100000",   // May 31
                    "20240630T100000",   // Jun 30
                },
                null
            },

            // ===========================================================
            // 22. Yearly BYMONTH with multiple months
            //     FREQ=YEARLY;BYMONTH=2,4,6;COUNT=7
            //     DTSTART provides the day: day 15
            // ===========================================================
            {
                "YEARLY_multiple_bymonth",
                "UTC",
                "20240215T100000",
                "FREQ=YEARLY;BYMONTH=2,4,6;COUNT=7",
                null, null, null,
                "20240101T000000", "20270101T000000",
                new String[]{
                    "20240215T100000Z",
                    "20240415T100000Z",
                    "20240615T100000Z",
                    "20250215T100000Z",
                    "20250415T100000Z",
                    "20250615T100000Z",
                    "20260215T100000Z",
                },
                null
            },

            // ===========================================================
            // 23. RDATE only (no RRULE)
            // ===========================================================
            {
                "RDATE_only_no_rrule",
                "America/Los_Angeles",
                "20240101T100000",
                null,
                "America/Los_Angeles;20240315T100000,20240615T100000,20240915T100000",
                null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240315T100000",
                    "20240615T100000",
                    "20240915T100000",
                },
                null
            },

            // ===========================================================
            // 24. Monthly with INTERVAL=3 and BYDAY=-1FR (last Friday of
            //     every 3rd month).
            //     2024: Jan last Fri=26, Apr last Fri=26, Jul last Fri=26
            //     Oct last Fri=25
            // ===========================================================
            {
                "MONTHLY_interval3_last_friday",
                "America/Los_Angeles",
                "20240126T100000",
                "FREQ=MONTHLY;INTERVAL=3;BYDAY=-1FR;COUNT=4",
                null, null, null,
                "20240101T000000", "20260101T000000",
                new String[]{
                    "20240126T100000",   // Jan last Fri
                    "20240426T100000",   // Apr last Fri
                    "20240726T100000",   // Jul last Fri
                    "20241025T100000",   // Oct last Fri
                },
                null
            },

            // ===========================================================
            // 25. DST crossing with monthly event – the local time stays
            //     the same even though UTC offset changes.
            //     Monthly on the 10th at 10 AM, Jan-May 2024.
            //     DST happens Mar 10 but event is on 10th so time is fine.
            // ===========================================================
            {
                "DST_monthly_crossing",
                "America/Los_Angeles",
                "20240110T100000",
                "FREQ=MONTHLY;COUNT=5;BYMONTHDAY=10",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240110T100000",   // PST
                    "20240210T100000",   // PST
                    "20240310T100000",   // DST day – still 10 AM local
                    "20240410T100000",   // PDT
                    "20240510T100000",   // PDT
                },
                null
            },

            // ===========================================================
            // 26. Weekly with BYDAY spanning month boundary and UNTIL
            // ===========================================================
            {
                "WEEKLY_byday_month_boundary_until",
                "America/Los_Angeles",
                "20240129T100000",                                   // Monday
                "FREQ=WEEKLY;UNTIL=20240219T180000Z;BYDAY=MO,FR",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240129T100000",   // Mon Jan 29
                    "20240202T100000",   // Fri Feb 2
                    "20240205T100000",   // Mon Feb 5
                    "20240209T100000",   // Fri Feb 9
                    "20240212T100000",   // Mon Feb 12
                    "20240216T100000",   // Fri Feb 16
                    "20240219T100000",   // Mon Feb 19 (=UNTIL 18:00Z = 10AM PST, inclusive)
                },
                null
            },

            // ===========================================================
            // 27. EXDATE with UTC format to remove an instance
            //     Feb 15 10:00 AM PST = Feb 15 18:00 UTC
            // ===========================================================
            {
                "EXDATE_utc_format",
                "America/Los_Angeles",
                "20240115T100000",
                "FREQ=MONTHLY;COUNT=4;BYMONTHDAY=15",
                null, null,
                "20240215T180000Z",                                   // EXDATE in UTC
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240115T100000",
                    "20240315T100000",
                    "20240415T100000",
                    // COUNT=4 generated Jan,Feb,Mar,Apr; EXDATE removed Feb → 3 left
                },
                null
            },

            // ===========================================================
            // 28. BYDAY with positional prefix + BYMONTHDAY
            //     2nd Tuesday that is also the 14th (only possible if
            //     the 14th falls on a Tuesday).
            //     2024: no match in Jan (14th=Sun), May (14th=Tue) ✓
            // ===========================================================
            {
                "BYDAY_positional_plus_BYMONTHDAY",
                "America/Los_Angeles",
                "20240101T100000",
                "FREQ=MONTHLY;BYDAY=2TU;BYMONTHDAY=14;COUNT=3",
                null, null, null,
                "20240101T000000", "20260101T000000",
                new String[]{
                    "20240101T100000",   // DTSTART
                    "20240514T100000",   // May 14 2024 = 2nd Tue
                    "20250114T100000",   // Jan 14 2025 = 2nd Tue
                },
                null
            },

            // ===========================================================
            // 29. Yearly on Feb 29 with BYMONTH, verifying leap-only gen
            // ===========================================================
            {
                "YEARLY_feb29_bymonth",
                "UTC",
                "20240229T120000",
                "FREQ=YEARLY;BYMONTH=2;COUNT=3",
                null, null, null,
                "20240101T000000", "20400101T000000",
                new String[]{
                    "20240229T120000Z",
                    "20280229T120000Z",
                    "20320229T120000Z",
                },
                null
            },

            // ===========================================================
            // 30. DST in Europe/Berlin timezone – spring forward 2024-03-31
            //     Weekly Sunday at 10 AM crossing DST.
            // ===========================================================
            {
                "DST_europe_berlin_spring",
                "Europe/Berlin",
                "20240317T100000",                                   // Sun Mar 17 CET
                "FREQ=WEEKLY;COUNT=4;BYDAY=SU",
                null, null, null,
                "20240101T000000", "20250101T000000",
                new String[]{
                    "20240317T100000",   // CET
                    "20240324T100000",   // CET
                    "20240331T100000",   // DST day – CEST
                    "20240407T100000",   // CEST
                },
                null
            },

        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a {@link RecurrenceSet} without relying on
     * {@code android.text.TextUtils}, which is not available (mocked as
     * return-default-values) in local unit tests.
     *
     * <p>The constructor is called with valid dummy values so that
     * {@code init()} succeeds; the public fields are then overwritten
     * with the actual recurrence data.
     */
    private static RecurrenceSet buildRecurrenceSet(
            String rruleStr, String rdateStr,
            String exruleStr, String exdateStr) throws Exception {

        // Construct with dummy valid values (init() will succeed).
        RecurrenceSet rs = new RecurrenceSet(
                "FREQ=DAILY;COUNT=1",
                "UTC;20240101T000000",
                "FREQ=DAILY;COUNT=1",
                "UTC;20240101T000000");

        // Overwrite with real data.
        rs.rrules  = parseRRules(rruleStr);
        rs.rdates  = parseDates(rdateStr);
        rs.exrules = parseRRules(exruleStr);
        rs.exdates = parseDates(exdateStr);
        return rs;
    }

    private static EventRecurrence[] parseRRules(String ruleStr) {
        if (ruleStr == null || ruleStr.isEmpty()) return null;
        String[] parts = ruleStr.split("\n");
        EventRecurrence[] rules = new EventRecurrence[parts.length];
        for (int i = 0; i < parts.length; i++) {
            rules[i] = new EventRecurrence();
            rules[i].parse(parts[i]);
        }
        return rules;
    }

    private static long[] parseDates(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        return RecurrenceSet.parseRecurrenceDates(dateStr);
    }

    private static String[] formatDates(long[] dates, Time time) {
        String[] out = new String[dates.length];
        for (int i = 0; i < dates.length; i++) {
            time.set(dates[i]);
            out[i] = time.format2445();
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Test execution
    // -----------------------------------------------------------------------

    @Test
    public void testRecurrence() throws Exception {
        Time dtstart = new Time(mTz);
        Time rangeStart = new Time(mTz);
        Time rangeEnd = new Time(mTz);
        Time outCal = new Time(mTz);

        dtstart.parse(mDtstartStr);
        rangeStart.parse(mRangeStartStr);
        rangeEnd.parse(mRangeEndStr);

        RecurrenceProcessor rp = new RecurrenceProcessor();
        RecurrenceSet recur = buildRecurrenceSet(
                mRrule, mRdate, mExrule, mExdate);

        long[] out = rp.expand(dtstart, recur,
                rangeStart.toMillis(), rangeEnd.toMillis());

        String[] actual = formatDates(out, outCal);

        assertArrayEquals("[" + mName + "] instance list mismatch",
                mExpected, actual);

        // Verify last occurrence estimate
        String lastExpected = mExpectedLast != null
                ? mExpectedLast : mExpected[mExpected.length - 1];

        long lastOccur = rp.getLastOccurence(dtstart, rangeEnd, recur);
        if (lastOccur == 0 && out.length == 0) {
            return;
        }
        assertTrue("[" + mName + "] expected a valid last occurrence",
                lastOccur != -1);

        outCal.set(lastOccur);
        String lastStr = outCal.format2445();

        Time expectedLast = new Time(mTz);
        expectedLast.parse(lastExpected);
        String expectedLastStr = expectedLast.format2445();

        assertEquals("[" + mName + "] last occurrence mismatch",
                expectedLastStr, lastStr);
    }
}
