package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.SuiteContract;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.ensureRunnerRuntimeManifest;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.nativeAgentMessage;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.record;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.runArtifact;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests preparation of an evaluator transaction from a resolved runner profile.
 */
public final class PromptEvaluationPreparationTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationPreparationTest()
	{
	}

	/**
	 * Verifies evaluation initialization retains a scenario-suite root for later trial preparation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void initializesScenarioDirectorySuite() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-scenario-initialization"));
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
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator context\n");
			Path run = root.resolve("run.json");

			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "fixture", "model-a",
				context.toString(), run.toString()});

			Path retainedSuite = runArtifact(run, "suite");
			requireThat(retainedSuite, "retainedSuite").isDirectory();
			SuiteContract.validate(retainedSuite);
		}
	}

	/**
	 * Verifies a second evaluation cannot overwrite a persistent baseline record.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*already exists.*")
	public void rejectsExistingRunRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path run = root.resolve("run.json");
			String[] arguments = {"begin", suite.toString(), prompt.toString(), "codex", "model-a", context.toString(),
				run.toString()};

			PromptEvaluation.run(arguments);
			PromptEvaluation.run(arguments);
		}
	}

	/**
	 * Verifies an evaluation retains the prompt content that was present when the run began.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void snapshotsPromptAtEvaluationStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Original prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});

			Files.writeString(prompt, "Rebuilt prompt artifact\n");
			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			assertTrue(Files.readString(runArtifact(run, "prompt_artifact")).contains("Original prompt artifact"));
			assertTrue(decision.contains("\"overall_decision\":\"RUNNING\""));
		}
	}

	/**
	 * Verifies an evaluation retains its original suite when the mutable source suite later changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsChangedSuiteSnapshot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path manifest = suite.resolve("suite.json");
			Files.writeString(manifest, Files.readString(manifest).replace("edge-condition", "new-edge-condition"));
			record(run, "requested-change", 1, "failed");

			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));

			assertTrue(decision.contains("\"overall_decision\":\"FAIL\""));
			assertTrue(Files.readString(runArtifact(run, "suite").resolve("suite.json")).contains("edge-condition"));
		}
	}

	/**
	 * Verifies an evaluation retains its original fixture when the mutable source fixture later changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsChangedPromptTestFixtureSnapshot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Files.writeString(suite.resolve("requested-change/project/project.txt"), "changed fixture\n");
			record(run, "requested-change", 1, "passed");

			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));

			assertTrue(decision.contains("\"overall_decision\":\"RUNNING\""));
			String snapshotFixture = Files.readString(runArtifact(run, "suite").resolve(
				"requested-change/project/project.txt"));
			assertTrue(snapshotFixture.contains("requested behavior fixture"));
		}
	}

	/**
	 * Verifies a persistent prompt-test fixture is copied into an unused trial workspace.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preparesFreshPromptTestFixture() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path trial = root.resolve("trial");

			String result = output(() -> PromptEvaluation.run(new String[]{"prepare-trial", suite.toString(),
				"requested-change", trial.toString()}));

			assertTrue(result.contains("\"case_id\":\"requested-change\""));
			assertTrue(Files.readString(trial.resolve("project.txt")).contains("requested behavior fixture"));
		}
	}

	/**
	 * Verifies a validator receives the executor's retained project instead of the unchanged trial fixture.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preparesRetainedValidatorEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path retainedProject = root.resolve("retained-project");
			write(retainedProject, "implementation.txt", "executor change\n");
			write(retainedProject, ".git/config", "private metadata\n");
			write(retainedProject, "node_modules/parser/index.js", "generated dependency\n");
			Path rawResult = write(root, "raw.jsonl", nativeAgentMessage("ROUTES READY") + System.lineSeparator() +
				nativeAgentMessage("Final reader response") + System.lineSeparator());
			Path validatorProject = root.resolve("validator-project");

			String result = output(() -> PromptEvaluation.run(new String[]{"prepare-validator",
				retainedProject.toString(), rawResult.toString(), validatorProject.toString()}));

			assertTrue(result.contains("\"project\":\"" + validatorProject + "\""));
			assertTrue(Files.readString(validatorProject.resolve("implementation.txt")).contains("executor change"));
			assertTrue(Files.readString(validatorProject.resolve("executor-output.jsonl")).contains("ROUTES READY"));
			requireThat(Files.readString(validatorProject.resolve("executor-response.md")), "finalResponse").
				isEqualTo("Final reader response");
			assertTrue(Files.notExists(validatorProject.resolve(".git")));
			assertTrue(Files.notExists(validatorProject.resolve("node_modules")));
		}
	}

	/**
	 * Verifies preparation creates the complete evaluation transaction from a runner's resolved default profile.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preparesAnEvaluationTransaction() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator profile and allowed context\n");
			Path artifact = Files.createDirectories(root.resolve("runner-artifact"));
			Files.writeString(artifact.resolve("runner.txt"), "original runner artifact\n");
			Path packagedRule = write(artifact, "rules/include/common/example.md", "Original rule guidance\n");
			Files.createSymbolicLink(artifact.resolve("runner-link"), Path.of("runner.txt"));
			Path bundledRunner = write(artifact, "client/bin/fixture-runner", "#!/bin/sh\nexit 0\n");
			requireThat(bundledRunner.toFile().setExecutable(true), "bundledRunnerExecutable").isTrue();
			ensureRunnerRuntimeManifest(artifact);
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path runner = write(root, "fixture-runner", """
				#!/bin/sh
				case "$1" in
				  --resolve-profile) printf 'harness=fixture\\nowner=default\\nmodel=model-a\\neffort=medium\\n' ;;
				  --verify-dependency-cache) printf '%%s\\n' '%s' ;;
				  --verify-artifact) printf '%%s\\n' "$2" ;;
				esac
				""".formatted(cache));
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();

			Path temporary = root.resolve(".cat/work/temp");
			Path retained = temporary.resolve("prepared");
			ObjectNode result = PromptEvaluation.prepare(temporary, suite, prompt, "fixture", context, runner, artifact,
				retained);

			Path workspace = Path.of(result.path("workspace").asString());
			assertTrue(Files.isDirectory(workspace));
			assertTrue(workspace.startsWith(retained));
			assertTrue(Files.isRegularFile(Path.of(result.path("run").asString())));
			Path plan = Path.of(result.path("execution_plan").asString());
			assertTrue(Files.isRegularFile(plan));
			String contents = Files.readString(plan);
			assertEquals(result.path("dependency_cache").asString(), cache.toString());
			Path work = temporary.getParent();
			Path evaluationArtifact;
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode planContents = (ObjectNode) scope.json().readTree(contents);
				evaluationArtifact = Path.of(planContents.path("identity").path("runner_artifact").asString());
				requireThat(planContents.path("identity").path("runner_launcher").asString(), "runnerLauncher").
					isEqualTo(evaluationArtifact.resolve("client/bin/fixture-runner").toString());
				requireThat(planContents.path("executor").path("install_cat").asBoolean(), "executorInstallsCat").
					isTrue();
				requireThat(planContents.path("validator").path("install_cat").asBoolean(), "validatorInstallsCat").
					isFalse();
				requireThat(planContents.path("validator_batch").path("install_cat").asBoolean(),
					"batchValidatorInstallsCat").isFalse();
				requireThat(planContents.path("validator_batch").path("read_only_evidence").asBoolean(),
					"batchValidatorReadOnlyEvidence").isFalse();
				requireThat(planContents.path("executor").has("command"), "executorCommand").isFalse();
				requireThat(planContents.path("validator").has("command"), "validatorCommand").isFalse();
				requireThat(planContents.path("validator_batch").has("command"), "batchValidatorCommand").isFalse();
			}
			assertTrue(evaluationArtifact.startsWith(work.resolve("prompt-evaluation-runner-artifacts")));
			assertEquals(Files.readString(evaluationArtifact.resolve("runner.txt")), "original runner artifact\n");
			assertTrue(Files.isSymbolicLink(evaluationArtifact.resolve("runner-link")));
			assertEquals(Files.readSymbolicLink(evaluationArtifact.resolve("runner-link")), Path.of("runner.txt"));
			ObjectNode repeated = PromptEvaluation.prepare(temporary, suite, prompt, "fixture", context, runner, artifact,
				temporary.resolve("prepared-again"));
			ObjectNode repeatedPlan;
			try (ToolScope scope = new ToolScope())
			{
				repeatedPlan = (ObjectNode) scope.json().readTree(Path.of(repeated.path("execution_plan").asString()).toFile());
				ObjectNode firstRun = (ObjectNode) scope.json().readTree(Path.of(result.path("run").asString()).toFile());
				ObjectNode repeatedRun = (ObjectNode) scope.json().readTree(Path.of(repeated.path("run").asString()).toFile());
				Path promptArtifact = Path.of(firstRun.path("prompt_artifact").asString());
				Path contextArtifact = Path.of(firstRun.path("execution_context").asString());
				Path suiteArtifact = Path.of(firstRun.path("suite").asString());
				assertTrue(promptArtifact.startsWith(work.resolve("prompt-evaluation-input-artifacts")));
				assertTrue(contextArtifact.startsWith(work.resolve("prompt-evaluation-input-artifacts")));
				assertTrue(suiteArtifact.startsWith(work.resolve("prompt-evaluation-suite-artifacts")));
				assertEquals(repeatedRun.path("prompt_artifact").asString(), promptArtifact.toString());
				assertEquals(repeatedRun.path("execution_context").asString(), contextArtifact.toString());
				assertEquals(repeatedRun.path("suite").asString(), suiteArtifact.toString());
			}
			requireThat(repeatedPlan.path("identity").path("runner_artifact").asString(), "repeatedArtifact").
				isEqualTo(evaluationArtifact.toString());
			Files.writeString(packagedRule, "Revised rule guidance\n");
			ObjectNode promptRevised = PromptEvaluation.prepare(temporary, suite, prompt, "fixture", context, runner,
				artifact, temporary.resolve("prepared-prompt-revised"));
			Path revisedArtifact;
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode revisedPlan = (ObjectNode) scope.json().readTree(Path.of(promptRevised.path(
					"execution_plan").asString()).toFile());
				revisedArtifact = Path.of(revisedPlan.path("identity").path("runner_artifact").asString());
			}
			requireThat(revisedArtifact, "revisedArtifact").isNotEqualTo(evaluationArtifact);
			requireThat(Files.readString(revisedArtifact.resolve("rules/include/common/example.md")), "revisedRule").
				isEqualTo("Revised rule guidance\n");
			Files.writeString(artifact.resolve("runner.txt"), "rebuilt runner artifact\n");
			assertEquals(Files.readString(evaluationArtifact.resolve("runner.txt")), "original runner artifact\n");
			ObjectNode status = PromptEvaluation.status(Path.of(result.path("run").asString()), workspace, true);
			requireThat(status.path("evaluation_state").asString(), "evaluationState").isEqualTo("abandoned");
			requireThat(status.path("next_action").asString(), "nextAction").isEqualTo("recover_interruption");
		}
	}
}
