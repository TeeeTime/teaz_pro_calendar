package pro.teaz.calendar.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Each user's iCloud account (table calendar_account); deleting one deletes its calendars and events. */
@Repository
public class Accounts {

	/**
	 * @param password    encrypted, see {@link PasswordCipher}
	 * @param syncedAt    start of the last successful sync; null until the first one
	 * @param syncError   why the last sync failed; null if it didn't
	 * @param authFailed  Apple rejected the password; no syncs until a new one is entered
	 * @param expandedOn  the day recurring events were last expanded on
	 */
	public record Account(
			String userId,
			String appleId,
			String password,
			String homeUrl,
			Instant connectedAt,
			Instant syncedAt,
			String syncError,
			boolean authFailed,
			LocalDate expandedOn) {
	}

	private static final String COLUMNS = """
			user_id, apple_id, password, home_url, connected_at, synced_at, sync_error, auth_failed, expanded_on""";

	private final JdbcClient jdbc;

	Accounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Account> find(String userId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM calendar_account WHERE user_id = ?")
				.param(userId)
				.query(Accounts::row)
				.optional();
	}

	public List<Account> all() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM calendar_account ORDER BY user_id")
				.query(Accounts::row)
				.list();
	}

	/** A new account, replacing an earlier one with its calendars and events. */
	public void create(String userId, String appleId, String password, String homeUrl, Instant now) {
		delete(userId);
		jdbc.sql("INSERT INTO calendar_account (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, NULL, NULL, false, NULL)")
				.params(userId, appleId, password, homeUrl, utc(now))
				.update();
	}

	/** A new password for the same Apple ID: calendars and events stay, syncing resumes. */
	public void updatePassword(String userId, String password, String homeUrl) {
		jdbc.sql("""
				UPDATE calendar_account SET password = ?, home_url = ?, auth_failed = false, sync_error = NULL
				WHERE user_id = ?""")
				.params(password, homeUrl, userId)
				.update();
	}

	public void markSynced(String userId, Instant syncedAt) {
		jdbc.sql("UPDATE calendar_account SET synced_at = ?, sync_error = NULL WHERE user_id = ?")
				.params(utc(syncedAt), userId)
				.update();
	}

	public void markFailed(String userId, String error, boolean authFailed) {
		jdbc.sql("UPDATE calendar_account SET sync_error = ?, auth_failed = ? WHERE user_id = ?")
				.params(error, authFailed, userId)
				.update();
	}

	public void markExpanded(String userId, LocalDate day) {
		jdbc.sql("UPDATE calendar_account SET expanded_on = ? WHERE user_id = ?").params(day, userId).update();
	}

	public void delete(String userId) {
		jdbc.sql("DELETE FROM calendar_account WHERE user_id = ?").param(userId).update();
	}

	private static Account row(ResultSet rs, int i) throws SQLException {
		return new Account(
				rs.getString("user_id"),
				rs.getString("apple_id"),
				rs.getString("password"),
				rs.getString("home_url"),
				instant(rs, "connected_at"),
				instant(rs, "synced_at"),
				rs.getString("sync_error"),
				rs.getBoolean("auth_failed"),
				rs.getObject("expanded_on", LocalDate.class));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
