package pro.teaz.calendar.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import pro.teaz.calendar.CalendarProperties;
import pro.teaz.calendar.account.Accounts;
import pro.teaz.calendar.account.Accounts.Account;
import pro.teaz.calendar.security.CurrentUser;
import pro.teaz.calendar.sync.CalendarStore;
import pro.teaz.calendar.sync.CalendarSync;

/**
 * The logged-in user's events in a range of days, from the synced copy. A
 * stale copy is refreshed in the background, the answer doesn't wait for it.
 */
@RestController
@RequestMapping("/api/events")
class EventController {

	static final int MAX_DAYS = 100;

	/** @param color {@code #rrggbb} as set in iCloud, or null */
	record Calendar(long id, String name, String color) {
	}

	/**
	 * @param start {@code 2026-10-23T09:30:00+02:00}, or for all-day events the first day, {@code 2026-10-23}
	 * @param end   likewise; for all-day events the day after the last one (exclusive)
	 */
	record Event(long id, long calendarId, String title, String location, boolean allDay, String start, String end) {
	}

	/**
	 * @param connected false: no iCloud account yet, everything else is empty
	 * @param syncing   a sync is running (maybe just started by this request): ask again shortly for its result
	 */
	record EventList(boolean connected, OffsetDateTime syncedAt, boolean syncing, List<Calendar> calendars,
			List<Event> events) {
	}

	private final Events events;
	private final CalendarStore store;
	private final Accounts accounts;
	private final CalendarSync sync;
	private final CurrentUser currentUser;
	private final CalendarProperties properties;

	EventController(Events events, CalendarStore store, Accounts accounts, CalendarSync sync, CurrentUser currentUser,
			CalendarProperties properties) {
		this.events = events;
		this.store = store;
		this.accounts = accounts;
		this.sync = sync;
		this.currentUser = currentUser;
		this.properties = properties;
	}

	/** Events overlapping the days {@code from} up to (excluding) {@code to}. */
	@GetMapping
	EventList between(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		if (!to.isAfter(from) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"\"to\" must be after \"from\", at most " + MAX_DAYS + " days.");
		}
		String user = currentUser.id();
		Optional<Account> account = accounts.find(user);
		if (account.isEmpty()) return new EventList(false, null, false, List.of(), List.of());

		boolean syncing = sync.syncIfStale(user) || sync.syncing(user);
		ZoneId zone = properties.timezone();
		List<Calendar> calendars = store.calendars(user).stream()
				.map(c -> new Calendar(c.id(), c.name(), c.color()))
				.toList();
		List<Event> list = events.between(user, from.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant())
				.stream()
				.map(e -> new Event(e.id(), e.calendarId(), e.title(), e.location(), e.allDay(),
						format(e.start(), e.allDay(), zone), format(e.end(), e.allDay(), zone)))
				.toList();
		Instant syncedAt = account.get().syncedAt();
		return new EventList(true, syncedAt == null ? null : syncedAt.atZone(zone).toOffsetDateTime(),
				syncing, calendars, list);
	}

	private static String format(Instant instant, boolean allDay, ZoneId zone) {
		return allDay ? LocalDate.ofInstant(instant, zone).toString() : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zone));
	}
}
