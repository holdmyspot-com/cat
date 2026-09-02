package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationOperation;
import io.github.cowwoc.cat.common.runner.HarnessRequestBinding;
import io.github.cowwoc.cat.common.optimization.development.FormalPromptTestScheduler;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationFailure;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns one scheduled formal trial across the durable harness, evaluator, outcome, and decision boundaries.
 */
public final class FormalTrialOperation
{
	/**
	 * Executes one selected family trial or returns its complete retained result.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param clock the completion clock
	 * @param allowanceManifest the lineage allowance manifest
	 * @param trialManifest the candidate's formal trial manifest
	 * @param policy the loaded statistical policy
	 * @param request the immutable promptTest and harness request
	 * @param runner the harness execution boundary
	 * @param evaluator the frozen evaluator boundary
	 * @return the retained outcome, next decision, and optional infrastructure diagnostic
	 * @throws IOException if evidence is invalid or an operation fails before it can retain terminal evidence
	 */
	public static Result execute(JsonMapper json, Clock clock, Path allowanceManifest, Path trialManifest,
		FamilySequentialPolicyDocument.Loaded policy, Request request, HarnessEvaluationRunner runner,
		Evaluator evaluator) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(allowanceManifest, "allowanceManifest must not be null");
		Objects.requireNonNull(trialManifest, "trialManifest must not be null");
		Objects.requireNonNull(policy, "policy must not be null");
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(runner, "runner must not be null");
		Objects.requireNonNull(evaluator, "evaluator must not be null");
		HarnessRequestBinding.requireExactPrompt(json, request.harnessRequest(),
		request.scheduledPromptTest().promptTest().renderedPrompt(), "Formal trial");

		Optional<FormalTrialLedger.RetainedOutcome> existing = FormalTrialLedger.find(json, trialManifest,
			request.operationId());
		if (existing.isPresent())
		{
			FormalTrialLedger.RetainedOutcome retained = existing.orElseThrow();
			verifyRequest(retained.outcome(), request);
			HarnessEvaluationOperation.retained(json, request.harnessRequest());
			Optional<String> infrastructureFailure = Optional.empty();
			if (retained.outcome().verdict() == FormalTrialLedger.Verdict.INFRASTRUCTURE_FAILURE)
				infrastructureFailure = Optional.of("Retained infrastructure failure");
			return result(json, allowanceManifest, trialManifest, policy, retained, infrastructureFailure);
		}

		FamilySequentialDecision.Result before = FamilySequentialEngine.decide(json, allowanceManifest, trialManifest,
			policy);
		if (before.action() != FamilySequentialDecision.Action.SAMPLE_FAMILY)
			throw new IOException("Formal trial cannot start from decision: " + before.action());
		String selectedFamily = before.familyId().orElseThrow();
		if (!selectedFamily.equals(request.familyId()))
		{
			throw new IOException("Formal trial promptTest belongs to " + request.familyId() +
				" but the scheduler selected " + selectedFamily);
		}

		Path harnessManifest = HarnessEvaluationOperation.manifestPath(request.harnessRequest());
		HarnessEvaluationExecution execution;
		try
		{
			execution = HarnessEvaluationOperation.execute(json, request.harnessRequest(), runner);
		}
		catch (HarnessEvaluationFailure failure)
		{
			if (!Files.isRegularFile(harnessManifest))
				throw failure;
			return retainInfrastructureFailure(json, clock, allowanceManifest, trialManifest, policy, request,
				harnessManifest, failure.getMessage());
		}

		String executorFingerprint = Fingerprints.ofFile(harnessManifest);
		Evaluation evaluation;
		try
		{
			evaluation = evaluator.evaluate(execution);
		}
		catch (IOException failure)
		{
			return retainInfrastructureFailure(json, clock, allowanceManifest, trialManifest, policy, request,
				harnessManifest, failure.getMessage());
		}
		FormalTrialLedger.Outcome outcome = new FormalTrialLedger.Outcome(request.operationId(), request.familyId(),
			request.promptTestId(), evaluation.verdict(), executorFingerprint,
			Optional.of(evaluation.evidenceFingerprint()), clock.instant());
		FormalTrialLedger.RetainedOutcome retained = FormalTrialLedger.record(json, trialManifest, outcome);
		return result(json, allowanceManifest, trialManifest, policy, retained, Optional.empty());
	}

	/**
	 * Retains a terminal infrastructure failure after the atomic operation has evidence.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param clock the completion clock
	 * @param allowanceManifest the lineage allowance manifest
	 * @param trialManifest the formal trial manifest
	 * @param policy the loaded policy
	 * @param request the trial request
	 * @param harnessManifest the retained harness operation evidence
	 * @param message the infrastructure diagnostic
	 * @return the retained result and next statistical decision
	 * @throws IOException if terminal evidence cannot be retained
	 */
	private static Result retainInfrastructureFailure(JsonMapper json, Clock clock, Path allowanceManifest,
		Path trialManifest, FamilySequentialPolicyDocument.Loaded policy, Request request, Path harnessManifest,
		String message) throws IOException
	{
		FormalTrialLedger.Outcome outcome = new FormalTrialLedger.Outcome(request.operationId(), request.familyId(),
			request.promptTestId(), FormalTrialLedger.Verdict.INFRASTRUCTURE_FAILURE,
			Fingerprints.ofFile(harnessManifest), Optional.empty(), clock.instant());
		FormalTrialLedger.RetainedOutcome retained = FormalTrialLedger.record(json, trialManifest, outcome);
		String diagnostic = message;
		if (diagnostic == null || diagnostic.isBlank())
			diagnostic = "Formal trial infrastructure failed";
		return result(json, allowanceManifest, trialManifest, policy, retained, Optional.of(diagnostic));
	}

	/**
	 * Projects the post-outcome decision.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param allowanceManifest the lineage allowance manifest
	 * @param trialManifest the formal trial manifest
	 * @param policy the loaded policy
	 * @param retained the retained formal outcome
	 * @param infrastructureFailure the optional failure diagnostic
	 * @return the complete operation result
	 * @throws IOException if decision evidence is invalid
	 */
	private static Result result(JsonMapper json, Path allowanceManifest, Path trialManifest,
		FamilySequentialPolicyDocument.Loaded policy, FormalTrialLedger.RetainedOutcome retained,
		Optional<String> infrastructureFailure) throws IOException
	{
		FamilySequentialDecision.Result decision = FamilySequentialEngine.decide(json, allowanceManifest, trialManifest,
			policy);
		return new Result(retained, decision, infrastructureFailure);
	}

	/**
	 * Verifies a retry identifies the same family and promptTest before reading retained harness evidence.
	 *
	 * @param retained the retained outcome
	 * @param request the retry request
	 * @throws IOException if the retry changes formal input
	 */
	private static void verifyRequest(FormalTrialLedger.Outcome retained, Request request) throws IOException
	{
		if (!retained.familyId().equals(request.familyId()) || !retained.promptTestId().equals(request.promptTestId()))
			throw new IOException("Formal trial retry changes the retained family or promptTest: " + request.operationId());
	}

	/**
	 * Evaluates one completed harness execution under the frozen assertion protocol.
	 */
	@FunctionalInterface
	public interface Evaluator
	{
		/**
		 * Produces one complete semantic verdict and its retained evidence fingerprint.
		 *
		 * @param execution the retained harness evidence
		 * @return the evaluator verdict
		 * @throws IOException if evaluation infrastructure cannot produce a verdict
		 */
		Evaluation evaluate(HarnessEvaluationExecution execution) throws IOException;
	}

	/**
	 * Holds one immutable formal trial request.
	 *
	 * @param operationId the idempotent operation identifier
	 * @param scheduledPromptTest the scheduler-selected family, draw, and retained pool identity
	 * @param harnessRequest the complete typed harness request
	 */
	public record Request(String operationId, FormalPromptTestScheduler.ScheduledPromptTest scheduledPromptTest,
		HarnessEvaluationRequest harnessRequest)
	{
		/**
		 * Validates one formal trial request.
		 *
		 * @param operationId the idempotent operation identifier
		 * @param scheduledPromptTest the scheduler-selected family, draw, and retained pool identity
		 * @param harnessRequest the complete typed harness request
		 */
		public Request
		{
			operationId = requireText(operationId, "operationId");
			Objects.requireNonNull(scheduledPromptTest, "scheduledPromptTest must not be null");
			Objects.requireNonNull(harnessRequest, "harnessRequest must not be null");
		}

		/**
		 * Returns the scheduler-selected family identifier.
		 *
		 * @return the frozen family identifier
		 */
		public String familyId()
		{
			return scheduledPromptTest.promptTest().familyIdentifier();
		}

		/**
		 * Returns the scheduler-selected promptTest identifier.
		 *
		 * @return the formal promptTest instance identifier
		 */
		public String promptTestId()
		{
			return scheduledPromptTest.promptTest().instanceIdentifier();
		}
	}

	/**
	 * Holds one semantic evaluator result.
	 *
	 * @param verdict the pass or fail verdict
	 * @param evidenceFingerprint the evaluator evidence fingerprint
	 */
	public record Evaluation(FormalTrialLedger.Verdict verdict, String evidenceFingerprint)
	{
		/**
		 * Validates one evaluator result.
		 *
		 * @param verdict the pass or fail verdict
		 * @param evidenceFingerprint the evaluator evidence fingerprint
		 */
		public Evaluation
		{
			Objects.requireNonNull(verdict, "verdict must not be null");
			if (verdict == FormalTrialLedger.Verdict.INFRASTRUCTURE_FAILURE)
				throw new IllegalArgumentException("Evaluator must throw an I/O failure for infrastructure failure");
			if (evidenceFingerprint == null || !evidenceFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("evidenceFingerprint must be a lowercase SHA-256 value");
		}
	}

	/**
	 * Holds one retained outcome and its proof-covered next action.
	 *
	 * @param retainedOutcome the terminal formal outcome
	 * @param decision the next statistical action
	 * @param infrastructureFailure the optional infrastructure diagnostic
	 */
	public record Result(FormalTrialLedger.RetainedOutcome retainedOutcome,
		FamilySequentialDecision.Result decision, Optional<String> infrastructureFailure)
	{
		/**
		 * Validates one operation result.
		 *
		 * @param retainedOutcome the terminal formal outcome
		 * @param decision the next statistical action
		 * @param infrastructureFailure the optional infrastructure diagnostic
		 */
		public Result
		{
			Objects.requireNonNull(retainedOutcome, "retainedOutcome must not be null");
			Objects.requireNonNull(decision, "decision must not be null");
			Objects.requireNonNull(infrastructureFailure, "infrastructureFailure must not be null");
			boolean failed = retainedOutcome.outcome().verdict() == FormalTrialLedger.Verdict.INFRASTRUCTURE_FAILURE;
			if (failed != infrastructureFailure.isPresent())
				throw new IllegalArgumentException("infrastructureFailure presence does not match retained verdict");
		}
	}

	/**
	 * Validates required open text.
	 *
	 * @param value the proposed text
	 * @param name the parameter name
	 * @return the unchanged text
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Prevents construction of this static operation owner.
	 */
	private FormalTrialOperation()
	{
	}
}
