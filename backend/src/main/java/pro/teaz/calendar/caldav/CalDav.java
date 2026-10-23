package pro.teaz.calendar.caldav;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading an iCloud account's calendars over CalDAV (RFC 4791, sync per
 * RFC 6578). Every call may throw {@link CalDavException}; most importantly
 * {@link CalDavException.Unauthorized} when Apple rejects the credentials.
 */
public interface CalDav {

	/** Apple ID and app-specific password. Never logged: {@link #toString()} leaves the password out. */
	record Credentials(String appleId, String password) {
		@Override
		public String toString() {
			return "Credentials[" + appleId + "]";
		}
	}

	/**
	 * @param url   absolute, on the account's partition host
	 * @param color {@code #rrggbb}, or null
	 */
	record RemoteCalendar(String url, String name, String color) {
	}

	/**
	 * What changed in a calendar since a sync token.
	 *
	 * @param syncToken for the next call
	 * @param changed   href to ETag of every new or changed resource; with {@code complete}, every resource
	 * @param deleted   hrefs of deleted resources
	 * @param complete  {@code changed} lists the whole calendar (no token was given), so anything stored
	 *                  that isn't in it is gone
	 */
	record Changes(String syncToken, Map<String, String> changed, Set<String> deleted, boolean complete) {
	}

	/** One calendar object resource: its href (path), ETag and iCalendar data. */
	record Resource(String href, String etag, String ics) {
	}

	/** Checks the credentials and finds the account's calendar home (calendar-home-set). */
	String discover(Credentials credentials);

	/** The calendars in the home that can hold events (not reminders, not the scheduling inbox). */
	List<RemoteCalendar> calendars(Credentials credentials, String homeUrl);

	/**
	 * Changes since {@code syncToken}, or the whole calendar with {@code null}.
	 * {@link CalDavException.InvalidSyncToken} when the server no longer knows the token.
	 */
	Changes changes(Credentials credentials, String calendarUrl, String syncToken);

	/** The resources with these hrefs; deleted ones in the meantime are missing from the result. */
	List<Resource> fetch(Credentials credentials, String calendarUrl, Collection<String> hrefs);

	/** Made-up calendars, no iCloud behind it (local dev). */
	boolean sample();
}
