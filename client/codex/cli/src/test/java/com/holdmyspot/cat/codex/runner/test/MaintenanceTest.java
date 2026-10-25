package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexRunnerCli;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Tests the Codex runner's maintenance commands without starting a harness process.
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
	 * Verifies the launcher entry point recognizes pruning and removes terminal runtime state.
	 *
	 * @throws IOException if fixture or maintenance I/O fails
	 */
	@Test
	public void mainPrunesTerminalRuntime() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"maintenance-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("codex");
			manager.markCompleted(run);

			CodexRunnerCli.main(new String[]{"--prune-terminal-runtimes", "--run-root", root.toString()});

			requireThat(Files.notExists(run.directory().resolve("home")), "runtimePruned").isTrue();
			requireThat(run.directory().resolve("project"), "evidence").isDirectory();
		}
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
			Path temporaryRoot = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporaryRoot, "maintenance-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("codex");
			Files.writeString(run.directory().resolve("home/cache.txt"), "cache");
			Path evidence = run.directory().resolve("project/source.txt");
			Files.writeString(evidence, "source");
			manager.markCompleted(run);

			CodexRunnerCli.executeMaintenance(new String[]{"--prune-terminal-runtimes", "--run-root", root.toString()});

			requireThat(Files.notExists(run.directory().resolve("home")), "runtimePruned").isTrue();
			requireThat(Files.readString(evidence), "evidence").isEqualTo("source");
		}
	}
}
