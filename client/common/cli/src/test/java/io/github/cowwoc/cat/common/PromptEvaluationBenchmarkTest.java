package io.github.cowwoc.cat.common;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeCandidateBundle;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFailingFormalBuildPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFailingFormalRunner;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeLifecycleSoakPrecondition;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests paired prompt-evaluation benchmark behavior.
 */
public final class PromptEvaluationBenchmarkTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationBenchmarkTest()
	{
	}

	/**
	 * Verifies a paired benchmark gives both candidates the same fixed evidence budget and records their identities.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void interleavesFixedPairedBenchmarkTrials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path baselineBundle = writeCandidateBundle(root.resolve("baseline"), "common/rule.md", "Baseline rule\n");
			Path candidateBundle = writeCandidateBundle(root.resolve("candidate"), "common/rule.md", "Changed rule\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			writeLifecycleSoakPrecondition(temporary, buildPlan, runner);

			ObjectNode result = PromptEvaluation.benchmarkPair(temporary, suite, baselineBundle, candidateBundle,
				"fixture", context, runner, buildPlan, temporary.resolve("paired-benchmark"), 1, 2, evaluationRunner);

			ObjectNode comparison = (ObjectNode) result.path("comparison");
			requireThat(comparison.path("comparable").asBoolean(), "comparable").isTrue();
			requireThat(comparison.path("baseline").path("completed_trials").asInt(), "baselineTrials").isEqualTo(1);
			requireThat(comparison.path("candidate").path("completed_trials").asInt(), "candidateTrials").isEqualTo(1);
			requireThat(comparison.path("baseline").path("candidate_bundle_sha256").asString(), "baselineBundle").
				isNotBlank();
			requireThat(comparison.path("candidate").path("candidate_bundle_sha256").asString(), "candidateBundle").
				isNotBlank();
			requireThat(comparison.path("baseline").path("benchmark_elapsed_millis").asLong(), "baselineElapsed").
				isGreaterThanOrEqualTo(0L);
			requireThat(comparison.path("candidate").path("benchmark_elapsed_millis").asLong(), "candidateElapsed").
				isGreaterThanOrEqualTo(0L);
			ObjectNode baselineRun = (ObjectNode) scope.json().readTree(Path.of(result.path("baseline").path("run").
				asString()).toFile());
			ObjectNode candidateRun = (ObjectNode) scope.json().readTree(Path.of(result.path("candidate").path("run").
				asString()).toFile());
			requireThat(baselineRun.path("benchmark_started_at_epoch_millis").asLong(), "baselineBenchmarkStart").
				isGreaterThan(0L);
			requireThat(candidateRun.path("benchmark_started_at_epoch_millis").asLong(), "candidateBenchmarkStart").
				isGreaterThan(0L);

			ObjectNode resumed = PromptEvaluation.benchmarkPair(temporary, suite, baselineBundle, candidateBundle,
				"fixture", context, runner, buildPlan, temporary.resolve("paired-benchmark"), 1, 2, evaluationRunner);
			requireThat(resumed.path("assembly").path("resumed").asBoolean(), "resumed").isTrue();
			requireThat(resumed.path("comparison").path("baseline").path("completed_trials").asInt(),
				"resumedBaselineTrials").isEqualTo(1);
			requireThat(resumed.path("comparison").path("candidate").path("completed_trials").asInt(),
				"resumedCandidateTrials").isEqualTo(1);
			ObjectNode resumedBaselineRun = (ObjectNode) scope.json().readTree(Path.of(resumed.path("baseline").path("run").
				asString()).toFile());
			requireThat(resumedBaselineRun.path("benchmark_elapsed_millis").asLong(), "retainedElapsed").
				isEqualTo(baselineRun.path("benchmark_elapsed_millis").asLong());
		}
	}

	/**
	 * Verifies a benchmark measures a prompt without changing candidate-promotion state.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void benchmarksWithoutPromotion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path candidateBundle = writeCandidateBundle(root, "common/rule.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			writeLifecycleSoakPrecondition(temporary, buildPlan, runner);

			ObjectNode result = PromptEvaluation.benchmark(temporary, suite, candidateBundle, "fixture", context, runner,
				buildPlan, temporary.resolve("benchmark"), 1, evaluationRunner);

			requireThat(result.path("decision").path("overall_decision").asString(), "benchmarkDecision").
				isEqualTo("FAIL");
			Path run = Path.of(result.path("run").asString());
			ObjectNode record = (ObjectNode) scope.json().readTree(run.toFile());
			requireThat(record.path("mode").asString(), "benchmarkMode").isEqualTo("benchmark");
			Path plan = Path.of(record.path("execution_plan").asString());
			ObjectNode planContents = (ObjectNode) scope.json().readTree(plan.toFile());
			Path artifact = Path.of(planContents.path("identity").path("runner_artifact").asString());
			requireThat(Files.readString(artifact.resolve("rules/common/rule.md")), "deliveredCandidate").
				isEqualTo("Prompt artifact\n");
			requireThat(record.path("candidate_bundle_sha256").asString(), "candidateBundleFingerprint").isNotBlank();
			ObjectNode report = (ObjectNode) scope.json().readTree(output(() -> PromptEvaluation.run(
				new String[]{"report", run.toString()})));
			requireThat(report.path("candidate_bundle_sha256").asString(), "reportedCandidateBundleFingerprint").
				isEqualTo(record.path("candidate_bundle_sha256").asString());
			requireThat(report.path("candidate_identity_sha256").asString(), "reportedCandidateIdentity").
				isEqualTo(record.path("candidate_identity_sha256").asString());
			requireThat(report.path("benchmark_elapsed_millis").asLong(), "reportedBenchmarkElapsed").
				isGreaterThanOrEqualTo(0L);
			requireThat(Files.exists(temporary.resolve("candidate-state.json")), "candidateState").isFalse();
		}
	}

	/**
	 * Verifies a second caller cannot start or resume the same retained paired benchmark while its first caller still
	 * owns the pair-level lifecycle lock.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*already owned by another evaluator.*")
	public void refusesConcurrentPairedBenchmarkOwner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path baselineBundle = writeCandidateBundle(root.resolve("baseline"), "common/rule.md", "Baseline rule\n");
			Path candidateBundle = writeCandidateBundle(root.resolve("candidate"), "common/rule.md", "Changed rule\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path retained = root.resolve(".cat/work/temp/paired-benchmark");
			Files.createDirectories(retained);
			Path marker = retained.resolve(".cat-prompt-evaluation-pair-active");
			try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(marker,
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
				java.nio.channels.FileLock lock = channel.lock())
			{
				requireThat(lock.isValid(), "pairLock").isTrue();
				PromptEvaluation.benchmarkPair(root.resolve(".cat/work/temp"), suite, baselineBundle, candidateBundle,
					"fixture", context, runner, buildPlan, retained, 1, 2);
			}
		}
	}

	/**
	 * Verifies a validator does not overwrite a testcase that a later deterministic failure has already canceled.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preservesCanceledBenchmarkAttempt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			String edgeConditionAssertion = "\"assertions\":[{\"id\":\"edge-result\",\"evaluation_kind\":\"semantic\"," +
				"\"instruction\":\"Verify the edge-condition result.\"}]";
			String deterministicEdgeCondition = "\"deterministic_check\":{\"artifact\":\"edge-condition-check.sh\"," +
				"\"command\":[\"sh\",\"${check}\"],\"assertions\":[\"edge-result\"]},\"assertions\":[{\"id\":" +
				"\"edge-result\",\"evaluation_kind\":\"deterministic\",\"instruction\":\"Verify the edge-condition result.\"}]";
			Path manifest = suite.resolve("suite.json");
			String suiteContents = Files.readString(manifest);
			Files.writeString(manifest, suiteContents.replace(edgeConditionAssertion, deterministicEdgeCondition));
			Path check = write(root, "edge-condition-check.sh", "printf '%s' " +
				"'{\"assertions\":{\"edge-result\":{\"status\":\"failed\",\"evidence\":\"fixture failure\"}}}'\n");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();
			Path baselineBundle = writeCandidateBundle(root.resolve("baseline"), "common/rule.md", "Baseline rule\n");
			Path candidateBundle = writeCandidateBundle(root.resolve("candidate"), "common/rule.md", "Changed rule\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			writeLifecycleSoakPrecondition(temporary, buildPlan, runner);

			ObjectNode result = PromptEvaluation.benchmarkPair(temporary, suite, baselineBundle, candidateBundle,
				"fixture", context, runner, buildPlan, temporary.resolve("paired-benchmark"), 1, 1, evaluationRunner);

			ObjectNode comparison = (ObjectNode) result.path("comparison");
			requireThat(comparison.path("baseline").path("completed_trials").asInt(), "baselineTrials").isEqualTo(1);
			requireThat(comparison.path("candidate").path("completed_trials").asInt(), "candidateTrials").isEqualTo(1);
		}
	}

	/**
	 * Verifies a benchmark stops before a model turn when its declared candidate cannot be selected by the suite route.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*was not delivered.*")
	public void rejectsUndeliveredBenchmarkCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path candidateBundle = writeCandidateBundle(root, "common/not-routed.md", "Unrouted candidate\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			writeLifecycleSoakPrecondition(temporary, buildPlan, runner);

			PromptEvaluation.benchmark(temporary, suite, candidateBundle, "fixture", context, runner, buildPlan,
				temporary.resolve("benchmark"), 1);
		}
	}

	/**
	 * Verifies a benchmark can require the evaluated candidate's primary rule even when its historical canonical path
	 * differs from the current suite's ordinary route path.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void benchmarksHistoricalCandidateRoute() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path candidateBundle = writeCandidateBundle(root, "common/historical-rule.md", "Historical prompt artifact\n",
				Map.of("common/default-rules.md", "Read `historical-rule.md` before task work.\n"),
				Map.of("common/rule.md", "common/historical-rule.md"));
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFailingFormalRunner(root);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			writeLifecycleSoakPrecondition(temporary, buildPlan, runner);

			ObjectNode result = PromptEvaluation.benchmark(temporary, suite, candidateBundle, "fixture", context, runner,
				buildPlan, temporary.resolve("benchmark"), 1, evaluationRunner);

			Path run = Path.of(result.path("run").asString());
			ObjectNode record = (ObjectNode) scope.json().readTree(run.toFile());
			String route = record.path("assembled_prompt_route").toString();
			requireThat(route, "assembledRoute").contains("common/historical-rule.md").doesNotContain(
				"\"relative_path\":\"common/rule.md\"");
		}
	}
}
