package pro.teaz.calendar.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pro.teaz.calendar.FakeICloud.event;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import pro.teaz.calendar.FakeICloud;
import pro.teaz.calendar.TestClock;

/**
 * Connecting iCloud, syncing and reading events, against {@link FakeICloud}
 * over real HTTP, with the real prod security and a fixed clock: Wednesday
 * 2026-10-21, 12:00 in Berlin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
@Import(TestClock.Config.class)
class CalendarFlowTests {

	static final FakeICloud icloud;

	static {
		try {
			icloud = new FakeICloud();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void useFakeICloud(DynamicPropertyRegistry registry) {
		registry.add("calendar.sample", () -> "false");
		registry.add("calendar.caldav-url", () -> icloud.url().toString());
	}

	@AfterAll
	static void stop() {
		icloud.close();
	}

	static final String WEEK = "/api/events?from=2026-10-19&to=2026-10-26";

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	CalendarSync sync;

	@BeforeEach
	void reset() {
		jdbc.sql("DELETE FROM calendar_account").update();
		icloud.reset();
		clock.set(LocalDateTime.of(2026, 10, 21, 12, 0));
		icloud.put("dentist", event("dentist", "Dentist", "LOCATION:Hauptstr. 5",
				"DTSTART;TZID=Europe/Berlin:20261020T083000", "DTEND;TZID=Europe/Berlin:20261020T091500"));
		icloud.put("call", event("call", "Customer call",
				"DTSTART:20261020T064500Z", "DTEND:20261020T080000Z"));
		icloud.put("standup",
				event("standup", "Standup", "DTSTART;TZID=Europe/Berlin:20261019T090000",
						"DTEND;TZID=Europe/Berlin:20261019T091500", "RRULE:FREQ=DAILY;COUNT=5",
						"EXDATE;TZID=Europe/Berlin:20261021T090000"),
				event("standup", "Standup (moved)", "RECURRENCE-ID;TZID=Europe/Berlin:20261022T090000",
						"DTSTART;TZID=Europe/Berlin:20261022T094000", "DTEND;TZID=Europe/Berlin:20261022T101000"));
		icloud.put("trip", event("trip", "Weekend trip", "DTSTART;VALUE=DATE:20261024", "DTEND;VALUE=DATE:20261026"));
	}

	private static JwtRequestPostProcessor user(String id) {
		return jwt().jwt(token -> token.subject(id));
	}

	private JsonNode body(ResultActions result) throws Exception {
		return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode read(String path, String userId) throws Exception {
		return body(mvc.perform(get(path).with(user(userId))));
	}

	private ResultActions connect(String userId, String password) throws Exception {
		return mvc.perform(put("/api/account").with(user(userId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("appleId", FakeICloud.APPLE_ID, "password", password))));
	}

	private JsonNode refresh(String userId) throws Exception {
		return body(mvc.perform(post("/api/account/sync").with(user(userId))));
	}

	private static List<String> titles(JsonNode list) {
		List<String> titles = new ArrayList<>();
		list.path("events").forEach(e -> titles.add(e.path("title").asString()));
		return titles;
	}

	@Test
	void withoutAccountEverythingIsEmpty() throws Exception {
		assertThat(read("/api/account", "a").path("connected").asBoolean()).isFalse();
		JsonNode events = read(WEEK, "a");
		assertThat(events.path("connected").asBoolean()).isFalse();
		assertThat(events.path("events").size()).isZero();
	}

	@Test
	void connectingSyncsTheCalendarsWithEvents() throws Exception {
		JsonNode status = body(connect("a", icloud.password()));
		assertThat(status.path("connected").asBoolean()).isTrue();
		assertThat(status.path("appleId").asString()).isEqualTo(FakeICloud.APPLE_ID);
		assertThat(status.path("syncedAt").asString()).isEqualTo("2026-10-21T12:00:00+02:00");
		assertThat(status.path("syncError").isNull()).isTrue();

		JsonNode week = read(WEEK, "a");
		// Only "Work": the reminders list and the scheduling inbox hold no events.
		assertThat(week.path("calendars").size()).isEqualTo(1);
		assertThat(week.path("calendars").get(0).path("name").asString()).isEqualTo("Work");
		assertThat(week.path("calendars").get(0).path("color").asString()).isEqualTo("#1badf8");

		// All-day first, then by start; the 21st is excluded, the 22nd moved.
		assertThat(titles(week)).containsExactly("Weekend trip", "Standup", "Dentist", "Customer call", "Standup",
				"Standup (moved)", "Standup");
		JsonNode trip = week.path("events").get(0);
		assertThat(trip.path("allDay").asBoolean()).isTrue();
		assertThat(trip.path("start").asString()).isEqualTo("2026-10-24");
		assertThat(trip.path("end").asString()).isEqualTo("2026-10-26");
		JsonNode call = week.path("events").get(3);
		assertThat(call.path("start").asString()).isEqualTo("2026-10-20T08:45:00+02:00");
		assertThat(call.path("end").asString()).isEqualTo("2026-10-20T10:00:00+02:00");
		JsonNode dentist = week.path("events").get(2);
		assertThat(dentist.path("location").asString()).isEqualTo("Hauptstr. 5");
		assertThat(dentist.path("start").asString()).isEqualTo("2026-10-20T08:30:00+02:00");

		// Only the days asked for.
		assertThat(titles(read("/api/events?from=2026-10-20&to=2026-10-21", "a")))
				.containsExactly("Dentist", "Customer call", "Standup");
	}

	@Test
	void todayIsTheDayInBerlin() throws Exception {
		JsonNode empty = read("/api/events/today", "a");
		assertThat(empty.path("connected").asBoolean()).isFalse();
		assertThat(empty.path("date").asString()).isEqualTo("2026-10-21");

		connect("a", icloud.password()).andExpect(status().isOk());
		// The 21st's standup was cancelled (EXDATE): nothing today.
		assertThat(titles(read("/api/events/today", "a"))).isEmpty();

		// 00:30 on the 20th in Berlin is still the 19th in UTC.
		clock.set(LocalDateTime.of(2026, 10, 20, 0, 30));
		JsonNode today = read("/api/events/today", "a");
		assertThat(today.path("date").asString()).isEqualTo("2026-10-20");
		assertThat(today.path("calendars").get(0).path("color").asString()).isEqualTo("#1badf8");
		assertThat(titles(today)).containsExactly("Dentist", "Customer call", "Standup");

		clock.set(LocalDateTime.of(2026, 10, 24, 12, 0));
		JsonNode saturday = read("/api/events/today", "a");
		assertThat(titles(saturday)).containsExactly("Weekend trip");
		assertThat(saturday.path("events").get(0).path("allDay").asBoolean()).isTrue();
	}

	@Test
	void thePasswordIsStoredEncrypted() throws Exception {
		connect("a", icloud.password()).andExpect(status().isOk());
		String stored = jdbc.sql("SELECT password FROM calendar_account").query(String.class).single();
		assertThat(stored).doesNotContain(icloud.password()).doesNotContain("abcd");
	}

	@Test
	void aWrongPasswordIsRejectedAndNothingStored() throws Exception {
		connect("a", "wrong-password").andExpect(status().isBadRequest());
		assertThat(read("/api/account", "a").path("connected").asBoolean()).isFalse();
	}

	@Test
	void refreshingFetchesOnlyChangesAndHasACooldown() throws Exception {
		connect("a", icloud.password()).andExpect(status().isOk());
		icloud.fetched.clear();
		icloud.put("dentist", event("dentist", "Dentist (new time)",
				"DTSTART;TZID=Europe/Berlin:20261020T140000", "DTEND;TZID=Europe/Berlin:20261020T143000"));
		icloud.remove("call");
		icloud.put("lunch", event("lunch", "Lunch", "DTSTART;TZID=Europe/Berlin:20261023T120000",
				"DTEND;TZID=Europe/Berlin:20261023T130000"));

		// Within 30 seconds of the last sync: nothing happens.
		clock.advance(Duration.ofSeconds(10));
		refresh("a");
		assertThat(titles(read(WEEK, "a"))).contains("Dentist", "Customer call").doesNotContain("Lunch");

		clock.advance(Duration.ofSeconds(30));
		JsonNode status = refresh("a");
		assertThat(status.path("syncedAt").asString()).isEqualTo("2026-10-21T12:00:40+02:00");
		assertThat(titles(read(WEEK, "a"))).containsExactly("Weekend trip", "Standup", "Standup",
				"Dentist (new time)", "Standup (moved)", "Standup", "Lunch");
		assertThat(icloud.fetched).containsExactly(List.of(FakeICloud.WORK + "dentist.ics", FakeICloud.WORK + "lunch.ics"));
	}

	@Test
	void anExpiredSyncTokenComparesTheWholeCalendar() throws Exception {
		connect("a", icloud.password()).andExpect(status().isOk());
		icloud.removeSilently("dentist");
		icloud.expireTokens();
		clock.advance(Duration.ofMinutes(1));

		JsonNode status = refresh("a");
		assertThat(status.path("syncError").isNull()).isTrue();
		assertThat(titles(read(WEEK, "a"))).doesNotContain("Dentist").contains("Customer call");
	}

	@Test
	void aRevokedPasswordStopsSyncingUntilANewOneIsEntered() throws Exception {
		connect("a", icloud.password()).andExpect(status().isOk());
		icloud.setPassword("qrst-uvwx-yzab-cdef");
		clock.advance(Duration.ofMinutes(1));

		JsonNode status = refresh("a");
		assertThat(status.path("passwordRejected").asBoolean()).isTrue();
		assertThat(status.path("syncError").asString()).contains("enter a new one");
		// The events stay, and no background sync bothers Apple with the old password.
		assertThat(titles(read(WEEK, "a"))).contains("Dentist");
		icloud.put("lunch", event("lunch", "Lunch", "DTSTART;TZID=Europe/Berlin:20261023T120000",
				"DTEND;TZID=Europe/Berlin:20261023T130000"));
		clock.advance(Duration.ofHours(1));
		sync.syncDue();
		assertThat(titles(read(WEEK, "a"))).doesNotContain("Lunch");

		status = body(connect("a", "qrst-uvwx-yzab-cdef"));
		assertThat(status.path("passwordRejected").asBoolean()).isFalse();
		assertThat(status.path("syncError").isNull()).isTrue();
		assertThat(titles(read(WEEK, "a"))).contains("Dentist", "Lunch");
	}

	@Test
	void backgroundSyncRunsEverySyncIntervalAndKeepsTheCopyOnErrors() throws Exception {
		connect("a", icloud.password()).andExpect(status().isOk());
		icloud.put("lunch", event("lunch", "Lunch", "DTSTART;TZID=Europe/Berlin:20261023T120000",
				"DTEND;TZID=Europe/Berlin:20261023T130000"));

		clock.advance(Duration.ofMinutes(10));
		sync.syncDue();
		assertThat(titles(read(WEEK, "a"))).doesNotContain("Lunch");

		clock.advance(Duration.ofMinutes(5));
		icloud.failNext(503);
		sync.syncDue();
		JsonNode status = read("/api/account", "a");
		assertThat(status.path("syncError").asString()).contains("503");
		assertThat(titles(read(WEEK, "a"))).contains("Dentist").doesNotContain("Lunch");

		clock.advance(Duration.ofMinutes(15));
		sync.syncDue();
		assertThat(read("/api/account", "a").path("syncError").isNull()).isTrue();
		assertThat(titles(read(WEEK, "a"))).contains("Lunch");
	}

	@Test
	void recurringEventsFollowTheWindowAsDaysPass() throws Exception {
		icloud.put("weekly", event("weekly", "Weekly", "DTSTART;TZID=Europe/Berlin:20261019T170000",
				"DTEND;TZID=Europe/Berlin:20261019T180000", "RRULE:FREQ=WEEKLY"));
		connect("a", icloud.password()).andExpect(status().isOk());
		// Two years ahead (calendar.expand-future) is the end of the stored occurrences.
		assertThat(titles(read("/api/events?from=2028-11-06&to=2028-11-13", "a"))).isEmpty();

		clock.set(LocalDateTime.of(2026, 11, 20, 12, 0));
		sync.syncDue();
		assertThat(titles(read("/api/events?from=2028-11-06&to=2028-11-13", "a"))).containsExactly("Weekly");
	}

	@Test
	void usersOnlySeeTheirOwnCalendar() throws Exception {
		connect("a", icloud.password()).andExpect(status().isOk());
		assertThat(read(WEEK, "b").path("connected").asBoolean()).isFalse();
		assertThat(read(WEEK, "b").path("events").size()).isZero();
	}

	@Test
	void disconnectingDeletesEverything() throws Exception {
		connect("a", icloud.password()).andExpect(status().isOk());
		mvc.perform(delete("/api/account").with(user("a"))).andExpect(status().isNoContent());

		assertThat(read("/api/account", "a").path("connected").asBoolean()).isFalse();
		for (String table : List.of("calendar_account", "calendar", "calendar_object", "event")) {
			assertThat(jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single()).as(table).isZero();
		}
	}

	@Test
	void rejectsBadRanges() throws Exception {
		mvc.perform(get("/api/events?from=2026-10-26&to=2026-10-26").with(user("a"))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/events?from=2026-01-01&to=2026-12-31").with(user("a"))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/events?from=yesterday&to=2026-12-31").with(user("a"))).andExpect(status().isBadRequest());
	}
}
