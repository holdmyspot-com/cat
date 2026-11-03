package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.release.TarGzExtractor;
import com.holdmyspot.cat.common.updater.activation.ActivationStep;
import com.holdmyspot.cat.common.updater.activation.AddDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceDirectory;
import com.holdmyspot.cat.common.updater.activation.ReplaceOwnedFiles;
import com.holdmyspot.cat.common.updater.activation.InstalledState;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.ManifestVerifier;
import com.holdmyspot.cat.common.updater.trust.ReleaseApproval;
import com.holdmyspot.cat.common.updater.trust.ReleaseAsset;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.VerifiedManifest;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Coordinates authenticated routine updates without executing staged code or editing harness configuration.
 */
public final class UpdateEngine
{
	private final UpdaterState state;
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
	 * @param dataDirectory protected updater data directory
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
	 * @param dataDirectory protected updater data directory
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
		requireThat(policy, "policy").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(activation, "activation").isNotNull();
		requireThat(platform, "platform").isNotBlank();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(lockWait, "lockWait").isGreaterThanOrEqualTo(Duration.ZERO);
		this.state = UpdaterState.of(dataDirectory, activation.harness());
		this.policy = policy;
		this.source = source;
		this.activation = activation;
		this.platform = platform;
		this.clock = clock;
		this.waiting = waiting;
		this.lockWait = lockWait;
	}

	/**
	 * Updates to a requested canonical tag, or the latest approved release when the tag is null.
	 *
	 * @param requestedTag a canonical tag, or null for latest
	 * @return the operation outcome
	 */
	public UpdateResult update(String requestedTag)
	{
		return operate(requestedTag, false);
	}

	/**
	 * Restores the previous successful activation.
	 *
	 * @return the outcome, refused when no activation is recorded
	 */
	public UpdateResult rollback()
	{
		return operate(null, true);
	}

	/**
	 * Reports installation state without acquiring a lock or creating state directories.
	 *
	 * @return the active version, consistency and trusted key identifiers
	 * @throws IOException if the installation cannot be inspected
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
	 * Holds the harness lock through recovery, validation and activation.
	 *
	 * @param requestedTag requested tag, or null for latest
	 * @param rollback whether to restore the previous activation
	 * @return the operation outcome
	 */
	private UpdateResult operate(String requestedTag, boolean rollback)
	{
		Path home = policy.harnesses().get(activation.harness());
		if (home == null)
			return result(Result.REFUSED, null, null, "Run the explicit CAT setup command for this harness.", List.of());
		ActivationTransaction transaction = new ActivationTransaction(state, activation, home);
		String active = null;
		try
		{
			Optional<ExclusiveFileLock> acquired = OperationLock.acquire(state, clock, lockWait, waiting);
			if (acquired.isEmpty())
				return result(Result.LOCKED, null, null, "Another CAT operation holds the harness lock.", List.of());
			try (ExclusiveFileLock lock = acquired.get())
			{
				if (!lock.isValid())
					return result(Result.LOCKED, null, null, "The harness lock is no longer valid.", List.of());
				return log(operateLocked(requestedTag, rollback, home, transaction));
			}
		}
		catch (ActivationTransaction.ActivationFailure e)
		{
			Result failure = Result.NOT_RESTORED;
			if (e.restored())
				failure = Result.RESTORED;
			return result(failure, active, null,
				e.getMessage(), transaction.paths());
		}
		catch (AccessDeniedException e)
		{
			return result(Result.NOT_WRITABLE, active, null,
				"The installation is not writable; use the explicit administrative setup path: " + e.getMessage(),
				List.of());
		}
		catch (TrustException | IOException | IllegalArgumentException e)
		{
			return result(Result.REFUSED, active, null, e.getMessage(), transaction.paths());
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return result(Result.LOCKED, active, null, "Interrupted while waiting for the harness lock.", List.of());
		}
	}

	/**
	 * Recovers and performs one operation under the held harness lock.
	 *
	 * @param requestedTag requested tag, or null
	 * @param rollback whether to roll back
	 * @param home protected provider home
	 * @param transaction activation transaction
	 * @return the operation outcome
	 * @throws IOException if local I/O fails
	 * @throws TrustException if approval fails
	 */
	private UpdateResult operateLocked(String requestedTag, boolean rollback, Path home,
		ActivationTransaction transaction) throws IOException, TrustException
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
			if (rollback)
			{
				String restored = transaction.rollback();
				return result(Result.ROLLED_BACK, restored, null, "The previous activation was restored.", List.of());
			}
			if (active == null)
				return result(Result.REFUSED, null, null, "Run the explicit CAT setup command for this harness.", List.of());
			cleanStaleStaging(activation.plan(home, active));
			return updateLocked(requestedTag, home, installed, transaction);
		}
		catch (ActivationTransaction.ActivationFailure e)
		{
			Result failure = Result.NOT_RESTORED;
			if (e.restored())
				failure = Result.RESTORED;
			return result(failure, active, null, e.getMessage(), transaction.paths());
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
	 * Authenticates the release before downloading or staging its plugin asset.
	 *
	 * @param requestedTag requested tag, or null for latest
	 * @param home protected provider home
	 * @param installed observed installation
	 * @param transaction recoverable activation coordinator
	 * @return the operation outcome
	 * @throws IOException if local validation or activation fails
	 * @throws TrustException if a release fails approval
	 */
	private UpdateResult updateLocked(String requestedTag, Path home, InstalledState installed,
		ActivationTransaction transaction) throws IOException, TrustException
	{
		String active = installed.version().orElseThrow();
		String tag;
		VerifiedManifest verified;
		try
		{
			tag = requestedTag;
			if (tag == null)
				tag = source.latestTag();
			if (tag == null || !tag.startsWith("v"))
				return result(Result.REFUSED, active, null, "A canonical v-prefixed release tag is required.", List.of());
			ReleaseVersion requested = ReleaseVersion.parse(tag.substring(1));
			verified = ManifestVerifier.verify(new ByteArrayInputStream(source.manifest(tag)),
				new ByteArrayInputStream(source.signature(tag)), policy);
			ReleaseApproval.approveUpdate(verified, policy, requested, ReleaseVersion.parse(active));
		}
		catch (IOException e)
		{
			return result(Result.DOWNLOAD_FAILED, active, null, e.getMessage(), List.of());
		}
		String version = verified.manifest().version().toString();
		if (version.equals(active) && installed.consistent())
			return result(Result.UP_TO_DATE, active, transaction.previousVersion().orElse(null),
				"CAT is up to date.", List.of());
		ReleaseAsset asset = verified.manifest().findPlugin(activation.harness(), platform).orElse(null);
		if (asset == null)
			return result(Result.REFUSED, active, null,
				"The approved release has no plugin for this harness and platform.", List.of());
		Path operation = state.directory().resolve("download");
		Path extractRoot = state.directory().resolve("extract");
		StateFiles.createDirectories(operation);
		UpdateResult completed = null;
		IOException cleanupFailure = null;
		Exception operationFailure = null;
		try
		{
			completed = stageRelease(tag, asset, operation, extractRoot, home, active, version, transaction);
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
				StateFiles.delete(operation);
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
				cleanupFailure.getMessage(), List.of(operation, extractRoot), completed.consistent(), completed.keyIds());
		}
		return completed;
	}

	/**
	 * Downloads and validates one release workspace before passing it to the activation journal.
	 *
	 * @param tag authenticated release tag
	 * @param asset authenticated asset
	 * @param operation download directory
	 * @param extractRoot extraction parent
	 * @param home protected provider home
	 * @param active installed version
	 * @param version approved new version
	 * @param transaction activation coordinator
	 * @return download failure or completed activation
	 * @throws IOException if local validation or activation fails
	 * @throws TrustException if staged metadata is refused
	 */
	private UpdateResult stageRelease(String tag, ReleaseAsset asset, Path operation, Path extractRoot,
		Path home, String active, String version, ActivationTransaction transaction) throws IOException, TrustException
	{
		Path archive = operation.resolve(asset.name());
		try
		{
			source.download(tag, asset.name(), asset.size(), archive);
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
		Path tree = extract.resolve(asset.topDirectory());
		StagedRelease.validate(tree, version, activation.pluginManifest(), platform);
		Path migrationFile = activation.migrationVersionFile(home);
		StateFiles.requireNoLinks(migrationFile);
		StagedRelease.requireNoPendingMigration(tree, migrationFile, active, version);
		transaction.activate(tree, activation.plan(home, version), migrationFile, active, version);
		String message = "CAT was updated.";
		if (transaction.warning().isPresent())
			message += " " + transaction.warning().get();
		return result(Result.UPDATED, version, active, message, List.of());
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
}
