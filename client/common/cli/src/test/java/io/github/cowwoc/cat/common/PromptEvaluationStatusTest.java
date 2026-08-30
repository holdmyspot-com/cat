package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.Lifecycle;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFailingFormalBuildPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFormalRunner;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeGeneralizationSuite;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.awaitFile;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the observable terminal status of {@link PromptEvaluation}.
 */
public final class PromptEvaluationStatusTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationStatusTest()
	{
	}

	/**
	 * Verifies a caller sees an evaluator as active while semantic-reviewer calibration is running.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws InterruptedException if the evaluator worker is interrupted
	 * @throws ExecutionException if the evaluator worker fails
	 */
	@Test
	public void reportsActiveDuringCalibration() throws IOException, InterruptedException, ExecutionException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			write(suite, "control-response.md", "A controlled reader-facing response.\n");
			Path suiteManifest = suite.resolve("suite.json");
			ObjectNode suiteMetadata = PromptEvaluation.object(PromptEvaluation.read(suiteManifest), "suite metadata");
			ObjectNode scenarios = PromptEvaluation.object(suiteMetadata.path("scenarios"), "scenario metadata");
			scenarios.withObject("requested-change").putArray("semantic_controls").addObject().
				put("id", "result-missing").put("response", "control-response.md").putObject(
				"expected_assertions").put("result", "failed");
			PromptEvaluation.write(suiteManifest, suiteMetadata);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			Path calibrationStarted = root.resolve("calibration-started");
			Path releaseCalibration = root.resolve("release-calibration");
			Path plan = writeStaticExecutionPlan(root);
			PromptEvaluation.run(new String[]{"bind-plan", run.toString(), plan.toString()});
			Path workspace = root.resolve("workspace");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			java.util.concurrent.atomic.AtomicBoolean calibrationCompleted = new java.util.concurrent.atomic.AtomicBoolean();
			HarnessEvaluationRunner runner = request ->
			{
				if (calibrationCompleted.compareAndSet(false, true))
				{
					Files.createFile(calibrationStarted);
					try
					{
						awaitFile(releaseCalibration);
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while waiting to release semantic-control calibration", e);
					}
				}
				return staticEvaluationResult(requests, request, "passed");
			};
			try (ExecutorService evaluator = Executors.newSingleThreadExecutor())
			{
				Future<Void> evaluation = evaluator.submit(() ->
				{
					PromptEvaluation.runUntilTerminal(run, workspace, plan, 1, runner);
					return null;
				});
				try
				{
					awaitFile(calibrationStarted);
					String status = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(),
						workspace.toString(), "--summary"}));
					requireThat(status, "status").contains("\"evaluation_state\":\"running\"").
						contains("\"current_phase\":\"verifying_semantic_controls\"");
				}
				finally
				{
					Files.createFile(releaseCalibration);
					evaluation.get();
				}
			}
		}
	}

	/**
	 * Verifies the controller publishes ownership before its runner build starts.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws InterruptedException if the evaluator worker is interrupted
	 * @throws ExecutionException if the evaluator worker fails
	 */
	@Test
	public void reportsActiveControllerDuringBuild() throws IOException, InterruptedException, ExecutionException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeGeneralizationSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "passed");
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path build = root.resolve("build-formal-fixture.sh");
			Path started = root.resolve("build-started");
			Path release = root.resolve("release-build");
			Files.writeString(build, "printf started > '" + started + "'; while [ ! -f '" + release +
				"' ]; do sleep 1; done;\n" + Files.readString(build));
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");

			try (ExecutorService evaluator = Executors.newSingleThreadExecutor())
			{
				Future<Void> evaluation = evaluator.submit(() ->
				{
					PromptEvaluation.advance(temporary, candidateState, suite, prompt, "fixture", context, runner,
						buildPlan, temporary.resolve("check"), 1);
					return null;
				});
				try
				{
					awaitFile(started);
					String status = output(() -> PromptEvaluation.run(new String[]{"goal-status",
						candidateState.toString()}));

					requireThat(status, "status").contains("\"active_controller\"").
						contains("\"current_phase\":\"running_regression_check\"").
						contains("\"evaluation_identity\"").contains("\"suite_sha256\"").
						contains("\"prompt_artifact_sha256\"");
				}
				finally
				{
					Files.createFile(release);
					evaluation.get();
				}
			}
		}
	}

	/**
	 * Verifies an active evaluation reports the observable state of its live harness run.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsLiveHarnessActivity() throws IOException
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

			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Files.createFile(workspace.resolve(".cat-prompt-evaluation-active"));
			Path runnerRoot = workspace.resolve("smoke/trial-7/configuration-presence/executor-project/runner-runs");
			io.github.cowwoc.cat.common.runner.HarnessRunManager manager =
				new io.github.cowwoc.cat.common.runner.HarnessRunManager(runnerRoot, java.time.Clock.systemUTC());
			io.github.cowwoc.cat.common.runner.HarnessRun liveRun = manager.create("codex");
			manager.markWorking(liveRun);

			try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
				workspace.resolve(".cat-prompt-evaluation-active"), java.nio.file.StandardOpenOption.WRITE);
				java.nio.channels.FileLock lock = channel.lock())
			{
				requireThat(lock.isValid(), "activeLock").isTrue();
				String result = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(),
					workspace.toString()}));

				requireThat(result, "result").contains("\"evaluation_state\":\"running\"").
					contains("\"phase\":\"working\"").contains("\"activity\":\"processing_submitted_turn\"").
					contains("\"case_id\":\"configuration-presence\"");
			}
		}
	}

	/**
	 * Verifies a caller can observe live work without parsing every retained runner manifest.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsCompactLiveStatus() throws IOException
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

			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Files.createFile(workspace.resolve(".cat-prompt-evaluation-active"));
			Path runnerRoot = workspace.resolve("batch-1/trial-7/configuration-presence/executor-project/runner-runs");
			io.github.cowwoc.cat.common.runner.HarnessRunManager manager =
				new io.github.cowwoc.cat.common.runner.HarnessRunManager(runnerRoot, java.time.Clock.systemUTC());
			io.github.cowwoc.cat.common.runner.HarnessRun liveRun = manager.create("codex");
			manager.markWorking(liveRun);

			try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
				workspace.resolve(".cat-prompt-evaluation-active"), java.nio.file.StandardOpenOption.WRITE);
				java.nio.channels.FileLock lock = channel.lock())
			{
				requireThat(lock.isValid(), "activeLock").isTrue();
				Lifecycle.publishActiveStatus(workspace, "starting_testcase");
				Lifecycle.publishActiveStatus(workspace, "executing_testcase");
				String result = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(),
					workspace.toString(), "--summary"}));

				requireThat(result, "result").contains("\"evaluation_state\":\"running\"").
					contains("\"owner_active\":true").contains("\"status_revision\":2").
					contains("\"current_phase\":\"executing_testcase\"").contains("\"workers_total\":1").
					contains("\"current_phase_elapsed_millis\":").
					contains("\"completed_phase_durations_millis\":{\"starting_testcase\":").
					contains("\"state\":\"running\"").
					contains("\"activity\":\"processing_submitted_turn\"").
					doesNotContain("\"workers\":[");
			}
		}
	}

	/**
	 * Verifies a caller in a separate sandbox can observe a freshly published evaluator heartbeat even when its file-lock
	 * view cannot observe the evaluator's live lock.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsPublishedHeartbeatAcrossSandboxes() throws IOException
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

			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			long now = System.currentTimeMillis();
			write(workspace, ".cat-prompt-evaluation-status.json", """
				{
				  "revision": 3,
				  "phase": "waiting_for_harness",
				  "phase_started_at_epoch_millis": %d,
				  "last_heartbeat_epoch_millis": %d,
				  "phase_durations_millis": {},
				  "active_workers": []
				}
				""".formatted(now, now));

			String result = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(),
				workspace.toString(), "--summary"}));
			requireThat(result, "result").contains("\"evaluation_state\":\"running\"").
				contains("\"owner_active\":true").contains("\"current_phase\":\"waiting_for_harness\"");
		}
	}

	/**
	 * Verifies the public status command follows a paired benchmark's active nested workspace instead of treating its
	 * inactive preparation workspace as an abandoned evaluator.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsActiveNestedBenchmarkWorkspace() throws IOException
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

			Path preparedWorkspace = root.resolve("workspace");
			Path activeWorkspace = preparedWorkspace.resolve("pair-1");
			Files.createDirectories(activeWorkspace);
			Files.writeString(activeWorkspace.resolve(".cat-prompt-evaluation-evidence"),
				run.toAbsolutePath().normalize() + System.lineSeparator());
			long now = System.currentTimeMillis();
			write(activeWorkspace, ".cat-prompt-evaluation-status.json", """
				{
				  "revision": 1,
				  "phase": "benchmarking",
				  "phase_started_at_epoch_millis": %d,
				  "last_heartbeat_epoch_millis": %d,
				  "phase_durations_millis": {},
				  "active_workers": []
				}
				""".formatted(now, now));
			try (ToolScope scope = new ToolScope())
			{
				ObjectNode record = (ObjectNode) scope.json().readTree(run.toFile());
				record.put("workspace", preparedWorkspace.toString());
				record.put("active_workspace", activeWorkspace.toString());
				Files.writeString(run, scope.json().writeValueAsString(record));
			}

			String result = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(), "--summary"}));
			requireThat(result, "result").contains("\"evaluation_state\":\"running\"").
				contains("\"current_phase\":\"benchmarking\"");
		}
	}

	/**
	 * Verifies publishing live status reads the runner manifest without entering the harness-owned home directory.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void publishesStatusWithoutHarnessHome() throws IOException
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

			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Files.createFile(workspace.resolve(".cat-prompt-evaluation-active"));
			Path runnerRoot = workspace.resolve("batch-1/trial-7/configuration-presence/executor-project/runner-runs");
			io.github.cowwoc.cat.common.runner.HarnessRunManager manager =
				new io.github.cowwoc.cat.common.runner.HarnessRunManager(runnerRoot, java.time.Clock.systemUTC());
			io.github.cowwoc.cat.common.runner.HarnessRun liveRun = manager.create("codex");
			manager.markWorking(liveRun);
			Path home = liveRun.directory().resolve("home");
			Files.setPosixFilePermissions(home, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));

			try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
				workspace.resolve(".cat-prompt-evaluation-active"), java.nio.file.StandardOpenOption.WRITE);
				java.nio.channels.FileLock lock = channel.lock())
			{
				requireThat(lock.isValid(), "activeLock").isTrue();
				Lifecycle.publishActiveStatus(workspace, "executing_testcase");
			}
			finally
			{
				Files.setPosixFilePermissions(home, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
			}
		}
	}

	/**
	 * Verifies a completed reviewer-control operation is terminal rather than abandoned.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsTerminalReviewerControls() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path record = root.resolve("record");
			Files.createDirectories(record);
			Files.writeString(record.resolve(".cat-prompt-evaluation-record"), record.resolve("run.json").
				toAbsolutePath().normalize() + System.lineSeparator());
			Path run = record.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "fixture", "model-a",
				context.toString(), run.toString()});
			Path workspace = Files.createDirectories(record.resolve("workspace"));
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Path receipt = write(workspace, "semantic-control-check.json", "{\"status\":\"passed\"}\n");

			ObjectNode controls;
			try (ToolScope scope = new ToolScope())
			{
				controls = PromptEvaluation.recordReviewerControls(scope.json(), run, workspace, true, null);
			}

			requireThat(controls.path("status").asString(), "controlStatus").isEqualTo("passed");
			requireThat(controls.path("semantic_control_receipt").asString(), "controlReceipt").
				isEqualTo(workspace.relativize(receipt).toString());
			ObjectNode status = Lifecycle.status(run, workspace, true);
			requireThat(status.path("evaluation_state").asString(), "evaluationState").isEqualTo("terminal");
			requireThat(status.path("last_completed_stage").asString(), "lastCompletedStage").
				isEqualTo("reviewer_controls_completed");
			requireThat(status.path("next_action").asString(), "nextAction").isEqualTo("finalize_evidence");
		}
	}

	/**
	 * Verifies candidate status selects the newest retained checkpoint rather than the order of handwritten state
	 * entries.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsLatestCandidateCheckpoint() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path earlierFormal = write(root, "earlier-formal.json", "{\"decision\":{\"overall_decision\":\"FAIL\"}}\n");
			Path laterFormal = write(root, "later-formal.json", "{\"decision\":{\"overall_decision\":\"FAIL\"}}\n");
			Path review = write(root, "review-result.json", "{}\n");
			Path check = write(root, "check.json", """
				{"regression_check":{"status":"passed","completed_at_epoch_millis":100}}
				""");
			Path state = write(root, "candidate-state.json", """
				{
				  "schema_version": 2,
				  "promotions": [{"check_run":"%s","promoted_at_epoch_millis":200}],
				  "latest_check":"%s",
				  "formal_failures": [
				    {"run":"%s","observed_at_epoch_millis":400},
				    {"run":"%s","observed_at_epoch_millis":300}
				  ],
				  "recurrence_reviews": [{"status":"completed","result":"%s","completed_at_epoch_millis":350}]
				}
				""".formatted(check, check, laterFormal, earlierFormal, review));

			String result = output(() -> PromptEvaluation.run(new String[]{"goal-status", state.toString()}));

			requireThat(result, "result").contains("\"checkpoint_kind\":\"formal-failure\"").
				contains("\"current_record\":\"" + laterFormal + "\"").
				contains("\"next_stage\":\"classify-retained-formal-failure\"");
		}
	}

	/**
	 * Verifies status uses the newest retained transition and does not require superseded historical evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void ignoresSupersededUnavailableCheckpoint() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path current = write(root, "current-formal.json", "{\"decision\":{\"overall_decision\":\"FAIL\"}}\n");
			Path unavailable = root.resolve("missing-review-result.json");
			Path state = write(root, "candidate-state.json", """
				{
				  "schema_version": 2,
				  "formal_failures": [{"run":"%s","observed_at_epoch_millis":400}],
				  "recurrence_reviews": [{"status":"completed","result":"%s","completed_at_epoch_millis":300}]
				}
				""".formatted(current, unavailable));

			String result = output(() -> PromptEvaluation.run(new String[]{"goal-status", state.toString()}));

			requireThat(result, "result").contains("\"checkpoint_kind\":\"formal-failure\"").
				contains("\"current_record\":\"" + current + "\"").
				contains("\"unavailable_historical_records\"").contains("recurrence-review").
				contains(unavailable.toString());
		}
	}

	/**
	 * Verifies goal status rejects a passing regression-check receipt after one of the receipt's current inputs changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsStaleCandidateCheckpoint() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "passed");
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "passed");
			PromptEvaluation.regressionCheck(temporary, candidateState, suite, prompt, "fixture", context, runner,
				buildPlan, temporary.resolve("check"), 1, evaluationRunner);
			Files.writeString(prompt, "changed prompt\n");

			String result = output(() -> PromptEvaluation.run(new String[]{"goal-status", candidateState.toString()}));

			requireThat(result, "result").contains("\"gate_status\":\"stale\"").
				contains("\"next_stage\":\"run-regression-check\"").contains(prompt.toString());
		}
	}

/**
	 * Verifies a changed execution plan makes a passing regression-check receipt stale.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsChangedExecutionPlanAsStale() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "passed");
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "passed");
			PromptEvaluation.regressionCheck(temporary, candidateState, suite, prompt, "fixture", context, runner,
				buildPlan, temporary.resolve("check"), 1, evaluationRunner);
			ObjectNode state = PromptEvaluation.object(PromptEvaluation.read(candidateState), "candidate state");
			Path check = Path.of(PromptEvaluation.text(state, "latest_check", candidateState));
			ObjectNode record = PromptEvaluation.object(PromptEvaluation.read(check), "regression-check receipt");
			Path executionPlan = Path.of(PromptEvaluation.text(record, "execution_plan", check));
			Files.writeString(executionPlan, "changed execution plan\n");

			String result = output(() -> PromptEvaluation.run(new String[]{"goal-status", candidateState.toString()}));

			requireThat(result, "result").contains("\"gate_status\":\"stale\"").
				contains("\"next_stage\":\"run-regression-check\"").contains("Execution plan changed");
		}
	}

/**
	 * Verifies a changed packaged runner runtime makes a passing regression-check receipt stale.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsChangedRunnerRuntimeAsStale() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeFormalRunner(root, "passed");
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");
			Path candidateState = root.resolve(".cat/work/candidate-state.json");
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "passed");
			PromptEvaluation.regressionCheck(temporary, candidateState, suite, prompt, "fixture", context, runner,
				buildPlan, temporary.resolve("check"), 1, evaluationRunner);
			ObjectNode state = PromptEvaluation.object(PromptEvaluation.read(candidateState), "candidate state");
			Path check = Path.of(PromptEvaluation.text(state, "latest_check", candidateState));
			ObjectNode record = PromptEvaluation.object(PromptEvaluation.read(check), "regression-check receipt");
			Path executionPlan = Path.of(PromptEvaluation.text(record, "execution_plan", check));
			ObjectNode plan = PromptEvaluation.object(PromptEvaluation.read(executionPlan), "execution plan");
			ObjectNode identity = PromptEvaluation.object(plan.path("identity"), "execution identity");
			Path runtime = Path.of(PromptEvaluation.text(identity, "runner_artifact", executionPlan));
			Files.writeString(runtime.resolve("client/runner.txt"), "changed packaged runtime\n");

			String result = output(() -> PromptEvaluation.run(new String[]{"goal-status", candidateState.toString()}));

			requireThat(result, "result").contains("\"gate_status\":\"stale\"").
				contains("\"next_stage\":\"run-regression-check\"").contains("Runner runtime manifest does not match");
		}
	}

	/**
	 * Verifies a goal cannot publish a handwritten checkpoint that conflicts with candidate state.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*earlier-formal\\.json.*later-formal\\.json.*goal-status.*")
	public void rejectsStaleGoalCheckpoint() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path earlierFormal = write(root, "earlier-formal.json", "{\"decision\":{\"overall_decision\":\"FAIL\"}}\n");
			Path laterFormal = write(root, "later-formal.json", "{\"decision\":{\"overall_decision\":\"FAIL\"}}\n");
			Path state = write(root, "candidate-state.json", """
				{"schema_version":2,"formal_failures":[
				  {"run":"%s","observed_at_epoch_millis":100},
				  {"run":"%s","observed_at_epoch_millis":200}
				]}
				""".formatted(earlierFormal, laterFormal));
			Path goal = write(root, "goal.md", """
				#### Current tuning checkpoint

				- Current retained run: %s
				- Next required stage: certify-promoted
				""".formatted(earlierFormal));

			PromptEvaluation.run(new String[]{"goal-status", state.toString(), goal.toString()});
		}
	}
}
