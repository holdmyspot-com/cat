package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult.Delivered;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import com.holdmyspot.cat.common.optimizer.store.TrialRecovery;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Removes the files of a campaign that ended by a certified delivery or by retaining the incumbent.
 * <p>
 * Removal runs three steps, in order: it removes the campaign's optimization worktree, then the campaign's runs below
 * every results folder, then the campaign directory. A nested session belongs to the campaign when its manifest's
 * {@code project_path} lies below the campaign directory; a nested session without a readable manifest identifies no
 * campaign and is kept. Every other ending keeps the campaign's files.
 * <p>
 * Before the first step, the campaign directory records that removal started. A removal that a crash interrupts or
 * a step fails is completed by {@link #resume()}, which every command runs first. Each step does nothing for files
 * that are already gone, so a step may run again. A nested session's manifest is deleted after the rest of the nested
 * session, so an interrupted deletion leaves a nested session that is still attributed to the campaign.
 * <p>
 * A removal that fails does not fail the command that runs it: the command reports a warning that names the path and
 * the cause, and continues.
 * <p>
 * It removes none of a campaign's files while anyone else, in any process, holds the campaign's {@link CampaignLock}:
 * {@link #end(Campaign, DeliveryResult)} then returns {@link Removal.Deferred}, and a later command removes the files.
 */
public final class Cleanup
{
	private static final String MANIFEST = "session.json";
	private final FileLocks fileLocks;
	private final Path base;
	private final Path campaigns;
	private final Forks forks;
	private final List<Path> resultsFolders;
	private final JsonMapper json;
	private final FileReplacer replacer;
	private final Consumer<Step> beforeStep;
	private final SessionPort sessions;
	private final PendingPilotRequests pendingPilotRequests;

	/**
	 * Creates a cleanup.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param base the base worktree
	 * @param campaigns the directory that holds the campaigns that this cleanup removes
	 * @param forks creates and removes the campaigns' optimization worktrees
	 * @param resultsFolders the directories that hold the nested sessions of those campaigns
	 * @param json the JSON mapper that parses session manifests
	 * @param replacer writes the record that a campaign's removal started
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Cleanup(FileLocks fileLocks, Path base, Path campaigns, Forks forks, List<Path> resultsFolders,
		JsonMapper json, FileReplacer replacer)
	{
		this(fileLocks, base, campaigns, forks, resultsFolders, json, replacer, _ ->
		{
		});
	}

	/**
	 * Creates a cleanup that reports each step before it runs.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param base the base worktree
	 * @param campaigns the directory that holds the campaigns that this cleanup removes
	 * @param forks creates and removes the campaigns' optimization worktrees
	 * @param resultsFolders the directories that hold the nested sessions of those campaigns
	 * @param json the JSON mapper that parses session manifests
	 * @param replacer writes the record that a campaign's removal started
	 * @param beforeStep receives each step before it runs; an exception that it throws stops the removal at that
	 *   point, as a crash would
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Cleanup(FileLocks fileLocks, Path base, Path campaigns, Forks forks, List<Path> resultsFolders,
		JsonMapper json, FileReplacer replacer, Consumer<Step> beforeStep)
	{
		this(fileLocks, base, campaigns, forks, resultsFolders, json, replacer, beforeStep, null, _ -> List.of());
	}

	/**
	 * Creates a cleanup that first resolves persisted retained trial requests.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param base the base worktree
	 * @param campaigns the directory that holds the campaigns that this cleanup removes
	 * @param forks creates and removes the campaigns' optimization worktrees
	 * @param resultsFolders the directories that hold the nested sessions of those campaigns
	 * @param json the JSON mapper that parses session manifests
	 * @param replacer writes the record that a campaign's removal started
	 * @param beforeStep receives each step before it runs
	 * @param sessions inspects and discards retained trial sessions
	 * @param pendingPilotRequests lists request IDs retained by guard pilots
	 */
	public Cleanup(FileLocks fileLocks, Path base, Path campaigns, Forks forks, List<Path> resultsFolders,
		JsonMapper json, FileReplacer replacer, Consumer<Step> beforeStep, SessionPort sessions,
		PendingPilotRequests pendingPilotRequests)
	{
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(base, "base").isNotNull();
		requireThat(campaigns, "campaigns").isNotNull();
		requireThat(forks, "forks").isNotNull();
		requireThat(resultsFolders, "resultsFolders").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(beforeStep, "beforeStep").isNotNull();
		requireThat(pendingPilotRequests, "pendingPilotRequests").isNotNull();
		this.fileLocks = fileLocks;
		this.base = base;
		this.campaigns = campaigns;
		this.forks = forks;
		this.resultsFolders = List.copyOf(resultsFolders);
		this.json = json;
		this.replacer = replacer;
		this.beforeStep = beforeStep;
		this.sessions = sessions;
		this.pendingPilotRequests = pendingPilotRequests;
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
			FileCampaignStore.recordRemoval(campaigns, id, replacer);
		}
		catch (IOException e)
		{
			return new Removal.Failed("Could not record the removal of the files of campaign " + id + ": " +
				cause(id, e) + ". The files stay in " + SessionDirectory.ROOT + "/.");
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
		for (String id : FileCampaignStore.removals(campaigns))
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
			FileCampaignStore.completeDeletions(campaigns);
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
	 * @return {@code <path>: <cause>}, as {@link #describe(Path, IOException)} words it, where the fallback path is the
	 *   campaign directory, or the directory that holds the campaigns if {@code id} is null
	 */
	private String cause(String id, IOException failure)
	{
		Path path;
		if (id == null)
			path = campaigns;
		else
			path = FileCampaignStore.directory(campaigns, id);
		return describe(path, failure);
	}

	/**
	 * Names the path and the cause of a failure.
	 *
	 * @param fallback the path to name when the failure names no file
	 * @param failure the failure
	 * @return {@code <path>: <cause>}: the file that a file system failure names and the system's reason for it, or
	 *   {@code fallback} and the failure's message; the class name of the failure stands in for a missing reason or
	 *   message
	 * @throws NullPointerException if any argument is null
	 */
	public static String describe(Path fallback, IOException failure)
	{
		requireThat(fallback, "fallback").isNotNull();
		requireThat(failure, "failure").isNotNull();
		if (failure instanceof FileSystemException fileFailure && fileFailure.getFile() != null)
		{
			String reason = fileFailure.getReason();
			if (reason == null)
				reason = failure.getClass().getSimpleName();
			return fileFailure.getFile() + ": " + reason;
		}
		String reason = failure.getMessage();
		if (reason == null)
			reason = failure.getClass().getSimpleName();
		return fallback + ": " + reason;
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
		Path directory = FileCampaignStore.directory(campaigns, id);
		Optional<CampaignLock> lock = CampaignLock.tryAcquire(fileLocks, FileCampaignStore.lockFile(campaigns, id));
		if (lock.isEmpty())
			return false;
		// Deleting the open lock file releases nothing until the lock closes
		try (CampaignLock _ = lock.get())
		{
			discardRetainedTrials(id);
			beforeStep.accept(Step.WORKTREE);
			forks.remove(base, directory);

			beforeStep.accept(Step.RUNS);
			List<Path> owners = new ArrayList<>(List.of(directory.toAbsolutePath().normalize()));
			owners.add(directory.toRealPath());
			for (Path resultsFolder : resultsFolders)
				removeResultsCopies(resultsFolder, owners);

			beforeStep.accept(Step.CAMPAIGN_DIRECTORY);
			// Windows refuses to rename a directory that holds an open file, and deleting the campaign directory
			// renames it first
			Files.deleteIfExists(FileCampaignStore.lockFile(campaigns, id));
			FileCampaignStore.delete(campaigns, id);
		}
		return true;
	}

	/**
	 * Resolves each persisted request while the campaign lock prevents new trial publication.
	 *
	 * @param id the campaign's identifier
	 * @throws IOException if retained trial state is active, ambiguous, or cannot be discarded
	 */
	private void discardRetainedTrials(String id) throws IOException
	{
		Set<UUID> requestIds = new LinkedHashSet<>();
		for (TrialRecovery recovery : new FileCampaignStore(campaigns, json, replacer).listTrialRecoveries(id))
			requestIds.add(recovery.requestId());
		requestIds.addAll(pendingPilotRequests.requestIds(id));
		if (requestIds.isEmpty())
			return;
		if (sessions == null || !sessions.recoversTrials())
			throw new IOException("A retained trial request cannot be resolved by this session port");
		for (UUID requestId : requestIds)
		{
			RetainedTrialResponse status = sessions.retainedTrial(RetainedTrialRequest.status(requestId));
			if (!requestId.equals(status.requestId()))
				throw new IOException("The retained trial response names a different request");
			if (status.status() == RetainedTrialResponse.Status.ABSENT)
				continue;
			if (status.status() != RetainedTrialResponse.Status.READY &&
				status.status() != RetainedTrialResponse.Status.UNSAFE)
				throw new IOException("The retained trial request is still active or cannot be safely discarded");
			RetainedTrialResponse discarded = sessions.retainedTrial(RetainedTrialRequest.discard(requestId,
				status.identity()));
			if (!requestId.equals(discarded.requestId()) || (discarded.status() != RetainedTrialResponse.Status.DISCARDED &&
				discarded.status() != RetainedTrialResponse.Status.ABSENT))
				throw new IOException("The retained trial slot could not be confirmed absent");
		}
	}

	/** Lists request IDs that remain pending in a campaign's guard pilot. */
	@FunctionalInterface
	public interface PendingPilotRequests
	{
		/**
		 * Returns request IDs whose pilot trial recovery state remains pending.
		 *
		 * @param campaignId the campaign's identifier
		 * @return pending launch request IDs
		 * @throws IOException if the pilot cannot be read
		 */
		List<UUID> requestIds(String campaignId) throws IOException;
	}

	/**
	 * Deletes the nested sessions of a campaign below one results folder.
	 *
	 * @param resultsFolder the results folder, which holds {@code <harness>/<id>/session.json}
	 * @param owners the campaign directory's paths that a nested session's project path may start with
	 * @throws IOException if a directory cannot be listed or a nested session cannot be deleted
	 */
	private void removeResultsCopies(Path resultsFolder, List<Path> owners) throws IOException
	{
		if (!Files.isDirectory(resultsFolder, LinkOption.NOFOLLOW_LINKS))
			return;
		for (Path harness : LeftoverNestedSessions.list(resultsFolder))
		{
			for (Path sessionFolder : LeftoverNestedSessions.list(harness))
			{
				Optional<Path> project = projectPath(sessionFolder.resolve(MANIFEST));
				if (project.isPresent() && owners.stream().anyMatch(project.get()::startsWith))
					NestedSessionManager.deleteSessionFolder(sessionFolder);
			}
		}
	}

	/**
	 * Returns the project path that a session manifest records.
	 *
	 * @param manifest the nested session's manifest
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
		 * Removes the campaign's runs below every results folder.
		 */
		RUNS,
		/**
		 * Removes the campaign directory.
		 */
		CAMPAIGN_DIRECTORY
	}
}
