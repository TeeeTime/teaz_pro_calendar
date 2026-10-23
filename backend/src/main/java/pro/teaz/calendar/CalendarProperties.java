package pro.teaz.calendar;

import java.net.URI;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code calendar.*}, see application.properties.
 *
 * @param timezone        time zone of every time in API responses, of all-day events and of "today"
 * @param sample          made-up calendars instead of iCloud (local dev); any Apple ID is accepted
 * @param caldavUrl       iCloud's CalDAV entry point; the account's own host (pXX-caldav.icloud.com) is
 *                        discovered from there
 * @param encryptionKey   AES-256 key (base64 of 32 bytes) for the stored app-specific passwords
 * @param syncInterval    how often each account is synced in the background
 * @param staleAfter      reading events starts a sync in the background when the last one is older
 * @param refreshCooldown minimum time between two manual refreshes of one account
 * @param expandPast      recurring events are stored as occurrences from this long before today ...
 * @param expandFuture    ... to this long after today
 * @param cors            other Teaz apps allowed to read the tile endpoint from the browser
 */
@ConfigurationProperties("calendar")
public record CalendarProperties(
		@DefaultValue("Europe/Berlin") ZoneId timezone,
		@DefaultValue("false") boolean sample,
		@DefaultValue("https://caldav.icloud.com/") URI caldavUrl,
		String encryptionKey,
		@DefaultValue("15m") Duration syncInterval,
		@DefaultValue("2m") Duration staleAfter,
		@DefaultValue("30s") Duration refreshCooldown,
		@DefaultValue("P2Y") Period expandPast,
		@DefaultValue("P2Y") Period expandFuture,
		@DefaultValue Cors cors) {

	public record Cors(@DefaultValue List<String> allowedOrigins) {
	}
}
