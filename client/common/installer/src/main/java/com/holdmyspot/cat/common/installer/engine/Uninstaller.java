package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
 * Removes one harness's CAT installation: the permission entries that install added, the harness registration and
 * the plugin files, including every version folder. CAT's plugin data, which holds the policy, the installer's state
 * and the release cache, stays unless the user asks to delete it. Each completed step is recorded in
 * {@code state/uninstall.json}, so that a later uninstall continues with the first step that did not complete; an
 * uninstall that completes leaves no record, so that an uninstall after a later install runs every step again.
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
	 * The steps in the order in which they run. The permission entries are removed first because their record is in
	 * the plugin data, which the harness deletes when it unregisters the plugin without keeping the data.
	 */
	private static final List<String> STEPS = List.of(PERMISSIONS, UNREGISTER, PLUGIN);
	/**
	 * The largest supported progress record.
	 */
	private static final int MAXIMUM_JOURNAL_BYTES = 1024 * 1024;
	/**
	 * The progress record, relative to the installer data directory.
	 */
	private static final String JOURNAL = "state/uninstall.json";
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private final Path data;
	private final TrustPolicy policy;
	private final PluginActivation activation;
	private final HarnessSession session;
	private final Path running;
	private final Clock clock;
	private final Waiting waiting;
	private final InstallerState state;
	private final Path journal;
	/**
	 * The sentences that tell the user which recorded entries this uninstall left in place.
	 */
	private final List<String> retained = new ArrayList<>();
	/**
	 * The folders of plugin files that hold the running installer's files, which are deleted once the installer no
	 * longer needs them.
	 */
	private final List<Path> exitDeletions = new ArrayList<>();

	/**
	 * Creates an uninstaller.
	 *
	 * @param data       the plugin data directory of the installation, which holds its policy and the installer's state
	 * @param policy     the installation's policy
	 * @param activation the harness's activation provider
	 * @param session    unregisters the plugin and removes the permission entries
	 * @param running    the version folder that the running installer belongs to
	 * @param clock      operation time source
	 * @param waiting    creates pauses between lock attempts
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Uninstaller(Path data, TrustPolicy policy, PluginActivation activation, HarnessSession session,
		Path running, Clock clock, Waiting waiting)
	{
		requireThat(data, "data").isNotNull();
		requireThat(policy, "policy").isNotNull();
		requireThat(activation, "activation").isNotNull();
		requireThat(session, "session").isNotNull();
		requireThat(running, "running").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		this.data = data.toAbsolutePath().normalize();
		this.policy = policy;
		this.activation = activation;
		this.session = session;
		this.running = running;
		this.clock = clock;
		this.waiting = waiting;
		this.state = InstallerState.of(this.data, activation.harness());
		this.journal = this.data.resolve(JOURNAL);
	}

	/**
	 * Runs every step that an earlier uninstall did not complete. When the harness program that install recorded no
	 * longer exists, the plugin cannot be unregistered, so that step is skipped without being recorded as completed and
	 * every other step still runs. The harness is asked to keep CAT's plugin data unless {@code deleteData} is true;
	 * then the plugin data is deleted once every step completed.
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
		Path recorded = policy.harnesses().get(activation.harness());
		if (recorded == null)
		{
			return failure(Result.REFUSED, "CAT's install has not recorded this harness, so there is nothing to " +
				"uninstall.", List.of());
		}
		retained.clear();
		exitDeletions.clear();
		String step = STEPS.getFirst();
		boolean unregistered;
		Path home;
		try
		{
			home = LinkSafeDeletion.resolveRoot(recorded);
			Optional<ExclusiveFileLock> acquired = OperationLock.acquire(state, clock, Duration.ofMinutes(10),
				waiting);
			if (acquired.isEmpty())
				return failure(Result.LOCKED, "Another CAT operation holds the harness lock.", List.of());
			try (ExclusiveFileLock lock = acquired.get())
			{
				if (!lock.isValid())
					return failure(Result.LOCKED, "The harness lock is no longer valid.", List.of());
				Progress progress = readProgress(journal).orElseGet(() -> new Progress(new ArrayList<>()));
				boolean programExists = programExists();
				for (String next : STEPS)
				{
					if (progress.completed().contains(next) || (next.equals(UNREGISTER) && !programExists))
						continue;
					step = next;
					run(next, home, deleteData);
					progress.completed().add(next);
					writeProgress(progress);
				}
				unregistered = progress.completed().contains(UNREGISTER);
				if (!deleteData)
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
				" is not writable; run uninstall again after fixing its permissions.", List.of(Path.of(e.getFile())));
		}
		catch (IOException | IllegalArgumentException e)
		{
			return failure(Result.UNINSTALL_INCOMPLETE, "Uninstall stopped at the " + step + " step: " +
				e.getMessage() + "; run the same uninstall command again to continue.", List.of(journal));
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return failure(Result.LOCKED, "Interrupted while waiting for the harness lock.", List.of());
		}
		if (deleteData)
		{
			// The harness lock is in the plugin data, so the data is deleted once the lock is released
			Path directory = activation.dataDirectory(home);
			try
			{
				LinkSafeDeletion.delete(directory, home);
			}
			catch (IOException | IllegalArgumentException e)
			{
				return failure(Result.UNINSTALL_INCOMPLETE, "CAT was removed from this harness, but its plugin data " +
					directory + " could not be deleted completely: " + e.getMessage() + "; delete it yourself or run " +
					"the same uninstall command again.", List.of(directory));
			}
		}
		return uninstalled(activation.harness(), policy.keys().stream().map(TrustedKey::id).toList(), unregistered,
			retained, exitDeletions);
	}

	/**
	 * Discards the progress of an earlier uninstall, because an operation that installed CAT again makes it stale: a
	 * later uninstall must run every step again.
	 *
	 * @param data the installation's installer data directory
	 * @throws IOException if the progress cannot be deleted
	 */
	public static void discard(Path data) throws IOException
	{
		Files.deleteIfExists(data.toAbsolutePath().normalize().resolve(JOURNAL));
	}

	/**
	 * Builds the outcome of an uninstall that completed every step that it could run.
	 *
	 * @param harness      the installation's harness
	 * @param keyIds       the identifiers of the policy's keys
	 * @param unregistered true if the plugin was unregistered through the harness program
	 * @param retained     the sentences that tell the user which recorded entries the uninstall left in place
	 * @param exitDeletions the folders to delete once the installer no longer needs its own files
	 * @return the outcome
	 */
	private static UninstallResult uninstalled(String harness, List<String> keyIds, boolean unregistered,
		List<String> retained, List<Path> exitDeletions)
	{
		StringJoiner message = new StringJoiner(" ");
		if (unregistered)
			message.add("CAT was removed from this harness.");
		else
		{
			message.add("CAT was removed from this harness, but the " + harness + " program that install recorded no " +
				"longer exists, so the harness may still list the plugin.");
		}
		retained.forEach(message::add);
		return new UninstallResult(new UpdateResult(Result.UNINSTALLED, harness, Optional.empty(), Optional.empty(),
			message.toString(), List.of(), true, keyIds).withExitDeletions(exitDeletions), unregistered);
	}

	/**
	 * Returns whether the harness program that install recorded still exists and is executable.
	 *
	 * @return true if the program can run
	 */
	private boolean programExists()
	{
		Optional<HarnessCommand> command = policy.command(activation.harness());
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
		switch (step)
		{
			case PERMISSIONS ->
			{
				Map<String, List<String>> recorded = PermissionRecord.read(state.permissions());
				if (!recorded.isEmpty())
				{
					session.integration().removePermissions(home, recorded, session.replacer());
					retained.addAll(session.integration().describeRetained(home, recorded));
				}
				Files.deleteIfExists(state.permissions());
			}
			case UNREGISTER -> session.integration().unregister(home, session.cli(), !deleteData);
			case PLUGIN -> deletePlugin(home);
			default -> throw new AssertionError(step);
		}
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
				LinkSafeDeletion.delete(path, home);
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
		return Optional.of(new Progress(completed));
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
			true, policy.keys().stream().map(TrustedKey::id).toList());
	}

	/**
	 * An uninstall's progress.
	 *
	 * @param completed the completed steps, in order
	 */
	private record Progress(List<String> completed)
	{
	}
}
