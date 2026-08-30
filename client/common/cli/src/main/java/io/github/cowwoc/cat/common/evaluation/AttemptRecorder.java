package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Admits one claimed testcase result and constructs its immutable retained attempt evidence.
 */
public final class AttemptRecorder
{
	/**
	 * Carries worker timing without exposing the coordinator's native-process implementation.
	 *
	 * @param queuedAtNanos the worker queue boundary
	 * @param executionStartedAtNanos the local execution-start boundary
	 * @param commandStartedAtNanos the native-command start boundary
	 * @param commandCompletedAtNanos the native-command completion boundary
	 * @param validationStartedAtNanos the validator-start boundary, or a negative value when absent
	 * @param validationCompletedAtNanos the validator-completion boundary, or a negative value when absent
	 * @param mergedAtNanos the result-merge boundary, or a negative value when absent
	 */
	public record Timing(long queuedAtNanos, long executionStartedAtNanos, long commandStartedAtNanos,
		long commandCompletedAtNanos, long validationStartedAtNanos, long validationCompletedAtNanos,
		long mergedAtNanos)
	{
	}

	/**
	 * Prevents construction of this static owner.
	 */
	private AttemptRecorder()
	{
	}

	/**
	 * Records one claimed testcase result while the caller owns the run lock.
	 *
	 * @param runPath the persistent result record
	 * @param caseId the testcase identifier
	 * @param trial the claimed whole-prompt trial number
	 * @param status the validated outcome
	 * @param rawResultPath the retained raw-result path
	 * @param validatorResultPath the retained validator-result path
	 * @param deterministicResultPath the optional retained deterministic-check receipt
	 * @param timing the optional measured worker timing
	 * @throws IOException if the claim, artifacts, or record transition is invalid
	 */
	public static void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResultPath,
		Path validatorResultPath, Path deterministicResultPath, Timing timing) throws IOException
	{
		ObjectNode run = PromptEvaluation.object(PromptEvaluation.read(runPath), "run");
		Path suitePath = PromptEvaluation.path(PromptEvaluation.text(run, "suite", runPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		int nextTrial = run.path("next_trial").asInt(-1);
		if (trial >= nextTrial)
			throw new IOException("Trial was not claimed: " + trial);
		if (!PromptEvaluation.containsCase(suite, caseId))
			throw new IOException("Trial names no suite test case: " + caseId);
		ObjectNode attemptIndex = PromptEvaluation.attemptIndex(run, runPath);
		String trialKey = Integer.toString(trial);
		ObjectNode trialIndex;
		if (attemptIndex.has(trialKey))
			trialIndex = PromptEvaluation.object(attemptIndex.path(trialKey), "attempt index");
		else
			trialIndex = attemptIndex.putObject(trialKey);
		if (trialIndex.has(caseId))
			throw new IOException("Trial is already recorded: " + caseId + "/" + trial);
		ObjectNode attempt = PromptEvaluation.newObject();
		attempt.put("case_id", caseId);
		attempt.put("trial", trial);
		attempt.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
		recordArtifact(attempt, "raw_result", rawResultPath);
		recordArtifact(attempt, "validator_result", validatorResultPath);
		if (deterministicResultPath != null)
		{
			if (!Files.isRegularFile(deterministicResultPath))
				throw new IOException("Deterministic check result is not a regular file: " + deterministicResultPath);
			recordArtifact(attempt, "deterministic_result", deterministicResultPath);
		}
		if (timing != null)
			attempt.set("performance", performance(timing));
		if (PromptEvaluation.completeSuiteRunCount(run, suite, runPath) > PromptEvaluation.maximumSuiteRuns())
		{
			throw new IOException("Evaluation reached the maximum complete-suite runs: " +
				PromptEvaluation.maximumSuiteRuns());
		}
		PromptEvaluation.appendAttempt(run, runPath, attempt);
		trialIndex.put(caseId, status.name().toLowerCase(java.util.Locale.ROOT));
		PromptEvaluation.write(runPath, run);
	}

	/**
	 * Adds one artifact path and fingerprint to an immutable attempt.
	 *
	 * @param attempt the attempt under construction
	 * @param field the artifact field prefix
	 * @param artifact the retained artifact
	 * @throws IOException if the artifact cannot be fingerprinted
	 */
	private static void recordArtifact(ObjectNode attempt, String field, Path artifact) throws IOException
	{
		attempt.put(field, artifact.toString());
		attempt.put(field + "_sha256", PromptEvaluation.sha256(artifact));
	}

	/**
	 * Converts monotonic worker boundaries into stable non-negative duration fields.
	 *
	 * @param timing the complete or executor-only timing
	 * @return the retained worker performance record
	 */
	private static ObjectNode performance(Timing timing)
	{
		ObjectNode result = PromptEvaluation.newObject();
		result.put("queue_millis", elapsedMillis(timing.queuedAtNanos(), timing.executionStartedAtNanos()));
		result.put("startup_millis", elapsedMillis(timing.executionStartedAtNanos(), timing.commandStartedAtNanos()));
		result.put("execution_millis", elapsedMillis(timing.commandStartedAtNanos(), timing.commandCompletedAtNanos()));
		if (timing.validationStartedAtNanos() < 0)
			return result;
		result.put("validation_millis", elapsedMillis(timing.validationStartedAtNanos(),
			timing.validationCompletedAtNanos()));
		result.put("merge_millis", elapsedMillis(timing.validationCompletedAtNanos(), timing.mergedAtNanos()));
		return result;
	}

	/**
	 * Returns the non-negative elapsed milliseconds between two monotonic boundaries.
	 *
	 * @param startedAtNanos the earlier boundary
	 * @param completedAtNanos the later boundary
	 * @return the elapsed whole milliseconds
	 */
	private static long elapsedMillis(long startedAtNanos, long completedAtNanos)
	{
		return TimeUnit.NANOSECONDS.toMillis(Math.max(0, completedAtNanos - startedAtNanos));
	}
}
