package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.optimization.development.FormalPromptTestScheduler;
import io.github.cowwoc.cat.common.optimization.development.PromptTestContractCatalog;
import io.github.cowwoc.cat.common.optimization.development.PromptTestGenerationPolicy;
import io.github.cowwoc.cat.common.optimization.development.PromptTestIdentityLedger;
import io.github.cowwoc.cat.common.optimization.development.PromptTestPoolPartition;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns one proof-directed formal scheduling and execution step.
 */
public final class FormalCertificationRound
{
	/**
	 * Schedules and executes one formal trial when the statistical engine requests another family sample.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param clock the completion clock
	 * @param allowanceManifest the lineage allowance manifest
	 * @param trialManifest the candidate formal-trial manifest
	 * @param policy the frozen statistical policy
	 * @param catalog the contract-bound formal promptTest catalog
	 * @param promptTestIdentityLedger the durable contract-wide evidence-pool partition
	 * @param seed the immutable formal draw seed
	 * @param executionIdentifier the idempotent execution identifier
	 * @param requestFactory the harness-specific typed request factory
	 * @param runner the harness execution boundary
	 * @param evaluator the approved frozen evaluator boundary
	 * @return the executed, blocked, or no-trial result
	 * @throws IOException if retained evidence or operation execution is invalid
	 */
	public static Result execute(JsonMapper json, Clock clock, Path allowanceManifest, Path trialManifest,
		FamilySequentialPolicyDocument.Loaded policy, PromptTestContractCatalog catalog, Path promptTestIdentityLedger,
		long seed, String executionIdentifier, RequestFactory requestFactory,
		HarnessEvaluationRunner runner, FormalTrialOperation.Evaluator evaluator) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(allowanceManifest, "allowanceManifest must not be null");
		Objects.requireNonNull(trialManifest, "trialManifest must not be null");
		Objects.requireNonNull(policy, "policy must not be null");
		Objects.requireNonNull(catalog, "catalog must not be null");
		Objects.requireNonNull(promptTestIdentityLedger, "promptTestIdentityLedger must not be null");
		Objects.requireNonNull(executionIdentifier, "executionIdentifier must not be null");
		Objects.requireNonNull(requestFactory, "requestFactory must not be null");
		Objects.requireNonNull(runner, "runner must not be null");
		Objects.requireNonNull(evaluator, "evaluator must not be null");
		FormalTrialLedger.Summary trialSummary = FormalTrialLedger.read(json, trialManifest);
		PromptTestIdentityLedger.Summary identitySummary = PromptTestIdentityLedger.read(json, promptTestIdentityLedger);
		if (!identitySummary.contractFingerprint().equals(trialSummary.identity().contractFingerprint()))
			throw new IOException("PromptTest identity ledger does not match the formal trial contract");

		Optional<FormalTrialLedger.RetainedOutcome> retained = FormalTrialLedger.find(json, trialManifest,
			executionIdentifier);
		if (retained.isPresent())
		{
			FormalTrialLedger.Outcome outcome = retained.orElseThrow().outcome();
			PromptTestGenerationPolicy.PromptTest generated = catalog.policy(outcome.familyId()).generate(seed);
			if (!generated.instanceIdentifier().equals(outcome.promptTestId()))
				throw new IOException("Formal trial retry changes the retained promptTest seed: " + executionIdentifier);
			PromptTestPoolPartition.Identity identity = identitySummary.identities().stream().
				filter(candidate -> candidate.executionIdentifier().equals(executionIdentifier)).findFirst().
				orElseThrow(() -> new IOException("Formal trial has no retained promptTest identity: " +
					executionIdentifier));
			FormalPromptTestScheduler.ScheduledPromptTest scheduled = new FormalPromptTestScheduler.ScheduledPromptTest(
				generated, identity);
			return executeScheduled(json, clock, allowanceManifest, trialManifest, policy, executionIdentifier,
				scheduled, requestFactory, runner, evaluator);
		}

		FamilySequentialDecision.Result decision = FamilySequentialEngine.decide(json, allowanceManifest,
			trialManifest, policy);
		if (decision.action() != FamilySequentialDecision.Action.SAMPLE_FAMILY)
			return new Result(Status.NO_TRIAL, decision, Optional.empty(), List.of());

		FormalPromptTestScheduler.Result scheduled = FormalPromptTestScheduler.schedule(decision, catalog, seed,
			executionIdentifier, identitySummary.identities());
		if (scheduled.status() == FormalPromptTestScheduler.Status.BLOCKED)
			return new Result(Status.BLOCKED, decision, Optional.empty(), scheduled.collisions());

		FormalPromptTestScheduler.ScheduledPromptTest promptTest = scheduled.scheduledPromptTest().orElseThrow();
		PromptTestIdentityLedger.Reservation reservation = PromptTestIdentityLedger.reserve(json, promptTestIdentityLedger,
			promptTest.identity());
		if (reservation.status() == PromptTestIdentityLedger.Status.BLOCKED)
			return new Result(Status.BLOCKED, decision, Optional.empty(), reservation.collisions());
		return executeScheduled(json, clock, allowanceManifest, trialManifest, policy, executionIdentifier, promptTest,
			requestFactory, runner, evaluator);
	}

	/**
	 * Materializes and executes one already-reserved scheduler-selected promptTest.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param clock the completion clock
	 * @param allowanceManifest the lineage allowance manifest
	 * @param trialManifest the candidate formal-trial manifest
	 * @param policy the frozen statistical policy
	 * @param executionIdentifier the idempotent execution identifier
	 * @param promptTest the exact retained formal promptTest
	 * @param requestFactory the harness-specific typed request factory
	 * @param runner the harness execution boundary
	 * @param evaluator the approved frozen evaluator boundary
	 * @return the retained operation result
	 * @throws IOException if materialization, execution, or evidence validation fails
	 */
	private static Result executeScheduled(JsonMapper json, Clock clock, Path allowanceManifest, Path trialManifest,
		FamilySequentialPolicyDocument.Loaded policy, String executionIdentifier,
		FormalPromptTestScheduler.ScheduledPromptTest promptTest, RequestFactory requestFactory,
		HarnessEvaluationRunner runner, FormalTrialOperation.Evaluator evaluator) throws IOException
	{
		HarnessEvaluationRequest harnessRequest = Objects.requireNonNull(
			requestFactory.create(promptTest, executionIdentifier), "requestFactory result must not be null");
		FormalTrialOperation.Request operationRequest = new FormalTrialOperation.Request(executionIdentifier, promptTest,
			harnessRequest);
		FormalTrialOperation.Result operationResult = FormalTrialOperation.execute(json, clock, allowanceManifest,
			trialManifest, policy, operationRequest, runner, evaluator);
		return new Result(Status.EXECUTED, operationResult.decision(), Optional.of(operationResult), List.of());
	}

	/**
	 * Creates the exact harness request for a scheduler-selected formal promptTest.
	 */
	@FunctionalInterface
	public interface RequestFactory
	{
		/**
		 * Creates one immutable formal trial request.
		 *
		 * @param promptTest the exact scheduler-selected promptTest
		 * @param executionIdentifier the idempotent execution identifier
		 * @return the typed harness request
		 * @throws IOException if request materialization fails
		 */
		HarnessEvaluationRequest create(FormalPromptTestScheduler.ScheduledPromptTest promptTest,
			String executionIdentifier) throws IOException;
	}

	/**
	 * Names the complete formal scheduling result.
	 */
	public enum Status
	{
		EXECUTED,
		BLOCKED,
		NO_TRIAL
	}

	/**
	 * Holds one formal scheduling and execution result.
	 *
	 * @param status the terminal step status
	 * @param decision the proof-covered decision before or after execution
	 * @param operationResult the executed operation result, when a trial ran
	 * @param collisions cross-pool identity collisions, when scheduling was blocked
	 */
	public record Result(Status status, FamilySequentialDecision.Result decision,
		Optional<FormalTrialOperation.Result> operationResult, List<PromptTestPoolPartition.Collision> collisions)
	{
		/**
		 * Validates one formal step result.
		 *
		 * @param status the terminal step status
		 * @param decision the proof-covered decision before or after execution
		 * @param operationResult the executed operation result, when a trial ran
		 * @param collisions cross-pool identity collisions, when scheduling was blocked
		 */
		public Result
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(decision, "decision must not be null");
			Objects.requireNonNull(operationResult, "operationResult must not be null");
			collisions = List.copyOf(collisions);
			if ((status == Status.EXECUTED) != operationResult.isPresent())
				throw new IllegalArgumentException("Only an executed result must contain operation evidence");
			if ((status == Status.BLOCKED) != !collisions.isEmpty())
				throw new IllegalArgumentException("Only a blocked result must contain identity collisions");
		}
	}

	/**
	 * Prevents construction of this static operation owner.
	 */
	private FormalCertificationRound()
	{
	}
}
