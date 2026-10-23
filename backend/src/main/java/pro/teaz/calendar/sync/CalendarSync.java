package pro.teaz.calendar.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import pro.teaz.calendar.CalendarProperties;
import pro.teaz.calendar.account.Accounts;
import pro.teaz.calendar.account.Accounts.Account;
import pro.teaz.calendar.account.PasswordCipher;
import pro.teaz.calendar.caldav.CalDav;
import pro.teaz.calendar.caldav.CalDav.Credentials;
import pro.teaz.calendar.caldav.CalDavException;
import pro.teaz.calendar.sync.CalendarStore.StoredCalendar;

/**
 * Keeps each user's copy of their iCloud calendars current. Everything else
 * (the API, other services) reads only that copy, never iCloud itself.
 *
 * <ul>
 * <li>Per calendar, a sync asks for the changes since its stored sync token
 * (sync-collection) and fetches only new or changed resources. Without a
 * token, or when iCloud no longer knows it, the whole calendar is compared.</li>
 * <li>In the background every {@code sync-interval}; when events are read and
 * the copy is older than {@code stale-after} (the answer comes from the copy
 * right away); on a manual refresh, at most once per {@code refresh-cooldown}.</li>
 * <li>One sync per account at a time. After 429/5xx the account waits longer
 * each time (2, 4, 8 ... up to 120 minutes) before the next automatic sync.</li>
 * <li>When Apple rejects the password (401), the account stops syncing until
 * the user enters a new one. Its events stay.</li>
 * </ul>
 */
@Service
public class CalendarSync {

	private static final Logger log = LoggerFactory.getLogger(CalendarSync.class);

	private static final Duration MAX_BACKOFF = Duration.ofMinutes(120);
	static final String PASSWORD_REJECTED = "Apple no longer accepts the app-specific password. Please enter a new one.";

	private final CalDav caldav;
	private final Accounts accounts;
	private final CalendarStore store;
	private final PasswordCipher cipher;
	private final CalendarProperties properties;
	private final Clock clock;
	private final TaskExecutor executor;
	private final Set<String> running = ConcurrentHashMap.newKeySet();
	// Start of the last sync attempt per user, for the cooldown and so failing syncs aren't retried every minute.
	private final Map<String, Instant> attempted = new ConcurrentHashMap<>();
	// Consecutive 429/5xx per user, for the backoff.
	private final Map<String, Integer> failures = new ConcurrentHashMap<>();

	CalendarSync(CalDav caldav, Accounts accounts, CalendarStore store, PasswordCipher cipher,
			CalendarProperties properties, Clock clock, @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
		this.caldav = caldav;
		this.accounts = accounts;
		this.store = store;
		this.cipher = cipher;
		this.properties = properties;
		this.clock = clock;
		this.executor = executor;
	}

	/**
	 * Checks the credentials with Apple, stores them and runs the first sync (a
	 * failure there is kept on the account). The same Apple ID again only
	 * replaces the password; another one replaces the account and its events.
	 *
	 * @throws CalDavException.Unauthorized when Apple rejects them; nothing is stored then
	 */
	public void connect(String userId, String appleId, String password) {
		String home = caldav.discover(new Credentials(appleId, password));
		String encrypted = cipher.encrypt(userId, password);
		Optional<Account> existing = accounts.find(userId);
		if (existing.isPresent() && existing.get().appleId().equalsIgnoreCase(appleId)) {
			accounts.updatePassword(userId, encrypted, home);
		} else {
			accounts.create(userId, appleId, encrypted, home, clock.instant());
		}
		attempted.remove(userId);
		failures.remove(userId);
		try {
			sync(userId);
		} catch (CalDavException e) {
			log.warn("First sync for {} failed: {}", userId, e.getMessage());
		}
	}

	/** Deletes the account with its stored password, calendars and events. */
	public void disconnect(String userId) {
		accounts.delete(userId);
		attempted.remove(userId);
		failures.remove(userId);
	}

	public boolean syncing(String userId) {
		return running.contains(userId);
	}

	/**
	 * A manual refresh: syncs now, unless the last sync of the account started
	 * less than {@code refresh-cooldown} ago.
	 *
	 * @return false if it was skipped for the cooldown
	 */
	public boolean refresh(String userId) {
		Instant last = attempted.get(userId);
		if (last != null && last.plus(properties.refreshCooldown()).isAfter(clock.instant())) return false;
		sync(userId);
		return true;
	}

	/**
	 * When events are read: a sync in the background if the copy is older than {@code stale-after}.
	 *
	 * @return whether one was started
	 */
	public boolean syncIfStale(String userId) {
		Optional<Account> account = accounts.find(userId);
		if (account.isEmpty() || !due(account.get(), properties.staleAfter()) || syncing(userId)) return false;
		executor.execute(() -> {
			try {
				sync(userId);
			} catch (CalDavException e) {
				log.info("Sync on reading for {} failed: {}", userId, e.getMessage());
			} catch (RuntimeException e) {
				log.warn("Sync on reading for {} failed", userId, e);
			}
		});
		return true;
	}

	/** Syncs each account whose last sync is older than {@code sync-interval}. */
	@Scheduled(fixedDelayString = "${calendar.sync-check:60s}", initialDelayString = "${calendar.sync-check:60s}")
	void syncDue() {
		for (Account account : accounts.all()) {
			if (!due(account, properties.syncInterval())) continue;
			try {
				sync(account.userId());
			} catch (CalDavException e) {
				log.info("Sync for {} failed: {}", account.userId(), e.getMessage());
			} catch (RuntimeException e) {
				log.warn("Sync for {} failed", account.userId(), e);
			}
		}
	}

	/**
	 * Syncs the user's account now. Does nothing without an account or while a
	 * sync for it is already running. A failure is stored on the account and thrown.
	 */
	public void sync(String userId) {
		if (!running.add(userId)) return;
		try {
			accounts.find(userId).ifPresent(this::sync);
		} finally {
			running.remove(userId);
		}
	}

	/** Not for an account Apple rejected, and not within {@code after} (or the backoff) of the last attempt. */
	private boolean due(Account account, Duration after) {
		if (account.authFailed()) return false;
		Instant last = attempted.getOrDefault(account.userId(), account.syncedAt());
		if (last == null) return true;
		int failed = failures.getOrDefault(account.userId(), 0);
		Duration wait = failed == 0 ? after : max(after, backoff(failed));
		return !last.plus(wait).isAfter(clock.instant());
	}

	/** 2, 4, 8 ... minutes, at most {@code MAX_BACKOFF}. */
	private static Duration backoff(int failures) {
		Duration wait = Duration.ofMinutes(1L << Math.min(failures, 7));
		return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
	}

	private static Duration max(Duration a, Duration b) {
		return a.compareTo(b) >= 0 ? a : b;
	}

	private void sync(Account account) {
		String userId = account.userId();
		Instant now = clock.instant();
		attempted.put(userId, now);
		try {
			Credentials credentials = credentials(account);
			List<CalDav.RemoteCalendar> remote = caldav.calendars(credentials, account.homeUrl());
			store.retainCalendars(userId, remote.stream().map(CalDav.RemoteCalendar::url).toList());
			remote.forEach(calendar -> store.saveCalendar(userId, calendar));
			for (StoredCalendar calendar : store.calendars(userId)) syncCalendar(userId, credentials, calendar, now);

			// The window of recurring events moves with today; what was fetched just now has today's already.
			LocalDate today = LocalDate.ofInstant(now, properties.timezone());
			if (account.expandedOn() != null && !today.equals(account.expandedOn())) expandRecurring(userId, now);
			accounts.markExpanded(userId, today);

			accounts.markSynced(userId, now);
			failures.remove(userId);
		} catch (CalDavException.Unauthorized e) {
			log.info("Apple rejected the password of {}; no syncs until a new one is entered.", userId);
			accounts.markFailed(userId, PASSWORD_REJECTED, true);
			throw e;
		} catch (CalDavException.Unavailable e) {
			failures.merge(userId, 1, Integer::sum);
			accounts.markFailed(userId, e.getMessage(), false);
			throw e;
		} catch (CalDavException e) {
			accounts.markFailed(userId, e.getMessage(), false);
			throw e;
		}
	}

	/** The stored password, decrypted; one that can't be (key changed) counts as rejected. */
	private Credentials credentials(Account account) {
		try {
			return new Credentials(account.appleId(), cipher.decrypt(account.userId(), account.password()));
		} catch (IllegalStateException e) {
			log.warn("Stored password of {} can't be decrypted: {}", account.userId(), e.getMessage());
			throw new CalDavException.Unauthorized();
		}
	}

	private void syncCalendar(String userId, Credentials credentials, StoredCalendar calendar, Instant now) {
		CalDav.Changes changes;
		try {
			changes = caldav.changes(credentials, calendar.url(), calendar.syncToken());
		} catch (CalDavException.InvalidSyncToken e) {
			log.info("Sync token of calendar {} expired, comparing the whole calendar.", calendar.id());
			changes = caldav.changes(credentials, calendar.url(), null);
		}
		Map<String, String> stored = store.etags(calendar.id());
		if (changes.complete()) {
			for (String href : stored.keySet()) {
				if (!changes.changed().containsKey(href)) store.deleteObject(calendar.id(), href);
			}
		}
		for (String href : changes.deleted()) store.deleteObject(calendar.id(), href);

		List<String> fetch = changes.changed().entrySet().stream()
				.filter(e -> e.getValue() == null || !e.getValue().equals(stored.get(e.getKey())))
				.map(Map.Entry::getKey)
				.toList();
		if (!fetch.isEmpty()) {
			Window window = window(now);
			for (CalDav.Resource resource : caldav.fetch(credentials, calendar.url(), fetch)) {
				store.saveObject(userId, calendar.id(), resource, parse(resource.ics(), resource.href(), window), now);
			}
		}
		// Only now: if anything above failed, the next sync asks for the same changes again.
		store.setSyncToken(calendar.id(), changes.syncToken());
	}

	private void expandRecurring(String userId, Instant now) {
		Window window = window(now);
		for (CalendarStore.RecurringObject object : store.recurring(userId)) {
			store.replaceEvents(userId, object, parse(object.ics(), "object " + object.id(), window).occurrences());
		}
	}

	/** An unreadable resource is kept without occurrences (and logged without its content). */
	private Occurrences.Parsed parse(String ics, String name, Window window) {
		try {
			return Occurrences.parse(ics, properties.timezone(), window.from(), window.to());
		} catch (RuntimeException e) {
			log.warn("Skipping unreadable calendar data {}: {}", name, e.getMessage());
			return new Occurrences.Parsed(null, false, List.of());
		}
	}

	/** Recurring events are expanded from {@code expand-past} before today to {@code expand-future} after. */
	private Window window(Instant now) {
		LocalDate today = LocalDate.ofInstant(now, properties.timezone());
		return new Window(today.minus(properties.expandPast()).atStartOfDay(properties.timezone()).toInstant(),
				today.plus(properties.expandFuture()).atStartOfDay(properties.timezone()).toInstant());
	}

	private record Window(Instant from, Instant to) {
	}
}
