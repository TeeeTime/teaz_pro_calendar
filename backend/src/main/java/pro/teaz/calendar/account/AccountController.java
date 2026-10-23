package pro.teaz.calendar.account;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import pro.teaz.calendar.CalendarProperties;
import pro.teaz.calendar.caldav.CalDav;
import pro.teaz.calendar.caldav.CalDavException;
import pro.teaz.calendar.security.CurrentUser;
import pro.teaz.calendar.sync.CalendarSync;

/** The user's iCloud account: connecting it, a new password, refreshing, disconnecting. */
@RestController
@RequestMapping("/api/account")
class AccountController {

	/**
	 * @param sample           made-up calendars, no iCloud behind it (local dev)
	 * @param syncedAt         null until the first sync succeeded
	 * @param syncError        why the last sync failed, null if it didn't
	 * @param passwordRejected Apple rejected the stored password; syncing waits for a new one
	 */
	record Status(boolean connected, boolean sample, String appleId, OffsetDateTime connectedAt,
			OffsetDateTime syncedAt, boolean syncing, String syncError, boolean passwordRejected) {
	}

	/** The password is never sent back or logged. */
	record Connect(String appleId, String password) {
		@Override
		public String toString() {
			return "Connect[" + appleId + "]";
		}
	}

	private static final int MAX_LENGTH = 320;

	private final Accounts accounts;
	private final CalendarSync sync;
	private final CalDav caldav;
	private final CurrentUser currentUser;
	private final CalendarProperties properties;

	AccountController(Accounts accounts, CalendarSync sync, CalDav caldav, CurrentUser currentUser,
			CalendarProperties properties) {
		this.accounts = accounts;
		this.sync = sync;
		this.caldav = caldav;
		this.currentUser = currentUser;
		this.properties = properties;
	}

	@GetMapping
	Status account() {
		return status(currentUser.id());
	}

	/**
	 * Connects the iCloud account, or stores a new app-specific password for it.
	 * Apple checks the credentials first; the first sync runs before this answers.
	 */
	@PutMapping
	Status connect(@RequestBody Connect body) {
		String appleId = body.appleId() == null ? "" : body.appleId().trim();
		String password = body.password() == null ? "" : body.password().trim();
		if (appleId.isEmpty() || password.isEmpty() || appleId.length() > MAX_LENGTH || password.length() > MAX_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter your Apple ID and an app-specific password.");
		}
		String user = currentUser.id();
		try {
			sync.connect(user, appleId, password);
		} catch (CalDavException.Unauthorized e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Apple did not accept this Apple ID and "
					+ "app-specific password. Check both; your normal Apple ID password doesn't work here.");
		}
		return status(user);
	}

	/** Syncs now (at most every {@code refresh-cooldown}); a failure shows up in {@code syncError}. */
	@PostMapping("/sync")
	Status refresh() {
		String user = currentUser.id();
		try {
			sync.refresh(user);
		} catch (CalDavException e) {
			// Stored on the account, part of the status.
		}
		return status(user);
	}

	/** Deletes the stored password and the synced calendars. */
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void disconnect() {
		sync.disconnect(currentUser.id());
	}

	private Status status(String user) {
		return accounts.find(user)
				.map(a -> new Status(true, caldav.sample(), a.appleId(), local(a.connectedAt()), local(a.syncedAt()),
						sync.syncing(user), a.syncError(), a.authFailed()))
				.orElseGet(() -> new Status(false, caldav.sample(), null, null, null, false, null, false));
	}

	private OffsetDateTime local(Instant instant) {
		return instant == null ? null : instant.atZone(properties.timezone()).toOffsetDateTime();
	}
}
