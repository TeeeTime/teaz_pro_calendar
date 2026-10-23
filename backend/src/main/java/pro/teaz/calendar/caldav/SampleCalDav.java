package pro.teaz.calendar.caldav;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Made-up calendars for local dev, so the app works offline (like the mock
 * auth): two calendars around the current week, with overlapping meetings,
 * all-day and multi-day events, one across midnight and recurring ones with an
 * exception. Any Apple ID works; the password "wrong" is rejected, to try that
 * path. Nothing ever changes after the first sync.
 */
class SampleCalDav implements CalDav {

	static final String HOME = "sample:/calendars/";
	private static final String WORK = HOME + "work/";
	private static final String PRIVATE = HOME + "home/";
	private static final String TOKEN = "sample-1";
	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("'T'HHmmss");

	private final Clock clock;
	private final ZoneId zone;

	SampleCalDav(Clock clock, ZoneId zone) {
		this.clock = clock;
		this.zone = zone;
	}

	@Override
	public String discover(Credentials credentials) {
		if ("wrong".equals(credentials.password())) throw new CalDavException.Unauthorized();
		return HOME;
	}

	@Override
	public List<RemoteCalendar> calendars(Credentials credentials, String homeUrl) {
		return List.of(new RemoteCalendar(WORK, "Work", "#f59e0b"), new RemoteCalendar(PRIVATE, "Home", "#10b981"));
	}

	@Override
	public Changes changes(Credentials credentials, String calendarUrl, String syncToken) {
		if (syncToken != null) return new Changes(TOKEN, Map.of(), Set.of(), false);
		Map<String, String> all = new LinkedHashMap<>();
		resources(calendarUrl).keySet().forEach(href -> all.put(href, "\"1\""));
		return new Changes(TOKEN, all, Set.of(), true);
	}

	@Override
	public List<Resource> fetch(Credentials credentials, String calendarUrl, Collection<String> hrefs) {
		Map<String, String> resources = resources(calendarUrl);
		return hrefs.stream()
				.filter(resources::containsKey)
				.map(href -> new Resource(href, "\"1\"", resources.get(href)))
				.toList();
	}

	@Override
	public boolean sample() {
		return true;
	}

	/** href to .ics, relative to this week's Monday. */
	private Map<String, String> resources(String calendarUrl) {
		LocalDate monday = LocalDate.now(clock.withZone(zone)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		Map<String, String> events = new LinkedHashMap<>();
		if (WORK.equals(calendarUrl)) {
			LocalDate thursday = monday.plusDays(3);
			events.put("standup", timed("standup", "Standup", null, monday, "09:00", monday, "09:15",
					"RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
					"EXDATE;TZID=" + zone.getId() + ":" + DATE.format(monday.plusDays(2)) + TIME.format(LocalTime.of(9, 0)))
					+ override("standup", "Standup (moved)", thursday, "09:00", "09:40", "10:10"));
			events.put("review", timed("review", "Design review", "Room 2", monday.plusDays(1), "10:00", monday.plusDays(1), "11:30"));
			events.put("customer", timed("customer", "Customer call", null, monday.plusDays(1), "10:30", monday.plusDays(1), "12:00"));
			events.put("one-on-one", timed("one-on-one", "1:1 with Sam", null, monday.plusDays(1), "11:00", monday.plusDays(1), "11:30"));
			events.put("workshop", timed("workshop", "Workshop", "Room 4", monday.plusDays(2), "13:00", monday.plusDays(2), "17:00"));
			events.put("planning", timed("planning", "Sprint planning", null, thursday, "14:00", thursday, "15:30"));
			events.put("retro", timed("retro", "Retro", null, monday.plusDays(4), "15:00", monday.plusDays(4), "16:00"));
			events.put("offsite", allDay("offsite", "Team offsite", monday.plusDays(9), monday.plusDays(11), null));
		} else if (PRIVATE.equals(calendarUrl)) {
			events.put("gym", timed("gym", "Gym", null, monday, "18:00", monday, "19:30", "RRULE:FREQ=WEEKLY;BYDAY=MO,TH"));
			events.put("run", timed("run", "Morning run", null, monday.plusDays(1), "07:00", monday.plusDays(1), "07:45"));
			events.put("dentist", timed("dentist", "Dentist", "Hauptstr. 5", monday.plusDays(2), "08:30", monday.plusDays(2), "09:15"));
			events.put("dinner", timed("dinner", "Dinner with friends", null, monday.plusDays(4), "19:30", monday.plusDays(4), "22:30"));
			events.put("concert", timed("concert", "Concert", "Columbiahalle", monday.plusDays(5), "21:00", monday.plusDays(6), "01:30"));
			events.put("trip", allDay("trip", "Weekend trip", monday.plusDays(5), monday.plusDays(7), null));
			events.put("birthday", allDay("birthday", "Mom's birthday", monday.plusDays(2), monday.plusDays(3), "RRULE:FREQ=YEARLY"));
		}
		Map<String, String> byHref = new LinkedHashMap<>();
		events.forEach((name, ics) -> byHref.put("/calendars/" + calendarUrl.substring(HOME.length()) + name + ".ics",
				calendar(ics)));
		return byHref;
	}

	private String timed(String uid, String title, String location, LocalDate startDay, String start, LocalDate endDay,
			String end, String... extra) {
		StringBuilder event = new StringBuilder("BEGIN:VEVENT\r\nUID:" + uid + "@sample\r\nSUMMARY:" + title + "\r\n");
		if (location != null) event.append("LOCATION:").append(location).append("\r\n");
		event.append("DTSTART;TZID=").append(zone.getId()).append(':').append(local(startDay, start)).append("\r\n");
		event.append("DTEND;TZID=").append(zone.getId()).append(':').append(local(endDay, end)).append("\r\n");
		for (String line : extra) event.append(line).append("\r\n");
		return event.append("END:VEVENT\r\n").toString();
	}

	/** The occurrence of {@code uid} on {@code day} at {@code original}, moved to start..end. */
	private String override(String uid, String title, LocalDate day, String original, String start, String end) {
		return "BEGIN:VEVENT\r\nUID:" + uid + "@sample\r\nSUMMARY:" + title + "\r\n"
				+ "RECURRENCE-ID;TZID=" + zone.getId() + ":" + local(day, original) + "\r\n"
				+ "DTSTART;TZID=" + zone.getId() + ":" + local(day, start) + "\r\n"
				+ "DTEND;TZID=" + zone.getId() + ":" + local(day, end) + "\r\n"
				+ "END:VEVENT\r\n";
	}

	private static String allDay(String uid, String title, LocalDate start, LocalDate endExclusive, String rule) {
		return "BEGIN:VEVENT\r\nUID:" + uid + "@sample\r\nSUMMARY:" + title + "\r\n"
				+ "DTSTART;VALUE=DATE:" + DATE.format(start) + "\r\n"
				+ "DTEND;VALUE=DATE:" + DATE.format(endExclusive) + "\r\n"
				+ (rule == null ? "" : rule + "\r\n")
				+ "END:VEVENT\r\n";
	}

	private static String local(LocalDate day, String time) {
		return DATE.format(day) + TIME.format(LocalTime.parse(time));
	}

	private static String calendar(String events) {
		return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Teaz//Calendar sample//EN\r\n" + events + "END:VCALENDAR\r\n";
	}
}
