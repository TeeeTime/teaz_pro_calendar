package pro.teaz.calendar.caldav;

/** A failed CalDAV call. The message is meant for the user and never contains credentials. */
public class CalDavException extends RuntimeException {

	public CalDavException(String message) {
		super(message);
	}

	public CalDavException(String message, Throwable cause) {
		super(message, cause);
	}

	/** 401: wrong Apple ID or app-specific password, or the password was revoked. */
	public static class Unauthorized extends CalDavException {
		public Unauthorized() {
			super("Apple did not accept the Apple ID and app-specific password.");
		}
	}

	/** 429 or 5xx: Apple is busy or down; try again later, with backoff. */
	public static class Unavailable extends CalDavException {
		public Unavailable(int status) {
			super("iCloud is not available right now (" + status + ").");
		}

		public Unavailable(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** The server no longer knows the sync token: fetch the whole calendar again. */
	public static class InvalidSyncToken extends CalDavException {
		public InvalidSyncToken() {
			super("iCloud no longer knows the calendar's sync token.");
		}
	}
}
