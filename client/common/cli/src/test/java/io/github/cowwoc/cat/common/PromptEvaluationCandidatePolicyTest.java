package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.CandidateState;
import io.github.cowwoc.cat.common.evaluation.RegressionCheck;
import io.github.cowwoc.cat.common.evaluation.RevisionReview;

import static io.github.cowwoc.cat.common.PromptEvaluationCandidateFixtures.staticBoundaryFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationCandidateFixtures.staticFormalFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationCandidateFixtures.staticStressFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.attemptLedger;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.beginScreen;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeActiveFailureState;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFailingFormalBuildPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFailingFormalRunner;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFormalRunner;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeLifecycleSoakPrecondition;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests candidate-promotion policy in {@link PromptEvaluation}.
 */
public final class PromptEvaluationCandidatePolicyTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Closes the test-owned tool scope after candidate-policy tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationCandidatePolicyTest()
	{
	}

	/**
	 * Verifies the controller records an incomplete first regression-check transition.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsIncompleteInitialTransition() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "passed", true);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");

			ObjectNode result = PromptEvaluation.advance(temporary, candidateState, suite, prompt, "fixture", context,
				runner, buildPlan, temporary.resolve("check"), 1,
				PromptEvaluationCandidateFixtures::staticUnavailableEvaluator);

			requireThat(result.path("transition").asString(), "transition").isEqualTo("regression-check");
			requireThat(result.path("result").path("regression_check").path("status").asString(), "checkStatus").
				isEqualTo("incomplete");
			ArrayNode transitions = (ArrayNode) scope.json().readTree(Files.readString(candidateState)).
				path("controller_transitions");
			requireThat(transitions.size(), "controllerTransitionCount").isEqualTo(1);
			requireThat(transitions.get(0).path("transition").asString(), "controllerTransition").
				isEqualTo("regression-check");
		}
	}

	/**
	 * Verifies a changed candidate cannot start another check until an independent review resolves its retained
	 * obligations.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void requiresReviewBeforeChangedCheck() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Original prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "failed");
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path state = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			PromptEvaluation.regressionCheck(temporary, state, suite, prompt, "fixture", context, runner, buildPlan,
				temporary.resolve("initial-check"), 1, evaluationRunner);

			Files.writeString(prompt, "Changed prompt artifact\n");
			Path candidateDirectory = temporary.resolve("changed-check");
			boolean rejected = false;
			try
			{
				PromptEvaluation.regressionCheck(temporary, state, suite, prompt, "fixture", context, runner, buildPlan,
					candidateDirectory, 1, evaluationRunner);
			}
			catch (IOException e)
			{
				rejected = true;
				requireThat(e.getMessage(), "message").contains("revision review");
			}
			requireThat(rejected, "rejected").isTrue();

			Path proposedRun = candidateDirectory.resolve("run.json");
			Path bundle = temporary.resolve("revision-review");
			List<HarnessEvaluationRequest> reviewRequests = new CopyOnWriteArrayList<>();
			ObjectNode completed = RevisionReview.run(scope.json(), temporary, state, proposedRun, bundle,
				request -> revisionReview(reviewRequests, request, true));

			requireThat(completed.path("reviewer_receipt").asString(), "reviewerReceipt").isEqualTo(
				bundle.resolve("reviewer.json").toAbsolutePath().normalize().toString());
			requireThat(Path.of(completed.path("reviewer_receipt").asString()), "reviewerReceipt").isRegularFile();
			requireThat(completed.path("review").path("status").asString(), "reviewStatus").isEqualTo("completed");
			requireThat(reviewRequests.size(), "reviewRequestCount").isEqualTo(1);
			requireThat(reviewRequests.getFirst().project(), "reviewProject").isEqualTo(
				bundle.toAbsolutePath().normalize());
			CandidateState.validateCandidateStateBeforeCheck(state, proposedRun);
		}
	}

	/**
	 * Verifies a runner failure leaves the changed candidate blocked until a complete revision review is recorded.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsCandidateBlockedAfterReviewFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Original prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "failed");
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path state = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			PromptEvaluation.regressionCheck(temporary, state, suite, prompt, "fixture", context, runner, buildPlan,
				temporary.resolve("initial-check"), 1, evaluationRunner);

			Files.writeString(prompt, "Changed prompt artifact\n");
			Path candidateDirectory = temporary.resolve("changed-check");
			try
			{
				PromptEvaluation.regressionCheck(temporary, state, suite, prompt, "fixture", context, runner, buildPlan,
					candidateDirectory, 1, evaluationRunner);
				throw new AssertionError("Changed candidate must require revision review");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("revision review");
			}

			Path proposedRun = candidateDirectory.resolve("run.json");
			try
			{
				RevisionReview.run(scope.json(), temporary, state, proposedRun, temporary.resolve("revision-review"),
					request ->
					{
						throw new IOException("reviewer unavailable");
					});
				throw new AssertionError("Expected revision-review runner failure");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").isEqualTo("reviewer unavailable");
			}

			try
			{
				CandidateState.validateCandidateStateBeforeCheck(state, proposedRun);
				throw new AssertionError("Unrecorded revision review must keep the candidate blocked");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("revision review");
			}
		}
	}

/**
	 * Verifies a semantic reviewer rejection cannot authorize a changed prompt candidate.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsNegativeRevisionReview() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Original prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "failed");
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path state = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			PromptEvaluation.regressionCheck(temporary, state, suite, prompt, "fixture", context, runner, buildPlan,
				temporary.resolve("initial-check"), 1, evaluationRunner);

			Files.writeString(prompt, "Changed prompt artifact\n");
			Path candidateDirectory = temporary.resolve("changed-check");
			try
			{
				PromptEvaluation.regressionCheck(temporary, state, suite, prompt, "fixture", context, runner, buildPlan,
					candidateDirectory, 1, evaluationRunner);
				throw new AssertionError("Changed candidate must require revision review");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("revision review");
			}
			Path proposedRun = candidateDirectory.resolve("run.json");
			List<HarnessEvaluationRequest> reviewRequests = new CopyOnWriteArrayList<>();

			try
			{
				RevisionReview.run(scope.json(), temporary, state, proposedRun, temporary.resolve("revision-review"),
					request -> revisionReview(reviewRequests, request, false));
				throw new AssertionError("A negative semantic review must not authorize the candidate");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("does not preserve");
			}
			try
			{
				CandidateState.validateCandidateStateBeforeCheck(state, proposedRun);
				throw new AssertionError("A rejected review must keep the candidate blocked");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("revision review");
			}
		}
	}

	/**
	 * Verifies a regressed check records the failed case and leaves the last successful promotion unchanged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void failedScreenPreservesPromotion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path promotion = root.resolve("promotion.json");
			Path successfulRun = root.resolve("successful-run.json");
			List<HarnessEvaluationRequest> successfulRequests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner successfulRunner = request -> staticEvaluationResult(successfulRequests, request,
				"passed");
			beginScreen(root, suite, prompt, context, successfulRun, writeStaticExecutionPlan(root), successfulRunner);
			output(() -> PromptEvaluation.run(new String[]{"record-regression-check-signatures", successfulRun.toString(),
				promotion.toString()}));
			PromptEvaluation.run(new String[]{"promote", successfulRun.toString(), promotion.toString()});
			String priorPromotion = Files.readString(promotion);

			Path failedRun = root.resolve("failed-run.json");
			List<HarnessEvaluationRequest> failedRequests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner failedRunner = request -> staticEvaluationResult(failedRequests, request, "failed");
			beginScreen(root, suite, prompt, context, failedRun, writeStaticExecutionPlan(root), failedRunner);
			Path failedCandidateState = root.resolve("failed-candidate-state.json");
			output(() -> PromptEvaluation.run(new String[]{"record-regression-check-signatures", failedRun.toString(),
				failedCandidateState.toString()}));
			String firstFailedState = Files.readString(failedCandidateState);
			requireThat(firstFailedState, "firstFailedState").contains("\"consecutive_observations\" : 1");
			output(() -> PromptEvaluation.run(new String[]{"record-regression-check-signatures", failedRun.toString(),
				failedCandidateState.toString()}));
			requireThat(Files.readString(failedCandidateState), "repeatedFailedState").isEqualTo(firstFailedState);
			String report = output(() -> PromptEvaluation.run(new String[]{"report", failedRun.toString()}));
			requireThat(report, "report").contains("\"status\":\"failed\"").
				contains("\"failure_signatures\"").contains("\"case_id\":\"requested-change\"").
				contains("\"assertion\":\"result\"").
				contains("\"boundary\":\"acceptance assertion result\"");
			try
			{
				PromptEvaluation.run(new String[]{"promote", failedRun.toString(), failedCandidateState.toString()});
				throw new AssertionError("Expected failed check to reject promotion");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("regression check has failed");
			}
			requireThat(Files.readString(promotion), "promotion").isEqualTo(priorPromotion);
		}
	}

	/**
	 * Verifies a candidate check executes every durable testcase once before it can create promotion evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void checksAllCasesBeforePromotion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode result = PromptEvaluation.runRegressionCheck(run, workspace, plan, 3, runner);

			requireThat(result.path("classification").asString(), "result").isEqualTo("non_certifying_regression_check");
			requireThat(result.path("status").asString(), "result").isEqualTo("passed");
			List<String> attempts = Files.readAllLines(attemptLedger(run), StandardCharsets.UTF_8);
			String ledger = String.join(System.lineSeparator(), attempts);
			requireThat(ledger, "ledger").contains("\"case_id\":\"requested-change\"").
				contains("\"case_id\":\"unrelated-request\"").contains("\"case_id\":\"edge-condition\"");
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"requested-change\"")).count(),
				"positiveSweepTrials").isEqualTo(1L);
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"unrelated-request\"")).count(),
				"negativeSweepTrials").isEqualTo(1L);
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"edge-condition\"")).count(),
				"boundarySweepTrials").isEqualTo(1L);
			String report = output(() -> PromptEvaluation.run(new String[]{"report", run.toString()}));
			requireThat(report, "report").contains("\"classification\":\"non_certifying_regression_check\"");
			Path promotion = root.resolve("promotion.json");
			output(() -> PromptEvaluation.run(new String[]{"record-regression-check-signatures", run.toString(),
				promotion.toString()}));
			String promoted = output(() -> PromptEvaluation.run(new String[]{"promote", run.toString(),
				promotion.toString()}));
			requireThat(promoted, "promoted").contains("\"check_run\":\"" + run + "\"");
			requireThat(Files.readString(promotion), "promotion").contains("\"evaluation_epoch_sha256\"");
		}
	}

	/**
	 * Verifies a candidate lineage preserves a failed obligation when a changed execution policy begins a new epoch.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preservesChangedCandidateEpochInLineage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path baseline = root.resolve("baseline.json");
			List<HarnessEvaluationRequest> baselineRequests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner baselineRunner = request -> staticEvaluationResult(baselineRequests, request, "failed");
			beginScreen(root, suite, prompt, context, baseline, writeStaticExecutionPlan(root.resolve("baseline-plan")),
				baselineRunner);
			Path state = root.resolve("candidate-state.json");
			PromptEvaluation.run(new String[]{"record-regression-check-signatures", baseline.toString(), state.toString()});

			Path candidatePlan = writeStaticExecutionPlan(root.resolve("candidate-plan"));
			Files.writeString(candidatePlan.getParent().resolve("sandbox-policy.json"), "changed sandbox policy\n");
			Path candidate = root.resolve("candidate.json");
			List<HarnessEvaluationRequest> candidateRequests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner candidateRunner = request -> staticEvaluationResult(candidateRequests, request, "passed");
			beginScreen(root, suite, prompt, context, candidate, candidatePlan, candidateRunner);

			PromptEvaluation.run(new String[]{"record-regression-check-signatures", candidate.toString(), state.toString()});
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode lineage = (ObjectNode) scope.json().readTree(state.toFile()).path("lineage");
				requireThat(lineage.withArray("epochs").size(), "lineageEpochs").isEqualTo(2);
				requireThat(lineage.withArray("obligations").get(0).path("status").asString(), "obligationStatus").
					isEqualTo("covered");
			}
		}
	}

	/**
	 * Verifies a passed testcase covers a retained terminal-outcome obligation even though that synthetic assertion is
	 * not declared among the testcase's semantic assertions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void coversTerminalOutcome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path check = root.resolve("check.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");
			beginScreen(root, suite, prompt, context, check, writeStaticExecutionPlan(root), runner);

			ObjectNode checkRecord = (ObjectNode) scope.json().readTree(check.toFile());
			Path state = root.resolve("candidate-state.json");
			ObjectNode candidate = scope.json().createObjectNode();
			candidate.put("schema_version", 2);
			candidate.put("suite_sha256", checkRecord.path("suite_sha256").asString());
			candidate.put("evaluation_epoch_sha256", checkRecord.path("evaluation_epoch_sha256").asString());
			candidate.putArray("failure_signatures");
			candidate.putArray("promotions");
			ObjectNode obligation = candidate.putObject("lineage").put("schema_version", 1).
				putArray("obligations").addObject();
			obligation.put("key", "requested-change/terminal-outcome/retained testcase outcome");
			obligation.put("status", "open");
			obligation.putObject("case").put("case_id", "requested-change").put("assertion", "terminal-outcome").
				put("boundary", "retained testcase outcome");
			candidate.path("lineage").withArray("epochs");
			Files.writeString(state, scope.json().writeValueAsString(candidate));

			PromptEvaluation.run(new String[]{"record-regression-check-signatures", check.toString(), state.toString()});
			ObjectNode result = (ObjectNode) scope.json().readTree(state.toFile());
			requireThat(result.path("lineage").path("obligations").get(0).path("status").asString(),
				"terminalOutcomeCoverage").isEqualTo("covered");
			PromptEvaluation.run(new String[]{"promote", check.toString(), state.toString()});
		}
	}

	/**
	 * Verifies a changed routed rule creates a distinct candidate revision even when the base prompt file is unchanged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsRoutedRevision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path baseline = root.resolve("baseline.json");
			Path candidate = root.resolve("candidate.json");
			Path baselinePlan = writeStaticExecutionPlan(root.resolve("baseline-plan"));
			Path candidatePlan = writeStaticExecutionPlan(root.resolve("candidate-plan"));
			Files.writeString(candidatePlan.getParent().resolve("runner-artifact/rules/common/rule.md"),
				"revised target rule guidance\n");
			List<HarnessEvaluationRequest> baselineRequests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner baselineRunner = request -> staticEvaluationResult(baselineRequests, request, "failed");
			beginScreen(root, suite, prompt, context, baseline, baselinePlan, baselineRunner);
			Path state = root.resolve("candidate-state.json");
			PromptEvaluation.run(new String[]{"record-regression-check-signatures", baseline.toString(), state.toString()});
			List<HarnessEvaluationRequest> candidateRequests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner candidateRunner = request -> staticEvaluationResult(candidateRequests, request, "failed");
			beginScreen(root, suite, prompt, context, candidate, candidatePlan, candidateRunner);
			PromptEvaluation.run(new String[]{"record-regression-check-signatures", candidate.toString(), state.toString()});

			try (ToolScope scope = new ToolScope())
			{
				ObjectNode record = (ObjectNode) scope.json().readTree(state.toFile());
				ObjectNode signature = (ObjectNode) record.path("failure_signatures").get(0);
				ArrayNode revisions = (ArrayNode) signature.path("observed_candidate_revisions");
				requireThat(revisions.size(), "observedCandidateRevisions").isEqualTo(2);
				requireThat(signature.path("last_observed_candidate_sha256").asString(), "lastCandidateRevision").
					isEqualTo(scope.json().readTree(candidate.toFile()).path("assembled_prompt_route_sha256").asString());
				ObjectNode latestCheck = (ObjectNode) record.path("checks").get(record.path("checks").size() - 1);
				requireThat(latestCheck.path("assembled_prompt_route_sha256").asString(), "latestCheckRoute").isEqualTo(
					scope.json().readTree(candidate.toFile()).path("assembled_prompt_route_sha256").asString());
			}
		}
	}

	/**
	 * Verifies a retry removes state derived from an incomplete check while retaining a completed check's obligation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void discardsIncompleteCandidateEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path statePath = root.resolve("candidate-state.json");
			Path completed = root.resolve("completed.json");
			Path incomplete = root.resolve("incomplete.json");
			ObjectNode state = scope.json().createObjectNode();
			ArrayNode signatures = state.putArray("failure_signatures");
			ObjectNode completedSignature = signatures.addObject();
			completedSignature.put("key", "completed-case/result/fixture");
			completedSignature.putObject("latest_case").put("case_id", "completed-case");
			completedSignature.put("last_observed_check", completed.toString());
			ObjectNode incompleteSignature = signatures.addObject();
			incompleteSignature.put("key", "blocked-case/terminal-outcome/fixture");
			incompleteSignature.putObject("latest_case").put("case_id", "blocked-case");
			incompleteSignature.put("first_observed_check", incomplete.toString());
			incompleteSignature.put("last_observed_check", incomplete.toString());
			incompleteSignature.put("observations", 1);
			ObjectNode lineage = state.putObject("lineage");
			ArrayNode obligations = lineage.putArray("obligations");
			ObjectNode completedObligation = obligations.addObject();
			completedObligation.put("key", "completed-case/result/fixture");
			completedObligation.put("last_observed_check", completed.toString());
			ObjectNode incompleteObligation = obligations.addObject();
			incompleteObligation.put("key", "blocked-case/terminal-outcome/fixture");
			incompleteObligation.put("last_observed_check", incomplete.toString());
			ArrayNode checks = state.putArray("checks");
			checks.addObject().put("run", completed.toString()).put("status", "failed");
			checks.addObject().put("run", incomplete.toString()).put("status", "incomplete");
			state.put("latest_check", incomplete.toString());
			state.put("latest_check_status", "incomplete");
			Files.writeString(statePath, scope.json().writeValueAsString(state));

			CandidateState.discardIncompleteCandidateCheckEvidence(scope.json(), statePath);

			ObjectNode repaired = (ObjectNode) scope.json().readTree(statePath.toFile());
			requireThat(repaired.path("failure_signatures").size(), "failureSignatures").isEqualTo(1);
			requireThat(repaired.path("failure_signatures").get(0).path("key").asString(), "retainedSignature").
				isEqualTo("completed-case/result/fixture");
			requireThat(repaired.path("lineage").path("obligations").size(), "lineageObligations").isEqualTo(1);
			requireThat(repaired.path("checks").size(), "checks").isEqualTo(1);
			requireThat(repaired.path("latest_check").asString(), "latestCheck").isEqualTo(completed.toString());
		}
	}

	/**
	 * Verifies repair refuses to erase a completed check's signature when an incomplete check later reused it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*cannot discard it safely.*")
	public void rejectsAmbiguousIncompleteEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path statePath = root.resolve("candidate-state.json");
			Path completed = root.resolve("completed.json");
			Path incomplete = root.resolve("incomplete.json");
			ObjectNode state = scope.json().createObjectNode();
			ObjectNode signature = state.putArray("failure_signatures").addObject();
			signature.put("key", "shared-case/result/fixture");
			signature.put("first_observed_check", completed.toString());
			signature.put("last_observed_check", incomplete.toString());
			signature.put("observations", 2);
			state.putArray("checks").addObject().put("run", incomplete.toString()).put("status", "incomplete");
			Files.writeString(statePath, scope.json().writeValueAsString(state));

			CandidateState.discardIncompleteCandidateCheckEvidence(scope.json(), statePath);
		}
	}

	/**
	 * Verifies an unavailable executor ends a regression check without creating a prompt-failure obligation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsInfrastructureAsPromptFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "passed", true);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");

			ObjectNode result = PromptEvaluation.regressionCheck(temporary, candidateState, suite, prompt, "fixture",
				context, runner, buildPlan, temporary.resolve("check"), 1,
				PromptEvaluationCandidateFixtures::staticUnavailableEvaluator);

			ObjectNode check = (ObjectNode) result.path("regression_check");
			requireThat(check.path("status").asString(), "regressionCheckStatus").isEqualTo("incomplete");
			requireThat(check.path("infrastructure_failure").asBoolean(), "infrastructureFailure").isTrue();
			requireThat(check.path("failure_signatures").size(), "failureSignatures").isEqualTo(0);
			requireThat(check.path("infrastructure_failures").get(0).path("result").asString(), "failureResult").
				isEqualTo("blocked");
			requireThat(result.has("candidate_state"), "candidateStateResult").isFalse();
			requireThat(Files.exists(candidateState), "candidateState").isFalse();
		}
	}

	/**
	 * Verifies a balanced candidate check rejects a failure confined to a later testcase before formal certification.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Candidate cannot be promoted because its regression check has failed: .+")
	public void rejectsLateCaseBeforeCertification() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticBoundaryFailure(requests, request);

			ObjectNode result = PromptEvaluation.runRegressionCheck(run, workspace, plan, 3, runner);

			requireThat(result.path("status").asString(), "result").isEqualTo("failed");
			requireThat(result.path("failure_signatures").toString(), "result").contains("edge-condition");
			List<String> attempts = Files.readAllLines(attemptLedger(run), StandardCharsets.UTF_8);
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"requested-change\"")).count(),
				"positiveSweepTrials").isEqualTo(1L);
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"unrelated-request\"")).count(),
				"negativeSweepTrials").isEqualTo(1L);
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"edge-condition\"")).count(),
				"boundarySweepTrials").isEqualTo(1L);
			PromptEvaluation.run(new String[]{"promote", run.toString(), root.resolve("promotion.json").toString()});
		}
	}

	/**
	 * Verifies a candidate check scales repeated coverage after the same signature recurs under one prompt route.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void scalesStressCoverageAfterRecurrence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			PromptEvaluation.run(new String[]{"bind-plan", run.toString(), plan.toString()});
			Path candidateState = writeActiveFailureState(run, "requested-change");
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode record = (ObjectNode) scope.json().readTree(run.toFile());
				ObjectNode state = (ObjectNode) scope.json().readTree(candidateState.toFile());
				ObjectNode signature = (ObjectNode) state.path("failure_signatures").get(0);
				signature.put("coverage_route_sha256", record.path("assembled_prompt_route_sha256").asString());
				signature.put("coverage_observations", 2);
				Files.writeString(candidateState, scope.json().writeValueAsString(state));
			}
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			PromptEvaluation.runRegressionCheck(run, workspace, plan, 16, runner);
			output(() -> PromptEvaluation.run(new String[]{"record-regression-check-signatures", run.toString(),
				candidateState.toString()}));

			List<String> attempts = Files.readAllLines(attemptLedger(run), StandardCharsets.UTF_8);
			long positiveTrials = attempts.stream().filter(line -> line.contains("\"case_id\":\"requested-change\"")).count();
			requireThat(positiveTrials, "positiveTrials").isEqualTo(17L);
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"unrelated-request\"")).count(),
				"negativeTrials").isEqualTo(1L);
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"edge-condition\"")).count(),
				"boundaryTrials").isEqualTo(1L);
			requireThat(Files.readString(candidateState), "candidateState").
				contains("\"not_observed_comparable_exposures\" : 17").doesNotContain("\"removed_by_check\"");
		}
	}

	/**
	 * Verifies a failed active-obligation stress wave prevents later stress waves from starting.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void stopsStressWavesAfterFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			PromptEvaluation.run(new String[]{"bind-plan", run.toString(), plan.toString()});
			Path candidateState = writeActiveFailureState(run, "requested-change");
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode record = (ObjectNode) scope.json().readTree(run.toFile());
				ObjectNode state = (ObjectNode) scope.json().readTree(candidateState.toFile());
				ObjectNode signature = (ObjectNode) state.path("failure_signatures").get(0);
				signature.put("coverage_route_sha256", record.path("assembled_prompt_route_sha256").asString());
				signature.put("coverage_observations", 2);
				Files.writeString(candidateState, scope.json().writeValueAsString(state));
			}
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticStressFailure(requests, request);

			PromptEvaluation.runRegressionCheck(run, workspace, plan, 16, runner);

			List<String> attempts = Files.readAllLines(attemptLedger(run), StandardCharsets.UTF_8);
			requireThat(attempts.stream().filter(line -> line.contains("\"case_id\":\"requested-change\"")).count(),
				"positiveTrials").isEqualTo(2L);
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode record = (ObjectNode) scope.json().readTree(run.toFile());
				requireThat(record.path("regression_check").path("active_failure_stress").path("status").asString(),
					"stressStatus").isEqualTo("failed");
			}
		}
	}

	/**
	 * Verifies recurrence coverage starts at eight trials and caps at thirty-two trials.
	 */
	@Test
	public void scalesStressCoverageByObservations()
	{
		requireThat(RegressionCheck.stressTrialCount(0), "initialCoverage").isEqualTo(8);
		requireThat(RegressionCheck.stressTrialCount(1), "firstObservationCoverage").isEqualTo(8);
		requireThat(RegressionCheck.stressTrialCount(2), "firstRecurrenceCoverage").isEqualTo(16);
		requireThat(RegressionCheck.stressTrialCount(3), "secondRecurrenceCoverage").isEqualTo(32);
		requireThat(RegressionCheck.stressTrialCount(10), "cappedCoverage").isEqualTo(32);
	}

	/**
	 * Verifies a changed assembled route starts an active signature's coverage again at its initial tier.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void resetsStressCoverageForChangedRoute() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path state = write(root, "candidate-state.json", """
				{"failure_signatures":[{
				  "latest_case":{"case_id":"requested-change"},
				  "coverage_route_sha256":"earlier-route",
				  "coverage_observations":10
				}]}
				""");
			Path run = write(root, "run.json", """
				{
				  "failure_signature_state":"%s",
				  "assembled_prompt_route_sha256":"new-route"
				}
				""".formatted(state));

			requireThat(RegressionCheck.activeFailureStressTrials(run), "coverage").isEqualTo(8);
		}
	}

	/**
	 * Verifies the public formal command rejects an unpromoted candidate before it can start an evaluation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsUnpromotedFormalCommand() throws IOException
	{
		try
		{
			PromptEvaluation.run(new String[]{"evaluate"});
			throw new AssertionError("Expected unpromoted formal command to be rejected");
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").contains("requires a successful regression check and promotion");
		}
	}

	/**
	 * Verifies formal certification rejects a promotion whose required regression-check receipt is unavailable.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*promotion's regression-check receipt.*")
	public void rejectsPromotionWithoutCheckReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = write(root, "unused-runner", "#!/bin/sh\n");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			Path buildPlan = root.resolve("unused-build-plan.json");
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			Files.createDirectories(temporary);
			ObjectNode state = scope.json().createObjectNode();
			state.putObject("current_promotion").put("check_run", temporary.resolve("missing-check.json").toString());
			Files.writeString(candidateState, scope.json().writeValueAsString(state));

			PromptEvaluation.certifyPromoted(temporary, candidateState, suite, prompt, "fixture", context, runner,
				buildPlan, temporary.resolve("formal"), 1);
		}
	}

	/**
	 * Verifies formal certification stops before model work when the exact packaged runner has not passed its lifecycle
	 * check.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void requiresLifecycleCheck() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "passed");
			PromptEvaluation.regressionCheck(temporary, candidateState, suite, prompt, "fixture", context, runner,
				buildPlan, temporary.resolve("check"), 1, evaluationRunner);

			try
			{
				PromptEvaluation.certifyPromoted(temporary, candidateState, suite, prompt, "fixture", context, runner,
					buildPlan, temporary.resolve("formal"), 1);
				throw new AssertionError("Expected certification to require its lifecycle check");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("requires a passing lifecycle check");
			}
		}
	}

	/**
	 * Verifies the controller owns the routine regression-check, promotion, and formal-certification sequence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void advancesCandidateTransitions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticFormalFailure(requests, request);

			ObjectNode check = PromptEvaluation.advance(temporary, candidateState, suite, prompt, "fixture", context,
				runner, buildPlan, temporary.resolve("check"), 1, evaluationRunner);
			requireThat(check.path("transition").asString(), "checkTransition").isEqualTo("regression-check");
			requireThat(check.path("result").path("promotion").path("check_run").asString(), "promotionCheckRun").
				isNotBlank();
			writeLifecycleSoakPrecondition(temporary, buildPlan, runner);

			ObjectNode formal = PromptEvaluation.advance(temporary, candidateState, suite, prompt, "fixture", context,
				runner, buildPlan, temporary.resolve("formal"), 1, evaluationRunner);
			requireThat(formal.path("transition").asString(), "formalTransition").isEqualTo("certify-promoted");
			requireThat(formal.path("result").path("decision").path("overall_decision").asString(), "formalDecision").
				isEqualTo("FAIL");
			try (ToolScope scope = new ToolScope())
			{
				ArrayNode transitions = (ArrayNode) scope.json().readTree(Files.readString(candidateState)).
					path("controller_transitions");
				requireThat(transitions.size(), "controllerTransitionCount").isEqualTo(2);
				requireThat(transitions.get(0).path("transition").asString(), "firstControllerTransition").
					isEqualTo("regression-check");
				requireThat(transitions.get(0).path("record").asString(), "firstControllerRecord").isNotBlank();
				requireThat(transitions.get(1).path("transition").asString(), "secondControllerTransition").
					isEqualTo("certify-promoted");
				requireThat(transitions.get(1).path("record").asString(), "secondControllerRecord").isNotBlank();
			}
		}
	}

	/**
	 * Retains a test-supplied independent-review result for one proposed candidate.
	 *
	 * @param requests the typed runner requests observed by the test
	 * @param request the revision-review request to complete
	 * @param preserved whether the reviewer concludes the revision preserves the obligation
	 * @return the retained result named by the runner receipt
	 * @throws IOException if the test-owned review evidence cannot be written
	 */
	private static HarnessEvaluationExecution revisionReview(List<HarnessEvaluationRequest> requests,
		HarnessEvaluationRequest request, boolean preserved) throws IOException
	{
		requests.add(request);
		Path reviewerProject = request.project().resolve("reviewer-project");
		Files.createDirectories(reviewerProject);
		Files.copy(request.project().resolve(".cat-prompt-review-bundle"),
			reviewerProject.resolve(".cat-prompt-review-bundle"));

		ObjectNode conclusion = PromptEvaluation.newObject();
		ArrayNode findings = conclusion.putArray("findings");
		String question = Files.readString(request.project().resolve("question.md"));
		String obligations = question.substring(question.indexOf(": ") + 2,
			question.indexOf(". For each obligation"));
		for (String obligation : obligations.split(", "))
		{
			ObjectNode finding = findings.addObject();
			finding.put("obligation_key", obligation);
			finding.put("actor", "the update author");
			finding.put("direction", "author to reader");
			finding.put("affected_scope", "the retained update");
			finding.put("correction_target", "the reader-facing explanation");
			finding.put("verification_outcome", "the retained check passes");
			finding.put("preserved", preserved);
		}
		Path reviewResult = reviewerProject.resolve("review-result.json");
		Files.writeString(reviewResult, PromptEvaluation.jsonText(conclusion));
		Files.writeString(request.transcript(), "static revision review\n");
		Files.writeString(request.receipt(), "{\"retained_project\":\"%s\",\"raw_result\":\"%s\"}".formatted(
			reviewerProject, request.transcript()));
		HarnessEvaluationResult result = HarnessEvaluationResult.retained(reviewerProject, request.transcript());
		return new HarnessEvaluationExecution(result, 1, 2);
	}
}
