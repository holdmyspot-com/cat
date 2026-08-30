package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.EvaluationFiles;
import io.github.cowwoc.cat.common.evaluation.SuiteStore;

import io.github.cowwoc.cat.common.evaluation.SuiteSnapshot;

import io.github.cowwoc.cat.common.evaluation.DeterministicChecks;

import io.github.cowwoc.cat.common.evaluation.SuiteContract;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeGeneralizationSuite;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests durable prompt-evaluation suite contracts and their generalization boundary.
 */
public final class PromptEvaluationSuiteContractTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationSuiteContractTest()
	{
	}

	/**
	 * Verifies a scenario directory supplies Runner input and evaluator assertions from its Markdown files.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void validatesScenarioDirectorySuite() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-scenarios"));
			Files.writeString(suite.resolve("suite.json"), """
				{
				  "schema_version": 3,
				  "purpose": "certification",
				  "sprt": {
				    "accept_pass_rate": 0.95,
				    "reject_pass_rate": 0.85,
				    "false_accept_rate": 0.05,
				    "false_reject_rate": 0.05
				  }
				}
				""");
			Path scenario = Files.createDirectories(suite.resolve("rules/example/ordinary-change"));
			Files.writeString(scenario.resolve("scenario.md"), """
				## Scenario

				Update the configuration after reading its documented constraint.

				## Assertions

				### reads-constraint

				The agent read `config/constraint.md` before changing the configuration.
				""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies a conventionally discovered Java check supplies a failed evaluator-owned deterministic outcome.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsJavaCheckFailure() throws IOException
	{
		try (ToolScope scope = new ToolScope();
		TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-java-check"));
			Files.writeString(suite.resolve("suite.json"), """
				{
				  "schema_version": 3,
				  "purpose": "certification",
				  "evaluation_test_package": "io.github.cowwoc.cat.common",
				  "sprt": {
				    "accept_pass_rate": 0.95,
				    "reject_pass_rate": 0.85,
				    "false_accept_rate": 0.05,
				    "false_reject_rate": 0.05
				  }
				}
				""");
			Path scenario = Files.createDirectories(suite.resolve("java-check-failure"));
			write(scenario, "scenario.md", """
				## Scenario

				Make the requested change.

				## Assertions

				### reports-change

				The retained project reports the requested change.
				""");
			Path retainedProject = Files.createDirectories(suite.resolve("retained-project"));
			Files.writeString(retainedProject.resolve("expected-failure"), "");
			Path executorResult = write(suite, "executor-result.json", "{}");
			Path workspace = Files.createDirectories(suite.resolve("workspace"));
			ObjectNode testCase = (ObjectNode) SuiteContract.read(suite).path("cases").get(0);

			Path receipt = DeterministicChecks.run(scope.json(), testCase, suite, retainedProject, executorResult,
				workspace, null);

			ObjectNode assertions = (ObjectNode) PromptEvaluation.object(PromptEvaluation.read(receipt),
				"deterministic receipt").path("assertions");
			requireThat(assertions.path("recordsRetainedResult").path("status").asString(), "secondJavaCheckStatus").
				isEqualTo("passed");
			requireThat(DeterministicChecks.failed(receipt, testCase, scenario.resolve("scenario.md")),
				"failed").isTrue();
		}
	}

	/**
	 * Verifies a scenario cannot declare the same stable evaluator assertion twice.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*duplicate evaluator assertion id.*")
	public void rejectsDuplicateScenarioAssertionId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-duplicate-assertion"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05}}
				""");
			Path scenario = Files.createDirectories(suite.resolve("ordinary-change"));
			write(scenario, "scenario.md", """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				The retained configuration contains the requested change.

				### records-change

				The final response identifies the requested change.
				""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies a scenario must contain both required structural headings.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Scenario must contain ## Scenario followed by ## Assertions.*")
	public void rejectsMalformedScenarioStructure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-malformed-scenario"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05}}
				""");
			Path scenario = Files.createDirectories(suite.resolve("ordinary-change"));
			write(scenario, "scenario.md", "## Scenario\n\nUpdate the configuration.\n");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies an assertion is a direct proposition rather than a pass-condition instruction.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Scenario has an invalid evaluator assertion.*")
	public void rejectsPassConditionAssertion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-pass-condition"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05}}
				""");
			Path scenario = Files.createDirectories(suite.resolve("ordinary-change"));
			write(scenario, "scenario.md", """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				Pass only if the configuration contains the requested change.
				""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies a scenario accepts the equivalent CommonMark closing-heading form.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsCommonMarkClosingHeadings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-commonmark"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05}}
				""");
			Path scenario = Files.createDirectories(suite.resolve("ordinary-change"));
			write(scenario, "scenario.md", """
				## Scenario ##

				Update the configuration.

				## Assertions ##

				### records-change ###

				The retained configuration contains the requested change.
				""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies the Runner receives the scenario request without evaluator-only assertions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void hidesScenarioAssertionsFromRunner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-scenario-input"));
			Path scenario = write(root, "scenario.md", """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				The agent retained the changed configuration.
				""");
			ObjectNode testCase = PromptEvaluation.newObject();
			testCase.put("scenario", true);

			String bridge = PromptEvaluation.bridgeInput(testCase, root, scenario);

			requireThat(bridge, "bridge").contains("Update the configuration.").
				doesNotContain("records-change").doesNotContain("retained the changed configuration");
		}
	}

	/**
	 * Verifies the scenario-suite identity changes when retained scenario input changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void fingerprintsScenarioSuiteContents() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-scenario-fingerprint"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05}}
				""");
			Path scenario = Files.createDirectories(suite.resolve("rules/example/ordinary-change"));
			Path scenarioFile = write(scenario, "scenario.md", """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				The agent retained the changed configuration.
				""");
			String before = PromptEvaluation.suiteFingerprint(suite);
			Files.writeString(scenarioFile, Files.readString(scenarioFile).replace("configuration", "policy"));

			requireThat(PromptEvaluation.suiteFingerprint(suite), "changedScenarioFingerprint").isNotEqualTo(before);
		}
	}

	/**
	 * Verifies an evaluation snapshot retains a complete scenario suite after its source changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void snapshotsScenarioDirectorySuite() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-scenario-snapshot"));
			Path suite = Files.createDirectories(root.resolve("suite"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05}}
				""");
			Path scenario = Files.createDirectories(suite.resolve("rules/example/ordinary-change"));
			Path scenarioFile = write(scenario, "scenario.md", """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				The agent retained the changed configuration.
				""");
			Path snapshot = SuiteSnapshot.copy(new SuiteSnapshot.Access()
			{
				@Override
				public void copyFixture(Path source, Path destination) throws IOException
				{
					EvaluationFiles.copyFixture(source, destination);
				}

				@Override
				public void delete(Path directory) throws IOException
				{
					TemporaryDirectories.deleteRecursively(directory);
				}
			}, suite, root.resolve("run.json"));
			Files.writeString(scenarioFile, "changed source\n");

			requireThat(snapshot, "snapshot").isDirectory();
			requireThat(Files.readString(snapshot.resolve("rules/example/ordinary-change/scenario.md")), "scenarioSnapshot").
				contains("Update the configuration.");
		}
	}

	/**
	 * Verifies the immutable suite store retains a scenario root as the record's suite value.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsScenarioSuiteRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-scenario-store"));
			Path suite = Files.createDirectories(root.resolve("suite"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05}}
				""");
			Path scenario = Files.createDirectories(suite.resolve("rules/example/ordinary-change"));
			write(scenario, "scenario.md", """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				The agent retained the changed configuration.
				""");

			Path retained = SuiteStore.retain(root.resolve("work"), suite);

			requireThat(retained, "retainedSuite").isDirectory();
			SuiteContract.validate(retained);
		}
	}

	/**
	 * Verifies scenario metadata supplies evaluator-only delivery declarations without exposing them to the Runner.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void validatesScenarioMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-scenario-metadata"));
			Files.writeString(suite.resolve("suite.json"), """
				{
				  "schema_version": 3,
				  "purpose": "certification",
				  "sprt": {"accept_pass_rate": 0.95, "reject_pass_rate": 0.85,
				    "false_accept_rate": 0.05, "false_reject_rate": 0.05},
				  "scenarios": {
				    "ordinary-change": {
				      "measures": ["application"],
				      "route_expectation": {"mode": "eager", "delivered": ["rules/example.md"]}
				    }
				  }
				}
				""");
			Path scenario = Files.createDirectories(suite.resolve("rules/example/ordinary-change"));
			write(scenario, "scenario.md", """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				The agent retained the changed configuration.
				""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies a scenario suite rejects unknown manifest metadata.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported scenario suite field.*")
	public void rejectsUnknownScenarioSuiteField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-unknown-suite-field"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","unrecognized":[],"sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05}}
				""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies scenario metadata rejects unknown evaluator declarations.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported scenario metadata field.*")
	public void rejectsUnknownScenarioMetadataField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path suite = cleanup.add(Files.createTempDirectory("prompt-evaluation-unknown-metadata-field"));
			Files.writeString(suite.resolve("suite.json"), """
				{"schema_version":3,"purpose":"certification","sprt":{"accept_pass_rate":0.95,
				"reject_pass_rate":0.85,"false_accept_rate":0.05,"false_reject_rate":0.05},
				"scenarios":{"ordinary-change":{"unrecognized":"value"}}}
				""");
			Path scenario = Files.createDirectories(suite.resolve("rules/example/ordinary-change"));
			write(scenario, "scenario.md", """
				## Scenario

				Update the configuration.

				## Assertions

				### records-change

				The agent retained the changed configuration.
				""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies validation accepts a suite only at its scenario-directory boundary.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Expected scenario-suite directory.*")
	public void requiresScenarioSuiteDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path manifest = cleanup.add(Files.createTempFile("prompt-evaluation-suite", ".json"));
			Files.writeString(manifest, "{}");

			SuiteContract.validate(manifest);
		}
	}

	/**
	 * Verifies the durable Prefer Reuse suite uses the current deterministic-check and evaluation-kind protocol.
	 *
	 * @throws IOException if the version-controlled suite is invalid
	 */
	@Test
	public void validatesPreferReuseSuite() throws IOException
	{
		Path suite = Path.of("..", "rules", "src", "test", "resources", "include", "common", "prefer-reuse").
			toAbsolutePath().normalize();
		requireThat(suite, "preferReuseSuite").isDirectory();

		SuiteContract.validate(suite);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path preflight = cleanup.add(Files.createTempDirectory("prefer-reuse-preflight"));
			try (ToolScope scope = new ToolScope())
			{
				PromptEvaluation.preflight(scope.json(), suite, preflight);
			}
			requireThat(preflight.resolve("toml-parser-library-check-control-0.json"), "knownGoodCheck").isRegularFile();
			requireThat(preflight.resolve("toml-parser-library-check-control-1.json"), "knownBadCheck").isRegularFile();
			Path controls = suite.resolve("toml-parser-library/deterministic-check");
			requireThat(Files.exists(controls.resolve("known-good/node_modules")), "knownGoodSourceDependencies").
				isFalse();
			requireThat(Files.exists(controls.resolve("known-bad/node_modules")), "knownBadSourceDependencies").
				isFalse();
		}
	}

	/**
	 * Verifies the durable Clear Writing suite assigns route facts and reader-facing assertions to separate owners.
	 *
	 * @throws IOException if the version-controlled suite is invalid
	 */
	@Test
	public void validatesWriteClearlySuite() throws IOException
	{
		Path suite = Path.of("..", "rules", "src", "test", "resources", "common", "write-clearly").
			toAbsolutePath().normalize();
		requireThat(suite, "writeClearlySuite").isDirectory();

		SuiteContract.validate(suite);
		String assertionVocabularySources = Files.readString(suite.resolve("suite.json"));
		requireThat(assertionVocabularySources, "writeClearlyAssertionVocabulary").doesNotContain(
			"reports-success-without-problem").doesNotContain("explains-observed-problem").
			doesNotContain("distinguishes-expected-result").doesNotContain("explains-user-consequence").
			doesNotContain("states-affected-actor").doesNotContain("states-next-action").
			doesNotContain("states-concrete-correction");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path preflight = cleanup.add(Files.createTempDirectory("write-clearly-preflight"));
			try (ToolScope scope = new ToolScope())
			{
				PromptEvaluation.preflight(scope.json(), suite, preflight);
			}
			requireThat(preflight.resolve("review-requires-narrower-change-check-control-0.json"),
				"reviewRouteControl").isRegularFile();
			requireThat(preflight.resolve("test-requires-repair-check-control-0.json"), "testRouteControl").
				isRegularFile();
			requireThat(preflight.resolve("completed-build-check-control-0.json"), "completedBuildRouteControl").
				isRegularFile();
		}
	}

	/**
	 * Verifies the Clear Writing rule does not expose a detail reserved to a concrete scenario fixture.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void auditsWriteClearlyGeneralization() throws IOException
	{
		Path suite = Path.of("..", "rules", "src", "test", "resources", "common", "write-clearly").
			toAbsolutePath().normalize();
		Path prompt = Path.of("..", "rules", "src", "main", "resources", "rules", "include", "common",
			"write-clearly", "unexpected-result.md").toAbsolutePath().normalize();

		PromptEvaluation.main(new String[]{"audit-generalization", suite.toString(), prompt.toString()});
	}

	/**
	 * Verifies the evaluator rejects a prompt artifact that copies a fixture-only detail into reader guidance.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*fixture-specific phrase.*empty value.*")
	public void rejectsFixtureSpecificPromptWording() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeGeneralizationSuite(root);
			Path prompt = write(root, "prompt.md", "Make validation reject empty values, then verify the change.\n");

			PromptEvaluation.main(new String[]{"audit-generalization", suite.toString(), prompt.toString()});
		}
	}

	/**
	 * Verifies the evaluation-input validator rejects a fixture-specific prompt leak.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*fixture-specific phrase.*")
	public void rejectsFixtureSpecificWording() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeGeneralizationSuite(root);
			Path prompt = write(root, "prompt.md", "Make validation reject empty values, then verify the change.\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = write(root, "fixture-runner", "#!/bin/sh\n");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			PromptEvaluation.validateEvaluationInputs(suite, prompt, "fixture", context, runner);
		}
	}

	/**
	 * Verifies an unrelated-domain example may teach the same reader decision without exposing a fixture detail.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsGeneralizedPromptWording() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeGeneralizationSuite(root);
			Path prompt = write(root, "prompt.md", "Make the parser reject a malformed header, then verify the change.\n");

			PromptEvaluation.main(new String[]{"audit-generalization", suite.toString(), prompt.toString()});
		}
	}

	/**
	 * Verifies a fixture detail may appear in reader guidance when a suite-owned external contract requires it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsAuthorizedFixtureWording() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeGeneralizationSuite(root);
			write(suite, "contract.md", "The external protocol reserves the empty value.\n");
			Path manifest = suite.resolve("suite.json");
			Files.writeString(manifest, Files.readString(manifest).replace("\"text\": \"empty value\"",
				"\"text\": \"empty value\", \"external_authority\": {\"path\": \"contract.md\"}"));
			Path prompt = write(root, "prompt.md", "Make validation reject empty values, then verify the change.\n");

			PromptEvaluation.main(new String[]{"audit-generalization", suite.toString(), prompt.toString()});
		}
	}

	/**
	 * Verifies a reloaded suite treats a retained failed Java check as a failed deterministic assertion.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsFailedJavaCheckAcrossSuiteReload() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-java-check-reload"));
			Path receipt = write(root, "deterministic-check.json", """
				{"assertions":{"checksRetainedEvidence":{"status":"failed","evidence":"failed assertion"}}}
				""");
			ObjectNode testCase = PromptEvaluation.newObject();
			testCase.put("java_check_class", "io.github.cowwoc.cat.evaluation.scenarios.OrdinaryChangeScenarioTest");

			requireThat(DeterministicChecks.failed(receipt, testCase, root.resolve("scenario.md")),
				"failed").isTrue();
		}
	}
}
