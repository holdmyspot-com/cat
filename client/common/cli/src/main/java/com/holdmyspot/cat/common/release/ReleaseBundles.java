package com.holdmyspot.cat.common.release;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.secrets.ReleaseBundlesAccess;
import com.holdmyspot.cat.common.secrets.ReleaseBundlesSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves another harness's CAT artifact of this CAT version and platform, so that a runner skill can run a task in
 * that harness with CAT installed although the user installed CAT only for its own harness.
 * <p>
 * The artifact is the first of: the harness's installed CAT plugin when its version matches; the copy that an earlier
 * download left in the bundle directory; otherwise a copy that this class downloads from the release of this CAT
 * version. A download fetches the harness's release archive and the release's {@code SHA256SUMS.txt}, verifies the
 * archive's checksum, extracts it into a temporary directory beside its destination, verifies the extracted artifact
 * and only then moves it into place, so that a failed download, checksum or extraction leaves no copy for a later
 * resolution to use.
 * <p>
 * A download over HTTP waits at most 30 seconds to connect, 60 seconds for the server's response headers and 15 minutes
 * for the whole file, and reports the limit that expired. After a download, the bundles of other CAT versions, of any
 * harness and platform, are deleted from the bundle directory.
 * <p>
 * A bundle's last-modified time tells when it was last used: a download sets it to the time of the download, and each
 * resolution that uses the bundle sets it to the time of that resolution. Every resolution deletes the bundles, of any
 * CAT version, harness and platform, that were last used more than three calendar months earlier, except the bundle
 * that it uses.
 */
public final class ReleaseBundles
{
	static
	{
		ReleaseBundlesSecrets.setReleaseBundlesAccess(new ReleaseBundlesAccess()
		{
			@Override
			public ReleaseBundles create(Path directory, URI release, Platform platform, Duration connectLimit,
				Duration responseLimit, Duration downloadLimit, Clock clock)
			{
				return new ReleaseBundles(directory, release, platform, connectLimit, responseLimit, downloadLimit,
					clock);
			}
		});
	}

	/**
	 * The release of this CAT version on GitHub, which holds every harness's archive for every platform.
	 */
	private static final URI DEFAULT_RELEASE = URI.create(PluginInfo.CAT_REPOSITORY + "/releases/download/v" +
		PluginInfo.CAT_VERSION);
	/**
	 * The environment variable that replaces the release's location, as it does for CAT's installers.
	 */
	private static final String RELEASE_VARIABLE = "CAT_RELEASE_BASE_URL";
	/**
	 * The release's checksum file.
	 */
	private static final String CHECKSUMS = "SHA256SUMS.txt";
	/**
	 * The largest checksum file that a resolution reads.
	 */
	private static final int MAXIMUM_CHECKSUMS_BYTES = 1_048_576;
	/**
	 * How long a download waits to connect to the release's server.
	 */
	private static final Duration CONNECT_LIMIT = Duration.ofSeconds(30);
	/**
	 * How long a download waits for the server's response headers, once connected.
	 */
	private static final Duration RESPONSE_LIMIT = Duration.ofSeconds(60);
	/**
	 * How long a download of one file may take in total.
	 */
	private static final Duration DOWNLOAD_LIMIT = Duration.ofMinutes(15);
	/**
	 * How long a bundle that no resolution uses is kept, in calendar months of UTC.
	 */
	private static final Period UNUSED_LIMIT = Period.ofMonths(3);
	/**
	 * The name of a downloaded artifact's directory, {@code cat-<harness>-v<version>-<platform>}, whose first group is
	 * the CAT version.
	 */
	private static final Pattern BUNDLE_NAME = Pattern.compile("cat-.+-v([^-]+)-(?:" +
		Arrays.stream(Platform.values()).map(platform -> Pattern.quote(platform.id())).
			collect(Collectors.joining("|")) + ")");

	/**
	 * Returns the location of this CAT version's release.
	 *
	 * @param environment the process environment
	 * @return {@code CAT_RELEASE_BASE_URL} without a trailing {@code /} when it is set and not blank, otherwise the
	 *   GitHub release of this CAT version
	 * @throws NullPointerException     if {@code environment} is null
	 * @throws IllegalArgumentException if {@code CAT_RELEASE_BASE_URL} is not a URI
	 */
	public static URI releaseUri(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		String configured = environment.get(RELEASE_VARIABLE);
		if (configured == null || configured.isBlank())
			return DEFAULT_RELEASE;
		String location = configured.strip();
		while (location.endsWith("/"))
			location = location.substring(0, location.length() - 1);
		return URI.create(location);
	}

	/**
	 * Returns the directory that holds the downloaded artifacts, in CAT's per-user directory.
	 *
	 * @param home the user's home directory
	 * @return {@code .cat/bundles} below {@code home}
	 * @throws NullPointerException if {@code home} is null
	 */
	public static Path directory(Path home)
	{
		requireThat(home, "home").isNotNull();
		return home.resolve(".cat/bundles");
	}

	private final Path directory;
	private final URI release;
	private final Platform platform;
	private final Duration connectLimit;
	private final Duration responseLimit;
	private final Duration downloadLimit;
	private final Clock clock;

	/**
	 * Creates a resolution.
	 *
	 * @param directory the directory that holds the downloaded artifacts, each in
	 *                  {@code cat-<harness>-v<version>-<platform>}
	 * @param release   the location of this CAT version's release: a {@code file}, {@code http} or {@code https} URI
	 *                  of the directory that holds its archives and {@code SHA256SUMS.txt}
	 * @param platform  the platform whose artifact to resolve
	 * @throws NullPointerException if any of the arguments are null
	 */
	public ReleaseBundles(Path directory, URI release, Platform platform)
	{
		this(directory, release, platform, CONNECT_LIMIT, RESPONSE_LIMIT, DOWNLOAD_LIMIT, Clock.systemUTC());
	}

	/**
	 * Creates a resolution with the given time limits for a download over HTTP and the given clock.
	 *
	 * @param directory     the directory that holds the downloaded artifacts
	 * @param release       the location of this CAT version's release
	 * @param platform      the platform whose artifact to resolve
	 * @param connectLimit  how long a download waits to connect to the release's server
	 * @param responseLimit how long a download waits for the server's response headers
	 * @param downloadLimit how long a download of one file may take in total
	 * @param clock         the clock that tells when a bundle was last used
	 * @throws NullPointerException if any of the arguments are null
	 */
	private ReleaseBundles(Path directory, URI release, Platform platform, Duration connectLimit,
		Duration responseLimit, Duration downloadLimit, Clock clock)
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(release, "release").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(connectLimit, "connectLimit").isNotNull();
		requireThat(responseLimit, "responseLimit").isNotNull();
		requireThat(downloadLimit, "downloadLimit").isNotNull();
		requireThat(clock, "clock").isNotNull();
		this.directory = directory.toAbsolutePath().normalize();
		this.release = release;
		this.platform = platform;
		this.connectLimit = connectLimit;
		this.responseLimit = responseLimit;
		this.downloadLimit = downloadLimit;
		this.clock = clock;
	}

	/**
	 * Resolves a harness's CAT artifact of this CAT version and platform, downloading it when neither the installed
	 * plugin nor an earlier download is usable. Before that, it deletes the bundles that were last used more than three
	 * calendar months earlier, except the bundle that it uses.
	 *
	 * @param installed the directory where the harness's CAT plugin is installed, which need not exist
	 * @param harness   the harness's name in release archive names, such as {@code codex}
	 * @param manifest  the harness's plugin manifest, relative to the artifact
	 * @param runner    the name of the harness's runner launcher in the artifact's {@code bin} directory, without the
	 *                  {@code .cmd} extension that it has on Windows
	 * @return the artifact
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} or {@code runner} are blank
	 * @throws IOException              if an earlier download is unusable, or the download, checksum verification,
	 *                                  extraction or the extracted artifact's verification fails
	 */
	public RunnerArtifact resolve(Path installed, String harness, Path manifest, String runner) throws IOException
	{
		requireThat(installed, "installed").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(manifest, "manifest").isNotNull();
		requireThat(runner, "runner").isNotBlank();

		RunnerArtifact plugin = findInstalled(installed, manifest, runner);
		String name = "cat-" + harness + "-v" + PluginInfo.CAT_VERSION + "-" + platform.id();
		Path bundle = directory.resolve(name);
		boolean usesBundle = plugin == null && Files.exists(bundle, NOFOLLOW_LINKS);
		Set<String> inUse;
		if (usesBundle)
		{
			markUsed(bundle);
			inUse = Set.of(name);
		}
		else
			inUse = Set.of();
		deleteUnusedBundles(inUse);

		if (plugin != null)
			return plugin;
		if (usesBundle)
			return verify(bundle, manifest, runner, RunnerArtifact.Source.BUNDLE);
		return download(name, manifest, runner);
	}

	/**
	 * Returns the installed plugin when it is a complete artifact of this CAT version that holds the runner.
	 *
	 * @param installed the installed plugin's directory
	 * @param manifest  the plugin manifest, relative to the artifact
	 * @param runner    the runner launcher's name
	 * @return the artifact, or {@code null} if the plugin is missing, incomplete or of another version
	 */
	private RunnerArtifact findInstalled(Path installed, Path manifest, String runner)
	{
		try
		{
			return verify(installed, manifest, runner, RunnerArtifact.Source.INSTALLED);
		}
		catch (IOException _)
		{
			return null;
		}
	}

	/**
	 * Verifies that a directory is a complete artifact of this CAT version and platform that holds the runner.
	 *
	 * @param artifact the artifact's directory
	 * @param manifest the plugin manifest, relative to the artifact
	 * @param runner   the runner launcher's name
	 * @param source   where the artifact came from
	 * @return the artifact
	 * @throws IOException if the directory is not such an artifact
	 */
	private RunnerArtifact verify(Path artifact, Path manifest, String runner, RunnerArtifact.Source source)
		throws IOException
	{
		Path runnerPath = HarnessArtifact.runnerLauncher(artifact, manifest, runner, platform);
		return new RunnerArtifact(runnerPath.getParent().getParent(), runnerPath, source);
	}

	/**
	 * Downloads, verifies and extracts a release archive into the bundle directory, and then deletes the bundles of
	 * other CAT versions.
	 *
	 * @param name     the archive's name without {@code .tar.gz}, which is also its top-level directory's name and
	 *                 the bundle's name
	 * @param manifest the plugin manifest, relative to the artifact
	 * @param runner   the runner launcher's name
	 * @return the artifact
	 * @throws IOException if the download, verification or extraction fails
	 */
	private RunnerArtifact download(String name, Path manifest, String runner) throws IOException
	{
		String archiveName = name + ".tar.gz";
		Files.createDirectories(directory);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// A sibling of the destination, so that the move into place is a rename on one file system
			Path temporary = cleanup.add(Files.createTempDirectory(directory, "." + name + "-"));
			Path archive = temporary.resolve(archiveName);
			fetch(archiveName, archive);
			String digest = sha256(archive);
			Path checksums = temporary.resolve(CHECKSUMS);
			fetch(CHECKSUMS, checksums);
			String expected = publishedChecksum(checksums, archiveName);
			if (!expected.equalsIgnoreCase(digest))
			{
				throw new IOException("The checksum of " + archiveName + " does not match " + CHECKSUMS + " of " +
					release + ": expected " + expected + ", got " + digest);
			}

			Path extracted = Files.createDirectory(temporary.resolve("extracted"));
			try (InputStream in = Files.newInputStream(archive))
			{
				TarGzExtractor.extract(in, extracted, name);
			}
			catch (IOException e)
			{
				throw new IOException("Cannot extract " + archiveName + ": " + e.getMessage(), e);
			}
			Path artifact = extracted.resolve(name);
			if (!Files.isDirectory(artifact, NOFOLLOW_LINKS))
				throw new IOException(archiveName + " does not hold the directory " + name);
			verify(artifact, manifest, runner, RunnerArtifact.Source.DOWNLOAD);
			RunnerArtifact result = moveIntoPlace(artifact, directory.resolve(name), manifest, runner);
			markUsed(result.artifact());
			deleteOtherVersions();
			return result;
		}
	}

	/**
	 * Records that a bundle is used now, so that its age restarts. A time that cannot be recorded is left as it was,
	 * because the resolution that uses the bundle keeps it and a later use records the time again.
	 *
	 * @param bundle the bundle's directory
	 */
	private void markUsed(Path bundle)
	{
		try
		{
			Files.setLastModifiedTime(bundle, FileTime.from(clock.instant()));
		}
		catch (IOException _)
		{
			// Retried by the next use
		}
	}

	/**
	 * Deletes the bundles, of any CAT version, harness and platform, that were last used more than three calendar
	 * months earlier. Hidden entries, such as the temporary directory of a download that is under way, and entries that
	 * are not bundles are kept. A bundle that cannot be deleted is left for the next resolution.
	 *
	 * @param inUse the names of the bundles that this resolution uses, which are kept
	 */
	private void deleteUnusedBundles(Set<String> inUse)
	{
		Instant oldestKept = clock.instant().atZone(ZoneOffset.UTC).minus(UNUSED_LIMIT).toInstant();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
		{
			for (Path entry : entries)
			{
				String name = entry.getFileName().toString();
				if (!BUNDLE_NAME.matcher(name).matches() || inUse.contains(name))
					continue;
				try
				{
					Instant lastUsed = Files.getLastModifiedTime(entry, NOFOLLOW_LINKS).toInstant();
					if (lastUsed.isBefore(oldestKept))
						TemporaryDirectories.deleteRecursively(entry);
				}
				catch (IOException _)
				{
					// Retried by the next resolution
				}
			}
		}
		catch (IOException _)
		{
			// The directory is missing or unreadable; retried by the next resolution
		}
	}

	/**
	 * Deletes the bundles of CAT versions other than this one, of any harness and platform. Hidden entries, such as
	 * the temporary directory of a download that is under way, and entries that are not bundles are kept. A bundle
	 * that cannot be deleted is left for the next download, because a stale copy does not affect the resolution.
	 */
	private void deleteOtherVersions()
	{
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
		{
			for (Path entry : entries)
			{
				Matcher matcher = BUNDLE_NAME.matcher(entry.getFileName().toString());
				if (!matcher.matches() || matcher.group(1).equals(PluginInfo.CAT_VERSION))
					continue;
				try
				{
					TemporaryDirectories.deleteRecursively(entry);
				}
				catch (IOException _)
				{
					// Retried by the next download
				}
			}
		}
		catch (IOException _)
		{
			// Retried by the next download
		}
	}

	/**
	 * Copies a release file to a local file.
	 *
	 * @param name   the file's name in the release
	 * @param target the local file, which must not exist
	 * @throws IOException if the file cannot be fetched
	 */
	private void fetch(String name, Path target) throws IOException
	{
		URI uri = URI.create(release + "/" + name);
		try
		{
			String scheme = uri.getScheme();
			if (scheme == null)
				throw new IOException("The release location names no scheme");
			switch (scheme)
			{
				case "file" -> Files.copy(Path.of(uri), target);
				case "http", "https" -> fetchHttp(uri, target);
				default -> throw new IOException("Unsupported release location scheme: " + scheme);
			}
		}
		catch (IOException e)
		{
			throw new IOException("Cannot download " + uri + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Copies a release file over HTTP, following the redirects that GitHub answers a release download with, within the
	 * time limits of this resolution.
	 *
	 * @param uri    the file's location
	 * @param target the local file
	 * @throws IOException if the request fails, its status is not 200, or a time limit expires
	 */
	private void fetchHttp(URI uri, Path target) throws IOException
	{
		try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).
			connectTimeout(connectLimit).build())
		{
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(responseLimit).GET().build();
			CompletableFuture<HttpResponse<Path>> future = client.sendAsync(request,
				HttpResponse.BodyHandlers.ofFile(target));
			HttpResponse<Path> response;
			try
			{
				response = future.get(downloadLimit.toNanos(), TimeUnit.NANOSECONDS);
			}
			catch (TimeoutException e)
			{
				future.cancel(true);
				client.shutdownNow();
				throw new IOException("The download did not finish within " + describe(downloadLimit), e);
			}
			catch (ExecutionException e)
			{
				client.shutdownNow();
				Throwable cause = e.getCause();
				if (cause instanceof HttpConnectTimeoutException)
					throw new IOException("No connection within " + describe(connectLimit), cause);
				if (cause instanceof HttpTimeoutException)
					throw new IOException("No response within " + describe(responseLimit), cause);
				if (cause instanceof IOException ioe)
					throw ioe;
				throw new IOException(String.valueOf(cause), cause);
			}
			if (response.statusCode() != 200)
				throw new IOException("HTTP status " + response.statusCode());
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	/**
	 * Describes a time limit for an error message.
	 *
	 * @param limit the limit
	 * @return the limit in the largest of minutes, seconds and milliseconds that holds it exactly
	 */
	private static String describe(Duration limit)
	{
		long milliseconds = limit.toMillis();
		if (milliseconds % 60_000 == 0)
			return plural(milliseconds / 60_000, "minute");
		if (milliseconds % 1_000 == 0)
			return plural(milliseconds / 1_000, "second");
		return plural(milliseconds, "millisecond");
	}

	/**
	 * Returns a count and a unit.
	 *
	 * @param count the count
	 * @param unit  the unit's singular name
	 * @return the count and the unit, in the plural unless the count is 1
	 */
	private static String plural(long count, String unit)
	{
		if (count == 1)
			return count + " " + unit;
		return count + " " + unit + "s";
	}

	/**
	 * Returns the checksum that a release's checksum file publishes for an archive.
	 *
	 * @param checksums   the checksum file, whose lines hold a digest, whitespace and a file name that may start with
	 *                    {@code *}
	 * @param archiveName the archive's name
	 * @return the digest
	 * @throws IOException if the file cannot be read or publishes no checksum for the archive
	 */
	private String publishedChecksum(Path checksums, String archiveName) throws IOException
	{
		String content = BoundedInput.readUtf8(checksums, MAXIMUM_CHECKSUMS_BYTES, CHECKSUMS);
		for (String line : content.lines().toList())
		{
			String[] fields = line.strip().split("\\s+", 2);
			if (fields.length == 2 && (fields[1].equals(archiveName) || fields[1].equals("*" + archiveName)))
				return fields[0];
		}
		throw new IOException("No checksum published for " + archiveName + " in " + CHECKSUMS + " of " + release);
	}

	/**
	 * Moves a verified artifact to its bundle directory. When another resolution moved its own copy there first, that
	 * copy is used instead.
	 *
	 * @param artifact the verified artifact
	 * @param bundle   the bundle directory
	 * @param manifest the plugin manifest, relative to the artifact
	 * @param runner   the runner launcher's name
	 * @return the artifact in the bundle directory
	 * @throws IOException if the artifact cannot be moved
	 */
	private RunnerArtifact moveIntoPlace(Path artifact, Path bundle, Path manifest, String runner)
		throws IOException
	{
		try
		{
			Files.move(artifact, bundle, ATOMIC_MOVE);
		}
		catch (IOException e)
		{
			if (Files.notExists(bundle, NOFOLLOW_LINKS))
				throw e;
			return verify(bundle, manifest, runner, RunnerArtifact.Source.BUNDLE);
		}
		return verify(bundle, manifest, runner, RunnerArtifact.Source.DOWNLOAD);
	}

	/**
	 * Returns the SHA-256 digest of a file.
	 *
	 * @param file the file
	 * @return the digest, in lower-case hexadecimal
	 * @throws IOException if the file cannot be read
	 */
	private static String sha256(Path file) throws IOException
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java implementation must provide SHA-256", e);
		}
		try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest))
		{
			in.transferTo(OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
