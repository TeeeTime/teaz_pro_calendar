package pro.teaz.calendar;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A small CalDAV server on localhost that answers like iCloud: Basic auth, a
 * redirect on the entry point, the principal and calendar home (absolute, as
 * on Apple's partition hosts), a calendar with events plus a reminders list
 * and the scheduling inbox that must be skipped, sync-collection with tokens
 * and calendar-multiget. One calendar, "Work", whose content tests change.
 */
public class FakeICloud implements AutoCloseable {

	public static final String APPLE_ID = "jordan@icloud.com";
	public static final String HOME = "/p07/123/calendars/";
	public static final String WORK = HOME + "work/";

	private static final Pattern TOKEN = Pattern.compile("<d:sync-token>(.*?)</d:sync-token>");
	private static final Pattern HREF = Pattern.compile("<d:href>(.*?)</d:href>");

	private record Stored(String etag, String ics) {
	}

	private record Change(int version, String href) {
	}

	private final HttpServer server;
	private final Map<String, Stored> resources = new LinkedHashMap<>();
	private final List<Change> changes = new ArrayList<>();
	private int version;
	private int oldestToken;
	private String password;
	private Integer failNext;
	/** The hrefs of each calendar-multiget, to check that only changes are fetched. */
	public final List<List<String>> fetched = new ArrayList<>();

	public FakeICloud() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		reset();
	}

	public URI url() {
		return URI.create("http://localhost:" + server.getAddress().getPort() + "/");
	}

	public synchronized void reset() {
		resources.clear();
		changes.clear();
		fetched.clear();
		version = 0;
		oldestToken = 0;
		password = "abcd-efgh-ijkl-mnop";
		failNext = null;
	}

	public synchronized void setPassword(String password) {
		this.password = password;
	}

	public synchronized String password() {
		return password;
	}

	/** Adds or changes {@code work/<name>.ics}. */
	public synchronized void put(String name, String... events) {
		String href = WORK + name + ".ics";
		version++;
		resources.put(href, new Stored("\"etag-" + version + "\"", calendar(events)));
		changes.add(new Change(version, href));
	}

	public synchronized void remove(String name) {
		String href = WORK + name + ".ics";
		version++;
		resources.remove(href);
		changes.add(new Change(version, href));
	}

	/** Every sync token handed out so far stops working, as when Apple expires them. */
	public synchronized void expireTokens() {
		oldestToken = version + 1;
	}

	/** Removes an event without it showing up in any sync token's changes. */
	public synchronized void removeSilently(String name) {
		resources.remove(WORK + name + ".ics");
	}

	/** The next request is answered with this status (429, 503, ...). */
	public synchronized void failNext(int status) {
		failNext = status;
	}

	public static String event(String uid, String title, String... lines) {
		return "BEGIN:VEVENT\r\nUID:" + uid + "\r\nSUMMARY:" + title + "\r\n" + String.join("\r\n", lines)
				+ "\r\nEND:VEVENT\r\n";
	}

	private static String calendar(String... events) {
		return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Apple Inc.//iCloud//EN\r\n" + String.join("", events)
				+ "END:VCALENDAR\r\n";
	}

	private synchronized void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (failNext != null) {
				int status = failNext;
				failNext = null;
				send(exchange, status, "");
				return;
			}
			String expected = "Basic " + Base64.getEncoder().encodeToString(
					(APPLE_ID + ":" + password).getBytes(StandardCharsets.UTF_8));
			if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
				send(exchange, 401, "");
				return;
			}
			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();
			if (method.equals("PROPFIND") && path.equals("/")) {
				exchange.getResponseHeaders().add("Location", "/entry/");
				send(exchange, 301, "");
			} else if (method.equals("PROPFIND") && path.equals("/entry/")) {
				send(exchange, 207, multistatus(response("/entry/",
						"<d:current-user-principal><d:href>/123/principal/</d:href></d:current-user-principal>")));
			} else if (method.equals("PROPFIND") && path.equals("/123/principal/")) {
				send(exchange, 207, multistatus(response("/123/principal/", "<c:calendar-home-set><d:href>"
						+ url().resolve(HOME) + "</d:href></c:calendar-home-set>")));
			} else if (method.equals("PROPFIND") && path.equals(HOME)) {
				send(exchange, 207, multistatus(
						response(HOME, "<d:resourcetype><d:collection/></d:resourcetype>"),
						response(HOME + "inbox/", "<d:resourcetype><d:collection/><c:schedule-inbox/></d:resourcetype>"),
						response(WORK, "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>"
								+ "<d:displayname>Work</d:displayname><a:calendar-color>#1BADF8FF</a:calendar-color>"
								+ "<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set>"),
						response(HOME + "reminders/", "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>"
								+ "<d:displayname>Reminders</d:displayname>"
								+ "<c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>")));
			} else if (method.equals("REPORT") && path.equals(WORK) && body.contains("sync-collection")) {
				syncCollection(exchange, body);
			} else if (method.equals("REPORT") && path.equals(WORK) && body.contains("calendar-multiget")) {
				multiget(exchange, body);
			} else {
				send(exchange, 404, "");
			}
		}
	}

	private void syncCollection(HttpExchange exchange, String body) throws IOException {
		Matcher matcher = TOKEN.matcher(body);
		String token = matcher.find() ? matcher.group(1) : "";
		List<String> entries = new ArrayList<>();
		if (token.isEmpty()) {
			resources.forEach((href, stored) -> entries.add(response(href, "<d:getetag>" + stored.etag() + "</d:getetag>")));
		} else {
			int since = token.startsWith("v") ? Integer.parseInt(token.substring(1)) : -1;
			if (since < oldestToken) {
				send(exchange, 403, "<?xml version=\"1.0\"?><d:error xmlns:d=\"DAV:\"><d:valid-sync-token/></d:error>");
				return;
			}
			Set<String> hrefs = new LinkedHashSet<>();
			changes.stream().filter(c -> c.version() > since).forEach(c -> hrefs.add(c.href()));
			for (String href : hrefs) {
				Stored stored = resources.get(href);
				entries.add(stored == null
						? "<d:response><d:href>" + href + "</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>"
						: response(href, "<d:getetag>" + stored.etag() + "</d:getetag>"));
			}
		}
		send(exchange, 207, multistatusWithToken("<d:sync-token>v" + version + "</d:sync-token>", entries.toArray(String[]::new)));
	}

	private void multiget(HttpExchange exchange, String body) throws IOException {
		List<String> hrefs = new ArrayList<>();
		HREF.matcher(body).results().forEach(m -> hrefs.add(m.group(1)));
		fetched.add(hrefs);
		List<String> entries = new ArrayList<>();
		for (String href : hrefs) {
			Stored stored = resources.get(href);
			entries.add(stored == null
					? "<d:response><d:href>" + href + "</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>"
					: response(href, "<d:getetag>" + stored.etag() + "</d:getetag><c:calendar-data>"
							+ stored.ics().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
							+ "</c:calendar-data>"));
		}
		send(exchange, 207, multistatus(entries.toArray(String[]::new)));
	}

	private static String response(String href, String props) {
		return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + props
				+ "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
	}

	private static String multistatus(String... responses) {
		return multistatusWithToken("", responses);
	}

	private static String multistatusWithToken(String trailer, String... responses) {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><d:multistatus xmlns:d=\"DAV:\" "
				+ "xmlns:c=\"urn:ietf:params:xml:ns:caldav\" xmlns:a=\"http://apple.com/ns/ical/\">"
				+ String.join("", responses) + trailer + "</d:multistatus>";
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		if (!body.isEmpty()) exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
