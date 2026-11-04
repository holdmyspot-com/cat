package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.release.TarGzExtractor;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceOwnedFiles;
import com.holdmyspot.cat.common.updater.engine.ActivationTransaction.Completion;
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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Coordinates authenticated plugin installs and updates without executing staged code. Harness configuration changes
 * only through the harness integration's registration.
 */
public final class UpdateEngine
{
	/**
	 * The largest recorded highest version, including its newline.
	 */
	private static final int MAXIMUM_VERSION_BYTES = 128;
	/**
	 * The plugin-relative file that names the Java runtime the plugin runs on.
	 */
	private static final Path BINDING_FILE = Path.of("client/runtime-binding");
	private final UpdaterState state;
	private final Path runtime;
	private final TrustPolicy policy;
	private final ReleaseSource source;
	private final PluginActivation activation;
	private final String platform;
	private final Clock clock;
	private final Waiting waiting;
	private final Duration lockWait;

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
		requireThat(dataDirectory, "dataDirectory").isNotNull();
		requireThat(policy, "policy").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(activation, "activation").isNotNull();
		requireThat(platform, "platform").isNotBlank();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(lockWait, "lockWait").isGreaterThanOrEqualTo(Duration.ZERO);
		this.state = UpdaterState.of(dataDirectory, activation.harness());
		this.runtime = dataDirectory.toAbsolutePath().normalize().resolve("runtime");
		this.policy = policy;
		this.source = source;
		this.activation = activation;
		this.platform = platform;
		this.clock = clock;
		this.waiting = waiting;
		this.lockWait = lockWait;
	}

	/**
	 * Updates the harness's plugin to an approved release, or registers the current plugin again when it is already
	 * that release. Refuses when the harness has no plugin.
	 *
	 * @param requestedTag the canonical v-prefixed tag, or null for the latest release
	 * @param registration registers the activated plugin with the harness
	 * @return the outcome
	 * @throws NullPointerException if {@code registration} is null
	 */
	public UpdateResult update(String requestedTag, Registration registration)
	{
		requireThat(registration, "registration").isNotNull();
		return operate(Mode.UPDATE, requestedTag, null, registration);
	}

	/**
	 * Adds an approved release where the harness has no plugin and registers it, or updates the plugin when the
	 * harness has one. A partial installation that the harness does not report is deleted first. Refuses a release
	 * older than the highest version that the harness has had.
	 *
	 * @param requestedTag the canonical v-prefixed tag, or null for the latest release
	 * @param registration registers the activated plugin with the harness
	 * @return the outcome
	 * @throws NullPointerException if {@code registration} is null
	 */
	public UpdateResult install(String requestedTag, Registration registration)
	{
		requireThat(registration, "registration").isNotNull();
		return operate(Mode.INSTALL, requestedTag, null, registration);
	}

	/**
	 * Activates the signed release of one version for the user's setup, also when the harness already has that
	 * version, and registers it. Setup may install a version older than the active one.
	 *
	 * @param version      the canonical version that setup comes from
	 * @param registration registers the activated plugin with the harness
	 * @return the outcome
	 * @throws NullPointerException if any of the arguments are null
	 */
	public UpdateResult setUp(String version, Registration registration)
	{
		requireThat(version, "version").isNotBlank();
		requireThat(registration, "registration").isNotNull();
		return operate(Mode.SETUP, "v" + version, null, registration);
	}

	/**
	 * Activates a local plugin tree that the user named for setup, in place of a signed release. The tree is validated,
	 * checked against the runtime and bound to it like a signed release, and is never changed.
	 *
	 * @param tree         the plugin tree
	 * @param version      the canonical version that setup comes from, which the tree must declare
	 * @param registration registers the activated plugin with the harness
	 * @return the outcome
	 * @throws NullPointerException if any of the arguments are null
	 */
	public UpdateResult setUpFrom(Path tree, String version, Registration registration)
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(version, "version").isNotBlank();
		requireThat(registration, "registration").isNotNull();
		return operate(Mode.SETUP, "v" + version, tree, registration);
	}

	/**
	 * Restores the installation retained by the last successful update.
	 *
	 * @return the outcome
	 */
	public UpdateResult rollback()
	{
		return operate(Mode.ROLLBACK, null, null, null);
	}

	/**
	 * Reports active and previous versions without writes.
	 *
	 * @return the status outcome; its version is empty when the harness has no plugin
	 * @throws IOException if provider state is unreadable
	 */
	public UpdateResult status() throws IOException
	{
		Path home = policy.harnesses().get(activation.harness());
		if (home == null)
			return result(Result.STATUS, null, null, "Run the explicit CAT setup command for this harness.", List.of());
		InstalledState installed = activation.installed(home);
		return new UpdateResult(Result.STATUS, activation.harness(), installed.version(),
			new ActivationTransaction(state, activation, home).previousVersion(),
			"The installation state was read.", List.of(), installed.consistent(), keyIds());
	}

	/**
	 * Runs one operation under the harness lock and converts every failure into an outcome.
	 *
	 * @param mode         the operation
	 * @param requestedTag the requested tag, or null for the latest release or a rollback
	 * @param localTree    the user's local plugin tree, or null for a signed release
	 * @param registration registers the activated plugin, or null for a rollback
	 * @return the outcome
	 */
	private UpdateResult operate(Mode mode, String requestedTag, Path localTree, Registration registration)
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
				return log(operateLocked(mode, requestedTag, localTree, registration, home, transaction));
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
	 * @param registration registers the activated plugin, or null for a rollback
	 * @param home         the protected harness home
	 * @param transaction  the harness's activation transaction
	 * @return the outcome
	 * @throws IOException    if the installation cannot be read
	 * @throws TrustException if the installed version is malformed
	 */
	private UpdateResult operateLocked(Mode mode, String requestedTag, Path localTree, Registration registration,
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
		InstalledState installed = activation.installed(home);
		String active = installed.version().orElse(null);
		try
		{
			if (mode == Mode.ROLLBACK)
			{
				String restored = transaction.rollback();
				return result(Result.ROLLED_BACK, restored, null, "The previous activation was restored.", List.of());
			}
			if (active == null && mode == Mode.UPDATE)
			{
				return result(Result.REFUSED, null, null, "CAT is not installed in this harness; run install to add it.",
					List.of());
			}
			if (active != null)
				cleanStaleStaging(activation.plan(home, active));
			return deliver(mode, requestedTag, localTree, registration, home, installed, transaction);
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
	 * @param registration registers the activated plugin
	 * @param home         the protected harness home
	 * @param installed    the harness's installation state
	 * @param transaction  the harness's activation transaction
	 * @return the outcome
	 * @throws IOException    if staging or activation fails
	 * @throws TrustException if the release is not approved
	 */
	private UpdateResult deliver(Mode mode, String requestedTag, Path localTree, Registration registration,
		Path home, InstalledState installed, ActivationTransaction transaction) throws IOException, TrustException
	{
		String active = installed.version().orElse(null);
		if (localTree != null)
		{
			String version = requestedTag.substring(1);
			return inScratch((download, extractRoot) ->
			{
				Path tree = StateFiles.createTemporaryDirectory(extractRoot, "local-").resolve("plugin");
				ActivationTransaction.copyTree(localTree, tree);
				return activateTree(tree, version, active, registration, home, transaction);
			});
		}
		String tag = requestedTag;
		VerifiedManifest verified;
		try
		{
			if (tag == null)
				tag = source.latestTag();
			if (tag == null || !tag.startsWith("v"))
				return result(Result.REFUSED, active, null, "A canonical v-prefixed release tag is required.", List.of());
			ReleaseVersion requested = ReleaseVersion.parse(tag.substring(1));
			verified = ManifestVerifier.verify(new ByteArrayInputStream(source.manifest(tag)),
				new ByteArrayInputStream(source.signature(tag)), policy);
			ReleaseVersion compared = null;
			if (mode != Mode.SETUP && active != null)
				compared = ReleaseVersion.parse(active);
			ReleaseApproval.approveUpdate(verified, policy, requested, compared);
		}
		catch (IOException e)
		{
			return result(Result.DOWNLOAD_FAILED, active, null, e.getMessage(), List.of());
		}
		String version = verified.manifest().version().toString();
		if (mode != Mode.SETUP && version.equals(active) && installed.consistent())
		{
			registration.integration().register(home, registration.cli());
			return result(Result.UP_TO_DATE, active, transaction.previousVersion().orElse(null),
				"CAT is up to date.", List.of());
		}
		if (mode == Mode.INSTALL && active == null)
		{
			Optional<ReleaseVersion> highest = highestVersion();
			if (highest.isPresent() && verified.manifest().version().compareTo(highest.get()) < 0)
			{
				return result(Result.REFUSED, null, null, "Release " + version + " is older than " + highest.get() +
					", the highest version this harness has had; run rollback, or the entry command to choose an " +
					"older release.", List.of());
			}
		}
		ReleaseAsset asset = verified.manifest().findPlugin(activation.harness(), platform).orElse(null);
		if (asset == null)
			return result(Result.REFUSED, active, null,
				"The approved release has no plugin for this harness and platform.", List.of());
		String releaseTag = tag;
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
			ReleaseDownload.validate(archive, asset);
			Path extract = StateFiles.createTemporaryDirectory(extractRoot, "release-");
			try (InputStream input = Files.newInputStream(archive))
			{
				TarGzExtractor.extract(input, extract, asset.topDirectory());
			}
			return activateTree(extract.resolve(asset.topDirectory()), version, active, registration, home,
				transaction);
		});
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
	 * and activates it: as an install where the harness has no plugin, otherwise as an update. The plugin is
	 * registered with the harness before the activation is kept.
	 *
	 * @param tree         the staged release tree, which this method may change
	 * @param version      the release version
	 * @param active       the active version, or null when the harness has no plugin
	 * @param registration registers the activated plugin
	 * @param home         the protected harness home
	 * @param transaction  the harness's activation transaction
	 * @return the outcome
	 * @throws IOException    if validation, the runtime check, activation or registration fails
	 * @throws TrustException if a version is malformed or a pending migration requires explicit installation
	 */
	private UpdateResult activateTree(Path tree, String version, String active, Registration registration,
		Path home, ActivationTransaction transaction) throws IOException, TrustException
	{
		StagedRelease.validate(tree, version, activation.pluginManifest(), platform);
		Path migrationFile = activation.migrationVersionFile(home);
		StateFiles.requireNoLinks(migrationFile);
		if (active != null)
			StagedRelease.requireNoPendingMigration(tree, migrationFile, active, version);
		else if (Files.exists(migrationFile, NOFOLLOW_LINKS))
			StagedRelease.requireNoPendingMigration(tree, migrationFile, version, version);
		requireRuntime(tree, version);
		Files.writeString(tree.resolve(BINDING_FILE), runtime + "\n", StandardCharsets.UTF_8);
		Completion register = () -> registration.integration().register(home, registration.cli());
		if (active == null)
		{
			for (Path path : activation.removalPaths(home))
				LinkSafeDeletion.delete(path, home);
			transaction.install(tree, activation.installPlan(home, version), migrationFile, version, register);
			recordHighestVersion(version);
			return result(Result.INSTALLED, version, null, withWarning("CAT was installed.", transaction),
				List.of());
		}
		transaction.activate(tree, activation.plan(home, version), migrationFile, active, version, register);
		recordHighestVersion(version);
		return result(Result.UPDATED, version, active, withWarning("CAT was updated.", transaction), List.of());
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
	 * Returns the highest version ever activated for the harness.
	 *
	 * @return the version, or empty if none was recorded
	 * @throws IOException    if the record is unreadable
	 * @throws TrustException if the record is malformed
	 */
	private Optional<ReleaseVersion> highestVersion() throws IOException, TrustException
	{
		Path file = state.highestVersion();
		StateFiles.requireNoLinks(file);
		if (!Files.exists(file, NOFOLLOW_LINKS))
			return Optional.empty();
		String text = BoundedInput.readUtf8(file, MAXIMUM_VERSION_BYTES, "The highest activated version");
		return Optional.of(ReleaseVersion.parse(text.strip()));
	}

	/**
	 * Records an activated version when it is higher than every earlier one.
	 *
	 * @param version the activated version
	 * @throws IOException    if the record cannot be read or written
	 * @throws TrustException if the record or version is malformed
	 */
	private void recordHighestVersion(String version) throws IOException, TrustException
	{
		ReleaseVersion activated = ReleaseVersion.parse(version);
		Optional<ReleaseVersion> highest = highestVersion();
		if (highest.isPresent() && highest.get().compareTo(activated) >= 0)
			return;
		StateFiles.write(state.highestVersion(), (activated + "\n").getBytes(StandardCharsets.UTF_8));
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
		SETUP,
		/**
		 * Restore the installation that the last update retained.
		 */
		ROLLBACK
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
