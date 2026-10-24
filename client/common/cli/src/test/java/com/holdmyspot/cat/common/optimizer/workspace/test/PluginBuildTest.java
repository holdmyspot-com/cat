package com.holdmyspot.cat.common.optimizer.workspace.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.workspace.PluginBuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

/**
 * Tests {@link PluginBuild}.
 */
public final class PluginBuildTest
{
	/**
	 * Verifies that a build runs in the worktree, with its program resolved against the worktree, and that the result
	 * holds its exit status and only the last lines of its output.
	 *
	 * @throws IOException if the fixture cannot be written or the build cannot run
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test
	public void runsInWorktreeAndKeepsTail() throws IOException, InterruptedException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path worktree = cleanup.add(Files.createTempDirectory("cat-plugin-build-")).toRealPath();
			Files.createDirectories(worktree.resolve("tools"));
			writeProgram(worktree.resolve("tools"));

			PluginBuild.Result result = PluginBuild.run(Yielding.executableFiles(), worktree,
				new TrialPlugin.Build(List.of("tools/build"), Path.of("out")));

			requireThat(result.exitStatus(), "exitStatus").isEqualTo(4);
			List<String> lines = result.tail().lines().toList();
			requireThat(lines.size(), "lines").isEqualTo(40);
			requireThat(lines.getLast().strip(), "last").isEqualTo("line 100");
			requireThat(result.tail(), "tail").doesNotContain("line 60\n");
			requireThat(worktree.resolve("out/built.txt"), "output").isRegularFile();
		}
	}

	/**
	 * Verifies that a build retries a start reporting the Linux busy-file diagnostic described by
	 * {@link ExecutableFiles}, then collects the started process's result.
	 *
	 * @throws IOException          if the fixture cannot be written or the build cannot run
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test
	public void retriesBusyBuildProgram() throws IOException, InterruptedException
	{
		AtomicInteger attempts = new AtomicInteger();
		AtomicInteger pauses = new AtomicInteger();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeHarnessCli process = new FakeHarnessCli(_ -> {}, 4))
		{
			Path worktree = cleanup.add(Files.createTempDirectory("cat-plugin-build-")).toRealPath();
			Files.createDirectories(worktree.resolve("tools"));
			writeProgram(worktree.resolve("tools"));
			process.emit("build output");
			process.exit();
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(
				pauses::incrementAndGet, new ManualClock(Instant.EPOCH), builder ->
				{
					if (attempts.getAndIncrement() == 0)
						throw new IOException("error=26, Text file busy");
					requireThat(builder.directory().toPath(), "directory").isEqualTo(worktree);
					return process;
				});

			PluginBuild.Result result = PluginBuild.run(executables, worktree,
				new TrialPlugin.Build(List.of("tools/build"), Path.of("out")));

			requireThat(attempts.get(), "attempts").isEqualTo(2);
			requireThat(pauses.get(), "pauses").isEqualTo(1);
			requireThat(result.exitStatus(), "exitStatus").isEqualTo(4);
			requireThat(result.tail(), "tail").isEqualTo("build output");
		}
	}

	/**
	 * Writes a build program that writes {@code out/built.txt} below the directory that it runs in, prints 100
	 * numbered lines and exits with status 4: a batch file on Windows, otherwise a shell script.
	 *
	 * @param directory the directory to write the program into
	 * @throws IOException if the program cannot be written
	 */
	private static void writeProgram(Path directory) throws IOException
	{
		if (Platform.current().isWindows())
		{
			Files.writeString(directory.resolve("build.cmd"), """
				@echo off\r
				if not exist out mkdir out\r
				echo built> out\\built.txt\r
				for /l %%i in (1,1,100) do echo line %%i\r
				exit /b 4\r
				""");
			return;
		}
		Path script = Files.writeString(directory.resolve("build"), """
			#!/bin/sh
			mkdir -p out
			echo built > out/built.txt
			i=1
			while [ "$i" -le 100 ]; do
			  echo "line $i"
			  i=$((i + 1))
			done
			exit 4
			""");
		requireThat(script.toFile().setExecutable(true, false), "executable").isTrue();
	}
}
