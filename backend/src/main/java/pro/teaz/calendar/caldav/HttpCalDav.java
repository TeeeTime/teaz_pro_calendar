package pro.teaz.calendar.caldav;

import static pro.teaz.calendar.caldav.Multistatus.APPLE;
import static pro.teaz.calendar.caldav.Multistatus.CALDAV;
import static pro.teaz.calendar.caldav.Multistatus.DAV;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

/**
 * {@link CalDav} against iCloud ({@code caldav.icloud.com}), with plain HTTP
 * and Basic auth. Discovery: PROPFIND current-user-principal on the entry
 * point, then calendar-home-set on the principal, which points to the
 * account's partition host (pXX-caldav.icloud.com). Redirects are followed by
 * hand, keeping method and body, and only within the entry point's domain, so
 * the credentials never go anywhere else.
 */
class HttpCalDav implements CalDav {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private static final int MAX_REDIRECTS = 5;
	// sync-collection may answer in parts (507 on the collection); more rounds than this means something is off.
	private static final int MAX_SYNC_ROUNDS = 50;
	private static final int MULTIGET_BATCH = 50;

	private static final String PRINCIPAL = """
			<d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>""";
	private static final String HOME = """
			<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">\
			<d:prop><c:calendar-home-set/></d:prop></d:propfind>""";
	private static final String CALENDARS = """
			<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">\
			<d:prop><d:resourcetype/><d:displayname/><a:calendar-color/><c:supported-calendar-component-set/></d:prop>\
			</d:propfind>""";

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private final URI entryPoint;
	private final String domain;

	HttpCalDav(URI entryPoint) {
		this.entryPoint = entryPoint;
		String[] labels = entryPoint.getHost().split("\\.");
		this.domain = labels.length >= 2
				? labels[labels.length - 2] + "." + labels[labels.length - 1]
				: entryPoint.getHost();
	}

	@Override
	public String discover(Credentials credentials) {
		Multistatus principal = multistatus(send(credentials, "PROPFIND", entryPoint, "0", PRINCIPAL));
		String principalHref = principal.responses().stream()
				.map(r -> r.hrefIn(DAV, "current-user-principal"))
				.filter(href -> href != null && !href.isEmpty())
				.findFirst()
				.orElseThrow(() -> new CalDavException("iCloud did not name the account's principal."));

		Multistatus home = multistatus(send(credentials, "PROPFIND", principal.uri().resolve(principalHref), "0", HOME));
		String homeHref = home.responses().stream()
				.map(r -> r.hrefIn(CALDAV, "calendar-home-set"))
				.filter(href -> href != null && !href.isEmpty())
				.findFirst()
				.orElseThrow(() -> new CalDavException("iCloud did not name the account's calendar home."));
		URI homeUri = home.uri().resolve(homeHref);
		requireAllowed(homeUri);
		return homeUri.toString();
	}

	@Override
	public List<RemoteCalendar> calendars(Credentials credentials, String homeUrl) {
		URI home = URI.create(homeUrl);
		Multistatus status = multistatus(send(credentials, "PROPFIND", home, "1", CALENDARS));
		List<RemoteCalendar> calendars = new ArrayList<>();
		for (Multistatus.Response response : status.responses()) {
			if (response.prop() == null) continue;
			Element type = Multistatus.child(response.prop(), DAV, "resourcetype");
			if (Multistatus.child(type, CALDAV, "calendar") == null) continue;
			Element components = Multistatus.child(response.prop(), CALDAV, "supported-calendar-component-set");
			// Without the property every component is allowed; iCloud's reminder lists only allow VTODO.
			if (components != null && Multistatus.children(components, CALDAV, "comp").stream()
					.noneMatch(comp -> "VEVENT".equalsIgnoreCase(comp.getAttribute("name")))) {
				continue;
			}
			String name = response.text(DAV, "displayname");
			calendars.add(new RemoteCalendar(status.uri().resolve(response.href()).toString(),
					name == null ? "Calendar" : name, color(response.text(APPLE, "calendar-color"))));
		}
		return calendars;
	}

	@Override
	public Changes changes(Credentials credentials, String calendarUrl, String syncToken) {
		URI calendar = URI.create(calendarUrl);
		Map<String, String> changed = new LinkedHashMap<>();
		Set<String> deleted = new HashSet<>();
		String token = syncToken;
		for (int round = 0; round < MAX_SYNC_ROUNDS; round++) {
			Raw raw = send(credentials, "REPORT", calendar, null, syncCollection(token));
			// RFC 6578 says 403 with DAV:valid-sync-token; other servers answer 400, 409 or 410.
			if (token != null && Set.of(400, 403, 409, 410, 412).contains(raw.status())) {
				throw new CalDavException.InvalidSyncToken();
			}
			Multistatus status = multistatus(raw);
			boolean truncated = false;
			for (Multistatus.Response response : status.responses()) {
				String href = path(status.uri(), response.href());
				if (sameCollection(href, calendar.getRawPath())) {
					truncated |= response.status() == 507;
					continue;
				}
				if (response.status() == 404) {
					changed.remove(href);
					deleted.add(href);
				} else if (response.prop() != null && !href.endsWith("/")) {
					deleted.remove(href);
					changed.put(href, response.text(DAV, "getetag"));
				}
			}
			if (status.syncToken() == null) throw new CalDavException("iCloud sent no sync token.");
			token = status.syncToken();
			if (!truncated) return new Changes(token, changed, deleted, syncToken == null);
		}
		throw new CalDavException("iCloud listed more changes than expected.");
	}

	@Override
	public List<Resource> fetch(Credentials credentials, String calendarUrl, Collection<String> hrefs) {
		URI calendar = URI.create(calendarUrl);
		List<String> all = new ArrayList<>(hrefs);
		List<Resource> resources = new ArrayList<>();
		for (int from = 0; from < all.size(); from += MULTIGET_BATCH) {
			List<String> batch = all.subList(from, Math.min(all.size(), from + MULTIGET_BATCH));
			Multistatus status = multistatus(send(credentials, "REPORT", calendar, "1", multiget(batch)));
			for (Multistatus.Response response : status.responses()) {
				String ics = response.prop() == null ? null : response.text(CALDAV, "calendar-data");
				if (ics == null) continue;
				resources.add(new Resource(path(status.uri(), response.href()), response.text(DAV, "getetag"), ics));
			}
		}
		return resources;
	}

	@Override
	public boolean sample() {
		return false;
	}

	private static String syncCollection(String token) {
		return "<d:sync-collection xmlns:d=\"DAV:\"><d:sync-token>" + (token == null ? "" : escape(token))
				+ "</d:sync-token><d:sync-level>1</d:sync-level><d:prop><d:getetag/></d:prop></d:sync-collection>";
	}

	private static String multiget(List<String> hrefs) {
		StringBuilder body = new StringBuilder("""
				<c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">\
				<d:prop><d:getetag/><c:calendar-data/></d:prop>""");
		for (String href : hrefs) body.append("<d:href>").append(escape(href)).append("</d:href>");
		return body.append("</c:calendar-multiget>").toString();
	}

	/** The response's href as an absolute path, so hrefs from different responses compare equal. */
	private static String path(URI base, String href) {
		return base.resolve(href).getRawPath();
	}

	private static boolean sameCollection(String path, String collection) {
		return strip(path).equals(strip(collection));
	}

	private static String strip(String path) {
		return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
	}

	/** Apple's "#1BADF8FF" (with alpha) -> "#1badf8"; anything else unusable -> null. */
	static String color(String value) {
		if (value == null || !value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) return null;
		return value.substring(0, 7).toLowerCase(Locale.ROOT);
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private record Raw(URI uri, int status, String body) {
	}

	private static Multistatus multistatus(Raw raw) {
		if (raw.status() != 207) throw new CalDavException("iCloud answered " + raw.status() + ".");
		return Multistatus.parse(raw.uri(), raw.body());
	}

	/** Sends the request, following redirects; 401, 429 and 5xx become exceptions. */
	private Raw send(Credentials credentials, String method, URI uri, String depth, String body) {
		String auth = "Basic " + Base64.getEncoder().encodeToString(
				(credentials.appleId() + ":" + credentials.password()).getBytes(StandardCharsets.UTF_8));
		URI target = uri;
		for (int redirects = 0; ; redirects++) {
			requireAllowed(target);
			HttpRequest.Builder request = HttpRequest.newBuilder(target)
					.timeout(TIMEOUT)
					.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.header("Authorization", auth)
					.header("Content-Type", "application/xml; charset=utf-8")
					.header("User-Agent", "Teaz Calendar");
			if (depth != null) request.header("Depth", depth);
			HttpResponse<String> response;
			try {
				response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new CalDavException.Unavailable("iCloud is not reachable right now.", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CalDavException("Interrupted while calling iCloud.", e);
			}
			int status = response.statusCode();
			if (Set.of(301, 302, 307, 308).contains(status)) {
				String location = response.headers().firstValue("Location")
						.orElseThrow(() -> new CalDavException("iCloud redirected without a location."));
				if (redirects >= MAX_REDIRECTS) throw new CalDavException("iCloud redirected too often.");
				target = target.resolve(location);
				continue;
			}
			if (status == 401) throw new CalDavException.Unauthorized();
			if (status == 429 || status >= 500) throw new CalDavException.Unavailable(status);
			return new Raw(target, status, response.body());
		}
	}

	/** Only the entry point's scheme and domain (icloud.com and its subdomains). */
	private void requireAllowed(URI uri) {
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean sameDomain = host.equals(domain) || host.endsWith("." + domain);
		if (!sameDomain || !entryPoint.getScheme().equalsIgnoreCase(uri.getScheme())) {
			throw new CalDavException("iCloud pointed to an unexpected host: " + host);
		}
	}
}
