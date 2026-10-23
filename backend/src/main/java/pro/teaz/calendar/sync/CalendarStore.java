package pro.teaz.calendar.sync;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import pro.teaz.calendar.caldav.CalDav;
import pro.teaz.calendar.sync.Occurrences.Occurrence;

/**
 * The synced copy of each account's calendars: tables calendar,
 * calendar_object (the .ics as fetched) and event (their occurrences).
 */
@Repository
public class CalendarStore {

	public record StoredCalendar(long id, String url, String name, String color, String syncToken) {
	}

	record RecurringObject(long id, long calendarId, String ics) {
	}

	private final JdbcClient jdbc;
	private final JdbcTemplate template;

	CalendarStore(JdbcClient jdbc, JdbcTemplate template) {
		this.jdbc = jdbc;
		this.template = template;
	}

	public List<StoredCalendar> calendars(String userId) {
		return jdbc.sql("SELECT id, url, name, color, sync_token FROM calendar WHERE user_id = ? ORDER BY name, id")
				.param(userId)
				.query((rs, i) -> new StoredCalendar(rs.getLong("id"), rs.getString("url"), rs.getString("name"),
						rs.getString("color"), rs.getString("sync_token")))
				.list();
	}

	/** Adds the calendar or updates its name and color; its id either way. */
	long saveCalendar(String userId, CalDav.RemoteCalendar calendar) {
		int updated = jdbc.sql("UPDATE calendar SET name = ?, color = ? WHERE user_id = ? AND url = ?")
				.params(calendar.name(), calendar.color(), userId, calendar.url())
				.update();
		if (updated == 0) {
			KeyHolder key = new GeneratedKeyHolder();
			jdbc.sql("INSERT INTO calendar (user_id, url, name, color) VALUES (?, ?, ?, ?)")
					.params(userId, calendar.url(), calendar.name(), calendar.color())
					.update(key, "id");
			return key.getKey().longValue();
		}
		return jdbc.sql("SELECT id FROM calendar WHERE user_id = ? AND url = ?")
				.params(userId, calendar.url())
				.query(Long.class)
				.single();
	}

	/** Deletes the user's calendars (with their events) that are not among {@code urls} any more. */
	@Transactional
	void retainCalendars(String userId, Collection<String> urls) {
		for (StoredCalendar calendar : calendars(userId)) {
			if (!urls.contains(calendar.url())) deleteCalendar(calendar.id());
		}
	}

	private void deleteCalendar(long calendarId) {
		jdbc.sql("DELETE FROM event WHERE calendar_id = ?").param(calendarId).update();
		jdbc.sql("DELETE FROM calendar WHERE id = ?").param(calendarId).update();
	}

	void setSyncToken(long calendarId, String syncToken) {
		jdbc.sql("UPDATE calendar SET sync_token = ? WHERE id = ?").params(syncToken, calendarId).update();
	}

	/** href to ETag of every stored resource of the calendar. */
	Map<String, String> etags(long calendarId) {
		Map<String, String> etags = new HashMap<>();
		jdbc.sql("SELECT href, etag FROM calendar_object WHERE calendar_id = ?")
				.param(calendarId)
				.query(rs -> {
					etags.put(rs.getString("href"), rs.getString("etag"));
				});
		return etags;
	}

	/** Stores the resource and its occurrences, replacing what was stored under its href. */
	@Transactional
	void saveObject(String userId, long calendarId, CalDav.Resource resource, Occurrences.Parsed parsed, Instant now) {
		deleteObject(calendarId, resource.href());
		KeyHolder key = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO calendar_object (calendar_id, href, etag, uid, ics, recurring, synced_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)""")
				.params(calendarId, resource.href(), resource.etag(), parsed.uid(), resource.ics(), parsed.recurring(),
						utc(now))
				.update(key, "id");
		insertEvents(userId, calendarId, key.getKey().longValue(), parsed.occurrences());
	}

	@Transactional
	void deleteObject(long calendarId, String href) {
		jdbc.sql("""
				DELETE FROM event WHERE object_id IN (SELECT id FROM calendar_object WHERE calendar_id = ? AND href = ?)""")
				.params(calendarId, href)
				.update();
		jdbc.sql("DELETE FROM calendar_object WHERE calendar_id = ? AND href = ?").params(calendarId, href).update();
	}

	List<RecurringObject> recurring(String userId) {
		return jdbc.sql("""
				SELECT o.id, o.calendar_id, o.ics FROM calendar_object o JOIN calendar c ON c.id = o.calendar_id
				WHERE c.user_id = ? AND o.recurring""")
				.param(userId)
				.query((rs, i) -> new RecurringObject(rs.getLong("id"), rs.getLong("calendar_id"), rs.getString("ics")))
				.list();
	}

	/** New occurrences of a stored object, e.g. for a window that moved on. */
	@Transactional
	void replaceEvents(String userId, RecurringObject object, List<Occurrence> occurrences) {
		jdbc.sql("DELETE FROM event WHERE object_id = ?").param(object.id()).update();
		insertEvents(userId, object.calendarId(), object.id(), occurrences);
	}

	private void insertEvents(String userId, long calendarId, long objectId, List<Occurrence> occurrences) {
		template.batchUpdate("""
				INSERT INTO event (object_id, calendar_id, user_id, title, location, all_day, starts_at, ends_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
				occurrences.stream().map(o -> new Object[] {objectId, calendarId, userId, o.title(), o.location(),
						o.allDay(), utc(o.start()), utc(o.end())}).toList());
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
