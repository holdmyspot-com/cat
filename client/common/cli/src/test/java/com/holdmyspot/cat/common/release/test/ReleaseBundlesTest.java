package com.holdmyspot.cat.common.release.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.release.ReleaseBundles;
import com.holdmyspot.cat.common.release.RunnerArtifact;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease.Entry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
		return new ReleaseBundles(bundles, release.toUri(), Platform.current());
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
	 * Verifies that an archive entry that names a path outside the archive's directory is rejected, writes nothing
	 * outside it and leaves no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
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

				RunnerArtifact result = new ReleaseBundles(bundlesDirectory, uri, Platform.current()).
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

				IOException failure = expectFailure(new ReleaseBundles(bundlesDirectory, uri, Platform.current()), root);

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
	 * Verifies that the release's location defaults to the GitHub release of CAT's version.
	 */
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
}
