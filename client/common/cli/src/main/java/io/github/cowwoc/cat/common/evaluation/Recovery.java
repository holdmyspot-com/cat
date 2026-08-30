package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import static io.github.cowwoc.cat.common.PromptEvaluation.decide;
import static io.github.cowwoc.cat.common.PromptEvaluation.isTerminalDecision;
import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.read;
import static io.github.cowwoc.cat.common.PromptEvaluation.recordedMaximumConcurrency;
import static io.github.cowwoc.cat.common.PromptEvaluation.resumableBatch;
import static io.github.cowwoc.cat.common.PromptEvaluation.runUntilTerminal;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;
import static io.github.cowwoc.cat.common.PromptEvaluation.validateEvidenceIntegrity;
import static io.github.cowwoc.cat.common.PromptEvaluation.writeText;
import static io.github.cowwoc.cat.common.evaluation.EvaluationWorkspaceLifecycle.interruptionMarker;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.runner.HarnessConcurrencyLimit;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.runner.NativeHarnessEvaluationRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recovers an interrupted evaluation or terminalizes workers whose owners have exited.
 * <p>
 * This owner changes retained lifecycle state only after proving that the recorded workspace belongs to the evaluation
 * and no evaluator or runner still owns that state. Status projection remains read-only in
 * {@link PromptEvaluationStatus}.
 */
public final class Recovery
{
	/**
	 * Prevents instantiation of this static lifecycle owner.
	 */
	private Recovery()
	{
	}

	/**
	 * Recovers a stopped evaluator after recording every ownerless worker as interrupted.
	 *
	 * @param json the operation-owned mapper used by any resumed runner
	 * @param runPath the persistent evaluation record
	 * @return the terminal decision, or the decision reached after resuming retained incomplete work
	 * @throws IOException if an evaluator or worker still owns work, or the workspace is not owned by the record
	 */
	public static ObjectNode recoverInterruptedEvaluation(JsonMapper json, Path runPath) throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		return recoverInterruptedEvaluation(json, runPath, Lifecycle.activeOrPreparedWorkspace(run, runPath));
	}

	/**
	 * Recovers a stopped evaluator after recording every ownerless worker as interrupted.
	 *
	 * @param json the operation-owned mapper used by any resumed runner
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the evaluator-owned workspace recorded for this evaluation
	 * @return the terminal decision, or the decision reached after resuming retained incomplete work
	 * @throws IOException if an evaluator or worker still owns work, or the workspace is not owned by the record
	 */
	public static ObjectNode recoverInterruptedEvaluation(JsonMapper json, Path runPath, Path workspaceRoot)
		throws IOException
	{
		requireThat(json, "json").isNotNull();
		return recoverInterruptedEvaluation(runPath, workspaceRoot, new NativeHarnessEvaluationRunner(json));
	}

	/**
	 * Recovers a stopped evaluator with the supplied runner for retained incomplete work.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the evaluator-owned workspace recorded for this evaluation
	 * @param evaluationRunner the runner that completes retained executor and validator work
	 * @return the terminal decision, or the decision reached after resuming retained incomplete work
	 * @throws IOException if an evaluator or worker still owns work, or the workspace is not owned by the record
	 */
	public static ObjectNode recoverInterruptedEvaluation(Path runPath, Path workspaceRoot,
		HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		runPath = runPath.toAbsolutePath().normalize();
		workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		ObjectNode run = object(read(runPath), "run");
		verifyWorkspaceOwnership(runPath, workspaceRoot);

		Path activeMarker = EvaluationWorkspaceLifecycle.activeMarker(workspaceRoot);
		Path resumptionPlan = null;
		int resumptionConcurrency = HarnessConcurrencyLimit.DEFAULT_MAXIMUM_CONCURRENCY;
		boolean ownsRecoveryMarker = false;
		try (Lock recoveryLock = Lock.claim(activeMarker))
		{
			recoveryLock.verifyHeld();
			ownsRecoveryMarker = true;
			ArrayNode workers = Lifecycle.workerRecords(workspaceRoot);
			if (Lifecycle.hasNonterminalWorker(workers))
			{
				if (Lifecycle.hasActiveWorkerOwner(workers))
					throw new IOException("Cannot recover an evaluation while a runner owns an orphaned worker: " +
						workspaceRoot);
				terminalizeOwnerlessWorkers(workspaceRoot);
			}
			if (isTerminalDecision(run.path("decision").path("overall_decision").asString()))
				return decide(runPath);
			Path resumable = resumableBatch(run, runPath, workspaceRoot);
			if (resumable != null)
			{
				resumptionPlan = Path.of(text(run, "execution_plan", runPath)).toAbsolutePath().normalize();
				resumptionConcurrency = recordedMaximumConcurrency(run, runPath);
			}
			else
				writeText(interruptionMarker(runPath), "interrupted\n");
		}
		finally
		{
			if (ownsRecoveryMarker)
				Files.deleteIfExists(activeMarker);
		}
		if (resumptionPlan != null)
			return runUntilTerminal(runPath, workspaceRoot, resumptionPlan, resumptionConcurrency, evaluationRunner);
		try
		{
			validateEvidenceIntegrity(run, runPath);
		}
		catch (IOException e)
		{
			writeText(interruptionMarker(runPath), "Evidence integrity failed during recovery: " + e.getMessage() +
				System.lineSeparator());
		}
		return decide(runPath);
	}

	/**
	 * Records ownerless retained harness runs as failed after their evaluator exited.
	 *
	 * @param runPath the persistent evaluation record
	 * @throws IOException if the evaluation is active or a retained worker cannot be stopped safely
	 */
	public static void terminateWorkers(Path runPath) throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		terminateWorkers(runPath, Lifecycle.activeOrPreparedWorkspace(run, runPath));
	}

	/**
	 * Records ownerless retained harness runs as failed after their evaluator exited.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the evaluator-owned workspace
	 * @throws IOException if the evaluation is active or a retained worker cannot be stopped safely
	 */
	public static void terminateWorkers(Path runPath, Path workspaceRoot) throws IOException
	{
		runPath = runPath.toAbsolutePath().normalize();
		workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		if (Lifecycle.hasActiveEvaluationOwner(EvaluationWorkspaceLifecycle.activeMarker(workspaceRoot)))
			throw new IOException("Cannot terminate workers while their evaluator owns the workspace: " + workspaceRoot);
		object(read(runPath), "run");
		verifyWorkspaceOwnership(runPath, workspaceRoot);
		ArrayNode workers = Lifecycle.workerRecords(workspaceRoot);
		if (Lifecycle.hasActiveWorkerOwner(workers))
			throw new IOException("Cannot terminate workers while a runner owns an orphaned worker: " + workspaceRoot);
		terminalizeOwnerlessWorkers(workspaceRoot);
	}

	/**
	 * Proves that a workspace's evidence marker names the evaluation requesting a lifecycle mutation.
	 *
	 * @param runPath the normalized persistent evaluation record
	 * @param workspaceRoot the normalized workspace proposed for mutation
	 * @throws IOException if the workspace lacks the evaluation's ownership marker
	 */
	private static void verifyWorkspaceOwnership(Path runPath, Path workspaceRoot) throws IOException
	{
		Path marker = EvaluationWorkspaceLifecycle.evidenceMarker(workspaceRoot);
		if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) ||
			!Files.readString(marker, StandardCharsets.UTF_8).strip().equals(runPath.toString()))
		{
			throw new IOException("Workspace is not owned by this prompt evaluation: " + workspaceRoot);
		}
	}

	/**
	 * Records every ownerless running harness as failed so a later evaluator can resume retained work or record an
	 * interruption.
	 *
	 * @param workspaceRoot the evaluator-owned workspace containing retained runner manifests
	 * @throws IOException if a retained runner manifest cannot be read or updated
	 */
	public static void terminalizeOwnerlessWorkers(Path workspaceRoot) throws IOException
	{
		for (Path manifestPath : Lifecycle.workerManifestPaths(workspaceRoot))
		{
			if (!Files.isRegularFile(manifestPath, NOFOLLOW_LINKS))
				continue;
			ObjectNode manifest = object(read(manifestPath), "runner manifest");
			if (!text(manifest, "state", manifestPath).equals("running"))
				continue;
			Path runDirectory = manifestPath.getParent();
			Path runRoot = runDirectory.getParent().getParent();
			new io.github.cowwoc.cat.common.runner.HarnessRunManager(runRoot, java.time.Clock.systemUTC()).fail(
				text(manifest, "harness", manifestPath), text(manifest, "id", manifestPath),
				"Evaluator exited before this worker reached a terminal result.", 1);
		}
	}
}
