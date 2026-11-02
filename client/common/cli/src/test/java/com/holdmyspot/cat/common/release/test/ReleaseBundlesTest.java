package com.holdmyspot.cat.common.release.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.release.ReleaseBundles;
import com.holdmyspot.cat.common.release.RunnerArtifact;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease.Entry;
import com.holdmyspot.cat.common.secrets.ReleaseBundlesSecrets;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link ReleaseBundles}: how a harness's CAT artifact is resolved, downloaded, verified and extracted, against a
 * local release directory.
 */
public final class ReleaseBundlesTest
{
	private static final Path MANIFEST = Path.of(".harness-a/plugin.json");
	private static final String HARNESS = "harness-a";
	private static final String RUNNER = "harness-a-runner";
	/**
	 * The time at which the tests that age bundles resolve. Three calendar months earlier is 2026-02-28T12:00:00Z,
	 * which 90 days would place on 2026-03-02.
	 */
	private static final Instant NOW = Instant.parse("2026-05-31T12:00:00Z");

	/**
	 * Creates a test instance.
	 */
	public ReleaseBundlesTest()
	{
	}

	/**
	 * Returns the name of the harness's archive for the current platform, without {@code .tar.gz}.
	 *
	 * @return the name
	 */
	private static String bundleName()
	{
		return "cat-" + HARNESS + "-v" + PluginInfo.CAT_VERSION + "-" + Platform.current().id();
	}

	/**
	 * Returns the runner launcher's path in an artifact built for the current platform.
	 *
	 * @param artifact the artifact
	 * @return the runner's path
	 */
	private static Path runner(Path artifact)
	{
		if (Platform.current().isWindows())
			return artifact.resolve("bin/" + RUNNER + ".cmd");
		return artifact.resolve("bin/" + RUNNER);
	}

	/**
	 * Creates an artifact of the harness for the current platform.
	 *
	 * @param directory the artifact's directory
	 * @param version   the CAT version that it states
	 * @return the artifact
	 * @throws IOException if the artifact cannot be written
	 */
	private static Path artifact(Path directory, String version) throws IOException
	{
		return FakeRelease.artifact(directory, MANIFEST.toString(), RUNNER, version, Platform.current());
	}

	/**
	 * Returns resolution against a release directory.
	 *
	 * @param bundles the bundle directory
	 * @param release the release directory
	 * @return the resolution
	 */
	private static ReleaseBundles bundles(Path bundles, Path release)
	{
		return new ReleaseBundles(bundles, release.toUri(), Platform.current(), Map.of());
	}

	/**
	 * Returns resolution against a release directory, at a fixed time.
	 *
	 * @param bundles the bundle directory
	 * @param release the release directory
	 * @param now     the time of the resolution
	 * @return the resolution
	 */
	private static ReleaseBundles bundles(Path bundles, Path release, Instant now)
	{
		return ReleaseBundlesSecrets.getReleaseBundlesAccess().create(bundles, release.toUri(), Platform.current(),
			Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC));
	}

	/**
	 * Sets the time at which a file or directory was last modified, which tells when a bundle was last used.
	 *
	 * @param path the file or directory
	 * @param time the time
	 * @throws IOException if the time cannot be set
	 */
	private static void lastUsed(Path path, Instant time) throws IOException
	{
		Files.setLastModifiedTime(path, FileTime.from(time));
	}

	/**
	 * Checks for another harness's bundle, which neither exists nor can be downloaded, at a fixed time.
	 *
	 * @param bundlesDirectory the bundle directory
	 * @param root             the test's directory
	 * @param now              the time of the check
	 */
	private static void checkOtherHarness(Path bundlesDirectory, Path root, Instant now)
	{
		try
		{
			bundles(bundlesDirectory, root.resolve("missing-release"), now).resolve(root.resolve("not-installed"),
				"harness-b", MANIFEST, RUNNER);
		}
		catch (IOException _)
		{
			// The release is missing, so the download fails after the check
			return;
		}
		throw new AssertionError("The resolution succeeded");
	}

	/**
	 * Returns the names of a directory's entries.
	 *
	 * @param directory the directory
	 * @return the names, sorted
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<String> entries(Path directory) throws IOException
	{
		if (Files.notExists(directory))
			return List.of();
		try (Stream<Path> paths = Files.list(directory))
		{
			return paths.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Verifies that an installed plugin of CAT's version is used without reading the release.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void usesInstalledPluginOfSameVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-installed-"));
			Path installed = artifact(root.resolve("installed"), PluginInfo.CAT_VERSION);

			RunnerArtifact result = bundles(root.resolve("bundles"), root.resolve("missing-release")).
				resolve(installed, HARNESS, MANIFEST, RUNNER);

			requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.INSTALLED);
			requireThat(result.artifact(), "artifact").isEqualTo(installed.toAbsolutePath().normalize());
			requireThat(result.runner(), "runner").isEqualTo(runner(installed.toAbsolutePath().normalize()));
			requireThat(entries(root.resolve("bundles")), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that an installed plugin of another CAT version is passed over for an earlier download.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void installedOtherVersionUsesBundle() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-other-installed-"));
			Path installed = artifact(root.resolve("installed"), "0.9");
			Path bundle = artifact(root.resolve("bundles").resolve(bundleName()), PluginInfo.CAT_VERSION);

			RunnerArtifact result = bundles(root.resolve("bundles"), root.resolve("missing-release")).
				resolve(installed, HARNESS, MANIFEST, RUNNER);

			requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.BUNDLE);
			requireThat(result.artifact(), "artifact").isEqualTo(bundle.toAbsolutePath().normalize());
		}
	}

	/**
	 * Verifies that a missing installed plugin and a missing bundle lead to a download that is verified, extracted
	 * into the bundle directory and used, with no temporary directory left beside it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void downloadsVerifiesAndExtracts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-download-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			Path bundlesDirectory = root.resolve("bundles");

			RunnerArtifact result = bundles(bundlesDirectory, release).resolve(root.resolve("not-installed"), HARNESS,
				MANIFEST, RUNNER);

			Path bundle = bundlesDirectory.resolve(bundleName()).toAbsolutePath().normalize();
			requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.DOWNLOAD);
			requireThat(result.artifact(), "artifact").isEqualTo(bundle);
			requireThat(result.runner(), "runner").isEqualTo(runner(bundle));
			requireThat(Files.isExecutable(result.runner()), "runnerExecutable").isTrue();
			requireThat(Files.readString(bundle.resolve("client/VERSION")).strip(), "version").
				isEqualTo(PluginInfo.CAT_VERSION);
			requireThat(entries(bundlesDirectory), "bundles").isEqualTo(List.of(bundleName()));

			RunnerArtifact again = bundles(bundlesDirectory, root.resolve("missing-release")).
				resolve(root.resolve("not-installed"), HARNESS, MANIFEST, RUNNER);
			requireThat(again.source(), "againSource").isEqualTo(RunnerArtifact.Source.BUNDLE);
		}
	}

	/**
	 * Verifies that an archive whose checksum differs from the one that {@code SHA256SUMS.txt} publishes is rejected
	 * and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void checksumMismatchLeavesNoCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-checksum-"));
			Path release = root.resolve("release");
			Path archive = FakeRelease.publish(release, bundleName(),
				artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			FakeRelease.addChecksum(release, archive.getFileName().toString(), "0".repeat(64));
			Path bundlesDirectory = root.resolve("bundles");

			IOException failure = expectFailure(bundles(bundlesDirectory, release), root);

			requireThat(failure.getMessage(), "message").contains("checksum").contains(bundleName() + ".tar.gz");
			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that a release whose {@code SHA256SUMS.txt} publishes no checksum for the archive is rejected and leaves
	 * no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void missingChecksumLeavesNoCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-no-checksum-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			Files.writeString(release.resolve("SHA256SUMS.txt"), "");
			Path bundlesDirectory = root.resolve("bundles");

			IOException failure = expectFailure(bundles(bundlesDirectory, release), root);

			requireThat(failure.getMessage(), "message").contains("No checksum");
			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that a release without the archive is reported and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void missingArchiveLeavesNoCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-no-archive-"));
			Path release = Files.createDirectories(root.resolve("release"));
			Path bundlesDirectory = root.resolve("bundles");

			IOException failure = expectFailure(bundles(bundlesDirectory, release), root);

			requireThat(failure.getMessage(), "message").contains(bundleName() + ".tar.gz");
			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that an archive that ends part way through, although its checksum matches, is rejected and leaves no
	 * partial copy for a later run to use.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void partialExtractionLeavesNoCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-partial-"));
			Path release = root.resolve("release");
			Path archive = FakeRelease.publish(release, bundleName(),
				artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			byte[] truncated = FakeRelease.truncated(archive);
			Files.write(archive, truncated);
			FakeRelease.addChecksum(release, archive.getFileName().toString(), FakeRelease.sha256(truncated));
			Path bundlesDirectory = root.resolve("bundles");

			expectFailure(bundles(bundlesDirectory, release), root);

			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
			IOException again = expectFailure(bundles(bundlesDirectory, root.resolve("missing-release")), root);
			requireThat(again.getMessage(), "again").contains(bundleName() + ".tar.gz");
		}
	}

	/**
	 * Verifies that an archive whose artifact states another CAT version is rejected and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void otherVersionArchiveLeavesNoCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-other-version-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), "0.9"));
			Path bundlesDirectory = root.resolve("bundles");

			IOException failure = expectFailure(bundles(bundlesDirectory, release), root);

			requireThat(failure.getMessage(), "message").contains("version");
			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that an archive whose artifact lacks the harness's runner launcher is rejected and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void archiveWithoutRunnerLeavesNoCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-no-runner-"));
			Path release = root.resolve("release");
			Path source = artifact(root.resolve("source"), PluginInfo.CAT_VERSION);
			Files.delete(runner(source));
			FakeRelease.publish(release, bundleName(), source);
			Path bundlesDirectory = root.resolve("bundles");

			IOException failure = expectFailure(bundles(bundlesDirectory, release), root);

			requireThat(failure.getMessage(), "message").contains("runner launcher");
			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that an archive entry that names a path outside the archive's directory is rejected, writes nothing
	 * outside it and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void entryOutsideArchiveRejected() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-traversal-"));
			Path release = root.resolve("release");
			FakeRelease.publishEntries(release, bundleName() + ".tar.gz", List.of(
				Entry.file(bundleName() + "/../../escaped.txt", "escaped")));
			Path bundlesDirectory = root.resolve("bundles");

			IOException failure = expectFailure(bundles(bundlesDirectory, release), root);

			requireThat(failure.getMessage(), "message").contains("escaped.txt");
			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
			requireThat(Files.notExists(root.resolve("escaped.txt")), "escapedAbsent").isTrue();
		}
	}

	/**
	 * Verifies that a symbolic link whose target lies outside the archive's directory is rejected and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void linkOutsideArchiveRejected() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-link-"));
			Path release = root.resolve("release");
			FakeRelease.publishEntries(release, bundleName() + ".tar.gz", List.of(
				Entry.link(bundleName() + "/outside", "../../outside")));
			Path bundlesDirectory = root.resolve("bundles");

			IOException failure = expectFailure(bundles(bundlesDirectory, release), root);

			requireThat(failure.getMessage(), "message").contains("outside");
			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that the release is downloaded over HTTP, which a published release uses, from an in-process server.
	 *
	 * @throws IOException if the fixture cannot be written or the server cannot start
	 */
	// Requirement: R-I6
	@Test
	public void downloadsOverHttp() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-http-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			HttpServer server = serve(release);
			try
			{
				URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/download/v1.0");
				Path bundlesDirectory = root.resolve("bundles");

				RunnerArtifact result = new ReleaseBundles(bundlesDirectory, uri, Platform.current(), Map.of()).
					resolve(root.resolve("not-installed"), HARNESS, MANIFEST, RUNNER);

				requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.DOWNLOAD);
				requireThat(entries(bundlesDirectory), "bundles").isEqualTo(List.of(bundleName()));
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Verifies that an HTTP error status is reported with the URL and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written or the server cannot start
	 */
	// Requirement: R-I6
	@Test
	public void httpErrorLeavesNoCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-http-error-"));
			HttpServer server = serve(Files.createDirectories(root.resolve("empty")));
			try
			{
				URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/download/v1.0");
				Path bundlesDirectory = root.resolve("bundles");

				IOException failure = expectFailure(new ReleaseBundles(bundlesDirectory, uri, Platform.current(), Map.of()),
					root);

				requireThat(failure.getMessage(), "message").contains("404").contains(uri.toString());
				requireThat(entries(bundlesDirectory), "bundles").isEmpty();
			}
			finally
			{
				server.stop(0);
			}
		}
	}

	/**
	 * Verifies that a download from a host that does not resolve, a failure that the HTTP client reports without a
	 * message, is reported with the URL and a reason that names the unresolved host, and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void unresolvedHostNamesFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-unresolved-"));
			// The reserved top-level domain ".invalid" never resolves
			URI uri = URI.create("http://release.invalid/download/v1.0");
			Path bundlesDirectory = root.resolve("bundles");

			IOException failure = expectFailure(new ReleaseBundles(bundlesDirectory, uri, Platform.current(), Map.of()),
				root);

			requireThat(failure.getMessage(), "message").contains(uri.toString()).contains("could not be resolved").
				doesNotContain("null");
			requireThat(entries(bundlesDirectory), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that a release whose host the machine cannot resolve is downloaded through the proxy that
	 * {@code HTTP_PROXY} names, including the redirect to another download host that GitHub answers a release download
	 * with. The release's server and its download host, which only the proxy reaches, are modeled by an in-process
	 * HTTP server that the download reaches as its proxy: it answers a request for the release host with a redirect to
	 * the download host, and a request for the download host with the release's file.
	 *
	 * @throws IOException if the fixture cannot be written or the server cannot start
	 */
	@Test
	public void downloadsThroughHttpProxy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-proxy-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			Queue<String> requested = new ConcurrentLinkedQueue<>();
			HttpServer proxy = serveAsProxy(release, requested, null);
			try
			{
				// The reserved top-level domain ".invalid" never resolves, so only the proxy reaches these hosts
				URI uri = URI.create("http://release.invalid/download/v1.0");
				Path bundlesDirectory = root.resolve("bundles");
				Map<String, String> environment = Map.of("HTTP_PROXY", "http://127.0.0.1:" +
					proxy.getAddress().getPort());

				RunnerArtifact result = new ReleaseBundles(bundlesDirectory, uri, Platform.current(), environment).
					resolve(root.resolve("not-installed"), HARNESS, MANIFEST, RUNNER);

				requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.DOWNLOAD);
				requireThat(entries(bundlesDirectory), "bundles").isEqualTo(List.of(bundleName()));
				requireThat(List.copyOf(requested), "requested").containsAll(List.of(
					"http://release.invalid/download/v1.0/" + bundleName() + ".tar.gz",
					"http://objects.invalid/download/v1.0/" + bundleName() + ".tar.gz"));
			}
			finally
			{
				proxy.stop(0);
			}
		}
	}

	/**
	 * Verifies that a download through a proxy that requires a login answers the proxy's challenge with the user name and
	 * password of the proxy's URL, percent-decoded. The proxy is modeled by the in-process server of
	 * {@link #downloadsThroughHttpProxy()}, which answers every request without the expected
	 * {@code Proxy-Authorization} header with status 407 and a {@code Proxy-Authenticate: Basic} challenge.
	 *
	 * @throws IOException if the fixture cannot be written or the server cannot start
	 */
	@Test
	public void proxyLoginUsesUrlCredentials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-proxy-login-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			Queue<String> requested = new ConcurrentLinkedQueue<>();
			HttpServer proxy = serveAsProxy(release, requested, "agent:p@ss word");
			try
			{
				URI uri = URI.create("http://release.invalid/download/v1.0");
				Path bundlesDirectory = root.resolve("bundles");
				Map<String, String> environment = Map.of("HTTP_PROXY", "http://agent:p%40ss%20word@127.0.0.1:" +
					proxy.getAddress().getPort());

				RunnerArtifact result = new ReleaseBundles(bundlesDirectory, uri, Platform.current(), environment).
					resolve(root.resolve("not-installed"), HARNESS, MANIFEST, RUNNER);

				requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.DOWNLOAD);
				requireThat(entries(bundlesDirectory), "bundles").isEqualTo(List.of(bundleName()));
			}
			finally
			{
				proxy.stop(0);
			}
		}
	}

	/**
	 * Verifies that a proxy that rejects the URL's credentials fails the download with a message that says the proxy
	 * named by the variable rejected the login, and does not reveal the credentials.
	 *
	 * @throws IOException if the fixture cannot be written or the server cannot start
	 */
	@Test
	public void rejectedProxyLoginHidesCredentials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-proxy-rejected-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			HttpServer proxy = serveAsProxy(release, new ConcurrentLinkedQueue<>(), "agent:right");
			try
			{
				URI uri = URI.create("http://release.invalid/download/v1.0");
				Map<String, String> environment = Map.of("HTTP_PROXY", "http://agent:wrong-s3cret@127.0.0.1:" +
					proxy.getAddress().getPort());

				IOException failure = expectFailure(new ReleaseBundles(root.resolve("bundles"), uri, Platform.current(),
					environment), root);

				requireThat(failure.getMessage(), "message").contains("HTTP_PROXY").contains("rejected the login").
					doesNotContain("wrong-s3cret").doesNotContain("agent");
				requireThat(entries(root.resolve("bundles")), "bundles").isEmpty();
			}
			finally
			{
				proxy.stop(0);
			}
		}
	}

	/**
	 * Verifies that the proxy's credentials are not sent to a release server that asks for a login, and that the
	 * failure says the server asks for one. The release server
	 * {@code private.invalid}, reached through the in-process proxy, answers with status 401 and a
	 * {@code WWW-Authenticate: Basic} challenge and records the {@code Authorization} header of each request.
	 *
	 * @throws IOException if the fixture cannot be written or the server cannot start
	 */
	@Test
	public void serverLoginGetsNoProxyCredentials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-server-login-"));
			Queue<String> requested = new ConcurrentLinkedQueue<>();
			HttpServer proxy = serveAsProxy(Files.createDirectories(root.resolve("release")), requested, "agent:secret");
			try
			{
				URI uri = URI.create("http://private.invalid/download/v1.0");
				Map<String, String> environment = Map.of("HTTP_PROXY", "http://agent:secret@127.0.0.1:" +
					proxy.getAddress().getPort());

				IOException failure = expectFailure(new ReleaseBundles(root.resolve("bundles"), uri, Platform.current(),
					environment), root);

				requireThat(failure.getMessage(), "message").contains("server asks for a login").doesNotContain("secret");
				requireThat(List.copyOf(requested), "requested").contains(
					"http://private.invalid/download/v1.0/" + bundleName() + ".tar.gz").
					doesNotContain("Authorization sent");
			}
			finally
			{
				proxy.stop(0);
			}
		}
	}

	/**
	 * Returns the process environments that name a proxy for a release on {@code https://release.invalid}.
	 *
	 * @return for each environment, the name of the variable that names the proxy, whose value the test sets to the
	 *   proxy's address, and the environment's other variables
	 */
	@DataProvider(name = "httpsProxyVariables")
	public static Object[][] httpsProxyVariables()
	{
		return new Object[][]
		{
			{"HTTPS_PROXY", Map.of()},
			{"https_proxy", Map.of()},
			{"ALL_PROXY", Map.of()},
			{"all_proxy", Map.of()},
			{"HTTPS_PROXY", Map.of("NO_PROXY", "example.com,other.invalid")},
			{"ALL_PROXY", Map.of("HTTPS_PROXY", "socks5://127.0.0.1:1")}
		};
	}

	/**
	 * Verifies that a download over HTTPS opens its tunnel through the proxy that the environment names for HTTPS,
	 * passing over a variable that names a proxy of a scheme that the download cannot use. The
	 * proxy is modeled by a local socket that records the request it receives and refuses it, so the download fails
	 * after the proxy has received it.
	 *
	 * @param variable the variable that names the proxy
	 * @param others   the other variables of the environment
	 * @throws IOException if the fixture cannot be written or the socket cannot be opened
	 */
	@Test(dataProvider = "httpsProxyVariables")
	public void httpsDownloadUsesProxy(String variable, Map<String, String> others) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			RefusingProxy proxy = new RefusingProxy())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-https-proxy-"));
			URI uri = URI.create("https://release.invalid/download/v1.0");
			Map<String, String> environment = new HashMap<>(others);
			environment.put(variable, "http://127.0.0.1:" + proxy.port());

			expectFailure(new ReleaseBundles(root.resolve("bundles"), uri, Platform.current(), environment), root);

			requireThat(List.copyOf(proxy.requested()), "requested").
				isEqualTo(List.of("CONNECT release.invalid:443 HTTP/1.1"));
		}
	}

	/**
	 * Returns the process environments that name a proxy that a download from {@code release.invalid} must not use.
	 *
	 * @return for each environment, the release's location and the environment's variables, in which the test replaces
	 *   each empty value by the proxy's address
	 */
	@DataProvider(name = "bypassedProxy")
	public static Object[][] bypassedProxy()
	{
		return new Object[][]
		{
			{"http://release.invalid/download/v1.0", Map.of("HTTP_PROXY", "", "NO_PROXY", "release.invalid")},
			{"http://release.invalid/download/v1.0", Map.of("HTTP_PROXY", "", "no_proxy", "other.example, invalid")},
			{"http://release.invalid/download/v1.0", Map.of("HTTP_PROXY", "", "NO_PROXY", ".invalid")},
			{"http://release.invalid/download/v1.0", Map.of("HTTP_PROXY", "", "NO_PROXY", "*")},
			{"https://release.invalid/download/v1.0", Map.of("HTTPS_PROXY", "", "NO_PROXY", "RELEASE.invalid")},
			{"https://release.invalid/download/v1.0", Map.of("HTTP_PROXY", "")},
			{"http://release.invalid/download/v1.0", Map.of("HTTPS_PROXY", "")},
			{"https://release.invalid/download/v1.0", Map.of("HTTPS_PROXY", "socks5://agent:s3cret@127.0.0.1:1")}
		};
	}

	/**
	 * Verifies that a download connects directly, without the proxy, when {@code NO_PROXY} lists its host, as an exact
	 * name, a domain with or without a leading dot, or {@code *}, when the environment names a proxy only for the other
	 * scheme, or when it names only a proxy of a scheme that the download cannot use. The proxy is modeled by a local
	 * socket that records the requests it receives; the direct connection fails because the release's host does not
	 * resolve.
	 *
	 * @param release   the release's location
	 * @param variables the environment, in which each empty value is replaced by the proxy's address
	 * @throws IOException if the fixture cannot be written or the socket cannot be opened
	 */
	@Test(dataProvider = "bypassedProxy")
	public void downloadBypassesProxy(String release, Map<String, String> variables) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			RefusingProxy proxy = new RefusingProxy())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-no-proxy-"));
			URI uri = URI.create(release);
			Map<String, String> environment = new HashMap<>();
			variables.forEach((name, value) ->
			{
				if (value.isEmpty())
					environment.put(name, "http://127.0.0.1:" + proxy.port());
				else
					environment.put(name, value);
			});

			IOException failure = expectFailure(new ReleaseBundles(root.resolve("bundles"), uri, Platform.current(),
				environment), root);

			requireThat(failure.getMessage(), "message").contains("could not be resolved").doesNotContain("s3cret");
			requireThat(List.copyOf(proxy.requested()), "requested").isEmpty();
		}
	}

	/**
	 * Verifies that a proxy URL that cannot be parsed fails the download with a message that names its variable and
	 * does not reveal the credentials that the URL holds.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void malformedProxyNamesVariable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-bad-proxy-"));
			URI uri = URI.create("https://release.invalid/download/v1.0");
			Map<String, String> environment = Map.of("HTTPS_PROXY", "http://agent:s3cret@proxy host:3128");

			IOException failure = expectFailure(new ReleaseBundles(root.resolve("bundles"), uri, Platform.current(),
				environment), root);

			requireThat(failure.getMessage(), "message").contains(uri.toString()).contains("HTTPS_PROXY").
				doesNotContain("s3cret").doesNotContain("agent");
			requireThat(entries(root.resolve("bundles")), "bundles").isEmpty();
		}
	}

	/**
	 * Verifies that a server that sends no response headers within the response limit is reported with the URL and the
	 * expired limit, and leaves no copy, although the limit for the whole file has not expired.
	 *
	 * @throws IOException if the fixture cannot be written or the server cannot start
	 */
	// Requirement: R-I6
	@Test
	public void silentServerExpiresResponseLimit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-silent-"));
			CountDownLatch release = new CountDownLatch(1);
			HttpServer server = serveStalled(release, false);
			try
			{
				URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/download/v1.0");
				Path bundlesDirectory = root.resolve("bundles");
				ReleaseBundles bundles = ReleaseBundlesSecrets.getReleaseBundlesAccess().create(bundlesDirectory, uri,
					Platform.current(), Duration.ofSeconds(30), Duration.ofMillis(300), Duration.ofSeconds(30),
					Clock.systemUTC());

				IOException failure = expectFailure(bundles, root);

				requireThat(failure.getMessage(), "message").contains(uri.toString()).contains("No response").
					contains("300 milliseconds");
				requireThat(entries(bundlesDirectory), "bundles").isEmpty();
			}
			finally
			{
				release.countDown();
				server.stop(0);
			}
		}
	}

	/**
	 * Verifies that a server that sends headers but never finishes the body is reported with the URL and the expired
	 * limit for the whole file, and leaves no copy, although the response limit has not expired.
	 *
	 * @throws IOException if the fixture cannot be written or the server cannot start
	 */
	// Requirement: R-I6
	@Test
	public void unfinishedBodyExpiresDownloadLimit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-unfinished-"));
			CountDownLatch release = new CountDownLatch(1);
			HttpServer server = serveStalled(release, true);
			try
			{
				URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/download/v1.0");
				Path bundlesDirectory = root.resolve("bundles");
				ReleaseBundles bundles = ReleaseBundlesSecrets.getReleaseBundlesAccess().create(bundlesDirectory, uri,
					Platform.current(), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMillis(400),
					Clock.systemUTC());

				IOException failure = expectFailure(bundles, root);

				requireThat(failure.getMessage(), "message").contains(uri.toString()).contains("did not finish").
					contains("400 milliseconds");
				requireThat(entries(bundlesDirectory), "bundles").isEmpty();
			}
			finally
			{
				release.countDown();
				server.stop(0);
			}
		}
	}

	/**
	 * Verifies that a download deletes the bundles of other CAT versions, of any harness and platform, and keeps this
	 * version's bundles, unrelated entries and the hidden temporary directories of a concurrent download.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void downloadDeletesOtherVersions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-prune-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			Path bundlesDirectory = root.resolve("bundles");
			String otherPlatform = "cat-" + HARNESS + "-v" + PluginInfo.CAT_VERSION + "-windows-arm64";
			String hidden = ".cat-" + HARNESS + "-v0.9-linux-x64-temporary";
			Files.createDirectories(bundlesDirectory.resolve("cat-" + HARNESS + "-v0.9-linux-x64/bin"));
			Files.createDirectories(bundlesDirectory.resolve("cat-other-v2.0-macos-arm64"));
			Files.createDirectories(bundlesDirectory.resolve(otherPlatform));
			Files.createDirectories(bundlesDirectory.resolve("notes"));
			Files.createDirectories(bundlesDirectory.resolve(hidden));
			Files.writeString(bundlesDirectory.resolve("cat-" + HARNESS + "-v0.8-windows-x64"), "file");

			RunnerArtifact result = bundles(bundlesDirectory, release).resolve(root.resolve("not-installed"), HARNESS,
				MANIFEST, RUNNER);

			requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.DOWNLOAD);
			requireThat(entries(bundlesDirectory), "bundles").isEqualTo(
				List.of(hidden, bundleName(), otherPlatform, "notes").stream().sorted().toList());
		}
	}

	/**
	 * Verifies that resolving to an existing bundle or the installed plugin deletes nothing.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void resolutionWithoutDownloadDeletesNothing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-no-prune-"));
			Path bundlesDirectory = root.resolve("bundles");
			Path installed = artifact(root.resolve("installed"), PluginInfo.CAT_VERSION);
			artifact(bundlesDirectory.resolve(bundleName()), PluginInfo.CAT_VERSION);
			String old = "cat-" + HARNESS + "-v0.9-linux-x64";
			Files.createDirectories(bundlesDirectory.resolve(old));
			ReleaseBundles bundles = bundles(bundlesDirectory, root.resolve("missing-release"));

			RunnerArtifact fromBundle = bundles.resolve(root.resolve("not-installed"), HARNESS, MANIFEST, RUNNER);
			RunnerArtifact fromPlugin = bundles.resolve(installed, HARNESS, MANIFEST, RUNNER);

			requireThat(fromBundle.source(), "bundleSource").isEqualTo(RunnerArtifact.Source.BUNDLE);
			requireThat(fromPlugin.source(), "pluginSource").isEqualTo(RunnerArtifact.Source.INSTALLED);
			requireThat(entries(bundlesDirectory), "bundles").isEqualTo(
				List.of(bundleName(), old).stream().sorted().toList());
		}
	}

	/**
	 * Verifies that checking for a bundle deletes the bundles of any version, harness and platform that were last used
	 * more than three calendar months earlier, and keeps the bundles used since then, the entries that are not bundles
	 * and the hidden temporary directories of a concurrent download.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void checkDeletesBundlesUnusedForThreeMonths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-age-"));
			Path bundlesDirectory = root.resolve("bundles");
			Instant threeMonthsEarlier = Instant.parse("2026-02-28T12:00:00Z");
			String expired = "cat-" + HARNESS + "-v" + PluginInfo.CAT_VERSION + "-windows-arm64";
			String expiredOldVersion = "cat-other-v0.9-linux-x64";
			String kept = "cat-other-v" + PluginInfo.CAT_VERSION + "-macos-arm64";
			String hidden = ".cat-other-v0.9-linux-x64-temporary";
			for (String name : List.of(expired, expiredOldVersion, kept, hidden, "notes"))
				Files.createDirectories(bundlesDirectory.resolve(name));
			lastUsed(bundlesDirectory.resolve(expired), threeMonthsEarlier.minusSeconds(1));
			lastUsed(bundlesDirectory.resolve(expiredOldVersion), Instant.parse("2025-01-01T00:00:00Z"));
			lastUsed(bundlesDirectory.resolve(kept), threeMonthsEarlier);
			lastUsed(bundlesDirectory.resolve(hidden), Instant.parse("2025-01-01T00:00:00Z"));
			lastUsed(bundlesDirectory.resolve("notes"), Instant.parse("2025-01-01T00:00:00Z"));

			checkOtherHarness(bundlesDirectory, root, NOW);

			requireThat(entries(bundlesDirectory), "bundles").isEqualTo(
				List.of(hidden, kept, "notes").stream().sorted().toList());
		}
	}

	/**
	 * Verifies that using a bundle restarts its age: a bundle last used four months earlier is used rather than deleted,
	 * survives a check two months after that use, and is deleted by a check more than three months after it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void usingBundleRestartsItsAge() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-age-use-"));
			Path bundlesDirectory = root.resolve("bundles");
			Path bundle = artifact(bundlesDirectory.resolve(bundleName()), PluginInfo.CAT_VERSION);
			lastUsed(bundle, Instant.parse("2026-01-31T12:00:00Z"));

			RunnerArtifact result = bundles(bundlesDirectory, root.resolve("missing-release"), NOW).
				resolve(root.resolve("not-installed"), HARNESS, MANIFEST, RUNNER);

			requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.BUNDLE);
			checkOtherHarness(bundlesDirectory, root, Instant.parse("2026-07-31T12:00:00Z"));
			requireThat(entries(bundlesDirectory), "afterTwoMonths").isEqualTo(List.of(bundleName()));
			checkOtherHarness(bundlesDirectory, root, Instant.parse("2026-08-31T12:00:01Z"));
			requireThat(entries(bundlesDirectory), "afterThreeMonths").isEmpty();
		}
	}

	/**
	 * Verifies that a downloaded bundle's age starts at its download: it survives a check three months after the
	 * download and is deleted by a check more than three months after it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-I6
	@Test
	public void downloadStartsBundleAge() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("release-age-download-"));
			Path release = root.resolve("release");
			FakeRelease.publish(release, bundleName(), artifact(root.resolve("source"), PluginInfo.CAT_VERSION));
			Path bundlesDirectory = root.resolve("bundles");
			Instant downloaded = Instant.parse("2020-01-30T12:00:00Z");

			RunnerArtifact result = bundles(bundlesDirectory, release, downloaded).resolve(root.resolve("not-installed"),
				HARNESS, MANIFEST, RUNNER);

			requireThat(result.source(), "source").isEqualTo(RunnerArtifact.Source.DOWNLOAD);
			checkOtherHarness(bundlesDirectory, root, Instant.parse("2020-04-30T12:00:00Z"));
			requireThat(entries(bundlesDirectory), "afterThreeMonths").isEqualTo(List.of(bundleName()));
			checkOtherHarness(bundlesDirectory, root, Instant.parse("2020-04-30T12:00:01Z"));
			requireThat(entries(bundlesDirectory), "afterLonger").isEmpty();
		}
	}

	/**
	 * Verifies that the release's location defaults to the GitHub release of CAT's version.
	 */
	// Requirement: R-I6
	@Test
	public void releaseUriDefaultsToGitHub()
	{
		requireThat(ReleaseBundles.releaseUri(Map.of()), "uri").isEqualTo(URI.create(
			"https://github.com/holdmyspot-com/cat/releases/download/v" + PluginInfo.CAT_VERSION));
	}

	/**
	 * Verifies that {@code CAT_RELEASE_BASE_URL} replaces the release's location, as it does for the installer.
	 */
	@Test
	public void releaseUriHonorsOverride()
	{
		requireThat(ReleaseBundles.releaseUri(Map.of("CAT_RELEASE_BASE_URL", "file:///srv/release/")), "uri").
			isEqualTo(URI.create("file:///srv/release"));
	}

	/**
	 * Verifies that bundles are kept in CAT's per-user directory.
	 */
	// Requirement: R-I6
	@Test
	public void bundlesLiveInCatDirectory()
	{
		Path home = Path.of("home");
		requireThat(ReleaseBundles.directory(home), "directory").isEqualTo(home.resolve(".cat/bundles"));
	}

	/**
	 * Resolves an artifact that is neither installed nor bundled, expecting the download to fail.
	 *
	 * @param bundles the resolution
	 * @param root    the test's directory
	 * @return the failure
	 */
	private static IOException expectFailure(ReleaseBundles bundles, Path root)
	{
		try
		{
			bundles.resolve(root.resolve("not-installed"), HARNESS, MANIFEST, RUNNER);
		}
		catch (IOException e)
		{
			return e;
		}
		throw new AssertionError("The resolution succeeded");
	}

	/**
	 * Starts an HTTP server on the loopback address whose every request stalls until a latch is released: before the
	 * response headers, or after the headers and the first bytes of the body.
	 *
	 * @param release   the latch that ends the stall
	 * @param afterHead {@code true} to send the headers and a part of the body before stalling
	 * @return the started server
	 * @throws IOException if the server cannot start
	 */
	private static HttpServer serveStalled(CountDownLatch release, boolean afterHead) throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/download/v1.0/", exchange ->
		{
			try (exchange)
			{
				if (afterHead)
				{
					exchange.sendResponseHeaders(200, 1_000_000);
					OutputStream out = exchange.getResponseBody();
					out.write(new byte[1024]);
					out.flush();
				}
				release.await();
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
			}
		});
		server.start();
		return server;
	}

	/**
	 * Starts an HTTP server on the loopback address that serves a release directory's files below
	 * {@code /download/v1.0/}.
	 *
	 * @param release the release directory
	 * @return the started server
	 * @throws IOException if the server cannot start
	 */
	private static HttpServer serve(Path release) throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/download/v1.0/", exchange ->
		{
			String name = exchange.getRequestURI().getPath().substring("/download/v1.0/".length());
			Path file = release.resolve(name);
			if (name.contains("/") || !Files.isRegularFile(file))
			{
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			byte[] body = Files.readAllBytes(file);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(body);
			}
		});
		server.start();
		return server;
	}

	/**
	 * Starts an HTTP server on the loopback address that a download reaches as its HTTP proxy, and that stands for two
	 * hosts that only the proxy reaches: {@code release.invalid}, which answers a request below
	 * {@code /download/v1.0/} with a redirect to the same path on {@code objects.invalid}, as GitHub redirects a release
	 * download to its download host; {@code objects.invalid}, which serves the release directory's files there; and
	 * {@code private.invalid}, which asks for a login with status 401 and a {@code WWW-Authenticate: Basic} challenge.
	 * When the proxy requires a login, it answers a request without the matching Basic {@code Proxy-Authorization}
	 * header with status 407 and a {@code Proxy-Authenticate: Basic} challenge, as an authenticating HTTP proxy does
	 * (RFC 9110, section 11.7).
	 *
	 * @param release     the release directory
	 * @param requested   receives the absolute URL of each request that the proxy receives, followed by
	 *                    {@code Authorization sent} for a request that carries an {@code Authorization} header
	 * @param credentials the user name and password, joined by {@code :}, that the proxy requires, or {@code null} if
	 *                    it requires no login
	 * @return the started server
	 * @throws IOException if the server cannot start
	 */
	private static HttpServer serveAsProxy(Path release, Queue<String> requested, String credentials)
		throws IOException
	{
		String expectedLogin;
		if (credentials == null)
			expectedLogin = null;
		else
			expectedLogin = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/download/v1.0/", exchange ->
		{
			if (expectedLogin != null &&
				!expectedLogin.equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization")))
			{
				exchange.getResponseHeaders().set("Proxy-Authenticate", "Basic realm=\"proxy\"");
				exchange.sendResponseHeaders(407, -1);
				exchange.close();
				return;
			}
			// A client that uses a proxy names the whole URL, host included, in its request
			URI target = exchange.getRequestURI();
			requested.add(target.toString());
			if (exchange.getRequestHeaders().containsKey("Authorization"))
				requested.add("Authorization sent");
			String name = target.getPath().substring("/download/v1.0/".length());
			Path file = release.resolve(name);
			if (Objects.equals(target.getHost(), "private.invalid"))
			{
				exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"release\"");
				exchange.sendResponseHeaders(401, -1);
				exchange.close();
				return;
			}
			if (Objects.equals(target.getHost(), "release.invalid"))
			{
				exchange.getResponseHeaders().set("Location", "http://objects.invalid" + target.getPath());
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
				return;
			}
			if (!Objects.equals(target.getHost(), "objects.invalid") || name.contains("/") ||
				!Files.isRegularFile(file))
			{
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			byte[] body = Files.readAllBytes(file);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(body);
			}
		});
		server.start();
		return server;
	}

	/**
	 * An HTTP proxy on the loopback address that records the first line of each request it receives and refuses the
	 * request with status 403, so that a download through it fails once the proxy has received the request.
	 */
	private static final class RefusingProxy implements AutoCloseable
	{
		private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		private final Queue<String> requested = new ConcurrentLinkedQueue<>();
		private final Thread acceptor = Thread.ofVirtual().start(this::accept);

		/**
		 * Opens the proxy's socket and starts accepting connections.
		 *
		 * @throws IOException if the socket cannot be opened
		 */
		private RefusingProxy() throws IOException
		{
		}

		/**
		 * Returns the port that the proxy listens on.
		 *
		 * @return the port
		 */
		private int port()
		{
			return socket.getLocalPort();
		}

		/**
		 * Returns the first line of each request that the proxy received, in the order received.
		 *
		 * @return the request lines
		 */
		private Queue<String> requested()
		{
			return requested;
		}

		/**
		 * Accepts connections until the socket closes, refusing the request of each.
		 */
		private void accept()
		{
			while (!socket.isClosed())
			{
				try (Socket connection = socket.accept();
					BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), UTF_8));
					OutputStream out = connection.getOutputStream())
				{
					String line = in.readLine();
					if (line != null)
						requested.add(line);
					out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".
						getBytes(UTF_8));
				}
				catch (IOException _)
				{
					// The socket closed, or the client went away
				}
			}
		}

		@Override
		public void close() throws IOException
		{
			socket.close();
			try
			{
				acceptor.join();
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
			}
		}
	}
}
