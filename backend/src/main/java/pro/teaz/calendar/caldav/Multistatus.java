package pro.teaz.calendar.caldav;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * A WebDAV 207 Multi-Status response body (RFC 4918), as PROPFIND and REPORT
 * return it.
 *
 * @param uri       where it came from (after redirects); hrefs are relative to it
 * @param responses one per resource
 * @param syncToken the new token of a sync-collection REPORT, else null
 */
record Multistatus(URI uri, List<Response> responses, String syncToken) {

	static final String DAV = "DAV:";
	static final String CALDAV = "urn:ietf:params:xml:ns:caldav";
	static final String APPLE = "http://apple.com/ns/ical/";

	/**
	 * @param href   as the server wrote it
	 * @param status the response's own status, or 200 when it has properties
	 * @param prop   the properties found (the 2xx propstat), or null
	 */
	record Response(String href, int status, Element prop) {

		/** Trimmed text of a property, or null if it's missing or empty. */
		String text(String namespace, String name) {
			Element element = child(prop, namespace, name);
			if (element == null) return null;
			String text = element.getTextContent().trim();
			return text.isEmpty() ? null : text;
		}

		/** The href inside a property such as current-user-principal or calendar-home-set. */
		String hrefIn(String namespace, String name) {
			Element element = child(prop, namespace, name);
			Element href = child(element, DAV, "href");
			return href == null ? null : href.getTextContent().trim();
		}
	}

	static Multistatus parse(URI uri, String body) {
		Element root;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			// Never resolve DTDs or external entities from a response (XXE).
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setExpandEntityReferences(false);
			root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body))).getDocumentElement();
		} catch (ParserConfigurationException | SAXException | IOException e) {
			throw new CalDavException("iCloud sent an unreadable response.", e);
		}
		if (!DAV.equals(root.getNamespaceURI()) || !"multistatus".equals(root.getLocalName())) {
			throw new CalDavException("iCloud sent an unexpected response.");
		}

		List<Response> responses = new ArrayList<>();
		for (Element response : children(root, DAV, "response")) {
			Element href = child(response, DAV, "href");
			if (href == null) continue;
			Element status = child(response, DAV, "status");
			int code = status == null ? 404 : statusCode(status.getTextContent());
			Element prop = null;
			for (Element propstat : children(response, DAV, "propstat")) {
				Element propstatStatus = child(propstat, DAV, "status");
				int propstatCode = propstatStatus == null ? 200 : statusCode(propstatStatus.getTextContent());
				if (propstatCode / 100 == 2) {
					prop = child(propstat, DAV, "prop");
					if (status == null) code = 200;
				}
			}
			responses.add(new Response(href.getTextContent().trim(), code, prop));
		}
		Element syncToken = child(root, DAV, "sync-token");
		return new Multistatus(uri, responses, syncToken == null ? null : syncToken.getTextContent().trim());
	}

	/** "HTTP/1.1 404 Not Found" -> 404. */
	private static int statusCode(String line) {
		String[] parts = line.trim().split("\\s+");
		try {
			return parts.length > 1 ? Integer.parseInt(parts[1]) : 500;
		} catch (NumberFormatException e) {
			return 500;
		}
	}

	static Element child(Element parent, String namespace, String name) {
		if (parent == null) return null;
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element e && namespace.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())) {
				return e;
			}
		}
		return null;
	}

	static List<Element> children(Element parent, String namespace, String name) {
		List<Element> result = new ArrayList<>();
		if (parent == null) return result;
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element e && namespace.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())) {
				result.add(e);
			}
		}
		return result;
	}
}
