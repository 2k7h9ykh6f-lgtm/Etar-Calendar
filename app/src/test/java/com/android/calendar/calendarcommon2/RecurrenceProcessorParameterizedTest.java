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

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

/**
 * Parameterized coverage for {@link RecurrenceProcessor#expand} focused on the
 * complex RRULE combinations that are easy to get wrong:
 *
 * <ol>
 *   <li>DST boundary crossing (wall-clock stability)</li>
 *   <li>UNTIL expressed in UTC vs. local time</li>
 *   <li>COUNT and UNTIL present at the same time</li>
 *   <li>EXDATE / RDATE, including mixing the two</li>
 *   <li>BYDAY / BYMONTHDAY / BYSETPOS combinations</li>
 *   <li>Leap-year February 29</li>
 *   <li>Month-end recurrence</li>
 *   <li>Week-start-day (WKST) differences</li>
 * </ol>
 *
 * <p>Each case fixes its own time zone so results never depend on the machine
 * default time zone, and states the full input (DTSTART / RRULE / RDATE /
 * EXDATE) together with the exact expected instance list. Expected values are
 * wall-clock {@code yyyyMMdd'T'HHmmss} strings in the case's time zone, which
 * is what {@link Time#format2445()} emits.
 *
 * <p>The expected values were derived from the documented engine behavior (see
 * {@code RecurrenceProcessor.expand}); notably UNTIL with a {@code Z} suffix or
 * a 15-char date-time form is interpreted as UTC and converted into DTSTART's
 * zone, whereas a date-only UNTIL is interpreted as local midnight.
 */
@RunWith(Parameterized.class)
public class RecurrenceProcessorParameterizedTest {

    private static final String LA = "America/Los_Angeles";

    @Parameter(0)
    public String name;
    @Parameter(1)
    public String tz;
    @Parameter(2)
    public String dtstart;
    @Parameter(3)
    public String rrule;
    @Parameter(4)
    public String rdate;
    @Parameter(5)
    public String exrule;
    @Parameter(6)
    public String exdate;
    @Parameter(7)
    public String rangeStart;
    @Parameter(8)
    public String rangeEnd;
    @Parameter(9)
    public String[] expected;

    @Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{

                // ---- 1. DST crossing ------------------------------------------------
                // Daily noon event crossing the 2021 spring-forward (Sun Mar 14, when
                // 02:00 jumps to 03:00). Noon is unambiguous, so the wall clock stays
                // fixed at 12:00 across the transition.
                {"dst_spring_forward_daily_noon", LA,
                        "20210313T120000", "FREQ=DAILY;COUNT=3", null, null, null,
                        "20210301T000000", "20210401T000000",
                        new String[]{
                                "20210313T120000",
                                "20210314T120000",
                                "20210315T120000",
                        }},
                // Daily noon event crossing the 2021 fall-back (Sun Nov 7, when 02:00
                // repeats). Wall clock again stays fixed at 12:00.
                {"dst_fall_back_daily_noon", LA,
                        "20211106T120000", "FREQ=DAILY;COUNT=3", null, null, null,
                        "20211101T000000", "20211201T000000",
                        new String[]{
                                "20211106T120000",
                                "20211107T120000",
                                "20211108T120000",
                        }},

                // ---- 2. UNTIL in UTC vs. local time --------------------------------
                // January is PST (UTC-8). A UTC date-time UNTIL is converted to the
                // DTSTART zone: 17:30Z on Jan 12 == 09:30 PST, so the Jan 12 10:00
                // instance falls after the cutoff and is excluded.
                {"until_utc_datetime_shifts", LA,
                        "20210110T100000", "FREQ=DAILY;UNTIL=20210112T173000Z", null, null, null,
                        "20210101T000000", "20210201T000000",
                        new String[]{
                                "20210110T100000",
                                "20210111T100000",
                        }},
                // A date-only UNTIL is treated as local midnight: Jan 13 00:00 local,
                // so the Jan 12 10:00 instance is kept. Same calendar neighbourhood as
                // above but a different (local) interpretation -> one extra instance.
                {"until_dateonly_is_local", LA,
                        "20210110T100000", "FREQ=DAILY;UNTIL=20210113", null, null, null,
                        "20210101T000000", "20210201T000000",
                        new String[]{
                                "20210110T100000",
                                "20210111T100000",
                                "20210112T100000",
                        }},

                // ---- 3. COUNT and UNTIL together -----------------------------------
                // Both are allowed (the parser only logs a warning). Here COUNT=3 is
                // the binding limit; UNTIL (Feb 1) is far away.
                {"count_binds_before_until", LA,
                        "20210110T100000", "FREQ=DAILY;COUNT=3;UNTIL=20210201T000000Z",
                        null, null, null,
                        "20210101T000000", "20210301T000000",
                        new String[]{
                                "20210110T100000",
                                "20210111T100000",
                                "20210112T100000",
                        }},
                // Here UNTIL binds first. 20:00Z on Jan 14 == 12:00 PST, after the
                // 10:00 instance, so Jan 14 is included and Jan 15 stops it -> 5
                // instances even though COUNT=10.
                {"until_binds_before_count", LA,
                        "20210110T100000", "FREQ=DAILY;COUNT=10;UNTIL=20210114T200000Z",
                        null, null, null,
                        "20210101T000000", "20210301T000000",
                        new String[]{
                                "20210110T100000",
                                "20210111T100000",
                                "20210112T100000",
                                "20210113T100000",
                                "20210114T100000",
                        }},

                // ---- 4. EXDATE / RDATE, including mixed ------------------------------
                // RDATE adds explicit instances (TZID prefix -> interpreted as LA wall
                // time). RRULE yields the two Tuesdays Jan 5 and Jan 12; RDATE adds
                // Jan 8 09:00 and Jan 20 12:00. Result is the merged, sorted set.
                {"rdate_adds_instances", LA,
                        "20210105T090000", "FREQ=WEEKLY;COUNT=2",
                        LA + ";20210108T090000,20210120T120000", null, null,
                        "20210101T000000", "20210201T000000",
                        new String[]{
                                "20210105T090000",
                                "20210108T090000",
                                "20210112T090000",
                                "20210120T120000",
                        }},
                // EXDATE is applied last, so it can cancel both an RRULE instance
                // (Jan 12) and an RDATE instance (Jan 20).
                {"rdate_and_exdate_mixed", LA,
                        "20210105T090000", "FREQ=WEEKLY;COUNT=2",
                        LA + ";20210108T090000,20210120T120000", null,
                        LA + ";20210112T090000,20210120T120000",
                        "20210101T000000", "20210201T000000",
                        new String[]{
                                "20210105T090000",
                                "20210108T090000",
                        }},
                // DST-aware EXDATE matching in summer: July is PDT (UTC-7), so the UTC
                // EXDATE 17:00Z on Jul 8 == 10:00 PDT and cancels the Jul 8 instance.
                {"exdate_utc_summer_dst", LA,
                        "20210706T100000", "FREQ=DAILY;COUNT=4",
                        null, null, "20210708T170000Z",
                        "20210701T000000", "20210801T000000",
                        new String[]{
                                "20210706T100000",
                                "20210707T100000",
                                "20210709T100000",
                        }},

                // ---- 5. BYDAY / BYMONTHDAY / BYSETPOS combinations ------------------
                // First and last weekday of each month (the supported monthly
                // BYSETPOS shape). Jan 2021: first weekday Fri Jan 1, last Fri Jan 29;
                // Feb: Mon Feb 1, Fri Feb 26; Mar: Mon Mar 1, Wed Mar 31.
                {"monthly_bysetpos_first_last", LA,
                        "20210101T090000",
                        "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1,-1;COUNT=6",
                        null, null, null,
                        "20210101T000000", "20210401T000000",
                        new String[]{
                                "20210101T090000",
                                "20210129T090000",
                                "20210201T090000",
                                "20210226T090000",
                                "20210301T090000",
                                "20210331T090000",
                        }},
                // BYMONTHDAY with a positive and a negative day: the 15th and the last
                // day of the month, month-length aware (Jan 31 vs Feb 28).
                {"monthly_bymonthday_pos_neg", LA,
                        "20210115T080000", "FREQ=MONTHLY;BYMONTHDAY=15,-1;COUNT=4",
                        null, null, null,
                        "20210101T000000", "20210401T000000",
                        new String[]{
                                "20210115T080000",
                                "20210131T080000",
                                "20210215T080000",
                                "20210228T080000",
                        }},
                // Ordinal BYDAY: 2nd Monday and last Friday of each month.
                {"monthly_byday_ordinal", LA,
                        "20210111T070000", "FREQ=MONTHLY;BYDAY=2MO,-1FR;COUNT=4",
                        null, null, null,
                        "20210101T000000", "20210401T000000",
                        new String[]{
                                "20210111T070000",
                                "20210129T070000",
                                "20210208T070000",
                                "20210226T070000",
                        }},

                // ---- 6. Leap-year February 29 --------------------------------------
                // Yearly on Feb 29 only lands in leap years; non-leap years are
                // skipped (2021/2022/2023), so the next instances are 2024 and 2028.
                {"yearly_feb29_leap_only", LA,
                        "20200229T100000", "FREQ=YEARLY;COUNT=3", null, null, null,
                        "20200101T000000", "20300101T000000",
                        new String[]{
                                "20200229T100000",
                                "20240229T100000",
                                "20280229T100000",
                        }},
                // Monthly on day 29 skips February in a non-leap year (no Feb 29 in
                // 2021), so Jan 29 is followed by Mar 29.
                {"monthly_day29_skips_feb", LA,
                        "20210129T100000", "FREQ=MONTHLY;BYMONTHDAY=29;COUNT=4",
                        null, null, null,
                        "20210101T000000", "20220101T000000",
                        new String[]{
                                "20210129T100000",
                                "20210329T100000",
                                "20210429T100000",
                                "20210529T100000",
                        }},

                // ---- 7. Month-end recurrence ---------------------------------------
                // Day 31 only exists in some months; Feb/Apr/Jun are skipped.
                {"monthly_day31_skips_short_months", LA,
                        "20210131T100000", "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=4",
                        null, null, null,
                        "20210101T000000", "20220101T000000",
                        new String[]{
                                "20210131T100000",
                                "20210331T100000",
                                "20210531T100000",
                                "20210731T100000",
                        }},
                // BYMONTHDAY=-1 is the last day of every month, which always exists.
                {"monthly_last_day_every_month", LA,
                        "20210131T100000", "FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=4",
                        null, null, null,
                        "20210101T000000", "20220101T000000",
                        new String[]{
                                "20210131T100000",
                                "20210228T100000",
                                "20210331T100000",
                                "20210430T100000",
                        }},

                // ---- 8. WKST differences -------------------------------------------
                // Same DTSTART and rule, only WKST differs. With an even INTERVAL the
                // week-start choice changes which weeks are "on", producing different
                // instances. These two outcomes mirror the canonical testWeekly9/10.
                {"weekly_wkst_monday", LA,
                        "19970805T100000", "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=MO",
                        null, null, null,
                        "19970101T000000", "19980101T000000",
                        new String[]{
                                "19970805T100000",
                                "19970810T100000",
                                "19970819T100000",
                                "19970824T100000",
                        }},
                {"weekly_wkst_sunday", LA,
                        "19970805T100000", "FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU;WKST=SU",
                        null, null, null,
                        "19970101T000000", "19980101T000000",
                        new String[]{
                                "19970805T100000",
                                "19970817T100000",
                                "19970819T100000",
                                "19970831T100000",
                        }},
        });
    }

    @Test
    public void expandMatchesExpectedInstances() throws Exception {
        Time dtstartTime = new Time(tz);
        dtstartTime.parse(dtstart);

        Time rangeStartTime = new Time(tz);
        rangeStartTime.parse(rangeStart);

        Time rangeEndTime = new Time(tz);
        rangeEndTime.parse(rangeEnd);

        RecurrenceProcessor rp = new RecurrenceProcessor();
        RecurrenceSet recur = new RecurrenceSet(rrule, rdate, exrule, exdate);

        long[] instances = rp.expand(dtstartTime, recur,
                rangeStartTime.toMillis(), rangeEndTime.toMillis());

        Time formatter = new Time(tz);
        String[] actual = new String[instances.length];
        for (int i = 0; i < instances.length; i++) {
            formatter.set(instances[i]);
            actual[i] = formatter.format2445();
        }

        assertArrayEquals("case '" + name + "' DTSTART=" + dtstart + " RRULE=" + rrule
                        + " RDATE=" + rdate + " EXDATE=" + exdate
                        + "\nexpected=" + Arrays.toString(expected)
                        + "\nactual  =" + Arrays.toString(actual),
                expected, actual);
    }
}
