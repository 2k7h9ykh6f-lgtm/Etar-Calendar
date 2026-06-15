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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * Unit tests for the iCalendar import parser ({@link VCalendar#populateFromString},
 * {@link VEvent}, {@link Attendee}, {@link Organizer}) and the pure helpers in
 * {@link IcalendarUtils}.
 *
 * <p>These tests run on the plain JVM (no Robolectric). They deliberately exercise only the
 * parsing code paths, which are pure Java; the Android-dependent helpers in
 * {@code IcalendarUtils} (file/URI reading, {@code CalendarContract}) are not touched here.
 *
 * <p>The real consumer of the parser is {@code ImportActivity.parseCalFile()}. To keep the
 * assertions meaningful, each test checks exactly the shapes that activity relies on:
 * <ul>
 *   <li>{@code firstEvent.getProperty(SUMMARY/LOCATION/DESCRIPTION/RRULE)} (later passed through
 *       {@link IcalendarUtils#uncleanseString});</li>
 *   <li>{@code firstEvent.getProperty(DTSTART)} together with
 *       {@code firstEvent.getPropertyParameters(DTSTART)} (where the {@code TZID=...} time zone
 *       reference is found and sliced via {@code substring(5)});</li>
 *   <li>arbitrary flat keys such as {@code getProperty("X-MICROSOFT-CDO-ALLDAYEVENT")};</li>
 *   <li>{@code firstEvent.mAttendees} / {@code attendee.mEmail}.</li>
 * </ul>
 * Assertions verify parsed title, time, time zone, reminder and error-handling behaviour rather
 * than merely asserting that parsing does not throw.
 */
public class IcalendarImportTest {

    // ------------------------------------------------------------------------
    // Fixtures.
    //
    // Each fixture is a self-contained .ics document expressed as a String so the test is easy to
    // read in one place. The Unicode/charset scenario instead uses a real resource file
    // (test/resources/.../unicode_event.ics) because charset handling can only be exercised with
    // actual bytes. The purpose of every fixture is documented inline.
    // ------------------------------------------------------------------------

    /** Purpose: a plain, well-formed single event. Baseline for title/time/location parsing. */
    private static final String SINGLE_EVENT = String.join("\n",
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//EN",
            "BEGIN:VEVENT",
            "UID:event-001@test.com",
            "DTSTART:20240115T090000Z",
            "DTEND:20240115T100000Z",
            "SUMMARY:Team Meeting",
            "LOCATION:Conference Room A",
            "DESCRIPTION:Weekly sync",
            "END:VEVENT",
            "END:VCALENDAR");

    /**
     * Purpose: a recurring event. The RRULE value itself contains ';' separators; the parser must
     * keep the whole rule as the property value (the first ';' falls AFTER the first ':').
     */
    private static final String RECURRING_EVENT = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:recurring@test.com",
            "DTSTART:20240115T090000Z",
            "SUMMARY:Standup",
            "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10",
            "END:VEVENT",
            "END:VCALENDAR");

    /**
     * Purpose: an event carrying a reminder (VALARM). The parser has no dedicated alarm model, so
     * it flattens VALARM lines into the event's property map. This fixture lets us pin that
     * documented behaviour, including the fact that the alarm's DESCRIPTION overwrites the event's.
     */
    private static final String EVENT_WITH_REMINDER = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:reminder@test.com",
            "DTSTART:20240115T090000Z",
            "SUMMARY:Doctor Appointment",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "DESCRIPTION:Reminder",
            "TRIGGER:-PT15M",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR");

    /**
     * Purpose: a floating-time event with a TZID time-zone reference. The time zone must be parsed
     * into the property PARAMETERS, and the bare local time into the property VALUE.
     */
    private static final String EVENT_WITH_TIMEZONE = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:tz@test.com",
            "DTSTART;TZID=America/New_York:20240115T090000",
            "DTEND;TZID=America/New_York:20240115T100000",
            "SUMMARY:NY Meeting",
            "END:VEVENT",
            "END:VCALENDAR");

    /** Purpose: an event missing BOTH DTSTART and DTEND. The parser must stay tolerant. */
    private static final String EVENT_NO_DATES = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:no-dates@test.com",
            "SUMMARY:Event without dates",
            "END:VEVENT",
            "END:VCALENDAR");

    /** Purpose: an event with DTSTART but no DTEND (ImportActivity treats end := start). */
    private static final String EVENT_START_ONLY = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:start-only@test.com",
            "DTSTART:20240115T090000Z",
            "SUMMARY:Open ended",
            "END:VEVENT",
            "END:VCALENDAR");

    /**
     * Purpose: a syntactically invalid RRULE. The importer performs no validation at parse time, so
     * the bogus value must be stored verbatim (validation/expansion happens downstream).
     */
    private static final String EVENT_INVALID_RRULE = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:bad-rrule@test.com",
            "DTSTART:20240115T090000Z",
            "SUMMARY:Bad recurrence",
            "RRULE:THIS_IS_NOT_VALID",
            "END:VEVENT",
            "END:VCALENDAR");

    /**
     * Purpose: RFC 5545 line folding. A long DESCRIPTION is split across physical lines, each
     * continuation beginning with a single space. The parser must unfold (re-join) them. Note the
     * fold boundaries split words ("multi"+"ple", "re"+"join") to prove seamless re-joining.
     */
    private static final String EVENT_FOLDED = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:folded@test.com",
            "DTSTART:20240115T090000Z",
            "DESCRIPTION:This is a long description split across multi",
            " ple physical lines per RFC 5545 folding rules and must re",
            " join seamlessly.",
            "SUMMARY:Folded Event",
            "END:VEVENT",
            "END:VCALENDAR");

    /**
     * Purpose: special characters in text fields. Per iCalendar, commas/semicolons/newlines are
     * backslash-escaped on the wire. The parser stores the RAW escaped value; ImportActivity later
     * decodes it with {@link IcalendarUtils#uncleanseString}. The \\ below are Java escapes, so the
     * actual line contains a single backslash before each ',' and ';'.
     */
    private static final String EVENT_SPECIAL_CHARS = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:special@test.com",
            "DTSTART:20240115T090000Z",
            "SUMMARY:Lunch\\, Q3 review\\; bring laptop",
            "LOCATION:Caf\\, 5th\\; floor",
            "END:VEVENT",
            "END:VCALENDAR");

    /**
     * Purpose: an event with ORGANIZER + ATTENDEE. Verifies attendee email/params parsing and pins
     * two real quirks in the current importer (documented in the test body).
     */
    private static final String EVENT_ORGANIZER_ATTENDEE = String.join("\n",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:people@test.com",
            "DTSTART:20240115T090000Z",
            "SUMMARY:Project kickoff",
            "ORGANIZER;CN=John Doe:mailto:john@test.com",
            "ATTENDEE;CN=Jane Smith;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:jane@test.com",
            "END:VEVENT",
            "END:VCALENDAR");

    /** Purpose: a calendar with two events, to verify the iterator handles multiple VEVENT blocks. */
    private static final String MULTI_EVENT = String.join("\n",
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "BEGIN:VEVENT",
            "UID:multi-1@test.com",
            "DTSTART:20240101T100000Z",
            "SUMMARY:First Event",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:multi-2@test.com",
            "DTSTART:20240102T100000Z",
            "SUMMARY:Second Event",
            "END:VEVENT",
            "END:VCALENDAR");

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** Splits a fixture document into the line list the parser expects (handles LF and CRLF). */
    private static ArrayList<String> toLines(String ics) {
        String[] split = ics.split("\\r?\\n", -1);
        ArrayList<String> lines = new ArrayList<>(Arrays.asList(split));
        // Mirror BufferedReader.readLine(), which yields no trailing empty line.
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static VCalendar parse(String ics) {
        VCalendar calendar = new VCalendar();
        calendar.populateFromString(toLines(ics));
        return calendar;
    }

    private static VEvent firstEventOf(String ics) {
        VCalendar calendar = parse(ics);
        LinkedList<VEvent> events = calendar.getAllEvents();
        assertNotNull("calendar should expose an events list", events);
        assertFalse("calendar should contain at least one event", events.isEmpty());
        return events.getFirst();
    }

    // ------------------------------------------------------------------------
    // Parser scenarios
    // ------------------------------------------------------------------------

    @Test
    public void testParsesSingleEvent_titleTimesLocation() {
        VCalendar calendar = parse(SINGLE_EVENT);
        assertEquals("exactly one event expected", 1, calendar.getAllEvents().size());

        VEvent event = calendar.getAllEvents().getFirst();
        assertEquals("Team Meeting", event.getProperty(VEvent.SUMMARY));
        assertEquals("20240115T090000Z", event.getProperty(VEvent.DTSTART));
        assertEquals("20240115T100000Z", event.getProperty(VEvent.DTEND));
        assertEquals("Conference Room A", event.getProperty(VEvent.LOCATION));
        assertEquals("Weekly sync", event.getProperty(VEvent.DESCRIPTION));
        // A UTC ("...Z") DTSTART carries no extra parameters.
        assertNull(event.getPropertyParameters(VEvent.DTSTART));
        // The UID from the file overwrites the auto-generated one set in the VEvent constructor.
        assertEquals("event-001@test.com", event.getProperty(VEvent.UID));
    }

    @Test
    public void testParsesRecurringEvent_rruleValuePreserved() {
        VEvent event = firstEventOf(RECURRING_EVENT);
        // The semicolons inside the RRULE come after the first ':', so the whole rule is the value.
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10", event.getProperty(VEvent.RRULE));
        // RRULE here has no leading "KEY;param:value" parameters.
        assertNull(event.getPropertyParameters(VEvent.RRULE));
        assertEquals("Standup", event.getProperty(VEvent.SUMMARY));
    }

    @Test
    public void testParsesEventWithReminder_valarmFlattenedTriggerReachable() {
        VEvent event = firstEventOf(EVENT_WITH_REMINDER);

        // Characterization: there is no VAlarm object model. VALARM lines are flattened into the
        // event's property map, so the reminder offset is reachable as a flat TRIGGER property and
        // the alarm ACTION as a flat ACTION property.
        assertEquals("Doctor Appointment", event.getProperty(VEvent.SUMMARY));
        assertEquals("-PT15M", event.getProperty("TRIGGER"));
        assertEquals("DISPLAY", event.getProperty("ACTION"));
        // The flattening means BEGIN/END lines of the VALARM also land as properties...
        assertEquals("VALARM", event.getProperty("BEGIN"));
        assertEquals("VALARM", event.getProperty("END"));
        // ...and, importantly, the alarm's DESCRIPTION collides with (overwrites) the event's.
        // ImportActivity reads getProperty(DESCRIPTION) for the event body, so it would pick up the
        // alarm text "Reminder" here. This pins the collision as current behaviour.
        assertEquals("Reminder", event.getProperty(VEvent.DESCRIPTION));
    }

    @Test
    public void testParsesEventWithTimezone_tzidCapturedInParameters() {
        VEvent event = firstEventOf(EVENT_WITH_TIMEZONE);

        // The bare local time becomes the property VALUE.
        assertEquals("20240115T090000", event.getProperty(VEvent.DTSTART));
        assertEquals("20240115T100000", event.getProperty(VEvent.DTEND));
        // The time-zone reference becomes the property PARAMETERS. ImportActivity reads exactly
        // this and matches it with startsWith("TZID="), then takes substring(5) => zone id.
        assertEquals("TZID=America/New_York", event.getPropertyParameters(VEvent.DTSTART));
        assertEquals("TZID=America/New_York", event.getPropertyParameters(VEvent.DTEND));
        assertTrue(event.getPropertyParameters(VEvent.DTSTART).startsWith("TZID="));
        assertEquals("America/New_York",
                event.getPropertyParameters(VEvent.DTSTART).substring(5));
    }

    @Test
    public void testMissingDates_parserTolerant() {
        // (a) Both DTSTART and DTEND absent: the event is still produced, the missing fields are
        // null (not empty strings / not crashing). This is the "graceful degradation" contract.
        VEvent both = firstEventOf(EVENT_NO_DATES);
        assertEquals("Event without dates", both.getProperty(VEvent.SUMMARY));
        assertNull("missing DTSTART should be null", both.getProperty(VEvent.DTSTART));
        assertNull("missing DTEND should be null", both.getProperty(VEvent.DTEND));

        // (b) DTSTART present but DTEND absent: ImportActivity falls back to end := start, which
        // relies on DTSTART being non-null and DTEND being null exactly as asserted here.
        VEvent startOnly = firstEventOf(EVENT_START_ONLY);
        assertEquals("20240115T090000Z", startOnly.getProperty(VEvent.DTSTART));
        assertNull(startOnly.getProperty(VEvent.DTEND));
    }

    @Test
    public void testInvalidRrule_storedVerbatimNoValidation() {
        VEvent event = firstEventOf(EVENT_INVALID_RRULE);
        // Error-handling contract: a malformed RRULE is NOT rejected at parse time; it is stored
        // unmodified so downstream (RecurrenceSet/RecurrenceProcessor) can decide what to do.
        assertEquals("THIS_IS_NOT_VALID", event.getProperty(VEvent.RRULE));
        // The rest of the event is unaffected by the bad rule.
        assertEquals("Bad recurrence", event.getProperty(VEvent.SUMMARY));
        assertEquals("20240115T090000Z", event.getProperty(VEvent.DTSTART));
    }

    @Test
    public void testFoldedLongDescription_continuationLinesUnfolded() {
        VEvent event = firstEventOf(EVENT_FOLDED);

        String expected = "This is a long description split across multiple physical lines "
                + "per RFC 5545 folding rules and must rejoin seamlessly.";
        assertEquals(expected, event.getProperty(VEvent.DESCRIPTION));
        // The unfolded value must not retain any physical line breaks or the fold marker.
        assertFalse(event.getProperty(VEvent.DESCRIPTION).contains("\n"));
        assertTrue(event.getProperty(VEvent.DESCRIPTION).contains("multiple physical"));
        // Parsing resumed correctly on the line after the folded block.
        assertEquals("Folded Event", event.getProperty(VEvent.SUMMARY));
    }

    @Test
    public void testSpecialCharacters_rawEscapedThenUncleansed() {
        VEvent event = firstEventOf(EVENT_SPECIAL_CHARS);

        // The parser keeps the raw, backslash-escaped form (it does NOT decode on import).
        assertEquals("Lunch\\, Q3 review\\; bring laptop", event.getProperty(VEvent.SUMMARY));
        assertEquals("Caf\\, 5th\\; floor", event.getProperty(VEvent.LOCATION));

        // ImportActivity decodes via uncleanseString before display; verify that round-trip yields
        // the human-readable text (escaped ',' and ';' become literal ',' and ';').
        assertEquals("Lunch, Q3 review; bring laptop",
                IcalendarUtils.uncleanseString(event.getProperty(VEvent.SUMMARY)));
        assertEquals("Caf, 5th; floor",
                IcalendarUtils.uncleanseString(event.getProperty(VEvent.LOCATION)));
    }

    @Test
    public void testOrganizerAndAttendee_attendeeParsedOrganizerQuirks() {
        VEvent event = firstEventOf(EVENT_ORGANIZER_ATTENDEE);

        // --- Attendee: parsed correctly ---
        assertEquals(1, event.mAttendees.size());
        Attendee attendee = event.mAttendees.getFirst();
        assertEquals("jane@test.com", attendee.mEmail);
        assertEquals("Jane Smith", attendee.mProperties.get(Attendee.CN));
        assertEquals("ACCEPTED", attendee.mProperties.get(Attendee.PARTSTAT));
        assertEquals("REQ-PARTICIPANT", attendee.mProperties.get(Attendee.ROLE));

        // --- Organizer: pins two real quirks of the current importer ---
        // Quirk 1: ORGANIZER is stored in the mOrganizer object, never in mProperties. So the
        // value ImportActivity reads via getProperty(ORGANIZER) is null.
        assertNull(event.getProperty(VEvent.ORGANIZER));
        assertNotNull(event.mOrganizer);
        assertEquals("John Doe", event.mOrganizer.mName);
        // Quirk 2: Organizer.populateFromICalString splits on ':' and then strips "mailto=" (with
        // '='), but the wire form uses "mailto:". The email is therefore mis-parsed as "mailto"
        // rather than the real address. Documented here so a future fix has a failing red anchor.
        assertEquals("mailto", event.mOrganizer.mEmail);
    }

    @Test
    public void testMultipleEvents_allParsedInOrder() {
        VCalendar calendar = parse(MULTI_EVENT);
        assertEquals(2, calendar.getAllEvents().size());
        assertEquals("First Event", calendar.getAllEvents().get(0).getProperty(VEvent.SUMMARY));
        assertEquals("Second Event", calendar.getAllEvents().get(1).getProperty(VEvent.SUMMARY));
        assertEquals("20240101T100000Z",
                calendar.getAllEvents().get(0).getProperty(VEvent.DTSTART));
        assertEquals("20240102T100000Z",
                calendar.getAllEvents().get(1).getProperty(VEvent.DTSTART));
    }

    // ------------------------------------------------------------------------
    // Charset / non-UTF-8 handling (uses a real resource fixture)
    // ------------------------------------------------------------------------

    @Test
    public void testUnicodeIcsResource_utf8DecodesCorrectly_latin1Mangles() throws IOException {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("unicode_event.ics")) {
            assertNotNull("fixture unicode_event.ics must be on the test classpath", in);
            bytes = in.readAllBytes();
        }

        // Decoded as UTF-8 (the correct charset for this file), multibyte text parses intact.
        VCalendar utf8Cal = new VCalendar();
        utf8Cal.populateFromString(toLines(new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(1, utf8Cal.getAllEvents().size());
        VEvent utf8Event = utf8Cal.getAllEvents().getFirst();
        // Expected text expressed with \\u escapes so the assertion is independent of the encoding
        // used to compile this source file: "Café meeting ☕ 会议" and "Zürich Büro".
        assertEquals("Caf\u00e9 meeting \u2615 \u4f1a\u8bae", utf8Event.getProperty(VEvent.SUMMARY));
        assertEquals("Z\u00fcrich B\u00fcro", utf8Event.getProperty(VEvent.LOCATION));

        // Decoded with the WRONG charset (ISO-8859-1) the same bytes are corrupted. This models the
        // risk in IcalendarUtils.getStringArrayFromFile(), which builds an InputStreamReader without
        // specifying UTF-8 and so depends on the platform default charset. Parsing still must not
        // crash, but the multibyte fields no longer match the UTF-8 result.
        VCalendar latin1Cal = new VCalendar();
        latin1Cal.populateFromString(toLines(new String(bytes, StandardCharsets.ISO_8859_1)));
        assertEquals(1, latin1Cal.getAllEvents().size());
        VEvent latin1Event = latin1Cal.getAllEvents().getFirst();
        assertNotEquals("ISO-8859-1 decoding must differ from UTF-8 for multibyte summaries",
                utf8Event.getProperty(VEvent.SUMMARY), latin1Event.getProperty(VEvent.SUMMARY));
    }

    // ------------------------------------------------------------------------
    // IcalendarUtils pure-Java helpers
    // ------------------------------------------------------------------------

    @Test
    public void testCleanseUncleanseRoundTrip() {
        String original = "a,b;c\nd"; // contains a comma, a semicolon and a real newline
        String cleansed = IcalendarUtils.cleanseString(original);
        // Newline -> \n, ';' -> \; , ',' -> \, (each backslash below is a Java escape).
        assertEquals("a\\,b\\;c\\nd", cleansed);
        assertEquals(original, IcalendarUtils.uncleanseString(cleansed));

        // Null handling on both directions.
        assertNull(IcalendarUtils.cleanseString(null));
        assertNull(IcalendarUtils.uncleanseString(null));
    }

    @Test
    public void testDateTimeHelpers() {
        // getICalFormattedDateTime formats the absolute instant in UTC as <yyyyMMdd>T<HHmmss>Z.
        assertEquals("19700101T000000Z", IcalendarUtils.getICalFormattedDateTime(0L, "UTC"));
        assertEquals("19700101T000001Z", IcalendarUtils.getICalFormattedDateTime(1000L, "UTC"));
        // Guard clause: negative epoch returns null.
        assertNull(IcalendarUtils.getICalFormattedDateTime(-1L, "UTC"));

        // convertTimeToUtc subtracts the zone's raw offset (DST-independent).
        assertEquals(1000L, IcalendarUtils.convertTimeToUtc(1000L, "UTC"));
        assertEquals(1000L - 18000000L, IcalendarUtils.convertTimeToUtc(1000L, "GMT+05:00"));
        // Guard clause: negative epoch returns 0.
        assertEquals(0L, IcalendarUtils.convertTimeToUtc(-5L, "UTC"));
    }

    @Test
    public void testEnforceICalLineLength() {
        // A short line (<= 75 chars) is returned unchanged.
        StringBuilder shortInput = new StringBuilder("a short line");
        assertEquals("a short line",
                IcalendarUtils.enforceICalLineLength(shortInput).toString());

        // A long line is folded with the "\n " continuation marker; folding must be reversible
        // (stripping the marker restores the original) so no characters are lost.
        StringBuilder longInput = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longInput.append('a');
        }
        String folded = IcalendarUtils.enforceICalLineLength(longInput).toString();
        assertTrue("long input should be folded", folded.contains("\n "));
        assertEquals("folding must preserve content", longInput.toString(),
                folded.replace("\n ", ""));
        for (String physicalLine : folded.split("\n", -1)) {
            assertTrue("no physical line should greatly exceed the 75-char limit",
                    physicalLine.length() <= 76);
        }
    }

    @Test
    public void testSplitQuoted() {
        // maxsplit is honoured: only the first separator splits, the rest stays in the tail.
        ArrayList<String> capped = IcalendarUtils.splitQuoted("a:b:c", ':', 1);
        assertEquals(2, capped.size());
        assertEquals("a", capped.get(0));
        assertEquals("b:c", capped.get(1));

        // A separator inside double quotes is protected and does not split (as used for attendee
        // values like CN="Last:First"). The quote here is not at index 0.
        ArrayList<String> quoted = IcalendarUtils.splitQuoted("x=\"a:b\":y", ':');
        assertEquals(2, quoted.size());
        assertEquals("x=\"a:b\"", quoted.get(0));
        assertEquals("y", quoted.get(1));
    }
}
