package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.classifiedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.unresolvedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

/**
 * Tests construction of one-case application diagnostics from retained unresolved failures.
 */
public final class PromptEvaluationDirectDiagnosticTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationDirectDiagnosticTest()
	{
	}

	/**
	 * Verifies a direct diagnostic keeps an ordinary task but makes only its selected rule delivery explicit.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recreatesDirectDiagnosticCase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path sourceRun = unresolvedFailure(root, suite, "requested-change");
			Path diagnostic = root.resolve("diagnostic-output/diagnostic");

			PromptEvaluation.run(new String[]{"create-direct-diagnostic", sourceRun.toString(), "requested-change", "1",
				diagnostic.toString()});

			String contents = Files.readString(diagnostic.resolve("suite.json"));
			requireThat(contents, "diagnostic").contains("\"purpose\" : \"diagnostic\"");
			requireThat(contents, "diagnostic").contains("\"route_preparation\" : [ \"common/rule.md\" ]");
			requireThat(contents, "diagnostic").contains("\"source_root\" : \"" +
				root.resolve("prompt-evaluation-suite-artifacts"));
		}
	}

	/**
	 * Verifies the public diagnostic command rejects a caller-supplied suite because the retained failed run owns the
	 * exact suite snapshot.
	 *
	 * @throws IOException if the command unexpectedly performs I/O
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Invalid invocation.*")
	public void rejectsSuppliedDiagnosticSuite() throws IOException
	{
		PromptEvaluation.main(new String[]{"create-direct-diagnostic", "source-run.json", "unexpected-argument",
			"requested-change", "1", "diagnostic.json"});
	}

	/**
	 * Verifies a one-case diagnostic omits source metadata that describes certification cases it does not retain.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void omitsGeneralizationFromDirectDiagnostic() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path manifest = suite.resolve("suite.json");
			Files.writeString(manifest, Files.readString(manifest).replace("  \"purpose\": \"certification\",\n", """
			  "purpose": "certification",
			  "generalization": {
			    "fixture_distinctives": [
			      {"case_id": "requested-change", "text": "accepted input"},
			      {"case_id": "unrelated-request", "text": "rejected input"}
			    ]
			  },
			"""));
			Path sourceRun = unresolvedFailure(root, suite, "requested-change");
			Path diagnostic = root.resolve("diagnostic-output/diagnostic");

			PromptEvaluation.run(new String[]{"create-direct-diagnostic", sourceRun.toString(), "requested-change", "1",
				diagnostic.toString()});

			requireThat(Files.readString(diagnostic.resolve("suite.json")), "diagnostic").
				doesNotContain("\"generalization\"");
		}
	}

	/**
	 * Verifies a direct diagnostic retains eager delivery because the ordinary evaluation already supplies the rule
	 * before the task, rather than claiming that the executor selected it organically.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recreatesEagerDirectDiagnosticCase() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path manifest = suite.resolve("suite.json");
			Files.writeString(manifest, Files.readString(manifest).replace(
				"\"measures\":[\"organic_selection\",\"application\"],\n" +
					"     \"route_expectation\":{\"mode\":\"organic\",\"selected\":[\"common/rule.md\"]}",
				"\"measures\":[\"application\"],\n" +
					"     \"route_expectation\":{\"mode\":\"eager\",\"delivered\":[\"common/rule.md\"]}"));
			Path sourceRun = unresolvedFailure(root, suite, "requested-change");
			Path diagnostic = root.resolve("diagnostic-output/diagnostic");

			PromptEvaluation.run(new String[]{"create-direct-diagnostic", sourceRun.toString(), "requested-change", "1",
				diagnostic.toString()});

			String diagnosticContents = Files.readString(diagnostic.resolve("suite.json"));
			requireThat(diagnosticContents, "diagnosticContents").contains("\"purpose\" : \"diagnostic\"").
				contains("\"mode\" : \"eager\"").
				doesNotContain("\"route_preparation\"");
		}
	}

	/**
	 * Verifies a direct diagnostic keeps product checks while removing checks that require the deliberately bypassed
	 * organic route.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void removesRoutingChecksFromDirectDiagnostic() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path manifest = suite.resolve("suite.json");
			String contents = Files.readString(manifest).replace("""
				"requested-change": {
				     "measures""", """
				"requested-change": {
				     "deterministic_check": {"type": "organic_route_delivery", "assertions": ["route-delivery"]},
				     "measures""");
			Files.writeString(manifest, contents);
			Path sourceRun = unresolvedFailure(root, suite, "requested-change");
			Path diagnostic = root.resolve("diagnostic-output/diagnostic");

			PromptEvaluation.run(new String[]{"create-direct-diagnostic", sourceRun.toString(), "requested-change", "1",
				diagnostic.toString()});

			String diagnosticContents = Files.readString(diagnostic.resolve("suite.json"));
			requireThat(diagnosticContents, "diagnostic").doesNotContain("\"deterministic_check\"").
				doesNotContain("route-delivery");
		}
	}

	/**
	 * Verifies a retained owner classification prevents a redundant model diagnostic.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*requires retained unresolved evidence.*establishes routing-delivery.*")
	public void rejectsDiagnosticAfterClassifiedFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path sourceRun = classifiedFailure(root, suite, "requested-change", "routing-delivery");

			PromptEvaluation.run(new String[]{"create-direct-diagnostic", sourceRun.toString(), "requested-change", "1",
				root.resolve("diagnostic.json").toString()});
		}
	}
}
