package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.engine.ReleaseCache.SignedRelease;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.ReleaseApproval;
import com.holdmyspot.cat.common.installer.trust.ReleaseAsset;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import com.holdmyspot.cat.common.installer.trust.VerifiedManifest;
import com.holdmyspot.cat.common.release.TarGzExtractor;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Installs CAT's plugin into a harness whose policy the installer already wrote, from an authenticated release or
 * from the extracted plugin archive that the running installer belongs to, without executing staged code. Where the
 * harness has a CAT version, the install updates it under the release and downgrade rules. The installer writes the
 * plugin into CAT's marketplace in the harness home and asks the harness to install it into the version's own
 * version folder; it then trains the plugin's startup cache there and rewrites the permission entries to name the
 * installer in that folder. Every install that succeeds, including one that finds the harness up to date, then deletes
 * the version folders other than the active version's, the one that it replaced and the running installer's. Nothing
 * in the version folder that the running installer belongs to is moved, replaced or deleted, and the policy is never
 * written.
 */
public final class UpdateEngine
{
	/**
	 * The release selector with which install names the latest approved release.
	 */
	public static final String LATEST = "latest";
	private final InstallerState state;
	private final Path data;
	private final boolean windows;
	private final ReleaseCache cache;
	private final TrustPolicy policy;
	private final ReleaseSource source;
	private final PluginActivation activation;
	private final String platform;
	private final Clock clock;
	private final Waiting waiting;
	private final Duration lockWait;
	private final StartupCacheTrainer trainer;
	private final MigrationRunner migrations;

	/**
	 * Creates an engine with a ten-minute lock wait.
	 *
	 * @param dataDirectory the harness's protected CAT data directory
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
		this(dataDirectory, policy, source, activation, platform, clock, waiting, OperationLock.WAIT);
	}

	/**
	 * Creates an engine with an explicit lock wait.
	 *
	 * @param dataDirectory the harness's protected CAT data directory
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
			ProcessElevation::isElevated, new ProcessMigrationRunner());
	}

	/**
	 * Creates an engine with an explicit lock wait, check of administrative rights, which decides whether startup
	 * cache training runs, and runner of data migration scripts.
	 *
	 * @param dataDirectory the harness's protected CAT data directory
	 * @param policy        protected policy
	 * @param source        release transport
	 * @param activation    the selected harness's activation provider
	 * @param platform      the release platform identifier
	 * @param clock         operation time source
	 * @param waiting       creates pauses between lock attempts
	 * @param lockWait      nonnegative maximum wait for the harness lock
	 * @param elevation     returns whether this process runs with administrative rights, given a directory that the
	 *                      check may write short-lived files to
	 * @param migrations    runs the data migration scripts of the release that install installs
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code platform} is blank or {@code lockWait} is negative
	 */
	public UpdateEngine(Path dataDirectory, TrustPolicy policy, ReleaseSource source, PluginActivation activation,
		String platform, Clock clock, Waiting waiting, Duration lockWait, Predicate<Path> elevation,
		MigrationRunner migrations)
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
		requireThat(migrations, "migrations").isNotNull();
		this.state = InstallerState.of(dataDirectory, activation.harness());
		this.data = dataDirectory.toAbsolutePath().normalize();
		this.windows = Arrays.stream(Platform.values()).
			anyMatch(candidate -> candidate.id().equals(platform) && candidate.isWindows());
		this.cache = new ReleaseCache(data);
		this.policy = policy;
		this.source = source;
		this.activation = activation;
		this.platform = platform;
		this.clock = clock;
		this.waiting = waiting;
		this.lockWait = lockWait;
		this.migrations = migrations;
		Path trainingScratch = state.directory().resolve("startup-cache");
		this.trainer = new StartupCacheTrainer(trainingScratch, Duration.ofMinutes(1),
			() -> elevation.test(trainingScratch));
	}

	/**
	 * Installs an approved release: where the harness has no CAT version it adds it, and otherwise it updates the
	 * harness's version to it. The release's signed manifest names its build: when the harness already has that build
	 * consistently installed, the harness installs nothing and only CAT's companion files, data migrations and
	 * permission and sandbox entries are brought up to date, and any other build of the active release is installed,
	 * whichever of the two was published first. The latest release is refused when it is older than the active one,
	 * and a named older release is refused when the active release changed CAT's data structures after it.
	 *
	 * @param requestedTag the canonical v-prefixed tag, or null or {@link #LATEST} for the latest release
	 * @param request      the harness and the running installer's plugin folder
	 * @return the outcome
	 * @throws NullPointerException if {@code request} is null
	 */
	public UpdateResult install(String requestedTag, InstallRequest request)
	{
		requireThat(request, "request").isNotNull();
		return operate(home -> installRelease(requestedTag, request, home));
	}

	/**
	 * Installs a plugin tree, the extracted plugin archive that the running installer belongs to, into a harness under
	 * the rules of {@link #install(String, InstallRequest)}; the tree's own {@code bin/VERSION} names its version. The
	 * tree is validated like a signed release and is never changed.
	 *
	 * @param tree    the plugin tree
	 * @param request the harness and the running installer's plugin folder
	 * @return the outcome
	 * @throws NullPointerException if any of the arguments are null
	 */
	public UpdateResult installFrom(Path tree, InstallRequest request)
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(request, "request").isNotNull();
		return operate(home -> installTree(tree, request, home));
	}

	/**
	 * Installs another harness's plugin of a release while this installation's harness lock is held: the release's
	 * signed manifest must be one that this installation's policy approves, and the plugin archive that it names for
	 * the other harness and this platform is downloaded and verified as every release that this installation installs
	 * is. The verified, extracted plugin is then handed to {@code install}.
	 *
	 * @param other   the other harness's activation provider
	 * @param release the release
	 * @param install installs the extracted plugin into the other harness
	 * @return the outcome, about the other harness
	 * @throws NullPointerException if any of the arguments are null
	 */
	public UpdateResult installOther(PluginActivation other, ReleaseVersion release, StagedInstall install)
	{
		requireThat(other, "other").isNotNull();
		requireThat(release, "release").isNotNull();
		requireThat(install, "install").isNotNull();
		UpdateResult outcome = operate(_ -> installOtherRelease(other, release, install));
		return new UpdateResult(outcome.result(), other.harness(), outcome.version(), outcome.previousVersion(),
			outcome.message(), outcome.paths(), outcome.consistent(), outcome.keyIds(), outcome.aotCache(),
			outcome.exitDeletions());
	}

	/**
	 * Reports the active version without writes.
	 *
	 * @return the status outcome; its version is empty when the harness has no plugin
	 * @throws IOException if provider state is unreadable
	 */
	public UpdateResult status() throws IOException
	{
		Path home = policy.harnesses().get(activation.harness());
		if (home == null)
			return result(Result.STATUS, null, null, "Run the explicit CAT install command for this harness.", List.of());
		InstalledState installed = activation.installed(home);
		return new UpdateResult(Result.STATUS, activation.harness(), installed.version(), Optional.empty(),
			"The installation state was read.", List.of(), installed.consistent(), keyIds());
	}

	/**
	 * Runs one operation under the harness lock and converts every failure into an outcome.
	 *
	 * @param work the operation, given the protected harness home
	 * @return the outcome
	 */
	private UpdateResult operate(HomeWork work)
	{
		Path recorded = policy.harnesses().get(activation.harness());
		if (recorded == null)
			return result(Result.REFUSED, null, null, "Run the explicit CAT install command for this harness.", List.of());
		try
		{
			Path home = LinkSafeDeletion.resolveRoot(recorded);
			Optional<ExclusiveFileLock> acquired = OperationLock.acquire(state, clock, lockWait, waiting);
			if (acquired.isEmpty())
				return result(Result.LOCKED, null, null, "Another CAT operation holds the harness lock.", List.of());
			try (ExclusiveFileLock lock = acquired.get())
			{
				if (!lock.isValid())
					return result(Result.LOCKED, null, null, "The harness lock is no longer valid.", List.of());
				cleanStaleDownloads();
				return log(work.run(home));
			}
		}
		catch (AccessDeniedException e)
		{
			return result(Result.NOT_WRITABLE, null, null, "The installation is not writable: " + e.getMessage(),
				List.of());
		}
		catch (TrustException | IOException | IllegalArgumentException e)
		{
			return result(Result.REFUSED, null, null, e.getMessage(), List.of());
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return result(Result.LOCKED, null, null, "Interrupted while waiting for the harness lock.", List.of());
		}
	}

	/**
	 * Installs an approved release while the harness lock is held.
	 *
	 * @param requestedTag the requested tag, or null or {@link #LATEST} for the latest release
	 * @param request      the harness and the running installer's plugin folder
	 * @param home         the protected harness home
	 * @return the outcome
	 * @throws IOException    if the installation cannot be read or the release cannot be staged
	 * @throws TrustException if the release is not approved or a version is malformed
	 */
	private UpdateResult installRelease(String requestedTag, InstallRequest request, Path home)
		throws IOException, TrustException
	{
		Active active = active(home);
		String tag = requestedTag;
		SignedRelease signed;
		try
		{
			if (tag == null || tag.equals(LATEST))
				tag = source.latestTag();
			if (tag == null || !tag.startsWith("v"))
			{
				return result(Result.REFUSED, active.version(), null, "A canonical v-prefixed release tag is required.",
					List.of());
			}
			ReleaseVersion requested = ReleaseVersion.parse(tag.substring(1));
			signed = signed(tag);
			ReleaseApproval.approveUpdate(signed.verified(), policy, requested);
		}
		catch (IOException e)
		{
			return result(Result.DOWNLOAD_FAILED, active.version(), null, e.getMessage(), List.of());
		}
		VerifiedManifest verified = signed.verified();
		ReleaseVersion release = verified.manifest().version();
		PluginVersion plugin = verified.manifest().pluginVersion();
		if (active.isUpToDate(plugin))
			return upToDate(request, home, active);
		boolean downgrade = active.release().isPresent() && release.compareTo(active.release().get()) < 0;
		if (downgrade && (requestedTag == null || requestedTag.equals(LATEST)))
		{
			return result(Result.REFUSED, active.version(), null, "The latest release " + release + " is older than " +
				"the active release " + active.release().get() + "; name a release to install an older one.",
				List.of());
		}
		ReleaseAsset asset = verified.manifest().findPlugin(activation.harness(), platform).orElse(null);
		if (asset == null)
		{
			return result(Result.REFUSED, active.version(), null,
				"The approved release has no plugin for this harness and platform.", List.of());
		}
		String releaseTag = tag;
		return inScratch((download, extractRoot) ->
		{
			if (downgrade)
			{
				Optional<UpdateResult> refusal = refuseDataChange(release, active, download, extractRoot);
				if (refusal.isPresent())
					return refusal.get();
			}
			Path archive = download.resolve(asset.name());
			try
			{
				obtain(releaseTag, release.toString(), asset, archive);
			}
			catch (IOException e)
			{
				return result(Result.DOWNLOAD_FAILED, active.version(), null, e.getMessage(), List.of());
			}
			Path tree = extract(download, extractRoot, asset);
			StagedRelease.validate(tree, plugin, activation.pluginManifest(), platform);
			UpdateResult outcome = deliver(tree, plugin, request, home, active);
			if (outcome.result().exitStatus() != 0)
				return outcome;
			return keep(outcome, signed, archive, active);
		});
	}

	/**
	 * Downloads, verifies and installs another harness's plugin of a release while the harness lock is held.
	 *
	 * @param other   the other harness's activation provider
	 * @param release the release
	 * @param install installs the extracted plugin into the other harness
	 * @return the outcome
	 * @throws IOException    if the release cannot be staged or the install fails
	 * @throws TrustException if the release is not approved or the install is refused
	 */
	private UpdateResult installOtherRelease(PluginActivation other, ReleaseVersion release, StagedInstall install)
		throws IOException, TrustException
	{
		String tag = release.tag();
		SignedRelease signed;
		try
		{
			signed = signed(tag);
		}
		catch (IOException e)
		{
			return result(Result.DOWNLOAD_FAILED, null, null, e.getMessage(), List.of());
		}
		VerifiedManifest verified = signed.verified();
		ReleaseApproval.approveUpdate(verified, policy, release);
		PluginVersion plugin = verified.manifest().pluginVersion();
		ReleaseAsset asset = verified.manifest().findPlugin(other.harness(), platform).orElse(null);
		if (asset == null)
		{
			return result(Result.REFUSED, null, null, "Release " + release + " has no plugin for " + other.harness() +
				" and this platform.", List.of());
		}
		return inScratch((download, extractRoot) ->
		{
			try
			{
				obtain(tag, release.toString(), asset, download.resolve(asset.name()));
			}
			catch (IOException e)
			{
				return result(Result.DOWNLOAD_FAILED, null, null, e.getMessage(), List.of());
			}
			Path tree = extract(download, extractRoot, asset);
			StagedRelease.validate(tree, plugin, other.pluginManifest(), platform);
			return install.install(tree);
		});
	}

	/**
	 * Installs a plugin tree while the harness lock is held.
	 *
	 * @param tree    the plugin tree
	 * @param request the harness and the running installer's plugin folder
	 * @param home    the protected harness home
	 * @return the outcome
	 * @throws IOException    if the installation or the tree cannot be read
	 * @throws TrustException if the tree is invalid or a version is malformed
	 */
	private UpdateResult installTree(Path tree, InstallRequest request, Path home) throws IOException, TrustException
	{
		Active active = active(home);
		Path versionFile = tree.resolve(PluginDirectory.VERSION);
		StateFiles.requireNoLinks(versionFile);
		PluginVersion plugin = PluginVersion.parse(Files.readString(versionFile, StandardCharsets.UTF_8).strip());
		StagedRelease.validate(tree, plugin, activation.pluginManifest(), platform);
		if (active.isUpToDate(plugin))
			return upToDate(request, home, active);
		ReleaseVersion release = ReleaseVersion.parse(plugin.release());
		boolean downgrade = active.release().isPresent() && release.compareTo(active.release().get()) < 0;
		return inScratch((download, extractRoot) ->
		{
			if (downgrade)
			{
				Optional<UpdateResult> refusal = refuseDataChange(release, active, download, extractRoot);
				if (refusal.isPresent())
					return refusal.get();
			}
			return deliver(tree, plugin, request, home, active);
		});
	}

	/**
	 * Returns the harness's CAT version.
	 *
	 * @param home the protected harness home
	 * @return the version
	 * @throws IOException if the installation cannot be read
	 */
	private Active active(Path home) throws IOException
	{
		InstalledState installed = activation.installed(home);
		if (installed.version().isEmpty())
			return new Active(null, null, installed.consistent());
		String version = installed.version().get();
		PluginVersion plugin;
		try
		{
			plugin = PluginVersion.parse(version);
		}
		catch (IllegalArgumentException _)
		{
			// A version without a build predates version folders, so the install repairs it like a first install
			return new Active(version, null, false);
		}
		return new Active(version, plugin, installed.consistent());
	}

	/**
	 * Completes an install of the build that the harness already has, without asking the harness to install anything:
	 * installs the provider's companion files from the build's version folder, runs the data migrations that an
	 * earlier install left pending, and brings the permission and sandbox entries in line with the harness
	 * configuration, which may have changed since the last install. A change that needs the user's permission is
	 * refused when the user withholds it.
	 *
	 * @param request the harness and the running installer's plugin folder
	 * @param home    the protected harness home
	 * @param active  the harness's CAT version, which is consistently installed
	 * @return the outcome
	 * @throws IOException    if a check cannot read its input, or the data version file is reached through a
	 *                        symbolic link
	 * @throws TrustException if the recorded data version is malformed, or the build's migration registry is absent
	 *                        or malformed while the data is older than the build
	 */
	private UpdateResult upToDate(InstallRequest request, Path home, Active active) throws IOException, TrustException
	{
		HarnessSession session = request.session();
		HarnessIntegration integration = session.integration();
		PluginVersion plugin = active.plugin();
		HarnessInstallation installation = integration.installation(home);
		Path versionFolder = installation.versionFolder(plugin);
		Path migrationFile = activation.migrationVersionFile(home);
		DataMigration migration = DataMigration.plan(versionFolder, migrationFile, active.release().orElseThrow(),
			active.release());
		integration.validateConfiguration(home);
		Optional<String> consentRequest = integration.consentRequest(home, request.catLocks());
		if (consentRequest.isPresent() && !request.consent().grants(consentRequest.get()))
			return result(Result.REFUSED, active.version(), null, consentRequest.get(), List.of());
		PluginInstall steps = new PluginInstall(integration, session.cli(), session.replacer());
		PluginInstall.InstalledEntries entries;
		try
		{
			activation.installCompanions(home, versionFolder);
			migration.apply(versionFolder, migrationFile.getParent(), migrations, windows);
			entries = steps.installEntries(home, PluginInstall.installer(versionFolder, windows), installation,
				request.others(), request.catLocks(), consentRequest.isPresent());
		}
		catch (IOException e)
		{
			return stopped(plugin, active, e, versionFolder);
		}
		FolderRetention retention = new InstallCompletion(integration, installation, data, state).complete(plugin,
			active.plugin(), request.image());
		return result(Result.UP_TO_DATE, active.version(), null, retention.append(entries.withNotices(
			"CAT is up to date.")), List.of()).withExitDeletions(retention.exitDeletions());
	}

	/**
	 * Reports an install that stopped after it began changing the harness.
	 *
	 * @param plugin        the plugin version being installed
	 * @param active        the harness's CAT version before the install
	 * @param failure       why the install stopped
	 * @param versionFolder the version folder of the plugin version
	 * @return the outcome
	 */
	private UpdateResult stopped(PluginVersion plugin, Active active, IOException failure, Path versionFolder)
	{
		if (failure instanceof AccessDeniedException denied)
		{
			return result(Result.NOT_WRITABLE, active.version(), null, "Installing CAT " + plugin + " stopped because " +
				denied.getFile() + " is not writable; run the same install command again after fixing its permissions.",
				List.of(Path.of(denied.getFile())));
		}
		return result(Result.FAILED, active.version(), null, "Installing CAT " + plugin + " stopped: " +
			failure.getMessage() + "; run the same install command again to complete it.", List.of(versionFolder));
	}

	/**
	 * Installs a validated plugin tree into the harness: refuses a version folder that the running installer belongs
	 * to, and a change that needs the user's permission when the user withholds it, then writes the marketplace, asks
	 * the harness to install the version, installs the provider's companion files from the version folder, runs the
	 * data migrations that the release registers after the recorded data version and records each, trains its startup
	 * cache and rewrites the permission entries.
	 *
	 * @param tree    the validated plugin tree, which this method does not change
	 * @param plugin  the plugin version that the tree states
	 * @param request the harness and the running installer's plugin folder
	 * @param home    the protected harness home
	 * @param active  the harness's CAT version
	 * @return the outcome
	 * @throws IOException    if a check cannot read its input
	 * @throws TrustException if the recorded data version is malformed, or the release's migration registry is absent
	 *                        or malformed while the data is older than the release
	 */
	private UpdateResult deliver(Path tree, PluginVersion plugin, InstallRequest request, Path home, Active active)
		throws IOException, TrustException
	{
		HarnessSession session = request.session();
		HarnessIntegration integration = session.integration();
		String release = plugin.release();
		Path migrationFile = activation.migrationVersionFile(home);
		// Data migrations are registered by release, so the active plugin's build does not matter to them
		DataMigration migration = DataMigration.plan(tree, migrationFile, ReleaseVersion.parse(release),
			active.release());
		HarnessInstallation installation = integration.installation(home);
		Path versionFolder = installation.versionFolder(plugin);
		Path image = real(request.image());
		for (Path written : List.of(versionFolder, integration.marketplaceFolder(home, plugin)))
		{
			if (real(written).equals(image))
			{
				return result(Result.REFUSED, active.version(), null, "Installing CAT " + plugin + " would replace " +
					image + ", which the running installer belongs to; rerun the entry command to repair CAT.",
					List.of(image));
			}
		}
		integration.validateConfiguration(home);
		Optional<String> consentRequest = integration.consentRequest(home, request.catLocks());
		if (consentRequest.isPresent() && !request.consent().grants(consentRequest.get()))
			return result(Result.REFUSED, active.version(), null, consentRequest.get(), List.of());
		PluginInstall steps = new PluginInstall(integration, session.cli(), session.replacer());
		boolean startupCache;
		PluginInstall.InstalledEntries entries;
		try
		{
			steps.deliver(home, tree, plugin);
			activation.installCompanions(home, versionFolder);
			migration.apply(versionFolder, migrationFile.getParent(), migrations, windows);
			startupCache = trainer.train(versionFolder);
			entries = steps.installEntries(home, PluginInstall.installer(versionFolder, windows), installation,
				request.others(), request.catLocks(), consentRequest.isPresent());
		}
		catch (IOException e)
		{
			return stopped(plugin, active, e, versionFolder);
		}
		FolderRetention retention = new InstallCompletion(integration, installation, data, state).complete(plugin,
			active.plugin(), request.image());
		if (active.version() == null)
		{
			return result(Result.INSTALLED, plugin.toString(), null, retention.append(entries.withNotices(
				"CAT was installed.")), List.of()).withAotCache(startupCache).withExitDeletions(retention.exitDeletions());
		}
		return result(Result.UPDATED, plugin.toString(), active.version(), retention.append(entries.withNotices(
			"CAT was updated.")), List.of()).withAotCache(startupCache).withExitDeletions(retention.exitDeletions());
	}

	/**
	 * Returns the real path of a file, or its absolute, normalized path when it does not exist.
	 *
	 * @param path the path
	 * @return the path to compare
	 */
	private static Path real(Path path)
	{
		try
		{
			return path.toRealPath();
		}
		catch (IOException _)
		{
			return path.toAbsolutePath().normalize();
		}
	}

	/**
	 * Keeps a release that an install delivered, so that a later install can reuse it without downloading it, and
	 * discards every kept release other than it and the release that it replaced. A failure to keep it discards what
	 * was kept of the release and is reported in the outcome's message, because the release is installed regardless.
	 *
	 * @param outcome the install's outcome
	 * @param signed  the release's signed manifest
	 * @param archive the release's validated plugin archive
	 * @param active  the harness's CAT version before the install
	 * @return the outcome, with a diagnostic when the release could not be kept
	 */
	private UpdateResult keep(UpdateResult outcome, SignedRelease signed, Path archive, Active active)
	{
		String version = signed.verified().manifest().version().toString();
		try
		{
			cache.keep(version, signed, List.of(archive));
			List<String> kept = new ArrayList<>(List.of(version));
			active.release().ifPresent(release -> kept.add(release.toString()));
			cache.retain(kept);
			return outcome;
		}
		catch (IOException e)
		{
			try
			{
				cache.discard(version);
			}
			catch (IOException discardFailure)
			{
				e.addSuppressed(discardFailure);
			}
			return new UpdateResult(outcome.result(), outcome.harness(), outcome.version(), outcome.previousVersion(),
				outcome.message() + " The release could not be kept for reuse: " + e.getMessage(), outcome.paths(),
				outcome.consistent(), outcome.keyIds(), outcome.aotCache(), outcome.exitDeletions());
		}
	}

	/**
	 * Returns a release's signed manifest: the release source's, and the one that the installation keeps only when the
	 * source cannot be reached and the policy's keys still verify the kept one. The source comes first because a
	 * release that is published again from another commit keeps its tag, and only the source's manifest names the
	 * build that the tag now serves.
	 *
	 * @param tag the release tag
	 * @return the release
	 * @throws IOException    if the release source cannot supply it and the installation keeps no valid copy
	 * @throws TrustException if no key of the policy verifies the source's manifest, or it breaks its format
	 */
	private SignedRelease signed(String tag) throws IOException, TrustException
	{
		byte[] manifest;
		byte[] signature;
		try
		{
			manifest = source.manifest(tag);
			signature = source.signature(tag);
		}
		catch (IOException e)
		{
			String version = ReleaseVersion.parse(tag.substring(1)).toString();
			Optional<SignedRelease> kept = cache.release(version, policy);
			if (kept.isPresent())
				return kept.get();
			throw e;
		}
		return SignedRelease.verify(manifest, signature, policy);
	}

	/**
	 * Writes a release's archive: the copy that the installation keeps when it matches the signed manifest, and
	 * otherwise a download from the release source.
	 *
	 * @param tag         the release tag
	 * @param version     the release version
	 * @param asset       the archive's signed entry
	 * @param destination the file to write
	 * @throws IOException    if the archive cannot be obtained
	 * @throws TrustException if the release source refuses the download
	 */
	private void obtain(String tag, String version, ReleaseAsset asset, Path destination)
		throws IOException, TrustException
	{
		if (!cache.archive(version, asset, destination))
			source.download(tag, asset.name(), asset.size(), destination);
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
	 * Refuses an install of an older release when the active release registers a data migration after it. The active
	 * release's migration registry comes from its plugin archive, which its signed manifest authenticates.
	 *
	 * @param older       the older release
	 * @param active      the harness's CAT version, whose release is present
	 * @param download    the directory of downloaded archives
	 * @param extractRoot the directory for extracted trees
	 * @return the refusal, or empty when the older release uses the active release's data structures
	 * @throws IOException    if the active release's archive or registry is invalid
	 * @throws TrustException if the active release is not approved or its registry is malformed
	 */
	private Optional<UpdateResult> refuseDataChange(ReleaseVersion older, Active active, Path download,
		Path extractRoot) throws IOException, TrustException
	{
		ReleaseVersion activeRelease = active.release().orElseThrow();
		String tag = "v" + activeRelease;
		Path activeDownload = StateFiles.createTemporaryDirectory(download, "active-");
		ReleaseAsset asset;
		try
		{
			VerifiedManifest verified = signed(tag).verified();
			ReleaseApproval.approveUpdate(verified, policy, activeRelease);
			asset = verified.manifest().findPlugin(activation.harness(), platform).orElse(null);
			if (asset == null)
			{
				return Optional.of(result(Result.REFUSED, active.version(), null, "The active release " +
					activeRelease + " has no plugin for this harness and platform, so its data structures cannot be " +
					"compared with release " + older + ".", List.of()));
			}
			obtain(tag, activeRelease.toString(), asset, activeDownload.resolve(asset.name()));
		}
		catch (IOException e)
		{
			return Optional.of(result(Result.DOWNLOAD_FAILED, active.version(), null, e.getMessage(), List.of()));
		}
		Path tree = extract(activeDownload, extractRoot, asset);
		List<String> changes = StagedRelease.migrationsAfter(tree, older, activeRelease);
		if (changes.isEmpty())
			return Optional.empty();
		return Optional.of(result(Result.REFUSED, active.version(), null, "Release " + older + " is older than the " +
			"active release " + activeRelease + ", and CAT's data structures changed between them (" +
			String.join(", ", changes) + "). They are not backward compatible, so installing " + older +
			" would lose data; run install with the active release " + activeRelease + " or a later one instead.",
			List.of()));
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
			Optional.ofNullable(previousVersion), message, paths, outcome != Result.FAILED, keyIds());
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
	 * The harness's CAT version before an install.
	 *
	 * @param version    the version that the harness reports, or null when it has none
	 * @param plugin     the reported version as a plugin version, or null when the harness has none or reports a
	 *                   version without a build
	 * @param consistent true if the harness's installation of the version is complete
	 */
	private record Active(String version, PluginVersion plugin, boolean consistent)
	{
		/**
		 * Returns the release of the active plugin.
		 *
		 * @return the release, or empty when the harness has no plugin version with a build
		 * @throws IllegalArgumentException if the release is malformed
		 */
		Optional<ReleaseVersion> release()
		{
			if (plugin == null)
				return Optional.empty();
			try
			{
				return Optional.of(ReleaseVersion.parse(plugin.release()));
			}
			catch (TrustException e)
			{
				throw new IllegalArgumentException(e.getMessage(), e);
			}
		}

		/**
		 * Indicates whether the harness already has a build consistently installed.
		 *
		 * @param candidate the build
		 * @return true if the harness's version is {@code candidate} and its installation is complete
		 */
		boolean isUpToDate(PluginVersion candidate)
		{
			return candidate.equals(plugin) && consistent;
		}
	}

	/**
	 * An install that runs while the harness lock is held.
	 */
	@FunctionalInterface
	private interface HomeWork
	{
		/**
		 * Runs the install.
		 *
		 * @param home the protected harness home
		 * @return the outcome
		 * @throws IOException    if the install fails
		 * @throws TrustException if the install is refused
		 */
		UpdateResult run(Path home) throws IOException, TrustException;
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
