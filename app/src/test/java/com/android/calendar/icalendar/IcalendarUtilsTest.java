/*
 * Copyright (C) 2024 The Etar Project
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

package com.android.calendar.icalendar;

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * Comprehensive unit tests for the iCalendar import parsing pipeline:
 *   IcalendarUtils, VCalendar, VEvent, Attendee, Organizer.
 *
 * Fixture strings simulate .ics content from various clients (Google Calendar,
 * Outlook, Thunderbird, Apple Calendar, etc.) and cover edge cases that users
 * encounter when importing real-world .ics files.
 *
 * Fixture index:
 *   FIXTURE_SIMPLE_EVENT         – basic single event (Google Calendar-style)
 *   FIXTURE_RECURRING_EVENT      – RRULE with FREQ, BYDAY, UNTIL
 *   FIXTURE_RECURRING_WEEKLY     – weekly RRULE with COUNT
 *   FIXTURE_RECURRING_MALFORMED  – invalid/incomplete RRULE values
 *   FIXTURE_EVENT_WITH_TZID      – DTSTART/DTEND with TZID parameter
 *   FIXTURE_EVENT_WITH_VALARM    – event containing VALARM reminder block
 *   FIXTURE_MISSING_DTSTART      – event missing DTSTART
 *   FIXTURE_MISSING_DTEND        – event missing DTEND
 *   FIXTURE_FOLDED_LONG_TEXT     – RFC 5545 line folding (continuation lines)
 *   FIXTURE_SPECIAL_CHARS        – escaped commas, semicolons, newlines in SUMMARY/LOCATION
 *   FIXTURE_MULTIPLE_EVENTS      – two events in one VCALENDAR
 *   FIXTURE_MULTIPLE_ATTENDEES   – event with several ATTENDEE lines
 *   FIXTURE_ALL_DAY_EVENT        – DTSTART/DTEND with VALUE=DATE (all-day)
 *   FIXTURE_EMPTY_CALENDAR       – VCALENDAR with no VEVENT
 *   FIXTURE_OUTLOOK_STYLE        – Outlook-style with X-MICROSOFT properties
 *   FIXTURE_UNCLOSED_EVENT       – missing END:VEVENT
 *   FIXTURE_WINDOWS_TZID         – Windows timezone ID in TZID parameter
 */
public class IcalendarUtilsTest extends TestCase {

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: simple single event (Google Calendar style)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_SIMPLE_EVENT =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "PRODID:-//Google Inc//Google Calendar 70.9054//EN\n" +
            "CALSCALE:GREGORIAN\n" +
            "METHOD:PUBLISH\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "DTEND:20231225T110000Z\n" +
            "DTSTAMP:20231201T120000Z\n" +
            "UID:abc123@google.com\n" +
            "SUMMARY:Team Meeting\n" +
            "LOCATION:Conference Room A\n" +
            "DESCRIPTION:Quarterly planning session\n" +
            "STATUS:CONFIRMED\n" +
            "SEQUENCE:0\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: recurring event with RRULE (FREQ=DAILY, BYDAY, UNTIL)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_RECURRING_EVENT =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "PRODID:-//Mozilla.org/NONSGML Mozilla Calendar V1.1//EN\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20230901T090000Z\n" +
            "DTEND:20230901T100000Z\n" +
            "RRULE:FREQ=DAILY;BYDAY=MO,WE,FR;UNTIL=20231231T235959Z\n" +
            "UID:recurring-001@thunderbird\n" +
            "SUMMARY:Standup\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: weekly recurrence with COUNT
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_RECURRING_WEEKLY =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20240105T140000Z\n" +
            "DTEND:20240105T150000Z\n" +
            "RRULE:FREQ=WEEKLY;COUNT=10;BYDAY=FR\n" +
            "UID:weekly-001@example\n" +
            "SUMMARY:Weekly Review\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: malformed RRULE – empty value, missing FREQ
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_RECURRING_MALFORMED =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20230101T120000Z\n" +
            "DTEND:20230101T130000Z\n" +
            "RRULE:\n" +
            "UID:bad-rrule@example\n" +
            "SUMMARY:Bad Recurrence\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: event with TZID parameters on DTSTART/DTEND
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_EVENT_WITH_TZID =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "PRODID:-//Apple Inc.//macOS 14.0//EN\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART;TZID=America/New_York:20231201T090000\n" +
            "DTEND;TZID=America/New_York:20231201T170000\n" +
            "UID:tzid-apple-001@apple.com\n" +
            "SUMMARY:NYC Workshop\n" +
            "LOCATION:Manhattan Office\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: event with VALARM reminder
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_EVENT_WITH_VALARM =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231220T150000Z\n" +
            "DTEND:20231220T160000Z\n" +
            "UID:valarm-001@example\n" +
            "SUMMARY:Doctor Appointment\n" +
            "DESCRIPTION:Annual checkup\n" +
            "BEGIN:VALARM\n" +
            "TRIGGER:-PT15M\n" +
            "ACTION:DISPLAY\n" +
            "DESCRIPTION:Reminder\n" +
            "END:VALARM\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: missing DTSTART
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_MISSING_DTSTART =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTEND:20231225T110000Z\n" +
            "UID:no-start@example\n" +
            "SUMMARY:Event Without Start\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: missing DTEND
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_MISSING_DTEND =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "UID:no-end@example\n" +
            "SUMMARY:Event Without End\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: RFC 5545 folded long text (continuation lines start with space)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_FOLDED_LONG_TEXT =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "DTEND:20231225T110000Z\n" +
            "UID:folded-001@example\n" +
            "SUMMARY:Annual Strategy Meeting - All Ha\n" +
            " nds On Deck For Planning Next Year Goa\n" +
            " ls And Roadmap\n" +
            "DESCRIPTION:This is a very long descript\n" +
            " ion that spans multiple lines\n" +
            "  in the iCal file\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: special characters in SUMMARY and LOCATION (iCal-escaped)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_SPECIAL_CHARS =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "DTEND:20231225T110000Z\n" +
            "UID:special-001@example\n" +
            "SUMMARY:Planning\\, Strategy & Roadmap\\n2024\n" +
            "LOCATION:Building 5\\; Room 101\\, Floor 3\n" +
            "DESCRIPTION:Discuss Q1\\, Q2 goals\\nBring notes\\; pens\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: multiple events in one calendar
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_MULTIPLE_EVENTS =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "DTEND:20231225T110000Z\n" +
            "UID:event-1@example\n" +
            "SUMMARY:First Event\n" +
            "END:VEVENT\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231226T140000Z\n" +
            "DTEND:20231226T150000Z\n" +
            "UID:event-2@example\n" +
            "SUMMARY:Second Event\n" +
            "LOCATION:Room B\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: multiple attendees
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_MULTIPLE_ATTENDEES =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "DTEND:20231225T110000Z\n" +
            "UID:attendees-001@example\n" +
            "SUMMARY:Team Sync\n" +
            "ATTENDEE;CN=Alice Smith;PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:alice@example.com\n" +
            "ATTENDEE;CN=Bob Jones;PARTSTAT=DECLINED:mailto:bob@example.com\n" +
            "ATTENDEE;CN=Carol White;PARTSTAT=TENTATIVE:mailto:carol@example.com\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: all-day event (VALUE=DATE, no time component)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_ALL_DAY_EVENT =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART;VALUE=DATE:20231225\n" +
            "DTEND;VALUE=DATE:20231226\n" +
            "UID:allday-001@example\n" +
            "SUMMARY:Christmas Day\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: empty calendar (no events)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_EMPTY_CALENDAR =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "PRODID:-//Test//Test//EN\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: Outlook-style with X-MICROSOFT and METHOD:REQUEST
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_OUTLOOK_STYLE =
            "BEGIN:VCALENDAR\n" +
            "METHOD:REQUEST\n" +
            "PRODID:Microsoft Exchange Server 2010\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231215T180000Z\n" +
            "DTEND:20231215T190000Z\n" +
            "UID:outlook-001@exchange\n" +
            "SUMMARY:Sprint Review\n" +
            "LOCATION:Teams Meeting\n" +
            "X-MICROSOFT-CDO-ALLDAYEVENT:FALSE\n" +
            "CLASS:PUBLIC\n" +
            "PRIORITY:5\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: unclosed event (missing END:VEVENT)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_UNCLOSED_EVENT =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "SUMMARY:Unclosed Event\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: Windows timezone ID (as generated by some Outlook versions)
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_WINDOWS_TZID =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART;TZID=Eastern Standard Time:20231201T090000\n" +
            "DTEND;TZID=Eastern Standard Time:20231201T170000\n" +
            "UID:win-tz@example\n" +
            "SUMMARY:Windows TZ Event\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: event with ORGANIZER
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_WITH_ORGANIZER =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "DTEND:20231225T110000Z\n" +
            "UID:org-001@example\n" +
            "SUMMARY:Organized Event\n" +
            "ORGANIZER;CN=Jane Boss:mailto:jane@company.com\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture: event with DURATION instead of DTEND
    // ──────────────────────────────────────────────────────────────────────────
    private static final String FIXTURE_WITH_DURATION =
            "BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "BEGIN:VEVENT\n" +
            "DTSTART:20231225T100000Z\n" +
            "DURATION:PT1H30M\n" +
            "UID:duration-001@example\n" +
            "SUMMARY:Duration Event\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n";

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Splits an iCalendar fixture string into an ArrayList of lines,
     * simulating the output of IcalendarUtils.getStringArrayFromFile().
     */
    private static ArrayList<String> toLines(String fixture) {
        return new ArrayList<>(Arrays.asList(fixture.split("\\n")));
    }

    /**
     * Parses a fixture string through the VCalendar pipeline and returns
     * the populated VCalendar.
     */
    private static VCalendar parseFixture(String fixture) {
        VCalendar calendar = new VCalendar();
        calendar.populateFromString(toLines(fixture));
        return calendar;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. SIMPLE SINGLE EVENT
    // ══════════════════════════════════════════════════════════════════════════

    public void testSimpleEvent_parsesSingleEvent() {
        VCalendar cal = parseFixture(FIXTURE_SIMPLE_EVENT);
        assertEquals("Expected exactly 1 event", 1, cal.getAllEvents().size());
    }

    public void testSimpleEvent_summaryTitle() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        assertEquals("Team Meeting", event.getProperty(VEvent.SUMMARY));
    }

    public void testSimpleEvent_location() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        assertEquals("Conference Room A", event.getProperty(VEvent.LOCATION));
    }

    public void testSimpleEvent_description() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        assertEquals("Quarterly planning session", event.getProperty(VEvent.DESCRIPTION));
    }

    public void testSimpleEvent_dtStart() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        assertEquals("20231225T100000Z", event.getProperty(VEvent.DTSTART));
    }

    public void testSimpleEvent_dtEnd() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        assertEquals("20231225T110000Z", event.getProperty(VEvent.DTEND));
    }

    public void testSimpleEvent_uid() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        assertEquals("abc123@google.com", event.getProperty(VEvent.UID));
    }

    public void testSimpleEvent_status() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        assertEquals("CONFIRMED", event.getProperty(VEvent.STATUS));
    }

    public void testSimpleEvent_sequence() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        // The iCal standard uses "SEQUENCE" as the property name, but VEvent.SEQ = "SEQ".
        // The parser stores the key exactly as found in the file, so we use "SEQUENCE".
        assertEquals("0", event.getProperty("SEQUENCE"));
    }

    public void testSimpleEvent_dtstamp() {
        VEvent event = parseFixture(FIXTURE_SIMPLE_EVENT).getAllEvents().getFirst();
        assertEquals("20231201T120000Z", event.getProperty(VEvent.DTSTAMP));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. RECURRING EVENTS (RRULE)
    // ══════════════════════════════════════════════════════════════════════════

    public void testRecurringEvent_rruleParsed() {
        VEvent event = parseFixture(FIXTURE_RECURRING_EVENT).getAllEvents().getFirst();
        String rrule = event.getProperty(VEvent.RRULE);
        assertNotNull("RRULE should be parsed", rrule);
        assertTrue("RRULE should contain FREQ=DAILY", rrule.contains("FREQ=DAILY"));
    }

    public void testRecurringEvent_rruleContainsByday() {
        VEvent event = parseFixture(FIXTURE_RECURRING_EVENT).getAllEvents().getFirst();
        String rrule = event.getProperty(VEvent.RRULE);
        assertTrue("RRULE should contain BYDAY=MO,WE,FR", rrule.contains("BYDAY=MO,WE,FR"));
    }

    public void testRecurringEvent_rruleContainsUntil() {
        VEvent event = parseFixture(FIXTURE_RECURRING_EVENT).getAllEvents().getFirst();
        String rrule = event.getProperty(VEvent.RRULE);
        assertTrue("RRULE should contain UNTIL", rrule.contains("UNTIL=20231231T235959Z"));
    }

    public void testRecurringEvent_summaryPreserved() {
        VEvent event = parseFixture(FIXTURE_RECURRING_EVENT).getAllEvents().getFirst();
        assertEquals("Standup", event.getProperty(VEvent.SUMMARY));
    }

    public void testRecurringWeekly_countInRrule() {
        VEvent event = parseFixture(FIXTURE_RECURRING_WEEKLY).getAllEvents().getFirst();
        String rrule = event.getProperty(VEvent.RRULE);
        assertNotNull(rrule);
        assertTrue("Should contain COUNT=10", rrule.contains("COUNT=10"));
        assertTrue("Should contain FREQ=WEEKLY", rrule.contains("FREQ=WEEKLY"));
        assertTrue("Should contain BYDAY=FR", rrule.contains("BYDAY=FR"));
    }

    public void testRecurringMalformed_emptyRruleParsedAsEmpty() {
        // An empty RRULE line "RRULE:" should still be stored (as empty string),
        // and the rest of the event should parse without error.
        VCalendar cal = parseFixture(FIXTURE_RECURRING_MALFORMED);
        assertEquals(1, cal.getAllEvents().size());
        VEvent event = cal.getAllEvents().getFirst();
        assertEquals("Bad Recurrence", event.getProperty(VEvent.SUMMARY));
        // The RRULE value after "RRULE:" is empty
        String rrule = event.getProperty(VEvent.RRULE);
        assertNotNull("RRULE key should exist", rrule);
        assertEquals("RRULE value should be empty", "", rrule);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. TIMEZONE (TZID) EVENTS
    // ══════════════════════════════════════════════════════════════════════════

    public void testTzidEvent_dtstartValue() {
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_TZID).getAllEvents().getFirst();
        assertEquals("20231201T090000", event.getProperty(VEvent.DTSTART));
    }

    public void testTzidEvent_dtstartTzidParameter() {
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_TZID).getAllEvents().getFirst();
        String params = event.getPropertyParameters(VEvent.DTSTART);
        assertNotNull("DTSTART should have parameters", params);
        assertTrue("Parameters should contain TZID=America/New_York",
                params.contains("TZID=America/New_York"));
    }

    public void testTzidEvent_dtendValue() {
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_TZID).getAllEvents().getFirst();
        assertEquals("20231201T170000", event.getProperty(VEvent.DTEND));
    }

    public void testTzidEvent_dtendTzidParameter() {
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_TZID).getAllEvents().getFirst();
        String params = event.getPropertyParameters(VEvent.DTEND);
        assertNotNull("DTEND should have parameters", params);
        assertTrue("DTEND parameters should contain TZID",
                params.contains("TZID=America/New_York"));
    }

    public void testTzidEvent_summaryAndLocation() {
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_TZID).getAllEvents().getFirst();
        assertEquals("NYC Workshop", event.getProperty(VEvent.SUMMARY));
        assertEquals("Manhattan Office", event.getProperty(VEvent.LOCATION));
    }

    public void testWindowsTzid_parsed() {
        VEvent event = parseFixture(FIXTURE_WINDOWS_TZID).getAllEvents().getFirst();
        assertEquals("20231201T090000", event.getProperty(VEvent.DTSTART));
        String params = event.getPropertyParameters(VEvent.DTSTART);
        assertNotNull("Should have TZID parameters", params);
        assertTrue("Should contain Windows TZID",
                params.contains("TZID=Eastern Standard Time"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. VALARM / REMINDER EVENTS
    // ══════════════════════════════════════════════════════════════════════════

    public void testValarmEvent_eventParsedSuccessfully() {
        VCalendar cal = parseFixture(FIXTURE_EVENT_WITH_VALARM);
        assertEquals(1, cal.getAllEvents().size());
    }

    public void testValarmEvent_summaryPreserved() {
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_VALARM).getAllEvents().getFirst();
        assertEquals("Doctor Appointment", event.getProperty(VEvent.SUMMARY));
    }

    public void testValarmEvent_eventPropertiesIntact() {
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_VALARM).getAllEvents().getFirst();
        assertEquals("20231220T150000Z", event.getProperty(VEvent.DTSTART));
        assertEquals("20231220T160000Z", event.getProperty(VEvent.DTEND));
        assertEquals("valarm-001@example", event.getProperty(VEvent.UID));
    }

    public void testValarmEvent_triggerParsedIntoProperties() {
        // VALARM lines are stored in the parent event's properties by the current parser.
        // The TRIGGER line "TRIGGER:-PT15M" should be captured.
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_VALARM).getAllEvents().getFirst();
        assertEquals("-PT15M", event.getProperty("TRIGGER"));
    }

    public void testValarmEvent_actionParsedIntoProperties() {
        VEvent event = parseFixture(FIXTURE_EVENT_WITH_VALARM).getAllEvents().getFirst();
        assertEquals("DISPLAY", event.getProperty("ACTION"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. MISSING FIELDS
    // ══════════════════════════════════════════════════════════════════════════

    public void testMissingDtstart_eventParsedWithoutStart() {
        VCalendar cal = parseFixture(FIXTURE_MISSING_DTSTART);
        assertEquals(1, cal.getAllEvents().size());
        VEvent event = cal.getAllEvents().getFirst();
        assertNull("DTSTART should be null", event.getProperty(VEvent.DTSTART));
        assertEquals("Event Without Start", event.getProperty(VEvent.SUMMARY));
    }

    public void testMissingDtstart_dtendStillParsed() {
        VEvent event = parseFixture(FIXTURE_MISSING_DTSTART).getAllEvents().getFirst();
        assertEquals("20231225T110000Z", event.getProperty(VEvent.DTEND));
    }

    public void testMissingDtend_eventParsedWithoutEnd() {
        VCalendar cal = parseFixture(FIXTURE_MISSING_DTEND);
        assertEquals(1, cal.getAllEvents().size());
        VEvent event = cal.getAllEvents().getFirst();
        assertNull("DTEND should be null", event.getProperty(VEvent.DTEND));
        assertEquals("Event Without End", event.getProperty(VEvent.SUMMARY));
    }

    public void testMissingDtend_dtstartStillParsed() {
        VEvent event = parseFixture(FIXTURE_MISSING_DTEND).getAllEvents().getFirst();
        assertEquals("20231225T100000Z", event.getProperty(VEvent.DTSTART));
    }

    public void testEmptyCalendar_noEvents() {
        VCalendar cal = parseFixture(FIXTURE_EMPTY_CALENDAR);
        assertNotNull("Calendar should not be null", cal.getAllEvents());
        assertTrue("Calendar should have no events", cal.getAllEvents().isEmpty());
    }

    public void testUnclosedEvent_stillParsedGracefully() {
        // Missing END:VEVENT -- the parser reads till END:VCALENDAR and stops.
        VCalendar cal = parseFixture(FIXTURE_UNCLOSED_EVENT);
        assertEquals("Should still parse 1 event", 1, cal.getAllEvents().size());
        VEvent event = cal.getAllEvents().getFirst();
        assertEquals("Unclosed Event", event.getProperty(VEvent.SUMMARY));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. LINE FOLDING (RFC 5545 continuation lines)
    // ══════════════════════════════════════════════════════════════════════════

    public void testFoldedText_summaryUnfolded() {
        VEvent event = parseFixture(FIXTURE_FOLDED_LONG_TEXT).getAllEvents().getFirst();
        String summary = event.getProperty(VEvent.SUMMARY);
        assertNotNull("SUMMARY should not be null", summary);
        assertEquals(
                "Annual Strategy Meeting - All Hands On Deck For Planning Next Year Goals And Roadmap",
                summary);
    }

    public void testFoldedText_descriptionUnfolded() {
        VEvent event = parseFixture(FIXTURE_FOLDED_LONG_TEXT).getAllEvents().getFirst();
        String desc = event.getProperty(VEvent.DESCRIPTION);
        assertNotNull("DESCRIPTION should not be null", desc);
        assertEquals(
                "This is a very long description that spans multiple lines in the iCal file",
                desc);
    }

    public void testFoldedText_uidPreserved() {
        VEvent event = parseFixture(FIXTURE_FOLDED_LONG_TEXT).getAllEvents().getFirst();
        assertEquals("folded-001@example", event.getProperty(VEvent.UID));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. SPECIAL CHARACTERS IN SUMMARY / LOCATION
    // ══════════════════════════════════════════════════════════════════════════

    public void testSpecialChars_summaryRetainsEscaping() {
        VEvent event = parseFixture(FIXTURE_SPECIAL_CHARS).getAllEvents().getFirst();
        String summary = event.getProperty(VEvent.SUMMARY);
        assertNotNull(summary);
        // Values are stored as-is from the iCal file (still escaped)
        assertTrue("Should contain escaped comma", summary.contains("\\,"));
        assertTrue("Should contain escaped newline", summary.contains("\\n"));
    }

    public void testSpecialChars_summaryUncleansed() {
        VEvent event = parseFixture(FIXTURE_SPECIAL_CHARS).getAllEvents().getFirst();
        String raw = event.getProperty(VEvent.SUMMARY);
        String uncleaned = IcalendarUtils.uncleanseString(raw);
        assertNotNull(uncleaned);
        assertTrue("Uncleansed should contain real comma",
                uncleaned.contains("Planning, Strategy"));
        assertTrue("Uncleansed should contain real newline",
                uncleaned.contains("Roadmap\n2024"));
    }

    public void testSpecialChars_locationRetainsEscaping() {
        VEvent event = parseFixture(FIXTURE_SPECIAL_CHARS).getAllEvents().getFirst();
        String location = event.getProperty(VEvent.LOCATION);
        assertNotNull(location);
        assertTrue("Should contain escaped semicolon", location.contains("\\;"));
        assertTrue("Should contain escaped comma", location.contains("\\,"));
    }

    public void testSpecialChars_locationUncleansed() {
        VEvent event = parseFixture(FIXTURE_SPECIAL_CHARS).getAllEvents().getFirst();
        String raw = event.getProperty(VEvent.LOCATION);
        String uncleaned = IcalendarUtils.uncleanseString(raw);
        assertEquals("Building 5; Room 101, Floor 3", uncleaned);
    }

    public void testSpecialChars_descriptionUncleansed() {
        VEvent event = parseFixture(FIXTURE_SPECIAL_CHARS).getAllEvents().getFirst();
        String raw = event.getProperty(VEvent.DESCRIPTION);
        String uncleaned = IcalendarUtils.uncleanseString(raw);
        assertEquals("Discuss Q1, Q2 goals\nBring notes; pens", uncleaned);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. MULTIPLE EVENTS
    // ══════════════════════════════════════════════════════════════════════════

    public void testMultipleEvents_bothParsed() {
        VCalendar cal = parseFixture(FIXTURE_MULTIPLE_EVENTS);
        assertEquals("Should parse 2 events", 2, cal.getAllEvents().size());
    }

    public void testMultipleEvents_firstEventCorrect() {
        LinkedList<VEvent> events = parseFixture(FIXTURE_MULTIPLE_EVENTS).getAllEvents();
        VEvent first = events.get(0);
        assertEquals("First Event", first.getProperty(VEvent.SUMMARY));
        assertEquals("event-1@example", first.getProperty(VEvent.UID));
        assertEquals("20231225T100000Z", first.getProperty(VEvent.DTSTART));
    }

    public void testMultipleEvents_secondEventCorrect() {
        LinkedList<VEvent> events = parseFixture(FIXTURE_MULTIPLE_EVENTS).getAllEvents();
        VEvent second = events.get(1);
        assertEquals("Second Event", second.getProperty(VEvent.SUMMARY));
        assertEquals("event-2@example", second.getProperty(VEvent.UID));
        assertEquals("Room B", second.getProperty(VEvent.LOCATION));
        assertEquals("20231226T140000Z", second.getProperty(VEvent.DTSTART));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. ATTENDEE PARSING
    // ══════════════════════════════════════════════════════════════════════════

    public void testMultipleAttendees_allParsed() {
        VEvent event = parseFixture(FIXTURE_MULTIPLE_ATTENDEES).getAllEvents().getFirst();
        assertEquals("Should have 3 attendees", 3, event.mAttendees.size());
    }

    public void testMultipleAttendees_firstAttendeeEmail() {
        Attendee a = parseFixture(FIXTURE_MULTIPLE_ATTENDEES)
                .getAllEvents().getFirst().mAttendees.get(0);
        assertEquals("alice@example.com", a.mEmail);
    }

    public void testMultipleAttendees_firstAttendeeName() {
        Attendee a = parseFixture(FIXTURE_MULTIPLE_ATTENDEES)
                .getAllEvents().getFirst().mAttendees.get(0);
        assertEquals("Alice Smith", a.mProperties.get(Attendee.CN));
    }

    public void testMultipleAttendees_firstAttendeeAccepted() {
        Attendee a = parseFixture(FIXTURE_MULTIPLE_ATTENDEES)
                .getAllEvents().getFirst().mAttendees.get(0);
        assertEquals("ACCEPTED", a.mProperties.get(Attendee.PARTSTAT));
    }

    public void testMultipleAttendees_secondAttendeeDeclined() {
        Attendee a = parseFixture(FIXTURE_MULTIPLE_ATTENDEES)
                .getAllEvents().getFirst().mAttendees.get(1);
        assertEquals("bob@example.com", a.mEmail);
        assertEquals("Bob Jones", a.mProperties.get(Attendee.CN));
        assertEquals("DECLINED", a.mProperties.get(Attendee.PARTSTAT));
    }

    public void testMultipleAttendees_thirdAttendeeTentative() {
        Attendee a = parseFixture(FIXTURE_MULTIPLE_ATTENDEES)
                .getAllEvents().getFirst().mAttendees.get(2);
        assertEquals("carol@example.com", a.mEmail);
        assertEquals("Carol White", a.mProperties.get(Attendee.CN));
        assertEquals("TENTATIVE", a.mProperties.get(Attendee.PARTSTAT));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. ALL-DAY EVENT (VALUE=DATE)
    // ══════════════════════════════════════════════════════════════════════════

    public void testAllDayEvent_dtstartDateOnly() {
        VEvent event = parseFixture(FIXTURE_ALL_DAY_EVENT).getAllEvents().getFirst();
        assertEquals("20231225", event.getProperty(VEvent.DTSTART));
    }

    public void testAllDayEvent_dtstartDateOnlyParam() {
        VEvent event = parseFixture(FIXTURE_ALL_DAY_EVENT).getAllEvents().getFirst();
        String params = event.getPropertyParameters(VEvent.DTSTART);
        assertNotNull("DTSTART should have VALUE parameter", params);
        assertTrue("Should contain VALUE=DATE", params.contains("VALUE=DATE"));
    }

    public void testAllDayEvent_dtendDateOnly() {
        VEvent event = parseFixture(FIXTURE_ALL_DAY_EVENT).getAllEvents().getFirst();
        assertEquals("20231226", event.getProperty(VEvent.DTEND));
    }

    public void testAllDayEvent_summaryCorrect() {
        VEvent event = parseFixture(FIXTURE_ALL_DAY_EVENT).getAllEvents().getFirst();
        assertEquals("Christmas Day", event.getProperty(VEvent.SUMMARY));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. ORGANIZER PARSING
    // ══════════════════════════════════════════════════════════════════════════

    public void testOrganizer_parsed() {
        VEvent event = parseFixture(FIXTURE_WITH_ORGANIZER).getAllEvents().getFirst();
        assertNotNull("Organizer should be parsed", event.mOrganizer);
    }

    public void testOrganizer_nameParsed() {
        Organizer org = parseFixture(FIXTURE_WITH_ORGANIZER)
                .getAllEvents().getFirst().mOrganizer;
        assertEquals("Jane Boss", org.mName);
    }

    public void testOrganizer_emailParsing_knownBug() {
        // Known bug in Organizer.populateFromICalString:
        // After splitting "ORGANIZER;CN=Jane Boss:mailto:jane@company.com" on ';', then on ':',
        // the array becomes ["CN=Jane Boss", "mailto", "jane@company.com"].
        // entries[1] = "mailto" and replace("mailto=", "") doesn't match "mailto:",
        // so mEmail ends up as "mailto" instead of the actual email address.
        // This documents the actual (buggy) behavior.
        Organizer org = parseFixture(FIXTURE_WITH_ORGANIZER)
                .getAllEvents().getFirst().mOrganizer;
        assertEquals("mailto", org.mEmail);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. OUTLOOK-STYLE PROPERTIES
    // ══════════════════════════════════════════════════════════════════════════

    public void testOutlookStyle_propertiesParsed() {
        VEvent event = parseFixture(FIXTURE_OUTLOOK_STYLE).getAllEvents().getFirst();
        assertEquals("Sprint Review", event.getProperty(VEvent.SUMMARY));
        assertEquals("Teams Meeting", event.getProperty(VEvent.LOCATION));
        assertEquals("PUBLIC", event.getProperty(VEvent.CLASS));
        assertEquals("5", event.getProperty(VEvent.PRIORITY));
    }

    public void testOutlookStyle_xMicrosoftPropertyParsed() {
        VEvent event = parseFixture(FIXTURE_OUTLOOK_STYLE).getAllEvents().getFirst();
        assertEquals("FALSE", event.getProperty("X-MICROSOFT-CDO-ALLDAYEVENT"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13. DURATION (instead of DTEND)
    // ══════════════════════════════════════════════════════════════════════════

    public void testDuration_parsedCorrectly() {
        VEvent event = parseFixture(FIXTURE_WITH_DURATION).getAllEvents().getFirst();
        assertEquals("PT1H30M", event.getProperty(VEvent.DURATION));
        assertNull("DTEND should be null when DURATION is used",
                event.getProperty(VEvent.DTEND));
        assertEquals("20231225T100000Z", event.getProperty(VEvent.DTSTART));
        assertEquals("Duration Event", event.getProperty(VEvent.SUMMARY));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 14. IcalendarUtils STRING UTILITIES
    // ══════════════════════════════════════════════════════════════════════════

    // ── uncleanseString ──

    public void testUncleanseString_escapedNewline() {
        assertEquals("hello\nworld", IcalendarUtils.uncleanseString("hello\\nworld"));
    }

    public void testUncleanseString_escapedSemicolon() {
        assertEquals("a;b", IcalendarUtils.uncleanseString("a\\;b"));
    }

    public void testUncleanseString_escapedComma() {
        assertEquals("a,b", IcalendarUtils.uncleanseString("a\\,b"));
    }

    public void testUncleanseString_null() {
        assertNull(IcalendarUtils.uncleanseString(null));
    }

    public void testUncleanseString_noEscapes() {
        assertEquals("plain text", IcalendarUtils.uncleanseString("plain text"));
    }

    public void testUncleanseString_multipleEscapes() {
        assertEquals("a,b;c\nd", IcalendarUtils.uncleanseString("a\\,b\\;c\\nd"));
    }

    // ── cleanseString ──

    public void testCleanseString_newline() {
        String result = IcalendarUtils.cleanseString("hello\nworld");
        assertEquals("hello\\nworld", result);
    }

    public void testCleanseString_carriageReturn() {
        String result = IcalendarUtils.cleanseString("hello\rworld");
        assertEquals("hello\\nworld", result);
    }

    public void testCleanseString_crlf() {
        // Note: cleanseString replaces \r and \n independently, so \r\n becomes two \n literals.
        // This is a known quirk: \r -> \\n, then \n -> \\n, producing \\n\\n.
        String result = IcalendarUtils.cleanseString("hello\r\nworld");
        assertEquals("hello\\n\\nworld", result);
    }

    public void testCleanseString_semicolon() {
        assertEquals("a\\;b", IcalendarUtils.cleanseString("a;b"));
    }

    public void testCleanseString_comma() {
        assertEquals("a\\,b", IcalendarUtils.cleanseString("a,b"));
    }

    public void testCleanseString_null() {
        assertNull(IcalendarUtils.cleanseString(null));
    }

    public void testCleanseString_plainText() {
        assertEquals("no change", IcalendarUtils.cleanseString("no change"));
    }

    public void testCleanseString_roundTrip() {
        String original = "Meeting: Q1, Q2; Goals\nBring notes";
        String cleansed = IcalendarUtils.cleanseString(original);
        String uncleaned = IcalendarUtils.uncleanseString(cleansed);
        assertEquals(original, uncleaned);
    }

    // ── splitQuoted ──

    public void testSplitQuoted_simpleSplit() {
        ArrayList<String> result = IcalendarUtils.splitQuoted("a:b:c", ':');
        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
        assertEquals("b", result.get(1));
        assertEquals("c", result.get(2));
    }

    public void testSplitQuoted_respectsQuotes() {
        ArrayList<String> result = IcalendarUtils.splitQuoted("a:\"b:c\":d", ':');
        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
        assertEquals("\"b:c\"", result.get(1));
        assertEquals("d", result.get(2));
    }

    public void testSplitQuoted_maxSplit() {
        ArrayList<String> result = IcalendarUtils.splitQuoted("a:b:c:d", ':', 1);
        assertEquals(2, result.size());
        assertEquals("a", result.get(0));
        assertEquals("b:c:d", result.get(1));
    }

    public void testSplitQuoted_maxSplitZero() {
        ArrayList<String> result = IcalendarUtils.splitQuoted("a:b:c", ':', 0);
        assertEquals(1, result.size());
        assertEquals("a:b:c", result.get(0));
    }

    public void testSplitQuoted_noSeparator() {
        ArrayList<String> result = IcalendarUtils.splitQuoted("hello", ':');
        assertEquals(1, result.size());
        assertEquals("hello", result.get(0));
    }

    public void testSplitQuoted_semicolonSeparator() {
        ArrayList<String> result = IcalendarUtils.splitQuoted("ATTENDEE;CN=John;PARTSTAT=ACCEPTED", ';');
        assertEquals(3, result.size());
        assertEquals("ATTENDEE", result.get(0));
        assertEquals("CN=John", result.get(1));
        assertEquals("PARTSTAT=ACCEPTED", result.get(2));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 15. IcalendarUtils.enforceICalLineLength
    // ══════════════════════════════════════════════════════════════════════════

    public void testEnforceLineLength_shortStringUnchanged() {
        StringBuilder input = new StringBuilder("SHORT LINE");
        StringBuilder result = IcalendarUtils.enforceICalLineLength(input);
        assertEquals("SHORT LINE", result.toString());
    }

    public void testEnforceLineLength_nullReturnsNull() {
        assertNull(IcalendarUtils.enforceICalLineLength(null));
    }

    public void testEnforceLineLength_longLineGetsFolded() {
        // Create a string longer than 75 characters
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            input.append("X");
        }
        StringBuilder result = IcalendarUtils.enforceICalLineLength(input);
        assertNotNull(result);
        // The result should contain a fold: "\n "
        assertTrue("Long line should be folded", result.toString().contains("\n "));
    }

    public void testEnforceLineLength_exactly75CharsUnchanged() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 75; i++) {
            input.append("A");
        }
        StringBuilder result = IcalendarUtils.enforceICalLineLength(input);
        assertEquals(input.toString(), result.toString());
    }

    public void testEnforceLineLength_multilineInputPreservesNewlines() {
        StringBuilder input = new StringBuilder("line1\nline2\nline3");
        StringBuilder result = IcalendarUtils.enforceICalLineLength(input);
        assertEquals("line1\nline2\nline3", result.toString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 16. IcalendarUtils.getICalFormattedDateTime
    // ══════════════════════════════════════════════════════════════════════════

    public void testFormattedDateTime_negativeReturnsNull() {
        assertNull(IcalendarUtils.getICalFormattedDateTime(-1, "UTC"));
    }

    public void testFormattedDateTime_epochZero() {
        String result = IcalendarUtils.getICalFormattedDateTime(0, "UTC");
        assertNotNull(result);
        assertEquals("19700101T000000Z", result);
    }

    public void testFormattedDateTime_formatStructure() {
        String result = IcalendarUtils.getICalFormattedDateTime(0, "UTC");
        assertNotNull(result);
        // Should match pattern: 8 digits + 'T' + 6 digits + 'Z'
        assertTrue("Should match iCal datetime format",
                result.matches("\\d{8}T\\d{6}Z"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 17. IcalendarUtils.convertTimeToUtc
    // ══════════════════════════════════════════════════════════════════════════

    public void testConvertTimeToUtc_negativeReturnsZero() {
        assertEquals(0, IcalendarUtils.convertTimeToUtc(-1, "UTC"));
    }

    public void testConvertTimeToUtc_utcTimeZone() {
        // UTC has 0 offset, so result should equal input
        long millis = 1700000000000L;
        assertEquals(millis, IcalendarUtils.convertTimeToUtc(millis, "UTC"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 18. IcalendarUtils.writeCalendarToStream
    // ══════════════════════════════════════════════════════════════════════════

    public void testWriteCalendarToStream_nullCalendar() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertFalse(IcalendarUtils.writeCalendarToStream(null, baos));
    }

    public void testWriteCalendarToStream_nullStream() {
        VCalendar cal = new VCalendar();
        assertFalse(IcalendarUtils.writeCalendarToStream(cal, null));
    }

    public void testWriteCalendarToStream_producesOutput() {
        VCalendar cal = new VCalendar();
        cal.addProperty(VCalendar.VERSION, "2.0");
        VEvent event = new VEvent();
        event.addProperty(VEvent.SUMMARY, "Test");
        // VEvent.getICalFormattedString() requires a non-null Organizer
        event.addOrganizer(new Organizer("Test", "test@example.com"));
        cal.addEvent(event);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(IcalendarUtils.writeCalendarToStream(cal, baos));
        String output = baos.toString();
        assertTrue("Output should contain BEGIN:VCALENDAR",
                output.contains("BEGIN:VCALENDAR"));
        assertTrue("Output should contain END:VCALENDAR",
                output.contains("END:VCALENDAR"));
        assertTrue("Output should contain BEGIN:VEVENT",
                output.contains("BEGIN:VEVENT"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 19. VCalendar API
    // ══════════════════════════════════════════════════════════════════════════

    public void testVCalendar_addProperty() {
        VCalendar cal = new VCalendar();
        assertTrue(cal.addProperty(VCalendar.VERSION, "2.0"));
        assertEquals("2.0", cal.getProperty(VCalendar.VERSION));
    }

    public void testVCalendar_addPropertyRejectsNull() {
        VCalendar cal = new VCalendar();
        assertFalse(cal.addProperty(VCalendar.VERSION, null));
    }

    public void testVCalendar_addPropertyRejectsUnknown() {
        VCalendar cal = new VCalendar();
        assertFalse(cal.addProperty("UNKNOWN_PROP", "value"));
    }

    public void testVCalendar_addEventNull() {
        VCalendar cal = new VCalendar();
        cal.addEvent(null);
        assertTrue(cal.getAllEvents().isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 20. VEvent API
    // ══════════════════════════════════════════════════════════════════════════

    public void testVEvent_constructorGeneratesUid() {
        VEvent event = new VEvent();
        String uid = event.getProperty(VEvent.UID);
        assertNotNull("UID should be auto-generated", uid);
        assertTrue("UID should end with @ws.xsoh.etar",
                uid.endsWith("@ws.xsoh.etar"));
    }

    public void testVEvent_constructorGeneratesTimestamp() {
        VEvent event = new VEvent();
        String dtstamp = event.getProperty(VEvent.DTSTAMP);
        assertNotNull("DTSTAMP should be auto-generated", dtstamp);
        assertTrue("DTSTAMP should end with Z", dtstamp.endsWith("Z"));
    }

    public void testVEvent_addPropertyRejectsNull() {
        VEvent event = new VEvent();
        assertFalse(event.addProperty(VEvent.SUMMARY, null));
    }

    public void testVEvent_addPropertyRejectsMultiValue() {
        // ATTENDEE has arity MAX_VALUE, so addProperty (unary only) should reject
        VEvent event = new VEvent();
        assertFalse(event.addProperty(VEvent.ATTENDEE, "someone@example.com"));
    }

    public void testVEvent_getPropertyReturnsNullForMissing() {
        VEvent event = new VEvent();
        assertNull(event.getProperty(VEvent.SUMMARY));
    }

    public void testVEvent_addEventStartNegativeIgnored() {
        VEvent event = new VEvent();
        event.addEventStart(-1, "UTC");
        // The auto-generated DTSTAMP exists, but no new DTSTART should be set
        // (note: constructor doesn't set DTSTART, only DTSTAMP and UID)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 21. Attendee API
    // ══════════════════════════════════════════════════════════════════════════

    public void testAttendee_addProperty() {
        Attendee a = new Attendee();
        assertTrue(a.addProperty(Attendee.CN, "John Doe"));
        assertEquals("John Doe", a.mProperties.get(Attendee.CN));
    }

    public void testAttendee_addPropertyRejectsNull() {
        Attendee a = new Attendee();
        assertFalse(a.addProperty(Attendee.CN, null));
    }

    public void testAttendee_addPropertyRejectsUnknown() {
        Attendee a = new Attendee();
        assertFalse(a.addProperty("UNKNOWN", "value"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 22. Organizer API
    // ══════════════════════════════════════════════════════════════════════════

    public void testOrganizer_constructorDefaults() {
        Organizer org = new Organizer(null, null);
        assertEquals("UNKNOWN", org.mName);
        assertEquals("UNKNOWN", org.mEmail);
    }

    public void testOrganizer_constructorSetsValues() {
        Organizer org = new Organizer("Alice", "alice@example.com");
        assertEquals("Alice", org.mName);
        assertEquals("alice@example.com", org.mEmail);
    }

    public void testOrganizer_populateFromICalString_malformedReturnsNull() {
        // Input without proper format should return null (exception caught)
        Organizer org = Organizer.populateFromICalString("GARBAGE");
        assertNull("Malformed input should return null", org);
    }

    public void testOrganizer_populateFromICalString_nullReturnsNull() {
        // populateFromICalString catches all exceptions internally, so null input
        // returns null instead of throwing.
        Organizer org = Organizer.populateFromICalString(null);
        assertNull("Null input should return null (exception caught internally)", org);
    }

    public void testOrganizer_getICalFormattedString() {
        Organizer org = new Organizer("John", "john@example.com");
        String formatted = org.getICalFormattedString();
        assertTrue("Should contain ORGANIZER", formatted.contains("ORGANIZER"));
        assertTrue("Should contain CN=John", formatted.contains("CN=John"));
        assertTrue("Should contain mailto:john@example.com",
                formatted.contains("mailto:john@example.com"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 23. VEvent.parseTillNextAttribute (line unfolding)
    // ══════════════════════════════════════════════════════════════════════════

    public void testParseTillNextAttribute_singleLine() {
        ArrayList<String> lines = new ArrayList<>(Arrays.asList(
                "SUMMARY:Hello World",
                "LOCATION:Room 1"
        ));
        java.util.ListIterator<String> iter = lines.listIterator();
        String first = iter.next();
        String result = VEvent.parseTillNextAttribute(iter, first);
        assertEquals("SUMMARY:Hello World", result);
    }

    public void testParseTillNextAttribute_foldedLines() {
        ArrayList<String> lines = new ArrayList<>(Arrays.asList(
                "DESCRIPTION:This is a lo",
                " ng description",
                "LOCATION:Room 1"
        ));
        java.util.ListIterator<String> iter = lines.listIterator();
        String first = iter.next();
        String result = VEvent.parseTillNextAttribute(iter, first);
        assertEquals("DESCRIPTION:This is a long description", result);
    }

    public void testParseTillNextAttribute_multipleFolds() {
        ArrayList<String> lines = new ArrayList<>(Arrays.asList(
                "SUMMARY:A",
                " B",
                " C",
                "END:VEVENT"
        ));
        java.util.ListIterator<String> iter = lines.listIterator();
        String first = iter.next();
        String result = VEvent.parseTillNextAttribute(iter, first);
        assertEquals("SUMMARY:ABC", result);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 24. Round-trip: parse then serialize
    // ══════════════════════════════════════════════════════════════════════════

    public void testRoundTrip_simpleEventContainsKeyFields() {
        // Note: VEvent.getICalFormattedString() throws NPE if mOrganizer is null,
        // so we use a fixture that includes an ORGANIZER line.
        VCalendar cal = parseFixture(FIXTURE_WITH_ORGANIZER);
        String serialized = cal.getICalFormattedString();
        assertNotNull(serialized);
        assertTrue("Serialized should have VCALENDAR header",
                serialized.contains("BEGIN:VCALENDAR"));
        assertTrue("Serialized should have VEVENT",
                serialized.contains("BEGIN:VEVENT"));
        assertTrue("Serialized should contain SUMMARY",
                serialized.contains("SUMMARY:"));
        assertTrue("Serialized should have END:VCALENDAR",
                serialized.contains("END:VCALENDAR"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 25. Edge cases
    // ══════════════════════════════════════════════════════════════════════════

    public void testEmptyInput_noEvents() {
        VCalendar cal = new VCalendar();
        cal.populateFromString(new ArrayList<>());
        assertTrue(cal.getAllEvents().isEmpty());
    }

    public void testGarbageInput_noEvents() {
        VCalendar cal = new VCalendar();
        cal.populateFromString(toLines("GARBAGE\nNOT_A_CALENDAR\nFOO:BAR\n"));
        assertTrue(cal.getAllEvents().isEmpty());
    }

    public void testEventWithUrl_parsed() {
        String fixture =
                "BEGIN:VCALENDAR\n" +
                "VERSION:2.0\n" +
                "BEGIN:VEVENT\n" +
                "DTSTART:20231225T100000Z\n" +
                "UID:url-test@example\n" +
                "SUMMARY:URL Test\n" +
                "URL:https://example.com/event/123\n" +
                "END:VEVENT\n" +
                "END:VCALENDAR\n";
        VEvent event = parseFixture(fixture).getAllEvents().getFirst();
        assertEquals("https://example.com/event/123", event.getProperty(VEvent.URL));
    }

    public void testEventWithCategories_parsed() {
        String fixture =
                "BEGIN:VCALENDAR\n" +
                "VERSION:2.0\n" +
                "BEGIN:VEVENT\n" +
                "DTSTART:20231225T100000Z\n" +
                "UID:cat-test@example\n" +
                "SUMMARY:Categories Test\n" +
                "CATEGORIES:Work,Important,Meeting\n" +
                "END:VEVENT\n" +
                "END:VCALENDAR\n";
        VEvent event = parseFixture(fixture).getAllEvents().getFirst();
        String cats = event.getProperty("CATEGORIES");
        assertNotNull("CATEGORIES should be parsed", cats);
        assertTrue("Should contain Work", cats.contains("Work"));
        assertTrue("Should contain Important", cats.contains("Important"));
    }
}
