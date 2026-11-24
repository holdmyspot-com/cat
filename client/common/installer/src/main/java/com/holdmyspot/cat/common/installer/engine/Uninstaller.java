package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes one harness's CAT installation: the permission entries that install added, the harness registration, the
 * plugin files, including every version folder, and the files of the uninstalling user's home folder that hold a
 * secret for the harness's CAT sessions alone. CAT's plugin data, which holds the policy, the installer's state and the
 * release cache, stays unless the user asks to delete it. A file or folder that a step would delete and that is already
 * absent is no failure, and an unknown home folder only leaves the user's secret files, which the outcome then
 * describes. Each completed step is recorded in {@code state/uninstall.json}, so that a later uninstall continues with
 * the first step that did not complete; an uninstall that completes leaves no record, so that an uninstall after a
 * later install runs every step again.
 */
public final class Uninstaller
{
	/**
	 * The step that removes the permission entries that install added.
	 */
	private static final String PERMISSIONS = "permissions";
	/**
	 * The step that unregisters the plugin through the harness program.
	 */
	private static final String UNREGISTER = "unregister";
	/**
	 * The step that deletes the plugin files.
	 */
	private static final String PLUGIN = "plugin";
	/**
	 * The step that removes the files of the user's home folder that hold a secret for the harness's CAT sessions
	 * alone.
	 */
	private static final String SECRETS = "secrets";
	/**
	 * The steps in the order in which they run. The permission entries are removed first because their record is in
	 * the plugin data, which the harness deletes when it unregisters the plugin without keeping the data.
	 */
	private static final List<String> STEPS = List.of(PERMISSIONS, UNREGISTER, PLUGIN, SECRETS);
	/**
	 * The largest supported progress record.
	 */
	private static final int MAXIMUM_JOURNAL_BYTES = 1024 * 1024;
	/**
	 * The progress record, relative to the installer data directory.
	 */
	private static final String JOURNAL = "state/uninstall.json";
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private final Installation installation;
	private final PluginActivation activation;
	private final HarnessIntegration integration;
	private final Path running;
	/**
	 * The uninstalling user's home folder, whose files that hold a secret for the harness's CAT sessions alone the
	 * uninstall removes, or null when the environment names none, so that the uninstall cannot find those files.
	 */
	private final Path userHome;
	private final Clock clock;
	private final Waiting waiting;
	private final InstallerState state;
	private final Path journal;
	/**
	 * The sentences that tell the user what this uninstall may have left in place: recorded entries that it kept, and
	 * the user's secret files that it could not look for.
	 */
	private final List<String> retained = new ArrayList<>();
	/**
	 * The folders of plugin files that hold the running installer's files, which are deleted once the installer no
	 * longer needs them.
	 */
	private final List<Path> exitDeletions = new ArrayList<>();
	/**
	 * The files of the user's home folder that hold a secret for the harness's CAT sessions alone, which this uninstall
	 * removed.
	 */
	private final List<Path> removedSecrets = new ArrayList<>();

	/**
	 * Creates an uninstaller.
	 *
	 * @param data       the plugin data directory of the installation, which holds its policy and the installer's state
	 * @param policy     the installation's policy
	 * @param activation the harness's activation provider
	 * @param session    unregisters the plugin and removes the permission entries
	 * @param running    the version folder that the running installer belongs to
	 * @param userHome   the uninstalling user's absolute home folder, or null when the environment names none
	 * @param clock      operation time source
	 * @param waiting    creates pauses between lock attempts
	 * @throws NullPointerException     if any of the arguments other than {@code userHome} are null
	 * @throws IllegalArgumentException if {@code userHome} is relative
	 */
	public Uninstaller(Path data, TrustPolicy policy, PluginActivation activation, HarnessSession session,
		Path running, Path userHome, Clock clock, Waiting waiting)
	{
		this(new Installation(data, policy.harnesses().get(activation.harness()), null, policy, session), activation,
			session.integration(), running, userHome, clock, waiting);
	}

	/**
	 * Creates an uninstaller for an installed version folder whose policy is missing. It removes the plugin and
	 * applicable user secrets, and reports how to finish unregistering the plugin and cleaning up settings.
	 *
	 * @param layout the installation located from the running version folder
	 * @param activation the harness's activation provider
	 * @param integration the harness's integration provider
	 * @param running the running version folder
	 * @param userHome the uninstalling user's absolute home folder, or null when the environment names none
	 * @param clock operation time source
	 * @param waiting creates pauses between lock attempts
	 * @throws NullPointerException if any argument other than {@code userHome} is null
	 * @throws IllegalArgumentException if {@code userHome} is relative
	 */
	public Uninstaller(HarnessInstallation layout, PluginActivation activation, HarnessIntegration integration,
		Path running, Path userHome, Clock clock, Waiting waiting)
	{
		this(new Installation(layout.dataDirectory(), layout.home(), layout.policy(), null, null), activation,
			integration, running, userHome, clock, waiting);
	}

	/**
	 * Configures the common removal steps and their paths.
	 *
	 * @param installation the authority for the removal steps
	 * @param activation the harness's activation provider
	 * @param integration the harness's integration provider
	 * @param running the running version folder
	 * @param userHome the uninstalling user's absolute home folder, or null when the environment names none
	 * @param clock operation time source
	 * @param waiting creates pauses between lock attempts
	 */
	private Uninstaller(Installation installation, PluginActivation activation, HarnessIntegration integration,
		Path running, Path userHome, Clock clock, Waiting waiting)
	{
		Path data = installation.data();
		requireThat(data, "data").isNotNull();
		requireThat(activation, "activation").isNotNull();
		requireThat(integration, "integration").isNotNull();
		requireThat(running, "running").isNotNull();
		if (userHome != null)
			requireThat(userHome, "userHome").isAbsolute();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		this.installation = installation;
		this.activation = activation;
		this.integration = integration;
		this.running = running;
		this.userHome = userHome;
		this.clock = clock;
		this.waiting = waiting;
		this.state = InstallerState.of(data, activation.harness());
		this.journal = data.toAbsolutePath().normalize().resolve(JOURNAL);
	}

	/**
	 * Runs every step that an earlier uninstall did not complete. When the harness program that install recorded no
	 * longer exists, the plugin cannot be unregistered, so that step is skipped without being recorded as completed and
	 * every other step still runs. When the environment named no home folder for the user, the step that removes the
	 * user's secret files adds the integration's sentences about the files that it could not look for to the outcome's
	 * message instead. The harness is asked to keep CAT's plugin data unless {@code deleteData} is true; then the plugin
	 * data is deleted once every step completed. Without a policy, unregistration and settings cleanup are skipped,
	 * and the result explains how to finish them. Plugin data created for that uninstall's lock is then removed.
	 * <p>
	 * A folder of plugin files that holds the running installer's version folder is not deleted while the installer
	 * runs; the outcome's {@link UpdateResult#exitDeletions() exit deletions} name it instead.
	 *
	 * @param deleteData true to delete the plugin's data directory, false to keep it
	 * @return {@code uninstalled} when every step that it could run completed; otherwise the outcome names the failed
	 * 	step and the next uninstall continues with it
	 */
	public UninstallResult uninstall(boolean deleteData)
	{
		Path recorded = installation.home();
		if (recorded == null)
		{
			return failure(Result.REFUSED, "CAT's install has not recorded this harness, so there is nothing to " +
				"uninstall.", List.of());
		}
		retained.clear();
		exitDeletions.clear();
		removedSecrets.clear();
		String step = STEPS.getFirst();
		boolean unregistered;
		boolean withoutPolicy = installation.missingPolicy() != null;
		boolean removeData;
		Path home;
		try
		{
			home = LinkSafeDeletion.resolveRoot(recorded);
			boolean recreateData = withoutPolicy && Files.notExists(installation.data(), NOFOLLOW_LINKS);
			Optional<ExclusiveFileLock> acquired = OperationLock.acquire(state, clock, OperationLock.WAIT, waiting);
			if (acquired.isEmpty())
				return failure(Result.LOCKED, "Another CAT operation holds the harness lock.", List.of());
			try (ExclusiveFileLock lock = acquired.get())
			{
				if (!lock.isValid())
					return failure(Result.LOCKED, "The harness lock is no longer valid.", List.of());
				if (withoutPolicy && !Files.notExists(installation.missingPolicy(), NOFOLLOW_LINKS))
				{
					return failure(Result.REFUSED, "The installer could not confirm that " +
						installation.missingPolicy() + " is missing. Run the same uninstall command again.",
						List.of(installation.missingPolicy()));
				}
				Progress progress = readProgress(journal).orElseGet(() -> new Progress(new ArrayList<>(), recreateData));
				removeData = deleteData || (withoutPolicy && progress.recreatedData());
				if (recreateData)
					writeProgress(progress);
				if (withoutPolicy)
					retained.addAll(integration.describeUninstallWithoutPolicy(home, !removeData));
				if (withoutPolicy && progress.completed().contains(PLUGIN))
					collectDeferredDeletions(home);
				boolean programExists = programExists();
				for (String next : STEPS)
				{
					if (withoutPolicy && next.equals(PERMISSIONS))
						continue;
					if (progress.completed().contains(next) || (next.equals(UNREGISTER) && !programExists))
						continue;
					step = next;
					run(next, home, deleteData);
					progress.completed().add(next);
					writeProgress(progress);
				}
				unregistered = progress.completed().contains(UNREGISTER);
				if (!removeData)
					Files.deleteIfExists(journal);
			}
		}
		catch (LinkSafeDeletion.Refused e)
		{
			return failure(Result.REFUSED, "Uninstall stopped at the " + step + " step: " + e.getMessage(), List.of());
		}
		catch (AccessDeniedException e)
		{
			return failure(Result.NOT_WRITABLE, "Uninstall stopped at the " + step + " step because " + e.getFile() +
				" is not writable or is in use, perhaps by a running CAT session; run uninstall again after closing the " +
				"session or fixing its permissions.", List.of(Path.of(e.getFile())));
		}
		catch (IOException | IllegalArgumentException e)
		{
			return failure(Result.UNINSTALL_INCOMPLETE, "Uninstall stopped at the " + step + " step: " +
				inUse(e) + "; run the same uninstall command again to continue.", List.of(journal));
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return failure(Result.LOCKED, "Interrupted while waiting for the harness lock.", List.of());
		}
		if (removeData)
		{
			// The harness lock is in the plugin data, so the data is deleted once the lock is released
			Path directory = activation.dataDirectory(home);
			try
			{
				LinkSafeDeletion.delete(directory, home, waiting);
			}
			catch (IOException | IllegalArgumentException e)
			{
				return failure(Result.UNINSTALL_INCOMPLETE, "CAT was removed from this harness, but its plugin data " +
					directory + " could not be deleted completely: " + inUse(e) + "; delete it yourself or run " +
					"the same uninstall command again.", List.of(directory));
			}
		}
		if (withoutPolicy && removeData && !deleteData)
			retained.add("CAT removed the temporary plugin data directory created for the uninstall lock.");
		return uninstalled(activation.harness(), keyIds(), unregistered, withoutPolicy, retained, removedSecrets,
			exitDeletions);
	}

	/**
	 * Discards the progress of an earlier uninstall, because an operation that installed CAT again makes it stale: a
	 * later uninstall must run every step again.
	 *
	 * @param data the installation's installer data directory
	 * @throws IOException if the progress cannot be deleted
	 */
	static void discard(Path data) throws IOException
	{
		Files.deleteIfExists(data.toAbsolutePath().normalize().resolve(JOURNAL));
	}

	/**
	 * Builds the outcome of an uninstall that completed every step that it could run.
	 *
	 * @param harness       the installation's harness
	 * @param keyIds        the identifiers of the policy's keys
	 * @param unregistered  true if the plugin was unregistered through the harness program
	 * @param withoutPolicy true if the policy was missing and the report already explains the skipped steps
	 * @param retained      the sentences that tell the user what the uninstall may have left in place
	 * @param secrets       the files of the user's home folder that held a secret and that the uninstall removed
	 * @param exitDeletions the folders to delete once the installer no longer needs its own files
	 * @return the outcome, whose message names each removed secret file and whose paths are those files
	 */
	private static UninstallResult uninstalled(String harness, List<String> keyIds, boolean unregistered,
		boolean withoutPolicy, List<String> retained, List<Path> secrets, List<Path> exitDeletions)
	{
		StringJoiner message = new StringJoiner(" ");
		if (unregistered || withoutPolicy)
			message.add("CAT was removed from this harness.");
		else
		{
			message.add("CAT was removed from this harness, but the " + harness + " program that install recorded no " +
				"longer exists, so the harness may still list the plugin.");
		}
		for (Path secret : secrets)
			message.add("Deleted " + secret + ", which held a secret that only CAT's sessions used.");
		retained.forEach(message::add);
		return new UninstallResult(new UpdateResult(Result.UNINSTALLED, harness, Optional.empty(), Optional.empty(),
			message.toString(), List.copyOf(secrets), true, keyIds).withExitDeletions(exitDeletions), unregistered);
	}

	/**
	 * Returns whether the harness program that install recorded still exists and is executable.
	 *
	 * @return true if the program can run
	 */
	private boolean programExists()
	{
		if (installation.policy() == null)
			return false;
		Optional<HarnessCommand> command = installation.policy().command(activation.harness());
		if (command.isEmpty())
			return false;
		Path program = Path.of(command.get().command().getFirst());
		return Files.isRegularFile(program) && Files.isExecutable(program);
	}

	/**
	 * Runs one step.
	 *
	 * @param step       the step's name
	 * @param home       the harness home
	 * @param deleteData true to let the harness delete the plugin's data directory
	 * @throws IOException if the step fails
	 */
	private void run(String step, Path home, boolean deleteData) throws IOException
	{
		HarnessSession session = installation.session();
		switch (step)
		{
			case PERMISSIONS ->
			{
				Map<String, List<String>> recorded = PermissionRecord.read(state.permissions());
				if (!recorded.isEmpty())
				{
					integration.removePermissions(home, recorded, session.replacer());
					retained.addAll(integration.describeRetained(home, recorded));
				}
				Files.deleteIfExists(state.permissions());
				Files.deleteIfExists(state.pendingPermissions());
			}
			case UNREGISTER -> integration.unregister(home, session.cli(), !deleteData);
			case PLUGIN -> deletePlugin(home);
			case SECRETS ->
			{
				if (userHome == null)
					retained.addAll(integration.describeUnsearchedUserSecrets());
				else
					removedSecrets.addAll(integration.removeUserSecrets(userHome));
			}
			default -> throw new AssertionError(step);
		}
	}

	/**
	 * Describes a failure, adding that a running CAT session may be using the folder when the folder still holds files.
	 *
	 * @param failure the failure
	 * @return the description
	 */
	private static String inUse(Exception failure)
	{
		if (failure instanceof DirectoryNotEmptyException)
			return failure.getMessage() + " still holds files that are in use, perhaps by a running CAT session";
		return failure.getMessage();
	}

	/**
	 * Deletes the plugin files, leaving each folder that holds the running installer's version folder for deletion
	 * once the installer no longer needs its own files.
	 *
	 * @param home the harness home
	 * @throws IOException if the files cannot be deleted
	 */
	private void deletePlugin(Path home) throws IOException
	{
		Path runningFolder = real(running);
		for (Path path : activation.removalPaths(home))
		{
			if (runningFolder.startsWith(path))
			{
				if (Files.exists(path, NOFOLLOW_LINKS))
					exitDeletions.add(path);
			}
			else
				LinkSafeDeletion.delete(path, home, waiting);
		}
	}

	/**
	 * Recovers the running version folder's deferred deletion after a prior invocation completed the plugin step.
	 *
	 * @param home the harness home
	 * @throws IOException if the plugin paths cannot be inspected
	 */
	private void collectDeferredDeletions(Path home) throws IOException
	{
		Path runningFolder = real(running);
		for (Path path : activation.removalPaths(home))
		{
			if (runningFolder.startsWith(path) && Files.exists(path, NOFOLLOW_LINKS))
				exitDeletions.add(path);
		}
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
	 * Reads the progress of an earlier uninstall.
	 *
	 * @param journal the progress record
	 * @return the progress, or empty if no uninstall recorded any
	 * @throws IOException if the progress record is unreadable or malformed
	 */
	private static Optional<Progress> readProgress(Path journal) throws IOException
	{
		StateFiles.requireNoLinks(journal);
		if (!Files.exists(journal, NOFOLLOW_LINKS))
			return Optional.empty();
		JsonNode root;
		try
		{
			root = MAPPER.readTree(BoundedInput.readUtf8(journal, MAXIMUM_JOURNAL_BYTES, "The uninstall progress"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The uninstall progress is not valid JSON: " + journal, e);
		}
		if (root == null || !root.isObject() || !root.path("format").isInt() || root.path("format").intValue() != 1)
			throw new IOException("The uninstall progress must be a format 1 JSON object: " + journal);
		List<String> completed = new ArrayList<>();
		for (JsonNode step : root.path("completed"))
		{
			if (!step.isString() || !STEPS.contains(step.stringValue()))
				throw new IOException("The uninstall progress names an unknown step: " + journal);
			completed.add(step.stringValue());
		}
		return Optional.of(new Progress(completed, root.path("recreated_data").asBoolean(false)));
	}

	/**
	 * Records the uninstall's progress.
	 *
	 * @param progress the progress
	 * @throws IOException if the record cannot be written
	 */
	private void writeProgress(Progress progress) throws IOException
	{
		ObjectNode root = MAPPER.createObjectNode();
		root.put("format", 1);
		if (progress.recreatedData())
			root.put("recreated_data", true);
		progress.completed().forEach(root.putArray("completed")::add);
		// Unregistering without keeping the data deletes the installer's state with the rest of the plugin data
		StateFiles.createDirectories(journal.getParent());
		StateFiles.write(journal, (MAPPER.writeValueAsString(root) + "\n").getBytes(UTF_8));
	}

	/**
	 * Builds the outcome of an uninstall that did not complete.
	 *
	 * @param outcome the result category
	 * @param message the user-facing explanation
	 * @param paths   affected paths
	 * @return the outcome
	 */
	private UninstallResult failure(Result outcome, String message, List<Path> paths)
	{
		return new UninstallResult(result(outcome, message, paths), false);
	}

	/**
	 * Builds an outcome with this policy's key identifiers.
	 *
	 * @param outcome the result category
	 * @param message the user-facing explanation
	 * @param paths   affected paths
	 * @return the outcome
	 */
	private UpdateResult result(Result outcome, String message, List<Path> paths)
	{
		return new UpdateResult(outcome, activation.harness(), Optional.empty(), Optional.empty(), message, paths,
			true, keyIds());
	}

	/**
	 * Returns the key identifiers from the installer policy, or an empty list if it was missing.
	 *
	 * @return the key identifiers
	 */
	private List<String> keyIds()
	{
		if (installation.policy() == null)
			return List.of();
		return installation.policy().keys().stream().map(TrustedKey::id).toList();
	}

	/**
	 * An uninstall's progress.
	 *
	 * @param completed the completed steps, in order
	 * @param recreatedData true if the plugin data directory was created for this uninstall's lock
	 */
	private record Progress(List<String> completed, boolean recreatedData)
	{
	}

	/**
	 * The location and authority for the removal steps.
	 *
	 * @param data the plugin data directory
	 * @param home the harness home, or null when a policy does not name the harness
	 * @param missingPolicy the expected policy file when the version folder identifies the installation, or null when
	 *                      the policy identifies it
	 * @param policy the validated policy, or null when it was missing
	 * @param session the session for registration and settings cleanup, or null when the policy was missing
	 */
	private record Installation(Path data, Path home, Path missingPolicy, TrustPolicy policy, HarnessSession session)
	{
	}
}
