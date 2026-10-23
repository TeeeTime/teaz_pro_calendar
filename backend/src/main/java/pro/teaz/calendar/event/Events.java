package pro.teaz.calendar.event;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reading the synced occurrences (table event); writing them is the sync's job. */
@Repository
class Events {

	/** @param allDay start and end are midnights in calendar.timezone, end exclusive */
	record Row(long id, long calendarId, String title, String location, boolean allDay, Instant start, Instant end) {
	}

	private final JdbcClient jdbc;

	Events(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Every occurrence overlapping from..to (an event without duration at {@code from} counts too). */
	List<Row> between(String userId, Instant from, Instant to) {
		return jdbc.sql("""
				SELECT id, calendar_id, title, location, all_day, starts_at, ends_at FROM event
				WHERE user_id = ? AND starts_at < ? AND (ends_at > ? OR starts_at >= ?)
				ORDER BY all_day DESC, starts_at, ends_at DESC, id""")
				.params(userId, utc(to), utc(from), utc(from))
				.query((rs, i) -> new Row(
						rs.getLong("id"),
						rs.getLong("calendar_id"),
						rs.getString("title"),
						rs.getString("location"),
						rs.getBoolean("all_day"),
						rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
						rs.getObject("ends_at", OffsetDateTime.class).toInstant()))
				.list();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
