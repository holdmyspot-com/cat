package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.EvaluationWorkspaceLifecycle;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.configureRequestedScenario;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.attemptLedger;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.ensureRunnerRuntimeManifest;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.validatorGroupWorkspace;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests complete-suite execution and its shared batch-validator evidence.
 */
public final class PromptEvaluationBatchExecutionTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationBatchExecutionTest()
	{
	}

	/**
	 * Verifies a batch claims complete trials, runs every testcase, and records the resulting whole-prompt outcome.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void executesCompleteBatch() throws IOException
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
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode result = PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 2, runner);

			String record = Files.readString(attemptLedger(run));
			requireThat(result.path("complete_suite_runs").asInt(), "result").isEqualTo(1);
			requireThat(record, "record").contains("\"case_id\":\"requested-change\"").
				contains("\"case_id\":\"unrelated-request\"").contains("\"case_id\":\"edge-condition\"");
		}
	}

	/**
	 * Verifies one independent validator receives every executor result in a trial and returns a complete verdict map.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void batchValidatorReviewsWholeTrial() throws IOException
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
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode result = PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 1, runner);

			String ledger = Files.readString(attemptLedger(run));
			requireThat(result.path("complete_suite_runs").asInt(), "result").isEqualTo(1);
			requireThat(ledger, "ledger").contains("\"case_id\":\"requested-change\"").
				contains("\"case_id\":\"unrelated-request\"").contains("\"case_id\":\"edge-condition\"");
			Path validatorWorkspace = validatorGroupWorkspace(root.resolve("batch"));
			requireThat(validatorWorkspace.resolve("retained/verdicts.json"), "retainedVerdicts").isRegularFile();
			String verdictSkeleton = Files.readString(validatorWorkspace.resolve("verdicts.json"));
			requireThat(verdictSkeleton, "verdictSkeleton").contains("\"trial-1/requested-change\"").
				contains("\"trial-1/unrelated-request\"").contains("\"trial-1/edge-condition\"").
				contains("\"result\"").contains("\"scope\"").contains("\"edge-result\"").
				contains("\"status\" : \"\"").contains("\"evidence\" : \"\"");
			requireThat(Files.readString(validatorWorkspace.resolve("validator-prompt.md")), "validatorPrompt").
				contains("\"id\" : \"result\"").contains("complete JSON shape").
				contains("\"source_evidence\" : \"trial-1/requested-change\"").
				contains("\"executor_output\" : \"trial-1/requested-change/executor-output.jsonl\"").
				contains("\"executor_response\" : \"trial-1/requested-change/executor-response.md\"").
				contains("existing `verdicts.json` in the current workspace").contains("Do not search for prior verdicts").
				contains("caches, or work directories");
		}
	}

	/**
	 * Verifies a workflow-owned empty directory can host a complete batch without being mistaken for stale evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void executesBatchInExistingEmptyWorkspace() throws IOException
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
			Path workspace = root.resolve("workflow-temp");
			Files.createDirectories(workspace);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode result = PromptEvaluation.runBatch(run, 1, workspace, plan, 2, runner);

			requireThat(result.path("complete_suite_runs").asInt(), "result").isEqualTo(1);
			requireThat(workspace.resolve("trial-1"), "trialWorkspace").isDirectory();
		}
	}

	/**
	 * Preserves a workflow-temp ownership marker while preparing an evaluation workspace.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void preparesWorkflowOwnedEvaluationWorkspace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path workspace = InvocationDirectory.create(root, "prompt-evaluation");

			EvaluationWorkspaceLifecycle.prepareTerminalWorkspace(workspace);

			requireThat(workspace.resolve(".cat-workflow-temp"), "workflowMarker").isRegularFile();
		}
	}

	/**
	 * Verifies an existing workspace with evidence cannot be reused by a new batch.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*workspace is not empty.*")
	public void rejectsNonEmptyBatchWorkspace() throws IOException
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
			Path workspace = root.resolve("stale-workspace");
			write(workspace, "evidence.txt", "previous attempt\n");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			PromptEvaluation.runBatch(run, 1, workspace, plan, 2, runner);
		}
	}

	/**
	 * Verifies a passed deterministic check is recorded without asking a model to restate its result.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsPassedCheckWithoutModelValidation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "requested-change/scenario.md", """
				## Scenario

				Implement the requested behavior.

				## Assertions
				""");
			configureRequestedScenario(suite, """
				{"deterministic_check":{"artifact":"acceptance-check.sh","command":["sh","${check}",
				"${project}"],"assertions":["result"]}}
				""");
			Path check = write(suite, "acceptance-check.sh", """
				#!/bin/sh
				printf '%s' '{"assertions":{"result":{"status":"passed","evidence":"controlled success"}}}'
				""");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 1, runner);

			Path validatorWorkspace = root.resolve("batch/trial-1-requested-change-validator-batch");
			requireThat(Files.exists(validatorWorkspace), "requestedChangeValidator").isFalse();
			requireThat(Files.readString(attemptLedger(run)), "attemptLedger").contains(
				"\"case_id\":\"requested-change\",\"trial\":1,\"status\":\"passed\"");
		}
	}

	/**
	 * Verifies a passed route-delivery check allows the remaining semantic assertions to reach the batch validator.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void sendsOnlySemanticAssertions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			configureRequestedScenario(suite, """
				{"deterministic_check":{"artifact":"acceptance-check.sh","command":["sh","${check}",
				"${project}"],"assertions":["route-delivery"]}}
				""");
			Path check = write(suite, "acceptance-check.sh", """
				#!/bin/sh
				printf '%s' '{"assertions":{"route-delivery":{"status":"passed","evidence":"controlled route delivery"}}}'
				""");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 1, runner);

			String validatorPrompt = Files.readString(validatorGroupWorkspace(root.resolve("batch")).resolve(
				"validator-prompt.md"));
			requireThat(validatorPrompt, "validatorPrompt").contains("\"id\" : \"result\"").
				doesNotContain("route-delivery").doesNotContain("deterministic_check").
				doesNotContain("route_expectation").contains(
					"do not require the response to repeat the assertion's terminology").contains(
						"merely because it omits the source label used by an assertion");
		}
	}

	/**
	 * Verifies a direct-evidence batch validator receives writable verdict storage and isolated read-only evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void batchValidatorSupportsDirectEvidence() throws IOException
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
			Path plan = writeStaticExecutionPlan(root, true);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode result = PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 1, runner);

			requireThat(result.path("complete_suite_runs").asInt(), "result").isEqualTo(1);
			requireThat(requests.size(), "requests").isEqualTo(4);
			Path validatorWorkspace = validatorGroupWorkspace(root.resolve("batch"));
			requireThat(validatorWorkspace.resolve("retained/verdicts.json"), "retainedVerdicts").isRegularFile();
			requireThat(validatorWorkspace.resolve("direct-evidence/trial-1/requested-change/implementation.txt"),
				"requestedChangeEvidence").isRegularFile();
			requireThat(validatorWorkspace.resolve("direct-evidence/trial-1/unrelated-request/implementation.txt"),
				"scopePreservationEvidence").isRegularFile();
			requireThat(validatorWorkspace.resolve("direct-evidence/trial-1/edge-condition/implementation.txt"),
				"edgeConditionEvidence").isRegularFile();
			requireThat(Files.notExists(root.resolve("batch/batch-1/trial-1/requested-change/retained/").
				resolve(".cat-evaluation-executor-result.txt")), "retainedOutput").isTrue();
		}
	}

	/**
	 * Verifies a batch validator without assertion evidence cannot make a trial result appear reviewed.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void blocksBatchValidatorWithoutEvidence() throws IOException
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
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticResultWithoutAssertionEvidence(requests, request);

			PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 1, runner);

			requireThat(Files.readString(attemptLedger(run)), "attemptLedger").contains("\"status\":\"blocked\"");
		}
	}

	/**
	 * Verifies a missing later batch verdict blocks only its unrecorded testcase after earlier verdicts were retained.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void blocksOnlyUnrecordedBatchVerdicts() throws IOException
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
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticResultWithoutSecondTrialVerdict(requests, request);

			PromptEvaluation.runBatch(run, 2, root.resolve("batch"), plan, 1, runner);

			String ledger = Files.readString(attemptLedger(run));
			requireThat(ledger, "attemptLedger").contains(
				"\"case_id\":\"requested-change\",\"trial\":1,\"status\":\"passed\"").contains(
				"\"case_id\":\"requested-change\",\"trial\":2,\"status\":\"blocked\"");
		}
	}

	/**
	 * Verifies malformed structured check output records an infrastructure result instead of abandoning its claimed
	 * testcase.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsMalformedCheckOutputAsBlocked() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "requested-change/scenario.md", """
				## Scenario

				Implement the requested behavior.

				## Assertions
				""");
			configureRequestedScenario(suite, """
				{"deterministic_check":{"artifact":"malformed-check.sh","command":["sh","${check}"],
				"assertions":["result"]}}
				""");
			Path check = write(suite, "malformed-check.sh", "#!/bin/sh\nprintf malformed-json\n");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});

			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 1, runner);

			String ledger = Files.readString(attemptLedger(run));
			requireThat(ledger, "attemptLedger").contains(
				"\"case_id\":\"requested-change\",\"trial\":1,\"status\":\"blocked\"");
			requireThat(Files.readString(root.resolve("batch/trial-1/requested-change/batch-validator-blocked.txt")),
				"blockedDiagnostic").contains("Invalid JSON document");
		}
	}

	/**
	 * Verifies a model-selected route that the delivered router rejects records a prompt failure rather than an
	 * infrastructure failure.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsRejectedRouteSelectionAsFailed() throws IOException
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
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticRejectedRouteResult(requests, request);

			PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 1, runner);

			requireThat(Files.readString(attemptLedger(run)), "attemptLedger").contains(
				"\"case_id\":\"edge-condition\",\"trial\":1,\"status\":\"failed\"");
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			requireThat(decision, "decision").contains("\"overall_decision\":\"FAIL\"").
				doesNotContain("\"infrastructure_failure\":true");
		}
	}

	/**
	 * Verifies a batch fills its shared capacity with the first testcase from distinct whole-prompt trials.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void fillsSharedCapacityAcrossSuiteTrials() throws IOException
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
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			List<String> starts = new CopyOnWriteArrayList<>();
			CyclicBarrier barrier = new CyclicBarrier(3);
			HarnessEvaluationRunner runner = request -> staticResultAfterThreeExecutorStarts(requests, starts, barrier,
				request);

			PromptEvaluation.runBatch(run, 3, root.resolve("batch"), plan, 3, runner);

			List<String> firstWorkers = starts.subList(0, 3);
			requireThat(firstWorkers, "firstWorkers").containsExactly(List.of("edge-condition", "edge-condition",
				"edge-condition"));
		}
	}

	/**
	 * Verifies a representative semantic-validation batch uses every available shared worker without changing its
	 * retained verdicts.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void groupsValidationCapacity() throws IOException
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
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			List<String> starts = new CopyOnWriteArrayList<>();
			CyclicBarrier barrier = new CyclicBarrier(16);
			HarnessEvaluationRunner runner = request -> staticResultAfterSixteenValidatorStarts(requests, starts, barrier,
				request);

			PromptEvaluation.runBatch(run, 16, root.resolve("batch"), plan, 16, runner);

			requireThat(starts.size(), "validatorStarts").isEqualTo(16);
			requireThat(Files.readString(attemptLedger(run)), "attemptLedger").doesNotContain("\"status\":\"blocked\"").
				doesNotContain("\"status\":\"failed\"");
		}
	}

	/**
	 * Verifies an independent validator acquires a declared dependency in its writable copy and executes the changed
	 * public boundary without changing the executor's retained project.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void validatesCopiedProductionWithDependency() throws IOException
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
			Path plan = writeExecutableEvidencePlan(root);
			Path execution = root.resolve("execution");

			String result = output(() -> PromptEvaluation.run(new String[]{"execute", run.toString(), "requested-change",
				"1", execution.toString(), plan.toString()}));

			Path retainedProject = execution.resolve("executor-project/retained");
			Path validatorProject = execution.resolve("validator-project");
			requireThat(result, "result").contains("\"status\":\"passed\"");
			requireThat(Files.readString(retainedProject.resolve("checkout.sh")), "retainedCheckout").
				contains("node_modules/declared.txt");
			requireThat(Files.notExists(retainedProject.resolve("node_modules")), "retainedDependencyInstallation").isTrue();
			requireThat(Files.readString(validatorProject.resolve("node_modules/declared.txt")), "acquiredDependency").
				isEqualTo("declared-dependency");
			requireThat(Files.readString(validatorProject.resolve("retained/checkout.sh")), "validatorResult").
				contains("node_modules/declared.txt");
		}
	}

	/**
	 * Verifies a deterministic probe can write only in its copied verifier project and retain its diagnostic separately.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsWritableCheckInCopiedProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "writable-check.mjs", """
				import { existsSync, writeFileSync } from "node:fs";
				import { join } from "node:path";

				const project = process.argv[2];
				writeFileSync(join(project, "probe-created.txt"), "created by the verifier");
				const status = existsSync(join(project, "project.txt")) ? "passed" : "failed";
				process.stderr.write("The executable probe retained this diagnostic separately.\\n");
				process.stdout.write(JSON.stringify({assertions:{result:{status,
				  evidence:"The probe wrote only in its copied project."}}}));
				""");
			write(suite, "check-good/project.txt", "known good\n");
			Files.createDirectories(suite.resolve("check-bad"));
			String completedMessage = "{\"method\":\"item/completed\",\"params\":{\"item\":{\"type\":" +
				"\"agentMessage\",\"text\":\"completed\"}}}";
			String controlEvents = "[{\"type\":\"native-event\",\"event\":" +
				PromptEvaluation.jsonText(completedMessage) + "}]\n";
			write(suite, "check-good-events.txt", controlEvents);
			write(suite, "check-bad-events.txt", controlEvents);
			write(suite, "requested-change/scenario.md", """
				## Scenario

				Implement the requested behavior.

				## Assertions
				""");
			configureRequestedScenario(suite, """
				{"deterministic_check":{"artifact":"writable-check.mjs","writable_project":true,
				"command":["node","${check}","${project}"],"assertions":["result"],"self_tests":[
				{"project":"check-good","evidence_events":"check-good-events.txt","expected_assertions":{"result":"passed"}},
				{"project":"check-bad","evidence_events":"check-bad-events.txt","expected_assertions":{"result":"failed"}}]}}
				""");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator context\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path preflightWorkspace = root.resolve("preflight");

			try (ToolScope scope = new ToolScope())
			{
				PromptEvaluation.preflight(scope.json(), suite, preflightWorkspace);
			}

			requireThat(Files.exists(suite.resolve("check-good/probe-created.txt")), "durableKnownGoodControl").isFalse();
			requireThat(Files.exists(suite.resolve("check-bad/probe-created.txt")), "durableKnownBadControl").isFalse();
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(root.resolve(".cat-prompt-evaluation-evidence"), run + System.lineSeparator());
			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");
			PromptEvaluation.smoke(run, "requested-change", workspace, plan, 1, runner);
			Path retained = workspace.resolve("trial-1/requested-change/executor-project/retained");
			Path copied = workspace.resolve("trial-1/requested-change/deterministic-check-project");
			requireThat(Files.exists(retained.resolve("probe-created.txt")), "retainedExecutorProject").isFalse();
			requireThat(copied.resolve("probe-created.txt"), "copiedVerifierProject").isRegularFile();
			requireThat(Files.readString(workspace.resolve("trial-1/requested-change/deterministic-check.stderr.txt")),
				"checkDiagnostic").contains("The executable probe retained this diagnostic separately.");
		}
	}

	/**
	 * Verifies a failed route-delivery check stops semantic review and records a failed prompt outcome.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsCheckFailureBeforeValidation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			configureRequestedScenario(suite, """
				{"deterministic_check":{"artifact":"acceptance-check.sh","command":["sh","${check}",
				"${project}"],"assertions":["route-delivery"]}}
				""");
			Path check = write(suite, "acceptance-check.sh", """
				#!/bin/sh
				printf '%s' '{"failure_class":"routing-delivery","assertions":{"route-delivery":{"status":"failed",'\
				'"evidence":"controlled missing route"}}}'
				""");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			PromptEvaluation.runBatch(run, 1, root.resolve("batch"), plan, 3, runner);

			String ledger = Files.readString(attemptLedger(run));
			requireThat(ledger, "attemptLedger").contains(
				"\"case_id\":\"requested-change\",\"trial\":1,\"status\":\"failed\"");
			requireThat(ledger, "canceledTestcases").contains(
				"\"case_id\":\"unrelated-request\",\"trial\":1,\"status\":\"canceled\"").contains(
					"\"case_id\":\"edge-condition\",\"trial\":1,\"status\":\"canceled\"");
			requireThat(Files.isRegularFile(root.resolve(
			"batch/trial-1/requested-change/deterministic-check.json")), "checkResult").isTrue();
			requireThat(Files.exists(root.resolve("batch/trial-1/unrelated-request/executor.json")),
			"canceledNegativeExecutor").isFalse();
			requireThat(Files.exists(root.resolve("batch/trial-1/edge-condition/executor.json")),
			"canceledBoundaryExecutor").isFalse();
			requireThat(Files.exists(root.resolve("batch/trial-1-requested-change-validator-batch")),
			"requestedChangeValidator").isFalse();
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			requireThat(decision, "decision").contains("\"overall_decision\":\"FAIL\"").
				contains("\"infrastructure_failure\":false");
		}
	}

	/**
	 * Retains a batch verdict map without the assertion evidence required to accept a semantic verdict.
	 *
	 * @param requests the observed typed requests
	 * @param request the evaluator request to retain
	 * @return the retained evaluator result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	private static HarnessEvaluationExecution
		staticResultWithoutAssertionEvidence(List<HarnessEvaluationRequest> requests,
		HarnessEvaluationRequest request) throws IOException
	{
		HarnessEvaluationExecution execution = staticEvaluationResult(requests, request, "passed");
		Path verdictsPath = execution.result().retainedProject().resolve("verdicts.json");
		if (!Files.isRegularFile(verdictsPath))
			return execution;
		ObjectNode verdicts = PromptEvaluation.object(PromptEvaluation.read(verdictsPath), "validator verdicts");
		for (Map.Entry<String, JsonNode> entry : verdicts.properties())
		{
			ObjectNode verdict = PromptEvaluation.object(entry.getValue(), "validator verdict");
			for (Map.Entry<String, JsonNode> assertion : PromptEvaluation.object(verdict.path("assertions"),
				"validator assertions").properties())
			{
				PromptEvaluation.object(assertion.getValue(), "validator assertion").remove("evidence");
			}
		}
		PromptEvaluation.write(verdictsPath, verdicts);
		return execution;
	}

	/**
	 * Writes a plan whose validator installs a declared dependency in its copied workspace before running the executor's
	 * changed public boundary.
	 *
	 * @param root the fixture directory
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	private static Path writeExecutableEvidencePlan(Path root) throws IOException
	{
		Path artifact = write(root, "runner-artifact/client/runner.txt", "runner artifact\n").getParent().getParent();
		ensureRunnerRuntimeManifest(artifact);
		Path launcher = write(root, "fixture-runner", """
			#!/bin/sh
			while [ "$#" -gt 0 ]; do
			  case "$1" in
			    --project) project="$2"; shift 2 ;;
			    --output) output="$2"; shift 2 ;;
			    --receipt) receipt="$2"; shift 2 ;;
			    --install-cat) install_cat=true; shift ;;
			    *) shift ;;
			  esac
			done
			cat > "$project/bridge-input.jsonl"
			mkdir -p "$project/retained"
			if [ "${install_cat:-false}" = true ]; then
			  printf '#!/bin/sh\ncat "$1/node_modules/declared.txt"\n' > "$project/retained/checkout.sh"
			  printf declared-dependency > "$project/retained/declared-dependency.txt"
			  printf raw > "$output"
			else
			  mkdir -p "$project/node_modules"
			  cp "$project/declared-dependency.txt" "$project/node_modules/declared.txt"
			  test "$(sh "$project/checkout.sh" "$project")" = declared-dependency
			  cp "$project/checkout.sh" "$project/retained/checkout.sh"
			  printf validator > "$output"
			  printf passed > "$project/retained/verdict.txt"
			fi
			printf '{"retained_project":"%s","raw_result":"%s"}' "$project/retained" "$output" > "$receipt"
			""");
		requireThat(launcher.toFile().setExecutable(true), "fixtureRunnerExecutable").isTrue();
		return write(root, "executable-evidence-plan.json", """
			{
			  "identity":{"runner_launcher":"%s","runner_artifact":"%s","executor_owner":"default",
			  "executor_model":"model-a","executor_effort":"medium","executor_profile":"executor-profile",
			  "validator_owner":"default","validator_model":"model-b","validator_effort":"medium",
			  "validator_profile":"validator-profile"},
			  "validator_excluded_directories":[".git","node_modules"],
			  "executor":{"install_cat":true},
			  "validator":{"install_cat":false}
			}
			""".formatted(launcher, artifact));
	}

	/**
	 * Retains every first-trial batch verdict but omits all verdicts for trial two.
	 *
	 * @param requests the observed typed requests
	 * @param request the evaluator request to retain
	 * @return the retained evaluator result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	private static HarnessEvaluationExecution
		staticResultWithoutSecondTrialVerdict(List<HarnessEvaluationRequest> requests,
		HarnessEvaluationRequest request) throws IOException
	{
		HarnessEvaluationExecution execution = staticEvaluationResult(requests, request, "passed");
		Path verdictsPath = execution.result().retainedProject().resolve("verdicts.json");
		if (!Files.isRegularFile(verdictsPath))
			return execution;
		ObjectNode verdicts = PromptEvaluation.object(PromptEvaluation.read(verdictsPath), "validator verdicts");
		List<String> keys = verdicts.properties().stream().map(Map.Entry::getKey).toList();
		for (String key : keys)
			if (key.startsWith("trial-2/"))
				verdicts.remove(key);
		PromptEvaluation.write(verdictsPath, verdicts);
		return execution;
	}

	/**
	 * Retains the route-rejection diagnostic that makes an executor failure a prompt failure.
	 *
	 * @param requests the observed typed requests
	 * @param request the executor request to fail
	 * @return never returns
	 * @throws IOException after retaining the rejected-route diagnostic
	 */
	private static HarnessEvaluationExecution staticRejectedRouteResult(List<HarnessEvaluationRequest> requests,
		HarnessEvaluationRequest request) throws IOException
	{
		requests.add(request);
		Files.writeString(request.transcript(), "CAT direct rule delivery cannot find declared rule: common/missing.md\n");
		throw new IOException("The static executor rejected the selected route");
	}

	/**
	 * Retains an executor result only after all three first-trial executor requests have started.
	 *
	 * @param requests the observed typed requests
	 * @param starts the ordered executor testcase identifiers
	 * @param barrier the three-party executor-start barrier
	 * @param request the evaluator request to retain
	 * @return the retained evaluator result
	 * @throws IOException if three executors do not start within five seconds
	 */
	private static HarnessEvaluationExecution
		staticResultAfterThreeExecutorStarts(List<HarnessEvaluationRequest> requests,
		List<String> starts, CyclicBarrier barrier, HarnessEvaluationRequest request) throws IOException
	{
		if (request.installCat())
		{
			starts.add(request.project().getParent().getFileName().toString());
			try
			{
				barrier.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for concurrent executor requests", e);
			}
			catch (BrokenBarrierException | TimeoutException e)
			{
				throw new IOException("Three executor requests did not start concurrently", e);
			}
		}
		return staticEvaluationResult(requests, request, "passed");
	}

	/**
	 * Retains a batch-validator result only after all sixteen validator groups have started.
	 *
	 * @param requests the observed typed requests
	 * @param starts the observed batch-validator starts
	 * @param barrier the sixteen-party validator-start barrier
	 * @param request the evaluator request to retain
	 * @return the retained evaluator result
	 * @throws IOException if sixteen validator groups do not start within five seconds
	 */
	private static HarnessEvaluationExecution
		staticResultAfterSixteenValidatorStarts(List<HarnessEvaluationRequest> requests, List<String> starts,
		CyclicBarrier barrier, HarnessEvaluationRequest request) throws IOException
	{
		if (!request.installCat() && Files.isRegularFile(request.project().resolve("verdicts.json")))
		{
			starts.add(request.project().toString());
			try
			{
				barrier.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for concurrent validator groups", e);
			}
			catch (BrokenBarrierException | TimeoutException e)
			{
				throw new IOException("Sixteen validator groups did not start concurrently", e);
			}
		}
		return staticEvaluationResult(requests, request, "passed");
	}
}
