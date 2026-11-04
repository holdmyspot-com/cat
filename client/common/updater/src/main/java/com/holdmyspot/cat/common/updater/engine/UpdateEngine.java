package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.release.TarGzExtractor;
import com.holdmyspot.cat.common.runner.RuntimeDescription;
import com.holdmyspot.cat.common.runner.RuntimeRequirement;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceOwnedFiles;
import com.holdmyspot.cat.common.updater.engine.ActivationTransaction.Completion;
import com.holdmyspot.cat.common.updater.engine.RuntimeSwap.Operation;
import com.holdmyspot.cat.common.updater.engine.SwapFiles.Recorded;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.ManifestVerifier;
import com.holdmyspot.cat.common.updater.trust.ReleaseApproval;
import com.holdmyspot.cat.common.updater.trust.ReleaseAsset;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
import com.holdmyspot.cat.common.updater.trust.VerifiedManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Coordinates authenticated plugin installs and updates without executing staged code. Harness configuration changes
 * only through the harness integration's registration. Once an install, update or setup has activated a plugin
 * directory, its session-start program runs to write the directory's startup cache.
 */
public final class UpdateEngine
{
	/**
	 * The release selector with which install names the latest approved release.
	 */
	public static final String LATEST = "latest";
	/**
	 * The plugin-relative file that names the Java runtime the plugin runs on.
	 */
	private static final Path BINDING_FILE = Path.of("client/runtime-binding");
	/**
	 * The permissions of a plugin's runtime binding where the file system records them.
	 */
	private static final Set<PosixFilePermission> BINDING_MODE = PosixFilePermissions.fromString("rw-r--r--");
	private final UpdaterState state;
	private final Path data;
	private final Path runtime;
	private final Bundle bundle;
	private final SwapFiles swaps;
	private final TrustPolicy policy;
	private final ReleaseSource source;
	private final PluginActivation activation;
	private final String platform;
	private final Clock clock;
	private final Waiting waiting;
	private final FileReplacer replacer;
	private final Duration lockWait;
	private final StartupCacheTrainer trainer;

	/**
	 * Creates an engine with a ten-minute lock wait.
	 *
	 * @param dataDirectory protected updater data directory, which contains the installation's Java runtime
	 * @param policy        protected policy
	 * @param source        release transport
	 * @param activation    the selected harness's activation provider
	 * @param platform      the release platform identifier
	 * @param clock         operation time source
	 * @param waiting       creates pauses between lock attempts
	 */
	public UpdateEngine(Path dataDirectory, TrustPolicy policy, ReleaseSource source, PluginActivation activation,
		String platform, Clock clock, Waiting waiting)
	{
		this(dataDirectory, policy, source, activation, platform, clock, waiting, Duration.ofMinutes(10));
	}

	/**
	 * Creates an engine with an explicit lock wait.
	 *
	 * @param dataDirectory protected updater data directory, which contains the installation's Java runtime
	 * @param policy        protected policy
	 * @param source        release transport
	 * @param activation    the selected harness's activation provider
	 * @param platform      the release platform identifier
	 * @param clock         operation time source
	 * @param waiting       creates pauses between lock attempts
	 * @param lockWait      nonnegative maximum wait for the harness lock
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code platform} is blank or {@code lockWait} is negative
	 */
	public UpdateEngine(Path dataDirectory, TrustPolicy policy, ReleaseSource source, PluginActivation activation,
		String platform, Clock clock, Waiting waiting, Duration lockWait)
	{
		this(dataDirectory, policy, source, activation, platform, clock, waiting, lockWait,
			ProcessElevation::isElevated);
	}

	/**
	 * Creates an engine with an explicit lock wait and check of administrative rights, which decides whether startup
	 * cache training runs.
	 *
	 * @param dataDirectory protected updater data directory, which contains the installation's Java runtime
	 * @param policy        protected policy
	 * @param source        release transport
	 * @param activation    the selected harness's activation provider
	 * @param platform      the release platform identifier
	 * @param clock         operation time source
	 * @param waiting       creates pauses between lock attempts
	 * @param lockWait      nonnegative maximum wait for the harness lock
	 * @param elevation     returns whether this process runs with administrative rights, given a directory that the
	 *                      check may write short-lived files to
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code platform} is blank or {@code lockWait} is negative
	 */
	public UpdateEngine(Path dataDirectory, TrustPolicy policy, ReleaseSource source, PluginActivation activation,
		String platform, Clock clock, Waiting waiting, Duration lockWait, Predicate<Path> elevation)
	{
		requireThat(dataDirectory, "dataDirectory").isNotNull();
		requireThat(policy, "policy").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(activation, "activation").isNotNull();
		requireThat(platform, "platform").isNotBlank();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(lockWait, "lockWait").isGreaterThanOrEqualTo(Duration.ZERO);
		requireThat(elevation, "elevation").isNotNull();
		this.state = UpdaterState.of(dataDirectory, activation.harness());
		this.data = dataDirectory.toAbsolutePath().normalize();
		boolean windows = Arrays.stream(Platform.values()).
			anyMatch(candidate -> candidate.id().equals(platform) && candidate.isWindows());
		this.replacer = FileReplacer.retrying(waiting);
		this.bundle = new Bundle(data, windows, replacer);
		this.runtime = bundle.runtime();
		this.swaps = new SwapFiles(data);
		this.policy = policy;
		this.source = source;
		this.activation = activation;
		this.platform = platform;
		this.clock = clock;
		this.waiting = waiting;
		this.lockWait = lockWait;
		Path trainingScratch = state.directory().resolve("startup-cache");
		this.trainer = new StartupCacheTrainer(trainingScratch, Duration.ofMinutes(1),
			() -> elevation.test(trainingScratch));
	}

	/**
	 * Updates the harness's plugin to an approved release, or registers the current plugin again when it is already
	 * that release. Refuses when the harness has no plugin.
	 *
	 * @param requestedTag the canonical v-prefixed tag, or null for the latest release
	 * @param session registers the activated plugin with the harness
	 * @return the outcome
	 * @throws NullPointerException if {@code session} is null
	 */
	public UpdateResult update(String requestedTag, HarnessSession session)
	{
		requireThat(session, "session").isNotNull();
		return operate(Mode.UPDATE, requestedTag, null, session);
	}

	/**
	 * Adds the release that the installation runs where the harness has no plugin and registers it, or installs it
	 * again over the harness's plugin. A partial installation that the harness does not report is deleted first.
	 * Refuses any other release, because only update and setup move the installation to another release.
	 *
	 * @param requestedTag the canonical v-prefixed tag, {@link #LATEST} for the latest release, or null for the release
	 *                     that the installation runs
	 * @param session registers the activated plugin with the harness
	 * @return the outcome
	 * @throws NullPointerException if {@code session} is null
	 */
	public UpdateResult install(String requestedTag, HarnessSession session)
	{
		requireThat(session, "session").isNotNull();
		return operate(Mode.INSTALL, requestedTag, null, session);
	}

	/**
	 * Activates the signed release of one version for the user's setup, also when the harness already has that
	 * version, and registers it. Setup may install a version older than the active one.
	 *
	 * @param version      the canonical version that setup comes from
	 * @param session registers the activated plugin with the harness
	 * @return the outcome
	 * @throws NullPointerException if any of the arguments are null
	 */
	public UpdateResult setUp(String version, HarnessSession session)
	{
		requireThat(version, "version").isNotBlank();
		requireThat(session, "session").isNotNull();
		return operate(Mode.SETUP, "v" + version, null, session);
	}

	/**
	 * Activates a local plugin tree that the user named for setup, in place of a signed release. The tree is validated,
	 * checked against the runtime and bound to it like a signed release, and is never changed.
	 *
	 * @param tree         the plugin tree
	 * @param version      the canonical version that setup comes from, which the tree must declare
	 * @param session registers the activated plugin with the harness
	 * @return the outcome
	 * @throws NullPointerException if any of the arguments are null
	 */
	public UpdateResult setUpFrom(Path tree, String version, HarnessSession session)
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(version, "version").isNotBlank();
		requireThat(session, "session").isNotNull();
		return operate(Mode.SETUP, "v" + version, tree, session);
	}

	/**
	 * Reports the active version and the version of the previous bundle without writes.
	 *
	 * @return the status outcome; its version is empty when the harness has no plugin, and its previous version is
	 *         empty when the installation keeps no previous bundle
	 * @throws IOException if provider state is unreadable
	 */
	public UpdateResult status() throws IOException
	{
		Path home = policy.harnesses().get(activation.harness());
		if (home == null)
			return result(Result.STATUS, null, null, "Run the explicit CAT setup command for this harness.", List.of());
		InstalledState installed = activation.installed(home);
		return new UpdateResult(Result.STATUS, activation.harness(), installed.version(),
			previousBundle(new ActivationTransaction(state, activation, home)), "The installation state was read.",
			List.of(), installed.consistent(), keyIds());
	}

	/**
	 * Returns the runtime replacement that an operation requested and the stable launcher has not finished, without
	 * writes.
	 *
	 * @return the replacement, or empty when none is pending
	 * @throws IOException if the request is unreadable or malformed
	 */
	public Optional<RuntimeSwap> pendingSwap() throws IOException
	{
		return swaps.read().map(Recorded::swap);
	}

	/**
	 * Returns the version of the bundle that the last update replaced: its plugin, kept by the activation journal, its
	 * Java runtime and its updater version directory.
	 *
	 * @param transaction the harness's activation transaction
	 * @return the version, or empty when any part of the bundle is missing
	 * @throws IOException if {@code current} or the activation record is unreadable
	 */
	private Optional<String> previousBundle(ActivationTransaction transaction) throws IOException
	{
		Optional<String> current = bundle.current();
		Optional<String> previous = transaction.previousVersion();
		if (current.isEmpty() || previous.isEmpty() || !bundle.hasPrevious(current.get(), previous.get()))
			return Optional.empty();
		return previous;
	}

	/**
	 * Runs one operation under the harness lock and converts every failure into an outcome.
	 *
	 * @param mode         the operation
	 * @param requestedTag the requested tag, or null for the latest release
	 * @param localTree    the user's local plugin tree, or null for a signed release
	 * @param session      registers the activated plugin
	 * @return the outcome
	 */
	private UpdateResult operate(Mode mode, String requestedTag, Path localTree, HarnessSession session)
	{
		Path recorded = policy.harnesses().get(activation.harness());
		if (recorded == null)
			return result(Result.REFUSED, null, null, "Run the explicit CAT setup command for this harness.", List.of());
		Path home;
		try
		{
			home = LinkSafeDeletion.resolveRoot(recorded);
		}
		catch (IOException e)
		{
			return result(Result.REFUSED, null, null, e.getMessage(), List.of());
		}
		ActivationTransaction transaction = new ActivationTransaction(state, activation, home);
		try
		{
			Optional<ExclusiveFileLock> acquired = OperationLock.acquire(state, clock, lockWait, waiting);
			if (acquired.isEmpty())
				return result(Result.LOCKED, null, null, "Another CAT operation holds the harness lock.", List.of());
			try (ExclusiveFileLock lock = acquired.get())
			{
				if (!lock.isValid())
					return result(Result.LOCKED, null, null, "The harness lock is no longer valid.", List.of());
				UpdateResult result = operateLocked(mode, requestedTag, localTree, session, home, transaction);
				if (result.result().exitStatus() == 0)
					Uninstaller.discard(data);
				return log(result);
			}
		}
		catch (ActivationTransaction.ActivationFailure e)
		{
			return failure(e, null, transaction);
		}
		catch (AccessDeniedException e)
		{
			return result(Result.NOT_WRITABLE, null, null,
				"The installation is not writable; use the explicit administrative setup path: " + e.getMessage(),
				List.of());
		}
		catch (TrustException | IOException | IllegalArgumentException e)
		{
			return result(Result.REFUSED, null, null, e.getMessage(), transaction.paths());
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return result(Result.LOCKED, null, null, "Interrupted while waiting for the harness lock.", List.of());
		}
	}

	/**
	 * Recovers an interrupted activation, then runs the operation.
	 *
	 * @param mode         the operation
	 * @param requestedTag the requested tag, or null
	 * @param localTree    the user's local plugin tree, or null
	 * @param session      registers the activated plugin
	 * @param home         the protected harness home
	 * @param transaction  the harness's activation transaction
	 * @return the outcome
	 * @throws IOException    if the installation cannot be read
	 * @throws TrustException if the installed version is malformed
	 */
	private UpdateResult operateLocked(Mode mode, String requestedTag, Path localTree, HarnessSession session,
		Path home, ActivationTransaction transaction) throws IOException, TrustException
	{
		try
		{
			transaction.recover();
		}
		catch (IOException | IllegalArgumentException e)
		{
			return result(Result.NOT_RESTORED, null, null,
				"Recovery could not restore the installation: " + e.getMessage(), transaction.paths());
		}
		cleanStaleDownloads();
		Optional<Boolean> undoneCache = Optional.empty();
		if (mode != Mode.SETUP)
		{
			// Setup replaces the bundle itself and handles a pending replacement before it activates the plugin
			Resolution resolution = resolveSwap(home, transaction);
			if (resolution.outcome().isPresent())
				return resolution.outcome().get();
			undoneCache = resolution.aotCache();
			prune(transaction);
		}
		UpdateResult outcome = operateResolved(mode, requestedTag, localTree, session, home, transaction);
		// An operation that undid an update reports the restored plugin's cache unless it activated another plugin
		if (undoneCache.isPresent() && outcome.aotCache().isEmpty())
			return outcome.withAotCache(undoneCache.get());
		return outcome;
	}

	/**
	 * Runs the operation once no runtime replacement is pending.
	 *
	 * @param mode         the operation
	 * @param requestedTag the requested tag, or null
	 * @param localTree    the user's local plugin tree, or null
	 * @param session      registers the activated plugin
	 * @param home         the protected harness home
	 * @param transaction  the harness's activation transaction
	 * @return the outcome
	 * @throws IOException    if the installation cannot be read
	 * @throws TrustException if the installed version is malformed
	 */
	private UpdateResult operateResolved(Mode mode, String requestedTag, Path localTree, HarnessSession session,
		Path home, ActivationTransaction transaction) throws IOException, TrustException
	{
		InstalledState installed = activation.installed(home);
		String active = installed.version().orElse(null);
		try
		{
			if (active == null && mode == Mode.UPDATE)
			{
				return result(Result.REFUSED, null, null, "CAT is not installed in this harness; run install to add it.",
					List.of());
			}
			if (active != null)
				cleanStaleStaging(activation.plan(home, active));
			return deliver(mode, requestedTag, localTree, session, home, installed, transaction);
		}
		catch (ActivationTransaction.ActivationFailure e)
		{
			return failure(e, active, transaction);
		}
		catch (AccessDeniedException e)
		{
			return result(Result.NOT_WRITABLE, active, null,
				"The installation is not writable; use explicit administrative setup: " + e.getMessage(), List.of());
		}
		catch (TrustException | IOException | IllegalArgumentException e)
		{
			return result(Result.REFUSED, active, null, e.getMessage(), transaction.paths());
		}
	}

	/**
	 * Obtains the release tree and activates it.
	 *
	 * @param mode         the operation
	 * @param requestedTag the requested tag, or null for the latest release
	 * @param localTree    the user's local plugin tree, or null for a signed release
	 * @param session registers the activated plugin
	 * @param home         the protected harness home
	 * @param installed    the harness's installation state
	 * @param transaction  the harness's activation transaction
	 * @return the outcome
	 * @throws IOException    if staging or activation fails
	 * @throws TrustException if the release is not approved
	 */
	private UpdateResult deliver(Mode mode, String requestedTag, Path localTree, HarnessSession session,
		Path home, InstalledState installed, ActivationTransaction transaction) throws IOException, TrustException
	{
		String active = installed.version().orElse(null);
		String current = null;
		if (mode != Mode.SETUP)
			current = bundle.current().orElse(null);
		if (localTree != null)
		{
			String version = requestedTag.substring(1);
			return inScratch((download, extractRoot) ->
			{
				Path tree = StateFiles.createTemporaryDirectory(extractRoot, "local-").resolve("plugin");
				ActivationTransaction.copyTree(localTree, tree);
				return activateTree(mode, tree, version, active, session, home, transaction);
			});
		}
		String tag = requestedTag;
		if (mode == Mode.INSTALL)
		{
			if (current == null)
			{
				return result(Result.REFUSED, active, null, "The installation in " + data + " does not record " +
					"its updater version; rerun the entry command to repair it.", List.of());
			}
			if (tag == null)
				tag = "v" + current;
		}
		VerifiedManifest verified;
		try
		{
			if (tag == null || tag.equals(LATEST))
				tag = source.latestTag();
			if (tag == null || !tag.startsWith("v"))
				return result(Result.REFUSED, active, null, "A canonical v-prefixed release tag is required.", List.of());
			ReleaseVersion requested = ReleaseVersion.parse(tag.substring(1));
			if (mode == Mode.INSTALL && requested.compareTo(ReleaseVersion.parse(current)) != 0)
			{
				return result(Result.REFUSED, active, null, "Install adds only release " + current + ", which " +
					"this installation runs; run update to move to release " + requested + ".", List.of());
			}
			verified = ManifestVerifier.verify(new ByteArrayInputStream(source.manifest(tag)),
				new ByteArrayInputStream(source.signature(tag)), policy);
			ReleaseApproval.approveUpdate(verified, policy, requested);
		}
		catch (IOException e)
		{
			return result(Result.DOWNLOAD_FAILED, active, null, e.getMessage(), List.of());
		}
		String version = verified.manifest().version().toString();
		// Every operation except setup replaces the whole bundle of an installation that has a plugin
		boolean bundled = mode != Mode.SETUP && active != null;
		if (bundled && version.equals(active) && installed.consistent() && version.equals(current))
		{
			session.integration().register(home, session.cli(), session.replacer());
			return result(Result.UP_TO_DATE, active, previousBundle(transaction).orElse(null), "CAT is up to date.",
				List.of());
		}
		boolean downgrade = bundled && verified.manifest().version().compareTo(ReleaseVersion.parse(active)) < 0;
		if (downgrade && (requestedTag == null || requestedTag.equals(LATEST)))
		{
			return result(Result.REFUSED, active, null, "The latest release " + version + " is older than the " +
				"active release " + active + "; name a release to install an older one.", List.of());
		}
		ReleaseAsset asset = verified.manifest().findPlugin(activation.harness(), platform).orElse(null);
		if (asset == null)
			return result(Result.REFUSED, active, null,
				"The approved release has no plugin for this harness and platform.", List.of());
		String releaseTag = tag;
		if (bundled && !version.equals(current))
		{
			if (current == null)
			{
				return result(Result.REFUSED, active, null, "The installation in " + data + " does not record " +
					"its updater version; rerun the entry command to repair it.", List.of());
			}
			ReleaseAsset updater = verified.manifest().findUpdater(platform).orElse(null);
			if (updater == null)
				return result(Result.REFUSED, active, null, "The approved release has no updater for this platform.",
					List.of());
			String running = current;
			return inScratch((download, extractRoot) ->
			{
				if (downgrade)
				{
					Optional<UpdateResult> refusal = refuseDataChange(version, active, download, extractRoot);
					if (refusal.isPresent())
						return refusal.get();
				}
				try
				{
					source.download(releaseTag, asset.name(), asset.size(), download.resolve(asset.name()));
					source.download(releaseTag, updater.name(), updater.size(), download.resolve(updater.name()));
				}
				catch (IOException e)
				{
					return result(Result.DOWNLOAD_FAILED, active, null, e.getMessage(), List.of());
				}
				Path pluginTree = extract(download, extractRoot, asset);
				Path updaterTree = extract(download, extractRoot, updater);
				return activateBundle(new Staged(pluginTree, updaterTree, version), running, active, session,
					home, transaction);
			});
		}
		return inScratch((download, extractRoot) ->
		{
			Path archive = download.resolve(asset.name());
			try
			{
				source.download(releaseTag, asset.name(), asset.size(), archive);
			}
			catch (IOException e)
			{
				return result(Result.DOWNLOAD_FAILED, active, null, e.getMessage(), List.of());
			}
			return activateTree(mode, extract(download, extractRoot, asset), version, active, session,
				home, transaction);
		});
	}

	/**
	 * Validates a downloaded archive against its signed manifest entry and extracts it.
	 *
	 * @param download    the directory of downloaded archives
	 * @param extractRoot the directory for extracted trees
	 * @param asset       the archive's manifest entry
	 * @return the archive's extracted top directory
	 * @throws IOException if the archive does not match its entry or cannot be extracted
	 */
	private static Path extract(Path download, Path extractRoot, ReleaseAsset asset) throws IOException
	{
		Path archive = download.resolve(asset.name());
		ReleaseDownload.validate(archive, asset);
		Path extract = StateFiles.createTemporaryDirectory(extractRoot, "release-");
		try (InputStream input = Files.newInputStream(archive))
		{
			TarGzExtractor.extract(input, extract, asset.topDirectory());
		}
		return extract.resolve(asset.topDirectory());
	}

	/**
	 * Refuses an update to an older release when the active release registers a data migration after it. The active
	 * release's migration registry comes from its plugin archive, which its signed manifest authenticates.
	 *
	 * @param older       the older release
	 * @param active      the active release
	 * @param download    the directory of downloaded archives
	 * @param extractRoot the directory for extracted trees
	 * @return the refusal, or empty when the older release uses the active release's data structures
	 * @throws IOException    if the active release's archive or registry is invalid
	 * @throws TrustException if the active release is not approved or its registry is malformed
	 */
	private Optional<UpdateResult> refuseDataChange(String older, String active, Path download, Path extractRoot)
		throws IOException, TrustException
	{
		String tag = "v" + active;
		Path activeDownload = StateFiles.createTemporaryDirectory(download, "active-");
		ReleaseAsset asset;
		try
		{
			VerifiedManifest verified = ManifestVerifier.verify(new ByteArrayInputStream(source.manifest(tag)),
				new ByteArrayInputStream(source.signature(tag)), policy);
			ReleaseApproval.approveUpdate(verified, policy, ReleaseVersion.parse(active));
			asset = verified.manifest().findPlugin(activation.harness(), platform).orElse(null);
			if (asset == null)
			{
				return Optional.of(result(Result.REFUSED, active, null, "The active release " + active + " has no " +
					"plugin for this harness and platform, so its data structures cannot be compared with release " +
					older + ".", List.of()));
			}
			source.download(tag, asset.name(), asset.size(), activeDownload.resolve(asset.name()));
		}
		catch (IOException e)
		{
			return Optional.of(result(Result.DOWNLOAD_FAILED, active, null, e.getMessage(), List.of()));
		}
		Path tree = extract(activeDownload, extractRoot, asset);
		List<String> changes = StagedRelease.migrationsAfter(tree, ReleaseVersion.parse(older),
			ReleaseVersion.parse(active));
		if (changes.isEmpty())
			return Optional.empty();
		return Optional.of(result(Result.REFUSED, active, null, "Release " + older + " is older than the active " +
			"release " + active + ", and CAT's data structures changed between them (" + String.join(", ", changes) +
			"). They are not backward compatible, so installing " + older + " would lose data.", List.of()));
	}

	/**
	 * Runs work that stages a release in the harness's scratch directories, deleting them afterwards.
	 *
	 * @param work the staging work
	 * @return the work's outcome, noting a cleanup failure
	 * @throws IOException    if the work fails
	 * @throws TrustException if the work refuses the release
	 */
	private UpdateResult inScratch(ScratchWork work) throws IOException, TrustException
	{
		Path download = state.directory().resolve("download");
		Path extractRoot = state.directory().resolve("extract");
		StateFiles.createDirectories(download);
		UpdateResult completed = null;
		IOException cleanupFailure = null;
		Exception operationFailure = null;
		try
		{
			completed = work.run(download, extractRoot);
		}
		catch (IOException | TrustException | IllegalArgumentException failure)
		{
			operationFailure = failure;
			throw failure;
		}
		finally
		{
			try
			{
				StateFiles.delete(download);
				StateFiles.delete(extractRoot);
			}
			catch (IOException failure)
			{
				if (operationFailure != null)
					operationFailure.addSuppressed(failure);
				else
					cleanupFailure = failure;
			}
		}
		if (cleanupFailure != null)
		{
			return new UpdateResult(completed.result(), completed.harness(), completed.version(),
				completed.previousVersion(), completed.message() + " Temporary cleanup failed: " +
				cleanupFailure.getMessage(), List.of(download, extractRoot), completed.consistent(), completed.keyIds());
		}
		return completed;
	}

	/**
	 * Validates a staged release tree, checks that the installation's runtime can run it, binds it to that runtime,
	 * and activates it: as an install where the harness has no plugin, as a repair that keeps the previous bundle where
	 * the harness has this version, otherwise as an update. The plugin is registered with the harness before the
	 * activation is kept; when an install's registration fails, CAT is also unregistered before the added plugin is
	 * deleted again.
	 *
	 * @param mode         the operation
	 * @param tree         the staged release tree, which this method may change
	 * @param version      the release version
	 * @param active       the active version, or null when the harness has no plugin
	 * @param session registers the activated plugin
	 * @param home         the protected harness home
	 * @param transaction  the harness's activation transaction
	 * @return the outcome
	 * @throws IOException    if validation, the runtime check, activation or registration fails
	 * @throws TrustException if a version is malformed or a pending migration requires explicit installation
	 */
	private UpdateResult activateTree(Mode mode, Path tree, String version, String active,
		HarnessSession session, Path home, ActivationTransaction transaction) throws IOException, TrustException
	{
		Path migrationFile = validatePlugin(mode, tree, version, active, home);
		requireRuntime(tree, version);
		Files.writeString(tree.resolve(BINDING_FILE), runtime + "\n", StandardCharsets.UTF_8);
		Completion register = () -> session.integration().register(home, session.cli(),
			session.replacer());
		if (active == null)
		{
			for (Path path : activation.removalPaths(home))
				LinkSafeDeletion.delete(path, home);
			transaction.install(tree, activation.installPlan(home, version), migrationFile, version,
				() -> registerOrUnregister(session, home));
			return result(Result.INSTALLED, version, null, withWarning("CAT was installed.", transaction),
				List.of()).withAotCache(train(home, version, runtime));
		}
		if (active.equals(version))
			transaction.repair(tree, activation.plan(home, version), migrationFile, version, register);
		else
			transaction.activate(tree, activation.plan(home, version), migrationFile, active, version, register);
		return result(Result.UPDATED, version, active, withWarning("CAT was updated.", transaction), List.of()).
			withAotCache(train(home, version, runtime));
	}

	/**
	 * Registers a plugin that an install added, and unregisters CAT from the harness when registration fails, so that
	 * an install which fails leaves the harness without a registration of the plugin files that it deletes again.
	 *
	 * @param session registers the activated plugin
	 * @param home         the protected harness home
	 * @throws IOException if registration fails; a failure to unregister is suppressed in it
	 */
	private static void registerOrUnregister(HarnessSession session, Path home) throws IOException
	{
		try
		{
			session.integration().register(home, session.cli(), session.replacer());
		}
		catch (IOException e)
		{
			try
			{
				session.integration().unregister(home, session.cli());
			}
			catch (IOException unregisterFailure)
			{
				e.addSuppressed(unregisterFailure);
			}
			throw e;
		}
	}

	/**
	 * Validates a staged plugin tree and refuses it while the harness has a data migration that only an explicit
	 * installation may run. Setup performs the per-harness layout migration itself, so it accepts a release that
	 * registers only that migration.
	 *
	 * @param mode    the operation
	 * @param tree    the staged plugin tree
	 * @param version the release version
	 * @param active  the active version, or null when the harness has no plugin
	 * @param home    the protected harness home
	 * @return the harness's migration marker
	 * @throws IOException    if the tree is invalid or a migration is pending
	 * @throws TrustException if a version is malformed
	 */
	private Path validatePlugin(Mode mode, Path tree, String version, String active, Path home)
		throws IOException, TrustException
	{
		StagedRelease.validate(tree, version, activation.pluginManifest(), platform);
		Path migrationFile = activation.migrationVersionFile(home);
		StateFiles.requireNoLinks(migrationFile);
		String recorded = active;
		if (recorded == null && Files.exists(migrationFile, NOFOLLOW_LINKS))
			recorded = version;
		if (recorded != null)
		{
			if (mode == Mode.SETUP)
				StagedRelease.requireNoPendingSetupMigration(tree, migrationFile, recorded, version);
			else
				StagedRelease.requireNoPendingMigration(tree, migrationFile, recorded, version);
		}
		return migrationFile;
	}

	/**
	 * Activates a release's whole bundle: checks that the release's own Java runtime can run its updater and plugin,
	 * moves that runtime and the updater version directory into the installation, activates the plugin bound to the
	 * installation's runtime location, installs the release's stable launcher, and leaves the request with which the
	 * stable launcher that is running puts the new runtime in place and selects the new updater once this JVM has
	 * exited. The replaced bundle stays in the installation.
	 *
	 * @param staged       the extracted release
	 * @param running      the updater version that {@code current} selects
	 * @param active       the active plugin version
	 * @param session registers the activated plugin
	 * @param home         the protected harness home
	 * @param transaction  the harness's activation transaction
	 * @return the outcome
	 * @throws IOException    if validation, the runtime checks, staging, activation or registration fails
	 * @throws TrustException if a version is malformed or a pending migration requires explicit installation
	 */
	private UpdateResult activateBundle(Staged staged, String running, String active, HarnessSession session,
		Path home, ActivationTransaction transaction) throws IOException, TrustException
	{
		String version = staged.version();
		bundle.validate(staged.updater(), version);
		Path migrationFile = validatePlugin(Mode.UPDATE, staged.plugin(), version, active, home);
		RuntimeDescription next = RuntimeDescription.read(staged.updater().resolve(Bundle.RUNTIME));
		requireOwnRuntime(next, RuntimeRequirement.read(staged.plugin().resolve(RuntimeRequirement.PLUGIN_FILE)),
			"plugin", version);
		requireOwnRuntime(next, RuntimeRequirement.read(staged.updater().resolve(version).
			resolve(Bundle.REQUIREMENT_FILE)), "updater", version);
		Files.writeString(staged.plugin().resolve(BINDING_FILE), runtime + "\n", StandardCharsets.UTF_8);
		Completion register = () -> session.integration().register(home, session.cli(),
			session.replacer());
		try
		{
			bundle.stage(staged.updater(), version, running);
			// The request follows the staged runtime, so a missing staged runtime means that the launcher renamed it
			swaps.write(new RuntimeSwap(Operation.UPDATE, running, version), Optional.of(active));
			transaction.activate(staged.plugin(), activation.plan(home, version), migrationFile, active, version,
				register);
		}
		catch (IOException | TrustException e)
		{
			cancelSwap(version, e);
			throw e;
		}
		boolean trained;
		try
		{
			// The stable launcher puts the staged runtime in place, so the cache must suit that runtime
			trained = train(home, version, bundle.staged());
			bundle.deleteOlder(running, version);
			// The running launcher reads its script while it runs, so the new one is renamed over it
			bundle.installLauncher(bundle.stableLauncher(version));
		}
		catch (IOException e)
		{
			boolean restored = true;
			try
			{
				transaction.undoLast();
			}
			catch (IOException | TrustException undoFailure)
			{
				e.addSuppressed(undoFailure);
				restored = false;
			}
			cancelSwap(version, e);
			throw new ActivationTransaction.ActivationFailure(e, restored);
		}
		return result(Result.UPDATED, version, running, withWarning("CAT was updated; the stable launcher " +
			"replaces the Java runtime once the updater exits.", transaction), List.of()).withAotCache(trained);
	}

	/**
	 * Deletes a pending update request and the bundle it staged after the update failed.
	 *
	 * @param version the staged version
	 * @param failure the update's failure, which records a cleanup failure
	 */
	private void cancelSwap(String version, Exception failure)
	{
		try
		{
			swaps.delete();
			bundle.discard(version);
		}
		catch (IOException cleanupFailure)
		{
			failure.addSuppressed(cleanupFailure);
		}
	}

	/**
	 * Refuses a release whose own Java runtime does not satisfy one of its parts.
	 *
	 * @param description the release's Java runtime
	 * @param requirement the part's requirement
	 * @param part        the part's name
	 * @param version     the release version
	 * @throws IOException if the runtime does not satisfy the requirement
	 */
	private static void requireOwnRuntime(RuntimeDescription description, RuntimeRequirement requirement,
		String part, String version) throws IOException
	{
		if (!description.satisfies(requirement))
		{
			throw new ActivationTransaction.Refused("The " + part + " of release " + version + " needs Java " +
				requirement.javaFeature() + " with the modules " + requirement.modules() + ", but the release's own " +
				"Java runtime is " + description.javaVersion() + "; the release cannot be installed.");
		}
	}

	/**
	 * Completes or undoes a runtime replacement that an earlier operation requested and the stable launcher did not
	 * finish. Undoing it also puts back the stable launcher of the version that {@code current} selects, and trains
	 * the startup cache of a plugin that it restores.
	 *
	 * @param home        the protected harness home
	 * @param transaction the harness's activation transaction
	 * @return the outcome that ends this operation, or none when the operation continues
	 * @throws IOException    if the installation cannot be read or repaired
	 * @throws TrustException if a recorded version is malformed
	 */
	private Resolution resolveSwap(Path home, ActivationTransaction transaction) throws IOException, TrustException
	{
		Optional<Recorded> pending = swaps.read();
		if (pending.isEmpty())
			return Resolution.CONTINUE;
		Recorded recorded = pending.get();
		if (recorded.launcherRunning())
		{
			return Resolution.ending(result(Result.LOCKED, null, null, "The stable launcher is still finishing a " +
				"runtime replacement.", List.of()));
		}
		RuntimeSwap swap = recorded.swap();
		Optional<String> current = bundle.current();
		if (current.equals(Optional.of(swap.toVersion())))
		{
			// The launcher selected the new updater and stopped before it deleted the request
			swaps.delete();
			return Resolution.CONTINUE;
		}
		if (!current.equals(Optional.of(swap.fromVersion())))
		{
			return Resolution.ending(result(Result.NOT_RESTORED, null, null, "The runtime replacement from " +
				swap.fromVersion() + " to " + swap.toVersion() + " does not match the selected updater version; " +
				"rerun the entry command to repair the installation.", List.of(swaps.request())));
		}
		if (!Files.exists(bundle.staged(), NOFOLLOW_LINKS))
			return Resolution.ending(completeSwap(swap));
		String plugin = activation.installed(home).version().orElse(null);
		Optional<Boolean> trained = Optional.empty();
		if (swap.toVersion().equals(plugin) && recorded.pluginVersion().isPresent() &&
			transaction.previousVersion().equals(recorded.pluginVersion()))
		{
			String restored = transaction.undoLast();
			// The undone update leaves the running runtime in place
			trained = Optional.of(train(home, restored, runtime));
		}
		bundle.installLauncher(bundle.stableLauncher(swap.fromVersion()));
		swaps.delete();
		bundle.discard(swap.toVersion());
		return new Resolution(Optional.empty(), trained);
	}

	/**
	 * Writes the startup cache of every plugin directory that the harness's plan for a version replaces or adds, with
	 * the Java runtime that the installation runs once the stable launcher has finished the operation. Each directory
	 * is bound to that runtime while it trains, and afterwards to the installation's runtime location, under which the
	 * stable launcher puts that runtime.
	 *
	 * @param home     the protected harness home
	 * @param version  the activated version
	 * @param training the Java runtime that the installation runs after the operation: {@code runtime} or a staged
	 *                 runtime that the stable launcher renames to it
	 * @return {@code true} if every activated plugin directory got its startup cache
	 * @throws IOException if a plugin directory cannot be bound to the installation's runtime location again
	 */
	private boolean train(Path home, String version, Path training) throws IOException
	{
		List<Path> plugins = new ArrayList<>();
		try
		{
			for (ActivationStep step : activation.plan(home, version))
			{
				switch (step)
				{
					case ReplaceDirectory replace -> plugins.add(replace.live());
					case AddDirectory add -> plugins.add(add.target());
					default ->
					{
					}
				}
			}
		}
		catch (IOException | IllegalArgumentException _)
		{
			return false;
		}
		boolean written = !plugins.isEmpty();
		for (Path plugin : plugins)
			written &= trainBound(plugin, training);
		return written;
	}

	/**
	 * Writes a plugin directory's startup cache with a Java runtime that the directory is bound to while it trains.
	 *
	 * @param plugin   the plugin directory
	 * @param training the Java runtime that trains the cache
	 * @return {@code true} if the cache was written
	 * @throws IOException if the directory cannot be bound to the installation's runtime location again
	 */
	private boolean trainBound(Path plugin, Path training) throws IOException
	{
		Path binding = plugin.resolve(BINDING_FILE);
		// The plugin's launchers pass the module path that Java checks the cache against, so training runs through them
		if (training.equals(runtime) || !Files.exists(binding, NOFOLLOW_LINKS))
			return trainer.train(plugin);
		boolean written;
		try
		{
			bind(binding, training);
			written = trainer.train(plugin);
		}
		catch (IOException _)
		{
			written = false;
		}
		try
		{
			// The binding names the location, not the content read before, so a killed training never leaves it stale
			bind(binding, runtime);
		}
		catch (IOException e)
		{
			try
			{
				StateFiles.delete(plugin.resolve(StartupCache.DIRECTORY));
			}
			catch (IOException deleteFailure)
			{
				e.addSuppressed(deleteFailure);
			}
			throw e;
		}
		if (!written)
			StateFiles.delete(plugin.resolve(StartupCache.DIRECTORY));
		return written;
	}

	/**
	 * Replaces a plugin's runtime binding.
	 *
	 * @param binding the binding file
	 * @param target  the Java runtime that the binding names
	 * @throws IOException if the binding cannot be replaced
	 */
	private void bind(Path binding, Path target) throws IOException
	{
		StateFiles.replace(binding, (target + "\n").getBytes(StandardCharsets.UTF_8), BINDING_MODE, replacer);
	}

	/**
	 * Completes a runtime replacement whose directories the stable launcher renamed before it stopped.
	 *
	 * @param swap the replacement
	 * @return the outcome of the operation that requested it
	 * @throws IOException if the installation does not hold the renamed bundle or cannot be repaired
	 */
	private UpdateResult completeSwap(RuntimeSwap swap) throws IOException
	{
		if (!Files.isDirectory(bundle.replaced(), NOFOLLOW_LINKS) ||
			!Files.isDirectory(data.resolve(swap.toVersion()), NOFOLLOW_LINKS))
		{
			return result(Result.NOT_RESTORED, null, null, "The runtime replacement from " + swap.fromVersion() +
				" to " + swap.toVersion() + " lost its runtime or updater; rerun the entry command to repair the " +
				"installation.", List.of(swaps.request()));
		}
		bundle.select(swap.toVersion());
		swaps.delete();
		return result(Result.UPDATED, swap.toVersion(), swap.fromVersion(), "An interrupted update was completed.",
			List.of());
	}

	/**
	 * Deletes the staged runtime, the replaced runtime and the updater version directories that neither the selected
	 * bundle nor the previous bundle needs.
	 *
	 * @param transaction the harness's activation transaction
	 * @throws IOException if the installation cannot be read or a directory cannot be deleted
	 */
	private void prune(ActivationTransaction transaction) throws IOException
	{
		Optional<String> current = bundle.current();
		if (current.isPresent())
			bundle.prune(current.get(), previousBundle(transaction));
	}

	/**
	 * Refuses a release whose plugin needs a Java runtime that the installation's runtime does not satisfy.
	 *
	 * @param tree    the staged release tree
	 * @param version the release version
	 * @throws IOException if either description is unreadable, or the runtime does not satisfy the plugin
	 */
	private void requireRuntime(Path tree, String version) throws IOException
	{
		RuntimeRequirement requirement = RuntimeRequirement.read(tree.resolve(RuntimeRequirement.PLUGIN_FILE));
		RuntimeDescription description = RuntimeDescription.read(runtime);
		if (!description.satisfies(requirement))
		{
			throw new ActivationTransaction.Refused("Release " + version + " needs Java " +
				requirement.javaFeature() + " with the modules " + requirement.modules() + ", but this installation's " +
				"Java runtime is " + description.javaVersion() + "; rerun the entry command of a newer release to " +
				"update the runtime.");
		}
	}

	/**
	 * Appends the transaction's cleanup warning to a message.
	 *
	 * @param message     the outcome message
	 * @param transaction the completed transaction
	 * @return the message, with the warning when there is one
	 */
	private static String withWarning(String message, ActivationTransaction transaction)
	{
		if (transaction.warning().isPresent())
			return message + " " + transaction.warning().get();
		return message;
	}

	/**
	 * Converts an activation failure into its outcome.
	 *
	 * @param failure     the failure
	 * @param active      the active version before the operation, or null
	 * @param transaction the harness's activation transaction
	 * @return the outcome
	 */
	private UpdateResult failure(ActivationTransaction.ActivationFailure failure, String active,
		ActivationTransaction transaction)
	{
		Result outcome = Result.NOT_RESTORED;
		if (failure.restored())
			outcome = Result.RESTORED;
		return result(outcome, active, null, failure.getMessage(), transaction.paths());
	}

	/**
	 * Removes only this engine's abandoned download workspaces while the harness lock is held.
	 *
	 * @throws IOException if a stale workspace cannot be removed
	 */
	private void cleanStaleDownloads() throws IOException
	{
		for (String name : List.of("download", "extract"))
			StateFiles.delete(state.directory().resolve(name));
	}

	/**
	 * Removes abandoned staging slots only from parents explicitly named by the protected provider plan.
	 *
	 * @param plan the provider's authoritative plan
	 * @throws IOException if a parent redirects traversal or cleanup fails
	 */
	private static void cleanStaleStaging(List<ActivationStep> plan) throws IOException
	{
		for (ActivationStep step : plan)
		{
			Path parent = switch (step)
			{
				case ReplaceDirectory replace -> replace.slotParent();
				case AddDirectory add -> add.stagingParent();
				case ReplaceOwnedFiles _ -> null;
			};
			if (parent == null)
				continue;
			StateFiles.requireNoLinks(parent);
			if (!Files.isDirectory(parent))
				continue;
			try (Stream<Path> paths = Files.list(parent))
			{
				for (Path path : paths.filter(path -> path.getFileName().toString().startsWith(".cat-staging-")).toList())
					StateFiles.delete(path);
			}
		}
	}

	/**
	 * Records an operation without changing its completed activation outcome if recording fails.
	 *
	 * @param outcome the completed outcome
	 * @return the outcome, including a logging diagnostic when necessary
	 */
	private UpdateResult log(UpdateResult outcome)
	{
		if (!Files.isDirectory(state.directory()))
			return outcome;
		try
		{
			StateFiles.requireNoLinks(state.log());
			String previous = "";
			if (Files.exists(state.log()) && Files.size(state.log()) < 1024 * 1024)
				previous = Files.readString(state.log());
			String entry = clock.instant() + " " + outcome.result() + " " +
				outcome.message().replace('\n', ' ').replace('\r', ' ') + "\n";
			StateFiles.write(state.log(), (previous + entry).getBytes(StandardCharsets.UTF_8));
			return outcome;
		}
		catch (IOException e)
		{
			return new UpdateResult(outcome.result(), outcome.harness(), outcome.version(), outcome.previousVersion(),
				outcome.message() + " The operation log could not be written: " + e.getMessage(), outcome.paths(),
				outcome.consistent(), outcome.keyIds());
		}
	}

	/**
	 * Builds an outcome with this policy's key identifiers.
	 *
	 * @param outcome the result category
	 * @param version the active version, or null
	 * @param previousVersion previous version, or null
	 * @param message the user-facing explanation
	 * @param paths affected paths
	 * @return the complete outcome
	 */
	private UpdateResult result(Result outcome, String version, String previousVersion, String message, List<Path> paths)
	{
		return new UpdateResult(outcome, activation.harness(), Optional.ofNullable(version),
			Optional.ofNullable(previousVersion), message, paths, outcome != Result.NOT_RESTORED, keyIds());
	}

	/**
	 * Returns the policy's trusted key names.
	 *
	 * @return the key names
	 */
	private List<String> keyIds()
	{
		return policy.keys().stream().map(TrustedKey::id).toList();
	}

	/**
	 * How a runtime replacement that an earlier operation left was resolved.
	 *
	 * @param outcome  the outcome that ends this operation, or empty when the operation continues
	 * @param aotCache whether the plugin that undoing an update restored got its startup cache, or empty when no plugin
	 *                 was restored
	 */
	private record Resolution(Optional<UpdateResult> outcome, Optional<Boolean> aotCache)
	{
		/**
		 * The resolution of an operation that continues without having restored a plugin.
		 */
		static final Resolution CONTINUE = new Resolution(Optional.empty(), Optional.empty());

		/**
		 * Returns the resolution that ends the operation.
		 *
		 * @param outcome the operation's outcome
		 * @return the resolution
		 */
		static Resolution ending(UpdateResult outcome)
		{
			return new Resolution(Optional.of(outcome), Optional.empty());
		}
	}

	/**
	 * A release whose archives are extracted.
	 *
	 * @param plugin  the plugin tree
	 * @param updater the updater archive's top directory
	 * @param version the release version
	 */
	private record Staged(Path plugin, Path updater, String version)
	{
	}

	/**
	 * The operations that the engine runs.
	 */
	private enum Mode
	{
		/**
		 * Update the plugin; refuse when the harness has none.
		 */
		UPDATE,
		/**
		 * Install the plugin where the harness has none, otherwise update it.
		 */
		INSTALL,
		/**
		 * Activate the release that setup comes from, whatever the harness has.
		 */
		SETUP
	}

	/**
	 * Stages a release in the harness's scratch directories.
	 */
	@FunctionalInterface
	private interface ScratchWork
	{
		/**
		 * Stages and activates the release.
		 *
		 * @param download    the directory for downloaded archives
		 * @param extractRoot the directory for extracted trees, which does not exist yet
		 * @return the outcome
		 * @throws IOException    if staging or activation fails
		 * @throws TrustException if the release is refused
		 */
		UpdateResult run(Path download, Path extractRoot) throws IOException, TrustException;
	}
}
