package com.holdmyspot.cat.common.release;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import com.holdmyspot.cat.common.runner.InstalledRuntime;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.secrets.ReleaseBundlesAccess;
import com.holdmyspot.cat.common.secrets.ReleaseBundlesSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves another harness's CAT artifact of this CAT version and platform, so that a runner launcher can run a task
 * in that harness with CAT installed although the user installed CAT only for its own harness.
 * <p>
 * The artifact is the first of: the harness's installed CAT plugin when its version matches; the copy that an earlier
 * download left in the bundle directory, when it is usable; otherwise a copy that this class downloads from the
 * release of this CAT version. The bundle directory is a cache: an earlier copy that is unusable, such as one that
 * lacks a launcher or its runtime requirement, is deleted and downloaded again, and only a failure of that download
 * is reported. A download fetches the harness's release archive and the release's {@code SHA256SUMS.txt}, verifies the
 * archive's checksum, extracts it into a temporary directory beside its destination, verifies the extracted artifact
 * and only then moves it into place, so that a failed download, checksum or extraction leaves no copy for a later
 * resolution to use.
 * <p>
 * Before a resolution uses a downloaded or earlier-downloaded bundle, it checks that the installation's Java runtime it
 * was given satisfies the bundle's {@code client/runtime-requirement.json} and refuses the bundle otherwise. It then
 * binds the bundle to that runtime unless the bundle's runtime binding already names it and is usable, so a bundle that
 * another installation bound to its own runtime, or to a runtime that was since removed, is rebound rather than
 * refused. It also removes any startup cache that the bundle holds, because Java accepts a startup cache only at the
 * location that it was trained at. An installed plugin keeps the binding and startup cache of its own installation.
 * <p>
 * A download over HTTP waits at most 30 seconds to connect, 60 seconds for the server's response headers and 15 minutes
 * for the whole file, and reports the limit that expired. It goes through the HTTP proxy that the process environment
 * names in {@code HTTPS_PROXY}, {@code HTTP_PROXY} or {@code ALL_PROXY}, with the login of the proxy's URL, unless
 * {@code NO_PROXY} lists the release's host. After a download, the bundles of other CAT versions, of any harness and
 * platform, are deleted from the bundle directory.
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
			public ReleaseBundles create(Path directory, URI release, Platform platform, Path runtime,
				Duration connectLimit, Duration responseLimit, Duration downloadLimit, Clock clock)
			{
				return new ReleaseBundles(directory, release, platform, Map.of(), runtime, connectLimit, responseLimit,
					downloadLimit, clock);
			}

			@Override
			public HttpClient newHttpClient(ReleaseBundles bundles, URI location) throws IOException
			{
				return bundles.newHttpClient(location);
			}

			@Override
			public HttpRequest newHttpRequest(ReleaseBundles bundles, URI location)
			{
				return bundles.newHttpRequest(location);
			}

			@Override
			public IOException httpFailure(ReleaseBundles bundles, HttpClient client, Throwable failure)
			{
				return bundles.httpFailure(client, failure);
			}

			@Override
			public void checkStatus(int statusCode) throws IOException
			{
				ReleaseBundles.checkStatus(statusCode);
			}

			@Override
			public IOException fetchFailure(URI location, IOException failure)
			{
				return ReleaseBundles.fetchFailure(location, failure);
			}
		});
	}

	/**
	 * The release of this CAT version on GitHub, which holds every harness's archive for every platform.
	 */
	private static final URI RELEASE = URI.create(PluginInfo.CAT_REPOSITORY + "/releases/download/v" +
		PluginInfo.CAT_VERSION);
	/**
	 * The release's checksum file.
	 */
	private static final String CHECKSUMS = "SHA256SUMS.txt";
	/**
	 * The file in an artifact that names the oldest Java feature release and the modules that it needs.
	 */
	private static final Path REQUIREMENT = Path.of("client/runtime-requirement.json");
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
	 * Returns the fixed GitHub release location of this CAT version.
	 *
	 * @return the GitHub release of this CAT version
	 */
	public static URI releaseUri()
	{
		return RELEASE;
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
	private final Map<String, String> environment;
	private final Path runtime;
	private final Duration connectLimit;
	private final Duration responseLimit;
	private final Duration downloadLimit;
	private final Clock clock;

	/**
	 * Creates a resolution.
	 *
	 * @param directory   the directory that holds the downloaded artifacts, each in
	 *                    {@code cat-<harness>-v<version>-<platform>}
	 * @param release     the location of this CAT version's release: a {@code file}, {@code http} or {@code https}
	 *                    URI of the directory that holds its archives and {@code SHA256SUMS.txt}
	 * @param platform    the platform whose artifact to resolve
	 * @param environment the process environment, whose proxy variables a download over HTTP follows, as
	 *                    {@link Platform#environment(Map)} returns it
	 * @param runtime     the installation's Java runtime that this resolution binds a bundle to, which is the
	 *                    runtime of the running JVM in production
	 * @throws NullPointerException if any of the arguments are null
	 */
	public ReleaseBundles(Path directory, URI release, Platform platform, Map<String, String> environment,
		Path runtime)
	{
		this(directory, release, platform, environment, runtime, CONNECT_LIMIT, RESPONSE_LIMIT, DOWNLOAD_LIMIT,
			Clock.systemUTC());
	}

	/**
	 * Creates a resolution with the given time limits for a download over HTTP and the given clock.
	 *
	 * @param directory     the directory that holds the downloaded artifacts
	 * @param release       the location of this CAT version's release
	 * @param platform      the platform whose artifact to resolve
	 * @param environment   the process environment, whose proxy variables a download over HTTP follows
	 * @param runtime       the installation's Java runtime that this resolution binds a bundle to
	 * @param connectLimit  how long a download waits to connect to the release's server
	 * @param responseLimit how long a download waits for the server's response headers
	 * @param downloadLimit how long a download of one file may take in total
	 * @param clock         the clock that tells when a bundle was last used
	 * @throws NullPointerException if any of the arguments are null
	 */
	private ReleaseBundles(Path directory, URI release, Platform platform, Map<String, String> environment,
		Path runtime, Duration connectLimit, Duration responseLimit, Duration downloadLimit, Clock clock)
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(release, "release").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(runtime, "runtime").isNotNull();
		requireThat(connectLimit, "connectLimit").isNotNull();
		requireThat(responseLimit, "responseLimit").isNotNull();
		requireThat(downloadLimit, "downloadLimit").isNotNull();
		requireThat(clock, "clock").isNotNull();
		this.directory = directory.toAbsolutePath().normalize();
		this.release = release;
		this.platform = platform;
		this.environment = environment;
		this.runtime = runtime.toAbsolutePath().normalize();
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
	 * @throws IOException              if an unusable earlier download cannot be deleted, or the download, checksum
	 *                                  verification, extraction or the extracted artifact's verification fails
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
		{
			RunnerArtifact reused = reuse(bundle, manifest, runner);
			if (reused != null)
				return reused;
		}
		return download(name, manifest, runner);
	}

	/**
	 * Returns an earlier download after binding it to the runtime, or deletes it when it is unusable so that it can be
	 * downloaded again.
	 *
	 * @param bundle   the bundle's directory
	 * @param manifest the plugin manifest, relative to the artifact
	 * @param runner   the runner launcher's name
	 * @return the artifact, or {@code null} if the bundle was unusable and has been deleted
	 * @throws IOException if an unusable bundle cannot be deleted
	 */
	private RunnerArtifact reuse(Path bundle, Path manifest, String runner) throws IOException
	{
		try
		{
			bind(bundle);
			return verify(bundle, manifest, runner, RunnerArtifact.Source.BUNDLE);
		}
		catch (IOException unusable)
		{
			try
			{
				TemporaryDirectories.deleteRecursively(bundle);
			}
			catch (IOException e)
			{
				e.addSuppressed(unusable);
				throw new IOException("Cannot delete the unusable bundle " + bundle + " to download it again: " +
					e.getMessage(), e);
			}
			return null;
		}
	}

	/**
	 * Binds a bundle to the installation's Java runtime of this resolution after checking that the runtime satisfies the
	 * bundle's requirement, and removes the bundle's startup cache. The binding is rewritten only when it does not
	 * already name that runtime usably.
	 *
	 * @param bundle the bundle's directory
	 * @throws IOException if the runtime is not a CAT runtime, does not satisfy the bundle's requirement, or the
	 *                     binding or startup cache cannot be written or removed
	 */
	private void bind(Path bundle) throws IOException
	{
		InstalledRuntime.requireSatisfies(runtime, bundle.resolve(REQUIREMENT));
		TemporaryDirectories.deleteRecursively(bundle.resolve(StartupCache.DIRECTORY));
		Path bound;
		try
		{
			bound = RuntimeBinding.readUsable(bundle, platform);
		}
		catch (IOException _)
		{
			bound = null;
		}
		if (!runtime.equals(bound))
			RuntimeBinding.write(bundle, runtime);
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
			bind(artifact);
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
			throw fetchFailure(uri, e);
		}
	}

	/**
	 * Returns the failure that a download of a release file reports.
	 *
	 * @param location the file's location
	 * @param failure  why the file could not be fetched
	 * @return a failure that names the location and why it failed, caused by {@code failure}
	 */
	private static IOException fetchFailure(URI location, IOException failure)
	{
		return new IOException("Cannot download " + location + ": " + describeFailure(failure), failure);
	}

	/**
	 * Copies a release file over HTTP, following the redirects that GitHub answers a release download with, within the
	 * time limits of this resolution. The request and its redirects go through the proxy that the environment names for
	 * the file's location, if any, which gets the login of its URL when it asks for one. Over an HTTPS tunnel, the JDK
	 * sends a Basic login only if {@code jdk.http.auth.tunneling.disabledSchemes} does not list {@code Basic}, as
	 * CAT's launchers configure it.
	 *
	 * @param uri    the file's location
	 * @param target the local file
	 * @throws IOException if the environment names a proxy URL that cannot be parsed, the request fails, its status is
	 *                     not 200, or a time limit expires
	 */
	private void fetchHttp(URI uri, Path target) throws IOException
	{
		try (HttpClient client = newHttpClient(uri))
		{
			CompletableFuture<HttpResponse<Path>> future = client.sendAsync(newHttpRequest(uri),
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
				throw httpFailure(client, e);
			}
			catch (ExecutionException e)
			{
				client.shutdownNow();
				throw httpFailure(client, e.getCause());
			}
			checkStatus(response.statusCode());
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	/**
	 * Returns the HTTP client that downloads a release file: it follows the redirects that GitHub answers a release
	 * download with, waits at most the connect limit of this resolution to connect, and goes through the proxy that the
	 * environment names for the file's location, if any, answering that proxy's login challenge with the login of its
	 * URL.
	 *
	 * @param location the file's location
	 * @return the client
	 * @throws IOException if the environment names a proxy URL that cannot be parsed
	 */
	private HttpClient newHttpClient(URI location) throws IOException
	{
		HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).
			connectTimeout(connectLimit);
		EnvironmentProxy proxy = findProxy(location);
		if (proxy != null)
		{
			builder.proxy(ProxySelector.of(proxy.address()));
			if (proxy.user() != null)
				builder.authenticator(new ProxyLogin(proxy));
		}
		return builder.build();
	}

	/**
	 * Returns the request that downloads a release file, which waits at most the response limit of this resolution for
	 * the server's response headers.
	 *
	 * @param location the file's location
	 * @return the request
	 */
	private HttpRequest newHttpRequest(URI location)
	{
		return HttpRequest.newBuilder(location).timeout(responseLimit).GET().build();
	}

	/**
	 * Returns the failure that a download over HTTP reports, naming the time limit that expired or the login that was
	 * refused.
	 *
	 * @param client  the client of the download, whose login, if any, recorded the challenges it received
	 * @param failure the {@link TimeoutException} of a download that did not finish within the download limit, or the
	 *                cause of a request that failed
	 * @return the failure to report
	 */
	private IOException httpFailure(HttpClient client, Throwable failure)
	{
		if (failure instanceof TimeoutException)
			return new IOException("The download did not finish within " + describe(downloadLimit), failure);
		if (failure instanceof HttpConnectTimeoutException)
			return new IOException("No connection within " + describe(connectLimit), failure);
		if (failure instanceof HttpTimeoutException)
			return new IOException("No response within " + describe(responseLimit), failure);
		if (client.authenticator().orElse(null) instanceof ProxyLogin login)
		{
			if (login.wasRejected())
			{
				return new IOException("The proxy that " + login.proxyVariable() +
					" names rejected the login of its URL", failure);
			}
			if (login.serverAskedForLogin())
			{
				return new IOException("The release's server asks for a login, which a download does not send",
					failure);
			}
		}
		if (failure instanceof IOException ioe)
			return ioe;
		return new IOException(String.valueOf(failure), failure);
	}

	/**
	 * Checks the status of a release file's response.
	 *
	 * @param statusCode the response's status code
	 * @throws IOException if the status is not 200
	 */
	private static void checkStatus(int statusCode) throws IOException
	{
		if (statusCode != 200)
			throw new IOException("HTTP status " + statusCode);
	}

	/**
	 * Finds the proxy that the environment names for a download, as the common convention of command-line tools such
	 * as curl reads it. The JDK's HTTP client does not read these variables, but a host that reaches the network only
	 * through a proxy names it there.
	 * <p>
	 * A location whose host {@code NO_PROXY}, or else {@code no_proxy}, lists is fetched directly. Otherwise the proxy
	 * is the first usable one of {@code HTTPS_PROXY}, {@code https_proxy}, {@code ALL_PROXY} and {@code all_proxy} for
	 * an {@code https} location, or of {@code HTTP_PROXY}, {@code http_proxy}, {@code ALL_PROXY} and
	 * {@code all_proxy} for an {@code http} location. A proxy is an {@code http} URL, or a host and port without a
	 * scheme; its port defaults to 80. A variable that names a proxy of another scheme, such as {@code socks5}, which
	 * the JDK's HTTP client cannot use, is passed over.
	 *
	 * @param location the location of the file to download
	 * @return the proxy, or {@code null} if the download connects directly
	 * @throws IOException if the first variable that names a proxy does not hold a URL that can be parsed
	 */
	private EnvironmentProxy findProxy(URI location) throws IOException
	{
		String host = location.getHost();
		if (host == null || isExcludedFromProxy(host))
			return null;
		List<String> variables;
		if (location.getScheme().equals("https"))
			variables = List.of("HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy");
		else
			variables = List.of("HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy");
		for (String variable : variables)
		{
			String value = environment.get(variable);
			if (value == null || value.isBlank())
				continue;
			EnvironmentProxy proxy = EnvironmentProxy.parse(variable, value.strip());
			if (proxy != null)
				return proxy;
		}
		return null;
	}

	/**
	 * Returns whether {@code NO_PROXY}, or else {@code no_proxy}, lists a host: a comma-separated entry that is
	 * {@code *}, the host itself, or a domain that holds it, with or without a leading dot. Names match regardless of
	 * case.
	 *
	 * @param host the host's name
	 * @return {@code true} if the host is fetched without a proxy
	 */
	private boolean isExcludedFromProxy(String host)
	{
		String excluded = environment.get("NO_PROXY");
		if (excluded == null || excluded.isBlank())
			excluded = environment.get("no_proxy");
		if (excluded == null || excluded.isBlank())
			return false;
		String name = host.toLowerCase(Locale.ROOT);
		for (String entry : excluded.split(","))
		{
			String domain = entry.strip().toLowerCase(Locale.ROOT);
			if (domain.equals("*"))
				return true;
			if (domain.startsWith("."))
				domain = domain.substring(1);
			if (domain.isEmpty())
				continue;
			if (name.equals(domain) || name.endsWith("." + domain))
				return true;
		}
		return false;
	}

	/**
	 * An HTTP proxy that an environment variable names, with the login of its URL, if any.
	 *
	 * @param variable the name of the variable
	 * @param address  the proxy's address
	 * @param user     the user name of the proxy's URL, percent-decoded, or {@code null} if the URL names no login
	 * @param password the password of the proxy's URL, percent-decoded, which is empty if the URL names a user without
	 *                 a password; {@code null} if the URL names no login
	 */
	private record EnvironmentProxy(String variable, InetSocketAddress address, String user, String password)
	{
		/**
		 * Returns the proxy that an environment variable names. Its messages do not quote the variable's value,
		 * because the value may hold the proxy's password.
		 *
		 * @param variable the variable's name
		 * @param value    the variable's value, without surrounding whitespace
		 * @return the proxy, or {@code null} if the value names a proxy of a scheme other than {@code http}
		 * @throws IOException if the value is not a URL with a host and a valid port
		 */
		private static EnvironmentProxy parse(String variable, String value) throws IOException
		{
			String url = value;
			if (!url.contains("://"))
				url = "http://" + url;
			URI proxy;
			try
			{
				proxy = new URI(url);
			}
			catch (URISyntaxException _)
			{
				// The cause's message quotes the value, which may hold the proxy's password
				throw new IOException(variable + " does not hold a proxy URL that can be parsed");
			}
			if (!proxy.getScheme().equalsIgnoreCase("http"))
				return null;
			String host = proxy.getHost();
			if (host == null)
				throw new IOException(variable + " does not hold a proxy URL that names a host");
			int port = proxy.getPort();
			if (port == -1)
				port = 80;
			if (port > 65_535)
				throw new IOException(variable + " names a proxy port above 65535");
			InetSocketAddress address = new InetSocketAddress(host, port);

			String login = proxy.getRawUserInfo();
			if (login == null)
				return new EnvironmentProxy(variable, address, null, null);
			int separator = login.indexOf(':');
			String user;
			String password;
			if (separator == -1)
			{
				user = login;
				password = "";
			}
			else
			{
				user = login.substring(0, separator);
				password = login.substring(separator + 1);
			}
			return new EnvironmentProxy(variable, address, percentDecode(variable, user),
				percentDecode(variable, password));
		}

		/**
		 * Decodes the percent-encoded octets of a part of a URL's login as UTF-8. Unlike form decoding, a {@code +}
		 * stays a {@code +}.
		 *
		 * @param variable the name of the variable that holds the URL
		 * @param encoded  the part of the login
		 * @return the decoded text
		 * @throws IOException if a {@code %} is not followed by two hexadecimal digits
		 */
		private static String percentDecode(String variable, String encoded) throws IOException
		{
			try
			{
				return URLDecoder.decode(encoded.replace("+", "%2B"), UTF_8);
			}
			catch (IllegalArgumentException _)
			{
				// The cause's message quotes the text, which may be the proxy's password
				throw new IOException(variable + " holds a proxy login that is not percent-encoded correctly");
			}
		}

		@Override
		public String toString()
		{
			// The default form would print the login, which a failure message or a log could then reveal
			return "EnvironmentProxy[variable=" + variable + ", address=" + address + "]";
		}
	}

	/**
	 * Answers a login challenge from the proxy of one download with the login of the proxy's URL, and records the
	 * challenges it receives so that a failed download can say why. A challenge from a release server, or from any
	 * other host, gets no login, so that the proxy's password reaches only the proxy.
	 */
	private static final class ProxyLogin extends Authenticator
	{
		private final EnvironmentProxy proxy;
		private final AtomicInteger proxyChallenges = new AtomicInteger();
		private final AtomicBoolean serverChallenged = new AtomicBoolean();

		/**
		 * Creates a login for a proxy.
		 *
		 * @param proxy the proxy, whose URL names a login
		 */
		private ProxyLogin(EnvironmentProxy proxy)
		{
			this.proxy = proxy;
		}

		@Override
		protected PasswordAuthentication getPasswordAuthentication()
		{
			InetSocketAddress address = proxy.address();
			boolean fromThisProxy = getRequestorType() == RequestorType.PROXY &&
				address.getHostString().equalsIgnoreCase(getRequestingHost()) &&
				address.getPort() == getRequestingPort();
			if (!fromThisProxy)
			{
				serverChallenged.set(true);
				return null;
			}
			proxyChallenges.incrementAndGet();
			return new PasswordAuthentication(proxy.user(), proxy.password().toCharArray());
		}

		/**
		 * Returns the name of the environment variable that names the proxy.
		 *
		 * @return the variable's name
		 */
		private String proxyVariable()
		{
			return proxy.variable();
		}

		/**
		 * Returns whether the proxy challenged the download again after it received the login.
		 *
		 * @return {@code true} if the proxy rejected the login
		 */
		private boolean wasRejected()
		{
			return proxyChallenges.get() > 1;
		}

		/**
		 * Returns whether a host other than the proxy asked for a login.
		 *
		 * @return {@code true} if a release server asked for a login
		 */
		private boolean serverAskedForLogin()
		{
			return serverChallenged.get();
		}
	}

	/**
	 * Describes why a download failed, for an error message. Some exceptions, such as the connection failure of an
	 * unresolved or blocked host, carry no message.
	 *
	 * @param failure the failure
	 * @return the failure's message, otherwise the first message of its causes, otherwise a description of its kind
	 */
	private static String describeFailure(Throwable failure)
	{
		for (Throwable current = failure; current != null; current = current.getCause())
		{
			String message = current.getMessage();
			if (message != null && !message.isBlank())
				return message;
		}
		for (Throwable current = failure; current != null; current = current.getCause())
		{
			if (current instanceof UnresolvedAddressException || current instanceof UnknownHostException)
				return "The host name could not be resolved";
		}
		if (failure instanceof ConnectException)
			return "The connection was refused or blocked";
		return "Failed with " + failure.getClass().getName();
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
			bind(bundle);
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
