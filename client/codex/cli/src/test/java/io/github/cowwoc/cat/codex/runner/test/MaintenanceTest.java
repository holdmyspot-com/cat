package io.github.cowwoc.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexRunnerCli;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
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
		try (ToolScope scope = new ToolScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(scope.temporaryWorkRoot()),
				"maintenance-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
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
		try (ToolScope scope = new ToolScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = Files.createDirectories(scope.temporaryWorkRoot());
			Path root = cleanup.add(Files.createTempDirectory(temporaryRoot, "maintenance-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
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
