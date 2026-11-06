package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.HttpsReleaseSource;
import com.holdmyspot.cat.common.installer.secrets.HttpsSourceAccess;
import com.holdmyspot.cat.common.installer.secrets.HttpsSourceAccess.Fetch;
import com.holdmyspot.cat.common.installer.secrets.HttpsSourceSecrets;
import com.holdmyspot.cat.common.installer.trust.ManifestVerifier;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow.Subscription;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link HttpsReleaseSource}'s decisions: the request it sends for a release, what it makes of a response's
 * status and {@code Location}, how it bounds a response's size, and how it writes a download. Each test gives the
 * decision the values that the HTTP transport would deliver, so no request is sent.
 */
public final class HttpsReleaseSourceTest
{
	private static final URI BASE_URL = URI.create("https://releases.invalid/project/releases/download");
	private static final Duration REQUEST_LIMIT = Duration.ofSeconds(2);
	private static final HttpsSourceAccess ACCESS = HttpsSourceSecrets.getHttpsSourceAccess();

	/**
	 * An HTTP base URL is refused during construction, before a transport request can be made.
	 *
	 * @throws IOException if proxy parsing fails unexpectedly
	 * @throws TrustException the expected scheme refusal
	 */
	@Test(expectedExceptions = TrustException.class)
	public void refusesHttpBase() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = new HttpsReleaseSource(URI.create("http://releases.invalid/download"),
			Map.of(), Duration.ofSeconds(1), REQUEST_LIMIT))
		{
			source.manifest("v1.0");
		}
	}

	/**
	 * A redirect to HTTP is refused, so the next request is never sent.
	 *
	 * @throws IOException    if the redirect is unexpectedly rejected as malformed
	 * @throws TrustException the expected redirect refusal
	 */
	@Test(expectedExceptions = TrustException.class)
	public void refusesHttpRedirect() throws IOException, TrustException
	{
		ACCESS.next(manifestLocation(), 302, Optional.of("http://assets.invalid/archive"));
	}

	/**
	 * A redirect to an HTTPS location is followed to that location, whose query is kept.
	 *
	 * @throws IOException    if the redirect is unexpectedly rejected as malformed
	 * @throws TrustException if an HTTPS location is unexpectedly refused
	 */
	@Test
	public void followsHttpsRedirect() throws IOException, TrustException
	{
		Optional<URI> next = ACCESS.next(manifestLocation(), 302, Optional.of("https://assets.invalid/release?token=one"));

		requireThat(next, "next").isEqualTo(Optional.of(URI.create("https://assets.invalid/release?token=one")));
	}

	/**
	 * A relative redirect is resolved against the location that was requested.
	 *
	 * @throws IOException    if the redirect is unexpectedly rejected as malformed
	 * @throws TrustException if an HTTPS location is unexpectedly refused
	 */
	@Test
	public void resolvesRelativeRedirect() throws IOException, TrustException
	{
		Optional<URI> next = ACCESS.next(manifestLocation(), 307, Optional.of("/other/archive"));

		requireThat(next, "next").isEqualTo(Optional.of(URI.create("https://releases.invalid/other/archive")));
	}

	/**
	 * A redirect that names no {@code Location} is a failed download.
	 *
	 * @throws IOException    the expected failure
	 * @throws TrustException if the redirect is unexpectedly refused as non-HTTPS
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsRedirectWithoutLocation() throws IOException, TrustException
	{
		ACCESS.next(manifestLocation(), 302, Optional.empty());
	}

	/**
	 * A successful response is the answer: no further request follows it.
	 *
	 * @throws IOException    if the status is unexpectedly rejected
	 * @throws TrustException if the response is unexpectedly refused
	 */
	@Test
	public void acceptsSuccessfulResponse() throws IOException, TrustException
	{
		requireThat(ACCESS.next(manifestLocation(), 200, Optional.empty()), "next").isEqualTo(Optional.empty());
	}

	/**
	 * An unsuccessful HTTP status is reported as a download failure that names the status.
	 *
	 * @throws IOException    the expected HTTP status failure
	 * @throws TrustException if the response is unexpectedly refused
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*HTTP status 503.*")
	public void reportsHttpFailure() throws IOException, TrustException
	{
		ACCESS.next(manifestLocation(), 503, Optional.empty());
	}

	/**
	 * The manifest is requested from the release's own download directory, and its size is capped at the trust
	 * verifier's manifest-size cap.
	 *
	 * @throws IOException    if the source cannot be created
	 * @throws TrustException if the controlled location is refused
	 */
	@Test
	public void requestsManifestFromTagDirectory() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			Fetch fetch = ACCESS.manifest(source, "v1.0");

			requireThat(fetch, "fetch").isEqualTo(new Fetch(manifestLocation(), ManifestVerifier.MAXIMUM_MANIFEST_SIZE));
		}
	}

	/**
	 * The signature is requested next to the manifest, and its size is capped at the trust verifier's signature-size
	 * cap.
	 *
	 * @throws IOException    if the source cannot be created
	 * @throws TrustException if the controlled location is refused
	 */
	@Test
	public void requestsSignatureNextToManifest() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			Fetch fetch = ACCESS.signature(source, "v1.0");

			requireThat(fetch, "fetch").isEqualTo(new Fetch(URI.create(BASE_URL + "/v1.0/cat-release-manifest.json.sig"),
				ManifestVerifier.MAXIMUM_SIGNATURE_SIZE));
		}
	}

	/**
	 * An archive is requested from the release's download directory, and its size is capped at the size that the
	 * signed manifest states.
	 *
	 * @throws IOException    if the source cannot be created
	 * @throws TrustException if the controlled location is refused
	 */
	@Test
	public void capsArchiveAtSignedSize() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			Fetch fetch = ACCESS.asset(source, "v1.0", "archive.tar.gz", 3);

			requireThat(fetch, "fetch").isEqualTo(new Fetch(URI.create(BASE_URL + "/v1.0/archive.tar.gz"), 3));
		}
	}

	/**
	 * Supplies asset names that are more than a single filename.
	 *
	 * @return the names
	 */
	@DataProvider
	public Object[][] pathAssetNames()
	{
		return new Object[][]
		{
			{"nested/archive.tar.gz"},
			{"nested\\archive.tar.gz"},
			{"."},
			{".."}
		};
	}

	/**
	 * An archive name that reaches outside the release's download directory is refused.
	 *
	 * @param name the asset name
	 * @throws IOException    if the source cannot be created
	 * @throws TrustException the expected refusal
	 */
	@Test(dataProvider = "pathAssetNames", expectedExceptions = TrustException.class)
	public void refusesAssetPath(String name) throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			ACCESS.asset(source, "v1.0", name, 3);
		}
	}

	/**
	 * The configured request limit reaches every request, which is a GET of the requested location.
	 *
	 * @throws IOException    if the source cannot be created
	 * @throws TrustException if the controlled location is refused
	 */
	@Test
	public void appliesRequestLimit() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			URI location = URI.create("https://assets.invalid/release?token=one");

			HttpRequest request = ACCESS.request(source, location);

			requireThat(request.uri(), "uri").isEqualTo(location);
			requireThat(request.method(), "method").isEqualTo("GET");
			requireThat(request.timeout().orElseThrow(), "timeout").isEqualTo(REQUEST_LIMIT);
		}
	}

	/**
	 * A request to a location that is not HTTPS is refused.
	 *
	 * @throws IOException    if the source cannot be created
	 * @throws TrustException the expected refusal
	 */
	@Test(expectedExceptions = TrustException.class)
	public void refusesRequestToHttp() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			ACCESS.request(source, URI.create("http://assets.invalid/release"));
		}
	}

	/**
	 * Latest resolution asks the release host's {@code latest} location and chooses the canonical tag from the
	 * redirect it answers with, without fetching the redirect's body.
	 *
	 * @throws IOException    if the source cannot be created or the redirect is unexpectedly rejected
	 * @throws TrustException if a valid tag is unexpectedly refused
	 */
	@Test
	public void resolvesLatestTag() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			requireThat(ACCESS.latestLocation(source), "latestLocation").isEqualTo(
				URI.create("https://releases.invalid/project/releases/latest"));
			requireThat(ACCESS.latestTag(source, 302, Optional.of("/project/releases/tag/v1.2.3")), "tag").
				isEqualTo("v1.2.3");
		}
	}

	/**
	 * Supplies equivalent noncanonical and malformed tag spellings, plus a path outside the release-tag directory.
	 *
	 * @return rejected Location paths
	 */
	@DataProvider
	public Object[][] invalidLocations()
	{
		return new Object[][]
		{
			{"/project/releases/tag/v01.0"},
			{"/project/releases/tag/v%31.0"},
			{"/project/releases/tag/v1.x"},
			{"/project/releases/tag/v1.0/extra"},
			{"/another/releases/tag/v1.0"}
		};
	}

	/**
	 * Latest resolution refuses a tag that is not canonical or is not in this release's tag directory.
	 *
	 * @param location Location path from the release host
	 * @throws IOException    if the source cannot be created or the redirect is unexpectedly rejected
	 * @throws TrustException the expected tag refusal
	 */
	@Test(dataProvider = "invalidLocations", expectedExceptions = TrustException.class)
	public void refusesMalformedLatest(String location) throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			ACCESS.latestTag(source, 302, Optional.of(location));
		}
	}

	/**
	 * A response to the latest-release request that is not a redirect names no tag, so it is a failed request.
	 *
	 * @throws IOException    the expected failure
	 * @throws TrustException if the response is unexpectedly refused
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsLatestWithoutRedirect() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			ACCESS.latestTag(source, 200, Optional.empty());
		}
	}

	/**
	 * A caller-supplied noncanonical tag is refused, so no request is built for it.
	 *
	 * @throws IOException    if the source cannot be created
	 * @throws TrustException the expected tag refusal
	 */
	@Test(expectedExceptions = TrustException.class)
	public void refusesNoncanonicalRequestedTag() throws IOException, TrustException
	{
		try (HttpsReleaseSource source = source())
		{
			ACCESS.manifest(source, "v01.0");
		}
	}

	/**
	 * Supplies the size caps of the metadata documents.
	 *
	 * @return the caps
	 */
	@DataProvider
	public Object[][] metadataCaps()
	{
		return new Object[][] {{ManifestVerifier.MAXIMUM_SIGNATURE_SIZE}, {ManifestVerifier.MAXIMUM_MANIFEST_SIZE}};
	}

	/**
	 * A response that exceeds the cap fails the transfer and stops it, whether it arrives in one chunk or after earlier
	 * chunks that fit.
	 *
	 * @param cap the largest response that is accepted
	 * @throws InterruptedException if the wait for the transfer's result is interrupted
	 */
	@Test(dataProvider = "metadataCaps")
	public void failsResponseOverCap(int cap) throws InterruptedException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		BodySubscriber<Long> subscriber = ACCESS.subscriber(output, cap);
		CountingSubscription subscription = new CountingSubscription();
		subscriber.onSubscribe(subscription);

		subscriber.onNext(List.of(ByteBuffer.wrap(new byte[cap])));
		subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));

		CompletableFuture<Long> body = subscriber.getBody().toCompletableFuture();
		requireThat(body.isCompletedExceptionally(), "failed").isTrue();
		try
		{
			body.get();
		}
		catch (ExecutionException e)
		{
			requireThat(e.getCause(), "cause").isInstanceOf(IOException.class);
		}
		requireThat(subscription.cancelled(), "cancelled").isTrue();
		requireThat(output.size(), "written").isEqualTo(cap);
	}

	/**
	 * A response of exactly the cap is copied whole, however it is split into chunks.
	 *
	 * @throws InterruptedException if the wait for the transfer's result is interrupted
	 * @throws ExecutionException   if the transfer unexpectedly fails
	 */
	@Test
	public void copiesResponseUpToCap() throws InterruptedException, ExecutionException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		BodySubscriber<Long> subscriber = ACCESS.subscriber(output, 5);
		subscriber.onSubscribe(new CountingSubscription());

		subscriber.onNext(List.of(ByteBuffer.wrap("ab".getBytes(UTF_8)), ByteBuffer.wrap("c".getBytes(UTF_8))));
		subscriber.onNext(List.of(ByteBuffer.wrap("de".getBytes(UTF_8))));
		subscriber.onComplete();

		requireThat(subscriber.getBody().toCompletableFuture().get(), "count").isEqualTo(5L);
		requireThat(output.toString(UTF_8), "copied").isEqualTo("abcde");
	}

	/**
	 * Supplies the number of bytes that a transfer delivers although the signed size is three bytes.
	 *
	 * @return invalid archive lengths
	 */
	@DataProvider
	public Object[][] wrongLengths()
	{
		return new Object[][] {{2}, {4}};
	}

	/**
	 * A download whose length differs from the signed size removes the file it created.
	 *
	 * @param size downloaded byte count
	 * @throws IOException    the expected length failure
	 * @throws TrustException if the transfer is unexpectedly refused
	 */
	@Test(dataProvider = "wrongLengths", expectedExceptions = IOException.class)
	public void removesWrongSizeDownload(int size) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path destination = cleanup.add(testDirectory("https-download-")).resolve("archive.tar.gz");
			try
			{
				ACCESS.receive(destination, 3, output ->
				{
					output.write(new byte[size]);
					return size;
				});
			}
			finally
			{
				requireThat(Files.exists(destination), "destinationExists").isFalse();
			}
		}
	}

	/**
	 * A transfer that fails after it began writing leaves no file behind.
	 *
	 * @throws IOException    the expected transfer failure
	 * @throws TrustException if the transfer is unexpectedly refused
	 */
	@Test(expectedExceptions = IOException.class)
	public void removesFailedDownload() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path destination = cleanup.add(testDirectory("https-download-")).resolve("archive.tar.gz");
			try
			{
				ACCESS.receive(destination, 3, output ->
				{
					output.write(new byte[2]);
					throw new IOException("The connection was lost");
				});
			}
			finally
			{
				requireThat(Files.exists(destination), "destinationExists").isFalse();
			}
		}
	}

	/**
	 * A download whose transfer refuses its location leaves no file behind.
	 *
	 * @throws IOException    if the transfer is unexpectedly rejected
	 * @throws TrustException the expected refusal
	 */
	@Test(expectedExceptions = TrustException.class)
	public void removesRefusedDownload() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path destination = cleanup.add(testDirectory("https-download-")).resolve("archive.tar.gz");
			try
			{
				ACCESS.receive(destination, 3, _ ->
				{
					throw new TrustException(TrustException.Reason.MALFORMED_POLICY, "The redirect leaves HTTPS");
				});
			}
			finally
			{
				requireThat(Files.exists(destination), "destinationExists").isFalse();
			}
		}
	}

	/**
	 * An exact-size archive download writes its bytes to the new destination.
	 *
	 * @throws IOException    if the download or fixture cleanup fails
	 * @throws TrustException if the transfer is unexpectedly refused
	 */
	@Test
	public void writesExactSizeDownload() throws IOException, TrustException
	{
		byte[] bytes = "archive".getBytes(UTF_8);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path destination = cleanup.add(testDirectory("https-download-")).resolve("archive.tar.gz");

			ACCESS.receive(destination, bytes.length, output ->
			{
				output.write(bytes);
				return bytes.length;
			});

			requireThat(Files.readAllBytes(destination), "archive").isEqualTo(bytes);
		}
	}

	/**
	 * A download never replaces a file that exists.
	 *
	 * @throws IOException    the expected refusal to overwrite
	 * @throws TrustException if the transfer is unexpectedly refused
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesExistingDestination() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path destination = Files.writeString(cleanup.add(testDirectory("https-download-")).resolve("archive.tar.gz"),
				"kept");
			try
			{
				ACCESS.receive(destination, 1, output ->
				{
					output.write(1);
					return 1;
				});
			}
			finally
			{
				requireThat(Files.readString(destination), "content").isEqualTo("kept");
			}
		}
	}

	/**
	 * Returns the location of the manifest of release v1.0.
	 *
	 * @return the location
	 */
	private static URI manifestLocation()
	{
		return URI.create(BASE_URL + "/v1.0/cat-release-manifest.json");
	}

	/**
	 * Creates a source for the test location. It sends no request, so it never opens a transport.
	 *
	 * @return release source
	 * @throws IOException    if the proxy settings are invalid
	 * @throws TrustException if the fixed HTTPS test location is refused
	 */
	private static HttpsReleaseSource source() throws IOException, TrustException
	{
		return new HttpsReleaseSource(BASE_URL, Map.of(), Duration.ofSeconds(1), REQUEST_LIMIT);
	}

	/**
	 * The transport's side of a response body: it grants the subscriber's demand and records a cancellation.
	 */
	private static final class CountingSubscription implements Subscription
	{
		private boolean cancelled;

		@Override
		public void request(long count)
		{
		}

		@Override
		public void cancel()
		{
			cancelled = true;
		}

		/**
		 * Indicates whether the subscriber cancelled the body.
		 *
		 * @return {@code true} after a cancellation
		 */
		boolean cancelled()
		{
			return cancelled;
		}
	}
}
