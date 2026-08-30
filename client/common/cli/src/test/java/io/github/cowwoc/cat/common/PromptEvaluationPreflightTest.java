package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.SuiteContract;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.runArtifact;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests deterministic and semantic preflight checks before evaluator work starts.
 */
public final class PromptEvaluationPreflightTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationPreflightTest()
	{
	}

	/**
	 * Verifies the evaluation-input validator rejects an invalid suite.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsInvalidSuite() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = write(root, "not-a-directory", "{}\n");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = write(root, "fixture-runner", """
				#!/bin/sh
				case "$1" in
				  --verify-artifact) printf '%s\\n' "$2" ;;
				esac
				""");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			PromptEvaluation.validateEvaluationInputs(suite, prompt, "fixture", context, runner);
		}
	}

	/**
	 * Verifies a suite snapshot retains deterministic-check control projects needed by later preflight checks.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsCheckControlProjectSnapshot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "check.mjs", "process.stdout.write('{}');\n");
			write(suite, "check-good/package.json", "{\"name\":\"good\"}\n");
			write(suite, "check-bad/package.json", "{\"name\":\"bad\"}\n");
			write(suite, "check-good-evidence.txt", "[]\n");
			write(suite, "check-bad-evidence.txt", "[]\n");
			String deterministicCheck = """
				"route_preparation":["common/rule.md"],"measures":["delivery","application"],
				"deterministic_check":{"artifact":"check.mjs","command":["node","${check}","${project}","${executor_result}"],
				"assertions":["result"],"self_tests":[
				{"project":"check-good","evidence_events":"check-good-evidence.txt","expected_assertions":{"result":"passed"}},
				{"project":"check-bad","evidence_events":"check-bad-evidence.txt","expected_assertions":{"result":"failed"}}]}
				""".stripTrailing();
			String contents = Files.readString(suite.resolve("suite.json")).replace("""
				"requested-change": {
				 "measures":["organic_selection","application"]""", """
				"requested-change": {
				 %s""".formatted(deterministicCheck));
			Files.writeString(suite.resolve("suite.json"), contents);
			Files.writeString(suite.resolve("requested-change/scenario.md"), """
				## Scenario

				Implement the requested behavior.

				## Assertions

				### outcome

				The retained execution evidence contains the requested outcome.
				""");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator context\n");
			Path run = root.resolve("run.json");

			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});

			Path suiteArtifact = runArtifact(run, "suite");
			requireThat(suiteArtifact.resolve("check-good/package.json"), "knownGoodControl").isRegularFile();
			requireThat(suiteArtifact.resolve("check-bad/package.json"), "knownBadControl").isRegularFile();
			requireThat(suiteArtifact.resolve("check-good-evidence.txt"), "knownGoodEvidence").isRegularFile();
			requireThat(suiteArtifact.resolve("check-bad-evidence.txt"), "knownBadEvidence").isRegularFile();
		}
	}

	/**
	 * Verifies the evaluator rejects a broken deterministic check before it builds or starts a harness.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsBrokenCheckPreflight() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			configureRequestedCheck(suite, "acceptance-check.sh");
			write(suite, "check-good/input.txt", "good\n");
			write(suite, "check-bad/input.txt", "bad\n");
			Path check = write(suite, "acceptance-check.sh", """
				#!/bin/sh
				printf '%s' '{"assertions":{"result":{"status":"passed","evidence":"always passes"}}}'
				""");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();

			try
			{
				try (ToolScope scope = new ToolScope())
				{
					PromptEvaluation.preflight(scope.json(), suite, root.resolve("preflight"));
				}
				throw new AssertionError("Expected check self-test failure");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("known-bad").contains("expected failed").
					contains("observed passed");
			}
		}
	}

	/**
	 * Verifies a deterministic check accepts its known-good control and rejects its minimally changed known-bad control
	 * before delegated work begins.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsVerifiedCheckControls() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			configureRequestedCheck(suite, "check.sh");
			write(suite, "check-good/input.txt", "good\n");
			write(suite, "check-bad/input.txt", "bad\n");
			Path check = write(suite, "check.sh", """
				#!/bin/sh
				if grep -q good "$1/input.txt"; then
				  printf '%s' '{"assertions":{"result":{"status":"passed","evidence":"expected input"}}}'
				else
				  printf '%s' '{"assertions":{"result":{"status":"failed","evidence":"changed input"}}}'
				fi
				""");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();

			try (ToolScope scope = new ToolScope())
			{
				PromptEvaluation.preflight(scope.json(), suite, root.resolve("preflight"));
			}

			requireThat(root.resolve("preflight/requested-change-check-control-0.json"), "knownGoodReceipt").
				isRegularFile();
			requireThat(root.resolve("preflight/requested-change-check-control-1.json"), "knownBadReceipt").
				isRegularFile();
		}
	}

	/**
	 * Verifies a mechanically decided assertion cannot be assigned to a check while the suite calls it semantic.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Scenario must not describe an assertion owned by its deterministic check.*")
	public void rejectsCheckThatOwnsSemanticAssertion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path check = write(root, "check.sh", "printf '%s' " +
				"'{\"assertions\":{\"result\":{\"status\":\"passed\",\"evidence\":\"fixture\"}}}'\n");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();
			addRequestedScenarioMetadata(suite, """
				"deterministic_check": {
				 "artifact": "check.sh",
				 "command": ["sh", "${check}"],
				 "assertions": ["result"]
				}""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies a semantic control must isolate one reader-facing assertion instead of treating every assertion as
	 * passing.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Semantic control must fail exactly one semantic assertion.*")
	public void rejectsSemanticControlWithoutOneFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "control-response.md", "A controlled reader-facing response.\n");
			addRequestedScenarioMetadata(suite, """
				"semantic_controls": [
				 {"id": "all-pass", "response": "control-response.md",
				  "expected_assertions": {"result": "passed"}}
				]""");

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies a suite rejects unsupported metadata rather than silently changing the evaluation plan.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Unsupported scenario suite field: unsupported-setting.*")
	public void rejectsUnknownSuiteMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			String contents = Files.readString(suite.resolve("suite.json")).replace("  \"purpose\": \"certification\",\n",
				"  \"purpose\": \"certification\",\n  \"unsupported-setting\": 3,\n");
			Files.writeString(suite.resolve("suite.json"), contents);

			SuiteContract.validate(suite);
		}
	}

	/**
	 * Verifies a durable suite declares the product each testcase measures.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*must declare one or more measured products.*")
	public void rejectsTestcaseWithoutMeasuredProducts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			String contents = Files.readString(suite.resolve("suite.json")).replace(
				"\"measures\":[\"organic_selection\",\"application\"],", "");
			Files.writeString(suite.resolve("suite.json"), contents);

			PromptEvaluation.run(new String[]{"validate-suite", suite.toString()});
		}
	}

	/**
	 * Verifies a certification suite cannot inject a direct route.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*A certification testcase cannot declare route_preparation.*")
	public void rejectsDirectRouteInCertificationSuite() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			addRequestedScenarioMetadata(suite, "\"route_preparation\": [\"common/rule.md\"]");

			PromptEvaluation.run(new String[]{"validate-suite", suite.toString()});
		}
	}

	/**
	 * Verifies an organic suite explains how to provide its missing router owner.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*must declare routing_owner as a relative Markdown path.*common/default-rules\\.md.*")
	public void explainsMissingOrganicRoutingOwner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Files.writeString(suite.resolve("suite.json"), Files.readString(suite.resolve("suite.json")).replace(
				"  \"routing_owner\": \"common/default-rules.md\",\n", ""));

			PromptEvaluation.run(new String[]{"validate-suite", suite.toString()});
		}
	}

	/**
	 * Adds a command-owned deterministic result alongside the scenario-owned semantic result.
	 *
	 * @param suite the scenario-suite root
	 * @param artifact the suite-relative checker path
	 * @throws IOException if the fixture manifest cannot be updated
	 */
	private static void configureRequestedCheck(Path suite, String artifact) throws IOException
	{
		addRequestedScenarioMetadata(suite, """
			"deterministic_check": {
			 "artifact": "%s",
			 "command": ["sh", "${check}", "${project}"],
			 "assertions": ["result"],
			 "self_tests": [
			  {"project": "check-good", "evidence_events": "check-good-events.json",
			   "expected_assertions": {"result": "passed"}},
			  {"project": "check-bad", "evidence_events": "check-bad-events.json",
			   "expected_assertions": {"result": "failed"}}
			 ]
			}""".formatted(artifact));
		write(suite, "check-good-events.json", "[]\n");
		write(suite, "check-bad-events.json", "[]\n");
		Files.writeString(suite.resolve("requested-change/scenario.md"), """
			## Scenario

			Implement the requested behavior.

			## Assertions

			### outcome

			The retained execution evidence contains the requested outcome.
			""");
	}

	/**
	 * Adds current evaluator metadata to the requested scenario without duplicating semantic assertions in JSON.
	 *
	 * @param suite the scenario-suite root
	 * @param metadata one complete metadata property
	 * @throws IOException if the fixture manifest does not have the expected scenario declaration
	 */
	private static void addRequestedScenarioMetadata(Path suite, String metadata) throws IOException
	{
		Path manifest = suite.resolve("suite.json");
		String contents = Files.readString(manifest);
		String target = """
			"requested-change": {
			     "measures""";
		String replacement = """
			"requested-change": {
			     %s,
			     "measures""".formatted(metadata);
		String updated = contents.replace(target, replacement);
		if (updated.equals(contents))
			throw new IOException("Fixture suite is missing requested-change metadata: " + manifest);
		Files.writeString(manifest, updated);
	}
}
