package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns an evaluation workspace's live-state publication, observation, and interruption-safety decisions.
 *
 * <p>
 * This type publishes a heartbeat while an evaluator owns its workspace, projects retained worker evidence for status
 * callers, and identifies the ownerless workers that recovery may change. It does not choose evaluation policy or
 * clean up terminal evidence.
 */
public final class Lifecycle
{
	static final long STATUS_HEARTBEAT_MILLIS = 10_000;
	static final long STATUS_STALE_MILLIS = STATUS_HEARTBEAT_MILLIS * 3;
	private static final String STATUS_FILE = ".cat-prompt-evaluation-status.json";
	private static final Map<Path, String> ACTIVE_PHASES = new ConcurrentHashMap<>();

	/**
	 * Prevents construction of this static lifecycle owner.
	 */
	private Lifecycle()
	{
	}

	/**
	 * Reports an evaluation's live workers with a short-lived caller scope.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the evaluator-owned workspace that may contain live worker runs
	 * @param summary whether to omit the complete retained-worker listing
	 * @return the observable evaluator and worker activity
	 * @throws IOException if the record or owned workspace cannot be read
	 */
	public static ObjectNode status(Path runPath, Path workspaceRoot, boolean summary) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return status(scope.json(), runPath, workspaceRoot, summary);
		}
	}

	/**
	 * Reports an evaluation's live workers using the phase each runner recorded in its retained manifest.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the evaluator-owned workspace that may contain live worker runs
	 * @param summary whether to omit the complete retained-worker listing
	 * @return the observable evaluator and worker activity
	 * @throws IOException if the record or owned workspace cannot be read
	 */
	public static ObjectNode status(JsonMapper json, Path runPath, Path workspaceRoot, boolean summary) throws IOException
	{
		Path normalizedRun = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();
		ObjectNode run = PromptEvaluation.object(RecordStore.read(json, normalizedRun), "run");
		if (run.path("workspace_finalized").asBoolean(false))
			return finalizedWorkspaceStatus(json, run, normalizedRun, summary);
		Path marker = EvaluationWorkspaceLifecycle.evidenceMarker(normalizedWorkspace);
		if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) ||
			!Files.readString(marker, StandardCharsets.UTF_8).strip().equals(normalizedRun.toString()))
		{
			throw new IOException("Workspace is not owned by this prompt evaluation: " + normalizedWorkspace);
		}

		ObjectNode result = json.createObjectNode();
		boolean terminal = terminalRecord(run);
		boolean active = !terminal &&
			(hasActiveEvaluationOwner(EvaluationWorkspaceLifecycle.activeMarker(normalizedWorkspace)) ||
				hasFreshPublishedStatus(json, normalizedWorkspace));
		ArrayNode workers = null;
		if (!active || !summary)
			workers = workerRecords(json, normalizedWorkspace);
		boolean orphanedWorker = !active && hasNonterminalWorker(workers);
		boolean activeOrphanedWorker = orphanedWorker && hasActiveWorkerOwner(workers);
		if (active)
		{
			result.put("evaluation_state", "running");
			result.put("last_completed_stage", "executing");
			result.put("next_action", "wait_for_terminal_result");
		}
		else if (terminal)
		{
			result.put("evaluation_state", "terminal");
			result.put("last_completed_stage", terminalStage(run));
			result.put("next_action", "finalize_evidence");
		}
		else
		{
			String evaluationState = "abandoned";
			if (orphanedWorker)
				evaluationState = "orphaned";
			result.put("evaluation_state", evaluationState);
			result.put("last_completed_stage", lastRecordedStage(run, normalizedRun));
			String nextAction = "recover_interruption";
			if (activeOrphanedWorker)
				nextAction = "wait_for_orphaned_workers";
			result.put("next_action", nextAction);
			if (!activeOrphanedWorker)
			{
				String recoveryMode = "resume_incomplete_batch";
				if (PromptEvaluation.resumableBatch(run, normalizedRun, normalizedWorkspace) == null)
					recoveryMode = "record_infrastructure_interruption";
				result.put("recovery_mode", recoveryMode);
			}
		}
		result.put("owner_active", active);
		if (active)
			addPublishedStatus(json, result, normalizedWorkspace);
		if (!summary)
			result.set("workers", workers);
		return result;
	}

	/**
	 * Reports an evaluation from its retained workspace with the caller's JSON mapper.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param runPath the persistent evaluation record
	 * @param summary whether to omit the complete retained-worker listing
	 * @return the observable evaluator and worker activity
	 * @throws IOException if the record lacks its prepared workspace or that workspace cannot be read
	 */
	public static ObjectNode status(JsonMapper json, Path runPath, boolean summary) throws IOException
	{
		ObjectNode run = PromptEvaluation.object(RecordStore.read(json, runPath), "run");
		if (run.path("workspace_finalized").asBoolean(false))
			return finalizedWorkspaceStatus(json, run, runPath, summary);
		return status(json, runPath, activeOrPreparedWorkspace(run, runPath), summary);
	}

	/**
	 * Reports an evaluation using the active workspace recorded in its persistent record.
	 *
	 * @param runPath the persistent evaluation record
	 * @param summary whether to omit the complete retained-worker listing
	 * @return the observable evaluator and worker activity
	 * @throws IOException if the record lacks its prepared workspace or that workspace cannot be read
	 */
	public static ObjectNode status(Path runPath, boolean summary) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			JsonMapper json = scope.json();
			ObjectNode run = PromptEvaluation.object(RecordStore.read(json, runPath), "run");
			if (run.path("workspace_finalized").asBoolean(false))
				return finalizedWorkspaceStatus(json, run, runPath, summary);
			return status(json, runPath, activeOrPreparedWorkspace(run, runPath), summary);
		}
	}

	/**
	 * Reports a terminal record whose transient workspace has already been removed.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param run the retained terminal record
	 * @param runPath the record location used in diagnostics
	 * @param summary whether callers requested the compact result
	 * @return the terminal status that does not require a deleted workspace
	 * @throws IOException if the record does not contain a terminal outcome
	 */
	static ObjectNode finalizedWorkspaceStatus(JsonMapper json, ObjectNode run, Path runPath, boolean summary)
		throws IOException
	{
		if (!terminalRecord(run))
			throw new IOException("A nonterminal evaluation cannot have a finalized workspace: " + runPath);
		ObjectNode result = json.createObjectNode();
		result.put("evaluation_state", "terminal");
		result.put("last_completed_stage", terminalStage(run));
		result.put("next_action", "report_retained_evidence");
		result.put("owner_active", false);
		result.put("workspace_finalized", true);
		if (!summary)
			result.putArray("workers");
		return result;
	}

	/**
	 * Produces a compact, atomic snapshot with a scope owned by that publication.
	 *
	 * @param workspaceRoot the evaluator-owned workspace
	 * @param phase the evaluator phase that owns the snapshot
	 * @throws IOException if the snapshot cannot be retained
	 */
	public static void publishActiveStatus(Path workspaceRoot, String phase) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			publishActiveStatus(scope.json(), workspaceRoot, phase);
		}
	}

	/**
	 * Produces a compact, atomic snapshot while an evaluator owns its workspace.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param workspaceRoot the evaluator-owned workspace
	 * @param phase the evaluator phase that owns the snapshot
	 * @throws IOException if the snapshot cannot be retained
	 */
	public static void publishActiveStatus(JsonMapper json, Path workspaceRoot, String phase) throws IOException
	{
		workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		if (!hasActiveEvaluationOwner(EvaluationWorkspaceLifecycle.activeMarker(workspaceRoot)))
			return;
		ACTIVE_PHASES.put(workspaceRoot, phase);
		writeActiveStatus(json, workspaceRoot, phase);
	}

	/**
	 * Records terminal workspace state with a scope owned by that publication.
	 *
	 * @param workspaceRoot the evaluator-owned workspace
	 * @throws IOException if the terminal status cannot be retained
	 */
	public static void publishTerminalStatus(Path workspaceRoot) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			publishTerminalStatus(scope.json(), workspaceRoot);
		}
	}

	/**
	 * Stops live-status tracking and atomically records the terminal workspace state.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param workspaceRoot the evaluator-owned workspace
	 * @throws IOException if the terminal status cannot be retained
	 */
	public static void publishTerminalStatus(JsonMapper json, Path workspaceRoot) throws IOException
	{
		workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		ACTIVE_PHASES.remove(workspaceRoot);
		Path statusPath = workspaceRoot.resolve(STATUS_FILE);
		long now = System.currentTimeMillis();
		long revision = 1;
		ObjectNode phaseDurations = json.createObjectNode();
		if (Files.isRegularFile(statusPath, NOFOLLOW_LINKS))
		{
			ObjectNode previous = PromptEvaluation.object(RecordStore.read(json, statusPath), "active evaluation status");
			revision = previous.path("revision").asLong() + 1;
			if (previous.path("phase_durations_millis").isObject())
				phaseDurations = PromptEvaluation.object(previous.path("phase_durations_millis"), "phase durations").deepCopy();
			String previousPhase = previous.path("phase").asString();
			long previousStartedAt = previous.path("phase_started_at_epoch_millis").asLong(now);
			if (!previousPhase.isBlank() && !previousPhase.equals("terminal"))
			{
				long duration = Math.max(0, now - previousStartedAt);
				phaseDurations.put(previousPhase, phaseDurations.path(previousPhase).asLong() + duration);
			}
		}
		ObjectNode status = json.createObjectNode();
		status.put("revision", revision);
		status.put("phase", "terminal");
		status.put("phase_started_at_epoch_millis", now);
		status.put("last_heartbeat_epoch_millis", now);
		status.set("phase_durations_millis", phaseDurations);
		status.putArray("active_workers");
		status.put("terminal", true);
		RecordStore.write(json, statusPath, status);
	}

	/**
	 * Starts one bounded status refresher for an active evaluator.
	 *
	 * @param workspaceRoot the owned evaluation workspace
	 * @param activeLock the claim held by that evaluator
	 * @return the refresher to close before relinquishing the workspace claim
	 */
	public static Heartbeat startHeartbeat(Path workspaceRoot, Lock activeLock)
	{
		return Heartbeat.start(workspaceRoot, activeLock);
	}

	/**
	 * Indicates whether a recently refreshed owner snapshot proves that an evaluator is still running.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param workspaceRoot the evaluator-owned workspace
	 * @return whether the owner refreshed its state within the heartbeat interval
	 * @throws IOException if an existing status snapshot is malformed
	 */
	static boolean hasFreshPublishedStatus(JsonMapper json, Path workspaceRoot) throws IOException
	{
		Path statusPath = workspaceRoot.resolve(STATUS_FILE);
		if (!Files.isRegularFile(statusPath, NOFOLLOW_LINKS))
			return false;
		ObjectNode status = PromptEvaluation.object(RecordStore.read(json, statusPath), "active evaluation status");
		long heartbeat = status.path("last_heartbeat_epoch_millis").asLong();
		return heartbeat > 0 && System.currentTimeMillis() - heartbeat <= STATUS_STALE_MILLIS;
	}

	/**
	 * Adds the active evaluator's last atomically published state to a caller result.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param result the caller result
	 * @param workspaceRoot the evaluator-owned workspace
	 * @throws IOException if a published state cannot be read
	 */
	static void addPublishedStatus(JsonMapper json, ObjectNode result, Path workspaceRoot) throws IOException
	{
		Path statusPath = workspaceRoot.resolve(STATUS_FILE);
		if (!Files.isRegularFile(statusPath, NOFOLLOW_LINKS))
		{
			result.put("status_revision", 0);
			result.put("current_phase", "starting");
			result.putArray("active_workers");
			result.put("workers_total", 0);
			return;
		}
		ObjectNode status = PromptEvaluation.object(RecordStore.read(json, statusPath), "active evaluation status");
		result.put("status_revision", status.path("revision").asLong());
		result.put("current_phase", PromptEvaluation.text(status, "phase", statusPath));
		long phaseStartedAt = status.path("phase_started_at_epoch_millis").asLong();
		if (phaseStartedAt > 0)
			result.put("current_phase_elapsed_millis", Math.max(0, System.currentTimeMillis() - phaseStartedAt));
		result.set("completed_phase_durations_millis", status.path("phase_durations_millis").deepCopy());
		ArrayNode activeWorkers = result.putArray("active_workers");
		for (JsonNode worker : PromptEvaluation.array(status.path("active_workers"), "active_workers", statusPath))
			activeWorkers.add(worker);
		result.put("workers_total", activeWorkers.size());
	}

	/**
	 * Returns retained worker observations with a short-lived caller scope.
	 *
	 * @param workspaceRoot the evaluator-owned workspace
	 * @return a stable representation of each retained worker
	 * @throws IOException if a retained manifest cannot be read
	 */
	public static ArrayNode workerRecords(Path workspaceRoot) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return workerRecords(scope.json(), workspaceRoot);
		}
	}

	/**
	 * Returns every retained worker manifest in an evaluator-owned workspace.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param workspaceRoot the evaluator-owned workspace
	 * @return a stable representation of each retained worker
	 * @throws IOException if a retained manifest cannot be read
	 */
	public static ArrayNode workerRecords(JsonMapper json, Path workspaceRoot) throws IOException
	{
		ArrayNode workers = json.createArrayNode();
		for (Path manifestPath : workerManifestPaths(workspaceRoot))
			addWorkerRecord(json, workers, manifestPath, workspaceRoot);
		return workers;
	}

	/**
	 * Locates only direct runner manifests in the evaluator-owned batch layout.
	 *
	 * @param workspaceRoot the evaluator-owned workspace
	 * @return direct runner manifests in stable path order
	 * @throws IOException if a present evaluator directory cannot be enumerated
	 */
	public static List<Path> workerManifestPaths(Path workspaceRoot) throws IOException
	{
		List<Path> manifests = new ArrayList<>();
		for (Path batch : childDirectories(workspaceRoot, "batch-[0-9]+|smoke|capability"))
			for (Path trial : childDirectories(batch, "trial-[0-9]+"))
				for (Path testCase : childDirectories(trial, ".+"))
					for (Path project : childDirectories(testCase, ".+"))
						addRunnerManifests(manifests, project.resolve("runner-runs"));
		for (Path batch : childDirectories(workspaceRoot, "batch-[0-9]+|smoke|capability"))
			for (Path validator : childDirectories(batch, ".*validator-batch"))
				addRunnerManifests(manifests, validator.resolve("runner-runs"));
		manifests.sort(Comparator.naturalOrder());
		return manifests;
	}

	/**
	 * Indicates whether a runner still owns a nonterminal worker through its exclusive run lease.
	 *
	 * @param manifestPath the retained worker manifest
	 * @return whether a runner still owns the worker
	 * @throws IOException if the runner lease cannot be inspected
	 */
	public static boolean hasActiveWorkerOwner(Path manifestPath) throws IOException
	{
		Path lockPath = manifestPath.getParent().resolve(".lock");
		try (FileChannel channel = FileChannel.open(lockPath, java.nio.file.StandardOpenOption.WRITE))
		{
			try (FileLock lock = channel.tryLock())
			{
				return lock == null;
			}
			catch (OverlappingFileLockException _)
			{
				return true;
			}
		}
	}

	/**
	 * Indicates whether retained worker observations include a nonterminal worker.
	 *
	 * @param workers the retained worker observations
	 * @return whether a retained worker is nonterminal
	 */
	public static boolean hasNonterminalWorker(ArrayNode workers)
	{
		for (JsonNode worker : workers)
			if (worker.path("state").asString().equals("running"))
				return true;
		return false;
	}

	/**
	 * Indicates whether a runner owns a running retained worker.
	 *
	 * @param workers the retained worker observations
	 * @return whether a runner owns a running worker
	 */
	public static boolean hasActiveWorkerOwner(ArrayNode workers)
	{
		for (JsonNode worker : workers)
			if (worker.path("state").asString().equals("running") && worker.path("owner_active").asBoolean())
				return true;
		return false;
	}

	/**
	 * Indicates whether a live evaluator still holds its workspace claim.
	 *
	 * @param activeMarker the workspace claim marker
	 * @return whether another evaluator still owns the workspace
	 * @throws IOException if the claim cannot be inspected
	 */
	public static boolean hasActiveEvaluationOwner(Path activeMarker) throws IOException
	{
		if (!Files.exists(activeMarker, NOFOLLOW_LINKS))
			return false;
		try (FileChannel channel = FileChannel.open(activeMarker, java.nio.file.StandardOpenOption.WRITE))
		{
			try (FileLock lock = channel.tryLock())
			{
				return lock == null;
			}
			catch (OverlappingFileLockException _)
			{
				return true;
			}
		}
	}

	/**
	 * Reports the last durable evaluation record when no active owner remains.
	 *
	 * @param run the persistent evaluation record
	 * @param runPath the record's location, used to validate its referenced ledger
	 * @return the latest stage whose evidence exists in the record
	 * @throws IOException if an existing attempt ledger cannot be inspected
	 */
	static String lastRecordedStage(ObjectNode run, Path runPath) throws IOException
	{
		if (run.hasNonNull("attempt_ledger") && Files.size(PromptEvaluation.path(
			PromptEvaluation.text(run, "attempt_ledger", runPath))) > 0)
		{
			return "recorded_attempt";
		}
		if (run.hasNonNull("execution_plan"))
			return "prepared_execution";
		return "prepared_run";
	}

	/**
	 * Returns the workspace currently owned by an evaluator, or the workspace prepared for its record.
	 *
	 * @param run the persistent evaluation record
	 * @param runPath the record path used in malformed-record diagnostics
	 * @return the normalized active or prepared workspace
	 * @throws IOException if the selected workspace field is missing or malformed
	 */
	public static Path activeOrPreparedWorkspace(ObjectNode run, Path runPath) throws IOException
	{
		if (run.hasNonNull("active_workspace"))
			return PromptEvaluation.path(PromptEvaluation.text(run, "active_workspace", runPath));
		return PromptEvaluation.path(PromptEvaluation.text(run, "workspace", runPath));
	}

	/**
	 * Determines whether retained evaluation evidence reaches a terminal status outcome.
	 *
	 * @param run the retained evaluation record
	 * @return whether the record has a terminal decision or completed bounded operation
	 */
	private static boolean terminalRecord(ObjectNode run)
	{
		return PromptEvaluation.isTerminalDecision(run.path("decision").path("overall_decision").asString()) ||
			PromptEvaluation.hasCompletedSmoke(run) || PromptEvaluation.hasCompletedRegressionCheck(run) ||
			PromptEvaluation.hasCompletedRoutingMatrix(run) || PromptEvaluation.hasCompletedReviewerControls(run);
	}

	/**
	 * Returns the last completed stage represented by a terminal record.
	 *
	 * @param run the retained terminal evaluation record
	 * @return the terminal record's completed stage
	 */
	private static String terminalStage(ObjectNode run)
	{
		if (PromptEvaluation.hasCompletedSmoke(run))
			return "smoke_completed";
		if (PromptEvaluation.hasCompletedRegressionCheck(run))
			return "regression_check_completed";
		if (PromptEvaluation.hasCompletedRoutingMatrix(run))
			return "routing_matrix_completed";
		if (PromptEvaluation.hasCompletedReviewerControls(run))
			return "reviewer_controls_completed";
		return "terminal_decision";
	}

	/**
	 * Refreshes the current evaluator state without changing its current phase.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param workspaceRoot the evaluator-owned workspace
	 * @param activeLock the claim held by this evaluator
	 * @throws IOException if the owner lost its workspace claim or the snapshot cannot be written
	 */
	private static void refreshActiveStatus(JsonMapper json, Path workspaceRoot, Lock activeLock) throws IOException
	{
		workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		requireThat(activeLock, "activeLock").isNotNull();
		String phase = ACTIVE_PHASES.get(workspaceRoot);
		if (phase == null)
			return;
		activeLock.verifyHeld();
		writeActiveStatus(json, workspaceRoot, phase);
	}

	/**
	 * Writes one atomic owner snapshot after ownership has been established.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param workspaceRoot the evaluator-owned workspace
	 * @param phase the current evaluator phase
	 * @throws IOException if the snapshot cannot be retained
	 */
	private static void writeActiveStatus(JsonMapper json, Path workspaceRoot, String phase) throws IOException
	{
		Path statusPath = workspaceRoot.resolve(STATUS_FILE);
		long revision = 1;
		long now = System.currentTimeMillis();
		ObjectNode phaseDurations = json.createObjectNode();
		long phaseStartedAt = now;
		if (Files.isRegularFile(statusPath, NOFOLLOW_LINKS))
		{
			ObjectNode previous = PromptEvaluation.object(RecordStore.read(json, statusPath), "active evaluation status");
			revision = previous.path("revision").asLong() + 1;
			if (previous.path("phase_durations_millis").isObject())
				phaseDurations = PromptEvaluation.object(previous.path("phase_durations_millis"), "phase durations").deepCopy();
			String previousPhase = previous.path("phase").asString();
			long previousStartedAt = previous.path("phase_started_at_epoch_millis").asLong(now);
			if (previousPhase.equals(phase))
				phaseStartedAt = previousStartedAt;
			else if (!previousPhase.isBlank())
			{
				long duration = Math.max(0, now - previousStartedAt);
				phaseDurations.put(previousPhase, phaseDurations.path(previousPhase).asLong() + duration);
			}
		}
		ObjectNode status = json.createObjectNode();
		status.put("revision", revision);
		status.put("phase", phase);
		status.put("phase_started_at_epoch_millis", phaseStartedAt);
		status.put("last_heartbeat_epoch_millis", now);
		status.set("phase_durations_millis", phaseDurations);
		ArrayNode activeWorkers = status.putArray("active_workers");
		for (JsonNode worker : workerRecords(json, workspaceRoot))
			if (worker.path("state").asString().equals("running"))
				activeWorkers.add(worker);
		RecordStore.write(json, statusPath, status);
	}

	/**
	 * Adds manifests directly retained by one executor or validator runner directory.
	 *
	 * @param manifests the destination collection
	 * @param runnerRuns the runner-owned result directory
	 * @throws IOException if a present runner directory cannot be enumerated
	 */
	private static void addRunnerManifests(List<Path> manifests, Path runnerRuns) throws IOException
	{
		requireThat(manifests, "manifests").isNotNull();
		requireThat(runnerRuns, "runnerRuns").isNotNull();
		if (!Files.isDirectory(runnerRuns, NOFOLLOW_LINKS))
			return;
		for (Path harness : childDirectories(runnerRuns, ".+"))
			for (Path runDirectory : childDirectories(harness, ".+"))
				manifests.add(runDirectory.resolve("run.json"));
	}

	/**
	 * Lists direct child directories whose names match the expected evaluator-owned layout.
	 *
	 * @param directory the parent directory
	 * @param namePattern the regular expression that accepted child names must match
	 * @return matching directories in stable name order, or no entries when concurrent cleanup removed the parent
	 * @throws IOException if a present directory cannot be enumerated
	 */
	private static List<Path> childDirectories(Path directory, String namePattern) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			return entries.filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS)).filter(path ->
				path.getFileName().toString().matches(namePattern)).sorted().toList();
		}
		catch (NoSuchFileException _)
		{
			return List.of();
		}
	}

	/**
	 * Adds one retained runner manifest to a live-status snapshot when it still exists.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param workers the snapshot being populated
	 * @param manifestPath the direct runner manifest location
	 * @param workspaceRoot the evaluator-owned workspace
	 * @throws IOException if a present manifest is malformed or cannot be inspected
	 */
	private static void addWorkerRecord(JsonMapper json, ArrayNode workers, Path manifestPath, Path workspaceRoot)
		throws IOException
	{
		if (!Files.isRegularFile(manifestPath, NOFOLLOW_LINKS))
			return;
		try
		{
			ObjectNode manifest = PromptEvaluation.object(RecordStore.read(json, manifestPath), "runner manifest");
			ObjectNode worker = workers.addObject();
			worker.put("location", workspaceRoot.relativize(manifestPath).toString());
			worker.put("harness", PromptEvaluation.text(manifest, "harness", manifestPath));
			worker.put("run_id", PromptEvaluation.text(manifest, "id", manifestPath));
			worker.put("state", PromptEvaluation.text(manifest, "state", manifestPath));
			String phase = PromptEvaluation.text(manifest, "phase", manifestPath);
			worker.put("phase", phase);
			worker.put("activity", activity(phase));
			worker.put("owner_active", hasActiveWorkerOwner(manifestPath));
			addWorkerLocation(worker, manifestPath, workspaceRoot);
		}
		catch (NoSuchFileException _)
		{
			// A completed runner can remove its manifest while this best-effort live status is being assembled.
		}
	}

	/**
	 * Returns the caller-facing activity represented by one runner phase.
	 *
	 * @param phase the runner-recorded phase
	 * @return the caller-facing activity description
	 */
	private static String activity(String phase)
	{
		return switch (phase)
		{
			case "initializing" -> "starting_harness";
			case "idle" -> "waiting_for_next_turn";
			case "working" -> "processing_submitted_turn";
			case "completed", "failed" -> "terminal";
			default -> "unknown";
		};
	}

	/**
	 * Adds a trial and testcase when the runner path uses the evaluator's standard batch layout.
	 *
	 * @param worker the output entry
	 * @param manifestPath the runner manifest
	 * @param workspaceRoot the evaluator-owned workspace
	 */
	private static void addWorkerLocation(ObjectNode worker, Path manifestPath, Path workspaceRoot)
	{
		Path relative = workspaceRoot.relativize(manifestPath);
		for (int index = 0; index + 1 < relative.getNameCount(); ++index)
		{
			String segment = relative.getName(index).toString();
			if (!segment.matches("trial-[0-9]+"))
				continue;
			worker.put("trial", Integer.parseInt(segment.substring("trial-".length())));
			worker.put("case_id", relative.getName(index + 1).toString());
			return;
		}
	}

	/**
	 * Keeps the evaluator's published state fresh while a harness turn is running.
	 */
	static final class Heartbeat implements EvaluationWorkspaceLifecycle.Heartbeat
	{
		private final ToolScope scope;
		private final JsonMapper json;
		private final ScheduledExecutorService executor;
		private final AtomicReference<IOException> failure = new AtomicReference<>();

		/**
		 * Creates a heartbeat owner.
		 *
		 * @param scope the scope that owns the heartbeat mapper
		 */
		private Heartbeat(ToolScope scope)
		{
			this.scope = scope;
			json = scope.json();
			executor = Executors.newSingleThreadScheduledExecutor();
		}

		/**
		 * Starts periodic publication for one owned workspace.
		 *
		 * @param workspaceRoot the evaluator-owned workspace
		 * @param activeLock the exclusive workspace claim
		 * @return the refresh owner that closes its executor
		 */
		private static Heartbeat start(Path workspaceRoot, Lock activeLock)
		{
			Heartbeat result = new Heartbeat(new ToolScope());
			result.executor.scheduleWithFixedDelay(() ->
			{
				try
				{
					refreshActiveStatus(result.json, workspaceRoot, activeLock);
				}
				catch (IOException e)
				{
					result.failure.compareAndSet(null, e);
				}
			}, STATUS_HEARTBEAT_MILLIS, STATUS_HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
			return result;
		}

		/**
		 * Fails the foreground evaluator when it can no longer publish a trustworthy live state.
		 *
		 * @throws IOException if a background refresh failed
		 */
		@Override
		public void verify() throws IOException
		{
			IOException exception = failure.get();
			if (exception != null)
				throw exception;
		}

		/**
		 * Stops refreshes before the caller relinquishes its workspace claim.
		 */
		@Override
		public void close()
		{
			executor.shutdownNow();
			scope.close();
		}
	}
}
