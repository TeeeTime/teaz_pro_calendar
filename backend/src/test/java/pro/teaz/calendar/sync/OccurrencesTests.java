package pro.teaz.calendar.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import pro.teaz.calendar.sync.Occurrences.Occurrence;
import pro.teaz.calendar.sync.Occurrences.Parsed;

class OccurrencesTests {

	static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
	static final Instant FROM = berlin(2026, 1, 1, 0, 0);
	static final Instant TO = berlin(2027, 1, 1, 0, 0);

	static Instant berlin(int year, int month, int day, int hour, int minute) {
		return LocalDateTime.of(year, month, day, hour, minute).atZone(BERLIN).toInstant();
	}

	static String ics(String... lines) {
		return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" + String.join("\r\n", lines)
				+ "\r\nEND:VCALENDAR\r\n";
	}

	static Parsed parse(String ics) {
		return Occurrences.parse(ics, BERLIN, FROM, TO);
	}

	@Test
	void singleEventWithTimeZone() {
		Parsed parsed = parse(ics("BEGIN:VEVENT", "UID:a", "SUMMARY:Dentist", "LOCATION:Hauptstr. 5",
				"DTSTART;TZID=Europe/Berlin:20261020T083000", "DTEND;TZID=Europe/Berlin:20261020T091500", "END:VEVENT"));

		assertThat(parsed.uid()).isEqualTo("a");
		assertThat(parsed.recurring()).isFalse();
		assertThat(parsed.occurrences()).containsExactly(new Occurrence("Dentist", "Hauptstr. 5", false,
				berlin(2026, 10, 20, 8, 30), berlin(2026, 10, 20, 9, 15)));
	}

	@Test
	void utcFloatingAndDuration() {
		Parsed utc = parse(ics("BEGIN:VEVENT", "UID:b", "SUMMARY:Call", "DTSTART:20261020T080000Z",
				"DURATION:PT45M", "END:VEVENT"));
		assertThat(utc.occurrences().getFirst().start()).isEqualTo(Instant.parse("2026-10-20T08:00:00Z"));
		assertThat(utc.occurrences().getFirst().end()).isEqualTo(Instant.parse("2026-10-20T08:45:00Z"));

		// Floating: the same wall-clock time wherever you are, here in Berlin.
		Parsed floating = parse(ics("BEGIN:VEVENT", "UID:c", "DTSTART:20261020T080000", "END:VEVENT"));
		Occurrence occurrence = floating.occurrences().getFirst();
		assertThat(occurrence.title()).isEqualTo("(No title)");
		assertThat(occurrence.start()).isEqualTo(berlin(2026, 10, 20, 8, 0));
		assertThat(occurrence.end()).isEqualTo(occurrence.start());
	}

	@Test
	void allDayEvents() {
		Parsed trip = parse(ics("BEGIN:VEVENT", "UID:d", "SUMMARY:Trip", "DTSTART;VALUE=DATE:20261024",
				"DTEND;VALUE=DATE:20261026", "END:VEVENT"));
		assertThat(trip.occurrences()).containsExactly(
				new Occurrence("Trip", null, true, berlin(2026, 10, 24, 0, 0), berlin(2026, 10, 26, 0, 0)));

		Parsed oneDay = parse(ics("BEGIN:VEVENT", "UID:e", "SUMMARY:Holiday", "DTSTART;VALUE=DATE:20261003", "END:VEVENT"));
		assertThat(oneDay.occurrences().getFirst().end()).isEqualTo(berlin(2026, 10, 4, 0, 0));
	}

	@Test
	void recurringWithExceptionAndMovedOccurrence() {
		Parsed parsed = parse(ics(
				"BEGIN:VEVENT", "UID:f", "SUMMARY:Standup",
				"DTSTART;TZID=Europe/Berlin:20261019T090000", "DTEND;TZID=Europe/Berlin:20261019T091500",
				"RRULE:FREQ=DAILY;COUNT=5",
				"EXDATE;TZID=Europe/Berlin:20261021T090000",
				"END:VEVENT",
				"BEGIN:VEVENT", "UID:f", "SUMMARY:Standup (moved)",
				"RECURRENCE-ID;TZID=Europe/Berlin:20261022T090000",
				"DTSTART;TZID=Europe/Berlin:20261022T094000", "DTEND;TZID=Europe/Berlin:20261022T101000",
				"END:VEVENT"));

		assertThat(parsed.recurring()).isTrue();
		assertThat(parsed.occurrences()).extracting(Occurrence::title, Occurrence::start).containsExactly(
				org.assertj.core.groups.Tuple.tuple("Standup", berlin(2026, 10, 19, 9, 0)),
				org.assertj.core.groups.Tuple.tuple("Standup", berlin(2026, 10, 20, 9, 0)),
				org.assertj.core.groups.Tuple.tuple("Standup (moved)", berlin(2026, 10, 22, 9, 40)),
				org.assertj.core.groups.Tuple.tuple("Standup", berlin(2026, 10, 23, 9, 0)));
		assertThat(parsed.occurrences().get(1).end()).isEqualTo(berlin(2026, 10, 20, 9, 15));
	}

	@Test
	void recurringAcrossDaylightSavingKeepsWallClockTime() {
		Parsed parsed = parse(ics("BEGIN:VEVENT", "UID:g", "SUMMARY:Gym",
				"DTSTART;TZID=Europe/Berlin:20261019T180000", "DTEND;TZID=Europe/Berlin:20261019T190000",
				"RRULE:FREQ=WEEKLY;COUNT=3", "END:VEVENT"));

		// Summer time ends on 25 October; still 18:00 in Berlin afterwards.
		assertThat(parsed.occurrences()).extracting(Occurrence::start).containsExactly(
				berlin(2026, 10, 19, 18, 0), berlin(2026, 10, 26, 18, 0), berlin(2026, 11, 2, 18, 0));
	}

	@Test
	void endlessRuleStopsAtTheWindow() {
		Parsed parsed = parse(ics("BEGIN:VEVENT", "UID:h", "SUMMARY:Birthday", "DTSTART;VALUE=DATE:20200304",
				"DTEND;VALUE=DATE:20200305", "RRULE:FREQ=YEARLY", "END:VEVENT"));

		List<Occurrence> occurrences = parsed.occurrences();
		assertThat(occurrences).hasSize(1);
		assertThat(occurrences.getFirst().allDay()).isTrue();
		assertThat(occurrences.getFirst().start()).isEqualTo(berlin(2026, 3, 4, 0, 0));
		assertThat(occurrences.getFirst().end()).isEqualTo(berlin(2026, 3, 5, 0, 0));
	}

	@Test
	void cancelledEventsAreLeftOut() {
		Parsed parsed = parse(ics("BEGIN:VEVENT", "UID:i", "STATUS:CANCELLED",
				"DTSTART;TZID=Europe/Berlin:20261020T083000", "END:VEVENT"));
		assertThat(parsed.occurrences()).isEmpty();
	}

	@Test
	void appleStyleTimeZoneDefinition() {
		Parsed parsed = parse(ics(
				"BEGIN:VTIMEZONE", "TZID:America/New_York",
				"BEGIN:DAYLIGHT", "TZOFFSETFROM:-0500", "TZOFFSETTO:-0400", "DTSTART:20070311T020000",
				"RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU", "TZNAME:EDT", "END:DAYLIGHT",
				"BEGIN:STANDARD", "TZOFFSETFROM:-0400", "TZOFFSETTO:-0500", "DTSTART:20071104T020000",
				"RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU", "TZNAME:EST", "END:STANDARD",
				"END:VTIMEZONE",
				"BEGIN:VEVENT", "UID:j", "SUMMARY:NY call",
				"DTSTART;TZID=America/New_York:20261020T090000", "DTEND;TZID=America/New_York:20261020T100000",
				"END:VEVENT"));
		assertThat(parsed.occurrences().getFirst().start()).isEqualTo(Instant.parse("2026-10-20T13:00:00Z"));
	}

	@Test
	void rejectsWhatIsntICalendar() {
		assertThatThrownBy(() -> parse("<html>nope</html>")).isInstanceOf(IllegalArgumentException.class);
	}
}
