package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.Recovery;

import io.github.cowwoc.cat.common.evaluation.ExecutionScheduler;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.output;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.record;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.staticEvaluationResult;
import static io.github.cowwoc.cat.common.PromptEvaluationExecutionFixtures.writeStaticExecutionPlan;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;

/**
 * Tests evaluator recovery and terminal-interruption behavior.
 */
public final class PromptEvaluationRecoveryTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationRecoveryTest()
	{
	}

	/**
	 * Verifies a caller cannot terminate workers while the evaluator still owns their workspace.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Cannot terminate workers while their evaluator owns the workspace.*")
	public void refusesActiveEvaluatorTermination() throws IOException
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

			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Files.createFile(workspace.resolve(".cat-prompt-evaluation-active"));
			try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
				workspace.resolve(".cat-prompt-evaluation-active"), java.nio.file.StandardOpenOption.WRITE);
				java.nio.channels.FileLock lock = channel.lock())
			{
				requireThat(lock.isValid(), "activeLock").isTrue();
				PromptEvaluation.run(new String[]{"terminate", run.toString(), workspace.toString()});
			}
		}
	}

	/**
	 * Verifies a recovery attempt that cannot acquire the active evaluator claim preserves that evaluator's marker.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preservesActiveRecoveryMarker() throws IOException
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

			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Path marker = workspace.resolve(".cat-prompt-evaluation-active");
			Files.createFile(marker);
			try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(marker,
				java.nio.file.StandardOpenOption.WRITE); java.nio.channels.FileLock lock = channel.lock())
			{
				try
				{
					PromptEvaluation.run(new String[]{"recover", run.toString(), workspace.toString()});
					throw new AssertionError("Expected active evaluator recovery to fail");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains("already owned by another evaluator");
				}
				requireThat(lock.isValid(), "activeLock").isTrue();
				requireThat(marker, "marker").isRegularFile();
			}
		}
	}

	/**
	 * Verifies a retained running harness is not reported as safely resumable after its evaluator exits.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsOrphanedHarnessActivity() throws IOException
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

			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Path runnerRoot = workspace.resolve("batch-1/trial-7/configuration-presence/executor-project/runner-runs");
			io.github.cowwoc.cat.common.runner.HarnessRunManager manager =
				new io.github.cowwoc.cat.common.runner.HarnessRunManager(runnerRoot, java.time.Clock.systemUTC());
			manager.markWorking(manager.create("codex"));

			String result = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(), workspace.toString()}));

			requireThat(result, "result").contains("\"evaluation_state\":\"orphaned\"").
				contains("\"next_action\":\"recover_interruption\"").
				contains("\"recovery_mode\":\"record_infrastructure_interruption\"");
		}
	}

	/**
	 * Verifies recovery does not disturb an orphaned worker that its runner still owns.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Cannot recover an evaluation while a runner owns an orphaned worker.*")
	public void preservesLiveOrphanedWorker() throws IOException
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

			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Path runnerRoot = workspace.resolve("batch-1/trial-7/configuration-presence/executor-project/runner-runs");
			io.github.cowwoc.cat.common.runner.HarnessRunManager manager =
				new io.github.cowwoc.cat.common.runner.HarnessRunManager(runnerRoot, java.time.Clock.systemUTC());
			io.github.cowwoc.cat.common.runner.HarnessRun worker = manager.create("codex");
			manager.markWorking(worker);
			try (io.github.cowwoc.cat.common.runner.HarnessRunLock workerLock =
				io.github.cowwoc.cat.common.runner.HarnessRunLock.acquire(worker.directory().resolve(".lock")))
			{
				requireThat(workerLock.isHeld(), "workerLock").isTrue();
				String result = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(),
					workspace.toString()}));
				requireThat(result, "result").contains("\"evaluation_state\":\"orphaned\"").
					contains("\"next_action\":\"wait_for_orphaned_workers\"").
					contains("\"owner_active\":true");
				PromptEvaluation.run(new String[]{"recover", run.toString(), workspace.toString()});
			}
		}
	}

	/**
	 * Verifies a completed evaluation tells a caller to finalize its owned evidence instead of resuming work.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsTerminalRecoveryAction() throws IOException
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
			for (int trial = 1; trial <= 88; ++trial)
				for (String caseId : new String[]{"requested-change", "unrelated-request", "edge-condition"})
					record(run, caseId, trial, "passed");
			output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());

			String result = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(), workspace.toString()}));

			requireThat(result, "result").contains("\"evaluation_state\":\"terminal\"").
				contains("\"last_completed_stage\":\"terminal_decision\"").
				contains("\"next_action\":\"finalize_evidence\"");
		}
	}

	/**
	 * Verifies an interrupted evaluation identifies the last evidence it actually recorded before directing terminal
	 * recovery.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void directsPreparedRunToRecovery() throws IOException
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
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());

			String result = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(), workspace.toString()}));

			requireThat(result, "result").contains("\"evaluation_state\":\"abandoned\"").
				contains("\"last_completed_stage\":\"prepared_run\"").
				contains("\"next_action\":\"recover_interruption\"");
		}
	}

	/**
	 * Verifies recovery records an interrupted evaluator as an infrastructure failure when no evaluator or worker
	 * remains to finish the prepared evaluation.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsAbandonedEvaluationFailure() throws IOException
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
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());

			String result = output(() -> PromptEvaluation.run(new String[]{"recover", run.toString(),
				workspace.toString()}));

			requireThat(result, "result").contains("\"overall_decision\":\"INFRASTRUCTURE_FAILED\"");
			String status = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(),
				workspace.toString(), "--summary"}));
			requireThat(status, "status").contains("\"evaluation_state\":\"terminal\"").
				contains("\"next_action\":\"finalize_evidence\"");
		}
	}

	/**
	 * Verifies recovery records the interruption even when an immutable input changed after the evaluator stopped.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recoversAfterInputChanges() throws IOException
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
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Files.writeString(run.resolveSibling("run.json.prompt"), "Changed after evaluator exit\n");

			String result = output(() -> PromptEvaluation.run(new String[]{"recover", run.toString(),
				workspace.toString()}));

			requireThat(result, "result").contains("\"overall_decision\":\"INFRASTRUCTURE_FAILED\"");
		}
	}

	/**
	 * Verifies recovery records a terminal infrastructure failure when retained evidence changed after the evaluator
	 * stopped.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recoversAfterRetainedEvidenceChanges() throws IOException
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
			record(run, "requested-change", 1, "failed");
			Path workspace = root.resolve("workspace");
			Files.createDirectories(workspace);
			Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
				System.lineSeparator());
			Files.writeString(root.resolve("requested-change-1-raw.json"), "changed after evaluator exit\n");

			String result = output(() -> PromptEvaluation.run(new String[]{"recover", run.toString(),
				workspace.toString()}));

			requireThat(result, "result").contains("\"overall_decision\":\"INFRASTRUCTURE_FAILED\"");
			requireThat(Files.readString(run.resolveSibling("run.json.interrupted")), "recoveryDiagnostic").
				contains("Evidence integrity failed during recovery").contains("Raw result changed");
		}
	}

	/**
	 * Verifies an infrastructure interruption stops the benchmark without treating it as prompt behavior evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void failsFastAfterInfrastructureInterruption() throws IOException
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
			record(run, "requested-change", 1, "blocked");

			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));
			assertTrue(decision.contains("\"overall_decision\":\"INFRASTRUCTURE_FAILED\""));
			assertTrue(decision.contains("\"complete_suite_runs\":0"));
			assertTrue(decision.contains("\"infrastructure_failure\":true"));
		}
	}

	/**
	 * Verifies a process interruption remains terminal even when its mutable execution inputs later change.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void closesInterruptedEvaluation() throws IOException
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
			Files.writeString(run.resolveSibling(run.getFileName() + ".interrupted"), "interrupted\n");
			Files.writeString(prompt, "Changed after interruption\n");

			String decision = output(() -> PromptEvaluation.run(new String[]{"decide", run.toString()}));

			assertTrue(decision.contains("\"overall_decision\":\"INFRASTRUCTURE_FAILED\""));
		}
	}

	/**
	 * Verifies an evaluator cancellation does not leave a stopped harness recorded as running.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void terminalizesInactiveCanceledHarnessRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path testcase = root.resolve("trial-1/unrelated-documentation");
			Path runRoot = testcase.resolve("executor-project/runner-runs");
			io.github.cowwoc.cat.common.runner.HarnessRunManager manager =
				new io.github.cowwoc.cat.common.runner.HarnessRunManager(runRoot, java.time.Clock.systemUTC());
			io.github.cowwoc.cat.common.runner.HarnessRun run = manager.create("codex");
			manager.initialize(run, root, root, "fixture", List.of("fixture-runner"));
			manager.markWorking(run);
			Path manifest = run.directory().resolve("run.json");
			String contents = Files.readString(manifest, StandardCharsets.UTF_8).replace(
				"\"ownerPid\": " + ProcessHandle.current().pid(), "\"ownerPid\": 999999999");
			Files.writeString(manifest, contents, StandardCharsets.UTF_8);

			ExecutionScheduler.terminalizeCanceledHarnessRuns(testcase,
				"A completed suite made later work unnecessary.");

			String terminal = Files.readString(manifest, StandardCharsets.UTF_8);
			requireThat(terminal, "manifest").contains("\"state\": \"failed\"").
				contains("Evaluator canceled this work");
		}
	}

	/**
	 * Verifies recovery resumes an interrupted batch from its retained execution plan instead of discarding its completed
	 * executor receipt.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recoversOwnerlessWorker() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path check = write(root, "acceptance-check.sh", "printf '%s' " +
				"'{\"assertions\":{\"deterministic\":{\"status\":\"passed\",\"evidence\":\"fixture check\"}}}' " +
				"> \"$1\"\n");
			requireThat(check.toFile().setExecutable(true), "checkExecutable").isTrue();
			String suiteContents = Files.readString(suite.resolve("suite.json")).replace(
				"\"assertions\":[{\"id\":\"result\",\"evaluation_kind\":\"semantic\",\"instruction\":" +
					"\"Verify the requested result.\"}]}",
				"\"deterministic_check\":{\"artifact\":\"acceptance-check.sh\",\"command\":[\"sh\",\"${check}\"," +
					"\"${output}\"],\"assertions\":[\"deterministic\"]},\"assertions\":[{\"id\":\"deterministic\"," +
					"\"evaluation_kind\":\"deterministic\",\"instruction\":\"Verify the deterministic result.\"},{\"id\":" +
					"\"result\",\"evaluation_kind\":\"semantic\",\"instruction\":\"Verify the requested result.\"}]}");
			Files.writeString(suite.resolve("suite.json"), suiteContents);
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path run = root.resolve("run.json");
			PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
				context.toString(), run.toString()});
			PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"});
			Path workspace = root.resolve("evaluation-workspace");
			Path caseWorkspace = workspace.resolve("batch-1/trial-1/requested-change");
			Path retainedProject = caseWorkspace.resolve("executor-project/retained");
			Path rawResult = write(caseWorkspace, "executor-result.txt", "completed executor output\n");
			write(retainedProject, "implementation.txt", "completed executor project\n");
			Path receipt = write(caseWorkspace, "executor.json", "{\"retained_project\":\"%s\",\"raw_result\":\"%s\"}".
				formatted(retainedProject, rawResult));
			write(workspace, ".cat-prompt-evaluation-evidence", run + "\n");
			Path runnerRoot = caseWorkspace.resolve("executor-project/runner-runs");
			io.github.cowwoc.cat.common.runner.HarnessRunManager manager =
				new io.github.cowwoc.cat.common.runner.HarnessRunManager(runnerRoot, java.time.Clock.systemUTC());
			io.github.cowwoc.cat.common.runner.HarnessRun worker = manager.create("codex");
			manager.markWorking(worker);
			Path manifest = worker.directory().resolve("run.json");
			Files.writeString(manifest, Files.readString(manifest).replaceFirst("\"ownerPid\": [0-9]+",
				"\"ownerPid\": 9223372036854775807"));
			Path plan = writeStaticExecutionPlan(root);
			PromptEvaluation.run(new String[]{"bind-plan", run.toString(), plan.toString()});
			List<HarnessEvaluationRequest> requests = new CopyOnWriteArrayList<>();
			HarnessEvaluationRunner evaluationRunner = request -> staticEvaluationResult(requests, request, "failed");
			String status = output(() -> PromptEvaluation.run(new String[]{"status", run.toString(),
				workspace.toString(), "--summary"}));
			requireThat(status, "status").contains("\"evaluation_state\":\"orphaned\"").
				contains("\"next_action\":\"recover_interruption\"").
				contains("\"recovery_mode\":\"resume_incomplete_batch\"");

			String result = Recovery.recoverInterruptedEvaluation(run, workspace, evaluationRunner).
				toString();

			requireThat(result, "result").contains("\"overall_decision\":\"FAIL\"").
				doesNotContain("\"overall_decision\":\"INFRASTRUCTURE_FAILED\"");
			requireThat(Files.readString(rawResult), "rawResult").contains("completed executor output");
			requireThat(Files.readString(receipt), "receipt").contains(retainedProject.toString());
		}
	}
}
