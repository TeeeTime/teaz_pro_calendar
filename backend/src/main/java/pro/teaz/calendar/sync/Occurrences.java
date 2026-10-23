package pro.teaz.calendar.sync;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.Duration;

/**
 * Turns one calendar object resource (.ics) into the occurrences the views
 * show, with ical4j: a single event, or for a recurring one (RRULE/RDATE minus
 * EXDATE) every occurrence within a window, where occurrences changed on their
 * own (RECURRENCE-ID) replace the regular ones. Cancelled occurrences are left
 * out. Floating times and all-day dates are taken in {@code zone}.
 */
public final class Occurrences {

	/** @param allDay start and end are midnights in the zone, end exclusive */
	public record Occurrence(String title, String location, boolean allDay, Instant start, Instant end) {
	}

	/**
	 * @param uid       the events' UID, null if there's none
	 * @param recurring has a rule or dates to repeat on, so its occurrences depend on the window
	 */
	public record Parsed(String uid, boolean recurring, List<Occurrence> occurrences) {
	}

	private static final String NO_TITLE = "(No title)";

	private Occurrences() {
	}

	/**
	 * @throws IllegalArgumentException when the data isn't iCalendar
	 */
	public static Parsed parse(String ics, ZoneId zone, Instant from, Instant to) {
		Calendar calendar;
		try {
			calendar = new CalendarBuilder().build(new StringReader(ics));
		} catch (IOException | ParserException e) {
			throw new IllegalArgumentException("Not iCalendar data: " + e.getMessage(), e);
		}
		List<VEvent> events = calendar.getComponents(Component.VEVENT);
		String uid = events.stream()
				.map(e -> e.<Property>getProperty(Property.UID).map(Property::getValue).orElse(null))
				.filter(value -> value != null)
				.findFirst()
				.orElse(null);

		List<VEvent> changed = events.stream().filter(e -> e.getProperty(Property.RECURRENCE_ID).isPresent()).toList();
		Set<Instant> replaced = new HashSet<>();
		for (VEvent event : changed) {
			date(event, Property.RECURRENCE_ID).ifPresent(id -> replaced.add(instant(id, zone)));
		}

		boolean recurring = false;
		List<Occurrence> occurrences = new ArrayList<>();
		for (VEvent event : events) {
			if (event.getProperty(Property.RECURRENCE_ID).isPresent()) continue;
			Optional<Temporal> start = date(event, Property.DTSTART);
			if (start.isEmpty()) continue;
			boolean repeats = event.getProperty(Property.RRULE).isPresent() || event.getProperty(Property.RDATE).isPresent();
			recurring |= repeats;
			if (!repeats) {
				if (!cancelled(event)) occurrences.add(single(event, start.get(), zone));
				continue;
			}
			if (cancelled(event)) continue;
			for (Period<Temporal> period : event.<Temporal>calculateRecurrenceSet(window(start.get(), from, to, zone))) {
				Instant occurrenceStart = instant(period.getStart(), zone);
				if (replaced.contains(occurrenceStart)) continue;
				occurrences.add(new Occurrence(title(event), location(event), start.get() instanceof LocalDate,
						occurrenceStart, instant(period.getEnd(), zone)));
			}
		}
		for (VEvent event : changed) {
			Optional<Temporal> start = date(event, Property.DTSTART);
			if (start.isPresent() && !cancelled(event)) occurrences.add(single(event, start.get(), zone));
		}
		occurrences.sort(Comparator.comparing(Occurrence::start));
		return new Parsed(uid, recurring, occurrences);
	}

	private static Occurrence single(VEvent event, Temporal start, ZoneId zone) {
		boolean allDay = start instanceof LocalDate;
		Instant begin = instant(start, zone);
		Instant end = date(event, Property.DTEND).map(t -> instant(t, zone))
				.or(() -> event.<Duration>getProperty(Property.DURATION)
						.map(Duration::getDuration)
						.map(amount -> instant(plus(start, amount), zone)))
				// RFC 5545: without either, an all-day event lasts the day, a timed one no time at all.
				.orElse(allDay ? instant(((LocalDate) start).plusDays(1), zone) : begin);
		if (end.isBefore(begin)) end = begin;
		return new Occurrence(title(event), location(event), allDay, begin, end);
	}

	/** from..to in the same kind of time as the event's start: ical4j compares them as they are. */
	private static Period<? extends Temporal> window(Temporal start, Instant from, Instant to, ZoneId zone) {
		if (start instanceof LocalDate) {
			return new Period<>(LocalDate.ofInstant(from, zone), LocalDate.ofInstant(to, zone));
		}
		if (start instanceof LocalDateTime) {
			return new Period<>(LocalDateTime.ofInstant(from, zone), LocalDateTime.ofInstant(to, zone));
		}
		return new Period<>(from.atZone(zone), to.atZone(zone));
	}

	private static Temporal plus(Temporal start, TemporalAmount amount) {
		return start.plus(amount);
	}

	private static Optional<Temporal> date(VEvent event, String name) {
		return event.<DateProperty<Temporal>>getProperty(name).map(DateProperty::getDate);
	}

	private static boolean cancelled(VEvent event) {
		return event.<Property>getProperty(Property.STATUS)
				.map(status -> "CANCELLED".equalsIgnoreCase(status.getValue()))
				.orElse(false);
	}

	private static String title(VEvent event) {
		return text(event, Property.SUMMARY).orElse(NO_TITLE);
	}

	private static String location(VEvent event) {
		return text(event, Property.LOCATION).orElse(null);
	}

	private static Optional<String> text(VEvent event, String name) {
		return event.<Property>getProperty(name).map(Property::getValue).map(String::trim).filter(s -> !s.isEmpty());
	}

	/** All-day dates and floating times are taken in {@code zone}. */
	static Instant instant(Temporal temporal, ZoneId zone) {
		if (temporal instanceof LocalDate date) return date.atStartOfDay(zone).toInstant();
		if (temporal instanceof LocalDateTime local) return local.atZone(zone).toInstant();
		return Instant.from(temporal);
	}
}
