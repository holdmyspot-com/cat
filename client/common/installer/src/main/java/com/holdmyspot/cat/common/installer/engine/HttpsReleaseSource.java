package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.secrets.HttpsSourceSecrets;
import com.holdmyspot.cat.common.installer.trust.ManifestVerifier;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;

/**
 * Downloads release metadata and archives from a policy's fixed HTTPS location. Every redirect is checked before a
 * request is sent. Downloaded bytes remain untrusted until the caller verifies their signatures and digests.
 */
public final class HttpsReleaseSource implements ReleaseSource, AutoCloseable
{
	/**
	 * The signed manifest published inside each release's download directory.
	 */
	private static final String MANIFEST_NAME = "cat-release-manifest.json";
	/**
	 * The maximum number of redirects followed for one download; repeated redirects fail as a download error.
	 */
	private static final int MAXIMUM_REDIRECTS = 10;
	static
	{
		HttpsSourceSecrets.setHttpsSourceAccess(HttpsReleaseSource::new);
	}

	private final URI baseUrl;
	/**
	 * Builds the owned HTTP client on its first request, or {@code null} for a borrowed client.
	 */
	private final HttpClient.Builder clientBuilder;
	private final Duration requestLimit;
	private final boolean ownsClient;
	/**
	 * The HTTP client, or {@code null} until the first request of an owned client.
	 */
	private HttpClient client;
	/**
	 * Guards the creation and release of {@link #client}.
	 */
	private final Object clientLock = new Object();

	/**
	 * Creates a release source with its own HTTP client, which {@link #close()} releases. The client is created on the
	 * first request, so an operation that sends none, such as setup from a local plugin tree, does not pay for it.
	 *
	 * @param releaseBaseUrl absolute HTTPS URL below which releases have tag-named directories; no login, query or
	 *                       fragment is allowed
	 * @param environment    HTTPS_PROXY/https_proxy and NO_PROXY/no_proxy values; other variables are ignored
	 * @param connectLimit   positive limit for establishing a connection
	 * @param requestLimit   positive HTTP request timeout
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if either timeout is not positive
	 * @throws TrustException           if the base URL is not an absolute HTTPS location without login, query or fragment
	 * @throws IOException              if the selected proxy setting is invalid
	 */
	public HttpsReleaseSource(URI releaseBaseUrl, Map<String, String> environment, Duration connectLimit,
		Duration requestLimit) throws IOException, TrustException
	{
		requireThat(releaseBaseUrl, "releaseBaseUrl").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(connectLimit, "connectLimit").isGreaterThan(Duration.ZERO);
		requireThat(requestLimit, "requestLimit").isGreaterThan(Duration.ZERO);
		requireHttps(releaseBaseUrl);
		if (releaseBaseUrl.getRawQuery() != null)
			throw new TrustException(Reason.MALFORMED_POLICY, "The release base URL must not contain a query");
		ProxyConfiguration proxy = new ProxyConfiguration(environment);
		this.baseUrl = withoutTrailingSlash(releaseBaseUrl);
		this.requestLimit = requestLimit;
		this.ownsClient = true;
		this.clientBuilder = HttpClient.newBuilder().connectTimeout(connectLimit).
			followRedirects(HttpClient.Redirect.NEVER).proxy(proxy).authenticator(proxy.authenticator());
	}

	/**
	 * Creates a source borrowing an explicitly supplied transport. The transport must return each redirect rather than
	 * follow it, so this source can refuse a non-HTTPS destination before a request is sent.
	 *
	 * @param releaseBaseUrl policy release base URL
	 * @param client         caller-owned HTTP transport
	 * @param requestLimit   positive per-request limit
	 * @throws TrustException if the base URL is refused
	 */
	private HttpsReleaseSource(URI releaseBaseUrl, HttpClient client, Duration requestLimit) throws TrustException
	{
		requireThat(releaseBaseUrl, "releaseBaseUrl").isNotNull();
		requireThat(client, "client").isNotNull();
		requireThat(requestLimit, "requestLimit").isGreaterThan(Duration.ZERO);
		requireThat(client.followRedirects(), "client.followRedirects()").isEqualTo(HttpClient.Redirect.NEVER);
		requireHttps(releaseBaseUrl);
		if (releaseBaseUrl.getRawQuery() != null)
			throw new TrustException(Reason.MALFORMED_POLICY, "The release base URL must not contain a query");
		this.baseUrl = withoutTrailingSlash(releaseBaseUrl);
		this.clientBuilder = null;
		this.client = client;
		this.requestLimit = requestLimit;
		this.ownsClient = false;
	}

	@Override
	public String latestTag() throws IOException, TrustException
	{
		URI latest = baseUrl.resolve("latest");
		HttpResponse<Long> response = send(latest, info -> BodySubscribers.replacing(0L));
		if (!redirect(response.statusCode()))
			throw new IOException("The latest-release request did not return a redirect Location");
		URI target = redirectLocation(latest, response);
		String path = target.getRawPath();
		String prefix = latest.resolve("tag/").getRawPath();
		if (!path.startsWith(prefix))
			throw new TrustException(Reason.MALFORMED_VERSION, "The latest release Location must name a releases/tag path");
		return canonicalTag(path.substring(prefix.length()));
	}

	@Override
	public byte[] manifest(String tag) throws IOException, TrustException
	{
		return metadata(tag, MANIFEST_NAME, ManifestVerifier.MAXIMUM_MANIFEST_SIZE);
	}

	@Override
	public byte[] signature(String tag) throws IOException, TrustException
	{
		return metadata(tag, MANIFEST_NAME + ".sig", ManifestVerifier.MAXIMUM_SIGNATURE_SIZE);
	}

	@Override
	public void download(String tag, String assetName, long expectedSize, Path destination) throws IOException,
		TrustException
	{
		requireThat(assetName, "assetName").isNotBlank();
		requireThat(expectedSize, "expectedSize").isGreaterThanOrEqualTo(0L);
		requireThat(destination, "destination").isNotNull();
		if (assetName.contains("/") || assetName.contains("\\") || assetName.equals(".") || assetName.equals(".."))
			throw new TrustException(Reason.MALFORMED_MANIFEST, "A release asset must be a single archive filename");
		URI location = assetLocation(tag, assetName);
		boolean created = false;
		try
		{
			try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE))
			{
				created = true;
				long count = transfer(location, output, expectedSize);
				if (count != expectedSize)
					throw new IOException("The release download length differs from its signed manifest size");
			}
		}
		catch (IOException | TrustException | RuntimeException failure)
		{
			if (created)
			{
				try
				{
					Files.deleteIfExists(destination);
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

	@Override
	public void close()
	{
		synchronized (clientLock)
		{
			if (ownsClient && client != null)
				client.close();
		}
	}

	/**
	 * Returns the HTTP client, creating an owned client on its first use.
	 *
	 * @return the client
	 */
	private HttpClient client()
	{
		synchronized (clientLock)
		{
			if (client == null)
				client = clientBuilder.build();
			return client;
		}
	}

	/**
	 * Reads one bounded metadata document.
	 *
	 * @param tag     release tag
	 * @param name    metadata filename
	 * @param maximum maximum accepted size in bytes
	 * @return document bytes
	 * @throws IOException if the request fails or exceeds its size limit
	 * @throws TrustException if a tag or URL is refused
	 */
	private byte[] metadata(String tag, String name, int maximum) throws IOException, TrustException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		transfer(assetLocation(tag, name), output, maximum);
		return output.toByteArray();
	}

	/**
	 * Resolves an asset filename within one canonical release's download directory.
	 *
	 * @param tag  canonical release tag
	 * @param name asset filename
	 * @return the asset's URL
	 * @throws TrustException if the tag or filename is invalid
	 */
	private URI assetLocation(String tag, String name) throws TrustException
	{
		canonicalTag(tag);
		try
		{
			return new URI(baseUrl.getScheme(), null, baseUrl.getHost(), baseUrl.getPort(),
				baseUrl.getPath() + "/" + tag + "/" + name, null, null);
		}
		catch (URISyntaxException _)
		{
			throw new TrustException(Reason.MALFORMED_MANIFEST, "The release asset filename is not valid in a URL");
		}
	}

	/**
	 * Copies a successful response through a bounded subscriber, following only checked HTTPS redirects.
	 *
	 * @param location initial URL
	 * @param output   caller-owned destination
	 * @param maximum  response size limit in bytes
	 * @return number of copied bytes
	 * @throws IOException if transport, status or size validation fails
	 * @throws TrustException if any URL is refused
	 */
	private long transfer(URI location, OutputStream output, long maximum) throws IOException, TrustException
	{
		for (int redirects = 0; redirects <= MAXIMUM_REDIRECTS; ++redirects)
		{
			HttpResponse<Long> response = send(location, info ->
			{
				if (info.statusCode() >= 200 && info.statusCode() < 300)
					return new HttpsBodySubscriber(output, maximum);
				return BodySubscribers.replacing(0L);
			});
			if (redirect(response.statusCode()))
			{
				location = redirectLocation(location, response);
				continue;
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300)
				throw new IOException("The release download returned HTTP status " + response.statusCode());
			return response.body();
		}
		throw new IOException("The release download exceeded " + MAXIMUM_REDIRECTS + " redirects");
	}

	/**
	 * Sends one HTTPS request; interruption remains visible on the calling thread.
	 *
	 * @param location request URL
	 * @param handler  response consumer
	 * @return completed response
	 * @throws IOException if sending fails or is interrupted
	 * @throws TrustException if the request URL is refused
	 */
	private HttpResponse<Long> send(URI location, BodyHandler<Long> handler) throws IOException, TrustException
	{
		requireHttps(location);
		HttpRequest request = HttpRequest.newBuilder(location).timeout(requestLimit).GET().build();
		try
		{
			return client().send(request, handler);
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("The release download was interrupted", failure);
		}
	}

	/**
	 * Resolves and validates a redirect before its destination can be requested.
	 *
	 * @param location current request URL
	 * @param response redirect response
	 * @return HTTPS destination
	 * @throws IOException if the Location is missing or malformed
	 * @throws TrustException if its destination is refused
	 */
	private URI redirectLocation(URI location, HttpResponse<?> response) throws IOException, TrustException
	{
		String value = response.headers().firstValue("Location").orElseThrow(() ->
			new IOException("The release redirect has no Location header"));
		URI target;
		try
		{
			target = location.resolve(new URI(value));
		}
		catch (URISyntaxException failure)
		{
			throw new IOException("The release redirect Location is not a valid URI", failure);
		}
		requireHttps(target);
		return target;
	}

	/**
	 * Indicates whether a status code redirects a GET request.
	 *
	 * @param status HTTP status code
	 * @return {@code true} for 301, 302, 303, 307 or 308
	 */
	private static boolean redirect(int status)
	{
		return switch (status)
		{
			case 301, 302, 303, 307, 308 -> true;
			default -> false;
		};
	}

	/**
	 * Parses a tag through the shared version parser and preserves its canonical spelling.
	 *
	 * @param tag release tag
	 * @return canonical tag
	 * @throws TrustException if the tag is malformed or noncanonical
	 */
	private static String canonicalTag(String tag) throws TrustException
	{
		if (tag == null || !tag.startsWith("v"))
			throw new TrustException(Reason.MALFORMED_VERSION, "A release tag must start with v followed by its version");
		String canonical = ReleaseVersion.parse(tag.substring(1)).tag();
		if (!canonical.equals(tag))
			throw new TrustException(Reason.MALFORMED_VERSION, "A release tag must use its canonical version spelling");
		return canonical;
	}

	/**
	 * Refuses URLs that could change the release source's transport or credentials.
	 *
	 * @param location request URL
	 * @throws TrustException if the URL is not an absolute HTTPS URL with a host and without login or fragment
	 */
	private static void requireHttps(URI location) throws TrustException
	{
		if (!"https".equalsIgnoreCase(location.getScheme()) || location.getHost() == null ||
			location.getUserInfo() != null || location.getRawFragment() != null)
		{
			throw new TrustException(Reason.MALFORMED_POLICY,
				"Release requests require an absolute HTTPS URL with a host and without login or fragment");
		}
	}

	/**
	 * Removes trailing slashes so resolving child assets cannot introduce an empty path segment.
	 *
	 * @param location release base URL
	 * @return the base URL without trailing slashes
	 */
	private static URI withoutTrailingSlash(URI location)
	{
		String text = location.toString();
		while (text.endsWith("/"))
			text = text.substring(0, text.length() - 1);
		return URI.create(text);
	}
}
