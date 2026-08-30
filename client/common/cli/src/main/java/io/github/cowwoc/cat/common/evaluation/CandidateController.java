package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PromptEvaluation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns one complete candidate transition and its durable liveness status.
 */
public final class CandidateController implements AutoCloseable
{
	private static final String LOCK_SUFFIX = ".controller.lock";
	private static final String STATUS_SUFFIX = ".controller.json";
	private static final long HEARTBEAT_MILLIS = 10_000;
	private static final long STALE_MILLIS = HEARTBEAT_MILLIS * 3;

	private final JsonMapper json;
	private final Path candidateStatePath;
	private final Path statusPath;
	private final Lock lock;
	private final ScheduledExecutorService executor;
	private final AtomicReference<IOException> failure = new AtomicReference<>();
	private ObjectNode identity;
	private String phase = "starting";
	private boolean terminal;

	/**
	 * Claims a candidate transition and begins publishing its liveness.
	 *
	 * @param json the mapper that creates controller status records
	 * @param access the coordinator-owned validation access
	 * @param candidateStatePath the candidate state to own
	 * @return the active transition owner
	 * @throws IOException if another controller already owns the candidate state
	 */
	@SuppressWarnings("PMD.CloseResource")
	public static CandidateController start(JsonMapper json, CandidateLifecycle.Access access, Path candidateStatePath)
		throws IOException
	{
		Path normalizedStatePath = candidateStatePath.toAbsolutePath().normalize();
		Path parent = normalizedStatePath.getParent();
		if (parent == null)
			throw new IOException("Candidate state must have a parent directory: " + normalizedStatePath);
		Files.createDirectories(parent);
		Path marker = normalizedStatePath.resolveSibling(normalizedStatePath.getFileName() + LOCK_SUFFIX);
		Lock lock = Lock.claim(marker);
		try
		{
			ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
			CandidateController result = new CandidateController(json, normalizedStatePath, lock, executor);
			result.publish("starting");
			executor.scheduleWithFixedDelay(result::refreshFromHeartbeat, HEARTBEAT_MILLIS, HEARTBEAT_MILLIS,
				TimeUnit.MILLISECONDS);
			return result;
		}
		catch (RuntimeException | IOException e)
		{
			lock.close();
			throw e;
		}
	}

	/**
	 * Creates an owner that already holds its candidate-transition claim.
	 *
	 * @param json the mapper that creates controller status records
	 * @param candidateStatePath the candidate state to own
	 * @param lock the held cross-process claim
	 * @param executor the heartbeat executor
	 */
	private CandidateController(JsonMapper json, Path candidateStatePath, Lock lock, ScheduledExecutorService executor)
	{
		this.json = json;
		identity = json.createObjectNode();
		this.candidateStatePath = candidateStatePath;
		statusPath = statusPath(candidateStatePath);
		this.lock = lock;
		this.executor = executor;
	}

	/**
	 * Publishes the transition phase before it performs work.
	 *
	 * @param phase the action currently owned by this controller
	 * @throws IOException if the controller lost ownership or cannot publish status
	 */
	public void publish(String phase) throws IOException
	{
		synchronized (this)
		{
			requireThat(phase, "phase").isNotBlank();
			verify();
			this.phase = phase;
			writeStatus("active", null);
		}
	}

	/**
	 * Records immutable transition inputs before preparatory work starts.
	 *
	 * @param access the coordinator-owned validation access
	 * @param suitePath the test population
	 * @param promptPath the delivered prompt bundle
	 * @param harness the selected harness
	 * @param contextPath the validator contract
	 * @param runner the selected runner
	 * @param buildPlanPath the build plan selecting the packaged runtime
	 * @param maximumConcurrency the shared worker limit
	 * @throws IOException if a selected input cannot be fingerprinted
	 */
	public void identify(CandidateLifecycle.Access access, Path suitePath, Path promptPath, String harness,
		Path contextPath, Path runner, Path buildPlanPath,
		int maximumConcurrency) throws IOException
	{
		synchronized (this)
		{
			requireThat(harness, "harness").isNotBlank();
			access.validateMaximumConcurrency(maximumConcurrency);
			ObjectNode selected = json.createObjectNode();
			addScenarioSuite(selected, suitePath);
			addFileInput(selected, "prompt_artifact", promptPath);
			addFileInput(selected, "execution_context", contextPath);
			addFileInput(selected, "runner", runner);
			addFileInput(selected, "build_plan", buildPlanPath);
			selected.put("harness", harness);
			selected.put("maximum_concurrency", maximumConcurrency);
			identity = selected;
			writeStatus("active", null);
		}
	}

	/**
	 * Records a successful terminal transition.
	 *
	 * @throws IOException if the terminal status cannot be persisted
	 */
	public void complete() throws IOException
	{
		synchronized (this)
		{
			verify();
			terminal = true;
			writeStatus("completed", null);
		}
	}

	/**
	 * Records a failed terminal transition before the caller receives its failure.
	 *
	 * @param failure the transition failure
	 * @throws IOException if the terminal status cannot be persisted
	 */
	public void fail(IOException failure) throws IOException
	{
		synchronized (this)
		{
			requireThat(failure, "failure").isNotNull();
			terminal = true;
			writeStatus("failed", failure.getMessage());
		}
	}

	/**
	 * Stops heartbeats and records interruption when no terminal status exists.
	 *
	 * @throws IOException if the ownership claim cannot be released
	 */
	@Override
	public void close() throws IOException
	{
		synchronized (this)
		{
			executor.shutdownNow();
			if (!terminal)
			{
				terminal = true;
				writeStatus("interrupted", "Controller closed before recording a terminal transition");
			}
			lock.close();
		}
	}

	/**
	 * Refreshes status from the heartbeat and preserves any refresh failure for foreground work.
	 */
	private void refreshFromHeartbeat()
	{
		try
		{
			refresh();
		}
		catch (IOException e)
		{
			failure.compareAndSet(null, e);
		}
	}

	/**
	 * Refreshes liveness without changing the visible phase.
	 *
	 * @throws IOException if the controller lost its ownership claim
	 */
	private void refresh() throws IOException
	{
		synchronized (this)
		{
			if (!terminal)
				writeStatus("active", null);
		}
	}

	/**
	 * Verifies heartbeat and claim health before a foreground transition.
	 *
	 * @throws IOException if the heartbeat or ownership claim failed
	 */
	private void verify() throws IOException
	{
		IOException exception = failure.get();
		if (exception != null)
			throw exception;
		lock.verifyHeld();
	}

	/**
	 * Writes the current controller snapshot atomically through the evaluator record boundary.
	 *
	 * @param state the controller lifecycle state
	 * @param failureMessage the optional terminal failure diagnostic
	 * @throws IOException if the snapshot cannot be persisted
	 */
	private void writeStatus(String state, String failureMessage) throws IOException
	{
		lock.verifyHeld();
		long revision = 1;
		if (Files.isRegularFile(statusPath, LinkOption.NOFOLLOW_LINKS))
			revision = PromptEvaluation.object(PromptEvaluation.read(statusPath), "candidate controller status").
				path("revision").asLong() + 1;
		ObjectNode status = json.createObjectNode();
		status.put("revision", revision);
		status.put("candidate_state", candidateStatePath.toString());
		status.put("controller_state", state);
		String currentPhase = phase;
		if (terminal)
			currentPhase = "terminal";
		status.put("current_phase", currentPhase);
		status.put("last_heartbeat_epoch_millis", System.currentTimeMillis());
		status.set("evaluation_identity", identity.deepCopy());
		status.putArray("active_workers");
		if (failureMessage != null && !failureMessage.isBlank())
			status.put("failure", failureMessage);
		PromptEvaluation.write(statusPath, status);
	}

	/**
	 * Adds one selected scenario suite and its current content identity.
	 *
	 * @param identity the transition identity being written
	 * @param suitePath the selected suite root
	 * @throws IOException if the suite cannot be fingerprinted
	 */
	private static void addScenarioSuite(ObjectNode identity, Path suitePath) throws IOException
	{
		Path normalizedSuite = suitePath.toAbsolutePath().normalize();
		identity.put("suite", normalizedSuite.toString());
		if (Files.isDirectory(normalizedSuite, LinkOption.NOFOLLOW_LINKS))
			identity.put("suite_sha256", PromptEvaluation.suiteFingerprint(normalizedSuite));
		else
			identity.put("suite_state", "unavailable");
	}

	/**
	 * Adds one selected file input and its current content identity.
	 *
	 * @param identity the transition identity being written
	 * @param field the input's semantic field name
	 * @param input the selected input path
	 * @throws IOException if a regular input cannot be fingerprinted
	 */
	private static void addFileInput(ObjectNode identity, String field, Path input) throws IOException
	{
		Path normalizedInput = input.toAbsolutePath().normalize();
		identity.put(field, normalizedInput.toString());
		if (Files.isRegularFile(normalizedInput, LinkOption.NOFOLLOW_LINKS))
			identity.put(field + "_sha256", PromptEvaluation.sha256(normalizedInput));
		else
			identity.put(field + "_state", "unavailable");
	}

	/**
	 * Returns the current controller snapshot only while its heartbeat remains current.
	 *
	 * @param candidateStatePath the candidate state the controller owns
	 * @return the active snapshot, or {@code null} when no current controller owns the transition
	 * @throws IOException if an existing controller snapshot is malformed
	 */
	public static ObjectNode activeStatus(Path candidateStatePath) throws IOException
	{
		Path statusPath = statusPath(candidateStatePath);
		if (!Files.isRegularFile(statusPath, LinkOption.NOFOLLOW_LINKS))
			return null;
		ObjectNode status = PromptEvaluation.object(PromptEvaluation.read(statusPath), "candidate controller status");
		if (!status.path("controller_state").asString().equals("active"))
			return null;
		long heartbeat = status.path("last_heartbeat_epoch_millis").asLong();
		if (heartbeat <= 0 || System.currentTimeMillis() - heartbeat > STALE_MILLIS)
			return null;
		return status;
	}

	/**
	 * Returns the adjacent durable status location for one candidate state.
	 *
	 * @param candidateStatePath the candidate state the controller owns
	 * @return the status location
	 */
	public static Path statusPath(Path candidateStatePath)
	{
		Path normalizedStatePath = candidateStatePath.toAbsolutePath().normalize();
		return normalizedStatePath.resolveSibling(normalizedStatePath.getFileName() + STATUS_SUFFIX);
	}
}
