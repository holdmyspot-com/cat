package com.holdmyspot.cat.common.delegation;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Selects the supported native HTTP proxy route and writes it as invocation-owned Maven user settings.
 */
public final class MavenSettings
{
	/**
	 * The filename Maven reads for the invocation-owned user settings.
	 */
	private static final String SETTINGS_FILE_NAME = "settings.xml";

	/**
	 * The Maven settings namespace used for the generated document.
	 */
	private static final String SETTINGS_NAMESPACE = "http://maven.apache.org/SETTINGS/1.3.0";

	/**
	 * The stable identifier for the single proxy entry owned by this adapter.
	 */
	private static final String MAVEN_PROXY_ID = "delegation";

	/**
	 * The Maven proxy protocol supported by the shared-route profile.
	 */
	private static final String HTTP_PROXY_PROTOCOL = "http";
	/** The highest port accepted by the native proxy profile. */
	private static final int MAXIMUM_PORT = 65_535;

	/**
	 * The lowercase native key for the HTTP proxy route.
	 */
	private static final String HTTP_PROXY_LOWERCASE_KEY = "http_proxy";

	/**
	 * The uppercase native key for the HTTP proxy route.
	 */
	private static final String HTTP_PROXY_UPPERCASE_KEY = "HTTP_PROXY";

	/**
	 * The lowercase native key for the HTTPS proxy route.
	 */
	private static final String HTTPS_PROXY_LOWERCASE_KEY = "https_proxy";

	/**
	 * The uppercase native key for the HTTPS proxy route.
	 */
	private static final String HTTPS_PROXY_UPPERCASE_KEY = "HTTPS_PROXY";

	/**
	 * The lowercase native key for the shared proxy route.
	 */
	private static final String ALL_PROXY_LOWERCASE_KEY = "all_proxy";

	/**
	 * The uppercase native key for the shared proxy route.
	 */
	private static final String ALL_PROXY_UPPERCASE_KEY = "ALL_PROXY";

	/**
	 * The lowercase native key for proxy bypass hosts.
	 */
	private static final String NO_PROXY_LOWERCASE_KEY = "no_proxy";

	/**
	 * The uppercase native key for proxy bypass hosts.
	 */
	private static final String NO_PROXY_UPPERCASE_KEY = "NO_PROXY";

	/**
	 * The exact bypass host names accepted by this bounded native profile.
	 */
	// PMD.AvoidUsingHardCodedIP: Maven needs the exact loopback host text accepted from native NO_PROXY.
	@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
	private static final Set<String> SUPPORTED_BYPASS_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

	/**
	 * Prevents construction of the stateless settings adapter.
	 */
	private MavenSettings()
	{
	}

	/**
	 * Selects the one supported shared HTTP proxy from the explicit native environment values.
	 * Lowercase keys take precedence over uppercase keys, including when the lowercase value is empty. Each absent
	 * HTTP or HTTPS value falls back to the selected ALL value. An empty value selects a direct route. Returns empty
	 * when both effective routes are direct, or the validated proxy when both routes select the same endpoint.
	 *
	 * @param inherited the explicit native transport environment values to inspect
	 * @return the shared proxy, or empty when both effective routes are direct
	 * @throws NullPointerException if {@code inherited} or a selected value is null
	 * @throws UnsupportedOperationException if a selected route is malformed, unsupported, or differs between HTTP
	 *     and HTTPS
	 */
	public static Optional<Proxy> selectProxy(Map<String, String> inherited)
	{
		Objects.requireNonNull(inherited, "inherited");

		Optional<NativeSetting> allProxy = resolveSetting(inherited, ALL_PROXY_LOWERCASE_KEY,
			ALL_PROXY_UPPERCASE_KEY);
		Optional<NativeSetting> httpProxy = resolveSetting(inherited, HTTP_PROXY_LOWERCASE_KEY,
			HTTP_PROXY_UPPERCASE_KEY);
		Optional<NativeSetting> httpsProxy = resolveSetting(inherited, HTTPS_PROXY_LOWERCASE_KEY,
			HTTPS_PROXY_UPPERCASE_KEY);
		Optional<URI> httpEndpoint = selectEndpoint(httpProxy, allProxy);
		Optional<URI> httpsEndpoint = selectEndpoint(httpsProxy, allProxy);

		if (httpEndpoint.isEmpty() && httpsEndpoint.isEmpty())
			return Optional.empty();
		if (httpEndpoint.isEmpty() || httpsEndpoint.isEmpty())
			throw unsupported("HTTP_PROXY/HTTPS_PROXY", "HTTP and HTTPS must both be direct or use the same proxy");

		URI selectedHttpEndpoint = httpEndpoint.orElseThrow();
		URI selectedHttpsEndpoint = httpsEndpoint.orElseThrow();
		if (!sameEndpoint(selectedHttpEndpoint, selectedHttpsEndpoint))
			throw unsupported("HTTP_PROXY/HTTPS_PROXY", "HTTP and HTTPS must select the same proxy endpoint");

		Optional<NativeSetting> bypassSetting = resolveSetting(inherited, NO_PROXY_LOWERCASE_KEY,
			NO_PROXY_UPPERCASE_KEY);
		List<String> bypassHosts = selectBypassHosts(bypassSetting);
		return Optional.of(new Proxy(selectedHttpEndpoint.getHost(), effectivePort(selectedHttpEndpoint), bypassHosts));
	}

	/**
	 * Creates the invocation-owned Maven user settings file for the explicit native transport values.
	 * The method validates the complete supported proxy profile before creating {@code settings.xml}; it never creates
	 * the home directory or replaces an existing destination. The returned path is absolute and normalized.
	 *
	 * @param home the existing invocation-owned home directory
	 * @param inherited the explicit native transport environment values to inspect
	 * @return the normalized absolute path to the newly created Maven user settings file
	 * @throws NullPointerException if any of {@code home}, {@code inherited}, or a selected environment value are null
	 * @throws UnsupportedOperationException if the selected proxy profile is malformed or unsupported
	 * @throws IOException if the destination exists or the file cannot be created or written
	 */
	public static Path write(Path home, Map<String, String> inherited) throws IOException
	{
		Objects.requireNonNull(home, "home");
		Objects.requireNonNull(inherited, "inherited");
		Path settingsFile = home.toAbsolutePath().normalize().resolve(SETTINGS_FILE_NAME);
		Optional<Proxy> proxy = selectProxy(inherited);

		try (OutputStream output = Files.newOutputStream(settingsFile, CREATE_NEW, WRITE))
		{
			XMLStreamWriter writer = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(output, "UTF-8");
			try (XMLWriterScope writerScope = new XMLWriterScope(writer))
			{
				writeDocument(writerScope.writer(), proxy);
			}
		}
		catch (XMLStreamException exception)
		{
			throw new IOException("Unable to write Maven user settings file " + settingsFile, exception);
		}

		return settingsFile;
	}

	/**
	 * Resolves one native key pair with lowercase presence taking precedence over uppercase presence.
	 *
	 * @param inherited the explicit native transport environment values to inspect
	 * @param lowercaseKey the lowercase key checked first
	 * @param uppercaseKey the uppercase fallback key
	 * @return the selected key and value, or empty when both keys are absent
	 * @throws NullPointerException if a present key maps to null
	 */
	private static Optional<NativeSetting> resolveSetting(Map<String, String> inherited, String lowercaseKey,
		String uppercaseKey)
	{
		if (inherited.containsKey(lowercaseKey))
			return Optional.of(new NativeSetting(lowercaseKey, Objects.requireNonNull(inherited.get(lowercaseKey),
				lowercaseKey)));
		if (inherited.containsKey(uppercaseKey))
			return Optional.of(new NativeSetting(uppercaseKey, Objects.requireNonNull(inherited.get(uppercaseKey),
				uppercaseKey)));
		return Optional.empty();
	}

	/**
	 * Selects and validates one protocol endpoint, using the shared setting only when its specific key is absent.
	 *
	 * @param specific the setting for one protocol
	 * @param allProxy the shared fallback setting
	 * @return the validated endpoint, or empty when the effective route is direct
	 * @throws UnsupportedOperationException if the selected endpoint is malformed or outside the supported profile
	 */
	private static Optional<URI> selectEndpoint(Optional<NativeSetting> specific, Optional<NativeSetting> allProxy)
	{
		Optional<NativeSetting> effectiveSetting;
		if (specific.isPresent())
			effectiveSetting = specific;
		else
			effectiveSetting = allProxy;
		if (effectiveSetting.isEmpty())
			return Optional.empty();

		NativeSetting setting = effectiveSetting.orElseThrow();
		if (setting.value().isEmpty())
			return Optional.empty();

		URI endpoint;
		try
		{
			endpoint = new URI(setting.value()).parseServerAuthority();
		}
		catch (URISyntaxException _)
		{
			throw unsupported(setting.key(), "must be a valid HTTP server URI");
		}

		if (!HTTP_PROXY_PROTOCOL.equalsIgnoreCase(endpoint.getScheme()))
			throw unsupported(setting.key(), "must use the HTTP scheme");

		String host = endpoint.getHost();
		if (host == null)
			throw unsupported(setting.key(), "must name a DNS or IPv4 server host");
		if (host.contains(":"))
			throw unsupported(setting.key(), "must not use an IPv6 proxy server host");
		if (endpoint.getRawUserInfo() != null)
			throw unsupported(setting.key(), "must not include user information");
		if (endpoint.getRawQuery() != null)
			throw unsupported(setting.key(), "must not include a query");
		if (endpoint.getRawFragment() != null)
			throw unsupported(setting.key(), "must not include a fragment");
		String path = endpoint.getRawPath();
		if (!path.isEmpty() && !path.equals("/"))
			throw unsupported(setting.key(), "must have an empty or root path");
		int port = endpoint.getPort();
		if (port == 0 || port > MAXIMUM_PORT)
			throw unsupported(setting.key(), "must use a port from 1 through " + MAXIMUM_PORT);
		return Optional.of(endpoint);
	}

	/**
	 * Compares endpoints using the supported equivalence: case-insensitive scheme and host, effective port, and
	 * equivalent empty or root paths.
	 *
	 * @param first the first validated endpoint
	 * @param second the second validated endpoint
	 * @return true when the endpoints select the same proxy
	 */
	private static boolean sameEndpoint(URI first, URI second)
	{
		boolean sameServer = first.getScheme().equalsIgnoreCase(second.getScheme()) &&
			first.getHost().equalsIgnoreCase(second.getHost());
		boolean samePort = effectivePort(first) == effectivePort(second);
		boolean samePath = first.getRawPath().equals(second.getRawPath()) ||
			(first.getRawPath().isEmpty() && second.getRawPath().equals("/")) ||
			(first.getRawPath().equals("/") && second.getRawPath().isEmpty());
		return sameServer && samePort && samePath;
	}

	/**
	 * Returns the endpoint's explicit port or the default HTTP port.
	 *
	 * @param endpoint a validated HTTP endpoint
	 * @return the explicit port, or 80 when the URI has no port
	 */
	private static int effectivePort(URI endpoint)
	{
		if (endpoint.getPort() == -1)
			return 80;
		return endpoint.getPort();
	}

	/**
	 * Selects supported loopback bypass hosts from the optional native bypass setting.
	 *
	 * @param bypassSetting the selected NO_PROXY setting
	 * @return distinct supported bypass host names in first-seen order
	 * @throws UnsupportedOperationException if a nonempty token is outside the supported bypass profile
	 */
	private static List<String> selectBypassHosts(Optional<NativeSetting> bypassSetting)
	{
		if (bypassSetting.isEmpty() || bypassSetting.orElseThrow().value().isEmpty())
			return List.of();

		NativeSetting setting = bypassSetting.orElseThrow();
		Set<String> selectedHosts = new LinkedHashSet<>();
		for (String token : setting.value().split(",", -1))
		{
			String trimmedToken = token.strip();
			if (trimmedToken.isEmpty())
				continue;

			String canonicalHost = canonicalBypassHost(trimmedToken, setting.key());
			selectedHosts.add(canonicalHost);
		}
		return List.copyOf(selectedHosts);
	}

	/**
	 * Canonicalizes one supported bypass host and rejects richer native bypass syntax.
	 *
	 * @param token a stripped native bypass token
	 * @param key the native key that supplied the token
	 * @return the canonical supported host name
	 * @throws UnsupportedOperationException if the token is not a supported loopback host
	 */
	private static String canonicalBypassHost(String token, String key)
	{
		String normalizedHost = token.toLowerCase(Locale.ROOT);
		if (SUPPORTED_BYPASS_HOSTS.contains(normalizedHost))
			return normalizedHost;
		throw unsupported(key, "may contain only localhost, 127.0.0.1, or ::1");
	}

	/**
	 * Creates a safe unsupported-profile failure that identifies the native key without exposing its value.
	 *
	 * @param key the native key whose value is unsupported
	 * @param constraint the supported constraint that the value violates
	 * @return the profile rejection to throw
	 */
	private static UnsupportedOperationException unsupported(String key, String constraint)
	{
		return new UnsupportedOperationException("Unsupported Maven proxy configuration in " + key + ": " + constraint);
	}

	/**
	 * Writes the Maven settings document for the selected proxy.
	 *
	 * @param writer the XML writer for the destination document
	 * @param proxy the selected shared proxy, or empty for direct access
	 * @throws XMLStreamException if the XML writer cannot complete the document
	 */
	private static void writeDocument(XMLStreamWriter writer, Optional<Proxy> proxy) throws XMLStreamException
	{
		writer.writeStartDocument("UTF-8", "1.0");
		writer.writeStartElement("", "settings", SETTINGS_NAMESPACE);
		writer.writeDefaultNamespace(SETTINGS_NAMESPACE);
		if (proxy.isPresent())
			writeProxy(writer, proxy.orElseThrow());
		writer.writeEndElement();
		writer.writeEndDocument();
		writer.flush();
	}

	/**
	 * Writes the sole active proxy entry selected for the shared route.
	 *
	 * @param writer the XML writer for the settings document
	 * @param proxy the validated shared HTTP proxy
	 * @throws XMLStreamException if the XML writer cannot complete the proxy entry
	 */
	private static void writeProxy(XMLStreamWriter writer, Proxy proxy) throws XMLStreamException
	{
		writer.writeStartElement("", "proxies", SETTINGS_NAMESPACE);
		writer.writeStartElement("", "proxy", SETTINGS_NAMESPACE);
		writeElement(writer, "id", MAVEN_PROXY_ID);
		writeElement(writer, "active", "true");
		writeElement(writer, "protocol", HTTP_PROXY_PROTOCOL);
		writeElement(writer, "host", proxy.host());
		writeElement(writer, "port", Integer.toString(proxy.port()));
		if (!proxy.nonProxyHosts().isEmpty())
			writeElement(writer, "nonProxyHosts", String.join("|", proxy.nonProxyHosts()));
		writer.writeEndElement();
		writer.writeEndElement();
	}

	/**
	 * Writes one namespaced Maven settings element with the supplied text value.
	 *
	 * @param writer the XML writer for the settings document
	 * @param name the Maven settings element name
	 * @param value the element's text content
	 * @throws XMLStreamException if the XML writer cannot complete the element
	 */
	private static void writeElement(XMLStreamWriter writer, String name, String value) throws XMLStreamException
	{
		writer.writeStartElement("", name, SETTINGS_NAMESPACE);
		writer.writeCharacters(value);
		writer.writeEndElement();
	}

	/**
	 * Holds one native key and its explicit value after lowercase and uppercase precedence is applied.
	 *
	 * @param key the exact key selected from the caller's environment map
	 * @param value the selected key's value, including an explicit empty value
	 */
	private record NativeSetting(String key, String value)
	{
	}

	/**
	 * Closes the XML stream writer through try-with-resources while preserving write failures.
	 */
	private static final class XMLWriterScope implements AutoCloseable
	{
		private final XMLStreamWriter writer;

		/**
		 * Wraps the XML writer for automatic resource cleanup.
		 *
		 * @param writer the writer to close with this scope
		 */
		private XMLWriterScope(XMLStreamWriter writer)
		{
			this.writer = Objects.requireNonNull(writer, "writer");
		}

		/**
		 * Returns the wrapped XML writer.
		 *
		 * @return the XML writer for the active scope
		 */
		private XMLStreamWriter writer()
		{
			return writer;
		}

		/**
		 * Closes the wrapped XML writer.
		 *
		 * @throws XMLStreamException if the XML writer cannot close
		 */
		@Override
		public void close() throws XMLStreamException
		{
			writer.close();
		}
	}

	/**
	 * Describes the validated endpoint and supported bypass hosts for a shared HTTP proxy.
	 *
	 * @param host the DNS or IPv4 server host
	 * @param port the effective server port from 1 through 65535
	 * @param nonProxyHosts distinct supported bypass host names in first-seen order
	 */
	public record Proxy(String host, int port, List<String> nonProxyHosts)
	{
		/**
		 * Validates and copies the selected proxy values.
		 *
		 * @param host the DNS or IPv4 server host
		 * @param port the effective server port from 1 through 65535
		 * @param nonProxyHosts supported bypass host names to copy
		 * @throws NullPointerException if any of {@code host}, {@code nonProxyHosts}, or an entry are null
		 * @throws IllegalArgumentException if {@code host} is blank, {@code port} is outside its supported range, or
		 *     an entry is not a supported bypass host
		 */
		public Proxy
		{
			Objects.requireNonNull(host, "host");
			if (host.isBlank())
				throw new IllegalArgumentException("host must not be blank");
		if (port < 1 || port > MAXIMUM_PORT)
			throw new IllegalArgumentException("port must be from 1 through 65535");
			Objects.requireNonNull(nonProxyHosts, "nonProxyHosts");
			nonProxyHosts = List.copyOf(nonProxyHosts);
			for (String bypassHost : nonProxyHosts)
			{
				if (!SUPPORTED_BYPASS_HOSTS.contains(bypassHost))
					throw new IllegalArgumentException("nonProxyHosts contains an unsupported bypass host");
			}
		}
	}
}
