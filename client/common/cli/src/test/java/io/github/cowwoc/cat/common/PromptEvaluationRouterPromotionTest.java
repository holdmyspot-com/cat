package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.classifiedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;

import tools.jackson.databind.node.ObjectNode;

/**
 * Tests promotion of a router after its routing diagnostic completes.
 */
public final class PromptEvaluationRouterPromotionTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationRouterPromotionTest()
	{
	}

	/**
	 * Verifies a router can be promoted only after its complete retained routing matrix passes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void promotesRouterAfterCompleteRoutingMatrix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			PromotionFixture fixture = completePassingMatrix(root);
			Path receipt = root.resolve("router-promotion.json");

			String result = output(() -> PromptEvaluation.run(new String[]{"promote-router", fixture.sourceRun().toString(),
				fixture.matrixRun().toString(), root.resolve("rules").toString(), receipt.toString()}));

			requireThat(result, "result").contains("\"source_run\":\"" + fixture.sourceRun() + "\"").
				contains("\"routing_matrix_run\":\"" + fixture.matrixRun() + "\"").
				contains("\"router_rule\":\"common/default-rules.md\"");
		}
	}

	/**
	 * Holds the source and completed matrix records for one promotion scenario.
	 *
	 * @param sourceRun the failed organic source record
	 * @param matrixRun the completed routing-matrix record
	 */
	private record PromotionFixture(Path sourceRun, Path matrixRun)
	{
	}

	/**
	 * Creates one retained source run and a passing routing matrix.
	 *
	 * @param root the fixture root
	 * @return the retained source and matrix records
	 * @throws IOException if fixture I/O fails
	 */
	private static PromotionFixture completePassingMatrix(Path root) throws IOException
	{
		Path suite = writeSuite(root);
		Path sourceRun = classifiedFailure(root, suite, "requested-change", "routing-delivery");
		Path diagnostic = root.resolve("diagnostic-output/routing.json");
		PromptEvaluation.run(new String[]{"create-routing-diagnostic", sourceRun.toString(), "requested-change", "1",
			diagnostic.toString()});
		Path prompt = write(root, "prompt.md", "Prompt artifact\n");
		Path context = write(root, "context.md", "Validator contract\n");
		Path matrixRun = root.resolve("routing-matrix-run.json");
		PromptEvaluation.run(new String[]{"begin", diagnostic.toString(), prompt.toString(), "codex", "model-a",
			context.toString(), matrixRun.toString()});
		PromptEvaluation.runRoutingMatrix(matrixRun, root.resolve("routing-matrix-workspace"),
			writeStaticExecutionPlan(root), 3, staticRoutingRunner(true));
		write(root, "rules/common/rule.md", "Reusable rule\n");
		write(root, "rules/common/default-rules.md", "Read rules/common/rule.md before implementation.\n");
		return new PromotionFixture(sourceRun, matrixRun);
	}

	/**
	 * Verifies a failed route in the matrix cannot publish a router-promotion receipt.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void failsRouterPromotionOnMatrixFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path sourceRun = classifiedFailure(root, suite, "requested-change", "routing-delivery");
			Path diagnostic = root.resolve("diagnostic-output/routing.json");
			PromptEvaluation.run(new String[]{"create-routing-diagnostic", sourceRun.toString(), "requested-change",
				"1", diagnostic.toString()});
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path matrixRun = root.resolve("routing-matrix-run.json");
			PromptEvaluation.run(new String[]{"begin", diagnostic.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), matrixRun.toString()});
			PromptEvaluation.runRoutingMatrix(matrixRun, root.resolve("routing-matrix-workspace"),
				writeStaticExecutionPlan(root), 3, staticRoutingRunner(false));
			write(root, "rules/common/rule.md", "Reusable rule\n");
			write(root, "rules/common/default-rules.md", "Read rules/common/rule.md before implementation.\n");
			Path receipt = root.resolve("router-promotion.json");

			try
			{
				PromptEvaluation.run(new String[]{"promote-router", sourceRun.toString(), matrixRun.toString(),
					root.resolve("rules").toString(), receipt.toString()});
				throw new AssertionError("Expected failed routing matrix to reject router promotion");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("every routing-matrix case to pass");
			}
			requireThat(Files.exists(receipt), "receipt").isFalse();
		}
	}

	/**
	 * Verifies an existing receipt prevents router promotion from replacing previously retained evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsPreexistingPromotionReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path receipt = write(root, "router-promotion.json", "existing receipt\n");
			try
			{
				PromptEvaluation.run(new String[]{"promote-router", root.resolve("source.json").toString(),
					root.resolve("matrix.json").toString(), root.resolve("rules").toString(), receipt.toString()});
				throw new AssertionError("Expected preexisting router-promotion receipt to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("Router-promotion receipt already exists");
			}
			requireThat(Files.readString(receipt), "receipt").isEqualTo("existing receipt\n");
		}
	}

	/**
	 * Verifies promotion rejects a completed matrix retained for another failed organic run.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsMatrixFromAnotherSourceRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			PromotionFixture fixture = completePassingMatrix(root);
			ObjectNode matrixRun = (ObjectNode) scope.json().readTree(fixture.matrixRun().toFile());
			Path matrixSuite = Path.of(matrixRun.path("suite").asString());
			Path manifest = matrixSuite.resolve("suite.json");
			ObjectNode suite = (ObjectNode) scope.json().readTree(manifest.toFile());
			suite.put("source_run", root.resolve("other-source-run.json").toString());
			Files.writeString(manifest, scope.json().writeValueAsString(suite));
			Path receipt = root.resolve("router-promotion.json");
			try
			{
				PromptEvaluation.run(new String[]{"promote-router", fixture.sourceRun().toString(),
					fixture.matrixRun().toString(), root.resolve("rules").toString(), receipt.toString()});
				throw new AssertionError("Expected a matrix from another source run to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("Routing matrix belongs to a different failed organic run");
			}
			requireThat(Files.exists(receipt), "receipt").isFalse();
		}
	}

	/**
	 * Verifies promotion rejects a matrix that changes an organic certification expectation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsChangedMatrixExpectation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			PromotionFixture fixture = completePassingMatrix(root);
			ObjectNode matrixRun = (ObjectNode) scope.json().readTree(fixture.matrixRun().toFile());
			Path matrixSuite = Path.of(matrixRun.path("suite").asString());
			Path manifest = matrixSuite.resolve("suite.json");
			ObjectNode suite = (ObjectNode) scope.json().readTree(manifest.toFile());
			ObjectNode scenarios = (ObjectNode) suite.path("scenarios");
			ObjectNode firstScenario = (ObjectNode) scenarios.properties().iterator().next().getValue();
			ObjectNode expectation = (ObjectNode) firstScenario.path("route_expectation");
			expectation.putArray("selected").add("common/other.md");
			Files.writeString(manifest, scope.json().writeValueAsString(suite));
			Path receipt = root.resolve("router-promotion.json");
			try
			{
				PromptEvaluation.run(new String[]{"promote-router", fixture.sourceRun().toString(),
					fixture.matrixRun().toString(), root.resolve("rules").toString(), receipt.toString()});
				throw new AssertionError("Expected a changed matrix expectation to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("Routing matrix changed the route expectation");
			}
			requireThat(Files.exists(receipt), "receipt").isFalse();
		}
	}

	/**
	 * Returns a static executor that retains the route selected for each routing-matrix testcase.
	 *
	 * @param correctRoutes whether the scenario configured to avoid a route does so
	 * @return the runner that retains native routing events
	 */
	private static HarnessEvaluationRunner staticRoutingRunner(boolean correctRoutes)
	{
		return request -> staticRoutingResult(request, correctRoutes);
	}

	/**
	 * Retains native routing events for one executor request without launching a process.
	 *
	 * @param request the executor request that needs retained route evidence
	 * @param correctRoutes whether the scenario configured to avoid a route does so
	 * @return the retained executor result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	private static HarnessEvaluationExecution staticRoutingResult(HarnessEvaluationRequest request,
		boolean correctRoutes) throws IOException
	{
		Path retainedProject = request.project().resolve("retained");
		Files.createDirectories(retainedProject);
		Files.copy(request.project().resolve("project.txt"), retainedProject.resolve("implementation.txt"));
		String route = "cat /rules/common/rule.md";
		if (correctRoutes && request.project().toString().contains("unrelated-request"))
			route = "cat /rules/common/other.md";
		String events = nativeRoutingEvent("item/started", route, false) +
			nativeRoutingEvent("item/completed", route, true);
		Files.writeString(request.transcript(), events);
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
