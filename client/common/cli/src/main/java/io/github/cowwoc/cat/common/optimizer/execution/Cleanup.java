package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.SessionWorkDirectory;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Delivered;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.workspace.Forks;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Removes the files of a campaign that ended by a certified delivery or by retaining the incumbent.
 * <p>
 * Removal runs three steps, in order: it removes the campaign's optimization worktree, then the campaign's runs below
 * every run root, then the campaign directory. A run belongs to the campaign when its manifest's {@code project_path}
 * lies below the campaign directory; a run without a readable manifest identifies no campaign and is kept. Every
 * other ending keeps the campaign's files.
 * <p>
 * Before the first step, the campaign directory records that removal started. A removal that a crash interrupts or
 * a step fails is completed by {@link #resume()}, which every command runs first. Each step does nothing for files
 * that are already gone, so a step may run again. A run's manifest is deleted after the rest of the run, so an
 * interrupted deletion leaves a run that is still attributed to the campaign.
 * <p>
 * A removal that fails does not fail the command that runs it: the command reports a warning that names the path and
 * the cause, and continues.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. Removal takes the campaign's write lock; a campaign whose lock
 * another process holds is removed by a later command.
 */
public final class Cleanup
{
	private static final String MANIFEST = "run.json";
	private final Path base;
	private final Forks forks;
	private final List<Path> runRoots;
	private final JsonMapper json;
	private final Consumer<Step> beforeStep;

	/**
	 * Creates a cleanup.
	 *
	 * @param base the base worktree
	 * @param forks creates and removes the campaigns' optimization worktrees
	 * @param runRoots the directories that hold the runs of every campaign of the project
	 * @param json the JSON mapper that parses run manifests
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Cleanup(Path base, Forks forks, List<Path> runRoots, JsonMapper json)
	{
		this(base, forks, runRoots, json, _ ->
		{
		});
	}

	/**
	 * Creates a cleanup that reports each step before it runs.
	 *
	 * @param base the base worktree
	 * @param forks creates and removes the campaigns' optimization worktrees
	 * @param runRoots the directories that hold the runs of every campaign of the project
	 * @param json the JSON mapper that parses run manifests
	 * @param beforeStep receives each step before it runs; an exception that it throws stops the removal at that
	 *   point, as a crash would
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Cleanup(Path base, Forks forks, List<Path> runRoots, JsonMapper json, Consumer<Step> beforeStep)
	{
		requireThat(base, "base").isNotNull();
		requireThat(forks, "forks").isNotNull();
		requireThat(runRoots, "runRoots").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(beforeStep, "beforeStep").isNotNull();
		this.base = base;
		this.forks = forks;
		this.runRoots = List.copyOf(runRoots);
		this.json = json;
		this.beforeStep = beforeStep;
	}

	/**
	 * Indicates whether a campaign's ending removes its files: the campaign finished as {@code certified}, and either
	 * the user kept the incumbent and the delivery of its prompt tests passed final verification, or its certified
	 * delivery passed final verification.
	 *
	 * @param campaign the campaign, as stored after the command that may have ended it
	 * @param delivery the result of the delivery that the command ran, or null if it ran none
	 * @return {@code true} if the campaign's files are removed
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static boolean isRemovable(Campaign campaign, DeliveryResult delivery)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (campaign.lifecycle() != Lifecycle.FINISHED || campaign.outcome() != Outcome.CERTIFIED ||
			!(delivery instanceof Delivered delivered))
		{
			return false;
		}
		// The delivery of a kept incumbent's prompt tests carries no label
		if (campaign.isIncumbentKept())
			return delivered.label() == null;
		return delivered.label() == Outcome.CERTIFIED;
	}

	/**
	 * Removes a campaign's files if its ending removes them.
	 *
	 * @param campaign the campaign, as stored after the command that may have ended it
	 * @param delivery the result of the delivery that the command ran, or null if it ran none
	 * @return what the ending did to the campaign's files; a removal that fails after it is recorded is retried by the
	 *   next command
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public Removal end(Campaign campaign, DeliveryResult delivery)
	{
		if (!isRemovable(campaign, delivery))
			return new Removal.Kept();
		String id = campaign.header().id();
		try
		{
			FileCampaignStore.recordRemoval(base, id);
		}
		catch (IOException e)
		{
			return new Removal.Failed("Could not record the removal of the files of campaign " + id + ": " +
				cause(id, e) + ". The files stay in " + SessionWorkDirectory.WORK_ROOT + "/.");
		}
		try
		{
			if (remove(id))
				return new Removal.Removed();
			return new Removal.Deferred();
		}
		catch (IOException e)
		{
			return new Removal.Failed(retryWarning(id, e));
		}
	}

	/**
	 * Completes every removal that an earlier command started but did not finish.
	 *
	 * @return a warning for each removal that failed again; the next command retries it
	 * @throws IOException if the campaigns cannot be listed
	 */
	public List<String> resume() throws IOException
	{
		List<String> warnings = new ArrayList<>();
		for (String id : FileCampaignStore.removals(base))
		{
			try
			{
				remove(id);
			}
			catch (IOException e)
			{
				warnings.add(retryWarning(id, e));
			}
		}
		try
		{
			FileCampaignStore.completeDeletions(base);
		}
		catch (IOException e)
		{
			warnings.add("Could not complete the deletion of a campaign's files: " + cause(null, e) +
				". The next command retries the deletion.");
		}
		return warnings;
	}

	/**
	 * Returns the warning of a recorded removal that failed.
	 *
	 * @param id the campaign's identifier
	 * @param failure the failure
	 * @return the warning
	 */
	private String retryWarning(String id, IOException failure)
	{
		return "Could not remove the files of campaign " + id + ": " + cause(id, failure) +
			". The next command retries the removal.";
	}

	/**
	 * Names the path and the cause of a failure.
	 *
	 * @param id the campaign's identifier, or {@code null} if the failure concerns no single campaign
	 * @param failure the failure
	 * @return {@code <path>: <cause>}; the path is the campaign directory, or the optimizer directory if {@code id} is
	 *   null, when the failure names no file
	 */
	private String cause(String id, IOException failure)
	{
		if (failure instanceof FileSystemException fileFailure && fileFailure.getFile() != null)
		{
			String reason = fileFailure.getReason();
			if (reason == null)
				reason = failure.getClass().getSimpleName();
			return fileFailure.getFile() + ": " + reason;
		}
		Path path;
		if (id == null)
			path = FileCampaignStore.root(base);
		else
			path = FileCampaignStore.directory(base, id);
		String reason = failure.getMessage();
		if (reason == null)
			reason = failure.getClass().getSimpleName();
		return path + ": " + reason;
	}

	/**
	 * Runs every removal step of a campaign whose removal is recorded, under the campaign's lock.
	 *
	 * @param id the campaign's identifier
	 * @return {@code false} if another process holds the campaign's lock, so a later command removes the files
	 * @throws IOException if a step fails
	 */
	private boolean remove(String id) throws IOException
	{
		Path directory = FileCampaignStore.directory(base, id);
		Optional<CampaignLock> lock = CampaignLock.tryAcquire(FileCampaignStore.lockFile(base, id));
		if (lock.isEmpty())
			return false;
		// Deleting the open lock file releases nothing until the lock closes
		try (CampaignLock _ = lock.get())
		{
			beforeStep.accept(Step.WORKTREE);
			forks.remove(base, directory);

			beforeStep.accept(Step.RUNS);
			List<Path> owners = new ArrayList<>(List.of(directory.toAbsolutePath().normalize()));
			owners.add(directory.toRealPath());
			for (Path runRoot : runRoots)
				removeRuns(runRoot, owners);

			beforeStep.accept(Step.CAMPAIGN_DIRECTORY);
			// Windows refuses to rename a directory that holds an open file, and deleting the campaign directory
			// renames it first
			Files.deleteIfExists(FileCampaignStore.lockFile(base, id));
			FileCampaignStore.delete(base, id);
		}
		return true;
	}

	/**
	 * Deletes the runs of a campaign below one run root.
	 *
	 * @param runRoot the run root, which holds {@code <harness>/<run>/run.json}
	 * @param owners the campaign directory's paths that a run's project path may start with
	 * @throws IOException if a directory cannot be listed or a run cannot be deleted
	 */
	private void removeRuns(Path runRoot, List<Path> owners) throws IOException
	{
		if (!Files.isDirectory(runRoot, LinkOption.NOFOLLOW_LINKS))
			return;
		for (Path harness : LeftoverRuns.list(runRoot))
		{
			for (Path run : LeftoverRuns.list(harness))
			{
				Optional<Path> project = projectPath(run.resolve(MANIFEST));
				if (project.isPresent() && owners.stream().anyMatch(project.get()::startsWith))
					deleteRun(run);
			}
		}
	}

	/**
	 * Returns the project path that a run manifest records.
	 *
	 * @param manifest the run's manifest
	 * @return the absolute, normalized path; empty if the manifest does not exist, cannot be parsed, or records no
	 *   absolute path
	 * @throws IOException if the manifest cannot be read
	 */
	private Optional<Path> projectPath(Path manifest) throws IOException
	{
		if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		JsonNode root;
		try
		{
			root = json.readTree(Files.readAllBytes(manifest));
		}
		catch (JacksonException _)
		{
			return Optional.empty();
		}
		if (root == null || !root.isObject())
			return Optional.empty();
		JsonNode value = root.get("project_path");
		if (value == null || !value.isString())
			return Optional.empty();
		Path path;
		try
		{
			path = Path.of(value.asString());
		}
		catch (InvalidPathException _)
		{
			return Optional.empty();
		}
		if (!path.isAbsolute())
			return Optional.empty();
		return Optional.of(path.normalize());
	}

	/**
	 * Deletes a run directory, deleting its manifest after everything else.
	 *
	 * @param run the run directory
	 * @throws IOException if the run cannot be deleted
	 */
	private static void deleteRun(Path run) throws IOException
	{
		Path manifest = run.resolve(MANIFEST);
		List<Path> children = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(run))
		{
			for (Path child : stream)
			{
				if (!child.equals(manifest))
					children.add(child);
			}
		}
		for (Path child : children)
			TemporaryDirectories.deleteRecursively(child);
		Files.deleteIfExists(manifest);
		Files.deleteIfExists(run);
	}

	/**
	 * What ending a campaign did to its files.
	 */
	public sealed interface Removal
	{
		/**
		 * The ending keeps the campaign's files.
		 */
		record Kept() implements Removal
		{
		}

		/**
		 * The campaign's files are removed.
		 */
		record Removed() implements Removal
		{
		}

		/**
		 * Another process holds the campaign's lock, so a later command removes the files.
		 */
		record Deferred() implements Removal
		{
		}

		/**
		 * The removal failed.
		 *
		 * @param warning the warning for the user, which names the path and the cause
		 */
		record Failed(String warning) implements Removal
		{
		}
	}

	/**
	 * A step of a campaign's removal.
	 */
	public enum Step
	{
		/**
		 * Removes the campaign's optimization worktree.
		 */
		WORKTREE,
		/**
		 * Removes the campaign's runs below every run root.
		 */
		RUNS,
		/**
		 * Removes the campaign directory.
		 */
		CAMPAIGN_DIRECTORY
	}
}
