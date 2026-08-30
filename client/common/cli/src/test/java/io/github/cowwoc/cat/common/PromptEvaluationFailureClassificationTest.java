package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.recordedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests classification of retained evaluation failures.
 */
public final class PromptEvaluationFailureClassificationTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationFailureClassificationTest()
	{
	}

	/**
	 * Verifies retained deterministic evidence classifies a failure without another harness run.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void classifiesRetainedFailureEvidence() throws IOException
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
			output(() -> PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"}));
			Path raw = write(root, "raw.json", "raw result\n");
			Path evidence = write(root, "evidence.json", "{\"failure_class\":\"routing-delivery\"}\n");
			PromptEvaluation.run(new String[]{"record", run.toString(), "requested-change", "1", "failed",
				raw.toString(), evidence.toString()});

			String result = output(() -> PromptEvaluation.run(new String[]{"classify-failure", run.toString(),
				"requested-change", "1"}));

			requireThat(result, "result").contains("\"failure_class\":\"routing-delivery\"").
				contains("\"evidence\":\"" + evidence + "\"");
		}
	}

	/**
	 * Verifies a failed executable assertion uses the suite's declared owner without another model review.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void classifiesOwnedDeterministicFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "check.sh", "printf '{\"assertions\":{\"result\":{\"status\":\"passed\"}}}'\n");
			write(suite, "requested-change/scenario.md", """
				## Scenario

				Implement the requested behavior.

				## Assertions
				""");
			configureRequestedScenario(suite, """
				{"deterministic_check":{"artifact":"check.sh","command":["sh","${check}"],"assertions":["result"]},
				"assertion_failure_classes":{"result":"application"}}
				""");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			output(() -> PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"}));
			Path raw = write(root, "raw.json", "raw result\n");
			Path evidence = write(root, "evidence.json", "{\"assertions\":{\"result\":{\"status\":\"failed\"}}}\n");
			PromptEvaluation.run(new String[]{"record", run.toString(), "requested-change", "1", "failed",
				raw.toString(), evidence.toString()});

			String result = output(() -> PromptEvaluation.run(new String[]{"classify-failure", run.toString(),
				"requested-change", "1"}));

			requireThat(result, "result").contains("\"failure_class\":\"application\"").
				contains("\"evidence\":\"" + evidence + "\"");
		}
	}

	/**
	 * Verifies a failed semantic assertion can identify prompt application when its durable test contract owns that
	 * conclusion.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void classifiesDeclaredSemanticFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			configureRequestedScenario(suite, """
				{"assertion_failure_classes":{"result":"application"}}
				""");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			output(() -> PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"}));
			Path raw = write(root, "raw.json", "raw result\n");
			Path evidence = write(root, "evidence.json", """
				{"assertions":{"result":{"status":"failed","evidence":"The executor omitted the required update."}}}
				""");
			PromptEvaluation.run(new String[]{"record", run.toString(), "requested-change", "1", "failed",
				raw.toString(), evidence.toString()});

			String result = output(() -> PromptEvaluation.run(new String[]{"classify-failure", run.toString(),
				"requested-change", "1"}));

			requireThat(result, "result").contains("\"failure_class\":\"application\"").
				contains("\"evidence\":\"" + evidence + "\"");
		}
	}

	/**
	 * Verifies every retained failure class has an explicit, stable user-visible result.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void classifiesEveryRetainedFailureClass() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			for (String[] control : List.of(
				new String[]{"routing-delivery", "failed", "routing-delivery"},
				new String[]{"application", "failed", "application"},
				new String[]{"deterministic-tool", "failed", "deterministic-tool"},
				new String[]{"external-prerequisite", "failed", "external-prerequisite"},
				new String[]{"unresolved", "failed", "unresolved"},
				new String[]{"infrastructure", "blocked", "infrastructure"}))
			{
				String declaredClass = control[0];
				if (declaredClass.equals("unresolved") || declaredClass.equals("infrastructure"))
					declaredClass = null;
				Path run = recordedFailure(root.resolve(control[0]), suite, "requested-change", declaredClass, control[1]);
				String result = output(() -> PromptEvaluation.run(new String[]{"classify-failure", run.toString(),
					"requested-change", "1"}));
				requireThat(result, "result").contains("\"failure_class\":\"" + control[2] + "\"").
					contains("\"evidence\"");
			}
		}
	}

	/**
	 * Verifies a deterministic validator or tool failure directs repair to that tool instead of the evaluated prompt.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void directsToolFailureAwayFromPrompt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path run = recordedFailure(root, suite, "requested-change", "deterministic-tool", "failed");

			String result = output(() -> PromptEvaluation.run(new String[]{"classify-failure", run.toString(),
				"requested-change", "1"}));

			requireThat(result, "result").contains("\"repair_owner\":\"deterministic-tool\"").
				contains("repair the deterministic tool that produced this evidence").
				contains("do not change the evaluated prompt to compensate");
		}
	}

	/**
	 * Verifies an organically selected rule is not treated as applied when the runner records a different model
	 * selection.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void classifiesMissingOrganicRoute() throws IOException
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
			output(() -> PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"}));
			Path raw;
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode nativeEvent = scope.json().createObjectNode();
				nativeEvent.put("method", "item/started");
				ObjectNode item = nativeEvent.putObject("params").putObject("item");
				item.put("type", "commandExecution");
				item.put("command", "cat rules/common/other.md");
				ObjectNode envelope = scope.json().createObjectNode();
				envelope.put("type", "native-event");
				envelope.put("event", scope.json().writeValueAsString(nativeEvent));
				ObjectNode selected = scope.json().createObjectNode();
				selected.put("type", "route-selected");
				selected.put("selection_turn", 1);
				selected.putArray("rules").add("common/other.md");
				raw = write(root, "raw.json", scope.json().writeValueAsString(envelope) + System.lineSeparator() +
					scope.json().writeValueAsString(selected) + System.lineSeparator());
			}
			Path evidence = write(root, "evidence.json", """
				{"assertions":{"result":{"status":"failed","evidence":"The response omitted the requested result."}}}
				""");
			PromptEvaluation.run(new String[]{"record", run.toString(), "requested-change", "1", "failed",
				raw.toString(), evidence.toString()});

			String result = output(() -> PromptEvaluation.run(new String[]{"classify-failure", run.toString(),
				"requested-change", "1"}));

			requireThat(result, "result").contains("\"failure_class\":\"routing-delivery\"").
				contains("\"evidence\":\"" + raw + "\"");
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			requireThat(decision, "decision").contains("\"overall_decision\":\"FAIL\"").
				contains("\"infrastructure_failure\":false").contains("\"complete_suite_runs\":1");
		}
	}

	/**
	 * Verifies a route command that exits unsuccessfully is classified as a routing-delivery failure.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void classifiesUnsuccessfulOrganicRoute() throws IOException
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
			output(() -> PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"}));
			Path raw;
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode startedItem = scope.json().createObjectNode();
				startedItem.put("type", "commandExecution");
				startedItem.put("id", "route");
				startedItem.put("command", "cat /installed/rules/common/rule.md");
				ObjectNode startedEvent = scope.json().createObjectNode();
				startedEvent.put("method", "item/started");
				startedEvent.putObject("params").set("item", startedItem);
				ObjectNode startedEnvelope = scope.json().createObjectNode();
				startedEnvelope.put("type", "native-event");
				startedEnvelope.put("event", scope.json().writeValueAsString(startedEvent));

				ObjectNode completedItem = startedItem.deepCopy();
				completedItem.put("status", "failed");
				completedItem.put("exitCode", 1);
				ObjectNode completedEvent = scope.json().createObjectNode();
				completedEvent.put("method", "item/completed");
				completedEvent.putObject("params").set("item", completedItem);
				ObjectNode completedEnvelope = scope.json().createObjectNode();
				completedEnvelope.put("type", "native-event");
				completedEnvelope.put("event", scope.json().writeValueAsString(completedEvent));
				raw = write(root, "raw.json", scope.json().writeValueAsString(startedEnvelope) + System.lineSeparator() +
					scope.json().writeValueAsString(completedEnvelope) + System.lineSeparator());
			}
			Path evidence = write(root, "evidence.json", """
				{"assertions":{"result":{"status":"failed","evidence":"The response omitted the requested result."}}}
				""");
			PromptEvaluation.run(new String[]{"record", run.toString(), "requested-change", "1", "failed",
				raw.toString(), evidence.toString()});

			String result = output(() -> PromptEvaluation.run(new String[]{"classify-failure", run.toString(),
				"requested-change", "1"}));

			requireThat(result, "result").contains("\"failure_class\":\"routing-delivery\"").
				contains("\"evidence\":\"" + raw + "\"");
		}
	}

	/**
	 * Adds current-format evaluator metadata to the requested-change scenario.
	 *
	 * @param suite the scenario-suite root
	 * @param metadata the JSON metadata object to merge
	 * @throws IOException if the fixture cannot be updated
	 */
	private static void configureRequestedScenario(Path suite, String metadata) throws IOException
	{
		Path manifest = suite.resolve("suite.json");
		ObjectNode suiteMetadata = PromptEvaluation.object(PromptEvaluation.read(manifest), "suite metadata");
		ObjectNode scenarios = PromptEvaluation.object(suiteMetadata.path("scenarios"), "scenario metadata");
		ObjectNode requested = scenarios.withObject("requested-change");
		ObjectNode additions = PromptEvaluation.object(PromptEvaluation.parseJson(metadata), "scenario additions");
		for (Map.Entry<String, tools.jackson.databind.JsonNode> entry : additions.properties())
			requested.set(entry.getKey(), entry.getValue());
		PromptEvaluation.write(manifest, suiteMetadata);
	}
}
