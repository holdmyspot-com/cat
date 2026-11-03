package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Selects an HTTP proxy from explicit HTTPS proxy settings, with credentials limited to that proxy.
 */
final class ProxyConfiguration extends ProxySelector
{
	private final URI proxy;
	private final String excluded;

	/**
	 * Parses the first nonblank HTTPS proxy variable and the no-proxy host list.
	 *
	 * @param environment supplied proxy variables
	 * @throws IOException if the selected proxy is not an HTTP URL with a valid host and port
	 */
	ProxyConfiguration(Map<String, String> environment) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		String value = environment.getOrDefault("HTTPS_PROXY", "");
		if (value.isBlank())
			value = environment.getOrDefault("https_proxy", "");
		this.excluded = environment.getOrDefault("NO_PROXY", environment.getOrDefault("no_proxy", ""));
		if (value.isBlank())
		{
			this.proxy = null;
			return;
		}
		try
		{
			String proxyUrl = value.strip();
			if (!value.contains("://"))
				proxyUrl = "http://" + proxyUrl;
			URI parsed = new URI(proxyUrl);
			if (!"http".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null ||
				parsed.getPort() == 0 || parsed.getPort() > 65_535 || parsed.getRawQuery() != null ||
				parsed.getRawFragment() != null || !(parsed.getPath().isEmpty() || parsed.getPath().equals("/")))
			{
				throw new IOException("HTTPS_PROXY must name an HTTP proxy host with an optional port and login");
			}
			this.proxy = parsed;
		}
		catch (URISyntaxException _)
		{
			// URI diagnostics contain the proxy's password, so only the invalid setting's name is reported.
			throw new IOException("HTTPS_PROXY is not a valid HTTP proxy URL");
		}
	}

	@Override
	public List<Proxy> select(URI uri)
	{
		if (proxy == null || bypassesProxy(uri))
			return List.of(Proxy.NO_PROXY);
		return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.getHost(), port())));
	}

	@Override
	public void connectFailed(URI uri, SocketAddress address, IOException failure)
	{
		// HttpClient reports connection failures to the request caller; no alternate proxy is configured.
	}

	/**
	 * Creates an authenticator that answers only the configured proxy's challenges.
	 *
	 * @return the proxy-scoped authenticator
	 */
	Authenticator authenticator()
	{
		return new Authenticator()
		{
			@Override
			protected PasswordAuthentication getPasswordAuthentication()
			{
				if (proxy == null || proxy.getUserInfo() == null || getRequestorType() != RequestorType.PROXY ||
					!proxy.getHost().equalsIgnoreCase(getRequestingHost()) || getRequestingPort() != port())
				{
					return null;
				}
				String[] login = proxy.getRawUserInfo().split(":", 2);
				String user = URI.create("http://" + login[0] + "@proxy.invalid").getUserInfo();
				String password = "";
				if (login.length == 2)
					password = URI.create("http://" + login[1] + "@proxy.invalid").getUserInfo();
				return new PasswordAuthentication(user, password.toCharArray());
			}
		};
	}

	/**
	 * Returns the configured proxy's port, defaulting to HTTP's port 80.
	 *
	 * @return the port
	 */
	private int port()
	{
		if (proxy.getPort() == -1)
			return 80;
		return proxy.getPort();
	}

	/**
	 * Indicates whether the no-proxy list names this host, a parent domain, or every host.
	 *
	 * @param uri request location
	 * @return {@code true} if the request connects directly
	 */
	private boolean bypassesProxy(URI uri)
	{
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		for (String entry : excluded.split(","))
		{
			entry = entry.strip().toLowerCase(Locale.ROOT);
			if (entry.equals("*"))
				return true;
			if (entry.startsWith("."))
				entry = entry.substring(1);
			if (!entry.isEmpty() && (host.equals(entry) || host.endsWith("." + entry)))
				return true;
		}
		return false;
	}
}
