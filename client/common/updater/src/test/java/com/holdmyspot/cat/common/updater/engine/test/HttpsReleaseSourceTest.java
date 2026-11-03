package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.engine.HttpsReleaseSource;
import com.holdmyspot.cat.common.updater.secrets.HttpsSourceSecrets;
import com.holdmyspot.cat.common.updater.trust.ManifestVerifier;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link HttpsReleaseSource}'s URL, redirect, size and cleanup decisions with an in-memory HTTP transport.
 */
public final class HttpsReleaseSourceTest
{
	private static final URI BASE_URL = URI.create("https://releases.invalid/project/releases/download");
	private static final Duration REQUEST_LIMIT = Duration.ofSeconds(2);

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
	 * A redirect to HTTP is refused before the next request is sent.
	 *
	 * @throws IOException if the controlled request fails
	 * @throws TrustException the expected redirect refusal
	 */
	@Test(expectedExceptions = TrustException.class)
	public void refusesHttpRedirect() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(302, "http://assets.invalid/archive", new byte[0]);
			try (HttpsReleaseSource source = source(client))
			{
				try
				{
					source.manifest("v1.0");
				}
				finally
				{
					requireThat(client.requests().size(), "requestCount").isEqualTo(1);
				}
			}
		}
	}

	/**
	 * An HTTPS redirect is followed, metadata retains its bytes, and the configured request limit reaches each request.
	 *
	 * @throws IOException if the controlled download fails
	 * @throws TrustException if an HTTPS location is unexpectedly refused
	 */
	@Test
	public void followsHttpsRedirect() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(302, "https://assets.invalid/release?token=one", new byte[0]);
			client.reply(200, "", "manifest content".getBytes(UTF_8));
			try (HttpsReleaseSource source = source(client))
			{
				requireThat(source.manifest("v1.0"), "manifest").isEqualTo("manifest content".getBytes(UTF_8));
				requireThat(client.requests().getFirst().uri(), "firstRequest").isEqualTo(
					URI.create(BASE_URL + "/v1.0/cat-release-manifest.json"));
				requireThat(client.requests().getLast().uri(), "redirectRequest").isEqualTo(
					URI.create("https://assets.invalid/release?token=one"));
				requireThat(client.requests().getLast().timeout().orElseThrow(), "timeout").isEqualTo(REQUEST_LIMIT);
			}
		}
	}

	/**
	 * Closing a source that borrows its transport keeps that transport open for its owning caller.
	 *
	 * @throws IOException if the controlled request fails
	 * @throws TrustException if the fixed HTTPS location is unexpectedly refused
	 */
	@Test
	public void keepsBorrowedClientOpen() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(200, "", new byte[0]);
			try (HttpsReleaseSource source = source(client))
			{
				requireThat(client.closed(), "clientClosedBeforeSource").isFalse();
				requireThat(source.manifest("v1.0"), "manifest").isEmpty();
			}
			requireThat(client.closed(), "borrowedClientClosed").isFalse();
		}
	}

	/**
	 * Latest resolution uses the first Location to choose a canonical tag without fetching that redirect's body.
	 *
	 * @throws IOException if the controlled request fails
	 * @throws TrustException if a valid tag is unexpectedly refused
	 */
	@Test
	public void resolvesLatestTag() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(302, "/project/releases/tag/v1.2.3", new byte[0]);
			try (HttpsReleaseSource source = source(client))
			{
				requireThat(source.latestTag(), "tag").isEqualTo("v1.2.3");
				requireThat(client.requests().size(), "requestCount").isEqualTo(1);
				requireThat(client.requests().getFirst().uri(), "latestRequest").isEqualTo(
					URI.create("https://releases.invalid/project/releases/latest"));
			}
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
	 * @throws IOException if the controlled request fails
	 * @throws TrustException the expected tag refusal
	 */
	@Test(dataProvider = "invalidLocations", expectedExceptions = TrustException.class)
	public void refusesMalformedLatest(String location) throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(302, location, new byte[0]);
			try (HttpsReleaseSource source = source(client))
			{
				source.latestTag();
			}
		}
	}

	/**
	 * A caller-supplied noncanonical tag is refused without sending a download request.
	 *
	 * @throws IOException if transport is unexpectedly reached
	 * @throws TrustException the expected tag refusal
	 */
	@Test(expectedExceptions = TrustException.class)
	public void refusesNoncanonicalRequestedTag() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			try (HttpsReleaseSource source = source(client))
			{
				try
				{
					source.manifest("v01.0");
				}
				finally
				{
					requireThat(client.requests(), "requests").isEmpty();
				}
			}
		}
	}

	/**
	 * Signature responses cannot exceed the trust verifier's signature-size cap.
	 *
	 * @throws IOException the expected oversized response failure
	 * @throws TrustException if the controlled location is refused
	 */
	@Test(expectedExceptions = IOException.class)
	public void capsSignature() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(200, "", new byte[ManifestVerifier.MAXIMUM_SIGNATURE_SIZE + 1]);
			try (HttpsReleaseSource source = source(client))
			{
				source.signature("v1.0");
			}
		}
	}

	/**
	 * Manifest responses cannot exceed the trust verifier's manifest-size cap.
	 *
	 * @throws IOException the expected oversized response failure
	 * @throws TrustException if the controlled location is refused
	 */
	@Test(expectedExceptions = IOException.class)
	public void capsManifest() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(200, "", new byte[ManifestVerifier.MAXIMUM_MANIFEST_SIZE + 1]);
			try (HttpsReleaseSource source = source(client))
			{
				source.manifest("v1.0");
			}
		}
	}

	/**
	 * Supplies truncated and oversized archive bodies for a signed size of three bytes.
	 *
	 * @return invalid archive lengths
	 */
	@DataProvider
	public Object[][] wrongLengths()
	{
		return new Object[][] {{2}, {4}};
	}

	/**
	 * A failed exact-size download removes the file it created.
	 *
	 * @param size downloaded byte count
	 * @throws IOException the expected length failure
	 * @throws TrustException if the controlled location is refused
	 */
	@Test(dataProvider = "wrongLengths", expectedExceptions = IOException.class)
	public void removesWrongSizeDownload(int size) throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(200, "", new byte[size]);
			try (TemporaryDirectories cleanup = new TemporaryDirectories();
				HttpsReleaseSource source = source(client))
			{
				Path directory = cleanup.add(testDirectory("https-download-"));
				Path destination = directory.resolve("archive.tar.gz");
				try
				{
					source.download("v1.0", "archive.tar.gz", 3, destination);
				}
				finally
				{
					requireThat(Files.exists(destination), "destinationExists").isFalse();
				}
			}
		}
	}

	/**
	 * An exact-size archive download writes its bytes to the new destination.
	 *
	 * @throws IOException if the controlled download or fixture cleanup fails
	 * @throws TrustException if the controlled location is refused
	 */
	@Test
	public void writesExactSizeDownload() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			byte[] bytes = "archive".getBytes(UTF_8);
			client.reply(200, "", bytes);
			try (TemporaryDirectories cleanup = new TemporaryDirectories();
				HttpsReleaseSource source = source(client))
			{
				Path directory = cleanup.add(testDirectory("https-download-"));
				Path destination = directory.resolve("archive.tar.gz");
				source.download("v1.0", "archive.tar.gz", bytes.length, destination);
				requireThat(Files.readAllBytes(destination), "archive").isEqualTo(bytes);
			}
		}
	}

	/**
	 * An unsuccessful HTTP status is reported as a download failure.
	 *
	 * @throws IOException the expected HTTP status failure
	 * @throws TrustException if the controlled location is refused
	 */
	@Test(expectedExceptions = IOException.class)
	public void reportsHttpFailure() throws IOException, TrustException
	{
		try (FakeHttpsClient client = new FakeHttpsClient())
		{
			client.reply(503, "", new byte[0]);
			try (HttpsReleaseSource source = source(client))
			{
				source.manifest("v1.0");
			}
		}
	}

	/**
	 * Creates a source whose transport is owned by this test.
	 *
	 * @param client controlled HTTP responses
	 * @return release source
	 * @throws TrustException if the fixed HTTPS test location is refused
	 */
	private static HttpsReleaseSource source(FakeHttpsClient client) throws TrustException
	{
		return HttpsSourceSecrets.getHttpsSourceAccess().create(BASE_URL, client, REQUEST_LIMIT);
	}
}
