package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Tests the Claude runner's maintenance commands without starting a harness process.
 */
public final class MaintenanceTest
{
	/**
	 * Creates the test.
	 */
	public MaintenanceTest()
	{
	}

	/**
	 * Verifies that pruning through the CLI removes terminal runtime state and preserves project evidence.
	 *
	 * @throws IOException if fixture or maintenance I/O fails
	 */
	@Test
	public void prunesTerminalRuntime() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"maintenance-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("claude");
			manager.markCompleted(run);

			ClaudeRunnerCli.executeMaintenance(new String[]{"--prune-terminal-runtimes", "--run-root", root.toString()},
				root, Map.of(), Yielding.waiting(), new PrintStream(OutputStream.nullOutputStream(), true, UTF_8));

			requireThat(Files.notExists(run.directory().resolve("home")), "runtimePruned").isTrue();
			requireThat(run.directory().resolve("project"), "evidence").isDirectory();
		}
	}
}
