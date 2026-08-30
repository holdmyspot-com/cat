package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.ExecutionScheduler;
import io.github.cowwoc.cat.common.evaluation.ValidatorExecution;

import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.classifiedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.evaluation.ValidatorSchedule;
import io.github.cowwoc.cat.common.evaluation.ValidatorLifecycle;
import io.github.cowwoc.cat.common.evaluation.SuiteContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.evaluation.ExecutorEvidence;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests top-level evaluation workflows with a caller-supplied static runner.
 */
public final class PromptEvaluationExecutionCompositionTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Closes the test-owned tool scope after execution-composition tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationExecutionCompositionTest()
	{
	}

	/**
	 * Verifies scheduler-owned executor behavior consumes a static typed runner result without launching a fixture
	 * process.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void schedulesExecutorWithStaticTypedRunner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suitePath = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suitePath.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path planPath = writeStaticExecutionPlan(root);
			ObjectNode suite = SuiteContract.read(suitePath);
			ObjectNode testCase = SuiteContract.testCase(suite, "requested-change", suitePath);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode plan = PromptEvaluation.object(PromptEvaluation.read(planPath), "execution plan");
			List<ExecutorEvidence> evidence = ExecutionScheduler.executeRound(run,
				suitePath, List.of(testCase), List.of(1), root.resolve("execution"), plan, 1, true, runner);
			ValidatorLifecycle.GroupResult<ExecutorEvidence> validation =
				ValidatorExecution.executeGroup(scope.json(), run,
					new ValidatorSchedule.Group<>(1, evidence), root.resolve("validator"), planPath,
					plan, runner);

			requireThat(evidence.size(), "evidence").isEqualTo(1);
			requireThat(validation.verdicts().path("trial-1/requested-change").path("status").asString(), "verdict").
				isEqualTo("passed");
			requireThat(requests.size(), "requests").isEqualTo(2);
			requireThat(requests.getFirst().installCat(), "installCat").isTrue();
			requireThat(requests.get(1).installCat(), "validatorInstallCat").isFalse();
			requireThat(Files.readString(evidence.getFirst().rawResult()), "rawResult").isEqualTo("static result\n");
		}
	}

	/**
	 * Verifies a complete claimed batch uses the supplied static runner for both executor and validator evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsClaimedBatchWithStaticTypedRunner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suitePath = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suitePath.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path planPath = writeStaticExecutionPlan(root);
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"});
			ObjectNode decision = PromptEvaluation.runClaimedBatch(run, List.of(1), root.resolve("batch"), planPath, 3,
				true, runner);

			requireThat(decision.path("complete_suite_runs").asInt(), "completeSuiteRuns").isEqualTo(1);
			requireThat(decision.path("complete_suite_passes").asInt(), "completeSuitePasses").isEqualTo(1);
			requireThat(requests.size(), "requests").isEqualTo(6);
		}
	}

	/**
	 * Verifies the non-certifying smoke composition uses the supplied static runner for its executor and validator.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsSmokeWithStaticTypedRunner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suitePath = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suitePath.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode result = PromptEvaluation.smoke(run, "requested-change", root.resolve("smoke"),
				writeStaticExecutionPlan(root), 1, runner);

			requireThat(result.path("classification").asString(), "classification").isEqualTo("non_certifying_smoke");
			requireThat(requests.size(), "requests").isEqualTo(2);
		}
	}

	/**
	 * Verifies the complete regression-check composition uses the supplied static runner for every evaluator request.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsRegressionCheckWithStaticTypedRunner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suitePath = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suitePath.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "passed");

			ObjectNode result = PromptEvaluation.runRegressionCheck(run, root.resolve("regression"),
				writeStaticExecutionPlan(root), 3, runner);

			requireThat(result.path("status").asString(), "status").isEqualTo("passed");
			requireThat(requests.size(), "requests").isEqualTo(6);
		}
	}

	/**
	 * Verifies terminal evaluation uses the supplied static runner for its first failed batch without launching a
	 * process.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void usesStaticRunnerForTerminalEvaluation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suitePath = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suitePath.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticEvaluationResult(requests, request, "failed");

			ObjectNode decision = PromptEvaluation.runUntilTerminal(run, root.resolve("evaluation"),
				writeStaticExecutionPlan(root), 3, runner);

			requireThat(decision.path("overall_decision").asString(), "decision").isEqualTo("FAIL");
			requireThat(requests.size(), "requests").isEqualTo(12);
		}
	}

	/**
	 * Verifies routing-only diagnostics interpret retained static native events without launching a fixture process.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsRoutingMatrixWithStaticTypedRunner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suitePath = writeSuite(root);
			Path sourceRun = classifiedFailure(root, suitePath, "requested-change", "routing-delivery");
			Path diagnostic = root.resolve("routing.json");
			PromptEvaluation.run(new String[]{"create-routing-diagnostic", sourceRun.toString(), "requested-change", "1",
				diagnostic.toString()});
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", diagnostic.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner runner = request -> staticRoutingResult(requests, request);

			ObjectNode result = PromptEvaluation.runRoutingMatrix(run, root.resolve("routing-matrix"),
				writeStaticExecutionPlan(root), 3, runner);

			requireThat(result.path("status").asString(), "status").isEqualTo("passed");
			requireThat(requests.size(), "requests").isEqualTo(3);
		}
	}

	/**
	 * Retains native routing events for one request without starting a native process.
	 *
	 * @param requests the observed typed requests
	 * @param request the executor request that needs retained routing evidence
	 * @return the retained executor result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	private static HarnessEvaluationExecution staticRoutingResult(List<HarnessEvaluationRequest> requests,
		HarnessEvaluationRequest request) throws IOException
	{
		requests.add(request);
		Path retainedProject = request.project().resolve("retained");
		Files.createDirectories(retainedProject);
		Files.copy(request.project().resolve("project.txt"), retainedProject.resolve("implementation.txt"));
		String route = "cat /rules/common/rule.md";
		if (request.project().toString().contains("unrelated-request"))
			route = "cat /rules/common/other.md";
		String event = nativeRoutingEvent("item/started", route, false) +
			nativeRoutingEvent("item/completed", route, true);
		Files.writeString(request.transcript(), event);
		HarnessEvaluationResult result = HarnessEvaluationResult.retained(retainedProject, request.transcript());
		return new HarnessEvaluationExecution(result, 1, 2);
	}

	/**
	 * Creates one retained native command event for a routing-only executor result.
	 *
	 * @param method the native event method
	 * @param route the command selected by the routed task
	 * @param completed whether the command completed successfully
	 * @return the bridge JSONL record
	 * @throws IOException if the event cannot be serialized
	 */
	private static String nativeRoutingEvent(String method, String route, boolean completed) throws IOException
	{
		ObjectNode item = PromptEvaluation.newObject();
		item.put("type", "commandExecution");
		item.put("id", "route");
		item.put("command", route);
		if (completed)
		{
			item.put("status", "completed");
			item.put("exitCode", 0);
		}
		ObjectNode parameters = PromptEvaluation.newObject();
		parameters.set("item", item);
		ObjectNode nativeEvent = PromptEvaluation.newObject();
		nativeEvent.put("method", method);
		nativeEvent.set("params", parameters);
		ObjectNode bridgeEvent = PromptEvaluation.newObject();
		bridgeEvent.put("type", "native-event");
		bridgeEvent.put("event", PromptEvaluation.jsonText(nativeEvent));
		return PromptEvaluation.jsonText(bridgeEvent) + System.lineSeparator();
	}
}
