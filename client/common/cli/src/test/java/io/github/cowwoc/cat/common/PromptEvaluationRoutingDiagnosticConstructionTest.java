package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.classifiedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests construction of routing-only diagnostics from retained routing failures.
 */
public final class PromptEvaluationRoutingDiagnosticConstructionTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationRoutingDiagnosticConstructionTest()
	{
	}

	/**
	 * Verifies a routing diagnostic retains every organic ordinary task while isolating rule selection from application.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recreatesRoutingDiagnosticCase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path sourceRun = classifiedFailure(root, suite, "requested-change", "routing-delivery");
			Path diagnostic = root.resolve("diagnostic-output/routing");

			PromptEvaluation.run(new String[]{"create-routing-diagnostic", sourceRun.toString(), "requested-change",
				"1", diagnostic.toString()});

			String contents = Files.readString(diagnostic.resolve("suite.json"));
			requireThat(contents, "contents").contains("\"purpose\" : \"diagnostic\"").
				contains("\"diagnostic_kind\" : \"routing_only\"").
				contains("\"source_run\" : \"" + sourceRun + "\"").
				contains("\"source_case_id\" : \"requested-change\"").
				contains("\"source_trial\" : 1").
				contains("\"routing_diagnostic\" : true").
				contains("\"measures\" : [ \"organic_selection\" ]").
				contains("\"requested-change\" : {").
				contains("\"unrelated-request\" : {").
				contains("\"edge-condition\" : {").
				doesNotContain("\"route_preparation\"").
				doesNotContain("\"deterministic_check\"");
		}
	}

	/**
	 * Verifies a routing-only diagnostic removes source controls that require semantic assertions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void dropsSemanticControlsForRouting() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "control-response.md", "A controlled reader-facing response.\n");
			Path manifest = suite.resolve("suite.json");
			ObjectNode suiteMetadata = PromptEvaluation.object(PromptEvaluation.read(manifest), "suite metadata");
			ObjectNode scenarios = PromptEvaluation.object(suiteMetadata.path("scenarios"), "scenario metadata");
			ArrayNode controls = scenarios.withObject("requested-change").putArray("semantic_controls");
			controls.addObject().put("id", "result-missing").put("response", "control-response.md").
				putObject("expected_assertions").put("result", "failed");
			PromptEvaluation.write(manifest, suiteMetadata);
			Path sourceRun = classifiedFailure(root, suite, "requested-change", "routing-delivery");
			Path diagnostic = root.resolve("diagnostic-output/routing");

			PromptEvaluation.run(new String[]{"create-routing-diagnostic", sourceRun.toString(), "requested-change",
				"1", diagnostic.toString()});

			requireThat(Files.readString(diagnostic.resolve("suite.json")), "diagnostic").doesNotContain(
				"\"semantic_controls\"");
		}
	}

	/**
	 * Verifies a routing diagnostic preserves an organic route that must remain absent.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recreatesRoutingDiagnosticForAbsentRoute() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Files.writeString(suite.resolve("suite.json"), Files.readString(suite.resolve("suite.json")).replace(
				"\"selected\":[\"common/rule.md\"]", "\"not_selected\":[\"common/rule.md\"]"));
			Path sourceRun = classifiedFailure(root, suite, "requested-change", "routing-delivery");
			Path diagnostic = root.resolve("diagnostic-output/routing");

			PromptEvaluation.run(new String[]{"create-routing-diagnostic", sourceRun.toString(), "requested-change",
				"1", diagnostic.toString()});

			String contents = Files.readString(diagnostic.resolve("suite.json"));
			requireThat(contents, "contents").contains("\"not_selected\" : [ \"common/rule.md\" ]").
				doesNotContain("\"deterministic_check\"");
		}
	}
}
