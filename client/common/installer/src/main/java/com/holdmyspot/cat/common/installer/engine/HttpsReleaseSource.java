package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.secrets.HttpsSourceAccess;
import com.holdmyspot.cat.common.installer.secrets.HttpsSourceAccess.Fetch;
import com.holdmyspot.cat.common.installer.secrets.HttpsSourceAccess.Transfer;
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
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Downloads release metadata and archives from a policy's fixed HTTPS location. Every redirect is checked before a
 * request is sent. Downloaded bytes remain untrusted until the caller verifies their signatures and digests.
 */
public final class HttpsReleaseSource implements ReleaseConnection
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
		HttpsSourceSecrets.setHttpsSourceAccess(new HttpsSourceAccess()
		{
			@Override
			public URI latestLocation(HttpsReleaseSource source)
			{
				return source.latestLocation();
			}

			@Override
			public String latestTag(HttpsReleaseSource source, int status, Optional<String> redirectLocation)
				throws IOException, TrustException
			{
				return source.latestTag(status, redirectLocation);
			}

			@Override
			public Fetch manifest(HttpsReleaseSource source, String tag) throws TrustException
			{
				return source.manifestFetch(tag);
			}

			@Override
			public Fetch signature(HttpsReleaseSource source, String tag) throws TrustException
			{
				return source.signatureFetch(tag);
			}

			@Override
			public Fetch asset(HttpsReleaseSource source, String tag, String assetName, long expectedSize)
				throws TrustException
			{
				return source.assetFetch(tag, assetName, expectedSize);
			}

			@Override
			public HttpRequest request(HttpsReleaseSource source, URI location) throws TrustException
			{
				return source.request(location);
			}

			@Override
			public Optional<URI> next(URI location, int status, Optional<String> redirectLocation)
				throws IOException, TrustException
			{
				return HttpsReleaseSource.next(location, status, redirectLocation);
			}

			@Override
			public BodySubscriber<Long> subscriber(OutputStream output, long maximum)
			{
				return new HttpsBodySubscriber(output, maximum);
			}

			@Override
			public void receive(Path destination, long expectedSize, Transfer transfer) throws IOException,
				TrustException
			{
				HttpsReleaseSource.receive(destination, expectedSize, transfer);
			}
		});
	}

	private final URI baseUrl;
	/**
	 * Builds the HTTP client on its first request.
	 */
	private final HttpClient.Builder clientBuilder;
	private final Duration requestLimit;
	/**
	 * The HTTP client, or {@code null} until the first request.
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
		this.clientBuilder = HttpClient.newBuilder().connectTimeout(connectLimit).
			followRedirects(HttpClient.Redirect.NEVER).proxy(proxy).authenticator(proxy.authenticator());
	}

	@Override
	public String latestTag() throws IOException, TrustException
	{
		URI latest = latestLocation();
		HttpResponse<Long> response = send(latest, info -> BodySubscribers.replacing(0L));
		return latestTag(response.statusCode(), response.headers().firstValue("Location"));
	}

	@Override
	public byte[] manifest(String tag) throws IOException, TrustException
	{
		return metadata(manifestFetch(tag));
	}

	@Override
	public byte[] signature(String tag) throws IOException, TrustException
	{
		return metadata(signatureFetch(tag));
	}

	@Override
	public void download(String tag, String assetName, long expectedSize, Path destination) throws IOException,
		TrustException
	{
		requireThat(destination, "destination").isNotNull();
		Fetch fetch = assetFetch(tag, assetName, expectedSize);
		receive(destination, expectedSize, output -> transfer(fetch.location(), output, fetch.maximum()));
	}

	@Override
	public void close()
	{
		synchronized (clientLock)
		{
			if (client != null)
				client.close();
		}
	}

	/**
	 * Returns the HTTP client, creating it on its first use.
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
	 * Returns the location that redirects to the latest release's tag.
	 *
	 * @return the location
	 */
	private URI latestLocation()
	{
		return baseUrl.resolve("latest");
	}

	/**
	 * Reads the latest release's tag from the response to the request for {@link #latestLocation()}. The response's
	 * body is never fetched: the first redirect decides the tag.
	 *
	 * @param status           the response's HTTP status code
	 * @param redirectLocation the response's {@code Location} header, if it has one
	 * @return the canonical tag
	 * @throws IOException    if the response is not a redirect, or a redirect without a {@code Location} or with one that
	 *                        is not a URI
	 * @throws TrustException if the redirect leaves HTTPS, does not name a tag in this source's tag directory, or names a
	 *                        tag that is not in its canonical spelling
	 */
	private String latestTag(int status, Optional<String> redirectLocation) throws IOException, TrustException
	{
		URI latest = latestLocation();
		if (!redirect(status))
			throw new IOException("The latest-release request did not return a redirect Location");
		URI target = redirectTarget(latest, redirectLocation);
		String path = target.getRawPath();
		String prefix = latest.resolve("tag/").getRawPath();
		if (!path.startsWith(prefix))
			throw new TrustException(Reason.MALFORMED_VERSION, "The latest release Location must name a releases/tag path");
		return canonicalTag(path.substring(prefix.length()));
	}

	/**
	 * Returns the request for a release's manifest.
	 *
	 * @param tag the release tag
	 * @return the manifest's location and its maximum size
	 * @throws TrustException if the tag is not canonical
	 */
	private Fetch manifestFetch(String tag) throws TrustException
	{
		return new Fetch(assetLocation(tag, MANIFEST_NAME), ManifestVerifier.MAXIMUM_MANIFEST_SIZE);
	}

	/**
	 * Returns the request for a release's manifest signature.
	 *
	 * @param tag the release tag
	 * @return the signature's location and its maximum size
	 * @throws TrustException if the tag is not canonical
	 */
	private Fetch signatureFetch(String tag) throws TrustException
	{
		return new Fetch(assetLocation(tag, MANIFEST_NAME + ".sig"), ManifestVerifier.MAXIMUM_SIGNATURE_SIZE);
	}

	/**
	 * Returns the request for a release's archive.
	 *
	 * @param tag          the release tag
	 * @param assetName    a single archive filename
	 * @param expectedSize the archive's size, which is also its maximum
	 * @return the archive's location and its maximum size
	 * @throws NullPointerException     if {@code assetName} is null
	 * @throws IllegalArgumentException if {@code assetName} is blank or {@code expectedSize} is negative
	 * @throws TrustException           if the tag is not canonical or {@code assetName} names more than a file
	 */
	private Fetch assetFetch(String tag, String assetName, long expectedSize) throws TrustException
	{
		requireThat(assetName, "assetName").isNotBlank();
		requireThat(expectedSize, "expectedSize").isGreaterThanOrEqualTo(0L);
		if (assetName.contains("/") || assetName.contains("\\") || assetName.equals(".") || assetName.equals(".."))
			throw new TrustException(Reason.MALFORMED_MANIFEST, "A release asset must be a single archive filename");
		return new Fetch(assetLocation(tag, assetName), expectedSize);
	}

	/**
	 * Reads one bounded metadata document.
	 *
	 * @param fetch the document's location and maximum size
	 * @return document bytes
	 * @throws IOException if the request fails or exceeds its size limit
	 * @throws TrustException if a URL is refused
	 */
	private byte[] metadata(Fetch fetch) throws IOException, TrustException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		transfer(fetch.location(), output, fetch.maximum());
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
	 * Writes a new file with the bytes that a transfer delivers, deleting the file on any failure.
	 *
	 * @param destination  the absent file to create
	 * @param expectedSize the exact byte count that the transfer must deliver
	 * @param transfer     delivers the bytes into the file
	 * @throws IOException    if writing fails, the transfer fails or it delivers another number of bytes
	 * @throws TrustException if the transfer refuses its location
	 */
	private static void receive(Path destination, long expectedSize, Transfer transfer) throws IOException,
		TrustException
	{
		boolean created = false;
		try
		{
			try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE))
			{
				created = true;
				long count = transfer.copyTo(output);
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
		URI current = location;
		for (int redirects = 0; redirects <= MAXIMUM_REDIRECTS; ++redirects)
		{
			HttpResponse<Long> response = send(current, info ->
			{
				if (success(info.statusCode()))
					return new HttpsBodySubscriber(output, maximum);
				return BodySubscribers.replacing(0L);
			});
			Optional<URI> next = next(current, response.statusCode(), response.headers().firstValue("Location"));
			if (next.isEmpty())
				return response.body();
			current = next.get();
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
		HttpRequest request = request(location);
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
	 * Returns the request that fetches a location.
	 *
	 * @param location request URL
	 * @return a GET request that times out after the request limit
	 * @throws TrustException if the URL is not an absolute HTTPS URL with a host and without login or fragment
	 */
	private HttpRequest request(URI location) throws TrustException
	{
		requireHttps(location);
		return HttpRequest.newBuilder(location).timeout(requestLimit).GET().build();
	}

	/**
	 * Decides what follows a response to a request for a download.
	 *
	 * @param location         the URL that was requested
	 * @param status           the response's HTTP status code
	 * @param redirectLocation the response's {@code Location} header, if it has one
	 * @return the HTTPS URL to request next, or empty when the response is the answer and its body is the download
	 * @throws IOException    if the response is neither a success nor a redirect, or is a redirect without a
	 *                        {@code Location} or with one that is not a URI
	 * @throws TrustException if the redirect leaves HTTPS
	 */
	private static Optional<URI> next(URI location, int status, Optional<String> redirectLocation) throws IOException,
		TrustException
	{
		if (redirect(status))
			return Optional.of(redirectTarget(location, redirectLocation));
		if (!success(status))
			throw new IOException("The release download returned HTTP status " + status);
		return Optional.empty();
	}

	/**
	 * Resolves and validates a redirect before its destination can be requested.
	 *
	 * @param location         current request URL
	 * @param redirectLocation the redirect's {@code Location} header, if it has one
	 * @return HTTPS destination
	 * @throws IOException if the Location is missing or malformed
	 * @throws TrustException if its destination is refused
	 */
	private static URI redirectTarget(URI location, Optional<String> redirectLocation) throws IOException,
		TrustException
	{
		String value = redirectLocation.orElseThrow(() -> new IOException("The release redirect has no Location header"));
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
	 * Indicates whether a status code reports a successful response.
	 *
	 * @param status HTTP status code
	 * @return {@code true} for 200 to 299
	 */
	private static boolean success(int status)
	{
		return status >= 200 && status < 300;
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
