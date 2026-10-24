package com.holdmyspot.cat.common.optimizer.workspace.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.workspace.PluginBuild;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * Tests build requests and failure results without starting a build program.
 */
public final class PluginBuildTest
{
	/**
	 * Verifies the worktree and resolved build program reach the process starter, and a failed build reports its
	 * exit status and the last 40 output lines.
	 *
	 * @throws IOException          if the build cannot run
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test
	public void runsInWorktreeAndKeepsTail() throws IOException, InterruptedException
	{
		// Requirement: R-W12
		Path worktree = Path.of("/worktree");
		if (Platform.current().isWindows())
			worktree = Path.of("C:/worktree");
		AtomicReference<ProcessBuilder> request = new AtomicReference<>();
		try (FakeHarnessCli process = new FakeHarnessCli(_ -> {}, 4))
		{
			process.emit(IntStream.rangeClosed(1, 100).mapToObj(i -> "line " + i).toArray(String[]::new));
			process.exit();
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(
				() -> {}, new ManualClock(Instant.EPOCH), builder ->
				{
					request.set(builder);
					return process;
				});

			PluginBuild.Result result = PluginBuild.run(executables, worktree,
				new TrialPlugin.Build(List.of("tools/build", "--release"), Path.of("out")));

			requireThat(request.get().directory().toPath(), "directory").isEqualTo(worktree);
			requireThat(request.get().command(), "command").isEqualTo(
				List.of(worktree.resolve("tools/build").toString(), "--release"));
			requireThat(request.get().redirectErrorStream(), "combinedOutput").isTrue();
			requireThat(result.exitStatus(), "exitStatus").isEqualTo(4);
			String expected = String.join("\n", IntStream.rangeClosed(61, 100).
				mapToObj(i -> "line " + i).toList());
			requireThat(result.tail(), "tail").isEqualTo(expected);
		}
	}

	/**
	 * Verifies that a briefly busy build program still produces its build result.
	 *
	 * @throws IOException          if the build cannot run
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test
	public void retriesBusyBuildProgram() throws IOException, InterruptedException
	{
		AtomicBoolean busy = new AtomicBoolean(true);
		try (FakeHarnessCli process = new FakeHarnessCli(_ -> {}, 4))
		{
			process.emit("build output");
			process.exit();
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(
				() -> {}, new ManualClock(Instant.EPOCH), _ ->
				{
					if (busy.getAndSet(false))
						throw new IOException("error=26, Text file busy");
					return process;
				});

			Path worktree = Path.of("/worktree");
			if (Platform.current().isWindows())
				worktree = Path.of("C:/worktree");

			PluginBuild.Result result = PluginBuild.run(executables, worktree,
				new TrialPlugin.Build(List.of("tools/build"), Path.of("out")));

			requireThat(result.exitStatus(), "exitStatus").isEqualTo(4);
			requireThat(result.tail(), "tail").isEqualTo("build output");
		}
	}
}
