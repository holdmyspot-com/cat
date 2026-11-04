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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes one harness's CAT installation: the harness registration, the plugin files, the downloaded runner bundles,
 * optionally the plugin's data, the permission entries and the policy. Each completed step is recorded in
 * {@code state/uninstall.json}, so that a later uninstall continues with the first step that did not complete.
 */
public final class Uninstaller
{
	/**
	 * The step that unregisters the plugin through the harness program.
	 */
	private static final String UNREGISTER = "unregister";
	/**
	 * The step that deletes the plugin's data directory when the user asked for that.
	 */
	private static final String DATA = "data";
	/**
	 * The steps in the order in which they run. The permission entries are removed last, so that an agent can still
	 * run an uninstall that stopped earlier.
	 */
	private static final List<String> STEPS = List.of(UNREGISTER, "plugin", "bundles", DATA, "permissions",
		"policy");
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
	private final Path policyFile;
	private final TrustPolicy policy;
	private final PluginActivation activation;
	private final HarnessSession session;
	private final Clock clock;
	private final Waiting waiting;
	private final InstallerState state;
	private final Path journal;

	/**
	 * Creates an uninstaller.
	 *
	 * @param data         the installation's installer data directory, whose name is its harness
	 * @param policyFile   the installation's policy file
	 * @param policy       the installation's policy
	 * @param activation   the harness's activation provider
	 * @param session unregisters the plugin and removes the permission entries
	 * @param clock        operation time source
	 * @param waiting      creates pauses between lock attempts
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Uninstaller(Path data, Path policyFile, TrustPolicy policy, PluginActivation activation,
		HarnessSession session, Clock clock, Waiting waiting)
	{
		requireThat(data, "data").isNotNull();
		requireThat(policyFile, "policyFile").isNotNull();
		requireThat(policy, "policy").isNotNull();
		requireThat(activation, "activation").isNotNull();
		requireThat(session, "session").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		this.data = data.toAbsolutePath().normalize();
		this.policyFile = policyFile.toAbsolutePath().normalize();
		this.policy = policy;
		this.activation = activation;
		this.session = session;
		this.clock = clock;
		this.waiting = waiting;
		this.state = InstallerState.of(this.data, activation.harness());
		this.journal = this.data.resolve(JOURNAL);
	}

	/**
	 * Runs every step that an earlier uninstall did not complete. When the harness program that setup recorded no
	 * longer exists, the plugin cannot be unregistered, so that step is skipped without being recorded as completed and
	 * every other step still runs. The data step is recorded as completed only when it deleted the plugin's data, so
	 * that a later uninstall asked to delete the data deletes it.
	 *
	 * @param deleteData true to delete the plugin's data directory, false to keep it
	 * @return {@code uninstalled} when every step that it could run completed; otherwise the outcome names the failed
	 * 	step and the next uninstall continues with it
	 */
	public UninstallResult uninstall(boolean deleteData)
	{
		Path recorded = policy.harnesses().get(activation.harness());
		if (recorded == null)
			return failure(Result.REFUSED, "Run the explicit CAT setup command for this harness.", List.of());
		String step = STEPS.getFirst();
		boolean unregistered;
		try
		{
			Path home = LinkSafeDeletion.resolveRoot(recorded);
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
					// Data that this run kept stays for a later uninstall that is asked to delete it
					if (next.equals(DATA) && !deleteData)
						continue;
					progress.completed().add(next);
					writeProgress(progress);
				}
				unregistered = progress.completed().contains(UNREGISTER);
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
		return uninstalled(activation.harness(), policy.keys().stream().map(TrustedKey::id).toList(), unregistered);
	}

	/**
	 * Returns the outcome of an earlier uninstall that completed its last step, deleting the policy. The policy no
	 * longer exists then, so running the same uninstall again reports that outcome again.
	 *
	 * @param data    the installation's installer data directory, whose name is its harness
	 * @param harness the installation's harness
	 * @return the completed uninstall's outcome, or empty if no uninstall completed its last step
	 * @throws IOException if the progress record is unreadable or malformed
	 */
	public static Optional<UninstallResult> completed(Path data, String harness) throws IOException
	{
		Optional<Progress> progress = readProgress(data.toAbsolutePath().normalize().resolve(JOURNAL));
		if (progress.isEmpty() || !progress.get().completed().contains(STEPS.getLast()))
			return Optional.empty();
		return Optional.of(uninstalled(harness, List.of(), progress.get().completed().contains(UNREGISTER)));
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
	 * @return the outcome
	 */
	private static UninstallResult uninstalled(String harness, List<String> keyIds, boolean unregistered)
	{
		String message = "CAT was removed from this harness.";
		if (!unregistered)
		{
			message = "CAT was removed from this harness, but the " + harness + " program that setup recorded no " +
				"longer exists, so the harness may still list the plugin.";
		}
		return new UninstallResult(new UpdateResult(Result.UNINSTALLED, harness, Optional.empty(), Optional.empty(),
			message, List.of(), true, keyIds), unregistered);
	}

	/**
	 * Returns whether the harness program that setup recorded still exists and is executable.
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
	 * @param deleteData true to delete the plugin's data directory
	 * @throws IOException if the step fails
	 */
	private void run(String step, Path home, boolean deleteData) throws IOException
	{
		switch (step)
		{
			case UNREGISTER -> session.integration().unregister(home, session.cli(), !deleteData);
			case "plugin" ->
			{
				for (Path path : activation.removalPaths(home))
					LinkSafeDeletion.delete(path, home);
			}
			case "bundles" -> deleteBundles();
			case DATA ->
			{
				if (deleteData)
					LinkSafeDeletion.delete(activation.dataDirectory(home), home);
			}
			case "permissions" ->
			{
				Map<String, List<String>> recorded = PermissionRecord.read(state.permissions());
				if (!recorded.isEmpty())
					session.integration().removePermissions(home, recorded, session.replacer());
			}
			case "policy" -> deletePolicy();
			default -> throw new AssertionError(step);
		}
	}

	/**
	 * Deletes the downloaded runner bundles of the recorded user's home, also while the other harness's CAT
	 * installation exists, which downloads them again when it needs them.
	 *
	 * @throws IOException if the bundles cannot be deleted
	 */
	private void deleteBundles() throws IOException
	{
		Optional<Path> user = userHome();
		if (user.isEmpty())
			return;
		Path root = LinkSafeDeletion.resolveRoot(user.get());
		LinkSafeDeletion.delete(root.resolve(".cat/bundles"), root);
	}

	/**
	 * Returns the user's home directory recorded with the harness program.
	 *
	 * @return the directory, or empty if none was recorded
	 */
	private Optional<Path> userHome()
	{
		Optional<HarnessCommand> command = policy.command(activation.harness());
		if (command.isEmpty())
			return Optional.empty();
		for (String name : List.of("HOME", "USERPROFILE"))
		{
			String value = command.get().environment().get(name);
			if (value != null && !value.isBlank() && Path.of(value).isAbsolute())
				return Optional.of(Path.of(value).normalize());
		}
		return Optional.empty();
	}

	/**
	 * Deletes the policy file, then its directory and the directory that holds every harness's policy directory, each
	 * when nothing else is in it.
	 *
	 * @throws IOException if the policy cannot be deleted
	 */
	private void deletePolicy() throws IOException
	{
		// The policy's directory and its parent belong to CAT, but the directories above them may be reached through
		// links
		Path parent = LinkSafeDeletion.resolveRoot(policyFile.getParent().getParent());
		Path directory = parent.resolve(policyFile.getParent().getFileName());
		LinkSafeDeletion.delete(directory.resolve(policyFile.getFileName()), directory);
		if (directory.startsWith(data))
			return;
		if (deleteIfEmpty(directory))
			deleteIfEmpty(parent);
	}

	/**
	 * Deletes a directory that holds nothing.
	 *
	 * @param directory the directory
	 * @return true if the directory no longer exists
	 * @throws IOException if the directory cannot be listed or deleted
	 */
	private static boolean deleteIfEmpty(Path directory) throws IOException
	{
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
			return !Files.exists(directory, NOFOLLOW_LINKS);
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
		{
			if (entries.iterator().hasNext())
				return false;
		}
		Files.delete(directory);
		return true;
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
