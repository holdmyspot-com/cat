package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.CandidateState;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.beginScreen;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeActiveFailureState;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests retained independent-review evidence for recurring candidate failures.
 */
public final class PromptEvaluationRecurrenceReviewTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationRecurrenceReviewTest()
	{
	}

	/**
	 * Verifies a repeated failure receives a sealed review bundle without changing its ledger entry.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preparesAndRecordsRepeatedFailureReview() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");
			beginScreen(root, suite, prompt, context, run, writeStaticExecutionPlan(root), runner);
			Path state = writeActiveFailureState(run, "requested-change");
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode candidateState = (ObjectNode) scope.json().readTree(state.toFile());
				ObjectNode signature = (ObjectNode) candidateState.path("failure_signatures").get(0);
				signature.put("consecutive_observations", 2);
				signature.put("observations", 2);
				Files.writeString(state, scope.json().writeValueAsString(candidateState));
			}
			Path bundle = root.resolve("recurrence-review");
			ObjectNode receipt = PromptEvaluation.prepareRecurrenceReview(root, state, run, bundle);
			requireThat(Path.of(receipt.path("result").asString()), "reviewResult").isEqualTo(
				bundle.resolve("review-result.json"));
			try
			{
				CandidateState.validateCandidateStateBeforeCheck(state, run);
				throw new AssertionError("Expected pending recurrence review to block a candidate check");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("requires a retained independent workflow-owned review");
			}
			String signatureBeforeRecording = Files.readString(state);
			write(bundle, "review-result.json", """
				{"findings":[{
				  "signature_key":"requested-change/result/fixture",
				  "decision":"restructure",
				  "preserved_obligations":["Explain the observed difference."]
				}]}
				""");
			ObjectNode completed = PromptEvaluation.recordRecurrenceReview(state, bundle,
				bundle.resolve("review-result.json"));
			requireThat(completed.path("status").asString(), "reviewStatus").isEqualTo("completed");
			CandidateState.validateCandidateStateBeforeCheck(state, run);
			Files.delete(bundle.resolve("review-result.json"));
			try
			{
				CandidateState.validateCandidateStateBeforeCheck(state, run);
				throw new AssertionError("Expected unavailable recurrence evidence to block a candidate check");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("requires a retained independent workflow-owned review");
			}
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode before = (ObjectNode) scope.json().readTree(signatureBeforeRecording);
				ObjectNode after = (ObjectNode) scope.json().readTree(state.toFile());
				requireThat(after.path("failure_signatures").toString(), "failureSignatures").isEqualTo(
					before.path("failure_signatures").toString());
			}
		}
	}

/**
	 * Verifies an unrelated older failure signature cannot complete a review for the currently repeated failure.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsUnrelatedReviewSignature() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");
			beginScreen(root, suite, prompt, context, run, writeStaticExecutionPlan(root), runner);
			Path state = writeActiveFailureState(run, "requested-change");
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode candidateState = (ObjectNode) scope.json().readTree(state.toFile());
				ObjectNode signature = (ObjectNode) candidateState.path("failure_signatures").get(0);
				signature.put("consecutive_observations", 2);
				signature.put("observations", 2);
				Files.writeString(state, scope.json().writeValueAsString(candidateState));
			}
			Path bundle = root.resolve("recurrence-review");
			PromptEvaluation.prepareRecurrenceReview(root, state, run, bundle);
			write(bundle, "review-result.json", """
				{"findings":[{
				  "signature_key":"older-unrelated-signature",
				  "decision":"restructure",
				  "preserved_obligations":["Explain the observed difference."]
				}]}
				""");

			try
			{
				PromptEvaluation.recordRecurrenceReview(state, bundle, bundle.resolve("review-result.json"));
				throw new AssertionError("An unrelated older signature must not complete the recurrence review");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("must address exactly the repeated failure signatures");
			}
			try
			{
				CandidateState.validateCandidateStateBeforeCheck(state, run);
				throw new AssertionError("The incomplete recurrence review must keep the candidate blocked");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("requires a retained independent workflow-owned review");
			}
		}
	}

	/**
	 * Verifies one recurrence review includes every active testcase failure that declares the same obligation family.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void groupsRelatedCasesInOneReview() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path manifest = suite.resolve("suite.json");
			ObjectNode suiteMetadata = PromptEvaluation.object(PromptEvaluation.read(manifest), "suite metadata");
			ObjectNode scenarios = PromptEvaluation.object(suiteMetadata.path("scenarios"), "scenario metadata");
			scenarios.withObject("requested-change").putObject("assertion_obligation_families").put("reader-explanation",
				"required-reader-explanation");
			scenarios.withObject("edge-condition").putObject("assertion_obligation_families").put("reader-explanation",
				"required-reader-explanation");
			PromptEvaluation.write(manifest, suiteMetadata);
			Path requestedScenario = suite.resolve("requested-change/scenario.md");
			Path edgeScenario = suite.resolve("edge-condition/scenario.md");
			Files.writeString(requestedScenario, Files.readString(requestedScenario).replace("### result",
				"### reader-explanation"));
			Files.writeString(edgeScenario, Files.readString(edgeScenario).replace("### edge-result",
				"### reader-explanation"));
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "failed");
			beginScreen(root, suite, prompt, context, run, writeStaticExecutionPlan(root), runner);
			Path state = root.resolve("candidate-state.json");
			PromptEvaluation.run(new String[]{"record-regression-check-signatures", run.toString(), state.toString()});

			ObjectNode receipt = PromptEvaluation.prepareRecurrenceReview(root, state, run,
				root.resolve("recurrence-review"));
			requireThat(receipt.path("signature_keys").toString(), "reviewedSignatureKeys").
				contains("requested-change/reader-explanation/acceptance assertion reader-explanation").
				contains("edge-condition/reader-explanation/acceptance assertion reader-explanation");
			requireThat(receipt.path("evidence").size(), "reviewEvidenceCount").isGreaterThan(5);
		}
	}
}
