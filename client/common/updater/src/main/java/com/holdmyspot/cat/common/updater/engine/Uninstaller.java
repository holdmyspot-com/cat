package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes one harness's CAT installation: the harness registration, the plugin files, the downloaded runner bundles,
 * optionally the plugin's data, the permission entries and the policy. Each completed step is recorded in
 * {@code state/uninstall.json}, so that a later uninstall continues with the first step that did not complete. The
 * updater's own data directory is deleted by the stable launcher after the updater exits.
 */
public final class Uninstaller
{
	/**
	 * The steps in the order in which they run. The permission entries are removed last, so that an agent can still
	 * run an uninstall that stopped earlier.
	 */
	private static final List<String> STEPS = List.of("unregister", "plugin", "bundles", "data", "permissions",
		"policy");
	/**
	 * The largest supported progress record.
	 */
	private static final int MAXIMUM_JOURNAL_BYTES = 1024 * 1024;
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private final Path data;
	private final Path policyFile;
	private final TrustPolicy policy;
	private final PluginActivation activation;
	private final Registration registration;
	private final Clock clock;
	private final Waiting waiting;
	private final UpdaterState state;
	private final Path journal;

	/**
	 * Creates an uninstaller.
	 *
	 * @param data         the installation's updater data directory, whose name is its harness
	 * @param policyFile   the installation's policy file
	 * @param policy       the installation's policy
	 * @param activation   the harness's activation provider
	 * @param registration unregisters the plugin and removes the permission entries
	 * @param clock        operation time source
	 * @param waiting      creates pauses between lock attempts
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Uninstaller(Path data, Path policyFile, TrustPolicy policy, PluginActivation activation,
		Registration registration, Clock clock, Waiting waiting)
	{
		requireThat(data, "data").isNotNull();
		requireThat(policyFile, "policyFile").isNotNull();
		requireThat(policy, "policy").isNotNull();
		requireThat(activation, "activation").isNotNull();
		requireThat(registration, "registration").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		this.data = data.toAbsolutePath().normalize();
		this.policyFile = policyFile.toAbsolutePath().normalize();
		this.policy = policy;
		this.activation = activation;
		this.registration = registration;
		this.clock = clock;
		this.waiting = waiting;
		this.state = UpdaterState.of(this.data, activation.harness());
		this.journal = this.data.resolve("state/uninstall.json");
	}

	/**
	 * Runs every step that an earlier uninstall did not complete.
	 *
	 * @param deleteData true to delete the plugin's data directory, false to keep it
	 * @return {@code uninstalled} when every step completed; otherwise the outcome names the failed step and the next
	 * 	uninstall continues with it
	 */
	public UpdateResult uninstall(boolean deleteData)
	{
		Path home = policy.harnesses().get(activation.harness());
		if (home == null)
			return result(Result.REFUSED, "Run the explicit CAT setup command for this harness.", List.of());
		String step = STEPS.getFirst();
		try
		{
			Optional<ExclusiveFileLock> acquired = OperationLock.acquire(state, clock, Duration.ofMinutes(10),
				waiting);
			if (acquired.isEmpty())
				return result(Result.LOCKED, "Another CAT operation holds the harness lock.", List.of());
			try (ExclusiveFileLock lock = acquired.get())
			{
				if (!lock.isValid())
					return result(Result.LOCKED, "The harness lock is no longer valid.", List.of());
				ActivationTransaction transaction = new ActivationTransaction(state, activation, home);
				transaction.recover();
				Progress progress = readProgress(transaction);
				for (String next : STEPS)
				{
					if (progress.completed().contains(next))
						continue;
					step = next;
					run(next, home, deleteData, progress);
					progress.completed().add(next);
					writeProgress(progress);
				}
			}
		}
		catch (LinkSafeDeletion.Refused e)
		{
			return result(Result.REFUSED, "Uninstall stopped at the " + step + " step: " + e.getMessage(), List.of());
		}
		catch (AccessDeniedException e)
		{
			return result(Result.NOT_WRITABLE, "Uninstall stopped at the " + step + " step because " + e.getFile() +
				" is not writable; run uninstall again after fixing its permissions.", List.of(Path.of(e.getFile())));
		}
		catch (IOException | IllegalArgumentException e)
		{
			return result(Result.NOT_REMOVED, "Uninstall stopped at the " + step + " step: " + e.getMessage() +
				"; run uninstall again to continue.", List.of(journal));
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			return result(Result.LOCKED, "Interrupted while waiting for the harness lock.", List.of());
		}
		return result(Result.UNINSTALLED, "CAT was removed from this harness.", List.of());
	}

	/**
	 * Runs one step.
	 *
	 * @param step       the step's name
	 * @param home       the harness home
	 * @param deleteData true to delete the plugin's data directory
	 * @param progress   the uninstall's progress
	 * @throws IOException if the step fails
	 */
	private void run(String step, Path home, boolean deleteData, Progress progress) throws IOException
	{
		switch (step)
		{
			case "unregister" -> registration.integration().unregister(home, registration.cli());
			case "plugin" ->
			{
				for (Path path : activation.removalPaths(home))
					LinkSafeDeletion.delete(path, home);
				for (Path path : progress.retained())
					LinkSafeDeletion.delete(path, home);
			}
			case "bundles" -> deleteBundles();
			case "data" ->
			{
				if (deleteData)
					LinkSafeDeletion.delete(activation.dataDirectory(home), home);
			}
			case "permissions" ->
			{
				Map<String, List<String>> recorded = PermissionRecord.read(state.permissions());
				if (!recorded.isEmpty())
					registration.integration().removePermissions(home, recorded);
			}
			case "policy" -> deletePolicy();
			default -> throw new AssertionError(step);
		}
	}

	/**
	 * Deletes the downloaded runner bundles of the recorded user's home, unless the other harness's CAT installation
	 * still exists and may use them.
	 *
	 * @throws IOException if the bundles cannot be deleted
	 */
	private void deleteBundles() throws IOException
	{
		Optional<Path> user = userHome();
		if (user.isEmpty() || otherInstallationExists())
			return;
		LinkSafeDeletion.delete(user.get().resolve(".cat/bundles"), user.get());
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
	 * Returns whether the updater data root holds another harness's CAT installation.
	 *
	 * @return true if another installation directory has a current updater version
	 * @throws IOException if the data root cannot be listed
	 */
	private boolean otherInstallationExists() throws IOException
	{
		Path base = data.getParent();
		if (base == null || !Files.isDirectory(base, NOFOLLOW_LINKS))
			return false;
		try (DirectoryStream<Path> installations = Files.newDirectoryStream(base))
		{
			for (Path installation : installations)
			{
				if (!installation.equals(data) && Files.isRegularFile(installation.resolve("current"), NOFOLLOW_LINKS))
					return true;
			}
		}
		return false;
	}

	/**
	 * Deletes the policy file, and its directory when nothing else is in it.
	 *
	 * @throws IOException if the policy cannot be deleted
	 */
	private void deletePolicy() throws IOException
	{
		Path directory = policyFile.getParent();
		LinkSafeDeletion.delete(policyFile, directory);
		if (directory.startsWith(data) || !Files.isDirectory(directory, NOFOLLOW_LINKS))
			return;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
		{
			if (entries.iterator().hasNext())
				return;
		}
		Files.delete(directory);
	}

	/**
	 * Reads the progress of an earlier uninstall, or starts a new one. A new uninstall records the plugin trees that
	 * the last update kept for a rollback, so that they are deleted with the plugin.
	 *
	 * @param transaction the harness's activation transaction
	 * @return the progress
	 * @throws IOException if the progress record is unreadable or malformed
	 */
	private Progress readProgress(ActivationTransaction transaction) throws IOException
	{
		StateFiles.requireNoLinks(journal);
		if (!Files.exists(journal, NOFOLLOW_LINKS))
			return new Progress(new ArrayList<>(), transaction.retainedPaths());
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
		List<Path> retained = new ArrayList<>();
		for (JsonNode path : root.path("retained"))
		{
			if (!path.isString() || !Path.of(path.stringValue()).isAbsolute())
				throw new IOException("The uninstall progress names a relative path: " + journal);
			retained.add(Path.of(path.stringValue()).normalize());
		}
		return new Progress(completed, List.copyOf(retained));
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
		ArrayNode retained = root.putArray("retained");
		for (Path path : progress.retained())
			retained.add(path.toString());
		StateFiles.write(journal, (MAPPER.writeValueAsString(root) + "\n").getBytes(UTF_8));
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
	 * @param retained  the plugin trees that the last update kept for a rollback
	 */
	private record Progress(List<String> completed, List<Path> retained)
	{
	}
}
