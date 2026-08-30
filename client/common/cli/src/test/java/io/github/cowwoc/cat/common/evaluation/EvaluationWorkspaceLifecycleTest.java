package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests live evaluation-workspace ownership and terminal cleanup.
 */
public final class EvaluationWorkspaceLifecycleTest
{
	/**
	 * Creates the test fixture.
	 */
	public EvaluationWorkspaceLifecycleTest()
	{
	}

	/**
	 * Verifies a workspace receipt refuses a second evaluation record.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Evaluation workspace belongs to a different run.*")
	public void refusesReceiptForDifferentRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluation-workspace-lifecycle"));
			EvaluationWorkspaceLifecycle lifecycle = new EvaluationWorkspaceLifecycle(new TestAccess());

			lifecycle.prepare(root.resolve("first.json"), root.resolve("workspace"));
			lifecycle.prepare(root.resolve("second.json"), root.resolve("workspace"));
		}
	}

	/**
	 * Verifies a failed operation retains its diagnostic before rethrowing the failure.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsInterruptionBeforeRethrowing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluation-workspace-lifecycle"));
			Path run = root.resolve("run.json");
			Path workspace = root.resolve("workspace");
			TestAccess access = new TestAccess();
			EvaluationWorkspaceLifecycle lifecycle = new EvaluationWorkspaceLifecycle(access);
			lifecycle.prepare(run, workspace);

			try
			{
				lifecycle.run(run, workspace, "executing", context ->
				{
					throw new IOException("runner stopped");
				}, result -> true, failure ->
				{
					throw failure;
				});
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").isEqualTo("runner stopped");
			}

			requireThat(access.interruption, "interruption").isEqualTo("runner stopped" + System.lineSeparator());
			requireThat(access.terminalPublications, "terminalPublications").isZero();
		}
	}

	/**
	 * Verifies a terminal result clears both the active marker and record pointer.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void clearsTerminalWorkspace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluation-workspace-lifecycle"));
			Path run = root.resolve("run.json");
			Path workspace = root.resolve("workspace");
			TestAccess access = new TestAccess();
			EvaluationWorkspaceLifecycle lifecycle = new EvaluationWorkspaceLifecycle(access);
			lifecycle.prepare(run, workspace);

			ObjectNode result = lifecycle.run(run, workspace, "executing", context -> scope.json().createObjectNode(),
				ignored -> true, failure ->
				{
					throw failure;
				});

			requireThat(result, "result").isNotNull();
			requireThat(access.terminalPublications, "terminalPublications").isEqualTo(1);
			requireThat(access.clearedActiveWorkspace, "clearedActiveWorkspace").isTrue();
			requireThat(Files.exists(EvaluationWorkspaceLifecycle.activeMarker(workspace)), "activeMarker").isFalse();
		}
	}

	/**
	 * Verifies a resumable nonterminal result retains its workspace ownership state.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsNonterminalWorkspaceResumable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("evaluation-workspace-lifecycle"));
			Path run = root.resolve("run.json");
			Path workspace = root.resolve("workspace");
			TestAccess access = new TestAccess();
			EvaluationWorkspaceLifecycle lifecycle = new EvaluationWorkspaceLifecycle(access);
			lifecycle.prepare(run, workspace);

			ObjectNode result = lifecycle.run(run, workspace, "executing", context ->
				scope.json().createObjectNode().put("overall_decision", "RUNNING"),
				candidate -> !candidate.path("overall_decision").asString().equals("RUNNING"), failure ->
				{
					throw failure;
				});

			requireThat(result.path("overall_decision").asString(), "decision").isEqualTo("RUNNING");
			requireThat(access.terminalPublications, "terminalPublications").isZero();
			requireThat(access.clearedActiveWorkspace, "clearedActiveWorkspace").isFalse();
			requireThat(Files.isRegularFile(EvaluationWorkspaceLifecycle.activeMarker(workspace)), "activeMarker").isTrue();
		}
	}

	private static final class TestAccess implements EvaluationWorkspaceLifecycle.Access
	{
		private int terminalPublications;
		private boolean clearedActiveWorkspace;
		private String interruption = "";

		@Override
		public void prepareEmptyWorkspace(Path workspaceRoot) throws IOException
		{
			Files.createDirectories(workspaceRoot);
		}

		@Override
		public void recordActiveWorkspace(Path runPath, Path workspaceRoot)
		{
		}

		@Override
		public void clearActiveWorkspace(Path runPath, Path workspaceRoot)
		{
			clearedActiveWorkspace = true;
		}

		@Override
		public EvaluationWorkspaceLifecycle.Heartbeat startHeartbeat(Path workspaceRoot, Lock activeLock)
		{
			return new EvaluationWorkspaceLifecycle.Heartbeat()
			{
				@Override
				public void verify()
				{
				}

				@Override
				public void close()
				{
				}
			};
		}

		@Override
		public void publishActiveStatus(Path workspaceRoot, String phase)
		{
		}

		@Override
		public void publishTerminalStatus(Path workspaceRoot)
		{
			++terminalPublications;
		}

		@Override
		public void writeInterruption(Path runPath, String diagnostic)
		{
			interruption = diagnostic;
		}

		@Override
		public void interruptActiveProcesses()
		{
		}
	}
}
