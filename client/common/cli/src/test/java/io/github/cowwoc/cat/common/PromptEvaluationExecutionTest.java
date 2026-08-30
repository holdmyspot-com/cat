package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.Executor;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.configureRequestedScenario;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.attemptLedger;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link PromptEvaluation} whole-suite execution scheduling.
 */
public final class PromptEvaluationExecutionTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationExecutionTest()
	{
	}

	/**
	 * Closes the test-owned tool scope after execution tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies concurrent workers retain every result from one claimed whole-prompt trial.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws InterruptedException if a worker is interrupted
	 * @throws ExecutionException if a worker cannot record its result
	 */
	@Test
	public void retainsConcurrentClaimedResults() throws IOException, InterruptedException, ExecutionException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "harness-a", "model-a",
				context.toString(), run.toString()});
			output(() -> PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"}));
			try (ExecutorService workers = Executors.newFixedThreadPool(3))
			{
				List<Callable<Void>> tasks = List.of(
					() -> recordClaimed(run, "requested-change", 1, "passed"),
					() -> recordClaimed(run, "unrelated-request", 1, "passed"),
					() -> recordClaimed(run, "edge-condition", 1, "passed"));
				for (Future<Void> result : workers.invokeAll(tasks))
					result.get();
			}
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			requireThat(decision, "decision").contains("\"complete_suite_runs\":1").
				contains("\"complete_suite_passes\":1");
			requireThat(Files.readString(run), "run").contains("\"attempt_index\"").
				contains("\"1\"").contains("\"requested-change\" : \"passed\"");
		}
	}

	/**
	 * Verifies scheduling rejects a record that omits its required attempt index.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*required attempt index.*")
	public void rejectsRecordWithoutAttemptIndex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "harness-a", "model-a",
				context.toString(), run.toString()});

			String recordWithoutIndex = Files.readString(run).replaceFirst(
				"(?m)^\\s*\"attempt_index\"\\s*:\\s*\\{\\s*},\\R", "");
			Files.writeString(run, recordWithoutIndex);

			PromptEvaluation.unrecordedTrials(run, "requested-change", List.of(1));
		}
	}

	/**
	 * Verifies semantic-control verdicts are checked before an evaluation starts its candidate trials.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void checksControlsBeforeCandidateTrials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "control-response.md", "A controlled reader-facing response.\n");
			configureRequestedScenario(suite, """
				{"semantic_controls":[{"id":"result-missing","response":"control-response.md",
				"expected_assertions":{"result":"failed"}}]}
				""");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			PromptEvaluation.run(new String[]{"bind-plan", run.toString(), plan.toString()});
			Path workspace = root.resolve("workspace");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "failed");

			PromptEvaluation.verifySemanticControls(run, workspace, plan, runner);

			Path receipt = workspace.resolve("semantic-control-check.json");
			requireThat(receipt, "semanticControlReceipt").isRegularFile();
			requireThat(Files.readString(receipt), "semanticControlReceipt").contains("\"status\" : \"passed\"");
			requireThat(requests.size(), "requests").isEqualTo(1);
		}
	}

	/**
	 * Verifies one execution transaction claims, executes, validates, and records a passing testcase.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void executesAndRecordsOneTestcase() throws IOException
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
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed", true);
			PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"});

			ObjectNode result = Executor.execute(scope.json(), run, "requested-change", 1, root.resolve("execution"), plan,
				runner);

			requireThat(result.path("status").asString(), "result").isEqualTo("passed");
			String record = Files.readString(attemptLedger(run));
			requireThat(record, "record").contains("\"case_id\":\"requested-change\"").contains("\"passed\"");
			requireThat(requests.size(), "requests").isEqualTo(2);
			requireThat(requests.getFirst().installCat(), "executorRequest").isTrue();
			requireThat(requests.get(1).installCat(), "validatorRequest").isFalse();
		}
	}

	/**
	 * Verifies a durable testcase declares steering, interruption, and resumed-turn messages for its executor.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void writesDeclaredMidTurnMessages() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "steer.md", "Keep the existing fixture and change course.\n");
			write(suite, "resume.md", "Resume and finish the requested behavior.\n");
			configureRequestedScenario(suite, """
				{"turns":[{"type":"steer","prompt":"steer.md","after":"turn-started"},
				{"type":"interrupt","after":"turn-started"},{"type":"turn","prompt":"resume.md"}]}
				""");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed", true);
			PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"});

			Executor.execute(scope.json(), run, "requested-change", 1, root.resolve("execution"), plan, runner);

			requireThat(requests.size(), "requests").isEqualTo(2);
			String executorInput = requests.getFirst().input();
			requireThat(executorInput, "executorInput").contains("\"type\":\"steer\"").
				contains("Keep the existing fixture and change course.").contains("\"type\":\"interrupt\"").
				contains("\"after\":\"turn-started\"").contains("Resume and finish the requested behavior.");
		}
	}

	/**
	 * Verifies a single-trial reviewer receives only semantic assertions when the testcase also has a deterministic
	 * check.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void singleValidatorSendsSemanticAssertions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Files.writeString(suite.resolve("requested-change/scenario.md"), """
				## Scenario

				Implement the requested behavior.

				## Assertions

				### result

				The retained project contains the requested result.

				### reports-result

				The retained executor result reports the requested result.
				""");
			configureRequestedScenario(suite, """
				{"deterministic_check":{"artifact":"acceptance-check.sh","command":["sh","${check}",
				"${project}"],"assertions":["check-result"]}}
				""");
			Path check = write(suite, "acceptance-check.sh", "printf '%s' " +
				"'{\"assertions\":{\"check-result\":{\"status\":\"passed\",\"evidence\":\"fixture\"}}}'\\n");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path plan = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed", true);
			PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"});

			Executor.execute(scope.json(), run, "requested-change", 1, root.resolve("execution"), plan, runner);

			requireThat(requests.size(), "requests").isEqualTo(2);
			String validatorTurnLine = requests.get(1).input().lines().findFirst().orElseThrow();
			ObjectNode validatorTurn = PromptEvaluation.object(PromptEvaluation.parseJson(validatorTurnLine),
				"validator turn");
			String validatorPrompt = validatorTurn.path("prompt").asString();
			requireThat(validatorPrompt, "validatorPrompt").contains("\"id\" : \"result\"").contains(
				"\"id\" : \"reports-result\"").doesNotContain("check-result").doesNotContain(
				"deterministic_check").doesNotContain("route_expectation");
		}
	}

	/**
	 * Verifies a terminal evaluation fills its shared executor capacity with complete suites until the collected evidence
	 * makes certification impossible.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsUntilTerminalDecision() throws IOException
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
			Path workspace = root.resolve("evaluation-workspace");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "failed");

			ObjectNode result = PromptEvaluation.runUntilTerminal(run, workspace, plan, 3, runner);

			requireThat(result.path("overall_decision").asString(), "result").isEqualTo("FAIL");
			requireThat(requests.size(), "requests").isEqualTo(12);
			requireThat(workspace.resolve("batch-1"), "firstBatch").isDirectory();
			requireThat(Files.notExists(workspace.resolve("batch-2")), "secondBatch").isTrue();
			requireThat(Files.readString(run), "run").contains("\"next_trial\" : 4");
			requireThat(Files.readString(attemptLedger(run)), "attemptLedger").contains("\"case_id\":\"requested-change\"").
				contains("\"case_id\":\"unrelated-request\",\"trial\":1,\"status\":\"failed\"");
			requireThat(workspace.resolve("batch-1/trial-3/requested-change"), "thirdPositiveCase").isDirectory();
		}
	}

	/**
	 * Verifies a validator receives an isolated read-only evidence copy without changing the executor's retained project.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void exposesDirectSingleTrialEvidence() throws IOException
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
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed", true);
			PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"});

			ObjectNode result = Executor.execute(scope.json(), run, "requested-change", 1, root.resolve("execution"), plan,
				runner);

			requireThat(result.path("status").asString(), "result").isEqualTo("passed");
			requireThat(requests.size(), "requests").isEqualTo(2);
			Path retainedProject = root.resolve("execution/executor-project/retained");
			String validatorPrompt = Files.readString(root.resolve("execution/validator-project/validator-prompt.md"));
			Path stagedProject = root.resolve("execution/validator-project/direct-evidence");
			Path stagedOutput = stagedProject.resolve(".cat-evaluation-executor-result.txt");
			Path stagedResponse = stagedProject.resolve(".cat-evaluation-final-response.md");
			requireThat(validatorPrompt, "validatorPrompt").contains("\"executor_output\" : \"" + stagedOutput + "\"").
				contains("\"executor_response\" : \"" + stagedResponse + "\"");
			requireThat(Files.notExists(retainedProject.resolve(".cat-evaluation-executor-result.txt")),
				"retainedOutput").isTrue();
			requireThat(Files.notExists(retainedProject.resolve(".cat-evaluation-final-response.md")),
				"retainedResponse").isTrue();
			requireThat(Files.readString(stagedOutput), "stagedOutput").contains("Static final reader response");
			requireThat(Files.readString(stagedResponse), "stagedResponse").contains("Static final reader response");
		}
	}

	/**
	 * Verifies an executor failure still records a terminal blocked testcase result.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsBlockedResultWhenExecutorFails() throws IOException
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
			Path plan = write(root, "failed-plan.json", """
				{
				  "executor":{"install_cat":true},
				  "validator":{"install_cat":false}
				}
				""");

			String result = output(() -> PromptEvaluation.run(new String[]{"execute", run.toString(), "requested-change",
				"1", root.resolve("failed-execution").toString(), plan.toString()}));

			requireThat(result, "result").contains("\"status\":\"blocked\"");
			requireThat(Files.readString(attemptLedger(run)), "attemptLedger").contains("\"status\":\"blocked\"");
		}
	}

	/**
	 * Verifies a certification command records a terminal infrastructure result when a later batch detects that its
	 * execution conditions changed.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsTerminalConditionFailure() throws IOException
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
			PromptEvaluation.runBatch(run, 1, root.resolve("first-batch"), plan, 1, runner);
			Files.writeString(root.resolve("runner-artifact/client/runner.txt"), "changed runner artifact\n");

			ObjectNode result = PromptEvaluation.runUntilTerminal(run, root.resolve("workspace"), plan, 1, runner);
			requireThat(result.path("overall_decision").asString(), "result").isEqualTo("INFRASTRUCTURE_FAILED");
			requireThat(Files.readString(run), "run").contains("\"overall_decision\" : \"INFRASTRUCTURE_FAILED\"");
		}
	}

	/**
	 * Records one already claimed trial result.
	 *
	 * @param run the result record
	 * @param caseId the testcase identifier
	 * @param trial the claimed whole-prompt trial number
	 * @param status the outcome status
	 * @return no value
	 * @throws IOException if recording fails
	 */
	private static Void recordClaimed(Path run, String caseId, int trial, String status) throws IOException
	{
		Path root = run.getParent();
		Path rawResult = write(root, caseId + "-" + trial + "-raw.json", "raw result\n");
		Path validatorResult = write(root, caseId + "-" + trial + "-validator.json", "validator result\n");
		PromptEvaluation.run(new String[]{"record", run.toString(), caseId, Integer.toString(trial), status,
			rawResult.toString(), validatorResult.toString()});
		return null;
	}
}
