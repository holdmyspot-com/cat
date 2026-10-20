package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Tests maintenance through the Claude runner's launcher entry point.
 */
public final class MaintenanceTest
{
	private static final Path TEMP = Path.of(System.getProperty("cat.session.temp")).toAbsolutePath().normalize();

	/**
	 * Creates the test.
	 */
	public MaintenanceTest()
	{
	}

	/**
	 * Verifies the launcher recognizes pruning and removes only terminal runtime state.
	 *
	 * @throws IOException if fixture or maintenance I/O fails
	 */
	@Test
	public void mainPrunesTerminalRuntime() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP),
				"maintenance-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("claude");
			manager.markCompleted(run);

			ClaudeRunnerCli.main(new String[]{"--prune-terminal-runtimes", "--run-root", root.toString()});

			requireThat(Files.notExists(run.directory().resolve("home")), "runtimePruned").isTrue();
			requireThat(run.directory().resolve("project"), "evidence").isDirectory();
		}
	}
}
